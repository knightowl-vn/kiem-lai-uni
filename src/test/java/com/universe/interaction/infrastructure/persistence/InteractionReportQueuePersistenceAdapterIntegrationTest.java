package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.application.query.InteractionReportQueueFilter;
import com.universe.interaction.application.query.InteractionReportQueueItem;
import com.universe.interaction.application.query.InteractionReportQueuePage;
import com.universe.interaction.application.query.InteractionReportQueueSort;
import com.universe.interaction.application.query.ReportQueueLifecycleScope;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused integration tests for {@link InteractionReportQueuePersistenceAdapter} against a real MySQL database.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        InteractionReportQueuePersistenceAdapter.class
})
class InteractionReportQueuePersistenceAdapterIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private InteractionReportQueuePersistenceAdapter adapter;

    @Autowired
    private SpringDataInteractionReportRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM interaction_reports");
        jdbcTemplate.update("DELETE FROM interaction_comments");
    }

    private UUID insertComment(CommentTargetType targetType, CommentStatus status, String body, UUID authorId, UUID targetId) {
        UUID commentId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Timestamp deletedAt = status == CommentStatus.DELETED ? Timestamp.from(now) : null;
        jdbcTemplate.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?)",
                commentId.toString(),
                targetType.name(),
                targetId.toString(),
                authorId.toString(),
                body,
                status.name(),
                Timestamp.from(now),
                Timestamp.from(now),
                deletedAt
        );
        return commentId;
    }

    private UUID insertComment(CommentTargetType targetType, CommentStatus status, String body) {
        return insertComment(targetType, status, body, UUID.randomUUID(), UUID.randomUUID());
    }

    private void insertReport(
            UUID reportId,
            UUID commentId,
            UUID reporterUserId,
            ReportReason reason,
            String description,
            String reportedBodySnapshot,
            ReportStatus status,
            Instant createdAt,
            UUID resolvedByUserId,
            Instant resolvedAt
    ) {
        insertReport(reportId, commentId, reporterUserId, reason, description, reportedBodySnapshot, status, createdAt, resolvedByUserId, resolvedAt, null);
    }

    private void insertReport(
            UUID reportId,
            UUID commentId,
            UUID reporterUserId,
            ReportReason reason,
            String description,
            String reportedBodySnapshot,
            ReportStatus status,
            Instant createdAt,
            UUID resolvedByUserId,
            Instant resolvedAt,
            ReportModerationAction moderationAction
    ) {
        Timestamp resolvedAtTs = resolvedAt != null ? Timestamp.from(resolvedAt) : null;
        jdbcTemplate.update(
                "INSERT INTO interaction_reports (id, comment_id, reporter_user_id, reason, description, reported_body_snapshot, status, created_at, resolved_by_user_id, resolved_at, moderation_action) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reportId.toString(),
                commentId.toString(),
                reporterUserId.toString(),
                reason.name(),
                description,
                reportedBodySnapshot,
                status.name(),
                Timestamp.from(createdAt),
                resolvedByUserId != null ? resolvedByUserId.toString() : null,
                resolvedAtTs,
                moderationAction != null ? moderationAction.name() : null
        );
    }

    @Test
    @DisplayName("A. NEWEST deterministic ordering: created_at DESC then reportId DESC tie-breaker")
    void shouldOrderNewestByCreatedAtDescThenReportIdDesc() {
        UUID commentId = insertComment(CommentTargetType.NOVEL_CHAPTER, CommentStatus.ACTIVE, "Comment body");
        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        Instant t1 = baseTime.minus(10, ChronoUnit.MINUTES);
        Instant t2 = baseTime.minus(5, ChronoUnit.MINUTES); // identical timestamp for two reports
        Instant t3 = baseTime;

        // Ensure predictable UUID string comparison for tie-breaker
        UUID idLow = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID idHigh = UUID.fromString("99999999-9999-9999-9999-999999999999");
        UUID idOldest = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idNewest = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        insertReport(idOldest, commentId, UUID.randomUUID(), ReportReason.SPAM, null, "Snap 1", ReportStatus.PENDING, t1, null, null);
        insertReport(idLow, commentId, UUID.randomUUID(), ReportReason.HARASSMENT, null, "Snap 2", ReportStatus.PENDING, t2, null, null);
        insertReport(idHigh, commentId, UUID.randomUUID(), ReportReason.HATE_SPEECH, null, "Snap 3", ReportStatus.PENDING, t2, null, null);
        insertReport(idNewest, commentId, UUID.randomUUID(), ReportReason.SPOILER, null, "Snap 4", ReportStatus.PENDING, t3, null, null);

        InteractionReportQueueFilter filter = new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, null, null, InteractionReportQueueSort.NEWEST, 0, 10
        );

        InteractionReportQueuePage page = adapter.findQueueReports(filter);
        List<UUID> reportIds = page.items().stream().map(InteractionReportQueueItem::reportId).toList();

        // Expected NEWEST order: t3 (idNewest), t2 with idHigh, t2 with idLow, t1 (idOldest)
        assertThat(reportIds).containsExactly(idNewest, idHigh, idLow, idOldest);
    }

    @Test
    @DisplayName("B. OLDEST deterministic ordering: created_at ASC then reportId ASC tie-breaker")
    void shouldOrderOldestByCreatedAtAscThenReportIdAsc() {
        UUID commentId = insertComment(CommentTargetType.NOVEL_CHAPTER, CommentStatus.ACTIVE, "Comment body");
        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        Instant t1 = baseTime.minus(10, ChronoUnit.MINUTES);
        Instant t2 = baseTime.minus(5, ChronoUnit.MINUTES); // identical timestamp for two reports
        Instant t3 = baseTime;

        UUID idLow = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID idHigh = UUID.fromString("99999999-9999-9999-9999-999999999999");
        UUID idOldest = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idNewest = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        insertReport(idOldest, commentId, UUID.randomUUID(), ReportReason.SPAM, null, "Snap 1", ReportStatus.PENDING, t1, null, null);
        insertReport(idLow, commentId, UUID.randomUUID(), ReportReason.HARASSMENT, null, "Snap 2", ReportStatus.PENDING, t2, null, null);
        insertReport(idHigh, commentId, UUID.randomUUID(), ReportReason.HATE_SPEECH, null, "Snap 3", ReportStatus.PENDING, t2, null, null);
        insertReport(idNewest, commentId, UUID.randomUUID(), ReportReason.SPOILER, null, "Snap 4", ReportStatus.PENDING, t3, null, null);

        InteractionReportQueueFilter filter = new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, null, null, InteractionReportQueueSort.OLDEST, 0, 10
        );

        InteractionReportQueuePage page = adapter.findQueueReports(filter);
        List<UUID> reportIds = page.items().stream().map(InteractionReportQueueItem::reportId).toList();

        // Expected OLDEST order: t1 (idOldest), t2 with idLow, t2 with idHigh, t3 (idNewest)
        assertThat(reportIds).containsExactly(idOldest, idLow, idHigh, idNewest);
    }

    @Test
    @DisplayName("C. Pagination: verifies page, size, totalElements, totalPages(), hasNext(), hasPrevious() across pages")
    void shouldPaginateCorrectlyAcrossMultiplePages() {
        UUID commentId = insertComment(CommentTargetType.NOVEL_CHAPTER, CommentStatus.ACTIVE, "Comment body");
        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        for (int i = 0; i < 5; i++) {
            insertReport(
                    UUID.randomUUID(),
                    commentId,
                    UUID.randomUUID(),
                    ReportReason.SPAM,
                    null,
                    "Snapshot " + i,
                    ReportStatus.PENDING,
                    baseTime.plus(i, ChronoUnit.MINUTES),
                    null,
                    null
            );
        }

        // Page 0 (size 2)
        InteractionReportQueueFilter filter0 = new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, null, null, InteractionReportQueueSort.NEWEST, 0, 2
        );
        InteractionReportQueuePage page0 = adapter.findQueueReports(filter0);
        assertThat(page0.items()).hasSize(2);
        assertThat(page0.page()).isEqualTo(0);
        assertThat(page0.size()).isEqualTo(2);
        assertThat(page0.totalElements()).isEqualTo(5);
        assertThat(page0.totalPages()).isEqualTo(3);
        assertThat(page0.hasNext()).isTrue();
        assertThat(page0.hasPrevious()).isFalse();

        // Page 1 (size 2)
        InteractionReportQueueFilter filter1 = new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, null, null, InteractionReportQueueSort.NEWEST, 1, 2
        );
        InteractionReportQueuePage page1 = adapter.findQueueReports(filter1);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.size()).isEqualTo(2);
        assertThat(page1.totalElements()).isEqualTo(5);
        assertThat(page1.totalPages()).isEqualTo(3);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.hasPrevious()).isTrue();

        // Page 2 (size 2) - last page with 1 item
        InteractionReportQueueFilter filter2 = new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, null, null, InteractionReportQueueSort.NEWEST, 2, 2
        );
        InteractionReportQueuePage page2 = adapter.findQueueReports(filter2);
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.page()).isEqualTo(2);
        assertThat(page2.size()).isEqualTo(2);
        assertThat(page2.totalElements()).isEqualTo(5);
        assertThat(page2.totalPages()).isEqualTo(3);
        assertThat(page2.hasNext()).isFalse();
        assertThat(page2.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("D. Scope isolation: PENDING scope returns only PENDING, PROCESSED returns ACTION_TAKEN and NO_ACTION")
    void shouldIsolatePendingAndProcessedScopes() {
        UUID commentId = insertComment(CommentTargetType.NOVEL_CHAPTER, CommentStatus.ACTIVE, "Comment body");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        UUID pendingId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID noActionId = UUID.randomUUID();
        UUID resolverId = UUID.randomUUID();

        insertReport(pendingId, commentId, UUID.randomUUID(), ReportReason.SPAM, null, "Snap 1", ReportStatus.PENDING, now, null, null, null);
        insertReport(actionId, commentId, UUID.randomUUID(), ReportReason.HARASSMENT, null, "Snap 2", ReportStatus.RESOLVED_ACTION_TAKEN, now, resolverId, now.plusSeconds(60), ReportModerationAction.DELETE_COMMENT);
        insertReport(noActionId, commentId, UUID.randomUUID(), ReportReason.OTHER, "Explanation", "Snap 3", ReportStatus.RESOLVED_NO_ACTION, now, resolverId, now.plusSeconds(120), ReportModerationAction.NO_ACTION);

        // 1. Filter PENDING
        InteractionReportQueuePage pendingPage = adapter.findQueueReports(new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, null, null, InteractionReportQueueSort.NEWEST, 0, 10
        ));
        assertThat(pendingPage.totalElements()).isEqualTo(1);
        assertThat(pendingPage.items().get(0).reportId()).isEqualTo(pendingId);
        assertThat(pendingPage.items().get(0).status()).isEqualTo(ReportStatus.PENDING);
        assertThat(pendingPage.items().get(0).moderationAction()).isNull();
        assertThat(pendingPage.items().get(0).resolverUserId()).isNull();
        assertThat(pendingPage.items().get(0).resolvedAt()).isNull();

        // 2. Filter PROCESSED
        InteractionReportQueuePage processedPage = adapter.findQueueReports(new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PROCESSED, null, null, InteractionReportQueueSort.OLDEST, 0, 10
        ));
        assertThat(processedPage.totalElements()).isEqualTo(2);
        // Processed is ordered by resolved_at DESC, so noActionId (plusSeconds(120)) comes first, then actionId (plusSeconds(60))
        assertThat(processedPage.items().get(0).reportId()).isEqualTo(noActionId);
        assertThat(processedPage.items().get(0).status()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
        assertThat(processedPage.items().get(0).moderationAction()).isEqualTo(ReportModerationAction.NO_ACTION);
        assertThat(processedPage.items().get(0).resolverUserId()).isEqualTo(resolverId);
        assertThat(processedPage.items().get(0).resolvedAt()).isEqualTo(now.plusSeconds(120));

        assertThat(processedPage.items().get(1).reportId()).isEqualTo(actionId);
        assertThat(processedPage.items().get(1).status()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
        assertThat(processedPage.items().get(1).moderationAction()).isEqualTo(ReportModerationAction.DELETE_COMMENT);
        assertThat(processedPage.items().get(1).resolverUserId()).isEqualTo(resolverId);
        assertThat(processedPage.items().get(1).resolvedAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    @DisplayName("D2. PROCESSED fixed ordering: resolved_at DESC then reportId DESC tie-breaker")
    void shouldOrderProcessedQueueByResolvedAtDescThenReportIdDesc() {
        UUID commentId = insertComment(CommentTargetType.NOVEL_CHAPTER, CommentStatus.ACTIVE, "Comment body");
        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        UUID resolverId = UUID.randomUUID();

        Instant createdTime = baseTime.minus(20, ChronoUnit.MINUTES);
        Instant t1 = baseTime.minus(10, ChronoUnit.MINUTES);
        Instant t2 = baseTime.minus(5, ChronoUnit.MINUTES);
        Instant t3 = baseTime;

        UUID idLow = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID idHigh = UUID.fromString("99999999-9999-9999-9999-999999999999");
        UUID idOldestResolved = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idNewestResolved = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        insertReport(idOldestResolved, commentId, UUID.randomUUID(), ReportReason.SPAM, null, "Snap 1", ReportStatus.RESOLVED_ACTION_TAKEN, createdTime, resolverId, t1, ReportModerationAction.DELETE_COMMENT);
        insertReport(idLow, commentId, UUID.randomUUID(), ReportReason.HARASSMENT, null, "Snap 2", ReportStatus.RESOLVED_NO_ACTION, createdTime, resolverId, t2, ReportModerationAction.NO_ACTION);
        insertReport(idHigh, commentId, UUID.randomUUID(), ReportReason.HATE_SPEECH, null, "Snap 3", ReportStatus.RESOLVED_ACTION_TAKEN, createdTime, resolverId, t2, ReportModerationAction.DELETE_COMMENT);
        insertReport(idNewestResolved, commentId, UUID.randomUUID(), ReportReason.SPOILER, null, "Snap 4", ReportStatus.RESOLVED_NO_ACTION, createdTime, resolverId, t3, ReportModerationAction.NO_ACTION);

        InteractionReportQueueFilter filter = new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PROCESSED, null, null, InteractionReportQueueSort.OLDEST, 0, 10
        );

        InteractionReportQueuePage page = adapter.findQueueReports(filter);
        List<UUID> reportIds = page.items().stream().map(InteractionReportQueueItem::reportId).toList();

        // Expected PROCESSED order: t3 (idNewestResolved), t2 with idHigh, t2 with idLow, t1 (idOldestResolved)
        assertThat(reportIds).containsExactly(idNewestResolved, idHigh, idLow, idOldestResolved);
    }

    @Test
    @DisplayName("D3. DB-level pagination and count isolation: PROCESSED vs PENDING")
    void shouldIsolateProcessedAndPendingPaginationAndCounts() {
        UUID commentId = insertComment(CommentTargetType.NOVEL_CHAPTER, CommentStatus.ACTIVE, "Comment body");
        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant createdTime = baseTime.minus(1, ChronoUnit.HOURS);
        UUID resolverId = UUID.randomUUID();

        // 5 PROCESSED reports
        for (int i = 0; i < 5; i++) {
            insertReport(
                    UUID.randomUUID(),
                    commentId,
                    UUID.randomUUID(),
                    ReportReason.SPAM,
                    null,
                    "Processed Snap " + i,
                    i % 2 == 0 ? ReportStatus.RESOLVED_ACTION_TAKEN : ReportStatus.RESOLVED_NO_ACTION,
                    createdTime.plus(i, ChronoUnit.MINUTES),
                    resolverId,
                    baseTime.plus(i, ChronoUnit.MINUTES),
                    i % 2 == 0 ? ReportModerationAction.DELETE_COMMENT : ReportModerationAction.NO_ACTION
            );
        }

        // 3 PENDING reports
        for (int i = 0; i < 3; i++) {
            insertReport(
                    UUID.randomUUID(),
                    commentId,
                    UUID.randomUUID(),
                    ReportReason.SPAM,
                    null,
                    "Pending Snap " + i,
                    ReportStatus.PENDING,
                    baseTime.plus(i, ChronoUnit.MINUTES),
                    null,
                    null,
                    null
            );
        }

        // Query PROCESSED with page 0, size 2
        InteractionReportQueuePage processedPage = adapter.findQueueReports(new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PROCESSED, null, null, InteractionReportQueueSort.NEWEST, 0, 2
        ));
        assertThat(processedPage.totalElements()).isEqualTo(5);
        assertThat(processedPage.totalPages()).isEqualTo(3);
        assertThat(processedPage.items()).hasSize(2);
        assertThat(processedPage.items()).allSatisfy(item ->
                assertThat(item.status()).isNotEqualTo(ReportStatus.PENDING));

        // Query PENDING with page 0, size 2
        InteractionReportQueuePage pendingPage = adapter.findQueueReports(new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, null, null, InteractionReportQueueSort.NEWEST, 0, 2
        ));
        assertThat(pendingPage.totalElements()).isEqualTo(3);
        assertThat(pendingPage.totalPages()).isEqualTo(2);
        assertThat(pendingPage.items()).hasSize(2);
        assertThat(pendingPage.items()).allSatisfy(item ->
                assertThat(item.status()).isEqualTo(ReportStatus.PENDING));
    }

    @Test
    @DisplayName("E. Reason filter: filters by exact ReportReason")
    void shouldFilterByReportReason() {
        UUID commentId = insertComment(CommentTargetType.NOVEL_CHAPTER, CommentStatus.ACTIVE, "Comment body");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        UUID spamId = UUID.randomUUID();
        UUID harassmentId = UUID.randomUUID();
        UUID spoilerId = UUID.randomUUID();

        insertReport(spamId, commentId, UUID.randomUUID(), ReportReason.SPAM, null, "Snap 1", ReportStatus.PENDING, now, null, null);
        insertReport(harassmentId, commentId, UUID.randomUUID(), ReportReason.HARASSMENT, null, "Snap 2", ReportStatus.PENDING, now, null, null);
        insertReport(spoilerId, commentId, UUID.randomUUID(), ReportReason.SPOILER, null, "Snap 3", ReportStatus.PENDING, now, null, null);

        InteractionReportQueuePage result = adapter.findQueueReports(new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, ReportReason.HARASSMENT, null, InteractionReportQueueSort.NEWEST, 0, 10
        ));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items().get(0).reportId()).isEqualTo(harassmentId);
        assertThat(result.items().get(0).reason()).isEqualTo(ReportReason.HARASSMENT);
    }

    @Test
    @DisplayName("F. TargetType filter: filters by NOVEL_CHAPTER and WIKI_ARTICLE")
    void shouldFilterByCommentTargetType() {
        UUID novelCommentId = insertComment(CommentTargetType.NOVEL_CHAPTER, CommentStatus.ACTIVE, "Novel comment");
        UUID wikiCommentId = insertComment(CommentTargetType.WIKI_ARTICLE, CommentStatus.ACTIVE, "Wiki comment");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        UUID novelReportId = UUID.randomUUID();
        UUID wikiReportId = UUID.randomUUID();

        insertReport(novelReportId, novelCommentId, UUID.randomUUID(), ReportReason.SPAM, null, "Snap Novel", ReportStatus.PENDING, now, null, null);
        insertReport(wikiReportId, wikiCommentId, UUID.randomUUID(), ReportReason.SPAM, null, "Snap Wiki", ReportStatus.PENDING, now, null, null);

        // Filter NOVEL_CHAPTER
        InteractionReportQueuePage novelPage = adapter.findQueueReports(new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, null, CommentTargetType.NOVEL_CHAPTER, InteractionReportQueueSort.NEWEST, 0, 10
        ));
        assertThat(novelPage.totalElements()).isEqualTo(1);
        assertThat(novelPage.items().get(0).reportId()).isEqualTo(novelReportId);
        assertThat(novelPage.items().get(0).targetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);

        // Filter WIKI_ARTICLE
        InteractionReportQueuePage wikiPage = adapter.findQueueReports(new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, null, CommentTargetType.WIKI_ARTICLE, InteractionReportQueueSort.NEWEST, 0, 10
        ));
        assertThat(wikiPage.totalElements()).isEqualTo(1);
        assertThat(wikiPage.items().get(0).reportId()).isEqualTo(wikiReportId);
        assertThat(wikiPage.items().get(0).targetType()).isEqualTo(CommentTargetType.WIKI_ARTICLE);
    }

    @Test
    @DisplayName("G. Combined filters: scope + reason + targetType excludes unrelated rows")
    void shouldApplyCombinedFiltersAccurately() {
        UUID novelCommentId = insertComment(CommentTargetType.NOVEL_CHAPTER, CommentStatus.ACTIVE, "Novel comment");
        UUID wikiCommentId = insertComment(CommentTargetType.WIKI_ARTICLE, CommentStatus.ACTIVE, "Wiki comment");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        UUID matchId = UUID.randomUUID();
        UUID wrongStatusId = UUID.randomUUID();
        UUID wrongReasonId = UUID.randomUUID();
        UUID wrongTargetId = UUID.randomUUID();

        // 1. Matches all: PENDING + SPAM + NOVEL_CHAPTER
        insertReport(matchId, novelCommentId, UUID.randomUUID(), ReportReason.SPAM, null, "Match", ReportStatus.PENDING, now, null, null);
        // 2. Wrong scope: RESOLVED_ACTION_TAKEN + SPAM + NOVEL_CHAPTER
        insertReport(wrongStatusId, novelCommentId, UUID.randomUUID(), ReportReason.SPAM, null, "Wrong Status", ReportStatus.RESOLVED_ACTION_TAKEN, now, UUID.randomUUID(), now.plusSeconds(10), ReportModerationAction.DELETE_COMMENT);
        // 3. Wrong reason: PENDING + HARASSMENT + NOVEL_CHAPTER
        insertReport(wrongReasonId, novelCommentId, UUID.randomUUID(), ReportReason.HARASSMENT, null, "Wrong Reason", ReportStatus.PENDING, now, null, null);
        // 4. Wrong target: PENDING + SPAM + WIKI_ARTICLE
        insertReport(wrongTargetId, wikiCommentId, UUID.randomUUID(), ReportReason.SPAM, null, "Wrong Target", ReportStatus.PENDING, now, null, null);

        InteractionReportQueuePage result = adapter.findQueueReports(new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, ReportReason.SPAM, CommentTargetType.NOVEL_CHAPTER, InteractionReportQueueSort.NEWEST, 0, 10
        ));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items().get(0).reportId()).isEqualTo(matchId);
    }

    @Test
    @DisplayName("H. Projection fidelity: verifies exact mapping of raw queue fields including moderation action and resolver")
    void shouldMapAll12QueueItemFieldsWithHighFidelity() {
        UUID authorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID commentId = insertComment(CommentTargetType.NOVEL_CHAPTER, CommentStatus.ACTIVE, "Live comment body", authorId, targetId);

        UUID reportId = UUID.randomUUID();
        UUID reporterUserId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        insertReport(
                reportId,
                commentId,
                reporterUserId,
                ReportReason.OTHER,
                "Detailed violation explanation",
                "Original snapshot text at report time",
                ReportStatus.PENDING,
                createdAt,
                null,
                null
        );

        InteractionReportQueuePage page = adapter.findQueueReports(new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, null, null, InteractionReportQueueSort.NEWEST, 0, 10
        ));

        assertThat(page.totalElements()).isEqualTo(1);
        InteractionReportQueueItem item = page.items().get(0);

        assertThat(item.reportId()).isEqualTo(reportId);
        assertThat(item.commentId()).isEqualTo(commentId);
        assertThat(item.reporterUserId()).isEqualTo(reporterUserId);
        assertThat(item.reason()).isEqualTo(ReportReason.OTHER);
        assertThat(item.description()).isEqualTo("Detailed violation explanation");
        assertThat(item.reportedBodySnapshot()).isEqualTo("Original snapshot text at report time");
        assertThat(item.status()).isEqualTo(ReportStatus.PENDING);
        assertThat(item.createdAt()).isEqualTo(createdAt);

        assertThat(item.commentAuthorUserId()).isEqualTo(authorId);
        assertThat(item.targetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
        assertThat(item.targetId()).isEqualTo(targetId);
        assertThat(item.commentStatus()).isEqualTo(CommentStatus.ACTIVE);
        assertThat(item.moderationAction()).isNull();
        assertThat(item.resolverUserId()).isNull();
        assertThat(item.resolvedAt()).isNull();
    }

    @Test
    @DisplayName("I. Deleted comment historical evidence: report remains in queue with snapshot intact and commentStatus DELETED")
    void shouldPreserveHistoricalEvidenceWhenCommentIsSoftDeleted() {
        UUID authorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID commentId = insertComment(CommentTargetType.NOVEL_CHAPTER, CommentStatus.ACTIVE, "Abusive comment before delete", authorId, targetId);

        UUID reportId = UUID.randomUUID();
        UUID reporterUserId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        insertReport(
                reportId,
                commentId,
                reporterUserId,
                ReportReason.HARASSMENT,
                "Threatening language",
                "Abusive comment before delete",
                ReportStatus.PENDING,
                createdAt,
                null,
                null
        );

        // Later: comment author or moderator soft-deletes the comment, clearing body and setting status = DELETED
        Instant deletedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Timestamp deletedTimestamp = Timestamp.from(deletedAt);
        jdbcTemplate.update(
                "UPDATE interaction_comments SET status = 'DELETED', body = NULL, updated_at = ?, deleted_at = ? WHERE id = ?",
                deletedTimestamp,
                deletedTimestamp,
                commentId.toString()
        );

        InteractionReportQueuePage page = adapter.findQueueReports(new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PENDING, null, null, InteractionReportQueueSort.NEWEST, 0, 10
        ));

        assertThat(page.totalElements()).isEqualTo(1);
        InteractionReportQueueItem item = page.items().get(0);

        // Historical evidence preserved:
        assertThat(item.reportedBodySnapshot()).isEqualTo("Abusive comment before delete");
        // Current state accurately reflected:
        assertThat(item.commentStatus()).isEqualTo(CommentStatus.DELETED);
    }

    @Test
    @DisplayName("J. Validation: asserts rejection of invalid filter arguments")
    void shouldRejectInvalidFilterArguments() {
        // Null filter
        assertThatThrownBy(() -> adapter.findQueueReports(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("InteractionReportQueueFilter cannot be null");

        // Null scope
        assertThatThrownBy(() -> new InteractionReportQueueFilter(null, null, null, InteractionReportQueueSort.NEWEST, 0, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ReportQueueLifecycleScope cannot be null");

        // Negative page index
        assertThatThrownBy(() -> new InteractionReportQueueFilter(ReportQueueLifecycleScope.PENDING, null, null, InteractionReportQueueSort.NEWEST, -1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page index cannot be negative");

        // Zero or negative size
        assertThatThrownBy(() -> new InteractionReportQueueFilter(ReportQueueLifecycleScope.PENDING, null, null, InteractionReportQueueSort.NEWEST, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be greater than zero");

        assertThatThrownBy(() -> new InteractionReportQueueFilter(ReportQueueLifecycleScope.PENDING, null, null, InteractionReportQueueSort.NEWEST, 0, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be greater than zero");

        // Null sort
        assertThatThrownBy(() -> new InteractionReportQueueFilter(ReportQueueLifecycleScope.PENDING, null, null, null, 0, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("InteractionReportQueueSort cannot be null");

        // Page constructor validation
        assertThatThrownBy(() -> new InteractionReportQueuePage(null, 0, 10, 0))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new InteractionReportQueuePage(List.of(), -1, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new InteractionReportQueuePage(List.of(), 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new InteractionReportQueuePage(List.of(), 0, 10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
