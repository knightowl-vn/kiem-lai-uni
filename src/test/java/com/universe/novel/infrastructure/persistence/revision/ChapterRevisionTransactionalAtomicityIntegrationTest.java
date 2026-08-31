package com.universe.novel.infrastructure.persistence.revision;

import com.universe.novel.application.chapter.DeleteDraftChapterCommand;
import com.universe.novel.application.chapter.DeleteDraftChapterUseCase;
import com.universe.novel.application.chapter.UpdateDraftChapterCommand;
import com.universe.novel.application.chapter.UpdateDraftChapterUseCase;
import com.universe.novel.application.chapter.revision.ChapterRevisionRecorder;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.revision.ChapterRevision;
import com.universe.novel.infrastructure.persistence.chapter.ChapterPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.volume.VolumePersistenceAdapter;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        VolumePersistenceAdapter.class,
        ChapterPersistenceAdapter.class,
        ChapterRevisionPersistenceAdapter.class,
        ChapterRevisionRecorder.class,
        UuidGeneratorAdapter.class,
        UpdateDraftChapterUseCase.class,
        DeleteDraftChapterUseCase.class,
        ChapterRevisionTransactionalAtomicityIntegrationTest.TestConfig.class
})
class ChapterRevisionTransactionalAtomicityIntegrationTest {

