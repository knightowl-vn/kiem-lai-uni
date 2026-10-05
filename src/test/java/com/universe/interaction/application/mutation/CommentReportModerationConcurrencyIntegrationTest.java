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
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceMapper;
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
import com.universe.community.contracts.port.CommunityPostInteractionMutationPort;
import java.util.Optional;
import java.util.List;

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
        ReactionPersistenceAdapter.class,
        ReactionPersistenceMapper.class,
        ResolveCommentReportUseCase.class,
        SubmitInteractionReportUseCase.class,
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

        @Bean
        public CommunityPostInteractionMutationPort communityPostInteractionMutationPort() {
            return postId -> Optional.empty();
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.resetTestDatabase("kiemlai_test");
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
        jdbcTemplate.update("DELETE FROM interaction_reactions");
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
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at, resolved_by_user_id, resolved_at, moderation_action) " +
                        "VALUES (?, 'COMMENT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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

            // Verify DB state for comment (physically deleted under V73 hard-delete)
            Integer commentCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, commentId.toString());
            assertThat(commentCount).isZero();

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

            Integer remainingCommentCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, commentId.toString());

            if (r1.isSuccess()) {
                // DELETE_COMMENT won
                assertThat(reportStatus).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN.name());
                assertThat(moderationAction).isEqualTo(ReportModerationAction.DELETE_COMMENT.name());
                assertThat(resolvedByUserId).isEqualTo(moderator1.toString());
                assertThat(remainingCommentCount).isZero();
            } else {
                // NO_ACTION won
                assertThat(reportStatus).isEqualTo(ReportStatus.RESOLVED_NO_ACTION.name());
                assertThat(moderationAction).isEqualTo(ReportModerationAction.NO_ACTION.name());
                assertThat(resolvedByUserId).isEqualTo(moderator2.toString());
                assertThat(remainingCommentCount).isEqualTo(1);
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

            int successes = (r1.isSuccess() ? 1 : 0) + (r2.isSuccess() ? 1 : 0);
            int failures = (r1.isFailure() ? 1 : 0) + (r2.isFailure() ? 1 : 0);

            assertThat(successes).isEqualTo(1);
            assertThat(failures).isEqualTo(1);

            TaskResult<Void> failed = r1.isFailure() ? r1 : r2;
            UUID winningReportId = r1.isSuccess() ? report1Id : report2Id;
            UUID losingReportId = r1.isFailure() ? report1Id : report2Id;

            // Losing moderator observes CommentNotFoundException after comment was physically deleted by winning moderator
            assertThat(isOrCausedBy(failed.error(), com.universe.interaction.application.exceptions.CommentNotFoundException.class)).isTrue();

            // Winning report is RESOLVED_ACTION_TAKEN with DELETE_COMMENT
            String winningStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_reports WHERE id = ?", String.class, winningReportId.toString());
            String winningAction = jdbcTemplate.queryForObject(
                    "SELECT moderation_action FROM interaction_reports WHERE id = ?", String.class, winningReportId.toString());
            assertThat(winningStatus).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN.name());
            assertThat(winningAction).isEqualTo(ReportModerationAction.DELETE_COMMENT.name());

            // Losing report transaction rolled back and remains PENDING with no corrupted action
            String losingStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM interaction_reports WHERE id = ?", String.class, losingReportId.toString());
            String losingAction = jdbcTemplate.queryForObject(
                    "SELECT moderation_action FROM interaction_reports WHERE id = ?", String.class, losingReportId.toString());
            assertThat(losingStatus).isEqualTo(ReportStatus.PENDING.name());
            assertThat(losingAction).isNull();

            // Comment is physically deleted under V73 hard-delete
            Integer commentCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, commentId.toString());
            assertThat(commentCount).isZero();

            // Comment revisions count is 0
            Integer revisionCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?", Integer.class, commentId.toString());
            assertThat(revisionCount).isZero();

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Scenario D: Author delete vs moderator delete -> comment deleted once, report resolves DELETE_COMMENT")
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

            int successes = (rAuthor.isSuccess() ? 1 : 0) + (rMod.isSuccess() ? 1 : 0);
            int failures = (rAuthor.isFailure() ? 1 : 0) + (rMod.isFailure() ? 1 : 0);

            assertThat(successes).isEqualTo(1);
            assertThat(failures).isEqualTo(1);

            TaskResult<Void> failed = rAuthor.isFailure() ? rAuthor : rMod;
            assertThat(isOrCausedBy(failed.error(), com.universe.interaction.application.exceptions.CommentNotFoundException.class)).isTrue();

            if (rMod.isSuccess()) {
                String reportStatus = jdbcTemplate.queryForObject(
                        "SELECT status FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
                String moderationAction = jdbcTemplate.queryForObject(
                        "SELECT moderation_action FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
                assertThat(reportStatus).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN.name());
                assertThat(moderationAction).isEqualTo(ReportModerationAction.DELETE_COMMENT.name());
            } else {
                String reportStatus = jdbcTemplate.queryForObject(
                        "SELECT status FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
                String moderationAction = jdbcTemplate.queryForObject(
                        "SELECT moderation_action FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
                assertThat(reportStatus).isEqualTo(ReportStatus.PENDING.name());
                assertThat(moderationAction).isNull();
            }

            // Comment is physically deleted under V73 hard-delete
            Integer commentCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, commentId.toString());
            assertThat(commentCount).isZero();

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

            // If author edit failed, it must be because comment was already deleted / not found
            if (rAuthor.isFailure()) {
                assertThat(isOrCausedBy(rAuthor.error(), CommentMutationForbiddenException.class)
                        || isOrCausedBy(rAuthor.error(), com.universe.interaction.application.exceptions.CommentNotFoundException.class)).isTrue();
            }

            // CRUCIAL INVARIANT: Comment MUST NOT be resurrected as ACTIVE with 'Edited Body'; it must be physically deleted
            Integer commentCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, commentId.toString());
            assertThat(commentCount).isZero();

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
    @DisplayName("Scenario F: Report submit vs comment delete -> if delete wins, throws CommentNotFoundException; if submit wins, captures valid non-empty snapshot")
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

            // Comment is physically deleted under V73 hard-delete
            Integer commentCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, commentId.toString());
            assertThat(commentCount).isZero();

            if (rSubmit.isSuccess()) {
                // Submit won: captured valid non-empty snapshot
                InteractionReport report = rSubmit.result();
                assertThat(report).isNotNull();
                assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
                assertThat(report.getReportedContentSnapshot()).isEqualTo(initialBody);

                // Verified in DB
                String snapshotInDb = jdbcTemplate.queryForObject(
                        "SELECT content_snapshot FROM interaction_reports WHERE id = ?", String.class, report.getId().toString());
                assertThat(snapshotInDb).isEqualTo(initialBody);
            } else {
                // Delete won: submit threw CommentNotFoundException or CommentNotReportableException
                assertThat(isOrCausedBy(rSubmit.error(), com.universe.interaction.application.exceptions.CommentNotFoundException.class)
                        || isOrCausedBy(rSubmit.error(), CommentNotReportableException.class)).isTrue();

                // No report in DB
                Integer reportCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM interaction_reports WHERE target_type = 'COMMENT' AND target_id = ?", Integer.class, commentId.toString());
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
                    "SELECT COUNT(*) FROM interaction_reports WHERE target_type = 'COMMENT' AND target_id = ? AND reporter_user_id = ? AND status = 'PENDING'",
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
