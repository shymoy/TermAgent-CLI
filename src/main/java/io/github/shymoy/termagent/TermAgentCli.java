

package io.github.shymoy.termagent;

import io.github.shymoy.termagent.config.AppConfig;
import io.github.shymoy.termagent.config.ConfigLoader;
import io.github.shymoy.termagent.print.PrintMode;
import io.github.shymoy.termagent.remote.RemoteServer;
import io.github.shymoy.termagent.tui.TermAgentModel;
import io.github.shymoy.termagent.tui.tea.Program;
import sun.misc.Signal;

import java.util.List;

public class TermAgentCli {

    // 默认 Remote 模式监听端口
    private static final String DEFAULT_REMOTE_ADDR = ":18888";

    public static void main(String[] args) {
        // 解析 CLI 参数：-p "prompt"、--output-format、--remote[=addr] 和配置文件路径
        String configPath = null;
        boolean remoteMode = false;
        String remoteAddr = DEFAULT_REMOTE_ADDR;
        String printPrompt = null;
        String outputFormat = "text";
        boolean showVersion = false;
        boolean showParameter = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-p") && i + 1 < args.length) {
                printPrompt = args[++i];
            } else if (arg.startsWith("-p=")) {
                printPrompt = arg.substring("-p=".length());
            } else if (arg.equals("--output-format") && i + 1 < args.length) {
                outputFormat = args[++i];
            } else if (arg.startsWith("--output-format=")) {
                outputFormat = arg.substring("--output-format=".length());
            } else if (arg.equals("--remote")) {
                remoteMode = true;
            } else if (arg.startsWith("--remote=")) {
                remoteMode = true;
                remoteAddr = arg.substring("--remote=".length());
            } else if (arg.equals("--version")) {
                showVersion = true;
            } else if (arg.equals("--help")) {
                showParameter = true;
            } else if (configPath == null) {
                configPath = arg;
            }
        }

        if (showParameter) {
            System.out.println("-p");
            System.out.println("-p=");
            System.out.println("--remote");
            System.out.println("--version");
            return;
        }

        if (showVersion) {
            System.out.println(TermAgentModel.VERSION);
            return;
        }

        // 环境变量回退
        if (configPath == null) {
            String envPath = System.getenv("TERMAGENT_CONFIG");
            if (envPath == null || envPath.isBlank()) {
                envPath = System.getenv("MEWCODE_CONFIG");
            }
            if (envPath != null && !envPath.isBlank()) {
                configPath = envPath;
            }
        }

        AppConfig config;
        try {
            config = ConfigLoader.load(configPath);
        } catch (ConfigLoader.ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }

        // -p 模式：非交互式运行，输出结果到 stdout
        if (printPrompt != null) {
            PrintMode.OutputFormat fmt = "stream-json".equals(outputFormat)
                    ? PrintMode.OutputFormat.STREAM_JSON
                    : PrintMode.OutputFormat.TEXT;
            PrintMode.run(config, printPrompt, fmt);
            return;
        }

        // --remote 模式：启动 HTTP + WebSocket 服务器，不进入 TUI
        if (remoteMode) {
            var server = new RemoteServer(
                    config.getProviders(),
                    config.getMcpServers() != null ? config.getMcpServers() : List.of(),
                    config.getHooks() != null ? config.getHooks() : List.of(),
                    remoteAddr
            );
            try {
                server.run();
            } catch (Exception e) {
                System.err.println("Remote server error: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        // TUI 模式（默认）
        var model = new TermAgentModel(
                config.getProviders(),
                config.getMcpServers() != null ? config.getMcpServers() : List.of(),
                config.getHooks() != null ? config.getHooks() : List.of()
        );

        var program = new Program(model);

        model.setProgram(program);

        System.out.print("\033[?25l");

        // TUI4J 启动 program.run() 后会安装默认的 SIGINT 处理器，
        // 因此需要重新注册，避免 Ctrl-C 直接终止 TUI。
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            try {
                Signal.handle(new Signal("INT"), sig -> model.handleSigint());
            } catch (IllegalArgumentException ignored) {}
        });

        try {
            program.run();
        } finally {
            System.out.print("\033[?25h");
            System.out.flush();
        }
    }
}
