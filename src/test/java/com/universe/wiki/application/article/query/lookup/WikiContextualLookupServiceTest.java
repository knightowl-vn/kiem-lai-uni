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
        when(wikiArticleQueryPort.findPublishedArticlesByNormalizedAlias("trần bình", 5))
                .thenReturn(List.of());

        WikiContextualLookupResultDTO result = lookupService.lookupByTitle("trần bình");

        assertThat(result.query()).isEqualTo("trần bình");
        assertThat(result.hasExactMatch()).isFalse();
        assertThat(result.items()).hasSize(1);
    }

    @Test
    @DisplayName("Locked ranking: exact title -> exact alias -> prefix title -> contains title")
    void shouldRankResultsAccordingToLockedRules() {
        UUID exactTitleId = UUID.randomUUID();
        UUID exactAliasId = UUID.randomUUID();
        UUID prefixTitleId = UUID.randomUUID();
        UUID containsTitleId = UUID.randomUUID();

        PublishedWikiArticleListItemDTO exactTitle = new PublishedWikiArticleListItemDTO(
                exactTitleId, "Kiếm Tiên", "kiem-tien", "CHARACTER", "Bài viết chính", Instant.now(), Instant.now()
        );
        PublishedWikiArticleListItemDTO prefixTitle = new PublishedWikiArticleListItemDTO(
                prefixTitleId, "Kiếm Tiên Lâu", "kiem-tien-lau", "LOCATION", "Địa danh", Instant.now(), Instant.now()
        );
        PublishedWikiArticleListItemDTO containsTitle = new PublishedWikiArticleListItemDTO(
                containsTitleId, "Đại Kiếm Tiên", "dai-kiem-tien", "CHARACTER", "Nhân vật", Instant.now(), Instant.now()
        );

        PublishedWikiArticleListItemDTO aliasArticle = new PublishedWikiArticleListItemDTO(
                exactAliasId, "Trần Bình An", "tran-binh-an", "CHARACTER", "Có alias Kiếm Tiên", Instant.now(), Instant.now()
        );

        when(wikiArticleQueryPort.findPublishedContextualMatches("Kiếm Tiên", 5))
                .thenReturn(List.of(exactTitle, prefixTitle, containsTitle));
        when(wikiArticleQueryPort.findPublishedArticlesByNormalizedAlias("kiếm tiên", 5))
                .thenReturn(List.of(aliasArticle));

        WikiContextualLookupResultDTO result = lookupService.lookupByTitle("Kiếm Tiên");

        assertThat(result.query()).isEqualTo("Kiếm Tiên");
        assertThat(result.hasExactMatch()).isTrue();
        assertThat(result.items()).hasSize(4);

        // 1. exact title
        assertThat(result.items().get(0).id()).isEqualTo(exactTitleId);
        assertThat(result.items().get(0).title()).isEqualTo("Kiếm Tiên");
        assertThat(result.items().get(0).matchedAlias()).isNull();

        // 2. exact alias
        assertThat(result.items().get(1).id()).isEqualTo(exactAliasId);
        assertThat(result.items().get(1).title()).isEqualTo("Trần Bình An");
        assertThat(result.items().get(1).matchedAlias()).isEqualTo("Kiếm Tiên");

        // 3. prefix title
        assertThat(result.items().get(2).id()).isEqualTo(prefixTitleId);
        assertThat(result.items().get(2).title()).isEqualTo("Kiếm Tiên Lâu");
        assertThat(result.items().get(2).matchedAlias()).isNull();

        // 4. contains title
        assertThat(result.items().get(3).id()).isEqualTo(containsTitleId);
        assertThat(result.items().get(3).title()).isEqualTo("Đại Kiếm Tiên");
        assertThat(result.items().get(3).matchedAlias()).isNull();
    }

    @Test
    @DisplayName("Shared alias returns multiple articles under exact alias rank with deduplication")
    void shouldSupportSharedAliasAndDeduplicate() {
        UUID article1Id = UUID.randomUUID();
        UUID article2Id = UUID.randomUUID();

        PublishedWikiArticleListItemDTO article1 = new PublishedWikiArticleListItemDTO(
                article1Id, "Trần Bình An", "tran-binh-an", "CHARACTER", "Nhân vật 1", Instant.now(), Instant.now()
        );
        PublishedWikiArticleListItemDTO article2 = new PublishedWikiArticleListItemDTO(
                article2Id, "Tạ Triêu Nhan", "ta-trieu-nhan", "CHARACTER", "Nhân vật 2", Instant.now(), Instant.now()
        );

        // Title matches also contains article 1 as a contains match
        when(wikiArticleQueryPort.findPublishedContextualMatches("Kiếm Tiên", 5))
                .thenReturn(List.of(article1));
        when(wikiArticleQueryPort.findPublishedArticlesByNormalizedAlias("kiếm tiên", 5))
                .thenReturn(List.of(article1, article2));

        WikiContextualLookupResultDTO result = lookupService.lookupByTitle("Kiếm Tiên");

        assertThat(result.items()).hasSize(2);
        // Exact alias rank (Rank 2) takes priority and deduplicates article1
        assertThat(result.items().get(0).id()).isEqualTo(article1Id);
        assertThat(result.items().get(0).matchedAlias()).isEqualTo("Kiếm Tiên");
        assertThat(result.items().get(1).id()).isEqualTo(article2Id);
        assertThat(result.items().get(1).matchedAlias()).isEqualTo("Kiếm Tiên");
    }

    @Test
    @DisplayName("Caps final merged result at 5")
    void shouldCapMergedResultsAtFive() {
        List<PublishedWikiArticleListItemDTO> aliasItems = List.of(
                createMockItem("Item 1"),
                createMockItem("Item 2"),
                createMockItem("Item 3"),
                createMockItem("Item 4")
        );
        List<PublishedWikiArticleListItemDTO> titleItems = List.of(
                createMockItem("Prefix 1"),
                createMockItem("Prefix 2"),
                createMockItem("Prefix 3")
        );

        when(wikiArticleQueryPort.findPublishedContextualMatches("test", 5))
                .thenReturn(titleItems);
        when(wikiArticleQueryPort.findPublishedArticlesByNormalizedAlias("test", 5))
                .thenReturn(aliasItems);

        WikiContextualLookupResultDTO result = lookupService.lookupByTitle("test");

        assertThat(result.items()).hasSize(5);
    }

    private PublishedWikiArticleListItemDTO createMockItem(String title) {
        return new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), title, title.toLowerCase().replace(" ", "-"),
                "CHARACTER", "Tóm tắt", Instant.now(), Instant.now()
        );
    }
}
