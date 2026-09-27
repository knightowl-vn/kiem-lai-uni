-- =========================================================
-- V49__create_interaction_comments.sql
--
-- Interaction Comments Persistence Foundation
-- =========================================================

CREATE TABLE interaction_comments (
    id CHAR(36) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id CHAR(36) NOT NULL,
    author_user_id CHAR(36) NOT NULL,
    parent_comment_id CHAR(36) NULL,
    body TEXT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,

    CONSTRAINT pk_interaction_comments
        PRIMARY KEY (id),

    CONSTRAINT fk_interaction_comments_parent
        FOREIGN KEY (parent_comment_id)
        REFERENCES interaction_comments (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT chk_interaction_comments_target_type
        CHECK (target_type IN ('NOVEL_CHAPTER', 'WIKI_ARTICLE')),

    CONSTRAINT chk_interaction_comments_status
        CHECK (status IN ('ACTIVE', 'DELETED')),

    CONSTRAINT chk_interaction_comments_updated_at
        CHECK (updated_at >= created_at),

    CONSTRAINT chk_interaction_comments_no_self_parent
        CHECK (parent_comment_id IS NULL OR parent_comment_id <> id),

    CONSTRAINT chk_interaction_comments_active
        CHECK (status <> 'ACTIVE' OR (body IS NOT NULL AND deleted_at IS NULL)),

    CONSTRAINT chk_interaction_comments_deleted
        CHECK (status <> 'DELETED' OR (body IS NULL AND deleted_at IS NOT NULL AND updated_at = deleted_at))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_interaction_comments_target_parent_created_id
    ON interaction_comments (target_type, target_id, parent_comment_id, created_at, id);

CREATE INDEX idx_interaction_comments_parent_created_id
    ON interaction_comments (parent_comment_id, created_at, id);
