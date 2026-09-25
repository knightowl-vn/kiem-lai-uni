package com.universe.wiki.entry.admin.dto;

import java.util.List;

/**
 * Paginated composite view of Wiki contribution admin queue items.
 */
public record AdminWikiContributionQueuePageDTO(
        List<AdminWikiContributionQueueItemDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public AdminWikiContributionQueuePageDTO {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static AdminWikiContributionQueuePageDTO empty(int page, int size) {
        return new AdminWikiContributionQueuePageDTO(List.of(), page, size, 0L, 0, true, true);
    }
}
