
package io.github.shymoy.termagent.toolresult;

import io.github.shymoy.termagent.conversation.ConversationManager;
import io.github.shymoy.termagent.conversation.Message;
import io.github.shymoy.termagent.conversation.ToolResultBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第一层工具结果预算（设计 B）。遍历输入会话，结合
 * {@link ContentReplacementState} 决定每个 tool_result 应保留原文还是替换为预览，
 * 然后返回一个已应用替换的新 {@link ConversationManager}。
 * 输入的 {@code conv} 始终不会被修改。{@code state.seenIds} 和
 * {@code state.replacements} 会记录本轮决策；后续调用将原样应用这些决策
 * （使用字节完全一致的预览文本，无需再次 I/O），使跨轮次的 Prompt Cache 前缀保持稳定。
 *
 * <p>本实现取代了已废弃的 {@code ContextCompactor.applyToolResultBudget}：
 * 旧实现只处理单个结果落盘，从未被主循环调用，并且会修改输入会话。
 *
 * <p>数据流可概括为：完整的 {@code conv} -> 本类生成的裁剪副本 -> LLM API。
 * 原会话不会因轻量裁剪丢失工具原始输出；被替换的完整内容另外写入磁盘。
 */
public final class ToolResultBudget {

    /** 单个工具结果超过该字符数时直接落盘；这里的单位是字符，不是 token。 */
    public static final int SINGLE_RESULT_LIMIT = 50_000;

    /** 同一条消息中多个工具结果的合计字符预算。 */
    public static final int MESSAGE_AGGREGATE_LIMIT = 200_000;

    /** 工具结果落盘目录，相对于 {@code sessionDir}。 */
    public static final String SPILL_SUBDIR = "tool_results";

    private static final String PERSISTED_TAG_PREFIX = "[Result of ";
    private ToolResultBudget() {}

    public static ApplyResult apply(
            ConversationManager conv,
            Path sessionDir,
            ContentReplacementState state
    ) {
        List<Message> messages = conv.getMessages();
        if (messages.isEmpty()) {
            return new ApplyResult(conv, List.of());
        }

        Path spillDir = sessionDir.resolve(SPILL_SUBDIR);
        // records 供调用方持久化“toolUseId -> 固定替换文本”，用于会话恢复。
        List<ContentReplacementRecord> records = new ArrayList<>();
        // newHistory 是本次发给 API 的新视图，不是对 conv.history 的就地修改。
        List<Message> newHistory = new ArrayList<>(messages.size());

        for (Message msg : messages) {
            List<ToolResultBlock> trs = msg.getToolResults();
            if (trs == null || trs.isEmpty()) {
                newHistory.add(msg);
                continue;
            }

            // 每个 toolUseId 在本条消息中最终应发送的内容：原文或固定预览。
            Map<String, String> decisions = new HashMap<>(trs.size() * 2);
            // fresh 只包含从未做过决策的结果；历史决策必须原样复用。
            List<ToolResultBlock> fresh = new ArrayList<>();

            for (ToolResultBlock tr : trs) {
                String id = tr.toolUseId();
                String existing = state.replacements().get(id);
                if (existing != null) {
                    // 复用字节级稳定的预览，避免改写历史前缀导致 Prompt Cache 失效。
                    decisions.put(id, existing);
                    continue;
                }
                if (state.seenIds().contains(id)) {
                    // 该结果曾经被完整发送，因此冻结为原文，不能在后续轮次再改成预览。
                    decisions.put(id, tr.content());
                    continue;
                }
                if (isAlreadyReplaced(tr.content())) {
                    // 外部传入的已标记内容：直接将该标记文本冻结为替换结果。
                    state.seenIds().add(id);
                    state.replacements().put(id, tr.content());
                    decisions.put(id, tr.content());
                    records.add(ContentReplacementRecord.toolResult(id, tr.content()));
                    continue;
                }
                fresh.add(tr);
            }

            // 第一遍：不考虑其他并发结果，先落盘所有单个超过 50,000 字符的结果。
            Set<String> persistedByP1 = new HashSet<>();
            for (ToolResultBlock tr : fresh) {
                if (tr.content().length() <= SINGLE_RESULT_LIMIT) continue;
                String preview = spillAndPreview(spillDir, tr);
                if (preview == null) {
                    // 落盘失败：冻结为原文，后续不再重试替换。
                    state.seenIds().add(tr.toolUseId());
                    decisions.put(tr.toolUseId(), tr.content());
                    persistedByP1.add(tr.toolUseId());
                    continue;
                }
                decisions.put(tr.toolUseId(), preview);
                state.seenIds().add(tr.toolUseId());
                state.replacements().put(tr.toolUseId(), preview);
                records.add(ContentReplacementRecord.toolResult(tr.toolUseId(), preview));
                persistedByP1.add(tr.toolUseId());
            }

            // 第二遍：若同一条 tool_result 消息仍超出总预算，从最大的未裁剪结果开始落盘，
            // 直到总字符数 <= MESSAGE_AGGREGATE_LIMIT。“最大优先”可用更少的替换释放更多空间。
            List<ToolResultBlock> remaining = new ArrayList<>();
            for (ToolResultBlock tr : fresh) {
                if (!persistedByP1.contains(tr.toolUseId())) {
                    remaining.add(tr);
                }
            }

            // total 既包含已冻结的原文/预览，也包含本轮尚未决策的新结果。
            int total = 0;
            for (String content : decisions.values()) total += content.length();
            for (ToolResultBlock tr : remaining) total += tr.content().length();

            if (total > MESSAGE_AGGREGATE_LIMIT && !remaining.isEmpty()) {
                List<ToolResultBlock> sorted = new ArrayList<>(remaining);
                sorted.sort(Comparator.comparingInt((ToolResultBlock t) -> t.content().length()).reversed());
                for (ToolResultBlock tr : sorted) {
                    if (total <= MESSAGE_AGGREGATE_LIMIT) break;
                    String preview = spillAndPreview(spillDir, tr);
                    if (preview == null) {
                        state.seenIds().add(tr.toolUseId());
                        decisions.put(tr.toolUseId(), tr.content());
                        continue;
                    }
                    decisions.put(tr.toolUseId(), preview);
                    state.seenIds().add(tr.toolUseId());
                    state.replacements().put(tr.toolUseId(), preview);
                    records.add(ContentReplacementRecord.toolResult(tr.toolUseId(), preview));
                    total -= tr.content().length() - preview.length();
                }
            }

            // 剩余 fresh 未触发任何预算：将其冻结为“已见且保留原文”。
            // 以后即使预算环境变化，也不回头改写已发送过的历史。
            for (ToolResultBlock tr : fresh) {
                if (decisions.containsKey(tr.toolUseId())) continue;
                state.seenIds().add(tr.toolUseId());
                decisions.put(tr.toolUseId(), tr.content());
            }

            // 按原始顺序物化结果，不让前面的“按体积排序”改变 tool_result 顺序。
            List<ToolResultBlock> newResults = new ArrayList<>(trs.size());
            for (ToolResultBlock tr : trs) {
                newResults.add(new ToolResultBlock(
                        tr.toolUseId(),
                        decisions.get(tr.toolUseId()),
                        tr.isError()
                ));
            }
            newHistory.add(copyMessageWithResults(msg, newResults));
        }

        // apiConv 是裁剪后副本；records 由 Agent 以 append-only 方式写入磁盘。
        return new ApplyResult(buildManager(newHistory), records);
    }

