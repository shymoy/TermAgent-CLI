
package io.github.shymoy.termagent.toolresult;

/**
 * 表示 {@link ToolResultBudget#apply} 生成的一条工具结果替换决策。
 * 记录可持久化为 JSONL，供 {@link ContentReplacementLifecycle#reconstruct}
 * 在恢复会话时重建状态。
 */
public record ContentReplacementRecord(String kind, String toolUseId, String replacement) {

    public static final String KIND_TOOL_RESULT = "tool-result";

    public static ContentReplacementRecord toolResult(String toolUseId, String replacement) {
        return new ContentReplacementRecord(KIND_TOOL_RESULT, toolUseId, replacement);
    }
}
