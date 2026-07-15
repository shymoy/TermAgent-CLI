
package io.github.shymoy.termagent.tui;

import io.github.shymoy.termagent.agent.Agent;
import io.github.shymoy.termagent.agent.AgentEvent;
import io.github.shymoy.termagent.agent.AgentRunHandle;
import io.github.shymoy.termagent.command.CommandRegistry;
import io.github.shymoy.termagent.config.AppPaths;
import io.github.shymoy.termagent.config.HookConfig;
import io.github.shymoy.termagent.config.McpServerConfig;
import io.github.shymoy.termagent.config.ProviderConfig;
import io.github.shymoy.termagent.conversation.ConversationManager;
import io.github.shymoy.termagent.history.HistoryStore;
import io.github.shymoy.termagent.hook.HookEngine;
import io.github.shymoy.termagent.llm.LlmClient;
import io.github.shymoy.termagent.mcp.McpManager;
import io.github.shymoy.termagent.memory.MemoryManager;
import io.github.shymoy.termagent.memory.MemoryRecall;
import io.github.shymoy.termagent.permission.PermissionChecker;
import io.github.shymoy.termagent.permission.PermissionMode;
import io.github.shymoy.termagent.permission.PermissionResponse;
import io.github.shymoy.termagent.prompt.PlanModePrompt;
import io.github.shymoy.termagent.prompt.PromptBuilder;
import io.github.shymoy.termagent.session.SessionManager;
import io.github.shymoy.termagent.skill.SkillCatalog;
import io.github.shymoy.termagent.plan.PlanFile;
import io.github.shymoy.termagent.subagent.AgentTool;
import io.github.shymoy.termagent.subagent.SubAgentProgress;
import io.github.shymoy.termagent.subagent.SubAgentTaskManager;
import io.github.shymoy.termagent.task.TaskList;
import io.github.shymoy.termagent.task.TaskTools;
import io.github.shymoy.termagent.tool.ToolRegistry;
import io.github.shymoy.termagent.tool.impl.AskUserTool;
import io.github.shymoy.termagent.tool.impl.ToolSearchTool;
import io.github.shymoy.termagent.teams.TeammateProgress;
import io.github.shymoy.termagent.tui.dialog.PlanApprovalDialog;

