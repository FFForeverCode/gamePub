package com.gamepub.server.agent;

import java.util.ArrayList;
import java.util.List;

import com.gamepub.server.config.TikAgentProperties;
import com.google.genai.Client;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import reactor.core.publisher.Flux;

public final class GoogleGenAiAgentClient implements AgentClient {
    private final ChatClient chatClient;
    private final String systemPrompt;

    GoogleGenAiAgentClient(ChatClient chatClient, String systemPrompt) {
        this.chatClient = chatClient;
        this.systemPrompt = systemPrompt;
    }

    public static GoogleGenAiAgentClient create(TikAgentProperties.Model model, String systemPrompt) {
        Client.Builder clientBuilder = new Client.Builder();
        String project = System.getenv("GOOGLE_CLOUD_PROJECT");
        String location = System.getenv("GOOGLE_CLOUD_LOCATION");
        if (!isBlank(model.apiKey())) {
            clientBuilder.apiKey(model.apiKey());
        } else if (!isBlank(project) && !isBlank(location)) {
            clientBuilder.vertexAI(true).project(project).location(location);
        } else {
            throw new IllegalArgumentException(
                    "Gemini requires an API key or Vertex AI project and location environment variables");
        }
        Client client = clientBuilder.build();
        var options = GoogleGenAiChatOptions.builder()
                .temperature(model.temperature())
                .model(model.modelName())
                .build();
        var chatModel = GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(options)
                .build();
        return new GoogleGenAiAgentClient(ChatClient.create(chatModel), systemPrompt);
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
                .map(response -> {
                    var generation = response.getResult();
                    String text = generation == null || generation.getOutput() == null
                            ? "" : generation.getOutput().getText();
                    String finishReason = generation == null || generation.getMetadata() == null
                            ? null : generation.getMetadata().getFinishReason();
                    var metadata = response.getMetadata();
                    var usage = metadata == null ? null : metadata.getUsage();
                    if (usage instanceof org.springframework.ai.chat.metadata.EmptyUsage) {
                        usage = null;
                    }
                    Long inputTokens = usage == null || usage.getPromptTokens() == null
                            ? null : usage.getPromptTokens().longValue();
                    Long outputTokens = usage == null || usage.getCompletionTokens() == null
                            ? null : usage.getCompletionTokens().longValue();
                    return new AgentStreamChunk(text, finishReason, inputTokens, outputTokens);
                });
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
