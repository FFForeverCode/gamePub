package com.gamepub.server.conversation;

import java.time.LocalDateTime;
import java.util.List;

import com.gamepub.server.common.BusinessException;
import com.gamepub.server.common.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    public static final String DEFAULT_TITLE = "新对话";
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int AUTO_TITLE_LENGTH = 30;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ConversationService(ConversationMapper conversationMapper, MessageMapper messageMapper,
                               ApplicationEventPublisher eventPublisher) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Conversation create() {
        LocalDateTime now = LocalDateTime.now();
        Conversation conversation = new Conversation(null, DEFAULT_TITLE, now, now);
        conversationMapper.insert(conversation);
        return conversationMapper.findById(conversation.getId());
    }

    public List<Conversation> list(Long beforeId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "limit 必须在 1 到 100 之间");
        }
        return conversationMapper.findPage(beforeId, limit);
    }

    @Transactional
    public Conversation rename(long id, String title) {
        requireConversation(id);
        String normalized = normalizeTitle(title);
        conversationMapper.rename(id, normalized, LocalDateTime.now());
        return conversationMapper.findById(id);
    }

    @Transactional
    public void delete(long id) {
        requireConversation(id);
        conversationMapper.deleteById(id);
        eventPublisher.publishEvent(new ConversationDeletedEvent(id));
    }

    public List<Message> messages(long conversationId) {
        requireConversation(conversationId);
        return messageMapper.findByConversationId(conversationId);
    }

    @Transactional
    public MessagePair createMessagePair(long conversationId, String content, String modelId) {
        Conversation conversation = requireConversation(conversationId);
        LocalDateTime now = LocalDateTime.now();
        long firstSequence = messageMapper.findMaxSequenceNo(conversationId) + 1;
        Message user = new Message(null, conversationId, MessageRole.USER, content,
                MessageStatus.COMPLETED, null, firstSequence, null, now, now);
        messageMapper.insert(user);
        Message assistant = new Message(null, conversationId, MessageRole.ASSISTANT, "",
                MessageStatus.STREAMING, modelId, firstSequence + 1, null, now, now);
        messageMapper.insert(assistant);
        if (DEFAULT_TITLE.equals(conversation.getTitle())) {
            conversationMapper.rename(conversationId, titleFromFirstMessage(content), now);
        } else {
            conversationMapper.touch(conversationId, now);
        }
        return new MessagePair(user, assistant);
    }

    @Transactional
    public void completeAssistant(long messageId, String content) {
        updateAssistant(messageId, content, MessageStatus.COMPLETED, null);
    }

    @Transactional
    public void failAssistant(long messageId, String content, String errorCode) {
        updateAssistant(messageId, content, MessageStatus.FAILED, errorCode);
    }

    @Transactional
    public void cancelAssistant(long messageId, String content) {
        updateAssistant(messageId, content, MessageStatus.CANCELLED, "CANCELLED");
    }

    @Transactional
    public int failStaleStreaming(LocalDateTime cutoff) {
        return messageMapper.failStaleStreaming(cutoff, "GENERATION_INTERRUPTED", LocalDateTime.now());
    }

    public Conversation requireConversation(long id) {
        Conversation conversation = conversationMapper.findById(id);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    public static String titleFromFirstMessage(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) {
            return DEFAULT_TITLE;
        }
        return normalized.substring(0, Math.min(AUTO_TITLE_LENGTH, normalized.length()));
    }

    private static String normalizeTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "标题不能为空");
        }
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "标题不能超过 120 个字符");
        }
        return normalized;
    }

    private void updateAssistant(long messageId, String content, MessageStatus status, String errorCode) {
        Message message = messageMapper.findById(messageId);
        if (message == null || message.getRole() != MessageRole.ASSISTANT) {
            throw new BusinessException(ErrorCode.MESSAGE_NOT_FOUND, "助手消息不存在");
        }
        messageMapper.finish(messageId, content == null ? "" : content, status, errorCode, LocalDateTime.now());
        conversationMapper.touch(message.getConversationId(), LocalDateTime.now());
    }
}
