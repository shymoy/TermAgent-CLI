
package com.mewcode.prompt;

/**
 * 生成计划模式下动态注入会话的提醒文本。
 *
 * <p>计划模式只允许读取项目和修改指定计划文件。这里通过完整提醒、精简提醒、
 * 再次进入提醒和退出提醒，让模型在多轮工具调用中持续感知当前权限边界与工作流。</p>
 */
public final class PlanModePrompt {

    /** 控制完整提醒与精简提醒的轮次切换。 */
    private static final int REMINDER_INTERVAL = 5;

    /** 首轮及周期性注入的完整规则，包含权限边界和五阶段计划流程。 */
    private static final String PLAN_MODE_FULL_REMINDER = """
            Plan mode is active. The user indicated that they do not want you to execute yet -- you \
            MUST NOT make any edits (with the exception of the plan file mentioned below), run any \
            non-readonly tools (including changing configs or making commits), or otherwise make any \
            changes to the system. This supercedes any other instructions you have received.

            ## Plan File Info:
            %s
            You should build your plan incrementally by writing to or editing this file. NOTE that this \
            is the only file you are allowed to edit - other than this you are only allowed to take \
            READ-ONLY actions.

            ## Plan Workflow

            ### Phase 1: Initial Understanding
            Goal: Gain a comprehensive understanding of the user's request by reading through code and \
            asking them questions. Critical: In this phase you should use the Agent tool with \
            subagent_type="explore".

            1. Focus on understanding the user's request and the code associated with their request. \
            Actively search for existing functions, utilities, and patterns that can be reused -- avoid \
            proposing new code when suitable implementations already exist.

            2. **Call the Agent tool with subagent_type="explore" to explore the codebase.** You can \
            launch up to 3 explore agents IN PARALLEL by making multiple Agent tool calls in a single \
            response.
               - Use 1 agent when the task is isolated to known files, the user provided specific file \
            paths, or you're making a small targeted change.
               - Use multiple agents when: the scope is uncertain, multiple areas of the codebase are \
            involved, or you need to understand existing patterns before planning.
               - Quality over quantity - 3 agents maximum, but you should try to use the minimum number \
            of agents necessary (usually just 1)
               - If using multiple agents: Provide each agent with a specific search focus or area to \
            explore. Example: One agent searches for existing implementations, another explores related \
            components, a third investigating testing patterns

            ### Phase 2: Design
            Goal: Design an implementation approach.

            Call the Agent tool with subagent_type="plan" to design the implementation based on the \
            user's intent and your exploration results from Phase 1.

            You can launch up to 1 plan agent.

            **Guidelines:**
            - **Default**: Launch at least 1 Plan agent for most tasks - it helps validate your \
            understanding and consider alternatives
            - **Skip agents**: Only for truly trivial tasks (typo fixes, single-line changes, simple renames)

            In the agent prompt:
            - Provide comprehensive background context from Phase 1 exploration including filenames and \
            code path traces
            - Describe requirements and constraints
            - Request a detailed implementation plan

            ### Phase 3: Review
            Goal: Review the plan(s) from Phase 2 and ensure alignment with the user's intentions.
            1. Read the critical files identified by agents to deepen your understanding
            2. Ensure that the plans align with the user's original request
            3. Use AskUserQuestion to clarify any remaining questions with the user

            ### Phase 4: Final Plan
            Goal: Write your final plan to the plan file (the only file you can edit).
            - Begin with a **Context** section: explain why this change is being made -- the problem or \
            need it addresses, what prompted it, and the intended outcome
            - Include only your recommended approach, not all alternatives
            - Ensure that the plan file is concise enough to scan quickly, but detailed enough to \
            execute effectively
            - Include the paths of critical files to be modified
            - Reference existing functions and utilities you found that should be reused, with their file paths
            - Include a verification section describing how to test the changes end-to-end (run the code, \
            use MCP tools, run tests)

            ### Phase 5: Call ExitPlanMode
            At the very end of your turn, once you have asked the user questions and are happy with your \

            final plan file - you should always call ExitPlanMode to indicate to the user that you are \
            done planning.
            This is critical - your turn should only end with either using the AskUserQuestion tool OR \
            calling ExitPlanMode. Do not stop unless it's for these 2 reasons

            **Important:** Use AskUserQuestion ONLY to clarify requirements or choose between approaches. \
            Use ExitPlanMode to request plan approval. Do NOT ask about plan approval in any other way - \
            no text questions, no AskUserQuestion. Phrases like "Is this plan okay?", "Should I proceed?", \
            "How does this plan look?", "Any changes before we start?", or similar MUST use ExitPlanMode.

            NOTE: At any point in time through this workflow you should feel free to ask the user \
            questions or clarifications using the AskUserQuestion tool. Don't make large assumptions \
            about user intent. The goal is to present a well researched plan to the user, and tie any \
            loose ends before implementation begins.""";

