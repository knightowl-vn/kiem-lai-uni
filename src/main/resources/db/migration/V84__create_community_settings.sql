-- =========================================================
-- V84__create_community_settings.sql
--
-- Community Runtime Settings Persistence (MS-07B8.5.4)
-- DB-backed runtime configuration for Community module publication mode.
-- - Singleton pattern enforced via PRIMARY KEY (id) and exact CHECK (id = 'DEFAULT')
-- - Optimistic locking support via version column
-- - Scalar updated_by_user_id (no FK coupling to Identity)
-- - Initial seeded publication_mode: AUTO_PUBLISH
-- =========================================================

CREATE TABLE community_settings (
    id VARCHAR(32) NOT NULL,
    publication_mode VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    updated_by_user_id CHAR(36) NULL,

    CONSTRAINT pk_community_settings
        PRIMARY KEY (id),

    CONSTRAINT chk_community_settings_id
        CHECK (id = 'DEFAULT'),

    CONSTRAINT chk_community_settings_publication_mode
        CHECK (publication_mode IN ('AUTO_PUBLISH', 'PRE_MODERATION'))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

INSERT INTO community_settings (id, publication_mode, version, updated_at, updated_by_user_id)
VALUES ('DEFAULT', 'AUTO_PUBLISH', 0, CURRENT_TIMESTAMP(6), NULL);
