

package com.mewcode.toolresult;

import com.mewcode.conversation.ConversationManager;

import java.util.List;

/**
 * {@link ToolResultBudget#apply} 的返回结果，包含已应用替换的新
 * {@link ConversationManager} 和本次调用新产生的决策记录。
 * 输入会话不会被修改；调用方应将新记录追加到会话日志，
 * 以便之后恢复 {@code state.replacements} 中的决策。
 */
public record ApplyResult(ConversationManager apiConv, List<ContentReplacementRecord> newRecords) {}
