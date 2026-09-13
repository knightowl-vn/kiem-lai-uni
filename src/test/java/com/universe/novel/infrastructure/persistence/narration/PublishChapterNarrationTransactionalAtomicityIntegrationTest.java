package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.chapter.PublishChapterCommand;
import com.universe.novel.application.chapter.PublishChapterUseCase;
import com.universe.novel.application.chapter.revision.ChapterRevisionRecorder;
import com.universe.novel.application.narration.AdaptiveNarrationTextSegmenter;
import com.universe.novel.application.narration.ReconcileChapterNarrationSegmentsUseCase;
import com.universe.novel.application.narration.SynchronizePublishedChapterNarrationUseCase;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
import com.universe.novel.domain.narration.NarrationManifestHasher;
import com.universe.novel.domain.narration.NarrationTextSegment;
import com.universe.novel.infrastructure.markdown.CommonMarkChapterNarrationBlockExtractor;
import com.universe.novel.infrastructure.persistence.chapter.ChapterPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.revision.ChapterRevisionPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.volume.VolumePersistenceAdapter;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;

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
import org.springframework.test.context.TestPropertySource;
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
        ChapterRevisionPersistenceAdapter.class,
        ChapterRevisionRecorder.class,
        UuidGeneratorAdapter.class,
        ChapterNarrationSegmentPersistenceAdapter.class,
        ChapterNarrationManifestPersistenceAdapter.class,
        CommonMarkChapterNarrationBlockExtractor.class,
        AdaptiveNarrationTextSegmenter.class,
        ReconcileChapterNarrationSegmentsUseCase.class,
        SynchronizePublishedChapterNarrationUseCase.class,
        PublishChapterUseCase.class,
        PublishChapterNarrationTransactionalAtomicityIntegrationTest.TestConfig.class
})
@DisplayName("PublishChapterNarration Transactional Atomicity Integration Tests (MySQL)")
class PublishChapterNarrationTransactionalAtomicityIntegrationTest {

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.universe.novel.application.reader.PublicReaderChapterListInvalidationCoordinator publicReaderChapterListInvalidationCoordinator;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.universe.novel.application.reader.PublicNovelLandingInvalidationCoordinator publicNovelLandingInvalidationCoordinator;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.universe.novel.application.reader.PublicReaderRenderedChapterInvalidationCoordinator publicReaderRenderedChapterInvalidationCoordinator;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.universe.novel.application.reader.PublicReaderNavigationInvalidationCoordinator publicReaderNavigationInvalidationCoordinator;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static final UUID ADMIN_ID = UUID.fromString("77777777-1111-1111-1111-111111111111");
    private static final UUID VOLUME_ID = UUID.fromString("77777777-2222-2222-2222-222222222222");
    private static final UUID CHAPTER_ID = UUID.fromString("77777777-3333-3333-3333-333333333333");
    private static final int VOLUME_SORT_ORDER = 4_000_001;
    private static final int CHAPTER_NUMBER = 4_000_001;
    private static final String CHAPTER_SLUG = "quyen-publish-sync-test-chuong-4000001";

    private static final String CHAPTER_CONTENT = """
            Đây là đoạn văn thứ nhất của chương tiểu thuyết dùng cho kiểm thử tích hợp transactional atomicity.
            
            Đây là đoạn văn thứ hai tiếp nối câu chuyện, đảm bảo nội dung có nhiều khối ngữ nghĩa chuẩn.
            
            Đây là đoạn văn thứ ba kết thúc chương với tình tiết hấp dẫn.
            """;

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
    private ChapterNarrationManifestPersistenceAdapter chapterNarrationManifestPersistenceAdapter;

    @Autowired
    private PublishChapterUseCase publishChapterUseCase;

    @BeforeEach
    void setUp() {
        Mockito.reset(chapterNarrationManifestPersistenceAdapter);
        cleanupDatabase();
        seedBaseData();
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(chapterNarrationManifestPersistenceAdapter);
        cleanupDatabase();
    }

