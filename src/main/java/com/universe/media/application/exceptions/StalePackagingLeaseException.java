package com.universe.media.application.exceptions;

/**
 * Thrown when a packaging worker attempts a fenced lease operation (heartbeat, fail, complete)
 * using an expired, superseded, or non-matching lease generation/owner or stale source version.
 */
public class StalePackagingLeaseException extends RuntimeException {

    public StalePackagingLeaseException(String message) {
        super(message);
    }
}
