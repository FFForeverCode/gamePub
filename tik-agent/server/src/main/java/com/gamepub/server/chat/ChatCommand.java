package com.gamepub.server.chat;

import jakarta.validation.constraints.NotBlank;

public record ChatCommand(@NotBlank String content, String modelId) {
    public ChatCommand {
        content = content == null ? "" : content.trim();
        modelId = modelId == null || modelId.isBlank() ? null : modelId.trim();
    }
}
