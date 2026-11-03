-- =========================================================
-- V95__add_airing_status_and_metadata_to_screen_titles.sql
--
-- Screen Title Airing Status and Release Metadata Extension (MS-08F2)
-- Adds nullable airing status, release year, country of origin, original author,
-- director, and production studio to screen_titles table.
-- =========================================================

ALTER TABLE screen_titles
    ADD COLUMN airing_status VARCHAR(20) NULL AFTER presentation,
    ADD COLUMN release_year INT NULL AFTER synopsis,
    ADD COLUMN country VARCHAR(100) NULL AFTER release_year,
    ADD COLUMN original_author VARCHAR(255) NULL AFTER country,
    ADD COLUMN director VARCHAR(255) NULL AFTER original_author,
    ADD COLUMN production_studio VARCHAR(255) NULL AFTER director,
    ADD CONSTRAINT chk_screen_titles_airing_status
        CHECK (airing_status IS NULL OR airing_status IN ('UPCOMING', 'ONGOING', 'COMPLETED', 'RELEASED')),
    ADD CONSTRAINT chk_screen_titles_release_year
        CHECK (release_year IS NULL OR (release_year >= 1900 AND release_year <= 2100));
