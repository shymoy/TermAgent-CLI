
package io.github.shymoy.termagent.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shymoy.termagent.config.ProviderConfig;

/**
 * 基于 OpenAI 兼容的 Chat Completions 传输层实现 DeepSeek V4 适配。
 */
public class DeepSeekClient extends OpenAiCompatClient {

    private static final String LEGACY_ALIAS_MODEL = "deepseek-v4-flash";

    private final boolean thinkingEnabled;
    private final String reasoningEffort;

    public DeepSeekClient(ProviderConfig cfg, String systemPrompt) {
        this(normalize(cfg), systemPrompt, isThinkingEnabled(cfg), normalizeReasoningEffort(cfg.getReasoningEffort()));
    }

    private DeepSeekClient(ProviderConfig cfg, String systemPrompt,
                           boolean thinkingEnabled, String reasoningEffort) {
        super(cfg, systemPrompt);
        this.thinkingEnabled = thinkingEnabled;
        this.reasoningEffort = reasoningEffort;
    }

    @Override
    protected void customizeRequestBody(ObjectNode root) {
        ObjectNode thinking = root.objectNode();
        thinking.put("type", thinkingEnabled ? "enabled" : "disabled");
        root.set("thinking", thinking);
        if (thinkingEnabled) {
            root.put("reasoning_effort", reasoningEffort);
        }
    }

    @Override
    protected boolean includeReasoningContent() {
        return thinkingEnabled;
    }

    @Override
    protected boolean requireAssistantContentForToolCalls() {
        return true;
    }

    private static ProviderConfig normalize(ProviderConfig cfg) {
        var out = new ProviderConfig();
        out.setName(cfg.getName());
        out.setProtocol(ProviderConfig.DEEPSEEK_PROTOCOL);
        out.setBaseUrl(isBlank(cfg.getBaseUrl())
                ? ProviderConfig.DEEPSEEK_DEFAULT_BASE_URL : cfg.getBaseUrl());
        out.setModel(resolveModel(cfg.getModel()));
        out.setApiKey(cfg.getApiKey());
        out.setThinking(isThinkingEnabled(cfg));
        out.setReasoningEffort(normalizeReasoningEffort(cfg.getReasoningEffort()));
        out.setContextWindow(cfg.getContextWindow());
        out.setMaxOutputTokens(cfg.getMaxOutputTokens());
        return out;
    }

    static String resolveModel(String model) {
        if (isBlank(model)) return ProviderConfig.DEEPSEEK_DEFAULT_MODEL;
        String m = model.trim();
        if ("deepseek-chat".equals(m) || "deepseek-reasoner".equals(m)) {
            return LEGACY_ALIAS_MODEL;
        }
        return m;
    }

    static boolean isThinkingEnabled(ProviderConfig cfg) {
        return (cfg.getModel() != null
                && "deepseek-reasoner".equals(cfg.getModel().trim()))
                || cfg.isThinking();
    }

    static String normalizeReasoningEffort(String reasoningEffort) {
        if (reasoningEffort == null) return "high";
        String value = reasoningEffort.trim().toLowerCase();
        return "max".equals(value) || "xhigh".equals(value) ? "max" : "high";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
