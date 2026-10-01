package com.universe.search.application;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorResultDTO;
import com.universe.novel.contracts.interfaces.NovelChapterLocatorContract;
import com.universe.search.contracts.dto.CommunityProfileSearchItemDTO;
import com.universe.search.contracts.dto.CommunityProfileSearchResultDTO;
import com.universe.search.contracts.dto.SearchAggregationResultDTO;
import com.universe.search.contracts.dto.SearchScope;
import com.universe.search.contracts.interfaces.SearchAggregationContract;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchResultDTO;
import com.universe.wiki.contracts.interfaces.WikiNavigationalSearchContract;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Use case orchestrating cross-context navigational search aggregation.
 * Delegates to WikiNavigationalSearchContract, NovelChapterLocatorContract,
 * and UserIdentityContract according to the requested SearchScope without cross-context ranking.
 */
@Service
public class SearchAggregationUseCase implements SearchAggregationContract {

    private static final int MAX_QUERY_LENGTH = 200;

    private final WikiNavigationalSearchContract wikiNavigationalSearchContract;
    private final NovelChapterLocatorContract novelChapterLocatorContract;
    private final UserIdentityContract userIdentityContract;

    public SearchAggregationUseCase(
            WikiNavigationalSearchContract wikiNavigationalSearchContract,
            NovelChapterLocatorContract novelChapterLocatorContract,
            UserIdentityContract userIdentityContract
    ) {
        this.wikiNavigationalSearchContract = Objects.requireNonNull(
                wikiNavigationalSearchContract,
                "WikiNavigationalSearchContract must not be null"
        );
        this.novelChapterLocatorContract = Objects.requireNonNull(
                novelChapterLocatorContract,
                "NovelChapterLocatorContract must not be null"
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract,
                "UserIdentityContract must not be null"
        );
    }

    @Override
    public SearchAggregationResultDTO aggregate(String query, SearchScope scope, int limit) {
        SearchScope effectiveScope = scope != null ? scope : SearchScope.ALL;
        String normalizedQuery = normalizeQuery(query);

        if (normalizedQuery.isEmpty()) {
            return new SearchAggregationResultDTO(
                    "",
                    effectiveScope,
                    emptyWikiResult(),
                    emptyNovelResult(),
                    emptyCommunityResult()
            );
        }

        int effectiveLimit = clampLimit(limit);

        WikiNavigationalSearchResultDTO wikiResult;
        NovelChapterLocatorResultDTO novelResult;
        CommunityProfileSearchResultDTO communityResult;

        switch (effectiveScope) {
            case WIKI -> {
                wikiResult = wikiNavigationalSearchContract.search(normalizedQuery, effectiveLimit);
                novelResult = emptyNovelResult();
                communityResult = emptyCommunityResult();
            }
            case NOVEL -> {
                wikiResult = emptyWikiResult();
                novelResult = novelChapterLocatorContract.locateChapters(normalizedQuery, effectiveLimit);
                communityResult = emptyCommunityResult();
            }
            case COMMUNITY -> {
                wikiResult = emptyWikiResult();
                novelResult = emptyNovelResult();
                communityResult = searchCommunityProfiles(normalizedQuery, effectiveLimit);
            }
            case ALL -> {
                wikiResult = wikiNavigationalSearchContract.search(normalizedQuery, effectiveLimit);
                novelResult = novelChapterLocatorContract.locateChapters(normalizedQuery, effectiveLimit);
                communityResult = searchCommunityProfiles(normalizedQuery, effectiveLimit);
            }
            default -> throw new IllegalStateException("Unexpected scope: " + effectiveScope);
        }

        return new SearchAggregationResultDTO(
                normalizedQuery,
                effectiveScope,
                wikiResult,
                novelResult,
                communityResult
        );
    }

    private CommunityProfileSearchResultDTO searchCommunityProfiles(String query, int limit) {
        List<UserPublicProfileDTO> users = Objects.requireNonNull(
                userIdentityContract.searchPublicUsers(query, limit),
                "community must not be null"
        );
        if (users.isEmpty()) {
            return emptyCommunityResult();
        }

        List<CommunityProfileSearchItemDTO> items = users.stream()
                .filter(Objects::nonNull)
                .map(u -> new CommunityProfileSearchItemDTO(
                        u.displayName(),
                        u.publicHandle(),
                        u.avatarUrl(),
                        "/community/@" + u.publicHandle()
                ))
                .toList();

        return new CommunityProfileSearchResultDTO(query, items);
    }

    private String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        String trimmed = rawQuery.trim().replaceAll("\\s+", " ");
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            trimmed = trimmed.substring(0, MAX_QUERY_LENGTH).trim();
        }
        return trimmed;
    }

    private int clampLimit(int limit) {
        if (limit <= 0 || limit > MAX_PER_GROUP_LIMIT) {
            return DEFAULT_PER_GROUP_LIMIT;
        }
        return limit;
    }

    private WikiNavigationalSearchResultDTO emptyWikiResult() {
        return new WikiNavigationalSearchResultDTO("", List.of());
    }

    private NovelChapterLocatorResultDTO emptyNovelResult() {
        return new NovelChapterLocatorResultDTO("", null, List.of());
    }

    private CommunityProfileSearchResultDTO emptyCommunityResult() {
        return new CommunityProfileSearchResultDTO("", List.of());
    }
}
