-- =========================================================
-- V26__create_novel_reading_progress.sql
--
-- Novel Reading Progress Persistence Foundation
-- =========================================================

CREATE TABLE novel_reading_progress (
    id CHAR(36) NOT NULL,

    user_id CHAR(36) NOT NULL,

    last_opened_chapter_id CHAR(36) NOT NULL,

    highest_reached_chapter_number INT NOT NULL,

    persistence_version BIGINT NOT NULL DEFAULT 0,

    created_at DATETIME(6) NOT NULL,

    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_reading_progress
        PRIMARY KEY (id),

    CONSTRAINT uq_novel_reading_progress_user
        UNIQUE (user_id),

    CONSTRAINT fk_novel_reading_progress_last_chapter
        FOREIGN KEY (last_opened_chapter_id)
        REFERENCES novel_chapters (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT chk_novel_reading_progress_highest_num
        CHECK (highest_reached_chapter_number >= 1),

    CONSTRAINT chk_novel_reading_progress_persistence_version
        CHECK (persistence_version >= 0)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
