-- =========================================================
-- V43__create_novel_narration_media_cleanup_tasks.sql
--
-- Novel Narration Media Cleanup Tasks (MS-04.9H.8D1A)
-- =========================================================

CREATE TABLE novel_narration_media_cleanup_tasks (
    id CHAR(36) NOT NULL,

    media_asset_id CHAR(36) NOT NULL,

    reason VARCHAR(50) NOT NULL,

    attempt_count INT NOT NULL DEFAULT 0,

    last_error_type VARCHAR(200) NULL,

    created_at DATETIME(6) NOT NULL,

    last_attempt_at DATETIME(6) NULL,

    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_narration_media_cleanup_tasks
        PRIMARY KEY (id),

    CONSTRAINT uq_novel_narration_media_cleanup_tasks_media_asset
        UNIQUE (media_asset_id),

    CONSTRAINT chk_novel_narration_cleanup_attempt_count
        CHECK (attempt_count >= 0),

    CONSTRAINT chk_novel_narration_cleanup_updated_at
        CHECK (updated_at >= created_at),

    CONSTRAINT chk_novel_narration_cleanup_last_attempt_at
        CHECK (last_attempt_at IS NULL OR last_attempt_at >= created_at),

    KEY idx_novel_narration_media_cleanup_tasks_created_id (
        created_at,
        id
    )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
