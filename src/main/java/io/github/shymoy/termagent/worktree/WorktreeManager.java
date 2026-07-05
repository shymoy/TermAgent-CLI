
package io.github.shymoy.termagent.worktree;

import io.github.shymoy.termagent.config.AppPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**

 * 管理 git 工作树以实现并行代理执行。

 * <p>

 * 每个工作树都有自己的分支和工作目录

 * {@code .termagent/worktrees/<branch>}。符号链接目录 (e.g.

 * node_modules）从主项目根链接，以便

 * 工作树共享重依赖树。

 */
public class WorktreeManager {

    public record WorktreeInfo(String path, String branch, Instant createdAt) {}

    private final String projectRoot;
    private final List<String> symlinkDirs;
    private final int staleCutoffHours;

    private final Map<String, WorktreeInfo> worktrees = new LinkedHashMap<>();

    public WorktreeManager(String projectRoot, List<String> symlinkDirs, int staleCutoffHours) {
        this.projectRoot = projectRoot;
        this.symlinkDirs = symlinkDirs != null ? symlinkDirs : List.of();
        this.staleCutoffHours = staleCutoffHours > 0 ? staleCutoffHours : 24;
    }

    public String getProjectRoot() { return projectRoot; }
    public List<String> getSymlinkDirs() { return symlinkDirs; }
    public int getStaleCutoffHours() { return staleCutoffHours; }

    /**

     * 为给定分支创建一个新的 git 工作树

     * {@code .termagent/worktrees/<branch>}。

     *

     * @param branch    新分支名称

     * @param targetDir 工作树目录的可选覆盖；当

     * {@code null}，默认为{@code .termagent/worktrees/<branch>}

     * @return metadata 关于创建的工作树

     */
    public synchronized WorktreeInfo create(String branch, Path targetDir) throws Exception {
        // 在执行任何 git 操作前校验分支名，防止路径穿越和非法字符
        SlugValidator.validate(branch);

        Path wtDir = targetDir != null
                ? targetDir
                : AppPaths.project(Path.of(projectRoot), "worktrees", branch);

        // -B（大写）重置已删除的工作树留下的任何孤立分支
        String output = runGit(projectRoot, "git", "worktree", "add", "-B", branch, wtDir.toString());

        // 创建后设置：设置、挂钩、符号链接、.worktreeinclude
        PostCreationSetup.perform(projectRoot, wtDir.toString(), symlinkDirs);

        var info = new WorktreeInfo(wtDir.toString(), branch, Instant.now());
        worktrees.put(branch, info);
        return info;
    }

    /**

     * 按分支名称删除工作树。

     */
    public synchronized void remove(String branch) throws Exception {
        WorktreeInfo info = worktrees.get(branch);
        if (info == null) {
            throw new IllegalArgumentException("worktree not found: " + branch);
        }

        runGit(projectRoot, "git", "worktree", "remove", info.path(), "--force");
        worktrees.remove(branch);
    }

    /**

     * 通过解析 {@code git worktree list --porcelain} 输出列出工作树。

     * <p>

     * 当瓷器解析没有产生结果时，回退到内存中的映射

     * （e.g。没有链接工作树的裸存储库）。

     */
    public synchronized List<WorktreeInfo> list() {
        try {
            String output = runGit(projectRoot, "git", "worktree", "list", "--porcelain");
            List<WorktreeInfo> result = parsePorcelain(output);
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception ignored) {
            // 落入内存映射
        }
        return new ArrayList<>(worktrees.values());
    }

    /**

     * 如果在内存中跟踪分支，则返回该分支的工作树信息。

     */
    public synchronized Optional<WorktreeInfo> get(String branch) {
        return Optional.ofNullable(worktrees.get(branch));
    }

    /**

     * 删除早于给定小时数的工作树。

     *

     * @param cutoffHours 最大年龄（以小时为单位）； {@code <= 0} 时使用配置的默认值

     * @return the  删除的工作树数量

     */
    public synchronized int cleanupStale(int cutoffHours) {
        int hours = cutoffHours > 0 ? cutoffHours : staleCutoffHours;
        Instant cutoff = Instant.now().minusSeconds((long) hours * 3600);
        int removed = 0;

        var it = worktrees.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            WorktreeInfo info = entry.getValue();
            if (info.createdAt().isBefore(cutoff)) {
                try {
                    runGit(projectRoot, "git", "worktree", "remove", info.path(), "--force");
                    it.remove();
                    removed++;
                } catch (Exception ignored) {
                    // 尽力清理
                }
            }
        }
        return removed;
    }

    /**

     * 删除所有跟踪的工作树（尽力而为）。

     */
    public synchronized void removeAll() {
        var it = worktrees.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            try {
                runGit(projectRoot, "git", "worktree", "remove", entry.getValue().path(), "--force");
            } catch (Exception ignored) {
                // 尽力而为
            }
            it.remove();
        }
    }

    /**

     * 通过 {@code git diff --stat} 检测工作树中未提交的更改。

     *

     * @param worktreePath 要检查的工作树的路径

     * @return the diff 统计输出，如果干净则为空字符串

     */
    public static String detectChanges(String worktreePath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--stat");
        pb.directory(Path.of(worktreePath).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git diff timed out in " + worktreePath);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git diff failed: " + output);
        }
        return output.strip();
    }

    // ---- 内部助手 ----

    private static String runGit(String workDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Path.of(workDir).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException(String.join(" ", command) + ": " + output);
        }
        return output;
    }

    /**

     * 解析 {@code git worktree list --porcelain} 的瓷器输出。

     * 每个块由空行分隔，并包含如下行：

     * <pre>

     * 工作树/路径/到/wt

     * HEAD abc123

     * 分支参考/头/分支名称

     * </pre>

     */
    private static List<WorktreeInfo> parsePorcelain(String output) {
        List<WorktreeInfo> result = new ArrayList<>();
        String currentPath = null;
        String currentBranch = null;

        for (String line : output.split("\n")) {
            if (line.startsWith("worktree ")) {
                currentPath = line.substring("worktree ".length()).strip();
            } else if (line.startsWith("branch ")) {
                String ref = line.substring("branch ".length()).strip();
                // refs/heads/我的分支 -> 我的分支
                if (ref.startsWith("refs/heads/")) {
                    currentBranch = ref.substring("refs/heads/".length());
                } else {
                    currentBranch = ref;
                }
            } else if (line.isBlank()) {
                if (currentPath != null && currentBranch != null) {
                    result.add(new WorktreeInfo(currentPath, currentBranch, Instant.now()));
                }
                currentPath = null;
                currentBranch = null;
            }
        }
        // 处理最后一个块（无尾随空白行）
        if (currentPath != null && currentBranch != null) {
            result.add(new WorktreeInfo(currentPath, currentBranch, Instant.now()));
        }
        return result;
    }
}
