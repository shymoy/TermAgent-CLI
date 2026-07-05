
package io.github.shymoy.termagent.config;

import java.util.Map;

public class ProviderConfig {

    public static final String DEEPSEEK_PROTOCOL = "deepseek";
    public static final String DEEPSEEK_DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEEPSEEK_DEFAULT_MODEL = "deepseek-v4-pro";

    private static final Map<String, String> ENV_KEY_MAP = Map.of(
            "anthropic", "ANTHROPIC_API_KEY",
            "openai", "OPENAI_API_KEY",
            "openai-compat", "OPENAI_API_KEY",
            DEEPSEEK_PROTOCOL, "DEEPSEEK_API_KEY"
    );

    private String name;
    private String protocol;
    private String baseUrl;
    private String model;
    private String apiKey;
    private boolean thinking;
    private String reasoningEffort;

    private int contextWindow;
    private int maxOutputTokens;

    /**

     * 第 2 层缓存：从提供商的上下文窗口自动获取

     * {@code /v1/models/{model}} 端点。 LLM客户端回填一次

     * 已构造（参见 {@code AnthropicClient}）； {@code null} 表示 "not yet

     * fetched / fetch failed"。留在这里所以{@link #resolvedContextWindow()}，

     * 它没有自己的客户端，可以读取结果。

     */
    private volatile Integer fetchedContextWindow;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProtocol() { return protocol; }

    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public boolean isThinking() { return thinking; }
    public void setThinking(boolean thinking) { this.thinking = thinking; }

    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }

    public int getContextWindow() { return contextWindow; }

    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }

    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    /**

     * 记录从供应商（第 2 层）自动获取的上下文窗口。

     * 传递一个> 0的值来缓存它；其他任何内容都会被忽略，因此失败

     * fetch 永远不会毒害缓存。在客户端构建时调用一次。

     */
    public void setFetchedContextWindow(int window) {
        if (window > 0) this.fetchedContextWindow = window;
    }

    /**

     * 解决有效上下文窗口有四层后备，

     * 最高优先级优先：

     *

     * <ol>

     * <li> 从配置中手写的 {@code context_window} (> 0) — 总是获胜。</li>

     * <li>Value 从供应商的模型端点自动获取并缓存

     * 通过 {@link #setFetchedContextWindow(int)}（仅 Anthropic 协议；

     * 获取本身是尽力而为的，并且在失败时默默地降级）。</li>

     * <li>内置模型名称→窗口表（子字符串匹配，{@link #windowForModel}）.</li>

     * <li>保守默认（Claude为200k，否则为128k）。</li>

     * </ol>

     */
    public int resolvedContextWindow() {
        // 第 1 层：显式配置覆盖。
        if (contextWindow > 0) return contextWindow;
        // 第 2 层：从供应商自动获取（在客户端创建时缓存）。
        Integer fetched = fetchedContextWindow;
        if (fetched != null && fetched > 0) return fetched;
        // Layers 3 + 4：内置表，然后保守默认。
        return windowForModel(model);
    }

    /**

     * 内置 "model name → context window" 查找（第 3 层和第 4 层）。

     * 按子字符串匹配，从最具体到最通用。值为

     * 仅合理的起点 - 随着供应商更新模型，它们可能会发生变化，

     * 因此，当值错误时，请在配置中设置 {@code context_window} 进行覆盖。

     *

     * @param model 型号ID（可能是{@code null}）

     * @return a  上下文窗口大小（以标记为单位）；从来没有 0

     */
    public static int windowForModel(String model) {
        String m = model == null ? "" : model.toLowerCase();
        // 最具体的先说。
        if (m.contains("1m") || m.contains("-1m")) return 1_000_000; // explicit 1M-context variants
        if (m.contains("gpt-4.1")) return 1_000_000;
        if (m.contains("gpt-4o")) return 128_000;
        if (m.contains("gpt-4-turbo")) return 128_000;
        if (m.contains("o1") || m.contains("o3") || m.contains("o4")) return 200_000; // OpenAI reasoning models
        if (m.contains("deepseek-v4")) return 1_000_000;
        if (m.contains("deepseek-chat") || m.contains("deepseek-reasoner")) return 1_000_000;
        if (m.contains("gpt-3.5")) return 16_385;
        if (m.contains("claude")) return 200_000;
        return 128_000; // conservative default
    }

    public int resolvedMaxOutputTokens() {
        if (maxOutputTokens > 0) return maxOutputTokens;
        return thinking ? 64_000 : 8192;
    }

    public String resolvedApiKey() {
        if (apiKey != null && !apiKey.isEmpty()) return apiKey;
        String envVar = ENV_KEY_MAP.get(protocol);
        if (envVar == null) return "";
        String val = System.getenv(envVar);
        return val != null ? val : "";
    }
}
