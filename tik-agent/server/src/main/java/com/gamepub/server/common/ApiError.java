package com.gamepub.server.common;

public record ApiError(String code, String message, String requestId) {
}
