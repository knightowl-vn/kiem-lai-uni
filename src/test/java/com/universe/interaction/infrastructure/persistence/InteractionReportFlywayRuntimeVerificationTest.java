package com.universe.interaction.infrastructure.persistence;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InteractionReportFlywayRuntimeVerificationTest {

    private static final String DB_NAME = "kiemlai_interaction_reports_test";
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpDatabase() {
        TestDatabaseSupport.resetTestDatabase(DB_NAME);
        dataSource = TestDatabaseSupport.createTestDataSource(DB_NAME);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    private UUID insertParentComment() {
        UUID commentId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'Target comment body', 'ACTIVE', ?, ?, NULL)",
                commentId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );
        return commentId;
    }

    @Test
    @DisplayName("1. Migration V78 execution and table structure verification")
    void shouldVerifyMigrationV78AndTableStructure() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(78);

        MigrationInfo v78Info = null;
        for (MigrationInfo mi : info) {
            if ("78".equals(mi.getVersion().getVersion())) {
                v78Info = mi;
                break;
            }
        }

        assertThat(v78Info).isNotNull();
        assertThat(v78Info.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v78Info.getDescription()).isEqualTo("generalize interaction reports");

        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'interaction_reports'",
                Integer.class,
                DB_NAME
        );
        assertThat(tableCount).isEqualTo(1);

        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'interaction_reports'",
                String.class,
                DB_NAME
        );
        assertThat(columns).containsExactlyInAnyOrder(
                "id",
                "target_type",
                "target_id",
                "reporter_user_id",
                "reason",
                "description",
                "content_snapshot",
                "evidence_media_asset_id",
                "status",
                "moderation_action",
                "created_at",
                "resolved_by_user_id",
                "resolved_at",
                "target_deleted_at",
                "pending_slot"
        );
    }

    @Test
    @DisplayName("2. Basic storage: insert and retrieve PENDING report row")
    void shouldInsertAndRetrievePendingReport() {
        UUID commentId = insertParentComment();
        UUID reportId = UUID.randomUUID();
        UUID reporterUserId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', 'Suspicious link', 'Target comment body snapshot', 'PENDING', ?, NULL, NULL)",
                reportId.toString(), commentId.toString(), reporterUserId.toString(), Timestamp.from(now)
        );

        String status = jdbc.queryForObject(
                "SELECT status FROM interaction_reports WHERE id = ?",
                String.class,
                reportId.toString()
        );
        assertThat(status).isEqualTo("PENDING");

        Integer pendingSlot = jdbc.queryForObject(
                "SELECT pending_slot FROM interaction_reports WHERE id = ?",
                Integer.class,
                reportId.toString()
        );
        assertThat(pendingSlot).isEqualTo(1);
    }

    @Test
    @DisplayName("3. Moderation report decoupled lifecycle: report survives comment hard-delete with snapshot intact")
    void shouldPersistReportAcrossCommentHardDelete() {
        UUID parentCommentId = insertParentComment();
        UUID reportId = UUID.randomUUID();
        UUID reporterUserId = UUID.randomUUID();
        Instant now = Instant.now();
        String snapshotBody = "Target comment body snapshot to preserve for audit";

        // 1. Create valid moderation report for the comment
        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', 'Suspicious link', ?, 'PENDING', ?, NULL, NULL)",
                reportId.toString(), parentCommentId.toString(), reporterUserId.toString(), snapshotBody, Timestamp.from(now)
        );

        // 2. Hard-delete the comment row (succeeds under V73 as fk_interaction_reports_comment was dropped)
        int deletedComments = jdbc.update(
                "DELETE FROM interaction_comments WHERE id = ?",
                parentCommentId.toString()
        );
        assertThat(deletedComments).isEqualTo(1);

        // 3. Verify the report row still exists with its immutable snapshot and identifiers intact
        var reportRow = jdbc.queryForMap(
                "SELECT id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status FROM interaction_reports WHERE id = ?",
                reportId.toString()
        );
        assertThat(reportRow.get("id")).isEqualTo(reportId.toString());
        assertThat(reportRow.get("target_type")).isEqualTo("COMMENT");
        assertThat(reportRow.get("target_id")).isEqualTo(parentCommentId.toString());
        assertThat(reportRow.get("reporter_user_id")).isEqualTo(reporterUserId.toString());
        assertThat(reportRow.get("reason")).isEqualTo("SPAM");
        assertThat(reportRow.get("description")).isEqualTo("Suspicious link");
        assertThat(reportRow.get("content_snapshot")).isEqualTo(snapshotBody);
        assertThat(reportRow.get("status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("4. Pending duplicate uniqueness constraint: 1 pending per reporter per target, allow multiple same-status resolved")
    void shouldEnforcePendingDuplicateUniqueness() {
        UUID commentId = insertParentComment();
        UUID reporterA = UUID.randomUUID();
        UUID reporterB = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Reporter A submits first PENDING report #1 -> succeeds
        UUID reportA1 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'PENDING', ?, NULL, NULL)",
                reportA1.toString(), commentId.toString(), reporterA.toString(), Timestamp.from(now)
        );

        // 2. Reporter A attempts simultaneous duplicate PENDING report for same comment -> fails
        UUID duplicatePendingId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'HARASSMENT', NULL, 'Snapshot', 'PENDING', ?, NULL, NULL)",
                duplicatePendingId.toString(), commentId.toString(), reporterA.toString(), Timestamp.from(now)
        )).hasMessageContaining("uq_interaction_reports_pending_target_reporter");

        // 3. Reporter B submits PENDING report for same comment -> succeeds (different reporter)
        UUID reportB1 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'HATE_SPEECH', NULL, 'Snapshot', 'PENDING', ?, NULL, NULL)",
                reportB1.toString(), commentId.toString(), reporterB.toString(), Timestamp.from(now)
        );

        // 4. Resolve Reporter A's first report #1 to RESOLVED_ACTION_TAKEN -> pending_slot becomes NULL
        UUID resolverId = UUID.randomUUID();
        Instant resolvedAt1 = now.plus(1, ChronoUnit.HOURS);
        jdbc.update(
                "UPDATE interaction_reports SET status = 'RESOLVED_ACTION_TAKEN', moderation_action = 'DELETE_COMMENT', resolved_by_user_id = ?, resolved_at = ? WHERE id = ?",
                resolverId.toString(), Timestamp.from(resolvedAt1), reportA1.toString()
        );

        Integer slotAfterResolve1 = jdbc.queryForObject(
                "SELECT pending_slot FROM interaction_reports WHERE id = ?",
                Integer.class,
                reportA1.toString()
        );
        assertThat(slotAfterResolve1).isNull();

        // 5. Reporter A can now submit PENDING report #2 because prior report is resolved
        UUID reportA2 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'PENDING', ?, NULL, NULL)",
                reportA2.toString(), commentId.toString(), reporterA.toString(), Timestamp.from(now)
        );

        // 6. Resolve Reporter A's report #2 ALSO to RESOLVED_ACTION_TAKEN
        Instant resolvedAt2 = now.plus(2, ChronoUnit.HOURS);
        jdbc.update(
                "UPDATE interaction_reports SET status = 'RESOLVED_ACTION_TAKEN', moderation_action = 'DELETE_COMMENT', resolved_by_user_id = ?, resolved_at = ? WHERE id = ?",
                resolverId.toString(), Timestamp.from(resolvedAt2), reportA2.toString()
        );

        Integer actionTakenCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interaction_reports WHERE target_type = 'COMMENT' AND target_id = ? AND reporter_user_id = ? AND status = 'RESOLVED_ACTION_TAKEN'",
                Integer.class,
                commentId.toString(),
                reporterA.toString()
        );
        assertThat(actionTakenCount).isEqualTo(2);

        // 7. Verify same-status duplication also holds for RESOLVED_NO_ACTION
        UUID reportA3 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'PENDING', ?, NULL, NULL)",
                reportA3.toString(), commentId.toString(), reporterA.toString(), Timestamp.from(now)
        );
        jdbc.update(
                "UPDATE interaction_reports SET status = 'RESOLVED_NO_ACTION', moderation_action = 'NO_ACTION', resolved_by_user_id = ?, resolved_at = ? WHERE id = ?",
                resolverId.toString(), Timestamp.from(resolvedAt2), reportA3.toString()
        );

        UUID reportA4 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'PENDING', ?, NULL, NULL)",
                reportA4.toString(), commentId.toString(), reporterA.toString(), Timestamp.from(now)
        );
        jdbc.update(
                "UPDATE interaction_reports SET status = 'RESOLVED_NO_ACTION', moderation_action = 'NO_ACTION', resolved_by_user_id = ?, resolved_at = ? WHERE id = ?",
                resolverId.toString(), Timestamp.from(resolvedAt2), reportA4.toString()
        );

        Integer noActionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interaction_reports WHERE target_type = 'COMMENT' AND target_id = ? AND reporter_user_id = ? AND status = 'RESOLVED_NO_ACTION'",
                Integer.class,
                commentId.toString(),
                reporterA.toString()
        );
        assertThat(noActionCount).isEqualTo(2);

        Integer totalTerminalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interaction_reports WHERE target_type = 'COMMENT' AND target_id = ? AND reporter_user_id = ?",
                Integer.class,
                commentId.toString(),
                reporterA.toString()
        );
        assertThat(totalTerminalCount).isEqualTo(4);
    }

    @Test
    @DisplayName("5. Reason CHECK constraint: accept standard reasons, reject unsupported")
    void shouldEnforceReasonCheckConstraint() {
        UUID commentId = insertParentComment();
        UUID reporterId = UUID.randomUUID();
        Instant now = Instant.now();

        // Reject invalid reason
        UUID invalidReasonReportId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'OFFENSIVE_CONTENT', NULL, 'Snapshot', 'PENDING', ?, NULL, NULL)",
                invalidReasonReportId.toString(), commentId.toString(), reporterId.toString(), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_reports_reason");
    }

    @Test
    @DisplayName("6. Status CHECK constraint: accept PENDING/RESOLVED_ACTION_TAKEN/RESOLVED_NO_ACTION, reject unsupported")
    void shouldEnforceStatusCheckConstraint() {
        UUID commentId = insertParentComment();
        UUID reporterId = UUID.randomUUID();
        Instant now = Instant.now();

        String checkClause = jdbc.queryForObject(
                "SELECT check_clause FROM information_schema.check_constraints " +
                        "WHERE constraint_schema = ? AND constraint_name = 'chk_interaction_reports_status'",
                String.class,
                DB_NAME
        );
        assertThat(checkClause).isNotNull();
        assertThat(checkClause).contains("PENDING", "RESOLVED_ACTION_TAKEN", "RESOLVED_NO_ACTION");

        UUID invalidStatusReportId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'DISMISSED', ?, NULL, NULL)",
                invalidStatusReportId.toString(), commentId.toString(), reporterId.toString(), Timestamp.from(now)
        )).isInstanceOf(Exception.class)
                .hasMessageMatching("(?s).*chk_interaction_reports_.*");
    }

    @Test
    @DisplayName("7. Snapshot CHECK constraint: require non-blank content_snapshot")
    void shouldEnforceSnapshotCheckConstraint() {
        UUID commentId = insertParentComment();
        UUID reporterId = UUID.randomUUID();
        Instant now = Instant.now();

        UUID invalidSnapshotReportId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, '    ', 'PENDING', ?, NULL, NULL)",
                invalidSnapshotReportId.toString(), commentId.toString(), reporterId.toString(), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_reports_content_snapshot");
    }

    @Test
    @DisplayName("8. OTHER description CHECK constraint: mandatory for OTHER, optional for others")
    void shouldEnforceOtherDescriptionCheckConstraint() {
        UUID commentId = insertParentComment();
        UUID reporter1 = UUID.randomUUID();
        UUID reporter2 = UUID.randomUUID();
        UUID reporter3 = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Reject OTHER with NULL description
        UUID nullDescId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'OTHER', NULL, 'Snapshot', 'PENDING', ?, NULL, NULL)",
                nullDescId.toString(), commentId.toString(), reporter1.toString(), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_reports_other_desc");

        // 2. Reject OTHER with whitespace-only description
        UUID blankDescId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'OTHER', '   ', 'Snapshot', 'PENDING', ?, NULL, NULL)",
                blankDescId.toString(), commentId.toString(), reporter2.toString(), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_reports_other_desc");

        // 3. Accept OTHER with non-blank description
        UUID validOtherId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'OTHER', 'Specific violation details', 'Snapshot', 'PENDING', ?, NULL, NULL)",
                validOtherId.toString(), commentId.toString(), reporter3.toString(), Timestamp.from(now)
        );

        String desc = jdbc.queryForObject(
                "SELECT description FROM interaction_reports WHERE id = ?",
                String.class,
                validOtherId.toString()
        );
        assertThat(desc).isEqualTo("Specific violation details");
    }

    @Test
    @DisplayName("9. Resolution lifecycle CHECK constraint: enforce resolution metadata rules")
    void shouldEnforceResolutionCheckConstraint() {
        UUID commentId = insertParentComment();
        UUID reporterId = UUID.randomUUID();
        UUID resolverId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Reject PENDING with non-null resolved_by_user_id
        UUID pendingWithResolverId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'PENDING', ?, ?, NULL)",
                pendingWithResolverId.toString(), commentId.toString(), reporterId.toString(), Timestamp.from(now), resolverId.toString()
        )).hasMessageContaining("chk_interaction_reports_resolution");

        // 2. Reject PENDING with non-null resolved_at
        UUID pendingWithResolvedAtId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'PENDING', ?, NULL, ?)",
                pendingWithResolvedAtId.toString(), commentId.toString(), reporterId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_reports_resolution");

        // 3. Reject RESOLVED_ACTION_TAKEN with null resolved_by_user_id
        UUID resolvedWithoutResolverId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'RESOLVED_ACTION_TAKEN', 'DELETE_COMMENT', ?, NULL, ?)",
                resolvedWithoutResolverId.toString(), commentId.toString(), reporterId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_reports_resolution");

        // 4. Reject RESOLVED_ACTION_TAKEN with resolved_at < created_at
        UUID resolvedEarlierId = UUID.randomUUID();
        Instant earlier = now.minus(1, ChronoUnit.HOURS);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'RESOLVED_ACTION_TAKEN', 'DELETE_COMMENT', ?, ?, ?)",
                resolvedEarlierId.toString(), commentId.toString(), reporterId.toString(), Timestamp.from(now), resolverId.toString(), Timestamp.from(earlier)
        )).hasMessageContaining("chk_interaction_reports_resolution");

        // 5. Accept valid RESOLVED_NO_ACTION
        UUID validResolvedId = UUID.randomUUID();
        Instant later = now.plus(1, ChronoUnit.HOURS);
        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'RESOLVED_NO_ACTION', 'NO_ACTION', ?, ?, ?)",
                validResolvedId.toString(), commentId.toString(), reporterId.toString(), Timestamp.from(now), resolverId.toString(), Timestamp.from(later)
        );

        String resolvedStatus = jdbc.queryForObject(
                "SELECT status FROM interaction_reports WHERE id = ?",
                String.class,
                validResolvedId.toString()
        );
        assertThat(resolvedStatus).isEqualTo("RESOLVED_NO_ACTION");
    }

    @Test
    @DisplayName("10. Index existence: verify status_created_id and target indexes")
    void shouldVerifyIndexes() {
        List<String> indexes = jdbc.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'interaction_reports'",
                String.class,
                DB_NAME
        );

        assertThat(indexes).contains(
                "PRIMARY",
                "uq_interaction_reports_pending_target_reporter",
                "idx_interaction_reports_status_created_id",
                "idx_interaction_reports_target"
        );
    }

    @Test
    @DisplayName("11. Moderation action CHECK constraint: enforce status-moderation action invariants")
    void shouldEnforceModerationActionCheckConstraint() {
        UUID commentId = insertParentComment();
        UUID reporterId = UUID.randomUUID();
        UUID resolverId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant later = now.plus(1, ChronoUnit.HOURS);

        // 1. Valid: PENDING with NULL moderation_action
        UUID validPendingId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'PENDING', NULL, ?, NULL, NULL)",
                validPendingId.toString(), commentId.toString(), reporterId.toString(), Timestamp.from(now)
        );

        // 2. Valid: RESOLVED_ACTION_TAKEN with DELETE_COMMENT
        UUID validActionTakenId = UUID.randomUUID();
        UUID reporter2 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'RESOLVED_ACTION_TAKEN', 'DELETE_COMMENT', ?, ?, ?)",
                validActionTakenId.toString(), commentId.toString(), reporter2.toString(), Timestamp.from(now), resolverId.toString(), Timestamp.from(later)
        );

        // 3. Valid: RESOLVED_NO_ACTION with NO_ACTION
        UUID validNoActionId = UUID.randomUUID();
        UUID reporter3 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'RESOLVED_NO_ACTION', 'NO_ACTION', ?, ?, ?)",
                validNoActionId.toString(), commentId.toString(), reporter3.toString(), Timestamp.from(now), resolverId.toString(), Timestamp.from(later)
        );

        // 4. Invalid: PENDING with DELETE_COMMENT
        UUID invalidPendingId = UUID.randomUUID();
        UUID reporter4 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'PENDING', 'DELETE_COMMENT', ?, NULL, NULL)",
                invalidPendingId.toString(), commentId.toString(), reporter4.toString(), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_reports_moderation_action");

        // 5. Invalid: RESOLVED_ACTION_TAKEN with NULL moderation_action
        UUID invalidActionTakenNullId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'RESOLVED_ACTION_TAKEN', NULL, ?, ?, ?)",
                invalidActionTakenNullId.toString(), commentId.toString(), reporter4.toString(), Timestamp.from(now), resolverId.toString(), Timestamp.from(later)
        )).hasMessageContaining("chk_interaction_reports_moderation_action");

        // 6. Invalid: RESOLVED_ACTION_TAKEN with NO_ACTION
        UUID invalidActionTakenNoActionId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'RESOLVED_ACTION_TAKEN', 'NO_ACTION', ?, ?, ?)",
                invalidActionTakenNoActionId.toString(), commentId.toString(), reporter4.toString(), Timestamp.from(now), resolverId.toString(), Timestamp.from(later)
        )).hasMessageContaining("chk_interaction_reports_moderation_action");

        // 7. Invalid: RESOLVED_NO_ACTION with NULL moderation_action
        UUID invalidNoActionNullId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'RESOLVED_NO_ACTION', NULL, ?, ?, ?)",
                invalidNoActionNullId.toString(), commentId.toString(), reporter4.toString(), Timestamp.from(now), resolverId.toString(), Timestamp.from(later)
        )).hasMessageContaining("chk_interaction_reports_moderation_action");

        // 8. Invalid: RESOLVED_NO_ACTION with DELETE_COMMENT
        UUID invalidNoActionDeleteId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, 'COMMENT', ?, ?, 'SPAM', NULL, 'Snapshot', 'RESOLVED_NO_ACTION', 'DELETE_COMMENT', ?, ?, ?)",
                invalidNoActionDeleteId.toString(), commentId.toString(), reporter4.toString(), Timestamp.from(now), resolverId.toString(), Timestamp.from(later)
        )).hasMessageContaining("chk_interaction_reports_moderation_action");
    }

    @Test
    @DisplayName("13. Migration V79 verification: evidence_media_asset_id column and persistence semantics")
    void shouldVerifyMigrationV79AndEvidenceMediaAssetIdPersistence() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] info = flyway.info().all();
        MigrationInfo v79Info = null;
        for (MigrationInfo mi : info) {
            if ("79".equals(mi.getVersion().getVersion())) {
                v79Info = mi;
                break;
            }
        }
        assertThat(v79Info).isNotNull();
        assertThat(v79Info.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v79Info.getDescription()).isEqualTo("add report evidence media asset");

        // Verify column definition: CHAR(36) NULL
        Map<String, Object> colMeta = jdbc.queryForMap(
                "SELECT column_name, data_type, character_maximum_length, is_nullable " +
                        "FROM information_schema.columns WHERE table_schema = ? AND table_name = 'interaction_reports' AND column_name = 'evidence_media_asset_id'",
                DB_NAME
        );
        assertThat(colMeta.get("column_name")).isEqualTo("evidence_media_asset_id");
        assertThat(colMeta.get("data_type")).isEqualTo("char");
        assertThat(((Number) colMeta.get("character_maximum_length")).intValue()).isEqualTo(36);
        assertThat(colMeta.get("is_nullable")).isEqualTo("YES");

        // A. Caption-only Community post report -> evidence_media_asset_id is NULL
        UUID captionPostId = UUID.randomUUID();
        UUID reporter1 = UUID.randomUUID();
        UUID reportId1 = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, evidence_media_asset_id, status, created_at) " +
                        "VALUES (?, 'COMMUNITY_POST', ?, ?, 'SPAM', 'Caption only spam', 'Caption snapshot text', NULL, 'PENDING', ?)",
                reportId1.toString(), captionPostId.toString(), reporter1.toString(), Timestamp.from(now)
        );

        Map<String, Object> row1 = jdbc.queryForMap(
                "SELECT target_type, target_id, evidence_media_asset_id, status, pending_slot FROM interaction_reports WHERE id = ?",
                reportId1.toString()
        );
        assertThat(row1.get("target_type")).isEqualTo("COMMUNITY_POST");
        assertThat(row1.get("evidence_media_asset_id")).isNull();
        assertThat(row1.get("pending_slot")).isEqualTo(1);

        // B. Community post report with image -> evidence_media_asset_id stores the post's image UUID
        UUID imagePostId = UUID.randomUUID();
        UUID imageMediaId = UUID.randomUUID();
        UUID reporter2 = UUID.randomUUID();
        UUID reportId2 = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, evidence_media_asset_id, status, created_at) " +
                        "VALUES (?, 'COMMUNITY_POST', ?, ?, 'HARASSMENT', 'Harassing post with image', 'Image post caption', ?, 'PENDING', ?)",
                reportId2.toString(), imagePostId.toString(), reporter2.toString(), imageMediaId.toString(), Timestamp.from(now)
        );

        Map<String, Object> row2 = jdbc.queryForMap(
                "SELECT target_type, target_id, evidence_media_asset_id, status, pending_slot FROM interaction_reports WHERE id = ?",
                reportId2.toString()
        );
        assertThat(row2.get("target_type")).isEqualTo("COMMUNITY_POST");
        assertThat(row2.get("evidence_media_asset_id")).isEqualTo(imageMediaId.toString());
        assertThat(row2.get("pending_slot")).isEqualTo(1);

        // C. Duplicate pending constraint: same user reporting the same COMMUNITY_POST twice while PENDING is rejected
        UUID duplicateReportId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, evidence_media_asset_id, status, created_at) " +
                        "VALUES (?, 'COMMUNITY_POST', ?, ?, 'OTHER', 'Duplicate attempt', 'Caption snapshot text', NULL, 'PENDING', ?)",
                duplicateReportId.toString(), captionPostId.toString(), reporter1.toString(), Timestamp.from(now)
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
