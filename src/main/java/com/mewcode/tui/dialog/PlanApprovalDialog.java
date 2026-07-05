
package com.mewcode.tui.dialog;

import com.mewcode.tui.Styles;
import com.mewcode.tui.tea.Style;
import com.mewcode.tui.tea.ANSI256Color;

/**

 * 计划模式完成时显示 TUI 对话框。提供三种选择：

 * <ol>

 * <li> YOLO方式执行计划（绕过所有权限）</li>

 * <li>执行计划，每次编辑均需手动批准</li>

 * <li>将反馈文本发送回代理</li>

 * </ol>

 * <p>

 * 这是一个纯字符串渲染器和键处理程序，而不是 TUI4J 组件。

 * 由{@code MewCodeModel}驱动，调用{@link #handleKey(String)}

 * 在每次按键和 {@link #render()} 每一帧上。

 *

 * @see com.mewcode.tui.MewCodeModel

 */
public class PlanApprovalDialog {

    /**

     * 光标位于三个选项中的哪一个 (0-2)。

     */
    private int cursor;

    /**

     * 选项 2 ("Send feedback") 的文本输入缓冲区。

     */
    private final StringBuilder feedbackInput = new StringBuilder();

    /**

     * 对话框当前是否可见。

     */
    private boolean active;

    // ── 样式（内联，匹配 Go renderPlanApprovalDialog） ────────────
    private static final Style HEADER_STYLE = Style.newStyle()
            .foreground(new ANSI256Color(99))
            .bold(true);

    private static final Style CURSOR_STYLE = Style.newStyle()
            .foreground(new ANSI256Color(99));

    private static final Style DIM_STYLE = Style.newStyle()
            .foreground(new ANSI256Color(242));

    private static final Style BOLD_STYLE = Style.newStyle()
            .bold(true);

    // ── 选项标签（匹配Go）──────────────────────────────────────
    private static final String[] OPTIONS = {
            "Yes, enter YOLO mode (auto-approve all)",
            "Yes, manually approve edits",
            "Tell MewCode what to change",
    };

    // ── 结果类型────────────────────────────────────────────────────

    /**

     * 用户选择的操作类型。

     */
    public enum Result {
        /**
         * 绕过所有权限（YOLO）。
         */
        YOLO,
        /**
         * 手动批准每个编辑。
         */
        MANUAL,
        /**
         * 将反馈文本发送回客服人员。
         */
        FEEDBACK,
        /**
         * 用户按下退出键；取消对话框。
         */
        CANCEL
    }

    /**

     * 当用户做出选择时返回不可变的结果。

     *

     * @param type      选择了哪个操作

     * @param feedback 反馈字符串（仅当{@code type == FEEDBACK}时有意义）

     */
    public record DialogResult(Result type, String feedback) {}

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**

     * 显示对话框、重置光标和反馈输入。

     */
    public void activate() {
        active = true;
        cursor = 0;
        feedbackInput.setLength(0);
    }

    /**

     * @return {@code true} 当对话框可见时

     */
    public boolean isActive() {
        return active;
    }

    // ── 按键处理────────────────────────────────────────────────────

    /**

     * 处理单个按键。

     *

     * @param key {@code KeyPressMessage} 中的密钥字符串（e.g."up"、"enter"、"a"）

     * 如果用户做出最终选择，则为 @return a {@link DialogResult}，或 {@code null}

     * 如果对话框保持打开状态（光标移动、文本输入等）

     */
    public DialogResult handleKey(String key) {
        switch (key) {
            case "up", "k" -> {
                if (cursor > 0) cursor--;
            }
            case "down", "j" -> {
                if (cursor < 2) cursor++;
            }
            case "enter" -> {
                if (cursor == 2 && feedbackInput.isEmpty()) {
                    // 尚未输入任何反馈——留在现场
                    return null;
                }
                active = false;
                return switch (cursor) {
                    case 0 -> new DialogResult(Result.YOLO, "");
                    case 1 -> new DialogResult(Result.MANUAL, "");
                    case 2 -> new DialogResult(Result.FEEDBACK, feedbackInput.toString());

                    default -> null;
                };
            }
            case "shift+tab" -> {
                // 批准并提供反馈（转到：选项 2 上的 shift+tab 发送反馈 AND 退出计划模式）
                if (cursor == 2 && !feedbackInput.isEmpty()) {
                    active = false;
                    return new DialogResult(Result.FEEDBACK, feedbackInput.toString());
                }
            }
            case "escape" -> {
                active = false;
                return new DialogResult(Result.CANCEL, "");
            }
            case "backspace" -> {
                if (cursor == 2 && !feedbackInput.isEmpty()) {
                    feedbackInput.deleteCharAt(feedbackInput.length() - 1);
                }
            }
            default -> {
                if (cursor == 2 && key.length() == 1) {
                    char ch = key.charAt(0);
                    if (ch >= 32 && ch <= 126) {
                        feedbackInput.append(ch);
                    }
                } else if (cursor == 2 && " ".equals(key)) {
                    feedbackInput.append(' ');
                }
            }
        }
        return null;
    }

    // ── Rendering ───────────────────────────────────────────────────────

    /**

     * 将对话框呈现为纯字符串（ANSI 样式）。

     * 布局与 Go {@code renderPlanApprovalDialog} 匹配。

     */
    public String render() {
        var sb = new StringBuilder();

        // Header
        sb.append(HEADER_STYLE.render(
                " MewCode has written up a plan and is ready to execute. Would you like to proceed?"));
        sb.append("\n\n");

        // 选项列表
        for (int i = 0; i < OPTIONS.length; i++) {
            // 光标前缀
            String prefix;
            if (i == cursor) {
                prefix = CURSOR_STYLE.render(" ❯ ");
            } else {
                prefix = "   ";
            }

            // Label
            String label;
            if (i == cursor) {
                label = BOLD_STYLE.render(OPTIONS[i]);
            } else {
                label = DIM_STYLE.render(OPTIONS[i]);
            }

            sb.append(prefix);
            sb.append(String.format("%d. %s", i + 1, label));
            sb.append('\n');

            // 反馈输入字段（仅适用于选项 2）
            if (i == 2) {
                String inputLine = feedbackInput.toString();
                if (cursor == 2) {
                    inputLine += "█"; // block cursor
                }
                if ((cursor == 2 && inputLine.equals("█")) || inputLine.isEmpty()) {
                    // 空时显示占位符
                    if (cursor == 2) {
                        String placeholder = DIM_STYLE.render("Type feedback here...");
                        sb.append("      ").append(placeholder).append('\n');
                    }
                } else {
                    sb.append("      ").append(inputLine).append('\n');
                }
                // Hint
                String hint = DIM_STYLE.render("      shift+tab to approve with this feedback");
                sb.append(hint);
                sb.append('\n');
            }
        }

        sb.append('\n');
        return sb.toString();
    }
}
