// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.conversation;

/**
 * 保存一次工具执行结果。
 * toolUseId 必须对应前面 ToolUseBlock 的 ID，协议适配器会据此生成 tool_result 或 tool output。
 */
public record ToolResultBlock(String toolUseId, String content, boolean isError) {}
