package com.universe.media.application.hls;

import com.universe.media.domain.MediaVideoHlsPackage;
import com.universe.media.domain.MediaVideoPackagingJob;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable result summarizing one attempt to execute a claimed HLS packaging lease.
 * Preserves post-commit cleanup diagnostics separately so a local cleanup exception
 * never corrupts an already committed READY/SUCCEEDED job state.
 */
public record HlsPackagingAttemptOutcome(
        HlsPackagingOutcomeStatus status,
        UUID jobId,
        UUID assetId,
        UUID packageId,
        long leaseGeneration,
        int attemptNumber,
        MediaVideoHlsPackage readyPackage,
        MediaVideoPackagingJob updatedJob,
        String failureCategory,
        String diagnosticMessage,
        Throwable cleanupFailure
) {

    public HlsPackagingAttemptOutcome {
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(jobId, "jobId cannot be null.");
        Objects.requireNonNull(assetId, "assetId cannot be null.");
        Objects.requireNonNull(packageId, "packageId cannot be null.");
        if (leaseGeneration < 1L) {
            throw new IllegalArgumentException("leaseGeneration must be >= 1, got: " + leaseGeneration);
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1, got: " + attemptNumber);
        }
    }

    public static HlsPackagingAttemptOutcome success(
            ClaimedVideoPackagingLease lease,
            MediaVideoHlsPackage readyPackage,
            Throwable cleanupFailure
    ) {
        Objects.requireNonNull(readyPackage, "readyPackage cannot be null.");
        return new HlsPackagingAttemptOutcome(
                HlsPackagingOutcomeStatus.SUCCESS,
                lease.jobId(),
                lease.assetId(),
                lease.packageId(),
                lease.leaseGeneration(),
                lease.attemptNumber(),
                readyPackage,
                null,
                null,
                null,
                cleanupFailure
        );
    }

    public static HlsPackagingAttemptOutcome staleOrCancelled(
            ClaimedVideoPackagingLease lease,
            String category,
            String reason,
            Throwable cleanupFailure
    ) {
        return new HlsPackagingAttemptOutcome(
                HlsPackagingOutcomeStatus.STALE_OR_CANCELLED,
                lease.jobId(),
                lease.assetId(),
                lease.packageId(),
                lease.leaseGeneration(),
                lease.attemptNumber(),
                null,
                null,
                category,
                reason,
                cleanupFailure
        );
    }

    public static HlsPackagingAttemptOutcome failure(
            HlsPackagingOutcomeStatus status,
            ClaimedVideoPackagingLease lease,
            MediaVideoPackagingJob updatedJob,
            String category,
            String reason,
            Throwable cleanupFailure
    ) {
        if (status == HlsPackagingOutcomeStatus.SUCCESS || status == HlsPackagingOutcomeStatus.STALE_OR_CANCELLED) {
            throw new IllegalArgumentException("Invalid failure status: " + status);
        }
        return new HlsPackagingAttemptOutcome(
                status,
                lease.jobId(),
                lease.assetId(),
                lease.packageId(),
                lease.leaseGeneration(),
                lease.attemptNumber(),
                null,
                updatedJob,
                category,
                reason,
                cleanupFailure
        );
    }

    public Optional<Throwable> cleanupFailureOptional() {
        return Optional.ofNullable(cleanupFailure);
    }
}
