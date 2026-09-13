package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when attempting to persist a duplicate narration media cleanup task for a media asset that is already queued (MS-04.9H.8D1A1).
 */
public class NarrationMediaCleanupTaskAlreadyExistsException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "NOVEL_NARRATION_MEDIA_CLEANUP_TASK_ALREADY_EXISTS";

    public NarrationMediaCleanupTaskAlreadyExistsException(UUID mediaAssetId) {
        super(DEFAULT_ERROR_CODE, String.format(
                "Narration media cleanup task already exists for media asset '%s'.",
                mediaAssetId
        ));
    }

    public NarrationMediaCleanupTaskAlreadyExistsException(String message) {
        super(DEFAULT_ERROR_CODE, message);
    }
}
