-- =========================================================
-- V28__create_novel_reading_history.sql
--
-- Novel Reading History Persistence Foundation
-- =========================================================

CREATE TABLE novel_reading_history (
    id CHAR(36) NOT NULL,

    user_id CHAR(36) NOT NULL,

    chapter_id CHAR(36) NOT NULL,

    first_read_at DATETIME(6) NOT NULL,

    last_read_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_reading_history
        PRIMARY KEY (id),

    CONSTRAINT uq_novel_reading_history_user_chapter
        UNIQUE (user_id, chapter_id),

    CONSTRAINT fk_novel_reading_history_chapter
        FOREIGN KEY (chapter_id)
        REFERENCES novel_chapters (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_novel_reading_history_user_time
    ON novel_reading_history (user_id, last_read_at DESC);
