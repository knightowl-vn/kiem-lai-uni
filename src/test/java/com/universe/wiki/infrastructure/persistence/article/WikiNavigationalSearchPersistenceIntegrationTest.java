package com.universe.wiki.infrastructure.persistence.article;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.article.query.search.WikiArticleAliasSearchMatchDTO;
import com.universe.wiki.application.article.query.search.WikiNavigationalSearchUseCase;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchItemDTO;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchResultDTO;
import com.universe.wiki.contracts.interfaces.WikiNavigationalSearchContract;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        WikiArticleQueryAdapter.class,
        ArticleTypePathMapper.class,
        WikiNavigationalSearchUseCase.class
})
class WikiNavigationalSearchPersistenceIntegrationTest {

    private static final String TEST_USER_ID = "88888888-8888-8888-8888-888888888888";

    @Autowired
    private WikiArticleQueryPort wikiArticleQueryPort;

    @Autowired
    private WikiNavigationalSearchContract wikiNavigationalSearchContract;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @BeforeEach
    void setUp() {
        cleanTestData();
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        jdbcTemplate.update("DELETE FROM wiki_article_aliases WHERE article_id IN (SELECT id FROM wiki_articles WHERE created_by = ?)", TEST_USER_ID);
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE created_by = ?", TEST_USER_ID);
    }

