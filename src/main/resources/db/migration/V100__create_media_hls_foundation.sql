-- =========================================================
-- V100__create_media_hls_foundation.sql
--
-- Media HLS Packaging Domain, Job Lifecycle, Source-Version Integrity,
-- and Provider-Neutral Revocation Outbox Foundation (MS-08-HLS1).
-- =========================================================

-- 1. Enforce composite identity triplet on immutable source MediaAssetVersion rows
-- so downstream HLS packages and packaging jobs bind atomically to (id, asset_id, version_number).
ALTER TABLE media_asset_versions
    ADD CONSTRAINT uq_media_asset_versions_identity_triplet
        UNIQUE (id, asset_id, version_number);

-- 2. Media Video HLS Packages (derivative immutable fMP4 attempt/package records)
CREATE TABLE media_video_hls_packages (
    id CHAR(36) NOT NULL,
    asset_id CHAR(36) NOT NULL,
    source_version_id CHAR(36) NOT NULL,
    source_version_number INT NOT NULL,
    package_uuid CHAR(36) NOT NULL,
    attempt_number INT NOT NULL,
    storage_provider_id VARCHAR(50) NOT NULL,
    storage_bucket_name VARCHAR(100) NOT NULL,
    base_prefix VARCHAR(500)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin
        NOT NULL,
    master_manifest_key VARCHAR(500)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin
        NOT NULL,
    hls_version INT NOT NULL DEFAULT 7,
    segment_format VARCHAR(20) NOT NULL DEFAULT 'FMP4',
    segment_duration_seconds INT NOT NULL,
    renditions_json VARCHAR(2000) NULL,
    master_manifest_sha256 CHAR(64) NULL,
    total_size_bytes BIGINT NULL,
    object_count INT NULL,
    status VARCHAR(20) NOT NULL,
    active_ready_flag TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'READY' THEN 1 ELSE NULL END) STORED,
    failure_reason VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    verified_at DATETIME(6) NULL,
    ready_at DATETIME(6) NULL,
    superseded_at DATETIME(6) NULL,
    purge_after DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    persistence_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_media_video_hls_packages
        PRIMARY KEY (id),

    CONSTRAINT fk_hls_pkg_asset
        FOREIGN KEY (asset_id)
        REFERENCES media_assets (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT fk_hls_pkg_version_triplet
        FOREIGN KEY (source_version_id, asset_id, source_version_number)
        REFERENCES media_asset_versions (id, asset_id, version_number)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT uq_hls_pkg_uuid
        UNIQUE (package_uuid),

    CONSTRAINT uq_hls_pkg_provider_prefix
        UNIQUE (storage_provider_id, base_prefix),

    CONSTRAINT uq_hls_pkg_one_ready
        UNIQUE (source_version_id, active_ready_flag),

    CONSTRAINT chk_hls_pkg_status
        CHECK (status IN ('PROCESSING', 'READY', 'FAILED', 'ABANDONED', 'SUPERSEDED', 'PURGING', 'PURGED')),

    CONSTRAINT chk_hls_pkg_format
        CHECK (segment_format = 'FMP4'),

    CONSTRAINT chk_hls_pkg_hls_version
        CHECK (hls_version >= 7),

    CONSTRAINT chk_hls_pkg_segment_duration
        CHECK (segment_duration_seconds BETWEEN 2 AND 10),

    CONSTRAINT chk_hls_pkg_attempt_number
        CHECK (attempt_number >= 1),

    CONSTRAINT chk_hls_pkg_bucket_not_blank
        CHECK (storage_bucket_name != ''),

    CONSTRAINT chk_hls_pkg_ready_evidence
        CHECK (
            status != 'READY'
            OR (
                verified_at IS NOT NULL
                AND ready_at IS NOT NULL
                AND master_manifest_sha256 IS NOT NULL
                AND total_size_bytes > 0
                AND object_count >= 4
            )
        ),

    CONSTRAINT chk_hls_pkg_persistence_version
        CHECK (persistence_version >= 0),

    CONSTRAINT chk_hls_pkg_updated_at
        CHECK (updated_at >= created_at)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_hls_pkg_asset_status
    ON media_video_hls_packages (asset_id, status);

CREATE INDEX idx_hls_pkg_purge
    ON media_video_hls_packages (status, purge_after);


-- 3. Media Video Packaging Jobs (fenced async job coordination per source version)
CREATE TABLE media_video_packaging_jobs (
    id CHAR(36) NOT NULL,
    asset_id CHAR(36) NOT NULL,
    source_version_id CHAR(36) NOT NULL,
    source_version_number INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    active_job_flag TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING', 'LEASED') THEN 1 ELSE NULL END) STORED,
    lease_generation BIGINT NOT NULL DEFAULT 0,
    current_package_id CHAR(36) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    lease_owner VARCHAR(100) NULL,
    lease_expires_at DATETIME(6) NULL,
    heartbeat_at DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    persistence_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_media_video_packaging_jobs
        PRIMARY KEY (id),

    CONSTRAINT fk_hls_job_asset
        FOREIGN KEY (asset_id)
        REFERENCES media_assets (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT fk_hls_job_version_triplet
        FOREIGN KEY (source_version_id, asset_id, source_version_number)
        REFERENCES media_asset_versions (id, asset_id, version_number)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT fk_hls_job_current_pkg
        FOREIGN KEY (current_package_id)
        REFERENCES media_video_hls_packages (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT uq_hls_job_one_active
        UNIQUE (source_version_id, active_job_flag),

    CONSTRAINT uq_hls_job_current_pkg
        UNIQUE (current_package_id),

    CONSTRAINT chk_hls_job_status
        CHECK (status IN ('PENDING', 'LEASED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),

    CONSTRAINT chk_hls_job_fencing
        CHECK (
            lease_generation >= 0
            AND attempt_count >= 0
            AND max_attempts >= 1
        ),

    CONSTRAINT chk_hls_job_leased_fields
        CHECK (
            status != 'LEASED'
            OR (
                lease_owner IS NOT NULL
                AND lease_expires_at IS NOT NULL
                AND current_package_id IS NOT NULL
            )
        ),

    CONSTRAINT chk_hls_job_persistence_version
        CHECK (persistence_version >= 0),

    CONSTRAINT chk_hls_job_updated_at
        CHECK (updated_at >= created_at)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_hls_job_claimable
    ON media_video_packaging_jobs (status, next_attempt_at, lease_expires_at, created_at);

CREATE INDEX idx_hls_job_asset_status
    ON media_video_packaging_jobs (asset_id, status);


-- 4. Provider-neutral transactional revocation outbox schema for future edge synchronization (HLS-3)
CREATE TABLE media_hls_revocation_outbox (
    event_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_key VARCHAR(160) NOT NULL,
    scope_sequence BIGINT NOT NULL,
    revoked_before_epoch BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    acknowledged_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    persistence_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_media_hls_revocation_outbox
        PRIMARY KEY (event_id),

    CONSTRAINT uq_hls_rev_outbox_idempotency
        UNIQUE (idempotency_key),

    CONSTRAINT uq_hls_rev_outbox_scope_seq
        UNIQUE (scope_key, scope_sequence),

    CONSTRAINT chk_hls_rev_outbox_scope_type
        CHECK (scope_type IN ('ENTRY', 'TITLE', 'ASSET', 'PACKAGE')),

    CONSTRAINT chk_hls_rev_outbox_status
        CHECK (status IN ('PENDING', 'ACKNOWLEDGED', 'FAILED')),

    CONSTRAINT chk_hls_rev_outbox_seq
        CHECK (scope_sequence >= 1 AND revoked_before_epoch >= 0),

    CONSTRAINT chk_hls_rev_outbox_attempts
        CHECK (attempt_count >= 0 AND persistence_version >= 0),

    CONSTRAINT chk_hls_rev_outbox_updated_at
        CHECK (updated_at >= created_at)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_hls_rev_outbox_dispatch
    ON media_hls_revocation_outbox (status, next_attempt_at, created_at);
