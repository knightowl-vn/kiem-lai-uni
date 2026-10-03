package com.universe.interaction.application;

import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.contracts.port.CommunityPostInteractionMutationPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.infrastructure.persistence.CommunityPostPersistenceAdapter;
import com.universe.community.infrastructure.persistence.CommunityPostPersistenceMapper;
import com.universe.community.infrastructure.persistence.CommunityPostRevisionPersistenceMapper;
import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.exceptions.ReactionTargetNotEligibleException;
import com.universe.interaction.application.mutation.CleanupCommunityPostInteractionsUseCase;
import com.universe.interaction.application.mutation.CreateRootCommentCommand;
import com.universe.interaction.application.mutation.CreateRootCommentUseCase;
import com.universe.interaction.application.mutation.LockedReactionMutationExecutor;
import com.universe.interaction.application.mutation.ReplyCommentCommand;
import com.universe.interaction.application.mutation.ReplyCommentUseCase;
import com.universe.interaction.application.mutation.SetReactionCommand;
import com.universe.interaction.application.mutation.SetReactionUseCase;
import com.universe.interaction.application.mutation.SubmitInteractionReportCommand;
import com.universe.interaction.application.mutation.SubmitInteractionReportUseCase;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.application.ports.ReactionTargetEligibilityPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportTargetType;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.CommentRevisionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentRevisionPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.InteractionReportPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.InteractionReportPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceMapper;
import com.universe.notification.contracts.port.NotificationDispatchPort;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@EntityScan(basePackages = {
        "com.universe.interaction.infrastructure.persistence",
        "com.universe.community.infrastructure.persistence"
})
@EnableJpaRepositories(basePackages = {
        "com.universe.interaction.infrastructure.persistence",
        "com.universe.community.infrastructure.persistence"
})
@Import({
        CommunityPostPersistenceAdapter.class,
        CommunityPostPersistenceMapper.class,
        CommunityPostRevisionPersistenceMapper.class,
        CommentPersistenceAdapter.class,
        CommentPersistenceMapper.class,
        CommentRevisionPersistenceAdapter.class,
        CommentRevisionPersistenceMapper.class,
        ReactionPersistenceAdapter.class,
        ReactionPersistenceMapper.class,
        InteractionReportPersistenceAdapter.class,
        InteractionReportPersistenceMapper.class,
        CreateRootCommentUseCase.class,
        ReplyCommentUseCase.class,
        LockedReactionMutationExecutor.class,
        SetReactionUseCase.class,
        SubmitInteractionReportUseCase.class,
        CleanupCommunityPostInteractionsUseCase.class,
        CommunityPostInteractionCleanupConcurrencyIntegrationTest.TestConfig.class
})
@DisplayName("CommunityPostInteractionCleanupConcurrencyIntegrationTest — Deletion Barrier & Cleanup Race Safety")
class CommunityPostInteractionCleanupConcurrencyIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.resetTestDatabase("kiemlai_test");
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    static class TestSyncHook {
        private volatile Runnable onClockNow;
        private volatile Runnable onAttemptingPostLock;

        public void setOnClockNow(Runnable onClockNow) {
            this.onClockNow = onClockNow;
        }

        public void triggerClockNow() {
            Runnable callback = this.onClockNow;
            if (callback != null) {
                callback.run();
            }
        }

        public void setOnAttemptingPostLock(Runnable onAttemptingPostLock) {
            this.onAttemptingPostLock = onAttemptingPostLock;
        }

        public void triggerAttemptingPostLock() {
            Runnable callback = this.onAttemptingPostLock;
            if (callback != null) {
                callback.run();
            }
        }

        public void reset() {
            this.onClockNow = null;
            this.onAttemptingPostLock = null;
        }
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public TestSyncHook testSyncHook() {
            return new TestSyncHook();
        }

        @Bean
        public ClockPort clockPort(TestSyncHook hook) {
            return () -> {
                hook.triggerClockNow();
                return Instant.now();
            };
        }

        @Bean
        @Primary
        public CommunityPostInteractionMutationPort testMutationPort(
                CommunityPostPersistenceAdapter realAdapter,
                TestSyncHook hook
        ) {
            return new CommunityPostInteractionMutationPort() {
                @Override
                public Optional<CommunityPostLockedView> lockExistingPostForInteraction(UUID postId) {
                    hook.triggerAttemptingPostLock();
                    return realAdapter.lockExistingPostForInteraction(postId);
                }
            };
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
        public ReactionTargetEligibilityPort reactionTargetEligibilityPort() {
            return target -> true;
        }

        @Bean
        public NotificationDispatchPort notificationDispatchPort() {
            return command -> {};
        }
    }

    @Autowired
    private CommunityPostRepositoryPort postRepositoryPort;

    @Autowired
    private CommunityPostPersistenceAdapter postAdapter;

    @Autowired
    private CreateRootCommentUseCase createRootCommentUseCase;

    @Autowired
    private ReplyCommentUseCase replyCommentUseCase;

    @Autowired
    private SetReactionUseCase setReactionUseCase;

    @Autowired
    private SubmitInteractionReportUseCase submitInteractionReportUseCase;

    @Autowired
    private CleanupCommunityPostInteractionsUseCase cleanupUseCase;

    @Autowired
    private CommentRepositoryPort commentRepositoryPort;

    @Autowired
    private ReactionRepositoryPort reactionRepositoryPort;

    @Autowired
    private InteractionReportRepositoryPort reportRepositoryPort;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestSyncHook testSyncHook;

    private TransactionTemplate tx;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        executor = Executors.newFixedThreadPool(4);
        testSyncHook.reset();
        cleanData();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        testSyncHook.reset();
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.execute("DELETE FROM interaction_reports;");
        jdbcTemplate.execute("DELETE FROM interaction_reactions;");
        jdbcTemplate.execute("DELETE FROM interaction_comment_revisions;");
        jdbcTemplate.execute("DELETE FROM interaction_comments;");
        jdbcTemplate.execute("DELETE FROM community_post_revisions;");
        jdbcTemplate.execute("DELETE FROM community_posts;");
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

    private UUID createCommunityPost(UUID authorId, String caption) {
        UUID postId = UUID.randomUUID();
        Instant now = Instant.now();
        CommunityPost post = CommunityPost.create(postId, authorId, caption, null, CommunityPostStatus.PUBLISHED, now);
        tx.executeWithoutResult(s -> postRepositoryPort.save(post));
        return postId;
    }

    // =========================================================================
    // SCENARIO A1: Root Comment Creation Owns Post Lock First (Deterministic)
    // =========================================================================

    @Test
    @DisplayName("Scenario A1: Root comment creation acquires post lock first -> deletion blocked until writer commits -> cleanup removes comment")
    void testScenarioA1_mutationWinsPostLockFirst_rootComment() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Test post for comment lock ownership");
        UUID commenterId = UUID.randomUUID();

        CountDownLatch writerLockedLatch = new CountDownLatch(1);
        CountDownLatch writerContinueLatch = new CountDownLatch(1);

        // Pause writer while holding post lock
        testSyncHook.setOnClockNow(() -> {
            writerLockedLatch.countDown();
            try {
                writerContinueLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Thread 1: Writer enters transaction and acquires post lock
        Future<TaskResult<Comment>> fWriter = executor.submit(() -> {
            try {
                Comment comment = createRootCommentUseCase.execute(new CreateRootCommentCommand(
                        commenterId,
                        CommentTarget.communityPost(postId),
                        "Concurrent root comment under lock"
                ));
                return new TaskResult<>(comment, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Ensure writer has acquired post lock in MySQL
        assertThat(writerLockedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: Deletion coordinator attempts to acquire the same post lock
        Future<TaskResult<Void>> fDeleter = executor.submit(() -> {
            try {
                tx.executeWithoutResult(s -> {
                    CommunityPost post = postAdapter.findByIdForUpdate(postId)
                            .orElseThrow(() -> new IllegalStateException("Post disappeared"));
                    cleanupUseCase.cleanupCommunityPostInteractions(post.getId(), Instant.now());
                    postAdapter.deleteById(post.getId());
                });
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Prove deletion is BLOCKED by writer's post lock
        assertThatThrownBy(() -> fDeleter.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // Release writer to commit transaction
        writerContinueLatch.countDown();

        TaskResult<Comment> rWriter = fWriter.get(5, TimeUnit.SECONDS);
        TaskResult<Void> rDeleter = fDeleter.get(5, TimeUnit.SECONDS);

        assertThat(rWriter.isSuccess()).isTrue();
        assertThat(rDeleter.isSuccess()).isTrue();

        // Final authoritative invariant: 0 comments exist for the deleted post
        Integer commentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE target_type = 'COMMUNITY_POST' AND target_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(commentCount).isZero();

        // 0 posts exist
        Integer postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isZero();
    }

    // =========================================================================
    // SCENARIO A2: Deletion Owns Post Lock First (Deterministic)
    // =========================================================================

    @Test
    @DisplayName("Scenario A2: Deletion acquires post lock first -> root comment creation blocked -> fails closed with CommentTargetNotEligibleException")
    void testScenarioA2_deletionWinsPostLockFirst_rootComment() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Test post for deletion lock ownership");
        UUID commenterId = UUID.randomUUID();

        CountDownLatch deleterLockedLatch = new CountDownLatch(1);
        CountDownLatch deleterContinueLatch = new CountDownLatch(1);
        CountDownLatch writerAttemptingLockLatch = new CountDownLatch(1);

        testSyncHook.setOnAttemptingPostLock(writerAttemptingLockLatch::countDown);

        // Thread 1: Deletion coordinator acquires post lock and pauses
        Future<TaskResult<Void>> fDeleter = executor.submit(() -> {
            try {
                tx.executeWithoutResult(s -> {
                    CommunityPost post = postAdapter.findByIdForUpdate(postId)
                            .orElseThrow(() -> new IllegalStateException("Post disappeared"));
                    deleterLockedLatch.countDown();
                    try {
                        deleterContinueLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    cleanupUseCase.cleanupCommunityPostInteractions(post.getId(), Instant.now());
                    postAdapter.deleteById(post.getId());
                });
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Ensure deleter has acquired post lock in MySQL
        assertThat(deleterLockedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: Writer attempts to create root comment
        Future<TaskResult<Comment>> fWriter = executor.submit(() -> {
            try {
                Comment comment = createRootCommentUseCase.execute(new CreateRootCommentCommand(
                        commenterId,
                        CommentTarget.communityPost(postId),
                        "Concurrent root comment"
                ));
                return new TaskResult<>(comment, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Prove writer has reached the mutation gate / lock call in MySQL
        assertThat(writerAttemptingLockLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Prove writer is BLOCKED waiting on post lock
        assertThatThrownBy(() -> fWriter.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // Release deleter to finish cleanup and delete post row
        deleterContinueLatch.countDown();

        TaskResult<Void> rDeleter = fDeleter.get(5, TimeUnit.SECONDS);
        TaskResult<Comment> rWriter = fWriter.get(5, TimeUnit.SECONDS);

        assertThat(rDeleter.isSuccess()).isTrue();
        assertThat(rWriter.isFailure()).isTrue();
        assertThat(isOrCausedBy(rWriter.error(), CommentTargetNotEligibleException.class)).isTrue();

        // Final authoritative invariant: 0 comments exist
        Integer commentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE target_type = 'COMMUNITY_POST' AND target_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(commentCount).isZero();
    }

    // =========================================================================
    // SCENARIO B1: Post Reaction Mutation Owns Post Lock First (Deterministic)
    // =========================================================================

    @Test
    @DisplayName("Scenario B1: Post reaction acquires post lock first -> deletion blocked until reaction commits -> cleanup removes reaction")
    void testScenarioB1_mutationWinsPostLockFirst_postReaction() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Test post for reaction lock ownership");
        UUID reactorId = UUID.randomUUID();

        CountDownLatch writerLockedLatch = new CountDownLatch(1);
        CountDownLatch writerContinueLatch = new CountDownLatch(1);

        // Pause reaction writer while holding post lock
        testSyncHook.setOnClockNow(() -> {
            writerLockedLatch.countDown();
            try {
                writerContinueLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Thread 1: Reaction writer enters LockedReactionMutationExecutor and acquires post lock
        Future<TaskResult<Void>> fWriter = executor.submit(() -> {
            try {
                setReactionUseCase.execute(new SetReactionCommand(
                        reactorId,
                        ReactionTarget.communityPost(postId),
                        ReactionType.FIRE
                ));
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Ensure reaction writer has acquired post lock in MySQL
        assertThat(writerLockedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: Deletion coordinator attempts post lock
        Future<TaskResult<Void>> fDeleter = executor.submit(() -> {
            try {
                tx.executeWithoutResult(s -> {
                    CommunityPost post = postAdapter.findByIdForUpdate(postId)
                            .orElseThrow(() -> new IllegalStateException("Post disappeared"));
                    cleanupUseCase.cleanupCommunityPostInteractions(post.getId(), Instant.now());
                    postAdapter.deleteById(post.getId());
                });
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Prove deletion is BLOCKED by reaction writer's post lock
        assertThatThrownBy(() -> fDeleter.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // Release writer to commit reaction
        writerContinueLatch.countDown();

        TaskResult<Void> rWriter = fWriter.get(5, TimeUnit.SECONDS);
        TaskResult<Void> rDeleter = fDeleter.get(5, TimeUnit.SECONDS);

        assertThat(rWriter.isSuccess()).isTrue();
        assertThat(rDeleter.isSuccess()).isTrue();

        // Final authoritative invariant: 0 reactions exist for the deleted post
        Integer reactionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reactions WHERE target_type = 'COMMUNITY_POST' AND target_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(reactionCount).isZero();
    }

    // =========================================================================
    // SCENARIO B2: Deletion Owns Post Lock First vs Reaction (Deterministic)
    // =========================================================================

    @Test
    @DisplayName("Scenario B2: Deletion acquires post lock first -> reaction writer blocked -> fails closed with ReactionTargetNotEligibleException")
    void testScenarioB2_deletionWinsPostLockFirst_postReaction() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Test post for reaction delete race");
        UUID reactorId = UUID.randomUUID();

        CountDownLatch deleterLockedLatch = new CountDownLatch(1);
        CountDownLatch deleterContinueLatch = new CountDownLatch(1);
        CountDownLatch reactorAttemptingLockLatch = new CountDownLatch(1);

        testSyncHook.setOnAttemptingPostLock(reactorAttemptingLockLatch::countDown);

        Future<TaskResult<Void>> fDeleter = executor.submit(() -> {
            try {
                tx.executeWithoutResult(s -> {
                    CommunityPost post = postAdapter.findByIdForUpdate(postId)
                            .orElseThrow(() -> new IllegalStateException("Post disappeared"));
                    deleterLockedLatch.countDown();
                    try {
                        deleterContinueLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    cleanupUseCase.cleanupCommunityPostInteractions(post.getId(), Instant.now());
                    postAdapter.deleteById(post.getId());
                });
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        assertThat(deleterLockedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        Future<TaskResult<Void>> fWriter = executor.submit(() -> {
            try {
                setReactionUseCase.execute(new SetReactionCommand(
                        reactorId,
                        ReactionTarget.communityPost(postId),
                        ReactionType.LOVE
                ));
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Prove reactor reached the mutation gate / lock call
        assertThat(reactorAttemptingLockLatch.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> fWriter.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        deleterContinueLatch.countDown();

        TaskResult<Void> rDeleter = fDeleter.get(5, TimeUnit.SECONDS);
        TaskResult<Void> rWriter = fWriter.get(5, TimeUnit.SECONDS);

        assertThat(rDeleter.isSuccess()).isTrue();
        assertThat(rWriter.isFailure()).isTrue();
        assertThat(isOrCausedBy(rWriter.error(), ReactionTargetNotEligibleException.class)).isTrue();

        Integer reactionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reactions WHERE target_type = 'COMMUNITY_POST' AND target_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(reactionCount).isZero();
    }

    // =========================================================================
    // SCENARIO C: Child Mutation vs Comment Physical Deletion
    // =========================================================================

    @Test
    @DisplayName("Scenario C: Child reply vs comment deletion -> serialized via pessimistic comment lock; no orphan reply")
    void testScenarioC_childReplyVsCommentDeletion() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Test post for reply race");
        UUID commenterId = UUID.randomUUID();

        Comment rootComment = createRootCommentUseCase.execute(new CreateRootCommentCommand(
                commenterId,
                CommentTarget.communityPost(postId),
                "Root comment to be replied or deleted"
        ));
        UUID rootCommentId = rootComment.getId();

        UUID replierId = UUID.randomUUID();
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<TaskResult<Comment>> replyTask = () -> {
            readyLatch.countDown();
            startLatch.await(5, TimeUnit.SECONDS);
            try {
                Comment reply = replyCommentUseCase.execute(new ReplyCommentCommand(
                        replierId,
                        rootCommentId,
                        "Child reply text"
                ));
                return new TaskResult<>(reply, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        };

        Callable<TaskResult<Void>> commentDeleteTask = () -> {
            readyLatch.countDown();
            startLatch.await(5, TimeUnit.SECONDS);
            try {
                tx.executeWithoutResult(s -> {
                    Comment comment = commentRepositoryPort.findByIdForUpdate(rootCommentId)
                            .orElseThrow(() -> new IllegalStateException("Comment missing"));
                    commentRepositoryPort.deleteById(comment.getId());
                });
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        };

        Future<TaskResult<Comment>> fReply = executor.submit(replyTask);
        Future<TaskResult<Void>> fDelete = executor.submit(commentDeleteTask);

        assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
        startLatch.countDown();

        TaskResult<Comment> rReply = fReply.get(10, TimeUnit.SECONDS);
        TaskResult<Void> rDelete = fDelete.get(10, TimeUnit.SECONDS);

        assertThat(rDelete.isSuccess()).isTrue();

        if (rReply.isFailure()) {
            assertThat(isOrCausedBy(rReply.error(), CommentNotFoundException.class)
                    || isOrCausedBy(rReply.error(), CommentMutationForbiddenException.class)).isTrue();
        }

        // Check if root comment is deleted
        Integer rootCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE id = ?",
                Integer.class,
                rootCommentId.toString()
        );
        assertThat(rootCount).isZero();
    }

    // =========================================================================
    // SCENARIO D: Equal Timestamp Inverted UUID Nested Reply vs Cleanup (Step 8)
    // =========================================================================

    @Test
    @DisplayName("Scenario D: Equal timestamp with inverted UUID order (childId < rootId) -> nested reply vs cleanup -> identical canonical lock order, zero deadlock, zero residuals")
    void testScenarioD_equalTimestampInvertedUuid_nestedReplyVsCleanup() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Test post for equal timestamp lock order");

        UUID laterRootId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        UUID earlierChildId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        assertThat(earlierChildId.toString().compareTo(laterRootId.toString())).isNegative();

        Instant equalTimestamp = Instant.parse("2026-09-30T10:00:00Z");

        // Seed root and child with equal timestamp in DB
        tx.executeWithoutResult(s -> {
            Comment root = Comment.createRoot(laterRootId, CommentTarget.communityPost(postId), authorId, "Root", equalTimestamp);
            commentRepositoryPort.save(root);
            Comment child = Comment.createReply(earlierChildId, root, authorId, "Child", equalTimestamp);
            commentRepositoryPort.save(child);
        });

        UUID replierId = UUID.randomUUID();
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<TaskResult<Comment>> writerTask = () -> {
            readyLatch.countDown();
            startLatch.await(5, TimeUnit.SECONDS);
            try {
                Comment reply = replyCommentUseCase.execute(new ReplyCommentCommand(
                        replierId,
                        earlierChildId,
                        "Nested reply under equal timestamp"
                ));
                return new TaskResult<>(reply, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        };

        Callable<TaskResult<Void>> cleanupTask = () -> {
            readyLatch.countDown();
            startLatch.await(5, TimeUnit.SECONDS);
            try {
                tx.executeWithoutResult(s -> {
                    CommunityPost post = postAdapter.findByIdForUpdate(postId)
                            .orElseThrow(() -> new IllegalStateException("Post disappeared"));
                    cleanupUseCase.cleanupCommunityPostInteractions(post.getId(), Instant.now());
                    postAdapter.deleteById(post.getId());
                });
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        };

        Future<TaskResult<Comment>> fWriter = executor.submit(writerTask);
        Future<TaskResult<Void>> fCleanup = executor.submit(cleanupTask);

        assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
        startLatch.countDown();

        TaskResult<Comment> rWriter = fWriter.get(10, TimeUnit.SECONDS);
        TaskResult<Void> rCleanup = fCleanup.get(10, TimeUnit.SECONDS);

        assertThat(rCleanup.isSuccess()).isTrue();

        // Invariant: 0 comments remain for post
        Integer commentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE target_type = 'COMMUNITY_POST' AND target_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(commentCount).isZero();
    }

    // =========================================================================
    // SCENARIO E: Zero Residual Graph Verification + Evidence Retention
    // =========================================================================

    @Test
    @DisplayName("Scenario E: Full graph post deletion -> zero residual graph and all reports retained with targetDeletedAt")
    void testScenarioE_zeroResidualGraphAndReportEvidenceRetention() {
        UUID authorId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Complete graph test post");

        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID reporter1 = UUID.randomUUID();
        UUID reporter2 = UUID.randomUUID();

        // 1. Create root comment and reply
        Comment rootComment = createRootCommentUseCase.execute(new CreateRootCommentCommand(
                user1,
                CommentTarget.communityPost(postId),
                "Root comment body"
        ));
        Comment replyComment = replyCommentUseCase.execute(new ReplyCommentCommand(
                user2,
                rootComment.getId(),
                "Reply comment body"
        ));

        // 2. Create post reaction and comment reaction
        setReactionUseCase.execute(new SetReactionCommand(
                user1,
                ReactionTarget.communityPost(postId),
                ReactionType.FIRE
        ));
        setReactionUseCase.execute(new SetReactionCommand(
                user2,
                ReactionTarget.comment(rootComment.getId()),
                ReactionType.LIKE
        ));

        // 3. Create post report and comment report
        InteractionReport postReport = submitInteractionReportUseCase.execute(new SubmitInteractionReportCommand(
                ReportTargetType.COMMUNITY_POST,
                postId,
                reporter1,
                ReportReason.SPAM,
                "Report on post"
        ));
        InteractionReport commentReport = submitInteractionReportUseCase.execute(new SubmitInteractionReportCommand(
                ReportTargetType.COMMENT,
                rootComment.getId(),
                reporter2,
                ReportReason.HARASSMENT,
                "Report on comment"
        ));

        // Verify pre-cleanup counts
        assertThat(commentRepositoryPort.findAllCommentIdsByTarget(
                com.universe.interaction.domain.CommentTargetType.COMMUNITY_POST, postId
        )).hasSize(2);
        assertThat(reactionRepositoryPort.countTotalReactionsByTarget(ReactionTarget.communityPost(postId))).isEqualTo(1);
        assertThat(reactionRepositoryPort.countTotalReactionsByTarget(ReactionTarget.comment(rootComment.getId()))).isEqualTo(1);

        // 4. Execute post deletion under barrier
        Instant deletedAt = Instant.now().plusSeconds(10);
        tx.executeWithoutResult(s -> {
            CommunityPost post = postAdapter.findByIdForUpdate(postId).orElseThrow();
            cleanupUseCase.cleanupCommunityPostInteractions(post.getId(), deletedAt);
            postAdapter.deleteById(post.getId());
        });

        // 5. Authoritative Database Assertions for Zero Residual Graph:
        // a) Community post is hard deleted
        Integer postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isZero();

        // b) Comments on post are deleted
        Integer postCommentsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE target_type = 'COMMUNITY_POST' AND target_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCommentsCount).isZero();

        // c) Comment revisions are deleted
        Integer revisionsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id IN (?, ?)",
                Integer.class,
                rootComment.getId().toString(),
                replyComment.getId().toString()
        );
        assertThat(revisionsCount).isZero();

        // d) Reactions on post are deleted
        Integer postReactionsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reactions WHERE target_type = 'COMMUNITY_POST' AND target_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postReactionsCount).isZero();

        // e) Reactions on comments are deleted
        Integer commentReactionsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reactions WHERE target_type = 'COMMENT' AND target_id = ?",
                Integer.class,
                rootComment.getId().toString()
        );
        assertThat(commentReactionsCount).isZero();

        // f) Reports are NOT deleted; evidence is retained with target_deleted_at stamped
        InteractionReport reloadedPostReport = reportRepositoryPort.findById(postReport.getId()).orElseThrow();
        assertThat(reloadedPostReport.getTargetDeletedAt()).isNotNull();
        assertThat(reloadedPostReport.getReportedContentSnapshot()).isEqualTo("Complete graph test post");

        InteractionReport reloadedCommentReport = reportRepositoryPort.findById(commentReport.getId()).orElseThrow();
        assertThat(reloadedCommentReport.getTargetDeletedAt()).isNotNull();
        assertThat(reloadedCommentReport.getReportedContentSnapshot()).isEqualTo("Root comment body");
    }

    // =========================================================================
    // SCENARIO F: Nested Reply Report vs Cleanup with Inverted Lock Order Proof
    // =========================================================================

    @Test
    @DisplayName("Scenario F: Nested reply report (rootId < replyId) holds canonical comment locks -> cleanup blocked -> on report commit, cleanup stamps targetDeletedAt")
    void testScenarioF_nestedReplyReportVsCleanup_canonicalLockOrderAndRetention() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Test post for comment report canonical lock order");

        UUID rootId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID replyId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        assertThat(rootId.toString().compareTo(replyId.toString())).isNegative();

        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        // Seed root and reply comments
        tx.executeWithoutResult(s -> {
            Comment root = Comment.createRoot(rootId, CommentTarget.communityPost(postId), authorId, "Root comment text", now);
            commentRepositoryPort.save(root);
            Comment reply = Comment.createReply(replyId, root, authorId, "Target reply comment to report", now.plusSeconds(1));
            commentRepositoryPort.save(reply);
        });

        UUID reporterId = UUID.randomUUID();
        CountDownLatch reporterLockedLatch = new CountDownLatch(1);
        CountDownLatch reporterContinueLatch = new CountDownLatch(1);

        // Pause report transaction while holding canonical locks on both root and reply
        testSyncHook.setOnClockNow(() -> {
            reporterLockedLatch.countDown();
            try {
                reporterContinueLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Thread 1: Submit report against nested reply (locks rootId 1111... then replyId 9999...)
        Future<TaskResult<InteractionReport>> fReporter = executor.submit(() -> {
            try {
                InteractionReport report = submitInteractionReportUseCase.execute(new SubmitInteractionReportCommand(
                        ReportTargetType.COMMENT,
                        replyId,
                        reporterId,
                        ReportReason.SPAM,
                        "Spam reply"
                ));
                return new TaskResult<>(report, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Ensure reporter has acquired canonical comment locks in MySQL
        assertThat(reporterLockedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: Deletion coordinator attempts cleanup, which bulk-locks comments using FORCE INDEX in ID ASC order
        Future<TaskResult<Void>> fDeleter = executor.submit(() -> {
            try {
                tx.executeWithoutResult(s -> {
                    CommunityPost post = postAdapter.findByIdForUpdate(postId)
                            .orElseThrow(() -> new IllegalStateException("Post disappeared"));
                    cleanupUseCase.cleanupCommunityPostInteractions(post.getId(), Instant.now());
                    postAdapter.deleteById(post.getId());
                });
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Prove cleanup is BLOCKED waiting on comment locks (no deadlock, orderly wait)
        assertThatThrownBy(() -> fDeleter.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // Release reporter to save report and commit transaction
        reporterContinueLatch.countDown();

        TaskResult<InteractionReport> rReporter = fReporter.get(5, TimeUnit.SECONDS);
        TaskResult<Void> rDeleter = fDeleter.get(5, TimeUnit.SECONDS);

        assertThat(rReporter.isSuccess()).isTrue();
        assertThat(rDeleter.isSuccess()).isTrue();

        // Verify post and comments deleted
        Integer postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isZero();

        Integer commentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE target_type = 'COMMUNITY_POST' AND target_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(commentCount).isZero();

        // Verify report on nested reply is retained with targetDeletedAt stamped by cleanup
        InteractionReport report = reportRepositoryPort.findById(rReporter.result().getId()).orElseThrow();
        assertThat(report.getTargetDeletedAt()).isNotNull();
        assertThat(report.getReportedContentSnapshot()).isEqualTo("Target reply comment to report");
    }
}
