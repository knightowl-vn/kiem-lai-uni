package com.universe.wiki.infrastructure.persistence.article;

import com.universe.wiki.application.article.query.WikiArticleService;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.interfaces.WikiArticleContract;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WikiArticleQueryAdapter.class, WikiArticleService.class})
@DisplayName("Published Wiki Article Lookup by UUID Persistence Integration Tests (MySQL)")
class WikiArticlePublishedLookupPersistenceIntegrationTest {

    private static final String TEST_USER_ID = "99999999-9999-9999-9999-999999999999";

    @Autowired
    private WikiArticleQueryPort wikiArticleQueryPort;

    @Autowired
    private WikiArticleContract wikiArticleContract;

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
        final String finalPass = (testDbPass != null && !testDbPass.isBlank()) ? testDbPass : "123456";

        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/kiemlai_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> finalPass);
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
        jdbcTemplate.update("DELETE FROM wiki_article_aliases WHERE article_id IN (SELECT id FROM wiki_articles WHERE created_by = ?)", TEST_USER_ID);
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
                "Tóm tắt " + title,
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
    @DisplayName("Should find published article by UUID when status is PUBLISHED")
    void shouldFindPublishedArticleByIdWhenStatusIsPublished() {
        UUID publishedArticleId = UUID.randomUUID();
        seedArticle(publishedArticleId, "Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED");

        Optional<PublishedWikiArticleDTO> queryPortResult = wikiArticleQueryPort.findPublishedById(publishedArticleId);
        assertThat(queryPortResult).isPresent();
        PublishedWikiArticleDTO dto = queryPortResult.get();
        assertThat(dto.id()).isEqualTo(publishedArticleId);
        assertThat(dto.title()).isEqualTo("Trần Bình An");
        assertThat(dto.slug()).isEqualTo("tran-binh-an");
        assertThat(dto.articleType()).isEqualTo("CHARACTER");
        assertThat(dto.summary()).isEqualTo("Tóm tắt Trần Bình An");

        Optional<PublishedWikiArticleDTO> contractResult = wikiArticleContract.findPublishedById(publishedArticleId);
        assertThat(contractResult).isPresent();
        assertThat(contractResult.get().id()).isEqualTo(publishedArticleId);
    }

    @Test
    @DisplayName("Should return empty Optional when article status is DRAFT")
    void shouldReturnEmptyWhenArticleStatusIsDraft() {
        UUID draftArticleId = UUID.randomUUID();
        seedArticle(draftArticleId, "Bản Thảo Kiếm Pháp", "ban-thao-kiem-phap", "TECHNIQUE", "DRAFT");

        Optional<PublishedWikiArticleDTO> queryPortResult = wikiArticleQueryPort.findPublishedById(draftArticleId);
        assertThat(queryPortResult).isEmpty();

        Optional<PublishedWikiArticleDTO> contractResult = wikiArticleContract.findPublishedById(draftArticleId);
        assertThat(contractResult).isEmpty();
    }

    @Test
    @DisplayName("Should return empty Optional when article status is ARCHIVED")
    void shouldReturnEmptyWhenArticleStatusIsArchived() {
        UUID archivedArticleId = UUID.randomUUID();
        seedArticle(archivedArticleId, "Địa Danh Cũ", "dia-danh-cu", "LOCATION", "ARCHIVED");

        Optional<PublishedWikiArticleDTO> queryPortResult = wikiArticleQueryPort.findPublishedById(archivedArticleId);
        assertThat(queryPortResult).isEmpty();

        Optional<PublishedWikiArticleDTO> contractResult = wikiArticleContract.findPublishedById(archivedArticleId);
        assertThat(contractResult).isEmpty();
    }

    @Test
    @DisplayName("Should return empty Optional when article UUID does not exist")
    void shouldReturnEmptyWhenArticleIdIsUnknown() {
        UUID unknownId = UUID.randomUUID();

        Optional<PublishedWikiArticleDTO> queryPortResult = wikiArticleQueryPort.findPublishedById(unknownId);
        assertThat(queryPortResult).isEmpty();

        Optional<PublishedWikiArticleDTO> contractResult = wikiArticleContract.findPublishedById(unknownId);
        assertThat(contractResult).isEmpty();
    }

    @Test
    @DisplayName("Should return empty Optional when article UUID is null")
    void shouldReturnEmptyWhenArticleIdIsNull() {
        assertThat(wikiArticleQueryPort.findPublishedById(null)).isEmpty();
        assertThat(wikiArticleContract.findPublishedById(null)).isEmpty();
    }
}
