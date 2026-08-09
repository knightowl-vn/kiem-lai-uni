package com.universe.identity.domain.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class InvalidBioException
        extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public InvalidBioException(
            String message
    ) {
        super(
                "IDENTITY_INVALID_BIO",
                message
        );
    }
}