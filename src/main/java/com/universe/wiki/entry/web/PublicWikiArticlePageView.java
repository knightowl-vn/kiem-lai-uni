package com.universe.wiki.entry.web;

import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * View model representing one paginated page of published Wiki articles for public index rendering.
 */
public record PublicWikiArticlePageView(
        List<PublicWikiArticleCardView> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public PublicWikiArticlePageView {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static PublicWikiArticlePageView fromDTO(
            PublishedWikiArticlePageDTO pageDTO,
            Map<UUID, String> directCoverUrls
    ) {
        Objects.requireNonNull(pageDTO, "PublishedWikiArticlePageDTO cannot be null.");
        Map<UUID, String> urls = directCoverUrls != null ? directCoverUrls : Map.of();
        List<PublicWikiArticleCardView> cardViews = pageDTO.items().stream()
                .map(item -> PublicWikiArticleCardView.fromDTO(
                        item,
                        item.coverMediaAssetId() != null ? urls.get(item.coverMediaAssetId()) : null
                ))
                .toList();

        return new PublicWikiArticlePageView(
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
