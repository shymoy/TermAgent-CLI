

package com.mewcode.teams;

import java.util.Set;

/**

 * 协调员模式将首席代理的工具限制为仅用于协调。

 * 当处于活动状态时，Lead 只能使用一组有限的工具。

 *

 * 四阶段工作流程：

 * 1. 研究：领导探索问题空间

 * 2. 综合：领导创建计划和任务分解

 * 3.执行：Lead催生队友执行任务

 * 4. 验证：领导验证结果并解决冲突

 */
public final class Coordinator {

    private Coordinator() {}

    public static final Set<String> ALLOWED_TOOLS = Set.of(
            "Agent",
            "SendMessage",
            "TaskCreate",
            "TaskGet",
            "TaskList",
            "TaskUpdate",
            "TeamCreate",
            "TeamDelete",
            "ReadFile",
            "Glob",
            "Grep",
            "Bash"
    );

    public static boolean isCoordinatorTool(String name) {
        return ALLOWED_TOOLS.contains(name);
    }
}
