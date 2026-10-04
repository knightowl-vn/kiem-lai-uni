package com.universe.community.infrastructure.persistence;

import com.universe.community.application.port.out.CommunityPostInteractionCleanupPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRevisionRepositoryPort;
import com.universe.community.application.usecase.DeleteCommunityPostUseCase;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.CommunityPostRevision;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.exception.CommunityPostUnauthorizedException;
import com.universe.community.infrastructure.interaction.InteractionCommunityPostCleanupAdapter;
import com.universe.community.infrastructure.interaction.InteractionCommunityPostReportQueryAdapter;
import com.universe.interaction.application.mutation.CleanupCommunityPostInteractionsUseCase;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.application.ports.ReactionTargetEligibilityPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.community.domain.exception.CommunityPostPendingReportConflictException;
import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
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
import com.universe.media.application.ports.MediaAssetRepositoryPort;
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
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.infrastructure.persistence.MediaAssetPersistenceAdapter;
import com.universe.notification.contracts.port.NotificationDispatchPort;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.shared.time.SystemClockAdapter;
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
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
        UuidGeneratorAdapter.class,
        SystemClockAdapter.class,
        CommunityPostHardDeleteTransactionIntegrationTest.TestConfig.class
})
@DisplayName("CommunityPostHardDeleteTransactionIntegrationTest — Real MySQL Multi-Context Transaction & Rollback Integration Tests")
class CommunityPostHardDeleteTransactionIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.resetTestDatabase("kiemlai_test");
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    static class TestControlHooks {
        private final AtomicBoolean failInteractionCleanup = new AtomicBoolean(false);
        private final AtomicBoolean failMediaDelete = new AtomicBoolean(false);
        private final AtomicBoolean failCommunityDelete = new AtomicBoolean(false);

        private final AtomicBoolean interactionCleanupCalled = new AtomicBoolean(false);
        private final AtomicBoolean mediaDeleteCalled = new AtomicBoolean(false);
        private final AtomicBoolean communityDeleteCalled = new AtomicBoolean(false);

        public void reset() {
            failInteractionCleanup.set(false);
            failMediaDelete.set(false);
            failCommunityDelete.set(false);
            interactionCleanupCalled.set(false);
            mediaDeleteCalled.set(false);
            communityDeleteCalled.set(false);
        }

        public boolean isFailInteractionCleanup() {
            return failInteractionCleanup.get();
        }

        public void setFailInteractionCleanup(boolean fail) {
            this.failInteractionCleanup.set(fail);
        }

        public boolean isFailMediaDelete() {
            return failMediaDelete.get();
        }

        public void setFailMediaDelete(boolean fail) {
            this.failMediaDelete.set(fail);
        }

        public boolean isFailCommunityDelete() {
            return failCommunityDelete.get();
        }

        public void setFailCommunityDelete(boolean fail) {
            this.failCommunityDelete.set(fail);
        }

        public boolean wasInteractionCleanupCalled() {
            return interactionCleanupCalled.get();
        }

        public void markInteractionCleanupCalled() {
            this.interactionCleanupCalled.set(true);
        }

        public boolean wasMediaDeleteCalled() {
            return mediaDeleteCalled.get();
        }

        public void markMediaDeleteCalled() {
            this.mediaDeleteCalled.set(true);
        }

        public boolean wasCommunityDeleteCalled() {
            return communityDeleteCalled.get();
        }

        public void markCommunityDeleteCalled() {
            this.communityDeleteCalled.set(true);
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        public TestControlHooks testControlHooks() {
            return new TestControlHooks();
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
        @Primary
        public CommunityPostInteractionCleanupPort testInteractionCleanupPort(
                InteractionCommunityPostCleanupAdapter realAdapter,
                TestControlHooks hooks
        ) {
            return (postId, deletedAt) -> {
                hooks.markInteractionCleanupCalled();
                if (hooks.isFailInteractionCleanup()) {
                    throw new RuntimeException("Simulated Interaction cleanup failure during transaction");
                }
                realAdapter.cleanupCommunityPostInteractions(postId, deletedAt);
            };
        }

        @Bean
        @Primary
        public MediaContract testMediaContract(
                DeleteMediaAssetUseCase deleteMediaAssetUseCase,
                TestControlHooks hooks
        ) {
            return new MediaContract() {
                @Override
                public void delete(UUID assetId) {
                    hooks.markMediaDeleteCalled();
                    if (hooks.isFailMediaDelete()) {
                        throw new RuntimeException("Simulated Media delete failure during transaction");
                    }
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

        @Bean
        @Primary
        public CommunityPostRepositoryPort testCommunityPostRepositoryPort(
                CommunityPostPersistenceAdapter realAdapter,
                TestControlHooks hooks
        ) {
            return new CommunityPostRepositoryPort() {
                @Override
                public CommunityPost save(CommunityPost post) {
                    return realAdapter.save(post);
                }

                @Override
                public Optional<CommunityPost> findById(UUID postId) {
                    return realAdapter.findById(postId);
                }

                @Override
                public Optional<CommunityPost> findByIdForUpdate(UUID postId) {
                    return realAdapter.findByIdForUpdate(postId);
                }

                @Override
                public boolean existsById(UUID postId) {
                    return realAdapter.existsById(postId);
                }

                @Override
                public void deleteById(UUID postId) {
                    hooks.markCommunityDeleteCalled();
                    if (hooks.isFailCommunityDelete()) {
                        throw new RuntimeException("Simulated Community delete failure during transaction");
                    }
                    realAdapter.deleteById(postId);
                }

                @Override
                public List<CommunityPost> findByIdIn(Collection<UUID> postIds) {
                    return realAdapter.findByIdIn(postIds);
                }

                @Override
                public CommunityPostPage findPendingReviewPosts(int page, int size) {
                    return realAdapter.findPendingReviewPosts(page, size);
                }

                @Override
                public CommunityPostPage findHiddenPosts(int page, int size) {
                    return realAdapter.findHiddenPosts(page, size);
                }
            };
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeleteCommunityPostUseCase deleteCommunityPostUseCase;

    @Autowired
    private CommunityPostPersistenceAdapter postAdapter;

    @Autowired
    private CommunityPostRevisionPersistenceAdapter revisionAdapter;

    @Autowired
    private MediaAssetPersistenceAdapter mediaAssetAdapter;

    @Autowired
    private CommentPersistenceAdapter commentPersistenceAdapter;

    @Autowired
    private ReactionPersistenceAdapter reactionPersistenceAdapter;

    @Autowired
    private InteractionReportPersistenceAdapter interactionReportPersistenceAdapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestControlHooks testControlHooks;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        testControlHooks.reset();
        cleanData();
    }

    @AfterEach
    void tearDown() {
        testControlHooks.reset();
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.execute("DELETE FROM interaction_reports;");
        jdbcTemplate.execute("DELETE FROM interaction_reactions;");
        jdbcTemplate.execute("DELETE FROM interaction_comment_revisions;");
        jdbcTemplate.execute("DELETE FROM interaction_comments;");
        jdbcTemplate.execute("DELETE FROM community_post_revisions;");
        jdbcTemplate.execute("DELETE FROM community_posts;");
        jdbcTemplate.execute("DELETE FROM media_image_variants;");
        jdbcTemplate.execute("DELETE FROM media_asset_versions;");
        jdbcTemplate.execute("DELETE FROM media_assets;");
    }

    private UUID seedCommunityPost(UUID authorId, String caption, UUID imageMediaAssetId) {
        UUID postId = UUID.randomUUID();
        Instant now = Instant.now().minus(1, ChronoUnit.HOURS);
        CommunityPost post = CommunityPost.create(postId, authorId, caption, imageMediaAssetId, CommunityPostStatus.PUBLISHED, now);
        tx.executeWithoutResult(s -> postAdapter.save(post));
        return postId;
    }

    private void seedPostRevisions(UUID postId, UUID authorId) {
        Instant now = Instant.now();
        CommunityPostRevision rev1 = new CommunityPostRevision(
                UUID.randomUUID(),
                postId,
                1,
                authorId,
                "Initial caption",
                "Edited caption 1",
                now.minus(30, ChronoUnit.MINUTES)
        );
        CommunityPostRevision rev2 = new CommunityPostRevision(
                UUID.randomUUID(),
                postId,
                2,
                authorId,
                "Edited caption 1",
                "Final caption",
                now.minus(10, ChronoUnit.MINUTES)
        );
        tx.executeWithoutResult(s -> {
            revisionAdapter.save(rev1);
            revisionAdapter.save(rev2);
        });
    }

    private UUID seedActiveMediaAsset() {
        UUID assetId = UUID.randomUUID();
        Instant now = Instant.now();
        MediaAsset asset = MediaAsset.registerInitial(
                assetId,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                "community.post.image",
                now
        );
        tx.executeWithoutResult(s -> mediaAssetAdapter.save(asset));
        return assetId;
    }

    // =========================================================================
    // 1. SUCCESSFUL CAPTION-ONLY POST HARD-DELETE + CASCADE & EVIDENCE RETENTION
    // =========================================================================

    @Test
    @DisplayName("Should successfully hard-delete caption-only post: purges post, cascades revisions, cleans interaction graph, retains report evidence")
    void shouldSuccessfullyHardDeleteCaptionOnlyPost() {
        UUID authorId = UUID.randomUUID();
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID reporter1 = UUID.randomUUID();
        UUID reporter2 = UUID.randomUUID();

        UUID postId = seedCommunityPost(authorId, "Caption-only post to delete", null);
        seedPostRevisions(postId, authorId);

        // Add comments, reactions, and reports
        UUID rootCommentId = UUID.randomUUID();
        Comment rootComment = Comment.createRoot(
                rootCommentId,
                CommentTarget.communityPost(postId),
                user1,
                "Root comment on post",
                Instant.now()
        );
        tx.executeWithoutResult(s -> commentPersistenceAdapter.save(rootComment));

        UUID replyCommentId = UUID.randomUUID();
        Comment replyComment = Comment.createReply(
                replyCommentId,
                rootComment,
                user2,
                "Reply to root comment",
                Instant.now().plusSeconds(1)
        );
        tx.executeWithoutResult(s -> commentPersistenceAdapter.save(replyComment));

        Reaction postReaction = Reaction.create(
                UUID.randomUUID(),
                user1,
                ReactionTarget.communityPost(postId),
                ReactionType.FIRE,
                Instant.now()
        );
        tx.executeWithoutResult(s -> reactionPersistenceAdapter.save(postReaction));

        Reaction commentReaction = Reaction.create(
                UUID.randomUUID(),
                user2,
                ReactionTarget.comment(rootCommentId),
                ReactionType.LIKE,
                Instant.now()
        );
        tx.executeWithoutResult(s -> reactionPersistenceAdapter.save(commentReaction));

        InteractionReport postReport = InteractionReport.reconstitute(
                UUID.randomUUID(),
                ReportTargetType.COMMUNITY_POST,
                postId,
                reporter1,
                ReportReason.SPAM,
                "Spam post",
                "Caption-only post to delete",
                null,
                ReportStatus.RESOLVED_NO_ACTION,
                Instant.now(),
                UUID.randomUUID(),
                Instant.now(),
                ReportModerationAction.NO_ACTION,
                null
        );
        tx.executeWithoutResult(s -> interactionReportPersistenceAdapter.save(postReport));

        InteractionReport commentReport = InteractionReport.createPending(
                UUID.randomUUID(),
                ReportTargetType.COMMENT,
                rootCommentId,
                reporter2,
                ReportReason.HARASSMENT,
                "Offensive comment",
                "Root comment on post",
                null,
                Instant.now()
        );
        tx.executeWithoutResult(s -> interactionReportPersistenceAdapter.save(commentReport));

        // Execute hard delete via proxied use case bean
        deleteCommunityPostUseCase.execute(authorId, postId);

        // Verify outside transaction:
        // A. Post is hard-deleted from DB
        int postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isZero();

        // B. Revisions are cascaded and deleted (FK ON DELETE CASCADE)
        int revisionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_revisions WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(revisionCount).isZero();

        // C. Interaction comments and reactions are completely purged
        int commentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE target_type = 'COMMUNITY_POST' AND target_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(commentCount).isZero();

        int reactionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reactions WHERE (target_type = 'COMMUNITY_POST' AND target_id = ?) OR (target_type = 'COMMENT' AND target_id IN (?, ?))",
                Integer.class,
                postId.toString(),
                rootCommentId.toString(),
                replyCommentId.toString()
        );
        assertThat(reactionCount).isZero();

        // D. Interaction reports are RETAINED with target_deleted_at stamped
        List<Map<String, Object>> postReports = jdbcTemplate.queryForList(
                "SELECT id, target_id, target_deleted_at FROM interaction_reports WHERE target_type = 'COMMUNITY_POST' AND target_id = ?",
                postId.toString()
        );
        assertThat(postReports).hasSize(1);
        assertThat(postReports.get(0).get("target_deleted_at")).isNotNull();

        List<Map<String, Object>> commentReports = jdbcTemplate.queryForList(
                "SELECT id, target_id, target_deleted_at FROM interaction_reports WHERE target_type = 'COMMENT' AND target_id = ?",
                rootCommentId.toString()
        );
        assertThat(commentReports).hasSize(1);
        assertThat(commentReports.get(0).get("target_deleted_at")).isNotNull();

        // E. Media delete was not called for caption-only post
        assertThat(testControlHooks.wasMediaDeleteCalled()).isFalse();
    }

    // =========================================================================
    // 2. SUCCESSFUL IMAGE POST HARD-DELETE + MEDIA DELETED TRANSITION
    // =========================================================================

    @Test
    @DisplayName("Should successfully hard-delete image post: purges post and transitions attached Media to DELETED")
    void shouldSuccessfullyHardDeleteImagePostAndMarkMediaDeleted() {
        UUID authorId = UUID.randomUUID();
        UUID imageAssetId = seedActiveMediaAsset();
        UUID postId = seedCommunityPost(authorId, "Post with attached image", imageAssetId);

        // Execute hard delete
        deleteCommunityPostUseCase.execute(authorId, postId);

        // Verify outside transaction:
        // A. Post is deleted
        int postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isZero();

        // B. Media asset row is transitioned to DELETED
        Map<String, Object> mediaRow = jdbcTemplate.queryForMap(
                "SELECT status FROM media_assets WHERE id = ?",
                imageAssetId.toString()
        );
        assertThat(mediaRow.get("status")).isEqualTo("DELETED");
        assertThat(testControlHooks.wasMediaDeleteCalled()).isTrue();
    }

    // =========================================================================
    // 3. ROLLBACK ON INTERACTION CLEANUP FAILURE
    // =========================================================================

    @Test
    @DisplayName("Should rollback entire transaction when Interaction cleanup fails: post and revisions remain, media remains ACTIVE")
    void shouldRollbackWhenInteractionCleanupFails() {
        UUID authorId = UUID.randomUUID();
        UUID imageAssetId = seedActiveMediaAsset();
        UUID postId = seedCommunityPost(authorId, "Post to test interaction rollback", imageAssetId);
        seedPostRevisions(postId, authorId);

        testControlHooks.setFailInteractionCleanup(true);

        assertThatThrownBy(() -> deleteCommunityPostUseCase.execute(authorId, postId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated Interaction cleanup failure during transaction");

        // Verify outside transaction:
        // A. Post is NOT deleted
        int postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isEqualTo(1);

        // B. Revisions are intact
        int revCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_revisions WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(revCount).isEqualTo(2);

        // C. Media asset remains ACTIVE (Media delete was not reached)
        Map<String, Object> mediaRow = jdbcTemplate.queryForMap(
                "SELECT status FROM media_assets WHERE id = ?",
                imageAssetId.toString()
        );
        assertThat(mediaRow.get("status")).isEqualTo("ACTIVE");
        assertThat(testControlHooks.wasMediaDeleteCalled()).isFalse();
    }

    // =========================================================================
    // 4. ROLLBACK ON MEDIA DELETE FAILURE
    // =========================================================================

    @Test
    @DisplayName("Should rollback entire transaction when Media delete fails: post remains, interaction DB mutations rolled back")
    void shouldRollbackWhenMediaDeleteFails() {
        UUID authorId = UUID.randomUUID();
        UUID commenterId = UUID.randomUUID();
        UUID imageAssetId = seedActiveMediaAsset();
        UUID postId = seedCommunityPost(authorId, "Post to test media rollback", imageAssetId);

        UUID commentId = UUID.randomUUID();
        Comment rootComment = Comment.createRoot(
                commentId,
                CommentTarget.communityPost(postId),
                commenterId,
                "Comment that should survive media failure rollback",
                Instant.now()
        );
        tx.executeWithoutResult(s -> commentPersistenceAdapter.save(rootComment));

        testControlHooks.setFailMediaDelete(true);

        assertThatThrownBy(() -> deleteCommunityPostUseCase.execute(authorId, postId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated Media delete failure during transaction");

        // Verify outside transaction:
        // A. Post is NOT deleted
        int postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isEqualTo(1);

        // B. Comment is NOT deleted (Interaction cleanup DB changes rolled back)
        int commentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE id = ?",
                Integer.class,
                commentId.toString()
        );
        assertThat(commentCount).isEqualTo(1);

        // C. Media asset remains ACTIVE
        Map<String, Object> mediaRow = jdbcTemplate.queryForMap(
                "SELECT status FROM media_assets WHERE id = ?",
                imageAssetId.toString()
        );
        assertThat(mediaRow.get("status")).isEqualTo("ACTIVE");
    }

    // =========================================================================
    // 5. ROLLBACK ON COMMUNITY DELETE FAILURE
    // =========================================================================

    @Test
    @DisplayName("Should rollback entire transaction when Community delete fails: post remains, interaction and media rolled back")
    void shouldRollbackWhenCommunityDeleteFails() {
        UUID authorId = UUID.randomUUID();
        UUID commenterId = UUID.randomUUID();
        UUID imageAssetId = seedActiveMediaAsset();
        UUID postId = seedCommunityPost(authorId, "Post to test community delete failure", imageAssetId);

        UUID commentId = UUID.randomUUID();
        Comment rootComment = Comment.createRoot(
                commentId,
                CommentTarget.communityPost(postId),
                commenterId,
                "Comment that should survive community delete failure rollback",
                Instant.now()
        );
        tx.executeWithoutResult(s -> commentPersistenceAdapter.save(rootComment));

        testControlHooks.setFailCommunityDelete(true);

        assertThatThrownBy(() -> deleteCommunityPostUseCase.execute(authorId, postId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated Community delete failure during transaction");

        // Verify outside transaction:
        // A. Post is NOT deleted
        int postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isEqualTo(1);

        // B. Comment is NOT deleted (Interaction cleanup rolled back)
        int commentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE id = ?",
                Integer.class,
                commentId.toString()
        );
        assertThat(commentCount).isEqualTo(1);

        // C. Media asset remains ACTIVE (Media DELETED transition rolled back)
        Map<String, Object> mediaRow = jdbcTemplate.queryForMap(
                "SELECT status FROM media_assets WHERE id = ?",
                imageAssetId.toString()
        );
        assertThat(mediaRow.get("status")).isEqualTo("ACTIVE");
    }

    // =========================================================================
    // 6. AUTHORIZATION AND NOT FOUND CHECKS
    // =========================================================================

    @Test
    @DisplayName("Should throw CommunityPostUnauthorizedException when stranger attempts deletion: 0 DB mutations")
    void shouldRejectStrangerDeletion() {
        UUID authorId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        UUID postId = seedCommunityPost(authorId, "Author post", null);

        assertThatThrownBy(() -> deleteCommunityPostUseCase.execute(strangerId, postId))
                .isInstanceOf(CommunityPostUnauthorizedException.class);

        int postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Should throw CommunityPostNotFoundException when post does not exist")
    void shouldThrowNotFoundForNonExistentPost() {
        UUID randomPostId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> deleteCommunityPostUseCase.execute(actorId, randomPostId))
                .isInstanceOf(CommunityPostNotFoundException.class);
    }

    // =========================================================================
    // 7. REPEATED DELETE CONTRACT & EVIDENCE RETENTION INTEGRATION TEST
    // =========================================================================

    @Test
    @DisplayName("Repeated Delete: First delete succeeds (204), second delete throws NotFound (404), zero second-delete side effects, reports retained intact")
    void shouldHandleRepeatedDeleteWithRetainedReportsIntact() {
        UUID authorId = UUID.randomUUID();
        UUID commenterId = UUID.randomUUID();
        UUID replyAuthorId = UUID.randomUUID();
        UUID reporter1 = UUID.randomUUID();
        UUID reporter2 = UUID.randomUUID();
        UUID imageAssetId = seedActiveMediaAsset();
        UUID postId = seedCommunityPost(authorId, "Post to test repeated delete", imageAssetId);
        seedPostRevisions(postId, authorId);

        // Seed comments, replies, reactions, reports
        UUID rootCommentId = UUID.randomUUID();
        Comment rootComment = Comment.createRoot(
                rootCommentId,
                CommentTarget.communityPost(postId),
                commenterId,
                "Root comment for repeated delete test",
                Instant.now()
        );
        tx.executeWithoutResult(s -> commentPersistenceAdapter.save(rootComment));

        UUID replyCommentId = UUID.randomUUID();
        Comment replyComment = Comment.createReply(
                replyCommentId,
                rootComment,
                replyAuthorId,
                "Reply comment for repeated delete test",
                Instant.now().plusSeconds(1)
        );
        tx.executeWithoutResult(s -> commentPersistenceAdapter.save(replyComment));

        Reaction postReaction = Reaction.create(
                UUID.randomUUID(),
                commenterId,
                ReactionTarget.communityPost(postId),
                ReactionType.FIRE,
                Instant.now()
        );
        tx.executeWithoutResult(s -> reactionPersistenceAdapter.save(postReaction));

        Reaction commentReaction = Reaction.create(
                UUID.randomUUID(),
                replyAuthorId,
                ReactionTarget.comment(rootCommentId),
                ReactionType.LIKE,
                Instant.now()
        );
        tx.executeWithoutResult(s -> reactionPersistenceAdapter.save(commentReaction));

        InteractionReport postReport = InteractionReport.reconstitute(
                UUID.randomUUID(),
                ReportTargetType.COMMUNITY_POST,
                postId,
                reporter1,
                ReportReason.SPAM,
                "Spam post report",
                "Post to test repeated delete",
                null,
                ReportStatus.RESOLVED_NO_ACTION,
                Instant.now(),
                UUID.randomUUID(),
                Instant.now(),
                ReportModerationAction.NO_ACTION,
                null
        );
        tx.executeWithoutResult(s -> interactionReportPersistenceAdapter.save(postReport));

        InteractionReport commentReport = InteractionReport.createPending(
                UUID.randomUUID(),
                ReportTargetType.COMMENT,
                rootCommentId,
                reporter2,
                ReportReason.HARASSMENT,
                "Harassment comment report",
                "Root comment for repeated delete test",
                null,
                Instant.now()
        );
        tx.executeWithoutResult(s -> interactionReportPersistenceAdapter.save(commentReport));

        // 1. First delete execution
        deleteCommunityPostUseCase.execute(authorId, postId);

        // Verify state after first delete:
        // A. Post is deleted
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM community_posts WHERE id = ?", Integer.class, postId.toString())).isZero();
        // B. Revisions are cascaded and deleted
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM community_post_revisions WHERE post_id = ?", Integer.class, postId.toString())).isZero();
        // C. Comments & reactions purged
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_comments WHERE target_type = 'COMMUNITY_POST' AND target_id = ?", Integer.class, postId.toString())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_reactions WHERE target_type = 'COMMUNITY_POST' AND target_id = ?", Integer.class, postId.toString())).isZero();
        // D. Media asset status is DELETED
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM media_assets WHERE id = ?", String.class, imageAssetId.toString())).isEqualTo("DELETED");

        // E. Retained reports have target_deleted_at stamped
        Map<String, Object> postReportRow1 = jdbcTemplate.queryForMap(
                "SELECT id, target_deleted_at FROM interaction_reports WHERE id = ?",
                postReport.getId().toString()
        );
        Object originalPostDeletedAt = postReportRow1.get("target_deleted_at");
        assertThat(originalPostDeletedAt).isNotNull();

        Map<String, Object> commentReportRow1 = jdbcTemplate.queryForMap(
                "SELECT id, target_deleted_at FROM interaction_reports WHERE id = ?",
                commentReport.getId().toString()
        );
        Object originalCommentDeletedAt = commentReportRow1.get("target_deleted_at");
        assertThat(originalCommentDeletedAt).isNotNull();

        // Reset control hooks before second delete attempt
        testControlHooks.reset();

        // 2. Second delete execution on the exact same post
        assertThatThrownBy(() -> deleteCommunityPostUseCase.execute(authorId, postId))
                .isInstanceOf(CommunityPostNotFoundException.class)
                .hasMessageContaining(postId.toString());

        // Verify state after second delete:
        // A. Zero invocations to downstream cleanup ports during second delete
        assertThat(testControlHooks.wasInteractionCleanupCalled()).isFalse();
        assertThat(testControlHooks.wasMediaDeleteCalled()).isFalse();
        assertThat(testControlHooks.wasCommunityDeleteCalled()).isFalse();

        // B. Media status is still DELETED
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM media_assets WHERE id = ?", String.class, imageAssetId.toString())).isEqualTo("DELETED");

        // C. Retained reports remain perfectly preserved and timestamps are unchanged
        Map<String, Object> postReportRow2 = jdbcTemplate.queryForMap(
                "SELECT id, target_deleted_at FROM interaction_reports WHERE id = ?",
                postReport.getId().toString()
        );
        assertThat(postReportRow2.get("target_deleted_at")).isEqualTo(originalPostDeletedAt);

        Map<String, Object> commentReportRow2 = jdbcTemplate.queryForMap(
                "SELECT id, target_deleted_at FROM interaction_reports WHERE id = ?",
                commentReport.getId().toString()
        );
        assertThat(commentReportRow2.get("target_deleted_at")).isEqualTo(originalCommentDeletedAt);
    }

    @Test
    @DisplayName("Anti-evasion barrier: Post with pending abuse report cannot be deleted -> throws CommunityPostPendingReportConflictException and post is preserved")
    void deletePost_withPendingPostReport_throwsPendingReportConflictException() {
        UUID authorId = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        UUID postId = seedCommunityPost(authorId, "Post under pending investigation", null);

        InteractionReport pendingReport = InteractionReport.createPending(
                UUID.randomUUID(),
                ReportTargetType.COMMUNITY_POST,
                postId,
                reporter,
                ReportReason.HARASSMENT,
                "Investigating harassment",
                "Post under pending investigation",
                null,
                Instant.now()
        );
        tx.executeWithoutResult(s -> interactionReportPersistenceAdapter.save(pendingReport));

        assertThatThrownBy(() -> deleteCommunityPostUseCase.execute(authorId, postId))
                .isInstanceOf(CommunityPostPendingReportConflictException.class)
                .hasMessageContaining(postId.toString());

        // Verify post still exists in DB
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(count).isEqualTo(1);
    }
}
