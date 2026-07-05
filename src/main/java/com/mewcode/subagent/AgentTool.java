
package com.mewcode.subagent;

import com.mewcode.agent.Agent;
import com.mewcode.agent.AgentEvent;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**

 * 启动子代理来处理重点任务的工具。子代理

 * 在其自己的虚拟线程中运行，具有隔离的对话和过滤的对话

 * 工具注册表（基于所选的 {@link SubAgentSpec}）。

 *

 * <p>实现{@link Tool}，因此可以在{@link ToolRegistry}中注册

 * 并由父代理通过标准工具调用机制调用。

 *

 * <p>{@link #shouldDefer()} 返回 {@code true}，因此该工具的架构仅

 * 通过 ToolSearch 显式获取时提供给模型。

 */
public class AgentTool implements Tool {

    private final LlmClient client;
    private final ToolRegistry parentRegistry;
    private final String protocol;
    private final ProviderConfig providerConfig;

    /**

     * 可选：将模型别名（"haiku"、"sonnet"、"opus"）解析为 LlmClient 实例。

     */
    private Function<String, LlmClient> modelResolver;

    /**

     * 可选：加载代理定义（内置+用户+项目）。

     */
    private Map<String, SubAgentSpec> agentSpecs;

    /**

     * 可选：在子代理运行时接收进度事件。

     */
    private Consumer<SubAgentProgress> progressListener;

    /**

     * 可选：用于后台代理执行的任务管理器。

     */
    private SubAgentTaskManager taskManager;

    /**

     * 可选：家长对话以获得分叉支持。

     */
    private ConversationManager parentConversation;

    /**

     * 可选：隔离模式的工作树管理器。

     */
    private com.mewcode.worktree.WorktreeManager worktreeManager;

    /**

     * 可选：用于 team_name 注册的团队经理。

     */
    private com.mewcode.teams.TeamManager teamManager;

    private static final String FORK_BOILERPLATE_TAG = "<fork_boilerplate>";

    private static final String FORK_BOILERPLATE = FORK_BOILERPLATE_TAG + """

            You are a forked worker process. You are NOT the main agent.
            Rules (non-negotiable):
            1. Do NOT fork again.
            2. Do NOT converse, ask questions, or request confirmation.
            3. Use tools directly: read files, search code, make changes.
            4. Stay strictly within your assigned task scope.
            5. Final report must be under 500 characters, starting with "Scope:".
            </fork_boilerplate>""";

    public AgentTool(LlmClient client, ToolRegistry parentRegistry, String protocol,
                     ProviderConfig providerConfig) {
        this.client = client;
        this.parentRegistry = parentRegistry;
        this.protocol = protocol;
        this.providerConfig = providerConfig;
    }

    public void setModelResolver(Function<String, LlmClient> modelResolver) {
        this.modelResolver = modelResolver;
    }

    public void setAgentSpecs(Map<String, SubAgentSpec> agentSpecs) {
        this.agentSpecs = agentSpecs;
    }

    public void setProgressListener(Consumer<SubAgentProgress> progressListener) {
        this.progressListener = progressListener;
    }

    public void setTaskManager(SubAgentTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    public SubAgentTaskManager getTaskManager() {
        return taskManager;
    }

    public void setParentConversation(ConversationManager parentConversation) {
        this.parentConversation = parentConversation;
    }

    /**

     * 父代理的工具结果决策日志。分叉子项 Clone() 于

     * 生成时间，以便他们对继承的 tool_use_ids 做出相同的决定

     * 来自父级 - 保留提示缓存前缀所必需的

     * 父级和子级之间的字节相同。非分叉子代理（那些

     * 与 subagent_type）从一个新的状态开始。

     */
    private com.mewcode.toolresult.ContentReplacementState parentReplacementState;

    public void setParentReplacementState(com.mewcode.toolresult.ContentReplacementState state) {
        this.parentReplacementState = state;
    }

    public void setWorktreeManager(com.mewcode.worktree.WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
    }

    public void setTeamManager(com.mewcode.teams.TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    // ---- 工具界面 ----

    @Override
    public String name() {
        return "Agent";
    }

    @Override
    public String description() {
        var sb = new StringBuilder();
        sb.append("Launch a sub-agent to handle a complex task. Each agent runs independently ");
        sb.append("with its own context.\n\n");
        sb.append("Use this when a task benefits from focused, isolated work -- e.g., ");
        sb.append("researching a question, implementing a component, or reviewing code. ");
        sb.append("The sub-agent cannot see the current conversation.\n\n");
        sb.append("Available agent types:");

        if (agentSpecs != null && !agentSpecs.isEmpty()) {
            for (String name : AgentLoader.listNames(agentSpecs)) {
                SubAgentSpec spec = agentSpecs.get(name);
                sb.append("\n- ").append(name).append(": ").append(spec.description());
            }
        } else {
            sb.append("\n- general-purpose: Full tool access for multi-step tasks (default)");
            sb.append("\n- plan: Read-only tools for designing implementation plans");
            sb.append("\n- explore: Read-only search agent for locating code");
        }

        sb.append("\n\nWrite a detailed prompt explaining what the agent should do and why ");
        sb.append("-- it has no prior context.");
        return sb.toString();
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> schema() {
        List<String> agentTypes;
        if (agentSpecs != null && !agentSpecs.isEmpty()) {
            agentTypes = AgentLoader.listNames(agentSpecs);
        } else {
            agentTypes = List.of("general-purpose", "plan", "explore");
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", Map.of(
                "type", "string",
                "description", "A short (3-5 word) description of the task"
        ));
        properties.put("prompt", Map.of(
                "type", "string",
                "description", "The task for the agent to perform. Be detailed -- the agent has no context from this conversation."
        ));
        properties.put("subagent_type", Map.of(
                "type", "string",
                "enum", agentTypes,
                "description", "The type of agent to use. Defaults to general-purpose."
        ));
        properties.put("model", Map.of(
                "type", "string",
                "enum", List.of("sonnet", "opus", "haiku"),
                "description", "Override the model for this agent. Defaults to the parent's model."
        ));
        properties.put("run_in_background", Map.of(
                "type", "boolean",
                "description", "Set to true to run the agent in the background."
        ));
        properties.put("isolation", Map.of(
                "type", "string",
                "enum", List.of("worktree"),
                "description", "Isolation mode. 'worktree' creates a temporary git worktree."
        ));
        properties.put("team_name", Map.of(
                "type", "string",
                "description", "REQUIRED when creating team members. Spawns the agent as a long-running "
                        + "teammate under this team (created via TeamCreate). Unlike regular sub-agents, team "
                        + "members run in their own terminal, persist after the lead returns, and communicate "
                        + "with each other via SendMessage. Without team_name the agent runs as a one-shot "
                        + "sub-agent that blocks and returns inline."
        ));

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("description", "prompt"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", name());
        schema.put("description", description());
        schema.put("input_schema", inputSchema);
        return schema;
    }

    @Override
    public boolean shouldDefer() {
        return false;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String description = getStringArg(args, "description");
        String prompt = getStringArg(args, "prompt");
        if (description == null || description.isEmpty() || prompt == null || prompt.isEmpty()) {
            return ToolResult.error("Error: description and prompt are required");
        }

        String subagentType = getStringArg(args, "subagent_type");
        String modelOverride = getStringArg(args, "model");
        String isolation = getStringArg(args, "isolation");
        String teamName = getStringArg(args, "team_name");

        // 团队成员路径：检查 BEFORE 分支/子代理，这样 team_name 就不会被跳过。
        if (teamName != null && !teamName.isEmpty() && teamManager != null) {
            SubAgentSpec spec = (subagentType != null && !subagentType.isEmpty())
                    ? resolveSpec(subagentType) : resolveSpec("general-purpose");
            if (spec == null) spec = resolveSpec("general-purpose");
            return runAsTeammate(spec, teamName, description, prompt, modelOverride, isolation);
        }

        // 分叉路径：未指定 subagent_type。
        if (subagentType == null || subagentType.isEmpty()) {
            return runFork(description, prompt, modelOverride);
        }

        // 解决规范
        SubAgentSpec spec = resolveSpec(subagentType);
        if (spec == null) {
            String available = (agentSpecs != null)
                    ? String.join(", ", AgentLoader.listNames(agentSpecs))
                    : "general-purpose, plan, explore";
            return ToolResult.error(
                    "Error: unknown agent type '%s'. Available: %s".formatted(subagentType, available));
        }

        boolean runInBackground = Boolean.TRUE.equals(args.get("run_in_background"));

        if (runInBackground) {
            return runAsync(spec, description, prompt, modelOverride);
        }
        return runSync(spec, description, prompt, modelOverride, isolation);
    }

    // ---- Internal ----

    private ToolResult runAsync(SubAgentSpec spec, String description, String prompt, String modelOverride) {
        if (taskManager == null) {
            return ToolResult.error("Background execution not available (no task manager configured)");
        }
        LlmClient subClient = selectClient(spec.model(), modelOverride);
        String taskId = taskManager.spawnSubAgent(subClient, parentRegistry, protocol, providerConfig, spec, prompt);
        return ToolResult.success(
                "Agent \"%s\" launched in background (task %s). You will be notified when it completes."
                        .formatted(description, taskId));
    }

    private ToolResult runFork(String description, String prompt, String modelOverride) {
        if (parentConversation == null) {
            return ToolResult.error("Error: fork requires parent conversation context");
        }
        if (taskManager == null) {
            return ToolResult.error("Error: fork requires task manager for background execution");
        }

        // 检查嵌套叉子
        for (var msg : parentConversation.getMessages()) {
            if (msg.getContent() != null && msg.getContent().contains(FORK_BOILERPLATE_TAG)) {
                return ToolResult.error("Error: cannot fork from a forked agent. Use subagent_type to spawn a definition-based agent instead.");
            }
        }

        // 构建分叉对话：复制父消息 + 附加分叉样板 + 任务
        ConversationManager forkedConv = buildForkedConversation(parentConversation, prompt);

        LlmClient subClient = selectClient(null, modelOverride);
        // Fork 始终在后台运行；父代的替换状态是
        // 传递，因此子进程继承一个克隆（字节稳定提示缓存）。
        String taskId = taskManager.spawnSubAgent(
                subClient, parentRegistry, protocol, providerConfig, SubAgentSpec.GENERAL_PURPOSE,
                FORK_BOILERPLATE + "\n\nYour task:\n" + prompt,
                parentReplacementState);

        return ToolResult.success(
                "Forked agent \"%s\" launched in background (task %s). Results will arrive via task-notification."
                        .formatted(description, taskId));
    }

    private static ConversationManager buildForkedConversation(ConversationManager parent, String task) {
        ConversationManager forked = new ConversationManager();
        for (var msg : parent.getMessages()) {
            if (msg.getToolUses() != null && !msg.getToolUses().isEmpty()
                    && (msg.getToolResults() == null || msg.getToolResults().isEmpty())) {
                // 带有待处理 tool_use 的助理消息 - 带有占位符结果的补丁
                forked.addAssistantFull(msg.getContent(), msg.getThinkingBlocks(), msg.getToolUses());
                var placeholders = msg.getToolUses().stream()
                        .map(tu -> new com.mewcode.conversation.ToolResultBlock(
                                tu.toolUseId(), "(tool execution interrupted by fork)", false))
                        .toList();
                forked.addToolResultsMessage(placeholders);
            } else if (msg.getToolUses() != null && !msg.getToolUses().isEmpty()) {
                forked.addAssistantFull(msg.getContent(), msg.getThinkingBlocks(), msg.getToolUses());
            } else if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                forked.addToolResultsMessage(msg.getToolResults());
            } else if ("assistant".equals(msg.getRole())) {
                forked.addAssistantMessage(msg.getContent());
            } else {
                forked.addUserMessage(msg.getContent());
            }
        }
        forked.addUserMessage(FORK_BOILERPLATE + "\n\nYour task:\n" + task);
        return forked;
    }

    private ToolResult runSync(SubAgentSpec spec, String description, String prompt, String modelOverride, String isolation) {
        ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec);
        LlmClient subClient = selectClient(spec.model(), modelOverride);

        Agent subAgent = new Agent(subClient, subRegistry, protocol, providerConfig);
        int maxTurns = spec.maxTurns() > 0 ? spec.maxTurns() : 200;
        subAgent.setMaxIterations(maxTurns);

        // 通过 AgentWorktree API 进行工作树隔离
        com.mewcode.worktree.AgentWorktree.Result wtResult = null;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            byte[] rndBytes = new byte[4];
            new java.security.SecureRandom().nextBytes(rndBytes);
            String slug = "agent-a" + java.util.HexFormat.of().formatHex(rndBytes).substring(0, 7);
            try {
                wtResult = com.mewcode.worktree.AgentWorktree.create(
                        slug, worktreeManager.getProjectRoot(), worktreeManager.getSymlinkDirs());
                subAgent.setWorkDir(wtResult.worktreePath());
                // 将工作树通知插入提示中
                String notice = com.mewcode.worktree.AgentWorktree.buildNotice(
                        System.getProperty("user.dir"), wtResult.worktreePath());
                prompt = notice + "\n\n" + prompt;
            } catch (Exception e) {
                return ToolResult.error("Error creating agent worktree: " + e.getMessage());
            }
        }

        ConversationManager conv = new ConversationManager();
        if (spec.systemPromptOverride() != null && !spec.systemPromptOverride().isEmpty()) {
            conv.addSystemReminder(spec.systemPromptOverride());
        }
        conv.addUserMessage(prompt);

        long startNanos = System.nanoTime();
        var output = new StringBuilder();
        int toolCount = 0;

        BlockingQueue<AgentEvent> queue = subAgent.run(conv);

        while (true) {
            AgentEvent event;
            try {
                event = queue.poll(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitProgress(description, spec.name(), true, true, toolCount, elapsedSeconds(startNanos));
                return ToolResult.error("Agent interrupted");
            }

            if (event == null) {
                emitProgress(description, spec.name(), true, true, toolCount, elapsedSeconds(startNanos));
                return ToolResult.error("Agent timed out waiting for events");
            }

            switch (event) {
                case AgentEvent.StreamText st -> output.append(st.text());

                case AgentEvent.ToolResultEvent tre -> {
                    toolCount++;
                    emitProgress(description, spec.name(), tre.toolName(), tre.output(),
                            tre.isError(), false, toolCount, elapsedSeconds(startNanos));
                }

                case AgentEvent.ErrorEvent err -> {
                    emitProgress(description, spec.name(), true, true, toolCount, elapsedSeconds(startNanos));
                    return ToolResult.error("Agent failed: " + err.message());
                }

                case AgentEvent.LoopComplete lc -> {
                    // 代理完毕
                    double totalTime = elapsedSeconds(startNanos);
                    emitProgress(description, spec.name(), false, true, toolCount, totalTime);

                    String result = output.toString();
                    if (result.isEmpty()) {
                        result = "(agent produced no output)";
                    }

                    // 通过 WorktreeChanges API 清理工作树（失败关闭）
                    String wtInfo = "";
                    if (wtResult != null) {
                        if (com.mewcode.worktree.WorktreeChanges.hasChanges(
                                wtResult.worktreePath(), wtResult.headCommit())) {
                            wtInfo = "\n\nWorktree kept at %s (branch %s) — has uncommitted changes or new commits."
                                    .formatted(wtResult.worktreePath(), wtResult.worktreeBranch());
                        } else {
                            com.mewcode.worktree.AgentWorktree.remove(
                                    wtResult.worktreePath(), wtResult.worktreeBranch(), wtResult.gitRoot());
                        }
                    }

                    long elapsedMs = Math.round(totalTime * 1000);
                    return ToolResult.success(
                            "Agent \"%s\" completed in %d.%03ds.\n\n%s%s".formatted(
                                    description, elapsedMs / 1000, elapsedMs % 1000, result, wtInfo));
                }

                default -> {
                    // ThinkingText、ThinkingComplete、ToolUseEvent、TurnComplete、UsageEvent 等
                    // -- 已消耗但未呈现给父级
                }
            }
        }
    }

    private SubAgentSpec resolveSpec(String name) {
        if (agentSpecs != null) {
            return agentSpecs.get(name);
        }
        return switch (name) {
            case "general-purpose" -> SubAgentSpec.GENERAL_PURPOSE;
            case "plan" -> SubAgentSpec.PLAN;
            case "explore" -> SubAgentSpec.EXPLORE;
            default -> null;
        };
    }

    private ToolResult runAsTeammate(SubAgentSpec spec, String teamName,
                                     String description, String prompt, String modelOverride, String isolation) {
        var team = teamManager.getTeam(teamName);
        if (team == null) {
            return ToolResult.error("Error: team '%s' not found. Create it first with TeamCreate.".formatted(teamName));
        }

        // 删除重复的成员名称
        String memberName = description.replaceAll("\\s+", "-").toLowerCase();
        if (memberName.length() > 30) memberName = memberName.substring(0, 30);
        int suffix = 2;
        String base = memberName;
        while (team.hasMember(memberName)) {
            memberName = base + "-" + suffix++;
        }

        ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec);
        // 为队友添加协调工具
        subRegistry.register(new com.mewcode.teams.TeamTools.SendMessageTool(teamManager, memberName));

        LlmClient subClient = selectClient(spec.model(), modelOverride);

        // 收集附录的同行名称
        var otherMembers = team.memberNames();

        // 构建附录
        String addendum = com.mewcode.teams.TeammateRunner.buildTeammateAddendum(
                teamName, memberName, otherMembers);

        // 可选的工作树隔离
        String workdir = null;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            try {
                byte[] rndBytes = new byte[4];
                new java.security.SecureRandom().nextBytes(rndBytes);
                String slug = "agent-a" + java.util.HexFormat.of().formatHex(rndBytes).substring(0, 7);
                var wtResult = com.mewcode.worktree.AgentWorktree.create(
                        slug, worktreeManager.getProjectRoot(), worktreeManager.getSymlinkDirs());
                workdir = wtResult.worktreePath();
                String notice = com.mewcode.worktree.AgentWorktree.buildNotice(
                        System.getProperty("user.dir"), wtResult.worktreePath());
                prompt = notice + "\n\n" + prompt;
            } catch (Exception e) {
                return ToolResult.error("Error creating teammate worktree: " + e.getMessage());
            }
        }

        // 生成队友
        try {
            var spawnResult = com.mewcode.teams.SpawnDispatcher.spawnTeammate(
                    new com.mewcode.teams.SpawnDispatcher.SpawnConfig(
                            team, memberName, prompt, addendum,
                            subClient, subRegistry, protocol, providerConfig, workdir));

            return ToolResult.success(
                    "Teammate \"%s\" spawned in team \"%s\" (mode: %s). The teammate is now working on the assigned task."
                            .formatted(memberName, teamName, spawnResult.mode()));
        } catch (Exception e) {
            return ToolResult.error("Error spawning teammate: " + e.getMessage());
        }
    }

    private LlmClient selectClient(String specModel, String overrideModel) {
        String model = (overrideModel != null && !overrideModel.isEmpty()) ? overrideModel : specModel;
        if (model == null || model.isEmpty() || "inherit".equals(model)) {
            return client;
        }
        if (modelResolver != null) {
            LlmClient resolved = modelResolver.apply(model);
            if (resolved != null) {
                return resolved;
            }
        }
        return client;
    }

    private void emitProgress(String description, String agentType,
                              boolean isError, boolean done, int toolCount, double totalTime) {
        emitProgress(description, agentType, null, null, isError, done, toolCount, totalTime);
    }

    private void emitProgress(String description, String agentType,
                              String toolName, String toolOutput,
                              boolean isError, boolean done, int toolCount, double totalTime) {
        if (progressListener != null) {
            progressListener.accept(new SubAgentProgress(
                    agentType, description, toolName, toolOutput,
                    isError, done, toolCount, totalTime));
        }
    }

    private static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static String getStringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }
}
