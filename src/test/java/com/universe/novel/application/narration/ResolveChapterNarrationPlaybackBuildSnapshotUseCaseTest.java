package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.NarrationManifestHasher;
import com.universe.novel.domain.narration.NarrationTextSegment;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResolveChapterNarrationPlaybackBuildSnapshotUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID VOICE_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID SEGMENT_1_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID SEGMENT_2_ID = UUID.fromString("30000000-0000-0000-0000-000000000004");
    private static final UUID AUDIO_1_ID = UUID.fromString("30000000-0000-0000-0000-000000000005");
    private static final UUID AUDIO_2_ID = UUID.fromString("30000000-0000-0000-0000-000000000006");
    private static final UUID MEDIA_1_ID = UUID.fromString("30000000-0000-0000-0000-000000000007");
    private static final UUID MEDIA_2_ID = UUID.fromString("30000000-0000-0000-0000-000000000008");
    private static final Instant NOW = Instant.parse("2026-09-08T11:00:00Z");

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
    private MediaContract mediaContract;
    @Mock
    private Chapter chapter;
    @Mock
    private ManagedVoice voice;

    private ResolveChapterNarrationPlaybackBuildSnapshotUseCase useCase;
    private ChapterNarrationSegment segment1;
    private ChapterNarrationSegment segment2;
    private ChapterNarrationAudio audio1;
    private ChapterNarrationAudio audio2;
    private String manifestHash;

    @BeforeEach
    void setUp() {
        useCase = new ResolveChapterNarrationPlaybackBuildSnapshotUseCase(
                chapterRepositoryPort,
                managedVoiceRepositoryPort,
                manifestRepositoryPort,
                segmentRepositoryPort,
                audioRepositoryPort,
                mediaContract
        );
        segment1 = ChapterNarrationSegment.create(SEGMENT_1_ID, CHAPTER_ID, 0, "First.", NOW);
        segment2 = ChapterNarrationSegment.create(SEGMENT_2_ID, CHAPTER_ID, 1, "Second.", NOW);
        audio1 = audio(AUDIO_1_ID, SEGMENT_1_ID, MEDIA_1_ID, 4L, 5L);
        audio2 = audio(AUDIO_2_ID, SEGMENT_2_ID, MEDIA_2_ID, 4L, 6L);
        manifestHash = NarrationManifestHasher.computeManifestHash(List.of(
                NarrationTextSegment.of(0, segment1.getText()),
                NarrationTextSegment.of(1, segment2.getText())
        ));
    }

    @Test
    void capturesOrderedReadyAssignmentsAndExactCurrentMediaVersions() {
        arrangeContext(List.of(segment2, segment1), List.of(audio2, audio1));
        MediaAssetVersionSnapshotDTO version1 = version(MEDIA_1_ID, 2, "a".repeat(64));
        MediaAssetVersionSnapshotDTO version2 = version(MEDIA_2_ID, 7, "b".repeat(64));
        when(mediaContract.getCurrentVersionSnapshot(MEDIA_1_ID)).thenReturn(Optional.of(version1));
        when(mediaContract.getCurrentVersionSnapshot(MEDIA_2_ID)).thenReturn(Optional.of(version2));

        ChapterNarrationPlaybackBuildSnapshot result = useCase.execute(CHAPTER_ID, VOICE_ID);

        assertThat(result.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(result.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.sourceContentVersion()).isEqualTo(9L);
        assertThat(result.synthesisRevision()).isEqualTo(4L);
        assertThat(result.manifestHash()).isEqualTo(manifestHash);
        assertThat(result.segments()).extracting(ChapterNarrationPlaybackSegmentSnapshot::segmentId)
                .containsExactly(SEGMENT_1_ID, SEGMENT_2_ID);
        assertThat(result.segments().get(0).narrationAudioId()).isEqualTo(AUDIO_1_ID);
        assertThat(result.segments().get(0).narrationAudioVersion()).isEqualTo(5L);
        assertThat(result.segments().get(0).sourceMediaVersion()).isEqualTo(version1);
        assertThat(result.segments().get(1).sourceMediaVersion()).isEqualTo(version2);
        verify(mediaContract).getCurrentVersionSnapshot(MEDIA_1_ID);
        verify(mediaContract).getCurrentVersionSnapshot(MEDIA_2_ID);
    }

    @Test
    void rejectsAnyNonReadySegmentBeforeCapturingMediaVersions() {
        ChapterNarrationAudio outdated = audio(AUDIO_2_ID, SEGMENT_2_ID, MEDIA_2_ID, 3L, 6L);
        arrangeContext(List.of(segment1, segment2), List.of(audio1, outdated));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, VOICE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not READY")
                .hasMessageContaining(SEGMENT_2_ID.toString());

        verifyNoInteractions(mediaContract);
    }

    @Test
    void rejectsCurrentSegmentSetThatDoesNotMatchPersistedManifest() {
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(chapter.getStatus()).thenReturn(ChapterStatus.PUBLISHED);
        when(chapter.getContentVersion()).thenReturn(9L);
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(voice.isActive()).thenReturn(true);
        when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(
                ChapterNarrationManifest.create(CHAPTER_ID, 9L, "f".repeat(64), NOW)
        ));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment1, segment2));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, VOICE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match the CURRENT segment set");

        verifyNoInteractions(audioRepositoryPort, mediaContract);
    }

    private void arrangeContext(
            List<ChapterNarrationSegment> segments,
            List<ChapterNarrationAudio> assignments
    ) {
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(chapter.getStatus()).thenReturn(ChapterStatus.PUBLISHED);
        when(chapter.getContentVersion()).thenReturn(9L);
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(voice.isActive()).thenReturn(true);
        when(voice.getSynthesisRevision()).thenReturn(4L);
        when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(
                ChapterNarrationManifest.create(CHAPTER_ID, 9L, manifestHash, NOW)
        ));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(segments);
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(
                List.of(SEGMENT_1_ID, SEGMENT_2_ID),
                VOICE_ID
        )).thenReturn(assignments);
    }

    private static ChapterNarrationAudio audio(
            UUID id,
            UUID segmentId,
            UUID mediaAssetId,
            long synthesisRevision,
            long version
    ) {
        return ChapterNarrationAudio.rehydrate(
                id,
                segmentId,
                VOICE_ID,
                mediaAssetId,
                synthesisRevision,
                version,
                NOW,
                NOW
        );
    }

    private static MediaAssetVersionSnapshotDTO version(UUID assetId, int versionNumber, String hash) {
        return new MediaAssetVersionSnapshotDTO(
                assetId,
                versionNumber,
                hash,
                "audio/wav",
                100L,
                "segment.wav"
        );
    }
}
