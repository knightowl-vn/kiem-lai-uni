package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotReportableException;
import com.universe.interaction.application.exceptions.DuplicatePendingReportException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.CommentRevisionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentRevisionPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.InteractionReportPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.InteractionReportPersistenceMapper;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic MySQL integration tests covering concurrency invariants for comment report moderation,
 * comment deletion, comment editing, and report submission under race conditions.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@EntityScan(basePackages = "com.universe.interaction.infrastructure.persistence")
@EnableJpaRepositories(basePackages = "com.universe.interaction.infrastructure.persistence")
@Import({
        CommentPersistenceAdapter.class,
        CommentPersistenceMapper.class,
        CommentRevisionPersistenceAdapter.class,
        CommentRevisionPersistenceMapper.class,
        InteractionReportPersistenceAdapter.class,
        InteractionReportPersistenceMapper.class,
        ResolveCommentReportUseCase.class,
        SubmitCommentReportUseCase.class,
        DeleteCommentUseCase.class,
        EditCommentUseCase.class,
        CommentReportModerationConcurrencyIntegrationTest.TestConfig.class
})
@DisplayName("Comment Report Moderation Concurrency MySQL Integration Tests")
class CommentReportModerationConcurrencyIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }

        @Bean
        public IdGeneratorPort idGeneratorPort() {
            return UUID::randomUUID;
        }

        @Bean
        public CommentTargetEligibilityPort commentTargetEligibilityPort() {
            return target -> true;
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private ResolveCommentReportUseCase resolveCommentReportUseCase;

    @Autowired
    private SubmitCommentReportUseCase submitCommentReportUseCase;

    @Autowired
    private DeleteCommentUseCase deleteCommentUseCase;

    @Autowired
    private EditCommentUseCase editCommentUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM interaction_comment_revisions");
        jdbcTemplate.update("DELETE FROM interaction_reports");
        jdbcTemplate.update("DELETE FROM interaction_comments");
    }

    private record TaskResult<T>(T result, Throwable error) {
        boolean isSuccess() {
            return error == null;
        }

        boolean isFailure() {
            return error != null;
        }
    }

    private static boolean isOrCausedBy(Throwable ex, Class<? extends Throwable> expectedType) {
        Throwable current = ex;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private <T> Callable<TaskResult<T>> task(CountDownLatch ready, CountDownLatch start, Callable<T> action) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for start latch.");
            }
            try {
                T result = action.call();
                return new TaskResult<>(result, null);
            } catch (Throwable ex) {
                return new TaskResult<>(null, ex);
            }
        };
    }

    private Callable<TaskResult<Void>> voidTask(CountDownLatch ready, CountDownLatch start, Runnable action) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for start latch.");
            }
            try {
                action.run();
                return new TaskResult<>(null, null);
            } catch (Throwable ex) {
                return new TaskResult<>(null, ex);
            }
        };
    }

    private UUID insertComment(UUID commentId, UUID authorId, CommentStatus status, String body) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Timestamp deletedAt = status == CommentStatus.DELETED ? Timestamp.from(now) : null;
        String actualBody = status == CommentStatus.DELETED ? null : body;
        jdbcTemplate.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?)",
                commentId.toString(),
                CommentTargetType.NOVEL_CHAPTER.name(),
                UUID.randomUUID().toString(),
                authorId.toString(),
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
            UUID reporterUserId,
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
                reporterUserId.toString(),
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
    @DisplayName("Scenario A: Same report, same action (DELETE_COMMENT + DELETE_COMMENT) -> 1 success, 1 ReportAlreadyResolvedException")
    void testScenarioA_sameReport_sameAction_deleteComment() throws Exception {
        UUID commentId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        insertComment(commentId, authorId, CommentStatus.ACTIVE, "Reported comment body");

        UUID reportId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        insertReport(reportId, commentId, reporterId, ReportStatus.PENDING, createdAt, null, null);

        UUID moderator1 = UUID.randomUUID();
        UUID moderator2 = UUID.randomUUID();

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<TaskResult<Void>> f1 = executor.submit(voidTask(readyLatch, startLatch, () ->
                    resolveCommentReportUseCase.execute(new ResolveCommentReportCommand(reportId, moderator1, ReportModerationAction.DELETE_COMMENT))));
            Future<TaskResult<Void>> f2 = executor.submit(voidTask(readyLatch, startLatch, () ->
                    resolveCommentReportUseCase.execute(new ResolveCommentReportCommand(reportId, moderator2, ReportModerationAction.DELETE_COMMENT))));

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            TaskResult<Void> r1 = f1.get(10, TimeUnit.SECONDS);
            TaskResult<Void> r2 = f2.get(10, TimeUnit.SECONDS);

            int successes = (r1.isSuccess() ? 1 : 0) + (r2.isSuccess() ? 1 : 0);
            int failures = (r1.isFailure() ? 1 : 0) + (r2.isFailure() ? 1 : 0);

            assertThat(successes).isEqualTo(1);
            assertThat(failures).isEqualTo(1);

            TaskResult<Void> failure = r1.isFailure() ? r1 : r2;
            assertThat(isOrCausedBy(failure.error(), ReportAlreadyResolvedException.class)).isTrue();

            // Verify DB state for report
            String reportStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
            String moderationAction = jdbcTemplate.queryForObject(
                    "SELECT moderation_action FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
            String resolvedByUserId = jdbcTemplate.queryForObject(
                    "SELECT resolved_by_user_id FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
            Timestamp resolvedAt = jdbcTemplate.queryForObject(
                    "SELECT resolved_at FROM interaction_reports WHERE id = ?", Timestamp.class, reportId.toString());

            assertThat(reportStatus).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN.name());
            assertThat(moderationAction).isEqualTo(ReportModerationAction.DELETE_COMMENT.name());
            UUID winningModerator = r1.isSuccess() ? moderator1 : moderator2;
            assertThat(resolvedByUserId).isEqualTo(winningModerator.toString());
            assertThat(resolvedAt).isNotNull();

            // Verify DB state for comment
            String commentDbStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_comments WHERE id = ?", String.class, commentId.toString());
            String commentBody = jdbcTemplate.queryForObject(
                    "SELECT body FROM interaction_comments WHERE id = ?", String.class, commentId.toString());
            Timestamp commentDeletedAt = jdbcTemplate.queryForObject(
                    "SELECT deleted_at FROM interaction_comments WHERE id = ?", Timestamp.class, commentId.toString());

            assertThat(commentDbStatus).isEqualTo(CommentStatus.DELETED.name());
            assertThat(commentBody).isNull();
            assertThat(commentDeletedAt).isNotNull();

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Scenario B: Same report, different action (DELETE_COMMENT vs NO_ACTION) -> 1 success, 1 ReportAlreadyResolvedException")
    void testScenarioB_sameReport_differentAction_deleteCommentVsNoAction() throws Exception {
        UUID commentId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        insertComment(commentId, authorId, CommentStatus.ACTIVE, "Contested comment body");

        UUID reportId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        insertReport(reportId, commentId, reporterId, ReportStatus.PENDING, createdAt, null, null);

        UUID moderator1 = UUID.randomUUID();
        UUID moderator2 = UUID.randomUUID();

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<TaskResult<Void>> f1 = executor.submit(voidTask(readyLatch, startLatch, () ->
                    resolveCommentReportUseCase.execute(new ResolveCommentReportCommand(reportId, moderator1, ReportModerationAction.DELETE_COMMENT))));
            Future<TaskResult<Void>> f2 = executor.submit(voidTask(readyLatch, startLatch, () ->
                    resolveCommentReportUseCase.execute(new ResolveCommentReportCommand(reportId, moderator2, ReportModerationAction.NO_ACTION))));

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            TaskResult<Void> r1 = f1.get(10, TimeUnit.SECONDS);
            TaskResult<Void> r2 = f2.get(10, TimeUnit.SECONDS);

            int successes = (r1.isSuccess() ? 1 : 0) + (r2.isSuccess() ? 1 : 0);
            int failures = (r1.isFailure() ? 1 : 0) + (r2.isFailure() ? 1 : 0);

            assertThat(successes).isEqualTo(1);
            assertThat(failures).isEqualTo(1);

            TaskResult<Void> failure = r1.isFailure() ? r1 : r2;
            assertThat(isOrCausedBy(failure.error(), ReportAlreadyResolvedException.class)).isTrue();

            String reportStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
            String moderationAction = jdbcTemplate.queryForObject(
                    "SELECT moderation_action FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
            String resolvedByUserId = jdbcTemplate.queryForObject(
                    "SELECT resolved_by_user_id FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
            Timestamp resolvedAt = jdbcTemplate.queryForObject(
                    "SELECT resolved_at FROM interaction_reports WHERE id = ?", Timestamp.class, reportId.toString());

            assertThat(resolvedAt).isNotNull();

            String commentDbStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_comments WHERE id = ?", String.class, commentId.toString());

            if (r1.isSuccess()) {
                // DELETE_COMMENT won
                assertThat(reportStatus).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN.name());
                assertThat(moderationAction).isEqualTo(ReportModerationAction.DELETE_COMMENT.name());
                assertThat(resolvedByUserId).isEqualTo(moderator1.toString());
                assertThat(commentDbStatus).isEqualTo(CommentStatus.DELETED.name());
            } else {
                // NO_ACTION won
                assertThat(reportStatus).isEqualTo(ReportStatus.RESOLVED_NO_ACTION.name());
                assertThat(moderationAction).isEqualTo(ReportModerationAction.NO_ACTION.name());
                assertThat(resolvedByUserId).isEqualTo(moderator2.toString());
                assertThat(commentDbStatus).isEqualTo(CommentStatus.ACTIVE.name());
            }

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Scenario C: Cross-report, same comment, both DELETE_COMMENT -> both reports reach RESOLVED_ACTION_TAKEN, comment deleted once")
    void testScenarioC_crossReport_sameComment_bothDeleteComment() throws Exception {
        UUID commentId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        insertComment(commentId, authorId, CommentStatus.ACTIVE, "Shared offending body");

        UUID report1Id = UUID.randomUUID();
        UUID report2Id = UUID.randomUUID();
        UUID reporter1 = UUID.randomUUID();
        UUID reporter2 = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);

        insertReport(report1Id, commentId, reporter1, ReportStatus.PENDING, createdAt, null, null);
        insertReport(report2Id, commentId, reporter2, ReportStatus.PENDING, createdAt, null, null);

        UUID moderator1 = UUID.randomUUID();
        UUID moderator2 = UUID.randomUUID();

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<TaskResult<Void>> f1 = executor.submit(voidTask(readyLatch, startLatch, () ->
                    resolveCommentReportUseCase.execute(new ResolveCommentReportCommand(report1Id, moderator1, ReportModerationAction.DELETE_COMMENT))));
            Future<TaskResult<Void>> f2 = executor.submit(voidTask(readyLatch, startLatch, () ->
                    resolveCommentReportUseCase.execute(new ResolveCommentReportCommand(report2Id, moderator2, ReportModerationAction.DELETE_COMMENT))));

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            TaskResult<Void> r1 = f1.get(10, TimeUnit.SECONDS);
            TaskResult<Void> r2 = f2.get(10, TimeUnit.SECONDS);

            // Both sibling reports must succeed
            assertThat(r1.isSuccess()).isTrue();
            assertThat(r2.isSuccess()).isTrue();

            // Report 1 is RESOLVED_ACTION_TAKEN
            String status1 = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_reports WHERE id = ?", String.class, report1Id.toString());
            String action1 = jdbcTemplate.queryForObject(
                    "SELECT moderation_action FROM interaction_reports WHERE id = ?", String.class, report1Id.toString());
            assertThat(status1).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN.name());
            assertThat(action1).isEqualTo(ReportModerationAction.DELETE_COMMENT.name());

            // Report 2 is RESOLVED_ACTION_TAKEN
            String status2 = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_reports WHERE id = ?", String.class, report2Id.toString());
            String action2 = jdbcTemplate.queryForObject(
                    "SELECT moderation_action FROM interaction_reports WHERE id = ?", String.class, report2Id.toString());
            assertThat(status2).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN.name());
            assertThat(action2).isEqualTo(ReportModerationAction.DELETE_COMMENT.name());

            // Comment is DELETED with null body
            String commentStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_comments WHERE id = ?", String.class, commentId.toString());
            String commentBody = jdbcTemplate.queryForObject(
                    "SELECT body FROM interaction_comments WHERE id = ?", String.class, commentId.toString());
            Timestamp deletedAt = jdbcTemplate.queryForObject(
                    "SELECT deleted_at FROM interaction_comments WHERE id = ?", Timestamp.class, commentId.toString());

            assertThat(commentStatus).isEqualTo(CommentStatus.DELETED.name());
            assertThat(commentBody).isNull();
            assertThat(deletedAt).isNotNull();

            // Comment revisions count is 0
            Integer revisionCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?", Integer.class, commentId.toString());
            assertThat(revisionCount).isZero();

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Scenario D: Author delete vs moderator delete -> comment deleted once (deleted_at preserved), report resolves DELETE_COMMENT")
    void testScenarioD_authorDeleteVsModeratorDelete() throws Exception {
        UUID commentId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        insertComment(commentId, authorId, CommentStatus.ACTIVE, "Delete race body");

        UUID reportId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        insertReport(reportId, commentId, reporterId, ReportStatus.PENDING, createdAt, null, null);

        UUID moderatorId = UUID.randomUUID();

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<TaskResult<Void>> fAuthor = executor.submit(voidTask(readyLatch, startLatch, () ->
                    deleteCommentUseCase.execute(new DeleteCommentCommand(authorId, commentId))));
            Future<TaskResult<Void>> fMod = executor.submit(voidTask(readyLatch, startLatch, () ->
                    resolveCommentReportUseCase.execute(new ResolveCommentReportCommand(reportId, moderatorId, ReportModerationAction.DELETE_COMMENT))));

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            TaskResult<Void> rAuthor = fAuthor.get(10, TimeUnit.SECONDS);
            TaskResult<Void> rMod = fMod.get(10, TimeUnit.SECONDS);

            // Both operations must succeed
            assertThat(rAuthor.isSuccess()).isTrue();
            assertThat(rMod.isSuccess()).isTrue();

            // Report resolved as ACTION_TAKEN
            String reportStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
            String moderationAction = jdbcTemplate.queryForObject(
                    "SELECT moderation_action FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
            assertThat(reportStatus).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN.name());
            assertThat(moderationAction).isEqualTo(ReportModerationAction.DELETE_COMMENT.name());

            // Comment is DELETED, body is null, deleted_at and updated_at match
            String commentStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_comments WHERE id = ?", String.class, commentId.toString());
            String commentBody = jdbcTemplate.queryForObject(
                    "SELECT body FROM interaction_comments WHERE id = ?", String.class, commentId.toString());
            Timestamp deletedAt = jdbcTemplate.queryForObject(
                    "SELECT deleted_at FROM interaction_comments WHERE id = ?", Timestamp.class, commentId.toString());
            Timestamp updatedAt = jdbcTemplate.queryForObject(
                    "SELECT updated_at FROM interaction_comments WHERE id = ?", Timestamp.class, commentId.toString());

            assertThat(commentStatus).isEqualTo(CommentStatus.DELETED.name());
            assertThat(commentBody).isNull();
            assertThat(deletedAt).isNotNull();
            assertThat(updatedAt).isEqualTo(deletedAt);

            Integer revisionCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?", Integer.class, commentId.toString());
            assertThat(revisionCount).isZero();

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Scenario E: Author edit vs moderator delete -> serialized via pessimistic comment lock; no resurrection")
    void testScenarioE_authorEditVsModeratorDelete_noResurrection() throws Exception {
        UUID commentId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        insertComment(commentId, authorId, CommentStatus.ACTIVE, "Original Body");

        UUID reportId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        insertReport(reportId, commentId, reporterId, ReportStatus.PENDING, createdAt, null, null);

        UUID moderatorId = UUID.randomUUID();

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<TaskResult<Void>> fAuthor = executor.submit(voidTask(readyLatch, startLatch, () ->
                    editCommentUseCase.execute(new EditCommentCommand(authorId, commentId, "Edited Body"))));
            Future<TaskResult<Void>> fMod = executor.submit(voidTask(readyLatch, startLatch, () ->
                    resolveCommentReportUseCase.execute(new ResolveCommentReportCommand(reportId, moderatorId, ReportModerationAction.DELETE_COMMENT))));

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            TaskResult<Void> rAuthor = fAuthor.get(10, TimeUnit.SECONDS);
            TaskResult<Void> rMod = fMod.get(10, TimeUnit.SECONDS);

            // Moderator delete MUST always succeed
            assertThat(rMod.isSuccess()).isTrue();

            // If author edit failed, it must be because comment was already deleted
            if (rAuthor.isFailure()) {
                assertThat(isOrCausedBy(rAuthor.error(), CommentMutationForbiddenException.class)).isTrue();
            }

            // CRUCIAL INVARIANT: Comment MUST be DELETED with null body (never resurrected as ACTIVE with 'Edited Body')
            String commentStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_comments WHERE id = ?", String.class, commentId.toString());
            String commentBody = jdbcTemplate.queryForObject(
                    "SELECT body FROM interaction_comments WHERE id = ?", String.class, commentId.toString());
            Timestamp deletedAt = jdbcTemplate.queryForObject(
                    "SELECT deleted_at FROM interaction_comments WHERE id = ?", Timestamp.class, commentId.toString());

            assertThat(commentStatus).isEqualTo(CommentStatus.DELETED.name());
            assertThat(commentBody).isNull();
            assertThat(deletedAt).isNotNull();

            // Revisions must be completely purged
            Integer revisionCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?", Integer.class, commentId.toString());
            assertThat(revisionCount).isZero();

            // Report must be RESOLVED_ACTION_TAKEN
            String reportStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
            String moderationAction = jdbcTemplate.queryForObject(
                    "SELECT moderation_action FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
            assertThat(reportStatus).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN.name());
            assertThat(moderationAction).isEqualTo(ReportModerationAction.DELETE_COMMENT.name());

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Scenario F: Report submit vs comment delete -> if delete wins, throws CommentNotReportableException; if submit wins, captures valid non-empty snapshot")
    void testScenarioF_reportSubmitVsCommentDelete() throws Exception {
        UUID commentId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        String initialBody = "Target comment for report vs delete race";
        insertComment(commentId, authorId, CommentStatus.ACTIVE, initialBody);

        UUID reporterUserId = UUID.randomUUID();

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<TaskResult<InteractionReport>> fSubmit = executor.submit(task(readyLatch, startLatch, () ->
                    submitCommentReportUseCase.execute(new SubmitCommentReportCommand(
                            commentId,
                            reporterUserId,
                            ReportReason.SPAM,
                            "Spam details"
                    ))));
            Future<TaskResult<Void>> fDelete = executor.submit(voidTask(readyLatch, startLatch, () ->
                    deleteCommentUseCase.execute(new DeleteCommentCommand(authorId, commentId))));

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            TaskResult<InteractionReport> rSubmit = fSubmit.get(10, TimeUnit.SECONDS);
            TaskResult<Void> rDelete = fDelete.get(10, TimeUnit.SECONDS);

            // Delete always succeeds
            assertThat(rDelete.isSuccess()).isTrue();

            // Comment is DELETED at the end
            String commentStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_comments WHERE id = ?", String.class, commentId.toString());
            assertThat(commentStatus).isEqualTo(CommentStatus.DELETED.name());

            if (rSubmit.isSuccess()) {
                // Submit won: captured valid non-empty snapshot
                InteractionReport report = rSubmit.result();
                assertThat(report).isNotNull();
                assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
                assertThat(report.getReportedBodySnapshot()).isEqualTo(initialBody);

                // Verified in DB
                String snapshotInDb = jdbcTemplate.queryForObject(
                        "SELECT reported_body_snapshot FROM interaction_reports WHERE id = ?", String.class, report.getId().toString());
                assertThat(snapshotInDb).isEqualTo(initialBody);
            } else {
                // Delete won: submit threw CommentNotReportableException
                assertThat(isOrCausedBy(rSubmit.error(), CommentNotReportableException.class)).isTrue();

                // No report in DB
                Integer reportCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM interaction_reports WHERE comment_id = ?", Integer.class, commentId.toString());
                assertThat(reportCount).isZero();
            }

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Scenario G: Duplicate report submit -> 1 success, 1 DuplicatePendingReportException; exactly 1 PENDING row")
    void testScenarioG_duplicateReportSubmit_sameUserSameComment() throws Exception {
        UUID commentId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        insertComment(commentId, authorId, CommentStatus.ACTIVE, "Duplicate report target body");

        UUID reporterUserId = UUID.randomUUID();

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<TaskResult<InteractionReport>> f1 = executor.submit(task(readyLatch, startLatch, () ->
                    submitCommentReportUseCase.execute(new SubmitCommentReportCommand(
                            commentId,
                            reporterUserId,
                            ReportReason.SPAM,
                            "First submission attempt"
                    ))));
            Future<TaskResult<InteractionReport>> f2 = executor.submit(task(readyLatch, startLatch, () ->
                    submitCommentReportUseCase.execute(new SubmitCommentReportCommand(
                            commentId,
                            reporterUserId,
                            ReportReason.HARASSMENT,
                            "Second submission attempt"
                    ))));

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            TaskResult<InteractionReport> r1 = f1.get(10, TimeUnit.SECONDS);
            TaskResult<InteractionReport> r2 = f2.get(10, TimeUnit.SECONDS);

            int successes = (r1.isSuccess() ? 1 : 0) + (r2.isSuccess() ? 1 : 0);
            int failures = (r1.isFailure() ? 1 : 0) + (r2.isFailure() ? 1 : 0);

            assertThat(successes).isEqualTo(1);
            assertThat(failures).isEqualTo(1);

            TaskResult<InteractionReport> failure = r1.isFailure() ? r1 : r2;
            assertThat(isOrCausedBy(failure.error(), DuplicatePendingReportException.class)).isTrue();

            // Exactly 1 PENDING report exists in DB for this comment and reporter
            Integer reportCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM interaction_reports WHERE comment_id = ? AND reporter_user_id = ? AND status = 'PENDING'",
                    Integer.class,
                    commentId.toString(),
                    reporterUserId.toString()
            );
            assertThat(reportCount).isEqualTo(1);

        } finally {
            executor.shutdownNow();
        }
    }
}
