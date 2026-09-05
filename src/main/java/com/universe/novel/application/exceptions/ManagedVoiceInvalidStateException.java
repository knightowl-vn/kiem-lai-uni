package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class ManagedVoiceInvalidStateException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "NOVEL_MANAGED_VOICE_INVALID_STATE";

    public ManagedVoiceInvalidStateException(String message) {
        super(DEFAULT_ERROR_CODE, message);
    }
}
