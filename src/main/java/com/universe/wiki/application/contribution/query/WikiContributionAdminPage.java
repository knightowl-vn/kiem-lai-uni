package com.universe.wiki.application.contribution.query;

import java.util.List;

/**
 * Paginated result of Wiki contribution admin inbox items.
 */
public record WikiContributionAdminPage(
        List<WikiContributionAdminItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public WikiContributionAdminPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static WikiContributionAdminPage empty(int page, int size) {
        return new WikiContributionAdminPage(List.of(), page, size, 0L, 0, true, true);
    }
}
