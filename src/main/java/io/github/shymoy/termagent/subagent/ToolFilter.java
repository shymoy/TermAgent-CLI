
package io.github.shymoy.termagent.subagent;

import io.github.shymoy.termagent.tool.Tool;
import io.github.shymoy.termagent.tool.ToolRegistry;

import java.util.HashSet;
import java.util.Set;

/**

 * 过滤 {@link ToolRegistry} 以生成合适的受限注册表

 * 对于子代理。过滤层（按顺序应用）是：

 * <ul>

 * <li>第1层：MCP工具（前缀为"mcp__"）始终通过。</li>

 * <li>第 2 层：{@code ALWAYS_DISALLOWED} — 全局阻止的工具

 * （任务输出、退出计划模式、进入计划模式、代理、询问用户问题、

 * 任务停止、工作流).</li>

 * <li>第3层：如果代理是自定义代理，也阻止

 * {@code CUSTOM_AGENT_DISALLOWED}.</li>

 * <li>第4层：异步模式下，仅允许{@code ASYNC_ALLOWED}工具。

 * 但是，如果代理是进程中的队友，也允许 "Agent"

 * 和 {@code IN_PROCESS_TEAMMATE_ALLOWED} 工具。</li>

 * <li>第 5 层：按规范 {@code disallowedTools} 排除。</li>

 * <li>第 6 层：按规范 {@code tools} 白名单交集

 * （如果为 null/空或仅包含 "*"，则跳过）。</li>

 * </ul>

 */
public final class ToolFilter {

    /**

     * 任何子代理都无法使用的工具。

     */
    private static final Set<String> ALWAYS_DISALLOWED = Set.of(
            "TaskOutput", "ExitPlanMode", "EnterPlanMode",
            "Agent", "AskUserQuestion", "TaskStop", "Workflow"
    );

    /**

     * 阻止自定义代理使用的其他工具（与 Go 中的 ALWAYS_DISALLOWED 相同）。

     */
    private static final Set<String> CUSTOM_AGENT_DISALLOWED = Set.of(
            "TaskOutput", "ExitPlanMode", "EnterPlanMode",
            "Agent", "AskUserQuestion", "TaskStop", "Workflow"
    );

    /**

     * 允许用于异步（后台）子代理的工具。

     */
    private static final Set<String> ASYNC_ALLOWED = Set.of(
            "ReadFile", "WebSearch", "TodoWrite", "Grep", "WebFetch", "Glob",
            "Bash", "EditFile", "WriteFile", "NotebookEdit", "Skill", "LoadSkill",
            "SyntheticOutput", "ToolSearch", "EnterWorktree", "ExitWorktree"
    );

    /**

     * 当代理是进程中的队友时，允许使用额外的工具。

     */
    private static final Set<String> IN_PROCESS_TEAMMATE_ALLOWED = Set.of(
            "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "SendMessage",
            "CronCreate", "CronDelete", "CronList"
    );

    private ToolFilter() {}

    /**

     * 方便的重载，委托给完整的方法

     * {@code isAsync=false}、{@code isCustom=false}、{@code isInProcessTeammate=false}。

     */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec) {
        return filterForAgent(source, spec, false, false, false);
    }

    /**

     * 创建一个新的 {@link ToolRegistry}，仅包含以下工具

     * 允许使用给定的子代理规范，与 Go 参考相匹配

     * 实现的{@code FilterToolsForAgentEx}。

     *

     * @param source              要过滤的父注册表

     * @param spec                子代理规范，其不允许/允许的工具遵守

     * @param isAsync             如果 {@code true}，限制到异步允许列表

     * @param isCustom            如果{@code true}，也块{@code CUSTOM_AGENT_DISALLOWED}工具

     * @param isInProcessTeammate if {@code true}（和异步），另外允许 "Agent"

     * 和 {@code IN_PROCESS_TEAMMATE_ALLOWED} 工具

     * @return a 新过滤注册表

     */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec,
                                              boolean isAsync, boolean isCustom,
                                              boolean isInProcessTeammate) {
        Set<String> disallowed = new HashSet<>(spec.disallowedTools());

        boolean hasWhitelist = spec.tools() != null && !spec.tools().isEmpty()
                && !(spec.tools().size() == 1 && "*".equals(spec.tools().get(0)));
        Set<String> allowed = hasWhitelist ? new HashSet<>(spec.tools()) : Set.of();

        ToolRegistry filtered = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            String name = tool.name();

            // 第 1 层：MCP 工具始终通过
            if (isMcpTool(name)) {
                filtered.register(tool);
                continue;
            }

            // 第 2 层：全局阻止的工具
            if (ALWAYS_DISALLOWED.contains(name)) {
                continue;
            }

            // 第 3 层：自定义代理特定块
            if (isCustom && CUSTOM_AGENT_DISALLOWED.contains(name)) {
                continue;
            }

            // 第 4 层：在异步模式下，仅允许允许列出的工具
            if (isAsync) {
                boolean asyncAllowed = ASYNC_ALLOWED.contains(name);
                if (!asyncAllowed) {
                    // 即使在异步模式下，进程内的队友也可以获得额外的工具
                    if (isInProcessTeammate
                            && ("Agent".equals(name) || IN_PROCESS_TEAMMATE_ALLOWED.contains(name))) {
                        // 跌倒——允许
                    } else {
                        continue;
                    }
                }
            }

            // 第 5 层：按规范不允许的工具
            if (disallowed.contains(name)) {
                continue;
            }

            // 第 6 层：按规范白名单交集
            if (hasWhitelist && !allowed.contains(name)) {
                continue;
            }

            filtered.register(tool);
        }
        return filtered;
    }

    private static boolean isMcpTool(String name) {
        return name.startsWith("mcp__");
    }
}
