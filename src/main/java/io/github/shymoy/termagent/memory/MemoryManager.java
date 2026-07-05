
package io.github.shymoy.termagent.memory;

import io.github.shymoy.termagent.config.AppPaths;
import io.github.shymoy.termagent.conversation.ConversationManager;
import io.github.shymoy.termagent.conversation.Message;
import io.github.shymoy.termagent.llm.LlmClient;
import io.github.shymoy.termagent.llm.StreamEvent;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Stream;

/**
 * 记忆管理器，使用独立 .md 文件 + MEMORY.md 索引的统一存储格式。
 *
 * <p>存储结构：
 * <ul>
 *   <li>用户级 (~/.termagent/memory/)：存放 type=user / type=feedback 的记忆文件</li>
 *   <li>项目级 (.termagent/memory/)：存放 type=project / type=reference 的记忆文件</li>
 * </ul>
 *
 * <p>每条记忆是一个独立的 .md 文件，包含 YAML frontmatter（name, description, type）。
 * 每个目录下有一个 MEMORY.md 索引文件，用一行指针格式 `- [Title](file.md) — description`
 * 汇总该目录下的所有记忆。
 */
public class MemoryManager {

    /** MEMORY.md 索引文件名 */
    private static final String ENTRYPOINT_NAME = "MEMORY.md";
    private static final int EXTRACTION_INTERVAL = 5;
    // user/feedback 跟随用户；project/reference 跟随项目
    private static final Set<String> USER_TYPES = Set.of("user", "feedback");
    private static final Set<String> PROJECT_TYPES = Set.of("project", "reference");

    private final Path userMemDirPath;
    private final Path projectMemDirPath;
    private int turnCount;

    public MemoryManager(String workDir) {
        Path root = Path.of(workDir);
        this.projectMemDirPath = AppPaths.project(root, "memory");
        this.userMemDirPath = AppPaths.user("memory");
        AppPaths.migrateDirectory(AppPaths.legacyProject(root, "memory"), projectMemDirPath);
        AppPaths.migrateDirectory(AppPaths.legacyUser("memory"), userMemDirPath);
        // 确保目录存在，让 Agent 的 Write 工具可以直接写入
        ensureDir(userMemDirPath);
        ensureDir(projectMemDirPath);
    }

    // ---- 目录访问器（用于内存调用）----

    /** 返回用户级记忆目录（~/.termagent/memory/） */
    public Path userMemDir() {
        return userMemDirPath;
    }

    /** 返回项目级记忆目录（.termagent/memory/） */
    public Path projectMemDir() {
        return projectMemDirPath;
    }

    /** 返回项目级 MEMORY.md 的路径 */
    public Path entrypointPath() {
        return projectMemDirPath.resolve(ENTRYPOINT_NAME);
    }

    /** 返回用户级 MEMORY.md 的路径 */
    public Path userEntrypointPath() {
        return userMemDirPath.resolve(ENTRYPOINT_NAME);
    }

    // ---- Accessors ----

    /**
     * 返回所有记忆的摘要行，格式为 "[type] name — description"。
     * 扫描两个目录下的 .md 文件（不含 MEMORY.md），按文件名排序。
     */
    public List<String> getMemories() {
        var files = loadAll();
        var out = new ArrayList<String>();
        for (var f : files) {
            String typeTag = f.type().isEmpty() ? "?" : f.type();
            String desc = f.description().isEmpty() ? f.filename() : f.description();
            out.add("[%s] %s — %s".formatted(typeTag, f.name(), desc));
        }
        return out;
    }

    public boolean shouldExtract() {
        turnCount++;
        return turnCount % EXTRACTION_INTERVAL == 0;
    }

    /**
     * 清除两个目录下的所有 .md 文件（包括 MEMORY.md）。
     */
    public void clear() {
        clearDir(userMemDirPath);
        clearDir(projectMemDirPath);
    }

    // ---- 记忆文件记录 ----

    /** 一个记忆文件的元数据 */
    public record MemoryFile(String path, String filename, String name, String description, String type) {}

