// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.agent;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.hook.HookEngine;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionMode;
import com.mewcode.plan.PlanFile;
import com.mewcode.prompt.PlanModePrompt;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.toolresult.ApplyResult;
import com.mewcode.toolresult.ContentReplacementState;
import com.mewcode.toolresult.ReplacementRecordsIO;
import com.mewcode.toolresult.ToolResultBudget;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.*;
import java.util.concurrent.*;

public class Agent {

    // 单次回答的输出恢复上限：模型因 max_tokens 截断时，最多提升一次上限并续写三次。
    private static final int MAX_TOKENS_CEILING = 64_000;
    private static final int MAX_OUTPUT_RECOVERIES = 3;

    // Agent 只依赖统一的 LlmClient；具体协议的请求转换和流解析由客户端实现负责。
    private final LlmClient client;
    // registry 同时为模型提供工具 schema，并在执行阶段按名称找到具体工具。
    private final ToolRegistry registry;
    private final String protocol;
    private final int contextWindow;
    private final int maxOutput;
    // 权限检查器和 HookEngine 可选；未配置时工具按默认执行链路运行。
    private PermissionChecker checker;
    private HookEngine hookEngine;
    private int maxIterations;
    private String workDir;
    /**
     * 当前会话的磁盘日志 ID。循环内发生上下文压缩时，用它把压缩边界写入同一份会话记录，
     * 使恢复会话时能够重建压缩后的状态。子 Agent 或一次性调用不写主会话日志，因此可以为空。
     */
    private String sessionId;
    private java.util.function.Supplier<List<String>> notificationFn;

    private java.util.function.Predicate<String> toolNameFilter;
    private String instructions = "";
    private String memoryContent = "";

    // 非阻塞 memory recall：prefetch 与主 LLM 调用并行，工具执行后注入
    private CompletableFuture<String> memoryRecallFuture;
    private boolean memoryRecallConsumed;
    private final com.mewcode.compact.ContextCompactor.AutoCompactTrackingState compactTracking =
            new com.mewcode.compact.ContextCompactor.AutoCompactTrackingState();

    /**
     * 上下文压缩使用的真实 token 基准。每轮流结束后根据服务商返回的 usage 更新；
     * 第一轮尚无 usage 时为空，压缩器会暂时使用字符数估算。
     */
    private com.mewcode.compact.ContextCompactor.UsageAnchor usageAnchor;

    /**
     * 当前会话的工具结果裁剪记录，需要跨迭代保存，保证相同历史始终采用相同替换结果，
     * 从而维持 Anthropic Prompt Cache 前缀稳定。创建子 Agent 时会复制这份状态。
     */
    private ContentReplacementState replacementState = new ContentReplacementState();

    public ContentReplacementState getReplacementState() { return replacementState; }
    public void setReplacementState(ContentReplacementState state) { this.replacementState = state; }

    /**
     * 保存上下文压缩后恢复工作现场所需的快照，例如最近读取的文件和已加载的 Skill。
     * ReadFile、Skill 调用时持续更新，二层压缩触发后由 ContextCompactor 使用。
     */
    private final com.mewcode.compact.RecoveryState recoveryState =
            new com.mewcode.compact.RecoveryState();

    public com.mewcode.compact.RecoveryState getRecoveryState() { return recoveryState; }

    private com.mewcode.filehistory.FileHistory fileHistory;
    public void setFileHistory(com.mewcode.filehistory.FileHistory fh) { this.fileHistory = fh; }
    public com.mewcode.filehistory.FileHistory getFileHistory() { return fileHistory; }

    public ToolRegistry getRegistry() { return registry; }
    public String getProtocol() { return protocol; }

    public Agent(LlmClient client, ToolRegistry registry, String protocol, ProviderConfig cfg) {
        this.client = client;
        this.registry = registry;
        this.protocol = protocol;
        this.contextWindow = cfg.resolvedContextWindow();
        this.maxOutput = cfg.resolvedMaxOutputTokens();
    }

