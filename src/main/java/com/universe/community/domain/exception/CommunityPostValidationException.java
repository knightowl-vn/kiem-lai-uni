package com.universe.community.domain.exception;

import com.universe.shared.exceptions.BaseApplicationException;

/**
 * Thrown when a CommunityPost domain or command validation rule is violated.
 */
public class CommunityPostValidationException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public CommunityPostValidationException(String message) {
        super("COMMUNITY_POST_VALIDATION_ERROR", message);
    }

    public CommunityPostValidationException(String message, Throwable cause) {
        super("COMMUNITY_POST_VALIDATION_ERROR", message, cause);
    }
}
