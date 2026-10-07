package com.gamepub.server.agent;

import java.util.ArrayList;
import java.util.List;

import com.gamepub.server.conversation.MessageRole;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

public class SpringAiAgentClient implements AgentClient {
    private final ChatClient chatClient;
    private final String systemPrompt;

    public SpringAiAgentClient(ChatClient chatClient, String systemPrompt) {
        this.chatClient = chatClient;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public Flux<AgentStreamChunk> stream(List<AgentMessage> messages) {
        List<Message> springMessages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            springMessages.add(new SystemMessage(systemPrompt));
        }
        for (AgentMessage message : messages) {
            springMessages.add(switch (message.role()) {
                case USER -> new UserMessage(message.content());
                case ASSISTANT -> new AssistantMessage(message.content());
            });
        }
        return chatClient.prompt().messages(springMessages).stream().chatResponse()
                .map(SpringAiAgentClient::toChunk);
    }

    private static AgentStreamChunk toChunk(org.springframework.ai.chat.model.ChatResponse response) {
        var generation = response.getResult();
        String text = generation == null || generation.getOutput() == null
                ? ""
                : generation.getOutput().getText();
        var generationMetadata = generation == null ? null : generation.getMetadata();
        String finishReason = generationMetadata == null ? null : generationMetadata.getFinishReason();
        var metadata = response.getMetadata();
        var usage = metadata == null ? null : metadata.getUsage();
        if (usage instanceof org.springframework.ai.chat.metadata.EmptyUsage) {
            usage = null;
        }
        Long inputTokenCount = usage == null ? null : valueOrNull(usage.getPromptTokens());
        Long outputTokenCount = usage == null ? null : valueOrNull(usage.getCompletionTokens());
        return new AgentStreamChunk(text, finishReason, inputTokenCount, outputTokenCount);
    }

    private static Long valueOrNull(Integer value) {
        return value == null ? null : value.longValue();
    }
}
