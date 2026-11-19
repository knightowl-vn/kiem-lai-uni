package com.universe.interaction.entry.screen;

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
import com.universe.interaction.entry.screen.dto.ScreenDiscussionFeedResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.screen.contracts.dto.ScreenEntryOriginDTO;
import com.universe.screen.contracts.interfaces.ScreenDiscussionContract;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spring AOP proxy and transaction boundary integration test for Screen discussion feed reads.
 *
 * <p>Proves:
 * <ul>
 *   <li>{@link ScreenTitleDiscussionCoreReadService} is Spring-proxied with {@code @Transactional(readOnly = true)};</li>
 *   <li>All Phase A core reads share a single read-only transaction and connection;</li>
 *   <li>Phase A commits and closes its connection strictly before Phase B Reaction lookup begins;</li>
 *   <li>Phase B opens a separate sequential read-only transaction with zero simultaneous connections;</li>
 *   <li>Reaction failures roll back only Phase B without marking Phase A rollback-only or causing {@link UnexpectedRollbackException};</li>
 *   <li>Core failures roll back Phase A cleanly without invoking Phase B.</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ScreenTitleDiscussionTransactionIntegrationTest.TestConfig.class)
@DisplayName("ScreenTitleDiscussionTransactionIntegrationTest — Spring AOP & Two-Phase Transaction Isolation")
class ScreenTitleDiscussionTransactionIntegrationTest {

    private static final UUID TITLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VIEWER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AUTHOR_1_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOT_1_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ENTRY_1_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        private final Connection coreConnection = mock(Connection.class);
        private final Connection reactionConnection = mock(Connection.class);
        private final DataSource dataSource = mock(DataSource.class);
        private final ScreenDiscussionContract screenDiscussionContract = mock(ScreenDiscussionContract.class);
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
        public ScreenDiscussionContract screenDiscussionContract() {
            return screenDiscussionContract;
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
        public GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase(CommentRepositoryPort commentRepositoryPort) {
            return new GetCommentTargetMetricsUseCase(commentRepositoryPort);
        }

        @Bean
        public ListCommentRootsUseCase listCommentRootsUseCase(CommentRepositoryPort commentRepositoryPort) {
            return new ListCommentRootsUseCase(commentRepositoryPort);
        }

        @Bean
        public CommentThreadVisibilityResolver visibilityResolver() {
            return new CommentThreadVisibilityResolver();
        }

        @Bean
        public GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase(
                CommentRepositoryPort commentRepositoryPort,
                CommentThreadVisibilityResolver visibilityResolver
        ) {
            return new GetCommentThreadsByRootIdsUseCase(commentRepositoryPort, visibilityResolver);
        }

        @Bean
        public GetCommentThreadUseCase getCommentThreadUseCase(
                CommentRepositoryPort commentRepositoryPort,
                CommentThreadVisibilityResolver visibilityResolver
        ) {
            return new GetCommentThreadUseCase(commentRepositoryPort, visibilityResolver);
        }

        @Bean
        public ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase(
                CommentRepositoryPort commentRepositoryPort
        ) {
            return new ValidateCommentTargetScopeUseCase(commentRepositoryPort);
        }

        @Bean
        public GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase(
                ReactionRepositoryPort reactionRepositoryPort
        ) {
            return new GetBatchReactionSummariesUseCase(reactionRepositoryPort);
        }

        @Bean
        public DiscussionCoreReadService discussionCoreReadService(
                GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
                ListCommentRootsUseCase listCommentRootsUseCase,
                GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
                UserIdentityContract userIdentityContract
        ) {
            return new DiscussionCoreReadService(
                    getCommentTargetMetricsUseCase,
                    listCommentRootsUseCase,
                    getCommentThreadsByRootIdsUseCase,
                    userIdentityContract
            );
        }

        @Bean
        public CommentReactionEnrichmentService commentReactionEnrichmentService(
                GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase
        ) {
            return new CommentReactionEnrichmentService(getBatchReactionSummariesUseCase);
        }

        @Bean
        public ScreenTitleDiscussionCoreReadService screenTitleDiscussionCoreReadService(
                ScreenDiscussionContract screenDiscussionContract,
                DiscussionCoreReadService discussionCoreReadService
        ) {
            return new ScreenTitleDiscussionCoreReadService(
                    screenDiscussionContract,
                    discussionCoreReadService
            );
        }

        @Bean
        public ScreenTitleDiscussionQueryCoordinator screenTitleDiscussionQueryCoordinator(
                ScreenDiscussionContract screenDiscussionContract,
                ScreenTitleDiscussionCoreReadService coreReadService,
                CommentReactionEnrichmentService reactionEnrichmentService,
                GetCommentThreadUseCase getCommentThreadUseCase,
                ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
                UserIdentityContract userIdentityContract
        ) {
            return new ScreenTitleDiscussionQueryCoordinator(
                    screenDiscussionContract,
                    coreReadService,
                    reactionEnrichmentService,
                    getCommentThreadUseCase,
                    validateCommentTargetScopeUseCase,
                    userIdentityContract,
                    false
            );
        }
    }

