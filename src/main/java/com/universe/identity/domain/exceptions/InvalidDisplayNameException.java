package com.universe.identity.domain.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class InvalidDisplayNameException
        extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public InvalidDisplayNameException(
            String message
    ) {
        super(
                "IDENTITY_INVALID_DISPLAY_NAME",
                message
        );
    }
}