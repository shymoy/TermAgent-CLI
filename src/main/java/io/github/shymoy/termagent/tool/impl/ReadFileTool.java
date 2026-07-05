
package io.github.shymoy.termagent.tool.impl;

import io.github.shymoy.termagent.tool.Tool;
import io.github.shymoy.termagent.tool.ToolCategory;
import io.github.shymoy.termagent.tool.ToolResult;

import io.github.shymoy.termagent.tool.FileStateCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 文件读取工具：根据模型提供的路径和行范围读取文件，并返回带行号的文本。
 * 该工具属于只读类别，因此同一批次中的多个 ReadFile 调用可以并行执行。
 */
public class ReadFileTool implements Tool {

    // 读取成功后记录文件状态，供后续 EditFile/WriteFile 做并发修改检查。
    private FileStateCache fileStateCache;

    public void setFileStateCache(FileStateCache c) { this.fileStateCache = c; }

    private static final String DESCRIPTION = """
            Read a file and return its contents with line numbers.

            Usage notes:
            - The file_path parameter should be an absolute path when possible.
            - By default reads up to 2000 lines from the beginning of the file.
            - Use offset and limit to read specific parts of large files. Only read what you need.
            - Results are returned with line numbers (1-based) for easy reference.
            - This tool can only read files, not directories. Use Glob to list directory contents.
            - Do NOT re-read a file you just edited to verify — EditFile would have errored if the change failed.""";

    @Override
    public String name() {
        return "ReadFile";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    /**
     * 定义提供给模型的参数契约。模型会根据该 schema 生成 file_path、offset 和 limit。
     */
    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "Absolute or relative path to the file to read"),
                                "offset", Map.of("type", "integer", "description", "Line offset to start reading from (0-based)", "default", 0),
                                "limit", Map.of("type", "integer", "description", "Maximum number of lines to read", "default", 2000)
                        ),
                        "required", List.of("file_path")
                )
        );
    }

    /**
     * 执行实际的文件读取。这里仍需校验模型传入的参数，不能只依赖 schema 描述。
     */
    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = stringArg(args, "file_path", "");
        if (filePath.isEmpty()) {
            return ToolResult.error("Error: file_path is required");
        }

        int offset = intArg(args, "offset", 0);
        int limit = intArg(args, "limit", 2000);

        Path path = Path.of(filePath);

        if (!Files.exists(path)) {
            return ToolResult.error("Error: file not found: " + filePath);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.error("Error: not a file: " + filePath);
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return ToolResult.error("Error reading file: " + e.getMessage());
        }

        String[] lines = content.split("\n", -1);

        if (offset >= lines.length) {
            return ToolResult.success("");
        }

        int end = offset + limit;
        if (end > lines.length) {
            end = lines.length;
        }

        // 保存本次读取时的内容和修改时间，让后续写入工具识别文件是否已被外部修改。
        if (fileStateCache != null) {
            try {
                long mtime = Files.getLastModifiedTime(path).toMillis();
                fileStateCache.record(path.toAbsolutePath().toString(), content, mtime);
            } catch (IOException ignored) {
                // 状态缓存是辅助校验，获取修改时间失败不应影响本次读取结果。
            }
        }

        // 只格式化请求范围内的内容，并使用从 1 开始的行号返回给模型。
        var sb = new StringBuilder();
        for (int i = offset; i < end; i++) {
            if (i > offset) {
                sb.append('\n');
            }
            sb.append(i + 1).append('\t').append(lines[i]);
        }

        return ToolResult.success(sb.toString());
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        var v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
