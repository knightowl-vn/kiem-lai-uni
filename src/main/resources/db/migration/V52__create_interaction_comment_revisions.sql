-- =========================================================
-- V52__create_interaction_comment_revisions.sql
--
-- Interaction Comment Revisions Persistence Foundation (MS-05E5G5B)
-- Immutable public edit history snapshots for active comments.
-- =========================================================

CREATE TABLE interaction_comment_revisions (
    id CHAR(36) NOT NULL,
    comment_id CHAR(36) NOT NULL,
    revision_number INT NOT NULL,
    body TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_interaction_comment_revisions
        PRIMARY KEY (id),

    CONSTRAINT fk_interaction_comment_revisions_comment
        FOREIGN KEY (comment_id)
        REFERENCES interaction_comments (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT uq_interaction_comment_revisions_comment_number
        UNIQUE (comment_id, revision_number),

    CONSTRAINT chk_interaction_comment_revisions_number
        CHECK (revision_number >= 1),

    CONSTRAINT chk_interaction_comment_revisions_body
        CHECK (CHAR_LENGTH(TRIM(body)) > 0)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_interaction_comment_revisions_comment_number
    ON interaction_comment_revisions (comment_id, revision_number DESC, id DESC);
