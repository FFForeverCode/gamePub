package com.gamepub.server.chat;

import java.util.ArrayList;
import java.util.List;

import com.gamepub.server.agent.AgentClient;
import com.gamepub.server.agent.AgentMessage;
import com.gamepub.server.agent.ModelCatalog;
import com.gamepub.server.common.BusinessException;
import com.gamepub.server.common.ErrorCode;
import com.gamepub.server.config.TikAgentProperties;
import com.gamepub.server.conversation.ConversationService;
import com.gamepub.server.conversation.Message;
import com.gamepub.server.conversation.MessagePair;
import com.gamepub.server.conversation.MessageStatus;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private final ConversationService conversationService;
    private final ModelCatalog modelCatalog;
    private final ShortTermMemory memory;
    private final TikAgentProperties properties;

    public ChatService(ConversationService conversationService, ModelCatalog modelCatalog,
                       ShortTermMemory memory, TikAgentProperties properties) {
        this.conversationService = conversationService;
        this.modelCatalog = modelCatalog;
        this.memory = memory;
        this.properties = properties;
    }

    public ChatPreparation prepare(long conversationId, ChatCommand command) {
        validateContent(command.content());
        conversationService.requireConversation(conversationId);
        String modelId = command.modelId() == null ? properties.agent().defaultModel() : command.modelId();
        AgentClient client = modelCatalog.requireClient(modelId);
        List<AgentMessage> context = new ArrayList<>(memory.get(conversationId));
        MessagePair pair = conversationService.createMessagePair(conversationId, command.content(), modelId);
        context.add(new AgentMessage(pair.userMessage().getRole(), pair.userMessage().getContent()));
        return new ChatPreparation(command, modelId, client, pair, List.copyOf(context));
    }

    public void complete(long conversationId, Message assistant, String content) {
        conversationService.completeAssistant(assistant.getId(), content);
        refreshMemory(conversationId);
    }

    public void fail(Message assistant, String content, String errorCode) {
        conversationService.failAssistant(assistant.getId(), content, errorCode);
    }

    public void cancel(Message assistant, String content) {
        conversationService.cancelAssistant(assistant.getId(), content);
    }

    private void refreshMemory(long conversationId) {
        List<Message> messages = conversationService.messages(conversationId).stream()
                .filter(message -> message.getStatus() == MessageStatus.COMPLETED)
                .toList();
        memory.replace(conversationId, messages.stream()
                .map(message -> new AgentMessage(message.getRole(), message.getContent()))
                .toList());
    }

    private void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "消息内容不能为空");
        }
        if (content.length() > properties.chat().maxContentLength()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "消息内容不能超过 " + properties.chat().maxContentLength() + " 个字符");
        }
    }
}
