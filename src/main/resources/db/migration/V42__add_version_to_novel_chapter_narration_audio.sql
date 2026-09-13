-- =========================================================
-- V42__add_version_to_novel_chapter_narration_audio.sql
--
-- Add Optimistic Locking Version to Novel Chapter Narration Audio
-- =========================================================

ALTER TABLE novel_chapter_narration_audio
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
