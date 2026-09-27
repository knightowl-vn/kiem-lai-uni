package com.universe.wiki.infrastructure.persistence.appreciation;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.appreciation.SetWikiAppreciationAttemptExecutor;
import com.universe.wiki.application.appreciation.SetWikiAppreciationCommand;
import com.universe.wiki.application.appreciation.SetWikiAppreciationResult;
import com.universe.wiki.application.appreciation.SetWikiAppreciationUseCase;
import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.application.ports.WikiAppreciationRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleEligibilitySnapshot;
import com.universe.wiki.domain.appreciation.WikiAppreciationRating;
import com.universe.wiki.domain.appreciation.WikiAppreciationScore;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
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
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
        SetWikiAppreciationAttemptExecutor.class,
        SetWikiAppreciationUseCase.class,
        WikiAppreciationConcurrencyIntegrationTest.TestConfig.class
})
@DisplayName("WikiAppreciation Concurrency Integration Tests (MS-05F7)")
class WikiAppreciationConcurrencyIntegrationTest {

    private static final UUID ARTICLE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SetWikiAppreciationUseCase useCase;

    @Autowired
    private WikiAppreciationQueryPort appreciationQueryPort;

    @Autowired
    private TestConcurrentRepositoryDecorator testDecorator;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        cleanData();
        insertArticle(ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED", "Nhân vật chính");
        executor = Executors.newFixedThreadPool(4);
        testDecorator.reset();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.update("DELETE FROM wiki_appreciation_ratings");
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE id = ?", ARTICLE_ID.toString());
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
    // SCENARIO A — DETERMINISTIC FIRST INSERT RACE & RETRY RECOVERY
    // =========================================================================
    @Test
    @DisplayName("SCENARIO A: Cùng người dùng + cùng bài viết + chưa có bản ghi: ép 2 transaction quan sát empty, phục hồi qua retry, đúng 1 bản ghi")
    void shouldDeterministicallyRecoverFromFirstInsertRace() throws Exception {
        // Kích hoạt test decorator để ép cả 2 luồng quan sát empty trước khi bất kỳ luồng nào thực hiện INSERT
        testDecorator.enableScenarioA(2);

        SetWikiAppreciationCommand command1 = new SetWikiAppreciationCommand(
                ARTICLE_ID, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("3.5"))
        );
        SetWikiAppreciationCommand command2 = new SetWikiAppreciationCommand(
                ARTICLE_ID, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("5.0"))
        );

        Future<SetWikiAppreciationResult> future1 = executor.submit(() -> useCase.execute(command1));
        Future<SetWikiAppreciationResult> future2 = executor.submit(() -> useCase.execute(command2));

        // Đợi cho đến khi CẢ 2 luồng đều hoàn tất truy vấn findByWikiArticleIdAndUserId và đều quan sát Optional.empty()
        boolean bothSawEmpty = testDecorator.bothObservedEmpty.await(5, TimeUnit.SECONDS);
        assertThat(bothSawEmpty)
                .as("Cả 2 transaction Attempt 1 phải quan sát được empty trước khi thực hiện INSERT")
                .isTrue();
        assertThat(testDecorator.findEmptyCount.get()).isEqualTo(2);

        // Phát tín hiệu mở khóa đồng thời cho cả 2 transaction thực hiện saveAndFlush vào MySQL
        testDecorator.releaseInserts.countDown();

        // Thu thập kết quả cả 2 futures (không được ném ngoại lệ chưa kiểm soát)
        SetWikiAppreciationResult result1 = future1.get(10, TimeUnit.SECONDS);
        SetWikiAppreciationResult result2 = future2.get(10, TimeUnit.SECONDS);

        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();

        // 1. Chứng minh đường đi phục hồi (retry) đã thực sự được kích hoạt:
        // Attempt 1 của luồng A -> save (1)
        // Attempt 1 của luồng B -> save (2, collision uq_wiki_appreciation_ratings_article_user)
        // Attempt 2 (retry) của luồng thua -> quan sát bản ghi của luồng thắng với giá trị khác (3.5 != 5.0) -> save (3, update)
        // Vì giá trị 3.5 và 5.0 khác nhau, retry không thể rơi vào same-value no-op, do đó chính xác 3 lần gọi save()
        assertThat(testDecorator.saveCallCount.get())
                .as("Phải chứng minh được retry đã diễn ra sau xung đột duplicate key và thực hiện lưu giá trị mới")
                .isEqualTo(3);

