

package io.github.shymoy.termagent.session;

import io.github.shymoy.termagent.conversation.ConversationManager;
import io.github.shymoy.termagent.conversation.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    @Test
    void saveAndLoadRoundtripPreservesOrderRoleAndContent(@TempDir Path dir) {
        String workDir = dir.toString();
        String sessionId = "20260101-120000";

        SessionManager.saveMessage(workDir, sessionId, "user", "hello");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "hi there");
        SessionManager.saveMessage(workDir, sessionId, "user", "how are you?");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "doing well");

        var loaded = SessionManager.loadSession(workDir, sessionId);

        assertEquals(4, loaded.size());

        assertEquals("user", loaded.get(0).role());
        assertEquals("hello", loaded.get(0).content());

        assertEquals("assistant", loaded.get(1).role());
        assertEquals("hi there", loaded.get(1).content());

        assertEquals("user", loaded.get(2).role());
        assertEquals("how are you?", loaded.get(2).content());

        assertEquals("assistant", loaded.get(3).role());
        assertEquals("doing well", loaded.get(3).content());
    }

    @Test
    void rebuildConversationFromPersistedSessionPreservesRoles(@TempDir Path dir) {
        String workDir = dir.toString();
        String sessionId = "20260101-130000";

        SessionManager.saveMessage(workDir, sessionId, "user", "hello");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "hi there");
        SessionManager.saveMessage(workDir, sessionId, "user", "how are you?");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "doing well");

        var loaded = SessionManager.loadSession(workDir, sessionId);
        ConversationManager conversation = SessionManager.rebuildConversation(loaded);

        List<Message> messages = conversation.getMessages();
        assertEquals(4, messages.size());

        assertEquals("user", messages.get(0).getRole());
        assertEquals("hello", messages.get(0).getContent());

        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("hi there", messages.get(1).getContent());

        assertEquals("user", messages.get(2).getRole());
        assertEquals("how are you?", messages.get(2).getContent());

        assertEquals("assistant", messages.get(3).getRole());
        assertEquals("doing well", messages.get(3).getContent());
    }

    @Test
    void loadSessionReturnsEmptyListForMissingSession(@TempDir Path dir) {
        var loaded = SessionManager.loadSession(dir.toString(), "does-not-exist");
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void newIdIsNonEmpty() {
        String id = SessionManager.newId();
        assertNotNull(id);
        assertFalse(id.isBlank());
    }

    // ── 紧凑边界往返 ──────────────────────────────────────

    @Test
    void resumeRebuildsCompactedStateFromBoundary(@TempDir Path dir) {
        String workDir = dir.toString();
        String sessionId = "20260101-140000";

        // 预压缩前缀：这些原始消息保留在文件中，但

        // 一旦存在边界，就必须重播 NOT。
        SessionManager.saveMessage(workDir, sessionId, "user", "original prefix question");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "original prefix answer");

        // 压缩写入一个内联摘要的边界+保留逐字尾部。
        var keep = List.of(
                new SessionManager.KeepMessage("user", "kept user turn"),
                new SessionManager.KeepMessage("assistant", "kept assistant turn"));
        SessionManager.saveCompactBoundary(workDir, sessionId, "THE SUMMARY", keep);

        // Continuation after the boundary (chained resume /续写).
        SessionManager.saveMessage(workDir, sessionId, "user", "post-boundary question");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "post-boundary answer");

        var loaded = SessionManager.loadSession(workDir, sessionId);

        // 边界扫描找到边界+其后的明文消息。
        var scan = SessionManager.findLastCompactBoundary(loaded);
        assertTrue(scan.found());
        assertEquals("THE SUMMARY", scan.boundary().summary());
        assertEquals(2, scan.boundary().keep().size());
        assertEquals(2, scan.after().size());
        assertEquals("post-boundary question", scan.after().get(0).content());

        // 压缩感知重建 = [作为用户摘要] + 保留 + 之后。
        ConversationManager conv = SessionManager.rebuildConversation(loaded);
        List<Message> msgs = conv.getMessages();
        assertEquals(5, msgs.size());

        // 摘要是主要的用户信息，采用中文框架。
        assertEquals("user", msgs.get(0).getRole());
        assertTrue(msgs.get(0).getContent().contains("本次会话延续自之前的对话"));
        assertTrue(msgs.get(0).getContent().contains("THE SUMMARY"));
        assertTrue(msgs.get(0).getContent().contains("近期消息已原样保留"),
                "kept tail is non-empty so the framing should include 近期消息已原样保留");

        // 保留逐字尾部（保留原始文本）。
        assertEquals("user", msgs.get(1).getRole());
        assertEquals("kept user turn", msgs.get(1).getContent());
        assertEquals("assistant", msgs.get(2).getRole());
        assertEquals("kept assistant turn", msgs.get(2).getContent());

        // 消息附加在边界之后。
        assertEquals("user", msgs.get(3).getRole());
        assertEquals("post-boundary question", msgs.get(3).getContent());
        assertEquals("assistant", msgs.get(4).getRole());
        assertEquals("post-boundary answer", msgs.get(4).getContent());

        // 预压缩前缀为 NOT 重播。
        boolean prefixReplayed = msgs.stream()
                .anyMatch(m -> m.getContent() != null
                        && (m.getContent().contains("original prefix question")
                            || m.getContent().contains("original prefix answer")));
        assertFalse(prefixReplayed, "pre-boundary original prefix must not be replayed");

        // 原始边界斑点永远不会作为对话消息重播。
        boolean blobReplayed = msgs.stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains("\"keep\""));
        assertFalse(blobReplayed, "raw boundary JSON blob must not appear in the conversation");
    }

    @Test
    void resumeReplaysEverythingWhenNoBoundary(@TempDir Path dir) {
        // 向后兼容性：必须是没有compact_boundary的旧会话
        // 逐字重播所有消息，与传统行为保持不变。
        String workDir = dir.toString();
        String sessionId = "20260101-150000";

        SessionManager.saveMessage(workDir, sessionId, "user", "q1");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "a1");
        SessionManager.saveMessage(workDir, sessionId, "user", "q2");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "a2");

        var loaded = SessionManager.loadSession(workDir, sessionId);
        assertFalse(SessionManager.findLastCompactBoundary(loaded).found());

        ConversationManager conv = SessionManager.rebuildConversation(loaded);
        List<Message> msgs = conv.getMessages();
        assertEquals(4, msgs.size());
        assertEquals("q1", msgs.get(0).getContent());
        assertEquals("a1", msgs.get(1).getContent());
        assertEquals("q2", msgs.get(2).getContent());
        assertEquals("a2", msgs.get(3).getContent());
    }

    @Test
    void onlyLastBoundaryWinsAcrossChainedCompactions(@TempDir Path dir) {
        // 一个会话中的两次压缩（链接）：恢复必须从
        // 仅 LAST 边界，删除第一个摘要及其之前的所有内容。
        String workDir = dir.toString();
        String sessionId = "20260101-160000";

        SessionManager.saveMessage(workDir, sessionId, "user", "very old q");
        SessionManager.saveCompactBoundary(workDir, sessionId, "FIRST SUMMARY",
                List.of(new SessionManager.KeepMessage("assistant", "first-kept")));
        SessionManager.saveMessage(workDir, sessionId, "user", "mid q");
        SessionManager.saveCompactBoundary(workDir, sessionId, "SECOND SUMMARY",
                List.of(new SessionManager.KeepMessage("user", "second-kept")));
        SessionManager.saveMessage(workDir, sessionId, "assistant", "newest a");

        var loaded = SessionManager.loadSession(workDir, sessionId);
        var scan = SessionManager.findLastCompactBoundary(loaded);
        assertTrue(scan.found());
        assertEquals("SECOND SUMMARY", scan.boundary().summary());
        assertEquals(1, scan.after().size());
        assertEquals("newest a", scan.after().get(0).content());

        ConversationManager conv = SessionManager.rebuildConversation(loaded);
        List<Message> msgs = conv.getMessages();
        // [SECOND SUMMARY 带中文框] + [第二个保留] + [最新一个]
        assertEquals(3, msgs.size());
        assertTrue(msgs.get(0).getContent().contains("本次会话延续自之前的对话"));
        assertTrue(msgs.get(0).getContent().contains("SECOND SUMMARY"));
        assertEquals("second-kept", msgs.get(1).getContent());
        assertEquals("newest a", msgs.get(2).getContent());

        boolean firstSummaryReplayed = msgs.stream()
                .anyMatch(m -> "FIRST SUMMARY".equals(m.getContent())
                        || "first-kept".equals(m.getContent())
                        || "very old q".equals(m.getContent())
                        || "mid q".equals(m.getContent()));
        assertFalse(firstSummaryReplayed, "only the last boundary's state must be rebuilt");
    }

    @Test
    void saveCompactBoundaryIsNoOpWhenSessionIdBlank(@TempDir Path dir) {
        // 防御性：子代理/一次性呼叫者传递空白 ID，并且不得
        // create a session file.
        SessionManager.saveCompactBoundary(dir.toString(), "", "x", List.of());
        SessionManager.saveCompactBoundary(dir.toString(), null, "x", List.of());
        assertTrue(SessionManager.loadSession(dir.toString(), "").isEmpty());
    }

    @Test
    void legacySessionIsReadableAndPromotedBeforeAppend(@TempDir Path dir) throws Exception {
        String sessionId = "20260101-170000";
        Path legacy = dir.resolve(".mewcode/sessions").resolve(sessionId + ".jsonl");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy,
                "{\"role\":\"user\",\"content\":\"legacy\",\"ts\":1}\n");

        assertEquals("legacy", SessionManager.loadSession(dir.toString(), sessionId).getFirst().content());

        SessionManager.saveMessage(dir.toString(), sessionId, "assistant", "current");
        Path current = dir.resolve(".termagent/sessions").resolve(sessionId + ".jsonl");
        assertTrue(Files.exists(current));
        assertEquals(List.of("legacy", "current"), SessionManager.loadSession(dir.toString(), sessionId)
                .stream().map(SessionManager.SessionMessage::content).toList());
        assertTrue(Files.exists(legacy), "legacy file should be preserved");
    }
}
