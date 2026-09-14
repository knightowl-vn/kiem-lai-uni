-- =========================================================
-- V47__add_encoded_timing_to_novel_chapter_narration_audio.sql
--
-- Add Encoded Timing Metadata to Novel Chapter Narration Audio
-- =========================================================

ALTER TABLE novel_chapter_narration_audio
    ADD COLUMN encoded_contribution_samples BIGINT NULL,
    ADD COLUMN encoded_sample_rate_hz INT NULL;
