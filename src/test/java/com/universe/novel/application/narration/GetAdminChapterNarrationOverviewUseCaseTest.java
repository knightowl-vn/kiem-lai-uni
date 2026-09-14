package com.universe.novel.application.narration;

import com.universe.novel.application.chapter.GetChapterDetailUseCase;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import com.universe.novel.domain.narration.NarrationAudioFailureStage;
import com.universe.novel.domain.narration.NarrationAudioOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetAdminChapterNarrationOverviewUseCase Unit Tests")
class GetAdminChapterNarrationOverviewUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOLUME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_1_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID VOICE_2_ID = UUID.fromString("33333333-3333-3333-3333-333333333332");
    private static final UUID VOICE_3_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID SEGMENT_1_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");
    private static final UUID SEGMENT_2_ID = UUID.fromString("44444444-4444-4444-4444-444444444442");
    private static final UUID SEGMENT_3_ID = UUID.fromString("44444444-4444-4444-4444-444444444443");
    private static final UUID SEGMENT_4_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final UUID MEDIA_ASSET_1_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID MEDIA_ASSET_2_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");

    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    @Mock
    private GetChapterDetailUseCase getChapterDetailUseCase;

    @Mock
    private GetVolumeDetailUseCase getVolumeDetailUseCase;

    @Mock
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;

    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;

    @Mock
    private ChapterNarrationAudioRepositoryPort audioRepositoryPort;

    @Mock
    private ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;
    @Mock private InspectChapterNarrationPlaybackUseCase playbackInspector;

    private GetAdminChapterNarrationOverviewUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetAdminChapterNarrationOverviewUseCase(
                getChapterDetailUseCase,
                getVolumeDetailUseCase,
                managedVoiceRepositoryPort,
                segmentRepositoryPort,
                audioRepositoryPort,
                failureRepositoryPort,
                playbackInspector
        );
    }

    private ChapterDTO createChapterDTO() {
        return new ChapterDTO(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương Một",
                "chuong-mot",
                "Tóm tắt",
                "Nội dung",
                "PUBLISHED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                NOW,
                NOW,
                NOW,
                null,
                1L,
                1L
        );
    }

    private VolumeDTO createVolumeDTO() {
        return new VolumeDTO(
                VOLUME_ID,
                "Quyển Một",
                "quyen-mot",
                "Tóm tắt quyển",
                1,
                "PUBLISHED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                NOW,
                NOW,
                NOW,
                null,
                1L
        );
    }

    private ManagedVoice createVoice(UUID id, String key, String name, ManagedVoiceStatus status, int order, boolean isDefault, long revision) {
        return ManagedVoice.rehydrate(
                id,
                key,
                name,
                "provider-" + key,
                status,
                order,
                isDefault,
                revision,
                NOW,
                NOW
        );
    }

    @Test
    void overviewIncludesSelectedVoicePlaybackStateIndependentlyOfSegmentCounts() {
        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(createChapterDTO());
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(createVolumeDTO());
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(
                createVoice(VOICE_1_ID, "voice-1", "Minh Đức", ManagedVoiceStatus.ACTIVE, 1, true, 2L)));
        for (ChapterNarrationPlaybackState state : ChapterNarrationPlaybackState.values()) {
            var metadata = new AdminChapterNarrationPlaybackDTO(state, 1000L, 3, NOW);
            when(playbackInspector.execute(CHAPTER_ID, VOICE_1_ID, 1L, 2L)).thenReturn(metadata);
            assertThat(useCase.execute(CHAPTER_ID, VOICE_1_ID).chapterPlayback()).isEqualTo(metadata);
        }
    }

    private ChapterNarrationSegment createSegment(UUID id, int index, String text) {
        return ChapterNarrationSegment.create(
                id,
                CHAPTER_ID,
                index,
                text,
                NOW
        );
    }

    @Test
    @DisplayName("1. Loads overview with explicit ACTIVE voice selection, CURRENT segments sorted by segmentIndex, and accurate health states")
    void shouldLoadOverviewWithExplicitActiveVoice() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();

        ManagedVoice voice1 = createVoice(VOICE_1_ID, "voice-1", "Minh Đức", ManagedVoiceStatus.ACTIVE, 1, true, 2L);
        ManagedVoice voice2 = createVoice(VOICE_2_ID, "voice-2", "Anh Khôi", ManagedVoiceStatus.ACTIVE, 2, false, 1L);

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_1_ID, 0, "Đoạn 0 - Ready");
        ChapterNarrationSegment seg1 = createSegment(SEGMENT_2_ID, 1, "Đoạn 1 - Outdated");
        ChapterNarrationSegment seg2 = createSegment(SEGMENT_3_ID, 2, "Đoạn 2 - Missing");
        ChapterNarrationSegment seg3 = createSegment(SEGMENT_4_ID, 3, "Đoạn 3 - Failed");

        // Segment 0 has audio compatible with revision 2 with canonical timing -> READY
        ChapterNarrationAudio audio0 = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_1_ID, VOICE_1_ID, MEDIA_ASSET_1_ID, 2L, 51840L, 48000, NOW
        );
        // Segment 1 has audio generated at revision 1 (voice is at revision 2) -> OUTDATED
        ChapterNarrationAudio audio1 = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_2_ID, VOICE_1_ID, MEDIA_ASSET_2_ID, 1L, NOW
        );
        // Segment 3 has no audio and has unresolved failure -> FAILED
        ChapterNarrationAudioFailure failure3 = ChapterNarrationAudioFailure.create(
                UUID.randomUUID(), SEGMENT_4_ID, VOICE_1_ID,
                NarrationAudioOperation.INITIAL_GENERATION, NarrationAudioFailureStage.TTS_SYNTHESIS,
                2L, "RuntimeException", NOW
        );

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voice1, voice2));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0, seg1, seg2, seg3));

        List<UUID> expectedSegmentIds = List.of(SEGMENT_1_ID, SEGMENT_2_ID, SEGMENT_3_ID, SEGMENT_4_ID);
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(expectedSegmentIds, VOICE_1_ID))
                .thenReturn(List.of(audio0, audio1));
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(expectedSegmentIds, VOICE_1_ID))
                .thenReturn(List.of(failure3));

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, VOICE_1_ID);

        assertThat(result.chapter()).isEqualTo(chapter);
        assertThat(result.volume()).isEqualTo(volume);
        assertThat(result.voices()).hasSize(2);
        assertThat(result.selectedVoice()).isNotNull();
        assertThat(result.selectedVoice().id()).isEqualTo(VOICE_1_ID);
        assertThat(result.selectedVoice().displayName()).isEqualTo("Minh Đức");

        assertThat(result.totalSegments()).isEqualTo(4);
        assertThat(result.readyCount()).isEqualTo(1);
        assertThat(result.outdatedCount()).isEqualTo(1);
        assertThat(result.missingCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);

        List<AdminChapterNarrationSegmentViewDTO> segViews = result.segments();
        assertThat(segViews).hasSize(4);

        // Segment 0 (READY)
        AdminChapterNarrationSegmentViewDTO s0 = segViews.get(0);
        assertThat(s0.segmentIndex()).isEqualTo(0);
        assertThat(s0.healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
        assertThat(s0.isReady()).isTrue();
        assertThat(s0.generatedSynthesisRevision()).isEqualTo(2L);
        assertThat(s0.currentVoiceSynthesisRevision()).isEqualTo(2L);
        assertThat(s0.mediaAssetId()).isEqualTo(MEDIA_ASSET_1_ID);
        assertThat(s0.hasFailureDiagnostics()).isFalse();

        // Segment 1 (OUTDATED)
        AdminChapterNarrationSegmentViewDTO s1 = segViews.get(1);
        assertThat(s1.segmentIndex()).isEqualTo(1);
        assertThat(s1.healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
        assertThat(s1.isOutdated()).isTrue();
        assertThat(s1.generatedSynthesisRevision()).isEqualTo(1L);
        assertThat(s1.currentVoiceSynthesisRevision()).isEqualTo(2L);
        assertThat(s1.mediaAssetId()).isEqualTo(MEDIA_ASSET_2_ID);
        assertThat(s1.hasFailureDiagnostics()).isFalse();

        // Segment 2 (MISSING)
        AdminChapterNarrationSegmentViewDTO s2 = segViews.get(2);
        assertThat(s2.segmentIndex()).isEqualTo(2);
        assertThat(s2.healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.MISSING);
        assertThat(s2.isMissing()).isTrue();
        assertThat(s2.generatedSynthesisRevision()).isNull();
        assertThat(s2.currentVoiceSynthesisRevision()).isEqualTo(2L);
        assertThat(s2.mediaAssetId()).isNull();
        assertThat(s2.hasFailureDiagnostics()).isFalse();

        // Segment 3 (FAILED)
        AdminChapterNarrationSegmentViewDTO s3 = segViews.get(3);
        assertThat(s3.segmentIndex()).isEqualTo(3);
        assertThat(s3.healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.FAILED);
        assertThat(s3.isFailed()).isTrue();
        assertThat(s3.generatedSynthesisRevision()).isNull();
        assertThat(s3.currentVoiceSynthesisRevision()).isEqualTo(2L);
        assertThat(s3.hasFailureDiagnostics()).isTrue();
        assertThat(s3.failureDiagnostics().operation()).isEqualTo(NarrationAudioOperation.INITIAL_GENERATION);
        assertThat(s3.failureDiagnostics().stage()).isEqualTo(NarrationAudioFailureStage.TTS_SYNTHESIS);
        assertThat(s3.failureDiagnostics().errorMessage()).isEqualTo("Narration TTS synthesis failed.");

        verify(audioRepositoryPort).findBySegmentIdInAndManagedVoiceId(expectedSegmentIds, VOICE_1_ID);
        verify(failureRepositoryPort).findBySegmentIdInAndManagedVoiceId(expectedSegmentIds, VOICE_1_ID);
        verify(audioRepositoryPort, never()).findBySegmentIdAndManagedVoiceId(any(), any());
        verify(failureRepositoryPort, never()).findBySegmentIdAndManagedVoiceId(any(), any());
    }

    @Test
    @DisplayName("2. Explicitly selecting a DISABLED voice succeeds and correctly derives audio health for that voice via batch queries")
    void shouldAllowExplicitSelectionOfDisabledVoice() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();

        ManagedVoice voiceDisabled = createVoice(VOICE_2_ID, "voice-disabled", "Giọng Tạm Dừng", ManagedVoiceStatus.DISABLED, 2, false, 1L);

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_1_ID, 0, "Đoạn 0");

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voiceDisabled));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_2_ID)).thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_2_ID)).thenReturn(List.of());

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, VOICE_2_ID);

        assertThat(result.selectedVoice()).isNotNull();
        assertThat(result.selectedVoice().id()).isEqualTo(VOICE_2_ID);
        assertThat(result.selectedVoice().status()).isEqualTo("DISABLED");
        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().get(0).healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.MISSING);

        verify(audioRepositoryPort).findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_2_ID);
        verify(failureRepositoryPort).findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_2_ID);
    }

    @Test
    @DisplayName("3. No voiceId parameter selects ACTIVE default voice first (Fallback 1)")
    void shouldSelectActiveDefaultVoiceWhenNoVoiceIdSupplied() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();

        ManagedVoice voice1 = createVoice(VOICE_1_ID, "voice-1", "Giọng 1", ManagedVoiceStatus.ACTIVE, 1, false, 1L);
        ManagedVoice voiceDefault = createVoice(VOICE_2_ID, "voice-def", "Giọng Mặc Định", ManagedVoiceStatus.ACTIVE, 2, true, 1L);

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voice1, voiceDefault));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(Collections.emptyList());

        GetAdminChapterNarrationOverviewResult result = useCase.execute(new GetAdminChapterNarrationOverviewQuery(CHAPTER_ID, null));

        assertThat(result.selectedVoice()).isNotNull();
        assertThat(result.selectedVoice().id()).isEqualTo(VOICE_2_ID);
        assertThat(result.selectedVoice().defaultVoice()).isTrue();
    }

    @Test
    @DisplayName("4. No voiceId parameter falls back to first ACTIVE voice by displayOrder when no default voice exists (Fallback 2)")
    void shouldFallbackToFirstActiveVoiceWhenNoDefaultVoice() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();

        ManagedVoice voiceDisabled = createVoice(VOICE_1_ID, "voice-dis", "Giọng Tắt", ManagedVoiceStatus.DISABLED, 1, false, 1L);
        ManagedVoice voiceActive2 = createVoice(VOICE_2_ID, "voice-act-2", "Giọng Bật 2", ManagedVoiceStatus.ACTIVE, 2, false, 1L);
        ManagedVoice voiceActive3 = createVoice(VOICE_3_ID, "voice-act-3", "Giọng Bật 3", ManagedVoiceStatus.ACTIVE, 3, false, 1L);

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voiceDisabled, voiceActive2, voiceActive3));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(Collections.emptyList());

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, null);

        assertThat(result.selectedVoice()).isNotNull();
        assertThat(result.selectedVoice().id()).isEqualTo(VOICE_2_ID);
        assertThat(result.selectedVoice().displayName()).isEqualTo("Giọng Bật 2");
    }

    @Test
    @DisplayName("5. No voiceId parameter falls back to first managed voice by displayOrder when all voices are DISABLED (Fallback 3)")
    void shouldFallbackToFirstVoiceWhenAllVoicesDisabled() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();

        ManagedVoice voiceDis1 = createVoice(VOICE_1_ID, "voice-dis-1", "Giọng Tắt 1", ManagedVoiceStatus.DISABLED, 1, false, 1L);
        ManagedVoice voiceDis2 = createVoice(VOICE_2_ID, "voice-dis-2", "Giọng Tắt 2", ManagedVoiceStatus.DISABLED, 2, false, 1L);

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voiceDis1, voiceDis2));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(Collections.emptyList());

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, null);

        assertThat(result.selectedVoice()).isNotNull();
        assertThat(result.selectedVoice().id()).isEqualTo(VOICE_1_ID);
    }

    @Test
    @DisplayName("6. When no managed voices exist in the system, returns empty selectedVoice with zero per-voice counters without inventing health")
    void shouldReturnEmptySelectedVoiceWhenNoVoicesExist() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_1_ID, 0, "Đoạn 0");

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(Collections.emptyList());
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0));

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, null);

        assertThat(result.voices()).isEmpty();
        assertThat(result.selectedVoice()).isNull();
        assertThat(result.totalSegments()).isEqualTo(1);
        assertThat(result.readyCount()).isEqualTo(0);
        assertThat(result.outdatedCount()).isEqualTo(0);
        assertThat(result.missingCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.segments().get(0).healthStatus()).isNull();
        assertThat(result.segments().get(0).isMissing()).isFalse();

        verify(audioRepositoryPort, never()).findBySegmentIdInAndManagedVoiceId(any(), any());
        verify(failureRepositoryPort, never()).findBySegmentIdInAndManagedVoiceId(any(), any());
        verify(audioRepositoryPort, never()).findBySegmentIdAndManagedVoiceId(any(), any());
        verify(failureRepositoryPort, never()).findBySegmentIdAndManagedVoiceId(any(), any());
    }

    @Test
    @DisplayName("10. Explicitly returns CURRENT segments ordered by segmentIndex ASC even when repository returns unsorted segments")
    void shouldGuaranteeDeterministicAscendingOrderWhenRepositoryReturnsUnsortedSegments() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();

        ManagedVoice voice1 = createVoice(VOICE_1_ID, "voice-1", "Minh Đức", ManagedVoiceStatus.ACTIVE, 1, true, 1L);

        ChapterNarrationSegment seg3 = createSegment(SEGMENT_4_ID, 3, "Đoạn 3");
        ChapterNarrationSegment seg1 = createSegment(SEGMENT_2_ID, 1, "Đoạn 1");
        ChapterNarrationSegment seg0 = createSegment(SEGMENT_1_ID, 0, "Đoạn 0");
        ChapterNarrationSegment seg2 = createSegment(SEGMENT_3_ID, 2, "Đoạn 2");

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voice1));
        // Deliberately unsorted repository return order: [3, 1, 0, 2]
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg3, seg1, seg0, seg2));

        List<UUID> sortedSegmentIds = List.of(SEGMENT_1_ID, SEGMENT_2_ID, SEGMENT_3_ID, SEGMENT_4_ID);
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(sortedSegmentIds, VOICE_1_ID))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(sortedSegmentIds, VOICE_1_ID))
                .thenReturn(List.of());

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, VOICE_1_ID);

        assertThat(result.segments()).hasSize(4);
        assertThat(result.segments().stream().map(AdminChapterNarrationSegmentViewDTO::segmentIndex).toList())
                .containsExactly(0, 1, 2, 3);
        assertThat(result.segments().stream().map(AdminChapterNarrationSegmentViewDTO::segmentId).toList())
                .containsExactly(SEGMENT_1_ID, SEGMENT_2_ID, SEGMENT_3_ID, SEGMENT_4_ID);

        verify(audioRepositoryPort).findBySegmentIdInAndManagedVoiceId(sortedSegmentIds, VOICE_1_ID);
    }

    @Test
    @DisplayName("7. Throws ManagedVoiceNotFoundException when an invalid voiceId is requested")
    void shouldThrowWhenInvalidVoiceIdRequested() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();
        UUID nonExistentVoiceId = UUID.randomUUID();

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, nonExistentVoiceId))
                .isInstanceOf(ManagedVoiceNotFoundException.class)
                .hasMessageContaining(nonExistentVoiceId.toString());
    }

    @Test
    @DisplayName("8. Throws ChapterNotFoundException when chapter does not exist")
    void shouldThrowWhenChapterNotFound() {
        when(getChapterDetailUseCase.execute(CHAPTER_ID))
                .thenThrow(new ChapterNotFoundException(CHAPTER_ID));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, null))
                .isInstanceOf(ChapterNotFoundException.class);
    }

    @Test
    @DisplayName("9. Rejects null chapterId")
    void shouldRejectNullChapterId() {
        assertThatThrownBy(() -> useCase.execute(null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("11. Admin diagnostics and counters adhere to locked health rules across all 4 health states")
    void shouldAdhereToLockedHealthRulesForAdminDiagnosticsAndCounters() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();

        // Selected voice is at synthesisRevision = 3
        ManagedVoice voice = createVoice(VOICE_1_ID, "voice-1", "Minh Đức", ManagedVoiceStatus.ACTIVE, 1, true, 3L);

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_1_ID, 0, "Seg 0: READY + ghost failure rev 2");
        ChapterNarrationSegment seg1 = createSegment(SEGMENT_2_ID, 1, "Seg 1: OUTDATED rev 1 + current failure rev 3");
        ChapterNarrationSegment seg2 = createSegment(SEGMENT_3_ID, 2, "Seg 2: OUTDATED rev 1 + old failure rev 2");
        ChapterNarrationSegment seg3 = createSegment(SEGMENT_4_ID, 3, "Seg 3: No audio + old failure rev 2");

        // seg0: audio at rev 3 == voice rev 3 with canonical timing -> READY
        ChapterNarrationAudio audio0 = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_1_ID, VOICE_1_ID, MEDIA_ASSET_1_ID, 3L, 51840L, 48000, NOW
        );
        ChapterNarrationAudioFailure ghostFailure0 = ChapterNarrationAudioFailure.create(
                UUID.randomUUID(), SEGMENT_1_ID, VOICE_1_ID,
                NarrationAudioOperation.REGENERATION, NarrationAudioFailureStage.MEDIA_UPLOAD,
                2L, "GhostFailure", NOW
        );

        // seg1: audio at rev 1 != voice rev 3 -> OUTDATED, failure at rev 3 == voice rev 3 -> relevant
        ChapterNarrationAudio audio1 = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_2_ID, VOICE_1_ID, MEDIA_ASSET_2_ID, 1L, NOW
        );
        ChapterNarrationAudioFailure currentFailure1 = ChapterNarrationAudioFailure.create(
                UUID.randomUUID(), SEGMENT_2_ID, VOICE_1_ID,
                NarrationAudioOperation.REGENERATION, NarrationAudioFailureStage.TTS_SYNTHESIS,
                3L, "CurrentFailure", NOW
        );

        // seg2: audio at rev 1 != voice rev 3 -> OUTDATED, failure at rev 2 != voice rev 3 -> suppressed
        ChapterNarrationAudio audio2 = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_3_ID, VOICE_1_ID, MEDIA_ASSET_2_ID, 1L, NOW
        );
        ChapterNarrationAudioFailure oldFailure2 = ChapterNarrationAudioFailure.create(
                UUID.randomUUID(), SEGMENT_3_ID, VOICE_1_ID,
                NarrationAudioOperation.REGENERATION, NarrationAudioFailureStage.TTS_SYNTHESIS,
                2L, "OldFailure", NOW
        );

        // seg3: no audio, failure at rev 2 != voice rev 3 -> MISSING, suppressed
        ChapterNarrationAudioFailure oldFailure3 = ChapterNarrationAudioFailure.create(
                UUID.randomUUID(), SEGMENT_4_ID, VOICE_1_ID,
                NarrationAudioOperation.INITIAL_GENERATION, NarrationAudioFailureStage.TTS_SYNTHESIS,
                2L, "OldInitialFailure", NOW
        );

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0, seg1, seg2, seg3));

        List<UUID> segmentIds = List.of(SEGMENT_1_ID, SEGMENT_2_ID, SEGMENT_3_ID, SEGMENT_4_ID);
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, VOICE_1_ID))
                .thenReturn(List.of(audio0, audio1, audio2));
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, VOICE_1_ID))
                .thenReturn(List.of(ghostFailure0, currentFailure1, oldFailure2, oldFailure3));

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, VOICE_1_ID);

        // Verify counters
        assertThat(result.totalSegments()).isEqualTo(4);
        assertThat(result.readyCount()).isEqualTo(1);
        assertThat(result.outdatedCount()).isEqualTo(2);
        assertThat(result.missingCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(0);

        List<AdminChapterNarrationSegmentViewDTO> views = result.segments();

        // Seg 0: READY + ghost failure -> diagnostics suppressed (null)
        assertThat(views.get(0).healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
        assertThat(views.get(0).hasFailureDiagnostics()).isFalse();
        assertThat(views.get(0).failureDiagnostics()).isNull();

        // Seg 1: OUTDATED + current failure -> diagnostics present
        assertThat(views.get(1).healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
        assertThat(views.get(1).hasFailureDiagnostics()).isTrue();
        assertThat(views.get(1).failureDiagnostics().errorType()).isEqualTo("CurrentFailure");
        assertThat(views.get(1).failureDiagnostics().attemptedSynthesisRevision()).isEqualTo(3L);

        // Seg 2: OUTDATED + old failure -> diagnostics suppressed (null)
        assertThat(views.get(2).healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
        assertThat(views.get(2).hasFailureDiagnostics()).isFalse();
        assertThat(views.get(2).failureDiagnostics()).isNull();

        // Seg 3: No audio + old failure -> MISSING + diagnostics suppressed (null)
        assertThat(views.get(3).healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.MISSING);
        assertThat(views.get(3).hasFailureDiagnostics()).isFalse();
        assertThat(views.get(3).failureDiagnostics()).isNull();
    }

    @Test
    @DisplayName("12. Admin overview read use case performs no save or delete writes to any repository")
    void shouldNeverPerformWritesOrDeletesDuringAdminOverview() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();
        ManagedVoice voice = createVoice(VOICE_1_ID, "voice-1", "Minh Đức", ManagedVoiceStatus.ACTIVE, 1, true, 2L);
        ChapterNarrationSegment seg0 = createSegment(SEGMENT_1_ID, 0, "Đoạn 0");

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(List.of());

        useCase.execute(CHAPTER_ID, VOICE_1_ID);

        verify(failureRepositoryPort, never()).save(any());
        verify(failureRepositoryPort, never()).deleteSupersededBySuccessfulRevision(any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(segmentRepositoryPort, never()).save(any());
        verify(segmentRepositoryPort, never()).saveAll(any());
        verify(audioRepositoryPort, never()).save(any());
        verify(audioRepositoryPort, never()).saveAll(any());
    }

    @Test
    @DisplayName("13. All CURRENT segments READY -> readyCount correct, generationRequired = 0, no warning")
    void shouldReportAllCurrentReadyWithZeroGenerationRequiredAndNoWarning() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();
        ManagedVoice voice = createVoice(VOICE_1_ID, "voice-1", "Minh Đức", ManagedVoiceStatus.ACTIVE, 1, true, 1L);

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_1_ID, 0, "Đoạn 0");
        ChapterNarrationSegment seg1 = createSegment(SEGMENT_2_ID, 1, "Đoạn 1");

        ChapterNarrationAudio audio0 = ChapterNarrationAudio.create(UUID.randomUUID(), SEGMENT_1_ID, VOICE_1_ID, MEDIA_ASSET_1_ID, 1L, 51840L, 48000, NOW);
        ChapterNarrationAudio audio1 = ChapterNarrationAudio.create(UUID.randomUUID(), SEGMENT_2_ID, VOICE_1_ID, MEDIA_ASSET_2_ID, 1L, 51840L, 48000, NOW);

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0, seg1));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                .thenReturn(Collections.emptyList());
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID, SEGMENT_2_ID), VOICE_1_ID))
                .thenReturn(List.of(audio0, audio1));
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID, SEGMENT_2_ID), VOICE_1_ID))
                .thenReturn(Collections.emptyList());

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, VOICE_1_ID);

        assertThat(result.currentSegmentCount()).isEqualTo(2);
        assertThat(result.totalSegments()).isEqualTo(2);
        assertThat(result.readyCount()).isEqualTo(2);
        assertThat(result.outdatedCount()).isEqualTo(0);
        assertThat(result.missingCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.currentGenerationRequiredCount()).isEqualTo(0);
        assertThat(result.retiredSegmentCount()).isEqualTo(0);
        assertThat(result.obsoleteRetiredSegmentCount()).isEqualTo(0);
        assertThat(result.obsoleteRetiredAudioCount()).isEqualTo(0);
        assertThat(result.contentChangeWarning()).isFalse();

        verify(audioRepositoryPort, never()).findBySegmentIdIn(any());
    }

    @Test
    @DisplayName("14. Retired segments without audio -> retiredSegmentCount > 0, obsolete counts = 0, warning = false")
    void shouldReportRetiredSegmentsWithoutAudioWithWarningFalse() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();
        ManagedVoice voice = createVoice(VOICE_1_ID, "voice-1", "Minh Đức", ManagedVoiceStatus.ACTIVE, 1, true, 1L);

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_1_ID, 0, "Đoạn 0");
        ChapterNarrationSegment retSeg1 = createSegment(SEGMENT_2_ID, 0, "Đoạn cũ 1");
        retSeg1.retire(NOW);
        ChapterNarrationSegment retSeg2 = createSegment(SEGMENT_3_ID, 1, "Đoạn cũ 2");
        retSeg2.retire(NOW);

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                .thenReturn(List.of(retSeg1, retSeg2));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(List.of());
        when(audioRepositoryPort.findBySegmentIdIn(List.of(SEGMENT_2_ID, SEGMENT_3_ID)))
                .thenReturn(Collections.emptyList());

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, VOICE_1_ID);

        assertThat(result.retiredSegmentCount()).isEqualTo(2);
        assertThat(result.obsoleteRetiredSegmentCount()).isEqualTo(0);
        assertThat(result.obsoleteRetiredAudioCount()).isEqualTo(0);
        assertThat(result.contentChangeWarning()).isFalse();
    }

    @Test
    @DisplayName("15. Retired segment with one audio assignment -> obsoleteRetiredSegmentCount = 1, obsoleteRetiredAudioCount = 1, warning = true")
    void shouldReportRetiredSegmentWithOneAudioAssignmentAndWarningTrue() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();
        ManagedVoice voice = createVoice(VOICE_1_ID, "voice-1", "Minh Đức", ManagedVoiceStatus.ACTIVE, 1, true, 1L);

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_1_ID, 0, "Đoạn 0");
        ChapterNarrationSegment retSeg = createSegment(SEGMENT_2_ID, 0, "Đoạn cũ");
        retSeg.retire(NOW);

        ChapterNarrationAudio oldAudio = ChapterNarrationAudio.create(UUID.randomUUID(), SEGMENT_2_ID, VOICE_1_ID, MEDIA_ASSET_1_ID, 1L, NOW);

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                .thenReturn(List.of(retSeg));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(List.of());
        when(audioRepositoryPort.findBySegmentIdIn(List.of(SEGMENT_2_ID)))
                .thenReturn(List.of(oldAudio));

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, VOICE_1_ID);

        assertThat(result.retiredSegmentCount()).isEqualTo(1);
        assertThat(result.obsoleteRetiredSegmentCount()).isEqualTo(1);
        assertThat(result.obsoleteRetiredAudioCount()).isEqualTo(1);
        assertThat(result.contentChangeWarning()).isTrue();
    }

    @Test
    @DisplayName("16. One retired segment with multiple managed-voice audio assignments -> obsoleteRetiredSegmentCount = 1, obsoleteRetiredAudioCount > 1")
    void shouldReportOneRetiredSegmentWithMultipleManagedVoiceAudios() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();
        ManagedVoice voice1 = createVoice(VOICE_1_ID, "voice-1", "Minh Đức", ManagedVoiceStatus.ACTIVE, 1, true, 1L);

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_1_ID, 0, "Đoạn 0");
        ChapterNarrationSegment retSeg = createSegment(SEGMENT_2_ID, 0, "Đoạn cũ");
        retSeg.retire(NOW);

        ChapterNarrationAudio oldAudioVoice1 = ChapterNarrationAudio.create(UUID.randomUUID(), SEGMENT_2_ID, VOICE_1_ID, MEDIA_ASSET_1_ID, 1L, NOW);
        ChapterNarrationAudio oldAudioVoice2 = ChapterNarrationAudio.create(UUID.randomUUID(), SEGMENT_2_ID, VOICE_2_ID, MEDIA_ASSET_2_ID, 1L, NOW);

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voice1));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                .thenReturn(List.of(retSeg));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(List.of());
        when(audioRepositoryPort.findBySegmentIdIn(List.of(SEGMENT_2_ID)))
                .thenReturn(List.of(oldAudioVoice1, oldAudioVoice2));

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, VOICE_1_ID);

        assertThat(result.retiredSegmentCount()).isEqualTo(1);
        assertThat(result.obsoleteRetiredSegmentCount()).isEqualTo(1);
        assertThat(result.obsoleteRetiredAudioCount()).isEqualTo(2);
        assertThat(result.contentChangeWarning()).isTrue();
    }

    @Test
    @DisplayName("17. Multiple retired segments with audio -> distinct retired segment count correct and total audio count correct")
    void shouldReportMultipleRetiredSegmentsWithAudioAndDistinctCounts() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();
        ManagedVoice voice = createVoice(VOICE_1_ID, "voice-1", "Minh Đức", ManagedVoiceStatus.ACTIVE, 1, true, 1L);

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_1_ID, 0, "Đoạn 0");
        ChapterNarrationSegment retSeg1 = createSegment(SEGMENT_2_ID, 0, "Đoạn cũ 1");
        retSeg1.retire(NOW);
        ChapterNarrationSegment retSeg2 = createSegment(SEGMENT_3_ID, 1, "Đoạn cũ 2");
        retSeg2.retire(NOW);
        ChapterNarrationSegment retSeg3 = createSegment(SEGMENT_4_ID, 2, "Đoạn cũ 3 (không có audio)");
        retSeg3.retire(NOW);

        ChapterNarrationAudio audio1 = ChapterNarrationAudio.create(UUID.randomUUID(), SEGMENT_2_ID, VOICE_1_ID, MEDIA_ASSET_1_ID, 1L, NOW);
        ChapterNarrationAudio audio2 = ChapterNarrationAudio.create(UUID.randomUUID(), SEGMENT_2_ID, VOICE_2_ID, MEDIA_ASSET_2_ID, 1L, NOW);
        ChapterNarrationAudio audio3 = ChapterNarrationAudio.create(UUID.randomUUID(), SEGMENT_3_ID, VOICE_1_ID, MEDIA_ASSET_1_ID, 1L, NOW);

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                .thenReturn(List.of(retSeg1, retSeg2, retSeg3));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(List.of());
        when(audioRepositoryPort.findBySegmentIdIn(List.of(SEGMENT_2_ID, SEGMENT_3_ID, SEGMENT_4_ID)))
                .thenReturn(List.of(audio1, audio2, audio3));

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, VOICE_1_ID);

        assertThat(result.retiredSegmentCount()).isEqualTo(3);
        assertThat(result.obsoleteRetiredSegmentCount()).isEqualTo(2);
        assertThat(result.obsoleteRetiredAudioCount()).isEqualTo(3);
        assertThat(result.contentChangeWarning()).isTrue();
    }

    @Test
    @DisplayName("18. CURRENT segment audio must NOT be counted as obsolete retired audio")
    void shouldNotCountCurrentSegmentAudioAsObsoleteRetiredAudio() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();
        ManagedVoice voice = createVoice(VOICE_1_ID, "voice-1", "Minh Đức", ManagedVoiceStatus.ACTIVE, 1, true, 1L);

        ChapterNarrationSegment curSeg = createSegment(SEGMENT_1_ID, 0, "Đoạn hiện tại");
        ChapterNarrationSegment retSeg = createSegment(SEGMENT_2_ID, 0, "Đoạn đã retire không có audio");
        retSeg.retire(NOW);

        ChapterNarrationAudio curAudio = ChapterNarrationAudio.create(UUID.randomUUID(), SEGMENT_1_ID, VOICE_1_ID, MEDIA_ASSET_1_ID, 1L, 51840L, 48000, NOW);

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(curSeg));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                .thenReturn(List.of(retSeg));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(List.of(curAudio));
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(Collections.emptyList());
        when(audioRepositoryPort.findBySegmentIdIn(List.of(SEGMENT_2_ID)))
                .thenReturn(Collections.emptyList());

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, VOICE_1_ID);

        assertThat(result.currentSegmentCount()).isEqualTo(1);
        assertThat(result.readyCount()).isEqualTo(1);
        assertThat(result.currentGenerationRequiredCount()).isEqualTo(0);
        assertThat(result.retiredSegmentCount()).isEqualTo(1);
        assertThat(result.obsoleteRetiredSegmentCount()).isEqualTo(0);
        assertThat(result.obsoleteRetiredAudioCount()).isEqualTo(0);
        assertThat(result.contentChangeWarning()).isFalse();
    }

    @Test
    @DisplayName("Should classify same-revision legacy untimed audio as OUTDATED in overview")
    void shouldCountSameRevisionLegacyUntimedAudioAsOutdated() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();
        ManagedVoice voice = createVoice(VOICE_1_ID, "voice-1", "Minh Đức", ManagedVoiceStatus.ACTIVE, 1, true, 2L);

        ChapterNarrationSegment curSeg = createSegment(SEGMENT_1_ID, 0, "Đoạn 0 - Untimed");

        // Audio at revision 2L (same as voice revision 2L), but legacy untimed (null/null)
        ChapterNarrationAudio curAudio = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_1_ID, VOICE_1_ID, MEDIA_ASSET_1_ID, 2L, NOW
        );

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(managedVoiceRepositoryPort.findAll()).thenReturn(List.of(voice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(curSeg));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                .thenReturn(Collections.emptyList());
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(List.of(curAudio));
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_1_ID), VOICE_1_ID))
                .thenReturn(Collections.emptyList());

        GetAdminChapterNarrationOverviewResult result = useCase.execute(CHAPTER_ID, VOICE_1_ID);

        assertThat(result.currentSegmentCount()).isEqualTo(1);
        assertThat(result.readyCount()).isEqualTo(0);
        assertThat(result.outdatedCount()).isEqualTo(1);
        assertThat(result.missingCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.currentGenerationRequiredCount()).isEqualTo(1);
        assertThat(result.segments().get(0).healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
        assertThat(result.segments().get(0).isOutdated()).isTrue();
    }
}
