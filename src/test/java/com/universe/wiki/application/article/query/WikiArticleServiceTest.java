package com.universe.wiki.application.article.query;

import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
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
@DisplayName("WikiArticleService Unit Tests")
class WikiArticleServiceTest {

    @Mock
    private WikiArticleQueryPort queryPort;

    @InjectMocks
    private WikiArticleService wikiArticleService;

    private static final UUID ARTICLE_ID = UUID.randomUUID();

    @Test
    @DisplayName("Should return published article DTO when article is PUBLISHED")
    void shouldReturnPublishedArticleWhenExistsAndPublished() {
        PublishedWikiArticleDTO dto = new PublishedWikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Tóm tắt",
                "Nội dung",
                Instant.now(),
                Instant.now()
        );

        when(queryPort.findPublishedById(ARTICLE_ID)).thenReturn(Optional.of(dto));

        Optional<PublishedWikiArticleDTO> result = wikiArticleService.findPublishedById(ARTICLE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(ARTICLE_ID);
        assertThat(result.get().title()).isEqualTo("Trần Bình An");
    }

    @Test
    @DisplayName("Should return empty Optional when article is unpublished or nonexistent")
    void shouldReturnEmptyWhenUnpublishedOrNotFound() {
        when(queryPort.findPublishedById(ARTICLE_ID)).thenReturn(Optional.empty());

        Optional<PublishedWikiArticleDTO> result = wikiArticleService.findPublishedById(ARTICLE_ID);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should return empty Optional when article ID is null")
    void shouldReturnEmptyWhenArticleIdIsNull() {
        Optional<PublishedWikiArticleDTO> result = wikiArticleService.findPublishedById(null);

        assertThat(result).isEmpty();
    }
}
