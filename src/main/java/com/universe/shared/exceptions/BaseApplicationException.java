package com.universe.shared.exceptions;

/**
 * Lớp Exception nền tảng cho toàn bộ hệ thống Kiếm Lai Universe.
 * Mọi Exception nghiệp vụ (Business Exception) đều phải kế thừa lớp này.
 */
public abstract class BaseApplicationException extends RuntimeException {
    
    private final String errorCode;

    protected BaseApplicationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected BaseApplicationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}