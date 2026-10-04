-- V82: Allow CONTENT_HIDDEN as valid moderation_action on interaction_reports for COMMUNITY_POST targets

ALTER TABLE interaction_reports
    DROP CHECK chk_interaction_reports_moderation_action;

ALTER TABLE interaction_reports
    ADD CONSTRAINT chk_interaction_reports_moderation_action
    CHECK (
        (status = 'PENDING' AND moderation_action IS NULL)
        OR
        (status = 'RESOLVED_ACTION_TAKEN' AND moderation_action IS NOT NULL AND moderation_action IN ('DELETE_COMMENT', 'CONTENT_HIDDEN'))
        OR
        (status = 'RESOLVED_NO_ACTION' AND moderation_action IS NOT NULL AND moderation_action = 'NO_ACTION')
    );
