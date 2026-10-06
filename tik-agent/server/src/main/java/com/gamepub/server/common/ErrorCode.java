package com.gamepub.server.common;

public enum ErrorCode {
    INVALID_ARGUMENT(400),
    CONVERSATION_NOT_FOUND(404),
    MESSAGE_NOT_FOUND(404),
    MODEL_NOT_AVAILABLE(503),
    GENERATION_IN_PROGRESS(409),
    MODEL_TIMEOUT(503),
    MODEL_RATE_LIMITED(503),
    MODEL_UNAVAILABLE(503),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) { this.httpStatus = httpStatus; }
    public int httpStatus() { return httpStatus; }
}
