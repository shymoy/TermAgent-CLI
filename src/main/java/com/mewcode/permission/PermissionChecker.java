// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.permission;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用层权限检查器。
 *
 * <p>模型发起工具调用后，本类会在工具真正执行前，按照固定顺序检查计划模式特例、
 * 安全与危险命令、受保护路径、YAML 权限规则、会话级授权和权限模式，最终返回
 * {@code ALLOW}、{@code DENY} 或 {@code ASK}。它负责回答“这次操作是否获准”，
 * OS 级沙箱则负责限制 Bash 命令真正能够影响的系统范围。</p>
 *
 * <p>注意：各检查步骤遇到匹配项会立即返回，因此代码中的先后顺序本身就是优先级。</p>
 */
public class PermissionChecker {

    /** 当前权限模式，可在运行期间切换。 */
    private PermissionMode mode;
    /** 项目根目录，也是文件工具默认允许访问的主要边界。 */
    private final Path projectRoot;

    /** 用户选择“始终允许”后生成的会话级精确匹配规则；进程退出后不会保留。 */
    private final Set<String> allowAlwaysRules = new java.util.HashSet<>();

    /** 从多级 YAML 文件加载的权限规则；追加本地规则后会动态刷新。 */
    private final ArrayList<PermissionRule> fileRules;
    /** 当前计划文件路径，由计划模式设置；预留给计划文件的精确权限判断。 */
    private String planFilePath;

    /** 沙箱保护路径列表：这些路径始终禁止写入，即使用户有写权限 */
    private final List<String> denyWrite;

    /** 沙箱模式开关：开启后命令类工具自动放行（由 OS 级沙箱保护） */
    private boolean sandboxEnabled;

    /**
     * 一条从 permissions.yaml 解析出的权限规则。
     *
     * @param toolName 规则针对的工具名称
     * @param pattern  用于匹配命令、文件路径或搜索模式的 glob 表达式
     * @param effect   匹配后的允许或拒绝效果
     */
    private record PermissionRule(String toolName, String pattern, RuleEffect effect) {
        boolean matches(String toolName, String content) {
            if (!this.toolName.equals(toolName)) {
                return false;
            }
            // 将规则表达式作为 glob，对工具调用中的命令、路径或搜索模式进行匹配。
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            try {
                return matcher.matches(Path.of(content));
            } catch (Exception e) {
                // content 无法转换为 Path 时退化为字符串精确匹配，避免解析异常中断 Agent。
                return content.equals(pattern);
            }
        }
    }

    /** YAML 规则命中后的效果。 */
    private enum RuleEffect {
        ALLOW, DENY
    }

    /** 计划模式下可以直接使用、不会触发常规权限询问的工具。 */
    private static final Set<String> PLAN_MODE_ALLOWED_TOOLS = Set.of(
            "Agent", "ToolSearch", "AskUserQuestion", "ExitPlanMode"
    );

    /**
     * 可自动放行的命令前缀白名单。
     * 只有不包含管道、重定向、命令替换等组合语法时，才会被 {@link #isSafeCommand(String)} 认定为安全。
     */
    private static final Set<String> SAFE_COMMANDS = Set.of(
            "ls", "dir", "pwd", "echo", "cat", "head", "tail", "wc",
            "find", "which", "whereis", "whoami", "hostname", "uname",
            "date", "cal", "uptime", "df", "du", "free", "env", "printenv",
            "file", "stat", "readlink", "realpath", "basename", "dirname",
            "sort", "uniq", "tr", "cut", "awk", "sed", "grep", "egrep", "fgrep",
            "diff", "comm", "tee", "xargs", "true", "false", "test",
            "git status", "git log", "git diff", "git show", "git branch",
            "git tag", "git remote", "git rev-parse", "git ls-files",
            "git blame", "git stash list", "go version", "go env",
            "node -v", "npm -v", "npx", "python --version", "pip list",
            "cargo --version", "rustc --version", "java -version", "java --version"
    );

