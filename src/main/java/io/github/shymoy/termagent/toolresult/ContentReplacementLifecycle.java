

package io.github.shymoy.termagent.toolresult;

import io.github.shymoy.termagent.conversation.Message;
import io.github.shymoy.termagent.conversation.ToolResultBlock;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从会话记录重建 {@link ContentReplacementState}，
 * 使恢复后的会话与原会话使用相同的工具结果替换决策。
 *
 * <ol>
 *   <li>将 {@code messages} 中已出现的每个 {@code tool_use_id}
 *       加入 {@code seenIds}。这些内容已发送给模型，因此决策必须冻结。</li>
 *   <li>将磁盘记录中的固定预览覆盖到 {@code replacements}。</li>
 *   <li>如果存在 {@code inheritedReplacements}，用父 Agent 的实时状态
 *       填补 fork-resume 场景中的缺口。</li>
 * </ol>
 */
public final class ContentReplacementLifecycle {

    private ContentReplacementLifecycle() {}

    public static ContentReplacementState reconstruct(
            List<Message> messages,
            List<ContentReplacementRecord> records,
            Map<String, String> inheritedReplacements
    ) {
        ContentReplacementState state = new ContentReplacementState();
        Set<String> candidateIds = new HashSet<>();
        for (Message m : messages) {
            if (m.getToolResults() == null) continue;
            for (ToolResultBlock tr : m.getToolResults()) {
                candidateIds.add(tr.toolUseId());
            }
        }
        state.seenIds().addAll(candidateIds);
        for (ContentReplacementRecord r : records) {
            if (!ContentReplacementRecord.KIND_TOOL_RESULT.equals(r.kind())) continue;
            if (candidateIds.contains(r.toolUseId())) {
                state.replacements().put(r.toolUseId(), r.replacement());
            }
        }
        if (inheritedReplacements != null) {
            for (Map.Entry<String, String> e : inheritedReplacements.entrySet()) {
                if (!candidateIds.contains(e.getKey())) continue;
                state.replacements().putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return state;
    }
}
