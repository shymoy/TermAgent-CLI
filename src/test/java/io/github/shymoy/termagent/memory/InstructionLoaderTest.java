package io.github.shymoy.termagent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionLoaderTest {

    @Test
    void currentInstructionNameLoadsAfterLegacyName(@TempDir Path root) throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", root.resolve("home").toString());
            Files.writeString(root.resolve("MEWCODE.md"), "legacy instruction");
            Files.writeString(root.resolve("AGENTS.md"), "shared instruction");
            Files.writeString(root.resolve("TERMAGENT.md"), "current instruction");

            var sources = InstructionLoader.discoverInstructions(root.toString());
            var paths = sources.stream().map(InstructionLoader.InstructionSource::path).toList();

            int legacy = indexEndingWith(paths, "MEWCODE.md");
            int shared = indexEndingWith(paths, "AGENTS.md");
            int current = indexEndingWith(paths, "TERMAGENT.md");
            assertTrue(legacy >= 0 && legacy < shared && shared < current);
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    private static int indexEndingWith(java.util.List<String> paths, String name) {
        for (int i = 0; i < paths.size(); i++) {
            if (paths.get(i).endsWith(name)) return i;
        }
        return -1;
    }
}
