package com.universe.screen.entry.web;

import com.universe.screen.application.dto.PublicScreenCatalogPageDTO;

import java.util.List;
import java.util.Objects;

/**
 * Public catalog pagination view model for Thymeleaf rendering.
 */
public record PublicScreenCatalogPageView(
        List<PublicScreenCatalogCardView> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public PublicScreenCatalogPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean isEmptyCatalog() {
        return totalElements == 0;
    }

    public boolean isOutOfRange() {
        return totalElements > 0 && items.isEmpty();
    }

    public static PublicScreenCatalogPageView fromDTO(PublicScreenCatalogPageDTO pageDTO) {
        Objects.requireNonNull(pageDTO, "PublicScreenCatalogPageDTO cannot be null.");
        List<PublicScreenCatalogCardView> cardViews = pageDTO.items().stream()
                .map(PublicScreenCatalogCardView::fromDTO)
                .toList();
        return new PublicScreenCatalogPageView(
                cardViews,
                pageDTO.page(),
                pageDTO.size(),
                pageDTO.totalElements(),
                pageDTO.totalPages(),
                pageDTO.first(),
                pageDTO.last()
        );
    }
}
