
package io.github.shymoy.termagent.agent;

import io.github.shymoy.termagent.permission.PermissionResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 向上层 UI 发送的统一事件。
 * LlmClient 返回的 {@code StreamEvent} 会先由 Agent 消费、整理，再转换成这里的事件；
 * UI 只依赖 AgentEvent，不需要理解 Anthropic、OpenAI 等协议的流式格式。
 */
public sealed interface AgentEvent {

    // 模型正文的流式增量，UI 收到后直接追加到当前回答。
    record StreamText(String text) implements AgentEvent {}

    // ThinkingText 用于实时展示思考增量；ThinkingComplete 携带最终完整内容及协议签名。
    record ThinkingText(String text) implements AgentEvent {}

    record ThinkingComplete(String thinking, String signature) implements AgentEvent {}

    // 工具调用开始或参数解析完成时发送，用于让 UI 展示即将执行的工具及参数。
    record ToolUseEvent(String toolId, String toolName, Map<String, Object> args) implements AgentEvent {}

    // 单个工具执行结束事件；elapsed 为耗时秒数，isError 表示结果是否应按错误样式展示。
    record ToolResultEvent(String toolId, String toolName, String output,
                           boolean isError, double elapsed) implements AgentEvent {}

    // 一轮模型调用及其工具执行已经结束，但 Agent 可能携带工具结果继续请求下一轮。
    record TurnComplete(int turn) implements AgentEvent {}

    // 整个 Agent 主循环结束；此后不会再因为本次用户输入自动发起下一轮请求。
    record LoopComplete(int totalTurns) implements AgentEvent {}

    // 当前主循环累计的输入、输出 token，用于 UI 展示本次任务的总用量。
    record UsageEvent(int inputTokens, int outputTokens) implements AgentEvent {}

    // 请求或主循环异常。它负责通知 UI，不等同于一定终止循环，部分错误可能触发重试。
    record ErrorEvent(String message) implements AgentEvent {}

    // 上下文压缩和自动重试的状态通知。
    record CompactEvent(String message) implements AgentEvent {}

    record RetryEvent(String reason, long waitMs) implements AgentEvent {}

    // 权限请求是双向事件：Agent 发给 UI，并等待 UI 通过同一个 future 回填用户决定。
    record PermissionRequestEvent(String toolName, String description,
                                  CompletableFuture<PermissionResponse> future) implements AgentEvent {}

    // AskUser 同样通过 future 等待 UI 收集答案；取消时答案中会包含约定的 _declined 标记。
    record AskUserRequestEvent(
            java.util.List<io.github.shymoy.termagent.tui.dialog.AskUserDialog.Question> questions,
            CompletableFuture<Map<String, String>> future) implements AgentEvent {}
}
