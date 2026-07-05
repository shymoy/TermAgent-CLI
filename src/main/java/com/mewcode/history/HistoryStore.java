
package com.mewcode.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**

 * 将提示历史记录保留为 JSONL 文件（每行一个 JSON 对象）。

 *

 * <p>每条线的形状为 {@code {"text":"user input","ts":1234567890}}

 * 其中 {@code ts} 是 Unix 纪元秒。连续的重复条目是

 * 被抑制，并且存储充当循环缓冲区，上限为

 * {@value #MAX_ENTRIES} 条目。

 */
public class HistoryStore {

    private static final int MAX_ENTRIES = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;
    private final List<String> entries = new ArrayList<>();

    /**

     * 默认构造函数 - 将历史记录存储在 {@code ~/.mewcode/prompt_history.jsonl} 中。

     */
    public HistoryStore() {
        this(Path.of(System.getProperty("user.home"), ".mewcode", "prompt_history.jsonl"));
    }

    /**

     * 接受显式文件路径的可测试构造函数。

     */
    public HistoryStore(Path filePath) {
        this.filePath = filePath;
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    /**

     * 读取 JSONL 文件并填充内存条目列表。

     * 格式错误的行和带有空 {@code text} 字段的条目将被静默处理

     * 跳过，匹配 Go 行为。

     */
    public void load() {
        entries.clear();
        if (!Files.exists(filePath)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    var node = MAPPER.readTree(line);
                    var textNode = node.get("text");
                    if (textNode != null && textNode.isTextual()) {
                        String text = textNode.asText();
                        if (!text.isEmpty()) {
                            entries.add(text);
                        }
                    }
                } catch (Exception ignored) {
                    // 跳过格式错误的行
                }
            }
        } catch (IOException ignored) {
            // 文件不可读 — 从空历史记录开始
        }
    }

    // ------------------------------------------------------------------
    // Append
    // ------------------------------------------------------------------

    /**

     * 将新条目添加到历史记录中。

     *

     * <ul>

     * <li>如果 {@code text} 等于最后一个条目，则调用是无操作（重复数据删除）。</li>

     * <li>当列表超过{@value #MAX_ENTRIES}时，最旧的条目是

     * 从前面修剪。</li>

     * <li>整个文件被重写，每个追加都有新的时间戳。</li>

     * </ul>

     */
    public void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        // 删除连续相同条目的重复数据。
        if (!entries.isEmpty() && entries.getLast().equals(text)) {
            return;
        }

        entries.add(text);

        // 圆形缓冲装饰。
        if (entries.size() > MAX_ENTRIES) {
            int excess = entries.size() - MAX_ENTRIES;
            entries.subList(0, excess).clear();
        }

        writeToDisk();
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    /**

     * 返回当前条目的不可修改的快照。

     */
    public List<String> getEntries() {
        return List.copyOf(entries);
    }

    /**

     * 当前持有的条目数。

     */
    public int size() {
        return entries.size();
    }

    /**

     * 返回给定索引处的条目。

     */
    public String get(int index) {
        return entries.get(index);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**

     * 重写内存列表中的完整 JSONL 文件。

     */
    private void writeToDisk() {
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException e) {
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(filePath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long now = Instant.now().getEpochSecond();
            for (String t : entries) {
                var node = MAPPER.createObjectNode();
                node.put("text", t);
                node.put("ts", now);
                writer.write(MAPPER.writeValueAsString(node));
                writer.newLine();
            }
        } catch (IOException ignored) {
            // 尽力坚持
        }
    }
}
