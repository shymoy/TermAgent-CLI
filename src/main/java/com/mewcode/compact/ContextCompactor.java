
package com.mewcode.compact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.session.SessionManager;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * 两层上下文压缩：第一层在本地卸载并裁剪内容，
 * 第二层在已用 token 接近上下文窗口上限时触发完整的 LLM 摘要。
 */
public final class ContextCompactor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // AUTOCOMPACT_THRESHOLD 是旧版比例阈值（仅保留作参考）。
    // 当前判断使用下方的绝对 token 公式，与 Claude Code 的 autoCompact.ts 保持一致：
    // 当已用 token >= effectiveWindow − margin 时触发。
    private static final double AUTOCOMPACT_THRESHOLD = 0.80;

    // SUMMARY_OUTPUT_RESERVE 为摘要响应本身预留空间，因此有效窗口为
    // contextWindow − min(型号maxOutput, SUMMARY_OUTPUT_RESERVE)。
    private static final int SUMMARY_OUTPUT_RESERVE = 20_000;
    // AUTO_COMPACT_SAFETY_MARGIN 设置低于有效窗口的自动压缩软触发线。
    private static final int AUTO_COMPACT_SAFETY_MARGIN = 13_000;
    // MANUAL_COMPACT_SAFETY_MARGIN 设置硬阻断线：当已用 token 越过
    // effectiveWindow − MANUAL_COMPACT_SAFETY_MARGIN 时强制压缩，
    // 不再依赖软触发。
    private static final int MANUAL_COMPACT_SAFETY_MARGIN = 3_000;

    private static final int SINGLE_RESULT_LIMIT = 50_000;
    private static final int MESSAGE_AGGREGATE_LIMIT = 200_000;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    // ── messagesToKeep 窗口（与 Claude Code compact.ts 保持一致）────────
    // 第二层压缩不再把整个对话折叠成单独一份摘要；它会原样保留最近的尾部消息，
    // 只摘要较早的前缀。KEEP_RECENT_TOKENS 是尝试保留的下限，
    // KEEP_MAX_TOKENS 是上限；MIN_KEEP_MESSAGES 保证即使消息很短，
    // 也至少保留最近几轮原始对话。
    private static final int KEEP_RECENT_TOKENS = 10_000;
    private static final int MIN_KEEP_MESSAGES = 5;
    private static final int KEEP_MAX_TOKENS = 40_000;

    private static final String SPILL_SUBDIR = ".mewcode/tool_results";

    /** 第二层摘要后所追加恢复附件的限制。 */
    public static final int RECOVERY_FILE_LIMIT = 5;
    public static final int RECOVERY_TOKENS_PER_FILE = 5_000;
    public static final int RECOVERY_SKILLS_BUDGET = 25_000;
    public static final int RECOVERY_TOKENS_PER_SKILL = 5_000;
    private static final double RECOVERY_CHARS_PER_TOKEN = 3.5;
    private static final DateTimeFormatter RECOVERY_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final String SUMMARY_SYSTEM_PROMPT = """
            Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.
            This summary should be thorough in capturing technical details, code patterns, and architectural decisions that would be essential for continuing development work without losing context.

            Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:

            1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:
               - The user's explicit requests and intents
               - Your approach to addressing the user's requests
               - Key decisions, technical concepts and code patterns
               - Specific details like:
                 - file names
                 - full code snippets
                 - function signatures
                 - file edits
               - Errors that you ran into and how you fixed them
               - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
            2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.

            After your analysis, output your final summary wrapped in <summary> tags. Your summary should include the following sections:

            1. Primary Request and Intent: Capture all of the user's explicit requests and intents in detail
            2. Key Technical Concepts: List all important technical concepts, technologies, and frameworks discussed.
            3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. Pay special attention to the most recent messages and include full code snippets where applicable and include a summary of why this file read or edit is important.
            4. Errors and fixes: List all errors that you ran into, and how you fixed them. Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
            5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.
            6. All user messages: List ALL user messages that are not tool results. These are critical for understanding the users' feedback and changing intent.
            7. Pending Tasks: Outline any pending tasks that you have explicitly been asked to work on.
            8. Current Work: Describe in detail precisely what was being worked on immediately before this summary request, paying special attention to the most recent messages from both user and assistant. Include file names and code snippets where applicable.
            9. Optional Next Step: List the next step that you will take that is related to the most recent work you were doing. IMPORTANT: ensure that this step is DIRECTLY in line with the user's most recent explicit requests. If there is a next step, include direct quotes from the most recent conversation showing exactly what task you were working on and where you left off.

            Output structure:

            <analysis>
            [Your thought process]
            </analysis>

            <summary>
            1. Primary Request and Intent:
               [Detailed description]

            2. Key Technical Concepts:
               - [Concept 1]

            3. Files and Code Sections:
               - [File and code snippet]

            4. Errors and fixes:
               - [Error and fix]

            5. Problem Solving:
               [Description]

            6. All user messages:
               - [User message 1]

            7. Pending Tasks:
               - [Task 1]

            8. Current Work:
               [Precise description]

            9. Optional Next Step:
               [Next step if applicable]
            </summary>""";

    private ContextCompactor() {}

    // ── 熔断器 ────────────────────────────────────────────────────────

    public static class AutoCompactTrackingState {
        private int consecutiveFailures;

        public boolean isTripped() {
            return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
        }

        public void recordFailure() {
            consecutiveFailures++;
        }

        public void reset() {
            consecutiveFailures = 0;
        }
    }

    // ── 公共 API ──────────────────────────────────────────────────────

    /**
     * 返回触发第二层压缩时已用 token 的绝对阈值。
     * effectiveWindow = contextWindow − min(maxOutput, SUMMARY_OUTPUT_RESERVE)；
     * 阈值为有效窗口减去安全边距（硬阻断线使用手动边距，软触发使用自动边距）。
     */
    private static int computeCompactThreshold(int contextWindow, int maxOutput, boolean manual) {
        int reserve = SUMMARY_OUTPUT_RESERVE;
        if (maxOutput > 0 && maxOutput < reserve) {
            reserve = maxOutput;
        }
        int effectiveWindow = contextWindow - reserve;
        int margin = manual ? MANUAL_COMPACT_SAFETY_MARGIN : AUTO_COMPACT_SAFETY_MARGIN;
        return effectiveWindow - margin;
    }

    /**
     * 第一层无条件执行（卸载 + 裁剪）。已用 token 达到自动压缩阈值
     *（effectiveWindow − 自动边距）时触发第二层；一旦越过硬阻断线
     *（effectiveWindow − 手动边距），则强制压缩。参见
     * {@link #computeCompactThreshold}。
     */
    public static String manage(ConversationManager conv, LlmClient client,
                                int contextWindow, int maxOutput, String workDir,
                                AutoCompactTrackingState tracking,
                                RecoveryState recovery,
                                List<Map<String, Object>> toolSchemas) {
        return manage(conv, client, contextWindow, maxOutput, workDir, null, tracking,
                recovery, toolSchemas, null, null);
    }

    public static String manage(ConversationManager conv, LlmClient client,
                                int contextWindow, int maxOutput, String workDir,
                                AutoCompactTrackingState tracking,
                                RecoveryState recovery,
                                List<Map<String, Object>> toolSchemas,
                                UsageAnchor anchor) {
        return manage(conv, client, contextWindow, maxOutput, workDir, null, tracking,
                recovery, toolSchemas, anchor, null);
    }

    /**
     * 管理第一层和第二层压缩。{@code workDir} + {@code sessionId} 用于定位磁盘上的
     * 会话日志；两者均非空时，第二层压缩还会追加一条 {@code compact_boundary}
     * 记录（摘要 + 保留的尾部），以便后续恢复时重建压缩后的状态。对子代理和一次性
     * 调用方而言，这两个参数为 null 或空白，此时不会写入边界记录。
     */
    public static String manage(ConversationManager conv, LlmClient client,
                                int contextWindow, int maxOutput, String workDir, String sessionId,
                                AutoCompactTrackingState tracking,
                                RecoveryState recovery,
                                List<Map<String, Object>> toolSchemas,
                                UsageAnchor anchor) {
        return manage(conv, client, contextWindow, maxOutput, workDir, sessionId, tracking,
                recovery, toolSchemas, anchor, null);
    }

    /**
     * 管理第一层和第二层压缩。{@code budgetMessages} 是经过 ToolResultBudget
     * 裁剪后的消息列表，用于更精确的 token 估算（budget 裁剪后的体积更小，能更
     * 准确判断是否需要触发 compact）。当 {@code budgetMessages} 为 null 时回退到
     * {@code conv.getMessages()}。
     */
    public static String manage(ConversationManager conv, LlmClient client,
                                int contextWindow, int maxOutput, String workDir, String sessionId,
                                AutoCompactTrackingState tracking,
                                RecoveryState recovery,
                                List<Map<String, Object>> toolSchemas,
                                UsageAnchor anchor,
                                List<Message> budgetMessages) {
        // Layer 1（工具结果裁剪）已由 ToolResultBudget.apply() 在 Agent 主循环中单独处理，
        // manage() 只负责 Layer 2（上下文压缩），避免与 ToolResultBudget 重复裁剪。
        // 当 budgetMessages 非空时，使用 budget 裁剪后的消息进行 token 估算
        List<Message> messagesForEstimate = (budgetMessages != null && !budgetMessages.isEmpty())
                ? budgetMessages : conv.getMessages();
        int tokens = currentTokens(messagesForEstimate, anchor);
        // 软触发：已用 token >= effectiveWindow − 自动安全边距
        if (tokens < computeCompactThreshold(contextWindow, maxOutput, false)) {
            return "";
        }

        // 硬触发：已用 token 逼近上下文窗口极限，强制压缩
        if (tokens >= computeCompactThreshold(contextWindow, maxOutput, true)) {
            return forceCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas, budgetMessages);
        }

        if (tracking == null || !tracking.isTripped()) {
            try {
                String l2 = autoCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas, budgetMessages);
                if (tracking != null) tracking.reset();
                return l2;
            } catch (Exception e) {
                if (tracking != null) tracking.recordFailure();
            }
        }
        return "";
    }

    /** 无论当前 token 使用量如何，都强制执行完整的自动压缩（不写入会话边界）。 */
    public static String forceCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas) {
        return autoCompact(conv, client, contextWindow, null, null, recovery, toolSchemas, null);
    }

    /**
     * 强制执行完整的自动压缩；提供 {@code workDir}/{@code sessionId} 时，
     * 向会话日志写入 compact_boundary。
     */
    public static String forceCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      String workDir, String sessionId,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas) {
        return autoCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas, null);
    }

    /**
     * 使用经过预算裁剪的消息估算 token，并强制执行完整的自动压缩。
     */
    public static String forceCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      String workDir, String sessionId,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas,
                                      List<Message> budgetMessages) {
        return autoCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas, budgetMessages);
    }

    /**
     * 用于计算上下文窗口用量的真实 API 使用量锚点。在每次流结束后捕获：
     * {@code baselineTokens} = 供应商报告的 input + cacheRead + cacheCreation + output，
     * {@code anchorCount} = 测量该用量时已有的对话消息数。{@code anchorCount}
     * 之后的所有内容都在基线上增量估算，因此缓存命中时（真实输入远低于原始字符估算）
     * 不会再夸大压缩判断所依据的用量。
     */
    public record UsageAnchor(int baselineTokens, int anchorCount) {}

    /**
     * 用于压缩判断的当前已用 token 估算值。
     *
     * <p>存在真实用量 {@code anchor} 时：使用 {@code baselineTokens}，并只对锚点后
     * 追加的消息（索引 >= anchorCount）按字符估算。没有锚点时（冷启动，尚无流报告用量），
     * 回退为估算所有消息，与旧版行为保持一致，从而确保首轮仍能正常工作。
     */
    public static int currentTokens(List<Message> messages, UsageAnchor anchor) {
        if (anchor == null || anchor.anchorCount() < 0
                || anchor.anchorCount() > messages.size()) {
            return estimateTokens(messages);
        }
        List<Message> appended = messages.subList(anchor.anchorCount(), messages.size());
        return anchor.baselineTokens() + estimateTokens(appended);
    }

    /** 使用简单的启发式规则估算一组消息的 token 数量。 */
    public static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += (int) (safeLength(m.getContent()) / 3.5) + 4;

            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    String argsJson;
                    try {
                        argsJson = MAPPER.writeValueAsString(tu.arguments());
                    } catch (JsonProcessingException e) {
                        argsJson = "{}";
                    }
                    total += 50 + (int) (argsJson.length() / 3.5);
                }
            }

            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    total += (int) (safeLength(tr.content()) / 3.5) + 10;
                }
            }

            if (m.getThinkingBlocks() != null) {
                for (ThinkingBlock tb : m.getThinkingBlocks()) {
                    total += (int) (safeLength(tb.thinking()) / 3.5);
                }
            }
        }
        return total;
    }

    // ── 第一层：卸载与裁剪 ────────────────────────────────────────────

    static String offloadAndSnip(ConversationManager conv, String workDir) {
        List<Message> messages = conv.getMessagesMutable();
        if (messages.isEmpty()) return "";

        String spillDir = workDir != null
                ? Path.of(workDir, SPILL_SUBDIR).toString()
                : null;
        int spillCount = 0;
        int savedChars = 0;
        boolean changed = false;

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg.getToolResults() == null) continue;

            List<ToolResultBlock> results = new ArrayList<>(msg.getToolResults());
            boolean msgChanged = false;

            // 按结果落盘：单个结果超过 SINGLE_RESULT_LIMIT
            for (int j = 0; j < results.size(); j++) {
                ToolResultBlock tr = results.get(j);
                if (alreadyProcessed(tr.content()) || safeLength(tr.content()) <= SINGLE_RESULT_LIMIT) {
                    continue;
                }
                if (spillDir == null) continue;
                Path path = writeSpill(spillDir, tr.toolUseId(), tr.content());
                if (path == null) continue;

                savedChars += tr.content().length();
                results.set(j, new ToolResultBlock(
                        tr.toolUseId(),
                        String.format("[Result of %d chars saved to %s]",
                                tr.content().length(), path),
                        tr.isError()));
                spillCount++;
                msgChanged = true;
            }

            // 按消息汇总落盘
            int agg = 0;
            for (ToolResultBlock tr : results) {
                agg += safeLength(tr.content());
            }
            if (agg > MESSAGE_AGGREGATE_LIMIT && spillDir != null) {
                for (int j = 0; j < results.size(); j++) {
                    ToolResultBlock tr = results.get(j);
                    if (alreadyProcessed(tr.content()) || safeLength(tr.content()) <= 200) {
                        continue;
                    }
                    Path path = writeSpill(spillDir, tr.toolUseId(), tr.content());
                    if (path == null) continue;

                    savedChars += tr.content().length();
                    results.set(j, new ToolResultBlock(
                            tr.toolUseId(),
                            String.format("[Result of %d chars saved to %s]",
                                    tr.content().length(), path),
                            tr.isError()));
                    spillCount++;
                    msgChanged = true;
                }
            }

            if (msgChanged) {
                msg.setToolResults(results);
                changed = true;
            }
        }

        if (!changed) return "";
        rebuildConversation(conv, messages);

        if (spillCount == 0) return "";
        return String.format("spilled %d tool result(s) to disk (~%d chars freed)", spillCount, savedChars);
    }

    // ── 第二层：自动压缩 ──────────────────────────────────────────────

    /**
     * 选择原样“保留”尾部的起始索引，与 Claude Code 的 messagesToKeep 选择逻辑一致。
     *
     * <p>从末尾反向遍历，累加每条消息的估算 token。只要达到任一下限
     *（token 达到 KEEP_RECENT_TOKENS，或消息数达到 MIN_KEEP_MESSAGES），
     * 就停止遍历并保留当前索引之后的全部内容，以先达到者为准。累加量同样设有上限：
     * 如果加入某条消息会使保留尾部超过 KEEP_MAX_TOKENS，则在加入前停止。
     *
     * <p>配对保护：携带 tool_result 块的 {@code user} 消息不能在缺少其来源
     * {@code assistant} tool_use 消息的情况下单独保留。如果选定边界落在此类消息上，
     * 则继续向前移动（纳入发出 tool_use 的 assistant 轮次），避免保留
     * tool_use↔tool_result 配对中孤立的一半。
     *
     * @return 保留窗口的起始索引；如果所有内容都能放入保留窗口（没有内容需要摘要），则返回 0。
     */
    static int computeKeepStartIndex(List<Message> messages) {
        int n = messages.size();
        if (n == 0) return 0;

        int accumulated = 0;
        int kept = 0;
        int keepStart = n; // 反向遍历：keepStart 是第一个保留元素的索引
        for (int i = n - 1; i >= 0; i--) {
            int msgTokens = estimateTokens(List.of(messages.get(i)));
            // 上限：如果这条消息会使保留尾部超过上限，则立即停止，
            // 将其留在需要摘要的前缀中。
            if (accumulated + msgTokens > KEEP_MAX_TOKENS && kept > 0) {
                break;
            }
            accumulated += msgTokens;
            kept++;
            keepStart = i;
            // 下限：任一阈值达到即视为满足。
            if (accumulated >= KEEP_RECENT_TOKENS || kept >= MIN_KEEP_MESSAGES) {
                break;
            }
        }

        // 配对保护：保留窗口不能从仅携带 tool_result 块的 user 消息开始，
        // 否则它会与对应的 assistant tool_use 脱节。向前移动边界以纳入 assistant
        // 轮次（并越过其他悬空的 tool_result 消息）。
        while (keepStart > 0 && isToolResultMessage(messages.get(keepStart))) {
            keepStart--;
        }
        return keepStart;
    }

    private static boolean isToolResultMessage(Message m) {
        return "user".equals(m.getRole())
                && m.getToolResults() != null
                && !m.getToolResults().isEmpty();
    }

    private static String autoCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      String workDir, String sessionId,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas,
                                      List<Message> budgetMessages) {
        // 当 budgetMessages 非空时，使用 budget 裁剪后的消息进行 token 估算和摘要构建，
        // 但最终仍然重写 conv（原始对话）
        List<Message> messages = (budgetMessages != null && !budgetMessages.isEmpty())
                ? budgetMessages : conv.getMessages();
        int beforeTokens = estimateTokens(messages);

        // 原样保留最近的尾部，只摘要较早的前缀。
        int keepStartIndex = computeKeepStartIndex(messages);

        // 退化情况：没有内容需要摘要（全部内容都在保留窗口中），
        // 或前缀太短，不值得额外请求一次摘要。
        // 回退到原有行为，保持对话不变。
        if (keepStartIndex <= 0 || keepStartIndex < MIN_KEEP_MESSAGES) {
            return "";
        }

        List<Message> toSummarize = messages.subList(0, keepStartIndex);
        List<Message> toKeep = messages.subList(keepStartIndex, messages.size());

        String serialized = serializeForSummary(toSummarize, 500);
        String summaryRaw = requestSummary(client,
                SUMMARY_SYSTEM_PROMPT + "\n\n" + serialized);
        String summaryText = formatCompactSummary(summaryRaw);

        // 持久化 compact_boundary 记录，以便后续恢复时重建压缩后的状态
        //（摘要 + 保留的尾部），而不是重放压缩前的完整对话。日志只追加：原始前缀消息
        // 仍留在文件中，但不会越过此边界重放。保留的尾部以内联 role+content 文本保存
        //（与会话日志现有的消息存储方式一致——仅文本，不含工具块）。边界只存储纯摘要文本，
        // 不包含恢复附件，因为恢复快照只是内存中的重建辅助信息，恢复会话时无法获取。
        // sessionId/workDir 为 null 或空白时跳过（测试、一次性调用方）。
        if (workDir != null && !workDir.isBlank() && sessionId != null && !sessionId.isBlank()) {
            List<SessionManager.KeepMessage> keepRecords = new ArrayList<>(toKeep.size());
            for (Message m : toKeep) {
                keepRecords.add(new SessionManager.KeepMessage(m.getRole(), nullSafe(m.getContent())));
            }
            SessionManager.saveCompactBoundary(workDir, sessionId, summaryText, keepRecords);
        }

        String content = "本次会话延续自之前的对话，因上下文空间不足进行了压缩。以下是早期对话的摘要：\n\n" + summaryText;
        if (!toKeep.isEmpty()) {
            content += "\n\n近期消息已原样保留。";
        }
        if (workDir != null && !workDir.isBlank() && sessionId != null && !sessionId.isBlank()) {
            content += "\n\n如果你需要压缩前的具体细节（代码片段、报错信息等），请用 ReadFile 读取完整会话记录："
                    + Path.of(workDir, ".mewcode", "sessions", sessionId + ".jsonl");
        }
        String attachment = buildRecoveryAttachment(recovery, toolSchemas);
        if (!attachment.isEmpty()) {
            content += "\n\n---\n\n" + attachment;
        }

        // 重建结果 = 摘要（user）+ 最近的原样尾部（不添加 assistant 确认消息）。
        ConversationManager compacted = new ConversationManager();
        compacted.addUserMessage(content);
        for (Message m : toKeep) {
            appendMessage(compacted, m);
        }

        replaceConversation(conv, compacted);

        int afterTokens = estimateTokens(conv.getMessages());
        return String.format("Compacted: %d -> %d estimated tokens", beforeTokens, afterTokens);
    }

    // ── 压缩后的恢复附件 ──────────────────────────────────────────────

    /**
     * 渲染追加到摘要 user 消息后的四段恢复块。没有值得输出的内容时返回 ""，
     * 以便调用方保持摘要整洁。
     *
     * @param state        每个代理最近读取文件和技能的快照
     * @param toolSchemas  代理将在下一次请求中发送的工具 schema
     */
    public static String buildRecoveryAttachment(RecoveryState state,
                                                 List<Map<String, Object>> toolSchemas) {
        var sb = new StringBuilder();

        if (state != null) {
            var files = state.snapshotFiles(RECOVERY_FILE_LIMIT);
            if (!files.isEmpty()) {
                sb.append("## Recently read files\n\n")
                  .append("These snapshots are what the file-reading tool last returned. ")
                  .append("Re-open with the tool if you need the current bytes.\n\n");
                for (var f : files) {
                    String body = truncateByTokens(f.content(), RECOVERY_TOKENS_PER_FILE);
                    sb.append("### ").append(f.path())
                      .append("  (read ").append(RECOVERY_TS.format(f.timestamp())).append(")\n\n")
                      .append("```\n").append(body);
                    if (!body.endsWith("\n")) sb.append('\n');
                    sb.append("```\n\n");
                }
            }
        }

        if (state != null) {
            var skills = state.snapshotSkills();
            if (!skills.isEmpty()) {
                var section = new StringBuilder();
                section.append("## Active skills\n\n")
                       .append("These skills were invoked earlier in the session. ")
                       .append("Continue to follow each SOP when its triggering condition applies.\n\n");
                int used = 0;
                boolean emitted = false;
                for (var sk : skills) {
                    String body = truncateByTokens(sk.body(), RECOVERY_TOKENS_PER_SKILL);
                    int tokens = approxTokens(body) + approxTokens(sk.name()) + 8;
                    if (used + tokens > RECOVERY_SKILLS_BUDGET) break;
                    used += tokens;
                    section.append("### ").append(sk.name()).append("\n\n")
                           .append(body).append("\n\n");
                    emitted = true;
                }
                if (emitted) sb.append(section);
            }
        }

        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            sb.append("## Available tools\n\n")
              .append("You still have access to the following tools — call them directly when the task needs one:\n\n");
            for (var t : toolSchemas) {
                if (t == null) continue;
                Object nameObj = t.get("name");
                if (nameObj == null) continue;
                String name = nameObj.toString();
                if (name.isEmpty()) continue;
                Object descObj = t.get("description");
                String desc = descObj == null ? "" : firstLine(descObj.toString());
                if (!desc.isEmpty()) {
                    sb.append("- ").append(name).append(" — ").append(desc).append('\n');
                } else {
                    sb.append("- ").append(name).append('\n');
                }
            }
            sb.append('\n');
        }

        if (sb.length() == 0) return "";

        sb.append("## Note\n\nEverything above the divider is reconstructed context. ")
          .append("For exact code, error strings, or user-typed text, re-read the source rather than ")
          .append("guess from the summary.\n");
        return sb.toString();
    }

    private static int approxTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        return (int) (s.length() / RECOVERY_CHARS_PER_TOKEN);
    }

    private static String truncateByTokens(String s, int tokenBudget) {
        if (s == null || s.isEmpty() || tokenBudget <= 0) return s == null ? "" : s;
        if (approxTokens(s) <= tokenBudget) return s;
        int maxChars = (int) (tokenBudget * RECOVERY_CHARS_PER_TOKEN);
        if (maxChars <= 0 || maxChars >= s.length()) return s;
        return s.substring(0, maxChars) + "\n… (content truncated)";
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    private static boolean alreadyProcessed(String s) {
        return s != null && s.startsWith("[Result of ");
    }

    private static Path writeSpill(String spillDir, String toolUseId, String content) {
        try {
            Path dir = Path.of(spillDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(toolUseId);
            Files.writeString(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return file;
        } catch (FileAlreadyExistsException e) {
            return Path.of(spillDir).resolve(toolUseId);
        } catch (IOException e) {
            return null;
        }
    }

    static String formatCompactSummary(String raw) {
        int start = raw.indexOf("<summary>");
        int end = raw.indexOf("</summary>");
        if (start >= 0 && end > start) {
            return raw.substring(start + "<summary>".length(), end).strip();
        }
        return raw.strip();
    }

    private static String requestSummary(LlmClient client, String prompt) {
        ConversationManager summaryConv = new ConversationManager();
        summaryConv.addUserMessage(prompt);

        BlockingQueue<StreamEvent> events = client.stream(summaryConv, null);
        var summary = new StringBuilder();

        try {
            while (true) {
                StreamEvent ev = events.take();
                if (ev instanceof StreamEvent.TextDelta td) {
                    summary.append(td.text());
                } else if (ev instanceof StreamEvent.Error err) {
                    throw new RuntimeException("LLM summary failed: " + err.message());
                } else if (ev instanceof StreamEvent.StreamEnd) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Summary interrupted", e);
        }

        return summary.toString();
    }

    private static String serializeForSummary(List<Message> messages, int toolResultCap) {
        var sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(String.format("[%s]: %s\n", m.getRole(), nullSafe(m.getContent())));

            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    sb.append(String.format("[tool_use %s]: %s\n", tu.toolName(), tu.toolUseId()));
                }
            }
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    String content = nullSafe(tr.content());
                    if (content.length() > toolResultCap) {
                        content = content.substring(0, toolResultCap) + "...";
                    }
                    sb.append(String.format("[tool_result]: %s\n", content));
                }
            }
        }
        return sb.toString();
    }

    private static void appendMessage(ConversationManager conv, Message m) {
        if (m.getToolUses() != null && !m.getToolUses().isEmpty()) {
            conv.addAssistantFull(m.getContent(), m.getThinkingBlocks(), m.getToolUses());
        } else if (m.getToolResults() != null && !m.getToolResults().isEmpty()) {
            conv.addToolResultsMessage(m.getToolResults());
        } else if ("user".equals(m.getRole())) {
            conv.addUserMessage(m.getContent());
        } else if ("assistant".equals(m.getRole())) {
            conv.addAssistantFull(m.getContent(), m.getThinkingBlocks(), null);
        }
    }

    private static void rebuildConversation(ConversationManager conv, List<Message> messages) {
        ConversationManager rebuilt = new ConversationManager();
        for (Message m : messages) {
            appendMessage(rebuilt, m);
        }
        replaceConversation(conv, rebuilt);
    }

    private static void replaceConversation(ConversationManager target, ConversationManager source) {
        List<Message> targetList = target.getMessagesMutable();
        targetList.clear();
        targetList.addAll(source.getMessages());
    }

    private static int safeLength(String s) {
        return s == null ? 0 : s.length();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
