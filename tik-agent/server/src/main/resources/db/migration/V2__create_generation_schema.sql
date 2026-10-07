CREATE TABLE chat_generations (
    id VARCHAR(36) NOT NULL,
    conversation_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    user_message_id BIGINT NULL,
    assistant_message_id BIGINT NULL,
    model_id VARCHAR(100) NOT NULL,
    status VARCHAR(24) NOT NULL,
    fence_epoch BIGINT NOT NULL,
    retry_of_generation_id VARCHAR(36) NULL,
    error_code VARCHAR(64) NULL,
    input_token_count BIGINT NULL,
    output_token_count BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_generations_request (conversation_id, client_request_id),
    UNIQUE KEY uk_chat_generations_assistant_message (assistant_message_id),
    KEY idx_chat_generations_status_updated (conversation_id, status, updated_at),
    KEY idx_chat_generations_retry_of (retry_of_generation_id),
    CONSTRAINT fk_chat_generations_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_generations_user_message FOREIGN KEY (user_message_id)
        REFERENCES messages (id) ON DELETE SET NULL,
    CONSTRAINT fk_chat_generations_assistant_message FOREIGN KEY (assistant_message_id)
        REFERENCES messages (id) ON DELETE SET NULL,
    CONSTRAINT fk_chat_generations_retry_of FOREIGN KEY (retry_of_generation_id)
        REFERENCES chat_generations (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE conversations
    ADD COLUMN generation_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE messages
    ADD COLUMN generation_id VARCHAR(36) NULL,
    ADD KEY idx_messages_generation (generation_id),
    ADD CONSTRAINT fk_messages_generation FOREIGN KEY (generation_id)
        REFERENCES chat_generations (id) ON DELETE SET NULL;
