package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ScreenUploadSessionNotFoundException extends BaseApplicationException {

    public ScreenUploadSessionNotFoundException(UUID sessionId) {
        super("SCREEN_UPLOAD_SESSION_NOT_FOUND",
                "Không tìm thấy phiên tải lên video Screen [" + sessionId + "].");
    }
}
