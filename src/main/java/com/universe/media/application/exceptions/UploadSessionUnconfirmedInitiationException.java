package com.universe.media.application.exceptions;

import java.util.Objects;
import java.util.UUID;

/**
 * Thrown when an upload session initiation attempt is rejected because a previous initiation
 * claim exists without a confirmed providerUploadId, requiring explicit reconciliation before
 * any secondary initiation can be permitted.
 */
public class UploadSessionUnconfirmedInitiationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM";

    private final UUID sessionId;
    private final String leaseOwner;
    private final String errorCode;

    public UploadSessionUnconfirmedInitiationException(UUID sessionId, String leaseOwner, String message) {
        super(message);
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        this.leaseOwner = leaseOwner;
        this.errorCode = DEFAULT_ERROR_CODE;
    }

    public UploadSessionUnconfirmedInitiationException(UUID sessionId, String leaseOwner, String message, Throwable cause) {
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
