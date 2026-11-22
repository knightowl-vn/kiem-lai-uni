package com.universe.media.application.exceptions;

/**
 * Thrown when a purge sweeper attempts to complete or record failure against an HLS package
 * using a stale, mismatched, or expired purge claim generation (MS-08 HLS-2B3A2B).
 */
public class StalePurgeClaimException extends RuntimeException {

    public StalePurgeClaimException(String message) {
        super(message);
    }
}
