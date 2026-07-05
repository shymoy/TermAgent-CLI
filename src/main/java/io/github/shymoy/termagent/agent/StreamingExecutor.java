
package io.github.shymoy.termagent.agent;

import io.github.shymoy.termagent.compact.RecoveryState;
import io.github.shymoy.termagent.hook.HookEngine;
import io.github.shymoy.termagent.permission.PermissionChecker;
import io.github.shymoy.termagent.permission.PermissionResponse;
import io.github.shymoy.termagent.tool.Tool;
import io.github.shymoy.termagent.tool.ToolCategory;
import io.github.shymoy.termagent.tool.ToolRegistry;
import io.github.shymoy.termagent.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 工具调用执行器。
 *
 * <p>它接收 Agent 收集到的完整工具调用，负责查找工具、检查权限、运行 Hook、
 * 调用 {@link Tool#execute(Map)}，并把执行过程和结果通过事件队列通知上层。</p>
 *
 * <p>连续的只读工具可以并行执行；写入或命令类工具保持串行，避免相互影响。</p>
 */
public class StreamingExecutor {

    private final ToolRegistry registry;
    private final PermissionChecker checker;

    private final HookEngine hookEngine;
    private final BlockingQueue<AgentEvent> eventQueue;
    private final RecoveryState recoveryState;

    public record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    public record ToolExecResult(String toolId, String output, boolean isError) {}

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue) {
        this(registry, checker, hookEngine, eventQueue, null);
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState) {
        this.registry = registry;
        this.checker = checker;
        this.hookEngine = hookEngine;
        this.eventQueue = eventQueue;
        this.recoveryState = recoveryState;
    }

    /**
     * 执行模型在一轮响应中产生的全部工具调用，并保持结果顺序与调用顺序一致。
     */
    public List<ToolExecResult> executeAll(List<ToolCallInfo> calls) {
        // 按相邻性分批：连续的只读工具合成一个并行批次，写/命令工具各自独占一批
        var batches = partitionToolCalls(calls);
        var results = new ArrayList<ToolExecResult>();

        for (var batch : batches) {
            if (batch.concurrent && batch.calls.size() > 1) {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = batch.calls.stream()
                            .map(call -> executor.submit(() -> executeSingle(call)))
                            .toList();
                    for (var future : futures) {
                        try { results.add(future.get()); }
                        catch (Exception ignored) {}
                    }
                }
            } else {
                for (var call : batch.calls) results.add(executeSingle(call));
            }
        }

        return results;
    }

    private record ToolBatch(boolean concurrent, List<ToolCallInfo> calls) {}

    /**
     * 根据工具类别划分执行批次：相邻的只读工具进入同一个并行批次，
     * 其余工具各自形成串行批次。
     */
    private List<ToolBatch> partitionToolCalls(List<ToolCallInfo> calls) {
        var batches = new ArrayList<ToolBatch>();
        for (var call : calls) {
            var tool = registry.get(call.toolName());
            boolean safe = tool != null && tool.category() == ToolCategory.READ;

            if (safe && !batches.isEmpty() && batches.getLast().concurrent()) {
                batches.getLast().calls().add(call);
            } else {
                batches.add(new ToolBatch(safe, new ArrayList<>(List.of(call))));
            }
        }
        return batches;
    }

    /**
     * 执行单次工具调用。完整顺序为：查找工具、权限检查、前置 Hook、
     * 调用工具、记录恢复快照、通知执行结果、运行后置 Hook。
     */
    private ToolExecResult executeSingle(ToolCallInfo call) {
        // 模型只返回工具名称，具体 Tool 实例需要从注册表中查找。
        Tool tool = registry.get(call.toolName());
        if (tool == null) {
            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), "Unknown tool", true, 0));
            return new ToolExecResult(call.toolId(), "Error: unknown tool '" + call.toolName() + "'", true);
        }

        // 权限检查优先于 hook（与 Go 版保持一致）：先拦截无权操作，再让 hook 介入
        if (checker != null) {
            var check = checker.check(tool, call.args());
            switch (check.decision()) {
                case DENY -> {
                    String msg = "Permission denied: " + check.reason();
                    putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                    return new ToolExecResult(call.toolId(), msg, true);
                }
                case ASK -> {
                    var future = new CompletableFuture<PermissionResponse>();
                    String desc = checker.describeToolAction(call.toolName(), call.args());
                    putSafe(new AgentEvent.PermissionRequestEvent(call.toolName(), desc, future));
                    PermissionResponse response;
                    try {
                        response = future.get(5, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        response = PermissionResponse.DENY;
                    }
                    if (response == PermissionResponse.DENY) {
                        putSafe(new AgentEvent.ToolResultEvent(
                                call.toolId(), call.toolName(), "Permission denied by user", true, 0));
                        return new ToolExecResult(call.toolId(), "User denied permission", true);
                    }
                    if (response == PermissionResponse.ALLOW_ALWAYS) {
                        String content = extractContent(call.toolName(), call.args());
                        if (content != null) {
                            checker.addAllowAlwaysRule(call.toolName(), content);
                        }
                    }
                }
                case ALLOW -> {}
            }
        }

        // Pre-tool hook 在权限通过后执行，可拦截特定工具调用
        if (hookEngine != null) {
            var hookResult = hookEngine.runPreToolHooks(call.toolName(), call.args());
            if (hookResult.rejected()) {
                String msg = "Rejected by hook: " + hookResult.message();
                putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                return new ToolExecResult(call.toolId(), msg, true);
            }
        }

        long start = System.nanoTime();
        ToolResult result;
        try {
            // 这里才真正进入具体工具（如 ReadFile、Bash）的 execute 方法。
            result = tool.execute(call.args());
        } catch (Exception e) {
            result = ToolResult.error("Tool execution error: " + e.getMessage());
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;

        snapshotForRecovery(call, result);

        String output = result.output();
        if (output.length() > ToolRegistry.MAX_OUTPUT_CHARS) {
            output = output.substring(0, ToolRegistry.MAX_OUTPUT_CHARS) + "\n... (truncated)";
        }

        putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), output, result.isError(), elapsed));

        // 工具执行完成后运行后置 Hook，用于审计或触发额外动作。
        if (hookEngine != null) {
            var ctx = new HookEngine.HookContext(
                    HookEngine.EventName.POST_TOOL_USE, call.toolName(), call.args(), null, null, null);
            hookEngine.runHooks(ctx);
        }

        return new ToolExecResult(call.toolId(), output, result.isError());
    }

    /** 将执行阶段产生的事件安全地交给 Agent/UI 消费，不负责执行工具本身。 */
    private void putSafe(AgentEvent event) {
        try {
            eventQueue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 保存 ReadFile 成功读取的文件内容，供二层上下文压缩后的恢复信息使用。
     * 这里重新读取原文件，避免快照依赖工具输出中的行号等展示格式。
     */
    private void snapshotForRecovery(ToolCallInfo call, ToolResult result) {
        if (recoveryState == null || result.isError()) return;
        if (!"ReadFile".equals(call.toolName())) return;
        Object pathObj = call.args() == null ? null : call.args().get("file_path");
        if (!(pathObj instanceof String) || ((String) pathObj).isEmpty()) return;
        String path = (String) pathObj;
        try {
            String content = Files.readString(Path.of(path));
            recoveryState.recordFileRead(path, content);
        } catch (IOException ignored) {
            // 快照只是辅助恢复；文件已消失时跳过，不影响本次工具调用结果。
        }
    }

    // 从常见工具参数中提取权限规则需要匹配的命令、路径或搜索模式。
    private static String extractContent(String toolName, Map<String, Object> args) {
        String field = switch (toolName) {
            case "Bash" -> "command";
            case "ReadFile", "WriteFile", "EditFile" -> "file_path";
            case "Glob", "Grep" -> "pattern";
            default -> null;
        };
        if (field == null) return null;
        var v = args.get(field);
        return v instanceof String s ? s : null;
    }
}
