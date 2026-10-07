package com.gamepub.server.conversation;

public enum GenerationStatus {
    CREATED,
    RUNNING,
    CANCEL_REQUESTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    RETRYABLE
}
