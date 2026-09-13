package com.universe.media.application.exceptions;

public class UploadContentMimeMismatchException extends IllegalArgumentException {

    public UploadContentMimeMismatchException(String message) {
        super(message);
    }

    public UploadContentMimeMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
