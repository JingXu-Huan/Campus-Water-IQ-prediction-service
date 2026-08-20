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

CREATE TABLE IF NOT EXISTS agent_scheduled_task (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    conversation_id UUID NOT NULL REFERENCES agent_conversation(id),
    task_name VARCHAR(128) NOT NULL,
    cron_expression VARCHAR(128) NOT NULL,
    instruction TEXT NOT NULL,
    time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    next_run_at TIMESTAMP NOT NULL,
    last_run_at TIMESTAMP NULL,
    last_status VARCHAR(16) NULL,
    last_result TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_scheduled_task_due
    ON agent_scheduled_task (status, next_run_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_agent_scheduled_task_user
    ON agent_scheduled_task (user_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS agent_scheduled_task_execution (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES agent_scheduled_task(id),
    user_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL,
    duration_ms BIGINT NULL,
    result TEXT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_scheduled_task_execution_task
    ON agent_scheduled_task_execution (task_id, started_at DESC);
