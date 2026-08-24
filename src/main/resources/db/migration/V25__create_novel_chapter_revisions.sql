-- =========================================================
-- V25__create_novel_chapter_revisions.sql
--
-- Novel Chapter Revision History & Restore Foundation
-- =========================================================

CREATE TABLE novel_chapter_revisions (
    id CHAR(36) NOT NULL,

    chapter_id CHAR(36) NOT NULL,

    volume_id CHAR(36) NOT NULL,

    revision_number BIGINT NOT NULL,

    content_version BIGINT NOT NULL,

    chapter_number INT NOT NULL,

    title VARCHAR(250) NOT NULL,

    slug VARCHAR(180) NOT NULL,

    summary VARCHAR(1000) NOT NULL DEFAULT '',

    content MEDIUMTEXT NOT NULL,

    status VARCHAR(20) NOT NULL,

    change_type VARCHAR(30) NOT NULL,

    edit_summary VARCHAR(500) DEFAULT NULL,

    edited_by CHAR(36) NOT NULL,

    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_chapter_revisions
        PRIMARY KEY (id),

    CONSTRAINT uq_novel_chapter_revisions_chapter_number
        UNIQUE (
            chapter_id,
            revision_number
        ),

    CONSTRAINT fk_novel_chapter_revisions_chapter
        FOREIGN KEY (chapter_id)
        REFERENCES novel_chapters (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT chk_novel_chapter_revisions_revision_number
        CHECK (
            revision_number >= 1
        ),

    CONSTRAINT chk_novel_chapter_revisions_content_version
        CHECK (
            content_version >= 1
        ),

    CONSTRAINT chk_novel_chapter_revisions_chapter_number
        CHECK (
            chapter_number >= 1
        ),

    CONSTRAINT chk_novel_chapter_revisions_title_length
        CHECK (
            CHAR_LENGTH(title) BETWEEN 2 AND 250
        ),

    CONSTRAINT chk_novel_chapter_revisions_slug_length
        CHECK (
            CHAR_LENGTH(slug) BETWEEN 1 AND 180
        ),

    CONSTRAINT chk_novel_chapter_revisions_summary_length
        CHECK (
            CHAR_LENGTH(summary) <= 1000
        ),

    CONSTRAINT chk_novel_chapter_revisions_content_length
        CHECK (
            CHAR_LENGTH(content) <= 500000
        ),

    CONSTRAINT chk_novel_chapter_revisions_status
        CHECK (
            status IN (
                'DRAFT',
                'PUBLISHED',
                'ARCHIVED'
            )
        ),

    CONSTRAINT chk_novel_chapter_revisions_change_type
        CHECK (
            change_type IN (
                'BASELINE',
                'CREATE_DRAFT',
                'UPDATE_DRAFT',
                'MOVE_VOLUME',
                'PUBLISH',
                'UNPUBLISH',
                'ARCHIVE',
                'RESTORE_TO_DRAFT',
                'RESTORE_REVISION'
            )
        ),

    CONSTRAINT chk_novel_chapter_revisions_edit_summary
        CHECK (
            edit_summary IS NULL
            OR CHAR_LENGTH(edit_summary) <= 500
        ),

    KEY idx_novel_chapter_revisions_chapter (
        chapter_id
    ),

    KEY idx_novel_chapter_revisions_chapter_created (
        chapter_id,
        created_at
    ),

    KEY idx_novel_chapter_revisions_editor (
        edited_by
    )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

-- =========================================================
-- BASELINE BACKFILL FOR EXISTING CHAPTERS
-- =========================================================
INSERT INTO novel_chapter_revisions (
    id,
    chapter_id,
    volume_id,
    revision_number,
    content_version,
    chapter_number,
    title,
    slug,
    summary,
    content,
    status,
    change_type,
    edit_summary,
    edited_by,
    created_at
)
SELECT
    UUID(),
    id,
    volume_id,
    aggregate_version,
    content_version,
    chapter_number,
    title,
    slug,
    summary,
    content,
    status,
    'BASELINE',
    NULL,
    updated_by,
    updated_at
FROM novel_chapters;
