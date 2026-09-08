-- =========================================================
-- V44__create_novel_chapter_narration_manifests.sql
--
-- Novel Chapter Published Narration Manifest (MS-04.9H.8E1)
-- =========================================================

CREATE TABLE novel_chapter_narration_manifests (
    chapter_id CHAR(36) NOT NULL,

    source_content_version BIGINT NOT NULL,

    manifest_hash VARCHAR(64) NOT NULL,

    created_at DATETIME(6) NOT NULL,

    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_chapter_narration_manifests
        PRIMARY KEY (chapter_id),

    CONSTRAINT fk_novel_chapter_narration_manifests_chapter
        FOREIGN KEY (chapter_id)
        REFERENCES novel_chapters (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT chk_novel_chapter_narration_manifests_source_ver
        CHECK (source_content_version >= 1),

    CONSTRAINT chk_novel_chapter_narration_manifests_hash_len
        CHECK (CHAR_LENGTH(manifest_hash) = 64),

    CONSTRAINT chk_novel_chapter_narration_manifests_updated_at
        CHECK (updated_at >= created_at)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
