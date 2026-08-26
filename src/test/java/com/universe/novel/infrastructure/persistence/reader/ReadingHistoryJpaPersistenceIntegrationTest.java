package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.exceptions.DuplicateReadingHistoryException;
import com.universe.novel.application.ports.ReadingHistoryRepositoryPort;
import com.universe.novel.domain.reader.UserChapterReadingHistory;
import com.universe.novel.infrastructure.persistence.chapter.ChapterPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.volume.VolumePersistenceAdapter;
import com.universe.shared.id.UuidGeneratorAdapter;
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
        VolumePersistenceAdapter.class,
        ChapterPersistenceAdapter.class,
        ReadingHistoryPersistenceAdapter.class,
        UuidGeneratorAdapter.class
})
class ReadingHistoryJpaPersistenceIntegrationTest {

    private static final UUID USER_1_ID =
            UUID.fromString("aaaa1111-1111-2222-3333-555555555555");

    private static final UUID USER_2_ID =
            UUID.fromString("aaaa2222-1111-2222-3333-555555555555");

    private static final UUID VOLUME_ID =
            UUID.fromString("bbbb1111-1111-2222-3333-555555555555");

    private static final UUID CHAPTER_1_ID =
            UUID.fromString("cccc1111-1111-2222-3333-555555555555");

    private static final UUID CHAPTER_2_ID =
            UUID.fromString("cccc2222-1111-2222-3333-555555555555");

