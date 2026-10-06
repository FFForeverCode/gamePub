package com.gamepub.server.chat;

import java.util.List;

import com.gamepub.server.agent.AgentClient;
import com.gamepub.server.agent.AgentMessage;
import com.gamepub.server.conversation.MessagePair;

public record ChatPreparation(ChatCommand command, String modelId, AgentClient client,
                              MessagePair pair, List<AgentMessage> context) {
}
