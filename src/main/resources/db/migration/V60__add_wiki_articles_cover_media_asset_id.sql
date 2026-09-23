-- =========================================================
-- V60__add_wiki_articles_cover_media_asset_id.sql
-- Add optional Media-backed cover_media_asset_id to wiki_articles
-- =========================================================

ALTER TABLE wiki_articles
    ADD COLUMN cover_media_asset_id CHAR(36) NULL
        AFTER content;

CREATE INDEX idx_wiki_articles_cover_media_asset_id
    ON wiki_articles(cover_media_asset_id);
