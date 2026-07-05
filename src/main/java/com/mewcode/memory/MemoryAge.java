
package com.mewcode.memory;

/**

 * 人类可读的记忆年龄助手。模型不擅长日期算术 —

 * 原始 ISO 时间戳不会触发陈旧推理方式

 * "47 days ago" 确实如此。

 */
public final class MemoryAge {

    private MemoryAge() {}

    /**

     * 自 mtime 以来，地板四舍五入的日子。 0 代表今天，1 代表昨天，依此类推。

     * 负输入（未来时间、时钟偏差）钳位至 0。

     */
    public static int ageDays(long mtimeMs) {
        long d = (System.currentTimeMillis() - mtimeMs) / 86_400_000L;
        return d < 0 ? 0 : (int) d;
    }

    /**

     * 人类可读的年龄："today"、"yesterday" 或 "N days ago"。

     */
    public static String age(long mtimeMs) {
        int d = ageDays(mtimeMs);
        if (d == 0) return "today";
        if (d == 1) return "yesterday";
        return d + " days ago";
    }

    /**

     * 超过 1 天的记忆的过时警告。返回 ""

     * 新鲜的（今天/昨天）记忆——警告有噪音。

     */
    public static String freshnessText(long mtimeMs) {
        int d = ageDays(mtimeMs);
        if (d <= 1) return "";
        return "This memory is " + d + " days old. "
                + "Memories are point-in-time observations, not live state — "
                + "claims about code behavior or file:line citations may be outdated. "
                + "Verify against current code before asserting as fact.";
    }
}
