package com.gamepub.server.agent;

import java.time.Duration;
import java.util.List;

import reactor.core.publisher.Flux;

public class MockAgentClient implements AgentClient {
    @Override
    public Flux<String> stream(List<AgentMessage> messages) {
        String question = messages.stream()
                .filter(message -> message.role() == com.gamepub.server.conversation.MessageRole.USER)
                .reduce((first, second) -> second)
                .map(AgentMessage::content)
                .orElse("");
        String answer = "这是本地 Mock 模型的流式回答。你刚才问的是：" + question
                + "\n\n当前服务已打通会话、历史记录和 SSE 流式传输。";
        return Flux.fromStream(java.util.stream.IntStream.iterate(0, i -> i < answer.length(), i -> i + 6)
                        .mapToObj(i -> answer.substring(i, Math.min(i + 6, answer.length()))))
                .delayElements(Duration.ofMillis(35));
    }
}