    @Autowired
    private ScreenTitleDiscussionQueryCoordinator coordinator;

    @Autowired
    private ScreenTitleDiscussionCoreReadService coreReadService;

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
    private ScreenDiscussionContract screenDiscussionContract;

    @Autowired
    private CommentRepositoryPort commentRepositoryPort;

    @Autowired
    private UserPublicProfileQueryPort userPublicProfileQueryPort;

    @Autowired
    private ReactionRepositoryPort reactionRepositoryPort;

    private AtomicInteger activeConnections;
    private AtomicInteger maxConcurrentConnections;
    private AtomicInteger coreCommitsSeenWhenReactionStarts;
    private AtomicInteger coreClosesSeenWhenReactionStarts;

    @BeforeEach
    void setUp() throws SQLException {
        reset(
                dataSource,
                coreConnection,
                reactionConnection,
                screenDiscussionContract,
                commentRepositoryPort,
                userPublicProfileQueryPort,
                reactionRepositoryPort
        );
        activeConnections = new AtomicInteger(0);
        maxConcurrentConnections = new AtomicInteger(0);
        coreCommitsSeenWhenReactionStarts = new AtomicInteger(-1);
        coreClosesSeenWhenReactionStarts = new AtomicInteger(-1);

        when(coreConnection.getAutoCommit()).thenReturn(true);
        when(reactionConnection.getAutoCommit()).thenReturn(true);

        AtomicInteger acquisitionSeq = new AtomicInteger(0);
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            int openNow = activeConnections.incrementAndGet();
            maxConcurrentConnections.accumulateAndGet(openNow, Math::max);
            return acquisitionSeq.getAndIncrement() == 0 ? coreConnection : reactionConnection;
        });

        AtomicInteger coreCommittedCount = new AtomicInteger(0);
        AtomicInteger coreClosedCount = new AtomicInteger(0);

        org.mockito.Mockito.doAnswer(invocation -> {
            coreCommittedCount.incrementAndGet();
            return null;
        }).when(coreConnection).commit();

        org.mockito.Mockito.doAnswer(invocation -> {
            coreClosedCount.incrementAndGet();
            activeConnections.decrementAndGet();
            return null;
        }).when(coreConnection).close();

        org.mockito.Mockito.doAnswer(invocation -> {
            activeConnections.decrementAndGet();
            return null;
        }).when(reactionConnection).close();

        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(any(), any())).thenAnswer(invocation -> {
            coreCommitsSeenWhenReactionStarts.set(coreCommittedCount.get());
            coreClosesSeenWhenReactionStarts.set(coreClosedCount.get());
            return Map.of();
        });
    }

    @Test
    @DisplayName("Core collaborator is Spring-proxied and Phase A commits/closes before Phase B starts")
    void coreReadsShareOneTransactionAndCommitBeforeSequentialReactionsBegin() throws SQLException {
        assertThat(AopUtils.isAopProxy(sharedCoreReadService)).isTrue();
        assertThat(AopUtils.isAopProxy(coreReadService)).isFalse();
        assertThat(AopUtils.isAopProxy(reactionEnrichmentService)).isFalse();
        assertThat(AopUtils.isAopProxy(coordinator)).isFalse();
        assertThat(AopUtils.isAopProxy(userIdentityContract)).isTrue();
        assertThat(AopUtils.isAopProxy(batchReactionSummariesUseCase)).isTrue();

        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);
        Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root body", NOW, ENTRY_1_ID);

        List<String> txNamesObserved = new ArrayList<>();
        List<Boolean> txReadOnlyFlags = new ArrayList<>();

        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            txReadOnlyFlags.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            txNamesObserved.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return true;
        });
        when(commentRepositoryPort.getMetricsForTarget(target)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            txNamesObserved.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return new CommentTargetMetrics(1, 1);
        });
        when(commentRepositoryPort.findActiveRoots(target, CommentSortMode.NEWEST, 0, 20)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            txNamesObserved.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return new CommentSlice(List.of(root), 0, 20, false);
        });
        when(screenDiscussionContract.findPublishedEntryOriginsByTitleIdAndEntryIds(TITLE_ID, Set.of(ENTRY_1_ID)))
                .thenAnswer(inv -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    txNamesObserved.add(TransactionSynchronizationManager.getCurrentTransactionName());
                    return Map.of(ENTRY_1_ID, new ScreenEntryOriginDTO(ENTRY_1_ID, "tap-1", "Tập 1"));
                });
        when(commentRepositoryPort.findActiveThreadCommentsByRootIds(target, List.of(ROOT_1_ID))).thenReturn(List.of(root));
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_1_ID))).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            txNamesObserved.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"));
        });

        List<String> reactionTxNames = new ArrayList<>();
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_1_ID)))
                .thenAnswer(inv -> {
                    coreCommitsSeenWhenReactionStarts.set(1);
                    coreClosesSeenWhenReactionStarts.set(1);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    reactionTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
                    return Map.of(ROOT_1_ID, Map.of(ReactionType.LIKE, 5L));
                });
        when(reactionRepositoryPort.findUserReactionsForTargetIds(VIEWER_ID, ReactionTargetType.COMMENT, Set.of(ROOT_1_ID)))
                .thenReturn(Map.of(ROOT_1_ID, ReactionType.LIKE));

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(
                TITLE_ID, 0, 20, VIEWER_ID, CommentSortMode.NEWEST
        );
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        assertThat(response.threads()).hasSize(1);
        assertThat(response.threads().get(0).root().origin().badgeText()).isEqualTo("Tập 1");
        assertThat(response.threads().get(0).root().reactionSummary().totalCount()).isEqualTo(5L);
        assertThat(response.threads().get(0).root().reactionSummary().currentUserReaction()).isEqualTo("LIKE");

        // All core operations participated in the exact same read-only transaction
        assertThat(txReadOnlyFlags).containsOnly(true);
        assertThat(txNamesObserved).hasSize(5);
        assertThat(txNamesObserved.stream().distinct().count()).isEqualTo(1);

        // Reactions ran in a distinct sequential transaction after core TX commit & close
        assertThat(reactionTxNames).hasSize(1);
        assertThat(reactionTxNames.get(0)).isNotEqualTo(txNamesObserved.get(0));
        assertThat(maxConcurrentConnections.get()).isEqualTo(1);
        assertThat(activeConnections.get()).isZero();

        verify(dataSource, times(2)).getConnection();
        verify(coreConnection, times(1)).setAutoCommit(false);
        verify(coreConnection, times(1)).setReadOnly(true);
        verify(coreConnection, times(1)).commit();
        verify(coreConnection, never()).rollback();
        verify(coreConnection, times(1)).close();

        verify(reactionConnection, times(1)).setAutoCommit(false);
        verify(reactionConnection, times(1)).setReadOnly(true);
        verify(reactionConnection, times(1)).commit();
        verify(reactionConnection, never()).rollback();
        verify(reactionConnection, times(1)).close();
    }

    @Test
    @DisplayName("Reaction DB failure rolls back ONLY Phase B while Phase A stays committed without UnexpectedRollbackException")
    void reactionFailureRollsBackOnlyPhaseBAndPreservesFeed() throws SQLException {
        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);
        Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root body", NOW);

        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
        when(commentRepositoryPort.getMetricsForTarget(target)).thenReturn(new CommentTargetMetrics(1, 1));
        when(commentRepositoryPort.findActiveRoots(target, CommentSortMode.FEATURED, 0, 20))
                .thenReturn(new CommentSlice(List.of(root), 0, 20, false));
        when(commentRepositoryPort.findActiveThreadCommentsByRootIds(target, List.of(ROOT_1_ID))).thenReturn(List.of(root));
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));

        // Verify core connection has ALREADY committed and closed when reaction query throws
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_1_ID)))
                .thenAnswer(inv -> {
                    assertThat(activeConnections.get()).isEqualTo(1);
                    throw new QueryTimeoutException("Simulated reaction query timeout");
                });

        ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(
                TITLE_ID, 0, 20, VIEWER_ID, CommentSortMode.FEATURED
        );

        assertThat(response.threads()).hasSize(1);
        assertThat(response.threads().get(0).root().body()).isEqualTo("Root body");
        assertThat(response.threads().get(0).root().author().displayName()).isEqualTo("Author One");
        assertThat(response.threads().get(0).root().reactionSummary()).isNull();

        assertThat(maxConcurrentConnections.get()).isEqualTo(1);
        assertThat(activeConnections.get()).isZero();

        verify(coreConnection, times(1)).commit();
        verify(coreConnection, never()).rollback();
        verify(coreConnection, times(1)).close();

        verify(reactionConnection, never()).commit();
        verify(reactionConnection, times(1)).rollback();
        verify(reactionConnection, times(1)).close();
    }

    @Test
    @DisplayName("Empty feed commits Phase A in 1 transaction and skips Phase B")
    void emptyFeedExecutesSingleTransactionAndSkipsPhaseB() throws SQLException {
        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
        when(commentRepositoryPort.getMetricsForTarget(target)).thenReturn(CommentTargetMetrics.EMPTY);
        when(commentRepositoryPort.findActiveRoots(target, CommentSortMode.NEWEST, 0, 20))
                .thenReturn(new CommentSlice(List.of(), 0, 20, false));

        ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(
                TITLE_ID, 0, 20, VIEWER_ID, CommentSortMode.NEWEST
        );

        assertThat(response.threads()).isEmpty();
        assertThat(activeConnections.get()).isZero();
        verify(dataSource, times(1)).getConnection();
        verify(coreConnection, times(1)).commit();
        verify(coreConnection, never()).rollback();
        verify(coreConnection, times(1)).close();
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());
    }

    @Test
    @DisplayName("Unpublished title rolls back Phase A and never calls Phase B")
    void unpublishedTitleRollsBackPhaseAWithoutCallingPhaseB() throws SQLException {
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(false);

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(
                TITLE_ID, 0, 20, VIEWER_ID, CommentSortMode.NEWEST
        )).isInstanceOf(CommentTargetNotEligibleException.class);

        assertThat(activeConnections.get()).isZero();
        verify(dataSource, times(1)).getConnection();
        verify(coreConnection, never()).commit();
        verify(coreConnection, times(1)).rollback();
        verify(coreConnection, times(1)).close();
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());
    }

    @Test
    @DisplayName("Core persistence exception rolls back Phase A without calling Phase B")
    void coreFailureRollsBackPhaseAAndDoesNotInvokePhaseB() throws SQLException {
        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
        when(commentRepositoryPort.getMetricsForTarget(target))
                .thenThrow(new QueryTimeoutException("Simulated core failure"));

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(
                TITLE_ID, 0, 20, null, CommentSortMode.NEWEST
        ))
                .isInstanceOf(QueryTimeoutException.class)
                .isNotInstanceOf(UnexpectedRollbackException.class);

        assertThat(activeConnections.get()).isZero();
        verify(dataSource, times(1)).getConnection();
        verify(coreConnection, never()).commit();
        verify(coreConnection, times(1)).rollback();
        verify(coreConnection, times(1)).close();
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());
    }
}
