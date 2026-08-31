package com.universe.novel.infrastructure.persistence.reader;

import com.universe.test.TestDatabaseSupport;
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

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
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
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE volume_id = ?",
                VOLUME_ID.toString());
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

    @Test
    @DisplayName("7. Retention Pruning: Xoá bản ghi cũ nhất khi vượt quá giới hạn và không ảnh hưởng tới user khác")
    void shouldPruneOldestEntriesExceedingLimitAndIsolateUsers() {
        Instant baseTime = Instant.parse("2026-08-26T08:00:00Z");

        // Seed 5 chapters for User 1
        UUID[] chIds = new UUID[5];
        for (int i = 0; i < 5; i++) {
            chIds[i] = UUID.randomUUID();
            seedChapter(chIds[i], 7_000_000 + i + 10, "Chương " + i, "chuong-p-" + i);
            Instant readTime = baseTime.plusSeconds(i * 60);
            readingHistoryRepositoryPort.save(UserChapterReadingHistory.createInitial(
                    UUID.randomUUID(), USER_1_ID, chIds[i], readTime
            ));
        }

        // Seed 1 entry for User 2
        UUID user2EntryId = UUID.randomUUID();
        readingHistoryRepositoryPort.save(UserChapterReadingHistory.createInitial(
                user2EntryId, USER_2_ID, CHAPTER_1_ID, baseTime
        ));

        // Prune User 1 to retain at most 3 entries
        readingHistoryRepositoryPort.pruneOldestEntriesExceedingLimit(USER_1_ID, 3);

        // User 1 should have exactly 3 entries left (chIds[4], chIds[3], chIds[2])
        Integer countUser1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_reading_history WHERE user_id = ?",
                Integer.class,
                USER_1_ID.toString()
        );
        assertThat(countUser1).isEqualTo(3);

        // Oldest chapters (chIds[0] and chIds[1]) were pruned
        assertThat(readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_1_ID, chIds[0])).isEmpty();
        assertThat(readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_1_ID, chIds[1])).isEmpty();

        // 3 newest chapters remain
        assertThat(readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_1_ID, chIds[2])).isPresent();
        assertThat(readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_1_ID, chIds[3])).isPresent();
        assertThat(readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_1_ID, chIds[4])).isPresent();

        // User 2's entry was completely untouched
        Integer countUser2 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_reading_history WHERE user_id = ?",
                Integer.class,
                USER_2_ID.toString()
        );
        assertThat(countUser2).isEqualTo(1);
    }
}
