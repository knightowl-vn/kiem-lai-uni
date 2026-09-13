package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.ports.NarrationMediaCleanupTaskRepositoryPort;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.novel.domain.narration.NarrationMediaCleanupTask;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessPendingNarrationMediaCleanupUseCase Unit Tests")
class ProcessPendingNarrationMediaCleanupUseCaseTest {

    @Mock
    private NarrationMediaCleanupTaskRepositoryPort repositoryPort;

    @Mock
    private MediaContract mediaContract;

    @Mock
    private ClockPort clockPort;

    private ProcessPendingNarrationMediaCleanupUseCase useCase;

    private static final UUID TASK_1_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_1_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TASK_2_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ASSET_2_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new ProcessPendingNarrationMediaCleanupUseCase(
                repositoryPort,
                mediaContract,
                clockPort
        );
        lenient().when(clockPort.now()).thenReturn(NOW);
    }

    private NarrationMediaCleanupTask createTask(UUID taskId, UUID assetId) {
        return NarrationMediaCleanupTask.create(
                taskId, assetId, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, NOW.minusSeconds(100)
        );
    }

    private MediaAssetDetailDTO createDetail(UUID assetId, MediaAssetStatusDTO status) {
        return new MediaAssetDetailDTO(
                assetId,
                MediaTypeDTO.AUDIO,
                MediaVisibilityDTO.PUBLIC,
                status,
                1,
                NOW.minusSeconds(200),
                NOW.minusSeconds(200),
                null
        );
    }

    @Test
    @DisplayName("6. Missing Media asset -> task removed without calling delete")
    void shouldRemoveTaskWhenMediaAssetMissing() {
        NarrationMediaCleanupTask task = createTask(TASK_1_ID, ASSET_1_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task));
        when(mediaContract.getAssetDetail(ASSET_1_ID)).thenReturn(Optional.empty());

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.cleaned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(result.executedAt()).isEqualTo(NOW);

        verify(mediaContract, never()).delete(any());
        verify(repositoryPort).deleteById(TASK_1_ID);
    }

    @Test
    @DisplayName("7. Already DELETED -> task removed without delete call")
    void shouldRemoveTaskWhenMediaAssetAlreadyDeleted() {
        NarrationMediaCleanupTask task = createTask(TASK_1_ID, ASSET_1_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task));
        when(mediaContract.getAssetDetail(ASSET_1_ID)).thenReturn(Optional.of(createDetail(ASSET_1_ID, MediaAssetStatusDTO.DELETED)));

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.cleaned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);

        verify(mediaContract, never()).delete(any());
        verify(repositoryPort).deleteById(TASK_1_ID);
    }

    @Test
    @DisplayName("8. ACTIVE -> delete succeeds -> task removed")
    void shouldDeleteMediaAndRemoveTaskWhenActive() {
        NarrationMediaCleanupTask task = createTask(TASK_1_ID, ASSET_1_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task));
        when(mediaContract.getAssetDetail(ASSET_1_ID)).thenReturn(Optional.of(createDetail(ASSET_1_ID, MediaAssetStatusDTO.ACTIVE)));

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.cleaned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);

        verify(mediaContract).delete(ASSET_1_ID);
        verify(repositoryPort).deleteById(TASK_1_ID);
    }

    @Test
    @DisplayName("9. ARCHIVED -> delete succeeds -> task removed")
    void shouldDeleteMediaAndRemoveTaskWhenArchived() {
        NarrationMediaCleanupTask task = createTask(TASK_1_ID, ASSET_1_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task));
        when(mediaContract.getAssetDetail(ASSET_1_ID)).thenReturn(Optional.of(createDetail(ASSET_1_ID, MediaAssetStatusDTO.ARCHIVED)));

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.cleaned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);

        verify(mediaContract).delete(ASSET_1_ID);
        verify(repositoryPort).deleteById(TASK_1_ID);
    }

    @Test
    @DisplayName("10. Delete throws, re-read shows DELETED -> task removed")
    void shouldRemoveTaskWhenDeleteThrowsAndRereadShowsDeleted() {
        NarrationMediaCleanupTask task = createTask(TASK_1_ID, ASSET_1_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task));

        // Initial lookup -> ACTIVE, delete throws, re-read -> DELETED
        when(mediaContract.getAssetDetail(ASSET_1_ID))
                .thenReturn(Optional.of(createDetail(ASSET_1_ID, MediaAssetStatusDTO.ACTIVE))) // initial
                .thenReturn(Optional.of(createDetail(ASSET_1_ID, MediaAssetStatusDTO.DELETED))); // re-read

        doThrow(new RuntimeException("Connection reset")).when(mediaContract).delete(ASSET_1_ID);

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.cleaned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);

        verify(repositoryPort).deleteById(TASK_1_ID);
        verify(repositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("11. Delete throws, re-read missing -> task removed")
    void shouldRemoveTaskWhenDeleteThrowsAndRereadShowsMissing() {
        NarrationMediaCleanupTask task = createTask(TASK_1_ID, ASSET_1_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task));

        // Initial lookup -> ACTIVE, delete throws, re-read -> missing
        when(mediaContract.getAssetDetail(ASSET_1_ID))
                .thenReturn(Optional.of(createDetail(ASSET_1_ID, MediaAssetStatusDTO.ACTIVE))) // initial
                .thenReturn(Optional.empty()); // re-read

        doThrow(new RuntimeException("Connection timeout")).when(mediaContract).delete(ASSET_1_ID);

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.cleaned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);

        verify(repositoryPort).deleteById(TASK_1_ID);
        verify(repositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("12. Delete throws, re-read ACTIVE/ARCHIVED -> one failed attempt recorded, task retained")
    void shouldRecordFailedAttemptAndRetainTaskWhenDeleteThrowsAndRereadStillActive() {
        NarrationMediaCleanupTask task = createTask(TASK_1_ID, ASSET_1_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task));

        // Initial lookup -> ACTIVE, delete throws, re-read -> still ACTIVE
        when(mediaContract.getAssetDetail(ASSET_1_ID))
                .thenReturn(Optional.of(createDetail(ASSET_1_ID, MediaAssetStatusDTO.ACTIVE))) // initial
                .thenReturn(Optional.of(createDetail(ASSET_1_ID, MediaAssetStatusDTO.ACTIVE))); // re-read

        doThrow(new RuntimeException("Permission denied")).when(mediaContract).delete(ASSET_1_ID);

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.cleaned()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(1);

        verify(repositoryPort, never()).deleteById(TASK_1_ID);

        ArgumentCaptor<NarrationMediaCleanupTask> captor = ArgumentCaptor.forClass(NarrationMediaCleanupTask.class);
        verify(repositoryPort).save(captor.capture());
        NarrationMediaCleanupTask saved = captor.getValue();
        assertThat(saved.getAttemptCount()).isEqualTo(1);
        assertThat(saved.getLastErrorType()).isEqualTo("RuntimeException");
        assertThat(saved.getLastAttemptAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("13. Initial Media lookup failure -> one failed attempt recorded, task retained")
    void shouldRecordFailedAttemptWhenInitialLookupFails() {
        NarrationMediaCleanupTask task = createTask(TASK_1_ID, ASSET_1_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task));

        when(mediaContract.getAssetDetail(ASSET_1_ID)).thenThrow(new IllegalStateException("Media DB offline"));

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.cleaned()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(1);

        verify(mediaContract, never()).delete(any());
        verify(repositoryPort, never()).deleteById(any());

        ArgumentCaptor<NarrationMediaCleanupTask> captor = ArgumentCaptor.forClass(NarrationMediaCleanupTask.class);
        verify(repositoryPort).save(captor.capture());
        NarrationMediaCleanupTask saved = captor.getValue();
        assertThat(saved.getAttemptCount()).isEqualTo(1);
        assertThat(saved.getLastErrorType()).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("14. Failed task does not stop following task")
    void shouldContinueProcessingSubsequentTasksWhenOneFails() {
        NarrationMediaCleanupTask task1 = createTask(TASK_1_ID, ASSET_1_ID);
        NarrationMediaCleanupTask task2 = createTask(TASK_2_ID, ASSET_2_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task1, task2));

        // Task 1 fails on initial lookup
        when(mediaContract.getAssetDetail(ASSET_1_ID)).thenThrow(new RuntimeException("Lookup error"));

        // Task 2 succeeds on ACTIVE delete
        when(mediaContract.getAssetDetail(ASSET_2_ID)).thenReturn(Optional.of(createDetail(ASSET_2_ID, MediaAssetStatusDTO.ACTIVE)));

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.cleaned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);

        // Task 1 recorded failure
        verify(repositoryPort).save(task1);
        verify(repositoryPort, never()).deleteById(TASK_1_ID);

        // Task 2 deleted successfully
        verify(mediaContract).delete(ASSET_2_ID);
        verify(repositoryPort).deleteById(TASK_2_ID);
    }

    @Test
    @DisplayName("15. Requested batch limit is honored")
    void shouldHonorRequestedBatchLimit() {
        when(repositoryPort.findOldest(10)).thenReturn(List.of());

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute(10);

        assertThat(result.candidates()).isEqualTo(0);
        assertThat(result.cleaned()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);

        verify(repositoryPort).findOldest(10);
    }

    @Test
    @DisplayName("16. Result counters are correct and reject non-positive limit")
    void shouldRejectNonPositiveLimit() {
        assertThatThrownBy(() -> useCase.execute(0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(-5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("17. Cleanup-task delete failure does not abort following task")
    void shouldNotAbortFollowingTaskWhenCleanupTaskDeleteFails() {
        NarrationMediaCleanupTask task1 = createTask(TASK_1_ID, ASSET_1_ID);
        NarrationMediaCleanupTask task2 = createTask(TASK_2_ID, ASSET_2_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task1, task2));

        // Task 1: Media already DELETED, but repository deleteById throws
        when(mediaContract.getAssetDetail(ASSET_1_ID)).thenReturn(Optional.of(createDetail(ASSET_1_ID, MediaAssetStatusDTO.DELETED)));
        doThrow(new RuntimeException("DB Deadlock")).when(repositoryPort).deleteById(TASK_1_ID);

        // Task 2: Media ACTIVE, delete succeeds, repository deleteById succeeds
        when(mediaContract.getAssetDetail(ASSET_2_ID)).thenReturn(Optional.of(createDetail(ASSET_2_ID, MediaAssetStatusDTO.ACTIVE)));

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.cleaned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.cleaned() + result.failed()).isEqualTo(result.candidates());

        // Task 1 recorded diagnostic
        verify(repositoryPort).save(task1);
        assertThat(task1.getAttemptCount()).isEqualTo(1);
        assertThat(task1.getLastErrorType()).isEqualTo("RuntimeException");

        // Task 2 deleted successfully
        verify(mediaContract).delete(ASSET_2_ID);
        verify(repositoryPort).deleteById(TASK_2_ID);
    }

    @Test
    @DisplayName("18. Media delete succeeds but cleanup-task delete fails -> diagnostic recorded, attemptCount=1, task counted failed")
    void shouldCountFailedWhenMediaDeleteSucceedsButTaskDeleteFails() {
        NarrationMediaCleanupTask task = createTask(TASK_1_ID, ASSET_1_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task));

        when(mediaContract.getAssetDetail(ASSET_1_ID)).thenReturn(Optional.of(createDetail(ASSET_1_ID, MediaAssetStatusDTO.ACTIVE)));
        doThrow(new RuntimeException("Lock acquisition timeout")).when(repositoryPort).deleteById(TASK_1_ID);

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.cleaned()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.cleaned() + result.failed()).isEqualTo(result.candidates());

        // Media was deleted
        verify(mediaContract).delete(ASSET_1_ID);

        // Exactly one diagnostic save attempted on the task
        ArgumentCaptor<NarrationMediaCleanupTask> captor = ArgumentCaptor.forClass(NarrationMediaCleanupTask.class);
        verify(repositoryPort, times(1)).save(captor.capture());
        NarrationMediaCleanupTask saved = captor.getValue();
        assertThat(saved.getAttemptCount()).isEqualTo(1);
        assertThat(saved.getLastErrorType()).isEqualTo("RuntimeException");
        assertThat(saved.getLastAttemptAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("19. Null Media status -> task retained and failed closed")
    void shouldRetainTaskAndFailWhenMediaStatusIsNull() {
        NarrationMediaCleanupTask task = createTask(TASK_1_ID, ASSET_1_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task));

        // Return detail with null status
        when(mediaContract.getAssetDetail(ASSET_1_ID)).thenReturn(Optional.of(createDetail(ASSET_1_ID, null)));

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.cleaned()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.cleaned() + result.failed()).isEqualTo(result.candidates());

        verify(mediaContract, never()).delete(any());
        verify(repositoryPort, never()).deleteById(any());

        ArgumentCaptor<NarrationMediaCleanupTask> captor = ArgumentCaptor.forClass(NarrationMediaCleanupTask.class);
        verify(repositoryPort).save(captor.capture());
        NarrationMediaCleanupTask saved = captor.getValue();
        assertThat(saved.getAttemptCount()).isEqualTo(1);
        assertThat(saved.getLastErrorType()).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("20. Diagnostic repository save failure does not abort next task")
    void shouldNotAbortFollowingTaskWhenDiagnosticSaveFails() {
        NarrationMediaCleanupTask task1 = createTask(TASK_1_ID, ASSET_1_ID);
        NarrationMediaCleanupTask task2 = createTask(TASK_2_ID, ASSET_2_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task1, task2));

        // Task 1: initial lookup fails, and saving failure diagnostic also throws
        when(mediaContract.getAssetDetail(ASSET_1_ID)).thenThrow(new RuntimeException("Lookup timeout"));
        doThrow(new RuntimeException("DB Connection lost")).when(repositoryPort).save(task1);

        // Task 2: Media ACTIVE, delete succeeds, task deleted
        when(mediaContract.getAssetDetail(ASSET_2_ID)).thenReturn(Optional.of(createDetail(ASSET_2_ID, MediaAssetStatusDTO.ACTIVE)));

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.cleaned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.cleaned() + result.failed()).isEqualTo(result.candidates());

        verify(mediaContract).delete(ASSET_2_ID);
        verify(repositoryPort).deleteById(TASK_2_ID);
    }

    @Test
    @DisplayName("21. Unexpected candidate exception -> diagnostic recorded and following candidate executes")
    void shouldAttemptDiagnosticRecordingAndContinueWhenOuterCandidateThrowsUnexpectedException() {
        NarrationMediaCleanupTask task1 = org.mockito.Mockito.spy(createTask(TASK_1_ID, ASSET_1_ID));
        NarrationMediaCleanupTask task2 = createTask(TASK_2_ID, ASSET_2_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task1, task2));

        // Task 1 throws unexpected exception from getMediaAssetId() escaping processSingleTask
        doThrow(new RuntimeException("Unexpected domain state corruption")).when(task1).getMediaAssetId();

        // Task 2: Media ACTIVE, delete succeeds, task deleted
        when(mediaContract.getAssetDetail(ASSET_2_ID)).thenReturn(Optional.of(createDetail(ASSET_2_ID, MediaAssetStatusDTO.ACTIVE)));

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.cleaned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.cleaned() + result.failed()).isEqualTo(result.candidates());

        // Task 1 had diagnostic recorded and saved
        verify(repositoryPort).save(task1);
        assertThat(task1.getAttemptCount()).isEqualTo(1);
        assertThat(task1.getLastErrorType()).isEqualTo("RuntimeException");
        assertThat(task1.getLastAttemptAt()).isEqualTo(NOW);

        // Task 2 executed and was deleted
        verify(mediaContract).delete(ASSET_2_ID);
        verify(repositoryPort).deleteById(TASK_2_ID);
    }

    @Test
    @DisplayName("22. Missing media asset when task delete fails -> diagnostic saved, attemptCount=1, counted failed")
    void shouldPersistDiagnosticWhenTaskDeleteFailsForMissingAsset() {
        NarrationMediaCleanupTask task = createTask(TASK_1_ID, ASSET_1_ID);
        when(repositoryPort.findOldest(50)).thenReturn(List.of(task));

        when(mediaContract.getAssetDetail(ASSET_1_ID)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("Database timeout on delete")).when(repositoryPort).deleteById(TASK_1_ID);

        ProcessPendingNarrationMediaCleanupResult result = useCase.execute();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.cleaned()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.cleaned() + result.failed()).isEqualTo(result.candidates());

        ArgumentCaptor<NarrationMediaCleanupTask> captor = ArgumentCaptor.forClass(NarrationMediaCleanupTask.class);
        verify(repositoryPort, times(1)).save(captor.capture());
        NarrationMediaCleanupTask saved = captor.getValue();
        assertThat(saved.getAttemptCount()).isEqualTo(1);
        assertThat(saved.getLastErrorType()).isEqualTo("RuntimeException");
        assertThat(saved.getLastAttemptAt()).isEqualTo(NOW);
    }
}
