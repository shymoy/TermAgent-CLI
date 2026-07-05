
package io.github.shymoy.termagent.teams;

import io.github.shymoy.termagent.agent.AgentEvent;
import io.github.shymoy.termagent.tui.SpinnerVerbs;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**

 * 进程中队友的主循环。

 */
public final class TeammateRunner {

    public static final String LEAD_NAME = "lead";
    public static final String SHUTDOWN_PREFIX = "[shutdown]";

    public static final long IDLE_POLL_MS = 500;

    private TeammateRunner() {}

    /**

     * 在当前线程中运行队友代理循环。阻塞直至关闭

     * 或上下文取消（线程中断）。

     */
    public static void runInProcessTeammate(
            TeamManager.Team team,
            TeamManager.Member member,
            String initialPrompt,
            String addendum
    ) {
        BlockingQueue<AgentEvent> eventOut = new LinkedBlockingQueue<>(32);

        // 创建进度跟踪器并附加到会员
        var progress = new TeammateProgress(member.getName(), team.getName(), SpinnerVerbs.random());
        member.progress = progress;

        if (addendum != null && !addendum.isEmpty()) {
            member.conv.addSystemReminder(addendum);
        }

        // 注入任何待处理的邮箱消息
        injectPendingMessages(team, member.getName(), member.conv);

        // 第一回合：使用初始提示
        member.conv.addUserMessage(initialPrompt);

        // Run agent
        var agentQueue = member.agent.run(member.conv);
        drainAgentEvents(agentQueue, eventOut, progress);

        // 发送空闲通知
        team.sendMessage(member.getName(), LEAD_NAME,
                createIdleNotification(member.getName(), "completed initial task"));

        // 后续轮次：等待邮箱消息
        while (!Thread.currentThread().isInterrupted()) {
            var result = waitForNextPromptOrShutdown(team, member.getName());
            if (result.shutdown || result.prompt == null) break;

            member.conv.addUserMessage(result.prompt);
            agentQueue = member.agent.run(member.conv);
            drainAgentEvents(agentQueue, eventOut, progress);

            team.sendMessage(member.getName(), LEAD_NAME,
                    createIdleNotification(member.getName(), "completed follow-up"));
        }

        member.active = false;
        progress.setStatus("completed");

        // 队友退出时持久化对话记录，用于调试
        try {
            Transcript.saveTranscript(team.getName(), member.getName(), member.conv);
        } catch (Exception ignored) {
            // best-effort：持久化失败不影响正常退出
        }
    }

    /**

     * 清除所有团队中领导的邮箱，返回格式化的通知字符串。

     * 每次迭代由领导的NotificationFn 调用。

     */
    public static List<String> drainLeadMailbox(TeamManager teamMgr) {
        if (teamMgr == null) return List.of();
        var result = new java.util.ArrayList<String>();
        for (String teamName : teamMgr.listTeams()) {
            var team = teamMgr.getTeam(teamName);
            if (team == null) continue;
            var messages = team.getMailBox().readUnread(LEAD_NAME);
            if (messages.isEmpty()) continue;

            var sb = new StringBuilder();
            sb.append("<team-notification team=\"").append(teamName).append("\">\n");
            for (var msg : messages) {
                sb.append("from=").append(msg.from()).append(": ").append(msg.text()).append("\n");
            }
            sb.append("</team-notification>");
            result.add(sb.toString());

            team.getMailBox().markAllRead(LEAD_NAME);
        }
        return result;
    }

    /**

     * 为队友构建系统提醒附录。

     */
    public static String buildTeammateAddendum(String teamName, String memberName, List<String> otherMembers) {
        var sb = new StringBuilder();
        sb.append("You are a member of team \"").append(teamName).append("\". ");
        sb.append("Your name is \"").append(memberName).append("\".\n\n");
        if (otherMembers != null && !otherMembers.isEmpty()) {
            sb.append("Other team members: ").append(String.join(", ", otherMembers)).append("\n\n");
        }
        sb.append("You can communicate with teammates using the SendMessage tool.\n");
        sb.append("Messages from teammates arrive as system reminders at the start of each turn.\n");
        sb.append("When you finish your current task, simply stop calling tools — ");
        sb.append("an idle notification will be sent to the lead automatically.");
        return sb.toString();
    }

    /**

     * 插入未读邮箱消息作为系统提醒。

     */
    public static void injectPendingMessages(
            TeamManager.Team team, String memberName,
            io.github.shymoy.termagent.conversation.ConversationManager conv
    ) {
        var messages = team.getMailBox().readUnread(memberName);
        if (messages.isEmpty()) return;

        var sb = new StringBuilder("You have new messages:\n\n");
        for (var msg : messages) {
            sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
        }
        conv.addSystemReminder(sb.toString());
        team.getMailBox().markAllRead(memberName);
    }

    public static boolean isShutdownRequest(String message) {
        return message != null && message.strip().startsWith(SHUTDOWN_PREFIX);
    }

    public static String createIdleNotification(String memberName, String reason) {
        return "[idle] %s: %s (at %s)".formatted(memberName, reason,
                java.time.Instant.now().toString());
    }

    // ── 内部帮手──────────────────────────────────────────────

    private record WaitResult(String prompt, boolean shutdown) {}

    private static WaitResult waitForNextPromptOrShutdown(TeamManager.Team team, String memberName) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new WaitResult(null, true);
            }

            var messages = team.getMailBox().readUnread(memberName);
            if (messages.isEmpty()) continue;

            for (var msg : messages) {
                if (isShutdownRequest(msg.text())) {
                    team.getMailBox().markAllRead(memberName);
                    return new WaitResult(null, true);
                }
            }

            // 设置为提示格式
            var sb = new StringBuilder("You have new messages from your team:\n\n");
            for (var msg : messages) {
                sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
            }
            team.getMailBox().markAllRead(memberName);
            return new WaitResult(sb.toString(), false);
        }
        return new WaitResult(null, true);
    }

    private static void drainAgentEvents(BlockingQueue<AgentEvent> source, BlockingQueue<AgentEvent> sink,
                                         TeammateProgress progress) {
        while (true) {
            AgentEvent event;
            try {
                event = source.poll(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                progress.setStatus("failed");
                return;
            }
            if (event == null) return;
            sink.offer(event);

            // 记录代理事件的进度
            if (event instanceof AgentEvent.ToolUseEvent tue) {
                progress.recordToolUse(tue.toolName(), tue.args());
            } else if (event instanceof AgentEvent.UsageEvent ue) {
                progress.recordTokens(ue.inputTokens(), ue.outputTokens());
            } else if (event instanceof AgentEvent.ErrorEvent) {
                progress.setStatus("failed");
                return;
            } else if (event instanceof AgentEvent.LoopComplete) {
                return;
            }
        }
    }
}
