-- =========================================================
-- V81__create_community_post_moderation_events.sql
--
-- Community Post Moderation Lifecycle Events (MS-07B8.5.2)
-- Append-only audit history of moderation actions on Community posts.
-- - No FK to Identity (boundary decoupling)
-- - Scalar post_id without foreign key cascade to ensure moderation event
--   survival even if CommunityPost is hard-deleted.
-- =========================================================

CREATE TABLE community_post_moderation_events (
    id CHAR(36) NOT NULL,
    post_id CHAR(36) NOT NULL,
    action VARCHAR(32) NOT NULL,
    from_status VARCHAR(32) NOT NULL,
    to_status VARCHAR(32) NOT NULL,
    moderator_user_id CHAR(36) NOT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_community_post_moderation_events
        PRIMARY KEY (id),

    CONSTRAINT chk_cpme_action
        CHECK (action IN ('APPROVE', 'REJECT', 'HIDE', 'RESTORE')),

    CONSTRAINT chk_cpme_from_status
        CHECK (from_status IN ('PUBLISHED', 'PENDING_REVIEW', 'HIDDEN', 'REJECTED')),

    CONSTRAINT chk_cpme_to_status
        CHECK (to_status IN ('PUBLISHED', 'PENDING_REVIEW', 'HIDDEN', 'REJECTED'))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_community_post_moderation_events_post_created
    ON community_post_moderation_events (post_id, created_at ASC);
