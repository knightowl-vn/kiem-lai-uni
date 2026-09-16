package com.universe.interaction.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class CommentMutationForbiddenException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public CommentMutationForbiddenException(String message) {
        super("COMMENT_MUTATION_FORBIDDEN", message);
    }
}
