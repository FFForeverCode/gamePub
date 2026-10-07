package com.gamepub.server.agent;

public record AgentStreamChunk(String text, String finishReason,
                               Long inputTokenCount, Long outputTokenCount) {
    public AgentStreamChunk {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
    }

    public AgentStreamChunk(String text) {
        this(text, null, null, null);
    }
}
