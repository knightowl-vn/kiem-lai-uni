-- =========================================================
-- V65__create_wiki_contributions.sql
--
-- Wiki Contribution Foundation (MS-05H2)
-- Independent contribution history table referencing Wiki Article
-- and Identity User scalar IDs without foreign key constraints.
-- =========================================================

CREATE TABLE wiki_contributions (
    id CHAR(36) NOT NULL,
    article_id CHAR(36) NOT NULL,
    article_type_snapshot VARCHAR(30) NOT NULL,
    article_title_snapshot VARCHAR(200) NOT NULL,
    article_slug_snapshot VARCHAR(180) NOT NULL,
    article_content_version BIGINT NOT NULL,
    submitted_by_user_id CHAR(36) NOT NULL,
    context_type VARCHAR(20) NOT NULL,
    contribution_type VARCHAR(32) NOT NULL,
    message TEXT NOT NULL,
    selected_text VARCHAR(1000) NULL,
    selected_prefix VARCHAR(100) NULL,
    selected_suffix VARCHAR(100) NULL,
    selected_heading_anchor VARCHAR(255) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_wiki_contributions
        PRIMARY KEY (id),

    CONSTRAINT chk_wiki_contributions_context_type
        CHECK (context_type IN ('GENERAL', 'TEXT_SELECTION')),

    CONSTRAINT chk_wiki_contributions_type
        CHECK (contribution_type IN ('INCORRECT_INFORMATION', 'MISSING_INFORMATION', 'OUTDATED_INFORMATION', 'WORDING', 'SOURCE_REFERENCE', 'OTHER')),

    CONSTRAINT chk_wiki_contributions_status
        CHECK (status IN ('NEW', 'REVIEWING', 'RESOLVED', 'REJECTED'))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_wiki_contributions_status_created_id
    ON wiki_contributions (status, created_at, id);

CREATE INDEX idx_wiki_contributions_article_created_id
    ON wiki_contributions (article_id, created_at, id);

CREATE INDEX idx_wiki_contributions_user_created_id
    ON wiki_contributions (submitted_by_user_id, created_at, id);
