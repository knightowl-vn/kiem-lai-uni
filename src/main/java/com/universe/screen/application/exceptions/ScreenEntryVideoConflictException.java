package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when an operation on a ScreenEntry video fails optimistic concurrency / stale checks.
 */
public class ScreenEntryVideoConflictException extends BaseApplicationException {

    public ScreenEntryVideoConflictException(UUID entryId, UUID expectedVideoAssetId, UUID actualVideoAssetId) {
        super("SCREEN_ENTRY_VIDEO_CONFLICT",
                "Xung đột video trên ScreenEntry [" + entryId + "]: Video hiện tại [" + actualVideoAssetId
                        + "] không khớp với video mong đợi [" + expectedVideoAssetId + "]. Thao tác đã bị hủy để tránh ghi đè dữ liệu.");
    }
}
