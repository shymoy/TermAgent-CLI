
package io.github.shymoy.termagent.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shymoy.termagent.conversation.ConversationManager;
import io.github.shymoy.termagent.config.AppPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

public class SessionManager {

    /**

     * TYPE_COMPACT_BOUNDARY 将会话记录标记为压缩边界

     * 而不是简单的对话消息。边界记录的内容

     * 包含一个带有摘要文本的 JSON blob（请参阅 {@link CompactBoundary}）

     * 加上最近的尾部（保留）在压缩时逐字保留。平原

     * 消息使 {@code type} 为空/空，因此旧会话和正常轮流

     * 不受影响（仅附加，向后兼容）。

     */
    public static final String TYPE_COMPACT_BOUNDARY = "compact_boundary";

    /**

     * 会话记录。 {@code type} 区分记录类型：空/空（

     * 默认）表示普通对话消息； {@link #TYPE_COMPACT_BOUNDARY}

     * 表示 {@code content} 是一个 {@link CompactBoundary} JSON 写入的 blob

     * {@link #saveCompactBoundary}。

     * <p>

     * {@code toolUseId} 记录 API 响应中的 tool_use 块 ID，以便

     * 链验证可以在简历上正常工作——模型需要

     * tool_result 块引用它们响应的确切 tool_use_id。

     */
    public record SessionMessage(String role, String type, String content, long timestamp, String toolUseId) {
        /** Convenience constructor for plain (non-boundary) messages (无 toolUseId). */
        public SessionMessage(String role, String content, long timestamp) {
            this(role, null, content, timestamp, null);
        }

        /**

         * 有类型但没有 toolUseId 的便捷构造函数。

         */
        public SessionMessage(String role, String type, String content, long timestamp) {
            this(role, type, content, timestamp, null);
        }

        public boolean isCompactBoundary() {
            return TYPE_COMPACT_BOUNDARY.equals(type);
        }
    }

    /**

     * 在压缩时，一条逐字记录的消息保留在最近的尾部中。仅

     * 存储角色+内容文本，与会话日志的方式相匹配

     * 保留消息（仅文本，无工具块）。

     */
    public record KeepMessage(String role, String content) {}

    /**

     * 存储在边界记录内容中的结构化有效负载（如 JSON）。

     * {@code summary} 是 LLM 生成的旧前缀的摘要； {@code keep}

     * 是最近的尾部逐字保存。恢复时重建压缩状态

     * 如：[用户消息 = 摘要] + 保留 + 后面附加的任何纯文本消息

     * 边界。

     */
    public record CompactBoundary(String summary, List<KeepMessage> keep) {}

    /**

     * {@link #findLastCompactBoundary} 的结果：边界和其后的明文消息。

     */
    public record BoundaryScan(CompactBoundary boundary, List<SessionMessage> after, boolean found) {}

    public record SessionInfo(String id, String firstMessage, int messageCount,
                              long fileSize, String gitBranch, Instant modTime) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path sessionsDir(String workDir) {
        return AppPaths.project(Path.of(workDir), "sessions");
    }

    private static Path sessionFileForRead(String workDir, String sessionId) {
        return AppPaths.readableProject(Path.of(workDir), "sessions", sessionId + ".jsonl");
    }

    // ---- ID生成----

    /**
     * 生成带随机后缀的 session ID，格式为 yyyyMMdd-HHmmss-xxxx。
     * 随机后缀使用 SecureRandom 生成 2 字节十六进制，防止同秒并发冲突。
     */
    public static String newId() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        byte[] randomBytes = new byte[2];
        try {
            java.security.SecureRandom.getInstanceStrong().nextBytes(randomBytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SecureRandom 极少失败；兜底用纳秒低 16 位
            int fallback = (int) (System.nanoTime() & 0xFFFF);
            return "%s-%04x".formatted(timestamp, fallback);
        }
        return "%s-%s".formatted(timestamp,
                java.util.HexFormat.of().formatHex(randomBytes));
    }

    // ---- 坚持 ----

    public static void saveMessage(String workDir, String sessionId, String role, String content) {
        saveRecord(workDir, sessionId, role, null, content, null);
    }

    /**
     * 保存带 toolUseId 的消息，用于 resume 时的 chain validation。
     */
    public static void saveMessageWithToolUseId(String workDir, String sessionId,
                                                 String role, String content, String toolUseId) {
        saveRecord(workDir, sessionId, role, null, content, toolUseId);
    }

