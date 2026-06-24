// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.conversation;

import java.util.Map;

/**
 * 保存 assistant 发起的一次工具调用。
 * toolUseId 用来把后续 ToolResultBlock 精确关联回这次调用。
 */
public record ToolUseBlock(String toolUseId, String toolName, Map<String, Object> arguments) {}
