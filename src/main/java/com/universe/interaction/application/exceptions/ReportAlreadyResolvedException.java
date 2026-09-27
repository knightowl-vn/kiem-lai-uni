package com.universe.interaction.application.exceptions;

import com.universe.interaction.domain.report.ReportStatus;
import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Exception thrown when attempting to moderate an interaction report that is no longer in PENDING status.
 */
public class ReportAlreadyResolvedException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public ReportAlreadyResolvedException(UUID reportId) {
        super(
                "REPORT_ALREADY_RESOLVED",
                "Interaction report " + reportId + " is already resolved and no longer pending."
        );
    }

    public ReportAlreadyResolvedException(UUID reportId, ReportStatus status) {
        super(
                "REPORT_ALREADY_RESOLVED",
                "Interaction report " + reportId + " is already resolved in status " + status + "."
        );
    }
}
