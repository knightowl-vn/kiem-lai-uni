package com.universe.interaction.entry.wiki;

import com.universe.identity.application.ports.UserPublicProfileQueryPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.application.query.UserIdentityQueryService;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
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
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.interaction.entry.wiki.dto.WikiDiscussionFeedResponseDTO;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
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
 * Spring AOP proxy and transaction boundary integration test for Wiki discussion feed and single-thread reads.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WikiArticleDiscussionTransactionIntegrationTest.TestConfig.class)
@DisplayName("WikiArticleDiscussionTransactionIntegrationTest — Spring AOP & Two-Phase Transaction Isolation")
class WikiArticleDiscussionTransactionIntegrationTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ARTICLE_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
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
        private final WikiArticleQueryPort wikiArticleQueryPort = mock(WikiArticleQueryPort.class);
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
        public WikiArticleQueryPort wikiArticleQueryPort() {
            return wikiArticleQueryPort;
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
        public CommentThreadVisibilityResolver visibilityResolver() {
            return new CommentThreadVisibilityResolver();
        }

        @Bean
        public GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase(
                CommentRepositoryPort port,
                CommentThreadVisibilityResolver resolver
        ) {
            return new GetCommentThreadsByRootIdsUseCase(port, resolver);
        }

        @Bean
        public ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase(CommentRepositoryPort port) {
            return new ValidateCommentTargetScopeUseCase(port);
        }

        @Bean
        public GetCommentThreadUseCase getCommentThreadUseCase(
                CommentRepositoryPort port,
                CommentThreadVisibilityResolver resolver
        ) {
            return new GetCommentThreadUseCase(port, resolver);
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

        @Bean
        public WikiArticleDiscussionQueryCoordinator wikiArticleDiscussionQueryCoordinator(
                WikiArticleQueryPort wikiArticleQueryPort,
                DiscussionCoreReadService discussionCoreReadService,
                CommentReactionEnrichmentService reactionEnrichmentService
        ) {
            return new WikiArticleDiscussionQueryCoordinator(
                    wikiArticleQueryPort,
                    discussionCoreReadService,
                    reactionEnrichmentService
            );
        }
    }

    @Autowired
    private WikiArticleDiscussionQueryCoordinator coordinator;

    @Autowired
    private DiscussionCoreReadService sharedCoreReadService;

    @Autowired
    private CommentReactionEnrichmentService reactionEnrichmentService;

    @Autowired
    private GetBatchReactionSummariesUseCase batchReactionSummariesUseCase;

    @Autowired
    private UserIdentityContract userIdentityContract;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Connection coreConnection;

    @Autowired
    private Connection reactionConnection;

    @Autowired
    private WikiArticleQueryPort wikiArticleQueryPort;

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
                wikiArticleQueryPort,
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

    @Test
    @DisplayName("Populated Wiki feed shares 1 read-only TX via DiscussionCoreReadService before sequential Reaction TX")
    void wikiFeedSharesPhaseATransactionBeforeSequentialReactionPhase() throws SQLException {
        assertThat(AopUtils.isAopProxy(sharedCoreReadService)).isTrue();
        assertThat(AopUtils.isAopProxy(coordinator)).isFalse();
        assertThat(AopUtils.isAopProxy(reactionEnrichmentService)).isFalse();
        assertThat(AopUtils.isAopProxy(userIdentityContract)).isTrue();
        assertThat(AopUtils.isAopProxy(batchReactionSummariesUseCase)).isTrue();

        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);
        Comment root = Comment.createRoot(ROOT_ID, target, AUTHOR_ID, "Wiki root", NOW);
        List<String> coreTxNames = new ArrayList<>();
        List<Boolean> coreRoFlags = new ArrayList<>();

        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            coreRoFlags.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return true;
        });
        when(commentRepositoryPort.getMetricsForTarget(target)).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return new CommentTargetMetrics(1, 1);
        });
        when(commentRepositoryPort.findActiveRoots(target, CommentSortMode.NEWEST, 0, 20)).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return new CommentSlice(List.of(root), 0, 20, false);
        });
        when(commentRepositoryPort.findActiveThreadCommentsByRootIds(target, List.of(ROOT_ID))).thenReturn(List.of(root));
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_ID))).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Map.of(AUTHOR_ID, new UserPublicProfileDTO(AUTHOR_ID, "Author One", null, "author_one"));
        });

        List<String> reactionTxNames = new ArrayList<>();
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenAnswer(inv -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    reactionTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
                    return Map.of(ROOT_ID, Map.of(ReactionType.LIKE, 3L));
                });
        when(reactionRepositoryPort.findUserReactionsForTargetIds(VIEWER_ID, ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenReturn(Map.of(ROOT_ID, ReactionType.LIKE));

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        WikiDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(
                ARTICLE_ID, 0, 20, VIEWER_ID, CommentSortMode.NEWEST
        );
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        assertThat(response.threads()).hasSize(1);
        assertThat(response.threads().get(0).root().reactionSummary().totalCount()).isEqualTo(3L);
        assertThat(response.threads().get(0).root().reactionSummary().currentUserReaction()).isEqualTo("LIKE");

        assertThat(coreRoFlags).containsOnly(true);
        assertThat(coreTxNames).hasSize(4);
        assertThat(coreTxNames.stream().distinct().count()).isEqualTo(1);
        assertThat(reactionTxNames).hasSize(1);
        assertThat(reactionTxNames.get(0)).isNotEqualTo(coreTxNames.get(0));
        assertThat(maxConcurrentConnections.get()).isEqualTo(1);
        assertThat(activeConnections.get()).isZero();

        verify(dataSource, times(2)).getConnection();
        verify(coreConnection, times(1)).commit();
        verify(coreConnection, times(1)).close();
        verify(reactionConnection, times(1)).commit();
        verify(reactionConnection, times(1)).close();
    }

    @Test
    @DisplayName("Wiki single thread shares 1 read-only TX via DiscussionCoreReadService before sequential Reaction TX")
    void wikiSingleThreadSharesPhaseATransactionBeforeSequentialReactionPhase() throws SQLException {
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);
        Comment root = Comment.createRoot(ROOT_ID, target, AUTHOR_ID, "Single thread root", NOW);
        List<String> coreTxNames = new ArrayList<>();

        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return true;
        });
        when(commentRepositoryPort.findById(ROOT_ID)).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Optional.of(root);
        });
        when(commentRepositoryPort.findThreadReplies(ROOT_ID)).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return List.of();
        });
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_ID))).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Map.of(AUTHOR_ID, new UserPublicProfileDTO(AUTHOR_ID, "Author One", null, "author_one"));
        });
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenReturn(Map.of(ROOT_ID, Map.of(ReactionType.FIRE, 2L)));

        CommentThreadResponseDTO thread = coordinator.getCommentThread(ARTICLE_ID, ROOT_ID, null);

        assertThat(thread.root().id()).isEqualTo(ROOT_ID);
        assertThat(thread.root().reactionSummary().totalCount()).isEqualTo(2L);
        assertThat(coreTxNames).hasSize(5);
        assertThat(coreTxNames.stream().distinct().count()).isEqualTo(1);
        assertThat(maxConcurrentConnections.get()).isEqualTo(1);
        assertThat(activeConnections.get()).isZero();
        verify(dataSource, times(2)).getConnection();
        verify(coreConnection, times(1)).commit();
        verify(coreConnection, times(1)).close();
        verify(reactionConnection, times(1)).commit();
        verify(reactionConnection, times(1)).close();
    }

    @Test
    @DisplayName("Reaction failure on Wiki single thread rolls back ONLY Phase B while Phase A stays committed")
    void reactionFailureOnSingleThreadRollsBackOnlyPhaseB() throws SQLException {
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);
        Comment root = Comment.createRoot(ROOT_ID, target, AUTHOR_ID, "Single thread root", NOW);

        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(root));
        when(commentRepositoryPort.findThreadReplies(ROOT_ID)).thenReturn(List.of());
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_ID)))
                .thenReturn(Map.of(AUTHOR_ID, new UserPublicProfileDTO(AUTHOR_ID, "Author One", null, "author_one")));
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenAnswer(inv -> {
                    assertThat(activeConnections.get()).isEqualTo(1);
                    throw new QueryTimeoutException("Simulated reaction failure");
                });

        CommentThreadResponseDTO thread = coordinator.getCommentThread(ARTICLE_ID, ROOT_ID, VIEWER_ID);

        assertThat(thread.root().body()).isEqualTo("Single thread root");
        assertThat(thread.root().author().displayName()).isEqualTo("Author One");
        assertThat(thread.root().reactionSummary()).isNull();
        assertThat(maxConcurrentConnections.get()).isEqualTo(1);
        assertThat(activeConnections.get()).isZero();
        verify(coreConnection, times(1)).commit();
        verify(coreConnection, never()).rollback();
        verify(reactionConnection, times(1)).rollback();
        verify(reactionConnection, never()).commit();
    }

    @Test
    @DisplayName("Unpublished article and mismatched scope roll back Phase A cleanly without calling Phase B")
    void unpublishedArticleAndScopeMismatchRollBackPhaseAWithoutPhaseB() throws SQLException {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(false);

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(ARTICLE_ID, 0, 20, null, CommentSortMode.FEATURED))
                .isInstanceOf(PublishedWikiArticleNotFoundException.class);

        verify(coreConnection, times(1)).rollback();
        verify(coreConnection, never()).commit();
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());

        // Second check: scope mismatch on single thread rolls back second Phase A acquisition
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        Comment foreignRoot = Comment.createRoot(ROOT_ID, CommentTarget.wikiArticle(OTHER_ARTICLE_ID), AUTHOR_ID, "Foreign", NOW);
        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(foreignRoot));

        assertThatThrownBy(() -> coordinator.getCommentThread(ARTICLE_ID, ROOT_ID, null))
                .isInstanceOf(CommentNotFoundException.class);

        verify(reactionConnection, times(1)).rollback();
        verify(reactionConnection, never()).commit();
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());
    }

    @Test
    @DisplayName("Core persistence exception rolls back Phase A and propagates without calling Phase B")
    void corePersistenceExceptionRollsBackPhaseAWithoutCallingPhaseB() throws SQLException {
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        when(commentRepositoryPort.getMetricsForTarget(target))
                .thenThrow(new QueryTimeoutException("Simulated Wiki core metrics timeout"));

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(ARTICLE_ID, 0, 20, null, CommentSortMode.NEWEST))
                .isInstanceOf(QueryTimeoutException.class)
                .isNotInstanceOf(UnexpectedRollbackException.class);

        verify(coreConnection, times(1)).rollback();
        verify(coreConnection, never()).commit();
        verify(coreConnection, times(1)).close();
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());
    }
}
