
package io.github.shymoy.termagent.tool.impl;

import io.github.shymoy.termagent.tool.Tool;
import io.github.shymoy.termagent.tool.ToolCategory;
import io.github.shymoy.termagent.tool.ToolResult;
import io.github.shymoy.termagent.worktree.WorktreeManager;
import io.github.shymoy.termagent.worktree.WorktreeSessionStore;
import io.github.shymoy.termagent.worktree.SlugValidator;

import java.security.SecureRandom;
import java.util.Map;

/**

 * 创建一个独立的 git 工作树并将会话切换到其中。

 */
public class EnterWorktreeTool implements Tool {

    private final WorktreeManager worktreeManager;
    private final String sessionId;
    private static final SecureRandom RANDOM = new SecureRandom();

    public EnterWorktreeTool(WorktreeManager worktreeManager, String sessionId) {
        this.worktreeManager = worktreeManager;
        this.sessionId = sessionId;
    }

    @Override public String name() { return "EnterWorktree"; }
    @Override public ToolCategory category() { return ToolCategory.COMMAND; }
    @Override public boolean shouldDefer() { return true; }

    @Override
    public String description() {
        return "Creates an isolated worktree (via git) and switches the session into it";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of(
                                        "type", "string",
                                        "description", "Optional name for the worktree. Max 64 chars."
                                )
                        )
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        if (WorktreeSessionStore.getCurrentSession() != null) {
            return ToolResult.error("Already in a worktree session");
        }

        String slug = args.containsKey("name") ? String.valueOf(args.get("name")) : null;
        if (slug == null || slug.isBlank()) {
            slug = "wt-" + Integer.toHexString(RANDOM.nextInt());
        }

        try {
            SlugValidator.validate(slug);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }

        try {
            var info = worktreeManager.create(slug, null);

            var session = new io.github.shymoy.termagent.worktree.WorktreeSession(
                    System.getProperty("user.dir"),
                    info.path(),
                    slug,
                    info.branch(),
                    "", "", sessionId, 0
            );
            WorktreeSessionStore.restoreSession(session);
            WorktreeSessionStore.save(worktreeManager.getProjectRoot(), session);

            return ToolResult.success(
                    "Created worktree at %s on branch %s. The session is now working in the worktree. Use ExitWorktree to leave mid-session."
                            .formatted(info.path(), info.branch())
            );
        } catch (Exception e) {
            return ToolResult.error("Error creating worktree: " + e.getMessage());
        }
    }
}
