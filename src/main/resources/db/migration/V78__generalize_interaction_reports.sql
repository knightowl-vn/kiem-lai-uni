-- =========================================================
-- V78__generalize_interaction_reports.sql
--
-- Interaction Generalization for Community Posts (MS-07B3)
-- - Allow COMMUNITY_POST in interaction_comments & interaction_reactions
-- - Generalize interaction_reports to support multiple target types
-- - Add target_deleted_at for decoupled evidence retention
-- =========================================================

-- 1. Extend interaction_comments target_type check constraint
ALTER TABLE interaction_comments
    DROP CHECK chk_interaction_comments_target_type;

ALTER TABLE interaction_comments
    ADD CONSTRAINT chk_interaction_comments_target_type
    CHECK (target_type IN ('NOVEL_CHAPTER', 'WIKI_ARTICLE', 'COMMUNITY_POST'));

-- 2. Extend interaction_reactions target_type check constraint
ALTER TABLE interaction_reactions
    DROP CHECK chk_interaction_reactions_target_type;

ALTER TABLE interaction_reactions
    ADD CONSTRAINT chk_interaction_reactions_target_type
    CHECK (target_type IN ('NOVEL_CHAPTER', 'COMMENT', 'DONGHUA_EPISODE', 'COMMUNITY_POST'));

-- 3. Add generalized columns and target_deleted_at to interaction_reports
ALTER TABLE interaction_reports
    ADD COLUMN target_type VARCHAR(40) NULL AFTER id,
    ADD COLUMN target_id CHAR(36) NULL AFTER target_type,
    ADD COLUMN content_snapshot TEXT NULL AFTER description,
    ADD COLUMN target_deleted_at DATETIME(6) NULL AFTER resolved_at;

-- 4. Backfill existing comment reports
UPDATE interaction_reports
    SET target_type = 'COMMENT',
        target_id = comment_id,
        content_snapshot = reported_body_snapshot
    WHERE target_type IS NULL;

-- 5. Enforce NOT NULL on new required columns
ALTER TABLE interaction_reports
    MODIFY COLUMN target_type VARCHAR(40) NOT NULL,
    MODIFY COLUMN target_id CHAR(36) NOT NULL,
    MODIFY COLUMN content_snapshot TEXT NOT NULL;

-- 6. Drop legacy index, constraint, and check constraints
DROP INDEX idx_interaction_reports_comment_id ON interaction_reports;

ALTER TABLE interaction_reports
    DROP INDEX uq_interaction_reports_pending_reporter;

ALTER TABLE interaction_reports
    DROP CHECK chk_interaction_reports_snapshot;

ALTER TABLE interaction_reports
    DROP CHECK chk_interaction_reports_moderation_action;

-- 7. Drop legacy columns
ALTER TABLE interaction_reports
    DROP COLUMN comment_id,
    DROP COLUMN reported_body_snapshot;

-- 8. Add generalized pending uniqueness constraint
ALTER TABLE interaction_reports
    ADD CONSTRAINT uq_interaction_reports_pending_target_reporter
    UNIQUE (target_type, target_id, reporter_user_id, pending_slot);

-- 9. Add updated check constraints
ALTER TABLE interaction_reports
    ADD CONSTRAINT chk_interaction_reports_target_type
    CHECK (target_type IN ('COMMENT', 'COMMUNITY_POST'));

ALTER TABLE interaction_reports
    ADD CONSTRAINT chk_interaction_reports_content_snapshot
    CHECK (CHAR_LENGTH(TRIM(content_snapshot)) > 0);

ALTER TABLE interaction_reports
    ADD CONSTRAINT chk_interaction_reports_moderation_action
    CHECK (
        (status = 'PENDING' AND moderation_action IS NULL)
        OR
        (status = 'RESOLVED_ACTION_TAKEN' AND moderation_action IS NOT NULL AND moderation_action = 'DELETE_COMMENT')
        OR
        (status = 'RESOLVED_NO_ACTION' AND moderation_action IS NOT NULL AND moderation_action = 'NO_ACTION')
    );

-- 10. Add indexes for target querying and retention scheduler
CREATE INDEX idx_interaction_reports_target
    ON interaction_reports (target_type, target_id, created_at DESC, id DESC);

CREATE INDEX idx_interaction_reports_target_deleted_at
    ON interaction_reports (target_deleted_at, id);

-- 11. Add composite index for deterministic lock ordering and covering ID sweeps
CREATE INDEX idx_interaction_comments_target_id
    ON interaction_comments (target_type, target_id, id);
