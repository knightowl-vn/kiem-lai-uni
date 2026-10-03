package com.universe.community.application;

import com.universe.community.application.command.EditCommunityPostCaptionCommand;
import com.universe.community.application.port.out.CommunityPostInteractionCleanupPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRevisionRepositoryPort;
import com.universe.community.application.usecase.DeleteCommunityPostUseCase;
import com.universe.community.application.usecase.EditCommunityPostCaptionUseCase;
import com.universe.community.contracts.port.CommunityPostInteractionMutationPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.exception.CommunityPostPendingReportConflictException;
import com.universe.community.infrastructure.interaction.InteractionCommunityPostCleanupAdapter;
import com.universe.community.infrastructure.interaction.InteractionCommunityPostReportQueryAdapter;
import com.universe.community.infrastructure.persistence.CommunityPostPersistenceAdapter;
import com.universe.community.infrastructure.persistence.CommunityPostPersistenceMapper;
import com.universe.community.infrastructure.persistence.CommunityPostRevisionPersistenceAdapter;
import com.universe.community.infrastructure.persistence.CommunityPostRevisionPersistenceMapper;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.mutation.CleanupCommunityPostInteractionsUseCase;
import com.universe.interaction.application.mutation.CreateRootCommentCommand;
import com.universe.interaction.application.mutation.CreateRootCommentUseCase;
import com.universe.interaction.application.mutation.SubmitInteractionReportCommand;
import com.universe.interaction.application.mutation.SubmitInteractionReportUseCase;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.application.ports.ReactionTargetEligibilityPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
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
import com.universe.media.application.asset.DeleteMediaAssetCommand;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.contracts.dto.ChangeMediaVisibilityRequestDTO;
import com.universe.media.contracts.dto.FindActiveMediaAssetsKeysetQuery;
import com.universe.media.contracts.dto.GenerateImageVariantRequestDTO;
import com.universe.media.contracts.dto.MediaAssetCandidateDTO;
import com.universe.media.contracts.dto.MediaAssetCurrentMetadataDTO;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetVersionContentDTO;
import com.universe.media.contracts.dto.MediaAssetVersionReferenceDTO;
import com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionConditionalResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MediaType;
import com.universe.media.infrastructure.persistence.MediaAssetPersistenceAdapter;
import com.universe.notification.contracts.port.NotificationDispatchPort;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.id.UuidGeneratorAdapter;
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
        "com.universe.community.infrastructure.persistence",
        "com.universe.interaction.infrastructure.persistence",
        "com.universe.media.infrastructure.persistence"
})
@EnableJpaRepositories(basePackages = {
        "com.universe.community.infrastructure.persistence",
        "com.universe.interaction.infrastructure.persistence",
        "com.universe.media.infrastructure.persistence"
})
@Import({
        CommunityPostPersistenceAdapter.class,
        CommunityPostRevisionPersistenceAdapter.class,
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
        MediaAssetPersistenceAdapter.class,
        DeleteMediaAssetUseCase.class,
        CleanupCommunityPostInteractionsUseCase.class,
        InteractionCommunityPostCleanupAdapter.class,
        InteractionCommunityPostReportQueryAdapter.class,
        DeleteCommunityPostUseCase.class,
        EditCommunityPostCaptionUseCase.class,
        CreateRootCommentUseCase.class,
        SubmitInteractionReportUseCase.class,
        UuidGeneratorAdapter.class,
        CommunityPostHardDeleteConcurrencyIntegrationTest.TestConfig.class
})
@DisplayName("CommunityPostHardDeleteConcurrencyIntegrationTest — Delete Concurrency vs Edit and Interaction Mutations")
class CommunityPostHardDeleteConcurrencyIntegrationTest {

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

