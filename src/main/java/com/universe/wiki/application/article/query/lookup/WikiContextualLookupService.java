package com.universe.wiki.application.article.query.lookup;

import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.WikiContextualLookupItemDTO;
import com.universe.wiki.contracts.dto.WikiContextualLookupResultDTO;
import com.universe.wiki.contracts.interfaces.WikiContextualLookupContract;
import com.universe.wiki.domain.article.WikiAliasNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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

        String cleanedQuery = WikiAliasNormalizer.cleanDisplayAlias(query);
        if (cleanedQuery.isEmpty() || cleanedQuery.length() > MAX_QUERY_LENGTH) {
            return new WikiContextualLookupResultDTO(cleanedQuery, false, List.of());
        }

        String normalizedQuery = WikiAliasNormalizer.normalize(cleanedQuery);

        // 1. Query Title Matches (at most MAX_RESULTS)
        List<PublishedWikiArticleListItemDTO> titleMatches =
                wikiArticleQueryPort.findPublishedContextualMatches(cleanedQuery, MAX_RESULTS);

        List<PublishedWikiArticleListItemDTO> exactTitleMatches = new ArrayList<>();
        List<PublishedWikiArticleListItemDTO> prefixTitleMatches = new ArrayList<>();
        List<PublishedWikiArticleListItemDTO> containsTitleMatches = new ArrayList<>();

        String lowerQuery = cleanedQuery.toLowerCase(Locale.ROOT);
        for (PublishedWikiArticleListItemDTO item : titleMatches) {
            String lowerTitle = item.title().toLowerCase(Locale.ROOT);
            if (lowerTitle.equals(lowerQuery)) {
                exactTitleMatches.add(item);
            } else if (lowerTitle.startsWith(lowerQuery)) {
                prefixTitleMatches.add(item);
            } else {
                containsTitleMatches.add(item);
            }
        }

        // 2. Query Exact Alias Matches (at most MAX_RESULTS)
        List<PublishedWikiArticleListItemDTO> aliasMatches =
                wikiArticleQueryPort.findPublishedArticlesByNormalizedAlias(normalizedQuery, MAX_RESULTS);

        boolean hasExactMatch = !exactTitleMatches.isEmpty() || !aliasMatches.isEmpty();

        // 3. Merge in locked ranking order with deduplication and cap at MAX_RESULTS:
        //    1. exact title
        //    2. exact alias
        //    3. prefix title
        //    4. contains title
        Set<UUID> seenArticleIds = new HashSet<>();
        List<WikiContextualLookupItemDTO> results = new ArrayList<>();

        // Rank 1: exact title
        for (PublishedWikiArticleListItemDTO item : exactTitleMatches) {
            if (seenArticleIds.add(item.id())) {
                results.add(new WikiContextualLookupItemDTO(
                        item.id(), item.title(), item.articleType(), item.slug(), item.summary(), null
                ));
                if (results.size() >= MAX_RESULTS) break;
            }
        }

        // Rank 2: exact alias
        if (results.size() < MAX_RESULTS) {
            for (PublishedWikiArticleListItemDTO item : aliasMatches) {
                if (seenArticleIds.add(item.id())) {
                    results.add(new WikiContextualLookupItemDTO(
                            item.id(), item.title(), item.articleType(), item.slug(), item.summary(), cleanedQuery
                    ));
                    if (results.size() >= MAX_RESULTS) break;
                }
            }
        }

        // Rank 3: prefix title
        if (results.size() < MAX_RESULTS) {
            for (PublishedWikiArticleListItemDTO item : prefixTitleMatches) {
                if (seenArticleIds.add(item.id())) {
                    results.add(new WikiContextualLookupItemDTO(
                            item.id(), item.title(), item.articleType(), item.slug(), item.summary(), null
                    ));
                    if (results.size() >= MAX_RESULTS) break;
                }
            }
        }

        // Rank 4: contains title
        if (results.size() < MAX_RESULTS) {
            for (PublishedWikiArticleListItemDTO item : containsTitleMatches) {
                if (seenArticleIds.add(item.id())) {
                    results.add(new WikiContextualLookupItemDTO(
                            item.id(), item.title(), item.articleType(), item.slug(), item.summary(), null
                    ));
                    if (results.size() >= MAX_RESULTS) break;
                }
            }
        }

        return new WikiContextualLookupResultDTO(cleanedQuery, hasExactMatch, results);
    }
}
