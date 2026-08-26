package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReaderReadingHistoryQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderReadingHistoryDTO;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
        ReaderReadingHistoryQueryPersistenceAdapter.class,
        UuidGeneratorAdapter.class
})
class ReaderReadingHistoryQueryPersistenceIntegrationTest {

    private static final UUID USER_1_ID =
            UUID.fromString("1111bbbb-1111-2222-3333-444444444444");

    private static final UUID USER_2_ID =
            UUID.fromString("2222bbbb-1111-2222-3333-444444444444");

    private static final UUID VOLUME_PUB_ID =
            UUID.fromString("3333bbbb-1111-2222-3333-444444444444");

    private static final UUID VOLUME_DRAFT_ID =
            UUID.fromString("4444bbbb-1111-2222-3333-444444444444");

    private static final UUID CH_PUB_1_ID =
            UUID.fromString("5555bbbb-1111-2222-3333-444444444444");

    private static final UUID CH_PUB_2_ID =
            UUID.fromString("6666bbbb-1111-2222-3333-444444444444");

    private static final UUID CH_DRAFT_ID =
            UUID.fromString("7777bbbb-1111-2222-3333-444444444444");

    private static final UUID CH_IN_DRAFT_VOL_ID =
            UUID.fromString("8888bbbb-1111-2222-3333-444444444444");