    public void setChecker(PermissionChecker checker) { this.checker = checker; }
    public void setHookEngine(HookEngine hookEngine) { this.hookEngine = hookEngine; }
    public void setMaxIterations(int max) { this.maxIterations = max; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getSessionId() { return sessionId; }
    public void setNotificationFn(java.util.function.Supplier<List<String>> fn) { this.notificationFn = fn; }

    public void setToolNameFilter(java.util.function.Predicate<String> filter) { this.toolNameFilter = filter; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public void setMemoryContent(String memoryContent) { this.memoryContent = memoryContent; }
    public void setMemoryRecallFuture(CompletableFuture<String> future) {
        this.memoryRecallFuture = future;
        this.memoryRecallConsumed = false;
    }
    public HookEngine getHookEngine() { return hookEngine; }

    public BlockingQueue<AgentEvent> run(ConversationManager conv) {
        // 无外部队列时创建默认队列；容量用于限制 Agent 生产事件的速度，避免 UI 来不及消费时无限堆积。
        var queue = new LinkedBlockingQueue<AgentEvent>(64);
        run(conv, queue);
        return queue;
    }

    // 使用调用方提供的 queue，允许 TUI 在 Agent 启动前就持有队列并立即开始轮询。
    public void run(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        // 模型请求和工具执行都可能阻塞，因此放在虚拟线程中，避免卡住 TUI 事件循环。
        Thread.startVirtualThread(() -> {
            try {
                agentLoop(conv, queue);
            } catch (Exception e) {
                // 主循环的未处理异常也转换成 AgentEvent，由 UI 统一展示，而不是让后台线程静默退出。
                putSafe(queue, new AgentEvent.ErrorEvent("Agent error: " + e.getMessage()));
            }
        });
    }

    /**
     * Agent 主循环：每一轮把当前会话转换成一次 LLM 请求，消费流式响应，
     * 如果模型调用了工具，就执行工具并把结果写回会话，然后进入下一轮。
     */
    private void agentLoop(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        // 长期记忆只在循环开始时注入一次，避免每轮请求重复塞入同一份上下文。
        conv.injectLongTermMemory(instructions, memoryContent);

        int totalInput = 0, totalOutput = 0;
        int outputRecoveries = 0;
        boolean maxTokensEscalated = false;

        int contextRetries = 0;
        boolean loopCompleted = false;

        try {
        for (int iteration = 1; ; iteration++) {
            if (maxIterations > 0 && iteration > maxIterations) {
                putSafe(queue, new AgentEvent.ErrorEvent(
                        "Agent reached maximum iterations (%d)".formatted(maxIterations)));
                break;
            }

            if (Thread.currentThread().isInterrupted()) break;

            // 拉取后台任务通知，并作为 system-reminder 注入到下一次模型请求中。
            if (notificationFn != null) {
                for (String note : notificationFn.get()) {
                    conv.addSystemReminder(note);
                }
            }

            // 每轮固定一次工具 schema，保证后面的压缩恢复信息和 LLM 请求使用同一组工具。
            // skill 过滤器只允许在两轮之间改变，不在单轮内部漂移。
            var iterToolSchemas = registry.getAllSchemas(protocol);
            if (toolNameFilter != null) {
                iterToolSchemas = iterToolSchemas.stream()
                        .filter(schema -> {
                            Object name = schema.get("name");
                            return name == null || toolNameFilter.test(name.toString());
                        })
                        .toList();
            }

            // 对齐 Claude Code：先应用 tool-result budget，再做 auto-compact
            // 这样 compact 的 token 估算使用的是 budget 裁剪后的体积，判断更精确
            Path preCompactSessionDir = Paths.get(workDir == null ? "." : workDir, ".mewcode/session");
            ApplyResult preCompactApplied = ToolResultBudget.apply(conv, preCompactSessionDir, replacementState);
            if (!preCompactApplied.newRecords().isEmpty()) {
                try {
                    ReplacementRecordsIO.append(preCompactSessionDir, preCompactApplied.newRecords());
                } catch (Exception ignored) {}
            }

            // 自动压缩检查：如果上下文接近窗口上限，先把旧历史压成摘要再继续请求模型。
            try {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                int sizeBefore = conv.size();
                String compactMsg = com.mewcode.compact.ContextCompactor.manage(
                        conv, client, contextWindow, maxOutput, wd, sessionId, compactTracking,
                        recoveryState, iterToolSchemas, usageAnchor,
                        preCompactApplied.apiConv().getMessages());
                if (compactMsg != null && !compactMsg.isEmpty()) {
                    putSafe(queue, new AgentEvent.CompactEvent(compactMsg));
                }
                // 二层压缩会重写会话列表，旧的 token 锚点已经对不上新的消息位置，
                // 需要清空并等待下一次真实 usage 重新建立锚点。
                if (conv.size() < sizeBefore) {
                    usageAnchor = null;
                }
            } catch (Exception ignored) {}

            // 延迟加载工具只注入名称提示，等模型显式使用 ToolSearch 后再加载完整 schema。
            var deferredNames = registry.getDeferredToolNames();
            if (!deferredNames.isEmpty()) {
                var sb = new StringBuilder();
                sb.append("The following deferred tools are available via ToolSearch. ");
                sb.append("Their schemas are NOT loaded - use ToolSearch with ");
                sb.append("query \"select:<name>[,<name>...]\" to load tool schemas before calling them:\n");
                for (var dn : deferredNames) {
                    sb.append(dn).append("\n");
                }
                conv.addSystemReminder(sb.toString());
            }

            // Plan 模式下，每轮都补充计划文件路径和当前审批流程提示。
            if (checker != null && checker.getMode() == PermissionMode.PLAN) {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                String planPath = PlanFile.getOrCreatePlanPath(wd);
                checker.setPlanFilePath(planPath);
                boolean planExists = PlanFile.planExists();
                String reminder = PlanModePrompt.buildReminder(planPath, planExists, iteration);
                conv.addSystemReminder(reminder);
            }

            // 复用本轮开头算好的工具列表，避免压缩恢复和模型请求看到不同工具集。
            var tools = iterToolSchemas;
            // 一层裁剪：为本次 API 请求生成带 tool_result 替换结果的临时会话。
            // 这里不修改原始 conv；前面注入的 reminder 会通过重建 apiConv 一并带入。
            Path sessionDir = Paths.get(workDir == null ? "." : workDir, ".mewcode/session");
            ApplyResult applied = ToolResultBudget.apply(conv, sessionDir, replacementState);
            if (!applied.newRecords().isEmpty()) {
                try {
                    ReplacementRecordsIO.append(sessionDir, applied.newRecords());
                } catch (Exception ignored) {
                    // 会话记录持久化是 best-effort；当前进程里以内存状态为准。
                }
            }
            // 真正发起模型流式请求。具体 API 格式转换发生在各 LlmClient 内部。
            var streamQueue = client.stream(applied.apiConv(), tools);

            // StreamEvent 是 LlmClient 发给 Agent 的协议无关事件；AgentEvent 是 Agent 发给 UI 的界面事件。
            // 这里一边把增量内容转发给 UI，一边收集最终要写入会话历史的完整内容。
            var text = new StringBuilder();
            var thinkingBlocks = new ArrayList<ThinkingBlock>();
            var toolCalls = new ArrayList<ToolCallInfo>();
            String stopReason = "end_turn";
            int turnInput = 0, turnOutput = 0;
            int turnCacheRead = 0, turnCacheCreation = 0;
            boolean streamError = false;

            while (true) {
                StreamEvent event;
                try {
                    event = streamQueue.poll(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (event == null) {
                    putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
                    return;
                }

                switch (event) {
                    case StreamEvent.TextDelta td -> {
                        text.append(td.text());
                        putSafe(queue, new AgentEvent.StreamText(td.text()));
                    }
                    case StreamEvent.ThinkingDelta td ->
                            putSafe(queue, new AgentEvent.ThinkingText(td.text()));
                    case StreamEvent.ThinkingComplete tc -> {
                        thinkingBlocks.add(new ThinkingBlock(tc.thinking(), tc.signature()));
                        putSafe(queue, new AgentEvent.ThinkingComplete(tc.thinking(), tc.signature()));
                    }
                    case StreamEvent.ToolCallStart tcs ->
                            putSafe(queue, new AgentEvent.ToolUseEvent(tcs.toolId(), tcs.toolName(), Map.of()));
                    // 工具参数增量已由 LlmClient 缓冲，Agent 等 ToolCallComplete 后再取得完整参数。
                    case StreamEvent.ToolCallDelta tcd -> {}
                    case StreamEvent.ToolCallComplete tcc -> {
                        toolCalls.add(new ToolCallInfo(tcc.toolId(), tcc.toolName(), tcc.arguments()));
                        putSafe(queue, new AgentEvent.ToolUseEvent(
                                tcc.toolId(), tcc.toolName(), tcc.arguments()));
                    }
                    case StreamEvent.StreamEnd se -> {
                        stopReason = se.stopReason();
                        turnInput = se.inputTokens();
                        turnOutput = se.outputTokens();
                        turnCacheRead = se.cacheReadTokens();
                        turnCacheCreation = se.cacheCreationTokens();
                    }
                    case StreamEvent.Error err -> {
                        lastStreamError = err.message();
                        putSafe(queue, new AgentEvent.ErrorEvent(err.message()));
                        streamError = true;
                    }
                }

                if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) break;
            }

            // 流式请求失败后的恢复：上下文过长优先强制压缩，限流则短暂等待后重试。
            if (streamError) {
                var lastErr = events_drain_last_error(queue);
                if (lastErr != null && (lastErr.contains("context") || lastErr.contains("too long")
                        || lastErr.contains("prompt"))) {
                    if (contextRetries < 3) {
                        contextRetries++;
                        putSafe(queue, new AgentEvent.RetryEvent("Context too long, compacting...", 0));
                        // 对齐 Claude Code：先应用 tool-result budget，再做 forceCompact
                        Path forceSessionDir = Paths.get(workDir == null ? "." : workDir, ".mewcode/session");
                        ApplyResult forceApplied = ToolResultBudget.apply(conv, forceSessionDir, replacementState);
                        if (!forceApplied.newRecords().isEmpty()) {
                            try { ReplacementRecordsIO.append(forceSessionDir, forceApplied.newRecords()); } catch (Exception ignored) {}
                        }
                        int sizeBeforeForce = conv.size();
                        try {
                            String wdForce = workDir != null ? workDir : System.getProperty("user.dir");
                            com.mewcode.compact.ContextCompactor.forceCompact(
                                    conv, client, contextWindow, wdForce, sessionId,
                                    recoveryState, iterToolSchemas,
                                    forceApplied.apiConv().getMessages());
                        } catch (Exception ignored) {}
                        // forceCompact 会把历史改成“摘要 + 保留尾部”，旧锚点失效。
                        if (conv.size() < sizeBeforeForce) {
                            usageAnchor = null;
                        }
                        continue;
                    }
                }
                if (lastErr != null && lastErr.toLowerCase().contains("rate limit")) {
                    putSafe(queue, new AgentEvent.RetryEvent("Rate limited, waiting 5s...", 5000));
                    try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                    continue;
                }
                break;
            }

            totalInput += turnInput;
            totalOutput += turnOutput;
            putSafe(queue, new AgentEvent.UsageEvent(totalInput, totalOutput));

            // 输出被 max_tokens 截断时，先提升输出上限并让模型从中断处续写。
            if ("max_tokens".equals(stopReason)) {
                if (!maxTokensEscalated) {
                    maxTokensEscalated = true;
                    client.setMaxOutputTokens(MAX_TOKENS_CEILING);
                    if (!text.isEmpty()) {
                        conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
                        conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Do not apologize or repeat previous content. Pick up mid-thought if needed.");
                    }
                    putSafe(queue, new AgentEvent.RetryEvent("max_tokens escalation", 0));
                    continue;
                } else if (outputRecoveries < MAX_OUTPUT_RECOVERIES) {
                    outputRecoveries++;
                    conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
                    conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Break remaining work into smaller pieces.");
                    putSafe(queue, new AgentEvent.RetryEvent(
                            "max_tokens recovery %d/%d".formatted(outputRecoveries, MAX_OUTPUT_RECOVERIES), 0));
                    continue;
                }
                // 重试次数耗尽后，保留当前已生成内容，按普通完成流程继续。
            } else {
                outputRecoveries = 0;
            }

            // 本轮模型响应结束后，才把 assistant 文本、thinking 和 tool_use 写入会话历史。
            var toolUseBlocks = toolCalls.stream()
                    .map(tc -> new ToolUseBlock(tc.toolId, tc.toolName, tc.args))
                    .toList();
            conv.addAssistantFull(text.toString(), thinkingBlocks, toolUseBlocks);

            // 用本轮 API 返回的真实 usage 重新建立压缩估算锚点。
            // 后续追加的 tool_result 或用户消息会在这个基准上增量估算。
            if (turnInput > 0 || turnOutput > 0 || turnCacheRead > 0 || turnCacheCreation > 0) {
                int baseline = turnInput + turnCacheRead + turnCacheCreation + turnOutput;
                usageAnchor = new com.mewcode.compact.ContextCompactor.UsageAnchor(
                        baseline, conv.size());
            }

            // 没有工具调用说明本轮已经给出最终回答，Agent 循环结束。
            if (toolCalls.isEmpty()) {
                if (fileHistory != null) {
                    String summary = text.length() > 60 ? text.substring(0, 60) + "..." : text.toString();
                    fileHistory.makeSnapshot(conv.size(), summary);
                }
                // 终止轮不发送 TurnComplete，只发送 LoopComplete。
                // TUI 在 TurnComplete 时会清空流缓冲，提前发送会导致最终回答无法落盘。
                putSafe(queue, new AgentEvent.LoopComplete(iteration));
                loopCompleted = true;
                break;
            }

            // 有工具调用时，执行工具；只读工具可并发，写/命令类工具由 StreamingExecutor 控制顺序。
            var executor = new StreamingExecutor(registry, checker, hookEngine, queue, recoveryState);
            var callInfos = toolCalls.stream()
                    .map(tc -> new StreamingExecutor.ToolCallInfo(tc.toolId, tc.toolName, tc.args))
                    .toList();
            var results = executor.executeAll(callInfos);

            // 工具结果作为一条内部 user 消息写回，下一轮请求时再由协议适配器转换。
            var resultBlocks = results.stream()
                    .map(r -> new ToolResultBlock(r.toolId(), r.output(), r.isError()))
                    .toList();
            conv.addToolResultsMessage(resultBlocks);

            // 非阻塞 memory recall：工具执行完后检查 prefetch 是否就绪
            // 与 Claude Code 一致——记忆在第 1 轮工具执行后、第 2 轮迭代前注入
            if (memoryRecallFuture != null && !memoryRecallConsumed) {
                if (memoryRecallFuture.isDone()) {
                    try {
                        String recall = memoryRecallFuture.getNow("");
                        if (recall != null && !recall.isEmpty()) {
                            conv.addSystemReminder(recall);
                        }
                    } catch (Exception ignored) {}
                    memoryRecallConsumed = true;
                }
            }

            boolean exitPlanCalled = toolCalls.stream()
                    .anyMatch(tc -> "ExitPlanMode".equals(tc.toolName));
            if (exitPlanCalled) {
                putSafe(queue, new AgentEvent.TurnComplete(iteration));
                putSafe(queue, new AgentEvent.LoopComplete(iteration));
                loopCompleted = true;
                break;
            }

            putSafe(queue, new AgentEvent.TurnComplete(iteration));
        }
        } finally {
            if (!loopCompleted) {
                putSafe(queue, new AgentEvent.LoopComplete(0));
            }
        }
    }

    private String lastStreamError;

    private String events_drain_last_error(BlockingQueue<AgentEvent> queue) {
        return lastStreamError;
    }

    private static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
        try {
            // queue.put 在队列满时等待，让 UI 消费速度对 Agent 产生自然背压。
            queue.put(event);
        } catch (InterruptedException e) {
            // 恢复中断标记，交给主循环在下一次检查时结束，而不是吞掉取消信号。
            Thread.currentThread().interrupt();
        }
    }

    private record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    private record ToolCallResult(String toolId, String output, boolean isError) {}
}
