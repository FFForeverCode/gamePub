package com.gamepub.server.conversation;

import java.time.LocalDateTime;

public class ChatGeneration {
    private String id;
    private Long conversationId;
    private String clientRequestId;
    private Long userMessageId;
    private Long assistantMessageId;
    private String modelId;
    private GenerationStatus status;
    private Long fenceEpoch;
    private String retryOfGenerationId;
    private String errorCode;
    private Long inputTokenCount;
    private Long outputTokenCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public Long getUserMessageId() { return userMessageId; }
    public void setUserMessageId(Long userMessageId) { this.userMessageId = userMessageId; }
    public Long getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(Long assistantMessageId) { this.assistantMessageId = assistantMessageId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public GenerationStatus getStatus() { return status; }
    public void setStatus(GenerationStatus status) { this.status = status; }
    public Long getFenceEpoch() { return fenceEpoch; }
    public void setFenceEpoch(Long fenceEpoch) { this.fenceEpoch = fenceEpoch; }
    public String getRetryOfGenerationId() { return retryOfGenerationId; }
    public void setRetryOfGenerationId(String retryOfGenerationId) { this.retryOfGenerationId = retryOfGenerationId; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Long getInputTokenCount() { return inputTokenCount; }
    public void setInputTokenCount(Long inputTokenCount) { this.inputTokenCount = inputTokenCount; }
    public Long getOutputTokenCount() { return outputTokenCount; }
    public void setOutputTokenCount(Long outputTokenCount) { this.outputTokenCount = outputTokenCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
