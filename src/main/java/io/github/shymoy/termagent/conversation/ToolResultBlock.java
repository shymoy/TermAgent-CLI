
package io.github.shymoy.termagent.conversation;

/**
 * 保存一次工具执行结果。
 * toolUseId 必须对应前面 ToolUseBlock 的 ID，协议适配器会据此生成 tool_result 或 tool output。
 */
public record ToolResultBlock(String toolUseId, String content, boolean isError) {}
