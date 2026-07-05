package io.github.shymoy.termagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPathsTest {

    @Test
    void readableProjectPrefersNewPathAndFallsBackToLegacy(@TempDir Path root) throws Exception {
        Path legacy = AppPaths.legacyProject(root, "sessions", "one.jsonl");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "legacy");

        assertEquals(legacy, AppPaths.readableProject(root, "sessions", "one.jsonl"));

        Path current = AppPaths.project(root, "sessions", "one.jsonl");
        Files.createDirectories(current.getParent());
        Files.writeString(current, "current");
        assertEquals(current, AppPaths.readableProject(root, "sessions", "one.jsonl"));
    }

    @Test
    void promoteProjectFileCopiesOnlyRequestedLegacyFile(@TempDir Path root) throws Exception {
        Path legacy = AppPaths.legacyProject(root, "sessions", "one.jsonl");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "legacy session");
        Files.writeString(legacy.getParent().resolve("other.jsonl"), "do not copy");

        Path promoted = AppPaths.promoteProjectFile(root, "sessions", "one.jsonl");

        assertEquals("legacy session", Files.readString(promoted));
        assertFalse(Files.exists(promoted.getParent().resolve("other.jsonl")));
        assertTrue(Files.exists(legacy));
    }

    @Test
    void configLoaderMergesLegacyBeforeCurrentConfig(@TempDir Path root) throws Exception {
        String oldHome = System.getProperty("user.home");
        String oldDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.home", root.resolve("home").toString());
            System.setProperty("user.dir", root.resolve("project").toString());
            Path legacy = root.resolve("home/.mewcode/config.yaml");
            Path current = root.resolve("home/.termagent/config.yaml");
            Files.createDirectories(legacy.getParent());
            Files.createDirectories(current.getParent());
            Files.writeString(legacy, config("legacy-model"));
            Files.writeString(current, config("current-model"));

            var loaded = ConfigLoader.load(null);

            assertEquals("current-model", loaded.getProviders().getFirst().getModel());
        } finally {
            System.setProperty("user.home", oldHome);
            System.setProperty("user.dir", oldDir);
        }
    }

    private static String config(String model) {
        return """
                providers:
                  - name: test
                    protocol: openai
                    base_url: https://example.invalid
                    model: %s
                """.formatted(model);
    }
}
