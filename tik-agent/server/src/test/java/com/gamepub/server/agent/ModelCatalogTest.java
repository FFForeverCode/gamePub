package com.gamepub.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.gamepub.server.config.TikAgentProperties;
import org.junit.jupiter.api.Test;

class ModelCatalogTest {

    @Test
    void listsConfiguredClientsInDeterministicOrderAndKeepsMockFallback() {
        Map<String, TikAgentProperties.Model> models = new LinkedHashMap<>();
        models.put("zeta", model("Zeta", "openai"));
        models.put("gemini", model("Gemini", "gemini"));
        models.put("mock", model("Mock", "mock"));
        TikAgentProperties properties = properties("gemini", models);

        ModelCatalog catalog = new ModelCatalog(properties, Map.of("zeta", mock(AgentClient.class)));

        assertThat(catalog.availableModels()).extracting(ModelDescriptor::id)
                .containsExactly("mock", "zeta");
        assertThat(catalog.availableModels()).filteredOn(ModelDescriptor::id, "gemini").isEmpty();
        assertThat(catalog.requireClient("mock")).isInstanceOf(MockAgentClient.class);
    }

    private static TikAgentProperties properties(String defaultModel,
                                                  Map<String, TikAgentProperties.Model> models) {
        return new TikAgentProperties(
                new TikAgentProperties.Chat(1000, Duration.ofSeconds(30)),
                new TikAgentProperties.Memory(20, 4096, Duration.ofMinutes(30), 1000),
                new TikAgentProperties.Stream(32, Duration.ofSeconds(1), 10000,
                        Duration.ofHours(24), 1024 * 1024),
                new TikAgentProperties.Generation(Duration.ofSeconds(30), Duration.ofSeconds(10),
                        Duration.ofSeconds(120), 1),
                new TikAgentProperties.Agent(defaultModel, "", models));
    }

    private static TikAgentProperties.Model model(String displayName, String provider) {
        return new TikAgentProperties.Model(displayName, provider, null, null, provider,
                0.2, Duration.ofSeconds(30), true);
    }
}
