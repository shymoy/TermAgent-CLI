
package com.mewcode.prompt;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 按顺序组装系统提示词的构建器。
 *
 * <p>每段提示词通过 priority 决定最终出现的位置；priority 越小越靠前。
 * 这里的优先级只表示拼接顺序，不负责解决不同指令之间的语义冲突。</p>
 */
public class PromptBuilder {

    // ── 内部类型──────────────────────────────────────────────────────

    /** 一段可独立排序的提示词。name 用于标识，当前不会输出到最终内容。 */
    public record Section(String name, int priority, String content) {}

    /** 构建提示词时需要暴露给模型的运行环境快照。 */
    public record EnvironmentContext(
            String workDir,
            String os,
            String arch,
            String shell,
            boolean isGitRepo,
            String gitBranch,
            String model,
            String date) {}

    /** 可按需追加到基础系统提示词后的动态内容。 */
    public record BuildOptions(
            String skillSection,
            String customInstructions,
            String memorySection) {}

    // ── 建造者状态────────────────────────────────────────────────────

    private final List<Section> sections = new ArrayList<>();

    public PromptBuilder add(Section section) {
        sections.add(section);
        return this;
    }

    public String build() {
        // 稳定地按数值从小到大排列，使基础规则始终位于动态上下文之前。
        sections.sort(Comparator.comparingInt(Section::priority));

        var parts = new ArrayList<String>();
        for (Section s : sections) {
            // 忽略空段并清理首尾空白，避免产生无意义的分隔行。
            String content = s.content() == null ? "" : s.content().strip();
            if (!content.isEmpty()) {
                parts.add(content);
            }
        }
        return String.join("\n\n", parts);
    }

    // ── 静态便捷方法──────────────────────────────────────

    /** 探测当前工作目录、平台和 Git 状态，生成一次性的运行环境快照。 */
    public static EnvironmentContext detectEnvironment(String model) {
        String workDir = System.getProperty("user.dir");
        String osName = System.getProperty("os.name", "unknown").toLowerCase();
        String arch = System.getProperty("os.arch", "unknown");
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isEmpty()) {
            shell = "bash";
        }

        boolean isGitRepo = false;
        String gitBranch = "";

        try {
            Process p = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--is-inside-work-tree")
                    .redirectErrorStream(true)
                    .start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if ("true".equals(line != null ? line.strip() : "")) {
                    isGitRepo = true;
                }
            }
            p.waitFor();
        } catch (Exception ignored) {
            // 不是 git 存储库或 git 不可用
        }

        if (isGitRepo) {
            try {
                Process p = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--abbrev-ref", "HEAD")
                        .redirectErrorStream(true)
                        .start();
                try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null) {
                        gitBranch = line.strip();
                    }
                }
                p.waitFor();
            } catch (Exception ignored) {
                // 分支检测失败
            }
        }

        String date = LocalDate.now().toString();
        return new EnvironmentContext(workDir, osName, arch, shell, isGitRepo, gitBranch, model, date);
    }

    /** 将固定行为规则、运行环境和可选动态内容组装成完整的系统提示词。 */
    public static String buildSystemPrompt(EnvironmentContext env, BuildOptions options) {
        var builder = new PromptBuilder();

        builder.add(PromptSections.identitySection());
        builder.add(PromptSections.systemSection());
        builder.add(PromptSections.doingTasksSection());
        builder.add(PromptSections.executingActionsSection());
        builder.add(PromptSections.usingToolsSection());
        builder.add(PromptSections.toneStyleSection());
        builder.add(PromptSections.outputEfficiencySection());
        builder.add(PromptSections.environmentSection(env));

        if (options.skillSection() != null && !options.skillSection().isEmpty()) {
            builder.add(new Section("Skills", 90, options.skillSection()));
        }

        // 用户自定义指令（CLAUDE.md 等），优先级 80
        if (options.customInstructions() != null && !options.customInstructions().isEmpty()) {
            builder.add(new Section("CustomInstructions", 80, options.customInstructions()));
        }

        // 持久记忆区（自动提取的记忆），优先级 85
        if (options.memorySection() != null && !options.memorySection().isEmpty()) {
            builder.add(new Section("Memory", 85, options.memorySection()));
        }

        return builder.build();
    }
}
