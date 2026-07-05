
package io.github.shymoy.termagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**

 * 涵盖真实token压缩锚点的用法解析：StreamEnd

 * record携带缓存读取/创建桶，OpenAI兼容用法是

 * 分割，以便缓存部分不会在锚总和中重复计算。

 */
class UsageParsingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void streamEndDefaultsCacheFieldsToZero() {
        // 3-arg 构造函数（由没有缓存崩溃的供应商使用）
        // 必须将两个缓存桶保留为 0。
        var end = new StreamEvent.StreamEnd("end_turn", 100, 20);
        assertEquals(0, end.cacheReadTokens());
        assertEquals(0, end.cacheCreationTokens());
    }

    @Test
    void streamEndCarriesCacheFields() {
        var end = new StreamEvent.StreamEnd("end_turn", 100, 20, 4_000, 1_500);
        assertEquals(100, end.inputTokens());
        assertEquals(20, end.outputTokens());
        assertEquals(4_000, end.cacheReadTokens());
        assertEquals(1_500, end.cacheCreationTokens());
    }

    @Test
    void openAiCompatUsageSplitsCachedFromPrompt() {
        // Prompt_tokens 包括cached_tokens； extractUsage 必须将它们分开，这样
        // input + cacheRead 只重构一次原始提示总数。
        JsonNode root = parse("""
                {"usage":{"prompt_tokens":10000,"completion_tokens":500,
                          "prompt_tokens_details":{"cached_tokens":7000}}}""");
        int[] usage = OpenAiCompatClient.extractUsage(root);
        assertEquals(3_000, usage[0], "input = prompt - cached");
        assertEquals(500, usage[1], "output");
        assertEquals(7_000, usage[2], "cacheRead from prompt_tokens_details");
        assertEquals(10_000, usage[0] + usage[2], "no double-count of the cache hit");
    }

    @Test
    void openAiCompatUsageDefaultsCacheToZeroWhenAbsent() {
        JsonNode root = parse("""
                {"usage":{"prompt_tokens":800,"completion_tokens":40}}""");
        int[] usage = OpenAiCompatClient.extractUsage(root);
        assertEquals(800, usage[0]);
        assertEquals(40, usage[1]);
        assertEquals(0, usage[2], "no cached_tokens → cacheRead is 0");
    }

    @Test
    void openAiCompatUsageMissingReturnsZeros() {
        int[] usage = OpenAiCompatClient.extractUsage(parse("{}"));
        assertArrayEquals(new int[]{0, 0, 0}, usage);
    }
}
