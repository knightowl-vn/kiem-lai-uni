package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.test.TestDatabaseSupport;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ChapterNarrationSegmentPersistenceAdapter.class)
@DisplayName("ChapterNarrationSegment JPA Persistence Integration Tests (MySQL)")
class ChapterNarrationSegmentJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChapterNarrationSegmentRepositoryPort repositoryPort;

    private static final UUID USER_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID VOLUME_ID = UUID.fromString("50000000-0000-0000-0000-000000000010");
    private static final UUID CHAPTER_1_ID = UUID.fromString("50000000-0000-0000-0000-000000000101");
    private static final UUID CHAPTER_2_ID = UUID.fromString("50000000-0000-0000-0000-000000000102");

    @BeforeEach
    void cleanAndSeedData() {
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_segments WHERE chapter_id IN (?, ?)",
                CHAPTER_1_ID.toString(), CHAPTER_2_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id IN (?, ?)",
                CHAPTER_1_ID.toString(), CHAPTER_2_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ?",
                VOLUME_ID.toString());
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ?",
                USER_ID.toString());

        Instant now = Instant.now();

        // 1. User
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'narr-admin@universe.local', '$2a$10$hash', 'Narr Admin', 'ACTIVE', 'ADMIN', 1, 0, ?, ?)",
                USER_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Narration Test', 'quyen-narr-test', 'Mô tả', 9950, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapters
        seedChapter(CHAPTER_1_ID, 9951, "Chương Narr 1", "chuong-9951-narr");
        seedChapter(CHAPTER_2_ID, 9952, "Chương Narr 2", "chuong-9952-narr");
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
    @DisplayName("Should save and retrieve single narration segment")
    void shouldSaveAndRetrieveSingleSegment() {
        UUID segmentId = UUID.randomUUID();
        Instant now = Instant.now();
        String text = "Lạc Phách sơn đầu gió núi thổi qua rặng trúc rì rào.";

        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                segmentId,
                CHAPTER_1_ID,
                0,
                text,
                now
        );

        repositoryPort.save(segment);

        Optional<ChapterNarrationSegment> loaded = repositoryPort.findById(segmentId);
        assertThat(loaded).isPresent();
        ChapterNarrationSegment s = loaded.get();
        assertThat(s.getId()).isEqualTo(segmentId);
        assertThat(s.getChapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(s.getSegmentIndex()).isEqualTo(0);
        assertThat(s.getText()).isEqualTo(text);
        assertThat(s.getCharacterCount()).isEqualTo(text.length());
        assertThat(s.getContentHash()).isEqualTo(segment.getContentHash());
        assertThat(s.getStatus()).isEqualTo(ChapterNarrationSegmentStatus.CURRENT);
        assertThat(s.isCurrent()).isTrue();
    }

    @Test
    @DisplayName("Should save multiple segments and retrieve by chapter ordered by segmentIndex ASC")
    void shouldSaveMultipleAndRetrieveOrdered() {
        Instant now = Instant.now();
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        ChapterNarrationSegment seg0 = ChapterNarrationSegment.create(id0, CHAPTER_1_ID, 0, "Đoạn 0.", now);
        ChapterNarrationSegment seg1 = ChapterNarrationSegment.create(id1, CHAPTER_1_ID, 1, "Đoạn 1.", now);
        ChapterNarrationSegment seg2 = ChapterNarrationSegment.create(id2, CHAPTER_1_ID, 2, "Đoạn 2.", now);

        repositoryPort.saveAll(List.of(seg2, seg0, seg1)); // intentionally unordered

        List<ChapterNarrationSegment> segments = repositoryPort.findByChapterId(CHAPTER_1_ID);
        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).getId()).isEqualTo(id0);
        assertThat(segments.get(0).getSegmentIndex()).isEqualTo(0);
        assertThat(segments.get(1).getId()).isEqualTo(id1);
        assertThat(segments.get(1).getSegmentIndex()).isEqualTo(1);
        assertThat(segments.get(2).getId()).isEqualTo(id2);
        assertThat(segments.get(2).getSegmentIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should filter segments by status (CURRENT vs RETIRED)")
    void shouldFilterSegmentsByStatus() {
        Instant now = Instant.now();
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();

        ChapterNarrationSegment seg0 = ChapterNarrationSegment.create(id0, CHAPTER_1_ID, 0, "Đoạn hiện tại.", now);
        ChapterNarrationSegment seg1 = ChapterNarrationSegment.create(id1, CHAPTER_1_ID, 1, "Đoạn đã gỡ.", now);
        seg1.retire(now);

        repositoryPort.saveAll(List.of(seg0, seg1));

        List<ChapterNarrationSegment> currentList = repositoryPort.findByChapterIdAndStatus(CHAPTER_1_ID, ChapterNarrationSegmentStatus.CURRENT);
        assertThat(currentList).hasSize(1);
        assertThat(currentList.get(0).getId()).isEqualTo(id0);

        List<ChapterNarrationSegment> retiredList = repositoryPort.findByChapterIdAndStatus(CHAPTER_1_ID, ChapterNarrationSegmentStatus.RETIRED);
        assertThat(retiredList).hasSize(1);
        assertThat(retiredList.get(0).getId()).isEqualTo(id1);
    }

    @Test
    @DisplayName("Should update segment lifecycle state (reposition, retire, restore) on real database")
    void shouldUpdateSegmentLifecycleStateOnDatabase() {
        Instant now = Instant.now();
        UUID segmentId = UUID.randomUUID();

        ChapterNarrationSegment segment = ChapterNarrationSegment.create(segmentId, CHAPTER_1_ID, 0, "Nội dung ban đầu.", now);
        repositoryPort.save(segment);

        // 1. Reposition to index 3
        Instant t1 = now.plusSeconds(10);
        segment.reposition(3, t1);
        repositoryPort.save(segment);

        ChapterNarrationSegment reloaded1 = repositoryPort.findById(segmentId).orElseThrow();
        assertThat(reloaded1.getSegmentIndex()).isEqualTo(3);
        assertThat(reloaded1.isCurrent()).isTrue();

        // 2. Retire
        Instant t2 = now.plusSeconds(20);
        reloaded1.retire(t2);
        repositoryPort.save(reloaded1);

        ChapterNarrationSegment reloaded2 = repositoryPort.findById(segmentId).orElseThrow();
        assertThat(reloaded2.isRetired()).isTrue();

        // 3. Restore to index 1
        Instant t3 = now.plusSeconds(30);
        reloaded2.restore(1, t3);
        repositoryPort.save(reloaded2);

        ChapterNarrationSegment reloaded3 = repositoryPort.findById(segmentId).orElseThrow();
        assertThat(reloaded3.isCurrent()).isTrue();
        assertThat(reloaded3.getSegmentIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should cascade delete narration segments when parent chapter is deleted")
    void shouldCascadeDeleteSegmentsWhenChapterDeleted() {
        Instant now = Instant.now();
        UUID segId = UUID.randomUUID();
        ChapterNarrationSegment seg = ChapterNarrationSegment.create(segId, CHAPTER_1_ID, 0, "Đoạn văn thuộc chương 1.", now);
        repositoryPort.save(seg);

        assertThat(repositoryPort.findByChapterId(CHAPTER_1_ID)).hasSize(1);

        // Delete parent chapter directly via SQL
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id = ?", CHAPTER_1_ID.toString());

        // Verify segments are removed via MySQL ON DELETE CASCADE
        assertThat(repositoryPort.findByChapterId(CHAPTER_1_ID)).isEmpty();
    }
}
