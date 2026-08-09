-- =========================================================
-- V1__init_identity_schema.sql
-- MS-01 Identity Module
-- MySQL 8 / UTF-8 / UTC timestamps
-- UUID strategy: CHAR(36)
-- =========================================================

-- =========================================================
-- 1. USERS
-- =========================================================

CREATE TABLE identity_users (
    id CHAR(36) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    avatar_url VARCHAR(1000) NULL,
    bio VARCHAR(500) NULL,

    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',

    failed_login_count INT NOT NULL DEFAULT 0,
    locked_until DATETIME(6) NULL,

    aggregate_version BIGINT NOT NULL DEFAULT 1,
    persistence_version BIGINT NOT NULL DEFAULT 0,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_identity_users
        PRIMARY KEY (id),

    CONSTRAINT uq_identity_users_email
        UNIQUE (email),

    CONSTRAINT chk_identity_users_status
        CHECK (status IN ('ACTIVE', 'LOCKED', 'BANNED')),

    CONSTRAINT chk_identity_users_failed_login_count
        CHECK (failed_login_count >= 0),

    CONSTRAINT chk_identity_users_display_name_length
        CHECK (CHAR_LENGTH(display_name) BETWEEN 3 AND 50),

    CONSTRAINT chk_identity_users_bio_length
        CHECK (bio IS NULL OR CHAR_LENGTH(bio) <= 500),

    CONSTRAINT chk_identity_users_aggregate_version
        CHECK (aggregate_version >= 1),

    CONSTRAINT chk_identity_users_persistence_version
        CHECK (persistence_version >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_identity_users_status
    ON identity_users (status);

CREATE INDEX idx_identity_users_locked_until
    ON identity_users (locked_until);

CREATE INDEX idx_identity_users_created_at
    ON identity_users (created_at);


-- =========================================================
-- 2. ROLES
-- =========================================================

CREATE TABLE identity_roles (
    id CHAR(36) NOT NULL,
    code VARCHAR(50) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    description VARCHAR(255) NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    persistence_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_identity_roles
        PRIMARY KEY (id),

    CONSTRAINT uq_identity_roles_code
        UNIQUE (code),

    CONSTRAINT chk_identity_roles_persistence_version
        CHECK (persistence_version >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;


-- =========================================================
-- 3. PERMISSIONS
-- =========================================================

CREATE TABLE identity_permissions (
    id CHAR(36) NOT NULL,
    code VARCHAR(100) NOT NULL,
    display_name VARCHAR(150) NOT NULL,
    description VARCHAR(255) NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_identity_permissions
        PRIMARY KEY (id),

    CONSTRAINT uq_identity_permissions_code
        UNIQUE (code)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;


-- =========================================================
-- 4. USER - ROLE
-- =========================================================

CREATE TABLE identity_user_roles (
    user_id CHAR(36) NOT NULL,
    role_id CHAR(36) NOT NULL,

    assigned_by CHAR(36) NULL,
    assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_identity_user_roles
        PRIMARY KEY (user_id, role_id),

    CONSTRAINT fk_identity_user_roles_user
        FOREIGN KEY (user_id)
        REFERENCES identity_users (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_identity_user_roles_role
        FOREIGN KEY (role_id)
        REFERENCES identity_roles (id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_identity_user_roles_assigned_by
        FOREIGN KEY (assigned_by)
        REFERENCES identity_users (id)
        ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_identity_user_roles_role_id
    ON identity_user_roles (role_id);

CREATE INDEX idx_identity_user_roles_assigned_by
    ON identity_user_roles (assigned_by);


-- =========================================================
-- 5. ROLE - PERMISSION
-- =========================================================

CREATE TABLE identity_role_permissions (
    role_id CHAR(36) NOT NULL,
    permission_id CHAR(36) NOT NULL,

    assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_identity_role_permissions
        PRIMARY KEY (role_id, permission_id),

    CONSTRAINT fk_identity_role_permissions_role
        FOREIGN KEY (role_id)
        REFERENCES identity_roles (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_identity_role_permissions_permission
        FOREIGN KEY (permission_id)
        REFERENCES identity_permissions (id)
        ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_identity_role_permissions_permission_id
    ON identity_role_permissions (permission_id);


-- =========================================================
-- 6. SESSIONS
-- =========================================================

CREATE TABLE identity_sessions (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,

    refresh_token_hash VARCHAR(255) NOT NULL,

    device_identifier VARCHAR(255) NOT NULL,
    device_info VARCHAR(500) NULL,
    ip_address VARCHAR(45) NULL,

    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',

    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    last_used_at DATETIME(6) NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    persistence_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_identity_sessions
        PRIMARY KEY (id),

    CONSTRAINT uq_identity_sessions_refresh_token_hash
        UNIQUE (refresh_token_hash),

    CONSTRAINT fk_identity_sessions_user
        FOREIGN KEY (user_id)
        REFERENCES identity_users (id)
        ON DELETE CASCADE,

    CONSTRAINT chk_identity_sessions_status
        CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),

    CONSTRAINT chk_identity_sessions_persistence_version
        CHECK (persistence_version >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_identity_sessions_user_status
    ON identity_sessions (user_id, status);

CREATE INDEX idx_identity_sessions_user_device
    ON identity_sessions (user_id, device_identifier);

CREATE INDEX idx_identity_sessions_expires_at
    ON identity_sessions (expires_at);


-- =========================================================
-- 7. DEFAULT ROLES
-- =========================================================

INSERT INTO identity_roles (
    id,
    code,
    display_name,
    description
)VALUES
(
    '00000000-0000-0000-0000-000000000001',
    'MEMBER',
    'Member',
    'Default role assigned to newly registered users'
),
(
    '00000000-0000-0000-0000-000000000002',
    'MODERATOR',
    'Moderator',
    'Community moderation role'
),
(
    '00000000-0000-0000-0000-000000000003',
    'ADMIN',
    'Administrator',
    'System administration role'
);

-- =========================================================
-- 8. OUTBOX (Transactional Outbox Pattern)
-- =========================================================

CREATE TABLE identity_outbox_messages (
    id CHAR(36) NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    event_version VARCHAR(20) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    source_module VARCHAR(100) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    correlation_id CHAR(36),
    causation_id CHAR(36),
    payload_json JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    processing_owner VARCHAR(255) NULL,
    claim_token CHAR(36) NULL,
    processing_started_at DATETIME(6) NULL,
    processing_lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    dead_at DATETIME(6) NULL,
    last_error TEXT NULL,
    persistence_version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT pk_identity_outbox 
        PRIMARY KEY (id),
    CONSTRAINT uq_identity_outbox_event 
        UNIQUE (event_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_identity_outbox_status_next_attempt 
    ON identity_outbox_messages (status, next_attempt_at);

-- =========================================================
-- 9. INBOX (Idempotent Consumption)
-- =========================================================

CREATE TABLE identity_inbox_messages (
    id CHAR(36) NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    event_version VARCHAR(20) NOT NULL,
    consumer_name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    processing_owner VARCHAR(255) NULL,
    claim_token CHAR(36) NULL,
    processing_started_at DATETIME(6) NULL,
    processing_lease_until DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    last_error TEXT NULL,
    persistence_version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT pk_identity_inbox 
        PRIMARY KEY (id),
    CONSTRAINT uq_identity_inbox_event_consumer 
        UNIQUE (event_id, consumer_name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;