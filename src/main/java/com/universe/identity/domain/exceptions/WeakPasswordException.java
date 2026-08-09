package com.universe.identity.domain.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class WeakPasswordException extends BaseApplicationException {
    
    private static final long serialVersionUID = 1L;

    public WeakPasswordException(String message) {
        super("IDENTITY_WEAK_PASSWORD", message);
    }
}