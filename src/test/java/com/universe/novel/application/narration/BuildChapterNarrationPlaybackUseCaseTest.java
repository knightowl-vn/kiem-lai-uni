package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetVersionContentDTO;
import com.universe.media.contracts.dto.MediaAssetVersionReferenceDTO;
import com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.ports.ChapterAudioAssemblerPort;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.novel.application.ports.ChapterNarrationPlaybackRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackArtifactRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackCueRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationPlayback;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackArtifact;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackCue;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuildChapterNarrationPlaybackUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID VOICE_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SEGMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID SOURCE_ASSET_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID CANDIDATE_ASSET_ID = UUID.fromString("10000000-0000-0000-0000-000000000005");
    private static final UUID OLD_ASSET_ID = UUID.fromString("10000000-0000-0000-0000-000000000006");
    private static final UUID PLAYBACK_ID = UUID.fromString("10000000-0000-0000-0000-000000000007");
    private static final UUID ARTIFACT_ID = UUID.fromString("10000000-0000-0000-0000-000000000008");
    private static final String SOURCE_HASH = "a".repeat(64);

    @Mock
    private ResolveChapterNarrationPlaybackBuildSnapshotUseCase snapshotUseCase;
    @Mock
    private MediaContract mediaContract;
    @Mock
    private ChapterAudioAssemblerPort assemblerPort;
    @Mock
    private UploadChapterNarrationPlaybackMediaUseCase uploadMediaUseCase;
    @Mock
    private FinalizeChapterNarrationPlaybackUseCase finalizerUseCase;
    @Mock
    private RequestNarrationMediaCleanupUseCase cleanupUseCase;
    @Mock private ChapterNarrationPlaybackRepositoryPort playbackRepository;
    @Mock private ChapterNarrationPlaybackArtifactRepositoryPort artifactRepository;
    @Mock private ChapterNarrationPlaybackCueRepositoryPort cueRepository;

    private BuildChapterNarrationPlaybackUseCase useCase;
    private TrackingInputStream sourceStream;
    private TestAssemblyResource assemblyResource;
    private ChapterNarrationPlaybackBuildSnapshot snapshot;
    private ChapterAudioAssemblyCue cue;

    @BeforeEach
    void setUp() {
        useCase = new BuildChapterNarrationPlaybackUseCase(
                snapshotUseCase,
                mediaContract,
                assemblerPort,
                uploadMediaUseCase,
                finalizerUseCase,
                cleanupUseCase,
                new InspectChapterNarrationPlaybackUseCase(playbackRepository, artifactRepository,
                        cueRepository, mediaContract, snapshotUseCase)
        );
        MediaAssetVersionSnapshotDTO sourceVersion = new MediaAssetVersionSnapshotDTO(
                SOURCE_ASSET_ID, 3, SOURCE_HASH, "audio/mpeg", 4L, "segment.mp3"
        );
        snapshot = new ChapterNarrationPlaybackBuildSnapshot(
                CHAPTER_ID,
                VOICE_ID,
                7L,
                4L,
                "b".repeat(64),
                List.of(new ChapterNarrationPlaybackSegmentSnapshot(
                        SEGMENT_ID,
                        0,
                        "c".repeat(64),
                        UUID.fromString("10000000-0000-0000-0000-000000000009"),
                        2L,
                        SOURCE_ASSET_ID,
                        4L,
                        sourceVersion,
                        48_000L,
                        48_000
                ))
        );
        cue = new ChapterAudioAssemblyCue(0, SEGMENT_ID, 0, 0L, 1_000L);
        sourceStream = new TrackingInputStream(new byte[]{1, 2, 3, 4});
        assemblyResource = new TestAssemblyResource();
    }

    @Test
    void buildsWithExactVersionAndClosesAllOwnedResourcesBeforeFinalization() {
        arrangeSuccessfulHeavyBuild();
        when(finalizerUseCase.execute(any())).thenAnswer(invocation -> {
            assertAll(
                    () -> assertThat(sourceStream.closed).isTrue(),
                    () -> assertThat(assemblyResource.closed).isTrue()
            );
            FinalizeChapterNarrationPlaybackCommand command = invocation.getArgument(0);
            assertThat(command.snapshot()).isSameAs(snapshot);
            assertThat(command.candidateMediaAssetId()).isEqualTo(CANDIDATE_ASSET_ID);
            assertThat(command.durationMillis()).isEqualTo(1_000L);
            assertThat(command.cues()).containsExactly(cue);
            return finalized(null);
        });

        BuildChapterNarrationPlaybackResult result = useCase.execute(
                new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID)
        );

        assertThat(result).isEqualTo(new BuildChapterNarrationPlaybackResult(
                PLAYBACK_ID, ARTIFACT_ID, CANDIDATE_ASSET_ID
        ));
        verify(mediaContract).openVersionContent(new MediaAssetVersionReferenceDTO(
                SOURCE_ASSET_ID, 3, SOURCE_HASH
        ));
        verify(uploadMediaUseCase).execute(new UploadChapterNarrationPlaybackMediaCommand(
                assemblyResource,
                BuildChapterNarrationPlaybackUseCase.originalFilename(CHAPTER_ID, VOICE_ID)
        ));
        verifyNoInteractions(cleanupUseCase);
    }

    @Test
    void exactCurrentArtifactReturnsNoOpBeforeAnyHeavyWork() {
        when(snapshotUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(snapshot);
        arrangeCurrentArtifact(snapshot, ChapterNarrationPlaybackSourceFingerprint.compute(snapshot));

        var result = useCase.execute(new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID));

        assertThat(result.outcome()).isEqualTo(BuildChapterNarrationPlaybackOutcome.ALREADY_CURRENT);
        assertThat(result.artifactId()).isEqualTo(ARTIFACT_ID);
        verifyNoInteractions(assemblerPort, uploadMediaUseCase, finalizerUseCase, cleanupUseCase);
        verify(mediaContract, never()).openVersionContent(any());
    }

    @Test
    void repeatedCommandBuildsOnceThenReturnsAlreadyCurrent() {
        arrangeSuccessfulHeavyBuild();
        when(finalizerUseCase.execute(any())).thenAnswer(invocation -> {
            arrangeCurrentArtifact(snapshot, ChapterNarrationPlaybackSourceFingerprint.compute(snapshot));
            return finalized(null);
        });
        var command = new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID);
        assertThat(useCase.execute(command).outcome()).isEqualTo(BuildChapterNarrationPlaybackOutcome.BUILT);
        assertThat(useCase.execute(command).outcome()).isEqualTo(BuildChapterNarrationPlaybackOutcome.ALREADY_CURRENT);
        verify(assemblerPort).assemble(any());
        verify(uploadMediaUseCase).execute(any());
        verify(finalizerUseCase).execute(any());
        verifyNoInteractions(cleanupUseCase);
    }

    @Test
    void legacyArtifactWithoutFingerprintRequiresUpgradeBuild() {
        arrangeCurrentArtifact(snapshot, null);
        assertRebuilt();
    }

    @Test
    void staleContentDoesNotSuppressRebuild() {
        var old = new ChapterNarrationPlaybackBuildSnapshot(CHAPTER_ID, VOICE_ID, 6L, 4L,
                snapshot.manifestHash(), snapshot.segments());
        arrangeCurrentArtifact(old, ChapterNarrationPlaybackSourceFingerprint.compute(old));
        assertRebuilt();
    }

    @Test
    void staleVoiceDoesNotSuppressRebuild() {
        var old = new ChapterNarrationPlaybackBuildSnapshot(CHAPTER_ID, VOICE_ID, 7L, 3L,
                snapshot.manifestHash(), snapshot.segments());
        arrangeCurrentArtifact(old, ChapterNarrationPlaybackSourceFingerprint.compute(old));
        assertRebuilt();
    }

    @Test
    void changedExactMediaSourceDoesNotSuppressRebuild() {
        var s = snapshot.segments().get(0);
        var old = new ChapterNarrationPlaybackBuildSnapshot(CHAPTER_ID, VOICE_ID, 7L, 4L, snapshot.manifestHash(),
                List.of(new ChapterNarrationPlaybackSegmentSnapshot(s.segmentId(), s.segmentIndex(), s.contentHash(),
                        s.narrationAudioId(), s.narrationAudioVersion(), s.mediaAssetId(), s.generatedSynthesisRevision(),
                        new MediaAssetVersionSnapshotDTO(SOURCE_ASSET_ID, 2, "d".repeat(64), "audio/mpeg", 4L, "segment.mp3"),
                        48_000L, 48_000)));
        arrangeCurrentArtifact(old, ChapterNarrationPlaybackSourceFingerprint.compute(old));
        assertRebuilt();
    }

    @Test
    void changedSegmentSequenceDoesNotSuppressRebuild() {
        var s = snapshot.segments().get(0);
        var old = new ChapterNarrationPlaybackBuildSnapshot(CHAPTER_ID, VOICE_ID, 7L, 4L, snapshot.manifestHash(),
                List.of(new ChapterNarrationPlaybackSegmentSnapshot(UUID.randomUUID(), 0, s.contentHash(),
                        s.narrationAudioId(), s.narrationAudioVersion(), s.mediaAssetId(), s.generatedSynthesisRevision(),
                        s.sourceMediaVersion(), 48_000L, 48_000)));
        arrangeCurrentArtifact(old, ChapterNarrationPlaybackSourceFingerprint.compute(old));
        assertRebuilt();
    }

    @Test
    void anotherVoiceCannotSuppressBuild() {
        when(playbackRepository.findByChapterIdAndManagedVoiceId(CHAPTER_ID, VOICE_ID)).thenReturn(Optional.of(
                ChapterNarrationPlayback.rehydrate(PLAYBACK_ID, CHAPTER_ID, UUID.randomUUID(), ARTIFACT_ID,
                        1L, Instant.EPOCH, Instant.EPOCH)));
        assertRebuilt();
        verifyNoInteractions(artifactRepository, cueRepository);
    }

    private void assertRebuilt() {
        arrangeSuccessfulHeavyBuild();
        when(finalizerUseCase.execute(any())).thenReturn(finalized(null));
        assertThat(useCase.execute(new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID)).outcome())
                .isEqualTo(BuildChapterNarrationPlaybackOutcome.BUILT);
        verify(assemblerPort).assemble(any());
        verify(uploadMediaUseCase).execute(any());
        verify(finalizerUseCase).execute(any());
    }

    private void arrangeCurrentArtifact(ChapterNarrationPlaybackBuildSnapshot source, String fingerprint) {
        var playback = ChapterNarrationPlayback.rehydrate(PLAYBACK_ID, CHAPTER_ID, VOICE_ID, ARTIFACT_ID,
                1L, Instant.EPOCH, Instant.EPOCH);
        var artifact = ChapterNarrationPlaybackArtifact.create(ARTIFACT_ID, playback, source.sourceContentVersion(),
                source.synthesisRevision(), source.manifestHash(), CANDIDATE_ASSET_ID, 1000L, 1, "audio/mpeg", Instant.EPOCH, fingerprint);
        when(playbackRepository.findByChapterIdAndManagedVoiceId(CHAPTER_ID, VOICE_ID)).thenReturn(Optional.of(playback));
        when(artifactRepository.findById(ARTIFACT_ID)).thenReturn(Optional.of(artifact));
        when(cueRepository.findByArtifactId(ARTIFACT_ID)).thenReturn(List.of(
                ChapterNarrationPlaybackCue.create(ARTIFACT_ID, 0, source.segments().get(0).segmentId(), 0, 0L, 1000L)));
        var detail = mock(MediaAssetDetailDTO.class);
        var version = mock(MediaVersionDTO.class);
        when(detail.id()).thenReturn(CANDIDATE_ASSET_ID);
        when(detail.status()).thenReturn(MediaAssetStatusDTO.ACTIVE);
        when(detail.visibility()).thenReturn(MediaVisibilityDTO.PUBLIC);
        when(detail.currentVersion()).thenReturn(version);
        when(detail.currentVersionNumber()).thenReturn(1);
        when(version.assetId()).thenReturn(CANDIDATE_ASSET_ID);
        when(version.versionNumber()).thenReturn(1);
        when(mediaContract.getAssetDetail(CANDIDATE_ASSET_ID)).thenReturn(Optional.of(detail));
    }

    @Test
    void replacementCleansOnlySupersededMediaAfterFinalizerReturns() {
        arrangeSuccessfulHeavyBuild();
        when(finalizerUseCase.execute(any())).thenReturn(finalized(OLD_ASSET_ID));

        useCase.execute(new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID));

        InOrder order = inOrder(finalizerUseCase, cleanupUseCase);
        order.verify(finalizerUseCase).execute(any());
        order.verify(cleanupUseCase).execute(
                OLD_ASSET_ID,
                NarrationMediaCleanupReason.SUPERSEDED_CHAPTER_PLAYBACK_ASSET
        );
        verify(cleanupUseCase, never()).execute(
                CANDIDATE_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET
        );
    }

    @Test
    void finalizationFailureCleansCandidateAndNeverCleansOldMedia() {
        arrangeSuccessfulHeavyBuild();
        IllegalStateException finalizationFailure = new IllegalStateException("stale snapshot");
        when(finalizerUseCase.execute(any())).thenThrow(finalizationFailure);

        assertThatThrownBy(() -> useCase.execute(new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID)))
                .isSameAs(finalizationFailure);

        verify(cleanupUseCase).execute(
                CANDIDATE_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET
        );
        verify(cleanupUseCase, never()).execute(
                any(),
                org.mockito.ArgumentMatchers.eq(NarrationMediaCleanupReason.SUPERSEDED_CHAPTER_PLAYBACK_ASSET)
        );
    }

    @Test
    void candidateCleanupFailureIsSuppressedUnderFinalizationFailure() {
        arrangeSuccessfulHeavyBuild();
        IllegalStateException finalizationFailure = new IllegalStateException("persistence failed");
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup failed");
        when(finalizerUseCase.execute(any())).thenThrow(finalizationFailure);
        doThrow(cleanupFailure).when(cleanupUseCase).execute(
                CANDIDATE_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET
        );

        assertThatThrownBy(() -> useCase.execute(new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID)))
                .isSameAs(finalizationFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(cleanupFailure));
    }

    @Test
    void resourceCloseFailureAfterUploadCleansKnownCandidateAndSkipsFinalization() {
        assemblyResource = new TestAssemblyResource(true);
        arrangeSuccessfulHeavyBuild();

        assertThatThrownBy(() -> useCase.execute(new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("assembly close failed");

        verify(cleanupUseCase).execute(
                CANDIDATE_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET
        );
        verifyNoInteractions(finalizerUseCase);
        assertThat(assemblyResource.closed).isTrue();
        assertThat(sourceStream.closed).isTrue();
    }

    @Test
    void buildFailureBeforeCandidateClosesSourcesAndRequestsNoMediaCleanup() {
        when(snapshotUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(snapshot);
        arrangeExactSource();
        IllegalStateException assemblyFailure = new IllegalStateException("assembly failed");
        when(assemblerPort.assemble(any())).thenThrow(assemblyFailure);

        assertThatThrownBy(() -> useCase.execute(new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID)))
                .isSameAs(assemblyFailure);

        assertThat(sourceStream.closed).isTrue();
        verifyNoInteractions(uploadMediaUseCase, finalizerUseCase, cleanupUseCase);
    }

    @Test
    void uploadFailureClosesResourcesAndSkipsFinalizationWithoutCleanup() {
        arrangeSuccessfulHeavyBuild();
        RuntimeException uploadFailure = new RuntimeException("upload failed");
        when(uploadMediaUseCase.execute(any())).thenThrow(uploadFailure);

        assertThatThrownBy(() -> useCase.execute(new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID)))
                .isSameAs(uploadFailure);

        assertThat(sourceStream.closed).isTrue();
        assertThat(assemblyResource.closed).isTrue();
        verifyNoInteractions(finalizerUseCase, cleanupUseCase);
    }

    @Test
    void assemblerReceivesExactCanonicalMp3SourcesWithTimingMetadata() {
        arrangeSuccessfulHeavyBuild();
        when(finalizerUseCase.execute(any())).thenReturn(finalized(null));

        useCase.execute(new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID));

        var captor = org.mockito.ArgumentCaptor.forClass(ChapterAudioAssemblyRequest.class);
        verify(assemblerPort).assemble(captor.capture());
        ChapterAudioAssemblyRequest capturedRequest = captor.getValue();
        assertThat(capturedRequest.segments()).hasSize(1);
        ChapterAudioSegmentSource segmentSource = capturedRequest.segments().get(0);
        assertThat(segmentSource.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(segmentSource.segmentIndex()).isEqualTo(0);
        assertThat(segmentSource.mimeType()).isEqualTo("audio/mpeg");
        assertThat(segmentSource.encodedContributionSamples()).isEqualTo(48_000L);
        assertThat(segmentSource.encodedSampleRateHz()).isEqualTo(48_000);
    }

    @Test
    void successfulCommitReturnsPublishedResultWhenSupersededCleanupRequestFails() {
        arrangeSuccessfulHeavyBuild();
        when(finalizerUseCase.execute(any())).thenReturn(finalized(OLD_ASSET_ID));
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup unavailable");
        doThrow(cleanupFailure).when(cleanupUseCase).execute(
                OLD_ASSET_ID,
                NarrationMediaCleanupReason.SUPERSEDED_CHAPTER_PLAYBACK_ASSET
        );

        BuildChapterNarrationPlaybackResult result = useCase.execute(
                new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID)
        );

        assertThat(result).isEqualTo(new BuildChapterNarrationPlaybackResult(
                PLAYBACK_ID, ARTIFACT_ID, CANDIDATE_ASSET_ID
        ));

        verify(finalizerUseCase).execute(any());
        verify(cleanupUseCase).execute(
                OLD_ASSET_ID,
                NarrationMediaCleanupReason.SUPERSEDED_CHAPTER_PLAYBACK_ASSET
        );
        verify(cleanupUseCase, never()).execute(
                CANDIDATE_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET
        );
    }

    @Test
    void postCommitSupersededCleanupErrorIsNotSwallowed() {
        arrangeSuccessfulHeavyBuild();
        when(finalizerUseCase.execute(any())).thenReturn(finalized(OLD_ASSET_ID));
        LinkageError cleanupError = new LinkageError("cleanup linkage failed");
        doThrow(cleanupError).when(cleanupUseCase).execute(
                OLD_ASSET_ID,
                NarrationMediaCleanupReason.SUPERSEDED_CHAPTER_PLAYBACK_ASSET
        );

        assertThatThrownBy(() -> useCase.execute(new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID)))
                .isSameAs(cleanupError);

        verify(finalizerUseCase).execute(any());
        verify(cleanupUseCase, never()).execute(
                CANDIDATE_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET
        );
    }

    @Test
    void buildCoordinatorSuspendsAmbientTransactions() throws NoSuchMethodException {
        Method execute = BuildChapterNarrationPlaybackUseCase.class.getMethod(
                "execute",
                BuildChapterNarrationPlaybackCommand.class
        );

        Transactional transactional = execute.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    private void arrangeSuccessfulHeavyBuild() {
        when(snapshotUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(snapshot);
        arrangeExactSource();
        when(assemblerPort.assemble(any())).thenAnswer(invocation -> {
            ChapterAudioAssemblyRequest request = invocation.getArgument(0);
            try (InputStream ignored = request.segments().get(0).binarySource().openStream()) {
                assertThat(ignored.read()).isEqualTo(1);
            }
            return new ChapterAudioAssemblyResult(assemblyResource, 1_000L, List.of(cue));
        });
        when(uploadMediaUseCase.execute(any())).thenReturn(
                new UploadChapterNarrationPlaybackMediaResult(CANDIDATE_ASSET_ID)
        );
    }

    private void arrangeExactSource() {
        when(mediaContract.openVersionContent(new MediaAssetVersionReferenceDTO(
                SOURCE_ASSET_ID, 3, SOURCE_HASH
        ))).thenReturn(new MediaAssetVersionContentDTO(
                SOURCE_ASSET_ID,
                3,
                SOURCE_HASH,
                "audio/mpeg",
                4L,
                sourceStream
        ));
    }

    private FinalizeChapterNarrationPlaybackResult finalized(UUID oldAssetId) {
        return new FinalizeChapterNarrationPlaybackResult(
                PLAYBACK_ID,
                ARTIFACT_ID,
                CANDIDATE_ASSET_ID,
                oldAssetId
        );
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TestAssemblyResource implements ChapterAudioAssemblyResource {

        private final boolean failOnClose;
        private boolean closed;

        private TestAssemblyResource() {
            this(false);
        }

        private TestAssemblyResource(boolean failOnClose) {
            this.failOnClose = failOnClose;
        }

        @Override
        public String mimeType() {
            return "audio/mpeg";
        }

        @Override
        public long sizeBytes() {
            return 44L;
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(new byte[]{1});
        }

        @Override
        public void close() {
            closed = true;
            if (failOnClose) {
                throw new IllegalStateException("assembly close failed");
            }
        }
    }
}
