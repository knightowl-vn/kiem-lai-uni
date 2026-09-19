package com.universe.interaction.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when a user attempts to submit a report for a comment while they already have an active PENDING report.
 */
public class DuplicatePendingReportException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public DuplicatePendingReportException(UUID commentId, UUID reporterUserId) {
        super(
                "DUPLICATE_PENDING_REPORT",
                "A pending report already exists for comment " + commentId + " by reporter " + reporterUserId
        );
    }

    public DuplicatePendingReportException(String message) {
        super("DUPLICATE_PENDING_REPORT", message);
    }
}
