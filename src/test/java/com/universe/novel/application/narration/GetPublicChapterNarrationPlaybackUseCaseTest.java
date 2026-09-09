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
import com.universe.novel.application.ports.ChapterNarrationPlaybackArtifactRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackCueRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableNarrationChapterReference;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackAvailability;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackDTO;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackFreshness;
import com.universe.novel.domain.narration.ChapterNarrationPlayback;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackArtifact;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackCue;
import com.universe.novel.domain.narration.ManagedVoice;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final String VOICE_KEY = "kiemlai-male-01";
    private static final long CONTENT_VERSION = 12L;
    private static final long SYNTHESIS_REVISION = 4L;
    private static final long DURATION_MILLIS = 4_000L;

    @Mock
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;

    @Mock
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;

    @Mock
    private ChapterNarrationPlaybackRepositoryPort playbackRepositoryPort;

    @Mock
    private ChapterNarrationPlaybackArtifactRepositoryPort artifactRepositoryPort;

    @Mock
    private ChapterNarrationPlaybackCueRepositoryPort cueRepositoryPort;

    @Mock
    private MediaContract mediaContract;

    private GetPublicChapterNarrationPlaybackUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetPublicChapterNarrationPlaybackUseCase(
                readerChapterAccessQueryPort,
                managedVoiceRepositoryPort,
                playbackRepositoryPort,
                artifactRepositoryPort,
                cueRepositoryPort,
                mediaContract
        );
    }

    @Test
    @DisplayName("CURRENT artifact is READY, playable, and preserves exact ordered cue timing")
    void shouldReturnCurrentReadyPlaybackWithOrderedCues() {
        ManagedVoice voice = voice(VOICE_ID, VOICE_KEY, 1, true, SYNTHESIS_REVISION, ManagedVoiceStatus.ACTIVE);
        ChapterNarrationPlaybackArtifact artifact = givenUsableArtifact(
                voice,
                CONTENT_VERSION,
                SYNTHESIS_REVISION,
                List.of(
                        cue(1, SEGMENT_1_ID, 1, 2_500L, 4_000L),
                        cue(0, SEGMENT_0_ID, 0, 0L, 2_000L)
                )
        );

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
        assertThat(artifact.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
    }

    @Test
    @DisplayName("STALE_VOICE remains READY and playable without regeneration")
    void shouldReturnPlayableStaleVoiceArtifact() {
        ManagedVoice voice = voice(VOICE_ID, VOICE_KEY, 1, true, SYNTHESIS_REVISION, ManagedVoiceStatus.ACTIVE);
        givenUsableArtifact(
                voice,
                CONTENT_VERSION,
                SYNTHESIS_REVISION - 1,
                List.of(cue(0, SEGMENT_0_ID, 0, 0L, 2_000L))
        );

        PublicChapterNarrationPlaybackDTO result = execute(null);

        assertThat(result.availability()).isEqualTo(PublicChapterNarrationPlaybackAvailability.READY);
        assertThat(result.freshness()).isEqualTo(PublicChapterNarrationPlaybackFreshness.STALE_VOICE);
        assertThat(result.playable()).isTrue();
        assertThat(result.audioUrl()).isNotNull();
        assertThat(result.cues()).hasSize(1);
        verifyNoMoreInteractions(mediaContract);
    }

    @Test
    @DisplayName("STALE_CONTENT wins over stale voice and withholds every playable payload")
    void shouldWithholdStaleContentPlayback() {
        ManagedVoice voice = voice(VOICE_ID, VOICE_KEY, 1, true, SYNTHESIS_REVISION, ManagedVoiceStatus.ACTIVE);
        givenUsableArtifact(
                voice,
                CONTENT_VERSION - 1,
                SYNTHESIS_REVISION - 1,
                List.of(cue(0, SEGMENT_0_ID, 0, 0L, 2_000L))
        );

        PublicChapterNarrationPlaybackDTO result = execute(null);

        assertThat(result.availability()).isEqualTo(PublicChapterNarrationPlaybackAvailability.READY);
        assertThat(result.freshness()).isEqualTo(PublicChapterNarrationPlaybackFreshness.STALE_CONTENT);
        assertThat(result.playable()).isFalse();
        assertThat(result.artifactId()).isEqualTo(ARTIFACT_ID);
        assertThat(result.audioUrl()).isNull();
        assertThat(result.codecMimeType()).isNull();
        assertThat(result.durationMillis()).isNull();
        assertThat(result.cues()).isEmpty();
    }

    @Test
    @DisplayName("No playback is a truthful MISSING response")
    void shouldReturnMissingWhenPlaybackDoesNotExist() {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, voice.getId()))
                .thenReturn(Optional.empty());

        PublicChapterNarrationPlaybackDTO result = execute(null);

        assertMissing(result, VOICE_KEY);
        verifyNoInteractions(artifactRepositoryPort, cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Playback without a current artifact pointer is MISSING")
    void shouldReturnMissingWhenCurrentArtifactPointerIsNull() {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, voice.getId()))
                .thenReturn(Optional.of(playback(null, CHAPTER_ID, VOICE_ID)));

        PublicChapterNarrationPlaybackDTO result = execute(null);

        assertMissing(result, VOICE_KEY);
        verifyNoInteractions(artifactRepositoryPort, cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Missing pointed artifact is MISSING")
    void shouldReturnMissingWhenCurrentArtifactDoesNotExist() {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, voice.getId()))
                .thenReturn(Optional.of(playback(ARTIFACT_ID, CHAPTER_ID, VOICE_ID)));
        when(artifactRepositoryPort.findById(ARTIFACT_ID)).thenReturn(Optional.empty());

        PublicChapterNarrationPlaybackDTO result = execute(null);

        assertMissing(result, VOICE_KEY);
        verifyNoInteractions(cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Artifact ownership inconsistency is MISSING and not exposed")
    void shouldReturnMissingForArtifactOwnershipInconsistency() {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        ChapterNarrationPlayback playback = playback(ARTIFACT_ID, CHAPTER_ID, VOICE_ID);
        ChapterNarrationPlaybackArtifact artifact = artifact(
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                CHAPTER_ID,
                VOICE_ID,
                CONTENT_VERSION,
                SYNTHESIS_REVISION,
                1
        );
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, voice.getId()))
                .thenReturn(Optional.of(playback));
        when(artifactRepositoryPort.findById(ARTIFACT_ID)).thenReturn(Optional.of(artifact));

        PublicChapterNarrationPlaybackDTO result = execute(null);

        assertMissing(result, VOICE_KEY);
        verifyNoInteractions(cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Non-contiguous cue ordinals are MISSING")
    void shouldReturnMissingForNonContiguousCues() {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        givenArtifactAndCues(voice, List.of(cue(1, SEGMENT_0_ID, 0, 0L, 2_000L)));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("Overlapping cue intervals are MISSING")
    void shouldReturnMissingForOverlappingCues() {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        givenArtifactAndCues(voice, List.of(
                cue(0, SEGMENT_0_ID, 0, 0L, 2_000L),
                cue(1, SEGMENT_1_ID, 1, 1_999L, 3_000L)
        ));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("Persisted cue count mismatch is MISSING")
    void shouldReturnMissingWhenPersistedCueCountDoesNotMatchArtifact() {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        ChapterNarrationPlayback playback = playback(ARTIFACT_ID, CHAPTER_ID, voice.getId());
        ChapterNarrationPlaybackArtifact artifact = artifact(
                PLAYBACK_ID,
                CHAPTER_ID,
                voice.getId(),
                CONTENT_VERSION,
                SYNTHESIS_REVISION,
                2
        );
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, voice.getId()))
                .thenReturn(Optional.of(playback));
        when(artifactRepositoryPort.findById(ARTIFACT_ID)).thenReturn(Optional.of(artifact));
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID))
                .thenReturn(List.of(cue(0, SEGMENT_0_ID, 0, 0L, 2_000L)));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("Cue belonging to another artifact is MISSING")
    void shouldReturnMissingWhenCueDoesNotBelongToCurrentArtifact() {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        ChapterNarrationPlaybackCue foreignCue = ChapterNarrationPlaybackCue.rehydrate(
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                0,
                SEGMENT_0_ID,
                0,
                0L,
                2_000L
        );
        givenArtifactAndCues(voice, List.of(foreignCue));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("Cue ending beyond artifact duration is MISSING")
    void shouldReturnMissingWhenCueExceedsArtifactDuration() {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        givenArtifactAndCues(voice, List.of(cue(0, SEGMENT_0_ID, 0, 0L, DURATION_MILLIS + 1)));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("Null cue entry is MISSING")
    void shouldReturnMissingForNullCue() {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        givenArtifactAndCues(voice, java.util.Collections.singletonList(null));

        assertMissing(execute(null), VOICE_KEY);
        verifyNoInteractions(mediaContract);
    }

    @ParameterizedTest(name = "Media case {0} is MISSING")
    @MethodSource("ineligibleMediaCases")
    @DisplayName("Missing or non-publicly eligible Media metadata is MISSING")
    void shouldReturnMissingForIneligibleMedia(
            String caseName,
            MediaAssetStatusDTO status,
            MediaVisibilityDTO visibility,
            boolean present
    ) {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        givenArtifactAndCues(voice, List.of(cue(0, SEGMENT_0_ID, 0, 0L, 2_000L)));
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID)).thenReturn(
                present ? Optional.of(mediaDetail(status, visibility)) : Optional.empty()
        );

        assertMissing(execute(null), VOICE_KEY);
    }

    @Test
    @DisplayName("Media detail without a current version is MISSING")
    void shouldReturnMissingWhenMediaCurrentVersionIsAbsent() {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        givenArtifactAndCues(voice, List.of(cue(0, SEGMENT_0_ID, 0, 0L, 2_000L)));
        MediaAssetDetailDTO detailWithoutVersion = new MediaAssetDetailDTO(
                MEDIA_ASSET_ID,
                MediaTypeDTO.AUDIO,
                MediaVisibilityDTO.PUBLIC,
                MediaAssetStatusDTO.ACTIVE,
                1,
                NOW,
                NOW,
                null
        );
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID)).thenReturn(Optional.of(detailWithoutVersion));

        assertMissing(execute(null), VOICE_KEY);
    }

    @Test
    @DisplayName("Unexpected Media infrastructure failure propagates instead of becoming MISSING")
    void shouldPropagateUnexpectedMediaFailure() {
        ManagedVoice voice = givenPublishedChapterAndVoice(CONTENT_VERSION, SYNTHESIS_REVISION);
        givenArtifactAndCues(voice, List.of(cue(0, SEGMENT_0_ID, 0, 0L, 2_000L)));
        IllegalStateException failure = new IllegalStateException("media unavailable");
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID)).thenThrow(failure);

        assertThatThrownBy(() -> execute(null)).isSameAs(failure);
    }

    @Test
    @DisplayName("Missing or non-public Chapter uses public not-found semantics before voice lookup")
    void shouldRejectMissingOrNonPublicChapter() {
        when(readerChapterAccessQueryPort.findPublishedNarrationById(CHAPTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> execute(null)).isInstanceOf(ChapterNotFoundException.class);

        verifyNoInteractions(managedVoiceRepositoryPort, playbackRepositoryPort, artifactRepositoryPort, cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Explicit missing voice is rejected")
    void shouldRejectExplicitMissingVoice() {
        givenPublishedChapter(CONTENT_VERSION);
        when(managedVoiceRepositoryPort.findAllActive()).thenReturn(List.of());
        when(managedVoiceRepositoryPort.findByVoiceKey("missing-voice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> execute("  missing-voice  "))
                .isInstanceOf(ManagedVoiceNotFoundException.class);
    }

    @Test
    @DisplayName("Explicit inactive voice is rejected")
    void shouldRejectExplicitInactiveVoice() {
        givenPublishedChapter(CONTENT_VERSION);
        ManagedVoice inactive = voice(VOICE_ID, VOICE_KEY, 1, false, SYNTHESIS_REVISION, ManagedVoiceStatus.DISABLED);
        when(managedVoiceRepositoryPort.findAllActive()).thenReturn(List.of());
        when(managedVoiceRepositoryPort.findByVoiceKey(VOICE_KEY)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> execute(VOICE_KEY))
                .isInstanceOf(ManagedVoiceInvalidStateException.class);
    }

    @Test
    @DisplayName("Explicit active voice key is selected after trimming")
    void shouldSelectExplicitActiveVoice() {
        givenPublishedChapter(CONTENT_VERSION);
        ManagedVoice defaultVoice = voice(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "default-voice",
                1,
                true,
                SYNTHESIS_REVISION,
                ManagedVoiceStatus.ACTIVE
        );
        ManagedVoice requestedVoice = voice(VOICE_ID, VOICE_KEY, 2, false, SYNTHESIS_REVISION, ManagedVoiceStatus.ACTIVE);
        when(managedVoiceRepositoryPort.findAllActive()).thenReturn(List.of(defaultVoice, requestedVoice));
        when(managedVoiceRepositoryPort.findByVoiceKey(VOICE_KEY)).thenReturn(Optional.of(requestedVoice));
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        PublicChapterNarrationPlaybackDTO result = execute("  " + VOICE_KEY + "  ");

        assertMissing(result, VOICE_KEY);
    }

    @Test
    @DisplayName("Absent voice key selects active default using established deterministic ordering")
    void shouldSelectActiveDefaultVoice() {
        givenPublishedChapter(CONTENT_VERSION);
        ManagedVoice fallback = voice(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "fallback-voice",
                1,
                false,
                SYNTHESIS_REVISION,
                ManagedVoiceStatus.ACTIVE
        );
        ManagedVoice defaultVoice = voice(VOICE_ID, VOICE_KEY, 9, true, SYNTHESIS_REVISION, ManagedVoiceStatus.ACTIVE);
        when(managedVoiceRepositoryPort.findAllActive()).thenReturn(List.of(defaultVoice, fallback));
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        PublicChapterNarrationPlaybackDTO result = execute(null);

        assertMissing(result, VOICE_KEY);
    }

    @Test
    @DisplayName("Without a default voice, deterministic first active voice is selected")
    void shouldSelectFirstOrderedActiveVoiceAsFallback() {
        givenPublishedChapter(CONTENT_VERSION);
        ManagedVoice later = voice(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "later-voice",
                20,
                false,
                SYNTHESIS_REVISION,
                ManagedVoiceStatus.ACTIVE
        );
        ManagedVoice first = voice(VOICE_ID, VOICE_KEY, 5, false, SYNTHESIS_REVISION, ManagedVoiceStatus.ACTIVE);
        when(managedVoiceRepositoryPort.findAllActive()).thenReturn(List.of(later, first));
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        PublicChapterNarrationPlaybackDTO result = execute(null);

        assertMissing(result, VOICE_KEY);
    }

    @Test
    @DisplayName("No active voice returns MISSING without querying playback")
    void shouldReturnMissingWhenNoActiveVoiceExists() {
        givenPublishedChapter(CONTENT_VERSION);
        when(managedVoiceRepositoryPort.findAllActive()).thenReturn(List.of());

        PublicChapterNarrationPlaybackDTO result = execute(null);

        assertMissing(result, null);
        verifyNoInteractions(playbackRepositoryPort, artifactRepositoryPort, cueRepositoryPort, mediaContract);
    }

    @Test
    @DisplayName("Playback GET uses Media metadata only and performs no repository writes")
    void shouldRemainReadOnlyAndAvoidBinaryMediaOperations() {
        ManagedVoice voice = voice(VOICE_ID, VOICE_KEY, 1, true, SYNTHESIS_REVISION, ManagedVoiceStatus.ACTIVE);
        givenUsableArtifact(
                voice,
                CONTENT_VERSION,
                SYNTHESIS_REVISION,
                List.of(cue(0, SEGMENT_0_ID, 0, 0L, 2_000L))
        );

        execute(null);

        verify(playbackRepositoryPort, never()).save(org.mockito.ArgumentMatchers.any());
        verify(artifactRepositoryPort, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(cueRepositoryPort, never()).insertAll(org.mockito.ArgumentMatchers.any());
        verify(mediaContract).getAssetDetail(MEDIA_ASSET_ID);
        verifyNoMoreInteractions(mediaContract);
    }

    private PublicChapterNarrationPlaybackDTO execute(String voiceKey) {
        return useCase.execute(new GetPublicChapterNarrationPlaybackQuery(CHAPTER_ID, voiceKey));
    }

    private ManagedVoice givenPublishedChapterAndVoice(long contentVersion, long synthesisRevision) {
        givenPublishedChapter(contentVersion);
        ManagedVoice voice = voice(VOICE_ID, VOICE_KEY, 1, true, synthesisRevision, ManagedVoiceStatus.ACTIVE);
        when(managedVoiceRepositoryPort.findAllActive()).thenReturn(List.of(voice));
        return voice;
    }

    private void givenPublishedChapter(long contentVersion) {
        when(readerChapterAccessQueryPort.findPublishedNarrationById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableNarrationChapterReference(CHAPTER_ID, contentVersion)));
    }

    private ChapterNarrationPlaybackArtifact givenUsableArtifact(
            ManagedVoice voice,
            long sourceContentVersion,
            long artifactSynthesisRevision,
            List<ChapterNarrationPlaybackCue> cues
    ) {
        givenPublishedChapter(CONTENT_VERSION);
        when(managedVoiceRepositoryPort.findAllActive()).thenReturn(List.of(voice));
        ChapterNarrationPlaybackArtifact artifact = givenArtifactAndCues(
                voice,
                sourceContentVersion,
                artifactSynthesisRevision,
                cues
        );
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID))
                .thenReturn(Optional.of(mediaDetail(MediaAssetStatusDTO.ACTIVE, MediaVisibilityDTO.PUBLIC)));
        return artifact;
    }

    private ChapterNarrationPlaybackArtifact givenArtifactAndCues(
            ManagedVoice voice,
            List<ChapterNarrationPlaybackCue> cues
    ) {
        return givenArtifactAndCues(voice, CONTENT_VERSION, SYNTHESIS_REVISION, cues);
    }

    private ChapterNarrationPlaybackArtifact givenArtifactAndCues(
            ManagedVoice voice,
            long sourceContentVersion,
            long artifactSynthesisRevision,
            List<ChapterNarrationPlaybackCue> cues
    ) {
        ChapterNarrationPlayback playback = playback(ARTIFACT_ID, CHAPTER_ID, voice.getId());
        ChapterNarrationPlaybackArtifact artifact = artifact(
                PLAYBACK_ID,
                CHAPTER_ID,
                voice.getId(),
                sourceContentVersion,
                artifactSynthesisRevision,
                cues.size()
        );
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, voice.getId()))
                .thenReturn(Optional.of(playback));
        when(artifactRepositoryPort.findById(ARTIFACT_ID)).thenReturn(Optional.of(artifact));
        when(cueRepositoryPort.findByArtifactId(ARTIFACT_ID)).thenReturn(cues);
        return artifact;
    }

    private static ChapterNarrationPlayback playback(
            UUID currentArtifactId,
            UUID chapterId,
            UUID voiceId
    ) {
        return ChapterNarrationPlayback.rehydrate(
                PLAYBACK_ID,
                chapterId,
                voiceId,
                currentArtifactId,
                0L,
                NOW,
                NOW
        );
    }

    private static ChapterNarrationPlaybackArtifact artifact(
            UUID playbackId,
            UUID chapterId,
            UUID voiceId,
            long sourceContentVersion,
            long synthesisRevision,
            int cueCount
    ) {
        return ChapterNarrationPlaybackArtifact.rehydrate(
                ARTIFACT_ID,
                playbackId,
                chapterId,
                voiceId,
                sourceContentVersion,
                synthesisRevision,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                MEDIA_ASSET_ID,
                DURATION_MILLIS,
                cueCount,
                "audio/mpeg",
                NOW
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
                ARTIFACT_ID,
                cueOrdinal,
                segmentId,
                segmentIndex,
                startMillis,
                endMillis
        );
    }

    private static ManagedVoice voice(
            UUID id,
            String voiceKey,
            int displayOrder,
            boolean defaultVoice,
            long synthesisRevision,
            ManagedVoiceStatus status
    ) {
        return ManagedVoice.rehydrate(
                id,
                voiceKey,
                voiceKey,
                "provider-voice",
                status,
                displayOrder,
                defaultVoice,
                synthesisRevision,
                NOW,
                NOW
        );
    }

    private static MediaAssetDetailDTO mediaDetail(
            MediaAssetStatusDTO status,
            MediaVisibilityDTO visibility
    ) {
        MediaVersionDTO version = new MediaVersionDTO(
                MEDIA_VERSION_ID,
                MEDIA_ASSET_ID,
                1,
                null,
                "audio/mpeg",
                1_024L,
                "chapter.mp3",
                NOW
        );
        return new MediaAssetDetailDTO(
                MEDIA_ASSET_ID,
                MediaTypeDTO.AUDIO,
                visibility,
                status,
                1,
                NOW,
                NOW,
                version
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