        @Bean
        public MediaContract testMediaContract(DeleteMediaAssetUseCase deleteMediaAssetUseCase) {
            return new MediaContract() {
                @Override
                public void delete(UUID assetId) {
                    deleteMediaAssetUseCase.execute(new DeleteMediaAssetCommand(assetId));
                }

                @Override
                public UploadMediaAssetResponseDTO uploadAsset(UploadMediaAssetRequestDTO request) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public UploadMediaAssetVersionResponseDTO uploadVersion(UploadMediaAssetVersionRequestDTO request) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public UploadMediaAssetVersionConditionalResponseDTO uploadVersionIfContentChanged(UploadMediaAssetVersionRequestDTO request) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void generateImageVariant(GenerateImageVariantRequestDTO request) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<MediaAssetDetailDTO> getAssetDetail(UUID assetId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<MediaAssetCurrentMetadataDTO> getAssetCurrentMetadata(UUID assetId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<MediaAssetVersionSnapshotDTO> getCurrentVersionSnapshot(UUID assetId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public MediaAssetVersionContentDTO openVersionContent(MediaAssetVersionReferenceDTO reference) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void changeVisibility(ChangeMediaVisibilityRequestDTO request) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void archive(UUID assetId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void restore(UUID assetId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void assignClientTagIfAbsent(UUID assetId, String clientTag) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<MediaAssetCandidateDTO> findActiveAssetsByClientTagKeyset(FindActiveMediaAssetsKeysetQuery query) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeleteCommunityPostUseCase deleteCommunityPostUseCase;

    @Autowired
    private EditCommunityPostCaptionUseCase editCommunityPostCaptionUseCase;

    @Autowired
    private CreateRootCommentUseCase createRootCommentUseCase;

    @Autowired
    private SubmitInteractionReportUseCase submitInteractionReportUseCase;

    @Autowired
    private CommunityPostPersistenceAdapter postAdapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        jdbcTemplate.execute("DELETE FROM media_assets;");
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
        tx.executeWithoutResult(s -> postAdapter.save(post));
        return postId;
    }

    // =========================================================================
    // SCENARIO 1: Caption Edit Wins Post Lock First vs Delete
    // =========================================================================

    @Test
    @DisplayName("Scenario 1: Caption edit acquires post lock first -> delete is blocked until edit commits -> delete removes post and new revision")
    void testScenario1_captionEditWinsPostLockFirst() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Original caption");

        CountDownLatch editorLockedLatch = new CountDownLatch(1);
        CountDownLatch editorContinueLatch = new CountDownLatch(1);

        // Pause editor while holding post lock
        testSyncHook.setOnClockNow(() -> {
            editorLockedLatch.countDown();
            try {
                editorContinueLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Thread 1: Editor starts and acquires post lock
        Future<TaskResult<CommunityPost>> fEditor = executor.submit(() -> {
            try {
                CommunityPost edited = editCommunityPostCaptionUseCase.execute(new EditCommunityPostCaptionCommand(
                        postId,
                        authorId,
                        "Edited caption"
                ));
                return new TaskResult<>(edited, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Ensure editor holds post lock
        assertThat(editorLockedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: Deleter attempts to acquire post lock
        Future<TaskResult<Void>> fDeleter = executor.submit(() -> {
            try {
                deleteCommunityPostUseCase.execute(authorId, postId);
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Prove deleter is BLOCKED by editor's post lock
        assertThatThrownBy(() -> fDeleter.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // Release editor to commit transaction
        editorContinueLatch.countDown();

        TaskResult<CommunityPost> rEditor = fEditor.get(5, TimeUnit.SECONDS);
        TaskResult<Void> rDeleter = fDeleter.get(5, TimeUnit.SECONDS);

        assertThat(rEditor.isSuccess()).isTrue();
        assertThat(rDeleter.isSuccess()).isTrue();

        // Final authoritative state: post and all revisions (including revision 1) are deleted
        int postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isZero();

        int revCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_revisions WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(revCount).isZero();
    }

    // =========================================================================
    // SCENARIO 2: Delete Wins Post Lock First vs Caption Edit
    // =========================================================================

    @Test
    @DisplayName("Scenario 2: Delete acquires post lock first -> caption edit is blocked -> post deleted -> caption edit fails with CommunityPostNotFoundException")
    void testScenario2_deleteWinsPostLockFirst_captionEditFailsClosed() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Post to be deleted");

        CountDownLatch deleterLockedLatch = new CountDownLatch(1);
        CountDownLatch deleterContinueLatch = new CountDownLatch(1);

        // Pause deleter while holding post lock
        testSyncHook.setOnClockNow(() -> {
            deleterLockedLatch.countDown();
            try {
                deleterContinueLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Thread 1: Deleter starts and acquires post lock
        Future<TaskResult<Void>> fDeleter = executor.submit(() -> {
            try {
                deleteCommunityPostUseCase.execute(authorId, postId);
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Ensure deleter holds post lock
        assertThat(deleterLockedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: Editor attempts to acquire post lock
        Future<TaskResult<CommunityPost>> fEditor = executor.submit(() -> {
            try {
                CommunityPost edited = editCommunityPostCaptionUseCase.execute(new EditCommunityPostCaptionCommand(
                        postId,
                        authorId,
                        "Concurrent caption edit"
                ));
                return new TaskResult<>(edited, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Prove editor is BLOCKED by deleter's post lock
        assertThatThrownBy(() -> fEditor.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // Release deleter to complete deletion and commit
        deleterContinueLatch.countDown();

        TaskResult<Void> rDeleter = fDeleter.get(5, TimeUnit.SECONDS);
        TaskResult<CommunityPost> rEditor = fEditor.get(5, TimeUnit.SECONDS);

        assertThat(rDeleter.isSuccess()).isTrue();
        assertThat(rEditor.isFailure()).isTrue();
        assertThat(isOrCausedBy(rEditor.error(), CommunityPostNotFoundException.class)).isTrue();

        // Final authoritative state: post is deleted
        int postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isZero();
    }

    // =========================================================================
    // SCENARIO 3: Delete Wins Post Lock First vs Root Comment Creation
    // =========================================================================

    @Test
    @DisplayName("Scenario 3: Delete acquires post lock first -> root comment creation blocked -> post deleted -> root comment fails closed with CommentTargetNotEligibleException")
    void testScenario3_deleteWinsPostLockFirst_rootCommentFailsClosed() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID commenterId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Post to delete before comment");

        CountDownLatch deleterLockedLatch = new CountDownLatch(1);
        CountDownLatch deleterContinueLatch = new CountDownLatch(1);
        CountDownLatch commenterAttemptingLockLatch = new CountDownLatch(1);

        testSyncHook.setOnAttemptingPostLock(commenterAttemptingLockLatch::countDown);

        // Thread 1: Deleter starts and acquires post lock
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
                    deleteCommunityPostUseCase.execute(authorId, post.getId());
                });
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Ensure deleter holds post lock
        assertThat(deleterLockedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: Commenter attempts to create root comment
        Future<TaskResult<Comment>> fCommenter = executor.submit(() -> {
            try {
                Comment comment = createRootCommentUseCase.execute(new CreateRootCommentCommand(
                        commenterId,
                        CommentTarget.communityPost(postId),
                        "Concurrent root comment attempt"
                ));
                return new TaskResult<>(comment, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Ensure commenter reached lock gate
        assertThat(commenterAttemptingLockLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Prove commenter is BLOCKED
        assertThatThrownBy(() -> fCommenter.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // Release deleter
        deleterContinueLatch.countDown();

        TaskResult<Void> rDeleter = fDeleter.get(5, TimeUnit.SECONDS);
        TaskResult<Comment> rCommenter = fCommenter.get(5, TimeUnit.SECONDS);

        assertThat(rDeleter.isSuccess()).isTrue();
        assertThat(rCommenter.isFailure()).isTrue();
        assertThat(isOrCausedBy(rCommenter.error(), CommentTargetNotEligibleException.class)).isTrue();

        int commentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE target_type = 'COMMUNITY_POST' AND target_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(commentCount).isZero();
    }

    // =========================================================================
    // SCENARIO 4: Report Submission Wins Post Lock First vs Delete (Anti-Evasion Barrier)
    // =========================================================================

    @Test
    @DisplayName("Scenario 4: Report submission acquires post lock first -> delete is blocked -> report commits -> delete unblocks and throws CommunityPostPendingReportConflictException")
    void testScenario4_reportWinsPostLockFirst_deleteThrowsConflict() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Post to report then delete");

        CountDownLatch reportLockedLatch = new CountDownLatch(1);
        CountDownLatch reportContinueLatch = new CountDownLatch(1);

        // Pause report submission while holding post lock
        testSyncHook.setOnClockNow(() -> {
            reportLockedLatch.countDown();
            try {
                reportContinueLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Thread 1: Reporter starts and acquires post lock
        Future<TaskResult<InteractionReport>> fReporter = executor.submit(() -> {
            try {
                InteractionReport report = submitInteractionReportUseCase.execute(new SubmitInteractionReportCommand(
                        ReportTargetType.COMMUNITY_POST,
                        postId,
                        reporterId,
                        ReportReason.SPAM,
                        null
                ));
                return new TaskResult<>(report, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Ensure reporter holds post lock
        assertThat(reportLockedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: Deleter attempts to acquire post lock
        Future<TaskResult<Void>> fDeleter = executor.submit(() -> {
            try {
                deleteCommunityPostUseCase.execute(authorId, postId);
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Prove deleter is BLOCKED by reporter's post lock
        assertThatThrownBy(() -> fDeleter.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // Release reporter to commit pending report
        reportContinueLatch.countDown();

        TaskResult<InteractionReport> rReporter = fReporter.get(5, TimeUnit.SECONDS);
        TaskResult<Void> rDeleter = fDeleter.get(5, TimeUnit.SECONDS);

        assertThat(rReporter.isSuccess()).isTrue();
        assertThat(rDeleter.isFailure()).isTrue();
        assertThat(isOrCausedBy(rDeleter.error(), CommunityPostPendingReportConflictException.class)).isTrue();

        // Post is preserved because delete was blocked by anti-evasion barrier
        int postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isEqualTo(1);

        // Pending report is recorded
        int reportCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reports WHERE target_type = 'COMMUNITY_POST' AND target_id = ? AND status = 'PENDING'",
                Integer.class,
                postId.toString()
        );
        assertThat(reportCount).isEqualTo(1);
    }

    // =========================================================================
    // SCENARIO 5: Delete Wins Post Lock First vs Report Submission
    // =========================================================================

    @Test
    @DisplayName("Scenario 5: Delete acquires post lock first -> report submission is blocked -> post deleted -> report fails closed with CommentTargetNotEligibleException")
    void testScenario5_deleteWinsPostLockFirst_reportFailsClosed() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID postId = createCommunityPost(authorId, "Post to delete before report");

        CountDownLatch deleterLockedLatch = new CountDownLatch(1);
        CountDownLatch deleterContinueLatch = new CountDownLatch(1);
        CountDownLatch reporterAttemptingLockLatch = new CountDownLatch(1);

        testSyncHook.setOnAttemptingPostLock(reporterAttemptingLockLatch::countDown);

        // Thread 1: Deleter starts and acquires post lock
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
                    deleteCommunityPostUseCase.execute(authorId, post.getId());
                });
                return new TaskResult<>(null, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Ensure deleter holds post lock
        assertThat(deleterLockedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: Reporter attempts to submit report
        Future<TaskResult<InteractionReport>> fReporter = executor.submit(() -> {
            try {
                InteractionReport report = submitInteractionReportUseCase.execute(new SubmitInteractionReportCommand(
                        ReportTargetType.COMMUNITY_POST,
                        postId,
                        reporterId,
                        ReportReason.HARASSMENT,
                        null
                ));
                return new TaskResult<>(report, null);
            } catch (Throwable t) {
                return new TaskResult<>(null, t);
            }
        });

        // Ensure reporter reached lock gate
        assertThat(reporterAttemptingLockLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Prove reporter is BLOCKED by deleter's post lock
        assertThatThrownBy(() -> fReporter.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // Release deleter
        deleterContinueLatch.countDown();

        TaskResult<Void> rDeleter = fDeleter.get(5, TimeUnit.SECONDS);
        TaskResult<InteractionReport> rReporter = fReporter.get(5, TimeUnit.SECONDS);

        assertThat(rDeleter.isSuccess()).isTrue();
        assertThat(rReporter.isFailure()).isTrue();
        assertThat(isOrCausedBy(rReporter.error(), CommentTargetNotEligibleException.class)).isTrue();

        // Post is deleted
        int postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isZero();

        // No reports created
        int reportCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reports WHERE target_type = 'COMMUNITY_POST' AND target_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(reportCount).isZero();
    }
}
