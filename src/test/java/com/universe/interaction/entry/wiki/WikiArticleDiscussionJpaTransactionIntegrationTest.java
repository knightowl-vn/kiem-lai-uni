package com.universe.interaction.entry.wiki;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.interaction.entry.wiki.dto.WikiDiscussionFeedResponseDTO;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
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
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * Real Hibernate / {@link JpaTransactionManager} integration test for Wiki discussion feed and single-thread
 * endpoints against the isolated local {@code kiemlai_test} MySQL database ({@link TestDatabaseSupport}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("perf")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(WikiArticleDiscussionJpaTransactionIntegrationTest.JdbcProbeConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "security.remember-me.key=test-secret-key-wiki-tx-audit-123456",
        "security.remember-me.secure-cookie=false",
        "spring.mail.username=test@universe.local",
        "spring.mail.password=testpassword",
        "cloudinary.cloud_name=test",
        "cloudinary.api_key=test",
        "cloudinary.api_secret=test",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret"
})
@DisplayName("WikiArticleDiscussionJpaTransactionIntegrationTest — Real JPA & Isolated MySQL Verification")
class WikiArticleDiscussionJpaTransactionIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class JdbcProbeConfig {

        @Bean
        static BeanPostProcessor wikiJdbcProbeDataSourcePostProcessor() {
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
                        Object result = method.invoke(delegate, args);
                        if ("prepareStatement".equals(name) && result instanceof PreparedStatement ps) {
                            return wrapStatement(ps, PreparedStatement.class);
                        }
                        if ("createStatement".equals(name) && result instanceof Statement st) {
                            return wrapStatement(st, Statement.class);
                        }
                        return result;
                    }
            );
        }

        private static <T extends Statement> T wrapStatement(T delegate, Class<T> iface) {
            return iface.cast(Proxy.newProxyInstance(
                    iface.getClassLoader(),
                    new Class<?>[]{iface},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("execute".equals(name) || "executeQuery".equals(name) || "executeUpdate".equals(name)) {
                            JdbcProbeTracker.SQL_EXEC_COUNT.incrementAndGet();
                        }
                        return method.invoke(delegate, args);
                    }
            ));
        }
    }

    static final class JdbcProbeTracker {
        static final AtomicInteger CONNECTION_ACQ_COUNT = new AtomicInteger();
        static final AtomicInteger ACTIVE_CONNECTIONS = new AtomicInteger();
        static final AtomicInteger MAX_CONCURRENT_CONNECTIONS = new AtomicInteger();
        static final AtomicInteger COMMIT_COUNT = new AtomicInteger();
        static final AtomicInteger ROLLBACK_COUNT = new AtomicInteger();
        static final AtomicInteger CLOSE_COUNT = new AtomicInteger();
        static final AtomicInteger SQL_EXEC_COUNT = new AtomicInteger();

        static void resetAll() {
            CONNECTION_ACQ_COUNT.set(0);
            ACTIVE_CONNECTIONS.set(0);
            MAX_CONCURRENT_CONNECTIONS.set(0);
            COMMIT_COUNT.set(0);
            ROLLBACK_COUNT.set(0);
            CLOSE_COUNT.set(0);
            SQL_EXEC_COUNT.set(0);
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
    private WikiArticleDiscussionQueryCoordinator coordinator;

    @Autowired
    private MockMvc mockMvc;

    @SpyBean
    private ReactionRepositoryPort reactionRepositoryPort;

    @SpyBean
    private UserIdentityContract userIdentityContract;

    private UUID publishedArticleId;
    private UUID draftArticleId;
    private UUID emptyArticleId;
    private UUID author1Id;
    private UUID author2Id;
    private UUID tombstoneAuthorId;
    private UUID viewerId;
    private UUID root1Id;
    private UUID root2Id;
    private UUID tombstoneReplyId;
    private UUID activeReplyId;
    private UUID reaction1Id;
    private UUID reaction2Id;

    @BeforeEach
    void setUp() {
        publishedArticleId = UUID.randomUUID();
        draftArticleId = UUID.randomUUID();
        emptyArticleId = UUID.randomUUID();
        author1Id = UUID.randomUUID();
        author2Id = UUID.randomUUID();
        tombstoneAuthorId = UUID.randomUUID();
        viewerId = UUID.randomUUID();
        root1Id = UUID.randomUUID();
        root2Id = UUID.randomUUID();
        tombstoneReplyId = UUID.randomUUID();
        activeReplyId = UUID.randomUUID();
        reaction1Id = UUID.randomUUID();
        reaction2Id = UUID.randomUUID();

        reset(reactionRepositoryPort, userIdentityContract);
        seedFixture();
        JdbcProbeTracker.resetAll();
    }

    @AfterEach
    void tearDown() {
        reset(reactionRepositoryPort, userIdentityContract);
        cleanupFixture();
    }

    @Test
    @DisplayName("Real JpaTransactionManager executes Wiki feed and single-thread in 2 sequential TXs with MockMvc Server-Timing")
    void realJpaWikiFeedAndThreadUseTwoSequentialTransactions() throws Exception {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThat(AopUtils.isAopProxy(sharedCoreReadService)).isTrue();
        assertThat(AopUtils.isAopProxy(reactionEnrichmentService)).isFalse();
        assertThat(AopUtils.isAopProxy(coordinator)).isFalse();

        // 1. Populated Wiki feed (guest NEWEST)
        JdbcProbeTracker.resetAll();
        WikiDiscussionFeedResponseDTO feed = coordinator.getDiscussionFeed(
                publishedArticleId, 0, 20, null, CommentSortMode.NEWEST
        );
        assertThat(feed.threadCount()).isEqualTo(2);
        assertThat(feed.commentCount()).isEqualTo(3);
        assertThat(feed.threads()).hasSize(2);
        assertThat(feed.threads().get(0).root().id()).isEqualTo(root2Id);
        assertThat(feed.threads().get(1).root().id()).isEqualTo(root1Id);
        assertThat(feed.threads().get(1).replies()).hasSize(2);
        assertThat(feed.threads().get(1).replies().get(0).tombstone()).isTrue();
        assertThat(feed.threads().get(1).replies().get(0).author()).isNull();
        assertThat(feed.threads().get(1).replies().get(1).author().displayName()).isEqualTo("Author Two");
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

        // 2. Populated Wiki single thread (authenticated FEATURED viewer state)
        JdbcProbeTracker.resetAll();
        CommentThreadResponseDTO singleThread = coordinator.getCommentThread(publishedArticleId, root1Id, viewerId);
        assertThat(singleThread.root().id()).isEqualTo(root1Id);
        assertThat(singleThread.root().reactionSummary().totalCount()).isEqualTo(2L);
        assertThat(singleThread.root().reactionSummary().currentUserReaction()).isEqualTo("LIKE");
        assertThat(singleThread.replies()).hasSize(2);
        assertThat(singleThread.replies().get(0).tombstone()).isTrue();
        assertThat(singleThread.replies().get(1).tombstone()).isFalse();
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

        // 3. MockMvc HTTP feed & single thread under actual servlet filter + JpaTransactionManager
        JdbcProbeTracker.resetAll();
        mockMvc.perform(get("/api/wiki/articles/{articleId}/comments", publishedArticleId)
                        .param("sort", "FEATURED")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadCount").value(2))
                .andExpect(jsonPath("$.threads[0].root.id").value(root1Id.toString()));
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

        JdbcProbeTracker.resetAll();
        mockMvc.perform(get("/api/wiki/articles/{articleId}/comments/{rootId}/thread", publishedArticleId, root1Id)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root.id").value(root1Id.toString()));
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Empty Wiki feed commits in 1 TX; draft/missing targets roll back 1 TX and fail closed")
    void emptyFeedAndUnpublishedOrInvalidTargetsUseSingleTransaction() throws Exception {
        // Empty feed -> 1 TX, skips Phase B
        JdbcProbeTracker.resetAll();
        WikiDiscussionFeedResponseDTO emptyFeed = coordinator.getDiscussionFeed(
                emptyArticleId, 0, 20, viewerId, CommentSortMode.NEWEST
        );
        assertThat(emptyFeed.threads()).isEmpty();
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());

        // Draft article feed & thread -> 1 TX rollback -> 404 via MockMvc
        JdbcProbeTracker.resetAll();
        assertThatThrownBy(() -> coordinator.getDiscussionFeed(draftArticleId, 0, 20, null, CommentSortMode.NEWEST))
                .isInstanceOf(PublishedWikiArticleNotFoundException.class);
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isEqualTo(1);

        mockMvc.perform(get("/api/wiki/articles/{articleId}/comments/{rootId}/thread", draftArticleId, root1Id))
                .andExpect(status().isNotFound());

        // Non-existent root -> 1 TX rollback
        JdbcProbeTracker.resetAll();
        assertThatThrownBy(() -> coordinator.getCommentThread(publishedArticleId, UUID.randomUUID(), null))
                .isInstanceOf(CommentNotFoundException.class);
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Real JpaTransactionManager rolls back ONLY Phase B on Reaction timeout for both Wiki feed and thread")
    void realJpaReactionTimeoutRollsBackOnlyPhaseBForWikiFeedAndThread() {
        doAnswer(inv -> {
            assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.ACTIVE_CONNECTIONS.get()).isEqualTo(1);
            throw new QueryTimeoutException("Simulated Wiki reaction timeout for " + viewerId);
        }).when(reactionRepositoryPort).countReactionsByTargetIdsGroupedByType(any(), any());

        Logger reactionLogger = (Logger) LoggerFactory.getLogger(CommentReactionEnrichmentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        reactionLogger.addAppender(appender);
        try {
            // Feed reaction timeout isolation
            JdbcProbeTracker.resetAll();
            WikiDiscussionFeedResponseDTO feed = coordinator.getDiscussionFeed(
                    publishedArticleId, 0, 20, viewerId, CommentSortMode.NEWEST
            );
            assertThat(feed.threads()).hasSize(2);
            assertThat(feed.threads().get(0).root().reactionSummary()).isNull();
            assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
            assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

            // Single-thread reaction timeout isolation
            JdbcProbeTracker.resetAll();
            CommentThreadResponseDTO thread = coordinator.getCommentThread(publishedArticleId, root1Id, viewerId);
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
                        .doesNotContain(publishedArticleId.toString())
                        .doesNotContain(root1Id.toString())
                        .doesNotContain(viewerId.toString());
            }
        } finally {
            reactionLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("Real JpaTransactionManager rolls back Phase A cleanly on core DB timeout without invoking Phase B")
    void realJpaCoreTimeoutRollsBackPhaseAWithoutInvokingPhaseB() {
        doThrow(new QueryTimeoutException("Simulated identity timeout during Wiki Phase A"))
                .when(userIdentityContract).findPublicProfilesByIds(any());

        JdbcProbeTracker.resetAll();
        assertThatThrownBy(() -> coordinator.getCommentThread(publishedArticleId, root1Id, viewerId))
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
        insertUser(author1Id, "Author One");
        insertUser(author2Id, "Author Two");
        insertUser(tombstoneAuthorId, "Deleted Author");
        insertUser(viewerId, "Viewer User");

        insertArticle(publishedArticleId, "Wiki TX Pub", "wiki-tx-pub-" + publishedArticleId, "PUBLISHED", nowTs);
        insertArticle(emptyArticleId, "Wiki TX Empty", "wiki-tx-empty-" + emptyArticleId, "PUBLISHED", nowTs);
        insertArticle(draftArticleId, "Wiki TX Draft", "wiki-tx-draft-" + draftArticleId, "DRAFT", null);

        insertComment(root1Id, publishedArticleId, author1Id, null, null, "Root One", "ACTIVE", NOW, NOW, null);
        insertComment(tombstoneReplyId, publishedArticleId, tombstoneAuthorId, root1Id, root1Id, null, "DELETED",
                NOW.plusSeconds(1), NOW.plusSeconds(5), NOW.plusSeconds(5));
        insertComment(activeReplyId, publishedArticleId, author2Id, tombstoneReplyId, root1Id, "Reply Two", "ACTIVE",
                NOW.plusSeconds(2), NOW.plusSeconds(2), null);
        insertComment(root2Id, publishedArticleId, author2Id, null, null, "Root Two", "ACTIVE",
                NOW.plusSeconds(10), NOW.plusSeconds(10), null);

        insertReaction(reaction1Id, viewerId, root1Id, "LIKE", nowTs);
        insertReaction(reaction2Id, author2Id, root1Id, "LOVE", nowTs);
    }

    private void cleanupFixture() {
        try {
            jdbcTemplate.update("DELETE FROM interaction_reactions WHERE id IN (?, ?)",
                    reaction1Id.toString(), reaction2Id.toString());
            jdbcTemplate.update("DELETE FROM interaction_comments WHERE id IN (?, ?)",
                    activeReplyId.toString(), tombstoneReplyId.toString());
            jdbcTemplate.update("DELETE FROM interaction_comments WHERE id IN (?, ?)",
                    root1Id.toString(), root2Id.toString());
            jdbcTemplate.update("DELETE FROM wiki_articles WHERE id IN (?, ?, ?)",
                    publishedArticleId.toString(), emptyArticleId.toString(), draftArticleId.toString());
            jdbcTemplate.update("DELETE FROM identity_users WHERE id IN (?, ?, ?, ?)",
                    author1Id.toString(), author2Id.toString(), tombstoneAuthorId.toString(), viewerId.toString());
        } catch (Exception ex) {
            System.err.println("Cleanup error in WikiArticleDiscussionJpaTransactionIntegrationTest: " + ex.getMessage());
        }
    }

    private void insertUser(UUID id, String displayName) {
        String handle = "u_" + id.toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO identity_users (id, email, password_hash, display_name, public_handle, status, role, auth_provider, created_at, updated_at, avatar_customized)
                VALUES (?, ?, 'hash', ?, ?, 'ACTIVE', 'USER', 'LOCAL', ?, ?, false)
                """, id.toString(), handle + "@test.local", displayName, handle, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertArticle(UUID id, String title, String slug, String status, Timestamp publishedAt) {
        Timestamp nowTs = Timestamp.from(NOW);
        String publishedBy = "PUBLISHED".equals(status) ? author1Id.toString() : null;
        jdbcTemplate.update("""
                INSERT INTO wiki_articles (id, title, slug, article_type, summary, content, status, created_by, updated_by, published_by, archived_by, aggregate_version, persistence_version, content_version, created_at, updated_at, published_at, archived_at)
                VALUES (?, ?, ?, 'CHARACTER', 'Summary text', 'Content text', ?, ?, ?, ?, NULL, 1, 0, 1, ?, ?, ?, NULL)
                """, id.toString(), title, slug, status, author1Id.toString(), author1Id.toString(), publishedBy, nowTs, nowTs, publishedAt);
    }

    private void insertComment(
            UUID id,
            UUID articleId,
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
                VALUES (?, 'WIKI_ARTICLE', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id.toString(),
                articleId.toString(),
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
