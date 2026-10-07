package com.gamepub.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.gamepub.server.conversation.MessageRole;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import com.gamepub.server.config.TikAgentProperties;

class GoogleGenAiAgentClientTest {

    @Test
    void streamMapsTextRolesFinishReasonAndProviderUsage() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec response = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.messages(anyList())).thenReturn(request);
        when(request.stream()).thenReturn(response);
        when(response.chatResponse()).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Gem")))),
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage("ini"),
                                ChatGenerationMetadata.builder().finishReason("STOP").build())),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(13, 5, 18)).build())));

        GoogleGenAiAgentClient client = new GoogleGenAiAgentClient(chatClient, "system prompt");
        List<AgentStreamChunk> chunks = client.stream(List.of(
                        new AgentMessage(MessageRole.USER, "question"),
                        new AgentMessage(MessageRole.ASSISTANT, "prior answer")))
                .collectList()
                .block();

        assertThat(chunks).extracting(AgentStreamChunk::text).containsExactly("Gem", "ini");
        assertThat(chunks.get(0).finishReason()).isNull();
        assertThat(chunks.get(0).inputTokenCount()).isNull();
        assertThat(chunks.get(0).outputTokenCount()).isNull();
        assertThat(chunks.get(1).finishReason()).isEqualTo("STOP");
        assertThat(chunks.get(1).inputTokenCount()).isEqualTo(13L);
        assertThat(chunks.get(1).outputTokenCount()).isEqualTo(5L);

        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(request).messages(messages.capture());
        assertThat(messages.getValue()).extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT);
        assertThat(messages.getValue()).extracting(Message::getText)
                .containsExactly("system prompt", "question", "prior answer");
    }

    @Test
    void createsDeveloperApiKeyClientWithoutMakingAProviderRequest() {
        TikAgentProperties.Model model = new TikAgentProperties.Model(
                "Gemini", "gemini", null, "test-key", "gemini-test", 0.2,
                java.time.Duration.ofSeconds(10), true);

        GoogleGenAiAgentClient client = GoogleGenAiAgentClient.create(model, "系统");

        assertThat(client).isNotNull();
    }
}
