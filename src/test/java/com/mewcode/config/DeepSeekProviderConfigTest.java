
package com.mewcode.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekProviderConfigTest {

    @Test
    void deepSeekProtocolGetsDefaultBaseUrlAndModel() throws Exception {
        var path = Files.createTempFile("mewcode-deepseek", ".yaml");
        Files.writeString(path, """
                providers:
                  - name: deepseek
                    protocol: deepseek
                    api_key: test-key
                """);

        var cfg = ConfigLoader.load(path.toString());
        var provider = cfg.getProviders().get(0);

        assertEquals("https://api.deepseek.com", provider.getBaseUrl());
        assertEquals("deepseek-v4-pro", provider.getModel());
    }

    @Test
    void deepSeekModelsUseOneMillionTokenContextWindow() {
        assertEquals(1_000_000, ProviderConfig.windowForModel("deepseek-v4-flash"));
        assertEquals(1_000_000, ProviderConfig.windowForModel("deepseek-v4-pro"));
        assertEquals(1_000_000, ProviderConfig.windowForModel("deepseek-reasoner"));
    }
}
