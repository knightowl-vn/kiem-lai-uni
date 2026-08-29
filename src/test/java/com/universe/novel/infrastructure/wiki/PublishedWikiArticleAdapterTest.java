package com.universe.novel.infrastructure.wiki;

import com.universe.novel.application.chapter.reference.PublishedWikiArticleSummary;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.interfaces.WikiArticleContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublishedWikiArticleAdapter Unit Tests")
class PublishedWikiArticleAdapterTest {

    @Mock
    private WikiArticleContract wikiArticleContract;

    @InjectMocks
    private PublishedWikiArticleAdapter adapter;

    private static final UUID ARTICLE_ID = UUID.randomUUID();

    @Test
    @DisplayName("Should map PublishedWikiArticleDTO to PublishedWikiArticleSummary correctly")
    void shouldMapDtoToSummary() {
        PublishedWikiArticleDTO dto = new PublishedWikiArticleDTO(
                ARTICLE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "BOOK",
                "Tóm tắt chi tiết",
                "Nội dung đầy đủ",
                Instant.now(),
                Instant.now()
        );

        when(wikiArticleContract.findPublishedById(ARTICLE_ID)).thenReturn(Optional.of(dto));

        Optional<PublishedWikiArticleSummary> summaryOpt = adapter.findPublishedById(ARTICLE_ID);

        assertThat(summaryOpt).isPresent();
        PublishedWikiArticleSummary summary = summaryOpt.get();
        assertThat(summary.id()).isEqualTo(ARTICLE_ID);
        assertThat(summary.title()).isEqualTo("Kiếm Lai");
        assertThat(summary.slug()).isEqualTo("kiem-lai");
        assertThat(summary.articleType()).isEqualTo("BOOK");
    }

    @Test
    @DisplayName("Should return empty when article contract returns empty")
    void shouldReturnEmptyWhenNotFound() {
        when(wikiArticleContract.findPublishedById(ARTICLE_ID)).thenReturn(Optional.empty());

        Optional<PublishedWikiArticleSummary> summaryOpt = adapter.findPublishedById(ARTICLE_ID);

        assertThat(summaryOpt).isEmpty();
    }

    @Test
    @DisplayName("Should return empty when articleId is null")
    void shouldReturnEmptyWhenNull() {
        Optional<PublishedWikiArticleSummary> summaryOpt = adapter.findPublishedById(null);

        assertThat(summaryOpt).isEmpty();
    }
}
