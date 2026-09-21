package com.universe.interaction.application.query;

/**
 * Lifecycle scope for the interaction comment report queue.
 *
 * <p>Application-level query descriptor separating actionable pending reports
 * from historically resolved reports.
 */
public enum ReportQueueLifecycleScope {
    PENDING,
    PROCESSED
}
