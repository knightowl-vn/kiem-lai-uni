-- =========================================================
-- V70__create_interaction_reactions.sql
--
-- Content Reactions Persistence Foundation (MS-05I2)
-- Authoritative emotional reaction mechanism for supported content.
-- =========================================================

CREATE TABLE interaction_reactions (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id CHAR(36) NOT NULL,
    reaction_type VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_interaction_reactions
        PRIMARY KEY (id),

    CONSTRAINT uq_interaction_reactions_user_target
        UNIQUE (user_id, target_type, target_id),

    CONSTRAINT chk_interaction_reactions_target_type
        CHECK (target_type IN ('NOVEL_CHAPTER', 'COMMENT', 'DONGHUA_EPISODE')),

    CONSTRAINT chk_interaction_reactions_type
        CHECK (reaction_type IN ('LOVE', 'FIRE', 'HAHA', 'SAD')),

    CONSTRAINT chk_interaction_reactions_updated_at
        CHECK (updated_at >= created_at)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_interaction_reactions_target_summary
    ON interaction_reactions (target_type, target_id, reaction_type);
