package com.universe.identity.domain.exceptions;


import com.universe.shared.exceptions.BaseApplicationException;

public class EmailAlreadyExistsException extends BaseApplicationException {
    
    private static final long serialVersionUID = 1L;

    public EmailAlreadyExistsException(String message) {
        super("IDENTITY_EMAIL_EXISTS", message);
    }
}