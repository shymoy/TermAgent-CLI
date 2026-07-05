
package com.mewcode.memory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**

 * 使用 YAML-ish frontmatter 扫描内存目录中的 .md 文件，

 * 返回按最新顺序排序的标头元数据。分享者

 * {@link MemoryRecall}（查询时召回）和任何未来的提取代理。

 */
public final class MemoryScanner {

    private MemoryScanner() {}

    /**

     * 最大记忆文件出现在选择器模型中。

     */
    public static final int MAX_MEMORY_FILES = 200;

    /**

     * frontmatter 解析需要读取多少行。

     */
    private static final int FRONTMATTER_MAX_LINES = 30;

    /**

     * MEMORY.md 是入口索引，而不是记忆文件本身。

     */
    private static final String ENTRYPOINT_NAME = "MEMORY.md";

    /**

     * YAML-ish frontmatter 块：以 `---` 开头，以 `---` 结尾。

     */
    private static final Pattern FRONTMATTER_RE =
            Pattern.compile("\\A---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);

    // ── 头记录──────────────────────────────────────────────────

    /**

     * 一份扫描的记忆文件的元数据。

     */
    public record MemoryHeader(
            String filename,    // path relative to memoryDir
            String filePath,    // absolute path
            String scope,       // "user" or "project"
            long mtimeMs,       // modification time, ms since epoch
            String description, // frontmatter description; "" if absent
            String type         // frontmatter type; "" if unrecognized
    ) {}

    // ── Scan ───────────────────────────────────────────────────────────

    /**

     * 遍历{@code memoryDir}的.md文件（不包括MEMORY.md），读取

     * frontmatter 来自每个，并返回一个按最新优先排序的标题列表，

     * 上限为 {@link #MAX_MEMORY_FILES}。

     *

     * @param memoryDir 要扫描的目录

     * @param scope     "user" 或 "project" — 螺纹连接到每个接头中

     * @return headers 按时间降序排序；如果缺少目录则为空列表

     */
    public static List<MemoryHeader> scanMemoryFiles(Path memoryDir, String scope) {
        if (memoryDir == null || !Files.isDirectory(memoryDir)) {
            return List.of();
        }
        List<Path> mdFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(memoryDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".md") && !name.equals(ENTRYPOINT_NAME);
                })
                .forEach(mdFiles::add);
        } catch (IOException e) {
            return List.of();
        }

        List<MemoryHeader> results = new ArrayList<>();
        for (Path fp : mdFiles) {
            MemoryHeader hdr = readMemoryHeader(fp, memoryDir, scope);
            if (hdr != null) {
                results.add(hdr);
            }
        }

        // 排序最新的在前。
        results.sort(Comparator.comparingLong(MemoryHeader::mtimeMs).reversed());
        if (results.size() > MAX_MEMORY_FILES) {
            results = new ArrayList<>(results.subList(0, MAX_MEMORY_FILES));
        }
        return results;
    }

    // ── 头解析──────────────────────────────────────────────────

    private static MemoryHeader readMemoryHeader(Path filePath, Path memoryDir, String scope) {
        long mtimeMs;
        try {
            mtimeMs = Files.getLastModifiedTime(filePath).toMillis();
        } catch (IOException e) {
            return null;
        }

        // 首先阅读 FRONTMATTER_MAX_LINES 进行 frontmatter 解析。
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            for (int i = 0; i < FRONTMATTER_MAX_LINES; i++) {
                String line = reader.readLine();
                if (line == null) break;
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return null;
        }

        Frontmatter fm = parseFrontmatter(sb.toString());
        String rel;
        try {
            rel = memoryDir.relativize(filePath).toString();
        } catch (IllegalArgumentException e) {
            rel = filePath.getFileName().toString();
        }

        return new MemoryHeader(rel, filePath.toAbsolutePath().toString(), scope,
                mtimeMs, fm.description(), fm.type());
    }

    // ── 前题──────────────────────────────────────────────────────

    record Frontmatter(String name, String description, String type) {}

    /**

     * 从 YAML-ish frontmatter 中提取名称/描述/类型。只有

     * 读取三个已知字段；其他一切都被忽略。文件不带

     * frontmatter 返回空字段。

     */
    static Frontmatter parseFrontmatter(String content) {
        Matcher m = FRONTMATTER_RE.matcher(content);
        if (!m.find()) {
            return new Frontmatter("", "", "");
        }
        String block = m.group(1);
        String name = "";
        String description = "";
        String type = "";
        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String val = line.substring(colon + 1).trim();
            // 剥离引号。
            if ((val.startsWith("\"") && val.endsWith("\""))
                    || (val.startsWith("'") && val.endsWith("'"))) {
                val = val.substring(1, val.length() - 1);
            }
            switch (key) {
                case "name" -> name = val;
                case "description" -> description = val;
                case "type" -> {
                    if (isValidType(val)) type = val;
                }
            }
        }
        return new Frontmatter(name, description, type);
    }

    private static final Set<String> VALID_TYPES =
            Set.of("user", "feedback", "project", "reference");

    private static boolean isValidType(String raw) {
        return VALID_TYPES.contains(raw);
    }

    // ── 清单格式────────────────────────────────────────────

    private static final DateTimeFormatter ISO_MS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /**

     * 将记忆元数据格式化为文本清单：每个文件一行

     * {@code [scope] [type] path (timestamp): description}。使用者

     * 调用选择器提示。

     */
    public static String formatMemoryManifest(List<MemoryHeader> memories) {
        if (memories.isEmpty()) return "";
        var sb = new StringBuilder();
        for (int i = 0; i < memories.size(); i++) {
            if (i > 0) sb.append('\n');
            var m = memories.get(i);
            String scope = (m.scope() != null && !m.scope().isEmpty())
                    ? "[" + m.scope() + "-scope] " : "";
            String tag = (m.type() != null && !m.type().isEmpty())
                    ? "[" + m.type() + "] " : "";
            String ts = ISO_MS.format(Instant.ofEpochMilli(m.mtimeMs()));
            String path = (m.filePath() != null && !m.filePath().isEmpty())
                    ? m.filePath() : m.filename();
            if (m.description() != null && !m.description().isEmpty()) {
                sb.append("- ").append(scope).append(tag).append(path)
                  .append(" (").append(ts).append("): ").append(m.description());
            } else {
                sb.append("- ").append(scope).append(tag).append(path)
                  .append(" (").append(ts).append(")");
            }
        }
        return sb.toString();
    }
}
