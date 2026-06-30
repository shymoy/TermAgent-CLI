
package com.mewcode.skill;

import com.mewcode.skill.SkillCatalog.Skill;
import com.mewcode.skill.SkillCatalog.SkillMeta;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.nio.file.*;

/**
 * 加载嵌入在 resources/builtins/ 中的内置 skill。
 * 对应 Go 版 internal/skills/builtins.go 的 LoadBuiltins。
 *
 * <p>Java 没有 go:embed，改用 ClassLoader.getResource 从 classpath 读取。
 * 每个 builtins/<name>/SKILL.md 被解析为一个 skill，body 在启动时立即加载
 * （因为资源已在内存中，无需延迟）。
 */
public final class BuiltinSkills {

    /** 内置 skill 目录列表，与 Go 版 builtins/ 保持同步 */
    private static final String[] BUILTIN_NAMES = {"commit", "test", "backend-interview"};
    private static final String BUILTINS_PREFIX = "builtins/";

    private BuiltinSkills() {}

    /**
     * 加载所有内置 skill 并返回列表。
     * 解析失败的 skill 静默跳过（与 Go 版行为一致）。
     */
    public static List<Skill> load() {
        List<Skill> result = new ArrayList<>();
        for (String name : BUILTIN_NAMES) {
            Skill skill = loadSingle(name);
            if (skill != null) {
                result.add(skill);
            }
        }
        return result;
    }

    /**
     * 读取指定内置 skill 的 tool.json 原始内容。
     * 无 tool.json 时返回 null。用于 directory-type skill 的工具注册。
     */
    public static String readToolJson(String skillName) {
        String path = BUILTINS_PREFIX + skillName + "/tool.json";
        try (InputStream in = BuiltinSkills.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 检查内置 skill 是否有 tool.json（标记为 directory-type skill）。
     */
    public static boolean hasToolJson(String skillName) {
        String path = BUILTINS_PREFIX + skillName + "/tool.json";
        return BuiltinSkills.class.getClassLoader().getResource(path) != null;
    }

    // ── 内部实现 ───────────────────────────────────────────────────────

    private static Skill loadSingle(String name) {
        String mdPath = BUILTINS_PREFIX + name + "/SKILL.md";
        try (InputStream in = BuiltinSkills.class.getClassLoader().getResourceAsStream(mdPath)) {
            if (in == null) {
                return null;
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseSkillMD(name, content);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 解析 SKILL.md 内容，支持可选的 YAML frontmatter。
     * sourceDir 传 null 表示嵌入式 skill（不需要热重载）。
     */
    @SuppressWarnings("unchecked")
    private static Skill parseSkillMD(String fallbackName, String content) {
        String body = content;
        Map<String, Object> frontMatter = Map.of();

        String trimmed = content.stripLeading();
        if (trimmed.startsWith("---")) {
            int firstSep = content.indexOf("---");
            int secondSep = content.indexOf("---", firstSep + 3);
            if (secondSep >= 0) {
                String yamlBlock = content.substring(firstSep + 3, secondSep);
                body = content.substring(secondSep + 3).strip();
                try {
                    Yaml yaml = new Yaml();
                    Map<String, Object> parsed = yaml.load(yamlBlock);
                    if (parsed != null) {
                        frontMatter = parsed;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 从 frontmatter 构建元数据
        String name = stringVal(frontMatter, "name");
        if (name == null || name.isBlank()) {
            name = fallbackName;
        }
        String description = stringVal(frontMatter, "description");
        String whenToUse = stringVal(frontMatter, "when_to_use");

        List<String> tags = List.of();
        Object rawTags = frontMatter.get("tags");
        if (rawTags instanceof List<?> list) {
            tags = list.stream().map(Object::toString).toList();
        }

        List<String> allowedTools = List.of();
        Object rawAllowed = frontMatter.get("allowed_tools");
        if (rawAllowed instanceof List<?> list) {
            allowedTools = list.stream().map(Object::toString).toList();
        }

        String mode = stringVal(frontMatter, "mode");
        if (mode == null || mode.isBlank()) {
            String ctx = stringVal(frontMatter, "context");
            mode = "fork".equals(ctx) ? "fork" : "inline";
        }

        String model = stringVal(frontMatter, "model");
        String forkContext = stringVal(frontMatter, "fork_context");
        if (forkContext == null || forkContext.isBlank()) {
            forkContext = "none";
        }

        // 如果 frontmatter 没有描述，用 body 第一个非标题行
        if (description == null || description.isBlank()) {
            for (String line : body.split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                    description = stripped;
                    break;
                }
            }
        }

        SkillMeta meta = new SkillMeta(
                name,
                description != null ? description : "",
                whenToUse != null ? whenToUse : "",
                tags, allowedTools, mode,
                model != null ? model : "",
                forkContext
        );

        // sourceDir 为 null：内置 skill 不走磁盘热重载
        return new Skill(meta, body, null, true);
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
