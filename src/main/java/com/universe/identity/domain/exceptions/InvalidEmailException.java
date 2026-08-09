package com.universe.identity.domain.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class InvalidEmailException extends BaseApplicationException {
    
    private static final long serialVersionUID = 1L;

    public InvalidEmailException(String message) {
        // "IDENTITY_INVALID_EMAIL" là errorCode trả về cho Frontend
        super("IDENTITY_INVALID_EMAIL", message);
    }
}