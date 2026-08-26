package com.universe.wiki.application.article.query.lookup;

import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.WikiContextualLookupItemDTO;
import com.universe.wiki.contracts.dto.WikiContextualLookupResultDTO;
import com.universe.wiki.contracts.interfaces.WikiContextualLookupContract;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class WikiContextualLookupService implements WikiContextualLookupContract {

    private static final int MAX_RESULTS = 5;
    private static final int MAX_QUERY_LENGTH = 100;

    private final WikiArticleQueryPort wikiArticleQueryPort;

    public WikiContextualLookupService(WikiArticleQueryPort wikiArticleQueryPort) {
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort,
                "WikiArticleQueryPort không được để trống."
        );
    }

    @Override
    @Transactional(readOnly = true)
    public WikiContextualLookupResultDTO lookupByTitle(String query) {
        if (query == null || query.isBlank()) {
            return new WikiContextualLookupResultDTO("", false, List.of());
        }

        String normalizedQuery = query.trim();
        if (normalizedQuery.length() > MAX_QUERY_LENGTH) {
            return new WikiContextualLookupResultDTO(normalizedQuery, false, List.of());
        }

        List<PublishedWikiArticleListItemDTO> matches =
                wikiArticleQueryPort.findPublishedContextualMatches(normalizedQuery, MAX_RESULTS);

        boolean hasExactMatch = !matches.isEmpty()
                && matches.get(0).title().equalsIgnoreCase(normalizedQuery);

        List<WikiContextualLookupItemDTO> items = matches.stream()
                .map(this::toLookupItemDTO)
                .toList();

        return new WikiContextualLookupResultDTO(normalizedQuery, hasExactMatch, items);
    }

    private WikiContextualLookupItemDTO toLookupItemDTO(PublishedWikiArticleListItemDTO dto) {
        return new WikiContextualLookupItemDTO(
                dto.id(),
                dto.title(),
                dto.articleType(),
                dto.slug(),
                dto.summary()
        );
    }
}
