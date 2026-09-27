-- =========================================================
-- V51__create_novel_chapter_comment_anchors.sql
--
-- Novel Chapter Comment Anchors Persistence Foundation (MS-05E5C)
-- Immutable Reader anchor evidence for inline discussion threads.
-- =========================================================

CREATE TABLE novel_chapter_comment_anchors (
    root_comment_id CHAR(36) NOT NULL,

    chapter_id CHAR(36) NOT NULL,

    content_version BIGINT NOT NULL,

    block_key VARCHAR(100) NOT NULL,

    anchor_kind VARCHAR(20) NOT NULL,

    start_offset INT NULL,

    end_offset INT NULL,

    selected_text MEDIUMTEXT NOT NULL,

    context_before VARCHAR(64) NOT NULL DEFAULT '',

    context_after VARCHAR(64) NOT NULL DEFAULT '',

    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_chapter_comment_anchors
        PRIMARY KEY (root_comment_id),

    CONSTRAINT fk_novel_chapter_comment_anchors_chapter
        FOREIGN KEY (chapter_id)
        REFERENCES novel_chapters (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT chk_novel_chapter_comment_anchors_version
        CHECK (content_version >= 1),

    CONSTRAINT chk_novel_chapter_comment_anchors_block_key
        CHECK (CHAR_LENGTH(TRIM(block_key)) >= 5 AND block_key LIKE 'blk-%'),

    CONSTRAINT chk_novel_chapter_comment_anchors_kind
        CHECK (anchor_kind IN ('BLOCK', 'TEXT_RANGE')),

    CONSTRAINT chk_novel_chapter_comment_anchors_selected_text
        CHECK (CHAR_LENGTH(selected_text) > 0),

    CONSTRAINT chk_novel_chapter_comment_anchors_shape
        CHECK (
            (anchor_kind = 'BLOCK' AND start_offset IS NULL AND end_offset IS NULL AND context_before = '' AND context_after = '')
            OR
            (anchor_kind = 'TEXT_RANGE' AND start_offset IS NOT NULL AND start_offset >= 0 AND end_offset IS NOT NULL AND end_offset > start_offset)
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_novel_chapter_comment_anchors_chapter
    ON novel_chapter_comment_anchors (chapter_id, content_version);
