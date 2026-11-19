package com.universe.interaction.entry.community;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.identity.application.ports.UserPublicProfileQueryPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.application.query.UserIdentityQueryService;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentSlice;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.application.query.CommentThreadVisibilityResolver;
import com.universe.interaction.application.query.CountVisibleActiveRepliesByRootIdsUseCase;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.interaction.entry.community.dto.CommunityDiscussionFeedResponseDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spring AOP proxy and transaction boundary integration test for Community roots-only feed
 * and single-thread discussion reads coordinated through {@link DiscussionCoreReadService}.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CommunityDiscussionTransactionIntegrationTest.TestConfig.class)
@DisplayName("CommunityDiscussionTransactionIntegrationTest — Spring AOP & Two-Phase Transaction Isolation")
class CommunityDiscussionTransactionIntegrationTest {

    private static final UUID POST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VIEWER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_AUTHOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REPLY_AUTHOR_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ROOT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID REPLY_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        private final Connection coreConnection = mock(Connection.class);
        private final Connection reactionConnection = mock(Connection.class);
        private final DataSource dataSource = mock(DataSource.class);
        private final CommunityPostQueryPort communityPostQueryPort = mock(CommunityPostQueryPort.class);
        private final CommentRepositoryPort commentRepositoryPort = mock(CommentRepositoryPort.class);
        private final UserRepositoryPort userRepositoryPort = mock(UserRepositoryPort.class);
        private final UserPublicProfileQueryPort userPublicProfileQueryPort = mock(UserPublicProfileQueryPort.class);
        private final ReactionRepositoryPort reactionRepositoryPort = mock(ReactionRepositoryPort.class);

        @Bean("coreConnection")
        public Connection coreConnection() {
            return coreConnection;
        }

        @Bean("reactionConnection")
        public Connection reactionConnection() {
            return reactionConnection;
        }

        @Bean
        public DataSource dataSource() {
            return dataSource;
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public CommunityPostQueryPort communityPostQueryPort() {
            return communityPostQueryPort;
        }

        @Bean
        public CommentRepositoryPort commentRepositoryPort() {
            return commentRepositoryPort;
        }

        @Bean
        public UserRepositoryPort userRepositoryPort() {
            return userRepositoryPort;
        }

        @Bean
        public UserPublicProfileQueryPort userPublicProfileQueryPort() {
            return userPublicProfileQueryPort;
        }

        @Bean
        public ReactionRepositoryPort reactionRepositoryPort() {
            return reactionRepositoryPort;
        }

        @Bean
        public UserIdentityContract userIdentityContract(
                UserRepositoryPort userRepo,
                UserPublicProfileQueryPort profilePort
        ) {
            return new UserIdentityQueryService(userRepo, profilePort);
        }

        @Bean
        public GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase(CommentRepositoryPort port) {
            return new GetCommentTargetMetricsUseCase(port);
        }

        @Bean
        public ListCommentRootsUseCase listCommentRootsUseCase(CommentRepositoryPort port) {
            return new ListCommentRootsUseCase(port);
        }

        @Bean
        public GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase(CommentRepositoryPort port) {
            return new GetCommentThreadsByRootIdsUseCase(port, new CommentThreadVisibilityResolver());
        }

        @Bean
        public ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase(CommentRepositoryPort port) {
            return new ValidateCommentTargetScopeUseCase(port);
        }

        @Bean
        public GetCommentThreadUseCase getCommentThreadUseCase(CommentRepositoryPort port) {
            return new GetCommentThreadUseCase(port, new CommentThreadVisibilityResolver());
        }

        @Bean
        public CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase(CommentRepositoryPort port) {
            return new CountVisibleActiveRepliesByRootIdsUseCase(port);
        }

        @Bean
        public GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase(ReactionRepositoryPort port) {
            return new GetBatchReactionSummariesUseCase(port);
        }

        @Bean
        public DiscussionCoreReadService discussionCoreReadService(
                GetCommentTargetMetricsUseCase metricsUseCase,
                ListCommentRootsUseCase listRootsUseCase,
                GetCommentThreadsByRootIdsUseCase threadsUseCase,
                ValidateCommentTargetScopeUseCase scopeUseCase,
                GetCommentThreadUseCase threadUseCase,
                CountVisibleActiveRepliesByRootIdsUseCase replyCountUseCase,
                UserIdentityContract identityContract
        ) {
            return new DiscussionCoreReadService(
                    metricsUseCase,
                    listRootsUseCase,
                    threadsUseCase,
                    scopeUseCase,
                    threadUseCase,
                    replyCountUseCase,
                    identityContract
            );
        }

        @Bean
        public CommentReactionEnrichmentService commentReactionEnrichmentService(
                GetBatchReactionSummariesUseCase batchReactionSummariesUseCase
        ) {
            return new CommentReactionEnrichmentService(batchReactionSummariesUseCase);
        }

        @Bean
        public CommunityPostDiscussionQueryCoordinator communityPostDiscussionQueryCoordinator(
                CommunityPostQueryPort postQueryPort,
                DiscussionCoreReadService coreReadService,
                CommentReactionEnrichmentService reactionEnrichmentService
        ) {
            return new CommunityPostDiscussionQueryCoordinator(
                    postQueryPort, coreReadService, reactionEnrichmentService
            );
        }
    }

