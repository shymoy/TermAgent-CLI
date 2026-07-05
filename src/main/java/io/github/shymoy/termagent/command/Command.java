
package io.github.shymoy.termagent.command;

/**

 * 斜杠命令定义。

 * 从 Go 移植：internal/commands/commands.go（命令结构）。

 *

 * @param name        不带前导斜杠的规范名称（e.g."help"）

 * /help 输出中显示的 @param description one 行描述

 * @param aliases     替代名称（e.g。{"h"，"?"}寻求帮助）

 * @param type        命令如何调度

 * @param hidden      if true，从/help列表中省略

 */
public record Command(
        String name,
        String description,
        String[] aliases,
        CommandType type,
        boolean hidden
) {

    /**

     * 命令的调度样式。

     */
    public enum CommandType {
        /**
         * 返回文本输出的同步处理程序。
         */
        LOCAL,
        /**
         * TUI 动作（清屏、模式切换）——无文本输出。
         */
        LOCAL_UI,
        /**
         * 生成发送到 LLM 代理的提示字符串。
         */
        PROMPT
    }

    /**

     * 当 {@code input} 与规范名称匹配时返回 {@code true}

     * 或任何别名（精确、区分大小写的比较）。

     */
    public boolean matches(String input) {
        if (name.equals(input)) {
            return true;
        }
        for (var alias : aliases) {
            if (alias.equals(input)) {
                return true;
            }
        }
        return false;
    }
}

