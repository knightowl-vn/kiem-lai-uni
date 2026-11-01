-- =========================================================
-- V94__add_total_episode_count_to_screen_titles.sql
--
-- Add optional total episode count to ScreenTitle (MS-08F1)
-- Represents planned total episodes for SERIES titles; NULL if unknown.
-- =========================================================

ALTER TABLE screen_titles
    ADD COLUMN total_episode_count INT NULL AFTER synopsis,
    ADD CONSTRAINT chk_screen_titles_total_episode_count
        CHECK (total_episode_count IS NULL OR total_episode_count > 0);
