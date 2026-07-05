
package io.github.shymoy.termagent.subagent;

import io.github.shymoy.termagent.config.AppPaths;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**

 * 从三个来源加载子代理定义（按优先级顺序）：

 * <ol>

 * <li>内置规格（{@link SubAgentSpec#GENERAL_PURPOSE}等）</li>

 * <li>{@code ~/.termagent/agents/*.md}</li> 的用户级定义

 * <li> {@code <projectRoot>/.termagent/agents/*.md}</li> 的项目级定义

 * </ol>

 * 后面的源会覆盖具有相同代理名称的早期源。

 *

 * <p>Each {@code .md} 文件使用由 {@code ---} 分隔的可选 YAML frontmatter

 * 接下来是成为系统提示覆盖的 Markdown 正文。的

 * frontmatter 字段为：{@code name}、{@code description}、{@code disallowedTools}、

 * {@code model} 和 {@code maxTurns}。

 */
public final class AgentLoader {

    // Go 版已移除模型白名单校验：第三方模型名（如 "glm-5.1"）需要透传给路由层，
    // 只对 "inherit" 做标准化（小写），其余保持原样

    private final Map<String, SubAgentSpec> agents = new LinkedHashMap<>();

    private AgentLoader() {}

    /**

     * 加载所有代理定义：内置规范，然后是用户级别，然后是项目级别。

     *

     * @param projectRoot 项目根目录（可能是{@code null}以跳过项目级别）

     * @return a  代理名称到规格的映射

     */
    public static Map<String, SubAgentSpec> loadAll(Path projectRoot) {
        var loader = new AgentLoader();
        loader.loadBuiltins();

        AppPaths.userLayers("agents").forEach(loader::loadDir);

        if (projectRoot != null) {
            AppPaths.projectLayers(projectRoot, "agents").forEach(loader::loadDir);
        }

        return Collections.unmodifiableMap(loader.agents);
    }

    /**

     * 返回所有加载的代理名称的排序列表。

     */
    public static List<String> listNames(Map<String, SubAgentSpec> agents) {
        var names = new ArrayList<>(agents.keySet());
        Collections.sort(names);
        return names;
    }

    private void loadBuiltins() {
        agents.put(SubAgentSpec.GENERAL_PURPOSE.name(), SubAgentSpec.GENERAL_PURPOSE);
        agents.put(SubAgentSpec.PLAN.name(), SubAgentSpec.PLAN);
        agents.put(SubAgentSpec.EXPLORE.name(), SubAgentSpec.EXPLORE);
    }

    private void loadDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    continue;
                }
                try {
                    SubAgentSpec spec = parseAgentFile(path);
                    agents.put(spec.name(), spec);
                } catch (Exception e) {
                    // 静默跳过无效文件，匹配 Go 行为
                }
            }
        } catch (IOException e) {
            // 目录不可读--跳过
        }
    }

    /**

     * 解析单个代理定义文件。该文件可以选择开始

     * {@code ---} 分隔符之间有 YAML frontmatter。

     */
    static SubAgentSpec parseAgentFile(Path path) throws IOException {
        String content = Files.readString(path);
        String trimmed = content.strip();

        String yamlBlock = null;
        String body = trimmed;

        if (trimmed.startsWith("---")) {
            // 在第二个 "---" 分隔符上拆分
            int firstEnd = trimmed.indexOf("---", 3);
            if (firstEnd >= 0) {
                yamlBlock = trimmed.substring(3, firstEnd).strip();
                body = trimmed.substring(firstEnd + 3).strip();
            }
        }

        String name = null;
        String description = null;
        List<String> tools = List.of();
        List<String> disallowedTools = List.of();
        String model = null;
        int maxTurns = 0;

        if (yamlBlock != null && !yamlBlock.isEmpty()) {
            Yaml yaml = new Yaml();
            Map<String, Object> frontmatter = yaml.load(yamlBlock);
            if (frontmatter != null) {
                name = getString(frontmatter, "name");
                description = getString(frontmatter, "description");
                tools = getStringList(frontmatter, "tools");
                disallowedTools = getStringList(frontmatter, "disallowedTools");
                model = getString(frontmatter, "model");
                Object maxTurnsObj = frontmatter.get("maxTurns");
                if (maxTurnsObj instanceof Number n) {
                    maxTurns = n.intValue();
                }
            }
        }

        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(
                    "Agent definition %s: missing required field 'name'".formatted(path));
        }
        if (description == null || description.isEmpty()) {
            throw new IllegalArgumentException(
                    "Agent definition %s: missing required field 'description'".formatted(path));
        }
        // 标准化 "inherit"（不区分大小写），其余模型名原样透传给路由层
        if (model != null) {
            model = model.strip();
            if (model.equalsIgnoreCase("inherit")) {
                model = "inherit";
            }
        }

        String systemPrompt = body.isEmpty() ? null : body;

        return new SubAgentSpec(name, description, tools, disallowedTools, systemPrompt, maxTurns, model);
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            var result = new ArrayList<String>();
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s);
                }
            }
            return List.copyOf(result);
        }
        return List.of();
    }
}
