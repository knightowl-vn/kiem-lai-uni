package com.universe.novel.application.chapter.reference;

import com.universe.novel.application.ports.WikiContextualLookupPort;
import com.universe.novel.application.wiki.lookup.WikiContextualLookupResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Use case tìm kiếm các bài viết Wiki đã xuất bản (PUBLISHED)
 * để phục vụ gán liên kết Wiki trong quản trị Chapter (MS-02.8.1 Step 6D1).
 */
@Service
@Transactional(readOnly = true)
public class SearchTargetWikiArticlesUseCase {

    public static final int MAX_QUERY_LENGTH = 100;

    private final WikiContextualLookupPort wikiContextualLookupPort;

    public SearchTargetWikiArticlesUseCase(WikiContextualLookupPort wikiContextualLookupPort) {
        this.wikiContextualLookupPort = Objects.requireNonNull(
                wikiContextualLookupPort,
                "WikiContextualLookupPort không được để trống."
        );
    }

    public TargetWikiArticleSearchResultDTO execute(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return TargetWikiArticleSearchResultDTO.empty("");
        }

        String normalizedQuery = rawQuery.trim();
        if (normalizedQuery.length() > MAX_QUERY_LENGTH) {
            return TargetWikiArticleSearchResultDTO.empty(normalizedQuery);
        }

        WikiContextualLookupResult result = wikiContextualLookupPort.lookup(normalizedQuery);
        if (result == null || result.items() == null || result.items().isEmpty()) {
            return TargetWikiArticleSearchResultDTO.empty(normalizedQuery);
        }

        List<TargetWikiArticleSearchItemDTO> items = result.items().stream()
                .map(item -> new TargetWikiArticleSearchItemDTO(
                        item.id(),
                        item.title(),
                        item.articleType(),
                        item.slug(),
                        item.summary(),
                        item.matchedAlias()
                ))
                .toList();

        return new TargetWikiArticleSearchResultDTO(
                result.query() != null ? result.query() : normalizedQuery,
                items
        );
    }
}
