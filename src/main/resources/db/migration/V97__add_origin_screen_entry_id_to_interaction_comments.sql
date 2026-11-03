-- =========================================================
-- V97__add_origin_screen_entry_id_to_interaction_comments.sql
--
-- Screen Comments Episode Origin Context (MS-08F2)
-- - Add nullable origin_screen_entry_id column to interaction_comments
-- =========================================================

ALTER TABLE interaction_comments
    ADD COLUMN origin_screen_entry_id CHAR(36) NULL AFTER thread_root_comment_id;
