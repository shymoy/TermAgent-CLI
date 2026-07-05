
package io.github.shymoy.termagent.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shymoy.termagent.config.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**

 * 将 WorktreeSession 保存到磁盘并管理全局单例。

 */
public final class WorktreeSessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile WorktreeSession currentSession;

    private WorktreeSessionStore() {}

    public static WorktreeSession getCurrentSession() {
        return currentSession;
    }

    public static void restoreSession(WorktreeSession session) {
        currentSession = session;
    }

    public static void save(String repoRoot, WorktreeSession session) throws IOException {
        Path root = Path.of(repoRoot);
        if (session == null) {
            Files.deleteIfExists(AppPaths.project(root, "worktree_session.json"));
            Files.deleteIfExists(AppPaths.legacyProject(root, "worktree_session.json"));
            return;
        }
        Path path = AppPaths.promoteProjectFile(root, "worktree_session.json");
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), session);
    }

    public static WorktreeSession load(String repoRoot) {
        Path path = AppPaths.readableProject(Path.of(repoRoot), "worktree_session.json");
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return MAPPER.readValue(path.toFile(), WorktreeSession.class);
        } catch (IOException e) {
            return null;
        }
    }

    static void clearForTesting() {
        currentSession = null;
    }
}
