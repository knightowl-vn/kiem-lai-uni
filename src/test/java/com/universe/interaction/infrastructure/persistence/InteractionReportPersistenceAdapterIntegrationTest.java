package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.domain.report.InteractionReport;
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
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        TestDatabaseSupport.resetTestDatabase("kiemlai_test");
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

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        assertThat(retrieved.getModerationAction()).isNull();
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
    @DisplayName("save surfaces database duplicate-pending constraint translated to DuplicatePendingReportException")
    void shouldSurfaceDatabaseDuplicatePendingConstraintTranslatedToApplicationException() {
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
                .isInstanceOf(com.universe.interaction.application.exceptions.DuplicatePendingReportException.class)
                .hasMessageContaining(commentId.toString())
                .hasMessageContaining(reporterUserId.toString());
    }

    @Test
    @DisplayName("Non-duplicate data integrity violations (e.g. column width constraint) are not translated to DuplicatePendingReportException")
    void shouldNotFalselyTranslateOtherDataIntegrityViolations() throws Exception {
        UUID commentId = insertComment();
        UUID reporterUserId = UUID.randomUUID();
        Instant now = Instant.now();

        InteractionReport report = InteractionReport.createPending(
                UUID.randomUUID(),
                commentId,
                reporterUserId,
                ReportReason.SPAM,
                null,
                "Valid initial snapshot",
                now
        );

        // Inject description exceeding column length (501 chars) via reflection to trigger DataIntegrityViolationException at DB flush
        java.lang.reflect.Field field = InteractionReport.class.getDeclaredField("description");
        field.setAccessible(true);
        field.set(report, "A".repeat(501));

        assertThatThrownBy(() -> adapter.save(report))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(com.universe.interaction.application.exceptions.DuplicatePendingReportException.class);
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
        assertThat(retrieved1.get().getModerationAction()).isEqualTo(ReportModerationAction.DELETE_COMMENT);

        assertThat(retrieved2).isPresent();
        assertThat(retrieved2.get().getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(retrieved2.get().getModerationAction()).isNull();
    }

    @Test
    @DisplayName("findByIdForUpdate throws IllegalArgumentException when id is null")
    void shouldRejectNullIdInFindByIdForUpdate() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> txTemplate.execute(status -> adapter.findByIdForUpdate(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Report ID cannot be null.");
    }

    @Test
    @DisplayName("findByIdForUpdate enforces mandatory transaction propagation")
    void shouldEnforceMandatoryTransactionForFindByIdForUpdate() {
        assertThatThrownBy(() -> adapter.findByIdForUpdate(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("No existing transaction found for transaction marked with propagation 'mandatory'");
    }

    @Test
    @DisplayName("findByIdForUpdate returns Optional.empty() when report is absent")
    void shouldReturnEmptyOptionalWhenReportAbsentInFindByIdForUpdate() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Optional<InteractionReport> result = txTemplate.execute(status -> adapter.findByIdForUpdate(UUID.randomUUID()));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByIdForUpdate retrieves and correctly maps existing report aggregate")
    void shouldRetrieveAndMapExistingReportInFindByIdForUpdate() {
        UUID reportId = UUID.randomUUID();
        UUID commentId = insertComment();
        UUID reporterUserId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        InteractionReport pendingReport = InteractionReport.createPending(
                reportId,
                commentId,
                reporterUserId,
                ReportReason.SPOILER,
                "Major plot spoiler",
                "Spoiler text snapshot",
                createdAt
        );
        adapter.save(pendingReport);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Optional<InteractionReport> found = txTemplate.execute(status -> adapter.findByIdForUpdate(reportId));
        assertThat(found).isPresent();

        InteractionReport retrieved = found.get();
        assertThat(retrieved.getId()).isEqualTo(reportId);
        assertThat(retrieved.getCommentId()).isEqualTo(commentId);
        assertThat(retrieved.getReporterUserId()).isEqualTo(reporterUserId);
        assertThat(retrieved.getReason()).isEqualTo(ReportReason.SPOILER);
        assertThat(retrieved.getDescription()).isEqualTo("Major plot spoiler");
        assertThat(retrieved.getReportedBodySnapshot()).isEqualTo("Spoiler text snapshot");
        assertThat(retrieved.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(retrieved.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("SpringDataInteractionReportRepository.findByIdForUpdate is configured with PESSIMISTIC_WRITE lock")
    void shouldVerifyPessimisticWriteLockAnnotationOnRepository() throws NoSuchMethodException {
        Method method = SpringDataInteractionReportRepository.class.getMethod("findByIdForUpdate", String.class);
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("findByIdForUpdate locks report row and serializes concurrent access")
    void shouldLockReportRowAndSerializeConcurrentAccess() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID commentId = insertComment();
        UUID reporterUserId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        InteractionReport report = InteractionReport.createPending(
                reportId, commentId, reporterUserId, ReportReason.HARASSMENT, null, "Harassment snapshot", now
        );
        adapter.save(report);

        CountDownLatch thread1LockedRow = new CountDownLatch(1);
        CountDownLatch thread2AttemptingLock = new CountDownLatch(1);
        AtomicBoolean thread2ObservedTerminal = new AtomicBoolean(false);

        TransactionTemplate txTemplate1 = new TransactionTemplate(transactionManager);
        txTemplate1.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        TransactionTemplate txTemplate2 = new TransactionTemplate(transactionManager);
        txTemplate2.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> future1 = executor.submit(() -> {
                txTemplate1.execute(status -> {
                    InteractionReport locked = adapter.findByIdForUpdate(reportId).orElseThrow();
                    thread1LockedRow.countDown();
                    try {
                        thread2AttemptingLock.await(5, TimeUnit.SECONDS);
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    locked.resolveActionTaken(UUID.randomUUID(), now.plusSeconds(30));
                    adapter.save(locked);
                    return null;
                });
            });

            Future<?> future2 = executor.submit(() -> {
                try {
                    thread1LockedRow.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                thread2AttemptingLock.countDown();
                txTemplate2.execute(status -> {
                    InteractionReport lockedByThread2 = adapter.findByIdForUpdate(reportId).orElseThrow();
                    thread2ObservedTerminal.set(lockedByThread2.isTerminal());
                    return null;
                });
            });

            future1.get(10, TimeUnit.SECONDS);
            future2.get(10, TimeUnit.SECONDS);

            assertThat(thread2ObservedTerminal.get()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Persists exact moderation_action column and reconstitutes faithfully for both terminal statuses")
    void shouldPersistAndReloadTerminalReportsWithExactModerationActionInDatabase() {
        UUID commentId = insertComment();
        UUID reporter1 = UUID.randomUUID();
        UUID reporter2 = UUID.randomUUID();
        UUID resolverUserId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // 1. RESOLVED_ACTION_TAKEN -> DELETE_COMMENT
        UUID reportId1 = UUID.randomUUID();
        InteractionReport report1 = InteractionReport.createPending(
                reportId1, commentId, reporter1, ReportReason.SPAM, null, "Snapshot 1", now
        );
        adapter.save(report1);
        report1.resolveActionTaken(resolverUserId, now.plusSeconds(10));
        adapter.save(report1);

        InteractionReport retrieved1 = adapter.findById(reportId1).orElseThrow();
        assertThat(retrieved1.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
        assertThat(retrieved1.getModerationAction()).isEqualTo(ReportModerationAction.DELETE_COMMENT);

        String dbAction1 = jdbcTemplate.queryForObject(
                "SELECT moderation_action FROM interaction_reports WHERE id = ?",
                String.class,
                reportId1.toString()
        );
        assertThat(dbAction1).isEqualTo("DELETE_COMMENT");

        // 2. RESOLVED_NO_ACTION -> NO_ACTION
        UUID reportId2 = UUID.randomUUID();
        InteractionReport report2 = InteractionReport.createPending(
                reportId2, commentId, reporter2, ReportReason.HARASSMENT, "No violation", "Snapshot 2", now
        );
        adapter.save(report2);
        report2.resolveNoAction(resolverUserId, now.plusSeconds(20));
        adapter.save(report2);

        InteractionReport retrieved2 = adapter.findById(reportId2).orElseThrow();
        assertThat(retrieved2.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
        assertThat(retrieved2.getModerationAction()).isEqualTo(ReportModerationAction.NO_ACTION);

        String dbAction2 = jdbcTemplate.queryForObject(
                "SELECT moderation_action FROM interaction_reports WHERE id = ?",
                String.class,
                reportId2.toString()
        );
        assertThat(dbAction2).isEqualTo("NO_ACTION");
    }

    @Test
    @DisplayName("Database check constraint rejects invalid status and moderation_action combinations")
    void shouldEnforceDatabaseCheckConstraintOnModerationAction() {
        UUID commentId = insertComment();
        UUID reporter = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Invalid: PENDING with moderation_action set
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO interaction_reports (id, comment_id, reporter_user_id, reason, reported_body_snapshot, status, moderation_action, created_at) " +
                        "VALUES (?, ?, ?, 'SPAM', 'Snapshot', 'PENDING', 'DELETE_COMMENT', ?)",
                UUID.randomUUID().toString(), commentId.toString(), reporter.toString(), Timestamp.from(now)
        )).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("chk_interaction_reports_moderation_action");

        // Invalid: RESOLVED_ACTION_TAKEN with NULL moderation_action
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO interaction_reports (id, comment_id, reporter_user_id, reason, reported_body_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, ?, ?, 'SPAM', 'Snapshot', 'RESOLVED_ACTION_TAKEN', NULL, ?, ?, ?)",
                UUID.randomUUID().toString(), commentId.toString(), reporter.toString(), Timestamp.from(now), UUID.randomUUID().toString(), Timestamp.from(now)
        )).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("chk_interaction_reports_moderation_action");

        // Invalid: RESOLVED_NO_ACTION with DELETE_COMMENT
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO interaction_reports (id, comment_id, reporter_user_id, reason, reported_body_snapshot, status, moderation_action, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, ?, ?, 'SPAM', 'Snapshot', 'RESOLVED_NO_ACTION', 'DELETE_COMMENT', ?, ?, ?)",
                UUID.randomUUID().toString(), commentId.toString(), reporter.toString(), Timestamp.from(now), UUID.randomUUID().toString(), Timestamp.from(now)
        )).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("chk_interaction_reports_moderation_action");
    }
}
