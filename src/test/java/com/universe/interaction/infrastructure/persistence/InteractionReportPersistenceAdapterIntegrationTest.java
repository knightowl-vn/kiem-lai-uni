package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.domain.report.InteractionReport;
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

/**
 * Focused integration tests for {@link InteractionReportPersistenceAdapter} against a real MySQL database.
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
class InteractionReportPersistenceAdapterIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private InteractionReportPersistenceAdapter adapter;

    @Autowired
    private InteractionReportPersistenceMapper mapper;

    @Autowired
    private SpringDataInteractionReportRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM interaction_reports");
        jdbcTemplate.update("DELETE FROM interaction_comments");
    }

    private UUID insertComment() {
        UUID commentId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'Target comment body', 'ACTIVE', ?, ?, NULL)",
                commentId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );
        return commentId;
    }

    @Test
    @DisplayName("save(PENDING) persists successfully and findById returns full aggregate roundtrip")
    void shouldPersistPendingReportAndFindByIdFullRoundtrip() {
        UUID reportId = UUID.randomUUID();
        UUID commentId = insertComment();
        UUID reporterUserId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        InteractionReport pendingReport = InteractionReport.createPending(
                reportId,
                commentId,
                reporterUserId,
                ReportReason.SPAM,
                "Commercial promotional link",
                "Spam text snapshot",
                createdAt
        );

        InteractionReport saved = adapter.save(pendingReport);
        assertThat(saved.getId()).isEqualTo(reportId);
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.PENDING);

        Optional<InteractionReport> found = adapter.findById(reportId);
        assertThat(found).isPresent();

        InteractionReport retrieved = found.get();
        assertThat(retrieved.getId()).isEqualTo(reportId);
        assertThat(retrieved.getCommentId()).isEqualTo(commentId);
        assertThat(retrieved.getReporterUserId()).isEqualTo(reporterUserId);
        assertThat(retrieved.getReason()).isEqualTo(ReportReason.SPAM);
        assertThat(retrieved.getDescription()).isEqualTo("Commercial promotional link");
        assertThat(retrieved.getReportedBodySnapshot()).isEqualTo("Spam text snapshot");
        assertThat(retrieved.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(retrieved.getCreatedAt()).isEqualTo(createdAt);
        assertThat(retrieved.getResolvedByUserId()).isNull();
        assertThat(retrieved.getResolvedAt()).isNull();
        assertThat(retrieved.isPending()).isTrue();
        assertThat(retrieved.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("existsPendingByCommentIdAndReporterUserId accurately reflects status lifecycle")
    void shouldAccuratelyTrackPendingExistenceAcrossLifecycle() {
        UUID reportId = UUID.randomUUID();
        UUID commentId = insertComment();
        UUID reporterUserId = UUID.randomUUID();
        UUID otherReporterUserId = UUID.randomUUID();
        UUID otherCommentId = insertComment();
        Instant now = Instant.now();

        // 1. Initially false
        assertThat(adapter.existsPendingByCommentIdAndReporterUserId(commentId, reporterUserId)).isFalse();

        // 2. True after saving PENDING report
        InteractionReport report = InteractionReport.createPending(
                reportId,
                commentId,
                reporterUserId,
                ReportReason.HARASSMENT,
                null,
                "Reported comment snapshot",
                now
        );
        adapter.save(report);

        assertThat(adapter.existsPendingByCommentIdAndReporterUserId(commentId, reporterUserId)).isTrue();

        // 3. False for other reporter or other comment
        assertThat(adapter.existsPendingByCommentIdAndReporterUserId(commentId, otherReporterUserId)).isFalse();
        assertThat(adapter.existsPendingByCommentIdAndReporterUserId(otherCommentId, reporterUserId)).isFalse();

        // 4. False after transition to terminal status (RESOLVED_ACTION_TAKEN)
        UUID resolverUserId = UUID.randomUUID();
        Instant resolvedAt = now.plus(1, ChronoUnit.HOURS);
        report.resolveActionTaken(resolverUserId, resolvedAt);
        adapter.save(report);

        assertThat(adapter.existsPendingByCommentIdAndReporterUserId(commentId, reporterUserId)).isFalse();
    }

    @Test
    @DisplayName("saveAndFlush surfaces database duplicate-pending constraint for concurrent PENDING rows")
    void shouldSurfaceDatabaseDuplicatePendingConstraintForConcurrentPendingRows() {
        UUID commentId = insertComment();
        UUID reporterUserId = UUID.randomUUID();
        Instant now = Instant.now();

        InteractionReport report1 = InteractionReport.createPending(
                UUID.randomUUID(),
                commentId,
                reporterUserId,
                ReportReason.SPAM,
                null,
                "Snapshot 1",
                now
        );
        InteractionReport report2 = InteractionReport.createPending(
                UUID.randomUUID(),
                commentId,
                reporterUserId,
                ReportReason.HARASSMENT,
                null,
                "Snapshot 2",
                now
        );

        adapter.save(report1);

        assertThatThrownBy(() -> adapter.save(report2))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_interaction_reports_pending_reporter");
    }

    @Test
    @DisplayName("Allows subsequent PENDING report after prior report for same reporter/comment becomes terminal")
    void shouldAllowSubsequentPendingReportAfterPriorReportBecomesTerminal() {
        UUID commentId = insertComment();
        UUID reporterUserId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Report #1: PENDING -> RESOLVED_ACTION_TAKEN
        UUID reportId1 = UUID.randomUUID();
        InteractionReport report1 = InteractionReport.createPending(
                reportId1, commentId, reporterUserId, ReportReason.SPAM, null, "Snapshot 1", now
        );
        adapter.save(report1);

        UUID resolverUserId = UUID.randomUUID();
        report1.resolveActionTaken(resolverUserId, now.plus(1, ChronoUnit.HOURS));
        adapter.save(report1);

        // 2. Report #2: PENDING from same reporter on same comment succeeds
        UUID reportId2 = UUID.randomUUID();
        InteractionReport report2 = InteractionReport.createPending(
                reportId2, commentId, reporterUserId, ReportReason.HARASSMENT, null, "Snapshot 2", now
        );
        InteractionReport savedReport2 = adapter.save(report2);

        assertThat(savedReport2.getId()).isEqualTo(reportId2);
        assertThat(savedReport2.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(adapter.existsPendingByCommentIdAndReporterUserId(commentId, reporterUserId)).isTrue();

        // Both rows coexist in database
        Optional<InteractionReport> retrieved1 = adapter.findById(reportId1);
        Optional<InteractionReport> retrieved2 = adapter.findById(reportId2);

        assertThat(retrieved1).isPresent();
        assertThat(retrieved1.get().getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);

        assertThat(retrieved2).isPresent();
        assertThat(retrieved2.get().getStatus()).isEqualTo(ReportStatus.PENDING);
    }
}
