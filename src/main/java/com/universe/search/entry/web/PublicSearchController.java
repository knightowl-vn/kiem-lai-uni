package com.universe.search.entry.web;

import com.universe.search.contracts.dto.SearchAggregationResultDTO;
import com.universe.search.contracts.dto.SearchScope;
import com.universe.search.contracts.interfaces.SearchAggregationContract;
import com.universe.wiki.contracts.dto.appreciation.WikiAppreciationSummaryDTO;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchItemDTO;
import com.universe.wiki.contracts.interfaces.WikiAppreciationContract;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Public controller providing the navigational search route GET /search.
 * Integrates search aggregation across Wiki and Novel bounded contexts.
 */
@Controller
public class PublicSearchController {

    private final SearchAggregationContract searchAggregationContract;
    private final WikiAppreciationContract wikiAppreciationContract;

    public PublicSearchController(
            SearchAggregationContract searchAggregationContract,
            WikiAppreciationContract wikiAppreciationContract
    ) {
        this.searchAggregationContract = Objects.requireNonNull(
                searchAggregationContract,
                "SearchAggregationContract must not be null"
        );
        this.wikiAppreciationContract = Objects.requireNonNull(
                wikiAppreciationContract,
                "WikiAppreciationContract must not be null"
        );
    }

    @GetMapping("/search")
    public String search(
            @RequestParam(name = "q", required = false) String rawQuery,
            @RequestParam(name = "scope", required = false) String rawScope,
            Model model
    ) {
        SearchScope scope = SearchScope.fromNullable(rawScope);

        SearchAggregationResultDTO searchResult =
                searchAggregationContract.aggregate(
                        rawQuery,
                        scope,
                        SearchAggregationContract.DEFAULT_PER_GROUP_LIMIT
                );

        String normalizedQuery = searchResult.query();
        String effectiveScopeLower = scope.name().toLowerCase(Locale.ROOT);

        List<UUID> eligibleWikiArticleIds = searchResult.wiki().items().stream()
                .filter(WikiNavigationalSearchItemDTO::isAppreciationEligible)
                .map(WikiNavigationalSearchItemDTO::id)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<UUID, WikiAppreciationSummaryDTO> appreciationSummaries = eligibleWikiArticleIds.isEmpty()
                ? Collections.emptyMap()
                : wikiAppreciationContract.findSummaries(eligibleWikiArticleIds);

        model.addAttribute("query", normalizedQuery);
        model.addAttribute("scope", effectiveScopeLower);
        model.addAttribute("searchResult", searchResult);
        model.addAttribute("appreciationSummaries", appreciationSummaries);
        model.addAttribute("pageTitle", normalizedQuery.isBlank()
                ? "Tìm kiếm | Kiếm Lai Universe"
                : "Tìm kiếm: " + normalizedQuery + " | Kiếm Lai Universe");
        model.addAttribute("activeNav", "search");
        model.addAttribute("navbarSearchScope", effectiveScopeLower);
        model.addAttribute("navbarSearchQuery", normalizedQuery);

        return "search/index";
    }
}