import io.github.shymoy.termagent.tui.tea.Command;
import io.github.shymoy.termagent.tui.tea.KeyPressMessage;
import io.github.shymoy.termagent.tui.tea.Message;
import io.github.shymoy.termagent.tui.tea.Model;
import io.github.shymoy.termagent.tui.tea.MouseMessage;
import io.github.shymoy.termagent.tui.tea.Program;
import io.github.shymoy.termagent.tui.tea.QuitMessage;
import io.github.shymoy.termagent.tui.tea.UpdateResult;
import io.github.shymoy.termagent.tui.tea.WindowSizeMessage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * TermAgent-CLI 的终端 UI 状态模型，也是界面层与 Agent 之间的协调器。
 *
 * <p>该类按照 Bubble Tea 的 Model 模式工作：{@link #update(Message)} 消费键盘、窗口和
 * Agent 事件并更新状态，{@link #view()} 根据当前状态生成界面，异步任务则通过 Command
 * 和事件队列把结果送回 UI 循环。</p>
 *
 * <p>它还负责在用户选择模型后组装 LLM 客户端、工具注册中心、权限检查器、会话管理器
 * 以及 Agent。具体的模型调用和工具执行仍由 Agent 与 StreamingExecutor 完成。</p>
 */
public class TermAgentModel implements Model {

    public static final String VERSION = "TermAgent-CLI v0.1.1-learning";

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    // ── 供应商选择────────────────────────────────────────────────
    private final List<ProviderConfig> providers;
    private final List<McpServerConfig> mcpServers;
    private final List<HookConfig> hookConfigs;
    private HookEngine hookEngine;
    private int providerCursor;
    private ProviderConfig selectedProvider;

    // ── Core state ──────────────────────────────────────────────────────
    private AppState state;
    private LlmClient client;
    private ConversationManager conversation;
    private Agent agent;
    private ToolRegistry registry;
    private PermissionChecker permChecker;

    // ── 聊天显示────────────────────────────────────────────────────
    private final List<ChatMessage> chatMessages;
    private final StringBuilder streamBuf;
    private final StringBuilder inputBuffer;
    private boolean streaming;
    private boolean userHasSentMessage;

    // ── Scrollback commit ──────────────────────────────────────────────
    // chatMessages[0..committedUpTo) 已通过 Command.println 输出到终端 scrollback，
    // viewChat() 只渲染 [committedUpTo..end) 的未提交内容
    private int committedUpTo;
    private boolean bannerPrinted;

    // ── 流媒体基础设施──────────────────────────────────────────
    private BlockingQueue<AgentEvent> agentQueue;
    // 当前一次 Agent Run 的生命周期句柄，供 Ctrl+C 同时取消 token 并中断后台线程。
    private AgentRunHandle agentRun;
    private CompletableFuture<PermissionResponse> pendingPermission;
    private boolean permDialog;
    private String permToolName;
    private String permDesc;
    private int permCursor;
    private Program program;

    // ── 快退对话────────────────────────────────────────────────────
    private boolean rewindDialog;
    private int rewindPhase;       // 0=snapshot list, 1=restore options
    private int rewindCursor;
    private int rewindOptionCursor;
    private java.util.List<io.github.shymoy.termagent.filehistory.FileHistory.Snapshot> rewindSnapshots;
    private io.github.shymoy.termagent.filehistory.FileHistory fileHistory;

    private static final String[] REWIND_OPTIONS = {
            "Restore code and conversation",
            "Restore conversation only",
            "Restore code only",
            "Never mind"
    };

    // ── 询问用户对话框──────────────────────────────────────────────────
    private final io.github.shymoy.termagent.tui.dialog.AskUserDialog askUserDialogState = new io.github.shymoy.termagent.tui.dialog.AskUserDialog();
    private CompletableFuture<Map<String, String>> askUserFuture;
    private AskUserTool askUserTool;

    // ── 高级功能──────────────────────────────────────────────
    private McpManager mcpManager;
    private SkillCatalog skillCatalog;
    private TaskList taskList;
    private MemoryManager memoryManager;
    private String instructionsContent = "";
    private String memoryContentField = "";

    // ── Slash menu ────────────────────────────────────────────────────
    private CommandRegistry cmdRegistry;
    private boolean slashMenuOpen;
    private List<io.github.shymoy.termagent.command.Command> slashMatches = new ArrayList<>();
    private int slashCursor;

    // ── 命令历史──────────────────────────────────────────────────
    private final HistoryStore historyStore = new HistoryStore();
    private int historyIndex = -1;
    private String historyDraft = "";

    // ── @ file menu ──────────────────────────────────────────────────
    private boolean atMenuOpen;
    private List<String> atMatches = new ArrayList<>();

    private int atCursor;

    // ── Resume ──────────────────────────────────────────────────────────
    private List<io.github.shymoy.termagent.session.SessionManager.SessionInfo> resumeSessions = new ArrayList<>();
    private List<io.github.shymoy.termagent.session.SessionManager.SessionInfo> resumeFiltered = new ArrayList<>();
    private int resumeCursor;
    private String resumeSearch = "";

    // ── 会话追踪────────────────────────────────────────────────
    private String sessionId;

    // ── Plan mode ────────────────────────────────────────────────────
    private PermissionMode prePlanMode = PermissionMode.DEFAULT;
    private final PlanApprovalDialog planApprovalDialog = new PlanApprovalDialog();
    /** 标记本次会话中是否曾退出过 plan mode，用于再次进入时注入 reentry reminder */
    private boolean hasExitedPlanMode;

    // ── 工具块（回合中累积，在TurnComplete时存档）──
    private final List<ChatMessage.ToolBlockInfo> toolBlocks = new ArrayList<>();
    private ChatMessage.SubAgentBlockState activeSubAgent;

    // ── 子代理基础设施────────────────────────────────────────
    private SubAgentTaskManager subAgentTaskManager;
    private AgentTool agentToolRef;

    // ── 团队基础设施────────────────────────────────────────────
    private final io.github.shymoy.termagent.teams.TeamManager teamManager = new io.github.shymoy.termagent.teams.TeamManager();
    private final ConcurrentLinkedQueue<SubAgentProgress> subAgentProgressQueue = new ConcurrentLinkedQueue<>();

    // ── Sandbox ─────────────────────────────────────────────────────────
    private io.github.shymoy.termagent.sandbox.Sandbox sandboxInstance;
    private io.github.shymoy.termagent.sandbox.SandboxConfig sandboxConfig;

    // ── MCP status ──────────────────────────────────────────────────────
    private volatile boolean mcpConnecting;
    private boolean pendingSingleProviderInit;
    private volatile String mcpInstructions = "";
    private volatile boolean mcpInstructionsOk;
    private volatile String mcpServerInfo = "";

    // ── Ready gate (等待首次 WindowSizeMessage 后再渲染) ──────────────
    private boolean ready;

    // ── 滚动追踪──────────────────────────────────────────────────
    private int scrollOffset;
    private boolean userScrolled;
    private int totalContentLines;

    // ── Spinner ─────────────────────────────────────────────────────────
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private int spinnerFrame = 0;

    private String thinkingVerb = "";
    private long thinkingStartMs;

    // ── Layout ──────────────────────────────────────────────────────────
    private int width;
    private int height;

    // ── token计数器────────────────────────────────────────────────────
    private int totalInput;
    private int totalOutput;

    // ── 代理事件的自定义消息──────────────────────────────────
    public record AgentEventMessage() implements Message {}
    public record MailboxPollMessage() implements Message {}

    public TermAgentModel(List<ProviderConfig> providers, List<McpServerConfig> mcpServers,
                        List<HookConfig> hookConfigs) {
        this.providers = providers != null ? providers : List.of();
        this.mcpServers = mcpServers != null ? mcpServers : List.of();
        this.hookConfigs = hookConfigs != null ? hookConfigs : List.of();
        if (this.providers.size() == 1) {
            this.selectedProvider = this.providers.get(0);
            this.state = AppState.CHAT;
            this.pendingSingleProviderInit = true;
        } else {
            this.state = AppState.PROVIDER_SELECT;
        }
        this.chatMessages = new ArrayList<>();
        this.streamBuf = new StringBuilder();
        this.inputBuffer = new StringBuilder();
        this.conversation = new ConversationManager();
        this.cmdRegistry = new CommandRegistry();
        this.historyStore.load();
        this.width = 80;
        this.height = 24;

        // 初始化 hook 引擎，将 YAML 配置转换为内部 Hook 对象
        this.hookEngine = new HookEngine();
        if (!this.hookConfigs.isEmpty()) {
            List<HookEngine.Hook> hooks = this.hookConfigs.stream().map(hc -> {
                HookEngine.EventName event = parseEventName(hc.getEvent());
                HookEngine.ActionType actionType = parseActionType(hc.getType());
                // 构建 Action：支持 command/prompt/http/agent 的全部字段
                Duration timeout = hc.getTimeout() > 0
                        ? Duration.ofSeconds(hc.getTimeout()) : Duration.ZERO;
                var action = new HookEngine.Action(actionType, hc.getCommand(), hc.getMessage(),
                        hc.getUrl(), hc.getMethod(), hc.getHeaders(), hc.getBody(), timeout);
                return new HookEngine.Hook(hc.getId(), event, hc.getCondition(), action,
                        hc.isReject(), hc.isOnce(), hc.isAsync(), hc.getOnError());
            }).toList();
            // 加载前做配置校验，提前暴露错误
            List<String> errors = HookEngine.validate(hooks);
            if (!errors.isEmpty()) {
                System.err.println("[hook] config validation errors:");
                errors.forEach(e -> System.err.println("  - " + e));
            }
            this.hookEngine.loadHooks(hooks);
        }
    }

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
            case "prompt"  -> HookEngine.ActionType.PROMPT;
            case "http"    -> HookEngine.ActionType.HTTP;
            case "agent"   -> HookEngine.ActionType.AGENT;
            default -> HookEngine.ActionType.COMMAND;
        };
    }

    /** 保存正在运行的 Program，使后台线程能够向 UI 事件循环发送消息。 */
    public void setProgram(Program program) {
        this.program = program;
    }

    /** 处理 Ctrl+C：生成中时保存部分响应，空闲时通知程序退出。 */
    public void handleSigint() {
        if (streaming) {
            cancelCurrentAgentRun();
            savePartialResponse();
            if (program != null) {
                program.send(new AgentEventMessage());
            }
        } else {
            if (program != null) {
                program.send(new QuitMessage());
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────

    // 模型接口

    // ──────────────────────────────────────────────────────────────────────

    /** 首次启动时请求终端尺寸，收到尺寸前暂不渲染界面。 */
    @Override
    public Command init() {
        return Command.checkWindowSize();
    }

    /**
     * UI 的统一消息入口。先处理全局事件和活动中的对话框，再按 AppState
     * 把键盘事件分发给模型选择、聊天或会话恢复页面。
     */
    @Override
    public UpdateResult<? extends Model> update(Message msg) {
        if (pendingSingleProviderInit) {
            pendingSingleProviderInit = false;
            initializeProvider();
        }

        // ── 调整窗口大小────────────────────────────────────────────────
        if (msg instanceof WindowSizeMessage wsm) {
            this.width = wsm.width();
            this.height = wsm.height();
            ready = true;
            // 首次获取窗口大小时，将 banner 输出到 scrollback
            if (!bannerPrinted && state == AppState.CHAT) {
                bannerPrinted = true;
                return UpdateResult.from(this, Command.println(renderBanner() + "\n"));
            }
            return UpdateResult.from(this);
        }

        // ── Ctrl+C / QuitMessage：中断串流或退出──────────
        if (msg instanceof QuitMessage) {
            if (streaming) {
                cancelCurrentAgentRun();
                savePartialResponse();
                return UpdateResult.from(this);
            }
        }
        if (msg instanceof KeyPressMessage kpm && kpm.key().equals("ctrl+c")) {
            if (streaming) {
                cancelCurrentAgentRun();
                savePartialResponse();
                return UpdateResult.from(this);
            }
            return UpdateResult.from(this, QuitMessage::new);
        }

        // ── 鼠标事件────────────────────────────────────────────────
        if (msg instanceof MouseMessage mm) {
            var btn = mm.getButton();
            if (btn == MouseMessage.MouseButton.MouseButtonWheelUp) {
                scrollOffset = Math.min(scrollOffset + 3, Math.max(totalContentLines - 3, 0));
                userScrolled = true;
            } else if (btn == MouseMessage.MouseButton.MouseButtonWheelDown) {
                scrollOffset = Math.max(scrollOffset - 3, 0);
                userScrolled = scrollOffset > 0;
            }
            return UpdateResult.from(this);
        }

        // ── 代理串流事件──────────────────────────────────────
        if (msg instanceof AgentEventMessage) {
            if (streaming) spinnerFrame++;
            return handleAgentEvents();
        }

        if (msg instanceof MailboxPollMessage) {
            if (streaming) {
                var poll = Command.tick(Duration.ofSeconds(2), t -> new MailboxPollMessage());
                return UpdateResult.from(this, poll);
            }
            var notes = new java.util.ArrayList<String>();
            notes.addAll(io.github.shymoy.termagent.teams.TeammateRunner.drainLeadMailbox(teamManager));
            if (subAgentTaskManager != null) {
                for (var n : subAgentTaskManager.drainNotifications()) {
                    notes.add("<task-notification>\n<task_id>" + n.taskId() + "</task_id>\n"
                            + "<status>" + n.status() + "</status>\n"
                            + "<result>" + n.output() + "</result>\n</task-notification>");
                }
            }
            if (notes.isEmpty()) {
                var poll = Command.tick(Duration.ofSeconds(2), t -> new MailboxPollMessage());
                return UpdateResult.from(this, poll);
            }
            for (String note : notes) {
                conversation.addSystemReminder(note);
            }
            streaming = true;
            thinkingStartMs = System.currentTimeMillis();
            thinkingVerb = SpinnerVerbs.random();
            streamBuf.setLength(0);
            toolBlocks.clear();
            agentRun = agent.runCancellable(conversation);
            agentQueue = agentRun.queue();
            var pollCmd = Command.tick(POLL_INTERVAL, t -> new AgentEventMessage());
            return UpdateResult.from(this, pollCmd);
        }

        // ── 权限对话框────────────────────────────────────────────
        if (msg instanceof KeyPressMessage kpm && permDialog) {
            return handlePermDialogKey(kpm);
        }

        // ── 快退对话────────────────────────────────────────────────
        if (msg instanceof KeyPressMessage kpm && rewindDialog) {
            return handleRewindKey(kpm);
        }

        // ── 计划批准对话框────────────────────────────────────────
        if (msg instanceof KeyPressMessage kpm && planApprovalDialog.isActive()) {
            return handlePlanApprovalKey(kpm);
        }

        // ── 询问用户对话框──────────────────────────────────────────────
        if (msg instanceof KeyPressMessage kpm && askUserDialogState.isActive()) {
            return handleAskUserDialogKey(kpm);
        }

        // ── 调度至特定状态处理程序──────────────────────────
        if (msg instanceof KeyPressMessage kpm) {
            return switch (state) {
                case PROVIDER_SELECT -> handleProviderSelectKey(kpm);
                case CHAT -> handleChatKey(kpm);
                case RESUME -> handleResumeKey(kpm);
            };
        }

        return UpdateResult.from(this);
    }

    /** 根据当前应用状态选择对应视图；该方法只渲染状态，不执行耗时业务。 */
    @Override
    public String view() {
        if (!ready) return "";
        return switch (state) {
            case PROVIDER_SELECT -> viewProviderSelect();
            case CHAT -> viewChat();
            case RESUME -> viewResume();
        };
    }

    // ──────────────────────────────────────────────────────────────────────

    // 供应商选择

    // ──────────────────────────────────────────────────────────────────────

    private UpdateResult<TermAgentModel> handleProviderSelectKey(KeyPressMessage kpm) {
        String key = kpm.key();
        return switch (key) {
            case "up", "k" -> {
                if (providerCursor > 0) providerCursor--;
                yield UpdateResult.from(this);
            }
            case "down", "j" -> {
                if (providerCursor < providers.size() - 1) providerCursor++;
                yield UpdateResult.from(this);
            }
            case "enter" -> {
                if (providers.isEmpty()) yield UpdateResult.from(this);
                selectedProvider = providers.get(providerCursor);
                initializeProvider();
                state = AppState.CHAT;
                bannerPrinted = true;
                yield UpdateResult.from(this, Command.println(renderBanner() + "\n"));
            }
            default -> UpdateResult.from(this);
        };
    }

    /**
     * 用户选定模型后组装一次会话需要的运行组件：客户端、工具、权限、
     * 文件状态、Agent、MCP 和技能。初始化完成后，聊天页只需驱动 Agent。
     */
    private void initializeProvider() {
        try {
            String workDir = System.getProperty("user.dir");

            memoryManager = new MemoryManager(workDir);

            var env = PromptBuilder.detectEnvironment(selectedProvider.getModel());
            instructionsContent = MemoryManager.loadInstructions(workDir);
            memoryContentField = buildMemorySection();
            var options = new PromptBuilder.BuildOptions(null, null, null);
            String systemPrompt = PromptBuilder.buildSystemPrompt(env, options);

            client = LlmClient.create(selectedProvider, systemPrompt);
            String protocol = selectedProvider.getProtocol();

            registry = ToolRegistry.createDefault();

            registry.register(new ToolSearchTool(registry, protocol));
            var exitPlanTool = new io.github.shymoy.termagent.tool.impl.ExitPlanModeTool();
            exitPlanTool.setIsPlanMode(() -> permChecker != null && permChecker.getMode() == PermissionMode.PLAN);
            exitPlanTool.setPlanExists(() -> io.github.shymoy.termagent.plan.PlanFile.planExists());
            registry.register(exitPlanTool);
            askUserTool = new AskUserTool();
            registry.register(askUserTool);
            agentToolRef = new AgentTool(client, registry, protocol, selectedProvider);
            subAgentTaskManager = new SubAgentTaskManager();
            agentToolRef.setProgressListener(this::handleSubAgentProgress);
            agentToolRef.setTaskManager(subAgentTaskManager);
            registry.register(agentToolRef);

            // 注册 Worktree 工具，并把同一个管理器交给子 Agent 使用。
            var worktreeManager = new io.github.shymoy.termagent.worktree.WorktreeManager(
                    workDir, java.util.List.of(), 720);
            agentToolRef.setWorktreeManager(worktreeManager);
            registry.register(new io.github.shymoy.termagent.tool.impl.EnterWorktreeTool(worktreeManager, sessionId));
            registry.register(new io.github.shymoy.termagent.tool.impl.ExitWorktreeTool(worktreeManager));
            // 恢复上次异常退出时遗留的 Worktree 会话。
            var savedSession = io.github.shymoy.termagent.worktree.WorktreeSessionStore.load(workDir);
            if (savedSession != null && java.nio.file.Files.isDirectory(java.nio.file.Path.of(savedSession.worktreePath()))) {
                io.github.shymoy.termagent.worktree.WorktreeSessionStore.restoreSession(savedSession);
            }

            taskList = new TaskList("default", workDir);
            registry.register(new TaskTools.TaskCreateTool(taskList));
            registry.register(new TaskTools.TaskGetTool(taskList));
            registry.register(new TaskTools.TaskListTool(taskList));
            registry.register(new TaskTools.TaskUpdateTool(taskList));

            // 注册团队协作工具。
            agentToolRef.setTeamManager(teamManager);
            registry.register(new io.github.shymoy.termagent.teams.TeamTools.TeamCreateTool(teamManager));
            registry.register(new io.github.shymoy.termagent.teams.TeamTools.TeamDeleteTool(teamManager));
            registry.register(new io.github.shymoy.termagent.teams.TeamTools.SendMessageTool(teamManager, "lead"));

            permChecker = new PermissionChecker(
                    PermissionMode.DEFAULT, Path.of(workDir));

            // 初始化 OS 级沙箱
            sandboxInstance = io.github.shymoy.termagent.sandbox.SandboxFactory.create();
            if (sandboxInstance != null && sandboxInstance.isAvailable()) {
                // 默认沙箱配置：允许写入项目目录和 /tmp，禁止写入敏感配置路径
                sandboxConfig = new io.github.shymoy.termagent.sandbox.SandboxConfig(
                        java.util.List.of(workDir, "/tmp"),
                        permChecker.getDenyWrite(),
                        true
                );
                // 将沙箱注入 BashTool
                for (var t : registry.listTools()) {
                    if (t instanceof io.github.shymoy.termagent.tool.impl.BashTool bt) {
                        bt.setSandbox(sandboxInstance);
                        bt.setSandboxConfig(sandboxConfig);
                    }
                }
            }

            sessionId = io.github.shymoy.termagent.session.SessionManager.newId();
            // 启动时清理超过 30 天的过期 session 文件
            io.github.shymoy.termagent.session.SessionManager.cleanExpiredSessions(workDir);
            fileHistory = new io.github.shymoy.termagent.filehistory.FileHistory(workDir, sessionId);
            // 三个文件工具共享同一个状态缓存，形成 ReadFile 记录、Edit/Write 校验并刷新的闭环。
            var fileStateCache = new io.github.shymoy.termagent.tool.FileStateCache();
            for (var t : registry.listTools()) {
                if (t instanceof io.github.shymoy.termagent.tool.impl.EditFileTool ef) { ef.setFileHistory(fileHistory); ef.setFileStateCache(fileStateCache); }
                if (t instanceof io.github.shymoy.termagent.tool.impl.WriteFileTool wf) { wf.setFileHistory(fileHistory); wf.setFileStateCache(fileStateCache); }
                if (t instanceof io.github.shymoy.termagent.tool.impl.ReadFileTool rf) rf.setFileStateCache(fileStateCache);
            }
            agent = new Agent(client, registry, protocol, selectedProvider);
            agent.setFileHistory(fileHistory);
            agent.setInstructions(instructionsContent);
            agent.setMemoryContent(memoryContentField);
            agent.setChecker(permChecker);
            agent.setWorkDir(workDir);
            agent.setSessionId(sessionId);

            // 每轮请求前提取团队邮箱和后台子任务通知，并作为 reminder 注入 Agent。
            final var teamMgrCapture = teamManager;
            agent.setNotificationFn(() -> {
                var notes = new java.util.ArrayList<String>();
                // 提取团队成员发给主 Agent 的消息。
                notes.addAll(io.github.shymoy.termagent.teams.TeammateRunner.drainLeadMailbox(teamMgrCapture));
                // 提取后台子任务的完成通知。
                if (subAgentTaskManager != null) {
                    for (var n : subAgentTaskManager.drainNotifications()) {
                        notes.add("<task-notification>Task %s: %s (%s)</task-notification>"
                                .formatted(n.taskId(), n.name(), n.status()));
                    }
                }
                return notes;
            });

            // 存在团队时，主 Agent 只保留协调器允许使用的工具。
            agent.setToolNameFilter(name -> {
                if (teamMgrCapture.listTeams().isEmpty()) return true;
                return io.github.shymoy.termagent.teams.Coordinator.isCoordinatorTool(name);
            });

            if (!mcpServers.isEmpty()) {
                mcpConnecting = true;
                final var registryRef = registry;
                Thread.ofVirtual().name("mcp-connect").start(() -> {
                    try {
                        mcpManager = new McpManager(mcpServers);
                        var result = mcpManager.connectAll();
                        for (var t : result.tools()) registryRef.register(t);
                        mcpServerInfo = "Connected to %d MCP server(s), %d tools registered".formatted(
                            result.servers().size(), result.tools().size());
                        synchronized (chatMessages) {
                            for (var e : result.errors()) {
                                chatMessages.add(new ChatMessage("error", "MCP: " + e));
                            }
                        }
                        var mcpParts = new ArrayList<String>();
                        for (var s : result.servers()) {
                            var sb2 = new StringBuilder();
                            sb2.append("## ").append(s.name()).append("\n");
                            if (s.instructions() != null && !s.instructions().isBlank()) {
                                sb2.append(s.instructions()).append("\n");
                            }
                            var toolNames = registryRef.listTools().stream()
                                    .filter(t -> t.name().startsWith("mcp__" + s.name() + "__"))
                                    .map(io.github.shymoy.termagent.tool.Tool::name)
                                    .toList();
                            if (!toolNames.isEmpty()) {
                                sb2.append("\nAvailable tools: ").append(String.join(", ", toolNames));
                            }
                            mcpParts.add(sb2.toString());
                        }
                        if (!mcpParts.isEmpty()) {
                            mcpInstructions = "# MCP Server Instructions\n\n"
                                    + "The following MCP servers are connected. Use their tools when the user asks.\n\n"
                                    + String.join("\n\n", mcpParts);
                        }
                    } catch (Exception e) {
                        synchronized (chatMessages) {
                            chatMessages.add(new ChatMessage("error", "MCP init failed: " + e.getMessage()));
                        }
                    } finally {
                        mcpConnecting = false;
                    }
                });
            }

            skillCatalog = new SkillCatalog();
            var skillDir = AppPaths.readableProject(Path.of(workDir), "skills");
            if (java.nio.file.Files.isDirectory(skillDir)) {
                skillCatalog.loadFromDirectory(skillDir);
            }

            wireSkillsToAgent();

            agent.setHookEngine(hookEngine);
            fireHook(HookEngine.EventName.SESSION_START, null, null);

        } catch (Exception e) {
            chatMessages.add(new ChatMessage("error",
                    "Failed to initialize: " + e.getMessage()));
        }
    }

    private void wireSkillsToAgent() {
        if (skillCatalog == null || cmdRegistry == null) return;
        for (var meta : skillCatalog.list()) {
            registerSkillCommand(meta.name());
        }
    }

    private void registerSkillCommand(String name) {
        if (cmdRegistry.find(name).isPresent()) return;
        var skill = skillCatalog.get(name);
        if (skill.isEmpty()) return;

        var meta = skill.get().meta();
        var cmd = new io.github.shymoy.termagent.command.Command(name, meta.description() + " [skill]",
                              new String[]{}, io.github.shymoy.termagent.command.Command.CommandType.PROMPT, false);

        String captured = name;
        cmdRegistry.register(cmd, ctx -> {
            var s = skillCatalog.get(captured);
            if (s.isEmpty()) return "[skill error] not found: " + captured;
            return s.get().promptBody();
        });
    }

    private String renderBanner() {
        String modelName = selectedProvider != null ? selectedProvider.getModel() : "";
        String workDir = System.getProperty("user.dir");
        return Styles.banner.render(" /\\_/\\    ") + Styles.bannerDim.render(VERSION) + "\n" +
               Styles.banner.render("( o.o )   ") + Styles.bannerDim.render(modelName) + "\n" +
               Styles.banner.render(" > ^ <    ") + Styles.bannerDim.render(workDir);
    }

    private String viewProviderSelect() {
        var sb = new StringBuilder();
        sb.append("\n");
        sb.append(renderBanner());
        sb.append("\n\n");
        sb.append(Styles.selectLabel.render("  Select a Provider"));
        sb.append("\n\n");

        for (int i = 0; i < providers.size(); i++) {
            var p = providers.get(i);
            String label = p.getName() + " (" + p.getModel() + ")";
            if (i == providerCursor) {
                sb.append(Styles.selectedItem.render("  ❯ " + label));
            } else {
                sb.append(Styles.normalItem.render("    " + label));
            }
            sb.append("\n");
        }

        sb.append("\n");
        sb.append(Styles.statusBar.render("  j/k to move · enter to select · ctrl+c to quit"));
        sb.append("\n");
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────────────
    // Chat
    // ────────────────────────────────────────────────────────────────────

    private UpdateResult<TermAgentModel> handleChatKey(KeyPressMessage kpm) {
        String key = kpm.key();

        // shift+tab：循环权限模式
        if (key.equals("shift+tab") && !streaming && permChecker != null) {
            var current = permChecker.getMode();
            var next = switch (current) {
                case DEFAULT -> PermissionMode.ACCEPT_EDITS;
                case ACCEPT_EDITS -> PermissionMode.PLAN;
                case PLAN -> PermissionMode.BYPASS;
                case BYPASS -> PermissionMode.DEFAULT;
            };
            permChecker.setMode(next);
            // 状态栏已显示模式 - 无需聊天消息
            return UpdateResult.from(this);
        }

        // ctrl+o：切换工具块折叠/展开
        if (key.equals("ctrl+o")) {
            for (var msg : chatMessages) {
                if ("tool_group".equals(msg.role) || "tool_collapsed".equals(msg.role)
                        || "sub_agent".equals(msg.role)) {
                    msg.expanded = !msg.expanded;
                }
            }
            return UpdateResult.from(this);
        }

        // ctrl+j：插入换行符而不发送
        if (key.equals("ctrl+j")) {
            inputBuffer.append('\n');
            return UpdateResult.from(this);
        }

        // 流媒体期间逃脱：将代理移动到后台
        if (key.equals("escape") && streaming) {
            chatMessages.add(new ChatMessage("system",
                    "Agent moved to background. You will be notified when it completes."));
            streaming = false;
            agentQueue = null;
            String commitText = renderMessagesRange(committedUpTo, chatMessages.size());
            committedUpTo = chatMessages.size();
            return !commitText.isEmpty()
                    ? UpdateResult.from(this, Command.println(commitText))
                    : UpdateResult.from(this);
        }

        // pgup/pgdown/home/end：视口滚动
        if (key.equals("pgup")) {
            scrollOffset = Math.min(scrollOffset + Math.max(height - 6, 1), Math.max(totalContentLines - 3, 0));
            userScrolled = true;
            return UpdateResult.from(this);
        }
        if (key.equals("pgdown")) {
            scrollOffset = Math.max(scrollOffset - Math.max(height - 6, 1), 0);
            userScrolled = scrollOffset > 0;
            return UpdateResult.from(this);
        }
        if (key.equals("home")) {
            scrollOffset = Math.max(totalContentLines - 3, 0);
            userScrolled = true;
            return UpdateResult.from(this);
        }
        if (key.equals("end")) {
            scrollOffset = 0;
            userScrolled = false;
            return UpdateResult.from(this);
        }

        // 斜线菜单打开：手柄导航
        if (slashMenuOpen) {
            return switch (key) {
                case "up" -> {
                    if (slashCursor > 0) slashCursor--;
                    yield UpdateResult.from(this);
                }
                case "down" -> {
                    if (slashCursor < slashMatches.size() - 1) slashCursor++;
                    yield UpdateResult.from(this);
                }
                case "enter", "tab" -> {
                    if (!slashMatches.isEmpty()) {
                        var cmd = slashMatches.get(slashCursor);
                        inputBuffer.setLength(0);
                        slashMenuOpen = false;
                        yield executeSlashCommand(cmd, "");
                    }
                    yield UpdateResult.from(this);
                }
                case "escape" -> {
                    slashMenuOpen = false;
                    yield UpdateResult.from(this);
                }
                default -> {
                    if (key.equals("backspace") || key.equals("ctrl+h")) {
                        if (!inputBuffer.isEmpty()) inputBuffer.deleteCharAt(inputBuffer.length() - 1);
                        updateSlashMenu();
                    } else {
                        char[] runes = kpm.runes();
                        if (runes != null && runes.length > 0) {
                            for (char ch : runes) if (ch >= 32) inputBuffer.append(ch);
                        } else if (key.length() == 1 && key.charAt(0) >= 32) {
                            inputBuffer.append(key.charAt(0));
                        }
                        updateSlashMenu();
                    }
                    yield UpdateResult.from(this);
                }
            };
        }

        // @文件菜单打开：处理导航
        if (atMenuOpen) {
            return switch (key) {
                case "up" -> { if (atCursor > 0) atCursor--; yield UpdateResult.from(this); }
                case "down" -> { if (atCursor < atMatches.size() - 1) atCursor++; yield UpdateResult.from(this); }
                case "enter", "tab" -> {
                    if (!atMatches.isEmpty()) {
                        String selected = atMatches.get(atCursor);
                        int atIdx = inputBuffer.lastIndexOf("@");
                        if (atIdx >= 0) inputBuffer.replace(atIdx, inputBuffer.length(), "@" + selected + " ");
                    }
                    atMenuOpen = false;
                    yield UpdateResult.from(this);
                }
                case "escape" -> { atMenuOpen = false; yield UpdateResult.from(this); }
                default -> {
                    if (key.equals("backspace") || key.equals("ctrl+h")) {
                        if (!inputBuffer.isEmpty()) inputBuffer.deleteCharAt(inputBuffer.length() - 1);
                        updateAtMenu();
                    } else {
                        char[] runes = kpm.runes();
                        if (runes != null && runes.length > 0) {
                            for (char ch : runes) if (ch >= 32) inputBuffer.append(ch);
                        } else if (key.length() == 1 && key.charAt(0) >= 32) {
                            inputBuffer.append(key.charAt(0));
                        }
                        updateAtMenu();
                    }
                    yield UpdateResult.from(this);
                }
            };
        }

        // Enter 发送消息或斜线命令
        if (key.equals("enter")) {
            if (inputBuffer.isEmpty()) return UpdateResult.from(this);
            if (streaming) {
                cancelCurrentAgentRun();
                savePartialResponse();
            }
            String text = inputBuffer.toString().trim();
            if (text.startsWith("/")) {
                String[] parts = text.substring(1).split("\\s+", 2);
                String cmdName = parts[0];
                String cmdArgs = parts.length > 1 ? parts[1] : "";
                var cmd = cmdRegistry.find(cmdName);
                if (cmd.isPresent()) {
                    inputBuffer.setLength(0);
                    return executeSlashCommand(cmd.get(), cmdArgs);
                } else {
                    inputBuffer.setLength(0);
                    chatMessages.add(new ChatMessage("error",
                            "Unknown command: /" + cmdName + " — type /help to see available commands"));
                    return UpdateResult.from(this);
                }
            }
            return sendUserMessage();
        }

        // 历史导航（不流式传输时向上/向下）
        if (!streaming && (key.equals("up") || key.equals("down"))) {
            var entries = historyStore.getEntries();
            if (!entries.isEmpty()) {
                if (key.equals("up")) {
                    if (historyIndex < 0) {
                        historyDraft = inputBuffer.toString();
                        historyIndex = entries.size() - 1;
                    } else if (historyIndex > 0) {
                        historyIndex--;
                    }
                    inputBuffer.setLength(0);
                    inputBuffer.append(entries.get(historyIndex));
                } else {
                    if (historyIndex >= 0) {
                        historyIndex++;
                        if (historyIndex >= entries.size()) {
                            historyIndex = -1;
                            inputBuffer.setLength(0);
                            inputBuffer.append(historyDraft);
                        } else {
                            inputBuffer.setLength(0);
                            inputBuffer.append(entries.get(historyIndex));
                        }
                    }
                }
            }
            return UpdateResult.from(this);
        }

        // 退格键（Linux 上为 DEL 0x7F/macOS，Windows 上为 BS 0x08 又名 ctrl+h）
        if (key.equals("backspace") || key.equals("ctrl+h")) {
            if (!inputBuffer.isEmpty()) {
                inputBuffer.deleteCharAt(inputBuffer.length() - 1);
                updateSlashMenu();
            }
            return UpdateResult.from(this);
        }

        // Space
        if (key.equals("space") || key.equals(" ")) {
            inputBuffer.append(' ');
            return UpdateResult.from(this);
        }

        // Tab
        if (key.equals("tab")) {
            inputBuffer.append("    ");
            return UpdateResult.from(this);
        }

        // 常规字符输入 — 使用 runes() 来支持 Unicode（中文等）
        char[] runes = kpm.runes();
        if (runes != null && runes.length > 0) {
            for (char ch : runes) {
                if (ch >= 32) {
                    inputBuffer.append(ch);
                }
            }
            if (inputBuffer.length() == 1 && inputBuffer.charAt(0) == '/') {
                updateSlashMenu();
            } else if (slashMenuOpen) {
                updateSlashMenu();
            }
            // 检查@触发器
            String text = inputBuffer.toString();
            int atIdx = text.lastIndexOf('@');
            if (atIdx >= 0 && !text.substring(atIdx).contains(" ")) {
                updateAtMenu();
            }
            return UpdateResult.from(this);
        }

        // 后备：单个可打印密钥字符串 (ASCII)
        if (key.length() == 1 && key.charAt(0) >= 32) {
            inputBuffer.append(key.charAt(0));
            if (key.charAt(0) == '/' && inputBuffer.length() == 1) {
                updateSlashMenu();
            } else if (slashMenuOpen) {
                updateSlashMenu();
            }
            if (key.charAt(0) == '@') updateAtMenu();
            return UpdateResult.from(this);
        }

        return UpdateResult.from(this);
    }

    private void updateSlashMenu() {
        String text = inputBuffer.toString();
        if (text.startsWith("/") && !text.contains(" ")) {
            String prefix = text.substring(1);
            slashMatches = cmdRegistry.search(prefix);
            slashMenuOpen = !slashMatches.isEmpty();
            slashCursor = 0;
        } else {
            slashMenuOpen = false;
        }
    }

    private void updateAtMenu() {
        String text = inputBuffer.toString();
        int atIdx = text.lastIndexOf('@');
        if (atIdx < 0) { atMenuOpen = false; return; }
        String prefix = text.substring(atIdx + 1);
        if (prefix.contains(" ")) { atMenuOpen = false; return; }

        var skipDirs = Set.of(".git", "node_modules", ".venv", "__pycache__",
                ".termagent", ".mewcode", "build", ".gradle");
        var matches = new ArrayList<String>();
        try {
            var dir = Path.of(System.getProperty("user.dir"));
            try (var stream = java.nio.file.Files.list(dir)) {
                stream.filter(p -> {
                    String name = p.getFileName().toString();
                    if (name.startsWith(".") && prefix.isEmpty()) return false;
                    if (skipDirs.contains(name)) return false;
                    return prefix.isEmpty() || name.toLowerCase().startsWith(prefix.toLowerCase());
                }).sorted().limit(10).forEach(p -> matches.add(p.getFileName().toString()));
            }
        } catch (Exception ignored) {}

        atMatches = matches;
        atMenuOpen = !matches.isEmpty();
        atCursor = 0;
    }

    private static final Set<String> AT_SKIP_DIRS = Set.of(
            ".git", "node_modules", ".venv", "__pycache__", ".termagent", ".mewcode",
            "build", ".gradle");

    private UpdateResult<TermAgentModel> executeSlashCommand(io.github.shymoy.termagent.command.Command cmd, String args) {
        return switch (cmd.type()) {
            case LOCAL -> {
                var ctx = buildCommandContext(args);
                String output = cmdRegistry.execute(cmd.name(), ctx);
                if (output != null && !output.isEmpty()) {
                    chatMessages.add(new ChatMessage("system", output));
                }
                yield UpdateResult.from(this);
            }
            case LOCAL_UI -> {
                switch (cmd.name()) {
                    case "clear" -> {
                        chatMessages.clear();
                        committedUpTo = 0;
                        conversation = new ConversationManager();
                    }
                    case "compact" -> {
                        if (agent != null) {
                            try {
                                var schemas = agent.getRegistry() != null
                                        ? agent.getRegistry().getAllSchemas(agent.getProtocol())
                                        : java.util.List.<java.util.Map<String, Object>>of();
                                String msg = io.github.shymoy.termagent.compact.ContextCompactor.forceCompact(
                                        conversation, client, selectedProvider.resolvedContextWindow(),
                                        System.getProperty("user.dir"), sessionId,
                                        agent.getRecoveryState(), schemas);
                                chatMessages.add(new ChatMessage("system", "⟳ " + msg));
                            } catch (Exception e) {
                                chatMessages.add(new ChatMessage("error", "Compact failed: " + e.getMessage()));
                            }
                        }
                    }
                    case "plan" -> {
                        if (permChecker != null) {
                            prePlanMode = permChecker.getMode();
                            permChecker.setMode(PermissionMode.PLAN);
                            String planPath = PlanFile.getOrCreatePlanPath(
                                    System.getProperty("user.dir"));
                            chatMessages.add(new ChatMessage("system",
                                    "Entered Plan mode. Plan file: %s\nExplore the codebase and design your approach."
                                            .formatted(planPath)));
                            // 曾退出过 plan mode 且 plan 文件存在 → 注入 reentry reminder
                            if (hasExitedPlanMode && PlanFile.planExists()) {
                                String reentryReminder = PlanModePrompt.buildReentryReminder(planPath);
                                conversation.addSystemReminder(reentryReminder);
                                hasExitedPlanMode = false;
                            }
                        }
                    }
                    case "resume" -> {
                        resumeSessions = SessionManager.listSessions(System.getProperty("user.dir"));
                        resumeFiltered = new ArrayList<>(resumeSessions);
                        resumeCursor = 0;
                        resumeSearch = "";
                        state = AppState.RESUME;
                    }
                    case "rewind" -> {
                        if (fileHistory == null || !fileHistory.hasSnapshots()) {
                            chatMessages.add(new ChatMessage("system", "No checkpoints to rewind to."));
                        } else {
                            rewindSnapshots = fileHistory.getSnapshots();
                            rewindCursor = rewindSnapshots.size() - 1;
                            rewindPhase = 0;
                            rewindOptionCursor = 0;
                            rewindDialog = true;
                        }
                    }
                }
                yield UpdateResult.from(this);
            }
            case PROMPT -> {
                boolean isSkill = cmd.description() != null && cmd.description().endsWith("[skill]");
                String prompt = cmdRegistry.execute(cmd.name(), buildCommandContext(args));

                String displayText = "/" + cmd.name();
                if (args != null && !args.isEmpty()) displayText += " " + args;
                chatMessages.add(new ChatMessage("user", displayText));
                String userLine = Styles.prompt.render("❯ ") + Styles.userText.render(displayText) + "\n";
                committedUpTo = chatMessages.size();
                SessionManager.saveMessage(System.getProperty("user.dir"), sessionId, "user", displayText);
                userHasSentMessage = true;

                if (prompt != null && !prompt.isBlank()) {
                    conversation.addUserMessage(prompt);
                }
                if (args != null && !args.isEmpty()) {
                    conversation.addUserMessage(args);
                }

                streaming = true;
                streamBuf.setLength(0);
                thinkingVerb = SpinnerVerbs.random();
                thinkingStartMs = System.currentTimeMillis();

                fireHook(HookEngine.EventName.TURN_START, null, null);

                try {
                    agentRun = agent.runCancellable(conversation);
                    agentQueue = agentRun.queue();
                    if (askUserTool != null) askUserTool.setEventQueue(agentQueue);
                } catch (Exception e) {
                    streaming = false;
                    chatMessages.add(new ChatMessage("error", "Agent error: " + e.getMessage()));
                    yield UpdateResult.from(this);
                }

                var pollCmd = Command.tick(POLL_INTERVAL, t -> new AgentEventMessage());
                if (isSkill) {
                    String loadedLine = Styles.system.render(
                        "  skill(" + cmd.name() + ")\n  Successfully loaded skill");
                    yield UpdateResult.from(this, Command.batch(Command.println(userLine), Command.println(loadedLine), pollCmd));
                }
                yield UpdateResult.from(this, Command.batch(Command.println(userLine), pollCmd));
            }
        };
    }

    private io.github.shymoy.termagent.command.CommandContext buildCommandContext(String args) {
        String workDir = System.getProperty("user.dir");
        String model = selectedProvider != null ? selectedProvider.getModel() : "unknown";
        return new io.github.shymoy.termagent.command.CommandContext(
                args,
                workDir,
                model,
                () -> permChecker != null ? permChecker.getMode().name().toLowerCase() : "default",
                () -> registry != null ? registry.listTools().size() : 0,
                () -> new int[]{totalInput, totalOutput},
                () -> memoryManager != null ? memoryManager.getMemories() : java.util.List.of(),
                () -> { if (memoryManager != null) memoryManager.clear(); },
                () -> sessionId != null ? "Session: " + sessionId : "No active session",
                () -> skillCatalog != null
                        ? skillCatalog.list().stream().map(s -> s.name()).toList()
                        : java.util.List.of(),
                () -> mcpServerInfo,
                this::getSandboxStatus,
                sandboxInstance != null && sandboxInstance.isAvailable() ? this::switchSandboxMode : null
        );
    }

    /** 返回当前沙箱状态的文本描述 */
    private String getSandboxStatus() {
        if (sandboxInstance == null || !sandboxInstance.isAvailable()) {
            return "不可用（当前平台不支持或沙箱工具未安装）";
        }
        if (permChecker != null && permChecker.isSandboxEnabled()) {
            return "已开启（沙箱 + 自动放行）";
        }
        // 沙箱工具可用但未开启自动放行
        boolean bashHasSandbox = false;
        for (var t : registry.listTools()) {
            if (t instanceof io.github.shymoy.termagent.tool.impl.BashTool bt) {
                bashHasSandbox = true;
                break;
            }
        }
        return bashHasSandbox ? "已开启（沙箱 + 常规权限）" : "已关闭";
    }

    /**
     * 切换沙箱模式。
     * mode=1: 沙箱 + 自动放行  mode=2: 沙箱 + 常规权限  mode=3: 关闭沙箱
     */
    private void switchSandboxMode(int mode) {
        if (sandboxInstance == null || !sandboxInstance.isAvailable()) return;
        String workDir = System.getProperty("user.dir");

        switch (mode) {
            case 1 -> {
                // 开启沙箱 + 自动放行
                sandboxConfig = new io.github.shymoy.termagent.sandbox.SandboxConfig(
                        java.util.List.of(workDir, "/tmp"),
                        permChecker != null ? permChecker.getDenyWrite() : java.util.List.of(),
                        true
                );
                for (var t : registry.listTools()) {
                    if (t instanceof io.github.shymoy.termagent.tool.impl.BashTool bt) {
                        bt.setSandbox(sandboxInstance);
                        bt.setSandboxConfig(sandboxConfig);
                    }
                }
                if (permChecker != null) permChecker.setSandboxEnabled(true);
            }
            case 2 -> {
                // 开启沙箱 + 常规权限（不自动放行）
                sandboxConfig = new io.github.shymoy.termagent.sandbox.SandboxConfig(
                        java.util.List.of(workDir, "/tmp"),
                        permChecker != null ? permChecker.getDenyWrite() : java.util.List.of(),
                        true
                );
                for (var t : registry.listTools()) {
                    if (t instanceof io.github.shymoy.termagent.tool.impl.BashTool bt) {
                        bt.setSandbox(sandboxInstance);
                        bt.setSandboxConfig(sandboxConfig);
                    }
                }
                if (permChecker != null) permChecker.setSandboxEnabled(false);
            }
            case 3 -> {
                // 关闭沙箱
                for (var t : registry.listTools()) {
                    if (t instanceof io.github.shymoy.termagent.tool.impl.BashTool bt) {
                        bt.setSandbox(null);
                        bt.setSandboxConfig(null);
                    }
                }
                if (permChecker != null) permChecker.setSandboxEnabled(false);
            }
        }
    }

    /**
     * 提交输入框中的用户消息：同步更新界面和内部会话，创建 AgentEvent 队列，
     * 再让 Agent 在后台运行；UI 通过定时消息持续轮询该队列，不阻塞按键和渲染。
     */
    private UpdateResult<TermAgentModel> sendUserMessage() {
        String userText = inputBuffer.toString().trim();
        inputBuffer.setLength(0);
        userScrolled = false;
        scrollOffset = 0;

        if (userText.isEmpty()) {
            return UpdateResult.from(this);
        }

        if (conversation.getMessages().isEmpty() && memoryManager != null) {
            memoryManager.injectMemories(conversation);
        }

        chatMessages.add(new ChatMessage("user", userText));
        // 用户消息立即提交到 scrollback
        String userLine = Styles.prompt.render("❯ ") + Styles.userText.render(userText) + "\n";
        committedUpTo = chatMessages.size();

        userHasSentMessage = true;
        conversation.addUserMessage(userText);
        SessionManager.saveMessage(System.getProperty("user.dir"), sessionId, "user", userText);
        historyStore.append(userText);
        historyIndex = -1;

        if (!mcpInstructions.isEmpty() && !mcpInstructionsOk) {
            conversation.addSystemReminder(mcpInstructions);
            mcpInstructionsOk = true;
        }

        // 提前在虚拟线程中检索相关记忆，最多等待 8 秒，但不阻塞本次消息发送。
        var prefetchFuture = prefetchRelevantMemories(userText);

        if (agent == null) {
            chatMessages.add(new ChatMessage("error", "No agent configured."));
            return UpdateResult.from(this);
        }

        streaming = true;
        streamBuf.setLength(0);
        thinkingVerb = SpinnerVerbs.random();
        thinkingStartMs = System.currentTimeMillis();

        fireHook(HookEngine.EventName.TURN_START, null, null);

        // 预先创建 queue 并立即开始轮询；Agent.run 内部负责把主循环放到后台虚拟线程。
        var queue = new java.util.concurrent.LinkedBlockingQueue<io.github.shymoy.termagent.agent.AgentEvent>(64);
        agentQueue = queue;
        if (askUserTool != null) askUserTool.setEventQueue(queue);

        // 非阻塞 memory recall：prefetch future 传给 agent，工具执行后注入
        // 不再同步等 3 秒——与 Claude Code 一致
        agent.setMemoryRecallFuture(prefetchFuture);

        agentRun = agent.runCancellable(conversation, queue);

        Command pollCmd = Command.tick(POLL_INTERVAL, t -> new AgentEventMessage());
        return UpdateResult.from(this, Command.batch(Command.println(userLine), pollCmd));
    }

    // ────────────────────────────────────────────────────────────────────
    // Agent 流式事件处理
    // ────────────────────────────────────────────────────────────────────

    /**
     * 批量消费 AgentEvent 队列并转换成 UI 状态。
     * 文本增量进入 streamBuf，工具事件更新工具块，LoopComplete 负责结束轮询并提交本轮内容。
     */
    private UpdateResult<TermAgentModel> handleAgentEvents() {
        drainSubAgentProgress();

        if (agentQueue == null) return UpdateResult.from(this);

        var events = new ArrayList<AgentEvent>();
        agentQueue.drainTo(events);

        boolean loopDone = false;
        // 收集本轮需要 commit 到 scrollback 的内容
        boolean needsCommit = false;

        for (var event : events) {
            switch (event) {
                case AgentEvent.StreamText e -> streamBuf.append(e.text());
                case AgentEvent.ThinkingText e -> {}
                case AgentEvent.ThinkingComplete e -> {}
                case AgentEvent.ToolUseEvent e -> {
                    boolean found = false;
                    for (int i = 0; i < toolBlocks.size(); i++) {
                        if (toolBlocks.get(i).toolName().equals(e.toolName())
                                && toolBlocks.get(i).args() == null) {
                            toolBlocks.set(i, new ChatMessage.ToolBlockInfo(
                                    e.toolName(), e.args(), null, false, 0, false, true));
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        toolBlocks.add(new ChatMessage.ToolBlockInfo(
                                e.toolName(), e.args(), null, false, 0, false, true));
                    }
                }
                case AgentEvent.ToolResultEvent e -> {
                    if (!streamBuf.isEmpty()) {
                        chatMessages.add(new ChatMessage("assistant", streamBuf.toString()));
                        streamBuf.setLength(0);
                    }
                    for (int i = 0; i < toolBlocks.size(); i++) {
                        var tb = toolBlocks.get(i);
                        if (tb.toolName().equals(e.toolName()) && tb.loading()) {
                            toolBlocks.set(i, new ChatMessage.ToolBlockInfo(
                                    e.toolName(), tb.args(), e.output(), e.isError(),
                                    e.elapsed(), true, false));
                            break;
                        }
                    }
                    commitCompletedToolBlocks();
                    needsCommit = true;
                }
                case AgentEvent.TurnComplete e -> {
                    if (!streamBuf.isEmpty()) {
                        chatMessages.add(new ChatMessage("assistant", streamBuf.toString()));
                        streamBuf.setLength(0);
                    }
                    commitToolBlocks();
                }
                case AgentEvent.LoopComplete e -> {
                    loopDone = true;
                    if (!streamBuf.isEmpty()) {
                        chatMessages.add(new ChatMessage("assistant", streamBuf.toString()));
                        SessionManager.saveMessage(System.getProperty("user.dir"), sessionId, "assistant", streamBuf.toString());
                        streamBuf.setLength(0);
                    }
                    commitToolBlocks();
                    double totalTime = (System.currentTimeMillis() - thinkingStartMs) / 1000.0;
                    String pastVerb = SpinnerVerbs.pastTense(thinkingVerb);
                    chatMessages.add(new ChatMessage("thinking",
                            "✻ %s for %.1fs".formatted(pastVerb, totalTime)));
                    fireHook(HookEngine.EventName.TURN_END, null, null);
                }
                case AgentEvent.UsageEvent e -> {
                    totalInput = e.inputTokens();
                    totalOutput = e.outputTokens();
                }
                case AgentEvent.ErrorEvent e -> {
                    chatMessages.add(new ChatMessage("error", e.message()));
                    needsCommit = true;
                }
                case AgentEvent.CompactEvent e ->
                        chatMessages.add(new ChatMessage("system", "⟳ " + e.message()));
                case AgentEvent.RetryEvent e -> {
                    String msg = "↻ Retrying: " + e.reason();
                    if (e.waitMs() > 0) msg += " (waiting %dms)".formatted(e.waitMs());
                    chatMessages.add(new ChatMessage("system", msg));
                }
                case AgentEvent.PermissionRequestEvent e -> {
                    permDialog = true;
                    permToolName = e.toolName();
                    permDesc = e.description();
                    permCursor = 0;
                    pendingPermission = e.future();
                }
                case AgentEvent.AskUserRequestEvent e -> {
                    askUserDialogState.activate(e.questions());
                    askUserFuture = e.future();
                }
            }
        }

        if (!userScrolled) {
            scrollOffset = 0;
        }

        // LoopComplete 或有工具/错误完成 → 提交到 scrollback
        if (loopDone || needsCommit) {
            String commitText = renderMessagesRange(committedUpTo, chatMessages.size());
            committedUpTo = chatMessages.size();
            Command printCmd = !commitText.isEmpty() ? Command.println(commitText) : null;

            if (loopDone) {
                streaming = false;
                agentQueue = null;
                agentRun = null;
                drainTaskNotifications();
                triggerMemoryExtraction();
                if (permChecker != null && permChecker.getMode() == PermissionMode.PLAN) {
                    planApprovalDialog.activate();
                }
                if (teamManager != null) {
                    var mailboxPoll = Command.tick(Duration.ofSeconds(2), t -> new MailboxPollMessage());
                    return UpdateResult.from(this, printCmd != null ? Command.batch(printCmd, mailboxPoll) : mailboxPoll);
                }
                return printCmd != null ? UpdateResult.from(this, printCmd) : UpdateResult.from(this);
            }
            // 工具/错误完成但未结束循环 → commit + 继续轮询
            Command pollCmd = Command.tick(POLL_INTERVAL, t -> new AgentEventMessage());
            return UpdateResult.from(this, printCmd != null ? Command.batch(printCmd, pollCmd) : pollCmd);
        }

        Command pollCmd = Command.tick(POLL_INTERVAL, t -> new AgentEventMessage());
        return UpdateResult.from(this, pollCmd);
    }

    private void fireHook(HookEngine.EventName event, String toolName, Map<String, Object> args) {
        if (hookEngine == null) return;
        var ctx = new HookEngine.HookContext(event, toolName, args, null, null, null);
        hookEngine.runHooks(ctx);
    }

    private String buildMemorySection() {
        if (memoryManager == null) return "";
        var memories = memoryManager.getMemories();
        if (memories.isEmpty()) return "";
        var sb = new StringBuilder("# Auto Memory\n\n");
        for (String mem : memories) {
            sb.append(mem).append("\n\n");
        }
        return sb.toString();
    }

    private void triggerMemoryExtraction() {
        if (memoryManager == null || client == null) return;
        if (!memoryManager.shouldExtract()) return;
        Thread.startVirtualThread(() -> memoryManager.extract(client, conversation));
    }

    // ──────────────────────────────────────────────────────────────────────

    // 内存调用预取

    // ──────────────────────────────────────────────────────────────────────

    /**

     * 在虚拟线程中运行召回选择器并返回 future

     * 这将与呈现的系统提醒字符串（或 ""

     * 如果没有选择任何内容/选择器超时）。激发出新鲜感

     * 每次调用都会进行侧查询 LlmClient，因此选择器的 SYSTEM 提示为

     * 独立于主要对话的系统提示。

     */
    private CompletableFuture<String> prefetchRelevantMemories(String query) {
        if (memoryManager == null || selectedProvider == null) {
            return CompletableFuture.completedFuture("");
        }
        var provider = selectedProvider;
        var userDir = memoryManager.userMemDir();
        var projectDir = memoryManager.projectMemDir();

        return CompletableFuture.supplyAsync(() -> {
            MemoryRecall.SelectorFn selector = (systemPrompt, userMessage) -> {
                LlmClient sideClient = LlmClient.create(provider, systemPrompt);
                ConversationManager miniConv = new ConversationManager();
                miniConv.addUserMessage(userMessage);
                BlockingQueue<io.github.shymoy.termagent.llm.StreamEvent> events = sideClient.stream(miniConv, null);
                var sb = new StringBuilder();
                while (true) {
                    var event = events.take();
                    if (event instanceof io.github.shymoy.termagent.llm.StreamEvent.TextDelta td) {
                        sb.append(td.text());
                    } else if (event instanceof io.github.shymoy.termagent.llm.StreamEvent.StreamEnd
                            || event instanceof io.github.shymoy.termagent.llm.StreamEvent.Error) {
                        break;
                    }
                }
                return sb.toString();
            };
            var results = MemoryRecall.findRelevantMemories(
                    query, userDir, projectDir, null, null, selector);
            return MemoryRecall.renderReminder(results);
        }, runnable -> {
            // 在虚拟线程上运行，超时时间为 8 秒。
            Thread t = Thread.ofVirtual().name("memory-recall-prefetch").start(runnable);
            Thread.ofVirtual().start(() -> {
                try {
                    if (!t.join(java.time.Duration.ofSeconds(8))) {
                        t.interrupt();
                    }
                } catch (InterruptedException ignored) {}
            });
        });
    }

    /**

     * 等待最多 3 秒，以便预取 future 生成渲染的结果

     * 提醒，然后将其作为给定的系统提醒注入

     * 谈话。如果首先触发超时，则预取将继续运行

     * 但它的结果被放弃了——召回是尽力而为的，不能拖延

     * 用户的主要请求。

     */
    private static void collectPrefetchedRecall(
            ConversationManager conv, CompletableFuture<String> prefetchFuture) {
        if (conv == null || prefetchFuture == null) return;
        try {
            String reminder = prefetchFuture.get(3, TimeUnit.SECONDS);
            if (reminder != null && !reminder.isEmpty()) {
                conv.addSystemReminder(reminder);
            }
        } catch (Exception ignored) {
            // 超时或错误 - 尽力召回，不要阻止用户。
        }
    }

    // ──────────────────────────────────────────────────────────────────────

    // 工具块管理

    // ──────────────────────────────────────────────────────────────────────

    private static final Set<String> COLLAPSIBLE_TOOLS = Set.of(
            "ReadFile", "Glob", "Grep", "ToolSearch");

    private static boolean isCollapsibleTool(String name) {
        return COLLAPSIBLE_TOOLS.contains(name);
    }

    /**
     * 实时归档已完成的工具块，保留仍在 loading 的。
     * 在 ToolResultEvent 后调用，让完成的工具立刻出现在聊天历史中。
     */
    private void commitCompletedToolBlocks() {
        var remaining = new ArrayList<ChatMessage.ToolBlockInfo>();
        var completed = new ArrayList<ChatMessage.ToolBlockInfo>();

        for (var tb : toolBlocks) {
            if (tb.loading()) {
                remaining.add(tb);
            } else {
                completed.add(tb);
            }
        }
        if (completed.isEmpty()) return;

        var visibleTools = new ArrayList<ChatMessage.ToolBlockInfo>();
        var collapsedTools = new ArrayList<ChatMessage.ToolBlockInfo>();

        for (var tb : completed) {
            if ("Agent".equals(tb.toolName()) && activeSubAgent != null) {
                if (!activeSubAgent.done) {
                    activeSubAgent.done = true;
                    activeSubAgent.toolCount = activeSubAgent.toolUses.size();
                    double total = 0;
                    for (var tu : activeSubAgent.toolUses) total += tu.elapsed();
                    activeSubAgent.totalTime = total;
                }
                var msg = new ChatMessage("sub_agent", null);
                msg.subAgentBlock = activeSubAgent;
                msg.expanded = false;
                chatMessages.add(msg);
                activeSubAgent = null;
            } else if (isCollapsibleTool(tb.toolName())) {
                collapsedTools.add(tb);
            } else {
                visibleTools.add(tb);
            }
        }

        for (var tb : visibleTools) {
            chatMessages.add(new ChatMessage("tool_visible",
                    renderToolBlockText(tb), List.of(tb)));
        }
        if (!collapsedTools.isEmpty()) {
            var msg = new ChatMessage("tool_collapsed", null, new ArrayList<>(collapsedTools));
            msg.expanded = false;
            chatMessages.add(msg);
        }

        toolBlocks.clear();
        toolBlocks.addAll(remaining);
    }

    private void commitToolBlocks() {
        if (toolBlocks.isEmpty()) return;

        var visibleTools = new ArrayList<ChatMessage.ToolBlockInfo>();
        var collapsedTools = new ArrayList<ChatMessage.ToolBlockInfo>();

        for (var tb : toolBlocks) {
            if ("Agent".equals(tb.toolName()) && activeSubAgent != null) {
                if (!activeSubAgent.done) {
                    activeSubAgent.done = true;
                    activeSubAgent.toolCount = activeSubAgent.toolUses.size();
                    double total = 0;
                    for (var tu : activeSubAgent.toolUses) total += tu.elapsed();
                    activeSubAgent.totalTime = total;
                }
                var msg = new ChatMessage("sub_agent", null);
                msg.subAgentBlock = activeSubAgent;
                msg.expanded = false;
                chatMessages.add(msg);
                activeSubAgent = null;
            } else if (isCollapsibleTool(tb.toolName())) {
                collapsedTools.add(tb);
            } else {
                visibleTools.add(tb);
            }
        }

        for (var tb : visibleTools) {
            chatMessages.add(new ChatMessage("tool_visible",
                    renderToolBlockText(tb), List.of(tb)));
        }

        if (!collapsedTools.isEmpty()) {
            var msg = new ChatMessage("tool_collapsed", null, new ArrayList<>(collapsedTools));
            msg.expanded = false;
            chatMessages.add(msg);
        }

        toolBlocks.clear();
    }

    private void savePartialResponse() {
        if (!streamBuf.isEmpty()) {
            chatMessages.add(new ChatMessage("assistant", streamBuf.toString()));
            conversation.addAssistantMessage(streamBuf.toString());
            SessionManager.saveMessage(System.getProperty("user.dir"), sessionId, "assistant", streamBuf.toString());
            streamBuf.setLength(0);
        }
        commitToolBlocks();
        chatMessages.add(new ChatMessage("system", "(response interrupted)"));
        streaming = false;
        agentQueue = null;
        agentRun = null;
        userScrolled = false;
        scrollOffset = 0;
    }

    /** 只发送协作式取消请求；具体工具是否立即停止由其取消实现决定。 */
    private void cancelCurrentAgentRun() {
        if (agentRun != null) {
            agentRun.cancel();
        }
    }

    private static String renderToolBlockText(ChatMessage.ToolBlockInfo tb) {
        String title = formatToolTitle(tb.toolName(),
                tb.args() != null ? tb.args() : Map.of());
        if (tb.isError()) {
            return "✗ %s (%.1fs)".formatted(title, tb.elapsed());
        }
        return "✓ %s (%.1fs)".formatted(title, tb.elapsed());
    }

    private static String renderToolGroupSummary(List<ChatMessage.ToolBlockInfo> tools) {
        double totalElapsed = 0;
        int errors = 0;
        for (var tb : tools) {
            totalElapsed += tb.elapsed();
            if (tb.isError()) errors++;
        }
        if (errors > 0) {
            return "● Done (%d tool uses · %d errors · %.1fs)".formatted(
                    tools.size(), errors, totalElapsed);
        }
        return "● Done (%d tool uses · %.1fs)".formatted(tools.size(), totalElapsed);
    }

    private String renderToolBlock(ChatMessage.ToolBlockInfo tb) {
        String title = formatToolTitle(tb.toolName(),
                tb.args() != null ? tb.args() : Map.of());
        if (tb.loading()) {
            return Styles.toolRunning.render("  ● %s …".formatted(title));
        }
        if (tb.isError()) {
            return Styles.toolError.render("  ✗ %s — error (%.1fs)".formatted(title, tb.elapsed()));
        }
        return Styles.toolDone.render("  ✓ %s (%.1fs)".formatted(title, tb.elapsed()));
    }

    // ────────────────────────────────────────────────────────────────────
    // Scrollback commit — 渲染 chatMessages[from..to) 为终端 scrollback 文本
    // ────────────────────────────────────────────────────────────────────

    @Override
    public String dumpHistory() {
        if (chatMessages.isEmpty()) return "";
        var sb = new StringBuilder();
        sb.append(renderBanner());
        sb.append("\n\n");
        sb.append(renderMessagesRange(0, chatMessages.size()));
        return sb.toString();
    }

    private String renderMessagesRange(int from, int to) {
        var sb = new StringBuilder();
        for (int i = from; i < to && i < chatMessages.size(); i++) {
            var msg = chatMessages.get(i);
            switch (msg.role) {
                case "user" -> {
                    sb.append(Styles.prompt.render("❯ "));
                    sb.append(Styles.userText.render(msg.content));
                    sb.append("\n\n");
                }
                case "assistant" -> {
                    sb.append(Styles.aiMarker.render("● "));
                    sb.append(MarkdownRenderer.render(msg.content, width));
                    sb.append("\n");
                }
                case "thinking" -> {
                    if (msg.content != null) {
                        sb.append(Styles.toolDetail.render("  " + msg.content));
                        sb.append("\n");
                    }
                }
                case "tool_visible" -> {
                    sb.append("  ");
                    if (msg.content != null && msg.content.startsWith("✗")) {
                        sb.append(Styles.toolError.render(msg.content));
                    } else {
                        sb.append(Styles.toolDone.render(msg.content));
                    }
                    sb.append("\n");
                }
                case "tool_collapsed" -> {
                    // scrollback 里始终展开每个工具
                    if (msg.toolGroup != null) {
                        for (var tb : msg.toolGroup) {
                            sb.append("  ");
                            String text = renderToolBlockText(tb);
                            sb.append(tb.isError() ? Styles.toolError.render(text) : Styles.toolDone.render(text));
                            sb.append("\n");
                        }
                    }
                }
                case "tool_group" -> {
                    if (msg.toolGroup != null) {
                        for (var tb : msg.toolGroup) {
                            sb.append("  ");
                            String text = renderToolBlockText(tb);
                            sb.append(tb.isError() ? Styles.toolError.render(text) : Styles.toolDone.render(text));
                            sb.append("\n");
                        }
                    }
                }
                case "sub_agent" -> {
                    if (msg.subAgentBlock != null) {
                        sb.append(renderSubAgentBlock(msg.subAgentBlock, false));
                    }
                }
                case "system" -> {
                    sb.append(Styles.system.render("  " + msg.content));
                    sb.append("\n");
                }
                case "error" -> {
                    sb.append(Styles.error.render("  ✖ " + msg.content));
                    sb.append("\n");
                }
                default -> {
                    sb.append("  " + msg.content);
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────

    // 子代理显示

    // ──────────────────────────────────────────────────────────────────────

    private void handleSubAgentProgress(SubAgentProgress progress) {
        subAgentProgressQueue.add(progress);
        if (program != null) {
            program.send(new AgentEventMessage());
        }
    }

    private void drainSubAgentProgress() {
        SubAgentProgress progress;
        while ((progress = subAgentProgressQueue.poll()) != null) {
            if (progress.done()) {
                if (activeSubAgent != null) {
                    activeSubAgent.done = true;
                    activeSubAgent.toolCount = progress.toolCount();
                    activeSubAgent.totalTime = progress.totalTime();
                }
            } else {
                if (activeSubAgent == null || activeSubAgent.done) {
                    activeSubAgent = new ChatMessage.SubAgentBlockState();
                    activeSubAgent.desc = progress.description();
                    activeSubAgent.agentType = progress.agentType();
                }
                if (progress.toolName() != null) {
                    activeSubAgent.toolUses.add(new ChatMessage.ToolBlockInfo(
                            progress.toolName(), Map.of(), progress.toolOutput(),
                            progress.toolError(), 0, false, false));
                }
            }
        }
    }

    private String renderSubAgentBlock(ChatMessage.SubAgentBlockState sab, boolean expanded) {
        var sb = new StringBuilder();
        String label = sab.agentType != null && !sab.agentType.isEmpty()
                ? sab.agentType.substring(0, 1).toUpperCase() + sab.agentType.substring(1)
                : "Agent";
        sb.append(Styles.aiMarker.render("● %s(%s)".formatted(label, sab.desc)));
        sb.append("\n");

        if (sab.done) {
            if (expanded) {
                for (var tu : sab.toolUses) {
                    String title = formatToolTitle(tu.toolName(),
                            tu.args() != null ? tu.args() : Map.of());
                    String line = "     %s (%.1fs)".formatted(title, tu.elapsed());
                    sb.append(tu.isError()
                            ? Styles.toolError.render(line)
                            : Styles.toolDone.render(line));
                    sb.append("\n");
                }
            } else {
                sb.append(Styles.toolDone.render("  ⎿  Done (%d tool uses · %.1fs)".formatted(
                        sab.toolCount, sab.totalTime)));
                sb.append(Styles.toolDetail.render("  (ctrl+o to expand)"));
                sb.append("\n");
            }
        } else {
            int n = sab.toolUses.size();
            if (n > 0) {
                var last = sab.toolUses.get(n - 1);
                sb.append(Styles.toolDone.render("  ⎿  %s".formatted(
                        formatToolTitle(last.toolName(),
                                last.args() != null ? last.args() : Map.of()))));
                sb.append("\n");
            }
            if (n > 1) {
                sb.append(Styles.toolDetail.render("     … +%d tool uses (ctrl+o to expand)".formatted(n - 1)));
                sb.append("\n");
            }
            sb.append(Styles.toolDetail.render("     Running…"));
            sb.append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────

    // 队友旋转树

    // ──────────────────────────────────────────────────────────────────────

    private String renderTeammateTree() {
        var progressList = teamManager.getAllTeammateProgress();
        if (progressList.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("\n");
        // 领导线
        sb.append("  ┌─ ").append(Styles.cyan("team-lead"))
          .append(": ").append(Styles.dim(thinkingVerb + "…"));
        if (totalInput + totalOutput > 0) {
            sb.append(Styles.dim(" · " + TeammateProgress.formatTokens(totalInput + totalOutput) + " tokens"));
        }
        sb.append("\n");

        // 队友线
        for (int i = 0; i < progressList.size(); i++) {
            var p = progressList.get(i);
            boolean isLast = (i == progressList.size() - 1);
            String connector = isLast ? "  └─ " : "  ├─ ";

            sb.append(connector).append(Styles.cyan("@" + p.getName())).append(": ");

            switch (p.getStatus()) {
                case "completed" -> sb.append(Styles.green("completed"));
                case "failed" -> sb.append(Styles.red("failed"));
                case "stopped" -> sb.append(Styles.yellow("stopped"));
                case "idle" -> sb.append(Styles.dim("idle"));
                default -> sb.append(Styles.dim(p.getActivitySummary() + "..."));
            }

            sb.append(Styles.dim(" · " + p.getToolUseCount() + " tools · "
                    + TeammateProgress.formatTokens(p.getTokenCount()) + " tokens"));
            sb.append("\n");
        }

        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────

    // 规划审批

    // ──────────────────────────────────────────────────────────────────────

    private UpdateResult<TermAgentModel> handlePlanApprovalKey(KeyPressMessage kpm) {
        var result = planApprovalDialog.handleKey(kpm.key());
        if (result == null) return UpdateResult.from(this);

        switch (result.type()) {
            case YOLO -> {
                if (permChecker != null) permChecker.setMode(PermissionMode.BYPASS);
                chatMessages.add(new ChatMessage("system",
                        "Plan approved. Entered YOLO mode (all operations auto-approved)."));
                // 退出 plan mode，注入 exit reminder 并标记已退出
                String exitPath = PlanFile.getOrCreatePlanPath(System.getProperty("user.dir"));
                String exitReminder = PlanModePrompt.buildExitReminder(exitPath, PlanFile.planExists());
                conversation.addSystemReminder(exitReminder);
                hasExitedPlanMode = true;
                PlanFile.resetPlanPath();
            }
            case MANUAL -> {
                if (permChecker != null) {
                    var restoreMode = prePlanMode != null ? prePlanMode : PermissionMode.DEFAULT;
                    permChecker.setMode(restoreMode);
                }
                chatMessages.add(new ChatMessage("system",
                        "Plan approved. Each edit will require your confirmation."));
                // 退出 plan mode，注入 exit reminder 并标记已退出
                String exitPath = PlanFile.getOrCreatePlanPath(System.getProperty("user.dir"));
                String exitReminder = PlanModePrompt.buildExitReminder(exitPath, PlanFile.planExists());
                conversation.addSystemReminder(exitReminder);
                hasExitedPlanMode = true;
                PlanFile.resetPlanPath();
            }
            case FEEDBACK -> {
                String feedback = result.feedback();
                inputBuffer.setLength(0);
                inputBuffer.append(feedback);
                return sendUserMessage();
            }
            case CANCEL -> {}
        }
        return UpdateResult.from(this);
    }

    // ──────────────────────────────────────────────────────────────────────

    // 任务通知

    // ──────────────────────────────────────────────────────────────────────

    private void drainTaskNotifications() {
        if (subAgentTaskManager == null) return;
        var notifications = subAgentTaskManager.drainNotifications();
        for (var n : notifications) {
            chatMessages.add(new ChatMessage("system",
                    "Task %s: %s (%s)".formatted(n.taskId(), n.name(), n.status())));
        }
    }

    private static String formatToolTitle(String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) return toolName;
        return switch (toolName) {
            case "ReadFile" -> {
                var p = args.get("file_path");
                yield "Read " + (p != null ? Path.of(p.toString()).getFileName() : toolName);
            }
            case "WriteFile" -> {
                var p = args.get("file_path");
                var content = args.get("content");
                String name = p != null ? Path.of(p.toString()).getFileName().toString() : "file";
                int lines = content instanceof String s ? s.split("\n", -1).length : 0;
                yield lines > 0 ? "Write %s (%d lines)".formatted(name, lines) : "Write " + name;
            }
            case "EditFile" -> {
                var p = args.get("file_path");
                yield "Edit " + (p != null ? Path.of(p.toString()).getFileName() : toolName);
            }
            case "Bash" -> {
                var cmd = args.get("command");
                if (cmd == null) yield toolName;
                String s = cmd.toString();
                yield s.length() > 50 ? "Bash: " + s.substring(0, 47) + "…" : "Bash: " + s;
            }
            case "Glob" -> "Glob: " + args.getOrDefault("pattern", "");
            case "Grep" -> "Grep: " + args.getOrDefault("pattern", "");
            default -> toolName;
        };
    }

    // ──────────────────────────────────────────────────────────────────────

    // 恢复屏幕

    // ──────────────────────────────────────────────────────────────────────

    private UpdateResult<TermAgentModel> handleResumeKey(KeyPressMessage kpm) {
        String key = kpm.key();
        return switch (key) {
            case "up", "k" -> { if (resumeCursor > 0) resumeCursor--; yield UpdateResult.from(this); }
            case "down", "j" -> { if (resumeCursor < resumeFiltered.size() - 1) resumeCursor++; yield UpdateResult.from(this); }
            case "escape" -> { state = AppState.CHAT; yield UpdateResult.from(this); }
            case "enter" -> {
                if (!resumeFiltered.isEmpty()) {
                    var session = resumeFiltered.get(resumeCursor);
                    var messages = SessionManager.loadSession(System.getProperty("user.dir"), session.id());
                    sessionId = session.id();
                    // 保持代理的会话日志指针同步，以便稍后使用
                    // 压缩将其边界写入同一个文件（链式
                    // 简历）； fileHistory 指针也随之跟随。
                    if (agent != null) agent.setSessionId(sessionId);
                    fileHistory = new io.github.shymoy.termagent.filehistory.FileHistory(
                            System.getProperty("user.dir"), sessionId);
                    if (agent != null) agent.setFileHistory(fileHistory);

                    // 压缩感知重建：当会话有

                    // Compact_boundary，实时对话是压缩的

                    // 状态（摘要+保留尾部+消息附加在

                    // 边界）；预压缩前缀保留在文件中

                    // 审核但不重播。无边界（旧会话）

                    // 一切都逐字重播。
                    conversation = SessionManager.rebuildConversation(messages);
                    var scan = SessionManager.findLastCompactBoundary(messages);
                    chatMessages.clear();
                    if (scan.found()) {
                        chatMessages.add(new ChatMessage("user", scan.boundary().summary()));
                        for (var k : scan.boundary().keep()) {
                            chatMessages.add(new ChatMessage(k.role(), k.content()));
                        }
                        for (var msg : scan.after()) {
                            chatMessages.add(new ChatMessage(msg.role(), msg.content()));
                        }
                        chatMessages.add(new ChatMessage("system",
                                "Resumed session %s from compacted state (summary + %d kept + %d newer messages)"
                                        .formatted(session.id(), scan.boundary().keep().size(), scan.after().size())));
                    } else {
                        for (var msg : messages) {
                            chatMessages.add(new ChatMessage(msg.role(), msg.content()));
                        }
                        chatMessages.add(new ChatMessage("system",
                                "Resumed session " + session.id() + " (%d messages)".formatted(messages.size())));
                    }
                }
                state = AppState.CHAT;
                // 恢复的会话全部提交到 scrollback
                String commitText = renderMessagesRange(0, chatMessages.size());
                committedUpTo = chatMessages.size();
                yield !commitText.isEmpty()
                        ? UpdateResult.from(this, Command.println(commitText))
                        : UpdateResult.from(this);
            }
            case "backspace", "ctrl+h" -> {
                if (!resumeSearch.isEmpty()) {
                    resumeSearch = resumeSearch.substring(0, resumeSearch.length() - 1);
                    filterResumeSessions();
                }
                yield UpdateResult.from(this);
            }
            default -> {
                char[] runes = kpm.runes();
                if (runes != null && runes.length > 0) {
                    for (char ch : runes) if (ch >= 32) resumeSearch += ch;
                    filterResumeSessions();
                }
                yield UpdateResult.from(this);
            }
        };
    }

    private void filterResumeSessions() {
        if (resumeSearch.isEmpty()) {
            resumeFiltered = new ArrayList<>(resumeSessions);
        } else {
            String lower = resumeSearch.toLowerCase();
            resumeFiltered = resumeSessions.stream()
                    .filter(s -> s.firstMessage().toLowerCase().contains(lower)
                            || s.id().contains(lower))
                    .toList();
        }
        resumeCursor = 0;
    }

    private String viewResume() {
        var sb = new StringBuilder();
        sb.append(renderBanner());
        sb.append("\n\n");
        sb.append(Styles.selectLabel.render("  Resume a Session"));
        sb.append("\n");
        if (!resumeSearch.isEmpty()) {
            sb.append(Styles.statusBar.render("  Search: " + resumeSearch + "█"));
            sb.append("\n");
        }
        sb.append("\n");

        if (resumeFiltered.isEmpty()) {
            sb.append(Styles.statusBar.render("  No sessions found."));
            sb.append("\n");
        } else {
            int start = Math.max(0, resumeCursor - 5);
            int end = Math.min(resumeFiltered.size(), start + 12);
            for (int i = start; i < end; i++) {
                var s = resumeFiltered.get(i);
                String marker = i == resumeCursor ? " ❯ " : "   ";
                var style = i == resumeCursor ? Styles.selectedItem : Styles.normalItem;
                String preview = s.firstMessage().length() > 50
                        ? s.firstMessage().substring(0, 47) + "..."
                        : s.firstMessage();
                String meta = "%s · %d msgs".formatted(s.id(), s.messageCount());
                sb.append(style.render(marker + preview));
                sb.append("\n");
                sb.append(Styles.statusBar.render("     " + meta));
                sb.append("\n");
            }
        }

        sb.append("\n");
        sb.append(Styles.statusBar.render("  j/k to move · enter to resume · escape to cancel · type to search"));
        sb.append("\n");
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────

    // 权限对话框

    // ──────────────────────────────────────────────────────────────────────

    private UpdateResult<TermAgentModel> handlePermDialogKey(KeyPressMessage kpm) {
        String key = kpm.key();
        return switch (key) {
            case "up", "k" -> { if (permCursor > 0) permCursor--; yield UpdateResult.from(this); }
            case "down", "j" -> { if (permCursor < 2) permCursor++; yield UpdateResult.from(this); }
            case "enter" -> {
                var response = switch (permCursor) {
                    case 0 -> PermissionResponse.ALLOW;
                    case 1 -> PermissionResponse.ALLOW_ALWAYS;
                    default -> PermissionResponse.DENY;
                };
                if (pendingPermission != null) pendingPermission.complete(response);
                permDialog = false;
                pendingPermission = null;
                yield UpdateResult.from(this);
            }
            case "escape" -> {
                if (pendingPermission != null) pendingPermission.complete(PermissionResponse.DENY);
                permDialog = false;
                pendingPermission = null;
                yield UpdateResult.from(this);
            }
            default -> UpdateResult.from(this);
        };
    }

    // ──────────────────────────────────────────────────────────────────────

    // 倒回对话框

    // ──────────────────────────────────────────────────────────────────────

    private UpdateResult<TermAgentModel> handleRewindKey(KeyPressMessage kpm) {
        String key = kpm.key();
        if (rewindPhase == 0) {
            return switch (key) {
                case "escape" -> { rewindDialog = false; yield UpdateResult.from(this); }
                case "up", "k" -> { if (rewindCursor > 0) rewindCursor--; yield UpdateResult.from(this); }
                case "down", "j" -> { if (rewindCursor < rewindSnapshots.size() - 1) rewindCursor++; yield UpdateResult.from(this); }
                case "enter" -> { rewindPhase = 1; rewindOptionCursor = 0; yield UpdateResult.from(this); }
                default -> UpdateResult.from(this);
            };
        }
        // 第一阶段：选项选择
        return switch (key) {
            case "escape" -> { rewindPhase = 0; yield UpdateResult.from(this); }
            case "up", "k" -> { if (rewindOptionCursor > 0) rewindOptionCursor--; yield UpdateResult.from(this); }
            case "down", "j" -> { if (rewindOptionCursor < REWIND_OPTIONS.length - 1) rewindOptionCursor++; yield UpdateResult.from(this); }
            case "enter" -> executeRewindOption();
            default -> UpdateResult.from(this);
        };
    }

    private UpdateResult<TermAgentModel> executeRewindOption() {
        var snap = rewindSnapshots.get(rewindCursor);
        switch (rewindOptionCursor) {
            case 0 -> { // Restore code and conversation
                var changed = fileHistory.rewind(rewindCursor);
                conversation.truncateTo(snap.messageIndex());
                chatMessages.clear();
                chatMessages.add(new ChatMessage("system",
                        "⟲ Rewound to checkpoint %d. Restored %d file(s) and conversation.".formatted(
                                rewindCursor + 1, changed.size())));
            }
            case 1 -> { // Restore conversation only
                conversation.truncateTo(snap.messageIndex());
                chatMessages.clear();
                chatMessages.add(new ChatMessage("system",
                        "⟲ Rewound conversation to checkpoint %d. Files unchanged.".formatted(rewindCursor + 1)));
            }
            case 2 -> { // Restore code only
                var changed = fileHistory.rewind(rewindCursor);
                chatMessages.add(new ChatMessage("system",
                        "⟲ Restored %d file(s) to checkpoint %d. Conversation unchanged.".formatted(
                                changed.size(), rewindCursor + 1)));
            }
            case 3 -> { // Never mind
                rewindDialog = false;
                rewindPhase = 0;
                return UpdateResult.from(this);
            }
        }
        rewindDialog = false;
        rewindPhase = 0;
        return UpdateResult.from(this);
    }

    // ──────────────────────────────────────────────────────────────────────

    // 询问用户对话框

    // ──────────────────────────────────────────────────────────────────────

    private UpdateResult<TermAgentModel> handleAskUserDialogKey(KeyPressMessage kpm) {
        var result = askUserDialogState.handleKey(kpm.key());
        if (result != null) {
            if (askUserFuture != null) {
                askUserFuture.complete(result);
                askUserFuture = null;
            }
        }
        return UpdateResult.from(this);
    }

    // ──────────────────────────────────────────────────────────────────────

    // 聊天视图渲染

    // ──────────────────────────────────────────────────────────────────────

    private String viewChat() {
        var sb = new StringBuilder();
        if (!bannerPrinted) {
            sb.append(renderBanner());
            sb.append("\n\n");
        }

        // ── Messages（只渲染未提交到 scrollback 的部分）────────────────
        for (int idx = committedUpTo; idx < chatMessages.size(); idx++) {
            var msg = chatMessages.get(idx);
            switch (msg.role) {
                case "user" -> {
                    sb.append(Styles.prompt.render("❯ "));
                    sb.append(Styles.userText.render(msg.content));
                    sb.append("\n\n");
                }
                case "assistant" -> {
                    sb.append(Styles.aiMarker.render("● "));
                    sb.append(MarkdownRenderer.render(msg.content, width));
                    sb.append("\n");
                }
                case "thinking" -> {
                    if (msg.content != null) {
                        sb.append(Styles.toolDetail.render("  "));
                        String preview = msg.content.length() > 80
                                ? msg.content.substring(0, 77) + "..."
                                : msg.content;
                        sb.append(Styles.toolDetail.render(preview));
                        sb.append("\n");
                    }
                }
                case "tool" -> {
                    sb.append(Styles.toolRunning.render("  " + msg.content));
                    sb.append("\n");
                }
                case "tool_visible" -> {
                    sb.append("  ");
                    if (msg.content != null && msg.content.startsWith("✗")) {
                        sb.append(Styles.toolError.render(msg.content));
                    } else {
                        sb.append(Styles.toolDone.render(msg.content));
                    }
                    sb.append("\n");
                }
                case "tool_collapsed" -> {
                    if (msg.toolGroup != null) {
                        if (msg.expanded) {
                            for (var tb : msg.toolGroup) {
                                sb.append("  ");
                                String text = renderToolBlockText(tb);
                                sb.append(tb.isError()
                                        ? Styles.toolError.render(text)
                                        : Styles.toolDone.render(text));
                                sb.append("\n");
                            }
                        } else {
                            sb.append(Styles.toolDone.render("  " + renderToolGroupSummary(msg.toolGroup)));
                            sb.append(Styles.toolDetail.render("  (ctrl+o to expand)"));
                            sb.append("\n");
                        }
                    }
                }
                case "tool_group" -> {
                    if (msg.expanded && msg.toolGroup != null) {
                        for (var tb : msg.toolGroup) {
                            sb.append("  ");
                            String text = renderToolBlockText(tb);
                            sb.append(tb.isError()
                                    ? Styles.toolError.render(text)
                                    : Styles.toolDone.render(text));
                            sb.append("\n");
                        }
                    } else if (msg.toolGroup != null) {
                        sb.append(Styles.toolDone.render("  " + renderToolGroupSummary(msg.toolGroup)));
                        sb.append(Styles.toolDetail.render("  (ctrl+o to expand)"));
                        sb.append("\n");
                    }
                }
                case "sub_agent" -> {
                    if (msg.subAgentBlock != null) {
                        sb.append(renderSubAgentBlock(msg.subAgentBlock, msg.expanded));
                    }
                }
                case "system" -> {
                    sb.append(Styles.system.render("  " + msg.content));
                    sb.append("\n");
                }
                case "error" -> {
                    sb.append(Styles.error.render("  ✖ " + msg.content));
                    sb.append("\n");
                }
                default -> {
                    sb.append("  " + msg.content);
                    sb.append("\n");
                }
            }
        }

        // ── 活动工具块（进行中）────────────────────────────
        for (var tb : toolBlocks) {
            if ("Agent".equals(tb.toolName()) && activeSubAgent != null) {
                continue;
            }
            sb.append(renderToolBlock(tb));
            sb.append("\n");
        }

        // ── 活跃子代理（进行中）──────────────────────────────
        if (activeSubAgent != null && !activeSubAgent.done) {
            sb.append(renderSubAgentBlock(activeSubAgent, false));
        }

        // ── 流媒体缓冲区（正在进行的文本）──────────────────────────
        if (streaming && !streamBuf.isEmpty()) {
            sb.append(Styles.aiMarker.render("● "));
            sb.append(Styles.streamingText.render(streamBuf.toString()));
            sb.append("\n");
        }

        // ── 持久旋转器（运行直到 LoopComplete） ──────────────────
        if (streaming) {
            double elapsed = (System.currentTimeMillis() - thinkingStartMs) / 1000.0;
            String frame = SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length];
            sb.append(Styles.thinking.render("  %s %s…  (%.0fs)".formatted(frame, thinkingVerb, elapsed)));
            sb.append("\n");
            // ── 队友旋转树（如下所示） ──────
            sb.append(renderTeammateTree());
        }

        // ── 权限对话框叠加────────────────────────────────────
        if (permDialog) {
            sb.append(Styles.permBorder.render("  %s command".formatted(permToolName)));
            sb.append("\n\n");
            if (permDesc != null && !permDesc.isEmpty() && !permDesc.equals(permToolName)) {
                sb.append(Styles.aiText.render("    " + permDesc));
                sb.append("\n\n");
            }
            sb.append(Styles.permDim.render("  This command requires approval"));
            sb.append("\n\n");
            sb.append(Styles.aiText.render("  Do you want to proceed?"));
            sb.append("\n");
            String[] options = {"Yes", "Yes, and don't ask again for this pattern", "No"};
            for (int i = 0; i < options.length; i++) {
                String marker = i == permCursor ? " ❯ " : "   ";
                var style = i == permCursor ? Styles.selectedItem : Styles.normalItem;
                sb.append(style.render(marker + (i + 1) + ". " + options[i]));
                sb.append("\n");
            }
            sb.append("\n");
        }

        // ── 快退对话框覆盖────────────────────────────────────────
        if (rewindDialog && rewindSnapshots != null) {
            sb.append("\n");
            sb.append(Styles.selectedItem.render("  ⟲ Rewind to checkpoint"));
            sb.append("\n\n");

            if (rewindPhase == 0) {
                int maxVisible = 8;
                int start = Math.max(0, rewindCursor - maxVisible + 1);
                int end = Math.min(start + maxVisible, rewindSnapshots.size());
                for (int i = start; i < end; i++) {
                    var snap = rewindSnapshots.get(i);
                    String marker = i == rewindCursor ? " ❯ " : "   ";
                    var style = i == rewindCursor ? Styles.selectedItem : Styles.normalItem;
                    String label = snap.userText();
                    if (label.length() > 50) label = label.substring(0, 50) + "…";
                    long secs = java.time.Duration.between(snap.timestamp(), java.time.Instant.now()).getSeconds();
                    sb.append(style.render("%s[%d] %s (%ds ago, %d file(s))".formatted(
                            marker, i + 1, label, secs, snap.backups().size())));
                    sb.append("\n");
                }
                sb.append("\n");
                sb.append(Styles.normalItem.render("  ↑/↓ navigate · enter select · esc cancel"));
            } else {
                var snap = rewindSnapshots.get(rewindCursor);
                String label = snap.userText();
                if (label.length() > 50) label = label.substring(0, 50) + "…";
                long secs = java.time.Duration.between(snap.timestamp(), java.time.Instant.now()).getSeconds();
                sb.append(Styles.normalItem.render("  Selected: [%d] %s (%ds ago, %d file(s))".formatted(
                        rewindCursor + 1, label, secs, snap.backups().size())));
                sb.append("\n\n");
                for (int i = 0; i < REWIND_OPTIONS.length; i++) {
                    String marker = i == rewindOptionCursor ? " ❯ " : "   ";
                    var style = i == rewindOptionCursor ? Styles.selectedItem : Styles.normalItem;
                    sb.append(style.render(marker + REWIND_OPTIONS[i]));
                    sb.append("\n");
                }
                sb.append("\n");
                sb.append(Styles.normalItem.render("  ↑/↓ navigate · enter select · esc back"));
            }
            sb.append("\n");
        }

        // ── AskUser对话框覆盖────────────────────────────────────────
        if (askUserDialogState.isActive()) {
            sb.append("\n");
            sb.append(askUserDialogState.render(width));
        }

        // ── 计划批准对话框叠加──────────────────────────────────
        if (planApprovalDialog.isActive()) {
            sb.append("\n");
            sb.append(planApprovalDialog.render());
        }

        // ── 应用线级视口────────────────────────────────────
        {
            String contentStr = sb.toString();
            sb.setLength(0);
            String[] contentLines = contentStr.split("\n", -1);
            int lineCount = contentLines.length;
            if (lineCount > 0 && contentLines[lineCount - 1].isEmpty()) lineCount--;
            totalContentLines = lineCount;

            int bottomHeight = 4;
            if (slashMenuOpen && !slashMatches.isEmpty()) bottomHeight += Math.min(slashMatches.size(), 8);
            if (atMenuOpen && !atMatches.isEmpty()) bottomHeight += Math.min(atMatches.size(), 8);
            int viewportHeight = Math.max(height - bottomHeight, 3);

            if (lineCount > viewportHeight && scrollOffset > lineCount - viewportHeight) {
                scrollOffset = lineCount - viewportHeight;
            }

            int endLine = Math.min(lineCount, lineCount - scrollOffset);
            int startLine = Math.max(0, endLine - viewportHeight);

            for (int i = startLine; i < endLine; i++) {
                sb.append(contentLines[i]).append("\n");
            }

            if (scrollOffset > 0) {
                sb.append(Styles.toolDetail.render("  ↓ %d more lines below (pgdn/end)".formatted(scrollOffset)));
                sb.append("\n");
            }
        }

        // ── Separator ───────────────────────────────────────────────────
        String sep = "─".repeat(Math.max(width - 2, 20));
        sb.append(Styles.separator.render(sep));
        sb.append("\n");

        // ── Input area ──────────────────────────────────────────────────
        if (streaming) {
            // 流媒体期间禁用输入；微调器位于上面的聊天内容中
        } else {
            if (inputBuffer.isEmpty()) {
                sb.append(Styles.prompt.render("❯ "));
                sb.append(Styles.placeholder.render("Send a message..."));
                sb.append("\n");
            } else {
                String[] inputLines = inputBuffer.toString().split("\n", -1);
                for (int i = 0; i < inputLines.length; i++) {
                    if (i == 0) {
                        sb.append(Styles.prompt.render("❯ "));
                    } else {
                        sb.append("  ");
                    }
                    sb.append(inputLines[i]);
                    if (i == inputLines.length - 1) sb.append("█");
                    sb.append("\n");
                }
            }
        }
        if (streaming) sb.append("\n");

        // ── Separator ────────────────────────────────────────────────────
        sb.append(Styles.separator.render("─".repeat(Math.max(width, 20))));
        sb.append("\n");

        // ── 斜线菜单（分隔符下方，状态栏上方）──────────────
        if (slashMenuOpen && !slashMatches.isEmpty()) {
            for (int i = 0; i < slashMatches.size() && i < 8; i++) {
                var cmdItem = slashMatches.get(i);
                String desc = cmdItem.description() != null ? cmdItem.description() : "";
                if (desc.codePointCount(0, desc.length()) > 30) {
                    desc = desc.substring(0, desc.offsetByCodePoints(0, 28)) + "…";
                }
                String label = String.format("  /%-16s — %s", cmdItem.name(), desc);
                var style = i == slashCursor ? Styles.selectedItem : Styles.normalItem;
                sb.append(style.render(label));
                sb.append("\n");
            }
        }

        // ── @ file menu ─────────────────────────────────────────────────
        if (atMenuOpen && !atMatches.isEmpty()) {
            for (int i = 0; i < atMatches.size() && i < 8; i++) {
                String marker = i == atCursor ? " ❯ " : "   ";
                var style = i == atCursor ? Styles.selectedItem : Styles.normalItem;
                sb.append(style.render(marker + "@" + atMatches.get(i)));
                sb.append("\n");
            }
        }

        String modeStr;
        var modeStyle = Styles.modeDefault;
        if (permChecker != null) {
            var mode = permChecker.getMode();
            modeStr = switch (mode) {
                case DEFAULT -> "default";
                case ACCEPT_EDITS -> "accept-edits";
                case PLAN -> "plan";
                case BYPASS -> "YOLO";
            };
            modeStyle = switch (mode) {
                case DEFAULT -> Styles.modeDefault;
                case ACCEPT_EDITS -> Styles.modeAcceptEdits;
                case PLAN -> Styles.modePlan;
                case BYPASS -> Styles.modeBypass;
            };
        } else {
            modeStr = "default";
        }

        String left = modeStyle.render("  " + modeStr);
        if (permChecker != null && permChecker.getMode() != PermissionMode.DEFAULT) {
            left += Styles.toolDetail.render(" (shift+tab)");
        }

        // 活跃队友指标
        long activeTeammates = teamManager.getAllTeammateProgress().stream()
                .filter(p -> "running".equals(p.getStatus()))
                .count();
        String teammateStr = "";
        if (activeTeammates > 0) {
            teammateStr = " · " + activeTeammates + " teammate" + (activeTeammates > 1 ? "s" : "");
            left += Styles.cyan(teammateStr);
        }

        var rightParts = new StringBuilder();
        if (mcpConnecting) {
            rightParts.append(Styles.modePlan.render("MCP connecting… "));
        }
        String modelStr = selectedProvider != null ? selectedProvider.getModel() : "";
        if (!modelStr.isEmpty()) {
            rightParts.append(Styles.statusItem.render(modelStr));
        }

        int leftLen = modeStr.length() + 2 + (permChecker != null
                && permChecker.getMode() != PermissionMode.DEFAULT ? 12 : 0)
                + teammateStr.length();
        int rightLen = (mcpConnecting ? 17 : 0) + modelStr.length();
        int gap = Math.max(width - leftLen - rightLen - 2, 2);
        sb.append(left);
        sb.append(" ".repeat(gap));
        sb.append(rightParts);
        sb.append("\n");

        // ── 硬性高度保护：不超过 Program 报告的可用行数，防止内容溢出到 scrollback ──
        String result = sb.toString();
        String[] allLines = result.split("\n", -1);
        int total = allLines.length;
        if (total > 0 && allLines[total - 1].isEmpty()) total--;
        int maxLines = program != null ? program.getAvailableHeight() : Math.max(height - 1, 3);
        if (total > maxLines) {
            var capped = new StringBuilder();
            int start = total - maxLines;
            for (int i = start; i < total; i++) {
                capped.append(allLines[i]);
                if (i < total - 1) capped.append("\n");
            }
            capped.append("\n");
            return capped.toString();
        }
        return result;
    }
}
