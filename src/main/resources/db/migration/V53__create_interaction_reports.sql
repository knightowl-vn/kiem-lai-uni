-- =========================================================
-- V53__create_interaction_reports.sql
--
-- Interaction Reports Persistence Foundation (MS-05E8A)
-- Authoritative reporting mechanism for interaction comments.
-- =========================================================

CREATE TABLE interaction_reports (
    id CHAR(36) NOT NULL,
    comment_id CHAR(36) NOT NULL,
    reporter_user_id CHAR(36) NOT NULL,
    reason VARCHAR(40) NOT NULL,
    description VARCHAR(500) NULL,
    reported_body_snapshot TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    resolved_by_user_id CHAR(36) NULL,
    resolved_at DATETIME(6) NULL,
    pending_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN 1 ELSE NULL END) STORED,

    CONSTRAINT pk_interaction_reports
        PRIMARY KEY (id),

    CONSTRAINT fk_interaction_reports_comment
        FOREIGN KEY (comment_id)
        REFERENCES interaction_comments (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT uq_interaction_reports_pending_reporter
        UNIQUE (comment_id, reporter_user_id, pending_slot),

    CONSTRAINT chk_interaction_reports_reason
        CHECK (reason IN ('SPAM', 'HARASSMENT', 'HATE_SPEECH', 'SEXUAL_OR_OBSCENE', 'SPOILER', 'OTHER')),

    CONSTRAINT chk_interaction_reports_status
        CHECK (status IN ('PENDING', 'RESOLVED_ACTION_TAKEN', 'RESOLVED_NO_ACTION')),

    CONSTRAINT chk_interaction_reports_snapshot
        CHECK (CHAR_LENGTH(TRIM(reported_body_snapshot)) > 0),

    CONSTRAINT chk_interaction_reports_other_desc
        CHECK (reason != 'OTHER' OR (description IS NOT NULL AND CHAR_LENGTH(TRIM(description)) > 0)),

    CONSTRAINT chk_interaction_reports_resolution
        CHECK (
            (status != 'PENDING' OR (resolved_by_user_id IS NULL AND resolved_at IS NULL))
            AND
            (status NOT IN ('RESOLVED_ACTION_TAKEN', 'RESOLVED_NO_ACTION')
                OR (resolved_by_user_id IS NOT NULL
                    AND resolved_at IS NOT NULL
                    AND resolved_at >= created_at))
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_interaction_reports_status_created_id
    ON interaction_reports (status, created_at DESC, id DESC);

CREATE INDEX idx_interaction_reports_comment_id
    ON interaction_reports (comment_id, created_at DESC, id DESC);
