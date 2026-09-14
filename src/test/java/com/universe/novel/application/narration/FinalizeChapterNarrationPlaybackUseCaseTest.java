package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackArtifactRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackCueRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
import com.universe.novel.domain.narration.ChapterNarrationPlayback;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackArtifact;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackCue;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FinalizeChapterNarrationPlaybackUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID VOICE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID SEGMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID AUDIO_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID SOURCE_MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final UUID CANDIDATE_MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000006");
    private static final UUID PLAYBACK_ID = UUID.fromString("20000000-0000-0000-0000-000000000007");
    private static final UUID ARTIFACT_ID = UUID.fromString("20000000-0000-0000-0000-000000000008");
    private static final UUID OLD_ARTIFACT_ID = UUID.fromString("20000000-0000-0000-0000-000000000009");
    private static final UUID OLD_MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000010");
    private static final UUID SEGMENT_2_ID = UUID.fromString("20000000-0000-0000-0000-000000000011");
    private static final UUID AUDIO_2_ID = UUID.fromString("20000000-0000-0000-0000-000000000012");
    private static final UUID SOURCE_MEDIA_2_ID = UUID.fromString("20000000-0000-0000-0000-000000000013");
    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final long CONTENT_VERSION = 5L;
    private static final long SYNTHESIS_REVISION = 3L;

    @Mock
    private ChapterRepositoryPort chapterRepositoryPort;
    @Mock
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    @Mock
    private ChapterNarrationManifestRepositoryPort manifestRepositoryPort;
    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    @Mock
    private ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    @Mock
    private ChapterNarrationPlaybackRepositoryPort playbackRepositoryPort;
    @Mock
    private ChapterNarrationPlaybackArtifactRepositoryPort artifactRepositoryPort;
    @Mock
    private ChapterNarrationPlaybackCueRepositoryPort cueRepositoryPort;
    @Mock
    private IdGeneratorPort idGeneratorPort;
    @Mock
    private ClockPort clockPort;
    @Mock
    private Chapter chapter;
    @Mock
    private ManagedVoice voice;

    private FinalizeChapterNarrationPlaybackUseCase useCase;
    private ChapterNarrationSegment segment;
    private ChapterNarrationAudio audio;
    private ChapterNarrationPlaybackBuildSnapshot snapshot;
    private ChapterAudioAssemblyCue cue;
    private String manifestHash;

    @BeforeEach
    void setUp() {
        useCase = new FinalizeChapterNarrationPlaybackUseCase(
                chapterRepositoryPort,
                managedVoiceRepositoryPort,
                manifestRepositoryPort,
                segmentRepositoryPort,
                audioRepositoryPort,
                playbackRepositoryPort,
                artifactRepositoryPort,
                cueRepositoryPort,
                idGeneratorPort,
                clockPort
        );
        segment = ChapterNarrationSegment.create(SEGMENT_ID, CHAPTER_ID, 0, "Narrated text.", NOW);
        audio = ChapterNarrationAudio.rehydrate(
                AUDIO_ID,
                SEGMENT_ID,
                VOICE_ID,
                SOURCE_MEDIA_ID,
                SYNTHESIS_REVISION,
                51840L,
                48000,
                2L,
                NOW,
                NOW
        );
        manifestHash = com.universe.novel.domain.narration.NarrationManifestHasher.computeManifestHash(List.of(
                com.universe.novel.domain.narration.NarrationTextSegment.of(0, segment.getText())
        ));
        snapshot = snapshot(audio, segment);
        cue = new ChapterAudioAssemblyCue(0, SEGMENT_ID, 0, 0L, 1_234L);
        arrangeUnchangedRepositoryTruth();
        when(clockPort.now()).thenReturn(NOW);
    }

    @Test
    void firstPublicationPersistsPlaybackArtifactCuesAndPointerInOrder() {
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(idGeneratorPort.generate()).thenReturn(PLAYBACK_ID, ARTIFACT_ID);
        arrangePlaybackSaves();
        when(artifactRepositoryPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cueRepositoryPort.insertAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FinalizeChapterNarrationPlaybackResult result = useCase.execute(command());

        assertThat(result).isEqualTo(new FinalizeChapterNarrationPlaybackResult(
                PLAYBACK_ID, ARTIFACT_ID, CANDIDATE_MEDIA_ID, null
        ));
        InOrder persistenceOrder = inOrder(playbackRepositoryPort, artifactRepositoryPort, cueRepositoryPort);
        persistenceOrder.verify(playbackRepositoryPort).save(any());
        persistenceOrder.verify(artifactRepositoryPort).insert(any());
        persistenceOrder.verify(cueRepositoryPort).insertAll(any());
        persistenceOrder.verify(playbackRepositoryPort).save(any());

        ArgumentCaptor<ChapterNarrationPlayback> playbackCaptor =
                ArgumentCaptor.forClass(ChapterNarrationPlayback.class);
        verify(playbackRepositoryPort, org.mockito.Mockito.times(2)).save(playbackCaptor.capture());
        assertThat(playbackCaptor.getAllValues().get(0).getCurrentArtifactId()).isNull();
        assertThat(playbackCaptor.getAllValues().get(1).getCurrentArtifactId()).isEqualTo(ARTIFACT_ID);

        ArgumentCaptor<ChapterNarrationPlaybackArtifact> artifactCaptor =
                ArgumentCaptor.forClass(ChapterNarrationPlaybackArtifact.class);
        verify(artifactRepositoryPort).insert(artifactCaptor.capture());
        ChapterNarrationPlaybackArtifact artifact = artifactCaptor.getValue();
        assertThat(artifact.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(artifact.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(artifact.getSourceContentVersion()).isEqualTo(CONTENT_VERSION);
        assertThat(artifact.getSynthesisRevision()).isEqualTo(SYNTHESIS_REVISION);
        assertThat(artifact.getManifestHash()).isEqualTo(manifestHash);
        assertThat(artifact.getSourceFingerprint()).isEqualTo(ChapterNarrationPlaybackSourceFingerprint.compute(snapshot));
        assertThat(artifact.getMediaAssetId()).isEqualTo(CANDIDATE_MEDIA_ID);
        assertThat(artifact.getDurationMillis()).isEqualTo(1_234L);
        assertThat(artifact.getCueCount()).isOne();
        assertThat(artifact.getCodecMimeType()).isEqualTo("audio/mpeg");

        ArgumentCaptor<List<ChapterNarrationPlaybackCue>> cueCaptor = ArgumentCaptor.forClass(List.class);
        verify(cueRepositoryPort).insertAll(cueCaptor.capture());
        assertThat(cueCaptor.getValue()).singleElement().satisfies(persistedCue -> {
            assertThat(persistedCue.getCueOrdinal()).isZero();
            assertThat(persistedCue.getSegmentId()).isEqualTo(SEGMENT_ID);
            assertThat(persistedCue.getSegmentIndex()).isZero();
            assertThat(persistedCue.getStartMillis()).isZero();
            assertThat(persistedCue.getEndMillis()).isEqualTo(1_234L);
        });
    }

    @Test
    void replacementReturnsSupersededMediaAndSwitchesExistingPlayback() {
        ChapterNarrationPlayback playback = ChapterNarrationPlayback.rehydrate(
                PLAYBACK_ID, CHAPTER_ID, VOICE_ID, OLD_ARTIFACT_ID, 4L, NOW, NOW
        );
        ChapterNarrationPlaybackArtifact oldArtifact = ChapterNarrationPlaybackArtifact.rehydrate(
                OLD_ARTIFACT_ID,
                PLAYBACK_ID,
                CHAPTER_ID,
                VOICE_ID,
                CONTENT_VERSION - 1,
                SYNTHESIS_REVISION,
                "d".repeat(64),
                OLD_MEDIA_ID,
                900L,
                1,
                "audio/mpeg",
                NOW.minusSeconds(60)
        );
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, VOICE_ID))
                .thenReturn(Optional.of(playback));
        when(artifactRepositoryPort.findById(OLD_ARTIFACT_ID)).thenReturn(Optional.of(oldArtifact));
        when(idGeneratorPort.generate()).thenReturn(ARTIFACT_ID);
        when(artifactRepositoryPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cueRepositoryPort.insertAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(playbackRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FinalizeChapterNarrationPlaybackResult result = useCase.execute(command());

        assertThat(result.supersededMediaAssetId()).isEqualTo(OLD_MEDIA_ID);
        assertThat(result.playbackId()).isEqualTo(PLAYBACK_ID);
        verify(playbackRepositoryPort).save(playback);
        verify(artifactRepositoryPort).insert(any());
        verify(cueRepositoryPort).insertAll(any());
    }

    @Test
    void contentRaceRejectsCandidateBeforePlaybackPersistence() {
        when(chapter.getContentVersion()).thenReturn(CONTENT_VERSION + 1);

        assertStaleAndNoPersistence("chapter content or publication state changed");
    }

    @Test
    void synthesisRaceRejectsCandidateBeforePlaybackPersistence() {
        when(voice.getSynthesisRevision()).thenReturn(SYNTHESIS_REVISION + 1);

        assertStaleAndNoPersistence("managed voice state or synthesis revision changed");
    }

    @Test
    void segmentRaceRejectsCandidateBeforePlaybackPersistence() {
        ChapterNarrationSegment replacement = ChapterNarrationSegment.create(
                UUID.randomUUID(), CHAPTER_ID, 0, "Replacement text.", NOW
        );
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(replacement));

        assertStaleAndNoPersistence("CURRENT narration segment identity, order, or content changed");
    }

    @Test
    void readyAudioProvenanceRaceRejectsCandidateBeforePlaybackPersistence() {
        ChapterNarrationAudio replacement = ChapterNarrationAudio.rehydrate(
                AUDIO_ID,
                SEGMENT_ID,
                VOICE_ID,
                UUID.randomUUID(),
                SYNTHESIS_REVISION,
                51840L,
                48000,
                3L,
                NOW,
                NOW.plusSeconds(1)
        );
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_ID), VOICE_ID))
                .thenReturn(List.of(replacement));

        assertStaleAndNoPersistence("READY narration audio provenance changed");
    }

    @Test
    void assignmentVersionRaceRejectsCandidateBeforePlaybackPersistence() {
        ChapterNarrationAudio replacement = ChapterNarrationAudio.rehydrate(
                AUDIO_ID,
                SEGMENT_ID,
                VOICE_ID,
                SOURCE_MEDIA_ID,
                SYNTHESIS_REVISION,
                51840L,
                48000,
                audio.getVersion() + 1,
                NOW,
                NOW.plusSeconds(1)
        );
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_ID), VOICE_ID))
                .thenReturn(List.of(replacement));

        assertStaleAndNoPersistence("READY narration audio provenance changed");
    }

    @Test
    void audioAssignmentMissingRaceRejectsCandidateBeforePlaybackPersistence() {
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_ID), VOICE_ID))
                .thenReturn(List.of());

        assertStaleAndNoPersistence("READY narration audio set changed");
    }

    @Test
    void cueOrdinalNonContiguousIsRejectedBeforePersistence() {
        List<ChapterAudioAssemblyCue> badOrdinalCues = List.of(
                new ChapterAudioAssemblyCue(1, SEGMENT_ID, 0, 0L, 1_234L)
        );

        assertThatThrownBy(() -> useCase.execute(command(1_234L, badOrdinalCues)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Assembly cues must preserve the captured segment order exactly.");

        verifyNoInteractions(playbackRepositoryPort, artifactRepositoryPort, cueRepositoryPort, idGeneratorPort);
    }

    @Test
    void cueSegmentMismatchIsRejectedBeforePersistence() {
        List<ChapterAudioAssemblyCue> mismatchedSegmentCues = List.of(
                new ChapterAudioAssemblyCue(0, UUID.randomUUID(), 0, 0L, 1_234L)
        );

        assertThatThrownBy(() -> useCase.execute(command(1_234L, mismatchedSegmentCues)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Assembly cues must preserve the captured segment order exactly.");

        verifyNoInteractions(playbackRepositoryPort, artifactRepositoryPort, cueRepositoryPort, idGeneratorPort);
    }

    @Test
    void finalCueEndEqualsArtifactDurationIsPreserved() {
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(idGeneratorPort.generate()).thenReturn(PLAYBACK_ID, ARTIFACT_ID);
        arrangePlaybackSaves();
        when(artifactRepositoryPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cueRepositoryPort.insertAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Exact duration equal to cue end
        FinalizeChapterNarrationPlaybackResult result = useCase.execute(command(1_234L, List.of(cue)));

        assertThat(result.artifactId()).isEqualTo(ARTIFACT_ID);
        ArgumentCaptor<ChapterNarrationPlaybackArtifact> artifactCaptor =
                ArgumentCaptor.forClass(ChapterNarrationPlaybackArtifact.class);
        verify(artifactRepositoryPort).insert(artifactCaptor.capture());
        assertThat(artifactCaptor.getValue().getDurationMillis()).isEqualTo(1_234L);

        ArgumentCaptor<List<ChapterNarrationPlaybackCue>> cueCaptor = ArgumentCaptor.forClass(List.class);
        verify(cueRepositoryPort).insertAll(cueCaptor.capture());
        assertThat(cueCaptor.getValue().get(0).getEndMillis()).isEqualTo(1_234L);
    }

    @Test
    void overlappingCuesAreRejectedBeforePersistence() {
        arrangeTwoSegmentSnapshotTruth();
        List<ChapterAudioAssemblyCue> overlappingCues = List.of(
                new ChapterAudioAssemblyCue(0, SEGMENT_ID, 0, 0L, 700L),
                new ChapterAudioAssemblyCue(1, SEGMENT_2_ID, 1, 650L, 1_234L)
        );

        assertThatThrownBy(() -> useCase.execute(command(1_234L, overlappingCues)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not overlap");

        verifyNoInteractions(playbackRepositoryPort, artifactRepositoryPort, cueRepositoryPort, idGeneratorPort);
    }

    @Test
    void cueEndBeyondArtifactDurationIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> useCase.execute(command(1_233L, List.of(cue))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed the artifact duration");

        verifyNoInteractions(playbackRepositoryPort, artifactRepositoryPort, cueRepositoryPort, idGeneratorPort);
    }

    @Test
    void validMultiCueTimelinePersistsUnchanged() {
        arrangeTwoSegmentSnapshotTruth();
        List<ChapterAudioAssemblyCue> assembledCues = List.of(
                new ChapterAudioAssemblyCue(0, SEGMENT_ID, 0, 0L, 500L),
                new ChapterAudioAssemblyCue(1, SEGMENT_2_ID, 1, 650L, 1_234L)
        );
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(idGeneratorPort.generate()).thenReturn(PLAYBACK_ID, ARTIFACT_ID);
        arrangePlaybackSaves();
        when(artifactRepositoryPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cueRepositoryPort.insertAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        useCase.execute(command(1_234L, assembledCues));

        ArgumentCaptor<List<ChapterNarrationPlaybackCue>> cueCaptor = ArgumentCaptor.forClass(List.class);
        verify(cueRepositoryPort).insertAll(cueCaptor.capture());
        List<ChapterNarrationPlaybackCue> persistedCues = cueCaptor.getValue();
        assertThat(persistedCues).hasSize(2);
        assertThat(persistedCues.get(0).getCueOrdinal()).isZero();
        assertThat(persistedCues.get(0).getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(persistedCues.get(0).getSegmentIndex()).isZero();
        assertThat(persistedCues.get(0).getStartMillis()).isZero();
        assertThat(persistedCues.get(0).getEndMillis()).isEqualTo(500L);
        assertThat(persistedCues.get(1).getCueOrdinal()).isOne();
        assertThat(persistedCues.get(1).getSegmentId()).isEqualTo(SEGMENT_2_ID);
        assertThat(persistedCues.get(1).getSegmentIndex()).isOne();
        assertThat(persistedCues.get(1).getStartMillis()).isEqualTo(650L);
        assertThat(persistedCues.get(1).getEndMillis()).isEqualTo(1_234L);
    }

    @Test
    void persistenceFailurePropagatesFromTransactionalFinalizer() {
        when(playbackRepositoryPort.findByChapterIdAndManagedVoiceId(CHAPTER_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(idGeneratorPort.generate()).thenReturn(PLAYBACK_ID, ARTIFACT_ID);
        arrangePlaybackSaves();
        IllegalStateException persistenceFailure = new IllegalStateException("artifact insert failed");
        when(artifactRepositoryPort.insert(any())).thenThrow(persistenceFailure);

        assertThatThrownBy(() -> useCase.execute(command())).isSameAs(persistenceFailure);

        verify(playbackRepositoryPort).save(any());
        verify(cueRepositoryPort, never()).insertAll(any());
    }

    @Test
    void finalizerUsesDedicatedSerializableRequiresNewTransaction() throws NoSuchMethodException {
        Method execute = FinalizeChapterNarrationPlaybackUseCase.class.getMethod(
                "execute",
                FinalizeChapterNarrationPlaybackCommand.class
        );

        Transactional transactional = execute.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transactional.isolation()).isEqualTo(Isolation.SERIALIZABLE);
    }

    private void arrangeUnchangedRepositoryTruth() {
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(chapter.getStatus()).thenReturn(ChapterStatus.PUBLISHED);
        when(chapter.getContentVersion()).thenReturn(CONTENT_VERSION);
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(voice.isActive()).thenReturn(true);
        when(voice.getSynthesisRevision()).thenReturn(SYNTHESIS_REVISION);
        when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(
                ChapterNarrationManifest.create(CHAPTER_ID, CONTENT_VERSION, manifestHash, NOW)
        ));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_ID), VOICE_ID))
                .thenReturn(List.of(audio));
    }

    private ChapterNarrationPlaybackBuildSnapshot snapshot(
            ChapterNarrationAudio capturedAudio,
            ChapterNarrationSegment capturedSegment
    ) {
        return new ChapterNarrationPlaybackBuildSnapshot(
                CHAPTER_ID,
                VOICE_ID,
                CONTENT_VERSION,
                SYNTHESIS_REVISION,
                manifestHash,
                List.of(segmentSnapshot(capturedAudio, capturedSegment))
        );
    }

    private ChapterNarrationPlaybackSegmentSnapshot segmentSnapshot(
            ChapterNarrationAudio capturedAudio,
            ChapterNarrationSegment capturedSegment
    ) {
        return new ChapterNarrationPlaybackSegmentSnapshot(
                capturedSegment.getId(),
                capturedSegment.getSegmentIndex(),
                capturedSegment.getContentHash(),
                capturedAudio.getId(),
                capturedAudio.getVersion(),
                capturedAudio.getMediaAssetId(),
                capturedAudio.getGeneratedSynthesisRevision(),
                new MediaAssetVersionSnapshotDTO(
                        capturedAudio.getMediaAssetId(),
                        2,
                        "e".repeat(64),
                        "audio/mpeg",
                        100L,
                        "segment.mp3"
                ),
                capturedAudio.getEncodedContributionSamples(),
                capturedAudio.getEncodedSampleRateHz()
        );
    }

    private FinalizeChapterNarrationPlaybackCommand command() {
        return command(1_234L, List.of(cue));
    }

    private FinalizeChapterNarrationPlaybackCommand command(
            long durationMillis,
            List<ChapterAudioAssemblyCue> cues
    ) {
        return new FinalizeChapterNarrationPlaybackCommand(
                snapshot,
                CANDIDATE_MEDIA_ID,
                durationMillis,
                cues
        );
    }

    private void arrangeTwoSegmentSnapshotTruth() {
        ChapterNarrationSegment secondSegment = ChapterNarrationSegment.create(
                SEGMENT_2_ID, CHAPTER_ID, 1, "Second narrated text.", NOW
        );
        ChapterNarrationAudio secondAudio = ChapterNarrationAudio.rehydrate(
                AUDIO_2_ID,
                SEGMENT_2_ID,
                VOICE_ID,
                SOURCE_MEDIA_2_ID,
                SYNTHESIS_REVISION,
                51840L,
                48000,
                1L,
                NOW,
                NOW
        );
        manifestHash = com.universe.novel.domain.narration.NarrationManifestHasher.computeManifestHash(List.of(
                com.universe.novel.domain.narration.NarrationTextSegment.of(0, segment.getText()),
                com.universe.novel.domain.narration.NarrationTextSegment.of(1, secondSegment.getText())
        ));
        snapshot = new ChapterNarrationPlaybackBuildSnapshot(
                CHAPTER_ID,
                VOICE_ID,
                CONTENT_VERSION,
                SYNTHESIS_REVISION,
                manifestHash,
                List.of(segmentSnapshot(audio, segment), segmentSnapshot(secondAudio, secondSegment))
        );
        when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(
                ChapterNarrationManifest.create(CHAPTER_ID, CONTENT_VERSION, manifestHash, NOW)
        ));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment, secondSegment));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(
                List.of(SEGMENT_ID, SEGMENT_2_ID),
                VOICE_ID
        )).thenReturn(List.of(audio, secondAudio));
    }

    private void arrangePlaybackSaves() {
        AtomicInteger invocation = new AtomicInteger();
        when(playbackRepositoryPort.save(any())).thenAnswer(answer -> {
            ChapterNarrationPlayback playback = answer.getArgument(0);
            if (invocation.getAndIncrement() == 0) {
                return ChapterNarrationPlayback.rehydrate(
                        playback.getId(),
                        playback.getChapterId(),
                        playback.getManagedVoiceId(),
                        null,
                        0L,
                        playback.getCreatedAt(),
                        playback.getUpdatedAt()
                );
            }
            return ChapterNarrationPlayback.rehydrate(
                    playback.getId(),
                    playback.getChapterId(),
                    playback.getManagedVoiceId(),
                    playback.getCurrentArtifactId(),
                    1L,
                    playback.getCreatedAt(),
                    playback.getUpdatedAt()
            );
        });
    }

    private void assertStaleAndNoPersistence(String messagePart) {
        assertThatThrownBy(() -> useCase.execute(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(messagePart);
        verifyNoInteractions(playbackRepositoryPort, artifactRepositoryPort, cueRepositoryPort, idGeneratorPort);
    }
}
