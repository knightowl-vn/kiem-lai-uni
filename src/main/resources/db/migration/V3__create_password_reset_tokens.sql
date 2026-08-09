CREATE TABLE identity_password_reset_tokens (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,

    token_hash CHAR(64) NOT NULL,

    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,

    requested_ip VARCHAR(45) NULL,
    user_agent VARCHAR(500) NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    persistence_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_identity_password_reset_tokens
        PRIMARY KEY (id),

    CONSTRAINT uq_identity_password_reset_tokens_token_hash
        UNIQUE (token_hash),

    CONSTRAINT fk_identity_password_reset_tokens_user
        FOREIGN KEY (user_id)
        REFERENCES identity_users (id)
        ON DELETE CASCADE,

    CONSTRAINT chk_identity_password_reset_tokens_persistence_version
        CHECK (persistence_version >= 0),

    CONSTRAINT chk_identity_password_reset_tokens_expiry
        CHECK (expires_at > created_at),

    CONSTRAINT chk_identity_password_reset_tokens_used_at
        CHECK (used_at IS NULL OR used_at >= created_at),

    CONSTRAINT chk_identity_password_reset_tokens_revoked_at
        CHECK (revoked_at IS NULL OR revoked_at >= created_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;