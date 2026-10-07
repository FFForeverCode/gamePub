package com.gamepub.server.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamepub.server.support.MySqlIntegrationTest;

@SpringBootTest
class GenerationPersistenceTest extends MySqlIntegrationTest {

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private GenerationMapper generationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void migratesGenerationSchemaAndKeepsExistingMessagesUnlinked() {
        Conversation conversation = insertConversation();
        Message legacyMessage = insertMessage(conversation.getId(), null);

        assertThat(conversationMapper.findById(conversation.getId()).getGenerationEpoch()).isZero();
        assertThat(messageMapper.findById(legacyMessage.getId()).getGenerationId()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'chat_generations'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void enforcesRequestIdUniquenessAndFindsTheOriginalGeneration() {
        Conversation conversation = insertConversation();
        String requestId = UUID.randomUUID().toString();
        ChatGeneration original = generation(conversation.getId(), requestId, 1L);
        generationMapper.create(original);

        ChatGeneration found = generationMapper.findByClientRequestId(conversation.getId(), requestId);
        assertThat(found.getId()).isEqualTo(original.getId());
        assertThat(found.getFenceEpoch()).isEqualTo(1L);
        assertThat(found.getStatus()).isEqualTo(GenerationStatus.CREATED);
        assertThatThrownBy(() -> generationMapper.create(generation(conversation.getId(), requestId, 2L)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void linksExistingMessagesToTheirGenerationAfterCreatingTheGenerationRow() {
        Conversation conversation = insertConversation();
        Message user = insertMessage(conversation.getId(), null);
        Message assistant = new Message(null, conversation.getId(), MessageRole.ASSISTANT, "",
                MessageStatus.STREAMING, "mock", 2L, null, LocalDateTime.now(), LocalDateTime.now());
        messageMapper.insert(assistant);
        ChatGeneration generation = generation(conversation.getId(), UUID.randomUUID().toString(), 1L);
        generation.setUserMessageId(user.getId());
        generation.setAssistantMessageId(assistant.getId());
        generationMapper.create(generation);

        assertThat(messageMapper.linkGeneration(user.getId(), generation.getId())).isEqualTo(1);
        assertThat(messageMapper.linkGeneration(assistant.getId(), generation.getId())).isEqualTo(1);
        assertThat(messageMapper.findById(user.getId()).getGenerationId()).isEqualTo(generation.getId());
        assertThat(messageMapper.findById(assistant.getId()).getGenerationId()).isEqualTo(generation.getId());
        assertThat(generationMapper.findById(generation.getId()).getAssistantMessageId()).isEqualTo(assistant.getId());
    }

    @Test
    void rejectsTerminalTransitionWhenEpochOrExpectedStatusIsStale() {
        Conversation conversation = insertConversation();
        ChatGeneration generation = generation(conversation.getId(), UUID.randomUUID().toString(), 7L);
        generationMapper.create(generation);
        Message assistant = new Message(null, conversation.getId(), MessageRole.ASSISTANT, "partial",
                MessageStatus.STREAMING, "mock", 1L, null, LocalDateTime.now(), LocalDateTime.now());
        messageMapper.insert(assistant);
        messageMapper.linkGeneration(assistant.getId(), generation.getId());

        assertThat(generationMapper.transitionIfCurrent(generation.getId(), 6L,
                GenerationStatus.RUNNING, GenerationStatus.COMPLETED,
                null, 12L, 34L, LocalDateTime.now())).isZero();
        assertThat(generationMapper.transitionIfCurrent(generation.getId(), 7L,
                GenerationStatus.RUNNING, GenerationStatus.COMPLETED,
                null, 12L, 34L, LocalDateTime.now())).isZero();

        assertThat(generationMapper.transitionIfCurrent(generation.getId(), 7L,
                GenerationStatus.CREATED, GenerationStatus.RUNNING,
                null, null, null, LocalDateTime.now())).isEqualTo(1);
        assertThat(generationMapper.transitionIfCurrent(generation.getId(), 7L,
                GenerationStatus.CREATED, GenerationStatus.FAILED,
                "MODEL_ERROR", null, null, LocalDateTime.now())).isZero();
        assertThat(messageMapper.finishIfGenerationCurrent(assistant.getId(), generation.getId(), 6L,
                GenerationStatus.COMPLETED, "answer", MessageStatus.COMPLETED, null,
                LocalDateTime.now())).isZero();
        assertThat(generationMapper.findById(generation.getId()).getStatus()).isEqualTo(GenerationStatus.RUNNING);
    }

    @Test
    void locksConversationBeforeCheckingActivityAndAllocatesMonotonicEpoch() {
        Conversation conversation = insertConversation();
        ChatGeneration active = generation(conversation.getId(), UUID.randomUUID().toString(), 1L);
        generationMapper.create(active);

        GenerationClaim claim = transactionTemplate.execute(status -> {
            Conversation locked = conversationMapper.lockForGeneration(conversation.getId());
            assertThat(locked).isNotNull();
            assertThat(generationMapper.findActiveByConversationForUpdate(conversation.getId()).getId())
                    .isEqualTo(active.getId());
            assertThat(conversationMapper.incrementGenerationEpoch(conversation.getId())).isEqualTo(1);
            assertThat(conversationMapper.findGenerationEpoch(conversation.getId())).isEqualTo(1);
            return new GenerationClaim(locked.getId(), conversationMapper.findGenerationEpoch(locked.getId()));
        });

        assertThat(claim).isEqualTo(new GenerationClaim(conversation.getId(), 1L));
        assertThat(conversationMapper.findById(conversation.getId()).getGenerationEpoch()).isEqualTo(1L);
    }

    @Test
    void rollsBackGenerationAndAssistantMessageTerminalWritesTogether() {
        Conversation conversation = insertConversation();
        ChatGeneration generation = generation(conversation.getId(), UUID.randomUUID().toString(), 1L);
        generationMapper.create(generation);
        Message assistant = new Message(null, conversation.getId(), MessageRole.ASSISTANT, "partial",
                MessageStatus.STREAMING, "mock", 1L, null, LocalDateTime.now(), LocalDateTime.now());
        assistant.setGenerationId(generation.getId());
        messageMapper.insert(assistant);

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            assertThat(generationMapper.transitionIfCurrent(generation.getId(), 1L,
                    GenerationStatus.CREATED, GenerationStatus.COMPLETED,
                    null, 10L, 20L, LocalDateTime.now())).isEqualTo(1);
            assertThat(messageMapper.finishIfGenerationCurrent(assistant.getId(), generation.getId(), 1L,
                    GenerationStatus.COMPLETED, "answer", MessageStatus.COMPLETED,
                    null, LocalDateTime.now())).isEqualTo(1);
            throw new IllegalStateException("force transaction rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force transaction rollback");

        assertThat(generationMapper.findById(generation.getId()).getStatus()).isEqualTo(GenerationStatus.CREATED);
        assertThat(messageMapper.findById(assistant.getId()).getStatus()).isEqualTo(MessageStatus.STREAMING);
    }

    @Test
    void listsOnlyRunningGenerationsOlderThanCutoff() {
        Conversation conversation = insertConversation();
        ChatGeneration stale = generation(conversation.getId(), UUID.randomUUID().toString(), 3L);
        stale.setStatus(GenerationStatus.RUNNING);
        stale.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        generationMapper.create(stale);
        ChatGeneration current = generation(conversation.getId(), UUID.randomUUID().toString(), 4L);
        current.setStatus(GenerationStatus.RUNNING);
        current.setUpdatedAt(LocalDateTime.now());
        generationMapper.create(current);

        assertThat(generationMapper.findStaleRunning(LocalDateTime.now().minusMinutes(5), 10))
                .extracting(ChatGeneration::getId).containsExactly(stale.getId());
    }

    private Conversation insertConversation() {
        LocalDateTime now = LocalDateTime.now();
        Conversation conversation = new Conversation(null, "generation test", now, now);
        conversationMapper.insert(conversation);
        return conversation;
    }

    private Message insertMessage(long conversationId, String generationId) {
        LocalDateTime now = LocalDateTime.now();
        Message message = new Message(null, conversationId, MessageRole.USER, "legacy", MessageStatus.COMPLETED,
                null, 1L, null, now, now);
        message.setGenerationId(generationId);
        messageMapper.insert(message);
        return message;
    }

    private ChatGeneration generation(long conversationId, String requestId, long epoch) {
        LocalDateTime now = LocalDateTime.now();
        ChatGeneration generation = new ChatGeneration();
        generation.setId(UUID.randomUUID().toString());
        generation.setConversationId(conversationId);
        generation.setClientRequestId(requestId);
        generation.setModelId("mock");
        generation.setStatus(GenerationStatus.CREATED);
        generation.setFenceEpoch(epoch);
        generation.setCreatedAt(now);
        generation.setUpdatedAt(now);
        return generation;
    }

    private record GenerationClaim(long conversationId, long epoch) { }
}
