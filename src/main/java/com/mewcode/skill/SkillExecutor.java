
package com.mewcode.skill;

import com.mewcode.conversation.Message;
import com.mewcode.skill.SkillCatalog.Skill;
import com.mewcode.skill.SkillCatalog.SkillMeta;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolRegistry;

import java.util.*;

/**

 * 以内联或分叉模式执行技能。

 */
public final class SkillExecutor {

    private static final int FORK_RECENT_COUNT = 5;

    private SkillExecutor() {}

    /**

     * 在主机代理上激活技能的 SOP，应用

     * allowed_tools 白名单，并返回渲染的提示体。

     */
    public static String executeInline(Skill skill, String args, SkillHost host) {
        assertAllowedToolsExist(skill, host.toolRegistry());
        String body = substituteArguments(skill.promptBody(), args);
        host.activateSkill(skill.meta().name(), body);
        host.recordSkillInvocation(skill.meta().name(), body);

        if (skill.meta().allowedTools() != null && !skill.meta().allowedTools().isEmpty()) {
            Set<String> allowed = new HashSet<>(skill.meta().allowedTools());
            host.setToolFilter(allowed::contains);
        } else {
            host.setToolFilter(null);
        }
        return body;
    }

    /**

     * 在孤立的子代理中执行技能并返回

     * 最后的助理文本。

     */
    public static String executeFork(Skill skill, String args, SkillForkHost host) {
        assertAllowedToolsExist(skill, host.toolRegistry());
        String body = substituteArguments(skill.promptBody(), args);
        host.recordSkillInvocation(skill.meta().name(), skill.promptBody());
        List<Message> seed = buildForkSeed(skill.meta().forkContext(), host.snapshotParentMessages());
        return host.runSubAgent(body, seed, skill.meta().allowedTools(), skill.meta().model());
    }

    static String substituteArguments(String body, String args) {
        if (args == null || args.isBlank()) {
            return body;
        }
        if (body.contains("$ARGUMENTS")) {
            return body.replace("$ARGUMENTS", args);
        }
        return body + "\n\n## User Request\n\n" + args;
    }

    static List<Message> buildForkSeed(String mode, List<Message> parent) {
        if (parent == null || parent.isEmpty()) {
            return List.of();
        }
        return switch (mode != null ? mode : "none") {
            case "full" -> new ArrayList<>(parent);
            case "recent" -> {
                if (parent.size() <= FORK_RECENT_COUNT) {
                    yield new ArrayList<>(parent);
                }
                yield new ArrayList<>(parent.subList(parent.size() - FORK_RECENT_COUNT, parent.size()));
            }
            default -> List.of();
        };
    }

    private static void assertAllowedToolsExist(Skill skill, ToolRegistry registry) {
        List<String> allowed = skill.meta().allowedTools();
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        for (String name : allowed) {
            if (registry.get(name) == null) {
                throw new IllegalStateException(
                        "Skill '" + skill.meta().name() + "' declares allowed tool '"
                                + name + "' which is not registered");
            }
        }
    }
}
