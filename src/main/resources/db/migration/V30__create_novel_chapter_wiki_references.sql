-- =========================================================
-- V30__create_novel_chapter_wiki_references.sql
--
-- Novel Chapter Wiki References Persistence Foundation
-- =========================================================

CREATE TABLE novel_chapter_wiki_references (
    id CHAR(36) NOT NULL,

    chapter_id CHAR(36) NOT NULL,

    term VARCHAR(100) NOT NULL,

    normalized_term VARCHAR(100) NOT NULL,

    reference_scope VARCHAR(30) NOT NULL,

    occurrence_index INT NOT NULL,

    context_snippet VARCHAR(255) NULL,

    bound_content_version BIGINT NULL,

    wiki_article_id CHAR(36) NOT NULL,

    created_by CHAR(36) NOT NULL,

    updated_by CHAR(36) NOT NULL,

    created_at DATETIME(6) NOT NULL,

    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_chapter_wiki_references
        PRIMARY KEY (id),

    CONSTRAINT fk_novel_chapter_wiki_references_chapter
        FOREIGN KEY (chapter_id)
        REFERENCES novel_chapters (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT uq_novel_chapter_wiki_ref_chapter_term_occ
        UNIQUE (chapter_id, normalized_term, occurrence_index),

    CONSTRAINT chk_novel_chapter_wiki_ref_scope_valid
        CHECK (
            reference_scope IN ('CHAPTER_WIDE', 'OCCURRENCE_SPECIFIC')
        ),

    CONSTRAINT chk_novel_chapter_wiki_ref_scope_rules
        CHECK (
            (reference_scope = 'CHAPTER_WIDE' AND occurrence_index = 0 AND bound_content_version IS NULL)
            OR
            (reference_scope = 'OCCURRENCE_SPECIFIC' AND occurrence_index >= 1 AND bound_content_version IS NOT NULL AND bound_content_version >= 1)
        ),

    CONSTRAINT chk_novel_chapter_wiki_ref_term_length
        CHECK (
            CHAR_LENGTH(TRIM(term)) >= 1 AND CHAR_LENGTH(TRIM(term)) <= 100
        ),

    CONSTRAINT chk_novel_chapter_wiki_ref_norm_term_length
        CHECK (
            CHAR_LENGTH(TRIM(normalized_term)) >= 1 AND CHAR_LENGTH(TRIM(normalized_term)) <= 100
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