    /**

     * 附加压缩边界记录，以便以后的恢复可以重建

     * 压缩状态（摘要+保留尾部）而不是重放完整状态

     * 预压缩转录本。仅附加：保留原始前缀消息

     * 在文件中，但不会重播超过此边界（请参阅

     * {@link #findLastCompactBoundary}）。摘要+保留内联到

     * 将内容记录为 {@link CompactBoundary} JSON blob。无操作时

     * workDir/sessionId 为 null/空白（测试、一次性呼叫者）。

     */
    public static void saveCompactBoundary(String workDir, String sessionId,
                                           String summary, List<KeepMessage> keep) {
        if (workDir == null || workDir.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String blob = MAPPER.writeValueAsString(
                    new CompactBoundary(summary, keep == null ? List.of() : keep));
            saveRecord(workDir, sessionId, "system", TYPE_COMPACT_BOUNDARY, blob, null);
        } catch (JsonProcessingException ignored) {
            // 尽力而为：失败的边界仅意味着下一个恢复重播
            // 逐字记录，这仍然是正确的（向后兼容）。
        }
    }

    private static void saveRecord(String workDir, String sessionId,
                                   String role, String type, String content, String toolUseId) {
        try {
            Path file = AppPaths.promoteProjectFile(
                    Path.of(workDir), "sessions", sessionId + ".jsonl");
            Files.createDirectories(file.getParent());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("role", role);
            // 省略 `type` 来表示普通消息，以便老读者和旧会话
            // 不受影响（与 Go 的 `omitempty` 匹配）。
            if (type != null && !type.isEmpty()) {
                line.put("type", type);
            }
            line.put("content", content);
            line.put("ts", Instant.now().getEpochSecond());
            // toolUseId 用于 resume 时的 chain validation，仅在有值时写入
            if (toolUseId != null && !toolUseId.isEmpty()) {
                line.put("tool_use_id", toolUseId);
            }
            String json = MAPPER.writeValueAsString(line) + "\n";
            Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 尽力而为，与 Go 版本相同
        }
    }

