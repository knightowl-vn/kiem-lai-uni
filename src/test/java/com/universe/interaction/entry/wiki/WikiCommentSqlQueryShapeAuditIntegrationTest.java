package com.universe.interaction.entry.wiki;

import com.universe.interaction.application.ports.CommentRevisionSlice;
import com.universe.interaction.application.query.GetPublicCommentRevisionsUseCase;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.CommentRevisionSliceResponseDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.wiki.dto.WikiDiscussionFeedResponseDTO;
import com.universe.test.TestDatabaseSupport;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MS-05E6E: Wiki Comment SQL Query Shape and N+1 Audit Integration Test.
 *
 * <p>Instruments and measures real prepared SQL statements across all Wiki Comment read paths
 * on MySQL to verify:
 * <ul>
 *   <li>1. Discussion Feed scale: increasing roots (1 vs 20) inside a page produces zero query growth (queryCount(small) == queryCount(large));</li>
 *   <li>2. Single Thread scale: increasing replies (1 vs 20) inside a thread produces zero query growth (queryCount(small) == queryCount(large));</li>
 *   <li>3. Revision history scale: increasing revisions (1 vs 10) produces zero query growth without COUNT(*) queries;</li>
 *   <li>4. Author profile lookups: strictly batched via {@code findPublicProfilesByIds} with zero per-user N+1;</li>
 *   <li>5. Tombstone privacy: tombstone author IDs are never passed to identity lookup;</li>
 *   <li>6. Exact query shape documentation: locks the exact query count and purpose of each statement.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.universe.interaction.entry.wiki.WikiCommentSqlQueryShapeAuditIntegrationTest$StatementCounter",
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false",
        "spring.mail.username=test@universe.local",
        "spring.mail.password=testpassword",
        "cloudinary.cloud_name=test",
        "cloudinary.api_key=test",
        "cloudinary.api_secret=test",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret"
})
@DisplayName("MS-05E6E Wiki Comment SQL Query Shape Audit Integration Tests")
class WikiCommentSqlQueryShapeAuditIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private WikiArticleDiscussionQueryCoordinator coordinator;

    @Autowired
    private WikiArticleCommentController controller;

    @Autowired
    private GetPublicCommentRevisionsUseCase getPublicCommentRevisionsUseCase;

    private UUID articleId;
    private UUID authorUserId;

    @BeforeEach
    void setUp() {
        cleanup();
        StatementCounter.reset();
        statistics().clear();

        authorUserId = UUID.randomUUID();
        insertUser(authorUserId, "wiki_audit_author_" + authorUserId + "@test.local", "Audit Author");

        articleId = UUID.randomUUID();
        insertArticle(articleId, "Wiki Audit Article", "wiki-audit-" + articleId, "PUBLISHED", NOW);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        try {
            jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
                try (java.sql.Statement stmt = con.createStatement()) {
                    try {
                        stmt.execute("SET FOREIGN_KEY_CHECKS = 0;");
                        stmt.executeUpdate("DELETE FROM interaction_comment_revisions;");
                        stmt.executeUpdate("DELETE FROM interaction_comments WHERE parent_comment_id IS NOT NULL;");
                        stmt.executeUpdate("DELETE FROM interaction_comments;");
                        stmt.executeUpdate("DELETE FROM wiki_articles WHERE slug LIKE 'wiki-audit-%';");
                        stmt.executeUpdate("DELETE FROM identity_users WHERE email LIKE '%@test.local';");
                    } finally {
                        stmt.execute("SET FOREIGN_KEY_CHECKS = 1;");
                    }
                }
                return null;
            });
        } catch (Exception ex) {
            System.err.println("Cleanup error in WikiCommentSqlQueryShapeAuditIntegrationTest: " + ex.getMessage());
        }
    }

    // =========================================================================
    // 1. READ PATH: DISCUSSION FEED SCALE AUDIT
    // =========================================================================

    @Test
    @DisplayName("Discussion Feed scale: 1 root vs 20 roots produces constant bounded query count (zero N+1)")
    void feedQueryCountRemainsConstantAsPageScales() {
        // --- DATASET A (small): 1 visible root + 1 reply + 2 distinct authors ---
        UUID rootA = UUID.randomUUID();
        UUID replyA = UUID.randomUUID();
        UUID replyAuthorA = UUID.randomUUID();
        insertUser(replyAuthorA, "reply_author_a@test.local", "Reply Author A");
        insertComment(rootA, articleId, authorUserId, null, null, "Root A", "ACTIVE", NOW, NOW, null);
        insertComment(replyA, articleId, replyAuthorA, rootA, rootA, "Reply A", "ACTIVE", NOW.plusSeconds(1), NOW.plusSeconds(1), null);

        StatementCounter.reset();
        statistics().clear();

        WikiDiscussionFeedResponseDTO responseA = coordinator.getDiscussionFeed(articleId, 0, 20);

        assertThat(responseA.threads()).hasSize(1);
        int queriesA = StatementCounter.totalCount();
        int selectsA = StatementCounter.selectCount();
        assertThat(StatementCounter.insertCount()).isZero();
        assertThat(StatementCounter.updateCount()).isZero();
        assertThat(StatementCounter.deleteCount()).isZero();

        // Feed must never touch revisions table
        assertThat(StatementCounter.statements().stream().noneMatch(s -> s.toLowerCase().contains("interaction_comment_revisions")))
                .as("Feed read must execute ZERO revision queries")
                .isTrue();

        // Feed must not issue standalone COUNT(*) for root pagination
        assertThat(StatementCounter.statements().stream().noneMatch(s ->
                s.toLowerCase().contains("interaction_comments")
                && s.toUpperCase().contains("COUNT(")
                && !s.toUpperCase().contains("COUNT(DISTINCT")
                && !s.toUpperCase().contains("GROUP BY")))
                .as("Feed pagination must NOT execute pagination COUNT(*)")
                .isTrue();

        // --- DATASET B (large): 20 visible roots + 40 replies (2 per root) + 20 distinct authors ---
        cleanup();
        authorUserId = UUID.randomUUID();
        insertUser(authorUserId, "wiki_audit_author_" + authorUserId + "@test.local", "Audit Author");
        articleId = UUID.randomUUID();
        insertArticle(articleId, "Wiki Audit Article", "wiki-audit-" + articleId, "PUBLISHED", NOW);

        for (int i = 0; i < 20; i++) {
            UUID rootId = UUID.randomUUID();
            UUID userI = UUID.randomUUID();
            insertUser(userI, "feed_author_" + i + "@test.local", "Feed Author " + i);
            Instant rootTime = NOW.plusSeconds(i * 10L);
            insertComment(rootId, articleId, userI, null, null, "Root " + i, "ACTIVE", rootTime, rootTime, null);

            UUID rep1 = UUID.randomUUID();
            UUID rep2 = UUID.randomUUID();
            insertComment(rep1, articleId, userI, rootId, rootId, "Reply 1 to " + i, "ACTIVE", rootTime.plusSeconds(1), rootTime.plusSeconds(1), null);
            insertComment(rep2, articleId, authorUserId, rootId, rootId, "Reply 2 to " + i, "ACTIVE", rootTime.plusSeconds(2), rootTime.plusSeconds(2), null);
        }

        StatementCounter.reset();
        statistics().clear();

        WikiDiscussionFeedResponseDTO responseB = coordinator.getDiscussionFeed(articleId, 0, 20);

        assertThat(responseB.threads()).hasSize(20);
        int queriesB = StatementCounter.totalCount();
        int selectsB = StatementCounter.selectCount();

        // CRITICAL SCALE ASSERTION: queryCount(small) == queryCount(large)
        assertThat(queriesB)
                .as("Feed query count for 20 roots (%d) must EQUAL query count for 1 root (%d) - zero N+1", queriesB, queriesA)
                .isEqualTo(queriesA);
        assertThat(selectsB)
                .as("Feed SELECT count for 20 roots (%d) must EQUAL SELECT count for 1 root (%d)", selectsB, selectsA)
                .isEqualTo(selectsA);

        // Verify zero revision queries in large feed read
        assertThat(StatementCounter.statements().stream().noneMatch(s -> s.toLowerCase().contains("interaction_comment_revisions")))
                .as("Large feed read must execute ZERO revision queries")
                .isTrue();
    }

    // =========================================================================
    // 2. READ PATH: SINGLE THREAD SCALE AUDIT
    // =========================================================================

    @Test
    @DisplayName("Single Thread scale: 1 reply vs 20 replies produces constant bounded query count (zero N+1)")
    void singleThreadQueryCountRemainsConstantAsRepliesScale() {
        // --- DATASET A (small): 1 root + 1 reply ---
        UUID rootA = UUID.randomUUID();
        UUID replyA = UUID.randomUUID();
        insertComment(rootA, articleId, authorUserId, null, null, "Root Thread A", "ACTIVE", NOW, NOW, null);
        insertComment(replyA, articleId, authorUserId, rootA, rootA, "Reply Thread A", "ACTIVE", NOW.plusSeconds(1), NOW.plusSeconds(1), null);

        StatementCounter.reset();
        statistics().clear();

        CommentThreadResponseDTO responseA = coordinator.getCommentThread(articleId, rootA, null);

        assertThat(responseA.replies()).hasSize(1);
        int queriesA = StatementCounter.totalCount();
        int selectsA = StatementCounter.selectCount();

        // --- DATASET B (large): 1 root + 20 replies with 20 distinct authors ---
        cleanup();
        authorUserId = UUID.randomUUID();
        insertUser(authorUserId, "wiki_audit_author_" + authorUserId + "@test.local", "Audit Author");
        articleId = UUID.randomUUID();
        insertArticle(articleId, "Wiki Audit Article", "wiki-audit-" + articleId, "PUBLISHED", NOW);

        UUID rootB = UUID.randomUUID();
        insertComment(rootB, articleId, authorUserId, null, null, "Root Thread B", "ACTIVE", NOW, NOW, null);

        for (int i = 0; i < 20; i++) {
            UUID userI = UUID.randomUUID();
            insertUser(userI, "thread_author_" + i + "@test.local", "Thread Author " + i);
            UUID repI = UUID.randomUUID();
            insertComment(repI, articleId, userI, rootB, rootB, "Reply " + i, "ACTIVE", NOW.plusSeconds(i + 1L), NOW.plusSeconds(i + 1L), null);
        }

        StatementCounter.reset();
        statistics().clear();

        CommentThreadResponseDTO responseB = coordinator.getCommentThread(articleId, rootB, null);

        assertThat(responseB.replies()).hasSize(20);
        int queriesB = StatementCounter.totalCount();
        int selectsB = StatementCounter.selectCount();

        // CRITICAL SCALE ASSERTION: queryCount(small) == queryCount(large)
        assertThat(queriesB)
                .as("Thread query count for 20 replies (%d) must EQUAL query count for 1 reply (%d) - zero N+1", queriesB, queriesA)
                .isEqualTo(queriesA);
        assertThat(selectsB)
                .as("Thread SELECT count for 20 replies (%d) must EQUAL SELECT count for 1 reply (%d)", selectsB, selectsA)
                .isEqualTo(selectsA);
    }

    // =========================================================================
    // 3. READ PATH: REVISION HISTORY LAZY LOAD & SCALE AUDIT
    // =========================================================================

    @Test
    @DisplayName("Revision history: 1 revision vs 10 revisions produces constant bounded query count without COUNT(*)")
    void revisionHistoryQueryCountRemainsConstantAndExcludesCount() {
        // --- DATASET A (small): 1 revision ---
        UUID comment1Id = UUID.randomUUID();
        insertComment(comment1Id, articleId, authorUserId, null, null, "Current Body 1", "ACTIVE", NOW, NOW.plusSeconds(10), null);
        insertRevision(UUID.randomUUID(), comment1Id, 1, "Original Body 1", NOW);

        StatementCounter.reset();
        statistics().clear();

        ResponseEntity<CommentRevisionSliceResponseDTO> response1 = controller.listCommentRevisions(articleId, comment1Id, 0, 20);

        assertThat(response1.getBody().items()).hasSize(1);
        int queries1 = StatementCounter.totalCount();
        List<String> sql1 = StatementCounter.statements();

        // Must NOT issue COUNT(*) query
        assertThat(sql1.stream().noneMatch(s -> s.toUpperCase().contains("COUNT(")))
                .as("Slice revision queries must NOT issue COUNT(*)")
                .isTrue();

        // --- DATASET B (large): 10 revisions ---
        UUID comment2Id = UUID.randomUUID();
        insertComment(comment2Id, articleId, authorUserId, null, null, "Current Body 2", "ACTIVE", NOW, NOW.plusSeconds(100), null);
        for (int r = 1; r <= 10; r++) {
            insertRevision(UUID.randomUUID(), comment2Id, r, "Revision Body " + r, NOW.plusSeconds(r));
        }

        StatementCounter.reset();
        statistics().clear();

        ResponseEntity<CommentRevisionSliceResponseDTO> response2 = controller.listCommentRevisions(articleId, comment2Id, 0, 20);

        assertThat(response2.getBody().items()).hasSize(10);
        int queries2 = StatementCounter.totalCount();
        List<String> sql2 = StatementCounter.statements();

        // CRITICAL SCALE ASSERTION: queryCount(small) == queryCount(large)
        assertThat(queries2)
                .as("Revision query count for 10 revisions (%d) must EQUAL query count for 1 revision (%d)", queries2, queries1)
                .isEqualTo(queries1);

        assertThat(sql2.stream().noneMatch(s -> s.toUpperCase().contains("COUNT(")))
                .as("Slice revision queries for 10 revisions must NOT issue COUNT(*)")
                .isTrue();

        // Verify deterministic ordering: newest first (revisionNumber DESC)
        assertThat(response2.getBody().items().get(0).revisionNumber()).isEqualTo(10);
        assertThat(response2.getBody().items().get(9).revisionNumber()).isEqualTo(1);

        String revisionSql = sql2.stream()
                .filter(s -> s.toLowerCase().contains("interaction_comment_revisions"))
                .filter(s -> s.toUpperCase().startsWith("SELECT"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected SELECT query on interaction_comment_revisions"));

        String normalized = revisionSql.replaceAll("\\s+", " ").toUpperCase();
        int revOrderIdx = normalized.indexOf("REVISION_NUMBER DESC");
        int idOrderIdx = normalized.indexOf("ID DESC");

        assertThat(revOrderIdx)
                .as("Revision slice query must contain 'REVISION_NUMBER DESC'")
                .isGreaterThanOrEqualTo(0);
        assertThat(idOrderIdx)
                .as("Revision slice query must contain 'ID DESC'")
                .isGreaterThanOrEqualTo(0);
        assertThat(revOrderIdx)
                .as("Revision slice query must order by REVISION_NUMBER DESC before ID DESC")
                .isLessThan(idOrderIdx);
    }

    // =========================================================================
    // 4. AUTHOR PROFILE BATCHING & TOMBSTONE AUDIT
    // =========================================================================

    @Test
    @DisplayName("Author profile lookup is strictly batched (1 SQL) regardless of number of distinct authors")
    void authorProfileLookupIsSingleBatchedQuery() {
        for (int i = 0; i < 20; i++) {
            UUID rootId = UUID.randomUUID();
            UUID userI = UUID.randomUUID();
            insertUser(userI, "batch_author_" + i + "@test.local", "Batch Author " + i);
            Instant time = NOW.plusSeconds(i * 10L);
            insertComment(rootId, articleId, userI, null, null, "Root " + i, "ACTIVE", time, time, null);
        }

        StatementCounter.reset();
        statistics().clear();

        WikiDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(articleId, 0, 20);
        assertThat(response.threads()).hasSize(20);

        long userQueries = StatementCounter.statements().stream()
                .filter(s -> s.toLowerCase().contains("identity_users"))
                .filter(s -> s.toUpperCase().startsWith("SELECT"))
                .count();

        assertThat(userQueries)
                .as("Exactly ONE batched query must be executed for all 20 author profiles (found %d)", userQueries)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Tombstone author identity privacy: tombstone author IDs are excluded from UserIdentity batch lookup")
    void tombstoneAuthorExcludedFromIdentityLookup() {
        UUID tombstoneAuthorId = UUID.randomUUID();
        insertUser(tombstoneAuthorId, "tombstone_author@test.local", "Tombstone Author");

        UUID activeAuthorId = UUID.randomUUID();
        insertUser(activeAuthorId, "active_author@test.local", "Active Author");

        UUID rootId = UUID.randomUUID();
        UUID tombstoneReplyId = UUID.randomUUID();
        UUID activeReplyId = UUID.randomUUID();

        insertComment(rootId, articleId, authorUserId, null, null, "Root", "ACTIVE", NOW, NOW, null);
        insertComment(tombstoneReplyId, articleId, tombstoneAuthorId, rootId, rootId, null, "DELETED", NOW.plusSeconds(1), NOW.plusSeconds(5), NOW.plusSeconds(5));
        insertComment(activeReplyId, articleId, activeAuthorId, tombstoneReplyId, rootId, "Active reply below tombstone", "ACTIVE", NOW.plusSeconds(2), NOW.plusSeconds(2), null);

        StatementCounter.reset();
        statistics().clear();

        CommentThreadResponseDTO thread = coordinator.getCommentThread(articleId, rootId, null);
        assertThat(thread.replies()).hasSize(2);

        // Find the identity_users query
        List<String> userQueries = StatementCounter.statements().stream()
                .filter(s -> s.toLowerCase().contains("identity_users"))
                .filter(s -> s.toUpperCase().startsWith("SELECT"))
                .toList();

        assertThat(userQueries)
                .as("Exactly ONE batched query must be executed for active author profiles")
                .hasSize(1);

        String userSql = userQueries.get(0);
        long placeholderCount = userSql.chars().filter(ch -> ch == '?').count();
        assertThat(placeholderCount)
                .as("identity_users query must contain exactly 2 parameter placeholders for root and active leaf authors, excluding tombstone")
                .isEqualTo(2);

        // Active author must be queried; tombstone author must be excluded
        assertThat(thread.replies().get(0).author()).isNull();
        assertThat(thread.replies().get(1).author()).isNotNull();
        assertThat(thread.replies().get(1).author().displayName()).isEqualTo("Active Author");
    }

    // =========================================================================
    // 5. EXACT QUERY SHAPES DOCUMENTATION & LOCKING
    // =========================================================================

    @Test
    @DisplayName("Exact query shape audit: documents and locks the exact constant SQL query count for all paths")
    void exactQueryShapesAreDocumentedAndLocked() {
        UUID rootId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        insertComment(rootId, articleId, authorUserId, null, null, "Root Comment", "ACTIVE", NOW, NOW, null);
        insertComment(replyId, articleId, authorUserId, rootId, rootId, "Reply Comment", "ACTIVE", NOW.plusSeconds(1), NOW.plusSeconds(1), null);
        insertRevision(UUID.randomUUID(), rootId, 1, "Rev 1", NOW);

        // --- PATH A: FEED READ ---
        StatementCounter.reset();
        coordinator.getDiscussionFeed(articleId, 0, 20);
        int feedQueries = StatementCounter.totalCount();
        System.out.println("AUDIT_WIKI_FEED_SQL: count=" + feedQueries);
        StatementCounter.statements().forEach(s -> System.out.println("  FEED_SQL: " + s));

        // Statements executed for Feed:
        // 1. SELECT exists on wiki_articles (Wiki publication check)
        // 2. SELECT countTargetMetrics on interaction_comments (Target metrics: threadCount & commentCount)
        // 3. SELECT findActiveRoots on interaction_comments (Root slice pagination, limit 21)
        // 4. SELECT findActiveRootsByIds on interaction_comments (Batch load active roots)
        // 5. SELECT findThreadRepliesByRootIds on interaction_comments (Batch load thread replies)
        // 6. SELECT findPublicProfilesByIds on identity_users (Batch load author public profiles)
        // 7. SELECT batch reaction counts on interaction_reactions (Batch load reaction summaries)
        assertThat(feedQueries)
                .as("Wiki discussion feed must execute exactly 7 queries: 1 publication check + 1 metrics aggregate + 1 root slice + 2 batch thread queries + 1 batch author profile query + 1 batch reaction count query")
                .isEqualTo(7);

        // --- PATH B: SINGLE THREAD READ ---
        StatementCounter.reset();
        coordinator.getCommentThread(articleId, rootId, null);
        int threadQueries = StatementCounter.totalCount();
        System.out.println("AUDIT_WIKI_THREAD_SQL: count=" + threadQueries);
        StatementCounter.statements().forEach(s -> System.out.println("  THREAD_SQL: " + s));

        // Statements executed for Single Thread:
        // 1. SELECT exists on wiki_articles (Wiki publication check)
        // 2. SELECT findById on interaction_comments (Target scope validation)
        // 3. SELECT findById on interaction_comments (Root comment lookup in getCommentThreadUseCase)
        // 4. SELECT findThreadReplies on interaction_comments (Thread replies lookup)
        // 5. SELECT findPublicProfilesByIds on identity_users (Batch load author public profiles)
        // 6. SELECT batch reaction counts on interaction_reactions (Batch load reaction summaries)
        assertThat(threadQueries)
                .as("Wiki single thread must execute exactly 6 queries: 1 publication check + 1 target scope validation + 1 root lookup + 1 thread replies load + 1 batch author profile query + 1 batch reaction count query")
                .isEqualTo(6);

        // --- PATH C: REVISION HISTORY READ ---
        StatementCounter.reset();
        controller.listCommentRevisions(articleId, rootId, 0, 20);
        int revisionQueries = StatementCounter.totalCount();
        System.out.println("AUDIT_WIKI_REVISION_SQL: count=" + revisionQueries);
        StatementCounter.statements().forEach(s -> System.out.println("  REVISION_SQL: " + s));

        // Statements executed for Revision History:
        // 1. SELECT exists on wiki_articles (Wiki publication check)
        // 2. SELECT findById on interaction_comments (Target comment lookup & active verification)
        // 3. SELECT findSliceByCommentId on interaction_comment_revisions (Revision slice pagination, limit 21)
        assertThat(revisionQueries)
                .as("Wiki revision history must execute exactly 3 queries: 1 publication check + 1 comment existence/active verification + 1 revision slice query without COUNT(*)")
                .isEqualTo(3);
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private void insertUser(UUID id, String email, String displayName) {
        String handle = "u_" + id.toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO identity_users (id, email, password_hash, display_name, public_handle, status, role, auth_provider, created_at, updated_at, avatar_customized)
                VALUES (?, ?, 'hash', ?, ?, 'ACTIVE', 'USER', 'LOCAL', ?, ?, false)
                """, id.toString(), email, displayName, handle, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertArticle(UUID id, String title, String slug, String status, Instant now) {
        String publishedBy = "PUBLISHED".equals(status) ? authorUserId.toString() : null;
        Timestamp publishedAt = "PUBLISHED".equals(status) ? Timestamp.from(now) : null;
        String archivedBy = "ARCHIVED".equals(status) ? authorUserId.toString() : null;
        Timestamp archivedAt = "ARCHIVED".equals(status) ? Timestamp.from(now) : null;

        jdbcTemplate.update("""
                INSERT INTO wiki_articles (id, title, slug, article_type, summary, content, status, created_by, updated_by, published_by, archived_by, aggregate_version, persistence_version, content_version, created_at, updated_at, published_at, archived_at)
                VALUES (?, ?, ?, 'CHARACTER', 'Summary text', 'Content text', ?, ?, ?, ?, ?, 1, 0, 1, ?, ?, ?, ?)
                """, id.toString(), title, slug, status, authorUserId.toString(), authorUserId.toString(),
                publishedBy, archivedBy, Timestamp.from(now), Timestamp.from(now), publishedAt, archivedAt);
    }

    private void insertComment(UUID id, UUID artId, UUID authorId, UUID parentId, UUID rootId, String body, String status, Instant created, Instant updated, Instant deleted) {
        jdbcTemplate.update("""
                INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at)
                VALUES (?, 'WIKI_ARTICLE', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), artId.toString(), authorId.toString(),
                parentId != null ? parentId.toString() : null,
                rootId != null ? rootId.toString() : null,
                body, status, Timestamp.from(created), Timestamp.from(updated),
                deleted != null ? Timestamp.from(deleted) : null);
    }

    private void insertRevision(UUID id, UUID commentId, int revNum, String body, Instant created) {
        jdbcTemplate.update("""
                INSERT INTO interaction_comment_revisions (id, comment_id, revision_number, body, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, id.toString(), commentId.toString(), revNum, body, Timestamp.from(created));
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    // =========================================================================
    // STATEMENT INSPECTOR INSTRUMENTATION
    // =========================================================================

    public static class StatementCounter implements StatementInspector {
        private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();
        private static final AtomicInteger SELECTS = new AtomicInteger();
        private static final AtomicInteger INSERTS = new AtomicInteger();
        private static final AtomicInteger UPDATES = new AtomicInteger();
        private static final AtomicInteger DELETES = new AtomicInteger();

        public static void reset() {
            STATEMENTS.clear();
            SELECTS.set(0);
            INSERTS.set(0);
            UPDATES.set(0);
            DELETES.set(0);
        }

        public static List<String> statements() {
            return Collections.unmodifiableList(new ArrayList<>(STATEMENTS));
        }

        public static int selectCount() { return SELECTS.get(); }
        public static int insertCount() { return INSERTS.get(); }
        public static int updateCount() { return UPDATES.get(); }
        public static int deleteCount() { return DELETES.get(); }
        public static int totalCount() { return STATEMENTS.size(); }

        @Override
        public String inspect(String sql) {
            if (sql != null) {
                STATEMENTS.add(sql);
                String trimmed = sql.trim().toUpperCase();
                if (trimmed.startsWith("SELECT")) {
                    SELECTS.incrementAndGet();
                } else if (trimmed.startsWith("INSERT")) {
                    INSERTS.incrementAndGet();
                } else if (trimmed.startsWith("UPDATE")) {
                    UPDATES.incrementAndGet();
                } else if (trimmed.startsWith("DELETE")) {
                    DELETES.incrementAndGet();
                }
            }
            return sql;
        }
    }
}
