package com.universe.search.entry.web;

import com.universe.novel.contracts.dto.locator.NovelChapterLocatorItemDTO;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorResultDTO;
import com.universe.search.contracts.dto.SearchAggregationResultDTO;
import com.universe.search.contracts.dto.SearchScope;
import com.universe.search.contracts.interfaces.SearchAggregationContract;
import com.universe.wiki.contracts.dto.appreciation.WikiAppreciationSummaryDTO;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchItemDTO;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchResultDTO;
import com.universe.wiki.contracts.interfaces.WikiAppreciationContract;
import com.universe.wiki.domain.article.ArticleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicSearchController — Web Controller Tests")
class PublicSearchControllerTest {

    @Mock
    private SearchAggregationContract searchAggregationContract;

    @Mock
    private WikiAppreciationContract wikiAppreciationContract;

    private PublicSearchController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicSearchController(searchAggregationContract, wikiAppreciationContract);
    }

    @Test
    @DisplayName("GET /search khi scope rỗng hoặc null -> fallback SearchScope.ALL và trả về view search/index")
    void searchWithMissingScopeFallsBackToAll() {
        UUID articleId = UUID.randomUUID();
        WikiNavigationalSearchItemDTO wikiItem = new WikiNavigationalSearchItemDTO(
                articleId,
                ArticleType.CHARACTER,
                "Trần Bình An",
                "tran-binh-an",
                "/wiki/character/tran-binh-an",
                null
        );
        SearchAggregationResultDTO mockResult = new SearchAggregationResultDTO(
                "tran binh an",
                SearchScope.ALL,
                new WikiNavigationalSearchResultDTO("tran binh an", List.of(wikiItem)),
                new NovelChapterLocatorResultDTO("tran binh an", null, List.of())
        );

        when(searchAggregationContract.aggregate(eq("tran binh an"), eq(SearchScope.ALL), eq(20)))
                .thenReturn(mockResult);
        WikiAppreciationSummaryDTO summary = new WikiAppreciationSummaryDTO(articleId, BigDecimal.valueOf(4.8), 12L);
        when(wikiAppreciationContract.findSummaries(List.of(articleId)))
                .thenReturn(Map.of(articleId, summary));

        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.search("tran binh an", null, model);

        assertThat(view).isEqualTo("search/index");
        assertThat(model.get("query")).isEqualTo("tran binh an");
        assertThat(model.get("scope")).isEqualTo("all");
        assertThat(model.get("searchResult")).isEqualTo(mockResult);
        assertThat(model.get("appreciationSummaries")).isEqualTo(Map.of(articleId, summary));
        assertThat(model.get("pageTitle")).isEqualTo("Tìm kiếm: tran binh an | Kiếm Lai Universe");
        assertThat(model.get("activeNav")).isEqualTo("search");
        assertThat(model.get("navbarSearchScope")).isEqualTo("all");
        assertThat(model.get("navbarSearchQuery")).isEqualTo("tran binh an");

        verify(searchAggregationContract).aggregate("tran binh an", SearchScope.ALL, 20);
        verify(wikiAppreciationContract, times(1)).findSummaries(List.of(articleId));
    }

    @Test
    @DisplayName("GET /search khi scope='all' -> SearchScope.ALL và gọi appreciation contract 1 lần cho danh sách Wiki ID")
    void searchWithScopeAll() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        WikiNavigationalSearchItemDTO item1 = new WikiNavigationalSearchItemDTO(id1, ArticleType.CHARACTER, "A", "a", "/wiki/character/a", null);
        WikiNavigationalSearchItemDTO item2 = new WikiNavigationalSearchItemDTO(id2, ArticleType.FACTION, "B", "b", "/wiki/faction/b", null);

        SearchAggregationResultDTO mockResult = new SearchAggregationResultDTO(
                "kiem lai",
                SearchScope.ALL,
                new WikiNavigationalSearchResultDTO("kiem lai", List.of(item1, item2)),
                new NovelChapterLocatorResultDTO("kiem lai", null, List.of())
        );

        when(searchAggregationContract.aggregate(eq("kiem lai"), eq(SearchScope.ALL), eq(20)))
                .thenReturn(mockResult);
        when(wikiAppreciationContract.findSummaries(List.of(id1, id2)))
                .thenReturn(Map.of(id1, WikiAppreciationSummaryDTO.empty(id1), id2, WikiAppreciationSummaryDTO.empty(id2)));

        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.search("kiem lai", "all", model);

        assertThat(view).isEqualTo("search/index");
        assertThat(model.get("scope")).isEqualTo("all");
        assertThat(model.get("navbarSearchScope")).isEqualTo("all");
        assertThat(model.get("navbarSearchQuery")).isEqualTo("kiem lai");

        verify(searchAggregationContract).aggregate("kiem lai", SearchScope.ALL, 20);
        verify(wikiAppreciationContract, times(1)).findSummaries(List.of(id1, id2));
    }

    @Test
    @DisplayName("GET /search khi scope='wiki' hoặc chữ hoa chữ thường 'WiKi' -> SearchScope.WIKI")
    void searchWithScopeWikiCaseInsensitive() {
        SearchAggregationResultDTO mockResult = new SearchAggregationResultDTO(
                "dong thuy",
                SearchScope.WIKI,
                new WikiNavigationalSearchResultDTO("dong thuy", List.of()),
                new NovelChapterLocatorResultDTO("", null, List.of())
        );

        when(searchAggregationContract.aggregate(eq("dong thuy"), eq(SearchScope.WIKI), eq(20)))
                .thenReturn(mockResult);

        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.search("dong thuy", "WiKi", model);

        assertThat(view).isEqualTo("search/index");
        assertThat(model.get("scope")).isEqualTo("wiki");
        assertThat(model.get("navbarSearchScope")).isEqualTo("wiki");
        assertThat(model.get("navbarSearchQuery")).isEqualTo("dong thuy");
        assertThat(model.get("appreciationSummaries")).isEqualTo(Map.of());

        verify(searchAggregationContract).aggregate("dong thuy", SearchScope.WIKI, 20);
        verifyNoInteractions(wikiAppreciationContract);
    }

    @Test
    @DisplayName("GET /search khi scope='novel' -> SearchScope.NOVEL và KHÔNG gọi appreciation contract")
    void searchWithScopeNovel() {
        NovelChapterLocatorItemDTO chapterItem = new NovelChapterLocatorItemDTO(
                UUID.randomUUID(),
                10,
                "Khởi Đầu",
                "chuong-10-khoi-dau",
                1,
                "Quyển 1"
        );

        SearchAggregationResultDTO mockResult = new SearchAggregationResultDTO(
                "chuong 10",
                SearchScope.NOVEL,
                new WikiNavigationalSearchResultDTO("", List.of()),
                new NovelChapterLocatorResultDTO("chuong 10", 10, List.of(chapterItem))
        );

        when(searchAggregationContract.aggregate(eq("chuong 10"), eq(SearchScope.NOVEL), eq(20)))
                .thenReturn(mockResult);

        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.search("chuong 10", "novel", model);

        assertThat(view).isEqualTo("search/index");
        assertThat(model.get("scope")).isEqualTo("novel");
        assertThat(model.get("query")).isEqualTo("chuong 10");
        assertThat(model.get("navbarSearchScope")).isEqualTo("novel");
        assertThat(model.get("navbarSearchQuery")).isEqualTo("chuong 10");
        assertThat(model.get("appreciationSummaries")).isEqualTo(Map.of());

        verify(searchAggregationContract).aggregate("chuong 10", SearchScope.NOVEL, 20);
        verifyNoInteractions(wikiAppreciationContract);
    }

    @Test
    @DisplayName("GET /search khi scope không hợp lệ (ví dụ 'invalid-scope') -> fallback SearchScope.ALL")
    void searchWithInvalidScopeFallsBackToAll() {
        SearchAggregationResultDTO mockResult = new SearchAggregationResultDTO(
                "test",
                SearchScope.ALL,
                new WikiNavigationalSearchResultDTO("test", List.of()),
                new NovelChapterLocatorResultDTO("test", null, List.of())
        );

        when(searchAggregationContract.aggregate(eq("test"), eq(SearchScope.ALL), eq(20)))
                .thenReturn(mockResult);

        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.search("test", "invalid-scope", model);

        assertThat(view).isEqualTo("search/index");
        assertThat(model.get("scope")).isEqualTo("all");
        assertThat(model.get("navbarSearchScope")).isEqualTo("all");
        assertThat(model.get("navbarSearchQuery")).isEqualTo("test");
        assertThat(model.get("appreciationSummaries")).isEqualTo(Map.of());

        verify(searchAggregationContract).aggregate("test", SearchScope.ALL, 20);
        verifyNoInteractions(wikiAppreciationContract);
    }

    @Test
    @DisplayName("GET /search khi query rỗng/null -> ủy quyền cho Aggregator, trả về model blank và KHÔNG gọi appreciation contract")
    void searchWithBlankQuery() {
        SearchAggregationResultDTO mockResult = new SearchAggregationResultDTO(
                "",
                SearchScope.ALL,
                new WikiNavigationalSearchResultDTO("", List.of()),
                new NovelChapterLocatorResultDTO("", null, List.of())
        );

        when(searchAggregationContract.aggregate(eq(null), eq(SearchScope.ALL), eq(20)))
                .thenReturn(mockResult);

        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.search(null, null, model);

        assertThat(view).isEqualTo("search/index");
        assertThat(model.get("query")).isEqualTo("");
        assertThat(model.get("pageTitle")).isEqualTo("Tìm kiếm | Kiếm Lai Universe");
        assertThat(model.get("scope")).isEqualTo("all");
        assertThat(model.get("navbarSearchScope")).isEqualTo("all");
        assertThat(model.get("navbarSearchQuery")).isEqualTo("");
        assertThat(model.get("appreciationSummaries")).isEqualTo(Map.of());

        verify(searchAggregationContract).aggregate(null, SearchScope.ALL, 20);
        verifyNoInteractions(wikiAppreciationContract);
    }

    @Test
    @DisplayName("GET /search sử dụng normalized query từ kết quả D làm query và navbarSearchQuery trong model (rawQuery không được lộ)")
    void searchUsesNormalizedQueryFromAggregationResult() {
        SearchAggregationResultDTO mockResult = new SearchAggregationResultDTO(
                "tran binh an",
                SearchScope.ALL,
                new WikiNavigationalSearchResultDTO("tran binh an", List.of()),
                new NovelChapterLocatorResultDTO("tran binh an", null, List.of())
        );

        when(searchAggregationContract.aggregate(eq("   tran    binh   an   "), eq(SearchScope.ALL), eq(20)))
                .thenReturn(mockResult);

        ExtendedModelMap model = new ExtendedModelMap();
        controller.search("   tran    binh   an   ", "all", model);

        assertThat(model.get("query")).isEqualTo("tran binh an");
        assertThat(model.get("navbarSearchQuery")).isEqualTo("tran binh an");
        assertThat(model.get("navbarSearchQuery")).isNotEqualTo("   tran    binh   an   ");
    }

    @Test
    @DisplayName("GET /search chỉ thu thập appreciation cho các bài viết thuộc loại CHARACTER hoặc FACTION và loại trừ ID trùng lặp")
    @SuppressWarnings("unchecked")
    void searchFiltersAppreciationEligibilityAndDeduplicatesIds() {
        UUID charId1 = UUID.randomUUID();
        UUID charId2 = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        WikiNavigationalSearchItemDTO item1 = new WikiNavigationalSearchItemDTO(charId1, ArticleType.CHARACTER, "Char 1", "c1", "/wiki/character/c1", null);
        WikiNavigationalSearchItemDTO item2 = new WikiNavigationalSearchItemDTO(itemId, ArticleType.ITEM, "Item 1", "i1", "/wiki/item/i1", null);
        WikiNavigationalSearchItemDTO item3 = new WikiNavigationalSearchItemDTO(charId2, ArticleType.FACTION, "Faction 1", "f1", "/wiki/faction/f1", null);
        WikiNavigationalSearchItemDTO item4 = new WikiNavigationalSearchItemDTO(locationId, ArticleType.LOCATION, "Loc 1", "l1", "/wiki/location/l1", null);
        WikiNavigationalSearchItemDTO item5 = new WikiNavigationalSearchItemDTO(charId1, ArticleType.CHARACTER, "Char 1 dup", "c1", "/wiki/character/c1", "alias");

        SearchAggregationResultDTO mockResult = new SearchAggregationResultDTO(
                "test",
                SearchScope.WIKI,
                new WikiNavigationalSearchResultDTO("test", List.of(item1, item2, item3, item4, item5)),
                new NovelChapterLocatorResultDTO("", null, List.of())
        );

        when(searchAggregationContract.aggregate(eq("test"), eq(SearchScope.WIKI), eq(20)))
                .thenReturn(mockResult);
        when(wikiAppreciationContract.findSummaries(anyCollection()))
                .thenReturn(Map.of(charId1, WikiAppreciationSummaryDTO.empty(charId1), charId2, WikiAppreciationSummaryDTO.empty(charId2)));

        ExtendedModelMap model = new ExtendedModelMap();
        controller.search("test", "wiki", model);

        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(wikiAppreciationContract, times(1)).findSummaries(captor.capture());

        Collection<UUID> queriedIds = captor.getValue();
        assertThat(queriedIds).containsExactly(charId1, charId2);
        assertThat(queriedIds).doesNotContain(itemId, locationId);
    }
}
