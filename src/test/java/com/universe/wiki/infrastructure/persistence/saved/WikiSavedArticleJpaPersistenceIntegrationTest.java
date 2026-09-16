package com.universe.wiki.infrastructure.persistence.saved;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.exceptions.DuplicateWikiSavedArticleException;
import com.universe.wiki.contracts.dto.saved.SavedWikiArticlePageDTO;
import com.universe.wiki.domain.saved.UserSavedWikiArticle;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        WikiSavedArticlePersistenceAdapter.class,
        WikiSavedArticlesQueryPersistenceAdapter.class
})
@DisplayName("WikiSavedArticle JPA Persistence Integration Tests")
class WikiSavedArticleJpaPersistenceIntegrationTest {

    private static final UUID USER_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID ARTICLE_1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ARTICLE_2 = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ARTICLE_3 = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static final UUID ADMIN_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WikiSavedArticlePersistenceAdapter persistenceAdapter;

    @Autowired
    private WikiSavedArticlesQueryPersistenceAdapter queryAdapter;

    @BeforeEach
    void cleanUpAndSeedArticles() {
        cleanData();

        // Seed 3 wiki articles directly via jdbcTemplate
        insertArticle(ARTICLE_1, "Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED", "Nhân vật chính");
        insertArticle(ARTICLE_2, "Ninh Diêu", "ninh-dieu", "CHARACTER", "DRAFT", "Bản nháp Ninh Diêu");
        insertArticle(ARTICLE_3, "Kiếm Khí Trường Thành", "kiem-khi-truong-thanh", "LOCATION", "PUBLISHED", "Trường thành");
    }

