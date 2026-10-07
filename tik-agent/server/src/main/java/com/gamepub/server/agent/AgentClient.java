package com.gamepub.server.agent;

import java.util.List;

import reactor.core.publisher.Flux;

public interface AgentClient {
    Flux<AgentStreamChunk> stream(List<AgentMessage> messages);
}
