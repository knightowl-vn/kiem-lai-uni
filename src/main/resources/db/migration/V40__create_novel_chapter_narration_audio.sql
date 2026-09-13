-- =========================================================
-- V40__create_novel_chapter_narration_audio.sql
--
-- Novel Chapter Narration Audio Assignment Persistence
-- =========================================================

CREATE TABLE novel_chapter_narration_audio (
    id CHAR(36) NOT NULL,

    segment_id CHAR(36) NOT NULL,

    managed_voice_id CHAR(36) NOT NULL,

    media_asset_id CHAR(36) NOT NULL,

    generated_synthesis_revision BIGINT NOT NULL,

    created_at DATETIME(6) NOT NULL,

    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_chapter_narration_audio
        PRIMARY KEY (id),

    CONSTRAINT uq_novel_chapter_narration_audio_segment_voice
        UNIQUE (segment_id, managed_voice_id),

    CONSTRAINT fk_novel_chapter_narration_audio_segment
        FOREIGN KEY (segment_id)
        REFERENCES novel_chapter_narration_segments (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT fk_novel_chapter_narration_audio_voice
        FOREIGN KEY (managed_voice_id)
        REFERENCES novel_managed_voices (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT chk_novel_chapter_narration_audio_revision
        CHECK (generated_synthesis_revision >= 1),

    CONSTRAINT chk_novel_chapter_narration_audio_updated_at
        CHECK (updated_at >= created_at),

    KEY idx_novel_chapter_narration_audio_voice (
        managed_voice_id
    ),

    KEY idx_novel_chapter_narration_audio_media_asset (
        media_asset_id
    )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
