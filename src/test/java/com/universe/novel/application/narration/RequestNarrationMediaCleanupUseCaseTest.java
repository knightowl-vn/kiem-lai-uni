package com.universe.novel.application.narration;

import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.exceptions.NarrationMediaCleanupRequestException;
import com.universe.novel.application.ports.NarrationMediaCleanupTaskRepositoryPort;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.novel.domain.narration.NarrationMediaCleanupTask;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RequestNarrationMediaCleanupUseCase Unit Tests")
class RequestNarrationMediaCleanupUseCaseTest {

    @Mock
    private EnqueueNarrationMediaCleanupUseCase enqueueUseCase;

    @Mock
    private MediaContract mediaContract;

    @Mock
    private NarrationMediaCleanupTaskRepositoryPort repositoryPort;

    @Mock
    private ClockPort clockPort;

    private RequestNarrationMediaCleanupUseCase useCase;

    private static final UUID TASK_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new RequestNarrationMediaCleanupUseCase(
                enqueueUseCase,
                mediaContract,
                repositoryPort,
                clockPort
        );
        lenient().when(clockPort.now()).thenReturn(NOW);
    }

    private NarrationMediaCleanupTask createTask(UUID taskId, UUID assetId, NarrationMediaCleanupReason reason) {
        return NarrationMediaCleanupTask.create(taskId, assetId, reason, NOW.minusSeconds(60));
    }

    @Test
    @DisplayName("1. Enqueue succeeds -> Media delete succeeds -> cleanup intent removal attempted and returns IMMEDIATELY_DELETED")
    void shouldDeleteImmediatelyAndRemoveTaskWhenEnqueueAndMediaDeleteSucceed() {
        NarrationMediaCleanupTask task = createTask(TASK_ID, ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        when(enqueueUseCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET)).thenReturn(task);

        RequestNarrationMediaCleanupResult result = useCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThat(result.mediaAssetId()).isEqualTo(ASSET_ID);
        assertThat(result.outcome()).isEqualTo(NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED);

        // Verify locked ordering with Mockito InOrder:
        // EnqueueNarrationMediaCleanupUseCase.execute(...) -> MediaContract.delete(...) -> repositoryPort.deleteById(...)
        InOrder inOrder = inOrder(enqueueUseCase, mediaContract, repositoryPort);
        inOrder.verify(enqueueUseCase).execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        inOrder.verify(mediaContract).delete(ASSET_ID);
        inOrder.verify(repositoryPort).deleteById(TASK_ID);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("2. Cleanup-intent removal fails after Media delete success -> request still succeeds and Media delete is not repeated")
    void shouldSucceedWhenCleanupIntentRemovalFailsAfterMediaDeleteSuccess() {
        NarrationMediaCleanupTask task = createTask(TASK_ID, ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        when(enqueueUseCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET)).thenReturn(task);
        doThrow(new RuntimeException("DB deadlock on task removal")).when(repositoryPort).deleteById(TASK_ID);

        RequestNarrationMediaCleanupResult result = useCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThat(result.mediaAssetId()).isEqualTo(ASSET_ID);
        assertThat(result.outcome()).isEqualTo(NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED);

        verify(mediaContract, times(1)).delete(ASSET_ID);
        verify(repositoryPort).deleteById(TASK_ID);
    }

    @Test
    @DisplayName("3. Enqueue succeeds -> Media delete fails -> task retained and returns ENQUEUED_FOR_RETRY")
    void shouldRetainTaskWhenEnqueueSucceedsAndMediaDeleteFails() {
        NarrationMediaCleanupTask task = createTask(TASK_ID, ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        when(enqueueUseCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET)).thenReturn(task);
        doThrow(new RuntimeException("Media server unavailable")).when(mediaContract).delete(ASSET_ID);

        RequestNarrationMediaCleanupResult result = useCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThat(result.mediaAssetId()).isEqualTo(ASSET_ID);
        assertThat(result.outcome()).isEqualTo(NarrationMediaCleanupOutcome.ENQUEUED_FOR_RETRY);

        // Task must NOT be deleted
        verify(repositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("4. Media delete failure records exactly one safe failed attempt on task")
    void shouldRecordSafeFailedAttemptWhenMediaDeleteFails() {
        NarrationMediaCleanupTask task = createTask(TASK_ID, ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        when(enqueueUseCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET)).thenReturn(task);
        doThrow(new IllegalStateException("Connection refused by storage provider")).when(mediaContract).delete(ASSET_ID);

        RequestNarrationMediaCleanupResult result = useCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThat(result.outcome()).isEqualTo(NarrationMediaCleanupOutcome.ENQUEUED_FOR_RETRY);

        ArgumentCaptor<NarrationMediaCleanupTask> captor = ArgumentCaptor.forClass(NarrationMediaCleanupTask.class);
        verify(repositoryPort, times(1)).save(captor.capture());
        NarrationMediaCleanupTask saved = captor.getValue();
        assertThat(saved.getAttemptCount()).isEqualTo(1);
        assertThat(saved.getLastErrorType()).isEqualTo("IllegalStateException");
        assertThat(saved.getLastAttemptAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("5. Diagnostic save failure after Media delete failure is non-fatal because durable intent already exists")
    void shouldRemainNonFatalWhenDiagnosticSaveFailsAfterMediaDeleteFailure() {
        NarrationMediaCleanupTask task = createTask(TASK_ID, ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        when(enqueueUseCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET)).thenReturn(task);
        doThrow(new RuntimeException("Media service down")).when(mediaContract).delete(ASSET_ID);
        doThrow(new RuntimeException("Database error saving diagnostic")).when(repositoryPort).save(any());

        RequestNarrationMediaCleanupResult result = useCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThat(result.mediaAssetId()).isEqualTo(ASSET_ID);
        assertThat(result.outcome()).isEqualTo(NarrationMediaCleanupOutcome.ENQUEUED_FOR_RETRY);

        verify(repositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("6. Enqueue fails -> Media delete succeeds -> request succeeds with IMMEDIATELY_DELETED")
    void shouldSucceedWhenEnqueueFailsAndMediaDeleteSucceeds() {
        when(enqueueUseCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenThrow(new RuntimeException("Database connection timeout on enqueue"));

        RequestNarrationMediaCleanupResult result = useCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThat(result.mediaAssetId()).isEqualTo(ASSET_ID);
        assertThat(result.outcome()).isEqualTo(NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED);

        verify(mediaContract).delete(ASSET_ID);
        verify(repositoryPort, never()).deleteById(any());
        verify(repositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("7. Enqueue fails -> Media delete also fails -> explicit NarrationMediaCleanupRequestException with causes preserved")
    void shouldPropagateExplicitExceptionWhenBothEnqueueAndMediaDeleteFail() {
        RuntimeException enqueueEx = new RuntimeException("Enqueue DB dead");
        RuntimeException deleteEx = new RuntimeException("Media 503 unavailable");

        when(enqueueUseCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenThrow(enqueueEx);
        doThrow(deleteEx).when(mediaContract).delete(ASSET_ID);

        assertThatThrownBy(() -> useCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .isInstanceOf(NarrationMediaCleanupRequestException.class)
                .hasCause(deleteEx)
                .satisfies(thrown -> {
                    assertThat(thrown.getSuppressed()).contains(enqueueEx);
                });
    }

    @Test
    @DisplayName("8. Pre-existing cleanup task remains idempotent and state is not reset")
    void shouldPreservePreExistingTaskState() {
        // Task already had 2 previous attempts
        NarrationMediaCleanupTask existingTask = NarrationMediaCleanupTask.rehydrate(
                TASK_ID,
                ASSET_ID,
                NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET,
                2,
                "OldError",
                NOW.minusSeconds(100),
                NOW.minusSeconds(50),
                NOW.minusSeconds(50)
        );

        when(enqueueUseCase.execute(ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET)).thenReturn(existingTask);
        doThrow(new RuntimeException("Media error")).when(mediaContract).delete(ASSET_ID);

        RequestNarrationMediaCleanupResult result = useCase.execute(ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);

        assertThat(result.outcome()).isEqualTo(NarrationMediaCleanupOutcome.ENQUEUED_FOR_RETRY);

        ArgumentCaptor<NarrationMediaCleanupTask> captor = ArgumentCaptor.forClass(NarrationMediaCleanupTask.class);
        verify(repositoryPort).save(captor.capture());
        NarrationMediaCleanupTask saved = captor.getValue();
        assertThat(saved.getAttemptCount()).isEqualTo(3);
        assertThat(saved.getReason()).isEqualTo(NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        assertThat(saved.getLastErrorType()).isEqualTo("RuntimeException");
    }

    @Test
    @DisplayName("9. Rejects null command, null assetId, and null reason with IllegalArgumentException")
    void shouldValidateNullInputs() {
        assertThatThrownBy(() -> useCase.execute((RequestNarrationMediaCleanupCommand) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command must not be null");

        assertThatThrownBy(() -> useCase.execute((UUID) null, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mediaAssetId must not be null");

        assertThatThrownBy(() -> useCase.execute(ASSET_ID, (NarrationMediaCleanupReason) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason must not be null");
    }

    @Test
    @DisplayName("10. Command overload delegates correctly to execute(UUID, Reason)")
    void shouldDelegateFromCommandOverload() {
        NarrationMediaCleanupTask task = createTask(TASK_ID, ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        when(enqueueUseCase.execute(ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET)).thenReturn(task);

        RequestNarrationMediaCleanupCommand command = new RequestNarrationMediaCleanupCommand(
                ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET
        );
        RequestNarrationMediaCleanupResult result = useCase.execute(command);

        assertThat(result.mediaAssetId()).isEqualTo(ASSET_ID);
        assertThat(result.outcome()).isEqualTo(NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED);
        verify(mediaContract).delete(ASSET_ID);
    }

    @Test
    @DisplayName("11. Superseded chapter playback reason passes through the existing durable retry path")
    void shouldPassSupersededChapterPlaybackReasonThroughDurableCleanupPath() {
        NarrationMediaCleanupReason reason = NarrationMediaCleanupReason.SUPERSEDED_CHAPTER_PLAYBACK_ASSET;
        NarrationMediaCleanupTask task = createTask(TASK_ID, ASSET_ID, reason);
        when(enqueueUseCase.execute(ASSET_ID, reason)).thenReturn(task);
        doThrow(new RuntimeException("Media temporarily unavailable")).when(mediaContract).delete(ASSET_ID);

        RequestNarrationMediaCleanupResult result = useCase.execute(ASSET_ID, reason);

        assertThat(result.mediaAssetId()).isEqualTo(ASSET_ID);
        assertThat(result.outcome()).isEqualTo(NarrationMediaCleanupOutcome.ENQUEUED_FOR_RETRY);
        verify(enqueueUseCase).execute(ASSET_ID, reason);
        ArgumentCaptor<NarrationMediaCleanupTask> captor = ArgumentCaptor.forClass(NarrationMediaCleanupTask.class);
        verify(repositoryPort).save(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo(reason);
        assertThat(captor.getValue().getAttemptCount()).isEqualTo(1);
    }
}
