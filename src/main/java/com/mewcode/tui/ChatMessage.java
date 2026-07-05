
package com.mewcode.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**

 * TUI 聊天视图的面向显示的消息。

 * 角色：用户、助理、系统、错误、工具、tool_collapsed、tool_group、sub_agent、思考。

 */
public class ChatMessage {

    public String role;
    public String content;
    public List<ToolBlockInfo> toolGroup;
    public SubAgentBlockState subAgentBlock;
    public boolean expanded;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public ChatMessage(String role, String content, List<ToolBlockInfo> toolGroup) {
        this.role = role;
        this.content = content;
        this.toolGroup = toolGroup;
    }

    /**

     * 有关在 TUI 中渲染的单个工具调用的信息。

     */
    public record ToolBlockInfo(
            String toolName,
            Map<String, Object> args,
            String output,
            boolean isError,
            double elapsed,
            boolean collapsed,
            boolean loading
    ) {}

    /**

     * 跟踪子代理的执行并显示在 TUI 中。

     */
    public static class SubAgentBlockState {
        public String desc;

        public String agentType;
        public List<ToolBlockInfo> toolUses = new ArrayList<>();
        public boolean done;
        public int toolCount;
        public double totalTime;
    }
}