    @Autowired
    private DiscussionCoreReadService sharedCoreReadService;

    @Autowired
    private CommunityPostDiscussionQueryCoordinator coordinator;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Connection coreConnection;

    @Autowired
    private Connection reactionConnection;

    @Autowired
    private CommunityPostQueryPort communityPostQueryPort;

    @Autowired
    private CommentRepositoryPort commentRepositoryPort;

    @Autowired
    private UserPublicProfileQueryPort userPublicProfileQueryPort;

    @Autowired
    private ReactionRepositoryPort reactionRepositoryPort;

    private AtomicInteger activeConnections;
    private AtomicInteger maxConcurrentConnections;

    @BeforeEach
    void setUp() throws SQLException {
        reset(
                dataSource,
                coreConnection,
                reactionConnection,
                communityPostQueryPort,
                commentRepositoryPort,
                userPublicProfileQueryPort,
                reactionRepositoryPort
        );
        activeConnections = new AtomicInteger(0);
        maxConcurrentConnections = new AtomicInteger(0);

        when(coreConnection.getAutoCommit()).thenReturn(true);
        when(reactionConnection.getAutoCommit()).thenReturn(true);

        AtomicInteger acquisitionSeq = new AtomicInteger(0);
        when(dataSource.getConnection()).thenAnswer(inv -> {
            int openNow = activeConnections.incrementAndGet();
            maxConcurrentConnections.accumulateAndGet(openNow, Math::max);
            return acquisitionSeq.getAndIncrement() == 0 ? coreConnection : reactionConnection;
        });

        doAnswer(inv -> {
            activeConnections.decrementAndGet();
            return null;
        }).when(coreConnection).close();

        doAnswer(inv -> {
            activeConnections.decrementAndGet();
            return null;
        }).when(reactionConnection).close();
    }

    private CommunityPostPublicDTO publicPost() {
        return new CommunityPostPublicDTO(POST_ID, ROOT_AUTHOR_ID, "Post caption", null, 1, NOW, NOW);
    }

