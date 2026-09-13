package com.universe.novel.domain.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChapterNarrationPlayback Domain Model Tests")
class ChapterNarrationPlaybackDomainTest {

    private static final UUID PLAYBACK_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ARTIFACT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID CHAPTER_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID VOICE_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("10000000-0000-0000-0000-000000000005");
    private static final UUID SEGMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000006");
    private static final UUID FOREIGN_ID = UUID.fromString("10000000-0000-0000-0000-000000000099");
    private static final Instant NOW = Instant.parse("2026-09-08T03:00:00Z");
    private static final String MANIFEST_HASH = "a".repeat(64);

    @Test
    @DisplayName("Should create stable playback with temporary null current artifact")
    void shouldCreateStablePlaybackWithNullCurrentArtifact() {
        ChapterNarrationPlayback playback = ChapterNarrationPlayback.create(
                PLAYBACK_ID,
                CHAPTER_ID,
                VOICE_ID,
                NOW
        );

        assertThat(playback.getId()).isEqualTo(PLAYBACK_ID);
        assertThat(playback.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(playback.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(playback.getCurrentArtifactId()).isNull();
        assertThat(playback.getVersion()).isNull();
        assertThat(playback.getCreatedAt()).isEqualTo(NOW);
        assertThat(playback.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Should switch current artifact through explicit domain operation")
    void shouldSwitchCurrentArtifact() {
        ChapterNarrationPlayback playback = ChapterNarrationPlayback.create(
                PLAYBACK_ID,
                CHAPTER_ID,
                VOICE_ID,
                NOW
        );
        ChapterNarrationPlaybackArtifact artifact = createArtifact(playback);
        Instant switchTime = NOW.plusSeconds(30);

        playback.switchCurrentArtifact(artifact, switchTime);

        assertThat(playback.getCurrentArtifactId()).isEqualTo(ARTIFACT_ID);
        assertThat(playback.getUpdatedAt()).isEqualTo(switchTime);
    }

    @Test
    @DisplayName("Should reject invalid playback arguments and timestamps")
    void shouldRejectInvalidPlaybackArguments() {
        assertThatThrownBy(() -> ChapterNarrationPlayback.create(null, CHAPTER_ID, VOICE_ID, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ChapterNarrationPlayback.create(PLAYBACK_ID, null, VOICE_ID, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ChapterNarrationPlayback.create(PLAYBACK_ID, CHAPTER_ID, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ChapterNarrationPlayback.create(PLAYBACK_ID, CHAPTER_ID, VOICE_ID, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ChapterNarrationPlayback.rehydrate(
                PLAYBACK_ID,
                CHAPTER_ID,
                VOICE_ID,
                ARTIFACT_ID,
                0L,
                NOW,
                NOW.minusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject invalid current artifact switch")
    void shouldRejectInvalidSwitch() {
        ChapterNarrationPlayback playback = ChapterNarrationPlayback.create(
                PLAYBACK_ID,
                CHAPTER_ID,
                VOICE_ID,
                NOW
        );

        assertThatThrownBy(() -> playback.switchCurrentArtifact(null, NOW.plusSeconds(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> playback.switchCurrentArtifact(createArtifact(playback), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> playback.switchCurrentArtifact(createArtifact(playback), NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should create immutable playback artifact deriving ownership from playback")
    void shouldCreatePlaybackArtifactDerivingOwnershipFromPlayback() {
        ChapterNarrationPlayback playback = ChapterNarrationPlayback.create(
                PLAYBACK_ID,
                CHAPTER_ID,
                VOICE_ID,
                NOW
        );

        ChapterNarrationPlaybackArtifact artifact = createArtifact(playback);

        assertThat(artifact.getId()).isEqualTo(ARTIFACT_ID);
        assertThat(artifact.getPlaybackId()).isEqualTo(PLAYBACK_ID);
        assertThat(artifact.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(artifact.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(artifact.getSourceContentVersion()).isEqualTo(3L);
        assertThat(artifact.getSynthesisRevision()).isEqualTo(2L);
        assertThat(artifact.getManifestHash()).isEqualTo(MANIFEST_HASH);
        assertThat(artifact.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(artifact.getDurationMillis()).isEqualTo(12_345L);
        assertThat(artifact.getCueCount()).isEqualTo(1);
        assertThat(artifact.getCodecMimeType()).isEqualTo("audio/mpeg");
        assertThat(artifact.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Should reject foreign playback artifact on pointer switch")
    void shouldRejectForeignPlaybackArtifactOnSwitch() {
        ChapterNarrationPlayback playback = ChapterNarrationPlayback.create(
                PLAYBACK_ID,
                CHAPTER_ID,
                VOICE_ID,
                NOW
        );
        ChapterNarrationPlaybackArtifact foreignPlaybackArtifact = ChapterNarrationPlaybackArtifact.rehydrate(
                ARTIFACT_ID,
                FOREIGN_ID,
                CHAPTER_ID,
                VOICE_ID,
                3L,
                2L,
                MANIFEST_HASH,
                MEDIA_ASSET_ID,
                12_345L,
                1,
                "audio/mpeg",
                NOW
        );

        assertThatThrownBy(() -> playback.switchCurrentArtifact(foreignPlaybackArtifact, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Artifact does not belong");
        assertThat(playback.getCurrentArtifactId()).isNull();
    }

    @Test
    @DisplayName("Should reject artifact whose chapter or voice ownership mismatches")
    void shouldRejectChapterOrVoiceOwnershipMismatchOnSwitch() {
        ChapterNarrationPlayback playback = ChapterNarrationPlayback.create(
                PLAYBACK_ID,
                CHAPTER_ID,
                VOICE_ID,
                NOW
        );
        ChapterNarrationPlaybackArtifact wrongChapter = ChapterNarrationPlaybackArtifact.rehydrate(
                ARTIFACT_ID,
                PLAYBACK_ID,
                FOREIGN_ID,
                VOICE_ID,
                3L,
                2L,
                MANIFEST_HASH,
                MEDIA_ASSET_ID,
                12_345L,
                1,
                "audio/mpeg",
                NOW
        );
        ChapterNarrationPlaybackArtifact wrongVoice = ChapterNarrationPlaybackArtifact.rehydrate(
                ARTIFACT_ID,
                PLAYBACK_ID,
                CHAPTER_ID,
                FOREIGN_ID,
                3L,
                2L,
                MANIFEST_HASH,
                MEDIA_ASSET_ID,
                12_345L,
                1,
                "audio/mpeg",
                NOW
        );

        assertThatThrownBy(() -> playback.switchCurrentArtifact(wrongChapter, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> playback.switchCurrentArtifact(wrongVoice, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(playback.getCurrentArtifactId()).isNull();
    }

    @Test
    @DisplayName("Should reject real pointer switch when timestamp moves updatedAt backwards")
    void shouldRejectRealSwitchWhenUpdatedAtWouldMoveBackwards() {
        ChapterNarrationPlayback playback = ChapterNarrationPlayback.create(
                PLAYBACK_ID,
                CHAPTER_ID,
                VOICE_ID,
                NOW
        );
        ChapterNarrationPlaybackArtifact first = createArtifact(playback);
        playback.switchCurrentArtifact(first, NOW.plusSeconds(30));
        ChapterNarrationPlaybackArtifact second = artifactWith(
                UUID.fromString("10000000-0000-0000-0000-000000000007"),
                playback,
                3L,
                2L,
                MANIFEST_HASH,
                MEDIA_ASSET_ID,
                12_345L,
                1,
                "audio/mpeg",
                NOW.plusSeconds(40)
        );

        assertThatThrownBy(() -> playback.switchCurrentArtifact(second, NOW.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updatedAt");
        assertThat(playback.getCurrentArtifactId()).isEqualTo(ARTIFACT_ID);
        assertThat(playback.getUpdatedAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    @DisplayName("Should keep idempotent same-artifact switch as no-op")
    void shouldKeepSameArtifactSwitchAsNoOp() {
        ChapterNarrationPlayback playback = ChapterNarrationPlayback.create(
                PLAYBACK_ID,
                CHAPTER_ID,
                VOICE_ID,
                NOW
        );
        ChapterNarrationPlaybackArtifact artifact = createArtifact(playback);
        Instant firstSwitchTime = NOW.plusSeconds(30);
        playback.switchCurrentArtifact(artifact, firstSwitchTime);

        playback.switchCurrentArtifact(artifact, NOW.plusSeconds(10));

        assertThat(playback.getCurrentArtifactId()).isEqualTo(ARTIFACT_ID);
        assertThat(playback.getUpdatedAt()).isEqualTo(firstSwitchTime);
    }

    @Test
    @DisplayName("Should reject invalid playback artifact state")
    void shouldRejectInvalidPlaybackArtifactState() {
        ChapterNarrationPlayback playback = ChapterNarrationPlayback.create(
                PLAYBACK_ID,
                CHAPTER_ID,
                VOICE_ID,
                NOW
        );

        assertThatThrownBy(() -> artifactWith(ARTIFACT_ID, playback, 0L, 1L, MANIFEST_HASH,
                MEDIA_ASSET_ID, 1L, 1, "audio/mpeg", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> artifactWith(ARTIFACT_ID, playback, 1L, 0L, MANIFEST_HASH,
                MEDIA_ASSET_ID, 1L, 1, "audio/mpeg", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> artifactWith(ARTIFACT_ID, playback, 1L, 1L, "ABC",
                MEDIA_ASSET_ID, 1L, 1, "audio/mpeg", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> artifactWith(ARTIFACT_ID, playback, 1L, 1L, MANIFEST_HASH,
                MEDIA_ASSET_ID, 0L, 1, "audio/mpeg", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> artifactWith(ARTIFACT_ID, playback, 1L, 1L, MANIFEST_HASH,
                MEDIA_ASSET_ID, 1L, -1, "audio/mpeg", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> artifactWith(ARTIFACT_ID, playback, 1L, 1L, MANIFEST_HASH,
                MEDIA_ASSET_ID, 1L, 1, " ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> artifactWith(ARTIFACT_ID, null, 1L, 1L, MANIFEST_HASH,
                MEDIA_ASSET_ID, 1L, 1, "audio/mpeg", NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should create playback cue and reject invalid cue boundaries")
    void shouldCreateAndValidateCue() {
        ChapterNarrationPlaybackCue cue = ChapterNarrationPlaybackCue.create(
                ARTIFACT_ID,
                0,
                SEGMENT_ID,
                0,
                100L,
                900L
        );

        assertThat(cue.getArtifactId()).isEqualTo(ARTIFACT_ID);
        assertThat(cue.getCueOrdinal()).isZero();
        assertThat(cue.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(cue.getSegmentIndex()).isZero();
        assertThat(cue.getStartMillis()).isEqualTo(100L);
        assertThat(cue.getEndMillis()).isEqualTo(900L);

        assertThatThrownBy(() -> ChapterNarrationPlaybackCue.create(null, 0, SEGMENT_ID, 0, 0L, 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ChapterNarrationPlaybackCue.create(ARTIFACT_ID, -1, SEGMENT_ID, 0, 0L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChapterNarrationPlaybackCue.create(ARTIFACT_ID, 0, null, 0, 0L, 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ChapterNarrationPlaybackCue.create(ARTIFACT_ID, 0, SEGMENT_ID, -1, 0L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChapterNarrationPlaybackCue.create(ARTIFACT_ID, 0, SEGMENT_ID, 0, -1L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChapterNarrationPlaybackCue.create(ARTIFACT_ID, 0, SEGMENT_ID, 0, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ChapterNarrationPlaybackArtifact createArtifact(ChapterNarrationPlayback playback) {
        return artifactWith(
                ARTIFACT_ID,
                playback,
                3L,
                2L,
                MANIFEST_HASH,
                MEDIA_ASSET_ID,
                12_345L,
                1,
                "audio/mpeg",
                NOW
        );
    }

    private static ChapterNarrationPlaybackArtifact artifactWith(
            UUID artifactId,
            ChapterNarrationPlayback playback,
            long sourceContentVersion,
            long synthesisRevision,
            String manifestHash,
            UUID mediaAssetId,
            long durationMillis,
            int cueCount,
            String codecMimeType,
            Instant createdAt
    ) {
        return ChapterNarrationPlaybackArtifact.create(
                artifactId,
                playback,
                sourceContentVersion,
                synthesisRevision,
                manifestHash,
                mediaAssetId,
                durationMillis,
                cueCount,
                codecMimeType,
                createdAt
        );
    }
}
