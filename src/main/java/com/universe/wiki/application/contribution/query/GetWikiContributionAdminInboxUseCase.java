package com.universe.wiki.application.contribution.query;

import com.universe.wiki.application.ports.WikiContributionAdminQueryPort;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Application use case for querying the read-only Wiki contribution administrative inbox.
 */
@Service
public class GetWikiContributionAdminInboxUseCase {

    private static final int MAX_PAGE_SIZE = 50;

    private final WikiContributionAdminQueryPort queryPort;

    public GetWikiContributionAdminInboxUseCase(WikiContributionAdminQueryPort queryPort) {
        this.queryPort = Objects.requireNonNull(queryPort, "WikiContributionAdminQueryPort cannot be null");
    }

    /**
     * Executes the admin inbox query with sanitized pagination bounds and out-of-range recovery.
     *
     * <p>If a page beyond the last available page is requested on a non-empty result set,
     * re-queries exactly once using {@code page = totalPages - 1} to return the valid final page.
     *
     * @param filter admin filter options
     * @return paginated inbox result
     */
    @Transactional(readOnly = true)
    public WikiContributionAdminPage getInboxPage(WikiContributionAdminFilter filter) {
        Objects.requireNonNull(filter, "Filter cannot be null");
        int safePage = Math.max(0, filter.page());
        int safeSize = filter.size() > 0 ? Math.min(filter.size(), MAX_PAGE_SIZE) : 20;

        WikiContributionAdminFilter safeFilter = new WikiContributionAdminFilter(
                filter.status(),
                filter.contributionType(),
                filter.keyword(),
                safePage,
                safeSize
        );

        WikiContributionAdminPage initialPage = queryPort.findAdminInboxPage(safeFilter);

        // Safe out-of-range page recovery:
        // When there is data (totalPages > 0), the requested page index is >= totalPages,
        // and items are empty, re-query once for the valid last page (page = totalPages - 1).
        if (initialPage != null
                && initialPage.totalPages() > 0
                && safePage >= initialPage.totalPages()
                && initialPage.items().isEmpty()) {

            int fallbackPage = initialPage.totalPages() - 1;
            WikiContributionAdminFilter fallbackFilter = new WikiContributionAdminFilter(
                    filter.status(),
                    filter.contributionType(),
                    filter.keyword(),
                    fallbackPage,
                    safeSize
            );
            return queryPort.findAdminInboxPage(fallbackFilter);
        }

        return initialPage != null ? initialPage : WikiContributionAdminPage.empty(safePage, safeSize);
    }

    /**
     * Retrieves the count of contributions currently in the given status.
     *
     * @param status status to count
     * @return total count
     */
    @Transactional(readOnly = true)
    public long getCountByStatus(WikiContributionStatus status) {
        Objects.requireNonNull(status, "Status cannot be null");
        return queryPort.countByStatus(status);
    }
}
