package com.universe.screen.domain;

/**
 * Value object representing a validated Intro time interval {@code [startSeconds, endSeconds)} in whole seconds.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@code startSeconds >= 0}</li>
 *   <li>{@code endSeconds > startSeconds}</li>
 * </ul>
 */
public record ScreenIntroInterval(long startSeconds, long endSeconds) {

    public ScreenIntroInterval {
        if (startSeconds < 0) {
            throw new IllegalArgumentException("Intro startSeconds must be non-negative: " + startSeconds);
        }
        if (endSeconds <= startSeconds) {
            throw new IllegalArgumentException(
                    "Intro endSeconds must be strictly greater than startSeconds: start="
                            + startSeconds + ", end=" + endSeconds
            );
        }
    }

    /**
     * Constructs an optional {@link ScreenIntroInterval} when both bounds are provided, or returns {@code null}
     * when both bounds are {@code null}. Rejects partial intervals where only one bound is provided.
     */
    public static ScreenIntroInterval ofNullable(Long startSeconds, Long endSeconds) {
        if (startSeconds == null && endSeconds == null) {
            return null;
        }
        if (startSeconds == null || endSeconds == null) {
            throw new IllegalArgumentException(
                    "Intro interval requires both startSeconds and endSeconds to be non-null or both null."
            );
        }
        return new ScreenIntroInterval(startSeconds, endSeconds);
    }

    public long durationSeconds() {
        return endSeconds - startSeconds;
    }

    public boolean contains(long positionSeconds) {
        return positionSeconds >= startSeconds && positionSeconds < endSeconds;
    }
}
