package com.universe.screen.application.dto;

import java.util.List;

/**
 * Public catalog pagination model representing a page of published Screen titles.
 */
public record PublicScreenCatalogPageDTO(
        List<PublicScreenCatalogItemDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public PublicScreenCatalogPageDTO {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
