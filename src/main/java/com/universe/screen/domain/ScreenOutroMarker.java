package com.universe.screen.domain;

/**
 * Value object representing a validated Outro start timestamp in whole seconds,
 * with an optional end boundary in whole seconds.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code startSeconds >= 0}: Outro starting boundary.</li>
 *   <li>{@code endSeconds == null}: Unbounded Outro, extending to the natural end of the video.</li>
 *   <li>{@code endSeconds > startSeconds}: Bounded Outro, ending before the end of the video
 *       (e.g., an after-credit scene follows).</li>
 * </ul>
 */
public record ScreenOutroMarker(long startSeconds, Long endSeconds) {

    public ScreenOutroMarker {
        if (startSeconds < 0) {
            throw new IllegalArgumentException("Outro startSeconds must be non-negative: " + startSeconds);
        }
        if (endSeconds != null && endSeconds <= startSeconds) {
            throw new IllegalArgumentException(
                    "Outro endSeconds (" + endSeconds + ") must be strictly greater than startSeconds (" + startSeconds + ")."
            );
        }
    }

    /**
     * Backward-compatible constructor for an unbounded Outro marker.
     */
    public ScreenOutroMarker(long startSeconds) {
        this(startSeconds, null);
    }

    /**
     * Constructs an optional {@link ScreenOutroMarker} with unbounded end when {@code startSeconds} is provided,
     * or returns {@code null} when {@code startSeconds} is {@code null}.
     */
    public static ScreenOutroMarker ofNullable(Long startSeconds) {
        return ofNullable(startSeconds, null);
    }

    /**
     * Constructs an optional {@link ScreenOutroMarker} when {@code startSeconds} is provided.
     *
     * @param startSeconds Outro start timestamp in whole seconds
     * @param endSeconds Outro end timestamp in whole seconds (nullable)
     * @return {@link ScreenOutroMarker} or {@code null} if both start and end are absent
     * @throws IllegalArgumentException if endSeconds is provided without startSeconds, or if invalid
     */
    public static ScreenOutroMarker ofNullable(Long startSeconds, Long endSeconds) {
        if (startSeconds == null && endSeconds == null) {
            return null;
        }
        if (startSeconds == null) {
            throw new IllegalArgumentException(
                    "Outro startSeconds is null while endSeconds is provided: " + endSeconds
            );
        }
        return new ScreenOutroMarker(startSeconds, endSeconds);
    }

    public boolean hasEnd() {
        return endSeconds != null;
    }

    public boolean isReachedAt(long positionSeconds) {
        return positionSeconds >= startSeconds;
    }

    /**
     * Checks if a playback position falls inside the Outro interval {@code [startSeconds, endSeconds)}.
     * If {@code endSeconds} is {@code null}, the interval is unbounded on the right ({@code [startSeconds, +inf)}).
     */
    public boolean contains(long positionSeconds) {
        if (positionSeconds < startSeconds) {
            return false;
        }
        if (endSeconds != null && positionSeconds >= endSeconds) {
            return false;
        }
        return true;
    }
}
