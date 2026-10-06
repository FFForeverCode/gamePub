package com.gamepub.server.chat;

import java.time.LocalDateTime;

import com.gamepub.server.config.TikAgentProperties;
import com.gamepub.server.conversation.ConversationService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StreamingMessageRecovery {
    private final ConversationService conversationService;
    private final TikAgentProperties properties;

    public StreamingMessageRecovery(ConversationService conversationService, TikAgentProperties properties) {
        this.conversationService = conversationService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        conversationService.failStaleStreaming(
                LocalDateTime.now().minus(properties.chat().streamTimeout()));
    }
}
