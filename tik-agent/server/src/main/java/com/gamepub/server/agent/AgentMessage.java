package com.gamepub.server.agent;

import com.gamepub.server.conversation.MessageRole;

public record AgentMessage(MessageRole role, String content) {
}
