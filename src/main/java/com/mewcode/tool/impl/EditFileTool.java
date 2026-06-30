
package com.mewcode.tool.impl;

import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 精确替换文件内容的写工具。
 * 只有 old_string 在文件中恰好出现一次时才执行替换，避免误改多个位置。
 * 与 WriteFile 不同，本工具只修改已存在文件中的指定片段，不负责创建新文件。
 * 该工具属于写入类别，因此 StreamingExecutor 会将它放入独立的串行批次。
 */
public class EditFileTool implements Tool {

    // 保存修改前的文件版本，供会话快照和回退使用。
    private com.mewcode.filehistory.FileHistory fileHistory;
    // 执行“先读后改”校验，避免模型根据旧内容覆盖外部修改。
    private FileStateCache fileStateCache;

    public void setFileStateCache(FileStateCache c) { this.fileStateCache = c; }

    private static final String DESCRIPTION = """
            Replace an exact string in a file. The old_string must appear exactly once in the file.

            Usage notes:
            - You MUST read the file with ReadFile before editing. This tool will fail otherwise.
            - When editing text from ReadFile output, preserve the exact indentation (tabs/spaces) as shown.
            - ALWAYS prefer editing existing files over creating new ones.
            - The edit will FAIL if old_string is not unique in the file. Provide more surrounding context to make it unique.
            - Use the smallest old_string that is clearly unique — 2-4 adjacent lines is usually sufficient.
            - The new_string must be different from old_string.""";

    @Override
    public String name() {
        return "EditFile";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    /** 定义模型调用本工具时必须提供的文件路径、原文本和替换文本。 */
    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "Path to the file to edit"),
                                "old_string", Map.of("type", "string", "description", "The exact string to find and replace (must be unique in file)"),
                                "new_string", Map.of("type", "string", "description", "The replacement string")
                        ),
                        "required", List.of("file_path", "old_string", "new_string")
                )
        );
    }

    public void setFileHistory(com.mewcode.filehistory.FileHistory fh) { this.fileHistory = fh; }

    /**
     * 执行精确替换：校验文件状态，确认原文本唯一，写入新内容并刷新文件状态缓存。
     */
    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = stringArg(args, "file_path", "");
        String oldStr = stringArg(args, "old_string", "");
        String newStr = stringArg(args, "new_string", "");

        if (filePath.isEmpty()) {
            return ToolResult.error("Error: file_path is required");
        }

        // 在实际写入前保存当前文件版本，便于后续生成快照或恢复。
        if (fileHistory != null) fileHistory.trackEdit(filePath);

        Path path = Path.of(filePath);

        // 必须先通过 ReadFile 建立基线；读取后被外部修改过的文件也会被拒绝编辑。
        if (fileStateCache != null) {
            String absPath = path.toAbsolutePath().toString();
            String err = fileStateCache.validate(absPath);
            if (err != null) return ToolResult.error(err);
        }

        if (!Files.exists(path)) {
            return ToolResult.error("Error: file not found: " + filePath);
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return ToolResult.error("Error reading file: " + e.getMessage());
        }

        // old_string 必须唯一，否则无法确定模型真正想修改的位置。
        int count = countOccurrences(content, oldStr);
        if (count == 0) {
            return ToolResult.error("Error: old_string not found in file");
        }
        if (count > 1) {
            return ToolResult.error("Error: old_string found " + count + " times, must be unique");
        }

        // 前面已经确认 old_string 只出现一次，因此 replace 不会连带修改其他位置。
        String newContent = content.replace(oldStr, newStr);

        try {
            Files.writeString(path, newContent);
        } catch (IOException e) {
            return ToolResult.error("Error writing file: " + e.getMessage());
        }

        // 写入成功后刷新内容和修改时间，使后续编辑以当前版本为新基线。
        if (fileStateCache != null) {
            fileStateCache.update(path.toAbsolutePath().toString(), newContent);
        }

        return ToolResult.success("Successfully edited " + filePath);
    }

    /** 统计目标字符串的不重叠出现次数，用于保证替换位置唯一。 */
    private static int countOccurrences(String text, String sub) {
        if (sub.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}
