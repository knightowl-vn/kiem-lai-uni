package com.universe.interaction.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class CommentThreadIntegrityException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public CommentThreadIntegrityException(String message) {
        super("COMMENT_THREAD_INTEGRITY_VIOLATION", message);
    }
}