    private static final int VOLUME_SORT_ORDER = 7_000_001;
    private static final int CHAPTER_1_NUM = 7_000_001;
    private static final int CHAPTER_2_NUM = 7_000_002;

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
        registry.add("spring.datasource.username", ReadingHistoryJpaPersistenceIntegrationTest::resolveUser);
        registry.add("spring.datasource.password", ReadingHistoryJpaPersistenceIntegrationTest::resolvePassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReadingHistoryRepositoryPort readingHistoryRepositoryPort;

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
        jdbcTemplate.update("DELETE FROM novel_reading_history WHERE user_id IN (?, ?)",
                USER_1_ID.toString(), USER_2_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id IN (?, ?) OR chapter_number IN (?, ?)",
                CHAPTER_1_ID.toString(), CHAPTER_2_ID.toString(),
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
                        "VALUES (?, 'history-user1@universe.local', '$2a$10$hash', 'History User 1', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_1_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'history-user2@universe.local', '$2a$10$hash', 'History User 2', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_2_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển History Test', 'quyen-history-test', 'Mô tả', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), VOLUME_SORT_ORDER, USER_1_ID.toString(), USER_1_ID.toString(), USER_1_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapters
        seedChapter(CHAPTER_1_ID, CHAPTER_1_NUM, "Chương History 1", "chuong-7000001-history");
        seedChapter(CHAPTER_2_ID, CHAPTER_2_NUM, "Chương History 2", "chuong-7000002-history");
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
    @DisplayName("1. Save initial and find: Lưu bản ghi lịch sử đọc mới và truy vấn thành công")
    void shouldSaveInitialAndFindHistoryRecord() {
        UUID historyId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-26T08:00:00Z");

        UserChapterReadingHistory history = UserChapterReadingHistory.createInitial(
                historyId,
                USER_1_ID,
                CHAPTER_1_ID,
                t0
        );

        Optional<UserChapterReadingHistory> beforeSave =
                readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_1_ID, CHAPTER_1_ID);
        assertThat(beforeSave).isEmpty();

        readingHistoryRepositoryPort.save(history);

        Optional<UserChapterReadingHistory> afterSave =
                readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_1_ID, CHAPTER_1_ID);
        assertThat(afterSave).isPresent();
        UserChapterReadingHistory retrieved = afterSave.get();
        assertThat(retrieved.getId()).isEqualTo(historyId);
        assertThat(retrieved.getUserId()).isEqualTo(USER_1_ID);
        assertThat(retrieved.getChapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(retrieved.getFirstReadAt()).isEqualTo(t0);
        assertThat(retrieved.getLastReadAt()).isEqualTo(t0);
    }

    @Test
    @DisplayName("2. Update existing: Cập nhật lastReadAt, bảo toàn firstReadAt trong cơ sở dữ liệu")
    void shouldUpdateLastReadAtWhilePreservingFirstReadAt() {
        UUID historyId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-26T08:00:00Z");
        Instant t1 = Instant.parse("2026-08-26T08:45:00Z");

        UserChapterReadingHistory initial = UserChapterReadingHistory.createInitial(
                historyId,
                USER_1_ID,
                CHAPTER_1_ID,
                t0
        );
        readingHistoryRepositoryPort.save(initial);

        // Load existing and record new access
        UserChapterReadingHistory loaded = readingHistoryRepositoryPort
                .findByUserIdAndChapterId(USER_1_ID, CHAPTER_1_ID)
                .orElseThrow();
        loaded.recordRead(t1);

        readingHistoryRepositoryPort.save(loaded);

        UserChapterReadingHistory reloaded = readingHistoryRepositoryPort
                .findByUserIdAndChapterId(USER_1_ID, CHAPTER_1_ID)
                .orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(historyId);
        assertThat(reloaded.getFirstReadAt()).isEqualTo(t0);
        assertThat(reloaded.getLastReadAt()).isEqualTo(t1);
    }

    @Test
    @DisplayName("3. Duplicate history: Ném DuplicateReadingHistoryException khi cùng user chèn lịch sử mới trùng chapterId")
    void shouldThrowDuplicateReadingHistoryExceptionOnDuplicateInsert() {
        UUID history1Id = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-26T08:00:00Z");

        UserChapterReadingHistory history1 = UserChapterReadingHistory.createInitial(
                history1Id,
                USER_1_ID,
                CHAPTER_1_ID,
                t0
        );
        readingHistoryRepositoryPort.save(history1);

        // Attempt to insert another history entry with a different ID for the same user and chapter
        UUID history2Id = UUID.randomUUID();
        UserChapterReadingHistory history2 = UserChapterReadingHistory.createInitial(
                history2Id,
                USER_1_ID,
                CHAPTER_1_ID,
                t0
        );

        assertThatThrownBy(() -> readingHistoryRepositoryPort.save(history2))
                .isInstanceOf(DuplicateReadingHistoryException.class)
                .hasMessageContaining(USER_1_ID.toString())
                .hasMessageContaining(CHAPTER_1_ID.toString());
    }

    @Test
    @DisplayName("4. Multi-user & Multi-chapter: Cho phép nhiều người đọc cùng chương và 1 người đọc nhiều chương")
    void shouldAllowMultiUserAndMultiChapterHistory() {
        Instant now = Instant.now();

        // User 1 reads Chapter 1
        readingHistoryRepositoryPort.save(UserChapterReadingHistory.createInitial(
                UUID.randomUUID(), USER_1_ID, CHAPTER_1_ID, now
        ));

        // User 2 reads Chapter 1 (different user, same chapter)
        readingHistoryRepositoryPort.save(UserChapterReadingHistory.createInitial(
                UUID.randomUUID(), USER_2_ID, CHAPTER_1_ID, now
        ));

        // User 1 reads Chapter 2 (same user, different chapter)
        readingHistoryRepositoryPort.save(UserChapterReadingHistory.createInitial(
                UUID.randomUUID(), USER_1_ID, CHAPTER_2_ID, now
        ));

        assertThat(readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_1_ID, CHAPTER_1_ID)).isPresent();
        assertThat(readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_2_ID, CHAPTER_1_ID)).isPresent();
        assertThat(readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_1_ID, CHAPTER_2_ID)).isPresent();
        assertThat(readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_2_ID, CHAPTER_2_ID)).isEmpty();
    }

    @Test
    @DisplayName("5. Chapter FK constraint: Từ chối lưu lịch sử cho chapter không tồn tại")
    void shouldRejectHistoryForNonExistentChapter() {
        UUID nonExistentChapterId = UUID.randomUUID();
        UserChapterReadingHistory invalidHistory = UserChapterReadingHistory.createInitial(
                UUID.randomUUID(),
                USER_1_ID,
                nonExistentChapterId,
                Instant.now()
        );

        assertThatThrownBy(() -> readingHistoryRepositoryPort.save(invalidHistory))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateReadingHistoryException.class);
    }

    @Test
    @DisplayName("6. Foreign key ON DELETE RESTRICT: Ngăn chặn xóa chương khi có bản ghi lịch sử đọc tham chiếu")
    void shouldPreventChapterDeletionWhenReferencedByHistory() {
        readingHistoryRepositoryPort.save(UserChapterReadingHistory.createInitial(
                UUID.randomUUID(),
                USER_1_ID,
                CHAPTER_1_ID,
                Instant.now()
        ));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM novel_chapters WHERE id = ?",
                CHAPTER_1_ID.toString()
        )).hasMessageContaining("foreign key constraint");
    }
}
