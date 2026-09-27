package com.universe.wiki.application.appreciation;

import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Use case tra cứu tổng hợp đánh giá mức độ yêu thích cho một bài viết Wiki đơn lẻ.
 */
@Service
public class GetWikiAppreciationSummaryUseCase {

    private final WikiAppreciationQueryPort queryPort;

    public GetWikiAppreciationSummaryUseCase(WikiAppreciationQueryPort queryPort) {
        this.queryPort = Objects.requireNonNull(
                queryPort,
                "WikiAppreciationQueryPort không được để trống."
        );
    }

    public WikiAppreciationSummary execute(UUID wikiArticleId) {
        Objects.requireNonNull(wikiArticleId, "ID bài viết Wiki không được để trống.");
        return queryPort.findSummaryByWikiArticleId(wikiArticleId);
    }
}
