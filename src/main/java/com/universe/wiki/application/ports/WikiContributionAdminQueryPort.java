package com.universe.wiki.application.ports;

import com.universe.wiki.application.contribution.query.WikiContributionAdminFilter;
import com.universe.wiki.application.contribution.query.WikiContributionAdminPage;
import com.universe.wiki.domain.contribution.WikiContributionStatus;

/**
 * Port for querying the Wiki contribution administrative inbox and queue counts.
 */
public interface WikiContributionAdminQueryPort {

    /**
     * Finds a paginated slice of Wiki contributions matching the admin filter.
     *
     * @param filter filter and pagination parameters (never null)
     * @return paginated results with deterministic ordering (createdAt DESC, id DESC)
     */
    WikiContributionAdminPage findAdminInboxPage(WikiContributionAdminFilter filter);

    /**
     * Counts contributions in the specified lifecycle status.
     *
     * @param status lifecycle status to count (never null)
     * @return total count of matching contributions
     */
    long countByStatus(WikiContributionStatus status);
}
