package com.universe.media.application.exceptions;

public class MalformedImageException extends ImageProcessingException {

    public MalformedImageException(String message) {
        super(message);
    }

    public MalformedImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
