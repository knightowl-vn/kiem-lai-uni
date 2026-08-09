ALTER TABLE identity_users
    MODIFY COLUMN password_hash VARCHAR(255) NULL,
    ADD COLUMN auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN provider_subject VARCHAR(255) NULL;

CREATE UNIQUE INDEX uq_identity_users_provider_subject
    ON identity_users (auth_provider, provider_subject);