package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

/**
 * Exception thrown when an error occurs while communicating with or receiving
 * responses from an external TTS provider.
 */
public class TtsProviderException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "NOVEL_TTS_PROVIDER_FAILURE";

    public TtsProviderException(String message) {
        super(DEFAULT_ERROR_CODE, message);
    }

    public TtsProviderException(String message, Throwable cause) {
        super(DEFAULT_ERROR_CODE, message, cause);
    }

    public TtsProviderException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
