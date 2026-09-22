package com.universe.wiki.application.appreciation;

import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.exceptions.WikiAppreciationTargetNotFoundException;
import com.universe.wiki.infrastructure.persistence.appreciation.WikiAppreciationPersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.appreciation.WikiAppreciationQueryPersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.article.WikiArticleQueryAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
        WikiAppreciationQueryPersistenceAdapter.class,
        WikiArticleQueryAdapter.class,
        UuidGeneratorAdapter.class,
        SetWikiAppreciationAttemptExecutor.class,
        SetWikiAppreciationUseCase.class,
        SetWikiAppreciationIntegrationTest.TestClockConfiguration.class
})
@DisplayName("SetWikiAppreciation Real-DB Integration Tests (MS-05F4)")
class SetWikiAppreciationIntegrationTest {

    private static final UUID USER_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID ARTICLE_CHARACTER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ARTICLE_FACTION = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID ARTICLE_DRAFT = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID ARTICLE_ARCHIVED = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID ARTICLE_ITEM = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    private static final UUID ADMIN_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestClockConfiguration {
        private final AtomicReference<Instant> currentInstant =
                new AtomicReference<>(Instant.parse("2026-09-22T10:00:00Z"));

        @Bean
        public ClockPort clockPort() {
            return currentInstant::get;
        }

        public void setInstant(Instant instant) {
            currentInstant.set(instant);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SetWikiAppreciationUseCase useCase;

    @Autowired
    private TestClockConfiguration clockConfig;

    @BeforeEach
    void setUpData() {
        cleanData();
        clockConfig.setInstant(Instant.parse("2026-09-22T10:00:00Z"));

        insertArticle(ARTICLE_CHARACTER, "Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED");
        insertArticle(ARTICLE_FACTION, "Lạc Phách Sơn", "lac-phach-son", "FACTION", "PUBLISHED");
        insertArticle(ARTICLE_DRAFT, "Bản nháp", "ban-nhap", "CHARACTER", "DRAFT");
        insertArticle(ARTICLE_ARCHIVED, "Lưu trữ", "luu-tru", "CHARACTER", "ARCHIVED");
        insertArticle(ARTICLE_ITEM, "Dưỡng Kiếm Hồ", "duong-kiem-ho", "ITEM", "PUBLISHED");
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.update("DELETE FROM wiki_appreciation_ratings");
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE id IN (?, ?, ?, ?, ?)",
                ARTICLE_CHARACTER.toString(),
                ARTICLE_FACTION.toString(),
                ARTICLE_DRAFT.toString(),
                ARTICLE_ARCHIVED.toString(),
                ARTICLE_ITEM.toString()
        );
    }

    private void insertArticle(UUID id, String title, String slug, String type, String status) {
        Timestamp now = Timestamp.from(Instant.now());
        String publishedBy = "PUBLISHED".equals(status) || "ARCHIVED".equals(status) ? ADMIN_ID.toString() : null;
        Timestamp publishedAt = "PUBLISHED".equals(status) || "ARCHIVED".equals(status) ? now : null;
        String archivedBy = "ARCHIVED".equals(status) ? ADMIN_ID.toString() : null;
        Timestamp archivedAt = "ARCHIVED".equals(status) ? now : null;

        jdbcTemplate.update("""
                INSERT INTO wiki_articles (
                    id, title, slug, article_type, status, summary, content,
                    created_by, created_at, updated_at, published_by, published_at,
                    archived_by, archived_at, aggregate_version, content_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)
                """,
                id.toString(), title, slug, type, status, "Tóm tắt", "# Nội dung",
                ADMIN_ID.toString(), now, now, publishedBy, publishedAt,
                archivedBy, archivedAt
        );
    }

    @Test
    @DisplayName("A. Đánh giá lần đầu lưu chính xác một bản ghi trong MySQL với đầy đủ thông tin")
    void shouldPersistExactlyOneRowOnFirstRating() {
        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_CHARACTER, USER_1, 4);
        SetWikiAppreciationResult result = useCase.execute(command);

        assertThat(result.changed()).isTrue();
        assertThat(result.value()).isEqualTo(4);
        assertThat(result.count()).isEqualTo(1L);
        assertThat(result.average()).isEqualByComparingTo(new BigDecimal("4.0"));

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                Integer.class,
                ARTICLE_CHARACTER.toString(),
                USER_1.toString()
        );
        assertThat(rowCount).isEqualTo(1);

