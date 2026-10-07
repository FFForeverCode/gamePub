package com.gamepub.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.gamepub.server.conversation.MessageRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import reactor.core.publisher.Flux;

class AgentClientContractTest {

    @Test
    void mockStreamProducesOrderedTextAndOnlyTerminalMetadata() {
        AgentClient client = new MockAgentClient();

        List<AgentStreamChunk> chunks = client.stream(List.of(
                        new AgentMessage(MessageRole.USER, "你好")))
                .collectList()
                .block();

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.stream().map(AgentStreamChunk::text).reduce("", String::concat))
                .isEqualTo("这是本地 Mock 模型的流式回答。你刚才问的是：你好\n\n当前服务已打通会话、历史记录和 SSE 流式传输。");
        assertThat(chunks.subList(0, chunks.size() - 1))
                .allSatisfy(chunk -> assertThat(chunk.finishReason()).isNull());
        assertThat(chunks.get(chunks.size() - 1).text()).isEmpty();
        assertThat(chunks.get(chunks.size() - 1).finishReason()).isEqualTo("STOP");
    }

    @Test
    void springAiStreamMapsRolesTextUsageAndFinishMetadata() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec response = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.messages(anyList())).thenReturn(request);
        when(request.stream()).thenReturn(response);
        when(response.chatResponse()).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("先")))),
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage("后"),
                                ChatGenerationMetadata.builder().finishReason("STOP").build())),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(11, 7, 18)).build())));

        List<AgentStreamChunk> chunks = new SpringAiAgentClient(chatClient, "系统")
                .stream(List.of(
                        new AgentMessage(MessageRole.USER, "问题"),
                        new AgentMessage(MessageRole.ASSISTANT, "历史回答")))
                .collectList()
                .block();

        assertThat(chunks).extracting(AgentStreamChunk::text).containsExactly("先", "后");
        assertThat(chunks.get(0).inputTokenCount()).isNull();
        assertThat(chunks.get(0).outputTokenCount()).isNull();
        assertThat(chunks.get(1).inputTokenCount()).isEqualTo(11L);
        assertThat(chunks.get(1).outputTokenCount()).isEqualTo(7L);
        assertThat(chunks.get(1).finishReason()).isEqualTo("STOP");

        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(request).messages(messages.capture());
        assertThat(messages.getValue()).extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT);
        assertThat(messages.getValue()).extracting(Message::getText)
                .containsExactly("系统", "问题", "历史回答");
    }
}
