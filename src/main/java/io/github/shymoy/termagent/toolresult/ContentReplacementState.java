
package io.github.shymoy.termagent.toolresult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 单个会话线程的工具结果预算决策记录。
 *
 * <p>这个类不保存工具结果原文，只保存“某个结果是否已经做过裁剪决策”
 * 以及“被裁剪后应该使用哪段固定预览”。它的作用是防止同一条历史工具结果
 * 在不同轮次之间改变形态，从而保持 Prompt Cache 前缀稳定。
 *
 * <ul>
 *   <li>{@code seenIds}：所有至少经过一次 {@link ToolResultBudget#apply} 的
 *       {@code tool_use_id}。ID 一旦进入该集合，它的“保留原文或替换”决策就不再改变。</li>
 *   <li>{@code replacements}：仅记录已决定替换的 ID，值为字节完全一致的预览文本。
 *       后续轮次直接复用该文本，无需再次读写文件，也不会产生内容漂移。</li>
 * </ul>
 *
 * <p>不变式：{@code replacements} 的所有键都必须存在于 {@code seenIds} 中。
 */
public final class ContentReplacementState {

    /** 已做过预算决策的工具调用 ID，包含保留原文和已替换两种情况。 */
    private final Set<String> seenIds = new HashSet<>();

    /** 已替换的工具调用 ID 与其固定预览文本的映射。 */
    private final Map<String, String> replacements = new HashMap<>();

    /** 返回可变的已决策 ID 集合，供预算算法就地更新。 */
    public Set<String> seenIds() {
        return seenIds;
    }

    /** 返回可变的固定替换映射，供预算算法就地更新。 */
    public Map<String, String> replacements() {
        return replacements;
    }

    /**
     * 创建完全独立的副本。派生子 Agent 时，子 Agent 可以继承父 Agent 已冻结的决策，
     * 但后续修改只写入自己的集合，不会反向影响父 Agent。
     */
    public ContentReplacementState copy() {
        ContentReplacementState out = new ContentReplacementState();
        out.seenIds.addAll(this.seenIds);
        out.replacements.putAll(this.replacements);
        return out;
    }
}
