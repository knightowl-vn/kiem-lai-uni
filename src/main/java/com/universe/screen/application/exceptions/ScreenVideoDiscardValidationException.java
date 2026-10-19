package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class ScreenVideoDiscardValidationException extends BaseApplicationException {

    public ScreenVideoDiscardValidationException(String errorCode, String message) {
        super(errorCode, "[" + errorCode + "] " + message);
    }
}
