
package com.mewcode.conversation;

import java.util.List;

/**
 * 项目内部使用的轻量消息模型。
 *
 * <p>它不是 Anthropic 或 OpenAI 的原始 API Message。内部只用 role + content
 * 保存普通对话文本，再通过 thinkingBlocks、toolUses、toolResults 表达模型思考、
 * 工具调用和工具结果。发送请求时，各 LlmClient 会把这些字段转换成目标协议格式。
 */
public class Message {

    // 当前内部主要使用 "user" 和 "assistant"；没有单独的 SYSTEM/TOOL 枚举角色。
    private String role;

    // 普通文本内容。assistant 调工具时，这里可以和 toolUses 同时存在。
    private String content;

    // 模型返回的 thinking/reasoning 内容及其签名，主要用于支持 Anthropic/OpenAI reasoning 回传。
    private List<ThinkingBlock> thinkingBlocks;

    // assistant 发起的工具调用；发送给不同 API 时会转换成 tool_use 或 function/tool call。
    private List<ToolUseBlock> toolUses;

    // 工具执行结果；内部挂在 role="user" 的消息上，协议适配时再转成目标 API 的工具结果格式。
    private List<ToolResultBlock> toolResults;

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<ThinkingBlock> getThinkingBlocks() { return thinkingBlocks; }

    public void setThinkingBlocks(List<ThinkingBlock> thinkingBlocks) { this.thinkingBlocks = thinkingBlocks; }

    public List<ToolUseBlock> getToolUses() { return toolUses; }

    public void setToolUses(List<ToolUseBlock> toolUses) { this.toolUses = toolUses; }

    public List<ToolResultBlock> getToolResults() { return toolResults; }
    public void setToolResults(List<ToolResultBlock> toolResults) { this.toolResults = toolResults; }
}
