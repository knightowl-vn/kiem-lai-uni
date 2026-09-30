package com.universe.interaction.application;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRankingCandidateDTO;
import com.universe.community.contracts.dto.CommunityPostRevisionPublicDTO;
import com.universe.community.contracts.port.CommunityPostInteractionMutationPort;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.interaction.application.exceptions.DuplicatePendingReportException;
import com.universe.interaction.application.exceptions.SelfReportNotAllowedException;
import com.universe.interaction.application.mutation.CleanupCommunityPostInteractionsUseCase;
import com.universe.interaction.application.mutation.SubmitInteractionReportCommand;
import com.universe.interaction.application.mutation.SubmitInteractionReportUseCase;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.Reaction;
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
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        CommentPersistenceAdapter.class,
        CommentPersistenceMapper.class,
        CommentRevisionPersistenceAdapter.class,
        CommentRevisionPersistenceMapper.class,
        ReactionPersistenceAdapter.class,
        ReactionPersistenceMapper.class,
        InteractionReportPersistenceAdapter.class,
        InteractionReportPersistenceMapper.class,
        CleanupCommunityPostInteractionsUseCase.class,
        CommunityPostInteractionIntegrationTest.TestConfig.class
})
@DisplayName("CommunityPostInteractionIntegrationTest — Full Lifecycle on COMMUNITY_POST")
class CommunityPostInteractionIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.resetTestDatabase("kiemlai_test");
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {

        private static final Map<UUID, CommunityPostPublicDTO> POST_STORE = new ConcurrentHashMap<>();

        public static void registerPost(CommunityPostPublicDTO post) {
            POST_STORE.put(post.id(), post);
        }

        public static void clear() {
            POST_STORE.clear();
        }

        @Bean
        public CommunityPostInteractionMutationPort communityPostInteractionMutationPort() {
            return postId -> {
                CommunityPostPublicDTO post = POST_STORE.get(postId);
                if (post == null) {
                    return Optional.empty();
                }
                return Optional.of(new CommunityPostInteractionMutationPort.CommunityPostLockedView(
                        post.id(),
                        post.authorUserId(),
                        post.caption()
                ));
            };
        }

        @Bean
        public CommunityPostQueryPort communityPostQueryPort() {
            return new CommunityPostQueryPort() {
                @Override
                public Optional<CommunityPostPublicDTO> findPublicPostById(UUID postId) {
                    return Optional.ofNullable(POST_STORE.get(postId));
                }

                @Override
                public List<CommunityPostRevisionPublicDTO> findPublicRevisionHistory(UUID postId) {
                    return List.of();
                }

                @Override
                public List<CommunityPostPublicDTO> findNewestPostsKeyset(
                        java.time.Instant cursorCreatedAt,
                        UUID cursorPostId,
                        int limit
                ) {
                    return List.of();
                }

                @Override
                public List<CommunityPostRankingCandidateDTO> findAllRankingCandidates() {
                    return POST_STORE.values().stream()
                            .map(p -> new CommunityPostRankingCandidateDTO(p.id(), p.createdAt()))
                            .toList();
                }

                @Override
                public List<CommunityPostPublicDTO> findPublicPostsByIds(java.util.Collection<UUID> postIds) {
                    if (postIds == null || postIds.isEmpty()) {
                        return List.of();
                    }
                    return postIds.stream()
                            .map(POST_STORE::get)
                            .filter(java.util.Objects::nonNull)
                            .toList();
                }
            };
        }

        @Bean
        public CommentTargetEligibilityPort commentTargetEligibilityPort(CommunityPostQueryPort queryPort) {
            return target -> {
                if (target == null) return false;
                if (target.type() == com.universe.interaction.domain.CommentTargetType.COMMUNITY_POST) {
                    return queryPort.findPublicPostById(target.targetId()).isPresent();
                }
                return true;
            };
        }

        @Bean
        public IdGeneratorPort idGeneratorPort() {
            return UUID::randomUUID;
        }

        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }

        @Bean
        public SubmitInteractionReportUseCase submitInteractionReportUseCase(
                CommentPersistenceAdapter commentAdapter,
                CommunityPostInteractionMutationPort postMutationPort,
                InteractionReportPersistenceAdapter reportAdapter,
                CommentTargetEligibilityPort eligibilityPort,
                IdGeneratorPort idGen,
                ClockPort clock
        ) {
            return new SubmitInteractionReportUseCase(
                    commentAdapter,
                    postMutationPort,
                    reportAdapter,
                    eligibilityPort,
                    idGen,
                    clock
            );
        }
    }

    @Autowired
    private CommentPersistenceAdapter commentAdapter;

    @Autowired
    private ReactionPersistenceAdapter reactionAdapter;

    @Autowired
    private InteractionReportPersistenceAdapter reportAdapter;

    @Autowired
    private CleanupCommunityPostInteractionsUseCase cleanupUseCase;

    @Autowired
    private SubmitInteractionReportUseCase submitReportUseCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate tx;

    private final UUID postAuthorId = UUID.randomUUID();
    private final UUID commenterId = UUID.randomUUID();
    private final UUID replierId = UUID.randomUUID();
    private final UUID reactorId = UUID.randomUUID();
    private final UUID reporterId = UUID.randomUUID();

    private UUID postId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        postId = UUID.randomUUID();

        CommunityPostPublicDTO postDTO = new CommunityPostPublicDTO(
                postId, postAuthorId, "Post caption content", null, 0,
                Instant.now(), Instant.now()
        );
        TestConfig.registerPost(postDTO);
    }

    @AfterEach
    void tearDown() {
        TestConfig.clear();
        jdbcTemplate.execute("DELETE FROM interaction_reports");
        jdbcTemplate.execute("DELETE FROM interaction_reactions");
        jdbcTemplate.execute("DELETE FROM interaction_comment_revisions");
        jdbcTemplate.execute("DELETE FROM interaction_comments");
    }

    @Test
    @DisplayName("1. Full lifecycle: comment, reply, react, report, and cleanup on COMMUNITY_POST")
    void shouldExecuteFullCommunityPostInteractionLifecycleAndCleanup() {
        Instant now = Instant.now();

        // 1. Create root comment on COMMUNITY_POST
        UUID rootCommentId = UUID.randomUUID();
        Comment rootComment = Comment.createRoot(
                rootCommentId,
                CommentTarget.communityPost(postId),
                commenterId,
                "First comment on community post",
                now
        );
        tx.executeWithoutResult(s -> commentAdapter.save(rootComment));

        // 2. Create reply comment
        UUID replyCommentId = UUID.randomUUID();
        Comment replyComment = Comment.createReply(
                replyCommentId,
                rootComment,
                replierId,
                "Reply to first comment",
                now.plusSeconds(5)
        );
        tx.executeWithoutResult(s -> commentAdapter.save(replyComment));

        // 3. React to post and comment
        Reaction postReaction = Reaction.create(
                UUID.randomUUID(),
                reactorId,
                ReactionTarget.communityPost(postId),
                ReactionType.LIKE,
                now
        );
        tx.executeWithoutResult(s -> reactionAdapter.save(postReaction));

        Reaction commentReaction = Reaction.create(
                UUID.randomUUID(),
                reactorId,
                ReactionTarget.comment(rootCommentId),
                ReactionType.LOVE,
                now
        );
        tx.executeWithoutResult(s -> reactionAdapter.save(commentReaction));

        // 4. Submit report against post
        SubmitInteractionReportCommand postReportCmd = new SubmitInteractionReportCommand(
                ReportTargetType.COMMUNITY_POST,
                postId,
                reporterId,
                ReportReason.SPAM,
                "Spam post content"
        );
        InteractionReport postReport = tx.execute(s -> submitReportUseCase.execute(postReportCmd));
        assertThat(postReport).isNotNull();
        assertThat(postReport.getTargetType()).isEqualTo(ReportTargetType.COMMUNITY_POST);
        assertThat(postReport.getTargetId()).isEqualTo(postId);
        assertThat(postReport.getReportedContentSnapshot()).isEqualTo("Post caption content");

        // 5. Submit report against comment
        SubmitInteractionReportCommand commentReportCmd = new SubmitInteractionReportCommand(
                ReportTargetType.COMMENT,
                rootCommentId,
                reporterId,
                ReportReason.HARASSMENT,
                "Harassing comment"
        );
        InteractionReport commentReport = tx.execute(s -> submitReportUseCase.execute(commentReportCmd));
        assertThat(commentReport).isNotNull();
        assertThat(commentReport.getTargetType()).isEqualTo(ReportTargetType.COMMENT);
        assertThat(commentReport.getTargetId()).isEqualTo(rootCommentId);
        assertThat(commentReport.getReportedContentSnapshot()).isEqualTo("First comment on community post");

        // 6. Execute deterministic interaction cleanup
        Instant deleteTime = now.plusSeconds(30);
        tx.executeWithoutResult(s -> cleanupUseCase.cleanupCommunityPostInteractions(postId, deleteTime));

        // 7. Verify post-cleanup assertions
        // a) Comments deleted
        List<UUID> remainingCommentIds = commentAdapter.findAllCommentIdsByTarget(
                com.universe.interaction.domain.CommentTargetType.COMMUNITY_POST, postId
        );
        assertThat(remainingCommentIds).isEmpty();

        // b) Reactions deleted
        long postReactions = reactionAdapter.countTotalReactionsByTarget(ReactionTarget.communityPost(postId));
        assertThat(postReactions).isZero();
        long commentReactions = reactionAdapter.countTotalReactionsByTarget(ReactionTarget.comment(rootCommentId));
        assertThat(commentReactions).isZero();

        // c) Reports stamped with targetDeletedAt
        InteractionReport reloadedPostReport = reportAdapter.findById(postReport.getId()).orElseThrow();
        assertThat(reloadedPostReport.getTargetDeletedAt()).isNotNull();

        InteractionReport reloadedCommentReport = reportAdapter.findById(commentReport.getId()).orElseThrow();
        assertThat(reloadedCommentReport.getTargetDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("2. Self-reporting post is rejected with SelfReportNotAllowedException")
    void shouldRejectSelfReportingPost() {
        SubmitInteractionReportCommand cmd = new SubmitInteractionReportCommand(
                ReportTargetType.COMMUNITY_POST,
                postId,
                postAuthorId, // author reporting own post
                ReportReason.SPAM,
                "Self report"
        );

        assertThatThrownBy(() -> tx.execute(s -> submitReportUseCase.execute(cmd)))
                .isInstanceOf(SelfReportNotAllowedException.class);
    }

    @Test
    @DisplayName("3. Duplicate pending report on post is rejected with DuplicatePendingReportException")
    void shouldRejectDuplicatePendingReportOnPost() {
        SubmitInteractionReportCommand cmd1 = new SubmitInteractionReportCommand(
                ReportTargetType.COMMUNITY_POST,
                postId,
                reporterId,
                ReportReason.SPAM,
                "First report"
        );
        tx.execute(s -> submitReportUseCase.execute(cmd1));

        SubmitInteractionReportCommand cmd2 = new SubmitInteractionReportCommand(
                ReportTargetType.COMMUNITY_POST,
                postId,
                reporterId,
                ReportReason.HARASSMENT,
                "Second report"
        );
        assertThatThrownBy(() -> tx.execute(s -> submitReportUseCase.execute(cmd2)))
                .isInstanceOf(DuplicatePendingReportException.class);
    }
}
