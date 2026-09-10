package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationPlaybackCueRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.PlaybackManagedVoiceQueryPort;
import com.universe.novel.application.ports.PlaybackManagedVoiceQueryPort.PlaybackManagedVoice;
import com.universe.novel.application.ports.PublicChapterNarrationPlaybackQueryPort;
import com.universe.novel.application.ports.PublicChapterNarrationPlaybackQueryPort.PublicChapterNarrationPlaybackSnapshot;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackAvailability;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackDTO;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackFreshness;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackCue;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetPublicChapterNarrationPlaybackUseCase")
class GetPublicChapterNarrationPlaybackUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PLAYBACK_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ARTIFACT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID MEDIA_VERSION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID SEGMENT_0_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID SEGMENT_1_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID FOREIGN_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final String VOICE_KEY = "kiemlai-male-01";
    private static final long CONTENT_VERSION = 12L;
    private static final long SYNTHESIS_REVISION = 4L;
    private static final long DURATION_MILLIS = 4_000L;

    @Mock
    private PlaybackManagedVoiceQueryPort playbackManagedVoiceQueryPort;

    @Mock
    private PublicChapterNarrationPlaybackQueryPort playbackQueryPort;

    @Mock
    private ChapterNarrationPlaybackCueRepositoryPort cueRepositoryPort;

    @Mock
    private MediaContract mediaContract;

    private GetPublicChapterNarrationPlaybackUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetPublicChapterNarrationPlaybackUseCase(
                playbackManagedVoiceQueryPort,
                playbackQueryPort,
                cueRepositoryPort,
                mediaContract
        );
    }

    @Test
    @DisplayName("CURRENT artifact remains READY and playable with ordered cues")
    void shouldReturnCurrentReadyPlaybackWithOrderedCues() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(snapshot(CONTENT_VERSION, SYNTHESIS_REVISION, 2));
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID)).thenReturn(List.of(
                cue(1, SEGMENT_1_ID, 1, 2_500L, 4_000L),
                cue(0, SEGMENT_0_ID, 0, 0L, 2_000L)
        ));
        givenEligibleMedia();

        PublicChapterNarrationPlaybackDTO result = execute(null);

        assertThat(result.availability()).isEqualTo(PublicChapterNarrationPlaybackAvailability.READY);
        assertThat(result.freshness()).isEqualTo(PublicChapterNarrationPlaybackFreshness.CURRENT);
        assertThat(result.playable()).isTrue();
        assertThat(result.artifactId()).isEqualTo(ARTIFACT_ID);
        assertThat(result.audioUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_ID + "/content");
        assertThat(result.codecMimeType()).isEqualTo("audio/mpeg");
        assertThat(result.durationMillis()).isEqualTo(DURATION_MILLIS);
        assertThat(result.cues()).extracting(
                        cue -> cue.cueOrdinal(),
                        cue -> cue.segmentId(),
                        cue -> cue.segmentIndex(),
                        cue -> cue.startMillis(),
                        cue -> cue.endMillis()
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, SEGMENT_0_ID, 0, 0L, 2_000L),
                        org.assertj.core.groups.Tuple.tuple(1, SEGMENT_1_ID, 1, 2_500L, 4_000L)
                );
    }

    @Test
    @DisplayName("STALE_VOICE remains READY and playable")
    void shouldReturnPlayableStaleVoiceArtifact() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(snapshot(CONTENT_VERSION, SYNTHESIS_REVISION - 1));
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID))
                .thenReturn(List.of(cue(0, SEGMENT_0_ID, 0, 0L, 2_000L)));
        givenEligibleMedia();

        PublicChapterNarrationPlaybackDTO result = execute(null);

        assertThat(result.freshness()).isEqualTo(PublicChapterNarrationPlaybackFreshness.STALE_VOICE);
        assertThat(result.playable()).isTrue();
        assertThat(result.audioUrl()).isNotNull();
    }

    @Test
    @DisplayName("STALE_CONTENT wins and skips cues and Media")
    void shouldReturnStaleContentBeforeCueOrMediaValidation() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(snapshot(CONTENT_VERSION - 1, SYNTHESIS_REVISION - 1));

        PublicChapterNarrationPlaybackDTO result = execute(null);

        assertThat(result.availability()).isEqualTo(PublicChapterNarrationPlaybackAvailability.READY);
        assertThat(result.freshness()).isEqualTo(PublicChapterNarrationPlaybackFreshness.STALE_CONTENT);
        assertThat(result.playable()).isFalse();
        assertThat(result.artifactId()).isEqualTo(ARTIFACT_ID);
        assertThat(result.audioUrl()).isNull();
        assertThat(result.codecMimeType()).isNull();
        assertThat(result.durationMillis()).isNull();
        assertThat(result.cues()).isEmpty();
        verifyNoInteractions(cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("No playback is MISSING")
    void shouldReturnMissingWhenPlaybackDoesNotExist() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(chapterOnlySnapshot());

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Null current artifact pointer is MISSING")
    void shouldReturnMissingWhenCurrentArtifactPointerIsNull() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(new PublicChapterNarrationPlaybackSnapshot(
                CHAPTER_ID, CONTENT_VERSION, PLAYBACK_ID, null,
                null, null, null, null, null, null, null, null, null, null
        ));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Missing pointed artifact is MISSING")
    void shouldReturnMissingWhenCurrentArtifactDoesNotExist() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(new PublicChapterNarrationPlaybackSnapshot(
                CHAPTER_ID, CONTENT_VERSION, PLAYBACK_ID, ARTIFACT_ID,
                null, null, null, null, null, null, null, null, null, null
        ));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Artifact ownership inconsistency is MISSING")
    void shouldReturnMissingForArtifactOwnershipInconsistency() {
        givenPreferredVoice(activeVoice());
        PublicChapterNarrationPlaybackSnapshot value = snapshot(CONTENT_VERSION, SYNTHESIS_REVISION);
        givenSnapshot(new PublicChapterNarrationPlaybackSnapshot(
                value.chapterId(), value.chapterContentVersion(), value.playbackId(), value.currentArtifactId(),
                value.artifactId(), FOREIGN_ID, value.artifactChapterId(), value.artifactManagedVoiceId(),
                value.artifactSourceContentVersion(), value.artifactSynthesisRevision(), value.mediaAssetId(),
                value.durationMillis(), value.cueCount(), value.codecMimeType()
        ));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Invalid basic artifact metadata is MISSING before cues or Media")
    void shouldReturnMissingForInvalidBasicArtifactMetadata() {
        givenPreferredVoice(activeVoice());
        PublicChapterNarrationPlaybackSnapshot value = snapshot(CONTENT_VERSION, SYNTHESIS_REVISION);
        givenSnapshot(new PublicChapterNarrationPlaybackSnapshot(
                value.chapterId(), value.chapterContentVersion(), value.playbackId(), value.currentArtifactId(),
                value.artifactId(), value.artifactPlaybackId(), value.artifactChapterId(),
                value.artifactManagedVoiceId(), value.artifactSourceContentVersion(),
                value.artifactSynthesisRevision(), value.mediaAssetId(), 0L, value.cueCount(), value.codecMimeType()
        ));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Cue validation remains authoritative on playable paths")
    void shouldReturnMissingForInvalidCueTiming() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(snapshot(CONTENT_VERSION, SYNTHESIS_REVISION));
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID))
                .thenReturn(List.of(cue(0, SEGMENT_0_ID, 0, 0L, DURATION_MILLIS + 1)));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("Non-contiguous cue ordinals remain MISSING")
    void shouldReturnMissingForNonContiguousCues() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(snapshot(CONTENT_VERSION, SYNTHESIS_REVISION));
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID))
                .thenReturn(List.of(cue(1, SEGMENT_0_ID, 0, 0L, 2_000L)));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("Overlapping cue intervals remain MISSING")
    void shouldReturnMissingForOverlappingCues() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(snapshot(CONTENT_VERSION, SYNTHESIS_REVISION, 2));
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID)).thenReturn(List.of(
                cue(0, SEGMENT_0_ID, 0, 0L, 2_000L),
                cue(1, SEGMENT_1_ID, 1, 1_999L, 3_000L)
        ));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("Persisted cue count mismatch remains MISSING")
    void shouldReturnMissingForCueCountMismatch() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(snapshot(CONTENT_VERSION, SYNTHESIS_REVISION, 2));
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID))
                .thenReturn(List.of(cue(0, SEGMENT_0_ID, 0, 0L, 2_000L)));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("Cue ownership mismatch remains MISSING")
    void shouldReturnMissingForCueOwnershipMismatch() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(snapshot(CONTENT_VERSION, SYNTHESIS_REVISION));
        ChapterNarrationPlaybackCue foreignCue = ChapterNarrationPlaybackCue.rehydrate(
                FOREIGN_ID, 0, SEGMENT_0_ID, 0, 0L, 2_000L
        );
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID)).thenReturn(List.of(foreignCue));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("Null cue entry remains MISSING")
    void shouldReturnMissingForNullCue() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(snapshot(CONTENT_VERSION, SYNTHESIS_REVISION));
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID))
                .thenReturn(java.util.Collections.singletonList(null));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(mediaContract);
    }

    @ParameterizedTest(name = "Media case {0} is MISSING")
    @MethodSource("ineligibleMediaCases")
    @DisplayName("Missing or non-publicly eligible Media metadata remains MISSING")
    void shouldReturnMissingForIneligibleMedia(
            String caseName,
            MediaAssetStatusDTO status,
            MediaVisibilityDTO visibility,
            boolean present
    ) {
        givenPreferredVoice(activeVoice());
        givenSnapshot(snapshot(CONTENT_VERSION, SYNTHESIS_REVISION));
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID))
                .thenReturn(List.of(cue(0, SEGMENT_0_ID, 0, 0L, 2_000L)));
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID)).thenReturn(
                present ? Optional.of(mediaDetail(status, visibility)) : Optional.empty()
        );

        assertMissing(execute(null), VOICE_KEY);
    }

    @Test
    @DisplayName("Media detail without a current version remains MISSING")
    void shouldReturnMissingWhenMediaCurrentVersionIsAbsent() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(snapshot(CONTENT_VERSION, SYNTHESIS_REVISION));
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID))
                .thenReturn(List.of(cue(0, SEGMENT_0_ID, 0, 0L, 2_000L)));
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID)).thenReturn(Optional.of(
                new MediaAssetDetailDTO(
                        MEDIA_ASSET_ID, MediaTypeDTO.AUDIO, MediaVisibilityDTO.PUBLIC,
                        MediaAssetStatusDTO.ACTIVE, 1, NOW, NOW, null
                )
        ));

        assertMissing(execute(null), VOICE_KEY);
    }

    @Test
    @DisplayName("Unexpected Media failure still propagates")
    void shouldPropagateUnexpectedMediaFailure() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(snapshot(CONTENT_VERSION, SYNTHESIS_REVISION));
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID))
                .thenReturn(List.of(cue(0, SEGMENT_0_ID, 0, 0L, 2_000L)));
        IllegalStateException failure = new IllegalStateException("media unavailable");
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID)).thenThrow(failure);

        assertThatThrownBy(() -> execute(null)).isSameAs(failure);
    }

    @Test
    @DisplayName("Explicit active voiceKey is trimmed and resolved directly")
    void shouldTrimAndResolveExplicitActiveVoice() {
        when(playbackManagedVoiceQueryPort.findByVoiceKey(VOICE_KEY)).thenReturn(Optional.of(activeVoice()));
        givenSnapshot(chapterOnlySnapshot());

        assertMissing(execute("  " + VOICE_KEY + "  "), VOICE_KEY);

        verify(playbackManagedVoiceQueryPort).findByVoiceKey(VOICE_KEY);
        verifyNoInteractions(cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Public Chapter plus unknown explicit voice preserves not-found behavior")
    void shouldRejectUnknownExplicitVoice() {
        when(playbackManagedVoiceQueryPort.findByVoiceKey("missing-voice")).thenReturn(Optional.empty());
        when(playbackQueryPort.findPublishedPlayback(CHAPTER_ID, null))
                .thenReturn(Optional.of(chapterOnlySnapshot()));

        assertThatThrownBy(() -> execute("  missing-voice  "))
                .isInstanceOf(ManagedVoiceNotFoundException.class);
    }

    @Test
    @DisplayName("Public Chapter plus inactive explicit voice preserves invalid-state behavior")
    void shouldRejectInactiveExplicitVoice() {
        PlaybackManagedVoice inactive = new PlaybackManagedVoice(
                VOICE_ID, VOICE_KEY, ManagedVoiceStatus.DISABLED, SYNTHESIS_REVISION
        );
        when(playbackManagedVoiceQueryPort.findByVoiceKey(VOICE_KEY)).thenReturn(Optional.of(inactive));
        when(playbackQueryPort.findPublishedPlayback(CHAPTER_ID, VOICE_ID))
                .thenReturn(Optional.of(chapterOnlySnapshot()));

        assertThatThrownBy(() -> execute(VOICE_KEY))
                .isInstanceOf(ManagedVoiceInvalidStateException.class);
    }

    @Test
    @DisplayName("Absent key uses the one-query preferred ACTIVE voice result")
    void shouldUsePreferredActiveVoiceForAbsentKey() {
        givenPreferredVoice(activeVoice());
        givenSnapshot(chapterOnlySnapshot());

        assertMissing(execute("   "), VOICE_KEY);

        verify(playbackManagedVoiceQueryPort).findPreferredActiveVoice();
        verifyNoInteractions(cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("No ACTIVE voice returns MISSING after public Chapter is established")
    void shouldReturnMissingWhenNoActiveVoiceExists() {
        when(playbackManagedVoiceQueryPort.findPreferredActiveVoice()).thenReturn(Optional.empty());
        when(playbackQueryPort.findPublishedPlayback(CHAPTER_ID, null))
                .thenReturn(Optional.of(chapterOnlySnapshot()));

        assertMissing(execute(null), null);
        verifyNoInteractions(cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Missing Chapter wins over unknown voice without leaking voice existence")
    void shouldPreferMissingChapterOverUnknownVoice() {
        when(playbackManagedVoiceQueryPort.findByVoiceKey("missing-voice")).thenReturn(Optional.empty());
        when(playbackQueryPort.findPublishedPlayback(CHAPTER_ID, null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> execute("missing-voice"))
                .isInstanceOf(ChapterNotFoundException.class);
    }

    @Test
    @DisplayName("Missing Chapter wins over inactive voice without leaking voice state")
    void shouldPreferMissingChapterOverInactiveVoice() {
        PlaybackManagedVoice inactive = new PlaybackManagedVoice(
                VOICE_ID, VOICE_KEY, ManagedVoiceStatus.DISABLED, SYNTHESIS_REVISION
        );
        when(playbackManagedVoiceQueryPort.findByVoiceKey(VOICE_KEY)).thenReturn(Optional.of(inactive));
        when(playbackQueryPort.findPublishedPlayback(CHAPTER_ID, VOICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> execute(VOICE_KEY))
                .isInstanceOf(ChapterNotFoundException.class);
    }

    @Test
    @DisplayName("Playback use case no longer depends on the aggregate voice repository")
    void shouldNotUseLegacyAggregateVoiceRepository() {
        assertThat(
                GetPublicChapterNarrationPlaybackUseCase.class
                        .getDeclaredConstructors()[0]
                        .getParameterTypes()
        ).doesNotContain(ManagedVoiceRepositoryPort.class);
    }

    private PublicChapterNarrationPlaybackDTO execute(String voiceKey) {
        return useCase.execute(new GetPublicChapterNarrationPlaybackQuery(CHAPTER_ID, voiceKey));
    }

    private void givenPreferredVoice(PlaybackManagedVoice voice) {
        when(playbackManagedVoiceQueryPort.findPreferredActiveVoice()).thenReturn(Optional.of(voice));
    }

    private void givenSnapshot(PublicChapterNarrationPlaybackSnapshot snapshot) {
        when(playbackQueryPort.findPublishedPlayback(CHAPTER_ID, VOICE_ID)).thenReturn(Optional.of(snapshot));
    }

    private static PlaybackManagedVoice activeVoice() {
        return new PlaybackManagedVoice(VOICE_ID, VOICE_KEY, ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION);
    }

    private static PublicChapterNarrationPlaybackSnapshot chapterOnlySnapshot() {
        return new PublicChapterNarrationPlaybackSnapshot(
                CHAPTER_ID, CONTENT_VERSION, null, null,
                null, null, null, null, null, null, null, null, null, null
        );
    }

    private static PublicChapterNarrationPlaybackSnapshot snapshot(
            long sourceContentVersion,
            long artifactSynthesisRevision
    ) {
        return snapshot(sourceContentVersion, artifactSynthesisRevision, 1);
    }

    private static PublicChapterNarrationPlaybackSnapshot snapshot(
            long sourceContentVersion,
            long artifactSynthesisRevision,
            int cueCount
    ) {
        return new PublicChapterNarrationPlaybackSnapshot(
                CHAPTER_ID, CONTENT_VERSION, PLAYBACK_ID, ARTIFACT_ID, ARTIFACT_ID,
                PLAYBACK_ID, CHAPTER_ID, VOICE_ID, sourceContentVersion, artifactSynthesisRevision,
                MEDIA_ASSET_ID, DURATION_MILLIS, cueCount, "audio/mpeg"
        );
    }

    private static ChapterNarrationPlaybackCue cue(
            int cueOrdinal,
            UUID segmentId,
            int segmentIndex,
            long startMillis,
            long endMillis
    ) {
        return ChapterNarrationPlaybackCue.rehydrate(
                ARTIFACT_ID, cueOrdinal, segmentId, segmentIndex, startMillis, endMillis
        );
    }

    private void givenEligibleMedia() {
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID)).thenReturn(Optional.of(
                mediaDetail(MediaAssetStatusDTO.ACTIVE, MediaVisibilityDTO.PUBLIC)
        ));
    }

    private static MediaAssetDetailDTO mediaDetail(
            MediaAssetStatusDTO status,
            MediaVisibilityDTO visibility
    ) {
        MediaVersionDTO version = new MediaVersionDTO(
                MEDIA_VERSION_ID, MEDIA_ASSET_ID, 1, null, "audio/mpeg", 1_024L, "chapter.mp3", NOW
        );
        return new MediaAssetDetailDTO(
                MEDIA_ASSET_ID, MediaTypeDTO.AUDIO, visibility, status, 1, NOW, NOW, version
        );
    }

    private static Stream<Arguments> ineligibleMediaCases() {
        return Stream.of(
                Arguments.of("missing", null, null, false),
                Arguments.of("private", MediaAssetStatusDTO.ACTIVE, MediaVisibilityDTO.PRIVATE, true),
                Arguments.of("restricted", MediaAssetStatusDTO.ACTIVE, MediaVisibilityDTO.RESTRICTED, true),
                Arguments.of("archived", MediaAssetStatusDTO.ARCHIVED, MediaVisibilityDTO.PUBLIC, true),
                Arguments.of("deleted", MediaAssetStatusDTO.DELETED, MediaVisibilityDTO.PUBLIC, true)
        );
    }

    private static void assertMissing(PublicChapterNarrationPlaybackDTO result, String voiceKey) {
        assertThat(result.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(result.voiceKey()).isEqualTo(voiceKey);
        assertThat(result.availability()).isEqualTo(PublicChapterNarrationPlaybackAvailability.MISSING);
        assertThat(result.freshness()).isNull();
        assertThat(result.playable()).isFalse();
        assertThat(result.artifactId()).isNull();
        assertThat(result.audioUrl()).isNull();
        assertThat(result.codecMimeType()).isNull();
        assertThat(result.durationMillis()).isNull();
        assertThat(result.cues()).isEmpty();
    }
}
