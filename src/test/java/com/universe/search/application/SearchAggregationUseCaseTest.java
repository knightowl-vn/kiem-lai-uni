package com.universe.search.application;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorItemDTO;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorResultDTO;
import com.universe.novel.contracts.interfaces.NovelChapterLocatorContract;
import com.universe.search.contracts.dto.CommunityProfileSearchItemDTO;
import com.universe.search.contracts.dto.CommunityProfileSearchResultDTO;
import com.universe.search.contracts.dto.SearchAggregationResultDTO;
import com.universe.search.contracts.dto.SearchScope;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchItemDTO;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchResultDTO;
import com.universe.wiki.contracts.interfaces.WikiNavigationalSearchContract;
import com.universe.wiki.domain.article.ArticleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchAggregationUseCaseTest {

    @Mock
    private WikiNavigationalSearchContract wikiNavigationalSearchContract;

    @Mock
    private NovelChapterLocatorContract novelChapterLocatorContract;

    @Mock
    private UserIdentityContract userIdentityContract;

    private SearchAggregationUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SearchAggregationUseCase(
                wikiNavigationalSearchContract,
                novelChapterLocatorContract,
                userIdentityContract
        );
    }

    @Nested
    @DisplayName("Scope Dispatch Tests")
    class ScopeDispatchTests {

        @Test
        @DisplayName("1. ALL invokes Wiki, Novel, and Identity contracts exactly once")
        void aggregate_whenScopeIsAll_invokesAllThreeContractsExactlyOnce() {
            WikiNavigationalSearchResultDTO wikiResult = new WikiNavigationalSearchResultDTO(
                    "kiếm lai",
                    List.of(createWikiItem("Trần Bình An", "tran-binh-an"))
            );
            NovelChapterLocatorResultDTO novelResult = new NovelChapterLocatorResultDTO(
                    "kiếm lai",
                    null,
                    List.of(createNovelItem(1, "Chương 1", "chuong-1"))
            );
            List<UserPublicProfileDTO> identityResult = List.of(
                    createUserProfile("Trần Bình An", "tranbinhan", "https://img/1.png")
            );

            when(wikiNavigationalSearchContract.search("kiếm lai", 20)).thenReturn(wikiResult);
            when(novelChapterLocatorContract.locateChapters("kiếm lai", 20)).thenReturn(novelResult);
            when(userIdentityContract.searchPublicUsers("kiếm lai", 20)).thenReturn(identityResult);

            SearchAggregationResultDTO result = useCase.aggregate("kiếm lai", SearchScope.ALL, 20);

            verify(wikiNavigationalSearchContract, times(1)).search("kiếm lai", 20);
            verify(novelChapterLocatorContract, times(1)).locateChapters("kiếm lai", 20);
            verify(userIdentityContract, times(1)).searchPublicUsers("kiếm lai", 20);

            assertThat(result.query()).isEqualTo("kiếm lai");
            assertThat(result.scope()).isEqualTo(SearchScope.ALL);
            assertThat(result.wiki().items()).hasSize(1);
            assertThat(result.novel().items()).hasSize(1);
            assertThat(result.community().items()).hasSize(1);
            assertThat(result.community().items().get(0).publicHandle()).isEqualTo("tranbinhan");
            assertThat(result.community().items().get(0).profileUrl()).isEqualTo("/community/@tranbinhan");
        }

        @Test
        @DisplayName("2. WIKI invokes only Wiki contract and returns explicit empty Novel and Community groups")
        void aggregate_whenScopeIsWiki_invokesOnlyWikiContract() {
            WikiNavigationalSearchResultDTO wikiResult = new WikiNavigationalSearchResultDTO(
                    "thạch hạo",
                    List.of(createWikiItem("Thạch Hạo", "thach-hao"))
            );
            when(wikiNavigationalSearchContract.search("thạch hạo", 10)).thenReturn(wikiResult);

            SearchAggregationResultDTO result = useCase.aggregate("thạch hạo", SearchScope.WIKI, 10);

            verify(wikiNavigationalSearchContract, times(1)).search("thạch hạo", 10);
            verifyNoInteractions(novelChapterLocatorContract);
            verifyNoInteractions(userIdentityContract);

            assertThat(result.query()).isEqualTo("thạch hạo");
            assertThat(result.scope()).isEqualTo(SearchScope.WIKI);
            assertThat(result.wiki().items()).hasSize(1);
            assertThat(result.novel().items()).isEmpty();
            assertThat(result.novel().query()).isEqualTo("");
            assertThat(result.novel().matchedChapterNumber()).isNull();
            assertThat(result.community().items()).isEmpty();
            assertThat(result.community().query()).isEqualTo("");
        }

        @Test
        @DisplayName("3. NOVEL invokes only Novel contract and returns explicit empty Wiki and Community groups")
        void aggregate_whenScopeIsNovel_invokesOnlyNovelContract() {
            NovelChapterLocatorResultDTO novelResult = new NovelChapterLocatorResultDTO(
                    "chương 10",
                    10,
                    List.of(createNovelItem(10, "Chương 10", "chuong-10"))
            );
            when(novelChapterLocatorContract.locateChapters("chương 10", 15)).thenReturn(novelResult);

            SearchAggregationResultDTO result = useCase.aggregate("chương 10", SearchScope.NOVEL, 15);

            verify(novelChapterLocatorContract, times(1)).locateChapters("chương 10", 15);
            verifyNoInteractions(wikiNavigationalSearchContract);
            verifyNoInteractions(userIdentityContract);

            assertThat(result.query()).isEqualTo("chương 10");
            assertThat(result.scope()).isEqualTo(SearchScope.NOVEL);
            assertThat(result.novel().items()).hasSize(1);
            assertThat(result.wiki().items()).isEmpty();
            assertThat(result.wiki().query()).isEqualTo("");
            assertThat(result.community().items()).isEmpty();
            assertThat(result.community().query()).isEqualTo("");
        }

        @Test
        @DisplayName("4. COMMUNITY invokes only Identity contract and returns explicit empty Wiki and Novel groups")
        void aggregate_whenScopeIsCommunity_invokesOnlyIdentityContract() {
            List<UserPublicProfileDTO> identityResult = List.of(
                    createUserProfile("Cố Huỳnh", "cohuynh", "https://img/co.png")
            );
            when(userIdentityContract.searchPublicUsers("cohuynh", 15)).thenReturn(identityResult);

            SearchAggregationResultDTO result = useCase.aggregate("cohuynh", SearchScope.COMMUNITY, 15);

            verify(userIdentityContract, times(1)).searchPublicUsers("cohuynh", 15);
            verifyNoInteractions(wikiNavigationalSearchContract);
            verifyNoInteractions(novelChapterLocatorContract);

            assertThat(result.query()).isEqualTo("cohuynh");
            assertThat(result.scope()).isEqualTo(SearchScope.COMMUNITY);
            assertThat(result.community().items()).hasSize(1);
            assertThat(result.community().items().get(0).displayName()).isEqualTo("Cố Huỳnh");
            assertThat(result.community().items().get(0).publicHandle()).isEqualTo("cohuynh");
            assertThat(result.community().items().get(0).profileUrl()).isEqualTo("/community/@cohuynh");
            assertThat(result.wiki().items()).isEmpty();
            assertThat(result.wiki().query()).isEqualTo("");
            assertThat(result.novel().items()).isEmpty();
            assertThat(result.novel().query()).isEqualTo("");
        }

        @Test
        @DisplayName("7. null scope behaves as SearchScope.ALL")
        void aggregate_whenScopeIsNull_behavesAsAll() {
            WikiNavigationalSearchResultDTO wikiResult = new WikiNavigationalSearchResultDTO("test", List.of());
            NovelChapterLocatorResultDTO novelResult = new NovelChapterLocatorResultDTO("test", null, List.of());
            List<UserPublicProfileDTO> identityResult = List.of();

            when(wikiNavigationalSearchContract.search("test", 20)).thenReturn(wikiResult);
            when(novelChapterLocatorContract.locateChapters("test", 20)).thenReturn(novelResult);
            when(userIdentityContract.searchPublicUsers("test", 20)).thenReturn(identityResult);

            SearchAggregationResultDTO result = useCase.aggregate("test", null, 20);

            assertThat(result.scope()).isEqualTo(SearchScope.ALL);
            verify(wikiNavigationalSearchContract, times(1)).search("test", 20);
            verify(novelChapterLocatorContract, times(1)).locateChapters("test", 20);
            verify(userIdentityContract, times(1)).searchPublicUsers("test", 20);
        }
    }

    @Nested
    @DisplayName("Query Normalization Tests")
    class QueryNormalizationTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n", " \t \n "})
        @DisplayName("4. null/blank query invokes neither contract and returns empty result immediately")
        void aggregate_whenQueryIsBlankOrNull_invokesNeitherContract(String blankQuery) {
            SearchAggregationResultDTO result = useCase.aggregate(blankQuery, SearchScope.ALL, 20);

            verifyNoInteractions(wikiNavigationalSearchContract);
            verifyNoInteractions(novelChapterLocatorContract);
            verifyNoInteractions(userIdentityContract);

            assertThat(result.query()).isEqualTo("");
            assertThat(result.scope()).isEqualTo(SearchScope.ALL);
            assertThat(result.wiki().items()).isEmpty();
            assertThat(result.novel().items()).isEmpty();
            assertThat(result.community().items()).isEmpty();
        }

        @Test
        @DisplayName("5. whitespace normalization trims and collapses whitespace runs")
        void aggregate_whenQueryHasIrregularWhitespace_normalizesCorrectly() {
            when(wikiNavigationalSearchContract.search(anyString(), anyInt()))
                    .thenReturn(new WikiNavigationalSearchResultDTO("kiếm lai", List.of()));
            when(novelChapterLocatorContract.locateChapters(anyString(), anyInt()))
                    .thenReturn(new NovelChapterLocatorResultDTO("kiếm lai", null, List.of()));
            when(userIdentityContract.searchPublicUsers(anyString(), anyInt()))
                    .thenReturn(List.of());

            SearchAggregationResultDTO result = useCase.aggregate("   kiếm     lai   ", SearchScope.ALL, 20);

            verify(wikiNavigationalSearchContract).search("kiếm lai", 20);
            verify(novelChapterLocatorContract).locateChapters("kiếm lai", 20);
            verify(userIdentityContract).searchPublicUsers("kiếm lai", 20);
            assertThat(result.query()).isEqualTo("kiếm lai");
        }

        @Test
        @DisplayName("6. query longer than 200 characters is capped before downstream invocation")
        void aggregate_whenQueryExceeds200Characters_capsTo200() {
            String longQuery = "a".repeat(250);
            String expectedCappedQuery = "a".repeat(200);

            when(wikiNavigationalSearchContract.search(anyString(), anyInt()))
                    .thenReturn(new WikiNavigationalSearchResultDTO(expectedCappedQuery, List.of()));
            when(novelChapterLocatorContract.locateChapters(anyString(), anyInt()))
                    .thenReturn(new NovelChapterLocatorResultDTO(expectedCappedQuery, null, List.of()));
            when(userIdentityContract.searchPublicUsers(anyString(), anyInt()))
                    .thenReturn(List.of());

            SearchAggregationResultDTO result = useCase.aggregate(longQuery, SearchScope.ALL, 20);

            verify(wikiNavigationalSearchContract).search(expectedCappedQuery, 20);
            verify(novelChapterLocatorContract).locateChapters(expectedCappedQuery, 20);
            verify(userIdentityContract).searchPublicUsers(expectedCappedQuery, 20);
            assertThat(result.query()).hasSize(200);
            assertThat(result.query()).isEqualTo(expectedCappedQuery);
        }
    }

    @Nested
    @DisplayName("Limit Handling Tests")
    class LimitHandlingTests {

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -5, -100})
        @DisplayName("9. limit <= 0 defaults to 20")
        void aggregate_whenLimitIsZeroOrNegative_defaultsTo20(int nonPositiveLimit) {
            when(wikiNavigationalSearchContract.search("test", 20))
                    .thenReturn(new WikiNavigationalSearchResultDTO("test", List.of()));
            when(novelChapterLocatorContract.locateChapters("test", 20))
                    .thenReturn(new NovelChapterLocatorResultDTO("test", null, List.of()));
            when(userIdentityContract.searchPublicUsers("test", 20))
                    .thenReturn(List.of());

            useCase.aggregate("test", SearchScope.ALL, nonPositiveLimit);

            verify(wikiNavigationalSearchContract).search("test", 20);
            verify(novelChapterLocatorContract).locateChapters("test", 20);
            verify(userIdentityContract).searchPublicUsers("test", 20);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 5, 10, 19, 20})
        @DisplayName("10. limit 1..20 is preserved exactly")
        void aggregate_whenLimitIsWithinValidRange_preservesLimit(int validLimit) {
            when(wikiNavigationalSearchContract.search("test", validLimit))
                    .thenReturn(new WikiNavigationalSearchResultDTO("test", List.of()));
            when(novelChapterLocatorContract.locateChapters("test", validLimit))
                    .thenReturn(new NovelChapterLocatorResultDTO("test", null, List.of()));
            when(userIdentityContract.searchPublicUsers("test", validLimit))
                    .thenReturn(List.of());

            useCase.aggregate("test", SearchScope.ALL, validLimit);

            verify(wikiNavigationalSearchContract).search("test", validLimit);
            verify(novelChapterLocatorContract).locateChapters("test", validLimit);
            verify(userIdentityContract).searchPublicUsers("test", validLimit);
        }

        @ParameterizedTest
        @ValueSource(ints = {21, 25, 50, 100})
        @DisplayName("11. limit > 20 is clamped to 20")
        void aggregate_whenLimitExceedsMax_clampsTo20(int excessiveLimit) {
            when(wikiNavigationalSearchContract.search("test", 20))
                    .thenReturn(new WikiNavigationalSearchResultDTO("test", List.of()));
            when(novelChapterLocatorContract.locateChapters("test", 20))
                    .thenReturn(new NovelChapterLocatorResultDTO("test", null, List.of()));
            when(userIdentityContract.searchPublicUsers("test", 20))
                    .thenReturn(List.of());

            useCase.aggregate("test", SearchScope.ALL, excessiveLimit);

            verify(wikiNavigationalSearchContract).search("test", 20);
            verify(novelChapterLocatorContract).locateChapters("test", 20);
            verify(userIdentityContract).searchPublicUsers("test", 20);
        }
    }

    @Nested
    @DisplayName("Ordering and Invariants Tests")
    class OrderingAndInvariantsTests {

        @Test
        @DisplayName("12 & 13. Wiki, Novel, and Community result orders are preserved exactly as returned")
        void aggregate_preservesDownstreamItemOrderingExactly() {
            WikiNavigationalSearchItemDTO wiki1 = createWikiItem("A", "slug-a");
            WikiNavigationalSearchItemDTO wiki2 = createWikiItem("B", "slug-b");
            WikiNavigationalSearchItemDTO wiki3 = createWikiItem("C", "slug-c");

            NovelChapterLocatorItemDTO novel1 = createNovelItem(1, "Chương 1", "c-1");
            NovelChapterLocatorItemDTO novel2 = createNovelItem(2, "Chương 2", "c-2");
            NovelChapterLocatorItemDTO novel3 = createNovelItem(3, "Chương 3", "c-3");

            UserPublicProfileDTO user1 = createUserProfile("U1", "h1", "https://img/1.png");
            UserPublicProfileDTO user2 = createUserProfile("U2", "h2", "https://img/2.png");
            UserPublicProfileDTO user3 = createUserProfile("U3", "h3", null);

            WikiNavigationalSearchResultDTO wikiResult = new WikiNavigationalSearchResultDTO("test", List.of(wiki1, wiki2, wiki3));
            NovelChapterLocatorResultDTO novelResult = new NovelChapterLocatorResultDTO("test", null, List.of(novel1, novel2, novel3));
            List<UserPublicProfileDTO> identityResult = List.of(user1, user2, user3);

            when(wikiNavigationalSearchContract.search("test", 20)).thenReturn(wikiResult);
            when(novelChapterLocatorContract.locateChapters("test", 20)).thenReturn(novelResult);
            when(userIdentityContract.searchPublicUsers("test", 20)).thenReturn(identityResult);

            SearchAggregationResultDTO result = useCase.aggregate("test", SearchScope.ALL, 20);

            assertThat(result.wiki().items()).containsExactly(wiki1, wiki2, wiki3);
            assertThat(result.novel().items()).containsExactly(novel1, novel2, novel3);
            assertThat(result.community().items())
                    .extracting(CommunityProfileSearchItemDTO::publicHandle)
                    .containsExactly("h1", "h2", "h3");
        }

        @Test
        @DisplayName("14. ALL keeps separate bounded-context groups without cross-ranking or interleaving")
        void aggregate_keepsGroupsSeparateWithoutCrossRanking() {
            WikiNavigationalSearchItemDTO wikiItem = createWikiItem("Kiếm Lai", "kiem-lai");
            NovelChapterLocatorItemDTO novelItem = createNovelItem(1, "Kiếm Lai Chương 1", "kiem-lai-c-1");
            UserPublicProfileDTO userItem = createUserProfile("Kiếm Lai", "kiemlai", null);

            when(wikiNavigationalSearchContract.search("kiem", 20))
                    .thenReturn(new WikiNavigationalSearchResultDTO("kiem", List.of(wikiItem)));
            when(novelChapterLocatorContract.locateChapters("kiem", 20))
                    .thenReturn(new NovelChapterLocatorResultDTO("kiem", null, List.of(novelItem)));
            when(userIdentityContract.searchPublicUsers("kiem", 20))
                    .thenReturn(List.of(userItem));

            SearchAggregationResultDTO result = useCase.aggregate("kiem", SearchScope.ALL, 20);

            assertThat(result.wiki().items()).containsExactly(wikiItem);
            assertThat(result.novel().items()).containsExactly(novelItem);
            assertThat(result.community().items())
                    .extracting(CommunityProfileSearchItemDTO::publicHandle)
                    .containsExactly("kiemlai");
        }

        @Test
        @DisplayName("15. Unqueried group is explicitly non-null and empty")
        void aggregate_unqueriedGroupIsExplicitlyNonNullAndEmpty() {
            when(wikiNavigationalSearchContract.search("wiki-only", 20))
                    .thenReturn(new WikiNavigationalSearchResultDTO("wiki-only", List.of()));

            SearchAggregationResultDTO wikiOnlyResult = useCase.aggregate("wiki-only", SearchScope.WIKI, 20);

            assertThat(wikiOnlyResult.novel()).isNotNull();
            assertThat(wikiOnlyResult.novel().items()).isEmpty();
            assertThat(wikiOnlyResult.novel().matchedChapterNumber()).isNull();
            assertThat(wikiOnlyResult.community()).isNotNull();
            assertThat(wikiOnlyResult.community().items()).isEmpty();

            when(novelChapterLocatorContract.locateChapters("novel-only", 20))
                    .thenReturn(new NovelChapterLocatorResultDTO("novel-only", null, List.of()));

            SearchAggregationResultDTO novelOnlyResult = useCase.aggregate("novel-only", SearchScope.NOVEL, 20);

            assertThat(novelOnlyResult.wiki()).isNotNull();
            assertThat(novelOnlyResult.wiki().items()).isEmpty();
            assertThat(novelOnlyResult.community()).isNotNull();
            assertThat(novelOnlyResult.community().items()).isEmpty();

            when(userIdentityContract.searchPublicUsers("community-only", 20))
                    .thenReturn(List.of());

            SearchAggregationResultDTO communityOnlyResult = useCase.aggregate("community-only", SearchScope.COMMUNITY, 20);

            assertThat(communityOnlyResult.wiki()).isNotNull();
            assertThat(communityOnlyResult.wiki().items()).isEmpty();
            assertThat(communityOnlyResult.novel()).isNotNull();
            assertThat(communityOnlyResult.novel().items()).isEmpty();
        }

        @Test
        @DisplayName("16. Mapping from UserPublicProfileDTO to CommunityProfileSearchItemDTO preserves privacy (no userId)")
        void aggregate_mapsIdentityDTOToCommunityItemDTOCorrectly() {
            UUID internalUserId = UUID.randomUUID();
            UserPublicProfileDTO identityDTO = new UserPublicProfileDTO(
                    internalUserId,
                    "Nguyễn Du",
                    "https://img/avatar.png",
                    "nguyendu"
            );
            when(userIdentityContract.searchPublicUsers("nguyen", 20)).thenReturn(List.of(identityDTO));

            SearchAggregationResultDTO result = useCase.aggregate("nguyen", SearchScope.COMMUNITY, 20);

            assertThat(result.community().items()).hasSize(1);
            CommunityProfileSearchItemDTO item = result.community().items().get(0);
            assertThat(item.displayName()).isEqualTo("Nguyễn Du");
            assertThat(item.publicHandle()).isEqualTo("nguyendu");
            assertThat(item.avatarUrl()).isEqualTo("https://img/avatar.png");
            assertThat(item.profileUrl()).isEqualTo("/community/@nguyendu");
        }

        @Test
        @DisplayName("17. Default overloads delegate correctly")
        void aggregate_defaultOverloadsDelegateCorrectly() {
            when(wikiNavigationalSearchContract.search("test", 20))
                    .thenReturn(new WikiNavigationalSearchResultDTO("test", List.of()));
            when(novelChapterLocatorContract.locateChapters("test", 20))
                    .thenReturn(new NovelChapterLocatorResultDTO("test", null, List.of()));
            when(userIdentityContract.searchPublicUsers("test", 20))
                    .thenReturn(List.of());

            SearchAggregationResultDTO result2Args = useCase.aggregate("test", SearchScope.ALL);
            assertThat(result2Args.query()).isEqualTo("test");

            SearchAggregationResultDTO result1Arg = useCase.aggregate("test");
            assertThat(result1Arg.query()).isEqualTo("test");
            assertThat(result1Arg.scope()).isEqualTo(SearchScope.ALL);
        }
    }

    @Nested
    @DisplayName("Constructor Null Checks")
    class ConstructorNullChecks {

        @Test
        @DisplayName("SearchAggregationUseCase constructor rejects null dependencies")
        void constructor_rejectsNullDependencies() {
            assertThatThrownBy(() -> new SearchAggregationUseCase(null, novelChapterLocatorContract, userIdentityContract))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("WikiNavigationalSearchContract must not be null");

            assertThatThrownBy(() -> new SearchAggregationUseCase(wikiNavigationalSearchContract, null, userIdentityContract))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("NovelChapterLocatorContract must not be null");

            assertThatThrownBy(() -> new SearchAggregationUseCase(wikiNavigationalSearchContract, novelChapterLocatorContract, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("UserIdentityContract must not be null");
        }

        @Test
        @DisplayName("SearchAggregationResultDTO constructor rejects null fields")
        void searchAggregationResultDTO_constructorRejectsNullFields() {
            WikiNavigationalSearchResultDTO wikiResult = new WikiNavigationalSearchResultDTO("", List.of());
            NovelChapterLocatorResultDTO novelResult = new NovelChapterLocatorResultDTO("", null, List.of());
            CommunityProfileSearchResultDTO commResult = new CommunityProfileSearchResultDTO("", List.of());

            assertThatThrownBy(() -> new SearchAggregationResultDTO(null, SearchScope.ALL, wikiResult, novelResult, commResult))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new SearchAggregationResultDTO("q", null, wikiResult, novelResult, commResult))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new SearchAggregationResultDTO("q", SearchScope.ALL, null, novelResult, commResult))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new SearchAggregationResultDTO("q", SearchScope.ALL, wikiResult, null, commResult))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new SearchAggregationResultDTO("q", SearchScope.ALL, wikiResult, novelResult, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("aggregate fails fast with NullPointerException when invoked Wiki contract returns null")
        void aggregate_whenInvokedWikiContractReturnsNull_throwsNullPointerException() {
            when(wikiNavigationalSearchContract.search("test", 20)).thenReturn(null);
            when(novelChapterLocatorContract.locateChapters("test", 20))
                    .thenReturn(new NovelChapterLocatorResultDTO("test", null, List.of()));
            when(userIdentityContract.searchPublicUsers("test", 20)).thenReturn(List.of());

            assertThatThrownBy(() -> useCase.aggregate("test", SearchScope.ALL, 20))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("wiki must not be null");
        }

        @Test
        @DisplayName("aggregate fails fast with NullPointerException when invoked Novel contract returns null")
        void aggregate_whenInvokedNovelContractReturnsNull_throwsNullPointerException() {
            when(wikiNavigationalSearchContract.search("test", 20))
                    .thenReturn(new WikiNavigationalSearchResultDTO("test", List.of()));
            when(novelChapterLocatorContract.locateChapters("test", 20)).thenReturn(null);
            when(userIdentityContract.searchPublicUsers("test", 20)).thenReturn(List.of());

            assertThatThrownBy(() -> useCase.aggregate("test", SearchScope.ALL, 20))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("novel must not be null");
        }

        @Test
        @DisplayName("aggregate fails fast with NullPointerException when invoked Identity contract returns null")
        void aggregate_whenInvokedIdentityContractReturnsNull_throwsNullPointerException() {
            when(wikiNavigationalSearchContract.search("test", 20))
                    .thenReturn(new WikiNavigationalSearchResultDTO("test", List.of()));
            when(novelChapterLocatorContract.locateChapters("test", 20))
                    .thenReturn(new NovelChapterLocatorResultDTO("test", null, List.of()));
            when(userIdentityContract.searchPublicUsers("test", 20)).thenReturn(null);

            assertThatThrownBy(() -> useCase.aggregate("test", SearchScope.ALL, 20))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("community must not be null");
        }

        @Test
        @DisplayName("aggregate fails fast with NullPointerException when scoped WIKI search returns null")
        void aggregate_whenScopedWikiReturnsNull_throwsNullPointerException() {
            when(wikiNavigationalSearchContract.search("test", 20)).thenReturn(null);

            assertThatThrownBy(() -> useCase.aggregate("test", SearchScope.WIKI, 20))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("wiki must not be null");
        }

        @Test
        @DisplayName("aggregate fails fast with NullPointerException when scoped NOVEL locator returns null")
        void aggregate_whenScopedNovelReturnsNull_throwsNullPointerException() {
            when(novelChapterLocatorContract.locateChapters("test", 20)).thenReturn(null);

            assertThatThrownBy(() -> useCase.aggregate("test", SearchScope.NOVEL, 20))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("novel must not be null");
        }

        @Test
        @DisplayName("aggregate fails fast with NullPointerException when scoped COMMUNITY search returns null")
        void aggregate_whenScopedCommunityReturnsNull_throwsNullPointerException() {
            when(userIdentityContract.searchPublicUsers("test", 20)).thenReturn(null);

            assertThatThrownBy(() -> useCase.aggregate("test", SearchScope.COMMUNITY, 20))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("community must not be null");
        }
    }

    private WikiNavigationalSearchItemDTO createWikiItem(String title, String slug) {
        return new WikiNavigationalSearchItemDTO(
                UUID.randomUUID(),
                ArticleType.CHARACTER,
                title,
                slug,
                "/wiki/nhan-vat/" + slug,
                null
        );
    }

    private NovelChapterLocatorItemDTO createNovelItem(int chapterNumber, String title, String slug) {
        return new NovelChapterLocatorItemDTO(
                UUID.randomUUID(),
                chapterNumber,
                title,
                slug,
                1,
                "Quyển 1"
        );
    }

    private UserPublicProfileDTO createUserProfile(String displayName, String publicHandle, String avatarUrl) {
        return new UserPublicProfileDTO(
                UUID.randomUUID(),
                displayName,
                avatarUrl,
                publicHandle
        );
    }
}
