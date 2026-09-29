-- =========================================================
-- V76__add_identity_users_public_handle.sql
-- MS-07 Community: Add immutable public_handle column to
-- identity_users with deterministic non-email backfill and
-- unique constraint.
-- =========================================================

-- 1. Add public_handle column as nullable initially for backfill
ALTER TABLE identity_users
    ADD COLUMN public_handle VARCHAR(40) NULL AFTER display_name;

-- 2. Backfill existing rows using deterministic UUID-derived handle ('u_' + 32-char hex id)
UPDATE identity_users
    SET public_handle = CONCAT('u_', LOWER(REPLACE(id, '-', '')))
    WHERE public_handle IS NULL;

-- 3. Enforce NOT NULL and add UNIQUE constraint
ALTER TABLE identity_users
    MODIFY COLUMN public_handle VARCHAR(40) NOT NULL,
    ADD CONSTRAINT uq_identity_users_public_handle UNIQUE (public_handle),
    ADD CONSTRAINT chk_identity_users_public_handle_length
        CHECK (CHAR_LENGTH(public_handle) BETWEEN 3 AND 40);