        Integer storedValue = jdbcTemplate.queryForObject(
                "SELECT value FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                Integer.class,
                ARTICLE_CHARACTER.toString(),
                USER_1.toString()
        );
        assertThat(storedValue).isEqualTo(4);
    }

    @Test
    @DisplayName("B & C & D & E. Cập nhật cùng người dùng/bài viết: cập nhật cùng bản ghi, bảo toàn ID, aggregate phản ánh giá trị mới")
    void shouldUpdateSameRowPreservingRowIdAndUpdatingAggregate() {
        // User 1 đánh giá 3 sao
        clockConfig.setInstant(Instant.parse("2026-09-22T10:00:00Z"));
        useCase.execute(new SetWikiAppreciationCommand(ARTICLE_CHARACTER, USER_1, 3));

        // User 2 đánh giá 5 sao
        useCase.execute(new SetWikiAppreciationCommand(ARTICLE_CHARACTER, USER_2, 5));

        // Lấy ID bản ghi ban đầu của User 1
        String originalRatingId = jdbcTemplate.queryForObject(
                "SELECT id FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                String.class,
                ARTICLE_CHARACTER.toString(),
                USER_1.toString()
        );
        assertThat(originalRatingId).isNotNull();

        // User 1 cập nhật đánh giá từ 3 -> 5
        Instant updateInstant = Instant.parse("2026-09-22T10:30:00Z");
        clockConfig.setInstant(updateInstant);
        SetWikiAppreciationResult updateResult = useCase.execute(
                new SetWikiAppreciationCommand(ARTICLE_CHARACTER, USER_1, 5)
        );

        assertThat(updateResult.changed()).isTrue();
        assertThat(updateResult.value()).isEqualTo(5);
        assertThat(updateResult.count()).isEqualTo(2L);
        assertThat(updateResult.average()).isEqualByComparingTo(new BigDecimal("5.0"));

        // Xác nhận ID của bản ghi vẫn là ID cũ (không insert row mới)
        String currentRatingId = jdbcTemplate.queryForObject(
                "SELECT id FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                String.class,
                ARTICLE_CHARACTER.toString(),
                USER_1.toString()
        );
        assertThat(currentRatingId).isEqualTo(originalRatingId);

        // Tổng số bản ghi của User 1 trên bài viết vẫn là 1
        Integer userRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                Integer.class,
                ARTICLE_CHARACTER.toString(),
                USER_1.toString()
        );
        assertThat(userRowCount).isEqualTo(1);

        // Tổng số bản ghi trên bài viết vẫn là 2
        Integer totalRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_appreciation_ratings WHERE wiki_article_id = ?",
                Integer.class,
                ARTICLE_CHARACTER.toString()
        );
        assertThat(totalRowCount).isEqualTo(2);
    }

    @Test
    @DisplayName("F. Gửi lại cùng giá trị (same-value): không thay đổi updated_at trong database, changed=false")
    void shouldNotMutatePersistenceOnSameValueSubmit() {
        Instant initialInstant = Instant.parse("2026-09-22T10:00:00Z");
        clockConfig.setInstant(initialInstant);

        // Ban đầu gửi 5 sao
        useCase.execute(new SetWikiAppreciationCommand(ARTICLE_CHARACTER, USER_1, 5));

        Timestamp initialUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                Timestamp.class,
                ARTICLE_CHARACTER.toString(),
                USER_1.toString()
        );
        assertThat(initialUpdatedAt).isNotNull();

        // Tiến đồng hồ thêm 1 giờ và gửi lại cùng điểm 5 sao
        Instant laterInstant = Instant.parse("2026-09-22T11:00:00Z");
        clockConfig.setInstant(laterInstant);

        SetWikiAppreciationResult noOpResult = useCase.execute(
                new SetWikiAppreciationCommand(ARTICLE_CHARACTER, USER_1, 5)
        );

        assertThat(noOpResult.changed()).isFalse();
        assertThat(noOpResult.value()).isEqualTo(5);
        assertThat(noOpResult.count()).isEqualTo(1L);
        assertThat(noOpResult.average()).isEqualByComparingTo(new BigDecimal("5.0"));

        // Kiểm tra database: updated_at phải hoàn toàn giữ nguyên mốc initialUpdatedAt, không bị đổi thành laterInstant
        Timestamp currentUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                Timestamp.class,
                ARTICLE_CHARACTER.toString(),
                USER_1.toString()
        );
        assertThat(currentUpdatedAt.toInstant().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(initialUpdatedAt.toInstant().truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("G. Các người dùng khác nhau độc lập hoàn toàn trong lưu trữ")
    void shouldKeepDifferentUsersIndependent() {
        useCase.execute(new SetWikiAppreciationCommand(ARTICLE_CHARACTER, USER_1, 4));
        useCase.execute(new SetWikiAppreciationCommand(ARTICLE_CHARACTER, USER_2, 2));

        Integer val1 = jdbcTemplate.queryForObject(
                "SELECT value FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                Integer.class,
                ARTICLE_CHARACTER.toString(),
                USER_1.toString()
        );
        Integer val2 = jdbcTemplate.queryForObject(
                "SELECT value FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                Integer.class,
                ARTICLE_CHARACTER.toString(),
                USER_2.toString()
        );

        assertThat(val1).isEqualTo(4);
        assertThat(val2).isEqualTo(2);
    }

    @Test
    @DisplayName("H. Bài viết không đủ điều kiện (DRAFT, ARCHIVED, ITEM, nonexistent) không tạo bản ghi nào trong database")
    void shouldNotPersistAnyRowForIneligibleArticles() {
        // DRAFT
        assertThatThrownBy(() -> useCase.execute(new SetWikiAppreciationCommand(ARTICLE_DRAFT, USER_1, 5)))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class);

        // ARCHIVED
        assertThatThrownBy(() -> useCase.execute(new SetWikiAppreciationCommand(ARTICLE_ARCHIVED, USER_1, 5)))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class);

        // ITEM (loại không được hỗ trợ)
        assertThatThrownBy(() -> useCase.execute(new SetWikiAppreciationCommand(ARTICLE_ITEM, USER_1, 5)))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class);

        // Nonexistent
        UUID nonExistent = UUID.randomUUID();
        assertThatThrownBy(() -> useCase.execute(new SetWikiAppreciationCommand(nonExistent, USER_1, 5)))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class);

        // Không có bản ghi nào được tạo
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_appreciation_ratings",
                Integer.class
        );
        assertThat(count).isEqualTo(0);
    }
}
