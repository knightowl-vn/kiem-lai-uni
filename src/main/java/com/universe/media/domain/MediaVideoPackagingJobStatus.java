package com.universe.media.domain;

/**
 * Lifecycle states of a fenced async HLS packaging job.
 */
public enum MediaVideoPackagingJobStatus {
    PENDING,
    LEASED,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
