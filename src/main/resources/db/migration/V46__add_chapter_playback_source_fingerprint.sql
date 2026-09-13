-- Existing artifacts have unknown binary source provenance and require one rebuild.
ALTER TABLE novel_chapter_narration_playback_artifacts
    ADD COLUMN source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
