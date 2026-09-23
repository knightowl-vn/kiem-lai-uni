-- =========================================================
-- V59__finalize_wiki_appreciation_half_star_units.sql
-- Finalize half-star units schema swap
-- =========================================================

ALTER TABLE wiki_appreciation_ratings
    DROP CHECK chk_wiki_appreciation_ratings_value,
    DROP COLUMN value,
    CHANGE COLUMN half_star_units value TINYINT UNSIGNED NOT NULL,
    ADD CONSTRAINT chk_wiki_appreciation_ratings_value
        CHECK (value BETWEEN 2 AND 10);
