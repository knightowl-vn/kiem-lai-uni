-- ====================================================================
-- V68__add_multi_admin_review_accountability_and_attribution.sql
--
-- MS-05H7.1: Multi-Admin Review Accountability, Workflow Audit Events,
-- and Wiki Edit Attribution.
-- ====================================================================

-- 1. Extend wiki_contributions with accountability and assignment metadata
ALTER TABLE wiki_contributions
    ADD COLUMN assigned_to_user_id CHAR(36) NULL AFTER resolved_article_content_version,
    ADD COLUMN assigned_at DATETIME(6) NULL AFTER assigned_to_user_id,
    ADD COLUMN review_started_by_user_id CHAR(36) NULL AFTER assigned_at,
    ADD COLUMN review_started_at DATETIME(6) NULL AFTER review_started_by_user_id,
    ADD COLUMN review_started_article_content_version BIGINT NULL AFTER review_started_at,
    ADD COLUMN resolution_outcome VARCHAR(30) NULL AFTER review_started_article_content_version;

ALTER TABLE wiki_contributions
    ADD KEY idx_wiki_contributions_assignee (assigned_to_user_id, status);

ALTER TABLE wiki_contributions
    ADD CONSTRAINT chk_wiki_contributions_resolution_outcome
        CHECK (resolution_outcome IS NULL OR resolution_outcome IN ('APPLIED', 'NO_CHANGE_NEEDED', 'DUPLICATE'));

-- 2. Extend wiki_article_revisions with optional source contribution context
ALTER TABLE wiki_article_revisions
    ADD COLUMN source_contribution_id CHAR(36) NULL AFTER edited_by;

ALTER TABLE wiki_article_revisions
    ADD KEY idx_wiki_article_revisions_source_contribution (source_contribution_id);

-- 3. Create append-only workflow audit events table
CREATE TABLE wiki_contribution_workflow_events (
    id CHAR(36) NOT NULL,
    contribution_id CHAR(36) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    actor_user_id CHAR(36) NOT NULL,
    target_user_id CHAR(36) NULL,
    from_status VARCHAR(20) NULL,
    to_status VARCHAR(20) NULL,
    article_content_version BIGINT NULL,
    resolution_outcome VARCHAR(30) NULL,
    note VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_wiki_contribution_workflow_events
        PRIMARY KEY (id),

    CONSTRAINT fk_wiki_contribution_events_contribution
        FOREIGN KEY (contribution_id)
        REFERENCES wiki_contributions (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT chk_wiki_contribution_events_type
        CHECK (event_type IN ('REVIEW_STARTED', 'CLAIMED', 'REASSIGNED', 'ARTICLE_UPDATE_LINKED', 'RESOLVED', 'REJECTED')),

    CONSTRAINT chk_wiki_contribution_events_outcome
        CHECK (resolution_outcome IS NULL OR resolution_outcome IN ('APPLIED', 'NO_CHANGE_NEEDED', 'DUPLICATE')),

    KEY idx_wiki_contribution_events_timeline (contribution_id, created_at, id)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
