package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.*;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.ports.*;
import com.universe.novel.domain.narration.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InspectChapterNarrationPlaybackUseCaseTest {
    private final UUID chapterId = UUID.randomUUID();
    private final UUID voiceId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();
    private final UUID mediaId = UUID.randomUUID();
    private final UUID segmentId = UUID.randomUUID();
    @Mock private ChapterNarrationPlaybackRepositoryPort playbackRepository;
    @Mock private ChapterNarrationPlaybackArtifactRepositoryPort artifactRepository;
    @Mock private ChapterNarrationPlaybackCueRepositoryPort cueRepository;
    @Mock private MediaContract mediaContract;
    @Mock private ResolveChapterNarrationPlaybackBuildSnapshotUseCase snapshotUseCase;
    private InspectChapterNarrationPlaybackUseCase inspector;
    private ChapterNarrationPlaybackBuildSnapshot snapshot;

    @BeforeEach
    void setUp() {
        inspector = new InspectChapterNarrationPlaybackUseCase(playbackRepository, artifactRepository,
                cueRepository, mediaContract, snapshotUseCase);
        UUID sourceId = UUID.randomUUID();
        snapshot = new ChapterNarrationPlaybackBuildSnapshot(chapterId, voiceId, 2L, 3L, "a".repeat(64), List.of(
                new ChapterNarrationPlaybackSegmentSnapshot(segmentId, 0, "b".repeat(64), UUID.randomUUID(), 1L,
                        sourceId, 3L, new MediaAssetVersionSnapshotDTO(sourceId, 1, "c".repeat(64), "audio/mpeg", 100L, "source.mp3"),
                        51840L, 48000)));
    }

    @Test
    void missingArtifactIsIndependentOfSegmentReadiness() {
        assertThat(inspector.execute(chapterId, voiceId, 2L, 3L)).isEqualTo(AdminChapterNarrationPlaybackDTO.missing());
        verifyNoInteractions(snapshotUseCase, mediaContract, artifactRepository, cueRepository);
    }

    @Test
    void exactCurrentShowsSafeDurationCuesAndTimestamp() {
        arrangeArtifact(ChapterNarrationPlaybackSourceFingerprint.compute(snapshot));
        when(snapshotUseCase.inspect(chapterId, voiceId)).thenReturn(snapshot);
        assertThat(inspector.execute(chapterId, voiceId, 2L, 3L)).isEqualTo(
                new AdminChapterNarrationPlaybackDTO(ChapterNarrationPlaybackState.CURRENT, 1000L, 1, Instant.EPOCH));
        verify(mediaContract, never()).openVersionContent(any());
    }

    @Test
    void staleContentTakesPrecedenceOverVoice() {
        arrangeArtifact(null);
        assertThat(inspector.execute(chapterId, voiceId, 4L, 5L).state()).isEqualTo(ChapterNarrationPlaybackState.STALE_CONTENT);
        verifyNoInteractions(snapshotUseCase);
    }

    @Test
    void staleVoiceRemainsDistinct() {
        arrangeArtifact(null);
        assertThat(inspector.execute(chapterId, voiceId, 2L, 5L).state()).isEqualTo(ChapterNarrationPlaybackState.STALE_VOICE);
        verifyNoInteractions(snapshotUseCase);
    }

    @Test
    void legacyNullFingerprintRequiresUpgrade() {
        arrangeArtifact(null);
        assertThat(inspector.execute(chapterId, voiceId, 2L, 3L).state()).isEqualTo(ChapterNarrationPlaybackState.STALE_SOURCE);
        verifyNoInteractions(snapshotUseCase);
    }

    @Test
    void v1StyleFingerprintCannotSuppressCanonicalRebuild() {
        arrangeArtifact("1".repeat(64));
        when(snapshotUseCase.inspect(chapterId, voiceId)).thenReturn(snapshot);
        assertThat(inspector.findAlreadyCurrent(snapshot)).isEmpty();
        assertThat(inspector.execute(chapterId, voiceId, 2L, 3L).state())
                .isEqualTo(ChapterNarrationPlaybackState.STALE_SOURCE);
    }

    @Test
    void changedSourceAndInvalidCurrentSourcesRequireUpdate() {
        arrangeArtifact("d".repeat(64));
        when(snapshotUseCase.inspect(chapterId, voiceId)).thenReturn(snapshot)
                .thenThrow(new IllegalStateException("source Media unavailable at private path"));
        assertThat(inspector.execute(chapterId, voiceId, 2L, 3L).state()).isEqualTo(ChapterNarrationPlaybackState.STALE_SOURCE);
        assertThat(inspector.execute(chapterId, voiceId, 2L, 3L).state()).isEqualTo(ChapterNarrationPlaybackState.STALE_SOURCE);
    }

    @Test
    void unavailableOutputMediaCannotBeCurrent() {
        arrangeArtifact(ChapterNarrationPlaybackSourceFingerprint.compute(snapshot));
        when(mediaContract.getAssetDetail(mediaId)).thenReturn(Optional.empty());
        assertThat(inspector.findAlreadyCurrent(snapshot)).isEmpty();
        assertThat(inspector.execute(chapterId, voiceId, 2L, 3L).state()).isEqualTo(ChapterNarrationPlaybackState.MISSING);
        verifyNoInteractions(snapshotUseCase);
    }

    @Test
    void mismatchedCueSegmentCannotBeCurrentDespiteMatchingFingerprint() {
        arrangeArtifact(ChapterNarrationPlaybackSourceFingerprint.compute(snapshot));
        when(cueRepository.findByArtifactId(artifactId)).thenReturn(List.of(
                ChapterNarrationPlaybackCue.create(artifactId, 0, UUID.randomUUID(), 0, 0L, 1000L)));
        assertThat(inspector.findAlreadyCurrent(snapshot)).isEmpty();
    }

    private void arrangeArtifact(String fingerprint) {
        var playback = ChapterNarrationPlayback.rehydrate(UUID.randomUUID(), chapterId, voiceId, artifactId,
                1L, Instant.EPOCH, Instant.EPOCH);
        when(playbackRepository.findByChapterIdAndManagedVoiceId(chapterId, voiceId)).thenReturn(Optional.of(playback));
        when(artifactRepository.findById(artifactId)).thenReturn(Optional.of(ChapterNarrationPlaybackArtifact.create(
                artifactId, playback, 2L, 3L, snapshot.manifestHash(), mediaId, 1000L, 1, "audio/mpeg", Instant.EPOCH, fingerprint)));
        when(cueRepository.findByArtifactId(artifactId)).thenReturn(List.of(
                ChapterNarrationPlaybackCue.create(artifactId, 0, segmentId, 0, 0L, 1000L)));
        when(mediaContract.getAssetDetail(mediaId)).thenReturn(Optional.of(new MediaAssetDetailDTO(
                mediaId, MediaTypeDTO.AUDIO, MediaVisibilityDTO.PUBLIC, MediaAssetStatusDTO.ACTIVE, 1, Instant.EPOCH, Instant.EPOCH,
                new MediaVersionDTO(UUID.randomUUID(), mediaId, 1, null, "audio/mpeg", 100L, "chapter.mp3", Instant.EPOCH))));
    }
}
