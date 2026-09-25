package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.exceptions.WikiContributionStaleMutationException;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
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
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.out-of-order=true"
})
@Import(WikiContributionPersistenceAdapter.class)
@DisplayName("WikiContribution JPA Persistence Integration Tests")
class WikiContributionJpaPersistenceIntegrationTest {

    private static final UUID NON_EXISTENT_ARTICLE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID NON_EXISTENT_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID SEEDED_ARTICLE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID SEEDED_ADMIN_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        cleanV65BeforeFlyway();
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static void cleanV65BeforeFlyway() {
        try {
            javax.sql.DataSource ds = TestDatabaseSupport.createTestDataSource(TestDatabaseSupport.resolveDatabaseName());
            org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
            jdbc.execute("DELETE FROM flyway_schema_history WHERE version IN ('65', '66', '67', '68')");
            jdbc.execute("DROP TABLE IF EXISTS wiki_contribution_workflow_events");
            jdbc.execute("DROP TABLE IF EXISTS wiki_contribution_sources");
            jdbc.execute("DROP TABLE IF EXISTS wiki_contributions");
            String dbName = TestDatabaseSupport.resolveDatabaseName();
            Integer revIdxExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'wiki_article_revisions' AND index_name = 'idx_wiki_article_revisions_source_contribution'",
                    Integer.class,
                    dbName
            );
            if (revIdxExists != null && revIdxExists > 0) {
                jdbc.execute("ALTER TABLE wiki_article_revisions DROP INDEX idx_wiki_article_revisions_source_contribution");
            }
            Integer revColExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'wiki_article_revisions' AND column_name = 'source_contribution_id'",
                    Integer.class,
                    dbName
            );
            if (revColExists != null && revColExists > 0) {
                jdbc.execute("ALTER TABLE wiki_article_revisions DROP COLUMN source_contribution_id");
            }
        } catch (Exception ignored) {
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WikiContributionPersistenceAdapter persistenceAdapter;

    @BeforeEach
    void setUp() {
        cleanData();
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.update("DELETE FROM wiki_contributions");
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE id = ?", SEEDED_ARTICLE_ID.toString());
    }

    private void seedWikiArticle(UUID articleId, String title, String slug) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                INSERT INTO wiki_articles (
                    id, title, slug, article_type, status, summary, content,
                    created_by, created_at, updated_at, published_by, published_at,
                    aggregate_version, content_version
                ) VALUES (?, ?, ?, 'CHARACTER', 'PUBLISHED', 'Summary', '# Content',
                    ?, ?, ?, ?, ?, 1, 1)
                """,
                articleId.toString(), title, slug,
                SEEDED_ADMIN_ID.toString(), now, now,
                SEEDED_ADMIN_ID.toString(), now
        );
    }

    @Test
    @DisplayName("Lưu và truy vấn thành công đóng góp GENERAL qua persistence adapter")
    void shouldSaveAndFindGeneralContribution() {
        UUID contributionId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiContribution contribution = WikiContribution.createGeneral(
                contributionId,
                NON_EXISTENT_ARTICLE_ID,
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                NON_EXISTENT_USER_ID,
                WikiContributionType.MISSING_INFORMATION,
                "Bài viết còn thiếu thông tin về phi kiếm bản mệnh.",
                now
        );

        WikiContribution saved = persistenceAdapter.save(contribution);
        assertThat(saved.getVersion()).isZero();

        Optional<WikiContribution> retrievedOpt = persistenceAdapter.findById(contributionId);
        assertThat(retrievedOpt).isPresent();

        WikiContribution retrieved = retrievedOpt.get();
        assertThat(retrieved.getId()).isEqualTo(contributionId);
        assertThat(retrieved.getArticleId()).isEqualTo(NON_EXISTENT_ARTICLE_ID);
        assertThat(retrieved.getArticleTypeSnapshot()).isEqualTo("CHARACTER");
        assertThat(retrieved.getArticleTitleSnapshot()).isEqualTo("Trần Bình An");
        assertThat(retrieved.getArticleSlugSnapshot()).isEqualTo("tran-binh-an");
        assertThat(retrieved.getArticleContentVersion()).isEqualTo(1L);
        assertThat(retrieved.getSubmittedByUserId()).isEqualTo(NON_EXISTENT_USER_ID);
        assertThat(retrieved.getContextType()).isEqualTo(WikiContributionContextType.GENERAL);
        assertThat(retrieved.getContributionType()).isEqualTo(WikiContributionType.MISSING_INFORMATION);
        assertThat(retrieved.getMessage()).isEqualTo("Bài viết còn thiếu thông tin về phi kiếm bản mệnh.");
        assertThat(retrieved.getSelectedText()).isNull();
        assertThat(retrieved.getSelectedPrefix()).isNull();
        assertThat(retrieved.getSelectedSuffix()).isNull();
        assertThat(retrieved.getSelectedHeadingAnchor()).isNull();
        assertThat(retrieved.getStatus()).isEqualTo(WikiContributionStatus.NEW);
        assertThat(retrieved.getVersion()).isZero();
    }

    @Test
    @DisplayName("Lưu và truy vấn thành công đóng góp TEXT_SELECTION với đầy đủ trường anchor")
    void shouldSaveAndFindTextSelectionContributionWithAnchors() {
        UUID contributionId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiContribution contribution = WikiContribution.createTextSelection(
                contributionId,
                NON_EXISTENT_ARTICLE_ID,
                "TECHNIQUE",
                "Bát Cực Quyền",
                "bat-cuc-quyen",
                2L,
                NON_EXISTENT_USER_ID,
                WikiContributionType.WORDING,
                "Cần diễn đạt lại đoạn miêu tả quyền ý cho súc tích hơn.",
                "Quyền ý như sấm sét rền vang chân trời",
                "Phía trước:",
                "Phía sau...",
                "quyen-y-section",
                now
        );

        WikiContribution saved = persistenceAdapter.save(contribution);
        assertThat(saved.getVersion()).isZero();

        Optional<WikiContribution> retrievedOpt = persistenceAdapter.findById(contributionId);
        assertThat(retrievedOpt).isPresent();

        WikiContribution retrieved = retrievedOpt.get();
        assertThat(retrieved.getContextType()).isEqualTo(WikiContributionContextType.TEXT_SELECTION);
        assertThat(retrieved.getSelectedText()).isEqualTo("Quyền ý như sấm sét rền vang chân trời");
        assertThat(retrieved.getSelectedPrefix()).isEqualTo("Phía trước:");
        assertThat(retrieved.getSelectedSuffix()).isEqualTo("Phía sau...");
        assertThat(retrieved.getSelectedHeadingAnchor()).isEqualTo("quyen-y-section");
        assertThat(retrieved.getStatus()).isEqualTo(WikiContributionStatus.NEW);
        assertThat(retrieved.getVersion()).isZero();
    }

    @Test
    @DisplayName("Optimistic locking: version tự động tăng từ 0 lên 1 khi cập nhật bản ghi đã tồn tại")
    void shouldIncrementVersionOnSubsequentSave() {
        UUID contributionId = UUID.randomUUID();
        Instant created = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiContribution initial = WikiContribution.createGeneral(
                contributionId,
                NON_EXISTENT_ARTICLE_ID,
                "LOCATION",
                "Lạc Phách Sơn",
                "lac-phach-son",
                1L,
                NON_EXISTENT_USER_ID,
                WikiContributionType.OUTDATED_INFORMATION,
                "Thông tin tông môn cần cập nhật sang giai đoạn mới.",
                created
        );

        WikiContribution savedFirst = persistenceAdapter.save(initial);
        assertThat(savedFirst.getVersion()).isZero();

        Instant updated = created.plus(1, ChronoUnit.HOURS);
        WikiContribution modified = WikiContribution.reconstitute(
                contributionId,
                NON_EXISTENT_ARTICLE_ID,
                "LOCATION",
                "Lạc Phách Sơn",
                "lac-phach-son",
                1L,
                NON_EXISTENT_USER_ID,
                WikiContributionContextType.GENERAL,
                WikiContributionType.OUTDATED_INFORMATION,
                "Thông tin tông môn đã được thẩm tra và cập nhật thêm tài liệu.",
                null,
                null,
                null,
                null,
                WikiContributionStatus.REVIEWING,
                savedFirst.getVersion(),
                created,
                updated
        );

        WikiContribution savedSecond = persistenceAdapter.save(modified);
        assertThat(savedSecond.getVersion()).isEqualTo(1L);
        assertThat(savedSecond.getStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(savedSecond.getMessage()).isEqualTo("Thông tin tông môn đã được thẩm tra và cập nhật thêm tài liệu.");

        Long dbVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM wiki_contributions WHERE id = ?",
                Long.class,
                contributionId.toString()
        );
        assertThat(dbVersion).isEqualTo(1L);
    }

    @Test
    @DisplayName("Chống ghi đè dữ liệu cũ (Stale Write): Bác bỏ lưu đối tượng domain mang version cũ hơn DB, bảo toàn thay đổi trước đó")
    void shouldRejectStaleWriteWhenDomainVersionIsOutdated() {
        UUID contributionId = UUID.randomUUID();
        Instant created = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiContribution initial = WikiContribution.createGeneral(
                contributionId,
                NON_EXISTENT_ARTICLE_ID,
                "LOCATION",
                "Lạc Phách Sơn",
                "lac-phach-son",
                1L,
                NON_EXISTENT_USER_ID,
                WikiContributionType.MISSING_INFORMATION,
                "Bản ghi ban đầu của đóng góp ở phiên bản 0.",
                created
        );
        persistenceAdapter.save(initial);

        // 2. Load 2 bản sao độc lập của domain tại cùng thời điểm (đều mang version 0)
        WikiContribution copyA = persistenceAdapter.findById(contributionId).orElseThrow();
        WikiContribution copyB = persistenceAdapter.findById(contributionId).orElseThrow();
        assertThat(copyA.getVersion()).isEqualTo(0L);
        assertThat(copyB.getVersion()).isEqualTo(0L);

        // 3. User A cập nhật và lưu trước
        WikiContribution modifiedA = WikiContribution.reconstitute(
                copyA.getId(),
                copyA.getArticleId(),
                copyA.getArticleTypeSnapshot(),
                copyA.getArticleTitleSnapshot(),
                copyA.getArticleSlugSnapshot(),
                copyA.getArticleContentVersion(),
                copyA.getSubmittedByUserId(),
                copyA.getContextType(),
                copyA.getContributionType(),
                "Nội dung đã được User A chỉnh sửa thành công.",
                copyA.getSelectedText(),
                copyA.getSelectedPrefix(),
                copyA.getSelectedSuffix(),
                copyA.getSelectedHeadingAnchor(),
                WikiContributionStatus.REVIEWING,
                copyA.getVersion(), // version 0
                copyA.getCreatedAt(),
                created.plus(10, ChronoUnit.MINUTES)
        );
        WikiContribution savedA = persistenceAdapter.save(modifiedA);
        assertThat(savedA.getVersion()).isEqualTo(1L);

        // 4. User B cố gắng lưu bản sao stale mang version 0
        WikiContribution modifiedB = WikiContribution.reconstitute(
                copyB.getId(),
                copyB.getArticleId(),
                copyB.getArticleTypeSnapshot(),
                copyB.getArticleTitleSnapshot(),
                copyB.getArticleSlugSnapshot(),
                copyB.getArticleContentVersion(),
                copyB.getSubmittedByUserId(),
                copyB.getContextType(),
                copyB.getContributionType(),
                "Nội dung do User B cố gắng ghi đè từ bản cũ.",
                copyB.getSelectedText(),
                copyB.getSelectedPrefix(),
                copyB.getSelectedSuffix(),
                copyB.getSelectedHeadingAnchor(),
                WikiContributionStatus.REJECTED,
                copyB.getVersion(), // stale version 0
                copyB.getCreatedAt(),
                created.plus(20, ChronoUnit.MINUTES),
                "Từ chối đóng góp",
                SEEDED_ADMIN_ID,
                created.plus(20, ChronoUnit.MINUTES),
                null
        );

        // 5. Khẳng định thao tác lưu của B bị từ chối với WikiContributionStaleMutationException
        assertThatThrownBy(() -> persistenceAdapter.save(modifiedB))
                .isInstanceOf(WikiContributionStaleMutationException.class)
                .hasMessageContaining("đồng thời");

        // 6. Tải lại từ database và kiểm tra trạng thái của A được bảo toàn nguyên vẹn, B không ghi đè
        WikiContribution reloaded = persistenceAdapter.findById(contributionId).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getMessage()).isEqualTo("Nội dung đã được User A chỉnh sửa thành công.");
        assertThat(reloaded.getStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
    }

    @Test
    @DisplayName("DB-Level Concurrency Guard: Bác bỏ ghi đè khi version trong DB đã tiến trước")
    void shouldTriggerOptimisticLockExceptionWhenDatabaseRowAdvancesConcurrently() {
        UUID contributionId = UUID.randomUUID();
        Instant created = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiContribution initial = WikiContribution.createGeneral(
                contributionId,
                NON_EXISTENT_ARTICLE_ID,
                "LOCATION",
                "Lạc Phách Sơn",
                "lac-phach-son",
                1L,
                NON_EXISTENT_USER_ID,
                WikiContributionType.MISSING_INFORMATION,
                "Bản ghi ban đầu phục vụ kiểm thử race condition.",
                created
        );
        persistenceAdapter.save(initial);

        // Giả lập giao dịch khác can thiệp trực tiếp tăng version trong DB từ 0 lên 1
        jdbcTemplate.update("UPDATE wiki_contributions SET version = 1 WHERE id = ?", contributionId.toString());

        WikiContribution staleDomain = WikiContribution.reconstitute(
                contributionId,
                NON_EXISTENT_ARTICLE_ID,
                "LOCATION",
                "Lạc Phách Sơn",
                "lac-phach-son",
                1L,
                NON_EXISTENT_USER_ID,
                WikiContributionContextType.GENERAL,
                WikiContributionType.MISSING_INFORMATION,
                "Thử ghi đè khi DB đã tăng version.",
                null, null, null, null,
                WikiContributionStatus.REVIEWING,
                0L, // stale version
                created, created
        );

        assertThatThrownBy(() -> persistenceAdapter.save(staleDomain))
                .isInstanceOf(WikiContributionStaleMutationException.class);
    }

    @Test
    @DisplayName("Cô lập khóa ngoại: Lưu thành công đóng góp với articleId và userId hoàn toàn không tồn tại trong DB")
    void shouldAllowContributionWithoutForeignKeyToWikiArticlesOrIdentityUsers() {
        UUID contributionId = UUID.randomUUID();
        WikiContribution contribution = WikiContribution.createGeneral(
                contributionId,
                UUID.randomUUID(), // Không tồn tại trong wiki_articles
                "ITEM",
                "Dưỡng Kiếm Hồ",
                "duong-kiem-ho",
                1L,
                UUID.randomUUID(), // Không tồn tại trong identity_users
                WikiContributionType.OTHER,
                "Đóng góp cho vật phẩm độc lập mà không bị ràng buộc FK.",
                Instant.now()
        );

        WikiContribution saved = persistenceAdapter.save(contribution);
        assertThat(saved).isNotNull();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_contributions WHERE id = ?",
                Integer.class,
                contributionId.toString()
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Lịch sử tồn tại độc lập: Khi bài viết cha bị xóa cứng, bản ghi đóng góp vẫn tồn tại an toàn")
    void shouldSurviveHardDeletionOfWikiArticle() {
        seedWikiArticle(SEEDED_ARTICLE_ID, "Hạo Nhiên Thiên Hạ", "hao-nhien-thien-ha");

        UUID contributionId = UUID.randomUUID();
        WikiContribution contribution = WikiContribution.createGeneral(
                contributionId,
                SEEDED_ARTICLE_ID,
                "LOCATION",
                "Hạo Nhiên Thiên Hạ",
                "hao-nhien-thien-ha",
                1L,
                NON_EXISTENT_USER_ID,
                WikiContributionType.INCORRECT_INFORMATION,
                "Địa lý thiên hạ cần điều chỉnh lại ranh giới mười ba châu.",
                Instant.now()
        );
        persistenceAdapter.save(contribution);

        // Xóa cứng bài viết khỏi wiki_articles
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE id = ?", SEEDED_ARTICLE_ID.toString());

        // Kiểm tra đóng góp vẫn tồn tại nguyên vẹn
        Optional<WikiContribution> surviving = persistenceAdapter.findById(contributionId);
        assertThat(surviving).isPresent();
        assertThat(surviving.get().getId()).isEqualTo(contributionId);
        assertThat(surviving.get().getArticleTitleSnapshot()).isEqualTo("Hạo Nhiên Thiên Hạ");
    }

    @Test
    @DisplayName("Ràng buộc CHECK ở cấp độ MySQL Database: ném ngoại lệ khi vi phạm context_type, contribution_type hoặc status")
    void shouldEnforceDatabaseCheckConstraints() {
        Timestamp now = Timestamp.from(Instant.now());

        // Vi phạm chk_wiki_contributions_context_type
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wiki_contributions (
                    id, article_id, article_type_snapshot, article_title_snapshot, article_slug_snapshot,
                    article_content_version, submitted_by_user_id, context_type, contribution_type,
                    message, status, version, created_at, updated_at
                ) VALUES (?, ?, 'ITEM', 'Title', 'slug', 1, ?, 'INVALID_CONTEXT', 'OTHER',
                    'Đoạn văn này có độ dài hợp lệ hơn 20 ký tự.', 'NEW', 0, ?, ?)
                """,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), now, now
        )).hasMessageContaining("chk_wiki_contributions_context_type");

        // Vi phạm chk_wiki_contributions_type
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wiki_contributions (
                    id, article_id, article_type_snapshot, article_title_snapshot, article_slug_snapshot,
                    article_content_version, submitted_by_user_id, context_type, contribution_type,
                    message, status, version, created_at, updated_at
                ) VALUES (?, ?, 'ITEM', 'Title', 'slug', 1, ?, 'GENERAL', 'UNSUPPORTED_TYPE',
                    'Đoạn văn này có độ dài hợp lệ hơn 20 ký tự.', 'NEW', 0, ?, ?)
                """,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), now, now
        )).hasMessageContaining("chk_wiki_contributions_type");

        // Vi phạm chk_wiki_contributions_status
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wiki_contributions (
                    id, article_id, article_type_snapshot, article_title_snapshot, article_slug_snapshot,
                    article_content_version, submitted_by_user_id, context_type, contribution_type,
                    message, status, version, created_at, updated_at
                ) VALUES (?, ?, 'ITEM', 'Title', 'slug', 1, ?, 'GENERAL', 'OTHER',
                    'Đoạn văn này có độ dài hợp lệ hơn 20 ký tự.', 'INVALID_STATUS', 0, ?, ?)
                """,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), now, now
        )).hasMessageContaining("chk_wiki_contributions_status");
    }
}
