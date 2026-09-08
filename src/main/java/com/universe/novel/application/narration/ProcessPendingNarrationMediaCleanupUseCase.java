package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.ports.NarrationMediaCleanupTaskRepositoryPort;
import com.universe.novel.domain.narration.NarrationMediaCleanupTask;
import com.universe.shared.time.ClockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Non-transactional batch orchestrator processing pending narration media cleanup tasks (MS-04.9H.8D1B).
 * <p>
 * <strong>State Matrix:</strong>
 * <ul>
 *     <li>Media asset does not exist (empty) &rarr; cleanup already complete &rarr; delete task.</li>
 *     <li>Media asset status == {@code DELETED} &rarr; cleanup already complete &rarr; delete task without calling delete.</li>
 *     <li>Media asset status == {@code ACTIVE} or {@code ARCHIVED} &rarr; call {@code mediaContract.delete(mediaAssetId)}:
 *         <ul>
 *             <li>If delete succeeds &rarr; delete task.</li>
 *             <li>If delete fails &rarr; re-read Media state once. If now deleted/missing &rarr; delete task; else record failure diagnostic and retain task.</li>
 *         </ul>
 *     </li>
 *     <li>Initial Media inspection fails &rarr; record failure diagnostic and retain task.</li>
 * </ul>
 * <p>
 * <strong>Transaction Boundary:</strong> This service is non-transactional. MediaContract calls occur strictly
 * outside database transactions. Individual repository operations execute within short transactions.
 */
@Service
public class ProcessPendingNarrationMediaCleanupUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessPendingNarrationMediaCleanupUseCase.class);
    public static final int DEFAULT_BATCH_SIZE = 50;

    private final NarrationMediaCleanupTaskRepositoryPort repositoryPort;
    private final MediaContract mediaContract;
    private final ClockPort clockPort;

    public ProcessPendingNarrationMediaCleanupUseCase(
            NarrationMediaCleanupTaskRepositoryPort repositoryPort,
            MediaContract mediaContract,
            ClockPort clockPort
    ) {
        this.repositoryPort = Objects.requireNonNull(repositoryPort, "repositoryPort must not be null");
        this.mediaContract = Objects.requireNonNull(mediaContract, "mediaContract must not be null");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort must not be null");
    }

    /**
     * Processes a batch of pending media cleanup tasks using the default batch limit (50).
     */
    public ProcessPendingNarrationMediaCleanupResult execute() {
        return execute(DEFAULT_BATCH_SIZE);
    }

    /**
     * Processes a batch of pending media cleanup tasks bounded by {@code limit}.
     *
     * @param limit maximum number of oldest tasks to process (must be &gt; 0)
     * @return batch execution result counters
     */
    public ProcessPendingNarrationMediaCleanupResult execute(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Batch limit must be greater than 0: " + limit);
        }

        List<NarrationMediaCleanupTask> tasks = repositoryPort.findOldest(limit);
        int candidateCount = tasks.size();
        int cleanedCount = 0;
        int failedCount = 0;

        for (NarrationMediaCleanupTask task : tasks) {
            boolean success = false;
            try {
                success = processSingleTask(task);
            } catch (Exception candidateEx) {
                log.warn("Unexpected runtime exception processing candidate cleanup task [{}]: {}",
                        task.getId(), candidateEx.getMessage(), candidateEx);
                recordFailureSafely(task, candidateEx);
                success = false;
            }

            if (success) {
                cleanedCount++;
            } else {
                failedCount++;
            }
        }

        Instant executedAt = clockPort.now();
        return new ProcessPendingNarrationMediaCleanupResult(
                candidateCount,
                cleanedCount,
                failedCount,
                executedAt
        );
    }

    private boolean processSingleTask(NarrationMediaCleanupTask task) {
        UUID mediaAssetId = task.getMediaAssetId();

        // 1. Initial lookup of Media asset state
        Optional<MediaAssetDetailDTO> detailOpt;
        try {
            detailOpt = mediaContract.getAssetDetail(mediaAssetId);
        } catch (Exception lookupEx) {
            log.warn("Initial Media detail lookup failed for asset [{}]: {}", mediaAssetId, lookupEx.getMessage());
            recordFailureSafely(task, lookupEx);
            return false;
        }

        // 2. State Decision Matrix
        if (detailOpt.isEmpty()) {
            // Asset does not exist -> already complete
            return deleteTask(task);
        }

        MediaAssetDetailDTO detail = detailOpt.get();
        MediaAssetStatusDTO status = detail.status();

        if (status == null) {
            log.warn("Media asset [{}] detail returned null status. Failing closed.", mediaAssetId);
            recordFailureSafely(task, new IllegalStateException("Media asset status is null for asset " + mediaAssetId));
            return false;
        }

        if (status == MediaAssetStatusDTO.DELETED) {
            // Asset already terminal DELETED -> already complete
            return deleteTask(task);
        }

        if (status == MediaAssetStatusDTO.ACTIVE || status == MediaAssetStatusDTO.ARCHIVED) {
            // 3. Attempt Media logical deletion
            try {
                mediaContract.delete(mediaAssetId);
                return deleteTask(task);
            } catch (Exception deleteEx) {
                log.warn("Media deletion call threw exception for asset [{}]: {}. Re-reading Media state.",
                        mediaAssetId, deleteEx.getMessage());

                // 4. Ambiguous delete failure: Re-read Media state ONCE
                Optional<MediaAssetDetailDTO> rereadOpt = Optional.empty();
                boolean rereadSucceeded = false;
                try {
                    rereadOpt = mediaContract.getAssetDetail(mediaAssetId);
                    rereadSucceeded = true;
                } catch (Exception rereadEx) {
                    deleteEx.addSuppressed(rereadEx);
                }

                if (rereadSucceeded && (rereadOpt.isEmpty() || rereadOpt.get().status() == MediaAssetStatusDTO.DELETED)) {
                    // Re-read confirms asset is gone or deleted -> cleanup successful
                    return deleteTask(task);
                } else {
                    // Re-read still ACTIVE/ARCHIVED or re-read itself failed
                    recordFailureSafely(task, deleteEx);
                    return false;
                }
            }
        }

        // Fail closed for any unknown status
        log.warn("Unknown Media status [{}] for asset [{}]. Failing closed.", status, mediaAssetId);
        recordFailureSafely(task, new IllegalStateException("Unknown Media asset status: " + status));
        return false;
    }

    private boolean deleteTask(NarrationMediaCleanupTask task) {
        try {
            repositoryPort.deleteById(task.getId());
            return true;
        } catch (Exception ex) {
            log.warn("Failed to delete cleanup task [{}] for media asset [{}]: {}",
                    task.getId(), task.getMediaAssetId(), ex.getMessage(), ex);
            recordFailureSafely(task, ex);
            return false;
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
            log.warn("Failed to persist failure diagnostics for cleanup task [{}]: {}",
                    task.getId(), saveEx.getMessage(), saveEx);
        }
    }
}
