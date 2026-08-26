package com.universe.wiki.application.article.query.lookup;

import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.WikiContextualLookupResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiContextualLookupServiceTest {

    @Mock
    private WikiArticleQueryPort wikiArticleQueryPort;

    private WikiContextualLookupService lookupService;

    @BeforeEach
    void setUp() {
        lookupService = new WikiContextualLookupService(wikiArticleQueryPort);
    }

    @Test
    @DisplayName("Returns empty result when query is null")
    void shouldReturnEmptyWhenQueryIsNull() {
        WikiContextualLookupResultDTO result = lookupService.lookupByTitle(null);

        assertThat(result.query()).isEmpty();
        assertThat(result.hasExactMatch()).isFalse();
        assertThat(result.items()).isEmpty();
        verify(wikiArticleQueryPort, never()).findPublishedContextualMatches(anyString(), anyInt());
    }

    @Test
    @DisplayName("Returns empty result when query is blank")
    void shouldReturnEmptyWhenQueryIsBlank() {
        WikiContextualLookupResultDTO result = lookupService.lookupByTitle("   ");

        assertThat(result.query()).isEmpty();
        assertThat(result.hasExactMatch()).isFalse();
        assertThat(result.items()).isEmpty();
        verify(wikiArticleQueryPort, never()).findPublishedContextualMatches(anyString(), anyInt());
    }

    @Test
    @DisplayName("Returns empty result when query is longer than 100 characters without invoking query port")
    void shouldReturnEmptyWhenQueryIsLongerThan100Characters() {
        String longQuery = "a".repeat(101);

        WikiContextualLookupResultDTO result = lookupService.lookupByTitle(longQuery);

        assertThat(result.query()).isEqualTo(longQuery);
        assertThat(result.hasExactMatch()).isFalse();
        assertThat(result.items()).isEmpty();
        verify(wikiArticleQueryPort, never()).findPublishedContextualMatches(anyString(), anyInt());
    }

    @Test
    @DisplayName("Performs lookup when query length is exactly 100 characters")
    void shouldPerformLookupWhenQueryLengthIs100() {
        String query100 = "a".repeat(100);

        when(wikiArticleQueryPort.findPublishedContextualMatches(eq(query100), eq(5)))
                .thenReturn(List.of());

        WikiContextualLookupResultDTO result = lookupService.lookupByTitle(query100);

        assertThat(result.query()).isEqualTo(query100);
        assertThat(result.hasExactMatch()).isFalse();
        assertThat(result.items()).isEmpty();
        verify(wikiArticleQueryPort).findPublishedContextualMatches(query100, 5);
    }

    @Test
    @DisplayName("Returns hasExactMatch=true when first result matches query case-insensitively")
    void shouldReturnExactMatchWhenFirstItemMatches() {
        UUID id = UUID.randomUUID();
        PublishedWikiArticleListItemDTO item = new PublishedWikiArticleListItemDTO(
                id,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Nhân vật chính",
                Instant.now(),
                Instant.now()
        );

        when(wikiArticleQueryPort.findPublishedContextualMatches("trần bình an", 5))
                .thenReturn(List.of(item));

        WikiContextualLookupResultDTO result = lookupService.lookupByTitle("  trần bình an  ");

        assertThat(result.query()).isEqualTo("trần bình an");
        assertThat(result.hasExactMatch()).isTrue();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(id);
        assertThat(result.items().get(0).title()).isEqualTo("Trần Bình An");
        assertThat(result.items().get(0).articleType()).isEqualTo("CHARACTER");
        assertThat(result.items().get(0).slug()).isEqualTo("tran-binh-an");
        assertThat(result.items().get(0).summary()).isEqualTo("Nhân vật chính");
    }

    @Test
    @DisplayName("Returns hasExactMatch=false when first result is a prefix or partial match")
    void shouldReturnNonExactMatchWhenFirstItemDoesNotMatchExactly() {
        UUID id = UUID.randomUUID();
        PublishedWikiArticleListItemDTO item = new PublishedWikiArticleListItemDTO(
                id,
                "Trần Bình An (Nhất Khí)",
                "tran-binh-an-nhat-khi",
                "CHARACTER",
                "Nhân vật phân thân",
                Instant.now(),
                Instant.now()
        );

        when(wikiArticleQueryPort.findPublishedContextualMatches("trần bình", 5))
                .thenReturn(List.of(item));

        WikiContextualLookupResultDTO result = lookupService.lookupByTitle("trần bình");

        assertThat(result.query()).isEqualTo("trần bình");
        assertThat(result.hasExactMatch()).isFalse();
        assertThat(result.items()).hasSize(1);
    }
}
