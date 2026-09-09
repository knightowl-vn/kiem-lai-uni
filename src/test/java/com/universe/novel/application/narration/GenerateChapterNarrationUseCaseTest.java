package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
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
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GenerateChapterNarrationUseCase Unit Tests")
class GenerateChapterNarrationUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOLUME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VOICE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final UUID SEGMENT_0_ID = UUID.fromString("55555555-5555-5555-5555-555555555550");
    private static final UUID SEGMENT_1_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID SEGMENT_2_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");
    private static final UUID SEGMENT_3_ID = UUID.fromString("55555555-5555-5555-5555-555555555553");

    private static final UUID MEDIA_0_ID = UUID.fromString("66666666-6666-6666-6666-666666666660");
    private static final UUID MEDIA_1_ID = UUID.fromString("66666666-6666-6666-6666-666666666661");
    private static final UUID MEDIA_2_ID = UUID.fromString("66666666-6666-6666-6666-666666666662");

    private static final Instant T0 = Instant.parse("2026-09-06T10:00:00Z");

    @Mock
    private ChapterRepositoryPort chapterRepositoryPort;

    @Mock
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;

    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;

    @Mock
    private ChapterNarrationAudioRepositoryPort audioRepositoryPort;

    @Mock
    private ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;

    @Mock
    private GenerateChapterNarrationAudioUseCase generateChapterNarrationAudioUseCase;

    @Mock
    private RegenerateChapterNarrationAudioUseCase regenerateChapterNarrationAudioUseCase;

    @Mock
    private CleanupCompletedChapterNarrationRetiredAudioUseCase cleanupCoordinator;

    private ChapterNarrationGenerationPlanner generationPlanner;
    private GenerateChapterNarrationUseCase useCase;

    @BeforeEach
    void setUp() {
        generationPlanner = new ChapterNarrationGenerationPlanner();
        useCase = new GenerateChapterNarrationUseCase(
                chapterRepositoryPort,
                managedVoiceRepositoryPort,
                segmentRepositoryPort,
                audioRepositoryPort,
                failureRepositoryPort,
                generationPlanner,
                generateChapterNarrationAudioUseCase,
                regenerateChapterNarrationAudioUseCase,
                cleanupCoordinator
        );
    }

    private Chapter createChapter(ChapterStatus status) {
        return Chapter.rehydrate(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương 1",
                new Slug("chuong-1"),
                "Tóm tắt",
                "Nội dung chương",
                status,
                USER_ID,
                USER_ID,
                status == ChapterStatus.PUBLISHED ? USER_ID : null,
                status == ChapterStatus.ARCHIVED ? USER_ID : null,
                T0,
                T0,
                status == ChapterStatus.PUBLISHED ? T0 : null,
                status == ChapterStatus.ARCHIVED ? T0 : null,
                1L,
                1L
        );
    }

    private ManagedVoice createVoice(ManagedVoiceStatus status, long revision) {
        return ManagedVoice.rehydrate(
                VOICE_ID,
                "voice-hn",
                "Hà Nội Nữ",
                "provider-hn",
                status,
                1,
                status == ManagedVoiceStatus.ACTIVE,
                revision,
                T0,
                T0
        );
    }

    private ChapterNarrationSegment createSegment(UUID id, int index, String text) {
        return ChapterNarrationSegment.create(id, CHAPTER_ID, index, text, T0);
    }

    @Test
    void adminLegacyBackfillSkipsThreeReadySegmentsWithoutCallingGenerationPrimitives() {
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, 2L)));
        List<UUID> segmentIds = List.of(SEGMENT_0_ID, SEGMENT_1_ID, SEGMENT_2_ID);
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(createSegment(SEGMENT_0_ID, 0, "Đoạn 0"),
                        createSegment(SEGMENT_1_ID, 1, "Đoạn 1"), createSegment(SEGMENT_2_ID, 2, "Đoạn 2")));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, VOICE_ID)).thenReturn(List.of(
                ChapterNarrationAudio.create(UUID.randomUUID(), SEGMENT_0_ID, VOICE_ID, MEDIA_0_ID, 2L, T0),
                ChapterNarrationAudio.create(UUID.randomUUID(), SEGMENT_1_ID, VOICE_ID, MEDIA_1_ID, 2L, T0),
                ChapterNarrationAudio.create(UUID.randomUUID(), SEGMENT_2_ID, VOICE_ID, MEDIA_2_ID, 2L, T0)
        ));
        BuildChapterNarrationPlaybackUseCase builder = mock(BuildChapterNarrationPlaybackUseCase.class);
        when(builder.execute(any())).thenReturn(new BuildChapterNarrationPlaybackResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        AdminNarrationGenerationWorker worker = new AdminNarrationGenerationWorker(useCase, builder);
        AdminNarrationGenerationDispatcher dispatcher = new AdminNarrationGenerationDispatcher(Runnable::run, worker);

        dispatcher.dispatch(CHAPTER_ID, VOICE_ID);

        assertThat(dispatcher.getOperationState(CHAPTER_ID, VOICE_ID).status()).isEqualTo(AdminNarrationOperationStatus.SUCCEEDED);
        assertThat(dispatcher.getOperationState(CHAPTER_ID, VOICE_ID).message()).contains("đã có: 3, tạo mới: 0, cập nhật: 0");
        verify(builder).execute(new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID));
        verifyNoInteractions(generateChapterNarrationAudioUseCase, regenerateChapterNarrationAudioUseCase);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("1. Rejects null arguments or null command")
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> useCase.execute((GenerateChapterNarrationCommand) null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(null, VOICE_ID))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("2. Preflight: missing chapter throws ChapterNotFoundException before loading voice or segments")
    void shouldThrowWhenChapterNotFound() {
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, VOICE_ID))
                .isInstanceOf(ChapterNotFoundException.class)
                .hasMessageContaining(CHAPTER_ID.toString());

        verifyNoInteractions(managedVoiceRepositoryPort);
        verifyNoInteractions(segmentRepositoryPort);
        verifyNoInteractions(generateChapterNarrationAudioUseCase);
        verifyNoInteractions(regenerateChapterNarrationAudioUseCase);
    }

    @Test
    @DisplayName("3. Preflight: non-PUBLISHED chapter throws IllegalStateException before loading voice or segments")
    void shouldThrowWhenChapterNotPublished() {
        Chapter draftChapter = createChapter(ChapterStatus.DRAFT);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(draftChapter));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, VOICE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHED")
                .hasMessageContaining("DRAFT");

        verifyNoInteractions(managedVoiceRepositoryPort);
        verifyNoInteractions(segmentRepositoryPort);
        verifyNoInteractions(generateChapterNarrationAudioUseCase);
        verifyNoInteractions(regenerateChapterNarrationAudioUseCase);
    }

    @Test
    @DisplayName("4. Preflight: missing voice throws ManagedVoiceNotFoundException before loading segments")
    void shouldThrowWhenManagedVoiceNotFound() {
        Chapter publishedChapter = createChapter(ChapterStatus.PUBLISHED);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, VOICE_ID))
                .isInstanceOf(ManagedVoiceNotFoundException.class)
                .hasMessageContaining(VOICE_ID.toString());

        verifyNoInteractions(segmentRepositoryPort);
        verifyNoInteractions(generateChapterNarrationAudioUseCase);
        verifyNoInteractions(regenerateChapterNarrationAudioUseCase);
    }

    @Test
    @DisplayName("5. Preflight: DISABLED voice throws ManagedVoiceInvalidStateException before loading segments")
    void shouldThrowWhenManagedVoiceIsDisabled() {
        Chapter publishedChapter = createChapter(ChapterStatus.PUBLISHED);
        ManagedVoice disabledVoice = createVoice(ManagedVoiceStatus.DISABLED, 1L);

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(disabledVoice));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, VOICE_ID))
                .isInstanceOf(ManagedVoiceInvalidStateException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("DISABLED");

        verifyNoInteractions(segmentRepositoryPort);
        verifyNoInteractions(generateChapterNarrationAudioUseCase);
        verifyNoInteractions(regenerateChapterNarrationAudioUseCase);
    }

    @Test
    @DisplayName("6. Empty CURRENT segments returns empty successful result without calling repositories or primitives")
    void shouldReturnEmptyResultWhenNoCurrentSegments() {
        Chapter publishedChapter = createChapter(ChapterStatus.PUBLISHED);
        ManagedVoice activeVoice = createVoice(ManagedVoiceStatus.ACTIVE, 1L);

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(activeVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(Collections.emptyList());

        GenerateChapterNarrationResult result = useCase.execute(new GenerateChapterNarrationCommand(CHAPTER_ID, VOICE_ID));

        assertThat(result.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(result.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.items()).isEmpty();
        assertThat(result.totalSegments()).isEqualTo(0);
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.isCompleteSuccess()).isTrue();

        verify(audioRepositoryPort, never()).findBySegmentIdInAndManagedVoiceId(any(), any());
        verify(failureRepositoryPort, never()).findBySegmentIdInAndManagedVoiceId(any(), any());
        verifyNoInteractions(generateChapterNarrationAudioUseCase);
        verifyNoInteractions(regenerateChapterNarrationAudioUseCase);
    }

    @Test
    @DisplayName("7. Mixed plan: executes plan in segmentIndex ASC order with batch planning and zero N+1")
    void shouldExecuteMixedPlanInAscendingOrderWithBatchQueries() {
        Chapter publishedChapter = createChapter(ChapterStatus.PUBLISHED);
        // Voice is at revision 2
        ManagedVoice activeVoice = createVoice(ManagedVoiceStatus.ACTIVE, 2L);

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_0_ID, 0, "Đoạn 0: READY");
        ChapterNarrationSegment seg1 = createSegment(SEGMENT_1_ID, 1, "Đoạn 1: MISSING -> GENERATE");
        ChapterNarrationSegment seg2 = createSegment(SEGMENT_2_ID, 2, "Đoạn 2: OUTDATED -> REGENERATE");
        ChapterNarrationSegment seg3 = createSegment(SEGMENT_3_ID, 3, "Đoạn 3: FAILED -> GENERATE (REUSED winner)");

        // seg0 has audio at rev 2 -> READY
        ChapterNarrationAudio audio0 = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_0_ID, VOICE_ID, MEDIA_0_ID, 2L, T0
        );
        // seg2 has audio at rev 1 (stale) -> OUTDATED
        ChapterNarrationAudio audio2 = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_2_ID, VOICE_ID, MEDIA_1_ID, 1L, T0
        );
        // seg3 has failure at rev 2 -> FAILED
        ChapterNarrationAudioFailure failure3 = ChapterNarrationAudioFailure.create(
                UUID.randomUUID(), SEGMENT_3_ID, VOICE_ID,
                NarrationAudioOperation.INITIAL_GENERATION, NarrationAudioFailureStage.TTS_SYNTHESIS,
                2L, "TtsException", T0
        );

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(activeVoice));
        // Return unordered segments to verify orchestrator sorts them
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg2, seg0, seg3, seg1));

        List<UUID> expectedSegmentIds = List.of(SEGMENT_0_ID, SEGMENT_1_ID, SEGMENT_2_ID, SEGMENT_3_ID);
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(expectedSegmentIds, VOICE_ID))
                .thenReturn(List.of(audio0, audio2));
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(expectedSegmentIds, VOICE_ID))
                .thenReturn(List.of(failure3));

        // Mock primitive execution responses
        when(generateChapterNarrationAudioUseCase.execute(SEGMENT_1_ID, VOICE_ID))
                .thenReturn(new GenerateChapterNarrationAudioResult(
                        UUID.randomUUID(), SEGMENT_1_ID, VOICE_ID, MEDIA_2_ID, 2L, NarrationAudioGenerationOutcome.GENERATED
                ));

        when(regenerateChapterNarrationAudioUseCase.execute(SEGMENT_2_ID, VOICE_ID))
                .thenReturn(new RegenerateChapterNarrationAudioResult(
                        audio2.getId(), SEGMENT_2_ID, VOICE_ID, MEDIA_1_ID, MEDIA_2_ID, 2L, RegenerateNarrationAudioOutcome.REGENERATED
                ));

        when(generateChapterNarrationAudioUseCase.execute(SEGMENT_3_ID, VOICE_ID))
                .thenReturn(new GenerateChapterNarrationAudioResult(
                        UUID.randomUUID(), SEGMENT_3_ID, VOICE_ID, MEDIA_2_ID, 2L, NarrationAudioGenerationOutcome.REUSED
                ));

        GenerateChapterNarrationResult result = useCase.execute(CHAPTER_ID, VOICE_ID);

        // Verify summary metrics
        assertThat(result.totalSegments()).isEqualTo(4);
        assertThat(result.skippedReadyCount()).isEqualTo(1);
        assertThat(result.generatedCount()).isEqualTo(2); // GENERATED + REUSED
        assertThat(result.regeneratedCount()).isEqualTo(1); // REGENERATED
        assertThat(result.completedWorkCount()).isEqualTo(3);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.retryRequiredCount()).isEqualTo(0);
        assertThat(result.remainingWorkCount()).isEqualTo(0);
        assertThat(result.isCompleteSuccess()).isTrue();

        // Verify items
        List<ChapterNarrationSegmentExecutionResult> items = result.items();
        assertThat(items).hasSize(4);

        // Item 0: SKIP_READY
        assertThat(items.get(0).segmentId()).isEqualTo(SEGMENT_0_ID);
        assertThat(items.get(0).segmentIndex()).isEqualTo(0);
        assertThat(items.get(0).plannedAction()).isEqualTo(ChapterNarrationGenerationAction.SKIP_READY);
        assertThat(items.get(0).executionOutcome()).isEqualTo(ChapterNarrationGenerationExecutionOutcome.SKIPPED_READY);
        assertThat(items.get(0).isSkipped()).isTrue();
        assertThat(items.get(0).isSuccess()).isTrue();

        // Item 1: GENERATE -> GENERATED
        assertThat(items.get(1).segmentId()).isEqualTo(SEGMENT_1_ID);
        assertThat(items.get(1).segmentIndex()).isEqualTo(1);
        assertThat(items.get(1).plannedAction()).isEqualTo(ChapterNarrationGenerationAction.GENERATE);
        assertThat(items.get(1).executionOutcome()).isEqualTo(ChapterNarrationGenerationExecutionOutcome.GENERATED);
        assertThat(items.get(1).isCompletedWork()).isTrue();
        assertThat(items.get(1).isSuccess()).isTrue();

        // Item 2: REGENERATE -> REGENERATED
        assertThat(items.get(2).segmentId()).isEqualTo(SEGMENT_2_ID);
        assertThat(items.get(2).segmentIndex()).isEqualTo(2);
        assertThat(items.get(2).plannedAction()).isEqualTo(ChapterNarrationGenerationAction.REGENERATE);
        assertThat(items.get(2).executionOutcome()).isEqualTo(ChapterNarrationGenerationExecutionOutcome.REGENERATED);
        assertThat(items.get(2).isCompletedWork()).isTrue();
        assertThat(items.get(2).isSuccess()).isTrue();

        // Item 3: GENERATE -> REUSED
        assertThat(items.get(3).segmentId()).isEqualTo(SEGMENT_3_ID);
        assertThat(items.get(3).segmentIndex()).isEqualTo(3);
        assertThat(items.get(3).plannedAction()).isEqualTo(ChapterNarrationGenerationAction.GENERATE);
        assertThat(items.get(3).executionOutcome()).isEqualTo(ChapterNarrationGenerationExecutionOutcome.REUSED);
        assertThat(items.get(3).isCompletedWork()).isTrue();
        assertThat(items.get(3).isSuccess()).isTrue();

        // Verify execution order
        InOrder inOrder = inOrder(generateChapterNarrationAudioUseCase, regenerateChapterNarrationAudioUseCase);
        inOrder.verify(generateChapterNarrationAudioUseCase).execute(SEGMENT_1_ID, VOICE_ID);
        inOrder.verify(regenerateChapterNarrationAudioUseCase).execute(SEGMENT_2_ID, VOICE_ID);
        inOrder.verify(generateChapterNarrationAudioUseCase).execute(SEGMENT_3_ID, VOICE_ID);

        // Verify batch queries were used and no N+1 single queries
        verify(audioRepositoryPort).findBySegmentIdInAndManagedVoiceId(expectedSegmentIds, VOICE_ID);
        verify(failureRepositoryPort).findBySegmentIdInAndManagedVoiceId(expectedSegmentIds, VOICE_ID);
        verify(audioRepositoryPort, never()).findBySegmentIdAndManagedVoiceId(any(), any());
        verify(failureRepositoryPort, never()).findBySegmentIdAndManagedVoiceId(any(), any());
    }

    @Test
    @DisplayName("8. Failure isolation: runtime exception during GENERATE is recorded as FAILED and returns safe generic error message")
    void shouldIsolateGenerateFailureAndContinueLoop() {
        Chapter publishedChapter = createChapter(ChapterStatus.PUBLISHED);
        ManagedVoice activeVoice = createVoice(ManagedVoiceStatus.ACTIVE, 1L);

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_0_ID, 0, "Đoạn 0 - Fails");
        ChapterNarrationSegment seg1 = createSegment(SEGMENT_1_ID, 1, "Đoạn 1 - Succeeds");

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(activeVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0, seg1));

        List<UUID> segmentIds = List.of(SEGMENT_0_ID, SEGMENT_1_ID);
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, VOICE_ID)).thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, VOICE_ID)).thenReturn(List.of());

        // seg0 throws RuntimeException with sensitive internal details
        when(generateChapterNarrationAudioUseCase.execute(SEGMENT_0_ID, VOICE_ID))
                .thenThrow(new IllegalStateException("TTS provider timeout with token secret-token-xyz at https://tts.provider.internal/v1"));

        // seg1 succeeds
        when(generateChapterNarrationAudioUseCase.execute(SEGMENT_1_ID, VOICE_ID))
                .thenReturn(new GenerateChapterNarrationAudioResult(
                        UUID.randomUUID(), SEGMENT_1_ID, VOICE_ID, MEDIA_1_ID, 1L, NarrationAudioGenerationOutcome.GENERATED
                ));

        GenerateChapterNarrationResult result = useCase.execute(CHAPTER_ID, VOICE_ID);

        assertThat(result.totalSegments()).isEqualTo(2);
        assertThat(result.generatedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.remainingWorkCount()).isEqualTo(1);
        assertThat(result.isCompleteSuccess()).isFalse();

        ChapterNarrationSegmentExecutionResult failedItem = result.items().get(0);
        assertThat(failedItem.segmentId()).isEqualTo(SEGMENT_0_ID);
        assertThat(failedItem.segmentIndex()).isEqualTo(0);
        assertThat(failedItem.isFailed()).isTrue();
        assertThat(failedItem.errorType()).isEqualTo("IllegalStateException");
        assertThat(failedItem.errorMessage()).isEqualTo(GenerateChapterNarrationUseCase.SAFE_EXECUTION_ERROR_MESSAGE);
        assertThat(failedItem.errorMessage()).doesNotContain("secret-token-xyz");
        assertThat(failedItem.errorMessage()).doesNotContain("https://tts.provider.internal/v1");

        ChapterNarrationSegmentExecutionResult successItem = result.items().get(1);
        assertThat(successItem.segmentId()).isEqualTo(SEGMENT_1_ID);
        assertThat(successItem.segmentIndex()).isEqualTo(1);
        assertThat(successItem.isCompletedWork()).isTrue();
        assertThat(successItem.executionOutcome()).isEqualTo(ChapterNarrationGenerationExecutionOutcome.GENERATED);
    }

    @Test
    @DisplayName("9. Failure isolation: runtime exception during REGENERATE is recorded as FAILED and returns safe generic error message")
    void shouldIsolateRegenerateFailureAndContinueLoop() {
        Chapter publishedChapter = createChapter(ChapterStatus.PUBLISHED);
        ManagedVoice activeVoice = createVoice(ManagedVoiceStatus.ACTIVE, 2L);

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_0_ID, 0, "Đoạn 0 - Stale");
        ChapterNarrationSegment seg1 = createSegment(SEGMENT_1_ID, 1, "Đoạn 1 - Missing");

        ChapterNarrationAudio audio0 = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_0_ID, VOICE_ID, MEDIA_0_ID, 1L, T0
        );

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(activeVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0, seg1));

        List<UUID> segmentIds = List.of(SEGMENT_0_ID, SEGMENT_1_ID);
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, VOICE_ID)).thenReturn(List.of(audio0));
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, VOICE_ID)).thenReturn(List.of());

        // seg0 regeneration fails with sensitive storage/SQL message
        when(regenerateChapterNarrationAudioUseCase.execute(SEGMENT_0_ID, VOICE_ID))
                .thenThrow(new RuntimeException("Media storage disk full at /mnt/storage/secure-bucket with key=secKey123"));

        // seg1 generation succeeds
        when(generateChapterNarrationAudioUseCase.execute(SEGMENT_1_ID, VOICE_ID))
                .thenReturn(new GenerateChapterNarrationAudioResult(
                        UUID.randomUUID(), SEGMENT_1_ID, VOICE_ID, MEDIA_1_ID, 2L, NarrationAudioGenerationOutcome.GENERATED
                ));

        GenerateChapterNarrationResult result = useCase.execute(CHAPTER_ID, VOICE_ID);

        assertThat(result.totalSegments()).isEqualTo(2);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.generatedCount()).isEqualTo(1);
        assertThat(result.remainingWorkCount()).isEqualTo(1);
        assertThat(result.isCompleteSuccess()).isFalse();

        ChapterNarrationSegmentExecutionResult failedItem = result.items().get(0);
        assertThat(failedItem.segmentId()).isEqualTo(SEGMENT_0_ID);
        assertThat(failedItem.plannedAction()).isEqualTo(ChapterNarrationGenerationAction.REGENERATE);
        assertThat(failedItem.isFailed()).isTrue();
        assertThat(failedItem.errorType()).isEqualTo("RuntimeException");
        assertThat(failedItem.errorMessage()).isEqualTo(GenerateChapterNarrationUseCase.SAFE_EXECUTION_ERROR_MESSAGE);
        assertThat(failedItem.errorMessage()).doesNotContain("secKey123");
        assertThat(failedItem.errorMessage()).doesNotContain("/mnt/storage/secure-bucket");

        ChapterNarrationSegmentExecutionResult successItem = result.items().get(1);
        assertThat(successItem.segmentId()).isEqualTo(SEGMENT_1_ID);
        assertThat(successItem.isCompletedWork()).isTrue();
    }

    @Test
    @DisplayName("10. Race outcomes: STALE maps to RETRY_REQUIRED, ALREADY_CURRENT maps to ALREADY_CURRENT")
    void shouldCorrectlyMapRaceOutcomes() {
        Chapter publishedChapter = createChapter(ChapterStatus.PUBLISHED);
        ManagedVoice activeVoice = createVoice(ManagedVoiceStatus.ACTIVE, 2L);

        ChapterNarrationSegment seg0 = createSegment(SEGMENT_0_ID, 0, "Đoạn 0 - Missing planned -> STALE encountered");
        ChapterNarrationSegment seg1 = createSegment(SEGMENT_1_ID, 1, "Đoạn 1 - Outdated planned -> ALREADY_CURRENT encountered");

        ChapterNarrationAudio audio1 = ChapterNarrationAudio.create(
                UUID.randomUUID(), SEGMENT_1_ID, VOICE_ID, MEDIA_1_ID, 1L, T0
        );

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(activeVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0, seg1));

        List<UUID> segmentIds = List.of(SEGMENT_0_ID, SEGMENT_1_ID);
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, VOICE_ID)).thenReturn(List.of(audio1));
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, VOICE_ID)).thenReturn(List.of());

        // seg0: Generate returns STALE
        when(generateChapterNarrationAudioUseCase.execute(SEGMENT_0_ID, VOICE_ID))
                .thenReturn(new GenerateChapterNarrationAudioResult(
                        UUID.randomUUID(), SEGMENT_0_ID, VOICE_ID, MEDIA_0_ID, 1L, NarrationAudioGenerationOutcome.STALE
                ));

        // seg1: Regenerate returns ALREADY_CURRENT
        when(regenerateChapterNarrationAudioUseCase.execute(SEGMENT_1_ID, VOICE_ID))
                .thenReturn(new RegenerateChapterNarrationAudioResult(
                        audio1.getId(), SEGMENT_1_ID, VOICE_ID, MEDIA_1_ID, MEDIA_1_ID, 2L, RegenerateNarrationAudioOutcome.ALREADY_CURRENT
                ));

        GenerateChapterNarrationResult result = useCase.execute(CHAPTER_ID, VOICE_ID);

        assertThat(result.totalSegments()).isEqualTo(2);
        assertThat(result.retryRequiredCount()).isEqualTo(1);
        assertThat(result.regeneratedCount()).isEqualTo(1);
        assertThat(result.completedWorkCount()).isEqualTo(1);
        assertThat(result.remainingWorkCount()).isEqualTo(1);
        assertThat(result.isCompleteSuccess()).isFalse();

        ChapterNarrationSegmentExecutionResult item0 = result.items().get(0);
        assertThat(item0.executionOutcome()).isEqualTo(ChapterNarrationGenerationExecutionOutcome.RETRY_REQUIRED);
        assertThat(item0.isRetryRequired()).isTrue();
        assertThat(item0.isSuccess()).isFalse();

        ChapterNarrationSegmentExecutionResult item1 = result.items().get(1);
        assertThat(item1.executionOutcome()).isEqualTo(ChapterNarrationGenerationExecutionOutcome.ALREADY_CURRENT);
        assertThat(item1.isCompletedWork()).isTrue();
        assertThat(item1.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("11. Result items collection is immutable")
    void shouldEnforceResultImmutability() {
        Chapter publishedChapter = createChapter(ChapterStatus.PUBLISHED);
        ManagedVoice activeVoice = createVoice(ManagedVoiceStatus.ACTIVE, 1L);
        ChapterNarrationSegment seg0 = createSegment(SEGMENT_0_ID, 0, "Đoạn 0");

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(activeVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_ID))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_ID))
                .thenReturn(List.of());

        when(generateChapterNarrationAudioUseCase.execute(SEGMENT_0_ID, VOICE_ID))
                .thenReturn(new GenerateChapterNarrationAudioResult(
                        UUID.randomUUID(), SEGMENT_0_ID, VOICE_ID, MEDIA_0_ID, 1L, NarrationAudioGenerationOutcome.GENERATED
                ));

        GenerateChapterNarrationResult result = useCase.execute(CHAPTER_ID, VOICE_ID);

        List<ChapterNarrationSegmentExecutionResult> items = result.items();
        assertThatThrownBy(() -> items.add(ChapterNarrationSegmentExecutionResult.skippedReady(UUID.randomUUID(), 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("12. Invokes completion cleanup coordinator only AFTER segment generation execution")
    void shouldInvokeCompletionCleanupCoordinatorAfterExecution() {
        Chapter publishedChapter = createChapter(ChapterStatus.PUBLISHED);
        ManagedVoice activeVoice = createVoice(ManagedVoiceStatus.ACTIVE, 1L);
        ChapterNarrationSegment seg0 = createSegment(SEGMENT_0_ID, 0, "Đoạn 0");

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(activeVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_ID))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_ID))
                .thenReturn(List.of());

        when(generateChapterNarrationAudioUseCase.execute(SEGMENT_0_ID, VOICE_ID))
                .thenReturn(new GenerateChapterNarrationAudioResult(
                        UUID.randomUUID(), SEGMENT_0_ID, VOICE_ID, MEDIA_0_ID, 1L, NarrationAudioGenerationOutcome.GENERATED
                ));

        ChapterNarrationCompletionCleanupSummary expectedSummary =
                new ChapterNarrationCompletionCleanupSummary(
                        ChapterNarrationCompletionCleanupStatus.COMPLETED,
                        true, 1, 1, 0, 0, 0, 0
                );
        when(cleanupCoordinator.execute(CHAPTER_ID, VOICE_ID)).thenReturn(expectedSummary);

        GenerateChapterNarrationResult result = useCase.execute(CHAPTER_ID, VOICE_ID);

        assertThat(result.cleanupSummary()).isEqualTo(expectedSummary);

        InOrder inOrder = org.mockito.Mockito.inOrder(generateChapterNarrationAudioUseCase, cleanupCoordinator);
        inOrder.verify(generateChapterNarrationAudioUseCase).execute(SEGMENT_0_ID, VOICE_ID);
        inOrder.verify(cleanupCoordinator).execute(CHAPTER_ID, VOICE_ID);
    }

    @Test
    @DisplayName("13. Safely handles unexpected cleanup coordinator failure without discarding generation results")
    void shouldSafelyHandleUnexpectedCleanupCoordinatorFailure() {
        Chapter publishedChapter = createChapter(ChapterStatus.PUBLISHED);
        ManagedVoice activeVoice = createVoice(ManagedVoiceStatus.ACTIVE, 1L);
        ChapterNarrationSegment seg0 = createSegment(SEGMENT_0_ID, 0, "Đoạn 0");

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(activeVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(seg0));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_ID))
                .thenReturn(List.of());
        when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_0_ID), VOICE_ID))
                .thenReturn(List.of());

        when(generateChapterNarrationAudioUseCase.execute(SEGMENT_0_ID, VOICE_ID))
                .thenReturn(new GenerateChapterNarrationAudioResult(
                        UUID.randomUUID(), SEGMENT_0_ID, VOICE_ID, MEDIA_0_ID, 1L, NarrationAudioGenerationOutcome.GENERATED
                ));

        // Cleanup coordinator throws unexpected RuntimeException
        when(cleanupCoordinator.execute(CHAPTER_ID, VOICE_ID))
                .thenThrow(new RuntimeException("Simulated coordinator unexpected failure"));

        GenerateChapterNarrationResult result = useCase.execute(CHAPTER_ID, VOICE_ID);

        // Generation result items are intact
        assertThat(result.isCompleteSuccess()).isTrue();
        assertThat(result.generatedCount()).isEqualTo(1);
        assertThat(result.totalSegments()).isEqualTo(1);

        // Safe fallback cleanup summary attached (COORDINATOR_FAILED with zero candidate counters)
        assertThat(result.cleanupSummary().status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.COORDINATOR_FAILED);
        assertThat(result.cleanupSummary().currentNarrationReady()).isFalse();
        assertThat(result.cleanupSummary().retiredAudioCandidateCount()).isEqualTo(0);
        assertThat(result.cleanupSummary().failedCount()).isEqualTo(0);
        assertThat(result.cleanupSummary().cleanupAttemptedCount()).isEqualTo(0);
        assertThat(result.cleanupSummary().cleanupComplete()).isFalse();
    }

    @Test
    @DisplayName("14. Returns notEligible cleanup summary when CURRENT segments are empty")
    void shouldReturnNotEligibleCleanupSummaryWhenCurrentSegmentsEmpty() {
        Chapter publishedChapter = createChapter(ChapterStatus.PUBLISHED);
        ManagedVoice activeVoice = createVoice(ManagedVoiceStatus.ACTIVE, 1L);

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(activeVoice));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(Collections.emptyList());

        GenerateChapterNarrationResult result = useCase.execute(CHAPTER_ID, VOICE_ID);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.cleanupSummary().status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.NOT_ELIGIBLE);
        assertThat(result.cleanupSummary().currentNarrationReady()).isFalse();
        org.mockito.Mockito.verify(cleanupCoordinator, org.mockito.Mockito.never()).execute(any(UUID.class), any(UUID.class));
    }
}
