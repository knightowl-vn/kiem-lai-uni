package com.universe.novel.infrastructure.persistence.locator;

import com.universe.novel.application.ports.PublishedChapterLocatorQueryPort;
import com.universe.novel.application.ports.PublishedChapterLocatorQueryPort.PublishedChapterLocatorRecord;
import com.universe.novel.infrastructure.persistence.chapter.ChapterPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.volume.VolumePersistenceAdapter;
import com.universe.shared.id.UuidGeneratorAdapter;
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
import java.util.List;
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
@Import({
        VolumePersistenceAdapter.class,
        ChapterPersistenceAdapter.class,
        PublishedChapterLocatorPersistenceAdapter.class,
        UuidGeneratorAdapter.class
})
class PublishedChapterLocatorPersistenceIntegrationTest {

    private static final UUID USER_ID =
            UUID.fromString("1111bbbb-1111-2222-3333-444444444444");

    private static final UUID VOL_PUB_ID =
            UUID.fromString("2222bbbb-1111-2222-3333-444444444444");
    private static final UUID VOL_DRAFT_ID =
            UUID.fromString("3333bbbb-1111-2222-3333-444444444444");
    private static final UUID VOL_ARCHIVED_ID =
            UUID.fromString("4444bbbb-1111-2222-3333-444444444444");

    private static final UUID CH_PUB_PUBVOL_ID =
            UUID.fromString("5555bbbb-1111-2222-3333-444444444444");
    private static final UUID CH_DRAFT_PUBVOL_ID =
            UUID.fromString("6666bbbb-1111-2222-3333-444444444444");
    private static final UUID CH_PUB_DRAFTVOL_ID =
            UUID.fromString("7777bbbb-1111-2222-3333-444444444444");
    private static final UUID CH_ARCHIVED_PUBVOL_ID =
            UUID.fromString("8888bbbb-1111-2222-3333-444444444444");
    private static final UUID CH_PUB_ARCHIVEDVOL_ID =
            UUID.fromString("9999bbbb-1111-2222-3333-444444444444");
    private static final UUID CH_SPECIAL_TITLE_ID =
            UUID.fromString("aaaabbbb-1111-2222-3333-444444444444");

    private static final int VOL_PUB_SORT = 8_000_001;
    private static final int VOL_DRAFT_SORT = 8_000_002;
    private static final int VOL_ARCHIVED_SORT = 8_000_003;

