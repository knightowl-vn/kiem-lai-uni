package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when an operation requires an attached video asset on a ScreenEntry, but none is attached.
 */
public class ScreenEntryVideoNotAttachedException extends BaseApplicationException {

    public ScreenEntryVideoNotAttachedException(UUID entryId) {
        super("SCREEN_ENTRY_VIDEO_NOT_ATTACHED",
                "ScreenEntry [" + entryId + "] chưa gắn video nào để thực hiện thao tác này.");
    }
}
