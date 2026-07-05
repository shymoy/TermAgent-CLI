
package io.github.shymoy.termagent.tool.impl;

import io.github.shymoy.termagent.tool.Tool;
import io.github.shymoy.termagent.tool.ToolCategory;
import io.github.shymoy.termagent.tool.ToolResult;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件模式搜索工具：从指定目录递归查找符合 glob 表达式的文件。
 * 该工具只读取文件元数据，属于只读类别，可以与其他只读工具并行执行。
 */
public class GlobTool implements Tool {

    // 遍历时跳过体积大或与源码检索无关的常见目录，避免无效扫描。
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".venv", "node_modules", "__pycache__", ".tox", ".mypy_cache"
    );

    private static final String DESCRIPTION = """
            Find files matching a glob pattern, returning relative paths sorted by modification time (most recent first).

            Usage notes:
            - Supports patterns like "**/*.py", "src/**/*.ts", "*.go".
            - Search from "." or a specific path, never from "/".
            - Automatically skips .git, node_modules, __pycache__, and similar directories.
            - Use this instead of find or ls commands via Bash.""";

    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    /** 定义提供给模型的搜索模式和起始目录参数，其中 pattern 为必填项。 */
    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "pattern", Map.of("type", "string", "description", "Glob pattern to match (e.g. '**/*.py')"),
                                "path", Map.of("type", "string", "description", "Base directory to search from", "default", ".")
                        ),
                        "required", List.of("pattern")
                )
        );
    }

    /** 执行目录遍历和模式匹配，并按文件修改时间从新到旧返回相对路径。 */
    @Override
    public ToolResult execute(Map<String, Object> args) {
        String pattern = stringArg(args, "pattern", "");
        String basePath = stringArg(args, "path", ".");
        if (basePath.isEmpty()) {
            basePath = ".";
        }
        if (pattern.isEmpty()) {
            return ToolResult.error("Error: pattern is required");
        }

        Path root = Path.of(basePath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return ToolResult.error("Error: path not found: " + basePath);
        }

        // 将模型传入的表达式编译成文件系统 PathMatcher，例如 **/*.java。
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        var matches = new ArrayList<String>();

        try {
            // walkFileTree 递归访问 root 下的目录和文件，由 visitor 决定继续或跳过。
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // SKIP_SUBTREE 表示不再进入当前目录及其任何子目录。
                    if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path rel = root.relativize(file);
                    // 兼容仅匹配文件名的模式（*.java）和包含目录的模式（src/**/*.java）。
                    if (matcher.matches(file.getFileName()) || matcher.matches(rel)) {
                        matches.add(rel.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // 单个文件无权访问或读取失败时跳过，继续搜索其他文件。
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return ToolResult.error("Error: " + e.getMessage());
        }

        // 按修改时间倒序，最近修改的排前面
        matches.sort((a, b) -> {
            try {
                long ma = Files.getLastModifiedTime(root.resolve(a)).toMillis();
                long mb = Files.getLastModifiedTime(root.resolve(b)).toMillis();
                return Long.compare(mb, ma);
            } catch (IOException e) {
                return a.compareTo(b);
            }
        });
        if (matches.isEmpty()) {
            // 没有匹配项是正常查询结果，不属于工具执行错误。
            return ToolResult.success("No files matched the pattern.");
        }
        return ToolResult.success(String.join("\n", matches));
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}
