package com.universe.novel.contracts.interfaces;

import com.universe.novel.contracts.dto.locator.NovelChapterLocatorResultDTO;

/**
 * Public Contract for locating published novel chapters via navigation search.
 */
public interface NovelChapterLocatorContract {

    int DEFAULT_LIMIT = 20;
    int MAX_LIMIT = 20;

    /**
     * Locates published novel chapters matching the query with an explicit limit (clamped between 1 and 20).
     *
     * @param query search query (chapter number or title text)
     * @param limit maximum number of results to return
     * @return search result DTO
     */
    NovelChapterLocatorResultDTO locateChapters(String query, int limit);

    /**
     * Locates published novel chapters matching the query using default limit of 20.
     *
     * @param query search query (chapter number or title text)
     * @return search result DTO
     */
    default NovelChapterLocatorResultDTO locateChapters(String query) {
        return locateChapters(query, DEFAULT_LIMIT);
    }
}
