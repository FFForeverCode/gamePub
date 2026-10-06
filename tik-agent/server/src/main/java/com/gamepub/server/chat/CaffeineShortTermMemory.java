package com.gamepub.server.chat;

import java.util.ArrayList;
import java.util.List;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.gamepub.server.agent.AgentMessage;
import com.gamepub.server.conversation.Message;
import com.gamepub.server.conversation.MessageMapper;
import com.gamepub.server.config.TikAgentProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.gamepub.server.conversation.ConversationDeletedEvent;

@Component
public class CaffeineShortTermMemory implements ShortTermMemory {
    private final MessageMapper messageMapper;
    private final int messageLimit;
    private final Cache<Long, List<AgentMessage>> cache;

    public CaffeineShortTermMemory(MessageMapper messageMapper, TikAgentProperties properties) {
        this.messageMapper = messageMapper;
        this.messageLimit = properties.memory().messageLimit();
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.memory().maximumSize())
                .expireAfterAccess(properties.memory().expireAfterAccess())
                .build();
    }

    @Override
    public List<AgentMessage> get(long conversationId) {
        return cache.get(conversationId, this::loadFromDatabase);
    }

    @Override
    public void replace(long conversationId, List<AgentMessage> messages) {
        List<AgentMessage> bounded = messages.size() <= messageLimit
                ? messages
                : messages.subList(messages.size() - messageLimit, messages.size());
        cache.put(conversationId, List.copyOf(bounded));
    }

    @Override
    public void invalidate(long conversationId) {
        cache.invalidate(conversationId);
    }

    @EventListener
    public void onConversationDeleted(ConversationDeletedEvent event) {
        invalidate(event.conversationId());
    }

    private List<AgentMessage> loadFromDatabase(long conversationId) {
        List<Message> messages = messageMapper.findRecentCompleted(conversationId, messageLimit);
        List<AgentMessage> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            result.add(new AgentMessage(message.getRole(), message.getContent()));
        }
        return List.copyOf(result);
    }
}
