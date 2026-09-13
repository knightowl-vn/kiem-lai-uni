-- =========================================================
-- V45__create_novel_chapter_narration_playback.sql
--
-- Novel Chapter-Level Narration Playback Foundation
-- =========================================================

CREATE TABLE novel_chapter_narration_playbacks (
    id CHAR(36) NOT NULL,

    chapter_id CHAR(36) NOT NULL,

    managed_voice_id CHAR(36) NOT NULL,

    current_artifact_id CHAR(36) NULL,

    version BIGINT NOT NULL DEFAULT 0,

    created_at DATETIME(6) NOT NULL,

    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_chapter_narration_playbacks
        PRIMARY KEY (id),

    CONSTRAINT uq_novel_chapter_narration_playbacks_chapter_voice
        UNIQUE (chapter_id, managed_voice_id),

    CONSTRAINT fk_novel_chapter_narration_playbacks_chapter
        FOREIGN KEY (chapter_id)
        REFERENCES novel_chapters (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT fk_novel_chapter_narration_playbacks_voice
        FOREIGN KEY (managed_voice_id)
        REFERENCES novel_managed_voices (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT chk_novel_chapter_narration_playbacks_version
        CHECK (version >= 0),

    CONSTRAINT chk_novel_chapter_narration_playbacks_updated_at
        CHECK (updated_at >= created_at),

    KEY idx_novel_chapter_narration_playbacks_voice (
        managed_voice_id
    ),

    KEY idx_novel_chapter_narration_playbacks_current_artifact (
        current_artifact_id
    )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


CREATE TABLE novel_chapter_narration_playback_artifacts (
    id CHAR(36) NOT NULL,

    playback_id CHAR(36) NOT NULL,

    chapter_id CHAR(36) NOT NULL,

    managed_voice_id CHAR(36) NOT NULL,

    source_content_version BIGINT NOT NULL,

    synthesis_revision BIGINT NOT NULL,

    manifest_hash VARCHAR(64) NOT NULL,

    media_asset_id CHAR(36) NOT NULL,

    duration_millis BIGINT NOT NULL,

    cue_count INT NOT NULL,

    codec_mime_type VARCHAR(100) NOT NULL,

    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_chapter_narration_playback_artifacts
        PRIMARY KEY (id),

    CONSTRAINT fk_novel_chapter_narration_playback_artifacts_playback
        FOREIGN KEY (playback_id)
        REFERENCES novel_chapter_narration_playbacks (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT fk_novel_chapter_narration_playback_artifacts_chapter
        FOREIGN KEY (chapter_id)
        REFERENCES novel_chapters (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT fk_novel_chapter_narration_playback_artifacts_voice
        FOREIGN KEY (managed_voice_id)
        REFERENCES novel_managed_voices (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT chk_novel_chapter_narration_playback_artifacts_source_ver
        CHECK (source_content_version >= 1),

    CONSTRAINT chk_novel_chapter_narration_playback_artifacts_synth_rev
        CHECK (synthesis_revision >= 1),

    CONSTRAINT chk_novel_chapter_narration_playback_artifacts_hash_len
        CHECK (CHAR_LENGTH(manifest_hash) = 64),

    CONSTRAINT chk_novel_chapter_narration_playback_artifacts_duration
        CHECK (duration_millis > 0),

    CONSTRAINT chk_novel_chapter_narration_playback_artifacts_cue_count
        CHECK (cue_count >= 0),

    CONSTRAINT chk_novel_chapter_narration_playback_artifacts_codec_mime
        CHECK (CHAR_LENGTH(codec_mime_type) BETWEEN 1 AND 100),

    KEY idx_novel_chapter_narration_playback_artifacts_playback (
        playback_id
    ),

    KEY idx_novel_chapter_narration_playback_artifacts_chapter_voice (
        chapter_id,
        managed_voice_id
    ),

    KEY idx_novel_chapter_narration_playback_artifacts_media (
        media_asset_id
    )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


CREATE TABLE novel_chapter_narration_playback_cues (
    artifact_id CHAR(36) NOT NULL,

    cue_ordinal INT NOT NULL,

    segment_id CHAR(36) NOT NULL,

    segment_index INT NOT NULL,

    start_millis BIGINT NOT NULL,

    end_millis BIGINT NOT NULL,

    CONSTRAINT pk_novel_chapter_narration_playback_cues
        PRIMARY KEY (artifact_id, cue_ordinal),

    CONSTRAINT fk_novel_chapter_narration_playback_cues_artifact
        FOREIGN KEY (artifact_id)
        REFERENCES novel_chapter_narration_playback_artifacts (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT fk_novel_chapter_narration_playback_cues_segment
        FOREIGN KEY (segment_id)
        REFERENCES novel_chapter_narration_segments (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT chk_novel_chapter_narration_playback_cues_ordinal
        CHECK (cue_ordinal >= 0),

    CONSTRAINT chk_novel_chapter_narration_playback_cues_segment_idx
        CHECK (segment_index >= 0),

    CONSTRAINT chk_novel_chapter_narration_playback_cues_start
        CHECK (start_millis >= 0),

    CONSTRAINT chk_novel_chapter_narration_playback_cues_end
        CHECK (end_millis > start_millis),

    KEY idx_novel_chapter_narration_playback_cues_segment (
        segment_id
    ),

    KEY idx_novel_chapter_narration_playback_cues_artifact_segment_index (
        artifact_id,
        segment_index
    )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


ALTER TABLE novel_chapter_narration_playbacks
    ADD CONSTRAINT fk_novel_chapter_narration_playbacks_current_artifact
        FOREIGN KEY (current_artifact_id)
        REFERENCES novel_chapter_narration_playback_artifacts (id)
        ON UPDATE RESTRICT
        ON DELETE SET NULL;
