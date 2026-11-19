package com.universe.interaction.entry.community;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.entry.community.dto.CommunityDiscussionFeedResponseDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.test.TestDatabaseSupport;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Hibernate / {@link JpaTransactionManager} and SQL shape integration test for Community
 * roots-only feed and single-thread endpoints against isolated local {@code kiemlai_test} MySQL
 * ({@link TestDatabaseSupport}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("perf")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(CommunityDiscussionJpaTransactionIntegrationTest.JdbcProbeConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.universe.interaction.entry.community.CommunityDiscussionJpaTransactionIntegrationTest$StatementCounter",
        "security.remember-me.key=test-secret-key-community-tx-audit-123456",
        "security.remember-me.secure-cookie=false",
        "spring.mail.username=test@universe.local",
        "spring.mail.password=testpassword",
        "cloudinary.cloud_name=test",
        "cloudinary.api_key=test",
        "cloudinary.api_secret=test",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret"
})
@DisplayName("CommunityDiscussionJpaTransactionIntegrationTest — Real JPA, SQL Shape & Isolated MySQL Verification")
class CommunityDiscussionJpaTransactionIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    public static class StatementCounter implements StatementInspector {
        private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

        public static void reset() {
            STATEMENTS.clear();
        }

        public static List<String> statements() {
            return Collections.unmodifiableList(new ArrayList<>(STATEMENTS));
        }

