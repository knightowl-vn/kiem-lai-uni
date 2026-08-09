ALTER TABLE identity_users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';

ALTER TABLE identity_users
    ADD CONSTRAINT chk_identity_users_role
        CHECK (role IN ('USER', 'ADMIN'));

CREATE INDEX idx_identity_users_role
    ON identity_users(role);