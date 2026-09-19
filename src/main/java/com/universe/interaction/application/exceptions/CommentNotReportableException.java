package com.universe.interaction.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when a comment is not in a publicly reportable state (e.g. deleted comment, or reply in a deleted thread).
 */
public class CommentNotReportableException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public CommentNotReportableException(UUID commentId, String reason) {
        super(
                "COMMENT_NOT_REPORTABLE",
                "Comment " + commentId + " cannot be reported: " + reason
        );
    }

    public CommentNotReportableException(String message) {
        super("COMMENT_NOT_REPORTABLE", message);
    }
}
