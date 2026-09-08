package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableChapterReference;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationManifestDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationSegmentDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationVoiceDTO;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetPublicChapterNarrationManifestUseCase Unit Tests (MS-04.9H.7A, MS-04.9H.7A1)")
class GetPublicChapterNarrationManifestUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOICE_1_ID = UUID.fromString("22222222-2222-2222-2222-222222222221");
    private static final UUID VOICE_2_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_3_ID = UUID.fromString("22222222-2222-2222-2222-222222222223");
    private static final UUID SEGMENT_0_ID = UUID.fromString("33333333-3333-3333-3333-333333333330");
    private static final UUID SEGMENT_1_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID SEGMENT_2_ID = UUID.fromString("33333333-3333-3333-3333-333333333332");
    private static final UUID MEDIA_ASSET_0_ID = UUID.fromString("44444444-4444-4444-4444-444444444440");
    private static final UUID MEDIA_ASSET_1_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");

    @Mock
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;

    @Mock
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;

    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;

    @Mock
    private ChapterNarrationAudioRepositoryPort audioRepositoryPort;

    @Mock
    private ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;

    private GetPublicChapterNarrationManifestUseCase useCase;

    private ManagedVoice defaultVoice;
    private ManagedVoice secondaryVoice;
    private ManagedVoice thirdVoice;
    private ChapterNarrationSegment segment0;
    private ChapterNarrationSegment segment1;
    private ChapterNarrationSegment segment2;

    @BeforeEach
    void setUp() {
        useCase = new GetPublicChapterNarrationManifestUseCase(
                readerChapterAccessQueryPort,
                managedVoiceRepositoryPort,
                segmentRepositoryPort,
                audioRepositoryPort,
                failureRepositoryPort
        );

        defaultVoice = ManagedVoice.rehydrate(
                VOICE_1_ID, "kiemlai-male-01", "Minh Đức", "vn_male_01",
                ManagedVoiceStatus.ACTIVE, 1, true, 1L,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")
        );

        secondaryVoice = ManagedVoice.rehydrate(
                VOICE_2_ID, "kiemlai-female-01", "Thu Trang", "vn_female_01",
                ManagedVoiceStatus.ACTIVE, 2, false, 2L,
                Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z")
        );

        thirdVoice = ManagedVoice.rehydrate(
                VOICE_3_ID, "kiemlai-male-02", "Bảo Long", "vn_male_02",
                ManagedVoiceStatus.ACTIVE, 3, false, 1L,
                Instant.parse("2026-01-03T00:00:00Z"), Instant.parse("2026-01-03T00:00:00Z")
        );

        segment0 = ChapterNarrationSegment.create(SEGMENT_0_ID, CHAPTER_ID, 0, "Đoạn văn 0", Instant.now());
        segment1 = ChapterNarrationSegment.create(SEGMENT_1_ID, CHAPTER_ID, 1, "Đoạn văn 1", Instant.now());
        segment2 = ChapterNarrationSegment.create(SEGMENT_2_ID, CHAPTER_ID, 2, "Đoạn văn 2", Instant.now());
    }

    @Test
    @DisplayName("1. ACTIVE default voice is selected when voiceKey is absent")
    void shouldSelectActiveDefaultVoiceWhenVoiceKeyAbsent() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(defaultVoice, secondaryVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0));

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, null);

        assertThat(result.selectedVoice()).isNotNull();
        assertThat(result.selectedVoice().voiceKey()).isEqualTo("kiemlai-male-01");
        assertThat(result.selectedVoice().defaultVoice()).isTrue();
    }

    @Test
    @DisplayName("2. Explicit ACTIVE voiceKey selection resolves requested voice")
    void shouldSelectExplicitActiveVoiceKey() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(defaultVoice, secondaryVoice));
        when(managedVoiceRepositoryPort.findByVoiceKey("kiemlai-female-01"))
                .thenReturn(Optional.of(secondaryVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0));

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, "kiemlai-female-01");

        assertThat(result.selectedVoice()).isNotNull();
        assertThat(result.selectedVoice().voiceKey()).isEqualTo("kiemlai-female-01");
        assertThat(result.selectedVoice().displayName()).isEqualTo("Thu Trang");
    }

    @Test
    @DisplayName("3. Unknown voiceKey is rejected with ManagedVoiceNotFoundException")
    void shouldRejectUnknownVoiceKey() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(defaultVoice));
        when(managedVoiceRepositoryPort.findByVoiceKey("unknown-voice"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, "unknown-voice"))
                .isInstanceOf(ManagedVoiceNotFoundException.class)
                .hasMessageContaining("unknown-voice");
    }

    @Test
    @DisplayName("4. DISABLED voice is not publicly selectable and throws ManagedVoiceInvalidStateException")
    void shouldRejectDisabledVoice() {
        ManagedVoice disabledVoice = ManagedVoice.rehydrate(
                UUID.randomUUID(), "kiemlai-disabled", "Disabled Voice", "vn_dis",
                ManagedVoiceStatus.DISABLED, 3, false, 1L,
                Instant.now(), Instant.now()
        );

        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(defaultVoice));
        when(managedVoiceRepositoryPort.findByVoiceKey("kiemlai-disabled"))
                .thenReturn(Optional.of(disabledVoice));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, "kiemlai-disabled"))
                .isInstanceOf(ManagedVoiceInvalidStateException.class)
                .hasMessageContaining("kiemlai-disabled");
    }

    @Test
    @DisplayName("5. No ACTIVE voices in system returns selectedVoice=null, availableVoices=[], segments=[] and performs no batch audio/failure queries")
    void shouldReturnEmptyStateAndSkipBatchQueriesWhenNoActiveVoices() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of());

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, null);

        assertThat(result.availableVoices()).isEmpty();
        assertThat(result.selectedVoice()).isNull();
        assertThat(result.segments()).isEmpty();

        verify(segmentRepositoryPort, never()).findByChapterIdAndStatus(any(), any());
        verify(audioRepositoryPort, never()).findBySegmentIdInAndManagedVoiceId(any(), any());
        verify(failureRepositoryPort, never()).findBySegmentIdInAndManagedVoiceId(any(), any());
    }

    @Test
    @DisplayName("6. Unsorted ACTIVE voices from port are deterministically sorted in application logic by displayOrder ASC")
    void shouldSortActiveVoicesDeterministicallyInApplicationLogic() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        // Return unsorted list from repository (order 3, 1, 2)
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(thirdVoice, defaultVoice, secondaryVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0));

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, null);

        assertThat(result.availableVoices())
                .extracting(PublicNarrationVoiceDTO::voiceKey)
                .containsExactly("kiemlai-male-01", "kiemlai-female-01", "kiemlai-male-02");
    }

    @Test
    @DisplayName("7. Fallback selection uses the explicitly sorted list when no default voice exists")
    void shouldSelectFirstVoiceFromSortedListWhenNoDefaultVoice() {
        ManagedVoice voiceA = ManagedVoice.rehydrate(
                VOICE_2_ID, "kiemlai-voice-a", "Voice A", "vn_a",
                ManagedVoiceStatus.ACTIVE, 10, false, 1L,
                Instant.now(), Instant.now()
        );
        ManagedVoice voiceB = ManagedVoice.rehydrate(
                VOICE_3_ID, "kiemlai-voice-b", "Voice B", "vn_b",
                ManagedVoiceStatus.ACTIVE, 5, false, 1L,
                Instant.now(), Instant.now()
        );

        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        // Return voiceA (order 10) before voiceB (order 5)
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(voiceA, voiceB));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0));

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, null);

        // voiceB has displayOrder 5, so it should be sorted first and selected as fallback
        assertThat(result.selectedVoice()).isNotNull();
        assertThat(result.selectedVoice().voiceKey()).isEqualTo("kiemlai-voice-b");
    }

    @Test
    @DisplayName("8. CURRENT segments are sorted ASC by segmentIndex")
    void shouldSortCurrentSegmentsAscendingByIndex() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(defaultVoice));
        // Return unsorted list
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment2, segment0, segment1));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(any(), eq(VOICE_1_ID)))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(any(), eq(VOICE_1_ID)))
                .thenReturn(List.of());

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, null);

        assertThat(result.segments()).extracting(PublicNarrationSegmentDTO::segmentIndex)
                .containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("9. READY status is playable, exposes audioUrl, and wins over stale failure diagnostics")
    void shouldBePlayableOnReadyAndWinOverStaleFailure() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(defaultVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0));

        // Compatible audio with revision 1 (matches defaultVoice revision 1)
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_0_ID, VOICE_1_ID, MEDIA_ASSET_0_ID, 1L, Instant.now()
        );
        // Stale failure diagnostic also exists
        ChapterNarrationAudioFailure staleFailure = ChapterNarrationAudioFailure.create(
                UUID.randomUUID(), SEGMENT_0_ID, VOICE_1_ID,
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                1L, "NETWORK_TIMEOUT", Instant.now()
        );

        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_1_ID))
                .thenReturn(List.of(audio));
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_1_ID))
                .thenReturn(List.of(staleFailure));

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, null);

        PublicNarrationSegmentDTO segmentDTO = result.segments().get(0);
        assertThat(segmentDTO.healthStatus()).isEqualTo("READY");
        assertThat(segmentDTO.playable()).isTrue();
        assertThat(segmentDTO.audioUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_0_ID + "/content");
    }

    @Test
    @DisplayName("10. OUTDATED status remains playable using existing old assigned audio")
    void shouldBePlayableOnOutdatedWithOldAudio() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(secondaryVoice)); // secondaryVoice has synthesisRevision = 2
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0));

        // Old audio with revision 1 (stale compared to secondaryVoice revision 2)
        ChapterNarrationAudio oldAudio = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_0_ID, VOICE_2_ID, MEDIA_ASSET_1_ID, 1L, Instant.now()
        );

        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_2_ID))
                .thenReturn(List.of(oldAudio));
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_2_ID))
                .thenReturn(List.of());

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, null);

        PublicNarrationSegmentDTO segmentDTO = result.segments().get(0);
        assertThat(segmentDTO.healthStatus()).isEqualTo("OUTDATED");
        assertThat(segmentDTO.playable()).isTrue();
        assertThat(segmentDTO.audioUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_1_ID + "/content");
    }

    @Test
    @DisplayName("11. MISSING status is not playable and has null audioUrl")
    void shouldNotBePlayableOnMissing() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(defaultVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0));

        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_1_ID))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_1_ID))
                .thenReturn(List.of());

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, null);

        PublicNarrationSegmentDTO segmentDTO = result.segments().get(0);
        assertThat(segmentDTO.healthStatus()).isEqualTo("MISSING");
        assertThat(segmentDTO.playable()).isFalse();
        assertThat(segmentDTO.audioUrl()).isNull();
    }

    @Test
    @DisplayName("12. FAILED status is not playable and has null audioUrl")
    void shouldNotBePlayableOnFailed() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(defaultVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0));

        ChapterNarrationAudioFailure failure = ChapterNarrationAudioFailure.create(
                UUID.randomUUID(), SEGMENT_0_ID, VOICE_1_ID,
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                1L, "INTERNAL_ERROR", Instant.now()
        );

        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_1_ID))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_1_ID))
                .thenReturn(List.of(failure));

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, null);

        PublicNarrationSegmentDTO segmentDTO = result.segments().get(0);
        assertThat(segmentDTO.healthStatus()).isEqualTo("FAILED");
        assertThat(segmentDTO.playable()).isFalse();
        assertThat(segmentDTO.audioUrl()).isNull();
    }

    @Test
    @DisplayName("13. Provider, failure, and storage internals are completely absent from public DTO")
    void shouldNotLeakInternalDetailsInPublicDto() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(defaultVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0));

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, null);

        // Voice DTO must only have voiceKey, displayName, defaultVoice
        assertThat(result.selectedVoice().voiceKey()).isEqualTo("kiemlai-male-01");
        assertThat(result.selectedVoice().displayName()).isEqualTo("Minh Đức");
        assertThat(result.selectedVoice().defaultVoice()).isTrue();

        // Class fields check via reflection/contract
        assertThat(PublicNarrationVoiceDTO.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .containsExactlyInAnyOrder("voiceKey", "displayName", "defaultVoice");

        assertThat(PublicNarrationSegmentDTO.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .containsExactlyInAnyOrder("segmentId", "segmentIndex", "healthStatus", "playable", "audioUrl");
    }

    @Test
    @DisplayName("14. Unpublished chapter narration is not exposed and throws ChapterNotFoundException")
    void shouldRejectUnpublishedChapter() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, null))
                .isInstanceOf(ChapterNotFoundException.class)
                .hasMessageContaining(CHAPTER_ID.toString());

        verify(managedVoiceRepositoryPort, never()).findAllActive();
        verify(segmentRepositoryPort, never()).findByChapterIdAndStatus(any(), any());
    }

    @Test
    @DisplayName("15. Narration audio and failure loading is batch-based (exactly 1 call for all segment IDs)")
    void shouldPerformBatchLoadingForAudioAndFailures() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(defaultVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0, segment1, segment2));

        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(
                List.of(SEGMENT_0_ID, SEGMENT_1_ID, SEGMENT_2_ID), VOICE_1_ID
        )).thenReturn(List.of());

        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(
                List.of(SEGMENT_0_ID, SEGMENT_1_ID, SEGMENT_2_ID), VOICE_1_ID
        )).thenReturn(List.of());

        useCase.execute(CHAPTER_ID, null);

        // Verify batch methods are called exactly once with all segment IDs
        verify(audioRepositoryPort, times(1)).findBySegmentIdInAndManagedVoiceId(
                List.of(SEGMENT_0_ID, SEGMENT_1_ID, SEGMENT_2_ID), VOICE_1_ID
        );
        verify(failureRepositoryPort, times(1)).findBySegmentIdInAndManagedVoiceId(
                List.of(SEGMENT_0_ID, SEGMENT_1_ID, SEGMENT_2_ID), VOICE_1_ID
        );
    }

    @Test
    @DisplayName("16. Media delivery uses Media public contract URL format without direct storage access")
    void shouldGenerateCanonicalMediaDeliveryUrl() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(defaultVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0));

        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_0_ID, VOICE_1_ID, MEDIA_ASSET_0_ID, 1L, Instant.now()
        );
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_1_ID))
                .thenReturn(List.of(audio));
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_1_ID))
                .thenReturn(List.of());

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, null);

        assertThat(result.segments().get(0).audioUrl())
                .isEqualTo("/media/assets/" + MEDIA_ASSET_0_ID + "/content")
                .doesNotContain("file://")
                .doesNotContain("s3://")
                .doesNotContain("blob");
    }

    @Test
    @DisplayName("17. OUTDATED status remains playable even when current-revision regeneration failure exists")
    void shouldRemainPlayableOnOutdatedEvenWithCurrentRevisionFailure() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(secondaryVoice)); // secondaryVoice has synthesisRevision = 2
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0));

        // Audio at rev 1 (stale compared to secondaryVoice rev 2)
        ChapterNarrationAudio oldAudio = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_0_ID, VOICE_2_ID, MEDIA_ASSET_1_ID, 1L, Instant.now()
        );
        // Current regeneration failure for rev 2
        ChapterNarrationAudioFailure currentFailure = ChapterNarrationAudioFailure.create(
                UUID.randomUUID(), SEGMENT_0_ID, VOICE_2_ID,
                NarrationAudioOperation.REGENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                2L, "TIMEOUT", Instant.now()
        );

        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_2_ID))
                .thenReturn(List.of(oldAudio));
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_2_ID))
                .thenReturn(List.of(currentFailure));

        PublicChapterNarrationManifestDTO result = useCase.execute(CHAPTER_ID, null);

        PublicNarrationSegmentDTO segmentDTO = result.segments().get(0);
        assertThat(segmentDTO.healthStatus()).isEqualTo("OUTDATED");
        assertThat(segmentDTO.playable()).isTrue();
        assertThat(segmentDTO.audioUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_1_ID + "/content");
    }

    @Test
    @DisplayName("18. Public manifest read use case performs no save or delete writes to failure repository")
    void shouldNeverPerformWritesOrDeletesDuringPublicManifestQuery() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(managedVoiceRepositoryPort.findAllActive())
                .thenReturn(List.of(defaultVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment0));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_1_ID))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_1_ID))
                .thenReturn(List.of());

        useCase.execute(CHAPTER_ID, null);

        verify(failureRepositoryPort, never()).save(any());
        verify(failureRepositoryPort, never()).deleteSupersededBySuccessfulRevision(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }
}
