
package io.github.shymoy.termagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shymoy.termagent.config.ProviderConfig;
import io.github.shymoy.termagent.conversation.ConversationManager;
import io.github.shymoy.termagent.conversation.ThinkingBlock;
import io.github.shymoy.termagent.conversation.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ProviderConfig cfg(String model) {
        var cfg = new ProviderConfig();
        cfg.setName("deepseek");
        cfg.setProtocol("deepseek");
        cfg.setBaseUrl("https://api.deepseek.com");
        cfg.setApiKey("test-key");
        cfg.setModel(model);
        return cfg;
    }

    @Test
    void defaultsToV4ProWithThinkingDisabled() throws Exception {
        var client = new DeepSeekClient(cfg(null), "system");
        var conv = new ConversationManager();
        conv.addUserMessage("hello");
        conv.addAssistantFull("hi", List.of(new ThinkingBlock("hidden reasoning", "")), List.of());

        var root = MAPPER.readTree(client.buildRequestBody(conv.getMessages(), null));

        assertEquals("deepseek-v4-pro", root.path("model").asText());
        assertEquals("disabled", root.path("thinking").path("type").asText());
        assertFalse(root.has("reasoning_effort"));
        assertFalse(root.path("messages").get(2).has("reasoning_content"));
    }

    @Test
    void mapsLegacyAliasesToV4Flash() {
        assertEquals("deepseek-v4-flash", DeepSeekClient.resolveModel("deepseek-chat"));
        assertEquals("deepseek-v4-flash", DeepSeekClient.resolveModel("deepseek-reasoner"));
        assertEquals("deepseek-v4-pro", DeepSeekClient.resolveModel("deepseek-v4-pro"));
    }

    @Test
    void llmFactoryCreatesDeepSeekClient() {
        assertInstanceOf(DeepSeekClient.class, LlmClient.create(cfg("deepseek-v4-flash"), "system"));
    }

    @Test
    void legacyReasonerEnablesThinking() throws Exception {
        var client = new DeepSeekClient(cfg("deepseek-reasoner"), "system");
        var conv = new ConversationManager();
        conv.addUserMessage("think");

        var root = MAPPER.readTree(client.buildRequestBody(conv.getMessages(), null));

        assertEquals("deepseek-v4-flash", root.path("model").asText());
        assertEquals("enabled", root.path("thinking").path("type").asText());
        assertEquals("high", root.path("reasoning_effort").asText());
    }

    @Test
    void explicitThinkingSupportsMaxReasoningEffort() throws Exception {
        var cfg = cfg("deepseek-v4-pro");
        cfg.setThinking(true);
        cfg.setReasoningEffort("max");
        var client = new DeepSeekClient(cfg, "system");
        var conv = new ConversationManager();
        conv.addUserMessage("think harder");
        conv.addAssistantFull("answer", List.of(new ThinkingBlock("kept reasoning", "")), List.of());

        var root = MAPPER.readTree(client.buildRequestBody(conv.getMessages(), null));

        assertEquals("deepseek-v4-pro", root.path("model").asText());
        assertEquals("enabled", root.path("thinking").path("type").asText());
        assertEquals("max", root.path("reasoning_effort").asText());
        assertEquals("kept reasoning", root.path("messages").get(2).path("reasoning_content").asText());
    }

    @Test
    void xhighReasoningEffortMapsToMax() throws Exception {
        var cfg = cfg("deepseek-v4-pro");
        cfg.setThinking(true);
        cfg.setReasoningEffort("xhigh");
        var client = new DeepSeekClient(cfg, "system");
        var conv = new ConversationManager();
        conv.addUserMessage("think harder");

        var root = MAPPER.readTree(client.buildRequestBody(conv.getMessages(), null));

        assertEquals("max", root.path("reasoning_effort").asText());
    }

    @Test
    void thinkingToolCallKeepsReasoningAndNonNullAssistantContent() throws Exception {
        var cfg = cfg("deepseek-v4-pro");
        cfg.setThinking(true);
        var client = new DeepSeekClient(cfg, "system");
        var conv = new ConversationManager();
        conv.addUserMessage("use a tool");
        conv.addAssistantFull("", List.of(new ThinkingBlock("tool reasoning", "")),
                List.of(new ToolUseBlock("call_1", "lookup", Map.of("key", "value"))));

        var root = MAPPER.readTree(client.buildRequestBody(conv.getMessages(), null));
        var assistant = root.path("messages").get(2);

        assertEquals("", assistant.path("content").asText());
        assertFalse(assistant.path("content").isNull());
        assertEquals("tool reasoning", assistant.path("reasoning_content").asText());
        assertEquals("call_1", assistant.path("tool_calls").get(0).path("id").asText());
    }

    @Test
    void openAiCompatRequestsDoNotAddDeepSeekThinking() throws Exception {
        var cfg = cfg("gpt-4o");
        cfg.setProtocol("openai-compat");
        var client = new OpenAiCompatClient(cfg, "system");
        var conv = new ConversationManager();
        conv.addUserMessage("hello");

        var root = MAPPER.readTree(client.buildRequestBody(conv.getMessages(), null));

        assertFalse(root.has("thinking"));
        assertFalse(root.has("reasoning_effort"));
    }
}
