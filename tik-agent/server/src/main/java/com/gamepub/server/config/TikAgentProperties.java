package com.gamepub.server.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tik-agent")
public record TikAgentProperties(Chat chat, Memory memory, Agent agent) {

    public record Chat(int maxContentLength, Duration streamTimeout) {
    }

    public record Memory(int messageLimit, Duration expireAfterAccess, long maximumSize) {
    }

    public record Agent(String defaultModel, String systemPrompt, Map<String, Model> models) {
        public Agent {
            models = models == null ? new LinkedHashMap<>() : new LinkedHashMap<>(models);
        }
    }

    public record Model(String displayName, String provider, String baseUrl, String apiKey,
                        String modelName, double temperature, Duration timeout, boolean enabled) {
    }
}
