package com.universe.interaction.entry.novel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.entry.dto.ChapterBlockDiscussionResponseDTO;
import com.universe.interaction.entry.dto.ChapterDiscussionFeedResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.novel.application.exceptions.ReaderBlockNotFoundException;
import com.universe.novel.infrastructure.markdown.CommonMarkReaderCanonicalBlocks;
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
 * Real Hibernate / {@link JpaTransactionManager} integration test for Novel chapter discussion feed
 * and block drawer endpoints against the isolated local {@code kiemlai_test} MySQL database ({@link TestDatabaseSupport}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("perf")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(NovelDiscussionJpaTransactionIntegrationTest.JdbcProbeConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "security.remember-me.key=test-secret-key-novel-tx-audit-123456",
        "security.remember-me.secure-cookie=false",
        "spring.mail.username=test@universe.local",
        "spring.mail.password=testpassword",
        "cloudinary.cloud_name=test",
        "cloudinary.api_key=test",
        "cloudinary.api_secret=test",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret"
})
@DisplayName("NovelDiscussionJpaTransactionIntegrationTest — Real JPA & Isolated MySQL Verification")
class NovelDiscussionJpaTransactionIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class JdbcProbeConfig {

        @Bean
        static BeanPostProcessor novelJdbcProbeDataSourcePostProcessor() {
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
    private static final AtomicInteger SEQ = new AtomicInteger(90000);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CommonMarkReaderCanonicalBlocks canonicalBlocks;

    @Autowired
    private DiscussionCoreReadService sharedCoreReadService;

    @Autowired
    private CommentReactionEnrichmentService reactionEnrichmentService;

    @Autowired
    private NovelChapterDiscussionFeedQueryCoordinator feedCoordinator;

    @Autowired
    private NovelBlockDiscussionQueryCoordinator blockCoordinator;

    @Autowired
    private MockMvc mockMvc;

    @SpyBean
    private ReactionRepositoryPort reactionRepositoryPort;

    @SpyBean
    private UserIdentityContract userIdentityContract;

    private UUID author1Id;
    private UUID author2Id;
    private UUID tombstoneAuthorId;
    private UUID viewerId;
    private UUID publishedVolumeId;
    private UUID publishedChapterId;
    private UUID emptyChapterId;
    private UUID draftChapterId;
    private UUID root1Id;
    private UUID root2Id;
    private UUID tombstoneReplyId;
    private UUID activeReplyId;
    private UUID reaction1Id;
    private UUID reaction2Id;
    private String blockKey1;
    private String blockText1;
    private String blockKey2;

    @BeforeEach
    void setUp() {
        author1Id = UUID.randomUUID();
        author2Id = UUID.randomUUID();
        tombstoneAuthorId = UUID.randomUUID();
        viewerId = UUID.randomUUID();
        publishedVolumeId = UUID.randomUUID();
        publishedChapterId = UUID.randomUUID();
        emptyChapterId = UUID.randomUUID();
        draftChapterId = UUID.randomUUID();
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
    @DisplayName("Real JpaTransactionManager executes Novel feed and block drawer in 2 sequential TXs with MockMvc HTTP parity")
    void realJpaFeedAndBlockDrawerUseTwoSequentialTransactions() throws Exception {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThat(AopUtils.isAopProxy(sharedCoreReadService)).isTrue();
        assertThat(AopUtils.isAopProxy(reactionEnrichmentService)).isFalse();
        assertThat(AopUtils.isAopProxy(feedCoordinator)).isFalse();
        assertThat(AopUtils.isAopProxy(blockCoordinator)).isFalse();

        // 1. Populated Novel chapter feed (authenticated NEWEST viewer)
        JdbcProbeTracker.resetAll();
        ChapterDiscussionFeedResponseDTO feed = feedCoordinator.getDiscussionFeed(
                publishedChapterId, 0, 20, viewerId, CommentSortMode.NEWEST
        );
        assertThat(feed.items()).hasSize(2);
        assertThat(feed.items().get(0).rootCommentId()).isEqualTo(root2Id);
        assertThat(feed.items().get(1).rootCommentId()).isEqualTo(root1Id);
        assertThat(feed.items().get(1).blockKey()).isEqualTo(blockKey1);
        assertThat(feed.items().get(1).anchorStatus()).isEqualTo("CURRENT");
        assertThat(feed.items().get(1).passageExcerpt()).isEqualTo(blockText1);
        assertThat(feed.items().get(1).replyCount()).isEqualTo(1);
        assertThat(feed.items().get(1).reactionSummary().totalCount()).isEqualTo(2L);
        assertThat(feed.items().get(1).reactionSummary().currentUserReaction()).isEqualTo("LOVE");
        assertThat(feed.items().get(1).replies()).hasSize(2);
        assertThat(feed.items().get(1).replies().get(0).tombstone()).isTrue();
        assertThat(feed.items().get(1).replies().get(0).author()).isNull();
        assertThat(feed.items().get(1).replies().get(1).author().displayName()).isEqualTo("Author Two");
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

        // 2. Populated Novel block drawer (authenticated viewer)
        JdbcProbeTracker.resetAll();
        ChapterBlockDiscussionResponseDTO blockDto = blockCoordinator.getBlockDiscussion(
                publishedChapterId, blockKey1, viewerId
        );
        assertThat(blockDto.chapterId()).isEqualTo(publishedChapterId);
        assertThat(blockDto.blockKey()).isEqualTo(blockKey1);
        assertThat(blockDto.canonicalText()).isEqualTo(blockText1);
        assertThat(blockDto.threadCount()).isEqualTo(1);
        assertThat(blockDto.commentCount()).isEqualTo(2);
        assertThat(blockDto.threads().get(0).root().id()).isEqualTo(root1Id);
        assertThat(blockDto.threads().get(0).root().reactionSummary().totalCount()).isEqualTo(2L);
        assertThat(blockDto.threads().get(0).root().reactionSummary().currentUserReaction()).isEqualTo("LOVE");
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(2);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

        // 3. MockMvc HTTP endpoints verify controller publication gate + JSON payloads with zero concurrent DB connections
        JdbcProbeTracker.resetAll();
        mockMvc.perform(get("/api/novel/chapters/{chapterId}/comments/feed", publishedChapterId)
                        .param("sort", "FEATURED")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();

        JdbcProbeTracker.resetAll();
        mockMvc.perform(get("/api/novel/chapters/{chapterId}/comments/blocks/{blockKey}", publishedChapterId, blockKey1)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockKey").value(blockKey1))
                .andExpect(jsonPath("$.threadCount").value(1));
        assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
    }

    @Test
    @DisplayName("Empty feed and empty block anchors commit 1 TX skipping Phase B; missing block and draft chapter fail closed (404)")
    void emptyAndMissingTargetsBehaveSafely() throws Exception {
        // 1. Empty chapter feed -> 1 TX, skips Phase B
        JdbcProbeTracker.resetAll();
        ChapterDiscussionFeedResponseDTO emptyFeed = feedCoordinator.getDiscussionFeed(
                emptyChapterId, 0, 20, viewerId, CommentSortMode.NEWEST
        );
        assertThat(emptyFeed.items()).isEmpty();
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());

        // 2. Published chapter block with zero anchors -> 1 TX, skips Phase B
        JdbcProbeTracker.resetAll();
        ChapterBlockDiscussionResponseDTO emptyBlock = blockCoordinator.getBlockDiscussion(
                publishedChapterId, blockKey2, viewerId
        );
        assertThat(emptyBlock.threadCount()).isZero();
        assertThat(emptyBlock.threads()).isEmpty();
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isZero();
        verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());

        // 3. Unknown blockKey throws ReaderBlockNotFoundException and rolls back Phase A cleanly
        JdbcProbeTracker.resetAll();
        assertThatThrownBy(() -> blockCoordinator.getBlockDiscussion(publishedChapterId, "blk-does-not-exist", null))
                .isInstanceOf(ReaderBlockNotFoundException.class);
        assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(1);
        assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isZero();
        assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isEqualTo(1);

        // 4. Draft chapter returns 404 via MockMvc on both feed & block drawer
        mockMvc.perform(get("/api/novel/chapters/{chapterId}/comments/feed", draftChapterId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/novel/chapters/{chapterId}/comments/blocks/{blockKey}", draftChapterId, blockKey1))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Real JpaTransactionManager rolls back ONLY Phase B on Reaction timeout for both feed and block drawer")
    void realJpaReactionTimeoutRollsBackOnlyPhaseB() {
        doAnswer(inv -> {
            assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.ACTIVE_CONNECTIONS.get()).isEqualTo(1);
            throw new QueryTimeoutException("Simulated Novel reaction timeout for " + viewerId);
        }).when(reactionRepositoryPort).countReactionsByTargetIdsGroupedByType(any(), any());

        Logger reactionLogger = (Logger) LoggerFactory.getLogger(CommentReactionEnrichmentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        reactionLogger.addAppender(appender);
        try {
            // Feed reaction timeout isolation
            JdbcProbeTracker.resetAll();
            ChapterDiscussionFeedResponseDTO feed = feedCoordinator.getDiscussionFeed(
                    publishedChapterId, 0, 20, viewerId, CommentSortMode.NEWEST
            );
            assertThat(feed.items()).hasSize(2);
            assertThat(feed.items().get(1).reactionSummary()).isNull();
            assertThat(JdbcProbeTracker.CONNECTION_ACQ_COUNT.get()).isEqualTo(2);
            assertThat(JdbcProbeTracker.MAX_CONCURRENT_CONNECTIONS.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.COMMIT_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.ROLLBACK_COUNT.get()).isEqualTo(1);
            assertThat(JdbcProbeTracker.CLOSE_COUNT.get()).isEqualTo(2);

            // Block drawer reaction timeout isolation
            JdbcProbeTracker.resetAll();
            ChapterBlockDiscussionResponseDTO blockDto = blockCoordinator.getBlockDiscussion(
                    publishedChapterId, blockKey1, viewerId
            );
            assertThat(blockDto.threadCount()).isEqualTo(1);
            assertThat(blockDto.threads().get(0).root().reactionSummary()).isNull();
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
                        .doesNotContain(publishedChapterId.toString())
                        .doesNotContain(blockKey1)
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
        doThrow(new QueryTimeoutException("Simulated Identity timeout during Novel Phase A"))
                .when(userIdentityContract).findPublicProfilesByIds(any());

        JdbcProbeTracker.resetAll();
        assertThatThrownBy(() -> blockCoordinator.getBlockDiscussion(publishedChapterId, blockKey1, viewerId))
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

        String chapterMarkdown = "Đoạn văn thứ nhất của chương thử nghiệm giao dịch.\n\nĐoạn văn thứ hai trống bình luận.";
        List<CommonMarkReaderCanonicalBlocks.CanonicalBlock> blocks = canonicalBlocks.extract(chapterMarkdown);
        blockKey1 = blocks.get(0).blockKey();
        blockText1 = blocks.get(0).canonicalText();
        blockKey2 = blocks.get(1).blockKey();

        insertVolume(publishedVolumeId);
        insertChapter(publishedChapterId, publishedVolumeId, chapterMarkdown, "PUBLISHED", nowTs);
        insertChapter(emptyChapterId, publishedVolumeId, "Nội dung trống.", "PUBLISHED", nowTs);
        insertChapter(draftChapterId, publishedVolumeId, chapterMarkdown, "DRAFT", null);

        insertComment(root1Id, publishedChapterId, author1Id, null, null, "Root One", "ACTIVE", NOW, NOW, null);
        insertComment(tombstoneReplyId, publishedChapterId, tombstoneAuthorId, root1Id, root1Id, null, "DELETED",
                NOW.plusSeconds(1), NOW.plusSeconds(5), NOW.plusSeconds(5));
        insertComment(activeReplyId, publishedChapterId, author2Id, tombstoneReplyId, root1Id, "Reply Two", "ACTIVE",
                NOW.plusSeconds(2), NOW.plusSeconds(2), null);
        insertComment(root2Id, publishedChapterId, author2Id, null, null, "Root Two (Unanchored)", "ACTIVE",
                NOW.plusSeconds(10), NOW.plusSeconds(10), null);

        insertAnchor(root1Id, publishedChapterId, 1L, blockKey1, blockText1);
        insertReaction(reaction1Id, viewerId, root1Id, "LOVE", nowTs);
        insertReaction(reaction2Id, author2Id, root1Id, "FIRE", nowTs);
    }

    private void cleanupFixture() {
        try {
            jdbcTemplate.update("DELETE FROM novel_chapter_comment_anchors WHERE chapter_id IN (?, ?, ?)",
                    publishedChapterId.toString(), emptyChapterId.toString(), draftChapterId.toString());
            jdbcTemplate.update("DELETE FROM interaction_reactions WHERE id IN (?, ?)",
                    reaction1Id.toString(), reaction2Id.toString());
            jdbcTemplate.update("DELETE FROM interaction_comments WHERE id IN (?, ?)",
                    activeReplyId.toString(), tombstoneReplyId.toString());
            jdbcTemplate.update("DELETE FROM interaction_comments WHERE id IN (?, ?)",
                    root1Id.toString(), root2Id.toString());
            jdbcTemplate.update("DELETE FROM novel_chapters WHERE id IN (?, ?, ?)",
                    publishedChapterId.toString(), emptyChapterId.toString(), draftChapterId.toString());
            jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ?", publishedVolumeId.toString());
            jdbcTemplate.update("DELETE FROM identity_users WHERE id IN (?, ?, ?, ?)",
                    author1Id.toString(), author2Id.toString(), tombstoneAuthorId.toString(), viewerId.toString());
        } catch (Exception ex) {
            System.err.println("Cleanup error in NovelDiscussionJpaTransactionIntegrationTest: " + ex.getMessage());
        }
    }

    private void insertUser(UUID id, String displayName) {
        String handle = "u_" + id.toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO identity_users (id, email, password_hash, display_name, public_handle, status, role, auth_provider, created_at, updated_at, avatar_customized)
                VALUES (?, ?, 'hash', ?, ?, 'ACTIVE', 'USER', 'LOCAL', ?, ?, false)
                """, id.toString(), handle + "@test.local", displayName, handle, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertVolume(UUID id) {
        int sortOrder = SEQ.incrementAndGet();
        Timestamp nowTs = Timestamp.from(NOW);
        jdbcTemplate.update("""
                INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, aggregate_version, persistence_version, created_at, updated_at, published_at)
                VALUES (?, 'Tập Test TX', ?, '', ?, 'PUBLISHED', ?, ?, ?, 1, 0, ?, ?, ?)
                """, id.toString(), "vol-tx-" + id, sortOrder, author1Id.toString(), author1Id.toString(), author1Id.toString(), nowTs, nowTs, nowTs);
    }

    private void insertChapter(UUID id, UUID volId, String content, String status, Timestamp publishedAt) {
        int chapterNumber = SEQ.incrementAndGet();
        Timestamp nowTs = Timestamp.from(NOW);
        String publishedBy = "PUBLISHED".equals(status) ? author1Id.toString() : null;
        jdbcTemplate.update("""
                INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, created_by, updated_by, published_by, aggregate_version, persistence_version, content_version, created_at, updated_at, published_at)
                VALUES (?, ?, ?, 'Chương Test TX', ?, '', ?, ?, ?, ?, ?, 1, 0, 1, ?, ?, ?)
                """, id.toString(), volId.toString(), chapterNumber, "chap-tx-" + id, content, status, author1Id.toString(), author1Id.toString(), publishedBy, nowTs, nowTs, publishedAt);
    }

    private void insertComment(
            UUID id,
            UUID chapId,
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
                VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id.toString(),
                chapId.toString(),
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

    private void insertAnchor(UUID rootId, UUID chapId, long version, String blockKey, String text) {
        jdbcTemplate.update("""
                INSERT INTO novel_chapter_comment_anchors (root_comment_id, chapter_id, content_version, block_key, anchor_kind, start_offset, end_offset, selected_text, context_before, context_after, created_at)
                VALUES (?, ?, ?, ?, 'BLOCK', NULL, NULL, ?, '', '', ?)
                """, rootId.toString(), chapId.toString(), version, blockKey, text, Timestamp.from(NOW));
    }

    private void insertReaction(UUID id, UUID userId, UUID targetId, String type, Timestamp ts) {
        jdbcTemplate.update("""
                INSERT INTO interaction_reactions (id, user_id, target_type, target_id, reaction_type, created_at, updated_at)
                VALUES (?, ?, 'COMMENT', ?, ?, ?, ?)
                """, id.toString(), userId.toString(), targetId.toString(), type, ts, ts);
    }
}