    /**
     * 扫描两个目录，加载所有记忆文件的 frontmatter 元数据。
     * 用户级在前，项目级在后。
     */
    List<MemoryFile> loadAll() {
        var out = new ArrayList<MemoryFile>();
        out.addAll(loadDir(userMemDirPath));
        out.addAll(loadDir(projectMemDirPath));
        return out;
    }

    private static List<MemoryFile> loadDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> mdFiles;
        try (Stream<Path> stream = Files.list(dir)) {
            mdFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".md") && !n.equals(ENTRYPOINT_NAME);
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }

        var out = new ArrayList<MemoryFile>();
        for (Path fp : mdFiles) {
            try {
                String content = Files.readString(fp);
                var fm = MemoryScanner.parseFrontmatter(content);
                String name = fm.name().isEmpty()
                        ? fp.getFileName().toString().replace(".md", "")
                        : fm.name();
                out.add(new MemoryFile(
                        fp.toAbsolutePath().toString(),
                        fp.getFileName().toString(),
                        name, fm.description(), fm.type()));
            } catch (IOException ignored) {
                // 跳过不可读的文件
            }
        }
        return out;
    }

    private static void clearDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().endsWith(".md"))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                  });
        } catch (IOException ignored) {}
    }

    // ---- 构建系统-提醒部分 ----

    /**
     * 构建记忆系统的 system-reminder 部分，包含 MEMORY.md 索引内容。
     * 确保两个目录都存在后读取各自的 MEMORY.md。
     */
    public String buildSystemReminder() {
        ensureDir(userMemDirPath);
        ensureDir(projectMemDirPath);

        var sb = new StringBuilder();
        sb.append("# auto memory\n\n");

        // 用户级 MEMORY.md
        appendEntrypoint(sb, "User-level", userMemDirPath);
        sb.append("\n\n");
        // 项目级 MEMORY.md
        appendEntrypoint(sb, "Project-level", projectMemDirPath);

        return sb.toString();
    }

    private static void appendEntrypoint(StringBuilder sb, String scopeLabel, Path memDir) {
        Path ep = memDir.resolve(ENTRYPOINT_NAME);
        sb.append("## %s %s (`%s`)\n\n".formatted(scopeLabel, ENTRYPOINT_NAME, ep));
        try {
            String content = Files.readString(ep).strip();
            if (!content.isEmpty()) {
                sb.append(content);
            } else {
                sb.append("This %s is currently empty.".formatted(ENTRYPOINT_NAME));
            }
        } catch (IOException e) {
            sb.append("This %s is currently empty.".formatted(ENTRYPOINT_NAME));
        }
    }

    // ---- 通过 LLM 提取 ----

    /**
     * 通过 LLM 从对话中提取记忆。提取结果写为独立 .md 文件并更新 MEMORY.md 索引。
     */
    public void extract(LlmClient client, ConversationManager conv) {
        List<Message> messages = conv.getMessages();
        if (messages.size() < 4) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            sb.append('[').append(msg.getRole()).append("]: ")
              .append(msg.getContent()).append('\n');
        }

        ConversationManager extractConv = new ConversationManager();
        extractConv.addUserMessage(
                "Extract key facts from this conversation worth remembering across future conversations. "
                        + "Classify each item into one of four types — the type decides which storage scope the item lives in:\n"
                        + "- `user` (user-level scope): the user's preferences, role, or background that applies across all projects\n"
                        + "- `feedback` (user-level scope): corrections the user gave or approaches the user validated\n"
                        + "- `project` (project-level scope): facts specific to the current project (tech stack, conventions, deadlines)\n"
                        + "- `reference` (project-level scope): external resources tied to this project (docs, dashboards)\n\n"
                        + "Format your output with these exact headers — skip a category if there is nothing worth saving for it:\n\n"
                        + "### user\n- item 1\n- item 2\n\n### feedback\n- item 3\n\n### project\n- item 4\n\n### reference\n- item 5\n\n"
                        + "Output nothing else (no preamble, no explanation). If nothing is worth remembering, output the four empty headers only.\n\n"
                        + "Conversation:\n"
                        + sb
        );

        BlockingQueue<StreamEvent> events = client.stream(extractConv, null);
        StringBuilder result = new StringBuilder();
        try {
            while (true) {
                StreamEvent event = events.take();
                if (event instanceof StreamEvent.TextDelta td) {
                    result.append(td.text());
                } else if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (result.isEmpty()) {
            return;
        }

        Map<String, String> bySection = parseTypedSections(result.toString());
        if (bySection.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> section : bySection.entrySet()) {
            String type = section.getKey();
            String content = section.getValue().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (!USER_TYPES.contains(type) && !PROJECT_TYPES.contains(type)) {
                continue;
            }
            // 选择目标目录：user/feedback 放用户级，project/reference 放项目级
            Path targetDir = USER_TYPES.contains(type) ? userMemDirPath : projectMemDirPath;
            writeMemoryFile(targetDir, type, content);
        }
    }

    /**
     * 将一条记忆写为独立的 .md 文件，并在 MEMORY.md 索引中追加指针。
     * 文件名基于类型和时间戳生成，确保唯一性。
     */
    private void writeMemoryFile(Path dir, String type, String content) {
        ensureDir(dir);
        // 生成文件名：type_timestamp.md
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(
                java.time.LocalDateTime.now());
        String filename = "%s_%s.md".formatted(type, ts);
        Path filePath = dir.resolve(filename);

        // 从内容的第一行提取简短描述
        String firstLine = content.lines().findFirst().orElse(content);
        if (firstLine.startsWith("- ")) {
            firstLine = firstLine.substring(2);
        }
        String description = firstLine.length() > 100 ? firstLine.substring(0, 97) + "..." : firstLine;
        String name = "%s_%s".formatted(type, ts);

        // 写入带 frontmatter 的 .md 文件
        String fileContent = """
                ---
                name: %s
                description: %s
                type: %s
                ---

                %s
                """.formatted(name, description, type, content);
        try {
            Files.writeString(filePath, fileContent);
        } catch (IOException e) {
            return;
        }

        // 更新 MEMORY.md 索引：追加一行指针
        Path entrypoint = dir.resolve(ENTRYPOINT_NAME);
        String pointer = "- [%s](%s) — %s\n".formatted(name, filename, description);
        try {
            Files.writeString(entrypoint, pointer,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // MEMORY.md 写入失败不影响记忆本身
        }
    }

    /**
     * 按 `### <type>` 分组解析 LLM 提取输出。
     * 大小写不敏感，归一化为小写。
     */
    static Map<String, String> parseTypedSections(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String currentType = null;
        StringBuilder buf = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("### ")) {
                if (currentType != null) {
                    String body = buf.toString().trim();
                    if (!body.isEmpty()) {
                        out.merge(currentType, body, (a, b) -> a + "\n" + b);
                    }
                }
                currentType = trimmed.substring(4).trim().toLowerCase(Locale.ROOT);
                buf.setLength(0);
            } else if (currentType != null) {
                buf.append(line).append('\n');
            }
        }
        if (currentType != null) {
            String body = buf.toString().trim();
            if (!body.isEmpty()) {
                out.merge(currentType, body, (a, b) -> a + "\n" + b);
            }
        }
        return out;
    }

    // ---- Injection ----

    /**
     * 向对话注入已有的记忆内容（MEMORY.md 索引）。
     */
    public void injectMemories(ConversationManager conv) {
        String reminder = buildSystemReminder();
        if (reminder.isBlank()) {
            return;
        }
        if (conv.getMessages().isEmpty()) {
            conv.addUserMessage(reminder);
            conv.addAssistantMessage("Understood, I'll keep this context in mind.");
        }
    }

    // ---- 定制说明 ----

    /**
     * 加载指令文件：支持用户级（~/.termagent/TERMAGENT.md）、项目级（git root 到 workDir 逐层）、
     * 兼容旧版 INSTRUCTIONS.md、私有 TERMAGENT.local.md，以及 @include 递归展开。
     * 委托给 {@link InstructionLoader} 实现完整的发现和展开逻辑。
     */
    public static String loadInstructions(String workDir) {
        return InstructionLoader.loadInstructions(workDir);
    }

    // ---- Helpers ----

    private static void ensureDir(Path dir) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {}
    }
}
