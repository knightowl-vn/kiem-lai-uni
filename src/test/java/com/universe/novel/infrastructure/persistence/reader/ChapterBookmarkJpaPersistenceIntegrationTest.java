package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.exceptions.BookmarkLimitExceededException;
import com.universe.novel.application.exceptions.DuplicateChapterBookmarkException;
import com.universe.novel.application.ports.ChapterBookmarkRepositoryPort;
import com.universe.novel.application.reader.BookmarkChapterAttemptExecutor;
import com.universe.novel.application.reader.BookmarkChapterCommand;
import com.universe.novel.application.reader.BookmarkChapterUseCase;
import com.universe.novel.domain.reader.UserChapterBookmark;
import com.universe.novel.infrastructure.persistence.chapter.ChapterPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.volume.VolumePersistenceAdapter;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
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
import java.util.concurrent.atomic.AtomicInteger;

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
        ChapterBookmarkPersistenceAdapter.class,
        ReaderChapterAccessQueryPersistenceAdapter.class,
        BookmarkChapterAttemptExecutor.class,
        BookmarkChapterUseCase.class,
        UuidGeneratorAdapter.class,
        ChapterBookmarkJpaPersistenceIntegrationTest.TestConfig.class
})
class ChapterBookmarkJpaPersistenceIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }
    }

    private static final UUID USER_1_ID =
            UUID.fromString("aaaa1111-1111-2222-3333-444444444444");

    private static final UUID USER_2_ID =
            UUID.fromString("aaaa2222-1111-2222-3333-444444444444");

    private static final UUID VOLUME_ID =
            UUID.fromString("bbbb1111-1111-2222-3333-444444444444");

    private static final UUID CHAPTER_1_ID =
            UUID.fromString("cccc1111-1111-2222-3333-444444444444");

    private static final UUID CHAPTER_2_ID =
            UUID.fromString("cccc2222-1111-2222-3333-444444444444");

    private static final int VOLUME_SORT_ORDER = 6_000_001;
    private static final int CHAPTER_1_NUM = 6_000_001;
    private static final int CHAPTER_2_NUM = 6_000_002;

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
        registry.add("spring.datasource.username", ChapterBookmarkJpaPersistenceIntegrationTest::resolveUser);
        registry.add("spring.datasource.password", ChapterBookmarkJpaPersistenceIntegrationTest::resolvePassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChapterBookmarkRepositoryPort bookmarkRepositoryPort;

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
        jdbcTemplate.update("DELETE FROM novel_chapter_bookmarks WHERE user_id IN (?, ?)",
                USER_1_ID.toString(), USER_2_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE volume_id = ? OR id IN (?, ?) OR chapter_number IN (?, ?)",
                VOLUME_ID.toString(), CHAPTER_1_ID.toString(), CHAPTER_2_ID.toString(),
                CHAPTER_1_NUM, CHAPTER_2_NUM);
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ? OR sort_order = ?",
                VOLUME_ID.toString(), VOLUME_SORT_ORDER);
        jdbcTemplate.update("DELETE FROM identity_users WHERE id IN (?, ?)",
                USER_1_ID.toString(), USER_2_ID.toString());
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. Users
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'bookmark-user1@universe.local', '$2a$10$hash', 'Bookmark User 1', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_1_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'bookmark-user2@universe.local', '$2a$10$hash', 'Bookmark User 2', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_2_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Bookmark Test', 'quyen-bookmark-test', 'Mô tả', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), VOLUME_SORT_ORDER, USER_1_ID.toString(), USER_1_ID.toString(), USER_1_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapters
        seedChapter(CHAPTER_1_ID, CHAPTER_1_NUM, "Chương Bookmark 1", "chuong-6000001-bookmark");
        seedChapter(CHAPTER_2_ID, CHAPTER_2_NUM, "Chương Bookmark 2", "chuong-6000002-bookmark");
    }

    private void seedChapter(UUID chapterId, int chapterNumber, String title, String slug) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, ?, ?, ?, 'Tóm tắt', 'Nội dung', 'PUBLISHED', " +
                        "?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                chapterId.toString(), VOLUME_ID.toString(), chapterNumber, title, slug,
                USER_1_ID.toString(), USER_1_ID.toString(), USER_1_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("1. Save and exists: Lưu bookmark và kiểm tra tồn tại thành công")
    void shouldSaveAndCheckExistsBookmark() {
        UUID bookmarkId = UUID.randomUUID();
        UserChapterBookmark bookmark = UserChapterBookmark.create(
                bookmarkId,
                USER_1_ID,
                CHAPTER_1_ID,
                Instant.now()
        );

        assertThat(bookmarkRepositoryPort.existsByUserIdAndChapterId(USER_1_ID, CHAPTER_1_ID)).isFalse();

        bookmarkRepositoryPort.save(bookmark);

        assertThat(bookmarkRepositoryPort.existsByUserIdAndChapterId(USER_1_ID, CHAPTER_1_ID)).isTrue();
    }

    @Test
    @DisplayName("2. Delete by user and chapter: Xóa bookmark theo userId và chapterId")
    void shouldDeleteBookmarkByUserIdAndChapterId() {
        UUID bookmarkId = UUID.randomUUID();
        UserChapterBookmark bookmark = UserChapterBookmark.create(
                bookmarkId,
                USER_1_ID,
                CHAPTER_1_ID,
                Instant.now()
        );
        bookmarkRepositoryPort.save(bookmark);
        assertThat(bookmarkRepositoryPort.existsByUserIdAndChapterId(USER_1_ID, CHAPTER_1_ID)).isTrue();

        int deletedCount = bookmarkRepositoryPort.deleteByUserIdAndChapterId(USER_1_ID, CHAPTER_1_ID);
        assertThat(deletedCount).isEqualTo(1);
        assertThat(bookmarkRepositoryPort.existsByUserIdAndChapterId(USER_1_ID, CHAPTER_1_ID)).isFalse();

        // Idempotent second delete returns 0
        int secondDelete = bookmarkRepositoryPort.deleteByUserIdAndChapterId(USER_1_ID, CHAPTER_1_ID);
        assertThat(secondDelete).isEqualTo(0);
    }

    @Test
    @DisplayName("3. Duplicate Bookmark: Ném DuplicateChapterBookmarkException khi cùng user đánh dấu cùng chương 2 lần")
    void shouldRejectDuplicateBookmarkWithSpecificException() {
        UUID bookmark1Id = UUID.randomUUID();
        UserChapterBookmark bookmark1 = UserChapterBookmark.create(
                bookmark1Id,
                USER_1_ID,
                CHAPTER_1_ID,
                Instant.now()
        );
        bookmarkRepositoryPort.save(bookmark1);

        UUID bookmark2Id = UUID.randomUUID();
        UserChapterBookmark bookmark2 = UserChapterBookmark.create(
                bookmark2Id,
                USER_1_ID,
                CHAPTER_1_ID,
                Instant.now()
        );

        assertThatThrownBy(() -> bookmarkRepositoryPort.save(bookmark2))
                .isInstanceOf(DuplicateChapterBookmarkException.class)
                .hasMessageContaining(USER_1_ID.toString())
                .hasMessageContaining(CHAPTER_1_ID.toString());
    }

    @Test
    @DisplayName("4. Multi-user & Multi-chapter: Cho phép nhiều người dùng đánh dấu cùng chương và 1 người đánh dấu nhiều chương")
    void shouldAllowMultiUserAndMultiChapterBookmarks() {
        // User 1 bookmarks Chapter 1
        bookmarkRepositoryPort.save(UserChapterBookmark.create(
                UUID.randomUUID(), USER_1_ID, CHAPTER_1_ID, Instant.now()
        ));

        // User 2 bookmarks Chapter 1 (same chapter, different user)
        bookmarkRepositoryPort.save(UserChapterBookmark.create(
                UUID.randomUUID(), USER_2_ID, CHAPTER_1_ID, Instant.now()
        ));

        // User 1 bookmarks Chapter 2 (same user, different chapter)
        bookmarkRepositoryPort.save(UserChapterBookmark.create(
                UUID.randomUUID(), USER_1_ID, CHAPTER_2_ID, Instant.now()
        ));

        assertThat(bookmarkRepositoryPort.existsByUserIdAndChapterId(USER_1_ID, CHAPTER_1_ID)).isTrue();
        assertThat(bookmarkRepositoryPort.existsByUserIdAndChapterId(USER_2_ID, CHAPTER_1_ID)).isTrue();
        assertThat(bookmarkRepositoryPort.existsByUserIdAndChapterId(USER_1_ID, CHAPTER_2_ID)).isTrue();
        assertThat(bookmarkRepositoryPort.existsByUserIdAndChapterId(USER_2_ID, CHAPTER_2_ID)).isFalse();
    }

    @Autowired
    private BookmarkChapterUseCase bookmarkChapterUseCase;

    @Test
    @DisplayName("5. Limit Boundary: 99 -> thêm 1 thành công đạt 100; 100 -> thêm mới ném BookmarkLimitExceededException; 100 -> bookmark lại chương cũ thành công idempotent")
    void shouldEnforceLimit100AndPreserveIdempotentSuccess() {
        // Seed 99 bookmarks for USER_1_ID
        for (int i = 1; i <= 99; i++) {
            UUID chId = UUID.randomUUID();
            seedChapter(chId, 6_000_100 + i, "Chương " + i, "chuong-bm-" + i);
            bookmarkRepositoryPort.save(UserChapterBookmark.create(
                    UUID.randomUUID(), USER_1_ID, chId, Instant.now()
            ));
        }

        assertThat(bookmarkRepositoryPort.countByUserIdForUpdate(USER_1_ID)).isEqualTo(99L);

        // 99 -> Add 100th chapter -> SUCCESS
        bookmarkChapterUseCase.execute(new BookmarkChapterCommand(USER_1_ID, CHAPTER_1_ID));
        assertThat(bookmarkRepositoryPort.countByUserIdForUpdate(USER_1_ID)).isEqualTo(100L);

        // 100 -> Re-bookmark CHAPTER_1_ID (already bookmarked) -> SUCCESS IDEMPOTENT
        bookmarkChapterUseCase.execute(new BookmarkChapterCommand(USER_1_ID, CHAPTER_1_ID));
        assertThat(bookmarkRepositoryPort.countByUserIdForUpdate(USER_1_ID)).isEqualTo(100L);

        // 100 -> Add 101st chapter (CHAPTER_2_ID, new) -> THROW BookmarkLimitExceededException
        assertThatThrownBy(() -> bookmarkChapterUseCase.execute(new BookmarkChapterCommand(USER_1_ID, CHAPTER_2_ID)))
                .isInstanceOf(BookmarkLimitExceededException.class)
                .hasMessageContaining(USER_1_ID.toString())
                .hasMessageContaining("100");

        // Database count remains strictly 100
        assertThat(bookmarkRepositoryPort.countByUserIdForUpdate(USER_1_ID)).isEqualTo(100L);
    }

    @Test
    @DisplayName("6. Unpublished chapters count: Các chương nháp/ẩn vẫn được tính vào tổng 100 bookmark")
    void shouldCountUnpublishedChaptersTowardsLimit() {
        // Seed 100 draft chapters with bookmarks
        for (int i = 1; i <= 100; i++) {
            UUID chId = UUID.randomUUID();
            Instant now = Instant.now();
            jdbcTemplate.update(
                    "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                            "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                            "VALUES (?, ?, ?, ?, ?, 'Tóm tắt', 'Nội dung', 'DRAFT', ?, ?, NULL, NULL, ?, ?, NULL, NULL, 1, 0, 1)",
                    chId.toString(), VOLUME_ID.toString(), 6_000_200 + i, "Chương Draft " + i, "chuong-draft-" + i,
                    USER_1_ID.toString(), USER_1_ID.toString(), Timestamp.from(now), Timestamp.from(now)
            );
            bookmarkRepositoryPort.save(UserChapterBookmark.create(
                    UUID.randomUUID(), USER_1_ID, chId, now
            ));
        }

        // Count is 100 despite chapters being DRAFT
        assertThat(bookmarkRepositoryPort.countByUserIdForUpdate(USER_1_ID)).isEqualTo(100L);

        // Attempting to bookmark a published chapter fails due to limit
        assertThatThrownBy(() -> bookmarkChapterUseCase.execute(new BookmarkChapterCommand(USER_1_ID, CHAPTER_1_ID)))
                .isInstanceOf(BookmarkLimitExceededException.class);
    }

    @RepeatedTest(5)
    @DisplayName("7. 99 + 2 Concurrent SAME Chapter: Cả 2 request thành công, đúng 1 dòng được tạo, tổng bookmark = 100")
    void shouldHandleConcurrentSameChapterBookmarksAtLimit() throws Exception {
        cleanupDatabase();
        seedBaseData();

        // Seed 99 bookmarks for USER_1_ID
        for (int i = 1; i <= 99; i++) {
            UUID chId = UUID.randomUUID();
            seedChapter(chId, 6_000_300 + i, "Chương Concurrency " + i, "chuong-cc-" + i);
            bookmarkRepositoryPort.save(UserChapterBookmark.create(
                    UUID.randomUUID(), USER_1_ID, chId, Instant.now()
            ));
        }

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<?> future1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                bookmarkChapterUseCase.execute(new BookmarkChapterCommand(USER_1_ID, CHAPTER_1_ID));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Future<?> future2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                bookmarkChapterUseCase.execute(new BookmarkChapterCommand(USER_1_ID, CHAPTER_1_ID));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        future1.get(10, TimeUnit.SECONDS);
        future2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Total count must be exactly 100
        Integer finalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_bookmarks WHERE user_id = ?",
                Integer.class,
                USER_1_ID.toString()
        );
        assertThat(finalCount).isEqualTo(100);

        // Exactly 1 row for CHAPTER_1_ID
        Integer chapter1Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_bookmarks WHERE user_id = ? AND chapter_id = ?",
                Integer.class,
                USER_1_ID.toString(), CHAPTER_1_ID.toString()
        );
        assertThat(chapter1Count).isEqualTo(1);
    }

    @RepeatedTest(5)
    @DisplayName("8. 99 + 2 Concurrent DIFFERENT Chapters: Đúng 1 request thành công, 1 request ném BookmarkLimitExceededException, tổng = 100, user khác không bị ảnh hưởng")
    void shouldHandleConcurrentDifferentChapterBookmarksAtLimit() throws Exception {
        cleanupDatabase();
        seedBaseData();

        // Seed 99 bookmarks for USER_1_ID
        for (int i = 1; i <= 99; i++) {
            UUID chId = UUID.randomUUID();
            seedChapter(chId, 6_000_400 + i, "Chương Concurrency Diff " + i, "chuong-cd-" + i);
            bookmarkRepositoryPort.save(UserChapterBookmark.create(
                    UUID.randomUUID(), USER_1_ID, chId, Instant.now()
            ));
        }

        // Seed 1 bookmark for USER_2_ID
        bookmarkRepositoryPort.save(UserChapterBookmark.create(
                UUID.randomUUID(), USER_2_ID, CHAPTER_1_ID, Instant.now()
        ));

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger limitExceededCount = new AtomicInteger(0);

        Future<?> future1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                bookmarkChapterUseCase.execute(new BookmarkChapterCommand(USER_1_ID, CHAPTER_1_ID));
                successCount.incrementAndGet();
            } catch (BookmarkLimitExceededException e) {
                limitExceededCount.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Future<?> future2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                bookmarkChapterUseCase.execute(new BookmarkChapterCommand(USER_1_ID, CHAPTER_2_ID));
                successCount.incrementAndGet();
            } catch (BookmarkLimitExceededException e) {
                limitExceededCount.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        future1.get(10, TimeUnit.SECONDS);
        future2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly 1 success, exactly 1 limit exceeded
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(limitExceededCount.get()).isEqualTo(1);

        // Final count for USER_1_ID is strictly 100
        Integer finalUser1Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_bookmarks WHERE user_id = ?",
                Integer.class,
                USER_1_ID.toString()
        );
        assertThat(finalUser1Count).isEqualTo(100);

        // USER_2_ID's count is unaffected (still 1)
        Integer finalUser2Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_bookmarks WHERE user_id = ?",
                Integer.class,
                USER_2_ID.toString()
        );
        assertThat(finalUser2Count).isEqualTo(1);
    }
}
