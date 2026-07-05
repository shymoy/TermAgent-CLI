
package io.github.shymoy.termagent.compact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存每个 Agent 在第二层上下文压缩后仍需使用的快照。
 *
 * <p>完整压缩会替换当前对话记录；如果不保留这些快照，
 * 模型将忘记刚读取的文件以及正在执行的技能 SOP。
 * {@link ContextCompactor#buildRecoveryAttachment} 会把这些记录渲染为一个附件块，
 * 追加到压缩后的摘要消息中。
 *
 * <p>该类是线程安全的，因为流式执行器可能从多个虚拟线程触发工具回调。
 */
public final class RecoveryState {

    /** 文件读取工具最后返回的内容的快照。 */
    public record FileReadRecord(String path, String content, Instant timestamp) {}

    /** 技能执行时传给模型的 SOP 正文快照。 */
    public record SkillInvocationRecord(String name, String body, Instant timestamp) {}

    private final Object lock = new Object();
    private final Map<String, FileReadRecord> files = new HashMap<>();

    private final Map<String, SkillInvocationRecord> skills = new HashMap<>();

    /** 覆盖同一路径的旧记录，使最新快照生效。 */
    public void recordFileRead(String path, String content) {
        if (path == null || path.isEmpty()) return;

        synchronized (lock) {
            files.put(path, new FileReadRecord(path, content, Instant.now()));
        }
    }

    /** 覆盖同名技能的旧记录。 */
    public void recordSkillInvocation(String name, String body) {
        if (name == null || name.isEmpty()) return;
        synchronized (lock) {
            skills.put(name, new SkillInvocationRecord(name, body, Instant.now()));
        }
    }

    /** 按时间倒序返回最多 {@code limit} 条文件记录。 */
    public List<FileReadRecord> snapshotFiles(int limit) {
        List<FileReadRecord> out;
        synchronized (lock) {
            out = new ArrayList<>(files.values());
        }
        out.sort(Comparator.comparing(FileReadRecord::timestamp).reversed());
        if (limit > 0 && out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
    }

    /** 按时间倒序返回全部技能记录。 */
    public List<SkillInvocationRecord> snapshotSkills() {
        List<SkillInvocationRecord> out;
        synchronized (lock) {
            out = new ArrayList<>(skills.values());
        }
        out.sort(Comparator.comparing(SkillInvocationRecord::timestamp).reversed());
        return out;
    }
}
