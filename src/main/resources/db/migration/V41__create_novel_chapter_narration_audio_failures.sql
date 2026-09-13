-- =========================================================
-- V41__create_novel_chapter_narration_audio_failures.sql
--
-- Novel Chapter Narration Audio Persistent Failure Diagnostics
-- =========================================================

CREATE TABLE novel_chapter_narration_audio_failures (
    id CHAR(36) NOT NULL,

    segment_id CHAR(36) NOT NULL,

    managed_voice_id CHAR(36) NOT NULL,

    operation VARCHAR(50) NOT NULL,

    stage VARCHAR(50) NOT NULL,

    attempted_synthesis_revision BIGINT NOT NULL,

    failure_count INT NOT NULL DEFAULT 1,

    error_type VARCHAR(200) NOT NULL,

    error_message VARCHAR(1000) NOT NULL,

    first_failed_at DATETIME(6) NOT NULL,

    last_failed_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_chapter_narration_audio_failures
        PRIMARY KEY (id),

    CONSTRAINT uq_novel_chapter_narration_audio_failures_segment_voice
        UNIQUE (segment_id, managed_voice_id),

    CONSTRAINT fk_novel_chapter_narration_audio_failures_segment
        FOREIGN KEY (segment_id)
        REFERENCES novel_chapter_narration_segments (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT fk_novel_chapter_narration_audio_failures_voice
        FOREIGN KEY (managed_voice_id)
        REFERENCES novel_managed_voices (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT chk_novel_narration_failure_revision
        CHECK (attempted_synthesis_revision >= 1),

    CONSTRAINT chk_novel_narration_failure_count
        CHECK (failure_count >= 1),

    CONSTRAINT chk_novel_narration_failure_last_failed_at
        CHECK (last_failed_at >= first_failed_at),

    KEY idx_novel_chapter_narration_audio_failures_voice (
        managed_voice_id
    )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