    private static String resolveHost() {
        String host = System.getProperty("test.mysql.host");
        if (host != null && !host.isBlank()) {
            return host.trim();
        }
        String envHost = System.getenv("TEST_MYSQL_HOST");
        if (envHost != null && !envHost.isBlank()) {
            return envHost.trim();
        }
        String dbHost = System.getenv("DB_HOST");
        if (dbHost != null && !dbHost.isBlank()) {
            return dbHost.trim();
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
        String dbName = System.getenv("DB_NAME");
        if (dbName != null && !dbName.isBlank()) {
            return dbName.trim();
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
        throw new IllegalStateException(
                "MySQL integration test requires a database password. "
                        + "Please configure system property 'test.mysql.pass' or environment variable 'TEST_MYSQL_PASS' / 'MYSQL_ROOT_PASSWORD'."
        );
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        String url = "jdbc:mysql://" + resolveHost() + "/" + resolveDatabaseName() + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ChapterRevisionTransactionalAtomicityIntegrationTest::resolveUser);
        registry.add("spring.datasource.password", ChapterRevisionTransactionalAtomicityIntegrationTest::resolvePassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    private static final UUID ADMIN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID VOLUME_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID CHAPTER_ID = UUID.fromString("33333333-4444-5555-6666-777777777777");
    private static final int VOLUME_SORT_ORDER = 3_000_001;
    private static final int CHAPTER_NUMBER = 3_000_001;
    private static final String CHAPTER_SLUG = "quyen-atomicity-test-chuong-3000001";

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private ChapterRevisionPersistenceAdapter chapterRevisionPersistenceAdapter;

    @SpyBean
    private ChapterPersistenceAdapter chapterPersistenceAdapter;

    @Autowired
    private UpdateDraftChapterUseCase updateDraftChapterUseCase;

    @Autowired
    private DeleteDraftChapterUseCase deleteDraftChapterUseCase;

    @BeforeEach
    void setUp() {
        Mockito.reset(chapterRevisionPersistenceAdapter, chapterPersistenceAdapter);
        cleanupDatabase();
        seedBaseData();
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(chapterRevisionPersistenceAdapter, chapterPersistenceAdapter);
        cleanupDatabase();
    }

    private void cleanupDatabase() {
        jdbcTemplate.update("DELETE FROM novel_chapter_revisions WHERE chapter_id = ?", CHAPTER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id = ? OR chapter_number = ?", CHAPTER_ID.toString(), CHAPTER_NUMBER);
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ? OR sort_order = ?", VOLUME_ID.toString(), VOLUME_SORT_ORDER);
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ? OR email = 'atomicity-admin@universe.local'", ADMIN_ID.toString());
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. User
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'atomicity-admin@universe.local', '$2a$10$hash', 'Atomicity Admin', 'ACTIVE', 'ADMIN', 1, 0, ?, ?)",
                ADMIN_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume (PUBLISHED)
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Atomicity Test', 'quyen-atomicity-test', 'Mô tả quyển test', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), VOLUME_SORT_ORDER, ADMIN_ID.toString(), ADMIN_ID.toString(), ADMIN_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
    }

    private void seedDraftChapterAndRevision() {
        Instant now = Instant.now();

        // Chapter Aggregate (DRAFT, aggregateVersion = 1, contentVersion = 1)
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, ?, 'Chương 1: Khởi Đầu', ?, 'Tóm tắt ban đầu', 'Nội dung ban đầu', 'DRAFT', " +
                        "?, ?, NULL, NULL, ?, ?, NULL, NULL, 1, 0, 1)",
                CHAPTER_ID.toString(), VOLUME_ID.toString(), CHAPTER_NUMBER, CHAPTER_SLUG,
                ADMIN_ID.toString(), ADMIN_ID.toString(),
                Timestamp.from(now), Timestamp.from(now)
        );

        // Revision #1 (CREATE_DRAFT)
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_revisions (id, chapter_id, volume_id, revision_number, content_version, chapter_number, title, slug, summary, content, status, change_type, edit_summary, edited_by, created_at) " +
                        "VALUES (?, ?, ?, 1, 1, ?, 'Chương 1: Khởi Đầu', ?, 'Tóm tắt ban đầu', 'Nội dung ban đầu', 'DRAFT', 'CREATE_DRAFT', NULL, ?, ?)",
                UUID.randomUUID().toString(), CHAPTER_ID.toString(), VOLUME_ID.toString(), CHAPTER_NUMBER,
                CHAPTER_SLUG, ADMIN_ID.toString(), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("CASE A: Khi Chapter save thành công nhưng ghi Revision thất bại -> Spring @Transactional rollback toàn bộ mutation")
    void shouldRollbackChapterMutationWhenRevisionPersistenceFails() {
        seedDraftChapterAndRevision();

        // Failure Injection: allow chapter save to execute, but force chapterRevisionPersistenceAdapter.save to throw RuntimeException
        Mockito.doThrow(new RuntimeException("Simulated failure in ChapterRevisionRepositoryPort.save"))
                .when(chapterRevisionPersistenceAdapter)
                .save(any(ChapterRevision.class));

        UpdateDraftChapterCommand command = new UpdateDraftChapterCommand(
                CHAPTER_ID,
                CHAPTER_NUMBER,
                "Chương 1: Tiêu Đề Đã Sửa",
                "Tóm tắt đã sửa",
                "Nội dung đã sửa",
                ADMIN_ID
        );

        // Execute use case and expect exception
        assertThatThrownBy(() -> updateDraftChapterUseCase.execute(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated failure in ChapterRevisionRepositoryPort.save");

        // Fresh Database Assertions outside the failed transaction
        Map<String, Object> chapterRow = jdbcTemplate.queryForMap(
                "SELECT title, summary, content, aggregate_version, content_version, updated_by FROM novel_chapters WHERE id = ?",
                CHAPTER_ID.toString()
        );
        assertThat(chapterRow.get("title")).isEqualTo("Chương 1: Khởi Đầu");
        assertThat(chapterRow.get("summary")).isEqualTo("Tóm tắt ban đầu");
        assertThat(chapterRow.get("content")).isEqualTo("Nội dung ban đầu");
        assertThat(((Number) chapterRow.get("aggregate_version")).longValue()).isEqualTo(1L);
        assertThat(((Number) chapterRow.get("content_version")).longValue()).isEqualTo(1L);

        List<Map<String, Object>> revisions = jdbcTemplate.queryForList(
                "SELECT revision_number, change_type, title FROM novel_chapter_revisions WHERE chapter_id = ? ORDER BY revision_number ASC",
                CHAPTER_ID.toString()
        );
        assertThat(revisions).hasSize(1);
        assertThat(((Number) revisions.get(0).get("revision_number")).longValue()).isEqualTo(1L);
        assertThat(revisions.get(0).get("change_type")).isEqualTo("CREATE_DRAFT");
        assertThat(revisions.get(0).get("title")).isEqualTo("Chương 1: Khởi Đầu");
    }

    @Test
    @DisplayName("CASE B: Khi Revision deletion thành công nhưng Chapter delete thất bại -> Spring @Transactional rollback toàn bộ delete")
    void shouldRollbackRevisionDeletionWhenChapterDeletionFails() {
        seedDraftChapterAndRevision();

        // Verify chapter can be safely hard deleted
        boolean canDelete = chapterRevisionPersistenceAdapter.canSafelyHardDelete(CHAPTER_ID);
        assertThat(canDelete).isTrue();

        // Failure Injection: allow revision deletion to execute, but force chapterPersistenceAdapter.delete to throw RuntimeException
        Mockito.doThrow(new RuntimeException("Simulated failure in ChapterRepositoryPort.delete"))
                .when(chapterPersistenceAdapter)
                .delete(any(Chapter.class), anyLong());

        DeleteDraftChapterCommand command = new DeleteDraftChapterCommand(
                CHAPTER_ID,
                ADMIN_ID
        );

        // Execute use case and expect exception
        assertThatThrownBy(() -> deleteDraftChapterUseCase.execute(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated failure in ChapterRepositoryPort.delete");

        // Fresh Database Assertions outside the failed transaction
        Integer chapterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapters WHERE id = ?",
                Integer.class,
                CHAPTER_ID.toString()
        );
        assertThat(chapterCount).isEqualTo(1);

        Map<String, Object> chapterRow = jdbcTemplate.queryForMap(
                "SELECT title, status, aggregate_version FROM novel_chapters WHERE id = ?",
                CHAPTER_ID.toString()
        );
        assertThat(chapterRow.get("title")).isEqualTo("Chương 1: Khởi Đầu");
        assertThat(chapterRow.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) chapterRow.get("aggregate_version")).longValue()).isEqualTo(1L);

        List<Map<String, Object>> revisions = jdbcTemplate.queryForList(
                "SELECT revision_number, change_type, title FROM novel_chapter_revisions WHERE chapter_id = ?",
                CHAPTER_ID.toString()
        );
        assertThat(revisions).hasSize(1);
        assertThat(((Number) revisions.get(0).get("revision_number")).longValue()).isEqualTo(1L);
        assertThat(revisions.get(0).get("change_type")).isEqualTo("CREATE_DRAFT");
        assertThat(revisions.get(0).get("title")).isEqualTo("Chương 1: Khởi Đầu");
    }
}
