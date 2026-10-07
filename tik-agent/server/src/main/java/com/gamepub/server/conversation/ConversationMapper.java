package com.gamepub.server.conversation;

import java.util.List;

import org.apache.ibatis.annotations.Param;

public interface ConversationMapper {
    int insert(Conversation conversation);
    Conversation findById(long id);
    Conversation lockForGeneration(@Param("id") long id);
    int incrementGenerationEpoch(@Param("id") long id);
    long findGenerationEpoch(@Param("id") long id);
    List<Conversation> findPage(@Param("beforeId") Long beforeId, @Param("limit") int limit);
    int rename(@Param("id") long id, @Param("title") String title, @Param("updatedAt") java.time.LocalDateTime updatedAt);
    int touch(@Param("id") long id, @Param("updatedAt") java.time.LocalDateTime updatedAt);
    int deleteById(long id);
}