    @Test
    @DisplayName("Community roots-only feed shares 1 Phase A RO TX without hydrating replies and opens Phase B sequentially")
    void communityRootsOnlyFeedConsolidatesPhaseABeforePhaseBWithoutReplyOverfetch() throws SQLException {
        assertThat(AopUtils.isAopProxy(sharedCoreReadService)).isTrue();
        assertThat(AopUtils.isAopProxy(coordinator)).isFalse();

        CommentTarget target = CommentTarget.communityPost(POST_ID);
        Comment root = Comment.createRoot(ROOT_ID, target, ROOT_AUTHOR_ID, "Root comment", NOW);
        List<String> coreTxNames = new ArrayList<>();
        List<Boolean> coreReadOnlyFlags = new ArrayList<>();

        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            coreReadOnlyFlags.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Optional.of(publicPost());
        });
        when(commentRepositoryPort.getMetricsForTarget(target)).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return new CommentTargetMetrics(1L, 3L);
        });
        when(commentRepositoryPort.findActiveRoots(target, CommentSortMode.FEATURED, 0, 10)).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return new CommentSlice(List.of(root), 0, 10, false);
        });
        when(commentRepositoryPort.countActiveRepliesByThreadRootIds(List.of(ROOT_ID))).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Map.of(ROOT_ID, 2L);
        });
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(ROOT_AUTHOR_ID))).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Map.of(ROOT_AUTHOR_ID, new UserPublicProfileDTO(ROOT_AUTHOR_ID, "Root Author", null, "root_author"));
        });
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenAnswer(inv -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    assertThat(TransactionSynchronizationManager.getCurrentTransactionName())
                            .isNotEqualTo(coreTxNames.get(0));
                    verify(coreConnection, times(1)).commit();
                    verify(coreConnection, times(1)).close();
                    return Map.of(ROOT_ID, Map.of(ReactionType.LIKE, 5L));
                });

        CommunityDiscussionFeedResponseDTO feed = coordinator.getDiscussionFeed(
                POST_ID, 0, 10, null, CommentSortMode.FEATURED
        );

        assertThat(feed.commentCount()).isEqualTo(3L);
        assertThat(feed.roots()).hasSize(1);
        assertThat(feed.roots().get(0).replyCount()).isEqualTo(2L);
        assertThat(feed.roots().get(0).root().reactionSummary().totalCount()).isEqualTo(5L);

        // Negative assertions: never hydrates thread replies or reply authors
        verify(commentRepositoryPort, never()).findActiveThreadCommentsByRootIds(any(), any());
        verify(commentRepositoryPort, never()).findThreadRepliesByRootIds(any());
        verify(commentRepositoryPort, never()).findThreadReplies(any());
        verify(commentRepositoryPort, never()).findActiveRootsByIds(any(), any());
        verify(userPublicProfileQueryPort).findPublicProfilesByIds(Set.of(ROOT_AUTHOR_ID));

        assertThat(activeConnections.get()).isZero();
        assertThat(maxConcurrentConnections.get()).isEqualTo(1);
        assertThat(coreReadOnlyFlags).containsOnly(true);
        assertThat(coreTxNames).hasSize(5);
        assertThat(coreTxNames.stream().distinct().count()).isEqualTo(1);
        verify(dataSource, times(2)).getConnection();
        verify(coreConnection, times(1)).commit();
        verify(reactionConnection, times(1)).commit();
        verify(reactionConnection, times(1)).close();
    }

    @Test
    @DisplayName("Community single-thread executes eligibility, scope check, thread, and authors in 1 Phase A RO TX before Phase B Reaction TX")
    void communitySingleThreadConsolidatesPhaseABeforePhaseB() throws SQLException {
        CommentTarget target = CommentTarget.communityPost(POST_ID);
        Comment root = Comment.createRoot(ROOT_ID, target, ROOT_AUTHOR_ID, "Thread root", NOW);
        Comment reply = Comment.createReply(REPLY_ID, root, REPLY_AUTHOR_ID, "Visible reply", NOW.plusSeconds(10));
        List<String> coreTxNames = new ArrayList<>();

        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Optional.of(publicPost());
        });
        when(commentRepositoryPort.findById(ROOT_ID)).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Optional.of(root);
        });
        when(commentRepositoryPort.findThreadReplies(ROOT_ID)).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return List.of(reply);
        });
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(ROOT_AUTHOR_ID, REPLY_AUTHOR_ID)))
                .thenAnswer(inv -> {
                    coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
                    return Map.of(
                            ROOT_AUTHOR_ID, new UserPublicProfileDTO(ROOT_AUTHOR_ID, "Root Author", null, "root_author"),
                            REPLY_AUTHOR_ID, new UserPublicProfileDTO(REPLY_AUTHOR_ID, "Reply Author", null, "reply_author")
                    );
                });
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID, REPLY_ID)))
                .thenAnswer(inv -> {
                    verify(coreConnection, times(1)).commit();
                    verify(coreConnection, times(1)).close();
                    return Map.of(ROOT_ID, Map.of(ReactionType.LOVE, 2L));
                });

        CommentThreadResponseDTO thread = coordinator.getCommentThread(POST_ID, ROOT_ID, null);

        assertThat(thread.root().id()).isEqualTo(ROOT_ID);
        assertThat(thread.replies()).hasSize(1);
        assertThat(thread.replies().get(0).author().displayName()).isEqualTo("Reply Author");
        assertThat(maxConcurrentConnections.get()).isEqualTo(1);
        assertThat(coreTxNames).hasSize(5); // gate + validate findById + thread findById + findThreadReplies + identity
        assertThat(coreTxNames.stream().distinct().count()).isEqualTo(1);
        verify(dataSource, times(2)).getConnection();
        verify(coreConnection, times(1)).commit();
        verify(reactionConnection, times(1)).commit();
    }

    @Test
    @DisplayName("Reaction QueryTimeoutException rolls back only Phase B while preserving committed Phase A for feed and single thread")
    void reactionTimeoutRollsBackOnlyPhaseBWithoutUnexpectedRollbackException() throws SQLException {
        CommentTarget target = CommentTarget.communityPost(POST_ID);
        Comment root = Comment.createRoot(ROOT_ID, target, ROOT_AUTHOR_ID, "Resilient root", NOW);

        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(publicPost()));
        when(commentRepositoryPort.getMetricsForTarget(target)).thenReturn(new CommentTargetMetrics(1L, 1L));
        when(commentRepositoryPort.findActiveRoots(target, CommentSortMode.NEWEST, 0, 10))
                .thenReturn(new CommentSlice(List.of(root), 0, 10, false));
        when(commentRepositoryPort.countActiveRepliesByThreadRootIds(List.of(ROOT_ID))).thenReturn(Map.of());
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(ROOT_AUTHOR_ID)))
                .thenReturn(Map.of(ROOT_AUTHOR_ID, new UserPublicProfileDTO(ROOT_AUTHOR_ID, "Root Author", null, "root_author")));
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenThrow(new QueryTimeoutException("Simulated reaction timeout on feed"));

        CommunityDiscussionFeedResponseDTO feed = coordinator.getDiscussionFeed(
                POST_ID, 0, 10, VIEWER_ID, CommentSortMode.NEWEST
        );
        assertThat(feed.roots()).hasSize(1);
        assertThat(feed.roots().get(0).root().reactionSummary()).isNull();
        verify(coreConnection, times(1)).commit();
        verify(reactionConnection, times(1)).rollback();

        // Reset connection sequencing for single-thread timeout test
        setUp();
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(publicPost()));
        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(root));
        when(commentRepositoryPort.findThreadReplies(ROOT_ID)).thenReturn(List.of());
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(ROOT_AUTHOR_ID)))
                .thenReturn(Map.of(ROOT_AUTHOR_ID, new UserPublicProfileDTO(ROOT_AUTHOR_ID, "Root Author", null, "root_author")));
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenThrow(new QueryTimeoutException("Simulated reaction timeout on thread"));

        CommentThreadResponseDTO thread = coordinator.getCommentThread(POST_ID, ROOT_ID, VIEWER_ID);
        assertThat(thread.root().id()).isEqualTo(ROOT_ID);
        assertThat(thread.root().reactionSummary()).isNull();
        verify(coreConnection, times(1)).commit();
        verify(reactionConnection, times(1)).rollback();
    }

    @Test
    @DisplayName("Ineligible post and Phase A core timeouts roll back cleanly without invoking Phase B")
    void ineligibleOrCoreFailureRollsBackPhaseAWithoutPhaseB() throws SQLException {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(POST_ID, 0, 10, null, CommentSortMode.FEATURED))
                .isInstanceOf(CommentTargetNotEligibleException.class);
        verify(coreConnection, times(1)).rollback();
        verify(coreConnection, never()).commit();
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());

        setUp();
        CommentTarget target = CommentTarget.communityPost(POST_ID);
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(publicPost()));
        when(commentRepositoryPort.getMetricsForTarget(target))
                .thenThrow(new QueryTimeoutException("Simulated core metrics timeout"));

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(POST_ID, 0, 10, VIEWER_ID, CommentSortMode.FEATURED))
                .isInstanceOf(QueryTimeoutException.class)
                .isNotInstanceOf(UnexpectedRollbackException.class);
        verify(coreConnection, times(1)).rollback();
        verify(coreConnection, never()).commit();
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());
    }
}
