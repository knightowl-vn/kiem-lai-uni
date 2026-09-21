package com.universe.interaction.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Exception thrown when an interaction comment report cannot be found by its ID.
 */
public class InteractionReportNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public InteractionReportNotFoundException(UUID reportId) {
        super("INTERACTION_REPORT_NOT_FOUND", "Không tìm thấy báo cáo: " + reportId);
    }
}
