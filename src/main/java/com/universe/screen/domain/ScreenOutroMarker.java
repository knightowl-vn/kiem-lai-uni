package com.universe.screen.domain;

/**
 * Value object representing a validated Outro start timestamp in whole seconds.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@code startSeconds >= 0}</li>
 * </ul>
 */
public record ScreenOutroMarker(long startSeconds) {

    public ScreenOutroMarker {
        if (startSeconds < 0) {
            throw new IllegalArgumentException("Outro startSeconds must be non-negative: " + startSeconds);
        }
    }

    /**
     * Constructs an optional {@link ScreenOutroMarker} when {@code startSeconds} is provided,
     * or returns {@code null} when {@code startSeconds} is {@code null}.
     */
    public static ScreenOutroMarker ofNullable(Long startSeconds) {
        if (startSeconds == null) {
            return null;
        }
        return new ScreenOutroMarker(startSeconds);
    }

    public boolean isReachedAt(long positionSeconds) {
        return positionSeconds >= startSeconds;
    }
}