    private static final int PREVIEW_CHARS = 2_000;

    private static String buildSpillPreview(String content, Path path) {
        int sizeKB = content.length() / 1024;
        String preview = content.length() <= PREVIEW_CHARS
                ? content : content.substring(0, PREVIEW_CHARS);
        boolean hasMore = content.length() > PREVIEW_CHARS;
        StringBuilder sb = new StringBuilder();
        sb.append("<persisted-output>\n");
        sb.append("输出太大（").append(sizeKB).append("KB），完整内容已保存到：\n");
        sb.append(path).append("\n\n");
        sb.append("预览（前 2KB）：\n").append(preview);
        if (hasMore) sb.append("\n...");
        sb.append("\n</persisted-output>");
        return sb.toString();
    }

    private static String spillAndPreview(Path spillDir, ToolResultBlock tr) {
        try {
            Files.createDirectories(spillDir);
            // toolUseId 在会话内唯一，同时作为落盘文件名，便于预览中给出可回读路径。
            Path file = spillDir.resolve(tr.toolUseId());
            if (Files.exists(file) && Files.size(file) == tr.content().length()) {
                // 相同长度的文件已存在时避免重复 I/O，并重建完全相同的预览文本。
                return buildSpillPreview(tr.content(), file);
            }
            Files.writeString(file, tr.content());
            return buildSpillPreview(tr.content(), file);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isAlreadyReplaced(String s) {
        return s != null && s.startsWith(PERSISTED_TAG_PREFIX);
    }

    private static Message copyMessageWithResults(Message src, List<ToolResultBlock> newResults) {
        Message copy = new Message(src.getRole(), src.getContent());
        copy.setThinkingBlocks(src.getThinkingBlocks());
        copy.setToolUses(src.getToolUses());
        copy.setToolResults(newResults);
        return copy;
    }

    /**
     * 根据消息列表构建全新的 {@link ConversationManager}。
     * {@code ConversationManager} 只通过 {@code addToolResultsMessage}、
     * {@code addAssistantFull} 和 {@code addUserMessage} 等方法提供写入入口，
     * 因此这里通过重放消息构建独立实例，不会触及源会话的内部历史列表。
     */
    private static ConversationManager buildManager(List<Message> messages) {
        ConversationManager out = new ConversationManager();
        for (Message m : messages) {
            boolean hasToolUses = m.getToolUses() != null && !m.getToolUses().isEmpty();
            boolean hasToolResults = m.getToolResults() != null && !m.getToolResults().isEmpty();
            if (hasToolUses) {
                out.addAssistantFull(m.getContent(), m.getThinkingBlocks(), m.getToolUses());
            } else if (hasToolResults) {
                out.addToolResultsMessage(m.getToolResults());
            } else if ("user".equals(m.getRole())) {
                out.addUserMessage(m.getContent());
            } else if ("assistant".equals(m.getRole())) {
                out.addAssistantMessage(m.getContent());
            }
        }
        return out;
    }
}
