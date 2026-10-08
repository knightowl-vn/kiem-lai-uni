package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ScreenEntryNotFoundException extends BaseApplicationException {

    public ScreenEntryNotFoundException(UUID entryId) {
        super("SCREEN_ENTRY_NOT_FOUND", "Không tìm thấy Screen entry với ID: " + entryId);
    }
}
