package io.github.shymoy.termagent.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 统一管理 TermAgent-CLI 的用户级和项目级数据目录。
 *
 * <p>所有新数据都写入 {@value #DATA_DIR}。读取时优先使用新路径；新路径不存在时，
 * 才回退到旧版 {@value #LEGACY_DATA_DIR}。需要继续写入旧文件时，可通过
 * {@link #promoteProjectFile(Path, String...)} 按文件复制到新目录，避免整体迁移大型 worktree。
 */
public final class AppPaths {

    public static final String DATA_DIR = ".termagent";
    public static final String LEGACY_DATA_DIR = ".mewcode";

    private AppPaths() {}

    public static Path project(Path root, String... segments) {
        return resolve(root.resolve(DATA_DIR), segments);
    }

    public static Path legacyProject(Path root, String... segments) {
        return resolve(root.resolve(LEGACY_DATA_DIR), segments);
    }

    public static Path user(String... segments) {
        return resolve(Path.of(System.getProperty("user.home"), DATA_DIR), segments);
    }

    public static Path legacyUser(String... segments) {
        return resolve(Path.of(System.getProperty("user.home"), LEGACY_DATA_DIR), segments);
    }

    /** 返回新旧两个读取层，旧层在前，便于后加载的新配置覆盖旧配置。 */
    public static List<Path> projectLayers(Path root, String... segments) {
        return List.of(legacyProject(root, segments), project(root, segments));
    }

    /** 返回新旧两个用户级读取层，旧层在前。 */
    public static List<Path> userLayers(String... segments) {
        return List.of(legacyUser(segments), user(segments));
    }

    /** 新路径存在时返回新路径，否则返回存在的旧路径；两者都不存在时仍返回新路径。 */
    public static Path readableProject(Path root, String... segments) {
        Path current = project(root, segments);
        if (Files.exists(current)) return current;
        Path legacy = legacyProject(root, segments);
        return Files.exists(legacy) ? legacy : current;
    }

    public static Path readableUser(String... segments) {
        Path current = user(segments);
        if (Files.exists(current)) return current;
        Path legacy = legacyUser(segments);
        return Files.exists(legacy) ? legacy : current;
    }

    /**
     * 首次写入某个旧文件前，将该文件复制到新目录并返回新路径。
     * 目录和不存在的路径不会整体复制，调用方可直接在返回的新路径中创建内容。
     */
    public static Path promoteProjectFile(Path root, String... segments) throws IOException {
        Path current = project(root, segments);
        if (Files.exists(current)) return current;
        Path legacy = legacyProject(root, segments);
        if (Files.isRegularFile(legacy)) {
            Files.createDirectories(current.getParent());
            Files.copy(legacy, current, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return current;
    }

    /** 用户级文件的按项迁移版本。 */
    public static Path promoteUserFile(String... segments) throws IOException {
        Path current = user(segments);
        if (Files.exists(current)) return current;
        Path legacy = legacyUser(segments);
        if (Files.isRegularFile(legacy)) {
            Files.createDirectories(current.getParent());
            Files.copy(legacy, current, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return current;
    }

    /** 将指定的小型数据目录逐文件迁移到新目录，已存在的新文件始终保留。 */
    public static void migrateDirectory(Path legacy, Path current) {
        if (!Files.isDirectory(legacy)) return;
        try (var paths = Files.walk(legacy)) {
            paths.filter(Files::isRegularFile).forEach(source -> {
                Path target = current.resolve(legacy.relativize(source));
                if (Files.exists(target)) return;
                try {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                } catch (IOException ignored) {
                    // 单个文件迁移失败不阻止启动，读取层仍可继续使用旧数据。
                }
            });
        } catch (IOException ignored) {
            // 旧目录不可读时保持新目录为空。
        }
    }

    private static Path resolve(Path base, String... segments) {
        Path result = base;
        for (String segment : segments) result = result.resolve(segment);
        return result;
    }
}
