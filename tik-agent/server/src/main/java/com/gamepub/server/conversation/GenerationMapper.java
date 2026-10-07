package com.gamepub.server.conversation;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

public interface GenerationMapper {
    int create(ChatGeneration generation);
    ChatGeneration findById(@Param("id") String id);
    ChatGeneration findByClientRequestId(@Param("conversationId") long conversationId,
                                         @Param("clientRequestId") String clientRequestId);
    /** Must run in a transaction after ConversationMapper.lockForGeneration for the same conversation. */
    ChatGeneration findActiveByConversationForUpdate(@Param("conversationId") long conversationId);
    int transitionIfCurrent(@Param("id") String id, @Param("epoch") long epoch,
                            @Param("expectedStatus") GenerationStatus expectedStatus,
                            @Param("newStatus") GenerationStatus newStatus,
                            @Param("errorCode") String errorCode,
                            @Param("inputTokenCount") Long inputTokenCount,
                            @Param("outputTokenCount") Long outputTokenCount,
                            @Param("updatedAt") LocalDateTime updatedAt);
    List<ChatGeneration> findStaleRunning(@Param("cutoff") LocalDateTime cutoff,
                                          @Param("limit") int limit);
}
