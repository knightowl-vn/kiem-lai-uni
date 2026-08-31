package com.universe.novel.infrastructure.persistence.reader;

import com.universe.test.TestDatabaseSupport;
import com.universe.novel.application.ports.ReadingProgressRepositoryPort;
import com.universe.novel.application.reader.RecordReadingProgressAttemptExecutor;
import com.universe.novel.application.reader.RecordReadingProgressCommand;
import com.universe.novel.application.reader.RecordReadingProgressUseCase;
import com.universe.novel.domain.reader.UserReadingProgress;
import com.universe.novel.infrastructure.persistence.chapter.ChapterPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.volume.VolumePersistenceAdapter;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        VolumePersistenceAdapter.class,
        ChapterPersistenceAdapter.class,
        ReadingProgressPersistenceAdapter.class,
        ReaderChapterAccessQueryPersistenceAdapter.class,
        RecordReadingProgressAttemptExecutor.class,
        RecordReadingProgressUseCase.class,
        UuidGeneratorAdapter.class,
        ReadingProgressConcurrencyAndRetryIntegrationTest.TestConfig.class
})
class ReadingProgressConcurrencyAndRetryIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static final UUID USER_ID =
            UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");

    private static final UUID VOLUME_ID =
            UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");

    private static final UUID CHAPTER_1_ID =
            UUID.fromString("cccccccc-1111-2222-3333-444444444444");

    private static final UUID CHAPTER_20_ID =
            UUID.fromString("dddddddd-1111-2222-3333-444444444444");

    private static final UUID CHAPTER_50_ID =
            UUID.fromString("eeeeeeee-1111-2222-3333-444444444444");

    private static final int VOLUME_SORT_ORDER = 4_000_001;
    private static final int CHAPTER_1_NUM = 4_000_001;
    private static final int CHAPTER_20_NUM = 4_000_020;
    private static final int CHAPTER_50_NUM = 4_000_050;

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SpringDataReadingProgressJpaRepository springDataReadingProgressJpaRepository;

    @Autowired
    private ReadingProgressRepositoryPort readingProgressRepositoryPort;

    @Autowired
    private RecordReadingProgressUseCase recordReadingProgressUseCase;

    @BeforeEach
    void setUp() {
        cleanupDatabase();
        seedBaseData();
    }

    @AfterEach
    void tearDown() {
        cleanupDatabase();
    }

    private void cleanupDatabase() {
        jdbcTemplate.update("DELETE FROM novel_reading_progress WHERE user_id = ?", USER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id IN (?, ?, ?) OR chapter_number IN (?, ?, ?)",
                CHAPTER_1_ID.toString(), CHAPTER_20_ID.toString(), CHAPTER_50_ID.toString(),
                CHAPTER_1_NUM, CHAPTER_20_NUM, CHAPTER_50_NUM);
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ? OR sort_order = ?", VOLUME_ID.toString(), VOLUME_SORT_ORDER);
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ? OR email = 'reader-concurrency@universe.local'", USER_ID.toString());
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. User
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'reader-concurrency@universe.local', '$2a$10$hash', 'Reader Concurrency', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume (PUBLISHED)
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Concurrency', 'quyen-concurrency-test', 'Mô tả', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), VOLUME_SORT_ORDER, USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapters: 1, 20, 50 (all PUBLISHED)
        seedChapter(CHAPTER_1_ID, CHAPTER_1_NUM, "Chương 1: Khởi Đầu", "chuong-4000001-khoi-dau");
        seedChapter(CHAPTER_20_ID, CHAPTER_20_NUM, "Chương 20: Giữa Chặng", "chuong-4000020-giua-chang");
        seedChapter(CHAPTER_50_ID, CHAPTER_50_NUM, "Chương 50: Đỉnh Cao", "chuong-4000050-dinh-cao");
    }

    private void seedChapter(UUID chapterId, int chapterNumber, String title, String slug) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, ?, ?, ?, 'Tóm tắt', 'Nội dung', 'PUBLISHED', " +
                        "?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                chapterId.toString(), VOLUME_ID.toString(), chapterNumber, title, slug,
                USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("1. Initial progress creation: Tạo bản ghi Reading Progress đầu tiên trên MySQL")
    void shouldCreateInitialReadingProgress() {
        recordReadingProgressUseCase.execute(
                new RecordReadingProgressCommand(USER_ID, CHAPTER_1_ID)
        );

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT user_id, last_opened_chapter_id, highest_reached_chapter_number, persistence_version FROM novel_reading_progress WHERE user_id = ?",
                USER_ID.toString()
        );

        assertThat(row.get("user_id")).isEqualTo(USER_ID.toString());
        assertThat(row.get("last_opened_chapter_id")).isEqualTo(CHAPTER_1_ID.toString());
        assertThat(((Number) row.get("highest_reached_chapter_number")).intValue()).isEqualTo(CHAPTER_1_NUM);
        assertThat(((Number) row.get("persistence_version")).longValue()).isEqualTo(0L);
    }

    @Test
    @DisplayName("2. JPA @Version optimistic locking: Hibernate phát hiện stale detached entity và ném OptimisticLockingFailureException")
    void shouldRejectStaleDetachedReadingProgressWithOptimisticLock() {
        recordReadingProgressUseCase.execute(
                new RecordReadingProgressCommand(USER_ID, CHAPTER_1_ID)
        );

        // Fetch two separate detached entities
        ReadingProgressJpaEntity staleSnapshot =
                springDataReadingProgressJpaRepository.findByUserId(USER_ID.toString()).orElseThrow();
        ReadingProgressJpaEntity winnerSnapshot =
                springDataReadingProgressJpaRepository.findByUserId(USER_ID.toString()).orElseThrow();

        assertThat(staleSnapshot.getPersistenceVersion()).isEqualTo(0L);
        assertThat(winnerSnapshot.getPersistenceVersion()).isEqualTo(0L);

        // Winner updates to Chapter 50
        winnerSnapshot.setLastOpenedChapterId(CHAPTER_50_ID.toString());
        winnerSnapshot.setHighestReachedChapterNumber(CHAPTER_50_NUM);
        winnerSnapshot.setUpdatedAt(Instant.now());
        ReadingProgressJpaEntity savedWinner =
                springDataReadingProgressJpaRepository.saveAndFlush(winnerSnapshot);

        assertThat(savedWinner.getPersistenceVersion()).isGreaterThan(0L);

        // Stale entity tries to save based on version 0
        staleSnapshot.setLastOpenedChapterId(CHAPTER_20_ID.toString());
        staleSnapshot.setHighestReachedChapterNumber(CHAPTER_20_NUM);
        staleSnapshot.setUpdatedAt(Instant.now());

        assertThatThrownBy(() -> springDataReadingProgressJpaRepository.saveAndFlush(staleSnapshot))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // Verify DB preserved winner state
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT last_opened_chapter_id, highest_reached_chapter_number FROM novel_reading_progress WHERE user_id = ?",
                USER_ID.toString()
        );
        assertThat(row.get("last_opened_chapter_id")).isEqualTo(CHAPTER_50_ID.toString());
        assertThat(((Number) row.get("highest_reached_chapter_number")).intValue()).isEqualTo(CHAPTER_50_NUM);
    }

    @Test
    @DisplayName("3. Retry and Monotonicity: Khi xảy ra xung đột đồng thời, retry tải lại state mới và giữ highestReached monotonic")
    void shouldPreserveMonotonicityUnderConcurrentStaleUpdate() throws Exception {
        // Initial progress: Chapter 1
        recordReadingProgressUseCase.execute(
                new RecordReadingProgressCommand(USER_ID, CHAPTER_1_ID)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        // Thread A: advances to Chapter 50
        Future<?> futureA = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                recordReadingProgressUseCase.execute(
                        new RecordReadingProgressCommand(USER_ID, CHAPTER_50_ID)
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Thread B: concurrently reads Chapter 20
        Future<?> futureB = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                recordReadingProgressUseCase.execute(
                        new RecordReadingProgressCommand(USER_ID, CHAPTER_20_ID)
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        futureA.get(10, TimeUnit.SECONDS);
        futureB.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Final verification: highestReachedChapterNumber must be CHAPTER_50_NUM regardless of thread order
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT last_opened_chapter_id, highest_reached_chapter_number FROM novel_reading_progress WHERE user_id = ?",
                USER_ID.toString()
        );

        int highestReached = ((Number) row.get("highest_reached_chapter_number")).intValue();
        assertThat(highestReached).isEqualTo(CHAPTER_50_NUM);

        String lastOpenedId = (String) row.get("last_opened_chapter_id");
        assertThat(lastOpenedId).isIn(CHAPTER_20_ID.toString(), CHAPTER_50_ID.toString());
    }

    @Test
    @DisplayName("4. Revisiting older chapter: Cập nhật lastOpened nhưng không làm giảm highestReached")
    void shouldUpdateLastOpenedWithoutReducingHighestReached() {
        // First jump to Chapter 50
        recordReadingProgressUseCase.execute(
                new RecordReadingProgressCommand(USER_ID, CHAPTER_50_ID)
        );

        Map<String, Object> row50 = jdbcTemplate.queryForMap(
                "SELECT last_opened_chapter_id, highest_reached_chapter_number FROM novel_reading_progress WHERE user_id = ?",
                USER_ID.toString()
        );
        assertThat(row50.get("last_opened_chapter_id")).isEqualTo(CHAPTER_50_ID.toString());
        assertThat(((Number) row50.get("highest_reached_chapter_number")).intValue()).isEqualTo(CHAPTER_50_NUM);

        // Now revisit Chapter 20
        recordReadingProgressUseCase.execute(
                new RecordReadingProgressCommand(USER_ID, CHAPTER_20_ID)
        );

        Map<String, Object> row20 = jdbcTemplate.queryForMap(
                "SELECT last_opened_chapter_id, highest_reached_chapter_number FROM novel_reading_progress WHERE user_id = ?",
                USER_ID.toString()
        );
        assertThat(row20.get("last_opened_chapter_id")).isEqualTo(CHAPTER_20_ID.toString());
        assertThat(((Number) row20.get("highest_reached_chapter_number")).intValue()).isEqualTo(CHAPTER_50_NUM);
    }
}
