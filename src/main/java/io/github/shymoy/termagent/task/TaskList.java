
package io.github.shymoy.termagent.task;

import io.github.shymoy.termagent.config.AppPaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**

 * 管理在下保存为 JSON 的命名任务列表

 * {@code .termagent/tasks/<listId>.json}。

 * <p>

 * 每次修改都会重新加载文件，应用更改并将其写回，以便

 * 共享同一存储的并发进程看到一致的数据。

 * 所有公共方法都是 {@code synchronized} 来保护进程内路径。

 */
public class TaskList {

    public enum Status {
        PENDING("pending"),
        IN_PROGRESS("in_progress"),
        COMPLETED("completed");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Status fromString(String s) {
            for (Status st : values()) {
                if (st.value.equals(s)) {
                    return st;
                }
            }
            throw new IllegalArgumentException("unknown status: " + s);
        }
    }

    public static class Task {
        private String id;
        private String subject;
        private String description;
        private String activeForm;

        private String status = Status.PENDING.value();
        private String owner;
        private List<String> blocks = new ArrayList<>();
        private List<String> blockedBy = new ArrayList<>();
        private Map<String, Object> metadata = new LinkedHashMap<>();

        // Jackson 需要一个无参构造函数
        public Task() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getActiveForm() { return activeForm; }
        public void setActiveForm(String activeForm) { this.activeForm = activeForm; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner; }

        public List<String> getBlocks() { return blocks; }
        public void setBlocks(List<String> blocks) { this.blocks = blocks; }

        public List<String> getBlockedBy() { return blockedBy; }
        public void setBlockedBy(List<String> blockedBy) { this.blockedBy = blockedBy; }

        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final SecureRandom RNG = new SecureRandom();

    private final String listId;
    private final Path storePath;

    public TaskList(String listId, String workDir) {
        this.listId = listId;
        Path root = Path.of(workDir);
        Path path;
        try {
            path = AppPaths.promoteProjectFile(root, "tasks", listId + ".json");
        } catch (IOException ignored) {
            path = AppPaths.project(root, "tasks", listId + ".json");
        }
        this.storePath = path;
    }

    public String getListId() {
        return listId;
    }

    // ---- CRUD ----

    /**

     * 创建一个新任务并保留该列表。

     */
    public synchronized Task create(String subject, String description, String activeForm,
                                    Map<String, Object> metadata) {
        List<Task> tasks = load();

        Task task = new Task();
        task.id = generateId();
        task.subject = subject;
        task.description = description;
        task.activeForm = activeForm;
        task.status = Status.PENDING.value();
        task.blocks = new ArrayList<>();
        task.blockedBy = new ArrayList<>();
        task.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();

        tasks.add(task);
        save(tasks);
        return task;
    }

    /**

     * 按任务 ID 返回任务，如果未找到则返回空。

     */
    public synchronized Optional<Task> get(String id) {
        return load().stream()
                .filter(t -> t.id.equals(id))
                .findFirst();
    }

    /**

     * 列出所有可见（非内部）任务。

     */
    public synchronized List<Task> list() {
        List<Task> visible = new ArrayList<>();
        for (Task t : load()) {
            if (t.metadata != null && t.metadata.containsKey("_internal")) {
                continue;
            }
            visible.add(t);
        }
        return visible;
    }

