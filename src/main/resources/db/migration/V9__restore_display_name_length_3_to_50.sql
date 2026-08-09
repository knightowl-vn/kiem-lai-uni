ALTER TABLE identity_users
DROP CHECK chk_identity_users_display_name_length;

ALTER TABLE identity_users
ADD CONSTRAINT chk_identity_users_display_name_length
CHECK (CHAR_LENGTH(display_name) BETWEEN 3 AND 50);