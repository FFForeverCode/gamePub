package com.gamepub.server.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "tik-agent")
@Validated
public record TikAgentProperties(Chat chat, @Valid Memory memory, @Valid Stream stream,
                                 @Valid Generation generation, Agent agent) {

    public record Chat(int maxContentLength, Duration streamTimeout) {
    }

    public record Memory(@Positive int maxMessages, @Positive int tokenBudget,
                         Duration expireAfterAccess, @Positive long maximumSize) {
        public int messageLimit() {
            return maxMessages;
        }
    }

    public record Stream(@Positive int checkpointTokenThreshold, Duration checkpointInterval,
                         @Positive int eventMaxLength, Duration eventTtl, @Positive int maxOutputBytes) {
    }

    public record Generation(Duration leaseTtl, Duration leaseRenewInterval, Duration timeout,
                             @Positive int maxConcurrentPerInstance) {
        @AssertTrue(message = "leaseRenewInterval must be shorter than leaseTtl")
        public boolean isLeaseRenewalIntervalShorterThanTtl() {
            return leaseRenewInterval != null && leaseTtl != null
                    && leaseRenewInterval.compareTo(leaseTtl) < 0;
        }
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
