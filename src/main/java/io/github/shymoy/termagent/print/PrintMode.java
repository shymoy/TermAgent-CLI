
package io.github.shymoy.termagent.print;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shymoy.termagent.agent.Agent;
import io.github.shymoy.termagent.agent.AgentEvent;
import io.github.shymoy.termagent.config.AppConfig;
import io.github.shymoy.termagent.config.AppPaths;
import io.github.shymoy.termagent.config.HookConfig;
import io.github.shymoy.termagent.config.McpServerConfig;
import io.github.shymoy.termagent.config.ProviderConfig;
import io.github.shymoy.termagent.conversation.ConversationManager;
import io.github.shymoy.termagent.filehistory.FileHistory;
import io.github.shymoy.termagent.hook.HookEngine;
import io.github.shymoy.termagent.llm.LlmClient;
import io.github.shymoy.termagent.mcp.McpManager;
import io.github.shymoy.termagent.memory.MemoryManager;
import io.github.shymoy.termagent.permission.PermissionChecker;
import io.github.shymoy.termagent.permission.PermissionMode;
import io.github.shymoy.termagent.permission.PermissionResponse;
import io.github.shymoy.termagent.prompt.PromptBuilder;
import io.github.shymoy.termagent.session.SessionManager;
import io.github.shymoy.termagent.skill.SkillCatalog;
import io.github.shymoy.termagent.subagent.AgentTool;
import io.github.shymoy.termagent.subagent.SubAgentTaskManager;
import io.github.shymoy.termagent.task.TaskList;
import io.github.shymoy.termagent.task.TaskTools;
import io.github.shymoy.termagent.teams.TeamManager;
import io.github.shymoy.termagent.tool.ToolRegistry;
import io.github.shymoy.termagent.tool.impl.AskUserTool;
import io.github.shymoy.termagent.tool.impl.ToolSearchTool;
import io.github.shymoy.termagent.worktree.WorktreeManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Print 模式（-p）：非交互式运行 Agent，将结果输出到 stdout。
 * 支持两种输出格式：
 *   - text（默认）：只输出最终文本
 *   - stream-json：每个事件输出一行 JSON
 */
public class PrintMode {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 输出格式枚举
     */
    public enum OutputFormat {
        TEXT,
        STREAM_JSON
    }

