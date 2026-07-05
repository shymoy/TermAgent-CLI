

package io.github.shymoy.termagent.compact;

import io.github.shymoy.termagent.conversation.ConversationManager;
import io.github.shymoy.termagent.conversation.Message;
import io.github.shymoy.termagent.conversation.ToolResultBlock;
import io.github.shymoy.termagent.conversation.ToolUseBlock;
import io.github.shymoy.termagent.llm.LlmClient;
import io.github.shymoy.termagent.llm.StreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompactorTest {

    /**

     * 最小存根：发出固定的 <summary> 并结束，无论输入如何。

     */
    private static final class StubSummaryClient implements LlmClient {
        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv,
                                                 List<Map<String, Object>> tools) {
            BlockingQueue<StreamEvent> q = new LinkedBlockingQueue<>();
            q.add(new StreamEvent.TextDelta(
                    "<summary>old prefix summarized</summary>"));
            q.add(new StreamEvent.StreamEnd("end_turn", 0, 0));
            return q;
        }
    }

    @Test
    void estimateTokensEmpty() {
        assertEquals(0, ContextCompactor.estimateTokens(List.of()));
    }

    @Test
    void estimateTokensWithContent() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("hello world");
        int tokens = ContextCompactor.estimateTokens(conv.getMessages());
        assertTrue(tokens > 0, "should estimate non-zero tokens for non-empty message");
    }

    @Test
    void currentTokensFallsBackToCharEstimateWhenNoAnchor() {
        // 冷启动：尚未实际使用，因此 currentTokens 必须等于旧版
        // 整个对话的性格估计。
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("hello world");
        conv.addAssistantMessage("a reply with some content");

        int estimate = ContextCompactor.estimateTokens(conv.getMessages());
        int current = ContextCompactor.currentTokens(conv.getMessages(), null);
        assertEquals(estimate, current, "no anchor → fall back to whole-conversation estimate");
    }

    @Test
    void currentTokensUsesBaselinePlusIncrementWhenAnchored() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("first turn");          // index 0
        conv.addAssistantMessage("first response"); // index 1
        // 在前两条消息之后锚定，其真实基线远低于任何消息
        // 字符估计（e.g。缓存命中报告了一个小的实际输入）。
        var anchor = new ContextCompactor.UsageAnchor(5_000, conv.size());

        // 锚点后还附加了两条消息。
        conv.addUserMessage("second turn");
        conv.addAssistantMessage("second response");

        var appended = conv.getMessages().subList(2, conv.getMessages().size());
        int increment = ContextCompactor.estimateTokens(appended);

        int current = ContextCompactor.currentTokens(conv.getMessages(), anchor);
        assertEquals(5_000 + increment, current,
                "anchored → baseline plus estimate of only the appended messages");
        // 并且它必须忽略预锚消息的字符成本。
        assertTrue(current < ContextCompactor.estimateTokens(conv.getMessages()) + 5_000);
    }

    @Test
    void currentTokensFallsBackWhenAnchorCountOutOfRange() {
        // 计数超过（现已压缩）消息列表的陈旧锚点必须
        // 不抛出并且必须降级为整个会话的估计。
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("only message");

        var stale = new ContextCompactor.UsageAnchor(9_999, 50);
        int current = ContextCompactor.currentTokens(conv.getMessages(), stale);
        assertEquals(ContextCompactor.estimateTokens(conv.getMessages()), current,
                "out-of-range anchorCount → safe fallback to char estimate");
    }

    @Test
    void offloadSpillsLargeResult(@TempDir Path tempDir) {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("run a command");

        String bigContent = "x".repeat(60_000);
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu-1", bigContent, false)));

        String result = ContextCompactor.offloadAndSnip(conv, tempDir.toString());
        assertFalse(result.isEmpty(), "should report spill activity");

        String content = conv.getMessages().get(1).getToolResults().get(0).content();
        assertTrue(content.startsWith("[Result of "), "result should be replaced with stub");

        Path spillFile = tempDir.resolve(".termagent/tool_results/tu-1");
        assertTrue(Files.exists(spillFile), "spill file should exist on disk");
    }

    @Test
    void offloadIsIdempotent(@TempDir Path tempDir) {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("run");
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu-2", "y".repeat(60_000), false)));

        ContextCompactor.offloadAndSnip(conv, tempDir.toString());
        String secondResult = ContextCompactor.offloadAndSnip(conv, tempDir.toString());

        assertEquals("", secondResult, "second pass should be a no-op");
    }

    @Test
    void formatCompactSummaryExtractsSummaryTag() {
        String raw = "<analysis>some analysis</analysis>\n<summary>the real summary</summary>";
        assertEquals("the real summary", ContextCompactor.formatCompactSummary(raw));
    }

    @Test
    void formatCompactSummaryFallback() {
        String raw = "no tags here, just plain text";
        assertEquals(raw, ContextCompactor.formatCompactSummary(raw));
    }

    // ── messagesToKeep 窗口 ────────────────────────────────────────────

    @Test
    void keepStartReturnsZeroWhenEverythingFitsInKeepWindow() {
        // 少于 MIN_KEEP_MESSAGES 消息 → 整个事情都是保留的
        // 窗口，没有什么可总结的。
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("one");
        conv.addAssistantMessage("two");
        assertEquals(0, ContextCompactor.computeKeepStartIndex(conv.getMessages()));
    }

    @Test
    void keepStartLeavesAtLeastMinKeepMessagesForSummarizablePrefix() {
        // 多条小信息：MIN_KEEP_MESSAGES(=5)为跳闸楼层
        // 首先，所以保留窗口恰好是最后 5 条消息。
        ConversationManager conv = new ConversationManager();
        for (int i = 0; i < 20; i++) {
            conv.addUserMessage("u" + i);
            conv.addAssistantMessage("a" + i);
        }
        int n = conv.size();
        int keepStart = ContextCompactor.computeKeepStartIndex(conv.getMessages());
        assertEquals(n - 5, keepStart,
                "small messages → MIN_KEEP_MESSAGES floor keeps exactly the last 5");
    }

    @Test
    void compactKeepsRecentMessagesVerbatim() {
        ConversationManager conv = new ConversationManager();
        // 将被总结掉的旧前缀。
        for (int i = 0; i < 12; i++) {
            conv.addUserMessage("old user msg " + i + " " + "x".repeat(200));
            conv.addAssistantMessage("old reply " + i + " " + "y".repeat(200));
        }
        // 最近的逐字尾部带有独特的标记。
        conv.addUserMessage("RECENT_MARKER_ALPHA latest question");
        conv.addAssistantMessage("RECENT_MARKER_BETA latest answer");

        String result = ContextCompactor.forceCompact(
                conv, new StubSummaryClient(), 100_000, null, null);
        assertFalse(result.isEmpty(), "compaction should have run");

        List<Message> after = conv.getMessages();
        // 摘要用户消息必须在前面（之后没有助手确认）。
        assertTrue(after.get(0).getContent().contains("本次会话延续自之前的对话"));
        assertTrue(after.get(0).getContent().contains("old prefix summarized"));

        // 最近的原件必须逐字保存——不能被摘要取代。
        String joined = after.stream().map(Message::getContent).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("RECENT_MARKER_ALPHA latest question"),
                "recent user message must be kept verbatim");
        assertTrue(joined.contains("RECENT_MARKER_BETA latest answer"),
                "recent assistant message must be kept verbatim");
    }

    @Test
    void compactDoesNotSplitToolUseToolResultPair() {
        ConversationManager conv = new ConversationManager();
        // 填充前缀所以有一些东西可以总结。
        for (int i = 0; i < 12; i++) {
            conv.addUserMessage("filler " + i + " " + "x".repeat(300));
            conv.addAssistantMessage("reply " + i + " " + "y".repeat(300));
        }
        // 位于尾部边界的 tool_use / tool_result 对。
        conv.addAssistantFull("calling tool", null,
                List.of(new ToolUseBlock("tu-pair", "ReadFile", Map.of("path", "/x"))));
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu-pair", "z".repeat(1_000), false)));
        conv.addUserMessage("after the tool");
        conv.addAssistantMessage("done");

        int keepStart = ContextCompactor.computeKeepStartIndex(conv.getMessages());
        Message boundary = conv.getMessages().get(keepStart);
        // 保留窗口绝不能在仅 tool_result 用户消息上 START，
        // 这会将其从辅助 tool_use 中孤立出来。
        boolean isOrphanResult = "user".equals(boundary.getRole())
                && boundary.getToolResults() != null
                && !boundary.getToolResults().isEmpty();
        assertFalse(isOrphanResult,
                "keepStart must not land on an orphaned tool_result message");

        // 压缩后，每个保留的 tool_result 也必须保留其 tool_use。
        ContextCompactor.forceCompact(conv, new StubSummaryClient(), 100_000, null, null);
        assertToolPairsBalanced(conv.getMessages());
    }

    @Test
    void summaryCoversOnlyPrefixNotKeptTail() {
        ConversationManager conv = new ConversationManager();
        for (int i = 0; i < 12; i++) {
            conv.addUserMessage("PREFIX_ONLY " + i + " " + "x".repeat(200));
            conv.addAssistantMessage("prefix reply " + i + " " + "y".repeat(200));
        }
        conv.addUserMessage("KEPT_TAIL question");
        conv.addAssistantMessage("KEPT_TAIL answer");

        int keepStartBefore = ContextCompactor.computeKeepStartIndex(conv.getMessages());
        List<Message> before = conv.getMessages();
        // 传递给 LLM 的序列化有效负载必须仅包含前缀。
        List<Message> prefix = before.subList(0, keepStartBefore);
        List<Message> kept = before.subList(keepStartBefore, before.size());
        assertTrue(prefix.stream().anyMatch(m -> m.getContent() != null
                && m.getContent().contains("PREFIX_ONLY")));
        assertTrue(kept.stream().anyMatch(m -> m.getContent() != null
                && m.getContent().contains("KEPT_TAIL")),
                "the kept tail must sit outside the summarized prefix");

        ContextCompactor.forceCompact(conv, new StubSummaryClient(), 100_000, null, null);
        // 压实后，尾巴仍然一字不差地存在。
        String joined = conv.getMessages().stream()
                .map(Message::getContent).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("KEPT_TAIL question"));
        assertTrue(joined.contains("KEPT_TAIL answer"));
    }

    @Test
    void compactDegradesToNoOpWhenTooFewMessages() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("only a couple");
        conv.addAssistantMessage("of messages here");

        int sizeBefore = conv.size();
        String result = ContextCompactor.forceCompact(
                conv, new StubSummaryClient(), 100_000, null, null);
        assertEquals("", result, "too few messages → no-op, no summary round-trip");
        assertEquals(sizeBefore, conv.size(), "conversation must be left untouched");
        assertEquals("only a couple", conv.getMessages().get(0).getContent());
    }

    /**

     * 断言列表中的每个 tool_result 都有一个匹配的早期 tool_use。

     */
    private static void assertToolPairsBalanced(List<Message> messages) {
        java.util.Set<String> seenToolUse = new java.util.HashSet<>();
        for (Message m : messages) {
            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    seenToolUse.add(tu.toolUseId());
                }
            }
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    assertTrue(seenToolUse.contains(tr.toolUseId()),
                            "tool_result " + tr.toolUseId()
                                    + " has no preceding tool_use (orphaned pair)");
                }
            }
        }
    }

    @Test
    void circuitBreakerTripsAfterThreeFailures() {
        var tracking = new ContextCompactor.AutoCompactTrackingState();
        assertFalse(tracking.isTripped());

        tracking.recordFailure();
        tracking.recordFailure();
        assertFalse(tracking.isTripped());

        tracking.recordFailure();
        assertTrue(tracking.isTripped());

        tracking.reset();
        assertFalse(tracking.isTripped());
    }
}

