-- =========================================================
-- V61__add_wiki_article_cover_focal_position.sql
-- Add cover focal position coordinates (0..100%, default 50%) to wiki_articles
-- =========================================================

ALTER TABLE wiki_articles
    ADD COLUMN cover_position_x TINYINT UNSIGNED NOT NULL DEFAULT 50
        AFTER cover_media_asset_id,
    ADD COLUMN cover_position_y TINYINT UNSIGNED NOT NULL DEFAULT 50
        AFTER cover_position_x,
    ADD CONSTRAINT chk_wiki_articles_cover_position_x
        CHECK (cover_position_x BETWEEN 0 AND 100),
    ADD CONSTRAINT chk_wiki_articles_cover_position_y
        CHECK (cover_position_y BETWEEN 0 AND 100);
