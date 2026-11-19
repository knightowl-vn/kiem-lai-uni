package com.universe.interaction.entry.novel;

import com.universe.identity.application.ports.UserPublicProfileQueryPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.application.query.UserIdentityQueryService;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentSlice;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
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
import com.universe.interaction.entry.dto.ChapterBlockDiscussionResponseDTO;
import com.universe.interaction.entry.dto.ChapterDiscussionFeedResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.novel.application.anchor.ChapterCommentAnchorResolver;
import com.universe.novel.application.anchor.GetChapterBlockDiscussionAnchorsUseCase;
import com.universe.novel.application.anchor.ResolveChapterCommentAnchorsByRootIdsUseCase;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
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
 * Spring AOP proxy and transaction boundary integration test for Novel chapter discussion feed
 * and block drawer reads coordinated through {@link DiscussionCoreReadService}.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = NovelDiscussionTransactionIntegrationTest.TestConfig.class)
@DisplayName("NovelDiscussionTransactionIntegrationTest — Spring AOP & Two-Phase Transaction Isolation")
class NovelDiscussionTransactionIntegrationTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VIEWER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AUTHOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String BLOCK_KEY = "blk-0000000000000001-1";
    private static final String BLOCK_TEXT = "Đoạn văn thứ nhất trong chương truyện.";
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        private final Connection coreConnection = mock(Connection.class);
        private final Connection reactionConnection = mock(Connection.class);
        private final DataSource dataSource = mock(DataSource.class);
        private final CommentRepositoryPort commentRepositoryPort = mock(CommentRepositoryPort.class);
        private final ChapterCommentAnchorRepositoryPort anchorRepositoryPort = mock(ChapterCommentAnchorRepositoryPort.class);
        private final ChapterAnchorResolutionSourcePort resolutionSourcePort = mock(ChapterAnchorResolutionSourcePort.class);
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
        public ChapterCommentAnchorRepositoryPort anchorRepositoryPort() {
            return anchorRepositoryPort;
        }

        @Bean
        public ChapterAnchorResolutionSourcePort resolutionSourcePort() {
            return resolutionSourcePort;
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
        public ChapterCommentAnchorResolver chapterCommentAnchorResolver() {
            return new ChapterCommentAnchorResolver();
        }

        @Bean
        public ResolveChapterCommentAnchorsByRootIdsUseCase resolveChapterCommentAnchorsByRootIdsUseCase(
                ChapterCommentAnchorRepositoryPort anchorPort,
                ChapterAnchorResolutionSourcePort sourcePort,
                ChapterCommentAnchorResolver resolver
        ) {
            return new ResolveChapterCommentAnchorsByRootIdsUseCase(anchorPort, sourcePort, resolver);
        }

        @Bean
        public GetChapterBlockDiscussionAnchorsUseCase getChapterBlockDiscussionAnchorsUseCase(
                ChapterCommentAnchorRepositoryPort anchorPort,
                ChapterAnchorResolutionSourcePort sourcePort,
                ChapterCommentAnchorResolver resolver
        ) {
            return new GetChapterBlockDiscussionAnchorsUseCase(anchorPort, sourcePort, resolver);
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
        public NovelChapterDiscussionFeedQueryCoordinator novelChapterDiscussionFeedQueryCoordinator(
                DiscussionCoreReadService coreReadService,
                CommentReactionEnrichmentService reactionEnrichmentService,
                ResolveChapterCommentAnchorsByRootIdsUseCase resolveAnchorsUseCase
        ) {
            return new NovelChapterDiscussionFeedQueryCoordinator(
                    coreReadService, reactionEnrichmentService, resolveAnchorsUseCase
            );
        }

        @Bean
        public NovelBlockDiscussionQueryCoordinator novelBlockDiscussionQueryCoordinator(
                DiscussionCoreReadService coreReadService,
                CommentReactionEnrichmentService reactionEnrichmentService,
                GetChapterBlockDiscussionAnchorsUseCase getBlockAnchorsUseCase
        ) {
            return new NovelBlockDiscussionQueryCoordinator(
                    coreReadService, reactionEnrichmentService, getBlockAnchorsUseCase
            );
        }
    }

    @Autowired
    private DiscussionCoreReadService sharedCoreReadService;

    @Autowired
    private ResolveChapterCommentAnchorsByRootIdsUseCase resolveAnchorsUseCase;

    @Autowired
    private GetChapterBlockDiscussionAnchorsUseCase getBlockAnchorsUseCase;

    @Autowired
    private NovelChapterDiscussionFeedQueryCoordinator feedCoordinator;

    @Autowired
    private NovelBlockDiscussionQueryCoordinator blockCoordinator;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Connection coreConnection;

    @Autowired
    private Connection reactionConnection;

    @Autowired
    private CommentRepositoryPort commentRepositoryPort;

    @Autowired
    private ChapterCommentAnchorRepositoryPort anchorRepositoryPort;

    @Autowired
    private ChapterAnchorResolutionSourcePort resolutionSourcePort;

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
                commentRepositoryPort,
                anchorRepositoryPort,
                resolutionSourcePort,
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
    @DisplayName("Chapter feed consolidates slice, threads, surviving-root anchors, and identity into 1 Phase A TX before Phase B Reaction TX")
    void chapterFeedConsolidatesPhaseAWithAnchorsBeforePhaseBReaction() throws SQLException {
        assertThat(AopUtils.isAopProxy(sharedCoreReadService)).isTrue();
        assertThat(AopUtils.isAopProxy(resolveAnchorsUseCase)).isTrue();
        assertThat(AopUtils.isAopProxy(feedCoordinator)).isFalse();

        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);
        Comment root = Comment.createRoot(ROOT_ID, target, AUTHOR_ID, "Feed root body", NOW);
        ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(ROOT_ID, CHAPTER_ID, 1L, BLOCK_KEY, BLOCK_TEXT, NOW);
        ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID, 1L, List.of(new ReaderBlock(BLOCK_KEY, BLOCK_TEXT))
        );

        List<String> coreTxNames = new ArrayList<>();
        List<Boolean> coreReadOnlyFlags = new ArrayList<>();

        when(commentRepositoryPort.findActiveRoots(target, CommentSortMode.NEWEST, 0, 20)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            coreReadOnlyFlags.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return new CommentSlice(List.of(root), 0, 20, false);
        });
        when(commentRepositoryPort.findActiveThreadCommentsByRootIds(target, List.of(ROOT_ID))).thenReturn(List.of(root));
        when(anchorRepositoryPort.findByRootCommentIds(List.of(ROOT_ID))).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            coreReadOnlyFlags.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return List.of(anchor);
        });
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return snapshot;
        });
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_ID))).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Map.of(AUTHOR_ID, new UserPublicProfileDTO(AUTHOR_ID, "Author One", null, "author_one"));
        });
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenAnswer(inv -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    assertThat(TransactionSynchronizationManager.getCurrentTransactionName())
                            .isNotEqualTo(coreTxNames.get(0));
                    verify(coreConnection, times(1)).commit();
                    verify(coreConnection, times(1)).close();
                    return Map.of(ROOT_ID, Map.of(ReactionType.LOVE, 3L));
                });

        ChapterDiscussionFeedResponseDTO feed = feedCoordinator.getDiscussionFeed(
                CHAPTER_ID, 0, 20, null, CommentSortMode.NEWEST
        );

        assertThat(feed.items()).hasSize(1);
        assertThat(feed.items().get(0).rootCommentId()).isEqualTo(ROOT_ID);
        assertThat(feed.items().get(0).blockKey()).isEqualTo(BLOCK_KEY);
        assertThat(feed.items().get(0).anchorStatus()).isEqualTo("CURRENT");
        assertThat(feed.items().get(0).reactionSummary().totalCount()).isEqualTo(3L);
        assertThat(activeConnections.get()).isZero();
        assertThat(maxConcurrentConnections.get()).isEqualTo(1);
        assertThat(coreReadOnlyFlags).containsOnly(true);
        assertThat(coreTxNames).hasSize(4);
        assertThat(coreTxNames.stream().distinct().count()).isEqualTo(1);
        verify(dataSource, times(2)).getConnection();
        verify(coreConnection, times(1)).commit();
        verify(reactionConnection, times(1)).commit();
        verify(reactionConnection, times(1)).close();
    }

    @Test
    @DisplayName("Block drawer consolidates block anchors, batch threads, and identity into 1 Phase A TX before Phase B Reaction TX")
    void blockDrawerConsolidatesPhaseABeforePhaseBReaction() throws SQLException {
        assertThat(AopUtils.isAopProxy(getBlockAnchorsUseCase)).isTrue();
        assertThat(AopUtils.isAopProxy(blockCoordinator)).isFalse();

        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);
        Comment root = Comment.createRoot(ROOT_ID, target, AUTHOR_ID, "Block root body", NOW);
        ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(ROOT_ID, CHAPTER_ID, 1L, BLOCK_KEY, BLOCK_TEXT, NOW);
        ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID, 1L, List.of(new ReaderBlock(BLOCK_KEY, BLOCK_TEXT))
        );

        List<String> coreTxNames = new ArrayList<>();
        List<Boolean> coreReadOnlyFlags = new ArrayList<>();

        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            coreReadOnlyFlags.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return snapshot;
        });
        when(anchorRepositoryPort.findByChapterId(CHAPTER_ID)).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return List.of(anchor);
        });
        when(commentRepositoryPort.findActiveThreadCommentsByRootIds(target, List.of(ROOT_ID))).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return List.of(root);
        });
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_ID))).thenAnswer(inv -> {
            coreTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return Map.of(AUTHOR_ID, new UserPublicProfileDTO(AUTHOR_ID, "Author One", null, "author_one"));
        });
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenAnswer(inv -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    verify(coreConnection, times(1)).commit();
                    verify(coreConnection, times(1)).close();
                    return Map.of(ROOT_ID, Map.of(ReactionType.FIRE, 2L));
                });

        ChapterBlockDiscussionResponseDTO blockDto = blockCoordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY, null);

        assertThat(blockDto.threadCount()).isEqualTo(1);
        assertThat(blockDto.commentCount()).isEqualTo(1);
        assertThat(blockDto.threads().get(0).root().reactionSummary().totalCount()).isEqualTo(2L);
        assertThat(activeConnections.get()).isZero();
        assertThat(maxConcurrentConnections.get()).isEqualTo(1);
        assertThat(coreReadOnlyFlags).containsOnly(true);
        assertThat(coreTxNames).hasSize(4);
        assertThat(coreTxNames.stream().distinct().count()).isEqualTo(1);
        verify(dataSource, times(2)).getConnection();
        verify(coreConnection, times(1)).commit();
        verify(reactionConnection, times(1)).commit();
        verify(reactionConnection, times(1)).close();
    }

    @Test
    @DisplayName("Reaction QueryTimeoutException rolls back only Phase B while preserving committed Phase A for feed and block drawer")
    void reactionTimeoutRollsBackOnlyPhaseBForFeedAndBlockDrawer() throws SQLException {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);
        Comment root = Comment.createRoot(ROOT_ID, target, AUTHOR_ID, "Resilient root body", NOW);
        ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(ROOT_ID, CHAPTER_ID, 1L, BLOCK_KEY, BLOCK_TEXT, NOW);
        ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID, 1L, List.of(new ReaderBlock(BLOCK_KEY, BLOCK_TEXT))
        );

        when(commentRepositoryPort.findActiveRoots(target, CommentSortMode.FEATURED, 0, 20))
                .thenReturn(new CommentSlice(List.of(root), 0, 20, false));
        when(commentRepositoryPort.findActiveThreadCommentsByRootIds(target, List.of(ROOT_ID))).thenReturn(List.of(root));
        when(anchorRepositoryPort.findByRootCommentIds(List.of(ROOT_ID))).thenReturn(List.of(anchor));
        when(anchorRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(anchor));
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_ID)))
                .thenReturn(Map.of(AUTHOR_ID, new UserPublicProfileDTO(AUTHOR_ID, "Author One", null, "author_one")));
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenThrow(new QueryTimeoutException("Simulated reaction query timeout"));

        ChapterDiscussionFeedResponseDTO feed = feedCoordinator.getDiscussionFeed(
                CHAPTER_ID, 0, 20, VIEWER_ID, CommentSortMode.FEATURED
        );
        assertThat(feed.items()).hasSize(1);
        assertThat(feed.items().get(0).author().displayName()).isEqualTo("Author One");
        assertThat(feed.items().get(0).reactionSummary()).isNull();
        verify(coreConnection, times(1)).commit();
        verify(reactionConnection, times(1)).rollback();

        // Reset connection sequencing for block drawer call
        setUp();
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);
        when(anchorRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(anchor));
        when(commentRepositoryPort.findActiveThreadCommentsByRootIds(target, List.of(ROOT_ID))).thenReturn(List.of(root));
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_ID)))
                .thenReturn(Map.of(AUTHOR_ID, new UserPublicProfileDTO(AUTHOR_ID, "Author One", null, "author_one")));
        when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(ReactionTargetType.COMMENT, Set.of(ROOT_ID)))
                .thenThrow(new QueryTimeoutException("Simulated block reaction query timeout"));

        ChapterBlockDiscussionResponseDTO blockDto = blockCoordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY, VIEWER_ID);
        assertThat(blockDto.threadCount()).isEqualTo(1);
        assertThat(blockDto.threads().get(0).root().reactionSummary()).isNull();
        verify(coreConnection, times(1)).commit();
        verify(reactionConnection, times(1)).rollback();
    }

    @Test
    @DisplayName("Core QueryTimeoutException in Phase A rolls back cleanly without invoking Phase B")
    void coreTimeoutRollsBackPhaseAWithoutInvokingPhaseB() throws SQLException {
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID))
                .thenThrow(new QueryTimeoutException("Simulated Novel anchor snapshot timeout"));

        assertThatThrownBy(() -> blockCoordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY, VIEWER_ID))
                .isInstanceOf(QueryTimeoutException.class)
                .isNotInstanceOf(UnexpectedRollbackException.class);

        verify(coreConnection, times(1)).rollback();
        verify(coreConnection, never()).commit();
        verify(coreConnection, times(1)).close();
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());
    }
}
