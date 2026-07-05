
package com.mewcode.llm;

import com.mewcode.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**

 * 验证第 2 层（自动获取）正常降级：指向

 * 无法到达端点，{@code fetchModelContextWindow()} 必须返回 0

 * 没有抛出，建筑一定不会爆炸，并且配置已解决

 * 窗口必须回退到内置表。

 *

 * <p>我们在测试中没有实时的人类端点（烟雾测试配置

 * 使用与 OpenAI 兼容的代理，该代理不会返回任何有用的信息），所以我们

 * 指向一个虚假的底座 URL — 这正是故障模式

 * 退化路径必须继续存在。

 */
class AnthropicClientContextWindowTest {

    private static ProviderConfig anthropicCfg(String baseUrl) {
        var cfg = new ProviderConfig();
        cfg.setProtocol("anthropic");
        cfg.setBaseUrl(baseUrl);
        cfg.setModel("claude-sonnet-4-6");
        cfg.setApiKey("sk-test-not-a-real-key"); // non-empty so the ctor proceeds
        return cfg;
    }

    @Test
    void constructionDoesNotThrowWhenFetchFails() {
        // 127.0.0.1:1是一个关闭的端口→连接很快被拒绝。
        var cfg = anthropicCfg("http://127.0.0.1:1");
        assertDoesNotThrow(() -> new AnthropicClient(cfg, "system"));
    }

    @Test
    void fetchReturnsZeroOnUnreachableEndpoint() {
        var cfg = anthropicCfg("http://127.0.0.1:1");
        var client = new AnthropicClient(cfg, "system");
        // 尽力获取必须产生 0（不可用），切勿抛出。
        assertEquals(0, client.fetchModelContextWindow());
    }

    @Test
    void resolvedWindowFallsBackToTableWhenFetchFails() {
        var cfg = anthropicCfg("http://127.0.0.1:1");
        // 构建客户端会触发（失败的）自动获取+回填。
        new AnthropicClient(cfg, "system");
        // 缓存保持为空→分辨率下降到内置表（克劳德→200k）。
        assertEquals(200_000, cfg.resolvedContextWindow());
    }

    @Test
    void configOverrideStillWinsEvenWithFailedFetch() {
        var cfg = anthropicCfg("http://127.0.0.1:1");
        cfg.setContextWindow(50_000);
        new AnthropicClient(cfg, "system");
        assertEquals(50_000, cfg.resolvedContextWindow());
    }
}
