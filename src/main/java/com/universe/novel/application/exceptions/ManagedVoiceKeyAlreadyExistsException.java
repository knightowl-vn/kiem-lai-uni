package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class ManagedVoiceKeyAlreadyExistsException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "NOVEL_MANAGED_VOICE_KEY_ALREADY_EXISTS";

    public ManagedVoiceKeyAlreadyExistsException(String voiceKey) {
        super(DEFAULT_ERROR_CODE, "Voice key của giọng đọc đã tồn tại: " + voiceKey);
    }
}
