
package io.github.shymoy.termagent.skill;

import io.github.shymoy.termagent.config.AppPaths;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**

 * 管理技能发现、加载和上下文生成。

 * <p>

 * 第一阶段加载只读取frontmatter（快速启动）； {@link #getFull}

 * 每次调用时都会触发阶段 2 重新读取正文（热加载）。

 * <p>

 * 通过 {@link #loadCatalog} 进行三层加载：内置 → 用户全局

 * ({@code ~/.termagent/skills/})→项目({@code .termagent/skills/}),

 * 后面的层按名称覆盖前面的层。

 */
public class SkillCatalog {

    // ── Data types ──────────────────────────────────────────────────────

    public record SkillMeta(
            String name,
            String description,
            String whenToUse,
            List<String> tags,
            List<String> allowedTools,
            String mode,
            String model,
            String forkContext
    ) {}

    public record Skill(SkillMeta meta, String promptBody, Path sourceDir, boolean bodyLoaded) {
        public Skill withBody(String newBody) {
            return new Skill(meta, newBody, sourceDir, true);
        }
    }

    // ── State ───────────────────────────────────────────────────────────

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();

    private String workDir;

    // ── Public API ──────────────────────────────────────────────────────

    public void register(Skill skill, String source) {
        skills.put(skill.meta().name(), skill);
        sources.put(skill.meta().name(), source);
    }

    public void register(Skill skill) {
        register(skill, "");
    }

    public Map<String, Skill> getSkills() {
        return Collections.unmodifiableMap(skills);
    }

    public Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**

     * 返回已加载正文的技能。对于磁盘支持的技能

     * 每次调用时都会重新读取正文（热加载）。读取失败时

     * 先前缓存的正文将被保留。

     */
    public Optional<Skill> getFull(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            return Optional.empty();
        }
        if (skill.sourceDir() == null) {
            return Optional.of(skill);
        }
        try {
            Skill reloaded = loadSkill(skill.sourceDir());
            if (reloaded != null) {
                skills.put(name, reloaded);
                return Optional.of(reloaded);
            }
        } catch (IOException ignored) {
            // 保留之前缓存的body
        }
        return Optional.of(skill);
    }

    public List<SkillMeta> list() {
        return skills.values().stream().map(Skill::meta).toList();
    }

    public String source(String name) {
        return sources.getOrDefault(name, "");
    }

    // ── 三层目录加载──────────────────────────────────────

    /**

     * 通过合并三层和后来的来源来构建目录

     * 按名称覆盖早期的项目（项目赢得用户胜利

     * 内置）。 Phase-1：只读取frontmatter；尸体空着

     * 直到调用{@link #getFull}。

     */
    public static SkillCatalog loadCatalog(String workDir) {
        SkillCatalog c = new SkillCatalog();
        c.workDir = workDir;

        // Tier 1: 从 resources/builtins/ 加载嵌入的内置 skill（优先级最低）
        for (var skill : BuiltinSkills.load()) {
            c.register(skill, "builtin");
        }

        // 第 2 层：用户全局
        AppPaths.userLayers("skills").forEach(path -> c.loadTier(path, "user"));

        // 第三层：项目
        AppPaths.projectLayers(Path.of(workDir), "skills").forEach(path -> c.loadTier(path, "project"));

        return c;
    }

    public void reload(String workDir) {
        SkillCatalog fresh = loadCatalog(workDir);
        this.skills.clear();
        this.skills.putAll(fresh.skills);
        this.sources.clear();
        this.sources.putAll(fresh.sources);
        this.workDir = fresh.workDir;
    }

    /**

     * 行走{@code dir}；每个直接子目录都被视为一项技能。

     * 丢失或无法访问的目录将被默默忽略。

     */
    public void loadFromDirectory(Path dir) {
        loadTier(dir, dir.toString());
    }

    private void loadTier(Path dir, String source) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(skillDir -> {
                try {
                    Skill skill = loadSkill(skillDir);
                    if (skill != null) {
                        register(skill, source);
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // ── 情境建构────────────────────────────────────────────────

    /**

     * 构建一个适合系统提示注入的上下文块

     * 包含给定主动技能名称的提示体。

     */
    public String buildActiveContext(Set<String> activeSkillNames) {
        if (activeSkillNames == null || activeSkillNames.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        sb.append("## Active Skills\n\n");
        for (var name : activeSkillNames) {
            var skill = skills.get(name);
            if (skill != null) {
                sb.append("### ").append(name).append("\n");
                sb.append(skill.promptBody()).append("\n\n");
            }
        }
        return sb.toString();
    }

    // ── 加载内部结构────────────────────────────────────────────────

    private static Skill loadSkill(Path dir) throws IOException {
        // 策略一：skill.yaml + prompt.md
        Path metaPath = dir.resolve("skill.yaml");
        if (Files.isRegularFile(metaPath)) {
            return loadFromYamlAndPrompt(dir, metaPath);
        }

        // 策略 2：SKILL.md 以及可选的 YAML 前端内容
        Path mdPath = dir.resolve("SKILL.md");
        if (Files.isRegularFile(mdPath)) {
            String content = Files.readString(mdPath);
            return parseSkillMD(dir, content);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Skill loadFromYamlAndPrompt(Path dir, Path metaPath) throws IOException {
        String yamlText = Files.readString(metaPath);
        Yaml yaml = new Yaml();
        Map<String, Object> map = yaml.load(yamlText);
        if (map == null) {
            map = Map.of();
        }

        SkillMeta meta = metaFromMap(map, dir);

        String promptBody = "";
        Path promptPath = dir.resolve("prompt.md");
        if (Files.isRegularFile(promptPath)) {
            promptBody = Files.readString(promptPath);
        }

        return new Skill(meta, promptBody, dir, true);
    }

    @SuppressWarnings("unchecked")
    private static Skill parseSkillMD(Path dir, String content) {
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
                } catch (Exception ignored) {
                }
            }
        }

        SkillMeta meta = metaFromMap(frontMatter, dir);

        // 如果不存在，则从第一个非空、非标题行自动生成描述
        String description = meta.description();
        if (description == null || description.isBlank()) {
            for (String line : body.split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                    description = stripped;
                    break;
                }
            }
            meta = new SkillMeta(meta.name(), description != null ? description : "",
                    meta.whenToUse(), meta.tags(), meta.allowedTools(),
                    meta.mode(), meta.model(), meta.forkContext());
        }

        return new Skill(meta, body, dir, true);
    }

    @SuppressWarnings("unchecked")
    private static SkillMeta metaFromMap(Map<String, Object> map, Path dir) {
        String name = stringVal(map, "name");
        if (name == null || name.isBlank()) {
            name = dir.getFileName().toString().toLowerCase().replace(' ', '-');
        }
        String description = stringVal(map, "description");
        String whenToUse = stringVal(map, "when_to_use");

        List<String> tags = List.of();
        Object rawTags = map.get("tags");
        if (rawTags instanceof List<?> list) {
            tags = list.stream().map(Object::toString).toList();
        }

        List<String> allowedTools = List.of();
        Object rawAllowed = map.get("allowed_tools");
        if (rawAllowed instanceof List<?> list) {
            allowedTools = list.stream().map(Object::toString).toList();
        }

        String mode = stringVal(map, "mode");
        if (mode == null || mode.isBlank()) {
            // 向后兼容：上下文："fork" 与模式相同："fork"
            String ctx = stringVal(map, "context");
            if ("fork".equals(ctx)) {
                mode = "fork";
            } else {
                mode = "inline";
            }
        }

        String model = stringVal(map, "model");

        String forkContext = stringVal(map, "fork_context");
        if (forkContext == null || forkContext.isBlank()) {
            forkContext = "none";
        }

        return new SkillMeta(
                name,
                description != null ? description : "",
                whenToUse != null ? whenToUse : "",
                tags,
                allowedTools,
                mode,
                model != null ? model : "",
                forkContext
        );
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
