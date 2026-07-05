
package com.mewcode.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**

 * LLM 客户端面向 OpenAI 聊天完成 API ({@code /chat/completions})。

 * <p>

 * 这是 "compat" 变体 - 它表示广泛采用的聊天完成

 * 有线格式而不是较新的响应 API，使其与任何

 * 公开 {@code /chat/completions} 端点的提供程序（vLLM、Ollama、

 * 一起，Groq 等）。

 */
public class OpenAiCompatClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    protected final String model;
    private final String systemPrompt;
    private volatile int maxOutputTokens;

    public OpenAiCompatClient(ProviderConfig cfg, String systemPrompt) {
        String key = cfg.resolvedApiKey();
        if (key.isEmpty()) {
            throw new LlmException.AuthenticationException(
                    "API key not found for " + cfg.getProtocol() + " provider '" + cfg.getName()
                            + "'. Set it in .mewcode/config.yaml or via the provider API key env var.");
        }
        this.apiKey = key;
        this.baseUrl = cfg.getBaseUrl().replaceAll("/+$", "");
        this.model = cfg.getModel();
        this.systemPrompt = systemPrompt;
        this.maxOutputTokens = cfg.resolvedMaxOutputTokens();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public void setMaxOutputTokens(int tokens) {
        this.maxOutputTokens = tokens;
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        var queue = new LinkedBlockingQueue<StreamEvent>(64);

        Thread.startVirtualThread(() -> {
            try {
                doStream(conv, tools, queue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                try {
                    queue.put(new StreamEvent.Error(classifyError(e).getMessage()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        return queue;
    }

    private void doStream(ConversationManager conv, List<Map<String, Object>> tools,
                           BlockingQueue<StreamEvent> queue) throws Exception {

        String body = buildRequestBody(conv.getMessages(), tools);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<java.io.InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

        int statusCode = response.statusCode();
        if (statusCode != 200) {
            String errBody;
            try (var is = response.body()) {
                errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IOException("HTTP " + statusCode + ": " + errBody);
        }

        // 工具调用增量的累积状态（按索引键控）
        var toolNames = new HashMap<Integer, StringBuilder>();
        var toolArgs = new HashMap<Integer, StringBuilder>();
        var toolIds = new HashMap<Integer, String>();
        // 累积 reasoning_content（DeepSeek/小米等 provider）
        var reasoningAccum = new StringBuilder();
        boolean streamEnded = false;

        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;       // blank SSE separator
                if (line.startsWith(":")) continue;  // SSE comment

                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();

                if ("[DONE]".equals(data)) {
                    // 刷新任何正在进行的工具调用，然后发出 StreamEnd（如果尚未完成）
                    flushPendingToolCalls(queue, toolNames, toolArgs, toolIds);
                    if (!streamEnded) {
                        queue.put(new StreamEvent.StreamEnd("end_turn", 0, 0));
                    }
                    break;
                }

                streamEnded = handleSseData(data, queue, toolNames, toolArgs, toolIds, reasoningAccum);
            }
        }
    }

    // ------------------------------------------------------------------

    // SSE 块处理

    // ------------------------------------------------------------------

    /**

     * 处理单个 SSE 数据有效负载。

     *

     * @return true 如果发出 StreamEnd 事件（i.e.finish_reason 可见）

     */
    private boolean handleSseData(String data, BlockingQueue<StreamEvent> queue,
                                   Map<Integer, StringBuilder> toolNames,
                                   Map<Integer, StringBuilder> toolArgs,
                                   Map<Integer, String> toolIds,
                                   StringBuilder reasoningAccum) throws InterruptedException {
        JsonNode root;
        try {
            root = MAPPER.readTree(data);
        } catch (JsonProcessingException e) {
            return false; // skip unparsable chunks
        }

        // ---- 错误对象 ----
        if (root.has("error")) {
            var errNode = root.get("error");
            String errMsg = errNode.has("message") ? errNode.get("message").asText() : errNode.toString();
            queue.put(new StreamEvent.Error(errMsg));
            return false;
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            // 可能是仅供使用的块 (stream_options.include_usage)
            return emitUsageIfPresent(root, queue);
        }

        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");

        // ----文字内容----
        if (delta.has("content") && !delta.get("content").isNull()) {
            String text = delta.get("content").asText();
            if (!text.isEmpty()) {
                queue.put(new StreamEvent.TextDelta(text));
            }
        }

        // ---- reasoning_content（DeepSeek/小米等 provider 的非标准字段）----
        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
            String rc = delta.get("reasoning_content").asText();
            if (!rc.isEmpty()) {
                reasoningAccum.append(rc);
                queue.put(new StreamEvent.ThinkingDelta(rc));
            }
        }

        // ---- 工具调用（增量）----
        if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
            for (JsonNode tc : delta.get("tool_calls")) {
                int idx = tc.path("index").asInt(0);

                // id 仅出现在给定工具调用的第一个块中
                if (tc.has("id") && !tc.get("id").isNull()) {
                    toolIds.put(idx, tc.get("id").asText());
                }

                JsonNode fn = tc.path("function");
                if (fn.has("name") && !fn.get("name").isNull()) {
                    toolNames.computeIfAbsent(idx, k -> new StringBuilder()).append(fn.get("name").asText());
                    // 一旦我们知道名字就发出开始
                    String name = toolNames.get(idx).toString();
                    String callId = toolIds.getOrDefault(idx, "call_" + idx);
                    queue.put(new StreamEvent.ToolCallStart(callId, name));
                }
                if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                    String argChunk = fn.get("arguments").asText();
                    toolArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(argChunk);
                    queue.put(new StreamEvent.ToolCallDelta(argChunk));
                }
            }
        }

        // ---- 完成_原因 ----
        String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                ? choice.get("finish_reason").asText() : null;

        if ("tool_calls".equals(finishReason) || "stop".equals(finishReason) || "length".equals(finishReason)) {
            if (reasoningAccum.length() > 0) {
                queue.put(new StreamEvent.ThinkingComplete(reasoningAccum.toString(), ""));
                reasoningAccum.setLength(0);
            }
        }

        if ("tool_calls".equals(finishReason)) {
            flushPendingToolCalls(queue, toolNames, toolArgs, toolIds);
            return false;
        } else if ("stop".equals(finishReason) || "length".equals(finishReason)) {
            String stopReason = "length".equals(finishReason) ? "max_tokens" : "end_turn";
            int[] usage = extractUsage(root);
            queue.put(new StreamEvent.StreamEnd(stopReason, usage[0], usage[1], usage[2], 0));
            return true;
        }

        return false;
    }

    // ------------------------------------------------------------------

    // 工具调用刷新

    // ------------------------------------------------------------------

    private void flushPendingToolCalls(BlockingQueue<StreamEvent> queue,
                                       Map<Integer, StringBuilder> toolNames,
                                       Map<Integer, StringBuilder> toolArgs,
                                       Map<Integer, String> toolIds) throws InterruptedException {
        if (toolNames.isEmpty()) return;

        var sorted = new ArrayList<>(toolNames.keySet());
        Collections.sort(sorted);

        for (int idx : sorted) {
            String name = toolNames.get(idx).toString();
            String callId = toolIds.getOrDefault(idx, "call_" + idx);
            String rawArgs = toolArgs.containsKey(idx) ? toolArgs.get(idx).toString() : "{}";

            Map<String, Object> args;
            try {
                @SuppressWarnings("unchecked")
                var parsed = MAPPER.readValue(rawArgs, Map.class);
                args = parsed;
            } catch (Exception e) {
                args = Map.of();
            }
            queue.put(new StreamEvent.ToolCallComplete(callId, name, args));
        }

        toolNames.clear();
        toolArgs.clear();
        toolIds.clear();
    }

    // ------------------------------------------------------------------

    // 用法提取

    // ------------------------------------------------------------------

    private boolean emitUsageIfPresent(JsonNode root, BlockingQueue<StreamEvent> queue) throws InterruptedException {
        int[] usage = extractUsage(root);
        if (usage[0] > 0 || usage[1] > 0) {
            queue.put(new StreamEvent.StreamEnd("end_turn", usage[0], usage[1], usage[2], 0));
            return true;
        }
        return false;
    }

    /**

     * 返回{输入，输出，cacheRead}。 OpenAI 兼容提供商没有

     * 缓存创建概念； cacheRead 读取自

     * prompt_tokens_details.cached_tokens（如果存在），否则为 0。

     */
    static int[] extractUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode()) return new int[]{0, 0, 0};
        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int output = usage.path("completion_tokens").asInt(0);
        int cacheRead = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        // OpenAI的prompt_tokens已经包含了缓存的部分；把它分开
        // 所以锚点的（输入+ cacheRead + cacheCreation +输出）不会
        // 双重计算缓存命中。
        int input = Math.max(0, promptTokens - cacheRead);
        return new int[]{input, output, cacheRead};
    }

    // ------------------------------------------------------------------

    // 要求健身

    // ------------------------------------------------------------------

    String buildRequestBody(List<Message> messages, List<Map<String, Object>> tools)
            throws JsonProcessingException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("stream", true);
        root.put("max_tokens", maxOutputTokens);

        // Stream_options：询问流中的使用情况
        ObjectNode streamOpts = MAPPER.createObjectNode();
        streamOpts.put("include_usage", true);
        root.set("stream_options", streamOpts);

        // messages
        root.set("messages", buildChatMessages(messages));

        // tools
        if (tools != null && !tools.isEmpty()) {
            root.set("tools", buildToolsArray(tools));
        }

        customizeRequestBody(root);

        return MAPPER.writeValueAsString(root);
    }

    protected void customizeRequestBody(ObjectNode root) {
    }

    protected boolean includeReasoningContent() {
        return true;
    }

    protected boolean requireAssistantContentForToolCalls() {
        return false;
    }

    @SuppressWarnings("unchecked")
    private ArrayNode buildChatMessages(List<Message> messages) {
        ArrayNode arr = MAPPER.createArrayNode();

        // 系统提示
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode sys = MAPPER.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            arr.add(sys);
        }

        for (var msg : messages) {
            boolean hasToolUses = msg.getToolUses() != null && !msg.getToolUses().isEmpty();
            boolean hasToolResults = msg.getToolResults() != null && !msg.getToolResults().isEmpty();

            // 拼接 thinking blocks 为 reasoning_content（DeepSeek/小米等 provider 要求）
            String reasoning = "";
            if (msg.getThinkingBlocks() != null) {
                var sb = new StringBuilder();
                for (var tb : msg.getThinkingBlocks()) {
                    sb.append(tb.thinking());
                }
                reasoning = sb.toString();
            }

            if ("assistant".equals(msg.getRole()) && hasToolUses) {
                ObjectNode node = MAPPER.createObjectNode();
                node.put("role", "assistant");
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    node.put("content", msg.getContent());
                } else if (requireAssistantContentForToolCalls()) {
                    node.put("content", "");
                } else {
                    node.putNull("content");
                }
                if (!reasoning.isEmpty() && includeReasoningContent()) {
                    node.put("reasoning_content", reasoning);
                }

                ArrayNode toolCallsArr = MAPPER.createArrayNode();
                for (var tu : msg.getToolUses()) {
                    ObjectNode tc = MAPPER.createObjectNode();
                    tc.put("id", tu.toolUseId());
                    tc.put("type", "function");
                    ObjectNode fn = MAPPER.createObjectNode();
                    fn.put("name", tu.toolName());
                    try {
                        fn.put("arguments", MAPPER.writeValueAsString(tu.arguments()));
                    } catch (JsonProcessingException e) {
                        fn.put("arguments", "{}");
                    }
                    tc.set("function", fn);
                    toolCallsArr.add(tc);
                }
                node.set("tool_calls", toolCallsArr);
                arr.add(node);

            } else if (hasToolResults) {
                for (var tr : msg.getToolResults()) {
                    ObjectNode node = MAPPER.createObjectNode();
                    node.put("role", "tool");
                    node.put("tool_call_id", tr.toolUseId());
                    node.put("content", tr.content());
                    arr.add(node);
                }
            } else {
                ObjectNode node = MAPPER.createObjectNode();
                node.put("role", msg.getRole());
                node.put("content", msg.getContent() != null ? msg.getContent() : "");
                if (!reasoning.isEmpty() && includeReasoningContent()) {
                    node.put("reasoning_content", reasoning);
                }
                arr.add(node);
            }
        }

        return arr;
    }

    @SuppressWarnings("unchecked")
    private ArrayNode buildToolsArray(List<Map<String, Object>> tools) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (var schema : tools) {
            ObjectNode tool = MAPPER.createObjectNode();
            tool.put("type", "function");

            ObjectNode fn = MAPPER.createObjectNode();
            fn.put("name", (String) schema.get("name"));
            if (schema.containsKey("description")) {
                fn.put("description", Objects.toString(schema.get("description"), ""));
            }

            var params = (Map<String, Object>) schema.getOrDefault("parameters",
                    schema.getOrDefault("input_schema", Map.of()));
            fn.set("parameters", MAPPER.valueToTree(params));

            tool.set("function", fn);
            arr.add(tool);
        }
        return arr;
    }

    // ------------------------------------------------------------------

    // 错误分类

    // ------------------------------------------------------------------

    private LlmException classifyError(Exception e) {
        if (e instanceof LlmException le) return le;
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String lower = msg.toLowerCase();

        if (msg.startsWith("HTTP 401") || lower.contains("unauthorized") || lower.contains("invalid api key")) {
            return new LlmException.AuthenticationException("Invalid API key: " + msg);
        }
        if (msg.startsWith("HTTP 429") || lower.contains("rate limit")) {
            return new LlmException.RateLimitException("Rate limited. Please wait.", "");
        }
        if (lower.contains("context_length_exceeded") || lower.contains("prompt is too long")
                || lower.contains("too many tokens") || msg.startsWith("HTTP 413")) {
            return new LlmException.ContextTooLongException("Context too long: " + msg);
        }
        if (e instanceof IOException) {
            return new LlmException.NetworkException("Network error: " + msg, e);
        }
        if (msg.startsWith("HTTP 4") || msg.startsWith("HTTP 5")) {
            return new LlmException("API error: " + msg, e);
        }
        return new LlmException("Unexpected error: " + msg, e);
    }
}
