package com.universe.interaction.entry.shared;

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
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService.ReactionEnrichmentResult;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreSingleThreadResult;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreThreadedFeedResult;
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
 * Spring AOP proxy and transaction boundary integration test for the shared comment read core.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = DiscussionCoreReadTransactionIntegrationTest.TestConfig.class)
@DisplayName("DiscussionCoreReadTransactionIntegrationTest — Shared Spring AOP & Sequential Phase Isolation")
class DiscussionCoreReadTransactionIntegrationTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VIEWER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AUTHOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        private final Connection coreConnection = mock(Connection.class);
        private final Connection reactionConnection = mock(Connection.class);
        private final DataSource dataSource = mock(DataSource.class);
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
                UserRepositoryPort userRepositoryPort,
                UserPublicProfileQueryPort userPublicProfileQueryPort
        ) {
            return new UserIdentityQueryService(userRepositoryPort, userPublicProfileQueryPort);
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
        public GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase(ReactionRepositoryPort port) {
            return new GetBatchReactionSummariesUseCase(port);
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
        public DiscussionCoreReadService discussionCoreReadService(
                GetCommentTargetMetricsUseCase metricsUseCase,
                ListCommentRootsUseCase listRootsUseCase,
                GetCommentThreadsByRootIdsUseCase threadsUseCase,
                ValidateCommentTargetScopeUseCase scopeUseCase,
                GetCommentThreadUseCase threadUseCase,
                UserIdentityContract identityContract
        ) {
            return new DiscussionCoreReadService(
                    metricsUseCase,
                    listRootsUseCase,
                    threadsUseCase,
                    scopeUseCase,
                    threadUseCase,
                    identityContract
            );
        }

        @Bean
        public CommentReactionEnrichmentService commentReactionEnrichmentService(
                GetBatchReactionSummariesUseCase batchReactionSummariesUseCase
        ) {
            return new CommentReactionEnrichmentService(batchReactionSummariesUseCase);
        }
    }

    @Autowired
    private DiscussionCoreReadService coreReadService;

    @Autowired
    private CommentReactionEnrichmentService reactionEnrichmentService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Connection coreConnection;

    @Autowired
    private Connection reactionConnection;

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
        reset(dataSource, coreConnection, reactionConnection, commentRepositoryPort, userPublicProfileQueryPort, reactionRepositoryPort);
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

    @Test
    @DisplayName("Shared core service owns Spring AOP read-only TX and commits/closes before Reaction TX starts")
    void sharedCoreCommitsAndClosesBeforeReactionStarts() throws SQLException {
        assertThat(AopUtils.isAopProxy(coreReadService)).isTrue();
        assertThat(AopUtils.isAopProxy(reactionEnrichmentService)).isFalse();

        CommentTarget target = CommentTarget.wikiArticle(TARGET_ID);
        Comment root = Comment.createRoot(ROOT_ID, target, AUTHOR_ID, "Root text", NOW);
        List<String> coreTxNames = new ArrayList<>();
        List<Boolean> coreReadOnlyFlags = new ArrayList<>();

        when(commentRepositoryPort.getMetricsForTarget(target)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            coreReadOnlyFlags.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return new CommentTargetMetrics(1, 1);
        });
        when(commentRepositoryPort.findActiveRoots(target, CommentSortMode.FEATURED, 0, 20)).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return new CommentSlice(List.of(root), 0, 20, false);
        });
        when(commentRepositoryPort.findActiveRootsByIds(target, List.of(ROOT_ID))).thenReturn(List.of(root));
        when(commentRepositoryPort.findThreadRepliesByRootIds(List.of(ROOT_ID))).thenReturn(List.of());
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_ID))).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Map.of(AUTHOR_ID, new UserPublicProfileDTO(AUTHOR_ID, "Author", null, "author"));
        });

        CoreThreadedFeedResult<String> coreResult = coreReadService.readThreadedFeed(
                target,
                0,
                20,
                CommentSortMode.FEATURED,
                true,
                false,
                () -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
                },
                () -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
                    return "attachment-ok";
                }
        );

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(activeConnections.get()).isZero();
        verify(coreConnection, times(1)).commit();
        verify(coreConnection, times(1)).close();

        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenAnswer(inv -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    assertThat(TransactionSynchronizationManager.getCurrentTransactionName())
                            .isNotEqualTo(coreTxNames.get(0));
                    return Map.of(ROOT_ID, Map.of(ReactionType.LIKE, 2L));
                });

        ReactionEnrichmentResult reactionResult = reactionEnrichmentService.loadCommentReactionSummaries(
                "shared test",
                coreResult.participants().activeCommentIds(),
                null,
                false
        );

        assertThat(reactionResult.failed()).isFalse();
        assertThat(reactionResult.summariesByCommentId().get(ROOT_ID).totalCount()).isEqualTo(2L);
        assertThat(maxConcurrentConnections.get()).isEqualTo(1);
        assertThat(coreReadOnlyFlags).containsOnly(true);
        assertThat(coreTxNames).hasSize(5);
        assertThat(coreTxNames.stream().distinct().count()).isEqualTo(1);
        verify(dataSource, times(2)).getConnection();
        verify(reactionConnection, times(1)).commit();
        verify(reactionConnection, times(1)).close();
    }

    @Test
    @DisplayName("Core failure and Reaction timeout roll back their respective transactions independently")
    void rollbackIsolationWorksAcrossCoreAndReactionPhases() throws SQLException {
        CommentTarget target = CommentTarget.screenTitle(TARGET_ID);

        // 1. Core failure rolls back Phase A cleanly
        assertThatThrownBy(() -> coreReadService.readThreadedFeed(
                target,
                0,
                20,
                CommentSortMode.NEWEST,
                true,
                false,
                () -> {
                    throw new CommentTargetNotEligibleException(target);
                }
        )).isInstanceOf(CommentTargetNotEligibleException.class);

        verify(coreConnection, times(1)).rollback();
        verify(coreConnection, never()).commit();
        verify(coreConnection, times(1)).close();

        // 2. Sequential Reaction failure after a committed Phase A rolls back only Phase B
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenThrow(new QueryTimeoutException("Simulated reaction timeout"));

        ReactionEnrichmentResult failed = reactionEnrichmentService.loadCommentReactionSummaries(
                "shared test",
                List.of(ROOT_ID),
                VIEWER_ID,
                true
        );

        assertThat(failed.failed()).isTrue();
        assertThat(failed.summariesByCommentId()).isEmpty();
        verify(reactionConnection, times(1)).rollback();
        verify(reactionConnection, never()).commit();
        verify(reactionConnection, times(1)).close();
    }

    @Test
    @DisplayName("Shared single-thread core executes all reads in 1 RO TX and commits/closes before Reaction TX")
    void sharedSingleThreadCommitsAndClosesBeforeReactionStarts() throws SQLException {
        CommentTarget target = CommentTarget.wikiArticle(TARGET_ID);
        Comment root = Comment.createRoot(ROOT_ID, target, AUTHOR_ID, "Thread root", NOW);
        List<String> coreTxNames = new ArrayList<>();
        List<Boolean> coreReadOnlyFlags = new ArrayList<>();

        when(commentRepositoryPort.findById(ROOT_ID)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            coreReadOnlyFlags.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return java.util.Optional.of(root);
        });
        when(commentRepositoryPort.findThreadReplies(ROOT_ID)).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return List.of();
        });
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_ID))).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Map.of(AUTHOR_ID, new UserPublicProfileDTO(AUTHOR_ID, "Author", null, "author"));
        });

        CoreSingleThreadResult<Void> singleResult = coreReadService.readSingleThreadCore(
                target,
                ROOT_ID,
                () -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
                }
        );

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(activeConnections.get()).isZero();
        verify(coreConnection, times(1)).commit();
        verify(coreConnection, times(1)).close();

        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenReturn(Map.of(ROOT_ID, Map.of(ReactionType.LIKE, 1L)));

        ReactionEnrichmentResult reactionResult = reactionEnrichmentService.loadCommentReactionSummaries(
                "shared thread test",
                singleResult.participants().activeCommentIds(),
                null,
                false
        );

        assertThat(reactionResult.failed()).isFalse();
        assertThat(reactionResult.summariesByCommentId().get(ROOT_ID).totalCount()).isEqualTo(1L);
        assertThat(maxConcurrentConnections.get()).isEqualTo(1);
        assertThat(coreReadOnlyFlags).containsOnly(true);
        assertThat(coreTxNames).hasSize(5);
        assertThat(coreTxNames.stream().distinct().count()).isEqualTo(1);
        verify(reactionConnection, times(1)).commit();
        verify(reactionConnection, times(1)).close();
    }
}
