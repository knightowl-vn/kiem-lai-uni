package com.universe.interaction.application.exceptions;

import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportTargetType;
import com.universe.shared.exceptions.BaseApplicationException;

/**
 * Thrown when an attempted moderation action is incompatible with the report's target type.
 */
public class UnsupportedReportModerationActionException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public UnsupportedReportModerationActionException(ReportTargetType targetType, ReportModerationAction action) {
        super(
                "UNSUPPORTED_REPORT_MODERATION_ACTION",
                "Moderation action " + action + " is not supported for report target type " + targetType
        );
    }
}
