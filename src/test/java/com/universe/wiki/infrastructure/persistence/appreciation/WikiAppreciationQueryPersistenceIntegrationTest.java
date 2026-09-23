package com.universe.wiki.infrastructure.persistence.appreciation;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.domain.appreciation.WikiAppreciationRating;
import com.universe.wiki.domain.appreciation.WikiAppreciationScore;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        WikiAppreciationPersistenceAdapter.class,
        WikiAppreciationQueryPersistenceAdapter.class
})
@DisplayName("WikiAppreciation Query Persistence Integration Tests (MS-05F3)")
class WikiAppreciationQueryPersistenceIntegrationTest {

    private static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID ARTICLE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ARTICLE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID ARTICLE_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final UUID ADMIN_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WikiAppreciationPersistenceAdapter persistenceAdapter;

    @Autowired
    private WikiAppreciationQueryPersistenceAdapter queryAdapter;

    @BeforeEach
    void cleanUpAndSeedArticles() {
        cleanData();

        insertArticle(ARTICLE_A, "Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED", "Nhân vật chính");
        insertArticle(ARTICLE_B, "Ninh Diêu", "ninh-dieu", "CHARACTER", "PUBLISHED", "Nữ kiếm tiên");
        insertArticle(ARTICLE_C, "Thần Tú Phong", "than-tu-phong", "FACTION", "PUBLISHED", "Tông môn");
    }

