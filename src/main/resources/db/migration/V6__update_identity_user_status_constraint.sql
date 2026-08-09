ALTER TABLE identity_users
    DROP CHECK chk_identity_users_status;

UPDATE identity_users
SET status = 'BLOCKED'
WHERE status = 'LOCKED';

ALTER TABLE identity_users
    ADD CONSTRAINT chk_identity_users_status
    CHECK (
        status IN (
            'ACTIVE',
            'BLOCKED',
            'UNVERIFIED',
            'BANNED'
        )
    );