ALTER TABLE identity_users
DROP CHECK chk_identity_users_role;

ALTER TABLE identity_users
ADD CONSTRAINT chk_identity_users_role
CHECK (
    role IN (
        'USER',
        'ADMIN',
        'SUPER_ADMIN'
    )
);