    /**

     * 更新现有任务的字段。返回更新后的任务和列表

     * 已更改的字段名称，如果未找到任务则为空。

     *

     * @param id      要更新的任务ID

     * @param updates a 字段名称到新值的映射（与 Go 相同的键）

     * 实现：主题、描述、activeForm、状态、

     * 所有者、addBlocks、addBlockedBy、元数据）

     * @return a {@link UpdateResult} 包含任务和更改的字段，

     * 或 {@code Optional.empty()} 未找到任务时

     */
    @SuppressWarnings("unchecked")
    public synchronized Optional<UpdateResult> update(String id, Map<String, Object> updates) {
        List<Task> tasks = load();

        Task target = null;
        for (Task t : tasks) {
            if (t.id.equals(id)) {
                target = t;
                break;
            }
        }
        if (target == null) {
            return Optional.empty();
        }

        // 特殊情况：status == "deleted" 表示完全删除任务
        Object statusVal = updates.get("status");
        if (statusVal instanceof String s && "deleted".equals(s)) {
            tasks.removeIf(t -> t.id.equals(id));
            save(tasks);
            return Optional.of(new UpdateResult(target, List.of("deleted")));
        }

        List<String> changed = new ArrayList<>();

        if (updates.containsKey("subject")) {
            String v = asString(updates.get("subject"));
            if (v != null && !v.equals(target.subject)) {
                target.subject = v;
                changed.add("subject");
            }
        }
        if (updates.containsKey("description")) {
            String v = asString(updates.get("description"));
            if (v != null && !v.equals(target.description)) {
                target.description = v;
                changed.add("description");
            }
        }
        if (updates.containsKey("activeForm")) {
            String v = asString(updates.get("activeForm"));
            if (v != null && !v.equals(target.activeForm)) {
                target.activeForm = v;
                changed.add("activeForm");
            }
        }
        if (updates.containsKey("status")) {
            String v = asString(updates.get("status"));
            if (v != null && !v.equals(target.status)) {
                target.status = v;
                changed.add("status");
            }
        }
        if (updates.containsKey("owner")) {
            String v = asString(updates.get("owner"));
            if (v != null && !v.equals(target.owner)) {
                target.owner = v;
                changed.add("owner");
            }
        }
        if (updates.containsKey("addBlocks")) {
            List<String> ids = toStringList(updates.get("addBlocks"));
            if (!ids.isEmpty()) {
                var existing = new java.util.LinkedHashSet<>(target.blocks);
                for (String b : ids) {
                    existing.add(b);
                }
                target.blocks = new ArrayList<>(existing);
                changed.add("blocks");
            }
        }
        if (updates.containsKey("addBlockedBy")) {
            List<String> ids = toStringList(updates.get("addBlockedBy"));
            if (!ids.isEmpty()) {
                var existing = new java.util.LinkedHashSet<>(target.blockedBy);
                for (String b : ids) {
                    existing.add(b);
                }
                target.blockedBy = new ArrayList<>(existing);
                changed.add("blockedBy");
            }
        }
        if (updates.containsKey("metadata")) {
            Object raw = updates.get("metadata");
            if (raw instanceof Map<?, ?> m) {
                if (target.metadata == null) {
                    target.metadata = new LinkedHashMap<>();
                }
                for (var entry : m.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if (entry.getValue() == null) {
                        target.metadata.remove(key);
                    } else {
                        target.metadata.put(key, entry.getValue());
                    }
                }
                changed.add("metadata");
            }
        }

        if (!changed.isEmpty()) {
            save(tasks);
        }

        return Optional.of(new UpdateResult(target, changed));
    }

    /**

     * {@link #update} 调用的结果。

     */
    public record UpdateResult(Task task, List<String> changed) {}

    // ---- 坚持 ----

    private List<Task> load() {
        try {
            if (!Files.exists(storePath)) {
                return new ArrayList<>();
            }
            byte[] data = Files.readAllBytes(storePath);
            if (data.length == 0) {
                return new ArrayList<>();
            }
            return MAPPER.readValue(data, new TypeReference<List<Task>>() {});
        } catch (IOException e) {
            // 文件损坏或无法读取 — 重新开始
            return new ArrayList<>();
        }
    }

    private void save(List<Task> tasks) {
        try {
            Files.createDirectories(storePath.getParent());
            MAPPER.writeValue(storePath.toFile(), tasks);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save task list to " + storePath, e);
        }
    }

    // ---- helpers ----

    private static String generateId() {
        byte[] bytes = new byte[4];
        RNG.nextBytes(bytes);
        var sb = new StringBuilder("t");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String asString(Object v) {
        return v instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object v) {
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s);
                }
            }
            return result;
        }
        return List.of();
    }
}
