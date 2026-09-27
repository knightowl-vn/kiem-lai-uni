package com.universe.interaction.application.ports;

import com.universe.interaction.application.query.InteractionReportQueueFilter;
import com.universe.interaction.application.query.InteractionReportQueuePage;

/**
 * Output port for querying the interaction comment report queue.
 *
 * <p>Separated from aggregate write/lifecycle operations to maintain clean CQRS boundaries.
 */
public interface InteractionReportQueueQueryPort {

    /**
     * Finds a paginated page of comment reports matching the specified filter criteria.
     *
     * @param filter the queue filter criteria (cannot be null)
     * @return the paginated queue result page
     */
    InteractionReportQueuePage findQueueReports(InteractionReportQueueFilter filter);
}
