package com.universe.community.entry.admin.dto;

import java.util.List;

/**
 * Paginated page response for Admin Hidden Community Posts management.
 */
public record AdminCommunityPostHiddenPageDTO(
        List<AdminCommunityPostHiddenItemDTO> items,
        int page,
        int size,
        long totalElements
) {
    public AdminCommunityPostHiddenPageDTO {
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
