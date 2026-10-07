package com.gamepub.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class TikAgentPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsRepresentativeSettingsAndAppliesDefaults() {
        contextRunner
                .withPropertyValues(
                        "tik-agent.memory.max-messages=20",
                        "tik-agent.memory.token-budget=4096",
                        "tik-agent.stream.checkpoint-token-threshold=32",
                        "tik-agent.stream.checkpoint-interval=1s",
                        "tik-agent.generation.lease-ttl=30s",
                        "tik-agent.generation.lease-renew-interval=10s",
                        "tik-agent.agent.models.mock.enabled=true",
                        "tik-agent.agent.models.gemini.enabled=false",
                        "tik-agent.agent.models.gemini.api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TikAgentProperties properties = context.getBean(TikAgentProperties.class);

                    assertThat(properties.memory().maxMessages()).isEqualTo(20);
                    assertThat(properties.memory().tokenBudget()).isPositive();
                    assertThat(properties.stream().checkpointTokenThreshold()).isEqualTo(32);
                    assertThat(properties.stream().checkpointInterval()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.generation().leaseTtl())
                            .isGreaterThan(properties.generation().leaseRenewInterval());
                    assertThat(properties.agent().models().get("mock").enabled()).isTrue();
                    assertThat(properties.agent().models().get("gemini").enabled()).isFalse();
                    assertThat(properties.agent().models().get("gemini").apiKey()).isEmpty();
                });
    }

    @Test
    void rejectsLeaseRenewalIntervalThatIsNotShorterThanLeaseTtl() {
        contextRunner
                .withPropertyValues(
                        "tik-agent.generation.lease-ttl=10s",
                        "tik-agent.generation.lease-renew-interval=10s")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TikAgentProperties.class)
    static class PropertiesConfiguration {
    }
}
