package com.universe.wiki.infrastructure.persistence.appreciation;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.exceptions.DuplicateWikiAppreciationException;
import com.universe.wiki.domain.appreciation.WikiAppreciationRating;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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
        WikiAppreciationPersistenceAdapter.class
})
@DisplayName("WikiAppreciation JPA Persistence Integration Tests")
class WikiAppreciationJpaPersistenceIntegrationTest {

    private static final UUID USER_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID ARTICLE_1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ARTICLE_2 = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final UUID ADMIN_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WikiAppreciationPersistenceAdapter persistenceAdapter;

    @BeforeEach
    void cleanUpAndSeedArticles() {
        cleanData();

        // Seed 2 wiki articles directly via jdbcTemplate
        insertArticle(ARTICLE_1, "Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED", "Nhân vật chính");
        insertArticle(ARTICLE_2, "Thần Tú Phong", "than-tu-phong", "FACTION", "PUBLISHED", "Tông môn / Tiên phong");
    }

    @AfterEach
    void cleanUp() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.update("DELETE FROM wiki_appreciation_ratings");
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE id IN (?, ?)",
                ARTICLE_1.toString(), ARTICLE_2.toString());
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
    @DisplayName("A. Lưu thành công bản ghi đánh giá 1 sao (boundary value)")
    void shouldPersistValidRowWithOneStar() {
        UUID ratingId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WikiAppreciationRating rating = WikiAppreciationRating.create(ratingId, ARTICLE_1, USER_1, 1, now);

        WikiAppreciationRating saved = persistenceAdapter.save(rating);

        assertThat(saved).isNotNull();
        assertThat(saved.getValue()).isEqualTo(1);

        Integer dbValue = jdbcTemplate.queryForObject(
                "SELECT value FROM wiki_appreciation_ratings WHERE id = ?",
                Integer.class,
                ratingId.toString()
        );
        assertThat(dbValue).isEqualTo(1);
    }

    @Test
    @DisplayName("B. Lưu thành công bản ghi đánh giá 5 sao (boundary value)")
    void shouldPersistValidRowWithFiveStars() {
        UUID ratingId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WikiAppreciationRating rating = WikiAppreciationRating.create(ratingId, ARTICLE_1, USER_1, 5, now);

        WikiAppreciationRating saved = persistenceAdapter.save(rating);

        assertThat(saved).isNotNull();
        assertThat(saved.getValue()).isEqualTo(5);

        Integer dbValue = jdbcTemplate.queryForObject(
                "SELECT value FROM wiki_appreciation_ratings WHERE id = ?",
                Integer.class,
                ratingId.toString()
        );
        assertThat(dbValue).isEqualTo(5);
    }

    @Test
    @DisplayName("C. Ràng buộc UNIQUE(wiki_article_id, user_id): từ chối bản ghi trùng lặp cho cùng một người dùng và bài viết")
    void shouldRejectDuplicateArticleAndUserRating() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiAppreciationRating first = WikiAppreciationRating.create(id1, ARTICLE_1, USER_1, 4, now);
        persistenceAdapter.save(first);

        WikiAppreciationRating duplicate = WikiAppreciationRating.create(id2, ARTICLE_1, USER_1, 5, now.plusSeconds(10));

        assertThatThrownBy(() -> persistenceAdapter.save(duplicate))
                .isInstanceOf(DuplicateWikiAppreciationException.class)
                .hasMessageContaining(ARTICLE_1.toString())
                .hasMessageContaining(USER_1.toString());
    }

    @Test
    @DisplayName("D. Cho phép cùng một người dùng đánh giá nhiều bài viết khác nhau")
    void shouldAllowSameUserRatingDifferentArticles() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        persistenceAdapter.save(WikiAppreciationRating.create(id1, ARTICLE_1, USER_1, 5, now));
        persistenceAdapter.save(WikiAppreciationRating.create(id2, ARTICLE_2, USER_1, 4, now));

        Optional<WikiAppreciationRating> r1 = persistenceAdapter.findByWikiArticleIdAndUserId(ARTICLE_1, USER_1);
        Optional<WikiAppreciationRating> r2 = persistenceAdapter.findByWikiArticleIdAndUserId(ARTICLE_2, USER_1);

        assertThat(r1).isPresent();
        assertThat(r1.get().getValue()).isEqualTo(5);
        assertThat(r2).isPresent();
        assertThat(r2.get().getValue()).isEqualTo(4);
    }

    @Test
    @DisplayName("E. Cho phép nhiều người dùng khác nhau cùng đánh giá một bài viết")
    void shouldAllowDifferentUsersRatingSameArticle() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        persistenceAdapter.save(WikiAppreciationRating.create(id1, ARTICLE_1, USER_1, 5, now));
        persistenceAdapter.save(WikiAppreciationRating.create(id2, ARTICLE_1, USER_2, 3, now));

        Optional<WikiAppreciationRating> r1 = persistenceAdapter.findByWikiArticleIdAndUserId(ARTICLE_1, USER_1);
        Optional<WikiAppreciationRating> r2 = persistenceAdapter.findByWikiArticleIdAndUserId(ARTICLE_1, USER_2);

        assertThat(r1).isPresent();
        assertThat(r1.get().getValue()).isEqualTo(5);
        assertThat(r2).isPresent();
        assertThat(r2.get().getValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("F. Database từ chối giá trị value = 0 do vi phạm CHECK constraint")
    void shouldRejectValueZeroAtDatabaseLevel() {
        UUID ratingId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wiki_appreciation_ratings (id, wiki_article_id, user_id, value, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?)
                """,
                ratingId.toString(), ARTICLE_1.toString(), USER_1.toString(), now, now
        )).hasMessageContaining("chk_wiki_appreciation_ratings_value");
    }

    @Test
    @DisplayName("G. Database từ chối giá trị value = 6 do vi phạm CHECK constraint")
    void shouldRejectValueSixAtDatabaseLevel() {
        UUID ratingId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wiki_appreciation_ratings (id, wiki_article_id, user_id, value, created_at, updated_at)
                VALUES (?, ?, ?, 6, ?, ?)
                """,
                ratingId.toString(), ARTICLE_1.toString(), USER_1.toString(), now, now
        )).hasMessageContaining("chk_wiki_appreciation_ratings_value");
    }

    @Test
    @DisplayName("H. Foreign Key: từ chối lưu đánh giá với wiki_article_id không tồn tại")
    void shouldRejectNonexistentWikiArticleId() {
        UUID nonExistentArticleId = UUID.randomUUID();
        UUID ratingId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiAppreciationRating rating = WikiAppreciationRating.create(ratingId, nonExistentArticleId, USER_1, 5, now);

        assertThatThrownBy(() -> persistenceAdapter.save(rating))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("I. ON DELETE CASCADE: khi bài viết cha bị xóa cứng, các bản ghi appreciation liên quan tự động bị xóa")
    void shouldCascadeDeleteAppreciationRatingsWhenArticleIsDeleted() {
        UUID ratingId1 = UUID.randomUUID();
        UUID ratingId2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        persistenceAdapter.save(WikiAppreciationRating.create(ratingId1, ARTICLE_1, USER_1, 5, now));
        persistenceAdapter.save(WikiAppreciationRating.create(ratingId2, ARTICLE_1, USER_2, 4, now));

        assertThat(persistenceAdapter.findByWikiArticleIdAndUserId(ARTICLE_1, USER_1)).isPresent();
        assertThat(persistenceAdapter.findByWikiArticleIdAndUserId(ARTICLE_1, USER_2)).isPresent();

        // Xóa bài viết khỏi bảng cha wiki_articles
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE id = ?", ARTICLE_1.toString());

        // Cả 2 bản ghi đánh giá của ARTICLE_1 phải tự động bị xóa
        assertThat(persistenceAdapter.findByWikiArticleIdAndUserId(ARTICLE_1, USER_1)).isEmpty();
        assertThat(persistenceAdapter.findByWikiArticleIdAndUserId(ARTICLE_1, USER_2)).isEmpty();

        Integer remainingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_appreciation_ratings WHERE wiki_article_id = ?",
                Integer.class,
                ARTICLE_1.toString()
        );
        assertThat(remainingCount).isEqualTo(0);
    }

    @Test
    @DisplayName("J. Persistence round-trip: bảo toàn chính xác id, article, user, value, createdAt, updatedAt")
    void shouldPreserveAllFieldsInPersistenceRoundTrip() {
        UUID ratingId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-22T08:30:00.123456Z");
        Instant updatedAt = Instant.parse("2026-09-22T09:45:00.654321Z");

        WikiAppreciationRating domain = WikiAppreciationRating.rehydrate(
                ratingId,
                ARTICLE_1,
                USER_1,
                4,
                createdAt,
                updatedAt
        );

        persistenceAdapter.save(domain);

        Optional<WikiAppreciationRating> fetched = persistenceAdapter.findByWikiArticleIdAndUserId(ARTICLE_1, USER_1);

        assertThat(fetched).isPresent();
        WikiAppreciationRating actual = fetched.get();
        assertThat(actual.getId()).isEqualTo(ratingId);
        assertThat(actual.getWikiArticleId()).isEqualTo(ARTICLE_1);
        assertThat(actual.getUserId()).isEqualTo(USER_1);
        assertThat(actual.getValue()).isEqualTo(4);
        assertThat(actual.getCreatedAt()).isEqualTo(createdAt);
        assertThat(actual.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("K. Repository find by article+user: trả về chính xác domain aggregate sau khi cập nhật giá trị")
    void shouldFindExactDomainObjectAfterUpdate() {
        UUID ratingId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-22T10:00:00Z");
        WikiAppreciationRating initial = WikiAppreciationRating.create(ratingId, ARTICLE_1, USER_1, 3, t1);
        persistenceAdapter.save(initial);

        // Cập nhật giá trị 3 -> 5
        Instant t2 = Instant.parse("2026-09-22T10:15:00Z");
        initial.updateValue(5, t2);
        persistenceAdapter.save(initial);

        Optional<WikiAppreciationRating> updated = persistenceAdapter.findByWikiArticleIdAndUserId(ARTICLE_1, USER_1);

        assertThat(updated).isPresent();
        assertThat(updated.get().getId()).isEqualTo(ratingId);
        assertThat(updated.get().getValue()).isEqualTo(5);
        assertThat(updated.get().getCreatedAt()).isEqualTo(t1);
        assertThat(updated.get().getUpdatedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("L. Missing lookup: trả về Optional rỗng khi chưa có đánh giá hoặc ID không tồn tại")
    void shouldReturnEmptyWhenNoRatingExists() {
        Optional<WikiAppreciationRating> missing = persistenceAdapter.findByWikiArticleIdAndUserId(
                ARTICLE_1,
                UUID.randomUUID()
        );
        assertThat(missing).isEmpty();

        Optional<WikiAppreciationRating> nullArticle = persistenceAdapter.findByWikiArticleIdAndUserId(
                null,
                USER_1
        );
        assertThat(nullArticle).isEmpty();

        Optional<WikiAppreciationRating> nullUser = persistenceAdapter.findByWikiArticleIdAndUserId(
                ARTICLE_1,
                null
        );
        assertThat(nullUser).isEmpty();
    }
}
