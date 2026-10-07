package com.gamepub.server.conversation;

import java.time.LocalDateTime;

public class Conversation {
    private Long id;
    private String title;
    private Long generationEpoch = 0L;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Conversation() {
    }

    public Conversation(Long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getGenerationEpoch() { return generationEpoch; }
    public void setGenerationEpoch(Long generationEpoch) { this.generationEpoch = generationEpoch; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
