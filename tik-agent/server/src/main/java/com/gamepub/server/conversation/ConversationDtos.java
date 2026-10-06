package com.gamepub.server.conversation;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ConversationDtos {
    private ConversationDtos() {}

    public record RenameRequest(@NotBlank @Size(max = 120) String title) {}
    public record ConversationResponse(long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static ConversationResponse from(Conversation value) {
            return new ConversationResponse(value.getId(), value.getTitle(), value.getCreatedAt(), value.getUpdatedAt());
        }
    }
    public record MessageResponse(long id, long conversationId, String role, String content,
                                  String status, String modelId, long sequenceNo, String errorCode,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static MessageResponse from(Message value) {
            return new MessageResponse(value.getId(), value.getConversationId(), value.getRole().name(),
                    value.getContent(), value.getStatus().name(), value.getModelId(), value.getSequenceNo(),
                    value.getErrorCode(), value.getCreatedAt(), value.getUpdatedAt());
        }
    }
}
