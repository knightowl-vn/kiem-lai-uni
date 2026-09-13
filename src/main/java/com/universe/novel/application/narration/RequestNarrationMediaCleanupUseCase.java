package com.universe.novel.application.narration;

import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.exceptions.NarrationMediaCleanupRequestException;
import com.universe.novel.application.ports.NarrationMediaCleanupTaskRepositoryPort;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.novel.domain.narration.NarrationMediaCleanupTask;
import com.universe.shared.time.ClockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Reusable application use case for requesting cleanup of a Media asset that is already known
 * to be no longer referenced/required by Narration (MS-04.9H.8D2A).
 * <p>
 * <strong>Execution Flow:</strong>
 * <ol>
 *     <li><strong>Establish Durable Intent:</strong> Enqueues the cleanup intent via {@link EnqueueNarrationMediaCleanupUseCase}.</li>
 *     <li><strong>Immediate Deletion:</strong> Attempts immediate logical deletion via {@link MediaContract#delete(UUID)}.</li>
 *     <li><strong>On Immediate Delete Success:</strong> Best-effort removes the pending cleanup task from repository storage.</li>
 *     <li><strong>On Immediate Delete Failure:</strong> Retains the durable cleanup task, records the failed attempt diagnostic, and returns {@link NarrationMediaCleanupOutcome#ENQUEUED_FOR_RETRY} for asynchronous worker processing.</li>
 *     <li><strong>On Enqueue Failure:</strong> Still attempts immediate Media deletion. If delete succeeds, cleanup succeeds; if delete also fails, throws {@link NarrationMediaCleanupRequestException} preserving both failures.</li>
 * </ol>
 * <p>
 * <strong>Transaction Boundary:</strong> Non-transactional. External MediaContract calls occur strictly
 * outside database transactions. Short repository operations execute independently.
 */
@Service
public class RequestNarrationMediaCleanupUseCase {

    private static final Logger log = LoggerFactory.getLogger(RequestNarrationMediaCleanupUseCase.class);

    private final EnqueueNarrationMediaCleanupUseCase enqueueUseCase;
    private final MediaContract mediaContract;
    private final NarrationMediaCleanupTaskRepositoryPort repositoryPort;
    private final ClockPort clockPort;

    public RequestNarrationMediaCleanupUseCase(
            EnqueueNarrationMediaCleanupUseCase enqueueUseCase,
            MediaContract mediaContract,
            NarrationMediaCleanupTaskRepositoryPort repositoryPort,
            ClockPort clockPort
    ) {
        this.enqueueUseCase = Objects.requireNonNull(enqueueUseCase, "enqueueUseCase must not be null");
        this.mediaContract = Objects.requireNonNull(mediaContract, "mediaContract must not be null");
        this.repositoryPort = Objects.requireNonNull(repositoryPort, "repositoryPort must not be null");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort must not be null");
    }

    public RequestNarrationMediaCleanupResult execute(RequestNarrationMediaCleanupCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return execute(command.mediaAssetId(), command.reason());
    }

    public RequestNarrationMediaCleanupResult execute(UUID mediaAssetId, NarrationMediaCleanupReason reason) {
        if (mediaAssetId == null) {
            throw new IllegalArgumentException("mediaAssetId must not be null");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }

        // 1. Establish durable cleanup intent first
        NarrationMediaCleanupTask task = null;
        Throwable enqueueEx = null;
        try {
            task = enqueueUseCase.execute(mediaAssetId, reason);
        } catch (Exception ex) {
            enqueueEx = ex;
            log.warn("Failed to enqueue durable cleanup intent for media asset [{}]: {}",
                    mediaAssetId, ex.getMessage(), ex);
        }

        if (task != null) {
            // 2A. Enqueue succeeded -> attempt immediate logical deletion
            try {
                mediaContract.delete(mediaAssetId);
                // Immediate delete succeeded -> best-effort remove the pending cleanup intent
                deleteTaskBestEffort(task);
                return new RequestNarrationMediaCleanupResult(mediaAssetId, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED);
            } catch (Exception deleteEx) {
                log.warn("Immediate Media deletion failed for asset [{}]: {}. Retaining durable task [{}] for async retry.",
                        mediaAssetId, deleteEx.getMessage(), task.getId());
                recordFailureSafely(task, deleteEx);
                return new RequestNarrationMediaCleanupResult(mediaAssetId, NarrationMediaCleanupOutcome.ENQUEUED_FOR_RETRY);
            }
        } else {
            // 2B. Enqueue failed -> attempt immediate logical deletion as best effort
            try {
                mediaContract.delete(mediaAssetId);
                log.info("Immediate Media deletion succeeded for asset [{}] despite enqueue failure.", mediaAssetId);
                return new RequestNarrationMediaCleanupResult(mediaAssetId, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED);
            } catch (Exception deleteEx) {
                log.error("Both durable enqueue and immediate Media deletion failed for asset [{}].", mediaAssetId);
                NarrationMediaCleanupRequestException requestEx = new NarrationMediaCleanupRequestException(
                        mediaAssetId,
                        "Failed both to enqueue durable cleanup intent and to immediately delete Media asset '" + mediaAssetId + "'.",
                        deleteEx
                );
                requestEx.addSuppressed(enqueueEx);
                throw requestEx;
            }
        }
    }

    private void deleteTaskBestEffort(NarrationMediaCleanupTask task) {
        if (task == null) {
            return;
        }
        try {
            repositoryPort.deleteById(task.getId());
        } catch (Exception ex) {
            log.warn("Failed to remove completed cleanup task [{}] for media asset [{}]: {}",
                    task.getId(), task.getMediaAssetId(), ex.getMessage());
        }
    }

    private void recordFailureSafely(NarrationMediaCleanupTask task, Throwable error) {
        if (task == null) {
            return;
        }
        try {
            Instant now = clockPort.now();
            task.recordFailedAttempt(error, now);
            repositoryPort.save(task);
        } catch (Exception saveEx) {
            log.warn("Failed to persist failure diagnostic on cleanup task [{}]: {}",
                    task.getId(), saveEx.getMessage());
        }
    }
}
