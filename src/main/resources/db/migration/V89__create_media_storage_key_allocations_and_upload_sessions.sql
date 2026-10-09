-- =========================================================
-- V89__create_media_storage_key_allocations_and_upload_sessions.sql
-- Media Storage-Key Registry, Durable Upload Sessions & Provenance
-- =========================================================

-- 1. Media Storage-Key Allocations Registry
-- Enforces absolute global uniqueness of (storage_provider_id, storage_key) across all sessions and versions.
CREATE TABLE media_storage_key_allocations (
    id CHAR(36) NOT NULL,
    storage_provider_id VARCHAR(50) NOT NULL,
    storage_bucket_name VARCHAR(100) NOT NULL,
    storage_key VARCHAR(500)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin
        NOT NULL,
    allocation_status VARCHAR(20) NOT NULL,
    allocated_by_type VARCHAR(50) NOT NULL,
    allocated_by_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    persistence_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_media_storage_key_allocations
        PRIMARY KEY (id),

    CONSTRAINT uq_media_storage_key_allocations_provider_key
        UNIQUE (storage_provider_id, storage_key),

    CONSTRAINT uq_media_storage_key_allocations_identity
        UNIQUE (id, storage_provider_id, storage_key),

    CONSTRAINT uq_media_storage_key_allocations_full_location
        UNIQUE (id, storage_provider_id, storage_bucket_name, storage_key),

    CONSTRAINT chk_media_storage_key_allocations_status
        CHECK (allocation_status IN ('RESERVED', 'COMMITTED', 'TOMBSTONE')),

    CONSTRAINT chk_media_storage_key_allocations_by_type
        CHECK (allocated_by_type IN ('UPLOAD_SESSION', 'ASSET_VERSION', 'IMAGE_VARIANT')),

    CONSTRAINT chk_media_storage_key_allocations_updated_at
        CHECK (updated_at >= created_at),

    CONSTRAINT chk_media_storage_key_allocations_persistence_version
        CHECK (persistence_version >= 0),

    CONSTRAINT chk_media_storage_key_allocations_bucket_not_blank
        CHECK (storage_bucket_name != ''),

    CONSTRAINT chk_media_storage_key_allocations_b2_bucket
        CHECK (storage_provider_id != 'b2' OR storage_bucket_name != 'NONE')
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


-- 2. Durable Multipart Media Upload Sessions
-- Tracks upload state machine, ownership, idempotency and cleanup metadata.
CREATE TABLE media_upload_sessions (
    id CHAR(36) NOT NULL,
    initiator_principal_id VARCHAR(100) NOT NULL,
    consumer_context VARCHAR(50) NOT NULL,
    target_entity_type VARCHAR(50) NOT NULL,
    target_entity_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    media_type VARCHAR(20) NOT NULL,
    declared_mime_type VARCHAR(100) NOT NULL,
    declared_size_bytes BIGINT NOT NULL,
    storage_provider_id VARCHAR(50) NOT NULL,
    storage_bucket_name VARCHAR(100) NOT NULL,
    storage_key VARCHAR(500)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin
        NOT NULL,
    storage_allocation_id CHAR(36) NOT NULL,
    provider_upload_id VARCHAR(255) NULL,
    part_size_bytes BIGINT NOT NULL DEFAULT 26214400,
    expected_part_count INT NOT NULL,
    declared_content_hash CHAR(64) NULL,
    original_filename VARCHAR(255) NOT NULL,
    client_tag VARCHAR(64) NULL,
    status VARCHAR(20) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    lease_owner VARCHAR(100) NULL,
    lease_expires_at DATETIME(6) NULL,
    registered_asset_id CHAR(36) NULL,
    cleanup_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    cleanup_retry_count INT NOT NULL DEFAULT 0,
    cleanup_last_attempt_at DATETIME(6) NULL,
    cleanup_error_detail VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    persistence_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_media_upload_sessions
        PRIMARY KEY (id),

    CONSTRAINT uq_media_upload_sessions_idempotency
        UNIQUE (initiator_principal_id, idempotency_key),

    CONSTRAINT uq_media_upload_sessions_allocation
        UNIQUE (storage_allocation_id),

    CONSTRAINT uq_media_upload_sessions_provider_key
        UNIQUE (storage_provider_id, storage_key),

    CONSTRAINT fk_media_upload_sessions_allocation_location
        FOREIGN KEY (storage_allocation_id, storage_provider_id, storage_bucket_name, storage_key)
        REFERENCES media_storage_key_allocations (id, storage_provider_id, storage_bucket_name, storage_key)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT chk_media_upload_sessions_bucket_not_blank
        CHECK (storage_bucket_name != ''),

    CONSTRAINT chk_media_upload_sessions_b2_bucket
        CHECK (storage_provider_id != 'b2' OR storage_bucket_name != 'NONE'),

    CONSTRAINT fk_media_upload_sessions_asset
        FOREIGN KEY (registered_asset_id)
        REFERENCES media_assets (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT chk_media_upload_sessions_media_type
        CHECK (media_type = 'VIDEO'),

    CONSTRAINT chk_media_upload_sessions_mime_type
        CHECK (declared_mime_type = 'video/mp4'),

    CONSTRAINT chk_media_upload_sessions_size_bytes
        CHECK (declared_size_bytes > 0 AND declared_size_bytes <= 53687091200),

    CONSTRAINT chk_media_upload_sessions_part_size
        CHECK (part_size_bytes = 26214400),

    CONSTRAINT chk_media_upload_sessions_expected_parts
        CHECK (
            expected_part_count BETWEEN 1 AND 2048
            AND expected_part_count = CEIL(declared_size_bytes / part_size_bytes)
        ),

    CONSTRAINT chk_media_upload_sessions_status
        CHECK (status IN (
            'ALLOCATED',
            'INITIATED',
            'COMPLETING',
            'COMPLETED',
            'ABORTED',
            'EXPIRED',
            'FAILED',
            'CLEANED_UP'
        )),

    CONSTRAINT chk_media_upload_sessions_cleanup_status
        CHECK (cleanup_status IN ('NONE', 'PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),

    CONSTRAINT chk_media_upload_sessions_persistence_version
        CHECK (persistence_version >= 0),

    CONSTRAINT chk_media_upload_sessions_cleanup_retry_count
        CHECK (cleanup_retry_count >= 0),

    CONSTRAINT chk_media_upload_sessions_updated_at
        CHECK (updated_at >= created_at),

    -- Invariant: provider_upload_id must be non-null once active in provider (INITIATED, COMPLETING, COMPLETED)
    -- Pre-provider initiation states (ALLOCATED, FAILED, ABORTED, EXPIRED, CLEANED_UP) permit NULL.
    CONSTRAINT chk_media_upload_sessions_provider_upload_id
        CHECK (
            (status NOT IN ('INITIATED', 'COMPLETING', 'COMPLETED'))
            OR (provider_upload_id IS NOT NULL)
        ),

    -- Invariant: COMPLETED sessions must have declared_content_hash, registered_asset_id, and completed_at
    CONSTRAINT chk_media_upload_sessions_completion
        CHECK (
            (status != 'COMPLETED')
            OR (
                declared_content_hash IS NOT NULL
                AND registered_asset_id IS NOT NULL
                AND completed_at IS NOT NULL
            )
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_media_upload_sessions_provider_upload
    ON media_upload_sessions (storage_provider_id, provider_upload_id);

CREATE INDEX idx_media_upload_sessions_status_expires
    ON media_upload_sessions (status, expires_at);

CREATE INDEX idx_media_upload_sessions_lease
    ON media_upload_sessions (status, lease_expires_at);

CREATE INDEX idx_media_upload_sessions_cleanup
    ON media_upload_sessions (cleanup_status, cleanup_last_attempt_at);

CREATE INDEX idx_media_upload_sessions_consumer_target
    ON media_upload_sessions (consumer_context, target_entity_type, target_entity_id);


-- 3. ContentHash Provenance on media_asset_versions
-- Distinguishes SERVER_COMPUTED and CLIENT_DECLARED_UNVERIFIED.
-- Historical rows remain NULL to truth-preservingly indicate unrecorded legacy provenance.
ALTER TABLE media_asset_versions
    ADD COLUMN content_hash_status VARCHAR(30) NULL
        AFTER content_hash;

ALTER TABLE media_asset_versions
    ADD CONSTRAINT chk_media_asset_versions_content_hash_status
        CHECK ((content_hash_status IS NULL) OR (content_hash_status IN ('SERVER_COMPUTED', 'CLIENT_DECLARED_UNVERIFIED')));