    @AfterEach
    void cleanUp() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.update("DELETE FROM wiki_saved_articles");
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE id IN (?, ?, ?)",
                ARTICLE_1.toString(), ARTICLE_2.toString(), ARTICLE_3.toString());
    }

    private void insertArticle(UUID id, String title, String slug, String type, String status, String summary) {
        Timestamp now = Timestamp.from(Instant.now());
        String publishedBy = "PUBLISHED".equalsIgnoreCase(status) ? ADMIN_ID.toString() : null;
        Timestamp publishedAt = "PUBLISHED".equalsIgnoreCase(status) ? now : null;

        jdbcTemplate.update("""
                INSERT INTO wiki_articles (
                    id, title, slug, article_type, status, summary, content,
                    created_by, created_at, updated_at, published_by, published_at,
                    aggregate_version, content_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)
                """,
                id.toString(), title, slug, type, status, summary, "# Content",
                ADMIN_ID.toString(), now, now, publishedBy, publishedAt
        );
    }

    @Test
    @DisplayName("Lưu và kiểm tra tồn tại UserSavedWikiArticle qua persistence adapter")
    void shouldSaveAndFindSavedArticle() {
        UUID savedId = UUID.randomUUID();
        Instant now = Instant.now();
        UserSavedWikiArticle domain = UserSavedWikiArticle.create(savedId, USER_1, ARTICLE_1, now);

        persistenceAdapter.save(domain);

        assertThat(persistenceAdapter.existsByUserIdAndArticleId(USER_1, ARTICLE_1)).isTrue();

        String insertedId = jdbcTemplate.queryForObject(
                "SELECT id FROM wiki_saved_articles WHERE user_id = ? AND article_id = ?",
                String.class,
                USER_1.toString(),
                ARTICLE_1.toString()
        );
        assertThat(insertedId).isEqualTo(savedId.toString());
    }

    @Test
    @DisplayName("Ràng buộc UNIQUE(user_id, article_id) tại database: ném DuplicateWikiSavedArticleException khi trùng lặp")
    void shouldEnforceUniqueUserArticleConstraint() {
        UUID savedId1 = UUID.randomUUID();
        UUID savedId2 = UUID.randomUUID();
        Instant now = Instant.now();

        UserSavedWikiArticle first = UserSavedWikiArticle.create(savedId1, USER_1, ARTICLE_1, now);
        persistenceAdapter.save(first);

        UserSavedWikiArticle duplicate = UserSavedWikiArticle.create(savedId2, USER_1, ARTICLE_1, now.plusSeconds(5));

        assertThatThrownBy(() -> persistenceAdapter.save(duplicate))
                .isInstanceOf(DuplicateWikiSavedArticleException.class);
    }

    @Test
    @DisplayName("Cô lập người dùng và sắp xếp mới nhất lên đầu (createdAt DESC, id DESC)")
    void shouldEnforceUserIsolationAndDeterministicOrdering() {
        Instant t1 = Instant.parse("2026-09-16T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-16T11:00:00Z");
        Instant t3 = Instant.parse("2026-09-16T12:00:00Z");

        UUID s1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID s2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID s3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        // USER_1 lưu ARTICLE_1 (t1), ARTICLE_2 (t2, DRAFT), ARTICLE_3 (t3)
        persistenceAdapter.save(UserSavedWikiArticle.create(s1, USER_1, ARTICLE_1, t1));
        persistenceAdapter.save(UserSavedWikiArticle.create(s2, USER_1, ARTICLE_2, t2));
        persistenceAdapter.save(UserSavedWikiArticle.create(s3, USER_1, ARTICLE_3, t3));

        // USER_2 lưu ARTICLE_1
        persistenceAdapter.save(UserSavedWikiArticle.create(UUID.randomUUID(), USER_2, ARTICLE_1, t2));

        SavedWikiArticlePageDTO user1Page = queryAdapter.findSavedArticles(USER_1, 0, 20);
        assertThat(user1Page.totalElements()).isEqualTo(3);
        assertThat(user1Page.items()).hasSize(3);

        // Thứ tự giảm dần theo createdAt: s3 (t3) -> s2 (t2) -> s1 (t1)
        assertThat(user1Page.items().get(0).savedId()).isEqualTo(s3);
        assertThat(user1Page.items().get(0).available()).isTrue();
        assertThat(user1Page.items().get(0).title()).isEqualTo("Kiếm Khí Trường Thành");

        assertThat(user1Page.items().get(1).savedId()).isEqualTo(s2);
        assertThat(user1Page.items().get(1).available()).isFalse();
        assertThat(user1Page.items().get(1).title()).isNull(); // Unavailable article must not leak title

        assertThat(user1Page.items().get(2).savedId()).isEqualTo(s1);
        assertThat(user1Page.items().get(2).available()).isTrue();
        assertThat(user1Page.items().get(2).title()).isEqualTo("Trần Bình An");

        // USER_2 chỉ thấy bản ghi của mình
        SavedWikiArticlePageDTO user2Page = queryAdapter.findSavedArticles(USER_2, 0, 20);
        assertThat(user2Page.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("ON DELETE CASCADE: Khi bài viết Wiki bị xóa khỏi wiki_articles, bản ghi wiki_saved_articles tự động bị xóa theo FK")
    void shouldCascadeDeleteWhenWikiArticleIsDeleted() {
        UUID savedId = UUID.randomUUID();
        persistenceAdapter.save(UserSavedWikiArticle.create(savedId, USER_1, ARTICLE_1, Instant.now()));

        assertThat(persistenceAdapter.existsByUserIdAndArticleId(USER_1, ARTICLE_1)).isTrue();

        // Xóa bài viết khỏi bảng cha wiki_articles
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE id = ?", ARTICLE_1.toString());

        // Kiểm tra bản ghi trong wiki_saved_articles đã tự động bị xóa do ON DELETE CASCADE
        assertThat(persistenceAdapter.existsByUserIdAndArticleId(USER_1, ARTICLE_1)).isFalse();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_saved_articles WHERE id = ?",
                Integer.class,
                savedId.toString()
        );
        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("Xóa bài viết đã lưu qua adapter deleteByUserIdAndArticleId")
    void shouldDeleteSavedArticleSuccessfully() {
        UUID savedId = UUID.randomUUID();
        persistenceAdapter.save(UserSavedWikiArticle.create(savedId, USER_1, ARTICLE_1, Instant.now()));

        boolean deleted = persistenceAdapter.deleteByUserIdAndArticleId(USER_1, ARTICLE_1);
        assertThat(deleted).isTrue();

        assertThat(persistenceAdapter.existsByUserIdAndArticleId(USER_1, ARTICLE_1)).isFalse();

        // Xóa lại (idempotent) trả về false nhưng không lỗi
        boolean deletedAgain = persistenceAdapter.deleteByUserIdAndArticleId(USER_1, ARTICLE_1);
        assertThat(deletedAgain).isFalse();
    }
}
