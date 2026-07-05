
package com.mewcode.tui;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**

 * 代理工作时显示的旋转动词短语。

 * 从 Go 移植：internal/tui/verbs.go

 */
public final class SpinnerVerbs {

    private SpinnerVerbs() {}

    private static final List<String> VERBS = List.of(
            "Accomplishing",
            "Architecting",
            "Baking",
            "Beboppin'",
            "Befuddling",
            "Bloviating",
            "Boogieing",
            "Boondoggling",
            "Bootstrapping",
            "Brewing",
            "Calculating",
            "Canoodling",
            "Caramelizing",
            "Cascading",
            "Cerebrating",
            "Choreographing",
            "Churning",
            "Coalescing",
            "Cogitating",
            "Combobulating",
            "Composing",
            "Computing",
            "Concocting",
            "Considering",
            "Contemplating",
            "Cooking",
            "Crafting",
            "Creating",
            "Crunching",
            "Crystallizing",
            "Cultivating",
            "Deciphering",
            "Deliberating",
            "Dilly-dallying",
            "Discombobulating",
            "Doodling",
            "Elucidating",
            "Enchanting",
            "Envisioning",
            "Fermenting",
            "Finagling",
            "Flambéing",
            "Flibbertigibbeting",
            "Flummoxing",
            "Forging",
            "Frolicking",
            "Gallivanting",
            "Garnishing",
            "Generating",
            "Germinating",
            "Grooving",
            "Harmonizing",
            "Hatching",
            "Honking",
            "Hullaballooing",
            "Ideating",
            "Imagining",
            "Improvising",
            "Incubating",
            "Inferring",
            "Infusing",
            "Kneading",
            "Lollygagging",
            "Manifesting",
            "Marinating",
            "Meandering",
            "Metamorphosing",
            "Mewing",
            "Moonwalking",
            "Moseying",
            "Mulling",
            "Musing",
            "Noodling",
            "Orbiting",
            "Orchestrating",
            "Percolating",
            "Philosophising",
            "Pondering",
            "Pontificating",
            "Pouncing",
            "Purring",
            "Puzzling",
            "Razzle-dazzling",
            "Ruminating",
            "Scampering",
            "Simmering",
            "Sketching",
            "Spelunking",
            "Spinning",
            "Sprouting",
            "Synthesizing",
            "Thinking",
            "Tinkering",
            "Transfiguring",
            "Transmuting",
            "Undulating",
            "Unfurling",
            "Unravelling",
            "Vibing",
            "Wandering",
            "Whisking",
            "Working",
            "Wrangling",
            "Zigzagging"
    );

    /**

     * 返回一个随机旋转动词（现在分词形式）。

     */
    public static String random() {
        return VERBS.get(ThreadLocalRandom.current().nextInt(VERBS.size()));
    }

    /**

     * 返回所有微调动词的不可修改视图。

     */
    public static List<String> all() {
        return VERBS;
    }

    /**

     * 将现在分词动词 ("Thinking") 转换为简单过去时

     * 形式（"Thought"）。  处理少数不规范案件并回退

     * 规则动词的机械条带-"ing" + "ed" 规则。

     */
    public static String pastTense(String verb) {
        if (verb == null || verb.isEmpty()) {
            return verb;
        }

        // 不规则/特殊情况映射
        return switch (verb) {
            case "Thinking"  -> "Thought";
            case "Brewing"   -> "Brewed";
            case "Mewing"    -> "Mewed";
            case "Beboppin'" -> "Bebopped";
            case "Dilly-dallying"  -> "Dilly-dallied";
            case "Razzle-dazzling" -> "Razzle-dazzled";
            case "Flibbertigibbeting" -> "Flibbertigibbeted";

            default -> convertRegular(verb);
        };
    }

    private static String convertRegular(String verb) {
        if (!verb.endsWith("ing")) {
            return verb + "ed";
        }

        String base = verb.substring(0, verb.length() - 3); // strip "ing"

        // 词根已经以 'e' 结尾的动词在 -ing 之前被删除：

        // e.g。 "Composing" -> 基础 "Compos" -> 恢复 'e' -> "Composed"

        // 通过检查茎看起来是否有静音 e 来检测

        // （需要恢复“e”的辅音结尾）。

        // 简单启发式：如果碱基以辅音簇结尾，则典型

        // 不发音的 e 动词，恢复“e”。
        char last = base.charAt(base.length() - 1);

        if (isDoubledConsonant(base)) {
            // e.g。 "Spinning" -> 基础 "Spinn" -> "Spinned"（删除重复项）
            return base.substring(0, base.length() - 1) + "ed";
        }

        if ("aeiouy".indexOf(last) >= 0) {
            // 基数以元音结尾：只需添加“d”
            // e.g。 "Cascading" -> 基数 "Cascad" -- 非元音，跳过
            // 实际上 "Ideating" -> 基数 "Ideat" ——也不是元音
            // 这处理：基数以元音结尾，如 "Grooving" -> "Groov" -> no。
            // 大多数情况下我们只需添加 "ed" 即可。
            return base + "d";
        }

        // 对于最初具有不发音-e 的词干（e.g.、来自 "compose" 的 "Composing"）：

        // 常见模式是以 s、z、c、g、v、t、l、n、r + "ing" 结尾的碱基

        // 我们恢复“e”并添加“d”。
        if ("szgvcln".indexOf(last) >= 0) {
            return base + "ed";
        }

        // 默认：将 "ed" 添加到基础上
        return base + "ed";
    }

    /**

     * 检查基部是否以两个相同的辅音（双音）结尾。

     */
    private static boolean isDoubledConsonant(String base) {
        if (base.length() < 2) return false;
        char c1 = base.charAt(base.length() - 1);
        char c2 = base.charAt(base.length() - 2);
        return c1 == c2 && "aeiouy".indexOf(c1) < 0;
    }
}
