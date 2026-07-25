CREATE TABLE IF NOT EXISTS agent_conversation (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    title VARCHAR(128) NOT NULL DEFAULT '新对话',
    summary TEXT,
    summarized_message_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_user_active
    ON agent_conversation (user_id, last_active_at DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS agent_message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES agent_conversation(id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant')),
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_message_conversation
    ON agent_message (conversation_id, id);