        @Override
        public String inspect(String sql) {
            if (sql != null) {
                STATEMENTS.add(sql);
            }
            return sql;
        }
    }

    @TestConfiguration
    static class JdbcProbeConfig {

        @Bean
        static BeanPostProcessor communityJdbcProbeDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if ("dataSource".equals(beanName) && bean instanceof DataSource targetDataSource) {
                        return Proxy.newProxyInstance(
                                DataSource.class.getClassLoader(),
                                new Class<?>[]{DataSource.class},
                                (proxy, method, args) -> {
                                    Object result = method.invoke(targetDataSource, args);
                                    if ("getConnection".equals(method.getName()) && result instanceof Connection rawConn) {
                                        JdbcProbeTracker.recordConnectionAcquired();
                                        return wrapConnection(rawConn);
                                    }
                                    return result;
                                }
                        );
                    }
                    return bean;
                }
            };
        }

        private static Connection wrapConnection(Connection delegate) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("commit".equals(name)) {
                            JdbcProbeTracker.COMMIT_COUNT.incrementAndGet();
                        } else if ("rollback".equals(name)) {
                            JdbcProbeTracker.ROLLBACK_COUNT.incrementAndGet();
                        } else if ("close".equals(name)) {
                            JdbcProbeTracker.recordConnectionClosed();
                        }
                        return method.invoke(delegate, args);
                    }
            );
        }
    }

    static final class JdbcProbeTracker {
        static final AtomicInteger CONNECTION_ACQ_COUNT = new AtomicInteger();
        static final AtomicInteger ACTIVE_CONNECTIONS = new AtomicInteger();
        static final AtomicInteger MAX_CONCURRENT_CONNECTIONS = new AtomicInteger();
        static final AtomicInteger COMMIT_COUNT = new AtomicInteger();
        static final AtomicInteger ROLLBACK_COUNT = new AtomicInteger();
        static final AtomicInteger CLOSE_COUNT = new AtomicInteger();

        static void resetAll() {
            CONNECTION_ACQ_COUNT.set(0);
            ACTIVE_CONNECTIONS.set(0);
            MAX_CONCURRENT_CONNECTIONS.set(0);
            COMMIT_COUNT.set(0);
            ROLLBACK_COUNT.set(0);
            CLOSE_COUNT.set(0);
        }

        static void recordConnectionAcquired() {
            CONNECTION_ACQ_COUNT.incrementAndGet();
            int open = ACTIVE_CONNECTIONS.incrementAndGet();
            MAX_CONCURRENT_CONNECTIONS.accumulateAndGet(open, Math::max);
        }

        static void recordConnectionClosed() {
            CLOSE_COUNT.incrementAndGet();
            ACTIVE_CONNECTIONS.decrementAndGet();
        }
    }

    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DiscussionCoreReadService sharedCoreReadService;

    @Autowired
    private CommentReactionEnrichmentService reactionEnrichmentService;

    @Autowired
    private CommunityPostDiscussionQueryCoordinator coordinator;

    @Autowired
    private MockMvc mockMvc;

    @SpyBean
    private ReactionRepositoryPort reactionRepositoryPort;

    @SpyBean
    private UserIdentityContract userIdentityContract;

    private UUID postAuthorId;
    private UUID rootAuthorId;
    private UUID replyAuthorId;
    private UUID tombstoneAuthorId;
    private UUID viewerId;
    private UUID publishedPostId;
    private UUID emptyPostId;
    private UUID hiddenPostId;
    private UUID otherPostId;
    private UUID root1Id;
    private UUID root2Id;
    private UUID deletedLeafReplyId;
    private UUID tombstoneIntermediateReplyId;
    private UUID activeDescendantReplyId;
    private UUID root2ReplyId;
    private UUID otherPostRootId;
    private UUID reactionRoot1Id;
    private UUID reactionReply1Id;

    @BeforeEach
    void setUp() {
        postAuthorId = UUID.randomUUID();
        rootAuthorId = UUID.randomUUID();
        replyAuthorId = UUID.randomUUID();
        tombstoneAuthorId = UUID.randomUUID();
        viewerId = UUID.randomUUID();
        publishedPostId = UUID.randomUUID();
        emptyPostId = UUID.randomUUID();
        hiddenPostId = UUID.randomUUID();
        otherPostId = UUID.randomUUID();
        root1Id = UUID.randomUUID();
        root2Id = UUID.randomUUID();
        deletedLeafReplyId = UUID.randomUUID();
        tombstoneIntermediateReplyId = UUID.randomUUID();
        activeDescendantReplyId = UUID.randomUUID();
        root2ReplyId = UUID.randomUUID();
        otherPostRootId = UUID.randomUUID();
        reactionRoot1Id = UUID.randomUUID();
        reactionReply1Id = UUID.randomUUID();

        reset(reactionRepositoryPort, userIdentityContract);
        seedFixture();
        StatementCounter.reset();
        JdbcProbeTracker.resetAll();
    }

    @AfterEach
    void tearDown() {
        reset(reactionRepositoryPort, userIdentityContract);
        cleanupFixture();
    }

    @Test
    @DisplayName("Populated Community roots-only feed executes in 2 sequential TXs with zero reply hydration and zero reply-author lookup")
    void realJpaPopulatedFeedUsesTwoSequentialTransactionsWithoutReplyOverfetch() throws Exception {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThat(AopUtils.isAopProxy(sharedCoreReadService)).isTrue();
        assertThat(AopUtils.isAopProxy(reactionEnrichmentService)).isFalse();
        assertThat(AopUtils.isAopProxy(coordinator)).isFalse();

        // 1. Roots-only feed (authenticated NEWEST viewer)
        StatementCounter.reset();
        JdbcProbeTracker.resetAll();
        CommunityDiscussionFeedResponseDTO feed = coordinator.getDiscussionFeed(
                publishedPostId, 0, 10, viewerId, CommentSortMode.NEWEST
        );

        assertThat(feed.commentCount()).isEqualTo(4L); // 2 active roots + 2 active replies (deleted leaf + deleted intermediate excluded)
        assertThat(feed.roots()).hasSize(2);
        assertThat(feed.roots().get(0).root().id()).isEqualTo(root2Id);
        assertThat(feed.roots().get(0).replyCount()).isEqualTo(1L);
        assertThat(feed.roots().get(1).root().id()).isEqualTo(root1Id);
        assertThat(feed.roots().get(1).replyCount()).isEqualTo(1L); // Only activeDescendantReplyId counted under root1
        assertThat(feed.roots().get(1).root().author().displayName()).isEqualTo("Root Author");
        assertThat(feed.roots().get(1).root().reactionSummary().totalCount()).isEqualTo(1L);
        assertThat(feed.roots().get(1).root().reactionSummary().currentUserReaction()).isEqualTo("LOVE");

        // Identity batch MUST receive only root author UUIDs (zero replyAuthorId / tombstoneAuthorId)
        verify(userIdentityContract).findPublicProfilesByIds(Set.of(rootAuthorId));
        // Reaction batch MUST receive only root comment IDs (zero reply IDs)
        verify(reactionRepositoryPort).countReactionsByTargetIdsGroupedByType(
                com.universe.interaction.domain.reaction.ReactionTargetType.COMMENT,
                Set.of(root2Id, root1Id)
        );

        // Zero reply-entity hydration SQL (never selects thread replies by thread_root_comment_id IN (...))
        List<String> feedSqls = StatementCounter.statements();
        System.out.println("COMMUNITY_FEED_NEWEST_AUTH_SQL_COUNT=" + feedSqls.size()
                + " JDBC_CONNS=" + JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()
                + " MAX_CONCURRENT=" + JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()
                + " COMMITS=" + JdbcProbeTracker.COMMIT_COUNT.get());
        assertThat(feedSqls).hasSize(7);
        assertThat(feedSqls).noneMatch(sql -> sql.toLowerCase().contains("order by c1_0.created_at asc"));
        assertThat(feedSqls).noneMatch(sql ->
                sql.toLowerCase().contains("thread_root_comment_id in")
                        && sql.toLowerCase().contains("body"));
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

        // 2. Populated Community single-thread (authenticated viewer)
        reset(reactionRepositoryPort, userIdentityContract);
        StatementCounter.reset();
        JdbcProbeTracker.resetAll();
        CommentThreadResponseDTO thread = coordinator.getCommentThread(publishedPostId, root1Id, viewerId);

        assertThat(thread.root().id()).isEqualTo(root1Id);
        assertThat(thread.root().reactionSummary().totalCount()).isEqualTo(1L);
        assertThat(thread.replies()).hasSize(2); // tombstoneIntermediate + activeDescendant (deletedLeaf pruned)
        assertThat(thread.replies().get(0).tombstone()).isTrue();
        assertThat(thread.replies().get(0).author()).isNull();
        assertThat(thread.replies().get(1).tombstone()).isFalse();
        assertThat(thread.replies().get(1).parentCommentId()).isEqualTo(tombstoneIntermediateReplyId);
        assertThat(thread.replies().get(1).replyToAuthorUserId()).isNull();
        assertThat(thread.replies().get(1).author().displayName()).isEqualTo("Reply Author");
        assertThat(thread.replies().get(1).reactionSummary().currentUserReaction()).isEqualTo("FIRE");

        verify(userIdentityContract).findPublicProfilesByIds(Set.of(rootAuthorId, replyAuthorId));
        System.out.println("COMMUNITY_SINGLE_THREAD_AUTH_SQL_COUNT=" + StatementCounter.statements().size()
                + " JDBC_CONNS=" + JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()
                + " MAX_CONCURRENT=" + JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()
                + " COMMITS=" + JdbcProbeTracker.COMMIT_COUNT.get());
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

        // 3. MockMvc HTTP endpoints parity
        StatementCounter.reset();
        JdbcProbeTracker.resetAll();
        mockMvc.perform(get("/api/community/posts/{postId}/comments", publishedPostId)
                        .param("sort", "FEATURED")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentCount").value(4))
                .andExpect(jsonPath("$.roots.length()").value(2));
        System.out.println("COMMUNITY_FEED_FEATURED_GUEST_HTTP_SQL_COUNT=" + StatementCounter.statements().size()
                + " JDBC_CONNS=" + JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()
                + " COMMITS=" + JdbcProbeTracker.COMMIT_COUNT.get());
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(2);

        JdbcProbeTracker.resetAll();
        mockMvc.perform(get("/api/community/posts/{postId}/comments/{rootId}/thread", publishedPostId, root1Id)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root.id").value(root1Id.toString()))
                .andExpect(jsonPath("$.replies.length()").value(2));
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Empty Community feed commits in 1 TX skipping reply counts/Identity/Reactions; hidden post and cross-post root fail closed (404)")
    void emptyAndIneligibleTargetsUseSingleTransaction() throws Exception {
        // 1. Empty public post -> 1 TX, skips reply counts, Identity, Reactions
        StatementCounter.reset();
        JdbcProbeTracker.resetAll();
        CommunityDiscussionFeedResponseDTO emptyFeed = coordinator.getDiscussionFeed(
                emptyPostId, 0, 10, viewerId, CommentSortMode.FEATURED
        );
        System.out.println("COMMUNITY_FEED_EMPTY_SQL_COUNT=" + StatementCounter.statements().size()
                + " JDBC_CONNS=" + JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()
                + " COMMITS=" + JdbcProbeTracker.COMMIT_COUNT.get());
        assertThat(emptyFeed.roots()).isEmpty();
        assertThat(emptyFeed.commentCount()).isZero();
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        verify(userIdentityContract, never()).findPublicProfilesByIds(any());
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());

        // 2. Non-public post fails closed with 1 TX rollback & 404 via MockMvc
        JdbcProbeTracker.resetAll();
        assertThatThrownBy(() -> coordinator.getDiscussionFeed(hiddenPostId, 0, 10, null, CommentSortMode.NEWEST))
                .isInstanceOf(CommentTargetNotEligibleException.class);
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isEqualTo(1);
        mockMvc.perform(get("/api/community/posts/{postId}/comments", hiddenPostId))
                .andExpect(status().isNotFound());

        // 3. Cross-post root fails closed with 1 TX rollback & 404 via MockMvc
        JdbcProbeTracker.resetAll();
        assertThatThrownBy(() -> coordinator.getCommentThread(publishedPostId, otherPostRootId, null))
                .isInstanceOf(CommentNotFoundException.class);
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isEqualTo(1);
        mockMvc.perform(get("/api/community/posts/{postId}/comments/{rootId}/thread", publishedPostId, otherPostRootId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Real JpaTransactionManager rolls back ONLY Phase B on Reaction timeout for both Community feed and single thread")
    void realJpaReactionTimeoutRollsBackOnlyPhaseB() {
        doAnswer(inv -> {
            assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.ACTIVE_CONNECTIONS.get()).isEqualTo(1);
            throw new QueryTimeoutException("Simulated Community reaction timeout for " + viewerId);
        }).when(reactionRepositoryPort).countReactionsByTargetIdsGroupedByType(any(), any());

        Logger reactionLogger = (Logger) LoggerFactory.getLogger(CommentReactionEnrichmentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        reactionLogger.addAppender(appender);
        try {
            JdbcProbeTracker.resetAll();
            CommunityDiscussionFeedResponseDTO feed = coordinator.getDiscussionFeed(
                    publishedPostId, 0, 10, viewerId, CommentSortMode.NEWEST
            );
            assertThat(feed.roots()).hasSize(2);
            assertThat(feed.roots().get(0).root().reactionSummary()).isNull();
            assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
            assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

            JdbcProbeTracker.resetAll();
            CommentThreadResponseDTO thread = coordinator.getCommentThread(publishedPostId, root1Id, viewerId);
            assertThat(thread.root().id()).isEqualTo(root1Id);
            assertThat(thread.root().reactionSummary()).isNull();
            assertThat(thread.replies()).hasSize(2);
            assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
            assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings).hasSize(2);
            for (ILoggingEvent warn : warnings) {
                assertThat(warn.getFormattedMessage())
                        .contains("QueryTimeoutException")
                        .doesNotContain(publishedPostId.toString())
                        .doesNotContain(root1Id.toString())
                        .doesNotContain(viewerId.toString());
            }
        } finally {
            reactionLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("Real JpaTransactionManager rolls back Phase A cleanly on core timeout without invoking Phase B")
    void realJpaCoreTimeoutRollsBackPhaseAWithoutPhaseB() {
        doThrow(new QueryTimeoutException("Simulated Identity timeout during Community Phase A"))
                .when(userIdentityContract).findPublicProfilesByIds(any());

        JdbcProbeTracker.resetAll();
        assertThatThrownBy(() -> coordinator.getDiscussionFeed(publishedPostId, 0, 10, viewerId, CommentSortMode.FEATURED))
                .isInstanceOf(QueryTimeoutException.class)
                .isNotInstanceOf(UnexpectedRollbackException.class);

        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(1);
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());
    }

    private void seedFixture() {
        Timestamp nowTs = Timestamp.from(NOW);
        insertUser(postAuthorId, "Post Author");
        insertUser(rootAuthorId, "Root Author");
        insertUser(replyAuthorId, "Reply Author");
        insertUser(tombstoneAuthorId, "Deleted Author");
        insertUser(viewerId, "Viewer User");

        insertPost(publishedPostId, postAuthorId, "Published Post", "PUBLISHED", nowTs);
        insertPost(emptyPostId, postAuthorId, "Empty Post", "PUBLISHED", nowTs);
        insertPost(hiddenPostId, postAuthorId, "Hidden Post", "HIDDEN", nowTs);
        insertPost(otherPostId, postAuthorId, "Other Post", "PUBLISHED", nowTs);

        insertComment(root1Id, publishedPostId, rootAuthorId, null, null, "Root One", "ACTIVE", NOW, NOW, null);
        insertComment(deletedLeafReplyId, publishedPostId, tombstoneAuthorId, root1Id, root1Id, null, "DELETED",
                NOW.plusSeconds(1), NOW.plusSeconds(5), NOW.plusSeconds(5));
        insertComment(tombstoneIntermediateReplyId, publishedPostId, tombstoneAuthorId, root1Id, root1Id, null, "DELETED",
                NOW.plusSeconds(2), NOW.plusSeconds(6), NOW.plusSeconds(6));
        insertComment(activeDescendantReplyId, publishedPostId, replyAuthorId, tombstoneIntermediateReplyId, root1Id, "Active Descendant", "ACTIVE",
                NOW.plusSeconds(3), NOW.plusSeconds(3), null);

        insertComment(root2Id, publishedPostId, rootAuthorId, null, null, "Root Two", "ACTIVE",
                NOW.plusSeconds(10), NOW.plusSeconds(10), null);
        insertComment(root2ReplyId, publishedPostId, replyAuthorId, root2Id, root2Id, "Reply on Root Two", "ACTIVE",
                NOW.plusSeconds(11), NOW.plusSeconds(11), null);

        insertComment(otherPostRootId, otherPostId, rootAuthorId, null, null, "Other Post Root", "ACTIVE",
                NOW.plusSeconds(12), NOW.plusSeconds(12), null);

        insertReaction(reactionRoot1Id, viewerId, root1Id, "LOVE", nowTs);
        insertReaction(reactionReply1Id, viewerId, activeDescendantReplyId, "FIRE", nowTs);
    }

    private void cleanupFixture() {
        try {
            jdbcTemplate.update("DELETE FROM interaction_reactions WHERE id IN (?, ?)",
                    reactionRoot1Id.toString(), reactionReply1Id.toString());
            jdbcTemplate.update("DELETE FROM interaction_comments WHERE id IN (?, ?, ?, ?)",
                    activeDescendantReplyId.toString(), tombstoneIntermediateReplyId.toString(),
                    deletedLeafReplyId.toString(), root2ReplyId.toString());
            jdbcTemplate.update("DELETE FROM interaction_comments WHERE id IN (?, ?, ?)",
                    root1Id.toString(), root2Id.toString(), otherPostRootId.toString());
            jdbcTemplate.update("DELETE FROM community_posts WHERE id IN (?, ?, ?, ?)",
                    publishedPostId.toString(), emptyPostId.toString(), hiddenPostId.toString(), otherPostId.toString());
            jdbcTemplate.update("DELETE FROM identity_users WHERE id IN (?, ?, ?, ?, ?)",
                    postAuthorId.toString(), rootAuthorId.toString(), replyAuthorId.toString(),
                    tombstoneAuthorId.toString(), viewerId.toString());
        } catch (Exception ex) {
            System.err.println("Cleanup error in CommunityDiscussionJpaTransactionIntegrationTest: " + ex.getMessage());
        }
    }

    private void insertUser(UUID id, String displayName) {
        String handle = "u_" + id.toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO identity_users (id, email, password_hash, display_name, public_handle, status, role, auth_provider, created_at, updated_at, avatar_customized)
                VALUES (?, ?, 'hash', ?, ?, 'ACTIVE', 'USER', 'LOCAL', ?, ?, false)
                """, id.toString(), handle + "@test.local", displayName, handle, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertPost(UUID id, UUID authorId, String caption, String status, Timestamp ts) {
        jdbcTemplate.update("""
                INSERT INTO community_posts (id, author_user_id, caption, image_media_asset_id, content_version, status, published_at, review_requested_at, created_at, updated_at)
                VALUES (?, ?, ?, NULL, 0, ?, ?, NULL, ?, ?)
                """, id.toString(), authorId.toString(), caption, status, ts, ts, ts);
    }

    private void insertComment(
            UUID id,
            UUID postId,
            UUID authorId,
            UUID parentId,
            UUID rootId,
            String body,
            String status,
            Instant created,
            Instant updated,
            Instant deleted
    ) {
        jdbcTemplate.update("""
                INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at)
                VALUES (?, 'COMMUNITY_POST', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id.toString(),
                postId.toString(),
                authorId.toString(),
                parentId != null ? parentId.toString() : null,
                rootId != null ? rootId.toString() : null,
                body,
                status,
                Timestamp.from(created),
                Timestamp.from(updated),
                deleted != null ? Timestamp.from(deleted) : null
        );
    }

    private void insertReaction(UUID id, UUID userId, UUID targetId, String type, Timestamp ts) {
        jdbcTemplate.update("""
                INSERT INTO interaction_reactions (id, user_id, target_type, target_id, reaction_type, created_at, updated_at)
                VALUES (?, ?, 'COMMENT', ?, ?, ?, ?)
                """, id.toString(), userId.toString(), targetId.toString(), type, ts, ts);
    }
}
