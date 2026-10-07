package com.gamepub.server.conversation;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

public interface MessageMapper {
    int insert(Message message);
    Message findById(long id);
    /** Link after creating the generation row, in the same transaction as generation creation. */
    int linkGeneration(@Param("id") long id, @Param("generationId") String generationId);
    long findMaxSequenceNo(long conversationId);
    List<Message> findByConversationId(long conversationId);
    List<Message> findRecentCompleted(@Param("conversationId") long conversationId,
                                      @Param("limit") int limit);
    int finish(@Param("id") long id, @Param("content") String content,
               @Param("status") MessageStatus status, @Param("errorCode") String errorCode,
               @Param("updatedAt") LocalDateTime updatedAt);
    /** Run in the same transaction as the matching generation transition. */
    int finishIfGenerationCurrent(@Param("id") long id, @Param("generationId") String generationId,
                                  @Param("epoch") long epoch,
                                  @Param("generationStatus") GenerationStatus generationStatus,
                                  @Param("content") String content,
                                  @Param("status") MessageStatus status,
                                  @Param("errorCode") String errorCode,
                                  @Param("updatedAt") LocalDateTime updatedAt);
    int failStaleStreaming(@Param("cutoff") LocalDateTime cutoff,
                           @Param("errorCode") String errorCode,
                           @Param("updatedAt") LocalDateTime updatedAt);
}
