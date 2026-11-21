package com.universe.media.application.hls;

import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Single-row conditional lease heartbeat. Modifies zero FK columns and acquires zero parent locks.
 */
@Service
public class HeartbeatMediaVideoPackagingJobUseCase {

    private final MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort;
    private final ClockPort clockPort;

    public HeartbeatMediaVideoPackagingJobUseCase(
            MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort,
            ClockPort clockPort
    ) {
        this.packagingJobRepositoryPort = Objects.requireNonNull(packagingJobRepositoryPort, "MediaVideoPackagingJobRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean execute(UUID jobId, String workerId, long leaseGeneration, Duration leaseExtension) {
        Objects.requireNonNull(jobId, "Job ID cannot be null.");
        Objects.requireNonNull(workerId, "workerId cannot be null.");
        if (workerId.isBlank()) {
            throw new IllegalArgumentException("workerId cannot be blank.");
        }
        Objects.requireNonNull(leaseExtension, "leaseExtension cannot be null.");
        if (leaseExtension.isNegative() || leaseExtension.isZero()) {
            throw new IllegalArgumentException("leaseExtension must be strictly positive.");
        }

        Instant now = clockPort.now();
        int updated = packagingJobRepositoryPort.updateHeartbeatIfLeaseMatches(
                jobId,
                workerId.trim(),
                leaseGeneration,
                now,
                now,
                now.plus(leaseExtension),
                now
        );
        return updated == 1;
    }
}