    public static List<SessionMessage> loadSession(String workDir, String sessionId) {
        Path file = sessionFileForRead(workDir, sessionId);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<SessionMessage> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = MAPPER.readValue(line, Map.class);
                    String role = (String) map.get("role");
                    String type = (String) map.get("type");
                    String content = (String) map.get("content");
                    long ts = map.get("ts") instanceof Number n ? n.longValue() : 0L;
                    // 读取 toolUseId，用于 resume 时的 chain validation
                    String toolUseId = (String) map.get("tool_use_id");
                    if (content != null && !content.isEmpty()) {
                        messages.add(new SessionMessage(role, type, content, ts, toolUseId));
                    }
                } catch (IOException ignored) {
                    // 跳过格式错误的行
                }
            }
        } catch (IOException ignored) {
            // 返回我们迄今为止收集的所有内容
        }
        return messages;
    }

    // ---- 压实-边界扫描 ----

    /**

     * 扫描加载的记录以查找 LAST 压缩边界。返回

     * 解析的边界加上其后附加的普通（非边界）消息。

     * 当不存在边界（或其斑点已损坏）时，{@code found} 为 false 并且

     * 调用者应逐字重播所有记录 - 向后兼容

     * old sessions that have no boundary records.

     */
    public static BoundaryScan findLastCompactBoundary(List<SessionMessage> messages) {
        int last = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).isCompactBoundary()) {
                last = i;
            }
        }
        if (last < 0) {
            return new BoundaryScan(null, List.of(), false);
        }
        CompactBoundary boundary;
        try {
            boundary = MAPPER.readValue(messages.get(last).content(), CompactBoundary.class);
        } catch (IOException e) {
            // 损坏的边界斑点 - 回退到完整重播而不是失败
            // 谈话。
            return new BoundaryScan(null, List.of(), false);
        }
        List<SessionMessage> after = new ArrayList<>();
        for (int i = last + 1; i < messages.size(); i++) {
            SessionMessage m = messages.get(i);
            if (m.isCompactBoundary()) continue; // defensive; we targeted the final one
            after.add(m);
        }
        return new BoundaryScan(boundary, after, true);
    }

    // ---- 对话重建 ----

    /**

     * 压缩感知重建。如果会话包含 {@code compact_boundary}，

     * 实时对话是压缩状态 - [摘要为用户消息] +

     * 保留尾部+边界后附加的任何普通消息 - 以及

     * 原始预压缩前缀是 NOT 重播（它保留在文件中

     * 审计）。如果没有边界（旧会话），一切都会逐字重播。

     */
    public static ConversationManager rebuildConversation(List<SessionMessage> messages) {
        BoundaryScan scan = findLastCompactBoundary(messages);
        if (!scan.found()) {
            return replay(messages);
        }
        List<SessionMessage> replay = new ArrayList<>();
        // 摘要成为同中文框架领先的用户留言
        // 作为 autoCompact，因此模型在简历中看到一致的上下文标头。
        String resumeSummary = "本次会话延续自之前的对话，因上下文空间不足进行了压缩。以下是早期对话的摘要：\n\n"
                + scan.boundary().summary();
        if (!scan.boundary().keep().isEmpty()) {
            resumeSummary += "\n\n近期消息已原样保留。";
        }
        replay.add(new SessionMessage("user", resumeSummary, 0L));
        for (KeepMessage k : scan.boundary().keep()) {
            replay.add(new SessionMessage(k.role(), k.content(), 0L));
        }
        replay.addAll(scan.after());
        return replay(replay);
    }

    private static ConversationManager replay(List<SessionMessage> messages) {
        ConversationManager conversation = new ConversationManager();
        for (SessionMessage msg : messages) {
            if (msg.isCompactBoundary()) continue; // never replay the raw boundary blob
            switch (msg.role()) {
                case "assistant" -> conversation.addAssistantMessage(msg.content());
                default -> conversation.addUserMessage(msg.content());
            }
        }
        return conversation;
    }

    // ---- 会话过期清理 ----

    /** 过期阈值：30 天 */
    private static final long EXPIRY_DAYS = 30;

    /**
     * 自动清理超过 30 天的过期 session 文件。
     * 根据文件的最后修改时间判断是否过期。
     * 失败时静默忽略——清理是尽力而为，不应影响正常流程。
     */
    public static void cleanExpiredSessions(String workDir) {
        Path baseDir = sessionsDir(workDir);
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        long cutoffMs = System.currentTimeMillis() - EXPIRY_DAYS * 24 * 60 * 60 * 1000L;
        try (Stream<Path> paths = Files.list(baseDir)) {
            paths.filter(p -> p.toString().endsWith(".jsonl"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     try {
                         long mtime = Files.getLastModifiedTime(p).toMillis();
                         if (mtime < cutoffMs) {
                             Files.deleteIfExists(p);
                         }
                     } catch (IOException ignored) {
                         // 单个文件清理失败不影响其它
                     }
                 });
        } catch (IOException ignored) {
            // 目录不可读时静默忽略
        }
    }

    // ---- Listing ----

    public static List<SessionInfo> listSessions(String workDir) {
        String branch = currentGitBranch(workDir);
        Map<String, SessionInfo> sessions = new LinkedHashMap<>();
        for (Path baseDir : AppPaths.projectLayers(Path.of(workDir), "sessions")) {
            if (!Files.isDirectory(baseDir)) continue;
            try (Stream<Path> paths = Files.list(baseDir)) {
                paths.filter(p -> p.toString().endsWith(".jsonl"))
                    .filter(Files::isRegularFile)
                    .forEach(p -> {
                     String fileName = p.getFileName().toString();
                     String id = fileName.substring(0, fileName.length() - ".jsonl".length());
                     try {
                         long fileSize = Files.size(p);
                         Instant modTime = Files.getLastModifiedTime(p).toInstant();
                         List<SessionMessage> msgs = loadSession(workDir, id);
                         String first = msgs.stream()
                                 .filter(m -> "user".equals(m.role()))
                                 .map(SessionMessage::content)
                                 .findFirst()
                                 .orElse("");
                         sessions.put(id, new SessionInfo(id, first, msgs.size(),
                                 fileSize, branch, modTime));
                     } catch (IOException ignored) {
                         // 跳过这个文件
                     }
                    });
            } catch (IOException ignored) {
                // 单个目录不可读时继续处理另一层
            }
        }
        List<SessionInfo> result = new ArrayList<>(sessions.values());
        result.sort(Comparator.comparing(SessionInfo::modTime).reversed());
        return result;
    }

    // ---- Git branch ----

    public static String currentGitBranch(String workDir) {
        try {
            Process proc = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--abbrev-ref", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            int code = proc.waitFor();
            return code == 0 ? output : "";
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    // ---- 格式化助手 ----

    public static String formatRelativeTime(Instant t) {
        Duration d = Duration.between(t, Instant.now());
        long seconds = d.getSeconds();
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes == 1 ? "1 minute ago" : minutes + " minutes ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours == 1 ? "1 hour ago" : hours + " hours ago";
        }
        long days = hours / 24;
        if (days < 7) {
            return days == 1 ? "1 day ago" : days + " days ago";
        }
        long weeks = days / 7;
        return weeks == 1 ? "1 week ago" : weeks + " weeks ago";
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            double kb = bytes / 1024.0;
            return kb == (long) kb
                    ? String.format("%.0fKB", kb)
                    : String.format("%.1fKB", kb);
        }
        double mb = bytes / 1024.0 / 1024.0;
        return String.format("%.1fMB", mb);
    }

    // ---- Search ----

    public static boolean matchesSearch(SessionInfo s, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.toLowerCase();
        return s.firstMessage().toLowerCase().contains(q)
                || s.id().toLowerCase().contains(q);
    }
}
