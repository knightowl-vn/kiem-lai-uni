package com.universe.novel.application.narration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReaderNarrationContinuationWorker Unit Tests (MS-04.9H.7C2C3B)")
class ReaderNarrationContinuationWorkerTest {

    @Mock
    private BuildReaderNarrationContinuationPlanUseCase buildPlanUseCase;
    @Mock
    private RegenerateChapterNarrationAudioUseCase regenerateUseCase;
    @Mock
    private ExecuteReaderNarrationContinuationUseCase executeContinuationUseCase;

    private ReaderNarrationContinuationWorker worker;

    private static final UUID CHAPTER_ID = UUID.randomUUID();
    private static final UUID SEGMENT_ID = UUID.randomUUID();
    private static final UUID VOICE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        worker = new ReaderNarrationContinuationWorker(
                buildPlanUseCase,
                regenerateUseCase,
                executeContinuationUseCase
        );
    }

    private ReaderNarrationContinuationPlan createPlan(boolean hasWork) {
        List<ReaderNarrationContinuationPlanItem> workItems = hasWork
                ? List.of(new ReaderNarrationContinuationPlanItem(
                        UUID.randomUUID(), 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE
                ))
                : List.of();
        return new ReaderNarrationContinuationPlan(CHAPTER_ID, VOICE_ID, SEGMENT_ID, 2, workItems);
    }

    @Test
    @DisplayName("Handles null command safely without exception")
    void handlesNullCommandSafely() {
        assertThatCode(() -> worker.runContinuation(null))
                .doesNotThrowAnyException();

        verify(buildPlanUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("11, 12, 17. Worker builds plan, refreshes requested segment once when flag=true, and executes non-empty plan")
    void workerFlowWithRefreshTrueAndNonEmptyPlan() {
        ReaderNarrationContinuationCommand command = new ReaderNarrationContinuationCommand(
                CHAPTER_ID, SEGMENT_ID, VOICE_ID, true
        );
        ReaderNarrationContinuationPlan plan = createPlan(true);

        when(buildPlanUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID)).thenReturn(plan);

        worker.runContinuation(command);

        // 1. Plan built
        verify(buildPlanUseCase).execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

        // 2. Requested segment regenerated once
        verify(regenerateUseCase, times(1)).execute(SEGMENT_ID, VOICE_ID);

        // 3. Plan executed through C2 orchestrator
        verify(executeContinuationUseCase, times(1)).execute(plan);
    }

    @Test
    @DisplayName("13, 16. refreshRequestedSegment=false skips regeneration, empty plan skips C2 execution")
    void workerFlowWithRefreshFalseAndEmptyPlan() {
        ReaderNarrationContinuationCommand command = new ReaderNarrationContinuationCommand(
                CHAPTER_ID, SEGMENT_ID, VOICE_ID, false
        );
        ReaderNarrationContinuationPlan plan = createPlan(false);

        when(buildPlanUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID)).thenReturn(plan);

        worker.runContinuation(command);

        verify(buildPlanUseCase).execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);
        verify(regenerateUseCase, never()).execute(any(), any());
        verify(executeContinuationUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("15. Requested refresh RuntimeException is isolated and remaining continuation plan still executes")
    void requestedRefreshExceptionIsIsolatedAndPlanExecutionProceeds() {
        ReaderNarrationContinuationCommand command = new ReaderNarrationContinuationCommand(
                CHAPTER_ID, SEGMENT_ID, VOICE_ID, true
        );
        ReaderNarrationContinuationPlan plan = createPlan(true);

        when(buildPlanUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID)).thenReturn(plan);
        doThrow(new RuntimeException("Media storage timeout on refresh"))
                .when(regenerateUseCase).execute(SEGMENT_ID, VOICE_ID);

        assertThatCode(() -> worker.runContinuation(command))
                .doesNotThrowAnyException();

        verify(regenerateUseCase).execute(SEGMENT_ID, VOICE_ID);
        verify(executeContinuationUseCase).execute(plan);
    }

    @Test
    @DisplayName("Build plan exception is isolated safely")
    void buildPlanExceptionIsIsolated() {
        ReaderNarrationContinuationCommand command = new ReaderNarrationContinuationCommand(
                CHAPTER_ID, SEGMENT_ID, VOICE_ID, true
        );

        when(buildPlanUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenThrow(new RuntimeException("Chapter not found or DB down"));

        assertThatCode(() -> worker.runContinuation(command))
                .doesNotThrowAnyException();

        verify(regenerateUseCase, never()).execute(any(), any());
        verify(executeContinuationUseCase, never()).execute(any());
    }
}
