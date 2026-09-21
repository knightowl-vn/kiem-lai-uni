-- =========================================================
-- V54__add_report_moderation_action.sql
--
-- Persist exact report moderation action (MS-05E8G-4A1)
-- =========================================================

-- 1. Add nullable moderation_action column
ALTER TABLE interaction_reports
    ADD COLUMN moderation_action VARCHAR(40) NULL AFTER status;

-- 2. Backfill existing terminal rows
UPDATE interaction_reports
    SET moderation_action = 'DELETE_COMMENT'
    WHERE status = 'RESOLVED_ACTION_TAKEN' AND moderation_action IS NULL;

UPDATE interaction_reports
    SET moderation_action = 'NO_ACTION'
    WHERE status = 'RESOLVED_NO_ACTION' AND moderation_action IS NULL;

-- 3. Add CHECK constraint enforcing state invariants
ALTER TABLE interaction_reports
    ADD CONSTRAINT chk_interaction_reports_moderation_action
    CHECK (
        (
            status = 'PENDING'
            AND moderation_action IS NULL
        )
        OR
        (
            status = 'RESOLVED_ACTION_TAKEN'
            AND moderation_action IS NOT NULL
            AND moderation_action = 'DELETE_COMMENT'
        )
        OR
        (
            status = 'RESOLVED_NO_ACTION'
            AND moderation_action IS NOT NULL
            AND moderation_action = 'NO_ACTION'
        )
    );
