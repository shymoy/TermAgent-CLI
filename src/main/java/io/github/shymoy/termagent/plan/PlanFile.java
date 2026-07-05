
package io.github.shymoy.termagent.plan;

import io.github.shymoy.termagent.config.AppPaths;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**

 * 管理工作中存储在 {@code .termagent/plans/} 下的计划文件

 * 目录。

 * <p>

 * 计划 slug 是根据单词列表和时间戳生成的，并且

 * 单例计划路径在进程的生命周期内被缓存。

 */
public class PlanFile {

    private static final String[] ADJECTIVES = {
            "bright", "calm", "bold", "swift", "quiet",
            "vivid", "clear", "keen", "warm", "cool",
            "sharp", "light", "deep", "pure", "soft",
    };

    private static final String[] NOUNS = {
            "plan", "draft", "design", "sketch", "blueprint",
            "outline", "strategy", "approach", "scheme", "map",
            "vision", "path", "route", "guide", "frame",
    };

    private static String currentPlanPath;

    // ── 弹头生成──────────────────────────────────────────────────

    /**

     * 生成一个人类友好的 slug，例如 {@code bold-sketch-0515-1423}。

     * 使用当前纳秒时间戳对单词列表长度取模，

     * 匹配 Go 实现的选择逻辑。

     */
    public static String generateSlug() {
        long nanos = System.nanoTime();
        int ai = (int) ((nanos / 1000) % ADJECTIVES.length);
        int ni = (int) ((nanos / 100) % NOUNS.length);
        if (ai < 0) ai += ADJECTIVES.length;
        if (ni < 0) ni += NOUNS.length;
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("MMdd-HHmm"));
        return ADJECTIVES[ai] + "-" + NOUNS[ni] + "-" + timestamp;
    }

    // ── 路径管理──────────────────────────────────────────────────

    public static String getOrCreatePlanPath(String workDir) {
        if (currentPlanPath != null) {
            return currentPlanPath;
        }
        Path dir = AppPaths.project(Path.of(workDir), "plans");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // 尽最大努力
        }
        String slug = generateSlug();
        currentPlanPath = dir.resolve(slug + ".md").toString();
        return currentPlanPath;
    }

    public static String getPlanFilePath(String workDir) {
        if (currentPlanPath != null) {
            return currentPlanPath;
        }
        return getOrCreatePlanPath(workDir);
    }

    public static void setPlanFilePath(String path) {
        currentPlanPath = path;
    }

    public static void resetPlanPath() {
        currentPlanPath = null;
    }

    // ── 坚持──────────────────────────────────────────────────────

    public static boolean planExists() {
        return currentPlanPath != null && Files.exists(Path.of(currentPlanPath));
    }

    public static String loadPlan() throws IOException {
        if (currentPlanPath == null) {
            return "";
        }
        Path path = Path.of(currentPlanPath);
        if (!Files.exists(path)) {
            return "";
        }
        return Files.readString(path);
    }

    public static void savePlan(String workDir, String content) throws IOException {
        String path = getOrCreatePlanPath(workDir);
        Path target = Path.of(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    // ── Utilities ───────────────────────────────────────────────────────

    /**

     * 当 {@code targetPath} 引用同一文件时返回 {@code true}

     * 作为 {@code planPath} （标准化后）或当一个是后缀时

     * 另一个。这与 Go 助手相匹配

     * {@code IsPlanFilePath(targetPath, planPath)}。

     */
    public static boolean isPlanFilePath(String targetPath, String planPath) {
        if (planPath == null || planPath.isBlank()) {
            return false;
        }
        String cleanTarget = Path.of(targetPath).normalize().toString();
        String cleanPlan = Path.of(planPath).normalize().toString();
        return cleanTarget.equals(cleanPlan) || cleanTarget.endsWith(cleanPlan);
    }
}
