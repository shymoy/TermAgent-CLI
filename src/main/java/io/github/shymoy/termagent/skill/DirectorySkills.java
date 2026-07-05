
package io.github.shymoy.termagent.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shymoy.termagent.skill.SkillCatalog.Skill;
import io.github.shymoy.termagent.tool.Tool;
import io.github.shymoy.termagent.tool.ToolCategory;
import io.github.shymoy.termagent.tool.ToolRegistry;
import io.github.shymoy.termagent.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 处理 directory-type skill 的 tool.json 解析与工具注册。
 * 对应 Go 版 internal/skills/directory.go。
 *
 * <p>tool.json 是一个 JSON 数组，每个元素声明一个工具的 schema
 * （name、description、input_schema）。对于磁盘上的 skill，从
 * sourceDir/tool.json 读取；对于内置 skill，回退到
 * resources/builtins/ 下的嵌入文件。
 *
 * <p>注意：Java 版不支持动态编译工具实现。已在 builtinToolFactories
 * 注册表中有实现的工具名会绑定到对应工厂；未注册的工具会记录警告并跳过。
 */
public final class DirectorySkills {

    private static final Logger LOG = Logger.getLogger(DirectorySkills.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DirectorySkills() {}

    /**
     * 工具 schema 数据类，与 Anthropic tool-use 约定一致。
     */
    public record ToolSchema(
            String name,
            String description,
            Map<String, Object> inputSchema
    ) {}

    /**
     * 解析 skill 的 tool.json，返回声明的工具 schema 列表。
     * 找不到 tool.json 时返回空列表。
     */
    public static List<ToolSchema> parseToolJson(Skill skill) throws IOException {
        String jsonContent = null;

        // 优先从磁盘读取（用户自定义 skill）
        if (skill.sourceDir() != null) {
            Path toolJsonPath = skill.sourceDir().resolve("tool.json");
            if (Files.isRegularFile(toolJsonPath)) {
                jsonContent = Files.readString(toolJsonPath);
            }
        }

        // 回退到内置 skill 的嵌入资源
        if (jsonContent == null) {
            jsonContent = BuiltinSkills.readToolJson(skill.meta().name());
        }

        if (jsonContent == null) {
            return List.of();
        }

        // 解析 JSON 数组
        List<Map<String, Object>> rawList = MAPPER.readValue(
                jsonContent, new TypeReference<>() {});

        return rawList.stream().map(map -> {
            String name = (String) map.get("name");
            String description = (String) map.get("description");
            @SuppressWarnings("unchecked")
            Map<String, Object> inputSchema = (Map<String, Object>) map.get("input_schema");
            return new ToolSchema(name, description, inputSchema != null ? inputSchema : Map.of());
        }).toList();
    }

    /**
     * 将 directory-type skill 的 tool.json 中声明的工具注册到 registry。
     * 已存在同名工具的条目会跳过（不覆盖）。
     * 返回成功注册的工具数量。
     *
     * <p>当前实现为每个 schema 创建一个占位工具，执行时返回提示信息。
     * 未来可扩展为绑定到具体实现的工厂模式（类似 Go 版的 builtinToolFactories）。
     */
    public static int registerDirectoryTools(Skill skill, ToolRegistry registry) {
        List<ToolSchema> schemas;
        try {
            schemas = parseToolJson(skill);
        } catch (IOException e) {
            LOG.warning("skill: 解析 tool.json 失败 [" + skill.meta().name() + "]: " + e.getMessage());
            return 0;
        }

        int count = 0;
        for (var schema : schemas) {
            // 已注册的工具不覆盖
            if (registry.get(schema.name()) != null) {
                continue;
            }

            // 创建基于 schema 的工具实例
            Tool tool = createSchemaBasedTool(schema);
            registry.register(tool);
            count++;
        }
        return count;
    }

    /**
     * 根据 tool.json 的 schema 创建一个工具实例。
     * 工具的 schema 直接来自 tool.json 声明，execute 返回占位提示。
     */
    private static Tool createSchemaBasedTool(ToolSchema schema) {
        return new Tool() {
            @Override
            public String name() {
                return schema.name();
            }

            @Override
            public String description() {
                return schema.description();
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.WRITE;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of(
                        "name", schema.name(),
                        "description", schema.description() != null ? schema.description() : "",
                        "input_schema", schema.inputSchema()
                );
            }

            @Override
            public ToolResult execute(Map<String, Object> args) {
                return ToolResult.error(
                        "Tool '" + schema.name() + "' 由 skill '"
                                + "' 的 tool.json 声明，但没有编译期实现");
            }
        };
    }
}
