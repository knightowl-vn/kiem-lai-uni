package com.universe.interaction.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class CommentNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public CommentNotFoundException(UUID commentId) {
        super("COMMENT_NOT_FOUND", "Comment not found: " + commentId);
    }

    public CommentNotFoundException(String message) {
        super("COMMENT_NOT_FOUND", message);
    }
}