    private void seedArticle(UUID id, String title, String slug, String articleType, String status) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO wiki_articles (
                    id, title, slug, article_type, summary, content, status,
                    created_by, updated_by, published_by, archived_by,
                    aggregate_version, persistence_version,
                    created_at, updated_at, published_at, archived_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?, ?, ?)
                """,
                id.toString(),
                title,
                slug,
                articleType,
                "Tóm tắt " + title,
                "Nội dung " + title,
                status,
                TEST_USER_ID,
                TEST_USER_ID,
                "PUBLISHED".equals(status) ? TEST_USER_ID : null,
                "ARCHIVED".equals(status) ? TEST_USER_ID : null,
                Timestamp.from(now),
                Timestamp.from(now),
                "PUBLISHED".equals(status) ? Timestamp.from(now) : null,
                "ARCHIVED".equals(status) ? Timestamp.from(now) : null
        );
    }

    private void seedAlias(UUID id, UUID articleId, String alias, String normalizedAlias) {
        jdbcTemplate.update("""
                INSERT INTO wiki_article_aliases (
                    id, article_id, alias, normalized_alias, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                id.toString(),
                articleId.toString(),
                alias,
                normalizedAlias,
                Timestamp.from(Instant.now())
        );
    }

    // ==========================================
    // 1. CANDIDATE COMPLETENESS
    // ==========================================

    @Test
    @DisplayName("Candidate completeness: One article with many matching aliases must not hide other matching articles in alias channel")
    void shouldNotAllowOneArticleWithManyAliasesToHideOtherMatchingArticles() {
        UUID articleAId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID articleBId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Article A: title does NOT match query "Kiếm", articleType = CHARACTER, id1
        seedArticle(articleAId, "Đông Hải Thần Chủ", "dong-hai-than-chu", "CHARACTER", "PUBLISHED");
        // Article B: title does NOT match query "Kiếm", articleType = CHARACTER, id2
        seedArticle(articleBId, "Bạch Dã Tiên Tôn", "bach-da-tien-ton", "CHARACTER", "PUBLISHED");

        // Seed 25 aliases for Article A, all containing "Kiếm" (same Alias Contains tier)
        for (int i = 1; i <= 25; i++) {
            seedAlias(UUID.randomUUID(), articleAId, "Đông Hải Kiếm Ca " + String.format("%02d", i), "đông hải kiếm ca " + String.format("%02d", i));
        }

        // Seed exactly 1 alias for Article B, also containing "Kiếm" (same Alias Contains tier)
        seedAlias(UUID.randomUUID(), articleBId, "Hải Ngoại Kiếm Tu", "hải ngoại kiếm tu");

        // 1. Direct Alias Query Port check with limit = 2
        // Under old raw alias LIMIT 2, Article A's first two rows consumed both slots, dropping Article B.
        // Under SQL CTE partition grouping, Article A produces 1 row and Article B produces 1 row.
        List<WikiArticleAliasSearchMatchDTO> aliasCandidates =
                wikiArticleQueryPort.findPublishedAliasSearchCandidates("kiem", "kiem", 2);

        assertThat(aliasCandidates).hasSize(2);
        assertThat(aliasCandidates).extracting(WikiArticleAliasSearchMatchDTO::articleId)
                .containsExactly(articleAId, articleBId)
                .doesNotHaveDuplicates();

        // 2. Full search usecase contract check with limit = 2
        WikiNavigationalSearchResultDTO searchResult = wikiNavigationalSearchContract.search("Kiếm", 2);

        assertThat(searchResult.items()).hasSize(2);
        assertThat(searchResult.items()).extracting(WikiNavigationalSearchItemDTO::id)
                .containsExactly(articleAId, articleBId)
                .doesNotHaveDuplicates();
        assertThat(searchResult.items().get(0).matchedAlias()).isEqualTo("Đông Hải Kiếm Ca 01");
        assertThat(searchResult.items().get(1).matchedAlias()).isEqualTo("Hải Ngoại Kiếm Tu");
    }

    @Test
    @DisplayName("Candidate completeness: More than 20 contains matches plus an exact match returns exact match at position 0")
    void shouldReturnExactMatchEvenWhenMoreThanTwentyContainsMatchesExist() {
        // Seed 25 contains matches
        for (int i = 1; i <= 25; i++) {
            UUID id = UUID.randomUUID();
            seedArticle(id, "Đại Kiếm Khách Thứ " + i, "dai-kiem-khach-" + i, "CHARACTER", "PUBLISHED");
        }

        // Seed 1 exact match
        UUID exactTargetId = UUID.randomUUID();
        seedArticle(exactTargetId, "Kiếm Khách", "kiem-khach", "CHARACTER", "PUBLISHED");

        WikiNavigationalSearchResultDTO result = wikiNavigationalSearchContract.search("Kiếm Khách", 20);

        assertThat(result.items()).hasSize(20);
        assertThat(result.items().get(0).id()).isEqualTo(exactTargetId);
        assertThat(result.items().get(0).title()).isEqualTo("Kiếm Khách");
    }

    @Test
    @DisplayName("Candidate completeness: More than 20 contains matches plus a prefix match returns prefix match at position 0")
    void shouldReturnPrefixMatchEvenWhenMoreThanTwentyContainsMatchesExist() {
        // Seed 25 contains matches
        for (int i = 1; i <= 25; i++) {
            UUID id = UUID.randomUUID();
            seedArticle(id, "Tông Môn Kiếm Đạo " + i, "tong-mon-kiem-dao-" + i, "FACTION", "PUBLISHED");
        }

        // Seed 1 prefix match
        UUID prefixTargetId = UUID.randomUUID();
        seedArticle(prefixTargetId, "Kiếm Đạo Tông", "kiem-dao-tong", "FACTION", "PUBLISHED");

        WikiNavigationalSearchResultDTO result = wikiNavigationalSearchContract.search("Kiếm Đạo", 20);

        assertThat(result.items()).hasSize(20);
        assertThat(result.items().get(0).id()).isEqualTo(prefixTargetId);
        assertThat(result.items().get(0).title()).isEqualTo("Kiếm Đạo Tông");
    }

    @Test
    @DisplayName("Candidate completeness: Multiple exact matches are ordered deterministically by articleType and ID")
    void shouldOrderMultipleExactMatchesDeterministically() {
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID id3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        seedArticle(id2, "Kiếm Thần", "kiem-than-char-2", "CHARACTER", "PUBLISHED");
        seedArticle(id1, "Kiếm Thần", "kiem-than-char-1", "CHARACTER", "PUBLISHED");
        seedArticle(id3, "Kiếm Thần", "kiem-than-item", "ITEM", "PUBLISHED");

        WikiNavigationalSearchResultDTO result = wikiNavigationalSearchContract.search("Kiếm Thần", 20);

        assertThat(result.items()).hasSize(3);
        // Ordering key: (Rank 1, articleType ASC, id ASC)
        // CHARACTER < ITEM; id1 < id2
        assertThat(result.items().get(0).id()).isEqualTo(id1);
        assertThat(result.items().get(1).id()).isEqualTo(id2);
        assertThat(result.items().get(2).id()).isEqualTo(id3);
    }

    @Test
    @DisplayName("Candidate completeness: Same article matching both title and alias deduplicates correctly with best rank")
    void shouldDeduplicateSameArticleMatchingBothTitleAndAlias() {
        UUID articleId = UUID.randomUUID();
        // Title is "Trần Bình An" -> query "Bình" matches Title Contains (Rank 5)
        seedArticle(articleId, "Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED");
        // Alias is "Bình An Ca" -> query "Bình" matches Alias Prefix (Rank 4)
        seedAlias(UUID.randomUUID(), articleId, "Bình An Ca", "bình an ca");

        WikiNavigationalSearchResultDTO result = wikiNavigationalSearchContract.search("Bình", 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(articleId);
        // Rank 4 (Alias Prefix) beats Rank 5 (Title Contains), so matchedAlias is populated
        assertThat(result.items().get(0).matchedAlias()).isEqualTo("Bình An Ca");
    }

    // ==========================================
    // 2. PUBLICATION SAFETY
    // ==========================================

    @Test
    @DisplayName("Publication safety: Published articles/aliases included; Non-published (DRAFT/ARCHIVED) excluded")
    void shouldIncludePublishedAndExcludeNonPublishedTitleAndAliases() {
        UUID pubTitleId = UUID.randomUUID();
        UUID draftTitleId = UUID.randomUUID();
        UUID archTitleId = UUID.randomUUID();

        UUID pubAliasParentId = UUID.randomUUID();
        UUID draftAliasParentId = UUID.randomUUID();
        UUID archAliasParentId = UUID.randomUUID();

        seedArticle(pubTitleId, "Đạo Khí", "dao-khi", "ITEM", "PUBLISHED");
        seedArticle(draftTitleId, "Đạo Khí Bản Thảo", "dao-khi-draft", "ITEM", "DRAFT");
        seedArticle(archTitleId, "Đạo Khí Lưu Trữ", "dao-khi-archived", "ITEM", "ARCHIVED");

        seedArticle(pubAliasParentId, "Vạn Pháp Tông", "van-phap-tong", "FACTION", "PUBLISHED");
        seedArticle(draftAliasParentId, "Nháp Tông", "nhap-tong", "FACTION", "DRAFT");
        seedArticle(archAliasParentId, "Lưu Tông", "luu-tong", "FACTION", "ARCHIVED");

        seedAlias(UUID.randomUUID(), pubAliasParentId, "Đạo Môn Đệ Nhất", "đạo môn đệ nhất");
        seedAlias(UUID.randomUUID(), draftAliasParentId, "Đạo Môn Nháp", "đạo môn nháp");
        seedAlias(UUID.randomUUID(), archAliasParentId, "Đạo Môn Cũ", "đạo môn cũ");

        WikiNavigationalSearchResultDTO result = wikiNavigationalSearchContract.search("Đạo", 20);

        assertThat(result.items()).extracting(item -> item.id()).contains(pubTitleId, pubAliasParentId);
        assertThat(result.items()).extracting(item -> item.id()).doesNotContain(draftTitleId, archTitleId, draftAliasParentId, archAliasParentId);
    }

    // ==========================================
    // 3. LIKE LITERALS ESCAPING
    // ==========================================

    @Test
    @DisplayName("LIKE literals: Literal %, _, and \\ are queried safely without wildcard injection")
    void shouldProperlyEscapeLiteralLikeMetacharacters() {
        UUID targetId = UUID.randomUUID();
        UUID unintendedId = UUID.randomUUID();

        seedArticle(targetId, "100% Real_Deal\\Key", "real-deal", "ITEM", "PUBLISHED");
        seedArticle(unintendedId, "1008 RealXDealYKey", "unintended", "ITEM", "PUBLISHED");

        WikiNavigationalSearchResultDTO result =
                wikiNavigationalSearchContract.search("100% Real_Deal\\Key", 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(targetId);
        assertThat(result.items().get(0).title()).isEqualTo("100% Real_Deal\\Key");
    }

    // ==========================================
    // 4. RESULT BOUNDS
    // ==========================================

    @Test
    @DisplayName("Result bounds: Default limit = 20, limit < 20 respected, limit > 20 clamped to 20, never returns > 20")
    void shouldEnforceResultBounds() {
        for (int i = 1; i <= 25; i++) {
            UUID id = UUID.randomUUID();
            seedArticle(id, "Tàng Kinh Các Quyển " + i, "tang-kinh-cac-" + i, "ITEM", "PUBLISHED");
        }

        // Default limit (0 or omitted) -> 20
        WikiNavigationalSearchResultDTO defaultResult = wikiNavigationalSearchContract.search("Tàng Kinh Các", 0);
        assertThat(defaultResult.items()).hasSize(20);

        // Caller limit < 20 -> respected
        WikiNavigationalSearchResultDTO customLimitResult = wikiNavigationalSearchContract.search("Tàng Kinh Các", 7);
        assertThat(customLimitResult.items()).hasSize(7);

        // Caller limit > 20 -> clamped to 20
        WikiNavigationalSearchResultDTO clampedResult = wikiNavigationalSearchContract.search("Tàng Kinh Các", 50);
        assertThat(clampedResult.items()).hasSize(20);
    }
}

