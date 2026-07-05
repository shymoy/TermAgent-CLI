
package io.github.shymoy.termagent.tool.impl;

import io.github.shymoy.termagent.tool.FileStateCache;
import io.github.shymoy.termagent.tool.Tool;
import io.github.shymoy.termagent.tool.ToolCategory;
import io.github.shymoy.termagent.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件完整写入工具：创建新文件或用给定内容覆盖已有文件。
 *
 * <p>该工具属于写入类别，因此 StreamingExecutor 会将它放入独立的串行批次。
 * 覆盖已有文件前还会执行“先读后写”检查，避免模型根据旧内容覆盖外部修改。</p>
 */
public class WriteFileTool implements Tool {

    // FileHistory 用于保留文件变更记录；FileStateCache 用于写入前的状态校验。
    private io.github.shymoy.termagent.filehistory.FileHistory fileHistory;
    private FileStateCache fileStateCache;

    public void setFileHistory(io.github.shymoy.termagent.filehistory.FileHistory fh) { this.fileHistory = fh; }
    public void setFileStateCache(FileStateCache c) { this.fileStateCache = c; }

    private static final String DESCRIPTION = """
            Write content to a file, creating parent directories if needed. Overwrites existing files.

            Usage notes:
            - If modifying an existing file, prefer EditFile over WriteFile — it only sends the diff.
            - Use this tool only to create new files or for complete rewrites.
            - You MUST read existing files with ReadFile before overwriting them.
            - NEVER create documentation files (*.md) or README files unless explicitly requested.""";

    @Override
    public String name() {
        return "WriteFile";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    /** 定义提供给模型的参数契约：目标路径和完整文件内容均为必填项。 */
    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "Path to the file to write"),
                                "content", Map.of("type", "string", "description", "Content to write to the file")
                        ),
                        "required", List.of("file_path", "content")
                )
        );
    }

    /**
     * 执行完整文件写入，包括参数校验、已有文件状态检查、父目录创建和缓存刷新。
     */
    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = stringArg(args, "file_path", "");
        String content = stringArg(args, "content", "");

        if (filePath.isEmpty()) {
            return ToolResult.error("Error: file_path is required");
        }

        if (fileHistory != null) fileHistory.trackEdit(filePath);

        Path path = Path.of(filePath);

        // 已有文件必须先通过 ReadFile 建立基线；新文件没有旧内容，因此跳过该检查。
        if (fileStateCache != null && Files.exists(path)) {
            String absPath = path.toAbsolutePath().toString();
            String err = fileStateCache.validate(absPath);
            if (err != null) return ToolResult.error(err);
        }

        // POSIX 文件系统支持显式设置 Unix 权限，其他文件系统只执行普通创建和写入。
        boolean posix = path.getFileSystem().supportedFileAttributeViews().contains("posix");

        // 父目录不存在时一并创建；POSIX 环境下新目录默认使用 755 权限。
        try {
            Path parent = path.getParent();
            if (parent != null) {
                if (posix) {
                    Set<PosixFilePermission> dirPerms = PosixFilePermissions.fromString("rwxr-xr-x");
                    Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(dirPerms));
                } else {
                    Files.createDirectories(parent);
                }
            }
        } catch (IOException e) {
            return ToolResult.error("Error creating directories: " + e.getMessage());
        }

        // writeString 会创建新文件或覆盖已有文件；POSIX 环境下最终设置为 644 权限。
        try {
            Files.writeString(path, content);
            if (posix) {
                Set<PosixFilePermission> filePerms = PosixFilePermissions.fromString("rw-r--r--");
                Files.setPosixFilePermissions(path, filePerms);
            }
        } catch (IOException e) {
            return ToolResult.error("Error writing file: " + e.getMessage());
        }

        // 写入成功后刷新内容和修改时间，作为下一次 EditFile/WriteFile 的校验基线。
        if (fileStateCache != null) {
            fileStateCache.update(path.toAbsolutePath().toString(), content);
        }

        return ToolResult.success("Successfully wrote to " + filePath);
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}
