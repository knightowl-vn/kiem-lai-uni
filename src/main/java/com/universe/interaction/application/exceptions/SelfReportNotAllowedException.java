package com.universe.interaction.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when a user attempts to report their own comment.
 */
public class SelfReportNotAllowedException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public SelfReportNotAllowedException(UUID commentId, UUID userId) {
        super(
                "SELF_REPORT_NOT_ALLOWED",
                "User " + userId + " cannot report their own comment " + commentId
        );
    }

    public SelfReportNotAllowedException(String message) {
        super("SELF_REPORT_NOT_ALLOWED", message);
    }
}
