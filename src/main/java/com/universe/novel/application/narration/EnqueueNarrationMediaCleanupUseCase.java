package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.NarrationMediaCleanupTaskAlreadyExistsException;
import com.universe.novel.application.ports.NarrationMediaCleanupTaskRepositoryPort;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.novel.domain.narration.NarrationMediaCleanupTask;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotent application use case for enqueuing obsolete or unreferenced Media assets
 * for subsequent asynchronous cleanup (MS-04.9H.8D1B).
 * <p>
 * <strong>Workflow:</strong>
 * <ol>
 *     <li>Checks if a cleanup task already exists for {@code mediaAssetId}. If present, returns it immediately without mutating state.</li>
 *     <li>If no task exists, creates a new {@link NarrationMediaCleanupTask} and saves it.</li>
 *     <li>If a concurrent insert race causes {@link NarrationMediaCleanupTaskAlreadyExistsException}, reloads the existing task by {@code mediaAssetId} and returns it.</li>
 * </ol>
 * <p>
 * <strong>Transaction Boundary:</strong> This service is non-transactional to avoid holding database transactions
 * across the race reload path.
 */
@Service
public class EnqueueNarrationMediaCleanupUseCase {

    private final NarrationMediaCleanupTaskRepositoryPort repositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public EnqueueNarrationMediaCleanupUseCase(
            NarrationMediaCleanupTaskRepositoryPort repositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.repositoryPort = Objects.requireNonNull(repositoryPort, "repositoryPort must not be null");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "idGeneratorPort must not be null");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort must not be null");
    }

    public NarrationMediaCleanupTask execute(EnqueueNarrationMediaCleanupCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return execute(command.mediaAssetId(), command.reason());
    }

    public NarrationMediaCleanupTask execute(UUID mediaAssetId, NarrationMediaCleanupReason reason) {
        if (mediaAssetId == null) {
            throw new IllegalArgumentException("mediaAssetId must not be null");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }

        // 1. Idempotent check for existing task
        Optional<NarrationMediaCleanupTask> existingOpt = repositoryPort.findByMediaAssetId(mediaAssetId);
        if (existingOpt.isPresent()) {
            return existingOpt.get();
        }

        // 2. Create new task
        UUID taskId = idGeneratorPort.generate();
        Instant now = clockPort.now();
        NarrationMediaCleanupTask newTask = NarrationMediaCleanupTask.create(taskId, mediaAssetId, reason, now);

        // 3. Save with concurrent race handling
        try {
            return repositoryPort.save(newTask);
        } catch (NarrationMediaCleanupTaskAlreadyExistsException raceEx) {
            Optional<NarrationMediaCleanupTask> reloadedOpt = repositoryPort.findByMediaAssetId(mediaAssetId);
            if (reloadedOpt.isPresent()) {
                return reloadedOpt.get();
            }
            throw raceEx;
        }
    }
}
