package com.universe.community.entry.admin.dto;

import java.util.List;
import java.util.Objects;

/**
 * Paginated composite response for Admin Community Post Report Queue.
 */
public record AdminCommunityPostReportPageDTO(
        List<AdminCommunityPostReportItemDTO> items,
        int page,
        int size,
        long totalElements
) {
    public AdminCommunityPostReportPageDTO {
        items = items != null ? List.copyOf(items) : List.of();
    }

    public int totalPages() {
        if (size <= 0) return 0;
        return (int) Math.ceil((double) totalElements / (double) size);
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        return page + 1 < totalPages();
    }
}
