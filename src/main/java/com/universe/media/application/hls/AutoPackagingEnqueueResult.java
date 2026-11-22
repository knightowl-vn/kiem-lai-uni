package com.universe.media.application.hls;

import com.universe.media.domain.MediaVideoPackagingJob;

import java.util.Objects;

/**
 * Result of evaluating/executing an opt-in automatic HLS packaging enqueue request.
 */
public record AutoPackagingEnqueueResult(
        AutoPackagingEnqueueStatus status,
        MediaVideoPackagingJob job
) {

    public AutoPackagingEnqueueResult {
        Objects.requireNonNull(status, "AutoPackagingEnqueueStatus cannot be null.");
        if ((status == AutoPackagingEnqueueStatus.CREATED || status == AutoPackagingEnqueueStatus.REUSED_ACTIVE)
                && job == null) {
            throw new IllegalArgumentException("MediaVideoPackagingJob cannot be null for status: " + status);
        }
    }

    public static AutoPackagingEnqueueResult created(MediaVideoPackagingJob job) {
        return new AutoPackagingEnqueueResult(AutoPackagingEnqueueStatus.CREATED, job);
    }

    public static AutoPackagingEnqueueResult reusedActive(MediaVideoPackagingJob job) {
        return new AutoPackagingEnqueueResult(AutoPackagingEnqueueStatus.REUSED_ACTIVE, job);
    }

    public static AutoPackagingEnqueueResult skipped(AutoPackagingEnqueueStatus status) {
        if (status == AutoPackagingEnqueueStatus.CREATED || status == AutoPackagingEnqueueStatus.REUSED_ACTIVE) {
            throw new IllegalArgumentException("Cannot create skipped result with active status: " + status);
        }
        return new AutoPackagingEnqueueResult(status, null);
    }

    public boolean hasActiveJob() {
        return status == AutoPackagingEnqueueStatus.CREATED || status == AutoPackagingEnqueueStatus.REUSED_ACTIVE;
    }
}
