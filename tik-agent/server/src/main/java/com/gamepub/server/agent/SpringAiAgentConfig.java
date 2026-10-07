package com.gamepub.server.agent;

import java.util.LinkedHashMap;
import java.util.Map;

import com.gamepub.server.config.TikAgentProperties;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiAgentConfig {
    @Bean
    Map<String, AgentClient> agentClients(TikAgentProperties properties) {
        Map<String, AgentClient> clients = new LinkedHashMap<>();
        properties.agent().models().forEach((id, model) -> {
            if (!model.enabled() || "mock".equalsIgnoreCase(model.provider())
                    || isBlank(model.modelName())) {
                return;
            }
            if ("gemini".equalsIgnoreCase(model.provider())) {
                if (!isBlank(model.apiKey())
                        || (!isBlank(System.getenv("GOOGLE_CLOUD_PROJECT"))
                        && !isBlank(System.getenv("GOOGLE_CLOUD_LOCATION")))) {
                    clients.put(id, GoogleGenAiAgentClient.create(model, properties.agent().systemPrompt()));
                }
                return;
            }
            if (isBlank(model.apiKey()) || isBlank(model.baseUrl())) {
                return;
            }
            var openAiClient = OpenAIOkHttpClient.builder()
                    .baseUrl(model.baseUrl())
                    .apiKey(model.apiKey())
                    .timeout(model.timeout())
                    .build();
            var options = OpenAiChatOptions.builder()
                    .model(model.modelName())
                    .temperature(model.temperature())
                    .build();
            var chatModel = OpenAiChatModel.builder()
                    .openAiClient(openAiClient)
                    .options(options)
                    .build();
            clients.put(id, new SpringAiAgentClient(ChatClient.create(chatModel), properties.agent().systemPrompt()));
        });
        return clients;
    }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
}
