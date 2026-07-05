
package io.github.shymoy.termagent.teams;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**

 * Tmux 后端，用于在单独的 tmux 窗口中生成队友。

 */
public final class TmuxBackend {

    private static final Logger log = Logger.getLogger(TmuxBackend.class.getName());

    private TmuxBackend() {}

    public static String spawnTmuxTeammate(String teamName, String memberName, String cliCommand) throws Exception {
        String paneName = teamName + "-" + memberName;
        var pb = new ProcessBuilder("tmux", "new-window", "-d", "-n", paneName, cliCommand);
        pb.redirectErrorStream(true);
        var proc = pb.start();
        proc.getInputStream().readAllBytes();
        boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
        if (!finished || proc.exitValue() != 0) {
            throw new RuntimeException("Failed to spawn tmux window: " + paneName);
        }
        return paneName;
    }

    public static void stopTmuxTeammate(String paneName) {
        try {
            // 首先发送 Ctrl-C
            new ProcessBuilder("tmux", "send-keys", "-t", paneName, "C-c")
                    .start().waitFor(5, TimeUnit.SECONDS);
            Thread.sleep(200);
            // 然后杀死窗口
            new ProcessBuilder("tmux", "kill-window", "-t", paneName)
                    .start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.fine("Failed to stop tmux teammate: " + e.getMessage());
        }
    }
}