    private static final int CH_PUB_PUBVOL_NUM = 8_000_001;
    private static final int CH_DRAFT_PUBVOL_NUM = 8_000_002;
    private static final int CH_PUB_DRAFTVOL_NUM = 8_000_003;
    private static final int CH_ARCHIVED_PUBVOL_NUM = 8_000_004;
    private static final int CH_PUB_ARCHIVEDVOL_NUM = 8_000_005;
    private static final int CH_SPECIAL_TITLE_NUM = 8_000_006;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PublishedChapterLocatorQueryPort locatorQueryPort;

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
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE volume_id IN (?, ?, ?) OR chapter_number >= 8000000",
                VOL_PUB_ID.toString(), VOL_DRAFT_ID.toString(), VOL_ARCHIVED_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id IN (?, ?, ?) OR sort_order IN (?, ?, ?)",
                VOL_PUB_ID.toString(), VOL_DRAFT_ID.toString(), VOL_ARCHIVED_ID.toString(),
                VOL_PUB_SORT, VOL_DRAFT_SORT, VOL_ARCHIVED_SORT);
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ?",
                USER_ID.toString());
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. User
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'locator-user@universe.local', '$2a$10$hash', 'Locator User', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volumes
        // PUBLISHED Volume
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển 1 Xuất Bản', 'quyen-1-xuat-ban', 'Mô tả', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOL_PUB_ID.toString(), VOL_PUB_SORT, USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
        // DRAFT Volume
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Nháp', 'quyen-nhap', 'Mô tả', ?, 'DRAFT', ?, ?, NULL, NULL, ?, ?, NULL, NULL, 1, 0)",
                VOL_DRAFT_ID.toString(), VOL_DRAFT_SORT, USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now)
        );
        // ARCHIVED Volume
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Lưu Trữ', 'quyen-luu-tru', 'Mô tả', ?, 'ARCHIVED', ?, ?, NULL, ?, ?, ?, NULL, ?, 1, 0)",
                VOL_ARCHIVED_ID.toString(), VOL_ARCHIVED_SORT, USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapters
        // 3.1: PUBLISHED in PUBLISHED volume -> ELIGIBLE
        seedChapter(CH_PUB_PUBVOL_ID, VOL_PUB_ID, CH_PUB_PUBVOL_NUM, "Đại Đạo Triều Thiên", "quyen-1-chuong-1", "PUBLISHED");
        // 3.2: DRAFT in PUBLISHED volume -> EXCLUDED
        seedChapter(CH_DRAFT_PUBVOL_ID, VOL_PUB_ID, CH_DRAFT_PUBVOL_NUM, "Đại Đạo Bản Thảo", "quyen-1-chuong-2", "DRAFT");
        // 3.3: PUBLISHED in DRAFT volume -> EXCLUDED
        seedChapter(CH_PUB_DRAFTVOL_ID, VOL_DRAFT_ID, CH_PUB_DRAFTVOL_NUM, "Đại Đạo Trong Quyển Nháp", "quyen-2-chuong-3", "PUBLISHED");
        // 3.4: ARCHIVED in PUBLISHED volume -> EXCLUDED
        seedChapter(CH_ARCHIVED_PUBVOL_ID, VOL_PUB_ID, CH_ARCHIVED_PUBVOL_NUM, "Đại Đạo Lưu Trữ", "quyen-1-chuong-4", "ARCHIVED");
        // 3.5: PUBLISHED in ARCHIVED volume -> EXCLUDED
        seedChapter(CH_PUB_ARCHIVEDVOL_ID, VOL_ARCHIVED_ID, CH_PUB_ARCHIVEDVOL_NUM, "Đại Đạo Trong Quyển Lưu Trữ", "quyen-3-chuong-5", "PUBLISHED");
        // 3.6: PUBLISHED with special LIKE characters: 100%_Special\Title
        seedChapter(CH_SPECIAL_TITLE_ID, VOL_PUB_ID, CH_SPECIAL_TITLE_NUM, "Tiến Độ 100% Hoàn Tất", "quyen-1-chuong-6", "PUBLISHED");
    }

    private void seedChapter(UUID chapterId, UUID volumeId, int chapterNumber, String title, String slug, String status) {
        Instant now = Instant.now();
        Timestamp pubTimestamp = "PUBLISHED".equals(status) ? Timestamp.from(now) : null;
        String pubBy = "PUBLISHED".equals(status) ? USER_ID.toString() : null;
        Timestamp archTimestamp = "ARCHIVED".equals(status) ? Timestamp.from(now) : null;
        String archBy = "ARCHIVED".equals(status) ? USER_ID.toString() : null;

        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, ?, ?, ?, 'Tóm tắt', 'Nội dung', ?, " +
                        "?, ?, ?, ?, ?, ?, ?, ?, 1, 0, 1)",
                chapterId.toString(), volumeId.toString(), chapterNumber, title, slug, status,
                USER_ID.toString(), USER_ID.toString(), pubBy, archBy,
                Timestamp.from(now), Timestamp.from(now), pubTimestamp, archTimestamp
        );
    }

    @Test
    @DisplayName("Publication invariant: findPublishedByChapterNumber enforces both chapter and volume PUBLISHED")
    void shouldEnforcePublicationInvariantOnFindByChapterNumber() {
        // 1. Published chapter in Published volume => FOUND
        Optional<PublishedChapterLocatorRecord> pubPub =
                locatorQueryPort.findPublishedByChapterNumber(CH_PUB_PUBVOL_NUM);
        assertThat(pubPub).isPresent();
        assertThat(pubPub.get().id()).isEqualTo(CH_PUB_PUBVOL_ID);
        assertThat(pubPub.get().chapterNumber()).isEqualTo(CH_PUB_PUBVOL_NUM);
        assertThat(pubPub.get().title()).isEqualTo("Đại Đạo Triều Thiên");
        assertThat(pubPub.get().volumeSortOrder()).isEqualTo(VOL_PUB_SORT);
        assertThat(pubPub.get().volumeTitle()).isEqualTo("Quyển 1 Xuất Bản");

        // 2. Draft chapter in Published volume => EXCLUDED
        Optional<PublishedChapterLocatorRecord> draftPub =
                locatorQueryPort.findPublishedByChapterNumber(CH_DRAFT_PUBVOL_NUM);
        assertThat(draftPub).isEmpty();

        // 3. Published chapter in Draft volume => EXCLUDED
        Optional<PublishedChapterLocatorRecord> pubDraft =
                locatorQueryPort.findPublishedByChapterNumber(CH_PUB_DRAFTVOL_NUM);
        assertThat(pubDraft).isEmpty();

        // 4. Archived chapter in Published volume => EXCLUDED
        Optional<PublishedChapterLocatorRecord> archivedPub =
                locatorQueryPort.findPublishedByChapterNumber(CH_ARCHIVED_PUBVOL_NUM);
        assertThat(archivedPub).isEmpty();

        // 5. Published chapter in Archived volume => EXCLUDED
        Optional<PublishedChapterLocatorRecord> pubArchived =
                locatorQueryPort.findPublishedByChapterNumber(CH_PUB_ARCHIVEDVOL_NUM);
        assertThat(pubArchived).isEmpty();
    }

    @Test
    @DisplayName("Publication invariant: findPublishedByTitleKeyword enforces both chapter and volume PUBLISHED")
    void shouldEnforcePublicationInvariantOnFindByTitleKeyword() {
        // Searching "Đại Đạo" - matches 5 chapters by title text in the DB,
        // but ONLY the 1 chapter with both chapter and volume PUBLISHED must be returned.
        List<PublishedChapterLocatorRecord> matches =
                locatorQueryPort.findPublishedByTitleKeyword("dại dạo", "dại dạo", 20);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).id()).isEqualTo(CH_PUB_PUBVOL_ID);
        assertThat(matches.get(0).chapterNumber()).isEqualTo(CH_PUB_PUBVOL_NUM);
        assertThat(matches.get(0).title()).isEqualTo("Đại Đạo Triều Thiên");
    }

    @Test
    @DisplayName("Mixed d/đ support: stored 'Dạ Đạo' matches query 'da dao'")
    void shouldMatchMixedDStrokeStoredTitleWithPlainDQuery() {
        UUID mixedId = UUID.randomUUID();
        seedChapter(mixedId, VOL_PUB_ID, 8_000_050, "Dạ Đạo", "quyen-1-chuong-8000050", "PUBLISHED");

        // Query "da dao" -> exactFolded="da dao", likeFolded="da dao"
        List<PublishedChapterLocatorRecord> matches =
                locatorQueryPort.findPublishedByTitleKeyword("da dao", "da dao", 20);

        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).id()).isEqualTo(mixedId);
        assertThat(matches.get(0).title()).isEqualTo("Dạ Đạo");
        assertThat(matches.get(0).chapterNumber()).isEqualTo(8_000_050);
    }

    @Test
    @DisplayName("Reverse mixed d/đ support: stored 'Đại Danh' matches query 'dai danh' and 'đai danh'")
    void shouldMatchReverseMixedDStoredTitle() {
        UUID id = UUID.randomUUID();
        seedChapter(id, VOL_PUB_ID, 8_000_060, "Đại Danh", "quyen-1-chuong-8000060", "PUBLISHED");

        List<PublishedChapterLocatorRecord> matchesPlain =
                locatorQueryPort.findPublishedByTitleKeyword("dai danh", "dai danh", 20);
        assertThat(matchesPlain).isNotEmpty();
        assertThat(matchesPlain.get(0).id()).isEqualTo(id);

        List<PublishedChapterLocatorRecord> matchesMixedQuery =
                locatorQueryPort.findPublishedByTitleKeyword("dại danh", "dại danh", 20);
        assertThat(matchesMixedQuery).isNotEmpty();
        assertThat(matchesMixedQuery.get(0).id()).isEqualTo(id);
    }

    @Test
    @DisplayName("LIKE Safety: literal '%', '_', and '\\' matched as literal characters")
    void shouldSafelyMatchLiteralSpecialCharacters() {
        UUID percentId = UUID.randomUUID();
        seedChapter(percentId, VOL_PUB_ID, 8_000_071, "100%", "quyen-1-chuong-8000071", "PUBLISHED");

        UUID underscoreId = UUID.randomUUID();
        seedChapter(underscoreId, VOL_PUB_ID, 8_000_072, "Chương_1", "quyen-1-chuong-8000072", "PUBLISHED");

        UUID underscoreOtherId = UUID.randomUUID();
        seedChapter(underscoreOtherId, VOL_PUB_ID, 8_000_073, "ChươngA1", "quyen-1-chuong-8000073", "PUBLISHED");

        UUID backslashId = UUID.randomUUID();
        seedChapter(backslashId, VOL_PUB_ID, 8_000_074, "Tập\\1", "quyen-1-chuong-8000074", "PUBLISHED");

        // 1. Literal % match
        List<PublishedChapterLocatorRecord> percentMatches =
                locatorQueryPort.findPublishedByTitleKeyword("100%", "100\\%", 20);
        assertThat(percentMatches).hasSize(2);
        assertThat(percentMatches.get(0).id()).isEqualTo(percentId);
        assertThat(percentMatches.get(0).title()).isEqualTo("100%");

        // 2. Literal _ match (must match Chương_1 and NOT ChươngA1)
        List<PublishedChapterLocatorRecord> underscoreMatches =
                locatorQueryPort.findPublishedByTitleKeyword("Chương_1", "Chương\\_1", 20);
        assertThat(underscoreMatches).hasSize(1);
        assertThat(underscoreMatches.get(0).id()).isEqualTo(underscoreId);

        // 3. Literal \ match
        List<PublishedChapterLocatorRecord> backslashMatches =
                locatorQueryPort.findPublishedByTitleKeyword("Tập\\1", "Tập\\\\1", 20);
        assertThat(backslashMatches).hasSize(1);
        assertThat(backslashMatches.get(0).id()).isEqualTo(backslashId);
    }

    @Test
    @DisplayName("Crowded ranking regression with literal LIKE metacharacter: Exact '100%' wins over 25 earlier contains matches")
    void shouldRankExactLiteralPercentMatchFirstOverManyContainsMatches() {
        // Seed 25 contains matches with earlier chapter numbers (8_000_101 .. 8_000_125)
        for (int i = 1; i <= 25; i++) {
            UUID cid = UUID.randomUUID();
            int num = 8_000_100 + i;
            seedChapter(cid, VOL_PUB_ID, num, "Hồi " + i + ": Đạt 100% Điểm", "quyen-1-chuong-" + num, "PUBLISHED");
        }

        // Seed 1 true exact match with higher chapter number (8_000_150)
        UUID exactId = UUID.randomUUID();
        seedChapter(exactId, VOL_PUB_ID, 8_000_150, "100%", "quyen-1-chuong-8000150", "PUBLISHED");

        // Query with candidate limit = 20: exactFolded="100%", likeFolded="100\\%"
        List<PublishedChapterLocatorRecord> results =
                locatorQueryPort.findPublishedByTitleKeyword("100%", "100\\%", 20);

        assertThat(results).hasSize(20);

        // 1st result MUST be the exact match (chapter 8_000_150) -> Rank 1
        assertThat(results.get(0).id()).isEqualTo(exactId);
        assertThat(results.get(0).chapterNumber()).isEqualTo(8_000_150);
        assertThat(results.get(0).title()).isEqualTo("100%");

        // Remaining 19 results (indices 1..19) are contains matches ordered by chapter_number ASC
        assertThat(results.get(1).chapterNumber()).isEqualTo(8_000_006);
        assertThat(results.get(2).chapterNumber()).isEqualTo(8_000_101);
        assertThat(results.get(19).chapterNumber()).isEqualTo(8_000_118);
    }

    @Test
    @DisplayName("Bounded candidate ordering: Exact and prefix matches win over many earlier contains matches within candidateLimit")
    void shouldOrderExactAndPrefixBeforeManyContainsMatchesWithinLimit() {
        // Seed 25 contains matches with earlier chapter numbers (8_000_201 .. 8_000_225)
        for (int i = 1; i <= 25; i++) {
            UUID cid = UUID.randomUUID();
            int num = 8_000_200 + i;
            seedChapter(cid, VOL_PUB_ID, num, "Hồi " + i + ": Vấn Kiếm Đạo", "quyen-1-chuong-" + num, "PUBLISHED");
        }

        // Seed 1 exact match with later chapter number (8_000_250)
        UUID exactId = UUID.randomUUID();
        seedChapter(exactId, VOL_PUB_ID, 8_000_250, "Kiếm Đạo", "quyen-1-chuong-8000250", "PUBLISHED");

        // Seed 1 prefix match with later chapter number (8_000_260)
        UUID prefixId = UUID.randomUUID();
        seedChapter(prefixId, VOL_PUB_ID, 8_000_260, "Kiếm Đạo Độc Tôn", "quyen-1-chuong-8000260", "PUBLISHED");

        // Query with candidate limit = 20: exactFolded="Kiếm Đạo", likeFolded="Kiếm Đạo"
        List<PublishedChapterLocatorRecord> results =
                locatorQueryPort.findPublishedByTitleKeyword("Kiếm dạo", "Kiếm dạo", 20);

        assertThat(results).hasSize(20);

        // 1st result MUST be the exact match (chapter 8_000_250) -> Rank 1
        assertThat(results.get(0).id()).isEqualTo(exactId);
        assertThat(results.get(0).chapterNumber()).isEqualTo(8_000_250);
        assertThat(results.get(0).title()).isEqualTo("Kiếm Đạo");

        // 2nd result MUST be the prefix match (chapter 8_000_260) -> Rank 2
        assertThat(results.get(1).id()).isEqualTo(prefixId);
        assertThat(results.get(1).chapterNumber()).isEqualTo(8_000_260);
        assertThat(results.get(1).title()).isEqualTo("Kiếm Đạo Độc Tôn");

        // Remaining 18 results (indices 2..19) are contains matches ordered by chapter_number ASC
        assertThat(results.get(2).chapterNumber()).isEqualTo(8_000_201);
        assertThat(results.get(19).chapterNumber()).isEqualTo(8_000_218);
    }
}
