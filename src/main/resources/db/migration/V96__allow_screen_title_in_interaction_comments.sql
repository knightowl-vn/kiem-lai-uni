-- =========================================================
-- V96__allow_screen_title_in_interaction_comments.sql
--
-- Interaction Generalization for Screen Titles (MS-08F2)
-- - Allow SCREEN_TITLE in interaction_comments target_type
-- =========================================================

-- 1. Extend interaction_comments target_type check constraint
ALTER TABLE interaction_comments
    DROP CHECK chk_interaction_comments_target_type;

ALTER TABLE interaction_comments
    ADD CONSTRAINT chk_interaction_comments_target_type
    CHECK (target_type IN ('NOVEL_CHAPTER', 'WIKI_ARTICLE', 'COMMUNITY_POST', 'SCREEN_TITLE'));
