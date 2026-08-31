package com.universe.wiki.infrastructure.persistence.article;

import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class WikiArticleAliasJpaPersistenceIntegrationTest {

    private static final String TEST_USER_ID =
            "99999999-9999-9999-9999-999999999999";

    @Autowired
    private SpringDataWikiArticleAliasJpaRepository aliasRepository;

    @Autowired
    private SpringDataWikiArticleJpaRepository articleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @BeforeEach
    void cleanUpDatabase() {
        cleanAllTestData();
    }

    @AfterEach
    void tearDown() {
        cleanAllTestData();
    }

    private void cleanAllTestData() {
        jdbcTemplate.update("DELETE FROM wiki_article_aliases WHERE created_at >= '2020-01-01'");
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE created_by = ?", TEST_USER_ID);
    }

    @Test
    @DisplayName("Lưu và truy vấn danh sách alias theo articleId và normalizedAlias")
    void shouldSaveAndQueryAliasesByArticleIdAndNormalizedAlias() {
        String articleId = insertArticle("Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED");

        WikiArticleAliasJpaEntity alias1 = new WikiArticleAliasJpaEntity(
                UUID.randomUUID().toString(),
                articleId,
                "Tiểu Phu Tử",
                "tiểu phu tử",
                Instant.now()
        );
        WikiArticleAliasJpaEntity alias2 = new WikiArticleAliasJpaEntity(
                UUID.randomUUID().toString(),
                articleId,
                "Trần Tiên Sinh",
                "trần tiên sinh",
                Instant.now()
        );

        aliasRepository.saveAll(List.of(alias1, alias2));

        List<WikiArticleAliasJpaEntity> byArticle = aliasRepository.findAllByArticleIdOrderByCreatedAtAsc(articleId);
        assertThat(byArticle).hasSize(2);
        assertThat(byArticle).extracting(WikiArticleAliasJpaEntity::getAlias)
                .containsExactlyInAnyOrder("Tiểu Phu Tử", "Trần Tiên Sinh");

        List<WikiArticleAliasJpaEntity> byNormalized = aliasRepository.findByNormalizedAlias("tiểu phu tử");
        assertThat(byNormalized).hasSize(1);
        assertThat(byNormalized.get(0).getArticleId()).isEqualTo(articleId);
        assertThat(byNormalized.get(0).getAlias()).isEqualTo("Tiểu Phu Tử");
    }

    @Test
    @DisplayName("Nhiều bài viết khác nhau có thể dùng chung một alias (không có global UNIQUE)")
    void shouldAllowSharedAliasesAcrossDifferentArticles() {
        String article1 = insertArticle("Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED");
        String article2 = insertArticle("Tề Tĩnh Xuân", "te-tinh-xuan", "CHARACTER", "PUBLISHED");

        WikiArticleAliasJpaEntity alias1 = new WikiArticleAliasJpaEntity(
                UUID.randomUUID().toString(),
                article1,
                "Tiên Sinh",
                "tiên sinh",
                Instant.now()
        );
        WikiArticleAliasJpaEntity alias2 = new WikiArticleAliasJpaEntity(
                UUID.randomUUID().toString(),
                article2,
                "Tiên Sinh",
                "tiên sinh",
                Instant.now()
        );

        aliasRepository.saveAll(List.of(alias1, alias2));

        List<WikiArticleAliasJpaEntity> sharedMatches = aliasRepository.findByNormalizedAlias("tiên sinh");
        assertThat(sharedMatches).hasSize(2);
        assertThat(sharedMatches).extracting(WikiArticleAliasJpaEntity::getArticleId)
                .containsExactlyInAnyOrder(article1, article2);
    }

    @Test
    @DisplayName("Trùng lặp (article_id, normalized_alias) trên cùng 1 bài viết sẽ vi phạm UNIQUE constraint")
    void shouldRejectDuplicateAliasForSameArticle() {
        String articleId = insertArticle("Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED");

        WikiArticleAliasJpaEntity alias1 = new WikiArticleAliasJpaEntity(
                UUID.randomUUID().toString(),
                articleId,
                "Tiểu Phu Tử",
                "tiểu phu tử",
                Instant.now()
        );
        aliasRepository.save(alias1);

        WikiArticleAliasJpaEntity duplicateAlias = new WikiArticleAliasJpaEntity(
                UUID.randomUUID().toString(),
                articleId,
                "tiểu   phu tử",
                "tiểu phu tử",
                Instant.now()
        );

        assertThatThrownBy(() -> aliasRepository.saveAndFlush(duplicateAlias))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Xóa bài viết cha sẽ CASCADE xóa toàn bộ alias của bài viết đó")
    void shouldCascadeDeleteAliasesWhenArticleIsDeleted() {
        String articleId = insertArticle("Trần Bình An", "tran-binh-an", "CHARACTER", "DRAFT");

        WikiArticleAliasJpaEntity alias1 = new WikiArticleAliasJpaEntity(
                UUID.randomUUID().toString(),
                articleId,
                "Tiểu Phu Tử",
                "tiểu phu tử",
                Instant.now()
        );
        aliasRepository.save(alias1);

        assertThat(aliasRepository.findAllByArticleId(articleId)).hasSize(1);

        // Delete parent article
        articleRepository.deleteById(articleId);

        assertThat(aliasRepository.findAllByArticleId(articleId)).isEmpty();
    }

    @Test
    @DisplayName("findPublishedArticleIdsByNormalizedAlias chỉ trả về articleId của các bài đã PUBLISHED")
    void shouldFindPublishedArticleIdsByNormalizedAlias() {
        String publishedArticleId = insertArticle("Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED");
        String draftArticleId = insertArticle("Lục Trầm", "luc-tram", "CHARACTER", "DRAFT");

        aliasRepository.save(new WikiArticleAliasJpaEntity(
                UUID.randomUUID().toString(),
                publishedArticleId,
                "Trần Kiếm Tiên",
                "trần kiếm tiên",
                Instant.now()
        ));
        aliasRepository.save(new WikiArticleAliasJpaEntity(
                UUID.randomUUID().toString(),
                draftArticleId,
                "Trần Kiếm Tiên",
                "trần kiếm tiên",
                Instant.now()
        ));

        List<String> publishedIds = aliasRepository.findPublishedArticleIdsByNormalizedAlias("trần kiếm tiên");
        assertThat(publishedIds).containsExactly(publishedArticleId);
    }

    private String insertArticle(String title, String slug, String articleType, String status) {
        String articleId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        boolean isPublished = "PUBLISHED".equals(status);
        boolean isArchived = "ARCHIVED".equals(status);

        String sql = """
                INSERT INTO wiki_articles (
                    id, title, slug, article_type, summary, content, status,
                    created_by, updated_by, published_by, archived_by,
                    aggregate_version, persistence_version,
                    created_at, updated_at, published_at, archived_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, 1, 0,
                    ?, ?, ?, ?
                )
                """;

        jdbcTemplate.update(
                sql,
                articleId,
                title,
                slug,
                articleType,
                "Summary of " + title,
                "Content of " + title,
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

        return articleId;
    }
}