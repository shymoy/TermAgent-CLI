
package io.github.shymoy.termagent.teams;

import io.github.shymoy.termagent.llm.LlmClient;
import io.github.shymoy.termagent.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;

/**

 * 统一调度程序，用于在所有后端生成队友。

 */
public final class SpawnDispatcher {

    public record SpawnConfig(
            TeamManager.Team team,
            String memberName,
            String task,
            String addendum,
            LlmClient client,
            ToolRegistry registry,
            String protocol,
            io.github.shymoy.termagent.config.ProviderConfig providerConfig,
            String workdir
    ) {}

    public record SpawnResult(
            TeamManager.TeamMode mode,
            String paneId
    ) {}

    private SpawnDispatcher() {}

    public static SpawnResult spawnTeammate(SpawnConfig config) throws Exception {
        var team = config.team();
        var mode = team.getMode();

        switch (mode) {
            case IN_PROCESS -> {
                var member = team.addMember(config.memberName(), config.client(),
                        config.registry(), config.protocol(), config.providerConfig());
                if (config.workdir() != null) {
                    member.agent.setWorkDir(config.workdir());
                }
                member.active = true;
                member.thread = Thread.startVirtualThread(() ->
                        TeammateRunner.runInProcessTeammate(team, member, config.task(), config.addendum()));
                return new SpawnResult(mode, null);
            }
            case TMUX -> {
                // 任务写入邮箱，新进程首次轮询即可获取
                if (config.task() != null && !config.task().isEmpty()) {
                    team.sendMessage(TeammateRunner.LEAD_NAME, config.memberName(), config.task());
                }
                String cliCommand = buildTeammateCLI(team.getName(), config.memberName(), config.workdir());
                String paneId = TmuxBackend.spawnTmuxTeammate(team.getName(), config.memberName(), cliCommand);
                recordExternalMember(team, config.memberName(), paneId);
                return new SpawnResult(mode, paneId);
            }
            case ITERM -> {
                // iTerm2 后端：在新标签页中启动队友进程
                if (config.task() != null && !config.task().isEmpty()) {
                    team.sendMessage(TeammateRunner.LEAD_NAME, config.memberName(), config.task());
                }
                String itermCmd = buildTeammateCLI(team.getName(), config.memberName(), config.workdir());
                String tabId = ITermBackend.spawnITermTeammate(team.getName(), config.memberName(), itermCmd);
                recordExternalMember(team, config.memberName(), tabId);
                return new SpawnResult(mode, tabId);
            }
            default -> throw new IllegalStateException("Unsupported team mode: " + mode);
        }
    }

    /**

     * 为工作进程构建 shell 命令。

     * 格式：cd '<workdir>' && '<termagent-cli>' --teammate --团队名称 <t> --代理名称 <n>

     */
    public static String buildTeammateCLI(String teamName, String memberName, String workdir) {
        String wd = workdir != null ? workdir : System.getProperty("user.dir");
        // 找到 TermAgent-CLI 可执行文件（假设它是当前的JAR或PATH上）
        String termAgent = ProcessHandle.current().info().command().orElse("termagent-cli");
        return "cd %s && %s --teammate --team-name %s --agent-name %s".formatted(
                shellQuote(wd), shellQuote(termAgent), shellQuote(teamName), shellQuote(memberName));
    }

    static String shellQuote(String s) {
        if (s.matches("[a-zA-Z0-9_./-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void recordExternalMember(TeamManager.Team team, String name, String paneId) {
        // 对于外部后端，创建占位符成员
        var member = new TeamManager.Member(name, null, null);
        member.active = true;
        // 通过字段访问存储 paneId（简单方法）
        synchronized (team) {
            team.members.put(name, member);
        }
    }
}
