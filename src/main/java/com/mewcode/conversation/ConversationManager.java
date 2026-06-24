// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * 维护项目内部的会话历史。
 *
 * <p>这里保存的是项目自己的轻量 Message，不是某个供应商的 API Message。
 * 当前内部 role 主要只有 "user" 和 "assistant"；tool result、system reminder
 * 等特殊内容通过 Message 的额外字段或包装文本表达，再由各 LlmClient 转成目标协议格式。
 */
public class ConversationManager {

    // 会话历史按追加顺序保存；当前类本身不提供并发写保护。
    private final List<Message> history = new ArrayList<>();

    // 长期记忆只允许注入一次，避免多轮 Agent 循环重复污染上下文。
    private boolean ltmInjected = false;

    /** 追加普通用户消息。 */
    public void addUserMessage(String content) {
        history.add(new Message("user", content));
    }

    /** 追加只有文本内容的 assistant 消息。 */
    public void addAssistantMessage(String content) {
        history.add(new Message("assistant", content));
    }

    /**
     * 追加完整 assistant 消息。
     * 一轮模型响应结束后，Agent 会把文本、thinking 和 tool_use 一次性写入这里。
     */
    public void addAssistantFull(String text, List<ThinkingBlock> thinking, List<ToolUseBlock> toolUses) {
        var msg = new Message("assistant", text);
        msg.setThinkingBlocks(thinking);
        msg.setToolUses(toolUses);
        history.add(msg);
    }

    /** 追加带工具调用的 assistant 消息。 */
    public void addAssistantMessageWithTools(String text, List<ToolUseBlock> toolUses) {
        var msg = new Message("assistant", text);
        msg.setToolUses(toolUses);
        history.add(msg);
    }

    /**
     * 追加工具结果消息。
     * 内部仍保存为 role="user"，具体发送时再由协议适配器转换：
     * Anthropic 转成 user + tool_result block，OpenAI compat 转成 role=tool。
     */
    public void addToolResultsMessage(List<ToolResultBlock> results) {
        var msg = new Message("user", "");
        msg.setToolResults(results);
        history.add(msg);
    }

    /**
     * 把用户指令和长期记忆包装成 system-reminder，并插到会话开头。
     * 这不是 API 层的 system role，而是一条内部 user 消息。
     */
    public void injectLongTermMemory(String instructions, String memories) {
        if (ltmInjected) return;
        var sections = new ArrayList<String>();
        if (instructions != null && !instructions.isEmpty()) {
            sections.add("# mewcodeMd\nCodebase and user instructions are shown below. Be sure to adhere to these instructions. IMPORTANT: These instructions OVERRIDE any default behavior and you MUST follow them exactly as written.\n\n" + instructions);
        }
        if (memories != null && !memories.isEmpty()) {
            sections.add("# autoMemory\n" + memories);
        }
        if (sections.isEmpty()) return;
        sections.add("# currentDate\nToday's date is " + java.time.LocalDate.now() + ".");
        String body = String.join("\n\n", sections);
        String wrapped = "<system-reminder>\nAs you answer the user's questions, you can use the following context:\n" +
            body +
            "\n\n      IMPORTANT: this context may or may not be relevant to your tasks. You should not respond to this context unless it is highly relevant to your task.\n</system-reminder>";
        history.add(0, new Message("user", wrapped));
        ltmInjected = true;
    }

    /**
     * 追加运行时系统提醒。内部仍作为 user 消息保存，
     * 通过 <system-reminder> 标签提示模型这不是普通用户输入。
     */
    public void addSystemReminder(String content) {
        history.add(new Message("user", "<system-reminder>\n" + content + "\n</system-reminder>"));
    }

    /** 返回只读快照，避免调用方直接修改内部历史列表。 */
    public List<Message> getMessages() {
        return List.copyOf(history);
    }

    /** 返回可变历史列表，供压缩/恢复等需要重写会话的内部流程使用。 */
    public List<Message> getMessagesMutable() {
        return history;
    }

    /** 当前会话消息数。 */
    public int size() {
        return history.size();
    }

    /** 删除 index 及其之后的消息，常用于回滚或恢复到某个历史边界。 */
    public void truncateTo(int index) {
        if (index >= 0 && index < history.size()) {
            history.subList(index, history.size()).clear();
        }
    }

}
