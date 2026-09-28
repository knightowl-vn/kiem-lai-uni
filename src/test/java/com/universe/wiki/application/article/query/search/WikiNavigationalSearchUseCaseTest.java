package com.universe.wiki.application.article.query.search;

import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchItemDTO;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchResultDTO;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import com.universe.wiki.domain.article.ArticleType;
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
class WikiNavigationalSearchUseCaseTest {

    @Mock
    private WikiArticleQueryPort wikiArticleQueryPort;

    private ArticleTypePathMapper articleTypePathMapper;
    private WikiNavigationalSearchUseCase useCase;

    @BeforeEach
    void setUp() {
        articleTypePathMapper = new ArticleTypePathMapper();
        useCase = new WikiNavigationalSearchUseCase(wikiArticleQueryPort, articleTypePathMapper);
    }

    @Test
    @DisplayName("Returns empty result without DB query when search query is null")
    void shouldReturnEmptyWhenQueryIsNull() {
        WikiNavigationalSearchResultDTO result = useCase.search(null, 20);

        assertThat(result.query()).isEmpty();
        assertThat(result.items()).isEmpty();
        verify(wikiArticleQueryPort, never()).findPublishedTitleSearchCandidates(anyString(), anyString(), anyInt());
        verify(wikiArticleQueryPort, never()).findPublishedAliasSearchCandidates(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Returns empty result without DB query when search query is blank")
    void shouldReturnEmptyWhenQueryIsBlank() {
        WikiNavigationalSearchResultDTO result = useCase.search("   \t  ", 20);

        assertThat(result.query()).isEmpty();
        assertThat(result.items()).isEmpty();
        verify(wikiArticleQueryPort, never()).findPublishedTitleSearchCandidates(anyString(), anyString(), anyInt());
        verify(wikiArticleQueryPort, never()).findPublishedAliasSearchCandidates(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Rank 1: Exact canonical title matches have highest priority with matchedAlias=null")
    void shouldRankExactCanonicalTitleFirst() {
        UUID articleId = UUID.randomUUID();
        WikiArticleListItemDTO article = createArticle(articleId, "Trần Bình An", "tran-binh-an", "CHARACTER");

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("tran binh an"), eq("tran binh an"), anyInt()))
                .thenReturn(List.of(article));
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("tran binh an"), eq("tran binh an"), anyInt()))
                .thenReturn(List.of());

        WikiNavigationalSearchResultDTO result = useCase.search("Trần Bình An", 20);

        assertThat(result.query()).isEqualTo("Trần Bình An");
        assertThat(result.items()).hasSize(1);
        WikiNavigationalSearchItemDTO item = result.items().get(0);
        assertThat(item.id()).isEqualTo(articleId);
        assertThat(item.title()).isEqualTo("Trần Bình An");
        assertThat(item.canonicalUrl()).isEqualTo("/wiki/character/tran-binh-an");
        assertThat(item.matchedAlias()).isNull();
    }