    /**
     * 运行 print 模式的入口
     */
    public static void run(AppConfig config, String prompt, OutputFormat format) {
        long startTime = System.currentTimeMillis();

        String workDir = System.getProperty("user.dir");
        ProviderConfig providerCfg = config.getProviders().get(0);
        List<McpServerConfig> mcpConfigs = config.getMcpServers() != null ? config.getMcpServers() : List.of();
        List<HookConfig> hookConfigs = config.getHooks() != null ? config.getHooks() : List.of();

        // ── 记忆管理 ──────────────────────────────────────────────────
        MemoryManager memoryManager = new MemoryManager(workDir);
        String instructionsContent = MemoryManager.loadInstructions(workDir);

        // ── 构建系统提示词 ──────────────────────────────────────────────
        var env = PromptBuilder.detectEnvironment(providerCfg.getModel());
        var options = new PromptBuilder.BuildOptions(null, null, null);
        String systemPrompt = PromptBuilder.buildSystemPrompt(env, options);

        // ── 创建 LLM 客户端 ─────────────────────────────────────────────
        LlmClient client = LlmClient.create(providerCfg, systemPrompt);
        String protocol = providerCfg.getProtocol();

        // ── 工具注册 ────────────────────────────────────────────────────
        ToolRegistry registry = ToolRegistry.createDefault();
        registry.register(new ToolSearchTool(registry, protocol));

        var exitPlanTool = new io.github.shymoy.termagent.tool.impl.ExitPlanModeTool();
        exitPlanTool.setIsPlanMode(() -> false); // print 模式不用 plan
        exitPlanTool.setPlanExists(() -> false);
        registry.register(exitPlanTool);

        // AskUser 工具：print 模式下自动返回空答案
        AskUserTool askUserTool = new AskUserTool();
        registry.register(askUserTool);

        // ── 子 Agent 工具 ───────────────────────────────────────────────
        var agentTool = new AgentTool(client, registry, protocol, providerCfg);
        SubAgentTaskManager subAgentTaskManager = new SubAgentTaskManager();
        agentTool.setTaskManager(subAgentTaskManager);
        registry.register(agentTool);

        // ── Worktree 工具 ───────────────────────────────────────────────
        var worktreeManager = new WorktreeManager(workDir, List.of(), 720);
        agentTool.setWorktreeManager(worktreeManager);
        String sessionId = SessionManager.newId();
        registry.register(new io.github.shymoy.termagent.tool.impl.EnterWorktreeTool(worktreeManager, sessionId));
        registry.register(new io.github.shymoy.termagent.tool.impl.ExitWorktreeTool(worktreeManager));

        // ── 任务工具 ────────────────────────────────────────────────────
        TaskList taskList = new TaskList("default", workDir);
        registry.register(new TaskTools.TaskCreateTool(taskList));
        registry.register(new TaskTools.TaskGetTool(taskList));
        registry.register(new TaskTools.TaskListTool(taskList));
        registry.register(new TaskTools.TaskUpdateTool(taskList));

        // ── 团队工具 ────────────────────────────────────────────────────
        TeamManager teamManager = new TeamManager();
        agentTool.setTeamManager(teamManager);
        registry.register(new io.github.shymoy.termagent.teams.TeamTools.TeamCreateTool(teamManager));
        registry.register(new io.github.shymoy.termagent.teams.TeamTools.TeamDeleteTool(teamManager));
        registry.register(new io.github.shymoy.termagent.teams.TeamTools.SendMessageTool(teamManager, "lead"));

        // ── 权限检查器：BYPASS 模式，自动批准所有操作 ──────────────────
        PermissionChecker permChecker = new PermissionChecker(PermissionMode.BYPASS, Path.of(workDir));

        // ── 会话和文件历史 ──────────────────────────────────────────────
        FileHistory fileHistory = new FileHistory(workDir, sessionId);
        var fileStateCache = new io.github.shymoy.termagent.tool.FileStateCache();
        for (var tool : registry.listTools()) {
            if (tool instanceof io.github.shymoy.termagent.tool.impl.EditFileTool ef) {
                ef.setFileHistory(fileHistory);
                ef.setFileStateCache(fileStateCache);
            }
            if (tool instanceof io.github.shymoy.termagent.tool.impl.WriteFileTool wf) {
                wf.setFileHistory(fileHistory);
                wf.setFileStateCache(fileStateCache);
            }
            if (tool instanceof io.github.shymoy.termagent.tool.impl.ReadFileTool rf) {
                rf.setFileStateCache(fileStateCache);
            }
        }

        // ── 构建 Agent ──────────────────────────────────────────────────
        ConversationManager conversation = new ConversationManager();
        Agent agent = new Agent(client, registry, protocol, providerCfg);
        agent.setFileHistory(fileHistory);
        agent.setInstructions(instructionsContent);
        agent.setChecker(permChecker);
        agent.setWorkDir(workDir);
        agent.setSessionId(sessionId);

        // 通知函数：排空团队邮箱和任务通知
        agent.setNotificationFn(() -> {
            var notes = new ArrayList<String>();
            notes.addAll(io.github.shymoy.termagent.teams.TeammateRunner.drainLeadMailbox(teamManager));
            for (var n : subAgentTaskManager.drainNotifications()) {
                notes.add("<task-notification>Task %s: %s (%s)</task-notification>"
                        .formatted(n.taskId(), n.name(), n.status()));
            }
            return notes;
        });

        // 工具名过滤器（团队协调模式）
        agent.setToolNameFilter(name -> {
            if (teamManager.listTeams().isEmpty()) return true;
            return io.github.shymoy.termagent.teams.Coordinator.isCoordinatorTool(name);
        });

        // 子 Agent 关联
        if (registry.get("Agent") instanceof AgentTool at) {
            at.setProgressListener(progress -> {}); // print 模式不需要进度回调
        }

        // ── Hook 引擎 ──────────────────────────────────────────────────
        HookEngine hookEngine = new HookEngine();
        if (!hookConfigs.isEmpty()) {
            List<HookEngine.Hook> hooks = hookConfigs.stream().map(hc -> {
                HookEngine.EventName event = parseEventName(hc.getEvent());
                HookEngine.ActionType actionType = parseActionType(hc.getType());
                Duration timeout = hc.getTimeout() > 0
                        ? Duration.ofSeconds(hc.getTimeout()) : Duration.ZERO;
                var action = new HookEngine.Action(actionType, hc.getCommand(), hc.getMessage(),
                        hc.getUrl(), hc.getMethod(), hc.getHeaders(), hc.getBody(), timeout);
                return new HookEngine.Hook(hc.getId(), event, hc.getCondition(), action,
                        hc.isReject(), hc.isOnce(), hc.isAsync(), hc.getOnError());
            }).toList();
            hookEngine.loadHooks(hooks);
        }
        agent.setHookEngine(hookEngine);

        // ── Skill 加载 ──────────────────────────────────────────────────
        SkillCatalog skillCatalog = new SkillCatalog();
        var skillDir = AppPaths.readableProject(Path.of(workDir), "skills");
        if (Files.isDirectory(skillDir)) {
            skillCatalog.loadFromDirectory(skillDir);
        }

        // ── MCP 服务器连接 ──────────────────────────────────────────────
        String mcpInstructions = "";
        if (!mcpConfigs.isEmpty()) {
            try {
                McpManager mcpManager = new McpManager(mcpConfigs);
                var result = mcpManager.connectAll();
                for (var t : result.tools()) registry.register(t);
                for (var e : result.errors()) System.err.println("MCP error: " + e);

                if (!result.servers().isEmpty()) {
                    var mcpParts = new ArrayList<String>();
                    for (var s : result.servers()) {
                        var sb = new StringBuilder();
                        sb.append("## ").append(s.name()).append("\n");
                        if (s.instructions() != null && !s.instructions().isBlank()) {
                            sb.append(s.instructions()).append("\n");
                        }
                        var toolNames = registry.listTools().stream()
                                .filter(t -> t.name().startsWith("mcp__" + s.name() + "__"))
                                .map(io.github.shymoy.termagent.tool.Tool::name)
                                .toList();
                        if (!toolNames.isEmpty()) {
                            sb.append("\nAvailable tools: ").append(String.join(", ", toolNames));
                        }
                        mcpParts.add(sb.toString());
                    }
                    mcpInstructions = "# MCP Server Instructions\n\n"
                            + "The following MCP servers are connected. Use their tools when the user asks.\n\n"
                            + String.join("\n\n", mcpParts);
                }
            } catch (Exception e) {
                System.err.println("MCP init failed: " + e.getMessage());
            }
        }

        // ── 注入用户消息并启动 Agent ─────────────────────────────────────
        conversation.addUserMessage(prompt);
        if (!mcpInstructions.isEmpty()) {
            conversation.addSystemReminder(mcpInstructions);
        }

        BlockingQueue<AgentEvent> queue = agent.run(conversation);
        if (askUserTool != null) askUserTool.setEventQueue(queue);

        // ── 消费事件循环 ────────────────────────────────────────────────
        var resultText = new StringBuilder();
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalTurns = 0;
        var toolCalls = new ArrayList<Map<String, Object>>();

        while (true) {
            AgentEvent event;
            try {
                event = queue.poll(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (event == null) {
                System.err.println("Stream timeout after 120s");
                System.exit(1);
                return;
            }

            switch (event) {
                case AgentEvent.StreamText e -> {
                    resultText.append(e.text());
                    if (format == OutputFormat.STREAM_JSON) {
                        // stream-json 模式不输出 stream_text 事件（太碎片化）
                    }
                }

                case AgentEvent.ThinkingText e -> {
                    // print 模式不输出 thinking 文本
                }

                case AgentEvent.ThinkingComplete e -> {
                    // print 模式不输出 thinking 完成
                }

                case AgentEvent.ToolUseEvent e -> {
                    if (format == OutputFormat.STREAM_JSON && e.args() != null && !e.args().isEmpty()) {
                        // 只输出带完整参数的 ToolUseEvent（即 ToolCallComplete）
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "tool_use");
                        obj.put("tool_name", e.toolName());
                        obj.put("tool_id", e.toolId());
                        obj.put("args", e.args());
                        printJson(obj);
                        toolCalls.add(Map.of("tool_name", e.toolName(), "tool_id", e.toolId()));
                    }
                }

                case AgentEvent.ToolResultEvent e -> {
                    if (format == OutputFormat.STREAM_JSON) {
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "tool_result");
                        obj.put("tool_name", e.toolName());
                        obj.put("tool_id", e.toolId());
                        obj.put("output", e.output() != null ? e.output() : "");
                        obj.put("is_error", e.isError());
                        obj.put("elapsed", e.elapsed());
                        printJson(obj);
                    }
                }

                case AgentEvent.PermissionRequestEvent e -> {
                    // BYPASS 模式下不应收到权限请求，但安全起见自动批准
                    e.future().complete(PermissionResponse.ALLOW);
                }

                case AgentEvent.AskUserRequestEvent e -> {
                    // 非交互模式自动返回空答案
                    e.future().complete(Map.of());
                }

                case AgentEvent.UsageEvent e -> {
                    totalInputTokens = e.inputTokens();
                    totalOutputTokens = e.outputTokens();
                    if (format == OutputFormat.STREAM_JSON) {
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "usage");
                        obj.put("input_tokens", e.inputTokens());
                        obj.put("output_tokens", e.outputTokens());
                        printJson(obj);
                    }
                }

                case AgentEvent.TurnComplete e -> {
                    totalTurns = e.turn();
                    // text 模式下清空已累积文本（中间 turn 的文本不是最终结果）
                    if (format == OutputFormat.TEXT) {
                        resultText.setLength(0);
                    }
                }

                case AgentEvent.LoopComplete e -> {
                    if (e.totalTurns() > 0) totalTurns = e.totalTurns();
                    long durationMs = System.currentTimeMillis() - startTime;

                    if (format == OutputFormat.TEXT) {
                        // 纯文本模式：输出最终结果
                        System.out.print(resultText);
                        // 确保末尾换行
                        if (resultText.length() > 0 && resultText.charAt(resultText.length() - 1) != '\n') {
                            System.out.println();
                        }
                    } else {
                        // stream-json 模式：输出最终 result 事件
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "result");
                        obj.put("result", resultText.toString());
                        obj.put("duration_ms", durationMs);
                        obj.put("num_turns", totalTurns);
                        obj.put("tool_calls", toolCalls);
                        obj.put("usage", Map.of(
                                "input_tokens", totalInputTokens,
                                "output_tokens", totalOutputTokens
                        ));
                        printJson(obj);
                    }
                    System.out.flush();
                    return;
                }

                case AgentEvent.ErrorEvent e -> {
                    if (format == OutputFormat.STREAM_JSON) {
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "error");
                        obj.put("message", e.message());
                        printJson(obj);
                    } else {
                        System.err.println("Error: " + e.message());
                    }
                }