        // 2. Bất biến cơ sở dữ liệu: chính xác 1 dòng duy nhất trong DB
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                Integer.class,
                ARTICLE_ID.toString(),
                USER_A.toString()
        );
        assertThat(rowCount).isEqualTo(1);

        // 3. Giá trị lưu trữ là một trong hai giá trị hợp lệ được gửi lên (7 hoặc 10 units)
        Integer storedValue = jdbcTemplate.queryForObject(
                "SELECT value FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                Integer.class,
                ARTICLE_ID.toString(),
                USER_A.toString()
        );
        assertThat(storedValue).isIn(7, 10);

        // 4. Bất biến tổng hợp trực tiếp từ SQL: COUNT == 1, AVG == storedValue / 2.0
        WikiAppreciationSummary summary = appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID);
        assertThat(summary.count()).isEqualTo(1L);
        assertThat(summary.average()).isEqualByComparingTo(
                java.math.BigDecimal.valueOf(storedValue).divide(java.math.BigDecimal.valueOf(2))
        );
    }

    // =========================================================================
    // SCENARIO B — CONCURRENT EXISTING UPDATES
    // =========================================================================
    @Test
    @DisplayName("SCENARIO B: Cùng người dùng + cùng bài viết + đã có bản ghi: cập nhật đồng thời giữ nguyên đúng 1 dòng với giá trị hợp lệ")
    void shouldHandleConcurrentUpdatesOnExistingRating() throws Exception {
        // Tạo sẵn bản ghi khởi tạo ban đầu với điểm 2.0
        useCase.execute(new SetWikiAppreciationCommand(
                ARTICLE_ID, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("2.0"))
        ));

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<SetWikiAppreciationResult> future1 = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return useCase.execute(new SetWikiAppreciationCommand(
                    ARTICLE_ID, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("4.0"))
            ));
        });

        Future<SetWikiAppreciationResult> future2 = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return useCase.execute(new SetWikiAppreciationCommand(
                    ARTICLE_ID, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("5.0"))
            ));
        });

        boolean bothReady = readyLatch.await(5, TimeUnit.SECONDS);
        assertThat(bothReady)
                .as("Both concurrent update workers must be ready before release")
                .isTrue();
        startLatch.countDown();

        SetWikiAppreciationResult result1 = future1.get(10, TimeUnit.SECONDS);
        SetWikiAppreciationResult result2 = future2.get(10, TimeUnit.SECONDS);

        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();

        // Bất biến: đúng 1 dòng, điểm là 8 hoặc 10 units (4.0 hoặc 5.0)
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                Integer.class,
                ARTICLE_ID.toString(),
                USER_A.toString()
        );
        assertThat(rowCount).isEqualTo(1);

        Integer storedValue = jdbcTemplate.queryForObject(
                "SELECT value FROM wiki_appreciation_ratings WHERE wiki_article_id = ? AND user_id = ?",
                Integer.class,
                ARTICLE_ID.toString(),
                USER_A.toString()
        );
        assertThat(storedValue).isIn(8, 10);

        WikiAppreciationSummary summary = appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID);
        assertThat(summary.count()).isEqualTo(1L);
        assertThat(summary.average()).isEqualByComparingTo(
                java.math.BigDecimal.valueOf(storedValue).divide(java.math.BigDecimal.valueOf(2))
        );
    }

    // =========================================================================
    // SCENARIO C — DIFFERENT USERS / SAME ARTICLE INDEPENDENCE
    // =========================================================================
    @Test
    @DisplayName("SCENARIO C: Khác người dùng + cùng bài viết: các thao tác độc lập, tạo 2 bản ghi, COUNT=2, AVG chính xác")
    void shouldHandleConcurrentRatingsFromDifferentUsersIndependently() throws Exception {
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<SetWikiAppreciationResult> future1 = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return useCase.execute(new SetWikiAppreciationCommand(
                    ARTICLE_ID, USER_A, WikiAppreciationScore.fromStars(new BigDecimal("4.0"))
            ));
        });

        Future<SetWikiAppreciationResult> future2 = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return useCase.execute(new SetWikiAppreciationCommand(
                    ARTICLE_ID, USER_B, WikiAppreciationScore.fromStars(new BigDecimal("5.0"))
            ));
        });

        boolean bothReady = readyLatch.await(5, TimeUnit.SECONDS);
        assertThat(bothReady)
                .as("Both concurrent workers must be ready before release")
                .isTrue();
        startLatch.countDown();

        SetWikiAppreciationResult result1 = future1.get(10, TimeUnit.SECONDS);
        SetWikiAppreciationResult result2 = future2.get(10, TimeUnit.SECONDS);

        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();

        // Bất biến: chính xác 2 dòng độc lập
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_appreciation_ratings WHERE wiki_article_id = ?",
                Integer.class,
                ARTICLE_ID.toString()
        );
        assertThat(rowCount).isEqualTo(2);

        // Điểm trung bình là (4 + 5) / 2 = 4.5
        WikiAppreciationSummary summary = appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID);
        assertThat(summary.count()).isEqualTo(2L);
        assertThat(summary.average()).isEqualByComparingTo(new java.math.BigDecimal("4.5"));
    }

    // =========================================================================
    // TEST CONFIGURATION & CONCURRENT REPOSITORY DECORATOR
    // =========================================================================
    @TestConfiguration
    static class TestConfig {

        @Bean
        public WikiArticleQueryPort wikiArticleQueryPort() {
            WikiArticleQueryPort mock = org.mockito.Mockito.mock(WikiArticleQueryPort.class);
            org.mockito.Mockito.when(mock.findEligibilityById(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> Optional.of(new WikiArticleEligibilitySnapshot(
                            inv.getArgument(0),
                            ArticleType.CHARACTER.name(),
                            ArticleStatus.PUBLISHED.name()
                    )));
            return mock;
        }

        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }

        @Bean
        public IdGeneratorPort idGeneratorPort() {
            return UUID::randomUUID;
        }

        @Bean
        @Primary
        public WikiAppreciationRepositoryPort testConcurrentRepositoryPort(
                WikiAppreciationPersistenceAdapter realAdapter
        ) {
            return new TestConcurrentRepositoryDecorator(realAdapter);
        }
    }

    static class TestConcurrentRepositoryDecorator implements WikiAppreciationRepositoryPort {

        private final WikiAppreciationPersistenceAdapter delegate;
        final AtomicBoolean scenarioAActive = new AtomicBoolean(false);
        final AtomicInteger findEmptyCount = new AtomicInteger(0);
        final AtomicInteger saveCallCount = new AtomicInteger(0);
        volatile CountDownLatch bothObservedEmpty = new CountDownLatch(2);
        volatile CountDownLatch releaseInserts = new CountDownLatch(1);

        TestConcurrentRepositoryDecorator(WikiAppreciationPersistenceAdapter delegate) {
            this.delegate = delegate;
        }

        void enableScenarioA(int expectedThreads) {
            this.scenarioAActive.set(true);
            this.findEmptyCount.set(0);
            this.saveCallCount.set(0);
            this.bothObservedEmpty = new CountDownLatch(expectedThreads);
            this.releaseInserts = new CountDownLatch(1);
        }

        void reset() {
            this.scenarioAActive.set(false);
            this.findEmptyCount.set(0);
            this.saveCallCount.set(0);
        }

        @Override
        public Optional<WikiAppreciationRating> findByWikiArticleIdAndUserId(UUID wikiArticleId, UUID userId) {
            Optional<WikiAppreciationRating> result = delegate.findByWikiArticleIdAndUserId(wikiArticleId, userId);
            if (scenarioAActive.get() && result.isEmpty()) {
                findEmptyCount.incrementAndGet();
                bothObservedEmpty.countDown();
                try {
                    bothObservedEmpty.await(5, TimeUnit.SECONDS);
                    releaseInserts.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result;
        }

        @Override
        public WikiAppreciationRating save(WikiAppreciationRating rating) {
            saveCallCount.incrementAndGet();
            return delegate.save(rating);
        }
    }
}