    @Test
    @DisplayName("Rank 2: Exact alias match resolves to canonical article with matchedAlias populated")
    void shouldRankExactAliasMatchSecond() {
        UUID articleId = UUID.randomUUID();
        WikiArticleAliasSearchMatchDTO aliasMatch = new WikiArticleAliasSearchMatchDTO(
                articleId,
                "Trần Bình An",
                "tran-binh-an",
                ArticleType.CHARACTER,
                "Bình An"
        );

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("binh an"), eq("binh an"), anyInt()))
                .thenReturn(List.of());
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("binh an"), eq("binh an"), anyInt()))
                .thenReturn(List.of(aliasMatch));

        WikiNavigationalSearchResultDTO result = useCase.search("Bình An", 20);

        assertThat(result.items()).hasSize(1);
        WikiNavigationalSearchItemDTO item = result.items().get(0);
        assertThat(item.id()).isEqualTo(articleId);
        assertThat(item.title()).isEqualTo("Trần Bình An");
        assertThat(item.canonicalUrl()).isEqualTo("/wiki/character/tran-binh-an");
        assertThat(item.matchedAlias()).isEqualTo("Bình An");
    }

    @Test
    @DisplayName("Rank 3: Canonical title prefix match")
    void shouldRankTitlePrefixThird() {
        UUID articleId = UUID.randomUUID();
        WikiArticleListItemDTO article = createArticle(articleId, "Trần Bình An", "tran-binh-an", "CHARACTER");

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("tran"), eq("tran"), anyInt()))
                .thenReturn(List.of(article));
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("tran"), eq("tran"), anyInt()))
                .thenReturn(List.of());

        WikiNavigationalSearchResultDTO result = useCase.search("Trần", 20);

        assertThat(result.items()).hasSize(1);
        WikiNavigationalSearchItemDTO item = result.items().get(0);
        assertThat(item.id()).isEqualTo(articleId);
        assertThat(item.title()).isEqualTo("Trần Bình An");
        assertThat(item.matchedAlias()).isNull();
    }

    @Test
    @DisplayName("Rank 4: Alias prefix match")
    void shouldRankAliasPrefixFourth() {
        UUID articleId = UUID.randomUUID();
        WikiArticleAliasSearchMatchDTO aliasMatch = new WikiArticleAliasSearchMatchDTO(
                articleId,
                "Trần Bình An",
                "tran-binh-an",
                ArticleType.CHARACTER,
                "Bình An Công Tử"
        );

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("binh an"), eq("binh an"), anyInt()))
                .thenReturn(List.of());
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("binh an"), eq("binh an"), anyInt()))
                .thenReturn(List.of(aliasMatch));

        WikiNavigationalSearchResultDTO result = useCase.search("Bình An", 20);

        assertThat(result.items()).hasSize(1);
        WikiNavigationalSearchItemDTO item = result.items().get(0);
        assertThat(item.id()).isEqualTo(articleId);
        assertThat(item.matchedAlias()).isEqualTo("Bình An Công Tử");
    }

    @Test
    @DisplayName("Rank 5: Canonical title contains match")
    void shouldRankTitleContainsFifth() {
        UUID articleId = UUID.randomUUID();
        WikiArticleListItemDTO article = createArticle(articleId, "Đại Đạo Triều Thiên", "dai-dao-trieu-thien", "REALM");

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("trieu"), eq("trieu"), anyInt()))
                .thenReturn(List.of(article));
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("trieu"), eq("trieu"), anyInt()))
                .thenReturn(List.of());

        WikiNavigationalSearchResultDTO result = useCase.search("Triều", 20);

        assertThat(result.items()).hasSize(1);
        WikiNavigationalSearchItemDTO item = result.items().get(0);
        assertThat(item.id()).isEqualTo(articleId);
        assertThat(item.title()).isEqualTo("Đại Đạo Triều Thiên");
        assertThat(item.matchedAlias()).isNull();
    }

    @Test
    @DisplayName("Rank 6: Alias contains match")
    void shouldRankAliasContainsSixth() {
        UUID articleId = UUID.randomUUID();
        WikiArticleAliasSearchMatchDTO aliasMatch = new WikiArticleAliasSearchMatchDTO(
                articleId,
                "Lý Bảo Bình",
                "ly-bao-binh",
                ArticleType.CHARACTER,
                "Tiểu Sư Muội Bảo Bình"
        );

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("bao binh"), eq("bao binh"), anyInt()))
                .thenReturn(List.of());
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("bao binh"), eq("bao binh"), anyInt()))
                .thenReturn(List.of(aliasMatch));

        WikiNavigationalSearchResultDTO result = useCase.search("Bảo Bình", 20);

        assertThat(result.items()).hasSize(1);
        WikiNavigationalSearchItemDTO item = result.items().get(0);
        assertThat(item.id()).isEqualTo(articleId);
        assertThat(item.matchedAlias()).isEqualTo("Tiểu Sư Muội Bảo Bình");
    }

    @Test
    @DisplayName("Deduplication: When an article matches both title and alias, highest relevance rank wins")
    void shouldDeduplicateArticleWithBestRank() {
        UUID articleId = UUID.randomUUID();
        // Title contains "Bình An" (Rank 5)
        WikiArticleListItemDTO titleMatch = createArticle(articleId, "Trần Bình An", "tran-binh-an", "CHARACTER");
        // Alias exact "Bình An" (Rank 2)
        WikiArticleAliasSearchMatchDTO aliasMatch = new WikiArticleAliasSearchMatchDTO(
                articleId,
                "Trần Bình An",
                "tran-binh-an",
                ArticleType.CHARACTER,
                "Bình An"
        );

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("binh an"), eq("binh an"), anyInt()))
                .thenReturn(List.of(titleMatch));
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("binh an"), eq("binh an"), anyInt()))
                .thenReturn(List.of(aliasMatch));

        WikiNavigationalSearchResultDTO result = useCase.search("Bình An", 20);

        assertThat(result.items()).hasSize(1);
        WikiNavigationalSearchItemDTO item = result.items().get(0);
        assertThat(item.id()).isEqualTo(articleId);
        // Rank 2 (exact alias) beats Rank 5 (title contains), so matchedAlias is preserved
        assertThat(item.matchedAlias()).isEqualTo("Bình An");
    }

    @Test
    @DisplayName("Deterministic matchedAlias: When multiple aliases of same article tie in rank, alphabetical tie-break is applied")
    void shouldDeterministicallySelectMatchedAliasOnRankTie() {
        UUID articleId = UUID.randomUUID();
        WikiArticleAliasSearchMatchDTO alias1 = new WikiArticleAliasSearchMatchDTO(
                articleId,
                "Trần Bình An",
                "tran-binh-an",
                ArticleType.CHARACTER,
                "Bình An Đại Ca"
        );
        WikiArticleAliasSearchMatchDTO alias2 = new WikiArticleAliasSearchMatchDTO(
                articleId,
                "Trần Bình An",
                "tran-binh-an",
                ArticleType.CHARACTER,
                "Bình An Công Tử"
        );

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("binh"), eq("binh"), anyInt()))
                .thenReturn(List.of());
        // Both match at Rank 4 (alias prefix). Regardless of DB order (alias1 before alias2 or alias2 before alias1),
        // "Bình An Công Tử" < "Bình An Đại Ca" alphabetically.
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("binh"), eq("binh"), anyInt()))
                .thenReturn(List.of(alias1, alias2));

        WikiNavigationalSearchResultDTO result = useCase.search("Bình", 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).matchedAlias()).isEqualTo("Bình An Công Tử");
    }

    @Test
    @DisplayName("Non-global unique alias: Multiple articles sharing the same alias are all returned")
    void shouldReturnAllArticlesSharingSameAlias() {
        UUID article1Id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID article2Id = UUID.fromString("00000000-0000-0000-0000-000000000002");

        WikiArticleAliasSearchMatchDTO match1 = new WikiArticleAliasSearchMatchDTO(
                article1Id,
                "Đạo Tổ",
                "dao-to",
                ArticleType.CHARACTER,
                "Lão Tử"
        );
        WikiArticleAliasSearchMatchDTO match2 = new WikiArticleAliasSearchMatchDTO(
                article2Id,
                "Thái Thượng Lão Quân",
                "thai-thuong-lao-quan",
                ArticleType.CHARACTER,
                "Lão Tử"
        );

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("lao tu"), eq("lao tu"), anyInt()))
                .thenReturn(List.of());
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("lao tu"), eq("lao tu"), anyInt()))
                .thenReturn(List.of(match1, match2));

        WikiNavigationalSearchResultDTO result = useCase.search("Lão Tử", 20);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).id()).isEqualTo(article1Id);
        assertThat(result.items().get(0).title()).isEqualTo("Đạo Tổ");
        assertThat(result.items().get(1).id()).isEqualTo(article2Id);
        assertThat(result.items().get(1).title()).isEqualTo("Thái Thượng Lão Quân");
    }

    @Test
    @DisplayName("Vietnamese accentless query Tran Binh An matches accented article Trần Bình An")
    void shouldMatchAccentlessTranVsTran() {
        UUID articleId = UUID.randomUUID();
        WikiArticleListItemDTO article = createArticle(articleId, "Trần Bình An", "tran-binh-an", "CHARACTER");

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("tran binh an"), eq("tran binh an"), anyInt()))
                .thenReturn(List.of(article));
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("tran binh an"), eq("tran binh an"), anyInt()))
                .thenReturn(List.of());

        WikiNavigationalSearchResultDTO result = useCase.search("tran binh an", 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).title()).isEqualTo("Trần Bình An");
    }

    @Test
    @DisplayName("Vietnamese d <-> đ folding: query dao to matches accented article Đạo Tổ")
    void shouldMatchFoldedDaoVsDao() {
        UUID articleId = UUID.randomUUID();
        WikiArticleListItemDTO article = createArticle(articleId, "Đạo Tổ", "dao-to", "CHARACTER");

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("dao to"), eq("dao to"), anyInt()))
                .thenReturn(List.of(article));
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("dao to"), eq("dao to"), anyInt()))
                .thenReturn(List.of());

        WikiNavigationalSearchResultDTO result = useCase.search("dao to", 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).title()).isEqualTo("Đạo Tổ");
    }

    @Test
    @DisplayName("LIKE metacharacters % and _ and \\ are escaped before querying query port")
    void shouldEscapeLikeWildcardsInQuery() {
        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("100% real_deal\\item"), eq("100\\% real\\_deal\\\\item"), anyInt()))
                .thenReturn(List.of());
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("100% real_deal\\item"), eq("100\\% real\\_deal\\\\item"), anyInt()))
                .thenReturn(List.of());

        useCase.search("100% Real_Deal\\Item", 20);

        verify(wikiArticleQueryPort).findPublishedTitleSearchCandidates(eq("100% real_deal\\item"), eq("100\\% real\\_deal\\\\item"), anyInt());
        verify(wikiArticleQueryPort).findPublishedAliasSearchCandidates(eq("100% real_deal\\item"), eq("100\\% real\\_deal\\\\item"), anyInt());
    }

    @Test
    @DisplayName("Deterministic ordering: Rank 1 -> Rank 2 -> Rank 3 -> Rank 4 -> Rank 5 -> Rank 6 with title/id tie-breakers")
    void shouldOrderResultsDeterministically() {
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID id3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID id4 = UUID.fromString("00000000-0000-0000-0000-000000000004");
        UUID id5 = UUID.fromString("00000000-0000-0000-0000-000000000005");
        UUID id6 = UUID.fromString("00000000-0000-0000-0000-000000000006");

        // Rank 5 (Title Contains)
        WikiArticleListItemDTO item5 = createArticle(id5, "Lạc Phách Sơn Kiếm Tiên", "lac-phach-son-kiem-tien", "LOCATION");
        // Rank 3 (Title Prefix)
        WikiArticleListItemDTO item3 = createArticle(id3, "Kiếm Tiên Tông", "kiem-tien-tong", "FACTION");
        // Rank 1 (Exact Title)
        WikiArticleListItemDTO item1 = createArticle(id1, "Kiếm Tiên", "kiem-tien", "REALM");

        // Rank 6 (Alias Contains)
        WikiArticleAliasSearchMatchDTO alias6 = new WikiArticleAliasSearchMatchDTO(
                id6, "Cố Thao", "co-thao", ArticleType.CHARACTER, "Tân Sinh Kiếm Tiên Giả"
        );
        // Rank 4 (Alias Prefix)
        WikiArticleAliasSearchMatchDTO alias4 = new WikiArticleAliasSearchMatchDTO(
                id4, "Tạ Linh", "ta-linh", ArticleType.CHARACTER, "Kiếm Tiên Chi Tử"
        );
        // Rank 2 (Exact Alias)
        WikiArticleAliasSearchMatchDTO alias2 = new WikiArticleAliasSearchMatchDTO(
                id2, "Bạch Dã", "bach-da", ArticleType.CHARACTER, "Kiếm Tiên"
        );

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("kiem tien"), eq("kiem tien"), anyInt()))
                .thenReturn(List.of(item5, item3, item1));
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("kiem tien"), eq("kiem tien"), anyInt()))
                .thenReturn(List.of(alias6, alias4, alias2));

        WikiNavigationalSearchResultDTO result = useCase.search("Kiếm Tiên", 20);

        assertThat(result.items()).hasSize(6);
        // Exact order must be Rank 1 -> Rank 2 -> Rank 3 -> Rank 4 -> Rank 5 -> Rank 6
        assertThat(result.items().get(0).id()).isEqualTo(id1); // Rank 1 (Kiếm Tiên - Exact Title)
        assertThat(result.items().get(1).id()).isEqualTo(id2); // Rank 2 (Bạch Dã - Exact Alias)
        assertThat(result.items().get(2).id()).isEqualTo(id3); // Rank 3 (Kiếm Tiên Tông - Title Prefix)
        assertThat(result.items().get(3).id()).isEqualTo(id4); // Rank 4 (Tạ Linh - Alias Prefix)
        assertThat(result.items().get(4).id()).isEqualTo(id5); // Rank 5 (Lạc Phách Sơn Kiếm Tiên - Title Contains)
        assertThat(result.items().get(5).id()).isEqualTo(id6); // Rank 6 (Cố Thao - Alias Contains)
    }

    @Test
    @DisplayName("Result limit: Caps returned results to requested limit and clamps to maximum 20")
    void shouldRespectResultLimit() {
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID id3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        WikiArticleListItemDTO item1 = createArticle(id1, "Kiếm Tiên 1", "kiem-tien-1", "CHARACTER");
        WikiArticleListItemDTO item2 = createArticle(id2, "Kiếm Tiên 2", "kiem-tien-2", "CHARACTER");
        WikiArticleListItemDTO item3 = createArticle(id3, "Kiếm Tiên 3", "kiem-tien-3", "CHARACTER");

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("kiem tien"), eq("kiem tien"), anyInt()))
                .thenReturn(List.of(item1, item2, item3));
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("kiem tien"), eq("kiem tien"), anyInt()))
                .thenReturn(List.of());

        WikiNavigationalSearchResultDTO resultLimit2 = useCase.search("Kiếm Tiên", 2);
        assertThat(resultLimit2.items()).hasSize(2);

        WikiNavigationalSearchResultDTO resultLimitDefault = useCase.search("Kiếm Tiên", 0);
        assertThat(resultLimitDefault.items()).hasSize(3);
    }

    @Test
    @DisplayName("Candidate validation: Skips candidates with null or invalid articleType rather than fabricating CHARACTER")
    void shouldSkipCandidatesWithNullOrInvalidArticleTypeWithoutFabrication() {
        UUID validId = UUID.randomUUID();
        UUID invalidId1 = UUID.randomUUID();
        UUID invalidId2 = UUID.randomUUID();

        WikiArticleListItemDTO validItem = createArticle(validId, "Kiếm Tiên", "kiem-tien", "FACTION");
        WikiArticleListItemDTO nullTypeItem = createArticle(invalidId1, "Kiếm Ma", "kiem-ma", null);
        WikiArticleListItemDTO invalidTypeItem = createArticle(invalidId2, "Kiếm Thần", "kiem-than", "UNKNOWN_TYPE_XYZ");

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("kiem"), eq("kiem"), anyInt()))
                .thenReturn(List.of(validItem, nullTypeItem, invalidTypeItem));
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("kiem"), eq("kiem"), anyInt()))
                .thenReturn(List.of());

        WikiNavigationalSearchResultDTO result = useCase.search("Kiếm", 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(validId);
        assertThat(result.items().get(0).articleType()).isEqualTo(ArticleType.FACTION);
        assertThat(result.items().get(0).canonicalUrl()).isEqualTo("/wiki/faction/kiem-tien");
    }

    @Test
    @DisplayName("Passes through lightweight presentation fields (summary, updatedAt, coverMediaAssetId, position) to WikiNavigationalSearchItemDTO")
    void shouldPassThroughPresentationFieldsForRichWikiCard() {
        UUID articleId = UUID.randomUUID();
        UUID coverAssetId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-08-15T10:30:00Z");

        WikiArticleListItemDTO article = new WikiArticleListItemDTO(
                articleId,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "PUBLISHED",
                "Tóm tắt Trần Bình An",
                UUID.randomUUID(),
                Instant.now(),
                updatedAt,
                1L,
                coverAssetId,
                30,
                70
        );

        when(wikiArticleQueryPort.findPublishedTitleSearchCandidates(eq("tran binh an"), eq("tran binh an"), anyInt()))
                .thenReturn(List.of(article));
        when(wikiArticleQueryPort.findPublishedAliasSearchCandidates(eq("tran binh an"), eq("tran binh an"), anyInt()))
                .thenReturn(List.of());

        WikiNavigationalSearchResultDTO result = useCase.search("Trần Bình An", 20);

        assertThat(result.items()).hasSize(1);
        WikiNavigationalSearchItemDTO item = result.items().get(0);
        assertThat(item.summary()).isEqualTo("Tóm tắt Trần Bình An");
        assertThat(item.updatedAt()).isEqualTo(updatedAt);
        assertThat(item.coverMediaAssetId()).isEqualTo(coverAssetId);
        assertThat(item.coverPositionX()).isEqualTo(30);
        assertThat(item.coverPositionY()).isEqualTo(70);
        assertThat(item.displayCoverImageUrl()).isEqualTo("/media/assets/" + coverAssetId + "/variants/w300");
        assertThat(item.fallbackCoverImageUrl()).isEqualTo("/media/assets/" + coverAssetId + "/content");
        assertThat(item.coverObjectPosition()).isEqualTo("30% 70%");
    }

    private WikiArticleListItemDTO createArticle(UUID id, String title, String slug, String articleType) {
        return new WikiArticleListItemDTO(
                id,
                title,
                slug,
                articleType,
                "PUBLISHED",
                null,
                Instant.now(),
                Instant.now(),
                1L
        );
    }
}
