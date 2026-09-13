package com.universe.novel.application.narration;

import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.NarrationAudioFailureStage;
import com.universe.novel.domain.narration.NarrationAudioOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChapterNarrationAudioHealthResolver Unit Tests (MS-04.9H.8C2)")
class ChapterNarrationAudioHealthResolverTest {

    private static final UUID SEGMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AUDIO_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID FAILURE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");

    private ChapterNarrationAudio createAudio(long generatedRevision) {
        return ChapterNarrationAudio.create(AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, generatedRevision, NOW);
    }

    private ChapterNarrationAudioFailure createFailure(long attemptedRevision) {
        return ChapterNarrationAudioFailure.create(
                FAILURE_ID, SEGMENT_ID, VOICE_ID,
                NarrationAudioOperation.INITIAL_GENERATION, NarrationAudioFailureStage.TTS_SYNTHESIS,
                attemptedRevision, "TimeoutException", NOW
        );
    }

    @Test
    @DisplayName("1. Audio revision == current revision + ghost failure -> READY, no relevant diagnostic")
    void shouldResolveReadyAndSuppressGhostFailureWhenAudioCompatible() {
        ChapterNarrationAudio audio = createAudio(2L);
        ChapterNarrationAudioFailure ghostFailure = createFailure(1L);

        ChapterNarrationAudioHealthResolution resolution =
                ChapterNarrationAudioHealthResolver.resolve(audio, ghostFailure, 2L);

        assertThat(resolution.status()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
        assertThat(resolution.isPlayable()).isTrue();
        assertThat(resolution.isFailureRelevant()).isFalse();
        assertThat(resolution.relevantFailure()).isNull();
        assertThat(resolution.optionalRelevantFailure()).isEmpty();
    }

    @Test
    @DisplayName("1b. Audio revision == current revision + no failure -> READY, no diagnostic")
    void shouldResolveReadyWithNoFailure() {
        ChapterNarrationAudio audio = createAudio(2L);

        ChapterNarrationAudioHealthResolution resolution =
                ChapterNarrationAudioHealthResolver.resolve(audio, null, 2L);

        assertThat(resolution.status()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
        assertThat(resolution.isPlayable()).isTrue();
        assertThat(resolution.isFailureRelevant()).isFalse();
        assertThat(resolution.relevantFailure()).isNull();
    }

    @Test
    @DisplayName("1c. Audio revision == current revision + same-revision ghost failure (A == R, F == R) -> READY, failure suppressed")
    void shouldResolveReadyAndSuppressSameRevisionGhostFailureWhenAudioCompatible() {
        ChapterNarrationAudio audio = createAudio(2L); // A = 2
        ChapterNarrationAudioFailure sameRevisionFailure = createFailure(2L); // F = 2, R = 2

        ChapterNarrationAudioHealthResolution resolution =
                ChapterNarrationAudioHealthResolver.resolve(audio, sameRevisionFailure, 2L);

        assertThat(resolution.status()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
        assertThat(resolution.isPlayable()).isTrue();
        assertThat(resolution.isFailureRelevant()).isFalse();
        assertThat(resolution.relevantFailure()).isNull();
        assertThat(resolution.optionalRelevantFailure()).isEmpty();
    }

    @Test
    @DisplayName("2. Outdated audio + failure for current revision -> OUTDATED, diagnostic relevant")
    void shouldResolveOutdatedWithRelevantDiagnosticWhenRegenerationFailedForCurrentRevision() {
        ChapterNarrationAudio audio = createAudio(1L); // audio at rev 1
        ChapterNarrationAudioFailure currentFailure = createFailure(2L); // voice at rev 2

        ChapterNarrationAudioHealthResolution resolution =
                ChapterNarrationAudioHealthResolver.resolve(audio, currentFailure, 2L);

        assertThat(resolution.status()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
        assertThat(resolution.isPlayable()).isTrue();
        assertThat(resolution.isFailureRelevant()).isTrue();
        assertThat(resolution.relevantFailure()).isEqualTo(currentFailure);
        assertThat(resolution.optionalRelevantFailure()).contains(currentFailure);
    }

    @Test
    @DisplayName("3. Outdated audio + old failure -> OUTDATED, diagnostic suppressed")
    void shouldResolveOutdatedAndSuppressOldFailure() {
        ChapterNarrationAudio audio = createAudio(1L); // audio at rev 1
        ChapterNarrationAudioFailure oldFailure = createFailure(1L); // failure at rev 1, voice at rev 2

        ChapterNarrationAudioHealthResolution resolution =
                ChapterNarrationAudioHealthResolver.resolve(audio, oldFailure, 2L);

        assertThat(resolution.status()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
        assertThat(resolution.isPlayable()).isTrue();
        assertThat(resolution.isFailureRelevant()).isFalse();
        assertThat(resolution.relevantFailure()).isNull();
    }

    @Test
    @DisplayName("3b. Outdated audio + no failure -> OUTDATED, diagnostic absent")
    void shouldResolveOutdatedWithNoFailure() {
        ChapterNarrationAudio audio = createAudio(1L);

        ChapterNarrationAudioHealthResolution resolution =
                ChapterNarrationAudioHealthResolver.resolve(audio, null, 2L);

        assertThat(resolution.status()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
        assertThat(resolution.isPlayable()).isTrue();
        assertThat(resolution.isFailureRelevant()).isFalse();
        assertThat(resolution.relevantFailure()).isNull();
    }

    @Test
    @DisplayName("4. No audio + current-revision failure -> FAILED, diagnostic relevant")
    void shouldResolveFailedWhenNoAudioAndCurrentRevisionFailureExists() {
        ChapterNarrationAudioFailure failure = createFailure(2L);

        ChapterNarrationAudioHealthResolution resolution =
                ChapterNarrationAudioHealthResolver.resolve(null, failure, 2L);

        assertThat(resolution.status()).isEqualTo(ChapterNarrationAudioHealthStatus.FAILED);
        assertThat(resolution.isPlayable()).isFalse();
        assertThat(resolution.isFailureRelevant()).isTrue();
        assertThat(resolution.relevantFailure()).isEqualTo(failure);
    }

    @Test
    @DisplayName("5. No audio + old-revision failure -> MISSING, diagnostic suppressed")
    void shouldResolveMissingWhenNoAudioAndFailureBelongsToOlderRevision() {
        ChapterNarrationAudioFailure oldFailure = createFailure(1L); // voice at rev 2

        ChapterNarrationAudioHealthResolution resolution =
                ChapterNarrationAudioHealthResolver.resolve(null, oldFailure, 2L);

        assertThat(resolution.status()).isEqualTo(ChapterNarrationAudioHealthStatus.MISSING);
        assertThat(resolution.isPlayable()).isFalse();
        assertThat(resolution.isFailureRelevant()).isFalse();
        assertThat(resolution.relevantFailure()).isNull();
    }

    @Test
    @DisplayName("6. No audio + no failure -> MISSING")
    void shouldResolveMissingWhenNoAudioAndNoFailure() {
        ChapterNarrationAudioHealthResolution resolution =
                ChapterNarrationAudioHealthResolver.resolve(null, null, 1L);

        assertThat(resolution.status()).isEqualTo(ChapterNarrationAudioHealthStatus.MISSING);
        assertThat(resolution.isPlayable()).isFalse();
        assertThat(resolution.isFailureRelevant()).isFalse();
        assertThat(resolution.relevantFailure()).isNull();
    }

    @Test
    @DisplayName("7. Resolves using Optionals correctly")
    void shouldResolveWithOptionals() {
        ChapterNarrationAudio audio = createAudio(2L);
        ChapterNarrationAudioFailure failure = createFailure(2L);

        ChapterNarrationAudioHealthResolution res1 =
                ChapterNarrationAudioHealthResolver.resolveFromOptionals(Optional.of(audio), Optional.of(failure), 2L);
        assertThat(res1.status()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
        assertThat(res1.relevantFailure()).isNull();

        ChapterNarrationAudioHealthResolution res2 =
                ChapterNarrationAudioHealthResolver.resolveFromOptionals(Optional.empty(), Optional.of(failure), 2L);
        assertThat(res2.status()).isEqualTo(ChapterNarrationAudioHealthStatus.FAILED);
        assertThat(res2.relevantFailure()).isEqualTo(failure);

        ChapterNarrationAudioHealthResolution res3 =
                ChapterNarrationAudioHealthResolver.resolveFromOptionals(Optional.empty(), Optional.empty(), 2L);
        assertThat(res3.status()).isEqualTo(ChapterNarrationAudioHealthStatus.MISSING);
        assertThat(res3.relevantFailure()).isNull();
    }
}
