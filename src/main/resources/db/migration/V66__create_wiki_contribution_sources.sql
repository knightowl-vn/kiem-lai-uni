-- =========================================================
-- V66__create_wiki_contribution_sources.sql
--
-- Wiki Contribution Sources (MS-05H3)
-- Supporting reference links for Wiki contributions (0..5 sources).
-- Cascading foreign key to wiki_contributions table.
-- =========================================================

CREATE TABLE wiki_contribution_sources (
    id CHAR(36) NOT NULL,
    contribution_id CHAR(36) NOT NULL,
    source_order TINYINT NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    url VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_wiki_contribution_sources
        PRIMARY KEY (id),

    CONSTRAINT fk_wiki_contribution_sources_contribution
        FOREIGN KEY (contribution_id)
        REFERENCES wiki_contributions (id)
        ON DELETE CASCADE,

    CONSTRAINT uq_wiki_contribution_sources_contribution_order
        UNIQUE (contribution_id, source_order),

    CONSTRAINT chk_wiki_contribution_sources_order
        CHECK (source_order BETWEEN 0 AND 4),

    CONSTRAINT chk_wiki_contribution_sources_type
        CHECK (source_type IN ('INTERNAL', 'EXTERNAL'))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
