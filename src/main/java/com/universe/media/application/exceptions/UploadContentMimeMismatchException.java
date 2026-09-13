package com.universe.media.application.exceptions;

public class UploadContentMimeMismatchException extends RuntimeException {

    public UploadContentMimeMismatchException(String message) {
        super(message);
    }

    public UploadContentMimeMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
