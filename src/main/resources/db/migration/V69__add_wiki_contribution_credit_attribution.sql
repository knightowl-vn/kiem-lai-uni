-- ====================================================================
-- V69__add_wiki_contribution_credit_attribution.sql
--
-- MS-05H8A: Credit Attribution Domain & Persistence Foundation.
-- ====================================================================

CREATE TABLE wiki_contribution_credits (
    id CHAR(36) NOT NULL,
    contribution_id CHAR(36) NOT NULL,
    credit_status VARCHAR(20) NOT NULL,
    credited_by_user_id CHAR(36) NOT NULL,
    credited_at DATETIME(6) NOT NULL,
    credit_note VARCHAR(1000) NULL,
    revoked_by_user_id CHAR(36) NULL,
    revoked_at DATETIME(6) NULL,
    revocation_reason VARCHAR(1000) NULL,
    persistence_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_wiki_contribution_credits
        PRIMARY KEY (id),

    CONSTRAINT uq_wiki_contribution_credits_contribution
        UNIQUE (contribution_id),

    CONSTRAINT fk_wiki_contribution_credits_contribution
        FOREIGN KEY (contribution_id)
        REFERENCES wiki_contributions (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT chk_wiki_contribution_credits_status
        CHECK (credit_status IN ('ACTIVE', 'REVOKED')),

    CONSTRAINT chk_wiki_contribution_credits_status_consistency
        CHECK (
            (credit_status = 'ACTIVE' AND revoked_by_user_id IS NULL AND revoked_at IS NULL AND revocation_reason IS NULL)
            OR
            (credit_status = 'REVOKED' AND revoked_by_user_id IS NOT NULL AND revoked_at IS NOT NULL AND revocation_reason IS NOT NULL)
        ),

    CONSTRAINT chk_wiki_contribution_credits_persistence_version
        CHECK (persistence_version >= 0)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