    @AfterEach
    void cleanUp() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.update("DELETE FROM wiki_appreciation_ratings");
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE id IN (?, ?, ?)",
                ARTICLE_A.toString(), ARTICLE_B.toString(), ARTICLE_C.toString());
    }

    private void insertArticle(UUID id, String title, String slug, String type, String status, String summary) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                INSERT INTO wiki_articles (
                    id, title, slug, article_type, status, summary, content,
                    created_by, created_at, updated_at, published_by, published_at,
                    aggregate_version, content_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)
                """,
                id.toString(), title, slug, type, status, summary, "# Content",
                ADMIN_ID.toString(), now, now, ADMIN_ID.toString(), now
        );
    }

    // =========================================================================
    // 1. SINGLE SUMMARY AGGREGATION TESTS
    // =========================================================================

    @Test
    @DisplayName("A. Single query: bài viết chưa có đánh giá trả về count=0, average=null (không ném lỗi, không trả empty)")
    void shouldReturnEmptySummaryWhenZeroRatings() {
        WikiAppreciationSummary summary = queryAdapter.findSummaryByWikiArticleId(ARTICLE_A);

        assertThat(summary).isNotNull();
        assertThat(summary.wikiArticleId()).isEqualTo(ARTICLE_A);
        assertThat(summary.count()).isEqualTo(0L);
        assertThat(summary.average()).isNull();
    }

    @Test
    @DisplayName("B. Single query: 1 đánh giá (5.0 sao) -> count=1, average=5.0 chính xác kiểu BigDecimal")
    void shouldReturnCorrectSummaryForSingleRating() {
        Instant now = Instant.now();
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_A, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("5.0")), now
        ));

        WikiAppreciationSummary summary = queryAdapter.findSummaryByWikiArticleId(ARTICLE_A);

        assertThat(summary).isNotNull();
        assertThat(summary.wikiArticleId()).isEqualTo(ARTICLE_A);
        assertThat(summary.count()).isEqualTo(1L);
        assertThat(summary.average()).isNotNull();
        assertThat(summary.average()).isEqualByComparingTo(new BigDecimal("5.0"));
        assertThat(summary.displayAverage()).isEqualByComparingTo(new BigDecimal("5.0"));
    }

    @Test
    @DisplayName("C. Single query: nhiều đánh giá (5.0, 4.0, 5.0) -> count=3, average mathematically 14/3 (khoảng 4.6667)")
    void shouldReturnCorrectSummaryForMultipleRatings() {
        Instant now = Instant.now();
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_A, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("5.0")), now
        ));
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_A, USER_B, WikiAppreciationScore.fromStars(new BigDecimal("4.0")), now
        ));
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_A, USER_C, WikiAppreciationScore.fromStars(new BigDecimal("5.0")), now
        ));

        WikiAppreciationSummary summary = queryAdapter.findSummaryByWikiArticleId(ARTICLE_A);

        assertThat(summary).isNotNull();
        assertThat(summary.wikiArticleId()).isEqualTo(ARTICLE_A);
        assertThat(summary.count()).isEqualTo(3L);
        assertThat(summary.average()).isNotNull();

        // (10 + 8 + 10) / 3 / 2.0 = 28 / 6 = 14 / 3 = 4.666666...
        BigDecimal expected = new BigDecimal("14").divide(new BigDecimal("3"), 4, RoundingMode.HALF_UP);
        assertThat(summary.average().setScale(4, RoundingMode.HALF_UP)).isEqualByComparingTo(expected);
        assertThat(summary.displayAverage()).isEqualByComparingTo(new BigDecimal("4.7"));
    }

    @Test
    @DisplayName("C2. Single query: đánh giá nửa sao (4.5 và 5.0) -> count=2, average nguyên bản 4.75, displayAverage=4.8")
    void shouldCalculateCorrectHalfStarAverageAndDisplayAverage() {
        Instant now = Instant.now();
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_A, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("4.5")), now
        ));
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_A, USER_B, WikiAppreciationScore.fromStars(new BigDecimal("5.0")), now
        ));

        WikiAppreciationSummary summary = queryAdapter.findSummaryByWikiArticleId(ARTICLE_A);

        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(2L);
        assertThat(summary.average()).isNotNull();
        // (9 + 10) / 2 / 2.0 = 19 / 4 = 4.75
        assertThat(summary.average()).isEqualByComparingTo(new BigDecimal("4.75"));
        assertThat(summary.displayAverage()).isEqualByComparingTo(new BigDecimal("4.8"));
    }

    @Test
    @DisplayName("D. Single query: cập nhật đánh giá (User A từ 3.5 -> 5.0) giữ nguyên count=2, average đổi từ 4.25 thành 5.0")
    void shouldReflectUpdatedRatingWithoutHistoricalCountInflation() {
        Instant t1 = Instant.parse("2026-09-22T10:00:00Z");
        UUID ratingAId = UUID.randomUUID();
        WikiAppreciationRating ratingA = WikiAppreciationRating.create(
                ratingAId, ARTICLE_A, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("3.5")), t1
        );
        persistenceAdapter.save(ratingA);

        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_A, USER_B, WikiAppreciationScore.fromStars(new BigDecimal("5.0")), t1
        ));

        // Ban đầu: User A = 3.5 (7 units), User B = 5.0 (10 units) -> count = 2, average = 17 / 4 = 4.25
        WikiAppreciationSummary beforeUpdate = queryAdapter.findSummaryByWikiArticleId(ARTICLE_A);
        assertThat(beforeUpdate.count()).isEqualTo(2L);
        assertThat(beforeUpdate.average()).isEqualByComparingTo(new BigDecimal("4.25"));
        assertThat(beforeUpdate.displayAverage()).isEqualByComparingTo(new BigDecimal("4.3"));

        // User A cập nhật đánh giá: 3.5 -> 5.0
        Instant t2 = Instant.parse("2026-09-22T10:15:00Z");
        ratingA.updateScore(WikiAppreciationScore.fromStars(new BigDecimal("5.0")), t2);
        persistenceAdapter.save(ratingA);

        // Sau cập nhật: User A = 5.0, User B = 5.0 -> count = 2, average = 5.0
        WikiAppreciationSummary afterUpdate = queryAdapter.findSummaryByWikiArticleId(ARTICLE_A);
        assertThat(afterUpdate.count()).isEqualTo(2L);
        assertThat(afterUpdate.average()).isEqualByComparingTo(new BigDecimal("5.0"));
        assertThat(afterUpdate.displayAverage()).isEqualByComparingTo(new BigDecimal("5.0"));

        // Xác nhận số bản ghi trong database của User A trên ARTICLE_A vẫn duy nhất = 1
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                Integer.class,
                ARTICLE_A.toString(), USER_A.toString()
        );
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    @DisplayName("E. Single query: cô lập dữ liệu giữa các bài viết (đánh giá bài A không ảnh hưởng bài B)")
    void shouldIsolateAggregatesBetweenArticles() {
        Instant now = Instant.now();
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_A, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("5.0")), now
        ));
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_B, USER_B, WikiAppreciationScore.fromStars(new BigDecimal("2.5")), now
        ));

        WikiAppreciationSummary summaryA = queryAdapter.findSummaryByWikiArticleId(ARTICLE_A);
        WikiAppreciationSummary summaryB = queryAdapter.findSummaryByWikiArticleId(ARTICLE_B);

        assertThat(summaryA.count()).isEqualTo(1L);
        assertThat(summaryA.average()).isEqualByComparingTo(new BigDecimal("5.0"));

        assertThat(summaryB.count()).isEqualTo(1L);
        assertThat(summaryB.average()).isEqualByComparingTo(new BigDecimal("2.5"));
    }

    @Test
    @DisplayName("Single query: từ chối ID bài viết null")
    void shouldRejectNullArticleIdInSingleQuery() {
        assertThatThrownBy(() -> queryAdapter.findSummaryByWikiArticleId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết Wiki không được để trống.");
    }

    // =========================================================================
    // 2. BULK SUMMARY QUERY TESTS
    // =========================================================================

    @Test
    @DisplayName("A. Bulk query: input rỗng trả về Map rỗng, không thực thi truy vấn malformed")
    void shouldReturnEmptyMapForEmptyCollection() {
        Map<UUID, WikiAppreciationSummary> map = queryAdapter.findSummariesByWikiArticleIds(Collections.emptyList());
        assertThat(map).isEmpty();
    }

    @Test
    @DisplayName("B. Bulk query: một bài viết yêu cầu không có đánh giá -> trả về entry với count=0, average=null")
    void shouldReturnZeroSummaryForSingleRequestedArticleWithNoRatings() {
        Map<UUID, WikiAppreciationSummary> map = queryAdapter.findSummariesByWikiArticleIds(List.of(ARTICLE_A));

        assertThat(map).hasSize(1);
        assertThat(map).containsKey(ARTICLE_A);

        WikiAppreciationSummary summary = map.get(ARTICLE_A);
        assertThat(summary.count()).isEqualTo(0L);
        assertThat(summary.average()).isNull();
    }

    @Test
    @DisplayName("C. Bulk query: batch hỗn hợp (A có ratings, B có 0 ratings, C có ratings) -> trả về đủ cả 3 ID")
    void shouldReturnFullMapForMixedBatch() {
        Instant now = Instant.now();
        // A: 2 ratings (5.0 và 4.0) -> average = 4.5
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_A, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("5.0")), now
        ));
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_A, USER_B, WikiAppreciationScore.fromStars(new BigDecimal("4.0")), now
        ));

        // B: 0 ratings

        // C: 1 rating (3.0) -> average = 3.0
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_C, USER_C, WikiAppreciationScore.fromStars(new BigDecimal("3.0")), now
        ));

        Map<UUID, WikiAppreciationSummary> map = queryAdapter.findSummariesByWikiArticleIds(
                List.of(ARTICLE_A, ARTICLE_B, ARTICLE_C)
        );

        assertThat(map).hasSize(3);

        // Kiểm tra Article A
        WikiAppreciationSummary sumA = map.get(ARTICLE_A);
        assertThat(sumA.count()).isEqualTo(2L);
        assertThat(sumA.average()).isEqualByComparingTo(new BigDecimal("4.5"));

        // Kiểm tra Article B (điền sẵn)
        WikiAppreciationSummary sumB = map.get(ARTICLE_B);
        assertThat(sumB.count()).isEqualTo(0L);
        assertThat(sumB.average()).isNull();

        // Kiểm tra Article C
        WikiAppreciationSummary sumC = map.get(ARTICLE_C);
        assertThat(sumC.count()).isEqualTo(1L);
        assertThat(sumC.average()).isEqualByComparingTo(new BigDecimal("3.0"));
    }

    @Test
    @DisplayName("D. Bulk query: input chứa trùng lặp ID -> tự động deduplicate, trả về 1 entry per unique ID")
    void shouldDeduplicateRequestedIds() {
        Instant now = Instant.now();
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_A, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("5.0")), now
        ));

        List<UUID> duplicateIds = List.of(ARTICLE_A, ARTICLE_A, ARTICLE_B, ARTICLE_B, ARTICLE_A);
        Map<UUID, WikiAppreciationSummary> map = queryAdapter.findSummariesByWikiArticleIds(duplicateIds);

        assertThat(map).hasSize(2);
        assertThat(map.keySet()).containsExactlyInAnyOrder(ARTICLE_A, ARTICLE_B);
    }

    @Test
    @DisplayName("E. Bulk query: cô lập các bài viết trong batch")
    void shouldIsolateAggregatesInBulkQuery() {
        Instant now = Instant.now();
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_A, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("5.0")), now
        ));
        persistenceAdapter.save(WikiAppreciationRating.create(
                UUID.randomUUID(), ARTICLE_B, USER_B, WikiAppreciationScore.fromStars(new BigDecimal("3.0")), now
        ));

        Map<UUID, WikiAppreciationSummary> map = queryAdapter.findSummariesByWikiArticleIds(List.of(ARTICLE_A, ARTICLE_B));

        assertThat(map.get(ARTICLE_A).average()).isEqualByComparingTo(new BigDecimal("5.0"));
        assertThat(map.get(ARTICLE_B).average()).isEqualByComparingTo(new BigDecimal("3.0"));
    }

    @Test
    @DisplayName("F. Bulk query: từ chối collection null hoặc chứa phần tử null")
    void shouldRejectNullInputInBulkQuery() {
        assertThatThrownBy(() -> queryAdapter.findSummariesByWikiArticleIds(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Danh sách ID bài viết Wiki không được để trống.");

        List<UUID> listWithNull = new ArrayList<>();
        listWithNull.add(ARTICLE_A);
        listWithNull.add(null);

        assertThatThrownBy(() -> queryAdapter.findSummariesByWikiArticleIds(listWithNull))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết Wiki trong danh sách không được để trống.");
    }
}
