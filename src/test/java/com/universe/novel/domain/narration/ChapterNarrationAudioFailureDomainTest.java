package com.universe.novel.domain.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChapterNarrationAudioFailure Domain Model Tests")
class ChapterNarrationAudioFailureDomainTest {

    private static final UUID ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SEGMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID VOICE_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    @Test
    @DisplayName("Should successfully create a valid ChapterNarrationAudioFailure record deriving safe errorMessage from stage")
    void shouldCreateValidFailureRecord() {
        ChapterNarrationAudioFailure failure = ChapterNarrationAudioFailure.create(
                ID,
                SEGMENT_ID,
                VOICE_ID,
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                1L,
                "RuntimeException",
                NOW
        );

        assertThat(failure.getId()).isEqualTo(ID);
        assertThat(failure.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(failure.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(failure.getOperation()).isEqualTo(NarrationAudioOperation.INITIAL_GENERATION);
        assertThat(failure.getStage()).isEqualTo(NarrationAudioFailureStage.TTS_SYNTHESIS);
        assertThat(failure.getAttemptedSynthesisRevision()).isEqualTo(1L);
        assertThat(failure.getFailureCount()).isEqualTo(1);
        assertThat(failure.getErrorType()).isEqualTo("RuntimeException");
        assertThat(failure.getErrorMessage()).isEqualTo("Narration TTS synthesis failed.");
        assertThat(failure.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(failure.getLastFailedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Should reject invalid attemptedSynthesisRevision (< 1) on creation")
    void shouldRejectInvalidAttemptedRevisionOnCreate() {
        assertThatThrownBy(() -> ChapterNarrationAudioFailure.create(
                ID, SEGMENT_ID, VOICE_ID, NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS, 0L, "RuntimeException", NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptedSynthesisRevision");

        assertThatThrownBy(() -> ChapterNarrationAudioFailure.create(
                ID, SEGMENT_ID, VOICE_ID, NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS, -5L, "RuntimeException", NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptedSynthesisRevision");
    }

    @Test
    @DisplayName("Should reject null mandatory parameters on creation")
    void shouldRejectNullParametersOnCreate() {
        assertThatThrownBy(() -> ChapterNarrationAudioFailure.create(
                null, SEGMENT_ID, VOICE_ID, NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, "Error", NOW
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ChapterNarrationAudioFailure.create(
                ID, null, VOICE_ID, NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, "Error", NOW
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ChapterNarrationAudioFailure.create(
                ID, SEGMENT_ID, null, NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, "Error", NOW
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ChapterNarrationAudioFailure.create(
                ID, SEGMENT_ID, VOICE_ID, null,
                NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, "Error", NOW
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ChapterNarrationAudioFailure.create(
                ID, SEGMENT_ID, VOICE_ID, NarrationAudioOperation.INITIAL_GENERATION,
                null, 1L, "Error", NOW
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ChapterNarrationAudioFailure.create(
                ID, SEGMENT_ID, VOICE_ID, NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, null, NOW
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ChapterNarrationAudioFailure.create(
                ID, SEGMENT_ID, VOICE_ID, NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, "Error", null
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should rehydrate failure record preserving all historical values")
    void shouldRehydratePreservingAllState() {
        Instant firstFailedAt = NOW.minusSeconds(3600);
        Instant lastFailedAt = NOW;

        ChapterNarrationAudioFailure failure = ChapterNarrationAudioFailure.rehydrate(
                ID,
                SEGMENT_ID,
                VOICE_ID,
                NarrationAudioOperation.REGENERATION,
                NarrationAudioFailureStage.MEDIA_UPLOAD,
                3L,
                5,
                "IOException",
                "Disk quota exceeded",
                firstFailedAt,
                lastFailedAt
        );

        assertThat(failure.getId()).isEqualTo(ID);
        assertThat(failure.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(failure.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(failure.getOperation()).isEqualTo(NarrationAudioOperation.REGENERATION);
        assertThat(failure.getStage()).isEqualTo(NarrationAudioFailureStage.MEDIA_UPLOAD);
        assertThat(failure.getAttemptedSynthesisRevision()).isEqualTo(3L);
        assertThat(failure.getFailureCount()).isEqualTo(5);
        assertThat(failure.getErrorType()).isEqualTo("IOException");
        assertThat(failure.getErrorMessage()).isEqualTo("Disk quota exceeded");
        assertThat(failure.getFirstFailedAt()).isEqualTo(firstFailedAt);
        assertThat(failure.getLastFailedAt()).isEqualTo(lastFailedAt);
    }

    @Test
    @DisplayName("Should reject rehydration when lastFailedAt is before firstFailedAt")
    void shouldRejectRehydrationWithInvalidTimestamps() {
        Instant firstFailedAt = NOW;
        Instant lastFailedAt = NOW.minusSeconds(10);

        assertThatThrownBy(() -> ChapterNarrationAudioFailure.rehydrate(
                ID, SEGMENT_ID, VOICE_ID, NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, 1, "Error", "Msg",
                firstFailedAt, lastFailedAt
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Thời điểm lỗi gần nhất không được trước thời điểm lỗi đầu tiên");
    }

    @Test
    @DisplayName("Should record subsequent failure incrementing failureCount and updating mutable fields with safe error message")
    void shouldRecordSubsequentFailure() {
        ChapterNarrationAudioFailure failure = ChapterNarrationAudioFailure.create(
                ID,
                SEGMENT_ID,
                VOICE_ID,
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                1L,
                "ConnectException",
                NOW
        );

        Instant subsequentTime = NOW.plusSeconds(300);
        failure.recordFailure(
                NarrationAudioOperation.REGENERATION,
                NarrationAudioFailureStage.MEDIA_UPLOAD,
                2L,
                "TimeoutException",
                subsequentTime
        );

        // Immutable fields preserved
        assertThat(failure.getId()).isEqualTo(ID);
        assertThat(failure.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(failure.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(failure.getFirstFailedAt()).isEqualTo(NOW);

        // Mutable fields updated & count incremented
        assertThat(failure.getOperation()).isEqualTo(NarrationAudioOperation.REGENERATION);
        assertThat(failure.getStage()).isEqualTo(NarrationAudioFailureStage.MEDIA_UPLOAD);
        assertThat(failure.getAttemptedSynthesisRevision()).isEqualTo(2L);
        assertThat(failure.getFailureCount()).isEqualTo(2);
        assertThat(failure.getErrorType()).isEqualTo("TimeoutException");
        assertThat(failure.getErrorMessage()).isEqualTo("Narration audio media upload failed.");
        assertThat(failure.getLastFailedAt()).isEqualTo(subsequentTime);
    }

    @Test
    @DisplayName("Should resolve bounded provider-neutral safe failure messages based on stage")
    void shouldResolveSafeFailureMessages() {
        assertThat(ChapterNarrationAudioFailure.resolveSafeErrorMessage(NarrationAudioFailureStage.TTS_SYNTHESIS))
                .isEqualTo("Narration TTS synthesis failed.");
        assertThat(ChapterNarrationAudioFailure.resolveSafeErrorMessage(NarrationAudioFailureStage.AUDIO_ENCODING))
                .isEqualTo("Narration audio encoding failed.");
        assertThat(ChapterNarrationAudioFailure.resolveSafeErrorMessage(NarrationAudioFailureStage.MEDIA_UPLOAD))
                .isEqualTo("Narration audio media upload failed.");
        assertThat(ChapterNarrationAudioFailure.resolveSafeErrorMessage(NarrationAudioFailureStage.ASSIGNMENT_PERSISTENCE))
                .isEqualTo("Narration audio assignment persistence failed.");
        assertThat(ChapterNarrationAudioFailure.resolveSafeErrorMessage(null))
                .isEqualTo("Narration audio generation failed.");
    }

    @Test
    @DisplayName("Should create failure record with AUDIO_ENCODING stage and provider-neutral error message")
    void shouldCreateFailureRecordWithAudioEncodingStage() {
        ChapterNarrationAudioFailure failure = ChapterNarrationAudioFailure.create(
                ID,
                SEGMENT_ID,
                VOICE_ID,
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.AUDIO_ENCODING,
                2L,
                "FfmpegEncodingException",
                NOW
        );

        assertThat(failure.getStage()).isEqualTo(NarrationAudioFailureStage.AUDIO_ENCODING);
        assertThat(failure.getErrorMessage()).isEqualTo("Narration audio encoding failed.");
        assertThat(failure.getErrorType()).isEqualTo("FfmpegEncodingException");
    }

    @Test
    @DisplayName("Should ensure recordFailure is atomic and leaves state completely unchanged on validation failure")
    void shouldEnsureRecordFailureIsAtomic() {
        ChapterNarrationAudioFailure failure = ChapterNarrationAudioFailure.create(
                ID,
                SEGMENT_ID,
                VOICE_ID,
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                1L,
                "ConnectException",
                NOW
        );

        // 1. Null operation -> no mutation
        assertThatThrownBy(() -> failure.recordFailure(
                null, NarrationAudioFailureStage.MEDIA_UPLOAD, 2L, "Error", NOW.plusSeconds(10)
        )).isInstanceOf(NullPointerException.class);
        assertStateUnchanged(failure, NarrationAudioOperation.INITIAL_GENERATION, NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, 1, "ConnectException", "Narration TTS synthesis failed.", NOW, NOW);

        // 2. Null stage -> no mutation
        assertThatThrownBy(() -> failure.recordFailure(
                NarrationAudioOperation.REGENERATION, null, 2L, "Error", NOW.plusSeconds(10)
        )).isInstanceOf(NullPointerException.class);
        assertStateUnchanged(failure, NarrationAudioOperation.INITIAL_GENERATION, NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, 1, "ConnectException", "Narration TTS synthesis failed.", NOW, NOW);

        // 3. Invalid revision (< 1) -> no mutation
        assertThatThrownBy(() -> failure.recordFailure(
                NarrationAudioOperation.REGENERATION, NarrationAudioFailureStage.MEDIA_UPLOAD, 0L, "Error", NOW.plusSeconds(10)
        )).isInstanceOf(IllegalArgumentException.class);
        assertStateUnchanged(failure, NarrationAudioOperation.INITIAL_GENERATION, NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, 1, "ConnectException", "Narration TTS synthesis failed.", NOW, NOW);

        // 4. Null / blank errorType -> no mutation
        assertThatThrownBy(() -> failure.recordFailure(
                NarrationAudioOperation.REGENERATION, NarrationAudioFailureStage.MEDIA_UPLOAD, 2L, null, NOW.plusSeconds(10)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> failure.recordFailure(
                NarrationAudioOperation.REGENERATION, NarrationAudioFailureStage.MEDIA_UPLOAD, 2L, "   ", NOW.plusSeconds(10)
        )).isInstanceOf(IllegalArgumentException.class);
        assertStateUnchanged(failure, NarrationAudioOperation.INITIAL_GENERATION, NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, 1, "ConnectException", "Narration TTS synthesis failed.", NOW, NOW);

        // 5. Null now -> no mutation
        assertThatThrownBy(() -> failure.recordFailure(
                NarrationAudioOperation.REGENERATION, NarrationAudioFailureStage.MEDIA_UPLOAD, 2L, "Error", null
        )).isInstanceOf(NullPointerException.class);
        assertStateUnchanged(failure, NarrationAudioOperation.INITIAL_GENERATION, NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, 1, "ConnectException", "Narration TTS synthesis failed.", NOW, NOW);

        // 6. now before lastFailedAt -> no mutation
        assertThatThrownBy(() -> failure.recordFailure(
                NarrationAudioOperation.REGENERATION, NarrationAudioFailureStage.MEDIA_UPLOAD, 2L, "Error", NOW.minusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertStateUnchanged(failure, NarrationAudioOperation.INITIAL_GENERATION, NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, 1, "ConnectException", "Narration TTS synthesis failed.", NOW, NOW);
    }

    private void assertStateUnchanged(
            ChapterNarrationAudioFailure failure,
            NarrationAudioOperation expectedOp,
            NarrationAudioFailureStage expectedStage,
            long expectedRevision,
            int expectedCount,
            String expectedType,
            String expectedMsg,
            Instant expectedFirstFailedAt,
            Instant expectedLastFailedAt
    ) {
        assertThat(failure.getId()).isEqualTo(ID);
        assertThat(failure.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(failure.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(failure.getOperation()).isEqualTo(expectedOp);
        assertThat(failure.getStage()).isEqualTo(expectedStage);
        assertThat(failure.getAttemptedSynthesisRevision()).isEqualTo(expectedRevision);
        assertThat(failure.getFailureCount()).isEqualTo(expectedCount);
        assertThat(failure.getErrorType()).isEqualTo(expectedType);
        assertThat(failure.getErrorMessage()).isEqualTo(expectedMsg);
        assertThat(failure.getFirstFailedAt()).isEqualTo(expectedFirstFailedAt);
        assertThat(failure.getLastFailedAt()).isEqualTo(expectedLastFailedAt);
    }

    @Test
    @DisplayName("Should sanitize error types properly")
    void shouldSanitizeErrorTypesProperly() {
        assertThat(ChapterNarrationAudioFailure.sanitizeErrorType(null)).isEqualTo("UnknownError");
        assertThat(ChapterNarrationAudioFailure.sanitizeErrorType(new IllegalStateException("msg"))).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("Should verify equality and hashCode based on ID")
    void shouldVerifyEqualityAndHashCode() {
        ChapterNarrationAudioFailure f1 = ChapterNarrationAudioFailure.create(
                ID, SEGMENT_ID, VOICE_ID, NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, "Error", NOW
        );
        ChapterNarrationAudioFailure f2 = ChapterNarrationAudioFailure.create(
                ID, SEGMENT_ID, VOICE_ID, NarrationAudioOperation.REGENERATION,
                NarrationAudioFailureStage.MEDIA_UPLOAD, 2L, "OtherError", NOW.plusSeconds(10)
        );
        ChapterNarrationAudioFailure f3 = ChapterNarrationAudioFailure.create(
                UUID.randomUUID(), SEGMENT_ID, VOICE_ID, NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS, 1L, "Error", NOW
        );

        assertThat(f1).isEqualTo(f2);
        assertThat(f1.hashCode()).isEqualTo(f2.hashCode());
        assertThat(f1).isNotEqualTo(f3);
    }
}