    private static final int VOL_PUB_SORT_ORDER = 9_000_001;
    private static final int VOL_DRAFT_SORT_ORDER = 9_000_002;
    private static final int CH_PUB_1_NUM = 9_000_001;
    private static final int CH_PUB_2_NUM = 9_000_002;
    private static final int CH_DRAFT_NUM = 9_000_003;
    private static final int CH_IN_DRAFT_VOL_NUM = 9_000_004;

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
        registry.add("spring.datasource.username", ReaderReadingHistoryQueryPersistenceIntegrationTest::resolveUser);
        registry.add("spring.datasource.password", ReaderReadingHistoryQueryPersistenceIntegrationTest::resolvePassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReaderReadingHistoryQueryPort queryPort;

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
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE volume_id IN (?, ?)",
                VOLUME_PUB_ID.toString(), VOLUME_DRAFT_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id IN (?, ?) OR sort_order IN (?, ?)",
                VOLUME_PUB_ID.toString(), VOLUME_DRAFT_ID.toString(),
                VOL_PUB_SORT_ORDER, VOL_DRAFT_SORT_ORDER);
        jdbcTemplate.update("DELETE FROM identity_users WHERE id IN (?, ?)",
                USER_1_ID.toString(), USER_2_ID.toString());
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. Users
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'query-history-user1@universe.local', '$2a$10$hash', 'History User 1', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_1_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'query-history-user2@universe.local', '$2a$10$hash', 'History User 2', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_2_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volumes (1 PUBLISHED, 1 DRAFT)
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển 1 Công Khai', 'quyen-1-cong-khai', 'Mô tả', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_PUB_ID.toString(), VOL_PUB_SORT_ORDER, USER_1_ID.toString(), USER_1_ID.toString(), USER_1_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển 2 Bản Thảo', 'quyen-2-ban-thao', 'Mô tả', ?, 'DRAFT', ?, ?, NULL, NULL, ?, ?, NULL, NULL, 1, 0)",
                VOLUME_DRAFT_ID.toString(), VOL_DRAFT_SORT_ORDER, USER_1_ID.toString(), USER_1_ID.toString(),
                Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapters
        // CH_PUB_1: PUBLISHED in PUBLISHED volume
        seedChapter(CH_PUB_1_ID, VOLUME_PUB_ID, CH_PUB_1_NUM, "Chương 1 Công Khai", "chuong-1-cong-khai", "PUBLISHED");
        // CH_PUB_2: PUBLISHED in PUBLISHED volume
        seedChapter(CH_PUB_2_ID, VOLUME_PUB_ID, CH_PUB_2_NUM, "Chương 2 Công Khai", "chuong-2-cong-khai", "PUBLISHED");
        // CH_DRAFT: DRAFT in PUBLISHED volume
        seedChapter(CH_DRAFT_ID, VOLUME_PUB_ID, CH_DRAFT_NUM, "Chương 3 Nháp", "chuong-3-nhap", "DRAFT");
        // CH_IN_DRAFT_VOL: PUBLISHED in DRAFT volume
        seedChapter(CH_IN_DRAFT_VOL_ID, VOLUME_DRAFT_ID, CH_IN_DRAFT_VOL_NUM, "Chương 4 Thuộc Quyển Nháp", "chuong-4-quyen-nhap", "PUBLISHED");
    }

    private void seedChapter(UUID chapterId, UUID volumeId, int chapterNumber, String title, String slug, String status) {
        Instant now = Instant.now();
        Timestamp pubTimestamp = "PUBLISHED".equals(status) ? Timestamp.from(now) : null;
        String pubBy = "PUBLISHED".equals(status) ? USER_1_ID.toString() : null;

        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, ?, ?, ?, 'Tóm tắt', 'Nội dung chi tiết', ?, " +
                        "?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                chapterId.toString(), volumeId.toString(), chapterNumber, title, slug, status,
                USER_1_ID.toString(), USER_1_ID.toString(), pubBy,
                Timestamp.from(now), Timestamp.from(now), pubTimestamp
        );
    }

    @Test
    @DisplayName("1. User Isolation & Ordering: Chỉ trả về lịch sử đọc của user yêu cầu, sắp xếp lastReadAt mới nhất trước")
    void shouldReturnOnlyRequestedUserHistoryOrderedByLastReadAtDesc() {
        Instant t1 = Instant.parse("2026-08-25T10:00:00Z");
        Instant t2 = Instant.parse("2026-08-25T10:30:00Z");

        // User 1 reads CH_PUB_1 at t1, and CH_PUB_2 at t2
        jdbcTemplate.update(
                "INSERT INTO novel_reading_history (id, user_id, chapter_id, first_read_at, last_read_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), USER_1_ID.toString(), CH_PUB_1_ID.toString(), Timestamp.from(t1), Timestamp.from(t1)
        );
        jdbcTemplate.update(
                "INSERT INTO novel_reading_history (id, user_id, chapter_id, first_read_at, last_read_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), USER_1_ID.toString(), CH_PUB_2_ID.toString(), Timestamp.from(t2), Timestamp.from(t2)
        );

        // User 2 reads CH_PUB_1 at t2
        jdbcTemplate.update(
                "INSERT INTO novel_reading_history (id, user_id, chapter_id, first_read_at, last_read_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), USER_2_ID.toString(), CH_PUB_1_ID.toString(), Timestamp.from(t2), Timestamp.from(t2)
        );

        List<ReaderReadingHistoryDTO> list = queryPort.findReadingHistoryByUserId(USER_1_ID);

        assertThat(list).hasSize(2);
        // Newest read (CH_PUB_2 at t2) comes first
        assertThat(list.get(0).chapterId()).isEqualTo(CH_PUB_2_ID);
        assertThat(list.get(0).chapterNumber()).isEqualTo(CH_PUB_2_NUM);
        assertThat(list.get(0).chapterTitle()).isEqualTo("Chương 2 Công Khai");
        assertThat(list.get(0).chapterSlug()).isEqualTo("chuong-2-cong-khai");
        assertThat(list.get(0).volumeTitle()).isEqualTo("Quyển 1 Công Khai");
        assertThat(list.get(0).lastReadAt()).isEqualTo(t2);

        // Older read (CH_PUB_1 at t1) comes second
        assertThat(list.get(1).chapterId()).isEqualTo(CH_PUB_1_ID);
        assertThat(list.get(1).chapterNumber()).isEqualTo(CH_PUB_1_NUM);
        assertThat(list.get(1).chapterTitle()).isEqualTo("Chương 1 Công Khai");
        assertThat(list.get(1).lastReadAt()).isEqualTo(t1);
    }

    @Test
    @DisplayName("2. Public Filtering: Loại bỏ các chương DRAFT hoặc thuộc Quyển DRAFT khỏi danh sách lịch sử")
    void shouldFilterOutDraftChaptersAndChaptersInDraftVolumes() {
        Instant now = Instant.now();

        // History 1: Public chapter in Public volume -> SHOULD BE RETURNED
        jdbcTemplate.update(
                "INSERT INTO novel_reading_history (id, user_id, chapter_id, first_read_at, last_read_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), USER_1_ID.toString(), CH_PUB_1_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // History 2: Draft chapter in Public volume -> MUST BE EXCLUDED
        jdbcTemplate.update(
                "INSERT INTO novel_reading_history (id, user_id, chapter_id, first_read_at, last_read_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), USER_1_ID.toString(), CH_DRAFT_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // History 3: Published chapter in Draft volume -> MUST BE EXCLUDED
        jdbcTemplate.update(
                "INSERT INTO novel_reading_history (id, user_id, chapter_id, first_read_at, last_read_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), USER_1_ID.toString(), CH_IN_DRAFT_VOL_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        List<ReaderReadingHistoryDTO> list = queryPort.findReadingHistoryByUserId(USER_1_ID);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).chapterId()).isEqualTo(CH_PUB_1_ID);

        // All 3 history rows still remain intact in database
        Integer rawCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_reading_history WHERE user_id = ?",
                Integer.class,
                USER_1_ID.toString()
        );
        assertThat(rawCount).isEqualTo(3);
    }

    @Test
    @DisplayName("3. Display Limit 10 & Filtering before LIMIT: Trả về tối đa 10 chương sau khi đã áp dụng bộ lọc PUBLISHED")
    void shouldLimitDisplayTo10AndFilterBeforeLimit() {
        Instant baseTime = Instant.parse("2026-08-25T10:00:00Z");

        // Seed 15 published chapters in VOLUME_PUB_ID
        for (int i = 10; i < 25; i++) {
            UUID chId = UUID.randomUUID();
            seedChapter(chId, VOLUME_PUB_ID, 9_000_000 + i, "Chương " + i, "chuong-" + i, "PUBLISHED");

            // Seed history with ascending lastReadAt
            Instant readTime = baseTime.plusSeconds(i * 60);
            jdbcTemplate.update(
                    "INSERT INTO novel_reading_history (id, user_id, chapter_id, first_read_at, last_read_at) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), USER_1_ID.toString(), chId.toString(), Timestamp.from(readTime), Timestamp.from(readTime)
            );
        }

        // Seed 5 draft chapters with even newer lastReadAt
        for (int i = 25; i < 30; i++) {
            UUID chId = UUID.randomUUID();
            seedChapter(chId, VOLUME_PUB_ID, 9_000_000 + i, "Chương Draft " + i, "chuong-draft-" + i, "DRAFT");

            Instant readTime = baseTime.plusSeconds(i * 60);
            jdbcTemplate.update(
                    "INSERT INTO novel_reading_history (id, user_id, chapter_id, first_read_at, last_read_at) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), USER_1_ID.toString(), chId.toString(), Timestamp.from(readTime), Timestamp.from(readTime)
            );
        }

        List<ReaderReadingHistoryDTO> list = queryPort.findReadingHistoryByUserId(USER_1_ID);

        // Display limit must be exactly 10
        assertThat(list).hasSize(10);

        // Verify draft chapters (25-29) were filtered out BEFORE limit was applied
        for (ReaderReadingHistoryDTO item : list) {
            assertThat(item.chapterTitle()).doesNotContain("Draft");
        }

        // Verify the 10 returned are the 10 newest published chapters (24 down to 15)
        assertThat(list.get(0).chapterTitle()).isEqualTo("Chương 24");
        assertThat(list.get(9).chapterTitle()).isEqualTo("Chương 15");
    }

    @Test
    @DisplayName("4. Republishing behavior: Chương/quyển được publish sau đó sẽ tự động xuất hiện lại trong lịch sử")
    void shouldIncludePreviouslyHiddenHistoryWhenChapterOrVolumeIsPublished() {
        Instant now = Instant.now();

        // Seed history for CH_DRAFT
        jdbcTemplate.update(
                "INSERT INTO novel_reading_history (id, user_id, chapter_id, first_read_at, last_read_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), USER_1_ID.toString(), CH_DRAFT_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // Currently excluded because CH_DRAFT is DRAFT
        assertThat(queryPort.findReadingHistoryByUserId(USER_1_ID)).isEmpty();

        // Publish the chapter
        jdbcTemplate.update(
                "UPDATE novel_chapters SET status = 'PUBLISHED', published_at = ?, published_by = ? WHERE id = ?",
                Timestamp.from(now), USER_1_ID.toString(), CH_DRAFT_ID.toString()
        );

        // Now it appears in reading history
        List<ReaderReadingHistoryDTO> list = queryPort.findReadingHistoryByUserId(USER_1_ID);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).chapterId()).isEqualTo(CH_DRAFT_ID);
    }

    @Test
    @DisplayName("5. Empty list: Trả về danh sách rỗng khi người dùng chưa đọc chương nào")
    void shouldReturnEmptyListForUserWithNoHistory() {
        UUID nonExistentUserId = UUID.randomUUID();
        List<ReaderReadingHistoryDTO> list = queryPort.findReadingHistoryByUserId(nonExistentUserId);
        assertThat(list).isEmpty();
    }
}
