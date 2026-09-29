package com.universe.wiki.contracts.interfaces;

import com.universe.wiki.contracts.dto.appreciation.WikiAppreciationSummaryDTO;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Public contract interface for resolving community appreciation summaries across bounded contexts.
 */
public interface WikiAppreciationContract {

    /**
     * Batch resolves appreciation summaries for a collection of Wiki article IDs.
     *
     * @param wikiArticleIds collection of target Wiki article IDs
     * @return map of article ID to its WikiAppreciationSummaryDTO
     */
    Map<UUID, WikiAppreciationSummaryDTO> findSummaries(Collection<UUID> wikiArticleIds);
}
