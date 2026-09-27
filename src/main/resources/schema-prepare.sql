-- schema-prepare.sql
-- 首次启动时由 spring.sql.init.mode=always 自动执行;后续启动 ddl-auto=validate 仅校验 schema。
-- SQLite 方言:没有严格的 BIGINT/BOOLEAN 类型,统一 INTEGER(64-bit)/TEXT/0-1。

CREATE TABLE IF NOT EXISTS conversations (
    id             VARCHAR(36)  PRIMARY KEY,
    title          VARCHAR(255),
    created_at     TIMESTAMP    NOT NULL,
    last_active_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS messages (
    id              INTEGER      PRIMARY KEY AUTOINCREMENT,
    conversation_id VARCHAR(36)  NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    content         TEXT,
    meta            TEXT,
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id)
        REFERENCES conversations(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_messages_conv_created
    ON messages(conversation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_messages_meta
    ON messages(conversation_id, role);

CREATE TABLE IF NOT EXISTS prompt_template_versions (
    id            VARCHAR(64)  PRIMARY KEY,
    template_name VARCHAR(64)  NOT NULL,
    version       VARCHAR(32)  NOT NULL,
    content       TEXT         NOT NULL,
    description   VARCHAR(512),
    created_at    TIMESTAMP    NOT NULL,
    active        INTEGER      NOT NULL DEFAULT 1,
    lock_version  INTEGER      NOT NULL DEFAULT 0
);

-- 当前激活版本唯一(部分索引,SQLite 3.8+ 支持)
CREATE UNIQUE INDEX IF NOT EXISTS uk_prompt_active
    ON prompt_template_versions(template_name)
    WHERE active = 1;

-- Agent 可观测 trace 日志(ProcessLogCollector 单点写入,保留 7 天)
CREATE TABLE IF NOT EXISTS trace_logs (
    id          INTEGER      PRIMARY KEY AUTOINCREMENT,
    session_id  VARCHAR(64)  NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    agent       VARCHAR(64),
    message     TEXT,
    tool_name   VARCHAR(128),
    tool_args   TEXT,
    tool_result TEXT,
    duration_ms INTEGER,
    status      VARCHAR(20),
    created_at  TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trace_logs_session
    ON trace_logs(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_trace_logs_created
    ON trace_logs(created_at);