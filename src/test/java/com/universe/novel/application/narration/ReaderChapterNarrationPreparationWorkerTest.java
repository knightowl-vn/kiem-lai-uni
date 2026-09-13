package com.universe.novel.application.narration;

import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReaderChapterNarrationPreparationWorker Unit Tests (MS-04.9H.9, H.9I5B)")
class ReaderChapterNarrationPreparationWorkerTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;

    @Mock
    private GenerateChapterNarrationUseCase generateChapterNarrationUseCase;

    @Mock
    private BuildChapterNarrationPlaybackUseCase buildChapterNarrationPlaybackUseCase;

    private ReaderChapterNarrationPreparationWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ReaderChapterNarrationPreparationWorker(
                readerChapterAccessQueryPort,
                generateChapterNarrationUseCase,
                buildChapterNarrationPlaybackUseCase
        );
    }

    @Test
    @DisplayName("1. Complete non-empty generation with preserved publication executes Generate then Build in exact order")
    void completeNonEmptyGenerationBuildsInExactOrder() {
        ReaderChapterAccessQueryPort.ReadableChapterReference accessView =
                new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(accessView));

        ChapterNarrationSegmentExecutionResult seg1 = ChapterNarrationSegmentExecutionResult.success(
                UUID.randomUUID(), 0, ChapterNarrationGenerationAction.GENERATE, ChapterNarrationGenerationExecutionOutcome.GENERATED
        );
        GenerateChapterNarrationResult successResult = new GenerateChapterNarrationResult(
                CHAPTER_ID, VOICE_ID, List.of(seg1)
        );
        when(generateChapterNarrationUseCase.execute(CHAPTER_ID, VOICE_ID))
                .thenReturn(successResult);

        when(buildChapterNarrationPlaybackUseCase.execute(any()))
                .thenReturn(new BuildChapterNarrationPlaybackResult(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BuildChapterNarrationPlaybackOutcome.BUILT
                ));

        worker.runPreparation(new ReaderChapterNarrationPreparationCommand(CHAPTER_ID, VOICE_ID));

        InOrder inOrder = inOrder(readerChapterAccessQueryPort, generateChapterNarrationUseCase, buildChapterNarrationPlaybackUseCase);
        inOrder.verify(readerChapterAccessQueryPort).findPublishedById(CHAPTER_ID);
        inOrder.verify(generateChapterNarrationUseCase).execute(CHAPTER_ID, VOICE_ID);
        inOrder.verify(readerChapterAccessQueryPort).findPublishedById(CHAPTER_ID);
        inOrder.verify(buildChapterNarrationPlaybackUseCase).execute(new BuildChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_ID));
    }

    @Test
    @DisplayName("2. Unreadable before generation (unpublished chapter or volume) stops immediately with zero generation and zero build")
    void unreadableBeforeGenerationStopsWithZeroGenerationAndBuild() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.empty());

        worker.runPreparation(new ReaderChapterNarrationPreparationCommand(CHAPTER_ID, VOICE_ID));

        verify(readerChapterAccessQueryPort).findPublishedById(CHAPTER_ID);
        verifyNoInteractions(generateChapterNarrationUseCase);
        verifyNoInteractions(buildChapterNarrationPlaybackUseCase);
    }

    @Test
    @DisplayName("3. Empty GenerateChapterNarrationResult stops before build")
    void emptyGenerationResultStopsBeforeBuild() {
        ReaderChapterAccessQueryPort.ReadableChapterReference accessView =
                new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(accessView));

        GenerateChapterNarrationResult emptyResult = new GenerateChapterNarrationResult(
                CHAPTER_ID, VOICE_ID, Collections.emptyList()
        );
        when(generateChapterNarrationUseCase.execute(CHAPTER_ID, VOICE_ID))
                .thenReturn(emptyResult);

        worker.runPreparation(new ReaderChapterNarrationPreparationCommand(CHAPTER_ID, VOICE_ID));

        verify(generateChapterNarrationUseCase).execute(CHAPTER_ID, VOICE_ID);
        verify(buildChapterNarrationPlaybackUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("4. Incomplete generation (!isCompleteSuccess) stops before build")
    void incompleteGenerationStopsBeforeBuild() {
        ReaderChapterAccessQueryPort.ReadableChapterReference accessView =
                new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(accessView));

        ChapterNarrationSegmentExecutionResult failedSeg = ChapterNarrationSegmentExecutionResult.failure(
                UUID.randomUUID(), 0, ChapterNarrationGenerationAction.GENERATE, "IllegalStateException", "Failed"
        );
        GenerateChapterNarrationResult incompleteResult = new GenerateChapterNarrationResult(
                CHAPTER_ID, VOICE_ID, List.of(failedSeg)
        );
        when(generateChapterNarrationUseCase.execute(CHAPTER_ID, VOICE_ID))
                .thenReturn(incompleteResult);

        worker.runPreparation(new ReaderChapterNarrationPreparationCommand(CHAPTER_ID, VOICE_ID));

        verify(generateChapterNarrationUseCase).execute(CHAPTER_ID, VOICE_ID);
        verify(buildChapterNarrationPlaybackUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("5. Unpublish occurring during long generation stops before build")
    void unpublishAfterGenerationStopsBeforeBuild() {
        ReaderChapterAccessQueryPort.ReadableChapterReference accessView =
                new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);
        // First check succeeds, second check returns empty (unpublished during generation)
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(accessView))
                .thenReturn(Optional.empty());

        ChapterNarrationSegmentExecutionResult seg1 = ChapterNarrationSegmentExecutionResult.success(
                UUID.randomUUID(), 0, ChapterNarrationGenerationAction.GENERATE, ChapterNarrationGenerationExecutionOutcome.GENERATED
        );
        GenerateChapterNarrationResult successResult = new GenerateChapterNarrationResult(
                CHAPTER_ID, VOICE_ID, List.of(seg1)
        );
        when(generateChapterNarrationUseCase.execute(CHAPTER_ID, VOICE_ID))
                .thenReturn(successResult);

        worker.runPreparation(new ReaderChapterNarrationPreparationCommand(CHAPTER_ID, VOICE_ID));

        verify(generateChapterNarrationUseCase).execute(CHAPTER_ID, VOICE_ID);
        verify(buildChapterNarrationPlaybackUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("6. Worker RuntimeException during execution is caught and isolated without leaking")
    void workerRuntimeExceptionIsSafelyIsolated() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenThrow(new IllegalStateException("Database temporary failure"));

        assertThatCode(() -> worker.runPreparation(new ReaderChapterNarrationPreparationCommand(CHAPTER_ID, VOICE_ID)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("7. Null command logs warning and returns safely")
    void nullCommandReturnsSafely() {
        assertThatCode(() -> worker.runPreparation(null))
                .doesNotThrowAnyException();

        verifyNoInteractions(readerChapterAccessQueryPort);
        verifyNoInteractions(generateChapterNarrationUseCase);
        verifyNoInteractions(buildChapterNarrationPlaybackUseCase);
    }
}
