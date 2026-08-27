package com.universe.novel.infrastructure.persistence.reference;

import com.universe.novel.application.ports.ChapterWikiReferenceRepositoryPort;
import com.universe.novel.domain.reference.ChapterWikiReference;
import com.universe.novel.domain.reference.ChapterWikiReferenceScope;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ChapterWikiReferencePersistenceAdapter.class)
@DisplayName("ChapterWikiReference JPA Persistence Integration Tests (MySQL)")
class ChapterWikiReferenceJpaPersistenceIntegrationTest {

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
        String dbPass = System.getenv("DB_PASSWORD");
        if (dbPass != null && !dbPass.isBlank()) {
            return dbPass;
        }
        return "123456";
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        String url = "jdbc:mysql://" + resolveHost() + "/" + resolveDatabaseName() + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ChapterWikiReferenceJpaPersistenceIntegrationTest::resolveUser);
        registry.add("spring.datasource.password", ChapterWikiReferenceJpaPersistenceIntegrationTest::resolvePassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChapterWikiReferenceRepositoryPort repositoryPort;

    private static final UUID USER_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID VOLUME_ID = UUID.fromString("60000000-0000-0000-0000-000000000010");
    private static final UUID CHAPTER_1_ID = UUID.fromString("60000000-0000-0000-0000-000000000101");
    private static final UUID CHAPTER_2_ID = UUID.fromString("60000000-0000-0000-0000-000000000102");
    private static final UUID WIKI_ARTICLE_1_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID WIKI_ARTICLE_2_ID = UUID.fromString("70000000-0000-0000-0000-000000000002");

    @BeforeEach
    void cleanAndSeedData() {
        jdbcTemplate.update("DELETE FROM novel_chapter_wiki_references WHERE chapter_id IN (?, ?)",
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
                        "VALUES (?, 'ref-admin@universe.local', '$2a$10$hash', 'Ref Admin', 'ACTIVE', 'ADMIN', 1, 0, ?, ?)",
                USER_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Ref Test', 'quyen-ref-test', 'Mô tả', 9901, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapters
        seedChapter(CHAPTER_1_ID, 9901, "Chương Ref 1", "chuong-9901-ref");
        seedChapter(CHAPTER_2_ID, 9902, "Chương Ref 2", "chuong-9902-ref");
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
    @DisplayName("Should save and retrieve CHAPTER_WIDE reference")
    void shouldSaveAndRetrieveChapterWideReference() {
        UUID refId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterWikiReference reference = ChapterWikiReference.createChapterWide(
                refId,
                CHAPTER_1_ID,
                "Trần Bình An",
                WIKI_ARTICLE_1_ID,
                USER_ID,
                now
        );

        ChapterWikiReference saved = repositoryPort.save(reference);
        assertThat(saved).isNotNull();

        Optional<ChapterWikiReference> found = repositoryPort.findById(refId);
        assertThat(found).isPresent();
        ChapterWikiReference loaded = found.get();
        assertThat(loaded.getId()).isEqualTo(refId);
        assertThat(loaded.getChapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(loaded.getTerm()).isEqualTo("Trần Bình An");
        assertThat(loaded.getNormalizedTerm()).isEqualTo("trần bình an");
        assertThat(loaded.getReferenceScope()).isEqualTo(ChapterWikiReferenceScope.CHAPTER_WIDE);
        assertThat(loaded.getOccurrenceIndex()).isEqualTo(0);
        assertThat(loaded.getBoundContentVersion()).isNull();
        assertThat(loaded.getContextSnippet()).isNull();
        assertThat(loaded.getWikiArticleId()).isEqualTo(WIKI_ARTICLE_1_ID);
        assertThat(loaded.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(loaded.getUpdatedBy()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("Should save and retrieve OCCURRENCE_SPECIFIC reference with bound content version and snippet")
    void shouldSaveAndRetrieveOccurrenceSpecificReference() {
        UUID refId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterWikiReference reference = ChapterWikiReference.createOccurrenceSpecific(
                refId,
                CHAPTER_1_ID,
                "Nhất Khí Hóa Tam Thanh",
                2,
                "thi triển Nhất Khí Hóa Tam Thanh hướng về đối phương",
                3L,
                WIKI_ARTICLE_2_ID,
                USER_ID,
                now
        );

        repositoryPort.save(reference);

        Optional<ChapterWikiReference> found = repositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                CHAPTER_1_ID,
                "nhất khí hóa tam thanh",
                2
        );

        assertThat(found).isPresent();
        ChapterWikiReference loaded = found.get();
        assertThat(loaded.getReferenceScope()).isEqualTo(ChapterWikiReferenceScope.OCCURRENCE_SPECIFIC);
        assertThat(loaded.getOccurrenceIndex()).isEqualTo(2);
        assertThat(loaded.getBoundContentVersion()).isEqualTo(3L);
        assertThat(loaded.getContextSnippet()).isEqualTo("thi triển Nhất Khí Hóa Tam Thanh hướng về đối phương");
        assertThat(loaded.getWikiArticleId()).isEqualTo(WIKI_ARTICLE_2_ID);
    }

    @Test
    @DisplayName("Should find multiple references by chapterId ordered by normalizedTerm ASC, occurrenceIndex ASC")
    void shouldFindByChapterIdOrdered() {
        Instant now = Instant.now();

        // 1. Chapter-wide for 'Trần Bình An' (occ 0)
        repositoryPort.save(ChapterWikiReference.createChapterWide(
                UUID.randomUUID(), CHAPTER_1_ID, "Trần Bình An", WIKI_ARTICLE_1_ID, USER_ID, now));

        // 2. Occurrence 2 for 'Trần Bình An'
        repositoryPort.save(ChapterWikiReference.createOccurrenceSpecific(
                UUID.randomUUID(), CHAPTER_1_ID, "Trần Bình An", 2, "snippet 2", 1L, WIKI_ARTICLE_2_ID, USER_ID, now));

        // 3. Occurrence 1 for 'Trần Bình An'
        repositoryPort.save(ChapterWikiReference.createOccurrenceSpecific(
                UUID.randomUUID(), CHAPTER_1_ID, "Trần Bình An", 1, "snippet 1", 1L, WIKI_ARTICLE_1_ID, USER_ID, now));

        // 4. Chapter-wide for 'Bảo Bình Châu' (alphabetically earlier)
        repositoryPort.save(ChapterWikiReference.createChapterWide(
                UUID.randomUUID(), CHAPTER_1_ID, "Bảo Bình Châu", WIKI_ARTICLE_1_ID, USER_ID, now));

        List<ChapterWikiReference> references = repositoryPort.findByChapterId(CHAPTER_1_ID);
        assertThat(references).hasSize(4);

        // Assert ordering: 'bảo bình châu' (occ 0), 'trần bình an' (occ 0), 'trần bình an' (occ 1), 'trần bình an' (occ 2)
        assertThat(references.get(0).getNormalizedTerm()).isEqualTo("bảo bình châu");
        assertThat(references.get(0).getOccurrenceIndex()).isEqualTo(0);

        assertThat(references.get(1).getNormalizedTerm()).isEqualTo("trần bình an");
        assertThat(references.get(1).getOccurrenceIndex()).isEqualTo(0);

        assertThat(references.get(2).getNormalizedTerm()).isEqualTo("trần bình an");
        assertThat(references.get(2).getOccurrenceIndex()).isEqualTo(1);

        assertThat(references.get(3).getNormalizedTerm()).isEqualTo("trần bình an");
        assertThat(references.get(3).getOccurrenceIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should check existence by chapterId, normalizedTerm, and occurrenceIndex")
    void shouldCheckExistence() {
        Instant now = Instant.now();
        repositoryPort.save(ChapterWikiReference.createChapterWide(
                UUID.randomUUID(), CHAPTER_1_ID, "Kiếm Lai", WIKI_ARTICLE_1_ID, USER_ID, now));

        assertThat(repositoryPort.existsByChapterIdAndNormalizedTermAndOccurrenceIndex(
                CHAPTER_1_ID, "kiếm lai", 0)).isTrue();
        assertThat(repositoryPort.existsByChapterIdAndNormalizedTermAndOccurrenceIndex(
                CHAPTER_1_ID, "kiếm lai", 1)).isFalse();
        assertThat(repositoryPort.existsByChapterIdAndNormalizedTermAndOccurrenceIndex(
                CHAPTER_2_ID, "kiếm lai", 0)).isFalse();
    }

    @Test
    @DisplayName("Should enforce UNIQUE(chapter_id, normalized_term, occurrence_index) constraint")
    void shouldRejectDuplicateChapterTermAndOccurrence() {
        Instant now = Instant.now();
        repositoryPort.save(ChapterWikiReference.createChapterWide(
                UUID.randomUUID(), CHAPTER_1_ID, "Đạo Tổ", WIKI_ARTICLE_1_ID, USER_ID, now));

        ChapterWikiReference duplicate = ChapterWikiReference.createChapterWide(
                UUID.randomUUID(), CHAPTER_1_ID, "đạo tổ", WIKI_ARTICLE_2_ID, USER_ID, now);

        assertThatThrownBy(() -> repositoryPort.save(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should cascade delete references when chapter is deleted")
    void shouldCascadeDeleteWhenChapterIsDeleted() {
        Instant now = Instant.now();
        repositoryPort.save(ChapterWikiReference.createChapterWide(
                UUID.randomUUID(), CHAPTER_1_ID, "Thuật ngữ 1", WIKI_ARTICLE_1_ID, USER_ID, now));
        repositoryPort.save(ChapterWikiReference.createOccurrenceSpecific(
                UUID.randomUUID(), CHAPTER_1_ID, "Thuật ngữ 2", 1, "snip", 1L, WIKI_ARTICLE_2_ID, USER_ID, now));

        assertThat(repositoryPort.findByChapterId(CHAPTER_1_ID)).hasSize(2);

        // Delete parent chapter directly via SQL
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id = ?", CHAPTER_1_ID.toString());

        // Verify references are removed via MySQL ON DELETE CASCADE
        assertThat(repositoryPort.findByChapterId(CHAPTER_1_ID)).isEmpty();
    }

    @Test
    @DisplayName("Should delete single reference and delete all by chapterId")
    void shouldDeleteReferenceAndAllByChapter() {
        Instant now = Instant.now();
        ChapterWikiReference ref1 = repositoryPort.save(ChapterWikiReference.createChapterWide(
                UUID.randomUUID(), CHAPTER_1_ID, "T1", WIKI_ARTICLE_1_ID, USER_ID, now));
        repositoryPort.save(ChapterWikiReference.createChapterWide(
                UUID.randomUUID(), CHAPTER_1_ID, "T2", WIKI_ARTICLE_1_ID, USER_ID, now));

        repositoryPort.delete(ref1);
        assertThat(repositoryPort.findByChapterId(CHAPTER_1_ID)).hasSize(1);

        repositoryPort.deleteAllByChapterId(CHAPTER_1_ID);
        assertThat(repositoryPort.findByChapterId(CHAPTER_1_ID)).isEmpty();
    }

    @Test
    @DisplayName("Should update existing reference, persist mutable fields, and preserve immutable metadata")
    void shouldUpdateExistingReferenceAndPersistMutableFields() {
        UUID refId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-27T10:00:00Z");

        ChapterWikiReference original = ChapterWikiReference.createOccurrenceSpecific(
                refId,
                CHAPTER_1_ID,
                "Nhất Khí Hóa Tam Thanh",
                1,
                "ngữ cảnh ban đầu",
                1L,
                WIKI_ARTICLE_1_ID,
                USER_ID,
                createdAt
        );
        repositoryPort.save(original);

        ChapterWikiReference loaded = repositoryPort.findById(refId).orElseThrow();

        UUID newTargetArticleId = WIKI_ARTICLE_2_ID;
        Instant updatedAt = createdAt.plusSeconds(3600);

        loaded.updateTargetArticle(newTargetArticleId, USER_ID, updatedAt);
        loaded.updateOccurrenceContext(2, "ngữ cảnh cập nhật", 2L, USER_ID, updatedAt);

        repositoryPort.save(loaded);

        Optional<ChapterWikiReference> reloadedOpt = repositoryPort.findById(refId);
        assertThat(reloadedOpt).isPresent();
        ChapterWikiReference reloaded = reloadedOpt.get();

        // Mutable fields updated
        assertThat(reloaded.getWikiArticleId()).isEqualTo(newTargetArticleId);
        assertThat(reloaded.getOccurrenceIndex()).isEqualTo(2);
        assertThat(reloaded.getContextSnippet()).isEqualTo("ngữ cảnh cập nhật");
        assertThat(reloaded.getBoundContentVersion()).isEqualTo(2L);
        assertThat(reloaded.getUpdatedBy()).isEqualTo(USER_ID);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);

        // Immutable fields preserved
        assertThat(reloaded.getId()).isEqualTo(refId);
        assertThat(reloaded.getChapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(reloaded.getTerm()).isEqualTo("Nhất Khí Hóa Tam Thanh");
        assertThat(reloaded.getNormalizedTerm()).isEqualTo("nhất khí hóa tam thanh");
        assertThat(reloaded.getReferenceScope()).isEqualTo(ChapterWikiReferenceScope.OCCURRENCE_SPECIFIC);
        assertThat(reloaded.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
    }
}
