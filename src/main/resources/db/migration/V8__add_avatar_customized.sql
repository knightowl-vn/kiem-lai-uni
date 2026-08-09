ALTER TABLE identity_users
    ADD COLUMN avatar_customized BOOLEAN NULL;

UPDATE identity_users
SET avatar_customized = TRUE;

ALTER TABLE identity_users
    MODIFY COLUMN avatar_customized BOOLEAN NOT NULL DEFAULT FALSE;