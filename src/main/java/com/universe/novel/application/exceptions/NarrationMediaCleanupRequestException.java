package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when requesting narration media cleanup fails completely: both establishing
 * the durable cleanup intent and executing immediate media deletion failed (MS-04.9H.8D2A).
 */
public class NarrationMediaCleanupRequestException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "NOVEL_NARRATION_MEDIA_CLEANUP_REQUEST_FAILED";

    public NarrationMediaCleanupRequestException(UUID mediaAssetId, String message, Throwable cause) {
        super(DEFAULT_ERROR_CODE, message, cause);
    }

    public NarrationMediaCleanupRequestException(String message, Throwable cause) {
        super(DEFAULT_ERROR_CODE, message, cause);
    }
}
