package com.universe.media.domain;

/**
 * Explicit failure disposition for an active fenced HLS packaging attempt (MS-08 HLS-2B3A2A).
 *
 * <p>Controls whether a failure schedules a retry on the {@link MediaVideoPackagingJob}
 * ({@link #TRANSIENT_RETRYABLE}) or immediately terminates remaining retries as {@code FAILED}
 * ({@link #PERMANENT_NON_RETRYABLE}).
 */
public enum PackagingFailureDisposition {
    TRANSIENT_RETRYABLE,
    PERMANENT_NON_RETRYABLE
}
