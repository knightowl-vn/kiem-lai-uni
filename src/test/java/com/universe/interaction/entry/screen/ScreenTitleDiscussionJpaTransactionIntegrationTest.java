package com.universe.interaction.entry.screen;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.entry.screen.dto.ScreenDiscussionFeedResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.test.TestDatabaseSupport;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
 * Real Hibernate / {@link JpaTransactionManager} integration test against the isolated
 * local {@code kiemlai_test} MySQL database ({@link TestDatabaseSupport}).
 *
 * <p>Verifies actual JPA/MySQL transaction lifecycles, JDBC connection acquisitions,
 * sequential Phase A / Phase B ordering, rollback isolation on Reaction failures,
 * core rollback propagation, and {@code Server-Timing} output under the {@code perf} profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("perf")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ScreenTitleDiscussionJpaTransactionIntegrationTest.JdbcProbeConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "security.remember-me.key=test-secret-key-screen-tx-audit-12345",
        "security.remember-me.secure-cookie=false",
        "spring.mail.username=test@universe.local",
        "spring.mail.password=testpassword",
        "cloudinary.cloud_name=test",
        "cloudinary.api_key=test",
        "cloudinary.api_secret=test",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret"
})
@DisplayName("ScreenTitleDiscussionJpaTransactionIntegrationTest — Real JPA & Isolated MySQL Verification")
class ScreenTitleDiscussionJpaTransactionIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class JdbcProbeConfig {

