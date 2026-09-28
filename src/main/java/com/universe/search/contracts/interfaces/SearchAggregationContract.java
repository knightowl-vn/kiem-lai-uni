package com.universe.search.contracts.interfaces;

import com.universe.search.contracts.dto.SearchAggregationResultDTO;
import com.universe.search.contracts.dto.SearchScope;

/**
 * Public Contract for cross-context navigational search aggregation.
 */
public interface SearchAggregationContract {

    int DEFAULT_PER_GROUP_LIMIT = 20;
    int MAX_PER_GROUP_LIMIT = 20;

    /**
     * Aggregates navigational search results across bounded contexts.
     *
     * @param query raw search string entered by user
     * @param scope search target scope (ALL, WIKI, NOVEL)
     * @param limit maximum results to return per bounded-context group (1..20)
     * @return grouped search result DTO
     */
    SearchAggregationResultDTO aggregate(String query, SearchScope scope, int limit);

    /**
     * Aggregates navigational search results with default per-group limit (20).
     *
     * @param query raw search string entered by user
     * @param scope search target scope (ALL, WIKI, NOVEL)
     * @return grouped search result DTO
     */
    default SearchAggregationResultDTO aggregate(String query, SearchScope scope) {
        return aggregate(query, scope, DEFAULT_PER_GROUP_LIMIT);
    }

    /**
     * Aggregates navigational search results with default scope (ALL) and default per-group limit (20).
     *
     * @param query raw search string entered by user
     * @return grouped search result DTO
     */
    default SearchAggregationResultDTO aggregate(String query) {
        return aggregate(query, SearchScope.ALL, DEFAULT_PER_GROUP_LIMIT);
    }
}
