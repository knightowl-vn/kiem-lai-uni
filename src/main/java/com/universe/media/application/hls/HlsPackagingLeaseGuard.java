package com.universe.media.application.hls;

import com.universe.media.application.exceptions.StalePackagingLeaseException;

import java.util.Optional;

/**
 * Liveness and cooperative cancellation boundary queried between major pipeline stages
 * while executing a claimed HLS packaging lease (MS-08 HLS-2B3B1).
 *
 * <p><strong>B2 Supervision Contract:</strong> Checkpoint polling alone does not renew leases
 * during long-running downloads, encodes, or uploads. Future B2 worker runtime must back this
 * contract with periodic {@link HeartbeatMediaVideoPackagingJobUseCase} renewal and active thread
 * interruption when ownership is lost. No silent no-op production guard is permitted.
 */
public interface HlsPackagingLeaseGuard {

    boolean isCancelled();

    boolean isLeaseConsideredLive();

    Optional<String> cancellationReason();

    default void ensureActive() {
        if (isCancelled()) {
            throw new StalePackagingLeaseException(
                    "Packaging attempt cancelled: " + cancellationReason().orElse("lease cancelled")
            );
        }
        if (!isLeaseConsideredLive()) {
            throw new StalePackagingLeaseException(
                    "Packaging lease is no longer considered live: " + cancellationReason().orElse("lease expired or lost")
            );
        }
    }
}
