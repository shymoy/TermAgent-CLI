

package io.github.shymoy.termagent.llm;

import io.github.shymoy.termagent.config.ProviderConfig;
import io.github.shymoy.termagent.conversation.ConversationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

public interface LlmClient {

    BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools);

    default void setMaxOutputTokens(int tokens) {}

    static LlmClient create(ProviderConfig cfg, String systemPrompt) {
        return switch (cfg.getProtocol()) {
            case "anthropic" -> new AnthropicClient(cfg, systemPrompt);
            case "openai" -> new OpenAiClient(cfg, systemPrompt);
            case "openai-compat" -> new OpenAiCompatClient(cfg, systemPrompt);
            case "deepseek" -> new DeepSeekClient(cfg, systemPrompt);

            default -> throw new IllegalArgumentException("Unknown protocol: " + cfg.getProtocol());
        };
    }
}
