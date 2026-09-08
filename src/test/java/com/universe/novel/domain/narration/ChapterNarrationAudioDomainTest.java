package com.universe.novel.domain.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChapterNarrationAudio Domain Model Tests")
class ChapterNarrationAudioDomainTest {

    private static final UUID ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SEGMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID VOICE_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    @Test
    @DisplayName("Should successfully create a valid ChapterNarrationAudio assignment with null version")
    void shouldCreateValidAssignment() {
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                ID,
                SEGMENT_ID,
                VOICE_ID,
                MEDIA_ASSET_ID,
                1L,
                NOW
        );

        assertThat(audio.getId()).isEqualTo(ID);
        assertThat(audio.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(audio.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(audio.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(1L);
        assertThat(audio.getVersion()).isNull();
        assertThat(audio.getCreatedAt()).isEqualTo(NOW);
        assertThat(audio.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Should reject invalid generatedSynthesisRevision (< 1)")
    void shouldRejectInvalidRevision() {
        assertThatThrownBy(() -> ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 0L, NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Synthesis revision must be at least 1");

        assertThatThrownBy(() -> ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, -5L, NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Synthesis revision must be at least 1");
    }

    @Test
    @DisplayName("Should reject null IDs or null timestamps on creation")
    void shouldRejectNullArgumentsOnCreate() {
        assertThatThrownBy(() -> ChapterNarrationAudio.create(null, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ChapterNarrationAudio.create(ID, null, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ChapterNarrationAudio.create(ID, SEGMENT_ID, null, MEDIA_ASSET_ID, 1L, NOW))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ChapterNarrationAudio.create(ID, SEGMENT_ID, VOICE_ID, null, 1L, NOW))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ChapterNarrationAudio.create(ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should successfully rehydrate audio assignment preserving timestamps and version")
    void shouldRehydratePreservingState() {
        Instant createdAt = NOW.minusSeconds(100);
        Instant updatedAt = NOW;
        Long version = 5L;

        ChapterNarrationAudio audio = ChapterNarrationAudio.rehydrate(
                ID,
                SEGMENT_ID,
                VOICE_ID,
                MEDIA_ASSET_ID,
                3L,
                version,
                createdAt,
                updatedAt
        );

        assertThat(audio.getId()).isEqualTo(ID);
        assertThat(audio.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(audio.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(audio.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(3L);
        assertThat(audio.getVersion()).isEqualTo(version);
        assertThat(audio.getCreatedAt()).isEqualTo(createdAt);
        assertThat(audio.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("Should reject rehydration if updatedAt is before createdAt")
    void shouldRejectRehydrationWithInvalidTimestamps() {
        Instant createdAt = NOW;
        Instant updatedAt = NOW.minusSeconds(10);

        assertThatThrownBy(() -> ChapterNarrationAudio.rehydrate(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, 0L, createdAt, updatedAt
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Thời gian cập nhật không được trước thời gian tạo");
    }

    @Test
    @DisplayName("Should return compatible true when revisions match")
    void shouldReturnCompatibleTrueWhenRevisionsMatch() {
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 2L, NOW
        );

        assertThat(audio.isCompatibleWith(2L)).isTrue();
    }

    @Test
    @DisplayName("Should return compatible false when revisions differ")
    void shouldReturnCompatibleFalseWhenRevisionsDiffer() {
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 2L, NOW
        );

        assertThat(audio.isCompatibleWith(1L)).isFalse();
        assertThat(audio.isCompatibleWith(3L)).isFalse();
    }

    @Test
    @DisplayName("Should evaluate compatibility with ManagedVoice instance correctly")
    void shouldEvaluateCompatibilityWithManagedVoice() {
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
        );

        ManagedVoice matchingVoice = ManagedVoice.create(
                VOICE_ID, "voice-north-01", "Giọng Bắc 01", "provider-v1",
                1, false, NOW
        );

        ManagedVoice differentVoiceId = ManagedVoice.create(
                UUID.randomUUID(), "voice-south-01", "Giọng Nam 01", "provider-v2",
                2, false, NOW
        );

        assertThat(audio.isCompatibleWith(matchingVoice)).isTrue();
        assertThat(audio.isCompatibleWith(differentVoiceId)).isFalse();
        assertThat(audio.isCompatibleWith((ManagedVoice) null)).isFalse();

        // Update provider mapping -> bumps synthesisRevision to 2
        matchingVoice.changeProviderMapping("provider-v1-new", NOW.plusSeconds(10));
        assertThat(audio.isCompatibleWith(matchingVoice)).isFalse();
    }

    @Test
    @DisplayName("Should verify equality and hashCode based on ID")
    void shouldVerifyEqualityAndHashCode() {
        ChapterNarrationAudio audio1 = ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
        );
        ChapterNarrationAudio audio2 = ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, UUID.randomUUID(), 2L, NOW.plusSeconds(5)
        );
        ChapterNarrationAudio audio3 = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
        );

        assertThat(audio1).isEqualTo(audio2);
        assertThat(audio1.hashCode()).isEqualTo(audio2.hashCode());
        assertThat(audio1).isNotEqualTo(audio3);
    }

    @Test
    @DisplayName("Should successfully replace audio preserving assignment identity and updating asset/revision/updatedAt together")
    void shouldSuccessfullyReplaceAudioPreservingIdentity() {
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
        );

        UUID replacementMediaAssetId = UUID.fromString("50000000-0000-0000-0000-000000000005");
        Instant replacementTime = NOW.plusSeconds(120);

        audio.replaceSuccessfulAudio(replacementMediaAssetId, 3L, replacementTime);

        // Immutable identity and creation timestamps preserved
        assertThat(audio.getId()).isEqualTo(ID);
        assertThat(audio.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(audio.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(audio.getCreatedAt()).isEqualTo(NOW);

        // Mutable audio asset, revision, and updated timestamp updated together
        assertThat(audio.getMediaAssetId()).isEqualTo(replacementMediaAssetId);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(3L);
        assertThat(audio.getUpdatedAt()).isEqualTo(replacementTime);
        assertThat(audio.isCompatibleWith(3L)).isTrue();
        assertThat(audio.isCompatibleWith(1L)).isFalse();
    }

    @Test
    @DisplayName("Should reject audio replacement with invalid parameters")
    void shouldRejectInvalidReplacementParameters() {
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
        );
        UUID newAssetId = UUID.randomUUID();

        // Null mediaAssetId
        assertThatThrownBy(() -> audio.replaceSuccessfulAudio(null, 2L, NOW.plusSeconds(10)))
                .isInstanceOf(NullPointerException.class);

        // Revision < 1
        assertThatThrownBy(() -> audio.replaceSuccessfulAudio(newAssetId, 0L, NOW.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Synthesis revision must be at least 1");

        assertThatThrownBy(() -> audio.replaceSuccessfulAudio(newAssetId, -1L, NOW.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Synthesis revision must be at least 1");

        // Null timestamp
        assertThatThrownBy(() -> audio.replaceSuccessfulAudio(newAssetId, 2L, null))
                .isInstanceOf(NullPointerException.class);

        // Timestamp before createdAt
        assertThatThrownBy(() -> audio.replaceSuccessfulAudio(newAssetId, 2L, NOW.minusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Thời gian thay thế không được trước thời gian tạo");
    }

    @Test
    @DisplayName("Should not mutate version during audio replacement")
    void shouldNotMutateVersionDuringAudioReplacement() {
        ChapterNarrationAudio audio = ChapterNarrationAudio.rehydrate(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, 7L, NOW, NOW
        );
        assertThat(audio.getVersion()).isEqualTo(7L);

        UUID replacementMediaAssetId = UUID.randomUUID();
        audio.replaceSuccessfulAudio(replacementMediaAssetId, 2L, NOW.plusSeconds(30));

        assertThat(audio.getMediaAssetId()).isEqualTo(replacementMediaAssetId);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(2L);
        assertThat(audio.getVersion()).isEqualTo(7L); // Version remains unchanged in memory
    }
}
