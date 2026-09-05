-- =========================================================
-- V39__create_novel_chapter_narration_segments.sql
--
-- Novel Chapter Narration Segment Manifest Persistence
-- =========================================================

CREATE TABLE novel_chapter_narration_segments (
    id CHAR(36) NOT NULL,

    chapter_id CHAR(36) NOT NULL,

    segment_index INT NOT NULL,

    text TEXT NOT NULL,

    character_count INT NOT NULL,

    content_hash VARCHAR(64) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'CURRENT',

    created_at DATETIME(6) NOT NULL,

    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_chapter_narration_segments
        PRIMARY KEY (id),

    CONSTRAINT fk_novel_chapter_narration_segments_chapter
        FOREIGN KEY (chapter_id)
        REFERENCES novel_chapters (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT chk_novel_chapter_narration_segments_index
        CHECK (segment_index >= 0),

    CONSTRAINT chk_novel_chapter_narration_segments_char_count
        CHECK (character_count >= 1),

    CONSTRAINT chk_novel_chapter_narration_segments_hash_len
        CHECK (CHAR_LENGTH(content_hash) = 64),

    CONSTRAINT chk_novel_chapter_narration_segments_status
        CHECK (status IN ('CURRENT', 'RETIRED')),

    KEY idx_novel_chapter_narration_segments_chapter (
        chapter_id
    ),

    KEY idx_novel_chapter_narration_segments_chapter_status_idx (
        chapter_id,
        status,
        segment_index
    ),

    KEY idx_novel_chapter_narration_segments_chapter_hash (
        chapter_id,
        content_hash
    )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
