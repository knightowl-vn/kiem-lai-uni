package com.universe.novel.application.chapter.reference;

import com.universe.novel.application.ports.WikiContextualLookupPort;
import com.universe.novel.application.wiki.lookup.WikiContextualLookupItem;
import com.universe.novel.application.wiki.lookup.WikiContextualLookupResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchTargetWikiArticlesUseCase Unit Tests")
class SearchTargetWikiArticlesUseCaseTest {

    private static final UUID ARTICLE_1_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ARTICLE_2_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private WikiContextualLookupPort wikiContextualLookupPort;

    private SearchTargetWikiArticlesUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SearchTargetWikiArticlesUseCase(wikiContextualLookupPort);
    }

    @Test
    @DisplayName("Returns empty result for null, blank, or empty query without calling port")
    void shouldReturnEmptyForBlankQuery() {
        TargetWikiArticleSearchResultDTO nullResult = useCase.execute(null);
        assertThat(nullResult.query()).isEmpty();
        assertThat(nullResult.items()).isEmpty();

        TargetWikiArticleSearchResultDTO emptyResult = useCase.execute("");
        assertThat(emptyResult.query()).isEmpty();
        assertThat(emptyResult.items()).isEmpty();

        TargetWikiArticleSearchResultDTO blankResult = useCase.execute("   ");
        assertThat(blankResult.query()).isEmpty();
        assertThat(blankResult.items()).isEmpty();

        verifyNoInteractions(wikiContextualLookupPort);
    }

    @Test
    @DisplayName("Returns empty result for oversized query (> 100 chars) without calling port")
    void shouldReturnEmptyForOversizedQuery() {
        String oversized = "a".repeat(101);

        TargetWikiArticleSearchResultDTO result = useCase.execute(oversized);

        assertThat(result.query()).isEqualTo(oversized);
        assertThat(result.items()).isEmpty();
        verifyNoInteractions(wikiContextualLookupPort);
    }

    @Test
    @DisplayName("Trims query, calls port, and maps items preserving title, type, slug, summary, and matchedAlias")
    void shouldCallPortAndMapPublishedItemsCorrectly() {
        String query = "  Trần Bình An  ";
        String normalizedQuery = "Trần Bình An";

        WikiContextualLookupItem item1 = new WikiContextualLookupItem(
                ARTICLE_1_ID,
                "Trần Bình An",
                "CHARACTER",
                "tran-binh-an",
                "Nhân vật chính của Kiếm Lai.",
                null
        );

        WikiContextualLookupItem item2 = new WikiContextualLookupItem(
                ARTICLE_2_ID,
                "Bình An",
                "CHARACTER",
                "binh-an",
                "Tên gọi khác của nhân vật.",
                "Trần Bình An"
        );

        WikiContextualLookupResult portResult = new WikiContextualLookupResult(
                normalizedQuery,
                true,
                List.of(item1, item2)
        );

        when(wikiContextualLookupPort.lookup(normalizedQuery)).thenReturn(portResult);

        TargetWikiArticleSearchResultDTO result = useCase.execute(query);

        verify(wikiContextualLookupPort).lookup(normalizedQuery);
        assertThat(result.query()).isEqualTo(normalizedQuery);
        assertThat(result.items()).hasSize(2);

        TargetWikiArticleSearchItemDTO mappedItem1 = result.items().get(0);
        assertThat(mappedItem1.id()).isEqualTo(ARTICLE_1_ID);
        assertThat(mappedItem1.title()).isEqualTo("Trần Bình An");
        assertThat(mappedItem1.articleType()).isEqualTo("CHARACTER");
        assertThat(mappedItem1.slug()).isEqualTo("tran-binh-an");
        assertThat(mappedItem1.summary()).isEqualTo("Nhân vật chính của Kiếm Lai.");
        assertThat(mappedItem1.matchedAlias()).isNull();

        TargetWikiArticleSearchItemDTO mappedItem2 = result.items().get(1);
        assertThat(mappedItem2.id()).isEqualTo(ARTICLE_2_ID);
        assertThat(mappedItem2.title()).isEqualTo("Bình An");
        assertThat(mappedItem2.articleType()).isEqualTo("CHARACTER");
        assertThat(mappedItem2.slug()).isEqualTo("binh-an");
        assertThat(mappedItem2.summary()).isEqualTo("Tên gọi khác của nhân vật.");
        assertThat(mappedItem2.matchedAlias()).isEqualTo("Trần Bình An");
    }

    @Test
    @DisplayName("Handles null or empty result from port gracefully")
    void shouldHandleEmptyPortResultGracefully() {
        when(wikiContextualLookupPort.lookup("Không Tồn Tại")).thenReturn(null);

        TargetWikiArticleSearchResultDTO result = useCase.execute("Không Tồn Tại");

        assertThat(result.query()).isEqualTo("Không Tồn Tại");
        assertThat(result.items()).isEmpty();
    }

    @Test
    @DisplayName("Falls back to normalizedQuery when result.query() is null")
    void shouldFallBackToNormalizedQueryWhenResultQueryIsNull() {
        String rawQuery = "  Tiểu Phu Tử  ";
        String normalizedQuery = "Tiểu Phu Tử";

        WikiContextualLookupItem item = new WikiContextualLookupItem(
                ARTICLE_1_ID,
                "Trần Bình An",
                "CHARACTER",
                "tran-binh-an",
                "Nhân vật chính của Kiếm Lai.",
                "Tiểu Phu Tử"
        );

        // Port returns a result whose query() is null
        WikiContextualLookupResult portResult = new WikiContextualLookupResult(
                null,
                false,
                List.of(item)
        );

        when(wikiContextualLookupPort.lookup(normalizedQuery)).thenReturn(portResult);

        TargetWikiArticleSearchResultDTO result = useCase.execute(rawQuery);

        verify(wikiContextualLookupPort).lookup(normalizedQuery);
        assertThat(result.query()).isEqualTo(normalizedQuery);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).matchedAlias()).isEqualTo("Tiểu Phu Tử");
    }
}
