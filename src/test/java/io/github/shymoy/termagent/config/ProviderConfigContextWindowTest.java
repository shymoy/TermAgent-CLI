
package io.github.shymoy.termagent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**

 * 涵盖{@link ProviderConfig}中的四层上下文窗口分辨率：

 * <ol>

 * <li>显式配置 {@code context_window} 获胜；</li>

 * <li> 无配置覆盖时使用的自动获取值（缓存）；</li>

 * <li>内置款→窗台；</li>

 * <li>保守默认.</li>

 * </ol>

 * 加上优雅降级契约：获取失败（缓存从未设置，或者

 * 设置为非正值）必须落到表中，永远不会崩溃。

 */
class ProviderConfigContextWindowTest {

    private static ProviderConfig cfg(String model) {
        var c = new ProviderConfig();
        c.setModel(model);
        return c;
    }

    // ---- 第 1 层：显式配置覆盖具有最高优先级 ----

    @Test
    void configContextWindowWinsOverEverything() {
        var c = cfg("claude-sonnet-4-6");
        c.setContextWindow(12_345);
        // 即使存在获取的值，配置仍然获胜。
        c.setFetchedContextWindow(999_999);
        assertEquals(12_345, c.resolvedContextWindow());
    }

    @Test
    void configContextWindowWinsForUnknownModel() {
        var c = cfg("some-exotic-model");
        c.setContextWindow(64_000);
        assertEquals(64_000, c.resolvedContextWindow());
    }

    // ---- 第 2 层：自动获取值，缓存 ----

    @Test
    void fetchedValueUsedWhenNoConfigOverride() {
        var c = cfg("claude-sonnet-4-6"); // table would say 200k
        c.setFetchedContextWindow(321_000);
        assertEquals(321_000, c.resolvedContextWindow());
    }

    @Test
    void fetchedNonPositiveIsIgnoredAndFallsThrough() {
        var c = cfg("gpt-4o"); // table → 128k
        // 模拟失败/空获取：不得毒害缓存。
        c.setFetchedContextWindow(0);
        c.setFetchedContextWindow(-1);
        assertEquals(128_000, c.resolvedContextWindow());
    }

    // ---- 第三层：内置模型→窗口表（子串匹配）----

    @Test
    void tableMatchesEachModelToExpectedWindow() {
        // 1M 上下文变体（子字符串 "1m"）
        assertEquals(1_000_000, ProviderConfig.windowForModel("claude-sonnet-4-6-1m"));
        assertEquals(1_000_000, ProviderConfig.windowForModel("some-model-1m-preview"));
        // gpt-4.1 family
        assertEquals(1_000_000, ProviderConfig.windowForModel("gpt-4.1-mini"));
        // gpt-4o
        assertEquals(128_000, ProviderConfig.windowForModel("gpt-4o"));
        assertEquals(128_000, ProviderConfig.windowForModel("gpt-4o-mini"));
        // gpt-4-turbo
        assertEquals(128_000, ProviderConfig.windowForModel("gpt-4-turbo-2024-04-09"));
        // 推理模型o1/o3/o4
        assertEquals(200_000, ProviderConfig.windowForModel("o1-preview"));
        assertEquals(200_000, ProviderConfig.windowForModel("o3-mini"));
        assertEquals(200_000, ProviderConfig.windowForModel("o4-mini"));
        // gpt-3.5
        assertEquals(16_385, ProviderConfig.windowForModel("gpt-3.5-turbo"));
        // claude
        assertEquals(200_000, ProviderConfig.windowForModel("claude-opus-4-6"));
        // 不区分大小写
        assertEquals(200_000, ProviderConfig.windowForModel("Claude-Haiku"));
    }

    @Test
    void oneMillionBeatsMoreGenericMatches() {
        // "1m" claude 变体必须解析为 1M，而不是通用的 claude 200k。
        assertEquals(1_000_000, ProviderConfig.windowForModel("claude-sonnet-4-6-1m"));
        // gpt-4.1 仍然是 1M，尽管它也包含 "gpt-4"。
        assertEquals(1_000_000, ProviderConfig.windowForModel("gpt-4.1"));
    }

    // ---- 第 4 层：保守默认 ----

    @Test
    void defaultsWhenNothingMatches() {
        assertEquals(128_000, ProviderConfig.windowForModel("totally-unknown-llm"));
        assertEquals(128_000, ProviderConfig.windowForModel(null));
        assertEquals(128_000, ProviderConfig.windowForModel(""));
    }

    @Test
    void resolveFallsBackToTableThenDefaultWithoutFetch() {
        // 没有配置覆盖，没有获取值 → 表。
        assertEquals(200_000, cfg("claude-opus-4-6").resolvedContextWindow());
        // 没有配置，没有获取，没有表命中→默认。
        assertEquals(128_000, cfg("mystery-model").resolvedContextWindow());
    }
}
