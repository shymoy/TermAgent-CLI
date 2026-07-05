
package io.github.shymoy.termagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**

 * 查询时内存调用：扫描用户级和项目级内存

 * 目录，要求选择器 LLM 选取最多 5 个相关文件名，

 * 并返回相应的路径+ mtimes，以便调用者可以读取

 * 完整内容并将其作为系统提醒注入。

 */
public final class MemoryRecall {

    private MemoryRecall() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 选择器系统提示──────────────────────────────────────────

    public static final String SELECTOR_SYSTEM_PROMPT = """
            You are selecting memories that will be useful to TermAgent-CLI as it processes a user's query. \
            You will be given the user's query and a list of available memory files with their filenames and descriptions.

            Return a list of filenames for the memories that will clearly be useful to TermAgent-CLI as it \
            processes the user's query (up to 5). Only include memories that you are certain will be \
            helpful based on their name and description.
            - If you are unsure if a memory will be useful in processing the user's query, then do not \
            include it in your list. Be selective and discerning.
            - If there are no memories in the list that would clearly be useful, feel free to return an empty list.
            - If a list of recently-used tools is provided, do not select memories that are usage reference \
            or API documentation for those tools (TermAgent-CLI is already exercising them). DO still select \
            memories containing warnings, gotchas, or known issues about those tools — active use is exactly \
            when those matter.

            Respond with valid JSON only, no markdown, in this exact shape: \
            {"selected_memories": ["filename1.md", "filename2.md"]}""";

    // ── 结果记录──────────────────────────────────────────────────

    /**

     * 选择一个记忆文件来显示到主要对话中。

     * {@code mtimeMs} 是线程化的，因此调用者可以呈现新鲜感

     * 没有第二个统计数据。

     */
    public record RelevantMemory(String path, long mtimeMs) {}

    // ── 选择器功能界面────────────────────────────────────

    /**

     * 召回选择器使用的辅助查询 LLM 调用的抽象。

     * 给定系统提示和用户消息，呼叫者起立

     * 专用的侧面查询客户端并返回原始助手文本。

     * 错误被视为 "selector failed -> no recall"。

     */
    @FunctionalInterface
    public interface SelectorFn {
        String select(String systemPrompt, String userMessage) throws Exception;
    }

    // ── 主要入口────────────────────────────────────────────────

    /**

     * 扫描两个目录，过滤已经出现的路径，询问

     * 选择器选取最多 5 个相关文件名，并返回

     * 对应的绝对路径+m次。

     *

     * @param query            用户的查询文本

     * @param userMemDir       用户级内存目录（可以为空）

     * @param projectMemDir    项目级内存目录（可能为空）

     * @param recentTools      最近使用的工具名称（可以为空）

     * 先前回合中显示的 @param alreadySurfaced   路径（可能为空）

     * @param selector         侧查询功能

     * @return selected 记忆；任何失败时的空列表

     */
    public static List<RelevantMemory> findRelevantMemories(
            String query,
            Path userMemDir,
            Path projectMemDir,
            List<String> recentTools,
            Set<String> alreadySurfaced,
            SelectorFn selector) {

        if (selector == null) return List.of();

        List<MemoryScanner.MemoryHeader> all = new ArrayList<>();
        if (userMemDir != null) {
            all.addAll(MemoryScanner.scanMemoryFiles(userMemDir, "user"));
        }
        if (projectMemDir != null) {
            all.addAll(MemoryScanner.scanMemoryFiles(projectMemDir, "project"));
        }

        // 过滤已经浮现的记忆。
        Set<String> surfaced = alreadySurfaced != null ? alreadySurfaced : Set.of();
        List<MemoryScanner.MemoryHeader> candidates = new ArrayList<>();
        for (var m : all) {
            if (!surfaced.contains(m.filePath())) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) return List.of();

        List<String> selectedFilenames = selectRelevantMemories(
                query, candidates, recentTools, selector);

        // 构建从 filePath 和文件名到标头的查找。
        Map<String, MemoryScanner.MemoryHeader> byKey = new HashMap<>();
        for (var m : candidates) {
            byKey.put(m.filePath(), m);
            byKey.putIfAbsent(m.filename(), m);
        }

        List<RelevantMemory> result = new ArrayList<>();
        for (String fn : selectedFilenames) {
            var m = byKey.get(fn);
            if (m != null) {
                result.add(new RelevantMemory(m.filePath(), m.mtimeMs()));
            }
        }
        return result;
    }

    // ── 选择器逻辑──────────────────────────────────────────────────

    private static List<String> selectRelevantMemories(
            String query,
            List<MemoryScanner.MemoryHeader> memories,
            List<String> recentTools,
            SelectorFn selector) {

        Set<String> validFilenames = new HashSet<>();
        for (var m : memories) {
            validFilenames.add(m.filename());
        }

        String manifest = MemoryScanner.formatMemoryManifest(memories);

        String toolsSection = "";
        if (recentTools != null && !recentTools.isEmpty()) {
            toolsSection = "\n\nRecently used tools: " + String.join(", ", recentTools);
        }

        String userMessage = "Query: " + query + "\n\nAvailable memories:\n" + manifest + toolsSection;

        String raw;
        try {
            raw = selector.select(SELECTOR_SYSTEM_PROMPT, userMessage);
        } catch (Exception e) {
            return List.of();
        }

        String clean = extractJsonObject(raw);
        if (clean.isEmpty()) return List.of();

        try {
            JsonNode root = MAPPER.readTree(clean);
            JsonNode arr = root.get("selected_memories");
            if (arr == null || !arr.isArray()) return List.of();

            List<String> out = new ArrayList<>();
            for (JsonNode node : arr) {
                String f = node.asText();
                if (validFilenames.contains(f)) {
                    out.add(f);
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**

     * 返回在 raw 中找到的第一个 {@code {...}} 子字符串，或者 raw

     * 如果文本已经以“{”开头，则文本被修剪。容忍降价

     * 尽管有提示，但仍围绕 JSON 进行栅栏或散文。

     */
    static String extractJsonObject(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) return trimmed;
        int start = trimmed.indexOf('{');
        if (start < 0) return "";
        int end = trimmed.lastIndexOf('}');
        if (end < start) return "";
        return trimmed.substring(start, end + 1);
    }

    // ── 提醒渲染──────────────────────────────────────────────

    /**

     * 读取每个选定记忆文件的完整内容并格式化单个文件

     * 带有新鲜度标题的系统提醒正文。

     *

     * @param memories 从{@link #findRelevantMemories}中选择的存储器

     * @return rendered  提醒文本，如果没有，则为 ""

     */
    public static String renderReminder(List<RelevantMemory> memories) {
        if (memories == null || memories.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("The following relevant memories from prior conversations may help:\n\n");
        for (var mem : memories) {
            String content;
            try {
                content = Files.readString(Path.of(mem.path()));
            } catch (IOException e) {
                continue; // skip unreadable files
            }
            String basename = Path.of(mem.path()).getFileName().toString();
            sb.append("## Memory: ").append(basename)
              .append(" (saved ").append(MemoryAge.age(mem.mtimeMs())).append(")\n\n");
            String note = MemoryAge.freshnessText(mem.mtimeMs());
            if (!note.isEmpty()) {
                sb.append(note).append("\n\n");
            }
            sb.append(content).append("\n\n---\n\n");
        }
        return sb.toString();
    }
}
