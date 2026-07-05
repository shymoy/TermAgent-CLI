
package io.github.shymoy.termagent.tool;

import java.util.*;

/**
 * 工具注册中心，统一管理工具实例及其对模型暴露的 schema。
 *
 * <p>启动阶段通过 {@link #register(Tool)} 注册工具；请求模型前通过
 * {@link #getAllSchemas(String)} 生成目标协议需要的工具定义；模型返回工具名后，
 * 执行器再通过 {@link #get(String)} 找到具体工具并调用其 execute 方法。</p>
 */
public class ToolRegistry {

    /** 发送给模型或 UI 的单次工具输出最大字符数。 */
    public static final int MAX_OUTPUT_CHARS = 10_000;

    // 工具可能在不同初始化流程中动态注册，因此使用并发容器保存。
    private final Map<String, Tool> tools = new java.util.concurrent.ConcurrentHashMap<>();
    // 记录已经通过 ToolSearch 发现的延迟工具；发现后才会把完整 schema 提供给模型。
    private final Set<String> discoveredTools = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // OpenAI 系协议的工具 schema 外层结构与 Anthropic 使用的内部基础格式不同。
    private static boolean isOpenAIProtocol(String protocol) {
        return "openai".equals(protocol) || "openai-compat".equals(protocol);
    }

    /** 标记延迟工具已被发现，使其可以出现在后续请求的完整工具列表中。 */
    public void markDiscovered(String name) {
        discoveredTools.add(name);
    }

    public boolean isDiscovered(String name) {
        return discoveredTools.contains(name);
    }

    public List<String> getDeferredToolNames() {
        return tools.values().stream()
                .filter(t -> t.shouldDefer() && !discoveredTools.contains(t.name()))
                .map(Tool::name)
                .toList();
    }

    /** 以工具名为键注册实例；同名工具会覆盖之前的注册。 */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** 根据模型返回的工具名查找真正要执行的 Tool 实例。 */
    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Tool> listTools() {
        return List.copyOf(tools.values());
    }

    /**
     * 获取本轮可发送给模型的全部工具 schema。
     * 未被发现的延迟工具会被过滤，普通工具和已发现工具会按目标协议转换格式。
     */
    public List<Map<String, Object>> getAllSchemas(String protocol) {
        var schemas = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (tool.shouldDefer() && !discoveredTools.contains(tool.name())) continue;
            var base = tool.schema();
            if (isOpenAIProtocol(protocol)) {
                // 内部基础 schema 使用 input_schema，OpenAI 系协议使用 parameters。
                schemas.add(Map.of(
                        "type", "function",
                        "name", base.get("name"),
                        "description", base.get("description"),
                        "parameters", base.get("input_schema")
                ));
            } else {
                schemas.add(base);
            }
        }
        return schemas;
    }

    public List<Tool> getDeferredTools() {
        return tools.values().stream()
                .filter(Tool::shouldDefer)
                .toList();
    }

    /**
     * 按名称或描述模糊搜索延迟工具，并返回目标协议格式的 schema。
     * 搜索只负责展示候选项，调用方确认后还需要通过 markDiscovered 标记为已发现。
     */
    public List<Map<String, Object>> searchDeferred(String query, int maxResults, String protocol) {
        String lower = query.toLowerCase();
        var matches = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (!tool.shouldDefer()) continue;
            if (tool.name().toLowerCase().contains(lower)
                    || tool.description().toLowerCase().contains(lower)) {
                var base = tool.schema();
                if (isOpenAIProtocol(protocol)) {
                    matches.add(Map.of(
                            "type", "function",
                            "name", base.get("name"),
                            "description", base.get("description"),
                            "parameters", base.get("input_schema")
                    ));
                } else {
                    matches.add(base);
                }
                if (matches.size() >= maxResults) break;
            }
        }
        return matches;
    }

    /** 按名称精确查找工具 schema，名称匹配忽略大小写。 */
    public List<Map<String, Object>> findDeferredByNames(List<String> names, String protocol) {
        var nameSet = new HashSet<String>();
        for (var n : names) nameSet.add(n.toLowerCase());

        var matches = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (nameSet.contains(tool.name().toLowerCase())) {
                var base = tool.schema();
                if (isOpenAIProtocol(protocol)) {
                    matches.add(Map.of(
                            "type", "function",
                            "name", base.get("name"),
                            "description", base.get("description"),
                            "parameters", base.get("input_schema")
                    ));
                } else {
                    matches.add(base);
                }
            }
        }
        return matches;
    }

    /** 创建包含项目基础文件和命令工具的默认注册中心。 */
    public static ToolRegistry createDefault() {
        var reg = new ToolRegistry();
        reg.register(new io.github.shymoy.termagent.tool.impl.ReadFileTool());
        reg.register(new io.github.shymoy.termagent.tool.impl.WriteFileTool());
        reg.register(new io.github.shymoy.termagent.tool.impl.EditFileTool());
        reg.register(new io.github.shymoy.termagent.tool.impl.BashTool());
        reg.register(new io.github.shymoy.termagent.tool.impl.GlobTool());
        reg.register(new io.github.shymoy.termagent.tool.impl.GrepTool());
        return reg;
    }
}
