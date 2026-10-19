package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class ScreenVideoDiscardAccessDeniedException extends BaseApplicationException {

    public ScreenVideoDiscardAccessDeniedException() {
        this("Access denied to discard video upload session.");
    }

    public ScreenVideoDiscardAccessDeniedException(String message) {
        super("SCREEN_VIDEO_DISCARD_ACCESS_DENIED", message);
    }
}
