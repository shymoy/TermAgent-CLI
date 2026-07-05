
package io.github.shymoy.termagent.tui.dialog;

import io.github.shymoy.termagent.tui.tea.Style;
import io.github.shymoy.termagent.tui.tea.ANSI256Color;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**

 * 用于多问题调查的 TUI 对话框（"ask user" 工具）。

 * <p>

 * 支持单选、多选（复选框）和自由文本 "Other" 输入

 * 每个问题。  对于多问题调查，选项卡式导航栏让

 * 用户在查看和提交所有答案之前在问题之间移动。

 * <p>

 * 这是一个纯字符串渲染器和键处理程序——而不是 TUI4J 组件。

 * 由{@code TermAgentModel}驱动，调用{@link #handleKey(String)}

 * 在每个按键和 {@link #render(int)} 每一帧上。

 *

 * @see io.github.shymoy.termagent.tui.TermAgentModel

 */
public class AskUserDialog {

    // ── 样式（匹配Go renderQuestionNavBar / renderQuestionView）──
    private static final ANSI256Color BRAND_PURPLE = new ANSI256Color(99);
    private static final ANSI256Color DIM_TEXT     = new ANSI256Color(242);
    private static final ANSI256Color BRIGHT_TEXT  = new ANSI256Color(255);
    private static final ANSI256Color TAB_FG       = new ANSI256Color(250);

    private static final Style HEADER_STYLE = Style.newStyle()
            .foreground(BRAND_PURPLE).bold(true);
    private static final Style CURSOR_STYLE = Style.newStyle()
            .foreground(BRAND_PURPLE);
    private static final Style BOLD_STYLE = Style.newStyle()
            .bold(true);

    private static final Style DIM_STYLE = Style.newStyle()
            .foreground(DIM_TEXT);
    private static final Style ACTIVE_TAB = Style.newStyle()
            .background(BRAND_PURPLE).foreground(BRIGHT_TEXT).bold(true).padding(0, 1);
    private static final Style INACTIVE_TAB = Style.newStyle()
            .foreground(TAB_FG).padding(0, 1);
    private static final Style BRIGHT_ARROW = Style.newStyle()
            .foreground(BRAND_PURPLE).bold(true);
    private static final Style DIM_ARROW = Style.newStyle()
            .foreground(DIM_TEXT);

    // ── State ───────────────────────────────────────────────────────────
    private boolean active;
    private List<Question> questions;

    /**

     * 当前显示的问题的索引。

     */
    private int questionIndex;

    /**

     * 每个问题的光标位置（选项索引 + "Other"）。

     */
    private int[] cursors;

    /**

     * 每个问题的选定选项索引集（用于多项选择）。

     */
    private List<Set<Integer>> selected;

    /**

     * 每个问题的自由文本 "Other" 输入。

     */
    private String[] otherText;

    /**

     * 收集的答案按问题索引键入。

     */
    private Map<Integer, String> answers;

    /**

     * 我们是否处于最终的“提交/取消”视图。

     */
    private boolean onSubmitTab;

    /**

     * 0 = 提交，1 = 取消提交视图。

     */
    private int submitCursor;

    // ── 数据记录────────────────────────────────────────────────────

    /**

     * 一个调查问题。

     *

     * @param text        标题中显示的问题文本

     * @param header       选项卡栏的短标签（回退到 "Q{n}"）

     * @param options     可选选项

     * @param multiSelect if {@code true}，复选框；否则无线电风格

     */
    public record Question(String text, String header, List<Option> options, boolean multiSelect) {}

    /**

     * 问题中的单个可选选项。

     *

     * @param label       显示标签

     * @param description  标签右侧显示的简短说明

     */
    public record Option(String label, String description) {}

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**

     * 显示对话框，重置一组新问题的所有状态。

     *

     * @param questions 要提出的问题清单

     */
    public void activate(List<Question> questions) {
        this.active = true;
        this.questions = questions != null ? questions : List.of();
        this.questionIndex = 0;

        int n = this.questions.size();
        this.cursors = new int[n];
        this.otherText = new String[n];
        this.selected = new ArrayList<>(n);
        this.answers = new HashMap<>();
        this.onSubmitTab = false;
        this.submitCursor = 0;

        for (int i = 0; i < n; i++) {
            otherText[i] = "";
            selected.add(new TreeSet<>());
        }
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

     * @param key {@code KeyPressMessage} 中的密钥字符串

     * 用户提交时（问题文本 -> 答案字符串）的 @return a map，

     * 取消时的单例映射 {@code {"_declined" -> "true"}}，

     * 或 {@code null}（如果对话框保持打开状态）

     */
    public Map<String, String> handleKey(String key) {
        if (questions == null || questions.isEmpty()) {
            return null;
        }

        boolean multiQuestion = questions.size() > 1;

        // ── Submit tab ──────────────────────────────────────────────────
        if (onSubmitTab) {
            return handleSubmitTabKey(key, multiQuestion);
        }

        // ── 问题视图────────────────────────────────────────────────
        Question q = questions.get(questionIndex);
        int optCount = q.options().size() + 1; // options + "Other"
        int cursor = cursors[questionIndex];

        switch (key) {
            case "up", "k" -> {
                if (cursor > 0) cursors[questionIndex]--;
            }
            case "down", "j" -> {
                if (cursor < optCount - 1) cursors[questionIndex]++;
            }
            case "left", "shift+tab" -> {
                if (multiQuestion && questionIndex > 0) {
                    questionIndex--;
                }
            }
            case "right", "tab" -> {
                if (multiQuestion) {
                    if (questionIndex < questions.size() - 1) {
                        questionIndex++;
                    } else {
                        onSubmitTab = true;
                        submitCursor = 0;
                    }
                }
            }
            case " " -> {
                if (q.multiSelect() && cursor < q.options().size()) {
                    Set<Integer> sel = selected.get(questionIndex);
                    if (sel.contains(cursor)) {
                        sel.remove(cursor);
                    } else {
                        sel.add(cursor);
                    }
                }
            }
            case "enter" -> {
                saveCurrentAnswer();
                // 单题单选：立即提交
                if (!multiQuestion && !q.multiSelect()) {
                    return submitAllAnswers();
                }
                // 前进到下一个问题或提交选项卡
                if (questionIndex < questions.size() - 1) {
                    questionIndex++;
                } else {
                    onSubmitTab = true;
                    submitCursor = 0;
                }
            }
            case "backspace" -> {
                if (cursor == q.options().size() && !otherText[questionIndex].isEmpty()) {
                    otherText[questionIndex] = otherText[questionIndex]
                            .substring(0, otherText[questionIndex].length() - 1);
                }
            }
            case "escape" -> {
                return cancelDialog();
            }
            default -> {
                // 输入 "Other" 字段
                if (cursor == q.options().size()) {
                    if (key.length() == 1) {
                        char ch = key.charAt(0);
                        if (ch >= 32 && ch <= 126) {
                            otherText[questionIndex] += ch;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**

     * 当提交/取消选项卡处于活动状态时处理按键。

     */
    private Map<String, String> handleSubmitTabKey(String key, boolean multiQuestion) {
        switch (key) {
            case "up", "k" -> {
                if (submitCursor > 0) submitCursor--;
            }
            case "down", "j" -> {
                if (submitCursor < 1) submitCursor++;
            }
            case "left", "shift+tab" -> {
                if (multiQuestion) {
                    onSubmitTab = false;
                    questionIndex = questions.size() - 1;
                }
            }
            case "enter" -> {
                if (submitCursor == 0) {
                    return submitAllAnswers();
                }
                return cancelDialog();
            }
            case "escape" -> {
                return cancelDialog();
            }
        }
        return null;
    }

    // ── 答案收集助手────────────────────────────────────────

    /**

     * 将当前问题的答案保留到 {@link #answers} 地图中

     * 基于光标位置和选择状态。

     */
    private void saveCurrentAnswer() {
        Question q = questions.get(questionIndex);
        int cursor = cursors[questionIndex];

        if (cursor == q.options().size()) {
            // "Other" 已选择
            String other = otherText[questionIndex];
            answers.put(questionIndex, other.isEmpty() ? "Other" : other);
        } else if (q.multiSelect()) {
            Set<Integer> sel = selected.get(questionIndex);
            var labels = new ArrayList<String>();
            for (int i = 0; i < q.options().size(); i++) {
                if (sel.contains(i)) {
                    labels.add(q.options().get(i).label());
                }
            }
            if (labels.isEmpty()) {
                // 没有切换 - 使用光标下的选项
                labels.add(q.options().get(cursor).label());
            }
            answers.put(questionIndex, String.join(", ", labels));
        } else {
            answers.put(questionIndex, q.options().get(cursor).label());
        }
    }

    /**

     * 将所有答案收集到由问题文本键入的地图中，然后关闭对话框。

     */
    private Map<String, String> submitAllAnswers() {
        active = false;
        var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < questions.size(); i++) {
            String answer = answers.get(i);
            if (answer != null) {
                result.put(questions.get(i).text(), answer);
            }
        }
        return result;
    }

    /**

     * 使用取消标记关闭对话框。

     */
    private Map<String, String> cancelDialog() {
        active = false;
        return Map.of("_declined", "true");
    }

    // ── Rendering ───────────────────────────────────────────────────────

    /**

     * 将对话框呈现为纯 ANSI 样式的字符串。

     *

     * @param width 终端宽度（用于布局提示）

     * @return the 渲染的对话框字符串

     */
    public String render(int width) {
        if (!active || questions == null || questions.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder();
        boolean multiQuestion = questions.size() > 1;

        // 导航栏（仅限多问题）
        if (multiQuestion) {
            sb.append(renderNavBar());
            sb.append("\n\n");
        }

        // 正文：提交视图或问题视图
        if (onSubmitTab) {
            sb.append(renderSubmitView());
        } else {
            sb.append(renderQuestionView());
        }

        // 底部提示（多问题，仅限问题视图）
        if (multiQuestion && !onSubmitTab) {
            sb.append(DIM_STYLE.render(
                    "      ← → navigate questions · enter to confirm"));
            sb.append("\n\n");
        }

        return sb.toString();
    }

    // ── 导航栏──────────────────────────────────────────────────

    private String renderNavBar() {
        var sb = new StringBuilder();

        // Left arrow
        if (questionIndex == 0 && !onSubmitTab) {
            sb.append(DIM_ARROW.render(" ←"));
        } else {
            sb.append(BRIGHT_ARROW.render(" ←"));
        }

        // 问题选项卡
        for (int i = 0; i < questions.size(); i++) {
            String header = questions.get(i).header();
            if (header == null || header.isEmpty()) {
                header = "Q" + (i + 1);
            }
            String check = answers.containsKey(i) ? "☑" : "☐";
            String label = header + " " + check;

            if (!onSubmitTab && i == questionIndex) {
                sb.append(ACTIVE_TAB.render(label));
            } else {
                sb.append(INACTIVE_TAB.render(label));
            }
        }

        // Submit tab
        String submitLabel = "✓ Submit";
        if (onSubmitTab) {
            sb.append(ACTIVE_TAB.render(submitLabel));
        } else {
            sb.append(INACTIVE_TAB.render(submitLabel));
        }

        // 右箭头
        if (onSubmitTab) {
            sb.append(DIM_ARROW.render(" →"));
        } else {
            sb.append(BRIGHT_ARROW.render(" →"));
        }

        return sb.toString();
    }

    // ── 问题视图────────────────────────────────────────────────────

    private String renderQuestionView() {
        var sb = new StringBuilder();
        Question q = questions.get(questionIndex);
        int cursor = cursors[questionIndex];
        int lines = 0;

        // 问题标题
        sb.append(HEADER_STYLE.render(" " + q.text()));
        sb.append("\n\n");
        lines += 2;

        // Options
        for (int i = 0; i < q.options().size(); i++) {
            Option opt = q.options().get(i);

            // 光标前缀
            String prefix;
            if (i == cursor) {
                prefix = CURSOR_STYLE.render(" ❯ ");
            } else {
                prefix = "   ";
            }

            // 多选复选框
            if (q.multiSelect()) {
                String check = selected.get(questionIndex).contains(i) ? "●" : "○";
                prefix += check + " ";
            }

            // Label
            String label;
            if (i == cursor) {
                label = BOLD_STYLE.render(opt.label());
            } else {
                label = opt.label();
            }

            // 描述
            String desc = "";
            if (opt.description() != null && !opt.description().isEmpty()) {
                desc = DIM_STYLE.render(" — " + opt.description());
            }

            sb.append(prefix).append(label).append(desc).append('\n');
            lines++;
        }

        // "Other" 选项
        int otherIdx = q.options().size();
        String otherPrefix;
        if (cursor == otherIdx) {
            otherPrefix = CURSOR_STYLE.render(" ❯ ");
        } else {
            otherPrefix = "   ";
        }
        String otherLabel;
        if (cursor == otherIdx) {
            otherLabel = BOLD_STYLE.render("Other");
        } else {
            otherLabel = DIM_STYLE.render("Other");
        }
        sb.append(otherPrefix).append(otherLabel);
        if (cursor == otherIdx) {
            sb.append(": ").append(otherText[questionIndex]).append("█"); // block cursor
        }
        sb.append('\n');
        lines++;

        // 多选提示
        if (q.multiSelect()) {
            sb.append(DIM_STYLE.render("      space to toggle, enter to confirm"));
            sb.append('\n');
            lines++;
        }

        // 垫到固定高度，因此切换问题不会导致布局变化
        if (questions.size() > 1) {
            int target = maxLines();
            while (lines < target) {
                sb.append('\n');
                lines++;
            }
        }

        return sb.toString();
    }

    // ── 提交查看──────────────────────────────────────────────────────

    private String renderSubmitView() {
        var sb = new StringBuilder();
        int lines = 0;

        sb.append(HEADER_STYLE.render(" Review your answers:"));
        sb.append("\n\n");
        lines += 2;

        // 答案总结
        for (int i = 0; i < questions.size(); i++) {
            String label = questions.get(i).header();
            if (label == null || label.isEmpty()) {
                label = "Q" + (i + 1);
            }
            String answer = answers.get(i);
            if (answer != null) {
                sb.append(String.format("   %s: %s%n", label, answer));
            } else {
                sb.append(DIM_STYLE.render(String.format("   %s: (not answered)", label)));
                sb.append('\n');
            }
            lines++;
        }
        sb.append('\n');
        lines++;

        // 提交/取消
        String[] actions = {"Submit answers", "Cancel"};
        for (int i = 0; i < actions.length; i++) {
            if (i == submitCursor) {
                String prefix = CURSOR_STYLE.render(" ❯ ");
                String label = BOLD_STYLE.render(actions[i]);
                sb.append(prefix).append(label).append('\n');
            } else {
                sb.append("   ").append(actions[i]).append('\n');
            }
            lines++;
        }

        // 垫以匹配问题视图高度
        int target = maxLines();
        while (lines < target) {
            sb.append('\n');
            lines++;
        }

        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**

     * 计算所有问题的最大渲染行数，

     * 以便视图可以填充到统一的高度。

     */
    private int maxLines() {
        int max = 0;
        for (Question q : questions) {
            int lines = 2 + q.options().size() + 1; // header + blank + options + Other
            if (q.multiSelect()) {
                lines++; // "space to toggle" hint
            }
            if (lines > max) {
                max = lines;
            }
        }
        return max;
    }
}
