-- =========================================================
-- V57__add_wiki_appreciation_half_star_units.sql
-- Add temporary shadow column for half-star units migration
-- =========================================================

ALTER TABLE wiki_appreciation_ratings
    ADD COLUMN half_star_units TINYINT UNSIGNED NULL;
