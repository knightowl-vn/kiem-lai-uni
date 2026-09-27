package com.universe.interaction.entry.admin.dto;

import java.util.List;
import java.util.Objects;

/**
 * Immutable paginated result container for the Admin comment report queue.
 *
 * @param items defensive copy of enriched queue items on the current page
 * @param page zero-based page index (>= 0)
 * @param size requested page size (> 0)
 * @param totalElements authoritative count of matching reports (>= 0)
 */
public record AdminCommentReportQueuePageDTO(
        List<AdminCommentReportQueueItemDTO> items,
        int page,
        int size,
        long totalElements
) {
    public AdminCommentReportQueuePageDTO {
        Objects.requireNonNull(items, "Items cannot be null.");
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero: " + size);
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("Total elements cannot be negative: " + totalElements);
        }
        items = List.copyOf(items);
    }

    /**
     * Derives total number of pages from totalElements and page size.
     *
     * @return 0 if totalElements is 0, otherwise ceil(totalElements / size)
     */
    public int totalPages() {
        if (totalElements == 0) {
            return 0;
        }
        return (int) Math.ceil((double) totalElements / (double) size);
    }

    /**
     * Whether there is a subsequent page of results.
     */
    public boolean hasNext() {
        return totalPages() > 0 && page < totalPages() - 1;
    }

    /**
     * Whether there is a preceding page of results.
     */
    public boolean hasPrevious() {
        return page > 0 && totalPages() > 0;
    }

    /**
     * Creates an empty page for the given page index and size.
     */
    public static AdminCommentReportQueuePageDTO empty(int page, int size) {
        return new AdminCommentReportQueuePageDTO(List.of(), page, size, 0);
    }
}
