

package com.mewcode.conversation;

import java.util.Map;

/**
 * 保存 assistant 发起的一次工具调用。
 * toolUseId 用来把后续 ToolResultBlock 精确关联回这次调用。
 */
public record ToolUseBlock(String toolUseId, String toolName, Map<String, Object> arguments) {}
