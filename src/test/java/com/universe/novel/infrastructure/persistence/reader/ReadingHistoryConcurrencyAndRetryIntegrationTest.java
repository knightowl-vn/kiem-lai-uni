package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReadingHistoryRepositoryPort;
import com.universe.novel.application.reader.RecordReadingHistoryAttemptExecutor;
import com.universe.novel.application.reader.RecordReadingHistoryCommand;
import com.universe.novel.application.reader.RecordReadingHistoryUseCase;
import com.universe.novel.domain.reader.UserChapterReadingHistory;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

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
        ReadingHistoryPersistenceAdapter.class,
        ReaderChapterAccessQueryPersistenceAdapter.class,
        RecordReadingHistoryAttemptExecutor.class,
        RecordReadingHistoryUseCase.class,
        UuidGeneratorAdapter.class,
        ReadingHistoryConcurrencyAndRetryIntegrationTest.TestConfig.class
})
class ReadingHistoryConcurrencyAndRetryIntegrationTest {

    private static final UUID USER_ID =
            UUID.fromString("aaaa9999-1111-2222-3333-444444444444");

    private static final UUID VOLUME_ID =
            UUID.fromString("bbbb9999-1111-2222-3333-444444444444");

    private static final UUID CHAPTER_ID =
            UUID.fromString("cccc9999-1111-2222-3333-444444444444");

    private static final int VOLUME_SORT_ORDER = 8_000_001;
    private static final int CHAPTER_NUM = 8_000_001;

    private static final AtomicLong TIME_INCREMENTER = new AtomicLong(1787654321000L);

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            // Mỗi lần gọi now() trả về thời điểm cách nhau 1000ms để phân biệt chính xác firstReadAt và lastReadAt
            return () -> Instant.ofEpochMilli(TIME_INCREMENTER.addAndGet(1000L));
        }
    }

    private static String resolveHost() {
        String host = System.getProperty("test.mysql.host");
        if (host != null && !host.isBlank()) {
            return host.trim();
        }
        String envHost = System.getenv("TEST_MYSQL_HOST");
        if (envHost != null && !envHost.isBlank()) {
            return envHost.trim();
        }
        return "localhost:3306";
    }

    private static String resolveDatabaseName() {
        String db = System.getProperty("test.mysql.db");
        if (db != null && !db.isBlank()) {
            return db.trim();
        }
        String envDb = System.getenv("TEST_MYSQL_DB");
        if (envDb != null && !envDb.isBlank()) {
            return envDb.trim();
        }
        String dbName = System.getenv("MYSQL_DATABASE");
        if (dbName != null && !dbName.isBlank()) {
            return dbName.trim();
        }
        String dbUrl = System.getenv("DB_URL");
        if (dbUrl != null && !dbUrl.isBlank()) {
            int slashIndex = dbUrl.indexOf('/', "jdbc:mysql://".length());
            if (slashIndex != -1) {
                int qIndex = dbUrl.indexOf('?', slashIndex);
                if (qIndex != -1) {
                    return dbUrl.substring(slashIndex + 1, qIndex);
                } else {
                    return dbUrl.substring(slashIndex + 1);
                }
            }
        }
        return "kiemlai_test";
    }

    private static String resolveUser() {
        String user = System.getProperty("test.mysql.user");
        if (user != null && !user.isBlank()) {
            return user.trim();
        }
        String envUser = System.getenv("TEST_MYSQL_USER");
        if (envUser != null && !envUser.isBlank()) {
            return envUser.trim();
        }
        String dbUser = System.getenv("DB_USERNAME");
        if (dbUser != null && !dbUser.isBlank()) {
            return dbUser.trim();
        }
        return "root";
    }

    private static String resolvePassword() {
        String pass = System.getProperty("test.mysql.pass");
        if (pass != null && !pass.isBlank()) {
            return pass;
        }
        String envPass = System.getenv("TEST_MYSQL_PASS");
        if (envPass != null && !envPass.isBlank()) {
            return envPass;
        }
        String rootPass = System.getenv("MYSQL_ROOT_PASSWORD");
        if (rootPass != null && !rootPass.isBlank()) {
            return rootPass;
        }
        String dbPass = System.getenv("DB_PASSWORD");
        if (dbPass != null && !dbPass.isBlank()) {
            return dbPass;
        }
        return "";
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        String url = "jdbc:mysql://" + resolveHost() + "/" + resolveDatabaseName() + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ReadingHistoryConcurrencyAndRetryIntegrationTest::resolveUser);
        registry.add("spring.datasource.password", ReadingHistoryConcurrencyAndRetryIntegrationTest::resolvePassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReadingHistoryRepositoryPort readingHistoryRepositoryPort;

    @Autowired
    private RecordReadingHistoryUseCase recordReadingHistoryUseCase;

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
        jdbcTemplate.update("DELETE FROM novel_reading_history WHERE user_id = ?", USER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id = ? OR chapter_number = ?", CHAPTER_ID.toString(), CHAPTER_NUM);
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ? OR sort_order = ?", VOLUME_ID.toString(), VOLUME_SORT_ORDER);
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ?", USER_ID.toString());
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. User
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'history-concurrent@universe.local', '$2a$10$hash', 'History Concurrency User', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume (PUBLISHED)
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Concurrency History', 'quyen-concurrency-history', 'Mô tả', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), VOLUME_SORT_ORDER, USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapter (PUBLISHED)
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, ?, 'Chương Concurrency History', 'chuong-concurrency-history', 'Tóm tắt', 'Nội dung', 'PUBLISHED', " +
                        "?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                CHAPTER_ID.toString(), VOLUME_ID.toString(), CHAPTER_NUM,
                USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("Đua ghi nhận lịch sử đọc lần đầu (first-read race): không tạo bản ghi trùng, hoàn thành thành công, duy trì đúng 1 dòng với firstReadAt ban đầu và lastReadAt mới nhất")
    void shouldHandleConcurrentFirstReadAttemptsWithoutDuplicatesOrUnexpectedRollback() throws Exception {
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<?> future1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                recordReadingHistoryUseCase.execute(new RecordReadingHistoryCommand(USER_ID, CHAPTER_ID));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Future<?> future2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                recordReadingHistoryUseCase.execute(new RecordReadingHistoryCommand(USER_ID, CHAPTER_ID));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        // Cả 2 future phải hoàn thành bình thường mà không ném UnexpectedRollbackException hay duplicate key error
        future1.get(10, TimeUnit.SECONDS);
        future2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Xác thực trong cơ sở dữ liệu MySQL thực tế
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_reading_history WHERE user_id = ? AND chapter_id = ?",
                Integer.class,
                USER_ID.toString(), CHAPTER_ID.toString()
        );
        assertThat(count).isEqualTo(1);

        UserChapterReadingHistory history = readingHistoryRepositoryPort
                .findByUserIdAndChapterId(USER_ID, CHAPTER_ID)
                .orElseThrow();

        assertThat(history.getFirstReadAt()).isNotNull();
        assertThat(history.getLastReadAt()).isNotNull();
        // lastReadAt phải lớn hơn hoặc bằng firstReadAt (do request sau đã cập nhật lastReadAt)
        assertThat(history.getLastReadAt()).isAfterOrEqualTo(history.getFirstReadAt());
    }
}
