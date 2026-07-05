
package com.mewcode.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AnthropicClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final com.anthropic.client.AnthropicClient sdkClient;
    private final String model;

    private final boolean thinking;
    private final String systemPrompt;
    private volatile int maxOutputTokens;

    public AnthropicClient(ProviderConfig cfg, String systemPrompt) {
        String apiKey = cfg.resolvedApiKey();
        if (apiKey.isEmpty()) {
            throw new LlmException.AuthenticationException(
                    "Anthropic API key not found. Set it in .mewcode/config.yaml or via ANTHROPIC_API_KEY env var.");
        }
        this.sdkClient = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(cfg.getBaseUrl())
                .build();
        this.model = ModelResolver.resolve(cfg.getModel());
        this.thinking = cfg.isThinking();
        this.systemPrompt = systemPrompt;
        this.maxOutputTokens = cfg.resolvedMaxOutputTokens();

        // 上下文窗口解析的第二层：尝试从供应商的模型端点获取数值，
        // 并回填到 cfg 供 resolvedContextWindow() 使用。该过程不得阻塞启动，
        // 任何失败都静默回退到内置模型表。
        cfg.setFetchedContextWindow(fetchModelContextWindow());
    }

    /**
     * 通过 {@code GET {base_url}/v1/models/{model}} 获取 Anthropic 协议模型的
     * 上下文窗口，读取 {@code ModelInfo.max_input_tokens}。
     *
     * <p>这是尽力而为的查询：网络、认证、未知模型、字段缺失或超时
     * 都返回 {@code 0}，不向调用方抛出异常。调用方会把 0 视为不可用，
     * 继续使用下一层解析策略。
     *
     * @return 成功时返回大于 0 的最大输入 token 数，失败时返回 {@code 0}
     */
    int fetchModelContextWindow() {
        try {
            var info = sdkClient.models().retrieve(
                    model,
                    com.anthropic.core.RequestOptions.builder()
                            .timeout(java.time.Duration.ofSeconds(5))
                            .build());
            return info.maxInputTokens()
                    .map(Long::intValue)
                    .filter(v -> v > 0)
                    .orElse(0);
        } catch (Exception | Error e) {
            // 该查询只是增强信息，任何失败都不应影响应用启动。
            return 0;
        }
    }

    @Override
    public void setMaxOutputTokens(int tokens) {
        this.maxOutputTokens = tokens;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        var queue = new LinkedBlockingQueue<StreamEvent>(64);

        Thread.startVirtualThread(() -> {
            try {
                doStream(conv, tools, queue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                try { queue.put(new StreamEvent.Error(classifyError(e).getMessage())); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        });

        return queue;
    }

    /**
     * 执行一次 Anthropic 流式请求，并把 Anthropic SDK 的原始流事件翻译成项目统一的 StreamEvent。
     * 这里不直接修改会话历史；Agent 会消费 StreamEvent，并在一轮结束后统一写回 ConversationManager。
     */
    private void doStream(ConversationManager conv, List<Map<String, Object>> tools,
                          BlockingQueue<StreamEvent> queue) throws Exception {

        // prompt cache 锚在最长且稳定的前缀上：system、tools、最后一条 user 消息尾部。
        // tool_result 的内容稳定性由 com.mewcode.toolresult 中的替换状态负责维护。
        var systemBlock = TextBlockParam.builder()
                .text(systemPrompt)
                .cacheControl(CacheControlEphemeral.builder().build())
                .build();

        // 把项目内部 Message 列表转换成 Anthropic Messages API 需要的消息结构。
        var messageParams = buildMessages(conv.getMessages());

        // 给最后一条 user 消息的尾部打 cache_control，让 Anthropic 缓存此前稳定内容。
        markLastUserTailForCache(messageParams);

        // 组装本次请求的基础参数：模型、输出上限、system prompt 和历史消息。
        var paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxOutputTokens)
                .system(MessageCreateParams.System.ofTextBlockParams(List.of(systemBlock)))
                .messages(messageParams);

        // thinking 开关由 provider 配置控制；不同模型支持的 thinking 配置形式不同。
        if (thinking) {
            if (ModelResolver.supportsAdaptiveThinking(model)) {
                paramsBuilder.thinking(ThinkingConfigAdaptive.builder().build());
            } else {
                paramsBuilder.thinking(ThinkingConfigEnabled.builder()
                        .budgetTokens(maxOutputTokens - 1)
                        .build());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            // 工具 schema 通常跨轮稳定，只给最后一个 tool 打 cache_control，
            // 就能让 Anthropic 缓存整个 tools 区块。
            for (int i = 0; i < tools.size(); i++) {
                boolean isLast = (i == tools.size() - 1);
                paramsBuilder.addTool(buildTool(tools.get(i), isLast));
            }
        }

        // 下面这些是本次流式响应的临时状态。Anthropic 工具参数会分片返回，
        // 所以必须先拼接 partial_json，等 content_block_stop 时再解析成完整 JSON。
        String currentToolName = "";
        String currentToolId = "";
        var jsonAccum = new StringBuilder();
        var thinkingAccum = new StringBuilder();
        String thinkingSignature = "";
        boolean inThinking = false;
        int inputTokens = 0, outputTokens = 0;
        int cacheReadTokens = 0, cacheCreationTokens = 0;
        String stopReason = "end_turn";

        try (StreamResponse<RawMessageStreamEvent> streamResponse =
                     sdkClient.messages().createStreaming(paramsBuilder.build())) {

            var iterator = streamResponse.stream().iterator();
            while (iterator.hasNext()) {
                var event = iterator.next();
                if (event.isContentBlockStart()) {
                    // 新 content block 开始：这里只需要记录当前 block 类型和工具元信息。
                    var startEvent = event.asContentBlockStart();
                    var block = startEvent.contentBlock();
                    if (block.isThinking()) {
                        inThinking = true;
                        thinkingAccum.setLength(0);
                        thinkingSignature = "";
                    } else if (block.isToolUse()) {
                        var tu = block.asToolUse();
                        currentToolName = tu.name();
                        currentToolId = tu.id();
                        jsonAccum.setLength(0);
                        queue.put(new StreamEvent.ToolCallStart(currentToolId, currentToolName));
                    }
                } else if (event.isContentBlockDelta()) {
                    // block 增量：文本和 thinking 直接转发；工具参数继续累积 JSON 片段。
                    var delta = event.asContentBlockDelta().delta();
                    if (delta.isThinking()) {
                        String text = delta.asThinking().thinking();
                        thinkingAccum.append(text);
                        queue.put(new StreamEvent.ThinkingDelta(text));
                    } else if (delta.isSignature()) {
                        thinkingSignature = delta.asSignature().signature();
                    } else if (delta.isText()) {
                        queue.put(new StreamEvent.TextDelta(delta.asText().text()));
                    } else if (delta.isInputJson()) {
                        String partialJson = delta.asInputJson().partialJson();
                        jsonAccum.append(partialJson);
                        queue.put(new StreamEvent.ToolCallDelta(partialJson));
                    }
                } else if (event.isContentBlockStop()) {
                    // 当前 block 结束：thinking 要带上签名完成；tool_use 要在这里解析完整参数。
                    if (inThinking) {
                        queue.put(new StreamEvent.ThinkingComplete(
                                thinkingAccum.toString(), thinkingSignature));
                        inThinking = false;
                    }
                    if (!currentToolName.isEmpty()) {
                        Map<String, Object> args;
                        try {
                            @SuppressWarnings("unchecked")
                            var parsed = MAPPER.readValue(jsonAccum.toString(), Map.class);
                            args = parsed;
                        } catch (Exception e) {
                            args = new HashMap<>();
                        }
                        queue.put(new StreamEvent.ToolCallComplete(
                                currentToolId, currentToolName, args));
                        currentToolName = "";
                        currentToolId = "";
                        jsonAccum.setLength(0);
                    }
                } else if (event.isMessageDelta()) {
                    // message_delta 携带 stopReason 和输出 token；部分兼容供应商也会在这里回传输入 token。
                    var msgDelta = event.asMessageDelta();
                    var sr = msgDelta.delta().stopReason();
                    if (sr.isPresent()) {
                        stopReason = sr.get().asString();
                    }
                    var usage = msgDelta.usage();
                    outputTokens = (int) usage.outputTokens();

                    // 标准 Anthropic 通常只在 message_delta 放 output_tokens。
                    // 一些兼容供应商会把真实 input/cache token 也放在这里；
                    // 只有值大于 0 时才覆盖，避免把 message_start 的有效数据清掉。
                    int deltaInput = deltaUsageLong(usage.inputTokens(), usage, "input_tokens");
                    int deltaCacheRead = deltaUsageLong(usage.cacheReadInputTokens(), usage, "cache_read_input_tokens");
                    int deltaCacheCreate = deltaUsageLong(usage.cacheCreationInputTokens(), usage, "cache_creation_input_tokens");
                    if (deltaInput > 0) {
                        inputTokens = deltaInput;
                    }
                    if (deltaCacheRead > 0) {
                        cacheReadTokens = deltaCacheRead;
                    }
                    if (deltaCacheCreate > 0) {
                        cacheCreationTokens = deltaCacheCreate;
                    }
                } else if (event.isMessageStart()) {
                    // message_start 主要提供本次请求的输入 token 和 prompt cache 统计。
                    var msg = event.asMessageStart().message();
                    var usage = msg.usage();
                    inputTokens = (int) usage.inputTokens();
                    if (usage.cacheReadInputTokens().isPresent()) {
                        cacheReadTokens = usage.cacheReadInputTokens().get().intValue();
                    }
                    if (usage.cacheCreationInputTokens().isPresent()) {
                        cacheCreationTokens = usage.cacheCreationInputTokens().get().intValue();
                    }
                }
            }
        }

        // Anthropic 流结束后，向 Agent 发一个统一的结束事件，携带停止原因和 usage。
        queue.put(new StreamEvent.StreamEnd(
                stopReason != null ? stopReason : "end_turn", inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens));
    }

    /**
     * 将项目内部的轻量消息转换为 Anthropic Messages API 参数。
     * 内部消息只区分 "user" 和 "assistant" 两种角色，thinking、
     * 工具调用和工具结果通过额外字段保存，并在这里展开成 Anthropic content block。
     */
    private List<MessageParam> buildMessages(List<Message> messages) {
        var result = new ArrayList<MessageParam>();
        for (var msg : messages) {
            boolean hasThinking = msg.getThinkingBlocks() != null && !msg.getThinkingBlocks().isEmpty();
            boolean hasToolUses = msg.getToolUses() != null && !msg.getToolUses().isEmpty();

            if ("assistant".equals(msg.getRole()) && (hasThinking || hasToolUses)) {
                // 带 thinking 或工具调用的 assistant 消息必须用 block 数组发送，
                // 这样 Anthropic 才能识别每个内容块的具体类型。
                var content = new ArrayList<ContentBlockParam>();
                if (hasThinking) {
                    for (var tb : msg.getThinkingBlocks()) {
                        content.add(ContentBlockParam.ofThinking(
                                ThinkingBlockParam.builder()
                                        .thinking(tb.thinking())
                                        .signature(tb.signature())
                                        .build()));
                    }
                }
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    content.add(ContentBlockParam.ofText(
                            TextBlockParam.builder().text(msg.getContent()).build()));
                }
                if (hasToolUses) {
                    for (var tu : msg.getToolUses()) {
                        content.add(ContentBlockParam.ofToolUse(
                                ToolUseBlockParam.builder()
                                        .id(tu.toolUseId())
                                        .name(tu.toolName())
                                        .input(JsonValue.from(tu.arguments()))
                                        .build()));
                    }
                }
                if (content.isEmpty()) {
                    content.add(ContentBlockParam.ofText(
                            TextBlockParam.builder().text("").build()));
                }
                result.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(content)
                        .build());
            } else if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                // Anthropic 没有顶层 "tool" 角色。工具结果要作为 user 消息中的
                // tool_result block 发送，并通过 tool_use_id 关联前面的 tool_use。
                var content = new ArrayList<ContentBlockParam>();
                for (var tr : msg.getToolResults()) {
                    content.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(tr.toolUseId())
                                    .content(tr.content())
                                    .isError(tr.isError())
                                    .build()));
                }
                result.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(content)
                        .build());
            } else {
                // 普通文本消息可以直接使用 SDK 的字符串 content helper。
                // 这里把非 assistant 的内部角色都按 user 发送。
                if (!result.isEmpty()) {
                    var prev = result.getLast();
                    if (prev.role().asString().equals(msg.getRole())) {
                        var merged = prev.toBuilder();
                        // SDK 不方便在这里直接合并不同形式的 content。
                        // 简单文本消息会在后面的 mergeConsecutiveSameRole 中再合并。
                    }
                }
                var builder = MessageParam.builder()
                        .content(msg.getContent());
                if ("assistant".equals(msg.getRole())) {
                    builder.role(MessageParam.Role.ASSISTANT);
                } else {
                    builder.role(MessageParam.Role.USER);
                }
                result.add(builder.build());
            }
        }
        // 规范化只发生在 API 请求转换阶段，不修改内部保存的会话历史。
        return mergeConsecutiveSameRole(result);
    }

    private List<MessageParam> mergeConsecutiveSameRole(List<MessageParam> messages) {
        if (messages.size() <= 1) return messages;
        var merged = new ArrayList<MessageParam>();
        merged.add(messages.getFirst());
        for (int i = 1; i < messages.size(); i++) {
            var prev = merged.getLast();
            var curr = messages.get(i);
            if (prev.role().equals(curr.role())) {
                // 相邻的同角色纯文本消息可以直接合并。
                var prevContent = prev.content();
                var currContent = curr.content();
                if (prevContent.isString() && currContent.isString()) {
                    merged.set(merged.size() - 1, MessageParam.builder()
                            .role(prev.role())
                            .content(prevContent.asString() + "\n\n" + currContent.asString())
                            .build());
                } else {
                    // 任一消息使用内容块时保持原样，交由 API 处理。
                    merged.add(curr);
                }
            } else {
                merged.add(curr);
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Tool buildTool(Map<String, Object> schema, boolean markCache) {
        var builder = Tool.builder()
                .name((String) schema.get("name"));
        if (schema.containsKey("description")) {
            builder.description((String) schema.get("description"));
        }
        var inputSchema = (Map<String, Object>) schema.getOrDefault("input_schema",
                Map.of("type", "object", "properties", Map.of()));
        builder.inputSchema(Tool.InputSchema.builder()
                .type(JsonValue.from(inputSchema.getOrDefault("type", "object")))
                .putAllAdditionalProperties(toJsonValueMap(inputSchema))
                .build());
        if (markCache) {
            builder.cacheControl(CacheControlEphemeral.builder().build());
        }
        return builder.build();
    }

    /**
     * 在 {@code messages} 中最后一条 user 消息的末尾内容块上
     * 附加临时 {@code cache_control} 标记。Anthropic 会缓存截至该内容块的前缀，
     * 后续请求在前缀字节完全一致时命中缓存。
     *
     * <p>该方法会就地替换 {@code messages} 中的末尾 {@code MessageParam}。
     * SDK 构建的对象不可变，因此需要重建整条消息，
     * 而不能直接修改内容块字段。
     */
    private void markLastUserTailForCache(List<MessageParam> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg.role() != MessageParam.Role.USER) continue;
            // user 消息可能是字符串或内容块列表。
            // cache_control 只能附着在内容块上，因此需要先将字符串提升为块。
            var content = msg.content();
            List<ContentBlockParam> blocks;
            if (content.string().isPresent()) {
                blocks = List.of(ContentBlockParam.ofText(
                        TextBlockParam.builder()
                                .text(content.string().get())
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()));
            } else if (content.blockParams().isPresent()) {
                var orig = content.blockParams().get();
                if (orig.isEmpty()) return;
                blocks = new ArrayList<>(orig);
                var last = blocks.getLast();
                ContentBlockParam rebuilt;
                if (last.text().isPresent()) {
                    var t = last.text().get();
                    rebuilt = ContentBlockParam.ofText(t.toBuilder()
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build());
                } else if (last.toolResult().isPresent()) {
                    var tr = last.toolResult().get();
                    rebuilt = ContentBlockParam.ofToolResult(tr.toBuilder()
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build());
                } else {
                    return; // unsupported block type at tail — silently skip
                }
                blocks.set(blocks.size() - 1, rebuilt);
            } else {
                return;
            }
            messages.set(i, MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(blocks)
                    .build());
            return;
        }
    }

    private Map<String, JsonValue> toJsonValueMap(Map<String, Object> map) {
        var result = new LinkedHashMap<String, JsonValue>();
        for (var entry : map.entrySet()) {
            if ("type".equals(entry.getKey())) continue;
            result.put(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return result;
    }

    private LlmException classifyError(Exception e) {
        if (e instanceof LlmException le) return le;
        if (e instanceof com.anthropic.errors.UnauthorizedException ue) {
            return new LlmException.AuthenticationException("Invalid API key: " + ue.getMessage());
        }
        if (e instanceof com.anthropic.errors.RateLimitException) {
            return new LlmException.RateLimitException("Rate limited. Please wait.", "");
        }
        if (e instanceof com.anthropic.errors.BadRequestException bre) {
            String msg = bre.getMessage() != null ? bre.getMessage().toLowerCase() : "";
            if (msg.contains("prompt is too long") || msg.contains("too many tokens")) {
                return new LlmException.ContextTooLongException("Context too long: " + bre.getMessage());
            }
            return new LlmException("Bad request: " + bre.getMessage(), bre);
        }
        if (e instanceof com.anthropic.errors.AnthropicServiceException se) {
            if (se.statusCode() == 413) {
                return new LlmException.ContextTooLongException("Context too long: " + se.getMessage());
            }
            return new LlmException("API error (" + se.statusCode() + "): " + se.getMessage(), se);
        }
        if (e instanceof com.anthropic.errors.AnthropicIoException) {
            return new LlmException.NetworkException("Network error: " + e.getMessage(), e);
        }
        return new LlmException("Unexpected error: " + e.getMessage(), e);
    }

    /**
     * 从 {@link MessageDeltaUsage} 中提取指定的 token 用量。
     *
     * <p>首先使用 SDK 的强类型 Optional 访问器。如果当前 SDK 没有映射该字段，
     * 则回退到 {@code _additionalProperties()}，以支持 MiniMax 等 Anthropic 兼容供应商
     * 返回的非标准字段。
     *
     * @param typed SDK 强类型访问器返回的 Optional，例如 {@code usage.inputTokens()}
     * @param usage 流式增量的用量对象，用于 additionalProperties 回退
     * @param jsonKey additionalProperties 中待查找的原始 JSON 键
     * @return token 数；字段不存在或无法解析时返回 0
     */
    private static int deltaUsageLong(Optional<Long> typed,
                                      MessageDeltaUsage usage,
                                      String jsonKey) {
        if (typed.isPresent()) {
            return typed.get().intValue();
        }
        // 一些兼容供应商会把额外字段放入 additionalProperties。
        var extra = usage._additionalProperties();
        if (extra != null && extra.containsKey(jsonKey)) {
            var val = extra.get(jsonKey);
            if (val != null) {
                Optional<Number> num = val.asNumber();
                if (num.isPresent()) {
                    return num.get().intValue();
                }
            }
        }
        return 0;
    }
}