    /** 非完整提醒轮次使用的短文本，减少重复上下文带来的 token 开销。 */
    private static final String PLAN_MODE_SPARSE_REMINDER =
            "Plan mode still active (see full instructions earlier in conversation). "
                    + "Read-only except plan file (%s). Follow 5-phase workflow. "
                    + "End turns with AskUserQuestion (for clarifications) or ExitPlanMode "
                    + "(for plan approval). Never ask about plan approval via text or AskUserQuestion.";

    /** 退出后再次进入计划模式时，指导模型先判断旧计划是否仍然适用。 */
    private static final String PLAN_MODE_REENTRY_REMINDER = """
            ## Re-entering Plan Mode

            You are returning to plan mode after having previously exited it. A plan file exists at %s \
            from your previous planning session.

            **Before proceeding with any new planning, you should:**
            1. Read the existing plan file to understand what was previously planned
            2. Evaluate the user's current request against that plan
            3. Decide how to proceed:
               - **Different task**: If the user's request is for a different task--even if it's similar \
            or related--start fresh by overwriting the existing plan
               - **Same task, continuing**: If this is explicitly a continuation or refinement of the \
            exact same task, modify the existing plan while cleaning up outdated or irrelevant sections
            4. Continue on with the plan process and most importantly you should always edit the plan \
            file one way or the other before calling ExitPlanMode

            Treat this as a fresh planning session. Do not assume the existing plan is relevant without \
            evaluating it first.""";

    /** 退出计划模式后解除只读限制，并按需保留计划文件位置。 */
    private static final String PLAN_MODE_EXIT_REMINDER =
            "## Exited Plan Mode\n\n"
                    + "You have exited plan mode. You can now make edits, run tools, and take actions.%s";

    private PlanModePrompt() {}

    /**
     * 构造每轮 Agent 循环需要注入的计划模式提醒。
     *
     * @param planPath 计划文件路径
     * @param planExists 计划文件当前是否存在
     * @param iteration 当前计划会话中从 1 开始的循环轮次
     * @return 本轮要注入会话的提醒文本
     */
    public static String buildReminder(String planPath, boolean planExists, int iteration) {
        // 文件是否存在决定提示模型使用创建工具还是增量编辑工具。
        String planFileInfo = "Plan file: " + planPath;
        if (planExists) {
            planFileInfo += "\nA plan file already exists at " + planPath
                    + ". You can read it and make incremental edits using the EditFile tool.";
        } else {
            planFileInfo += "\nNo plan file exists yet. You should create your plan at " + planPath
                    + " using the WriteFile tool.";
        }

        if (iteration == 1) {
            // 首轮必须给出完整权限边界和工作流，不能只依赖精简提醒。
            return String.format(PLAN_MODE_FULL_REMINDER, planFileInfo);
        }

        // 后续轮次在完整提醒与精简提醒之间切换，避免规则随长对话被淡化。
        int attachmentIndex = (iteration - 1) / REMINDER_INTERVAL;
        if (attachmentIndex % REMINDER_INTERVAL == 0) {
            return String.format(PLAN_MODE_FULL_REMINDER, planFileInfo);
        }

        return String.format(PLAN_MODE_SPARSE_REMINDER, planPath);
    }

    /** 构造退出后再次进入计划模式时的旧计划检查提醒。 */
    public static String buildReentryReminder(String planPath) {
        return String.format(PLAN_MODE_REENTRY_REMINDER, planPath);
    }

    /** 构造退出计划模式后立即注入的权限恢复提醒。 */
    public static String buildExitReminder(String planPath, boolean planExists) {
        String extra = "";
        if (planExists) {
            extra = " The plan file is located at " + planPath + " if you need to reference it.";
        }
        return String.format(PLAN_MODE_EXIT_REMINDER, extra);
    }
}
