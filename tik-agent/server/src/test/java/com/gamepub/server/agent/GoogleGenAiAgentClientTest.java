package com.gamepub.server.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.gamepub.server.config.TikAgentProperties;

class GoogleGenAiAgentClientTest {

    @Test
    void createsDeveloperApiKeyClientWithoutMakingAProviderRequest() {
        TikAgentProperties.Model model = new TikAgentProperties.Model(
                "Gemini", "gemini", null, "test-key", "gemini-test", 0.2,
                java.time.Duration.ofSeconds(10), true);

        GoogleGenAiAgentClient client = GoogleGenAiAgentClient.create(model, "系统");

        assertThat(client).isNotNull();
    }
}
