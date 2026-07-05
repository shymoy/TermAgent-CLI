
package io.github.shymoy.termagent.mcp;

import io.github.shymoy.termagent.config.McpServerConfig;
import io.github.shymoy.termagent.tool.Tool;
import io.github.shymoy.termagent.tool.ToolCategory;
import io.github.shymoy.termagent.tool.ToolRegistry;
import io.github.shymoy.termagent.tool.ToolResult;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 管理 MCP 服务的连接生命周期，并将服务端工具适配为应用内部的 {@link Tool}。
 */
public class McpManager {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-zA-Z0-9_]");

    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([^}]+)}");

    /** MCP 服务初始化后需要暴露给上层的元数据。 */
    public record ServerInfo(String name, String instructions) {}

    /** 批量连接结果；允许调用方在部分服务失败时继续使用其余服务。 */
    public record ConnectResult(List<Tool> tools, List<ServerInfo> servers, List<String> errors) {}

    private final Map<String, McpServerConfig> configs = new LinkedHashMap<>();
    private final Map<String, McpSyncClient> clients = new LinkedHashMap<>();

    public McpManager(List<McpServerConfig> configs) {
        if (configs != null) {
            for (var cfg : configs) this.configs.put(cfg.getName(), cfg);
        }
    }

    /**
     * 依次初始化所有已配置的 MCP 服务并收集其工具。
     * 成功创建的客户端由本管理器持有，调用方应在应用退出时调用 {@link #shutdown()}。
     */
    public ConnectResult connectAll() {
        var tools = new ArrayList<Tool>();
        var servers = new ArrayList<ServerInfo>();
        var errors = new ArrayList<String>();

        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            var cfg = entry.getValue();

            try {
                var client = createClient(cfg);
                client.initialize();
                clients.put(name, client);

                String instructions = client.getServerInstructions();
                servers.add(new ServerInfo(name, instructions != null ? instructions : ""));

                var result = client.listTools();
                if (result != null && result.tools() != null) {
                    for (var sdkTool : result.tools()) {
                        tools.add(new McpToolWrapper(name, sdkTool, client));
                    }
                }
            } catch (Exception e) {
                // 单个服务连接失败不应阻止其他 MCP 服务继续初始化。
                errors.add("MCP server '" + name + "': " + e.getMessage());
            }
        }

        return new ConnectResult(List.copyOf(tools), List.copyOf(servers), List.copyOf(errors));
    }

    /** 连接全部服务并将成功发现的工具注册到应用工具表中。 */
    public List<String> registerAllTools(ToolRegistry registry) {
        var result = connectAll();
        for (var t : result.tools()) registry.register(t);
        return result.errors();
    }

    /** 尽力关闭所有已初始化客户端；单个客户端关闭失败不会影响其余客户端。 */
    public void shutdown() {
        for (var client : clients.values()) {
            try { client.closeGracefully(); } catch (Exception ignored) {}
        }
        clients.clear();
    }

    private McpSyncClient createClient(McpServerConfig cfg) {
        io.modelcontextprotocol.spec.McpClientTransport transport;

        // command 配置优先使用本地 stdio；否则回退到远程 Streamable HTTP。
        if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
            var paramsBuilder = ServerParameters.builder(windowsSafe(cfg.getCommand()));
            if (cfg.getArgs() != null) {
                paramsBuilder.args(cfg.getArgs());
            }
            if (cfg.getEnv() != null) {
                var resolvedEnv = new HashMap<String, String>();
                for (var e : cfg.getEnv().entrySet()) {
                    resolvedEnv.put(e.getKey(), resolveEnvVars(e.getValue()));
                }
                paramsBuilder.env(resolvedEnv);
            }
            transport = new StdioClientTransport(paramsBuilder.build(), McpJsonDefaults.getMapper());
        } else if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
            var httpBuilder = HttpClientStreamableHttpTransport.builder(cfg.getUrl());
            if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
                httpBuilder.customizeRequest(rb -> {
                    for (var e : cfg.getHeaders().entrySet()) {
                        rb.header(e.getKey(), resolveEnvVars(e.getValue()));
                    }
                });
            }
            transport = httpBuilder.build();
        } else {
            throw new IllegalArgumentException("Neither command nor url configured");
        }

        return McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("termagent-cli", "0.1.0"))
                .requestTimeout(Duration.ofSeconds(60))
                .build();
    }

    private static final Set<String> WIN_CMD_SUFFIXED = Set.of(
            "npx", "npm", "node", "uvx", "uv", "pnpm", "yarn", "bunx");

    static String windowsSafe(String command) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return command;
        String base = command.toLowerCase();
        // npm 等工具在 Windows 上实际由 .cmd 启动，直接执行无后缀命令可能失败。
        if (WIN_CMD_SUFFIXED.contains(base)) return command + ".cmd";
        return command;
    }

    static String sanitizeName(String name) {
        return NON_ALNUM.matcher(name).replaceAll("_");
    }

    static String resolveEnvVars(String value) {
        if (value == null) return null;
        return ENV_VAR.matcher(value).replaceAll(m -> {
            String env = System.getenv(m.group(1));
            // 未定义的变量保留原占位符，避免静默替换为空字符串造成配置含义变化。
            return env != null ? env : m.group(0);
        });
    }

    // ── MCP 工具包装器────────────────────────────────────────────────

    private static class McpToolWrapper implements Tool {
        private final String serverName;
        private final McpSchema.Tool sdkTool;
        private final McpSyncClient client;

        McpToolWrapper(String serverName, McpSchema.Tool sdkTool, McpSyncClient client) {
            this.serverName = serverName;
            this.sdkTool = sdkTool;
            this.client = client;
        }

        @Override public String name() {
            return "mcp__" + sanitizeName(serverName) + "__" + sanitizeName(sdkTool.name());
        }

        @Override public String description() {
            return sdkTool.description() != null ? sdkTool.description() : "";
        }

        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        // MCP 服务可能暴露大量工具，默认通过工具搜索按需加入模型上下文。
        @Override public boolean shouldDefer() { return true; }

        @Override public Map<String, Object> schema() {
            var input = new LinkedHashMap<String, Object>();
            var jsonSchema = sdkTool.inputSchema();
            // 仅透传工具系统能够识别的 JSON Schema 核心字段。
            if (jsonSchema != null) {
                if (jsonSchema.type() != null) input.put("type", jsonSchema.type());
                if (jsonSchema.properties() != null) input.put("properties", jsonSchema.properties());
                if (jsonSchema.required() != null) input.put("required", jsonSchema.required());
            }
            if (input.isEmpty()) {
                input.put("type", "object");
                input.put("properties", Map.of());
            }
            return Map.of("name", name(), "description", description(), "input_schema", input);
        }

        @Override public ToolResult execute(Map<String, Object> args) {
            try {
                var request = new McpSchema.CallToolRequest(
                        sdkTool.name(), args != null ? args : Map.of());
                var result = client.callTool(request);
                String text = extractTextContent(result);
                boolean isError = result.isError() != null && result.isError();
                return isError ? ToolResult.error(text) : ToolResult.success(text);
            } catch (Exception e) {
                return ToolResult.error("MCP tool call failed: " + e.getMessage());
            }
        }
    }

    private static String extractTextContent(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) return "(no output)";
        var sb = new StringBuilder();
        for (var content : result.content()) {
            // 当前 ToolResult 只承载文本，图片等非文本内容暂不转换。
            if (content instanceof McpSchema.TextContent tc) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(tc.text());
            }
        }
        return sb.isEmpty() ? "(no output)" : sb.toString();
    }
}
