
package io.github.shymoy.termagent.worktree;

import io.github.shymoy.termagent.config.AppPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**

 * 删除陈旧的代理/工作流工作树。

 */
public final class StaleCleanup {

    private static final Logger log = Logger.getLogger(StaleCleanup.class.getName());

    private static final List<Pattern> EPHEMERAL_PATTERNS = List.of(
            Pattern.compile("^agent-a[0-9a-f]{7}$"),
            Pattern.compile("^wf_[0-9a-f]{8}-[0-9a-f]{3}-\\d+$"),
            Pattern.compile("^wf-\\d+$"),
            Pattern.compile("^bridge-[A-Za-z0-9_]+(-[A-Za-z0-9_]+)*$"),
            Pattern.compile("^job-[a-zA-Z0-9._-]{1,55}-[0-9a-f]{8}$")
    );

    private StaleCleanup() {}

    static boolean isEphemeral(String slug) {
        return EPHEMERAL_PATTERNS.stream().anyMatch(p -> p.matcher(slug).matches());
    }

    /**

     * 扫描工作树目录并删除陈旧的临时工作树

     * 早于截止日期。三层安全过滤器。

     */
    public static int cleanup(String repoRoot, Instant cutoff) {
        Path dir = AppPaths.project(Path.of(repoRoot), "worktrees");
        if (!Files.isDirectory(dir)) return 0;

        String currentPath = null;
        var session = WorktreeSessionStore.getCurrentSession();
        if (session != null) currentPath = session.worktreePath();

        int removed = 0;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                String slug = entry.getFileName().toString();

                // 第 1 层：仅短暂模式
                if (!isEphemeral(slug)) continue;

                String wtPath = entry.toString();
                if (wtPath.equals(currentPath)) continue;

                // 第二层：年龄检查
                try {
                    var attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toInstant().isAfter(cutoff)) continue;
                } catch (IOException e) {
                    continue;
                }

                // 第 3 层：失败关闭更改检查（-uno 跳过未跟踪的）
                String statusOut = runGitQuiet(wtPath, "--no-optional-locks", "status", "--porcelain", "-uno");
                if (statusOut == null || !statusOut.isBlank()) continue;

                String unpushedOut = runGitQuiet(wtPath, "rev-list", "--max-count=1", "HEAD", "--not", "--remotes");
                if (unpushedOut == null || !unpushedOut.isBlank()) continue;

                String branch = SlugValidator.branchName(slug);
                if (AgentWorktree.remove(wtPath, branch, repoRoot)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            log.fine("Failed to list worktrees directory: " + e.getMessage());
        }

        if (removed > 0) {
            runGitQuiet(repoRoot, "worktree", "prune");
        }
        return removed;
    }

    /**

     * 使用 ScheduledExecutorService 启动后台清理循环。

     */
    public static void startCleanupLoop(
            ScheduledExecutorService executor,
            String repoRoot,
            int intervalSeconds,
            int cutoffHours
    ) {
        if (intervalSeconds <= 0) return;
        executor.scheduleAtFixedRate(() -> {
            try {
                Instant cutoff = Instant.now().minusSeconds((long) cutoffHours * 3600);
                int removed = cleanup(repoRoot, cutoff);
                if (removed > 0) {
                    log.fine("Cleaned up " + removed + " stale worktree(s)");
                }
            } catch (Exception e) {
                log.fine("Stale cleanup error: " + e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private static String runGitQuiet(String cwd, String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(Path.of(cwd).toFile());
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_ASKPASS", "");
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return null;
            }
            return proc.exitValue() == 0 ? stdout : null;
        } catch (Exception e) {
            return null;
        }
    }
}