        @Bean
        static BeanPostProcessor jdbcProbeDataSourcePostProcessor() {
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
                        if ("setAutoCommit".equals(name) && args != null && args.length == 1 && Boolean.FALSE.equals(args[0])) {
                            JdbcProbeTracker.BEGIN_TX_COUNT.incrementAndGet();
                        } else if ("setReadOnly".equals(name) && args != null && args.length == 1 && Boolean.TRUE.equals(args[0])) {
                            JdbcProbeTracker.SET_READ_ONLY_COUNT.incrementAndGet();
                        } else if ("commit".equals(name)) {
                            JdbcProbeTracker.COMMIT_COUNT.incrementAndGet();
                        } else if ("rollback".equals(name)) {
                            JdbcProbeTracker.ROLLBACK_COUNT.incrementAndGet();
                        } else if ("close".equals(name)) {
                            JdbcProbeTracker.recordConnectionClosed();
                        }
                        Object result = method.invoke(delegate, args);
                        if (("prepareStatement".equals(name) || "createStatement".equals(name))
                                && result instanceof PreparedStatement ps) {
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
        static final AtomicInteger BEGIN_TX_COUNT = new AtomicInteger();
        static final AtomicInteger SET_READ_ONLY_COUNT = new AtomicInteger();
        static final AtomicInteger COMMIT_COUNT = new AtomicInteger();
        static final AtomicInteger ROLLBACK_COUNT = new AtomicInteger();
        static final AtomicInteger CLOSE_COUNT = new AtomicInteger();
        static final AtomicInteger SQL_EXEC_COUNT = new AtomicInteger();

        static void resetAll() {
            CONNECTION_ACQ_COUNT.set(0);
            ACTIVE_CONNECTIONS.set(0);
            MAX_CONCURRENT_CONNECTIONS.set(0);
            BEGIN_TX_COUNT.set(0);
            SET_READ_ONLY_COUNT.set(0);
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
    private ScreenTitleDiscussionCoreReadService coreReadService;

    @Autowired
    private CommentReactionEnrichmentService reactionEnrichmentService;

    @Autowired
    private ScreenTitleDiscussionQueryCoordinator coordinator;

    @Autowired
    private MockMvc mockMvc;

    @SpyBean
    private ReactionRepositoryPort reactionRepositoryPort;

    @SpyBean
    private UserIdentityContract userIdentityContract;

    private UUID publishedTitleId;
    private UUID draftTitleId;
    private UUID emptyTitleId;
    private UUID entryId;
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
        publishedTitleId = UUID.randomUUID();
        draftTitleId = UUID.randomUUID();
        emptyTitleId = UUID.randomUUID();
        entryId = UUID.randomUUID();
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
    @DisplayName("Real JpaTransactionManager executes Phase A + Phase B in 2 sequential transactions and returns Server-Timing")
    void realJpaPopulatedFeedUsesTwoSequentialTransactionsAndPreservesContracts() throws Exception {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThat(AopUtils.isAopProxy(sharedCoreReadService)).isTrue();
        assertThat(AopUtils.isAopProxy(coreReadService)).isFalse();
        assertThat(AopUtils.isAopProxy(reactionEnrichmentService)).isFalse();
        assertThat(AopUtils.isAopProxy(coordinator)).isFalse();

        // Guest populated feed (NEWEST)
        JdbcProbeTracker.resetAll();
        ScreenDiscussionFeedResponseDTO guestFeed = coordinator.getDiscussionFeed(
                publishedTitleId, 0, 20, null, CommentSortMode.NEWEST
        );

        assertThat(guestFeed.threadCount()).isEqualTo(2);
        assertThat(guestFeed.commentCount()).isEqualTo(3);
        assertThat(guestFeed.threads()).hasSize(2);
        assertThat(guestFeed.threads().get(0).root().id()).isEqualTo(root2Id);
        assertThat(guestFeed.threads().get(1).root().id()).isEqualTo(root1Id);
        assertThat(guestFeed.threads().get(1).root().origin().badgeText()).isEqualTo("Tập 1");
        assertThat(guestFeed.threads().get(1).root().reactionSummary().totalCount()).isEqualTo(2L);
        assertThat(guestFeed.threads().get(1).root().reactionSummary().currentUserReaction()).isNull();
        // Tombstone reply retained cleanly with redacted author/origin/reaction, active descendant preserved
        assertThat(guestFeed.threads().get(1).replies()).hasSize(2);
        assertThat(guestFeed.threads().get(1).replies().get(0).tombstone()).isTrue();
        assertThat(guestFeed.threads().get(1).replies().get(0).author()).isNull();
        assertThat(guestFeed.threads().get(1).replies().get(1).tombstone()).isFalse();
        assertThat(guestFeed.threads().get(1).replies().get(1).author().displayName()).isEqualTo("Author Two");

        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

        // Authenticated populated feed (FEATURED)
        JdbcProbeTracker.resetAll();
        ScreenDiscussionFeedResponseDTO authFeed = coordinator.getDiscussionFeed(
                publishedTitleId, 0, 20, viewerId, CommentSortMode.FEATURED
        );
        assertThat(authFeed.threads()).hasSize(2);
        // root1 has 2 reactions + 1 active reply (score=3) vs root2 (score=0) -> root1 first under FEATURED
        assertThat(authFeed.threads().get(0).root().id()).isEqualTo(root1Id);
        assertThat(authFeed.threads().get(0).root().reactionSummary().currentUserReaction()).isEqualTo("LIKE");
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();

        // MockMvc GET under perf profile verifies Server-Timing reflects 2 acquisitions
        JdbcProbeTracker.resetAll();
        MvcResult mvcResult = mockMvc.perform(get("/api/screen/titles/{titleId}/comments", publishedTitleId)
                        .param("sort", "NEWEST")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadCount").value(2))
                .andExpect(jsonPath("$.commentCount").value(3))
                .andExpect(jsonPath("$.threads[0].root.id").value(root2Id.toString()))
                .andExpect(jsonPath("$.threads[1].root.origin.badgeText").value("Tập 1"))
                .andReturn();

        String serverTiming = mvcResult.getResponse().getHeader("Server-Timing");
        assertThat(serverTiming)
                .isNotNull()
                .contains("conn;dur=")
                .contains("desc=\"2 acquisitions\"");
        assertThat(JdbcProbeTracker.SQL_EXEC_COUNT.get()).isEqualTo(7);

        // Authenticated MockMvc GET verifies AccountStatusFilter (1 acq, 1 SQL) + Phase A (1 acq, 6 SQL) + Phase B (1 acq, 2 SQL) = 3 acquisitions, 9 SQL
        JdbcProbeTracker.resetAll();
        MvcResult authMvcResult = mockMvc.perform(get("/api/screen/titles/{titleId}/comments", publishedTitleId)
                        .with(SecurityMockMvcRequestPostProcessors.user("u_" + viewerId.toString().replace("-", "") + "@test.local").roles("USER"))
                        .param("sort", "FEATURED")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threads[0].root.id").value(root1Id.toString()))
                .andExpect(jsonPath("$.threads[0].root.reactionSummary.currentUserReaction").value("LIKE"))
                .andReturn();

        String authServerTiming = authMvcResult.getResponse().getHeader("Server-Timing");
        assertThat(authServerTiming)
                .isNotNull()
                .contains("conn;dur=")
                .contains("desc=\"3 acquisitions\"");
        assertThat(JdbcProbeTracker.SQL_EXEC_COUNT.get()).isEqualTo(9);
    }

    @Test
    @DisplayName("Real JpaTransactionManager empty feed uses 1 connection and skips Phase B; unpublished rolls back cleanly")
    void emptyAndUnpublishedFeedsExecuteSingleTransaction() {
        JdbcProbeTracker.resetAll();
        ScreenDiscussionFeedResponseDTO emptyFeed = coordinator.getDiscussionFeed(
                emptyTitleId, 0, 20, viewerId, CommentSortMode.NEWEST
        );
        assertThat(emptyFeed.threads()).isEmpty();
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(1);
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());

        JdbcProbeTracker.resetAll();
        assertThatThrownBy(() -> coordinator.getDiscussionFeed(
                draftTitleId, 0, 20, viewerId, CommentSortMode.NEWEST
        )).isInstanceOf(CommentTargetNotEligibleException.class);
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Real JpaTransactionManager rolls back ONLY Phase B when Reaction query throws DB failure after Phase A commits")
    void realJpaReactionFailureRollsBackOnlyPhaseBWithoutUnexpectedRollbackException() {
        // Verify core connection has ALREADY committed (COMMIT_COUNT == 1) and closed (ACTIVE_CONNECTIONS == 1 for Phase B only)
        doAnswer(invocation -> {
            assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.ACTIVE_CONNECTIONS.get()).isEqualTo(1);
            throw new QueryTimeoutException("Simulated DB timeout on interaction_reactions for " + viewerId);
        }).when(reactionRepositoryPort).countReactionsByTargetIdsGroupedByType(any(), any());

        Logger coordinatorLogger = (Logger) LoggerFactory.getLogger(ScreenTitleDiscussionQueryCoordinator.class);
        Logger reactionLogger = (Logger) LoggerFactory.getLogger(CommentReactionEnrichmentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        coordinatorLogger.addAppender(appender);
        reactionLogger.addAppender(appender);
        try {
            JdbcProbeTracker.resetAll();
            ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(
                    publishedTitleId, 0, 20, viewerId, CommentSortMode.NEWEST
            );

            assertThat(response.threads()).hasSize(2);
            assertThat(response.threads().get(0).root().body()).isEqualTo("Root Two");
            assertThat(response.threads().get(1).root().origin().badgeText()).isEqualTo("Tập 1");
            assertThat(response.threads().get(0).root().reactionSummary()).isNull();
            assertThat(response.threads().get(1).root().reactionSummary()).isNull();

            assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
            assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getFormattedMessage())
                    .contains("QueryTimeoutException")
                    .doesNotContain(publishedTitleId.toString())
                    .doesNotContain(viewerId.toString());

            List<String> diagLogs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("[PERF-DIAGNOSTIC]"))
                    .toList();
            assertThat(diagLogs).hasSize(1);
            assertThat(diagLogs.get(0)).contains("failed=true");
        } finally {
            coordinatorLogger.detachAppender(appender);
            reactionLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("Real JpaTransactionManager rolls back Phase A cleanly on core DB failure without invoking Phase B")
    void realJpaCoreFailureRollsBackPhaseAAndSkipsPhaseB() {
        doThrow(new QueryTimeoutException("Simulated identity timeout during Phase A"))
                .when(userIdentityContract).findPublicProfilesByIds(any());

        JdbcProbeTracker.resetAll();
        assertThatThrownBy(() -> coordinator.getDiscussionFeed(
                publishedTitleId, 0, 20, viewerId, CommentSortMode.NEWEST
        ))
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

        insertTitle(publishedTitleId, "screen-tx-pub-" + publishedTitleId, "Published Title", "PUBLISHED", nowTs);
        insertTitle(emptyTitleId, "screen-tx-empty-" + emptyTitleId, "Empty Title", "PUBLISHED", nowTs);
        insertTitle(draftTitleId, "screen-tx-draft-" + draftTitleId, "Draft Title", "DRAFT", null);

        jdbcTemplate.update("""
                INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, published_at, created_at, updated_at)
                VALUES (?, ?, 'tap-1', 'EPISODE', 1, 'Episode 1', 'PUBLISHED', ?, ?, ?)
                """, entryId.toString(), publishedTitleId.toString(), nowTs, nowTs, nowTs);

        insertComment(root1Id, publishedTitleId, author1Id, null, null, entryId, "Root One", "ACTIVE", NOW, NOW, null);
        insertComment(tombstoneReplyId, publishedTitleId, tombstoneAuthorId, root1Id, root1Id, null, null, "DELETED",
                NOW.plusSeconds(1), NOW.plusSeconds(5), NOW.plusSeconds(5));
        insertComment(activeReplyId, publishedTitleId, author2Id, tombstoneReplyId, root1Id, null, "Reply Two", "ACTIVE",
                NOW.plusSeconds(2), NOW.plusSeconds(2), null);
        insertComment(root2Id, publishedTitleId, author2Id, null, null, null, "Root Two", "ACTIVE",
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
            jdbcTemplate.update("DELETE FROM screen_entries WHERE id = ?", entryId.toString());
            jdbcTemplate.update("DELETE FROM screen_titles WHERE id IN (?, ?, ?)",
                    publishedTitleId.toString(), emptyTitleId.toString(), draftTitleId.toString());
            jdbcTemplate.update("DELETE FROM identity_users WHERE id IN (?, ?, ?, ?)",
                    author1Id.toString(), author2Id.toString(), tombstoneAuthorId.toString(), viewerId.toString());
        } catch (Exception ex) {
            System.err.println("Cleanup error in ScreenTitleDiscussionJpaTransactionIntegrationTest: " + ex.getMessage());
        }
    }

    private void insertUser(UUID id, String displayName) {
        String handle = "u_" + id.toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO identity_users (id, email, password_hash, display_name, public_handle, status, role, auth_provider, created_at, updated_at, avatar_customized)
                VALUES (?, ?, 'hash', ?, ?, 'ACTIVE', 'USER', 'LOCAL', ?, ?, false)
                """, id.toString(), handle + "@test.local", displayName, handle, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertTitle(UUID id, String slug, String title, String status, Timestamp publishedAt) {
        Timestamp nowTs = Timestamp.from(NOW);
        jdbcTemplate.update("""
                INSERT INTO screen_titles (id, slug, title, structure, presentation, status, published_at, created_at, updated_at)
                VALUES (?, ?, ?, 'SERIES', 'ANIMATION_3D', ?, ?, ?, ?)
                """, id.toString(), slug, title, status, publishedAt, nowTs, nowTs);
    }

    private void insertComment(
            UUID id,
            UUID titleId,
            UUID authorId,
            UUID parentId,
            UUID rootId,
            UUID originEntryId,
            String body,
            String status,
            Instant created,
            Instant updated,
            Instant deleted
    ) {
        jdbcTemplate.update("""
                INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, origin_screen_entry_id, body, status, created_at, updated_at, deleted_at)
                VALUES (?, 'SCREEN_TITLE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id.toString(),
                titleId.toString(),
                authorId.toString(),
                parentId != null ? parentId.toString() : null,
                rootId != null ? rootId.toString() : null,
                originEntryId != null ? originEntryId.toString() : null,
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
