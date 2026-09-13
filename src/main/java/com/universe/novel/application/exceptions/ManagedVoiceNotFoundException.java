package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ManagedVoiceNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "NOVEL_MANAGED_VOICE_NOT_FOUND";

    public ManagedVoiceNotFoundException(UUID id) {
        super(DEFAULT_ERROR_CODE, "Không tìm thấy giọng đọc có ID: " + id);
    }

    public ManagedVoiceNotFoundException(String voiceKey) {
        super(DEFAULT_ERROR_CODE, "Không tìm thấy giọng đọc có voiceKey: " + voiceKey);
    }
}