                case AgentEvent.CompactEvent e -> {
                    // print 模式静默处理 compact
                }

                case AgentEvent.RetryEvent e -> {
                    // print 模式静默处理 retry
                }
            }
        }
    }

    /**
     * 将对象序列化为 JSON 并输出一行到 stdout
     */
    private static void printJson(Object obj) {
        try {
            System.out.println(MAPPER.writeValueAsString(obj));
        } catch (Exception e) {
            System.err.println("JSON serialization error: " + e.getMessage());
        }
    }

    // ── Hook 事件名 / 动作类型解析（复刻 RemoteServer） ──────────────
    private static HookEngine.EventName parseEventName(String s) {
        if (s == null) return HookEngine.EventName.SESSION_START;
        return switch (s.toLowerCase()) {
            case "session_start" -> HookEngine.EventName.SESSION_START;
            case "session_end" -> HookEngine.EventName.SESSION_END;
            case "turn_start" -> HookEngine.EventName.TURN_START;
            case "turn_end" -> HookEngine.EventName.TURN_END;
            case "pre_send" -> HookEngine.EventName.PRE_SEND;
            case "post_receive" -> HookEngine.EventName.POST_RECEIVE;
            case "pre_tool_use" -> HookEngine.EventName.PRE_TOOL_USE;
            case "post_tool_use" -> HookEngine.EventName.POST_TOOL_USE;
            case "shutdown" -> HookEngine.EventName.SHUTDOWN;
            default -> HookEngine.EventName.SESSION_START;
        };
    }

    private static HookEngine.ActionType parseActionType(String s) {
        if (s == null) return HookEngine.ActionType.COMMAND;
        return switch (s.toLowerCase()) {
            case "command" -> HookEngine.ActionType.COMMAND;
            case "prompt" -> HookEngine.ActionType.PROMPT;
            case "http" -> HookEngine.ActionType.HTTP;
            case "agent" -> HookEngine.ActionType.AGENT;
            default -> HookEngine.ActionType.COMMAND;
        };
    }
}
