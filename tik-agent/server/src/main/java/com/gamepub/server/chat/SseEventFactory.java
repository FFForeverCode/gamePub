package com.gamepub.server.chat;

import com.gamepub.server.conversation.ConversationDtos.MessageResponse;
import com.gamepub.server.conversation.Message;

public final class SseEventFactory {
    private SseEventFactory() {
    }

    public record MessageCreated(MessageResponse userMessage, MessageResponse assistantMessage) {
    }

    public record Delta(long messageId, String content) {
    }

    public record Complete(MessageResponse message) {
    }

    public record StreamError(long messageId, String code, String message) {
    }

    public record Cancelled(MessageResponse message) {
    }

    public static MessageCreated messageCreated(Message user, Message assistant) {
        return new MessageCreated(MessageResponse.from(user), MessageResponse.from(assistant));
    }

    public static Complete complete(Message message) {
        return new Complete(MessageResponse.from(message));
    }

    public static Cancelled cancelled(Message message) {
        return new Cancelled(MessageResponse.from(message));
    }
}
