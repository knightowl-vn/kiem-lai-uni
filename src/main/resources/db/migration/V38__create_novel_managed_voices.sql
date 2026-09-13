-- =========================================================
-- V38__create_novel_managed_voices.sql
--
-- Novel Managed Narration Voices Catalog
-- =========================================================

CREATE TABLE novel_managed_voices (
    id CHAR(36) NOT NULL,

    voice_key VARCHAR(100) NOT NULL,

    display_name VARCHAR(200) NOT NULL,

    provider_voice_id VARCHAR(200) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    display_order INT NOT NULL DEFAULT 0,

    is_default BOOLEAN NOT NULL DEFAULT FALSE,

    is_default_unique_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN is_default = TRUE THEN 1 ELSE NULL END
    ) VIRTUAL,

    synthesis_revision BIGINT NOT NULL DEFAULT 1,

    persistence_version BIGINT NOT NULL DEFAULT 0,

    created_at DATETIME(6) NOT NULL,

    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_managed_voices
        PRIMARY KEY (id),

    CONSTRAINT uq_novel_managed_voices_key
        UNIQUE (voice_key),

    CONSTRAINT uq_novel_managed_voices_single_default
        UNIQUE (is_default_unique_guard),

    CONSTRAINT chk_novel_managed_voices_key_length
        CHECK (CHAR_LENGTH(voice_key) BETWEEN 2 AND 100),

    CONSTRAINT chk_novel_managed_voices_name_length
        CHECK (CHAR_LENGTH(display_name) BETWEEN 1 AND 200),

    CONSTRAINT chk_novel_managed_voices_provider_id_length
        CHECK (CHAR_LENGTH(provider_voice_id) BETWEEN 1 AND 200),

    CONSTRAINT chk_novel_managed_voices_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),

    CONSTRAINT chk_novel_managed_voices_active_default
        CHECK (is_default = FALSE OR status = 'ACTIVE'),

    CONSTRAINT chk_novel_managed_voices_display_order
        CHECK (display_order >= 0),

    CONSTRAINT chk_novel_managed_voices_synthesis_revision
        CHECK (synthesis_revision >= 1),

    CONSTRAINT chk_novel_managed_voices_persistence_version
        CHECK (persistence_version >= 0),

    KEY idx_novel_managed_voices_status (status),

    KEY idx_novel_managed_voices_display_order (display_order),

    KEY idx_novel_managed_voices_default (is_default)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
