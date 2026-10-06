package com.gamepub.server.chat;

import java.util.List;

import com.gamepub.server.agent.AgentMessage;

public interface ShortTermMemory {
    List<AgentMessage> get(long conversationId);

    void replace(long conversationId, List<AgentMessage> messages);

    void invalidate(long conversationId);
}
