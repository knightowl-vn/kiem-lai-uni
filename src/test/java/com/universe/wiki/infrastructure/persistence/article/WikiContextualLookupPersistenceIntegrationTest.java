package com.universe.wiki.infrastructure.persistence.article;

import com.universe.wiki.application.article.query.lookup.WikiContextualLookupService;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiContextualLookupResultDTO;
import com.universe.wiki.contracts.interfaces.WikiContextualLookupContract;
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
        WikiContextualLookupService.class
})
class WikiContextualLookupPersistenceIntegrationTest {

    private static final String TEST_USER_ID =
            "99999999-9999-9999-9999-999999999999";

    @Autowired
    private WikiArticleQueryPort wikiArticleQueryPort;

    @Autowired
    private WikiContextualLookupContract wikiContextualLookupContract;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        String testDbPass = System.getProperty("test.mysql.pass");
        if (testDbPass == null || testDbPass.isBlank()) {
            testDbPass = System.getenv("TEST_MYSQL_PASS");
        }
        if (testDbPass == null || testDbPass.isBlank()) {
            testDbPass = System.getenv("DB_PASSWORD");
        }
        if (testDbPass == null || testDbPass.isBlank()) {
            testDbPass = "123456";
        }

        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/kiemlai_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "123456");
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
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
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE created_by = ?", TEST_USER_ID);
    }

    private void seedArticle(UUID id, String title, String slug, String articleType, String status) {
        Instant now = Instant.now();
        boolean isPublished = "PUBLISHED".equals(status);
        boolean isArchived = "ARCHIVED".equals(status);
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
                "Tóm tắt bài viết " + title,
                "Nội dung chi tiết " + title,
                status,
                TEST_USER_ID,
                TEST_USER_ID,
                isPublished ? TEST_USER_ID : null,
                isArchived ? TEST_USER_ID : null,
                Timestamp.from(now),
                Timestamp.from(now),
                isPublished ? Timestamp.from(now) : null,
                isArchived ? Timestamp.from(now) : null
        );
    }

    @Test
    @DisplayName("1. Ranking order: exact match first, prefix second, contains third")
    void shouldRankExactFirstPrefixSecondContainsThird() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        UUID id4 = UUID.randomUUID();

        // Seed in non-ordered sequence
        seedArticle(id3, "Kiếm Linh của Trần Bình An", "kiem-linh-tba", "ITEM", "PUBLISHED");
        seedArticle(id1, "Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED");
        seedArticle(id2, "Trần Bình An (Nhất Khí)", "tran-binh-an-nhat-khi", "CHARACTER", "PUBLISHED");
        seedArticle(id4, "Lạc Phách Sơn", "lac-phach-son", "LOCATION", "PUBLISHED");

        WikiContextualLookupResultDTO result =
                wikiContextualLookupContract.lookupByTitle("trần bình an");

        assertThat(result.query()).isEqualTo("trần bình an");
        assertThat(result.hasExactMatch()).isTrue();
        assertThat(result.items()).hasSize(3);

        // Exact match must be 1st
        assertThat(result.items().get(0).id()).isEqualTo(id1);
        assertThat(result.items().get(0).title()).isEqualTo("Trần Bình An");

        // Prefix match must be 2nd
        assertThat(result.items().get(1).id()).isEqualTo(id2);
        assertThat(result.items().get(1).title()).isEqualTo("Trần Bình An (Nhất Khí)");

        // Contains match must be 3rd
        assertThat(result.items().get(2).id()).isEqualTo(id3);
        assertThat(result.items().get(2).title()).isEqualTo("Kiếm Linh của Trần Bình An");
    }

    @Test
    @DisplayName("2. Published-only filter: Draft and Archived articles are never returned")
    void shouldExcludeNonPublishedArticles() {
        UUID publishedId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        UUID archivedId = UUID.randomUUID();

        seedArticle(publishedId, "Tề Tĩnh Xuân", "te-tinh-xuan", "CHARACTER", "PUBLISHED");
        seedArticle(draftId, "Tề Tĩnh Xuân (Bản thảo)", "te-tinh-xuan-draft", "CHARACTER", "DRAFT");
        seedArticle(archivedId, "Tề Tĩnh Xuân (Lưu trữ)", "te-tinh-xuan-archived", "CHARACTER", "ARCHIVED");

        WikiContextualLookupResultDTO result =
                wikiContextualLookupContract.lookupByTitle("tề tĩnh xuân");

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(publishedId);
        assertThat(result.items().get(0).title()).isEqualTo("Tề Tĩnh Xuân");
    }

    @Test
    @DisplayName("3. Result limit: Maximum 5 articles returned")
    void shouldCapResultsAtFive() {
        for (int i = 1; i <= 7; i++) {
            seedArticle(
                    UUID.randomUUID(),
                    "Kiếm Lai Đại Lục Quyển " + i,
                    "kiem-lai-dai-luc-" + i,
                    "WORLD",
                    "PUBLISHED"
            );
        }

        WikiContextualLookupResultDTO result =
                wikiContextualLookupContract.lookupByTitle("Kiếm Lai Đại Lục");

        assertThat(result.items()).hasSize(5);
    }

    @Test
    @DisplayName("4. Literal escaping: %, _, and backslash are treated literally and do not act as wildcards")
    void shouldTreatWildcardsLiterally() {
        UUID literalId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        seedArticle(literalId, "Khuyến mãi 50%_discount\\tổng", "khuyen-mai-50", "ITEM", "PUBLISHED");
        seedArticle(otherId, "Khuyến mãi 50000000000", "khuyen-mai-5000", "ITEM", "PUBLISHED");

        // Query with literal 50%
        WikiContextualLookupResultDTO percentResult =
                wikiContextualLookupContract.lookupByTitle("50%");

        assertThat(percentResult.items()).hasSize(1);
        assertThat(percentResult.items().get(0).id()).isEqualTo(literalId);

        // Query with literal %_
        WikiContextualLookupResultDTO percentUnderscoreResult =
                wikiContextualLookupContract.lookupByTitle("%_discount\\");

        assertThat(percentUnderscoreResult.items()).hasSize(1);
        assertThat(percentUnderscoreResult.items().get(0).id()).isEqualTo(literalId);

        // Query with non-matching pattern should not match % as arbitrary wildcard
        WikiContextualLookupResultDTO nonMatchResult =
                wikiContextualLookupContract.lookupByTitle("50_discount");

        assertThat(nonMatchResult.items()).isEmpty();
    }
}