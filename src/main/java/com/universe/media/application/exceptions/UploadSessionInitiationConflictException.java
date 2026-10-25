package com.universe.media.application.exceptions;

import java.util.Objects;
import java.util.UUID;

/**
 * Thrown when an upload session initiation attempt is rejected due to an active concurrent
 * initiation lease already held by another worker or request.
 */
public class UploadSessionInitiationConflictException extends IllegalStateException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "MEDIA_UPLOAD_INITIATION_IN_PROGRESS";

    private final UUID sessionId;
    private final String leaseOwner;
    private final String errorCode;

    public UploadSessionInitiationConflictException(UUID sessionId, String leaseOwner, String message) {
        super(message);
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        this.leaseOwner = leaseOwner;
        this.errorCode = DEFAULT_ERROR_CODE;
    }

    public UploadSessionInitiationConflictException(UUID sessionId, String leaseOwner, String message, Throwable cause) {
        super(message, cause);
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        this.leaseOwner = leaseOwner;
        this.errorCode = DEFAULT_ERROR_CODE;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
