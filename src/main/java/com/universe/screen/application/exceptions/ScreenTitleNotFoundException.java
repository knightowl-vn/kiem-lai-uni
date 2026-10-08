package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ScreenTitleNotFoundException extends BaseApplicationException {

    public ScreenTitleNotFoundException(UUID titleId) {
        super("SCREEN_TITLE_NOT_FOUND", "Không tìm thấy Screen title với ID: " + titleId);
    }
}
