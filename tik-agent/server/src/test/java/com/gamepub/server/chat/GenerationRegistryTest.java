package com.gamepub.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GenerationRegistryTest {
    @Test
    void allowsOnlyOneGenerationPerConversation() {
        GenerationRegistry registry = new GenerationRegistry();

        GenerationRegistry.GenerationHandle first = registry.acquire(7L);

        assertThatThrownBy(() -> registry.acquire(7L))
                .isInstanceOf(com.gamepub.server.common.BusinessException.class)
                .hasMessageContaining("已有回答");

        registry.release(7L, first);
        GenerationRegistry.GenerationHandle second = registry.acquire(7L);
        assertThat(second).isNotSameAs(first);
        assertThat(registry.activeCount()).isEqualTo(1);
    }
}
