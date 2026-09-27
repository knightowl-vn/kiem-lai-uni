package com.universe.interaction.domain.report;

/**
 * Lifecycle status of an {@link InteractionReport}.
 *
 * <p>Valid states:
 * <ul>
 *   <li>{@link #PENDING}: Initial state when report is submitted. Awaiting moderation.</li>
 *   <li>{@link #RESOLVED_ACTION_TAKEN}: Terminal state when moderator addressed the report and took action.</li>
 *   <li>{@link #RESOLVED_NO_ACTION}: Terminal state when moderator reviewed the report and took no action.</li>
 * </ul>
 */
public enum ReportStatus {
    PENDING,
    RESOLVED_ACTION_TAKEN,
    RESOLVED_NO_ACTION;

    public boolean isPending() {
        return this == PENDING;
    }

    public boolean isTerminal() {
        return this != PENDING;
    }
}
