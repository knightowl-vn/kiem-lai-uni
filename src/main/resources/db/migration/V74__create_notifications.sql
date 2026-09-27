-- =========================================================
-- V74__create_notifications.sql
--
-- In-App Notification Foundation (MS-05K1B)
-- Persists recipient notification events, read states, and dedupe keys
-- without cross-context foreign keys.
-- =========================================================

CREATE TABLE notifications (
    id CHAR(36) NOT NULL,
    recipient_user_id CHAR(36) NOT NULL,
    type VARCHAR(40) NOT NULL,
    actor_user_id CHAR(36) NULL,
    actor_display_name_snapshot VARCHAR(100) NULL,
    target_type VARCHAR(40) NULL,
    target_id CHAR(36) NULL,
    target_title_snapshot VARCHAR(255) NULL,
    comment_id CHAR(36) NULL,
    thread_root_id CHAR(36) NULL,
    detail_snapshot VARCHAR(2000) NULL,
    dedupe_key VARCHAR(191) NOT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_notifications
        PRIMARY KEY (id),

    CONSTRAINT uq_notifications_dedupe_key
        UNIQUE (dedupe_key),

    CONSTRAINT chk_notifications_type
        CHECK (type IN (
            'COMMENT_REPLY',
            'WIKI_CONTRIBUTION_REVIEWING',
            'WIKI_CONTRIBUTION_RESOLVED',
            'WIKI_CONTRIBUTION_REJECTED'
        ))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_notifications_recipient_created
    ON notifications (recipient_user_id, created_at, id);

CREATE INDEX idx_notifications_recipient_read_created
    ON notifications (recipient_user_id, read_at, created_at, id);
