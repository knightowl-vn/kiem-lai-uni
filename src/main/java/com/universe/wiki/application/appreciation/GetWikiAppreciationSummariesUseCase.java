package com.universe.wiki.application.appreciation;

import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.contracts.dto.appreciation.WikiAppreciationSummaryDTO;
import com.universe.wiki.contracts.interfaces.WikiAppreciationContract;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case tra cứu hàng loạt tổng hợp đánh giá mức độ yêu thích cho tập hợp bài viết Wiki.
 */
@Service
public class GetWikiAppreciationSummariesUseCase implements WikiAppreciationContract {

    private final WikiAppreciationQueryPort queryPort;

    public GetWikiAppreciationSummariesUseCase(WikiAppreciationQueryPort queryPort) {
        this.queryPort = Objects.requireNonNull(
                queryPort,
                "WikiAppreciationQueryPort không được để trống."
        );
    }

    public Map<UUID, WikiAppreciationSummary> execute(Collection<UUID> wikiArticleIds) {
        Objects.requireNonNull(wikiArticleIds, "Danh sách ID bài viết Wiki không được để trống.");
        return queryPort.findSummariesByWikiArticleIds(wikiArticleIds);
    }

    @Override
    public Map<UUID, WikiAppreciationSummaryDTO> findSummaries(Collection<UUID> wikiArticleIds) {
        if (wikiArticleIds == null || wikiArticleIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<UUID, WikiAppreciationSummary> domainSummaries = execute(wikiArticleIds);
        Map<UUID, WikiAppreciationSummaryDTO> dtos = new HashMap<>(domainSummaries.size());
        domainSummaries.forEach((id, summary) -> {
            if (summary != null) {
                dtos.put(id, new WikiAppreciationSummaryDTO(
                        summary.wikiArticleId(),
                        summary.average(),
                        summary.count()
                ));
            }
        });
        return Collections.unmodifiableMap(dtos);
    }
}