    /** 明显危险的 Bash 命令模式；该列表只是快速拦截，不能替代 OS 级沙箱。 */
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("rm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+/\\s*$"),
            Pattern.compile("mkfs\\."),
            Pattern.compile("dd\\s+if=.*of=/dev/"),
            Pattern.compile("chmod\\s+-R\\s+777\\s+/"),
            Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),
            Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),
            Pattern.compile("wget\\s+.*\\|\\s*(ba)?sh"),
            Pattern.compile(">\\s*/dev/sd")
    );

    /** 不同工具中参与权限规则匹配的核心参数字段。 */
    private static final Map<String, String> CONTENT_FIELDS = Map.of(
            "Bash", "command",
            "ReadFile", "file_path",
            "WriteFile", "file_path",
            "EditFile", "file_path",
            "Glob", "pattern",
            "Grep", "pattern"
    );

    /** 默认受保护的路径：配置文件和权限文件不允许被 AI 写入 */
    private static final List<String> DEFAULT_DENY_WRITE = List.of(
            ".mewcode/config.yaml",
            ".mewcode/permissions.local.yaml",
            ".mewcode/skills/"
    );

    public PermissionChecker(PermissionMode mode, Path projectRoot) {
        this.mode = mode;
        this.projectRoot = projectRoot;
        this.fileRules = new ArrayList<>(loadRules());

        // 初始化 denyWrite 列表，将相对路径解析为绝对路径
        var resolvedDeny = new ArrayList<String>();
        if (projectRoot != null) {
            for (String rel : DEFAULT_DENY_WRITE) {
                resolvedDeny.add(projectRoot.resolve(rel).toAbsolutePath().normalize().toString());
            }
        }
        this.denyWrite = resolvedDeny;
        this.sandboxEnabled = false;
    }

    public PermissionMode getMode() { return mode; }
    public void setMode(PermissionMode mode) { this.mode = mode; }
    public void setPlanFilePath(String path) { this.planFilePath = path; }

    public boolean isSandboxEnabled() { return sandboxEnabled; }
    public void setSandboxEnabled(boolean enabled) { this.sandboxEnabled = enabled; }
    public List<String> getDenyWrite() { return Collections.unmodifiableList(denyWrite); }

    /**
     * 一次权限检查的结果。
     * {@code ASK} 由上层执行器转换成人机确认；{@code DENY} 会作为工具错误返回给模型。
     */
    public record CheckResult(PermissionMode.Decision decision, String reason) {
        public static CheckResult allow() { return new CheckResult(PermissionMode.Decision.ALLOW, ""); }

        public static CheckResult deny(String reason) { return new CheckResult(PermissionMode.Decision.DENY, reason); }
        public static CheckResult ask() { return new CheckResult(PermissionMode.Decision.ASK, ""); }
        public static CheckResult ask(String reason) { return new CheckResult(PermissionMode.Decision.ASK, reason); }
    }

    /**
     * 按照从特殊规则到通用策略的顺序检查一次工具调用。
     * 每层一旦返回结果，后续层便不再执行，因此不要随意调整这些检查的顺序。
     */
    public CheckResult check(Tool tool, Map<String, Object> args) {
        String toolName = tool.name();
        String content = extractContent(toolName, args);

        // 第 0 层：计划模式特例。允许规划相关工具，以及写入 .mewcode/plans/ 下的计划文件。
        if (mode == PermissionMode.PLAN) {
            if (PLAN_MODE_ALLOWED_TOOLS.contains(toolName)) {
                return CheckResult.allow();
            }
            if ("WriteFile".equals(toolName) || "EditFile".equals(toolName)) {
                String path = stringArg(args, "file_path", "");
                if (path.contains(".mewcode/plans/")) {
                    return CheckResult.allow();
                }
            }
        }

        // 第 1 层：安全命令自动放行，减少只读诊断命令频繁打断用户。
        if ("Bash".equals(toolName) && content != null && isSafeCommand(content)) {
            return CheckResult.allow();
        }

        // 第 2 层：危险命令硬拒绝。正则只能识别典型形式，仍需要 OS 沙箱作为最终边界。
        if ("Bash".equals(toolName) && content != null) {
            for (var pattern : DANGEROUS_PATTERNS) {
                if (pattern.matcher(content).find()) {
                    return CheckResult.deny("Dangerous command detected: " + pattern.pattern());
                }
            }
        }

        // 第 2b 层：受保护路径硬拒绝；即使权限模式允许写入，也不能修改这些敏感文件。
        if (content != null && isWritePathTool(toolName) && isDeniedPath(content)) {
            return CheckResult.deny("Path is protected by sandbox: " + content);
        }

        // 第 3 层：文件工具访问项目目录和 /tmp 之外的路径时，交给用户确认。
        if (content != null && isPathTool(toolName)) {
            if (!isPathAllowed(content) && mode != PermissionMode.BYPASS) {
                return CheckResult.ask("Path outside allowed sandbox: " + content);
            }
        }

        // 第 4 层：匹配 YAML 权限规则。逆序遍历实现“最后一条匹配规则生效”。
        if (content != null) {
            for (int i = fileRules.size() - 1; i >= 0; i--) {
                PermissionRule rule = fileRules.get(i);
                if (rule.matches(toolName, content)) {
                    return switch (rule.effect) {
                        case ALLOW -> CheckResult.allow();
                        case DENY -> CheckResult.deny("Denied by rule: " + rule.toolName + "(" + rule.pattern + ")");
                    };
                }
            }
        }

        // 第 4b 层：匹配用户在当前会话中选择的“始终允许”规则。
        if (allowAlwaysRules.contains(toolName + ":" + content)) {
            return CheckResult.allow();
        }

        // 第 4c 层：开启沙箱自动放行后，命令类工具不再逐条询问，由 OS 沙箱限制实际能力。
        if (sandboxEnabled && tool.category() == ToolCategory.COMMAND) {
            return CheckResult.allow();
        }

        // 第 5 层：前面均未命中时，使用权限模式与工具类别矩阵给出兜底决策。
        var decision = mode.decide(tool.category());
        return switch (decision) {
            case ALLOW -> CheckResult.allow();
            case DENY -> CheckResult.deny("Denied by permission mode: " + mode);
            case ASK -> CheckResult.ask();
        };
    }

    public void addAllowAlwaysRule(String toolName, String content) {
        // 使用“工具名 + 核心参数”精确记录，只在当前 PermissionChecker 实例生命周期内有效。
        allowAlwaysRules.add(toolName + ":" + content);
    }

    // --- YAML 权限规则加载与持久化 ---

    private static final Pattern RULE_PATTERN = Pattern.compile("^(\\w+)\\((.+)\\)$");

    /**
     * 按“用户级 → 项目级 → 本地级”的顺序加载权限规则：
     * <ul>
     *   <li>用户级：{@code ~/.mewcode/permissions.yaml}</li>
     *   <li>项目级：{@code {projectRoot}/.mewcode/permissions.yaml}</li>
     *   <li>本地级：{@code {projectRoot}/.mewcode/permissions.local.yaml}</li>
     * </ul>
     * 检查时会逆序遍历，因此本地级规则优先于项目级，项目级优先于用户级；
     * 同一文件内也是靠后的匹配规则优先。
     */
    private List<PermissionRule> loadRules() {
        var rules = new ArrayList<PermissionRule>();

        // 用户级规则：对当前用户的所有项目生效。
        Path userHome = Path.of(System.getProperty("user.home"));
        Path userFile = userHome.resolve(".mewcode").resolve("permissions.yaml");
        rules.addAll(loadRulesFile(userFile));

        // 项目级规则：通常可以提交到版本库，供项目成员共享。
        if (projectRoot != null) {
            Path projectFile = projectRoot.resolve(".mewcode").resolve("permissions.yaml");
            rules.addAll(loadRulesFile(projectFile));

            // 本地级规则：通常被 Git 忽略，只在当前项目和当前机器上持久生效。
            Path localFile = projectRoot.resolve(".mewcode").resolve("permissions.local.yaml");
            rules.addAll(loadRulesFile(localFile));
        }

        return new ArrayList<>(rules);
    }

    public void appendLocalRule(String toolName, String pattern) {
        if (projectRoot == null) return;
        Path localFile = projectRoot.resolve(".mewcode").resolve("permissions.local.yaml");
        try {
            Files.createDirectories(localFile.getParent());
            var rules = new ArrayList<>(loadRulesFile(localFile));
            rules.add(new PermissionRule(toolName, pattern, RuleEffect.ALLOW));

            var entries = new ArrayList<Map<String, String>>();
            for (var r : rules) {
                entries.add(Map.of("rule", r.toolName + "(" + r.pattern + ")", "effect",
                        r.effect == RuleEffect.ALLOW ? "allow" : "deny"));
            }
            var yaml = new Yaml();
            Files.writeString(localFile, yaml.dump(entries));
            // 写入成功后重新加载三级规则，使新增规则立即生效。
            fileRules.clear();
            fileRules.addAll(loadRules());
        } catch (IOException ignored) {}
    }

    /**
     * 解析单个 YAML 权限文件。文件内容应为包含 {@code rule} 和 {@code effect} 的列表，例如：
     * <pre>
     * - rule: "Bash(git *)"
     *   effect: allow
     * - rule: "WriteFile(/etc/*)"
     *   effect: deny
     * </pre>
     * 文件不存在、读取失败、YAML 非法或条目格式错误时会忽略对应内容。
     */
    @SuppressWarnings("unchecked")
    private List<PermissionRule> loadRulesFile(Path path) {
        if (!Files.exists(path)) {
            return List.of();
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return List.of();
        }

        Yaml yaml = new Yaml();
        Object parsed;
        try {
            parsed = yaml.load(content);
        } catch (Exception e) {
            return List.of();
        }

        if (!(parsed instanceof List<?> entries)) {
            return List.of();
        }

        var rules = new ArrayList<PermissionRule>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object ruleObj = map.get("rule");
            Object effectObj = map.get("effect");
            if (!(ruleObj instanceof String ruleStr) || !(effectObj instanceof String effectStr)) {
                continue;
            }

            RuleEffect effect;
            if ("allow".equals(effectStr)) {
                effect = RuleEffect.ALLOW;
            } else if ("deny".equals(effectStr)) {
                effect = RuleEffect.DENY;
            } else {
                continue;
            }

            Matcher m = RULE_PATTERN.matcher(ruleStr.trim());
            if (!m.matches()) {
                continue;
            }
            rules.add(new PermissionRule(m.group(1), m.group(2), effect));
        }
        return rules;
    }

    private boolean isSafeCommand(String command) {
        String trimmed = command.trim();
        // 出现命令组合或重定向语法后，不再把它当作单一安全命令自动放行。
        if (trimmed.contains("|") || trimmed.contains(";") || trimmed.contains("&&")
                || trimmed.contains(">") || trimmed.contains("$(") || trimmed.contains("`")) {
            return false;
        }
        for (var safe : SAFE_COMMANDS) {
            // 既允许无参数命令，也允许白名单命令后跟普通参数。
            if (trimmed.equals(safe) || trimmed.startsWith(safe + " ")) {
                return true;
            }
        }
        return false;
    }

    private boolean isPathTool(String toolName) {
        return "ReadFile".equals(toolName) || "WriteFile".equals(toolName) || "EditFile".equals(toolName);
    }

    /** 写入类工具（WriteFile、EditFile），用于 denyWrite 检查 */
    private boolean isWritePathTool(String toolName) {
        return "WriteFile".equals(toolName) || "EditFile".equals(toolName);
    }

    /**
     * 检查路径是否在 denyWrite 保护列表中。
     * 如果目标路径以任何 denyWrite 条目为前缀，则禁止写入。
     */
    private boolean isDeniedPath(String pathStr) {
        try {
            String normalized = Path.of(pathStr).toAbsolutePath().normalize().toString();
            for (String deny : denyWrite) {
                if (normalized.startsWith(deny)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean isPathAllowed(String pathStr) {
        try {
            // 这里只做词法规范化，不会解析符号链接；项目目录与 /tmp 是默认允许边界。
            Path p = Path.of(pathStr).toAbsolutePath().normalize();
            Path root = projectRoot.toAbsolutePath().normalize();
            Path tmp = Path.of("/tmp").toAbsolutePath().normalize();
            return p.startsWith(root) || p.startsWith(tmp);
        } catch (Exception e) {
            // 保持当前实现的 fail-open 行为：路径无法解析时交给后续规则继续判断。
            return true;
        }
    }

    /** 提取工具调用中用于安全检查和规则匹配的核心参数。 */
    private static String extractContent(String toolName, Map<String, Object> args) {
        String field = CONTENT_FIELDS.get(toolName);
        if (field == null) return null;
        var v = args.get(field);
        return v instanceof String s ? s : null;
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    /**
     * 生成人机确认对话框中的操作摘要，避免用户直接阅读完整参数对象。
     */
    public String describeToolAction(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "Bash" -> "Execute: " + stringArg(args, "command", "");
            case "ReadFile" -> "Read: " + stringArg(args, "file_path", "");
            case "WriteFile" -> "Write: " + stringArg(args, "file_path", "");
            case "EditFile" -> "Edit: " + stringArg(args, "file_path", "");
            case "Glob" -> "Glob: " + stringArg(args, "pattern", "");
            case "Grep" -> "Grep: " + stringArg(args, "pattern", "");
            case "Agent" -> {
                String desc = stringArg(args, "description", "");
                String prompt = stringArg(args, "prompt", "");
                if (!desc.isEmpty()) {
                    yield "Agent: " + desc;
                } else if (!prompt.isEmpty()) {
                    yield "Agent: " + (prompt.length() > 80 ? prompt.substring(0, 77) + "..." : prompt);
                } else {
                    yield "Agent";
                }
            }
            default -> {
                var parts = new java.util.ArrayList<String>();
                for (var entry : args.entrySet()) {
                    String s = String.valueOf(entry.getValue());
                    if (s.length() > 80) s = s.substring(0, 77) + "...";
                    parts.add(entry.getKey() + "=" + s);
                }
                yield parts.isEmpty() ? toolName : String.join(", ", parts);
            }
        };
    }
}
