package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real MySQL integration tests for {@link InteractionReportPersistenceAdapter#purgeExpiredResolvedBefore(Instant, int)}
 * and Flyway V55 index verification.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        InteractionReportPersistenceAdapter.class,
        InteractionReportPersistenceMapper.class
})
class InteractionReportRetentionPersistenceTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private InteractionReportPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM interaction_reports");
        jdbcTemplate.update("DELETE FROM interaction_comments");
    }

    private UUID insertComment(CommentStatus status, String body) {
        UUID commentId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Timestamp deletedAt = status == CommentStatus.DELETED ? Timestamp.from(now) : null;
        String actualBody = status == CommentStatus.DELETED ? null : body;
        jdbcTemplate.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?)",
                commentId.toString(),
                CommentTargetType.NOVEL_CHAPTER.name(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                actualBody,
                status.name(),
                Timestamp.from(now),
                Timestamp.from(now),
                deletedAt
        );
        return commentId;
    }


    private void insertReport(
            UUID reportId,
            UUID commentId,
            ReportStatus status,
            Instant createdAt,
            Instant resolvedAt,
            ReportModerationAction moderationAction
    ) {
        Timestamp resolvedAtTs = resolvedAt != null ? Timestamp.from(resolvedAt) : null;
        jdbcTemplate.update(
                "INSERT INTO interaction_reports (id, comment_id, reporter_user_id, reason, description, reported_body_snapshot, status, created_at, resolved_by_user_id, resolved_at, moderation_action) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reportId.toString(),
                commentId.toString(),
                UUID.randomUUID().toString(),
                ReportReason.SPAM.name(),
                "Report description",
                "Comment body snapshot",
                status.name(),
                Timestamp.from(createdAt),
                resolvedAt != null ? UUID.randomUUID().toString() : null,
                resolvedAtTs,
                moderationAction != null ? moderationAction.name() : null
        );
    }

    @Test
    @DisplayName("1. PENDING older than 30 days is never deleted")
    void shouldNeverDeletePendingReportsEvenIfOld() {
        UUID commentId = insertComment(CommentStatus.ACTIVE, "Pending comment");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(30, ChronoUnit.DAYS);

        UUID pendingReportId = UUID.randomUUID();
        insertReport(pendingReportId, commentId, ReportStatus.PENDING, now.minus(45, ChronoUnit.DAYS), null, null);

        int deleted = adapter.purgeExpiredResolvedBefore(cutoff, 50);

        assertThat(deleted).isZero();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reports WHERE id = ?",
                Integer.class,
                pendingReportId.toString()
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("2. Terminal resolved 29 days ago is retained")
    void shouldRetainTerminalReportResolved29DaysAgo() {
        UUID commentId = insertComment(CommentStatus.ACTIVE, "Recent resolved comment");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(30, ChronoUnit.DAYS);

        UUID recentReportId = UUID.randomUUID();
        Instant resolvedAt = now.minus(29, ChronoUnit.DAYS);
        insertReport(recentReportId, commentId, ReportStatus.RESOLVED_ACTION_TAKEN, resolvedAt.minus(1, ChronoUnit.HOURS), resolvedAt, ReportModerationAction.DELETE_COMMENT);

        int deleted = adapter.purgeExpiredResolvedBefore(cutoff, 50);

        assertThat(deleted).isZero();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reports WHERE id = ?",
                Integer.class,
                recentReportId.toString()
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("3 & 4. Boundary test: resolved_at == cutoff is retained, resolved_at < cutoff is deleted")
    void shouldRetainExactCutoffAndPurgeStrictlyBeforeCutoff() {
        UUID commentId = insertComment(CommentStatus.ACTIVE, "Boundary comment");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(30, ChronoUnit.DAYS);

        UUID exactCutoffId = UUID.randomUUID();
        insertReport(exactCutoffId, commentId, ReportStatus.RESOLVED_ACTION_TAKEN, cutoff.minus(1, ChronoUnit.HOURS), cutoff, ReportModerationAction.DELETE_COMMENT);

        UUID beforeCutoffId = UUID.randomUUID();
        Instant justBeforeCutoff = cutoff.minus(1, ChronoUnit.MILLIS);
        insertReport(beforeCutoffId, commentId, ReportStatus.RESOLVED_ACTION_TAKEN, justBeforeCutoff.minus(1, ChronoUnit.HOURS), justBeforeCutoff, ReportModerationAction.DELETE_COMMENT);

        int deleted = adapter.purgeExpiredResolvedBefore(cutoff, 50);

        assertThat(deleted).isEqualTo(1);

        // Exact cutoff retained
        Integer exactCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reports WHERE id = ?",
                Integer.class,
                exactCutoffId.toString()
        );
        assertThat(exactCount).isEqualTo(1);

        // Before cutoff deleted
        Integer beforeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reports WHERE id = ?",
                Integer.class,
                beforeCutoffId.toString()
        );
        assertThat(beforeCount).isZero();
    }

    @Test
    @DisplayName("5. Both terminal statuses (RESOLVED_ACTION_TAKEN and RESOLVED_NO_ACTION) are eligible when expired")
    void shouldPurgeBothActionTakenAndNoActionWhenExpired() {
        UUID commentId = insertComment(CommentStatus.ACTIVE, "Dual terminal comment");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(30, ChronoUnit.DAYS);

        UUID actionTakenId = UUID.randomUUID();
        Instant resolved1 = cutoff.minus(2, ChronoUnit.DAYS);
        insertReport(actionTakenId, commentId, ReportStatus.RESOLVED_ACTION_TAKEN, resolved1.minus(1, ChronoUnit.HOURS), resolved1, ReportModerationAction.DELETE_COMMENT);

        UUID noActionId = UUID.randomUUID();
        Instant resolved2 = cutoff.minus(3, ChronoUnit.DAYS);
        insertReport(noActionId, commentId, ReportStatus.RESOLVED_NO_ACTION, resolved2.minus(1, ChronoUnit.HOURS), resolved2, ReportModerationAction.NO_ACTION);

        int deleted = adapter.purgeExpiredResolvedBefore(cutoff, 50);

        assertThat(deleted).isEqualTo(2);

        Integer remaining = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_reports", Integer.class);
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("6. Hard batch bound: seed 10 eligible reports with limit=4 deletes exactly 4 and leaves 6")
    void shouldEnforceHardBatchLimit() {
        UUID commentId = insertComment(CommentStatus.ACTIVE, "Batch limit comment");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(30, ChronoUnit.DAYS);

        for (int i = 0; i < 10; i++) {
            UUID id = UUID.randomUUID();
            Instant resolved = cutoff.minus(i + 1, ChronoUnit.DAYS);
            insertReport(id, commentId, ReportStatus.RESOLVED_NO_ACTION, resolved.minus(1, ChronoUnit.HOURS), resolved, ReportModerationAction.NO_ACTION);
        }

        int deleted = adapter.purgeExpiredResolvedBefore(cutoff, 4);

        assertThat(deleted).isEqualTo(4);

        Integer remaining = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_reports", Integer.class);
        assertThat(remaining).isEqualTo(6);
    }

    @Test
    @DisplayName("7. Oldest-first: rows resolved 40, 35, 32 days ago with limit=2 deletes 40-day and 35-day rows")
    void shouldDeleteOldestFirstDeterministically() {
        UUID commentId = insertComment(CommentStatus.ACTIVE, "Oldest first comment");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(30, ChronoUnit.DAYS);

        UUID id40 = UUID.randomUUID();
        Instant resolved40 = now.minus(40, ChronoUnit.DAYS);
        insertReport(id40, commentId, ReportStatus.RESOLVED_ACTION_TAKEN, resolved40.minus(1, ChronoUnit.HOURS), resolved40, ReportModerationAction.DELETE_COMMENT);

        UUID id35 = UUID.randomUUID();
        Instant resolved35 = now.minus(35, ChronoUnit.DAYS);
        insertReport(id35, commentId, ReportStatus.RESOLVED_NO_ACTION, resolved35.minus(1, ChronoUnit.HOURS), resolved35, ReportModerationAction.NO_ACTION);

        UUID id32 = UUID.randomUUID();
        Instant resolved32 = now.minus(32, ChronoUnit.DAYS);
        insertReport(id32, commentId, ReportStatus.RESOLVED_ACTION_TAKEN, resolved32.minus(1, ChronoUnit.HOURS), resolved32, ReportModerationAction.DELETE_COMMENT);

        int deleted = adapter.purgeExpiredResolvedBefore(cutoff, 2);

        assertThat(deleted).isEqualTo(2);

        // 40 and 35 deleted
        Integer count40 = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_reports WHERE id = ?", Integer.class, id40.toString());
        Integer count35 = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_reports WHERE id = ?", Integer.class, id35.toString());
        Integer count32 = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_reports WHERE id = ?", Integer.class, id32.toString());

        assertThat(count40).isZero();
        assertThat(count35).isZero();
        assertThat(count32).isEqualTo(1);
    }

    @Test
    @DisplayName("8. ID tie-breaker: same resolved_at, lower lexical ID is deleted first with limit=1")
    void shouldBreakTieWithIdAscending() {
        UUID commentId = insertComment(CommentStatus.ACTIVE, "Tie breaker comment");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(30, ChronoUnit.DAYS);
        Instant sameResolvedAt = cutoff.minus(5, ChronoUnit.DAYS);

        // Construct two known UUIDs where idLower < idHigher lexically
        UUID idLower = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idHigher = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

        insertReport(idHigher, commentId, ReportStatus.RESOLVED_NO_ACTION, sameResolvedAt.minus(1, ChronoUnit.HOURS), sameResolvedAt, ReportModerationAction.NO_ACTION);
        insertReport(idLower, commentId, ReportStatus.RESOLVED_NO_ACTION, sameResolvedAt.minus(1, ChronoUnit.HOURS), sameResolvedAt, ReportModerationAction.NO_ACTION);

        int deleted = adapter.purgeExpiredResolvedBefore(cutoff, 1);

        assertThat(deleted).isEqualTo(1);

        Integer countLower = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_reports WHERE id = ?", Integer.class, idLower.toString());
        Integer countHigher = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_reports WHERE id = ?", Integer.class, idHigher.toString());

        assertThat(countLower).isZero();
        assertThat(countHigher).isEqualTo(1);
    }

    @Test
    @DisplayName("9. Repeated cleanup: second execution after all deleted returns 0 without error")
    void shouldReturnZeroHarmlesslyOnRepeatedRun() {
        UUID commentId = insertComment(CommentStatus.ACTIVE, "Repeated run comment");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(30, ChronoUnit.DAYS);

        UUID id = UUID.randomUUID();
        Instant resolved = cutoff.minus(5, ChronoUnit.DAYS);
        insertReport(id, commentId, ReportStatus.RESOLVED_NO_ACTION, resolved.minus(1, ChronoUnit.HOURS), resolved, ReportModerationAction.NO_ACTION);

        int firstRun = adapter.purgeExpiredResolvedBefore(cutoff, 50);
        assertThat(firstRun).isEqualTo(1);

        int secondRun = adapter.purgeExpiredResolvedBefore(cutoff, 50);
        assertThat(secondRun).isZero();
    }

    @Test
    @DisplayName("10 & 11. Comment safety & scope safety: comments and non-expired reports remain untouched")
    void shouldKeepCommentsAndNonExpiredReportsIntact() {
        UUID commentId1 = insertComment(CommentStatus.ACTIVE, "Comment 1 body");
        UUID commentId2 = insertComment(CommentStatus.DELETED, "Comment 2 body");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(30, ChronoUnit.DAYS);

        // Expired report referencing comment 1
        UUID expiredReportId = UUID.randomUUID();
        Instant resolvedExpired = cutoff.minus(10, ChronoUnit.DAYS);
        insertReport(expiredReportId, commentId1, ReportStatus.RESOLVED_ACTION_TAKEN, resolvedExpired.minus(1, ChronoUnit.HOURS), resolvedExpired, ReportModerationAction.DELETE_COMMENT);

        // Unexpired report referencing comment 2
        UUID unexpiredReportId = UUID.randomUUID();
        Instant resolvedRecent = now.minus(5, ChronoUnit.DAYS);
        insertReport(unexpiredReportId, commentId2, ReportStatus.RESOLVED_NO_ACTION, resolvedRecent.minus(1, ChronoUnit.HOURS), resolvedRecent, ReportModerationAction.NO_ACTION);

        // Pending report referencing comment 1
        UUID pendingReportId = UUID.randomUUID();
        insertReport(pendingReportId, commentId1, ReportStatus.PENDING, now.minus(40, ChronoUnit.DAYS), null, null);

        int deleted = adapter.purgeExpiredResolvedBefore(cutoff, 50);

        assertThat(deleted).isEqualTo(1);

        // Comment 1 still exists with status ACTIVE
        String comment1Status = jdbcTemplate.queryForObject("SELECT status FROM interaction_comments WHERE id = ?", String.class, commentId1.toString());
        assertThat(comment1Status).isEqualTo(CommentStatus.ACTIVE.name());

        // Comment 2 still exists with status DELETED
        String comment2Status = jdbcTemplate.queryForObject("SELECT status FROM interaction_comments WHERE id = ?", String.class, commentId2.toString());
        assertThat(comment2Status).isEqualTo(CommentStatus.DELETED.name());

        // Unexpired report remains
        Integer unexpiredCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_reports WHERE id = ?", Integer.class, unexpiredReportId.toString());
        assertThat(unexpiredCount).isEqualTo(1);

        // Pending report remains
        Integer pendingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_reports WHERE id = ?", Integer.class, pendingReportId.toString());
        assertThat(pendingCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Input validation: null cutoff or non-positive limit rejected")
    void shouldRejectInvalidArguments() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> adapter.purgeExpiredResolvedBefore(null, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cutoff cannot be null");

        assertThatThrownBy(() -> adapter.purgeExpiredResolvedBefore(now, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Limit must be greater than 0");

        assertThatThrownBy(() -> adapter.purgeExpiredResolvedBefore(now, -10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Limit must be greater than 0");
    }

    @Test
    @DisplayName("16. Schema verification: Flyway V55 created idx_interaction_reports_resolved_at_id (resolved_at, id)")
    void shouldVerifyV55IndexExistsWithCorrectColumns() {
        List<Map<String, Object>> indexColumns = jdbcTemplate.queryForList("""
                SELECT COLUMN_NAME, SEQ_IN_INDEX
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'interaction_reports'
                  AND INDEX_NAME = 'idx_interaction_reports_resolved_at_id'
                ORDER BY SEQ_IN_INDEX ASC
                """);

        assertThat(indexColumns).hasSize(2);
        assertThat(indexColumns.get(0).get("COLUMN_NAME")).isEqualTo("resolved_at");
        assertThat(indexColumns.get(0).get("SEQ_IN_INDEX")).isEqualTo(1L);
        assertThat(indexColumns.get(1).get("COLUMN_NAME")).isEqualTo("id");
        assertThat(indexColumns.get(1).get("SEQ_IN_INDEX")).isEqualTo(2L);
    }
}