    private void cleanupDatabase() {
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_manifests WHERE chapter_id = ?", CHAPTER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_segments WHERE chapter_id = ?", CHAPTER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapter_revisions WHERE chapter_id = ?", CHAPTER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id = ? OR chapter_number = ?", CHAPTER_ID.toString(), CHAPTER_NUMBER);
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ? OR sort_order = ?", VOLUME_ID.toString(), VOLUME_SORT_ORDER);
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ? OR email = 'publish-sync-admin@universe.local'", ADMIN_ID.toString());
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. User
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'publish-sync-admin@universe.local', '$2a$10$hash', 'Publish Sync Admin', 'ACTIVE', 'ADMIN', 1, 0, ?, ?)",
                ADMIN_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume (PUBLISHED)
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Publish Sync Test', 'quyen-publish-sync-test', 'Mô tả quyển test', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), VOLUME_SORT_ORDER, ADMIN_ID.toString(), ADMIN_ID.toString(), ADMIN_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
    }

    private void seedDraftChapterAndRevision(String content) {
        Instant now = Instant.now();

        // Chapter Aggregate (DRAFT, aggregateVersion = 1, contentVersion = 1)
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, ?, 'Chương 1: Khởi Đầu Narration', ?, 'Tóm tắt ban đầu', ?, 'DRAFT', " +
                        "?, ?, NULL, NULL, ?, ?, NULL, NULL, 1, 0, 1)",
                CHAPTER_ID.toString(), VOLUME_ID.toString(), CHAPTER_NUMBER, CHAPTER_SLUG, content,
                ADMIN_ID.toString(), ADMIN_ID.toString(),
                Timestamp.from(now), Timestamp.from(now)
        );

        // Revision #1 (CREATE_DRAFT)
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_revisions (id, chapter_id, volume_id, revision_number, content_version, chapter_number, title, slug, summary, content, status, change_type, edit_summary, edited_by, created_at) " +
                        "VALUES (?, ?, ?, 1, 1, ?, 'Chương 1: Khởi Đầu Narration', ?, 'Tóm tắt ban đầu', ?, 'DRAFT', 'CREATE_DRAFT', NULL, ?, ?)",
                UUID.randomUUID().toString(), CHAPTER_ID.toString(), VOLUME_ID.toString(), CHAPTER_NUMBER,
                CHAPTER_SLUG, content, ADMIN_ID.toString(), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("CASE A: Publish Chapter thành công -> Chapter PUBLISHED, PUBLISH revision, Narration segments và Manifest persistence commit atomically")
    void shouldAtomicallyCommitPublishChapterRevisionSegmentsAndManifest() {
        seedDraftChapterAndRevision(CHAPTER_CONTENT);

        PublishChapterCommand command = new PublishChapterCommand(CHAPTER_ID, ADMIN_ID);
        ChapterDTO result = publishChapterUseCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("PUBLISHED");
        assertThat(result.aggregateVersion()).isEqualTo(2L);
        assertThat(result.publishedBy()).isEqualTo(ADMIN_ID);
        assertThat(result.publishedAt()).isNotNull();

        // 1. Assert Chapter in database
        Map<String, Object> chapterRow = jdbcTemplate.queryForMap(
                "SELECT status, aggregate_version, content_version, published_by, published_at FROM novel_chapters WHERE id = ?",
                CHAPTER_ID.toString()
        );
        assertThat(chapterRow.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) chapterRow.get("aggregate_version")).longValue()).isEqualTo(2L);
        assertThat(((Number) chapterRow.get("content_version")).longValue()).isEqualTo(1L);
        assertThat(chapterRow.get("published_by")).isEqualTo(ADMIN_ID.toString());
        assertThat(chapterRow.get("published_at")).isNotNull();

        // 2. Assert Chapter Revisions in database (Revision 1 = CREATE_DRAFT, Revision 2 = PUBLISH)
        List<Map<String, Object>> revisions = jdbcTemplate.queryForList(
                "SELECT revision_number, content_version, status, change_type, edited_by FROM novel_chapter_revisions WHERE chapter_id = ? ORDER BY revision_number ASC",
                CHAPTER_ID.toString()
        );
        assertThat(revisions).hasSize(2);

        Map<String, Object> rev1 = revisions.get(0);
        assertThat(((Number) rev1.get("revision_number")).longValue()).isEqualTo(1L);
        assertThat(rev1.get("change_type")).isEqualTo("CREATE_DRAFT");
        assertThat(rev1.get("status")).isEqualTo("DRAFT");

        Map<String, Object> rev2 = revisions.get(1);
        assertThat(((Number) rev2.get("revision_number")).longValue()).isEqualTo(2L);
        assertThat(((Number) rev2.get("content_version")).longValue()).isEqualTo(1L);
        assertThat(rev2.get("change_type")).isEqualTo("PUBLISH");
        assertThat(rev2.get("status")).isEqualTo("PUBLISHED");
        assertThat(rev2.get("edited_by")).isEqualTo(ADMIN_ID.toString());

        // 3. Assert Narration Segments in database
        List<Map<String, Object>> segments = jdbcTemplate.queryForList(
                "SELECT id, segment_index, content_hash, text, status FROM novel_chapter_narration_segments WHERE chapter_id = ? ORDER BY segment_index ASC",
                CHAPTER_ID.toString()
        );
        assertThat(segments).isNotEmpty();

        List<NarrationTextSegment> domainSegments = segments.stream()
                .map(row -> {
                    assertThat(row.get("status")).isEqualTo("CURRENT");
                    String text = (String) row.get("text");
                    String contentHash = (String) row.get("content_hash");
                    int index = ((Number) row.get("segment_index")).intValue();
                    return new NarrationTextSegment(
                            index,
                            text,
                            text.length(),
                            contentHash
                    );
                })
                .toList();

        // Verify segment contiguous 0..N-1 indices
        for (int i = 0; i < domainSegments.size(); i++) {
            assertThat(domainSegments.get(i).index()).isEqualTo(i);
        }

        // 4. Assert Chapter Narration Manifest in database
        List<Map<String, Object>> manifests = jdbcTemplate.queryForList(
                "SELECT chapter_id, source_content_version, manifest_hash, created_at, updated_at FROM novel_chapter_narration_manifests WHERE chapter_id = ?",
                CHAPTER_ID.toString()
        );
        assertThat(manifests).hasSize(1);

        Map<String, Object> manifestRow = manifests.get(0);
        assertThat(manifestRow.get("chapter_id")).isEqualTo(CHAPTER_ID.toString());
        assertThat(((Number) manifestRow.get("source_content_version")).longValue()).isEqualTo(1L);
        assertThat(manifestRow.get("created_at")).isNotNull();
        assertThat(manifestRow.get("updated_at")).isNotNull();

        String expectedManifestHash = NarrationManifestHasher.computeManifestHash(domainSegments);
        assertThat(manifestRow.get("manifest_hash")).isEqualTo(expectedManifestHash);
    }

    @Test
    @DisplayName("CASE B: Khi manifest persistence thất bại -> Spring @Transactional rollback toàn bộ Chapter publish, revision, segments và manifest")
    void shouldRollbackEntirePublishTransactionWhenManifestPersistenceFails() {
        seedDraftChapterAndRevision(CHAPTER_CONTENT);

        // Failure Injection: allow chapter save, revision save, and segment saves to execute,
        // but force ChapterNarrationManifestPersistenceAdapter.save to throw RuntimeException
        Mockito.doThrow(new RuntimeException("Simulated failure in ChapterNarrationManifestRepositoryPort.save"))
                .when(chapterNarrationManifestPersistenceAdapter)
                .save(any(ChapterNarrationManifest.class));

        PublishChapterCommand command = new PublishChapterCommand(CHAPTER_ID, ADMIN_ID);

        // Execute use case and expect exception propagation
        assertThatThrownBy(() -> publishChapterUseCase.execute(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated failure in ChapterNarrationManifestRepositoryPort.save");

        // Fresh Database Assertions outside the failed transaction
        // 1. Chapter remains DRAFT with aggregate_version = 1, published_at / published_by NULL
        Map<String, Object> chapterRow = jdbcTemplate.queryForMap(
                "SELECT status, aggregate_version, content_version, published_by, published_at FROM novel_chapters WHERE id = ?",
                CHAPTER_ID.toString()
        );
        assertThat(chapterRow.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) chapterRow.get("aggregate_version")).longValue()).isEqualTo(1L);
        assertThat(((Number) chapterRow.get("content_version")).longValue()).isEqualTo(1L);
        assertThat(chapterRow.get("published_by")).isNull();
        assertThat(chapterRow.get("published_at")).isNull();

        // 2. Revisions: Only the initial CREATE_DRAFT revision exists (NO PUBLISH revision)
        List<Map<String, Object>> revisions = jdbcTemplate.queryForList(
                "SELECT revision_number, change_type, status FROM novel_chapter_revisions WHERE chapter_id = ? ORDER BY revision_number ASC",
                CHAPTER_ID.toString()
        );
        assertThat(revisions).hasSize(1);
        assertThat(((Number) revisions.get(0).get("revision_number")).longValue()).isEqualTo(1L);
        assertThat(revisions.get(0).get("change_type")).isEqualTo("CREATE_DRAFT");
        assertThat(revisions.get(0).get("status")).isEqualTo("DRAFT");

        // 3. Narration Segments: 0 rows exist for this chapter
        Integer segmentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_narration_segments WHERE chapter_id = ?",
                Integer.class,
                CHAPTER_ID.toString()
        );
        assertThat(segmentCount).isEqualTo(0);

        // 4. Narration Manifest: 0 rows exist for this chapter
        Integer manifestCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_narration_manifests WHERE chapter_id = ?",
                Integer.class,
                CHAPTER_ID.toString()
        );
        assertThat(manifestCount).isEqualTo(0);
    }
}
