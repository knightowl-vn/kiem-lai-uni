package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableNarrationChapterReference;
import com.universe.test.TestDatabaseSupport;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import(ReaderChapterAccessQueryPersistenceAdapter.class)
class ReaderChapterNarrationAccessQueryPersistenceIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("91000000-0000-0000-0000-000000000001");
    private static final UUID PUBLISHED_VOLUME_ID = UUID.fromString("91000000-0000-0000-0000-000000000002");
    private static final UUID DRAFT_VOLUME_ID = UUID.fromString("91000000-0000-0000-0000-000000000003");
    private static final UUID PUBLISHED_CHAPTER_ID = UUID.fromString("91000000-0000-0000-0000-000000000004");
    private static final UUID DRAFT_CHAPTER_ID = UUID.fromString("91000000-0000-0000-0000-000000000005");
    private static final UUID CHAPTER_IN_DRAFT_VOLUME_ID = UUID.fromString("91000000-0000-0000-0000-000000000006");
    private static final int PUBLISHED_VOLUME_ORDER = 9_100_001;
    private static final int DRAFT_VOLUME_ORDER = 9_100_002;
    private static final int PUBLISHED_CHAPTER_NUMBER = 9_100_001;
    private static final int DRAFT_CHAPTER_NUMBER = 9_100_002;
    private static final int CHAPTER_IN_DRAFT_VOLUME_NUMBER = 9_100_003;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReaderChapterAccessQueryPort queryPort;

    @BeforeEach
    void setUp() {
        cleanupDatabase();
        seedData();
    }

    @AfterEach
    void tearDown() {
        cleanupDatabase();
    }

    @Test
    @DisplayName("Published Chapter in published Volume returns only ID and current contentVersion")
    void shouldReturnPublishedNarrationChapterContentVersion() {
        Optional<ReadableNarrationChapterReference> result = queryPort.findPublishedNarrationById(PUBLISHED_CHAPTER_ID);

        assertThat(result).contains(new ReadableNarrationChapterReference(PUBLISHED_CHAPTER_ID, 17L));
    }

    @Test
    @DisplayName("Unpublished Chapter is not publicly eligible for narration")
    void shouldRejectUnpublishedChapter() {
        assertThat(queryPort.findPublishedNarrationById(DRAFT_CHAPTER_ID)).isEmpty();
    }

    @Test
    @DisplayName("Published Chapter in unpublished Volume is not publicly eligible for narration")
    void shouldRejectChapterInUnpublishedVolume() {
        assertThat(queryPort.findPublishedNarrationById(CHAPTER_IN_DRAFT_VOLUME_ID)).isEmpty();
    }

    private void seedData() {
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        Timestamp timestamp = Timestamp.from(now);

        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) "
                        + "VALUES (?, 'h9f1-reader-query@universe.local', '$2a$10$hash', 'H9F1 Reader', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_ID.toString(), timestamp, timestamp
        );
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) "
                        + "VALUES (?, 'H9F1 Published Volume', 'h9f1-published-volume', 'H9F1', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                PUBLISHED_VOLUME_ID.toString(), PUBLISHED_VOLUME_ORDER,
                USER_ID.toString(), USER_ID.toString(), USER_ID.toString(), timestamp, timestamp, timestamp
        );
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) "
                        + "VALUES (?, 'H9F1 Draft Volume', 'h9f1-draft-volume', 'H9F1', ?, 'DRAFT', ?, ?, NULL, NULL, ?, ?, NULL, NULL, 1, 0)",
                DRAFT_VOLUME_ID.toString(), DRAFT_VOLUME_ORDER,
                USER_ID.toString(), USER_ID.toString(), timestamp, timestamp
        );

        seedChapter(
                PUBLISHED_CHAPTER_ID,
                PUBLISHED_VOLUME_ID,
                PUBLISHED_CHAPTER_NUMBER,
                "H9F1 Published Chapter",
                "h9f1-published-chapter",
                "PUBLISHED",
                17L,
                timestamp
        );
        seedChapter(
                DRAFT_CHAPTER_ID,
                PUBLISHED_VOLUME_ID,
                DRAFT_CHAPTER_NUMBER,
                "H9F1 Draft Chapter",
                "h9f1-draft-chapter",
                "DRAFT",
                18L,
                timestamp
        );
        seedChapter(
                CHAPTER_IN_DRAFT_VOLUME_ID,
                DRAFT_VOLUME_ID,
                CHAPTER_IN_DRAFT_VOLUME_NUMBER,
                "H9F1 Chapter in Draft Volume",
                "h9f1-chapter-in-draft-volume",
                "PUBLISHED",
                19L,
                timestamp
        );
    }

    private void seedChapter(
            UUID chapterId,
            UUID volumeId,
            int chapterNumber,
            String title,
            String slug,
            String status,
            long contentVersion,
            Timestamp timestamp
    ) {
        String publishedBy = "PUBLISHED".equals(status) ? USER_ID.toString() : null;
        Timestamp publishedAt = "PUBLISHED".equals(status) ? timestamp : null;
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, "
                        + "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) "
                        + "VALUES (?, ?, ?, ?, ?, 'H9F1 summary', 'H9F1 content', ?, ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, ?)",
                chapterId.toString(), volumeId.toString(), chapterNumber, title, slug, status,
                USER_ID.toString(), USER_ID.toString(), publishedBy,
                timestamp, timestamp, publishedAt, contentVersion
        );
    }

    private void cleanupDatabase() {
        jdbcTemplate.update(
                "DELETE FROM novel_chapters WHERE id IN (?, ?, ?) OR chapter_number IN (?, ?, ?)",
                PUBLISHED_CHAPTER_ID.toString(), DRAFT_CHAPTER_ID.toString(), CHAPTER_IN_DRAFT_VOLUME_ID.toString(),
                PUBLISHED_CHAPTER_NUMBER, DRAFT_CHAPTER_NUMBER, CHAPTER_IN_DRAFT_VOLUME_NUMBER
        );
        jdbcTemplate.update(
                "DELETE FROM novel_volumes WHERE id IN (?, ?) OR sort_order IN (?, ?)",
                PUBLISHED_VOLUME_ID.toString(), DRAFT_VOLUME_ID.toString(),
                PUBLISHED_VOLUME_ORDER, DRAFT_VOLUME_ORDER
        );
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ?", USER_ID.toString());
    }
}
