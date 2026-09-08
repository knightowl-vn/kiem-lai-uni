package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
import com.universe.test.TestDatabaseSupport;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ChapterNarrationManifestPersistenceAdapter.class)
@DisplayName("ChapterNarrationManifest JPA Persistence Integration Tests (MySQL)")
class ChapterNarrationManifestJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private ChapterNarrationManifestRepositoryPort repositoryPort;

    private static final UUID USER_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID VOLUME_ID = UUID.fromString("60000000-0000-0000-0000-000000000010");
    private static final UUID CHAPTER_1_ID = UUID.fromString("60000000-0000-0000-0000-000000000101");
    private static final UUID CHAPTER_2_ID = UUID.fromString("60000000-0000-0000-0000-000000000102");

    private static final String HASH_V1 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String HASH_V2 = "1111111111111111111111111111111111111111111111111111111111111111";

    @BeforeEach
    void cleanAndSeedData() {
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_manifests WHERE chapter_id IN (?, ?)",
                CHAPTER_1_ID.toString(), CHAPTER_2_ID.toString());
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
                        "VALUES (?, 'manifest-admin@universe.local', '$2a$10$hash', 'Manifest Admin', 'ACTIVE', 'ADMIN', 1, 0, ?, ?)",
                USER_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Manifest Test', 'quyen-manifest-test', 'Mô tả', 9960, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapters
        seedChapter(CHAPTER_1_ID, 9961, "Chương Manifest 1", "chuong-9961-manifest");
        seedChapter(CHAPTER_2_ID, 9962, "Chương Manifest 2", "chuong-9962-manifest");
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
    @DisplayName("Should save new manifest and find existing with exact field round trip")
    void shouldSaveAndFindExistingManifestWithExactFieldRoundTrip() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        ChapterNarrationManifest manifest = ChapterNarrationManifest.create(
                CHAPTER_1_ID,
                1L,
                HASH_V1,
                now
        );

        ChapterNarrationManifest saved = repositoryPort.save(manifest);
        assertThat(saved.getChapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(saved.getSourceContentVersion()).isEqualTo(1L);
        assertThat(saved.getManifestHash()).isEqualTo(HASH_V1);
        assertThat(saved.getCreatedAt()).isEqualTo(now);
        assertThat(saved.getUpdatedAt()).isEqualTo(now);

        entityManager.clear();

        Optional<ChapterNarrationManifest> loaded = repositoryPort.findByChapterId(CHAPTER_1_ID);
        assertThat(loaded).isPresent();
        ChapterNarrationManifest m = loaded.get();
        assertThat(m.getChapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(m.getSourceContentVersion()).isEqualTo(1L);
        assertThat(m.getManifestHash()).isEqualTo(HASH_V1);
        assertThat(m.getCreatedAt()).isEqualTo(now);
        assertThat(m.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Should return empty optional when finding missing or null chapterId")
    void shouldReturnEmptyWhenFindingMissingOrNull() {
        assertThat(repositoryPort.findByChapterId(CHAPTER_2_ID)).isEmpty();
        assertThat(repositoryPort.findByChapterId(null)).isEmpty();
    }

    @Test
    @DisplayName("Should update manifest on reconcileTo and persist modified fields")
    void shouldUpdateManifestOnReconcile() {
        Instant t0 = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        ChapterNarrationManifest manifest = ChapterNarrationManifest.create(
                CHAPTER_1_ID,
                1L,
                HASH_V1,
                t0
        );
        repositoryPort.save(manifest);
        entityManager.clear();

        ChapterNarrationManifest loaded = repositoryPort.findByChapterId(CHAPTER_1_ID).orElseThrow();
        Instant t1 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        loaded.reconcileTo(2L, HASH_V2, t1);

        repositoryPort.save(loaded);
        entityManager.clear();

        ChapterNarrationManifest updated = repositoryPort.findByChapterId(CHAPTER_1_ID).orElseThrow();
        assertThat(updated.getChapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(updated.getSourceContentVersion()).isEqualTo(2L);
        assertThat(updated.getManifestHash()).isEqualTo(HASH_V2);
        assertThat(updated.getCreatedAt()).isEqualTo(t0);
        assertThat(updated.getUpdatedAt()).isEqualTo(t1);
    }

    @Test
    @DisplayName("Should cascade delete manifest when parent chapter is deleted")
    void shouldCascadeDeleteWhenChapterDeleted() {
        ChapterNarrationManifest manifest = ChapterNarrationManifest.create(
                CHAPTER_1_ID,
                1L,
                HASH_V1,
                Instant.now()
        );
        repositoryPort.save(manifest);
        entityManager.clear();

        assertThat(repositoryPort.findByChapterId(CHAPTER_1_ID)).isPresent();

        // Delete parent chapter via direct SQL
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id = ?", CHAPTER_1_ID.toString());
        entityManager.clear();

        assertThat(repositoryPort.findByChapterId(CHAPTER_1_ID)).isEmpty();
    }

    @Test
    @DisplayName("Should fail foreign key constraint if chapter does not exist")
    void shouldFailForeignKeyConstraint() {
        UUID nonExistentChapterId = UUID.randomUUID();
        ChapterNarrationManifest manifest = ChapterNarrationManifest.create(
                nonExistentChapterId,
                1L,
                HASH_V1,
                Instant.now()
        );

        assertThatThrownBy(() -> {
            repositoryPort.save(manifest);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
