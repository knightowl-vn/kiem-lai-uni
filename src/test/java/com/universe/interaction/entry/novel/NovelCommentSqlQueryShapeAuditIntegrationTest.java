package com.universe.interaction.entry.novel;

import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.mutation.CreateRootCommentCommand;
import com.universe.interaction.application.mutation.CreateRootCommentUseCase;
import com.universe.interaction.application.mutation.DeleteCommentCommand;
import com.universe.interaction.application.mutation.DeleteCommentUseCase;
import com.universe.interaction.application.mutation.EditCommentCommand;
import com.universe.interaction.application.mutation.EditCommentUseCase;
import com.universe.interaction.application.mutation.ReplyCommentCommand;
import com.universe.interaction.application.mutation.ReplyCommentUseCase;
import com.universe.interaction.application.ports.CommentRevisionSlice;
import com.universe.interaction.application.query.GetPublicCommentRevisionsUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.ChapterBlockDiscussionResponseDTO;
import com.universe.interaction.entry.dto.ChapterDiscussionFeedResponseDTO;
import com.universe.novel.infrastructure.markdown.CommonMarkReaderCanonicalBlocks;
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
 * MS-05E5I-PERF: Novel Comment SQL Query Shape and N+1 Audit Integration Test.
 *
 * <p>Instruments and measures real prepared SQL statements across all Novel Comment read and mutation paths
 * on MySQL to verify:
 * <ul>
 *   <li>1. Bottom Feed scale: increasing roots (1 vs 20) inside a page produces zero query growth;</li>
 *   <li>2. Block Drawer scale: increasing roots (1 vs 10) inside a block produces zero query growth;</li>
 *   <li>3. Revision history is strictly lazy (0 revision queries in feed/drawer) and slice-bounded (0 COUNT(*) queries);</li>
 *   <li>4. Author profile lookups are strictly batched via {@code findPublicProfilesByIdIn} with zero per-user N+1;</li>
 *   <li>5. Mutation query shapes: verified SELECT, INSERT, UPDATE, DELETE counts for create, reply, edit, and delete flows.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.universe.interaction.entry.novel.NovelCommentSqlQueryShapeAuditIntegrationTest$StatementCounter",
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
@DisplayName("MS-05E5I-PERF Novel Comment SQL Query Shape Audit Integration Tests")
class NovelCommentSqlQueryShapeAuditIntegrationTest {

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
    private NovelChapterDiscussionFeedQueryCoordinator feedCoordinator;

    @Autowired
    private NovelBlockDiscussionQueryCoordinator blockCoordinator;

    @Autowired
    private GetPublicCommentRevisionsUseCase getPublicCommentRevisionsUseCase;

    @Autowired
    private CreateRootCommentUseCase createRootCommentUseCase;

    @Autowired
    private ReplyCommentUseCase replyCommentUseCase;

    @Autowired
    private EditCommentUseCase editCommentUseCase;

    @Autowired
    private DeleteCommentUseCase deleteCommentUseCase;

    @Autowired
    private CommonMarkReaderCanonicalBlocks canonicalBlocks;

    private UUID chapterId;
    private UUID volumeId;
    private UUID authorUserId;
    private String blockKey1;
    private String blockText1;
    private String blockKey2;
    private String blockText2;

    private static final java.util.concurrent.atomic.AtomicInteger COUNTER = new java.util.concurrent.atomic.AtomicInteger(80000);

    @BeforeEach
    void setUp() {
        cleanup();
        StatementCounter.reset();
        statistics().clear();

        authorUserId = UUID.randomUUID();
        insertUser(authorUserId, "primary_author_" + authorUserId + "@test.local", "Primary Author");

        volumeId = UUID.randomUUID();
        insertVolume(volumeId);

        chapterId = UUID.randomUUID();
        String chapterMarkdown = "Đoạn văn thứ nhất của chương truyện.\n\nĐoạn văn thứ hai của chương truyện.";
        insertChapter(chapterId, volumeId, chapterMarkdown);

        List<CommonMarkReaderCanonicalBlocks.CanonicalBlock> blocks = canonicalBlocks.extract(chapterMarkdown);
        assertThat(blocks).hasSize(2);
        blockKey1 = blocks.get(0).blockKey();
        blockText1 = blocks.get(0).canonicalText();
        blockKey2 = blocks.get(1).blockKey();
        blockText2 = blocks.get(1).canonicalText();
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
                        stmt.executeUpdate("DELETE FROM novel_chapter_comment_anchors;");
                        stmt.executeUpdate("DELETE FROM interaction_comments WHERE parent_comment_id IS NOT NULL;");
                        stmt.executeUpdate("DELETE FROM interaction_comments;");
                        stmt.executeUpdate("DELETE FROM novel_chapters WHERE slug LIKE 'chap-perf-%';");
                        stmt.executeUpdate("DELETE FROM novel_volumes WHERE slug LIKE 'vol-perf-%';");
                        stmt.executeUpdate("DELETE FROM identity_users WHERE email LIKE '%@test.local';");
                    } finally {
                        stmt.execute("SET FOREIGN_KEY_CHECKS = 1;");
                    }
                }
                return null;
            });
        } catch (Exception ignored) {
        }
    }

    // =========================================================================
    // 1. READ PATH: BOTTOM FEED SCALE TEST (SECTION 3)
    // =========================================================================

    @Test
    @DisplayName("Bottom Feed scale: 1 root vs 20 roots produces constant bounded query count (zero N+1)")
    void bottomFeedQueryCountRemainsConstantAsPageScales() {
        // --- DATASET A: 1 visible root + 1 reply + 2 distinct authors ---
        UUID rootA = UUID.randomUUID();
        UUID replyA = UUID.randomUUID();
        UUID replyAuthorA = UUID.randomUUID();
        insertUser(replyAuthorA, "reply_author_a@test.local", "Reply Author A");
        insertComment(rootA, chapterId, authorUserId, null, null, "Root A", "ACTIVE", NOW, NOW, null);
        insertAnchor(rootA, chapterId, 1L, blockKey1, blockText1);
        insertComment(replyA, chapterId, replyAuthorA, rootA, rootA, "Reply A", "ACTIVE", NOW.plusSeconds(1), NOW.plusSeconds(1), null);

        StatementCounter.reset();
        statistics().clear();

        ChapterDiscussionFeedResponseDTO responseA = feedCoordinator.getDiscussionFeed(chapterId, 0, 20);

        assertThat(responseA.items()).hasSize(1);
        int queriesA = StatementCounter.totalCount();
        int selectsA = StatementCounter.selectCount();
        assertThat(StatementCounter.insertCount()).isZero();
        assertThat(StatementCounter.updateCount()).isZero();
        assertThat(StatementCounter.deleteCount()).isZero();

        // Verify ZERO queries touch comment revisions during feed read
        assertThat(StatementCounter.statements().stream().noneMatch(s -> s.toLowerCase().contains("interaction_comment_revisions")))
                .as("Feed read must execute ZERO revision queries")
                .isTrue();

        // --- DATASET B: 20 visible roots + 40 replies (2 per root) + 20 distinct authors ---
        cleanup();
        insertUser(authorUserId, "primary_author_" + authorUserId + "@test.local", "Primary Author");
        insertVolume(volumeId);
        insertChapter(chapterId, volumeId, "Đoạn văn thứ nhất của chương truyện.\n\nĐoạn văn thứ hai của chương truyện.");

        for (int i = 0; i < 20; i++) {
            UUID rootId = UUID.randomUUID();
            UUID userI = UUID.randomUUID();
            insertUser(userI, "author_" + i + "@test.local", "Author " + i);
            Instant rootTime = NOW.plusSeconds(i * 10L);
            insertComment(rootId, chapterId, userI, null, null, "Root " + i, "ACTIVE", rootTime, rootTime, null);
            insertAnchor(rootId, chapterId, 1L, blockKey1, blockText1);

            UUID reply1 = UUID.randomUUID();
            UUID reply2 = UUID.randomUUID();
            insertComment(reply1, chapterId, userI, rootId, rootId, "Reply 1 of " + i, "ACTIVE", rootTime.plusSeconds(1), rootTime.plusSeconds(1), null);
            insertComment(reply2, chapterId, authorUserId, rootId, rootId, "Reply 2 of " + i, "ACTIVE", rootTime.plusSeconds(2), rootTime.plusSeconds(2), null);
        }

        StatementCounter.reset();
        statistics().clear();

        ChapterDiscussionFeedResponseDTO responseB = feedCoordinator.getDiscussionFeed(chapterId, 0, 20);

        assertThat(responseB.items()).hasSize(20);
        int queriesB = StatementCounter.totalCount();
        int selectsB = StatementCounter.selectCount();

        // CRITICAL SCALE ASSERTION: Total queries for 20 roots must NOT exceed Dataset A
        assertThat(queriesB)
                .as("Query count for 20 roots (%d) must equal query count for 1 root (%d) - zero N+1", queriesB, queriesA)
                .isEqualTo(queriesA);
        assertThat(selectsB)
                .as("Select count for 20 roots (%d) must equal select count for 1 root (%d)", selectsB, selectsA)
                .isEqualTo(selectsA);

        // Verify ZERO revision queries in large feed read
        assertThat(StatementCounter.statements().stream().noneMatch(s -> s.toLowerCase().contains("interaction_comment_revisions")))
                .as("Large feed read must execute ZERO revision queries")
                .isTrue();
    }

    // =========================================================================
    // 2. READ PATH: BLOCK DISCUSSION DRAWER SCALE TEST (SECTION 4)
    // =========================================================================

    @Test
    @DisplayName("Block Drawer scale: 1 root vs 10 roots produces constant bounded query count (zero N+1)")
    void blockDrawerQueryCountRemainsConstantAsBlockGrows() {
        // --- DATASET A: 1 root + 1 reply on block 1 ---
        UUID rootA = UUID.randomUUID();
        UUID replyA = UUID.randomUUID();
        insertComment(rootA, chapterId, authorUserId, null, null, "Root on block 1", "ACTIVE", NOW, NOW, null);
        insertAnchor(rootA, chapterId, 1L, blockKey1, blockText1);
        insertComment(replyA, chapterId, authorUserId, rootA, rootA, "Reply on block 1", "ACTIVE", NOW.plusSeconds(1), NOW.plusSeconds(1), null);

        StatementCounter.reset();
        statistics().clear();

        ChapterBlockDiscussionResponseDTO responseA = blockCoordinator.getBlockDiscussion(chapterId, blockKey1);

        assertThat(responseA.threads()).hasSize(1);
        int queriesA = StatementCounter.totalCount();
        int selectsA = StatementCounter.selectCount();

        // Verify ZERO queries touch comment revisions during drawer read
        assertThat(StatementCounter.statements().stream().noneMatch(s -> s.toLowerCase().contains("interaction_comment_revisions")))
                .as("Drawer read must execute ZERO revision queries")
                .isTrue();

        // --- DATASET B: 10 roots + 20 replies on block 2 with 10 distinct authors ---
        for (int i = 0; i < 10; i++) {
            UUID rootId = UUID.randomUUID();
            UUID userI = UUID.randomUUID();
            insertUser(userI, "drawer_user_" + i + "@test.local", "Drawer User " + i);
            Instant rootTime = NOW.plusSeconds(i * 10L);
            insertComment(rootId, chapterId, userI, null, null, "Root " + i + " on block 2", "ACTIVE", rootTime, rootTime, null);
            insertAnchor(rootId, chapterId, 1L, blockKey2, blockText2);

            UUID rep1 = UUID.randomUUID();
            UUID rep2 = UUID.randomUUID();
            insertComment(rep1, chapterId, userI, rootId, rootId, "Reply 1 to " + i, "ACTIVE", rootTime.plusSeconds(1), rootTime.plusSeconds(1), null);
            insertComment(rep2, chapterId, authorUserId, rootId, rootId, "Reply 2 to " + i, "ACTIVE", rootTime.plusSeconds(2), rootTime.plusSeconds(2), null);
        }

        StatementCounter.reset();
        statistics().clear();

        ChapterBlockDiscussionResponseDTO responseB = blockCoordinator.getBlockDiscussion(chapterId, blockKey2);

        assertThat(responseB.threads()).hasSize(10);
        int queriesB = StatementCounter.totalCount();
        int selectsB = StatementCounter.selectCount();

        assertThat(queriesB)
                .as("Query count for 10 roots in block (%d) must equal query count for 1 root (%d) - zero N+1", queriesB, queriesA)
                .isEqualTo(queriesA);
        assertThat(selectsB)
                .as("Select count for 10 roots in block (%d) must equal select count for 1 root (%d)", selectsB, selectsA)
                .isEqualTo(selectsA);

        assertThat(StatementCounter.statements().stream().noneMatch(s -> s.toLowerCase().contains("interaction_comment_revisions")))
                .as("Large drawer read must execute ZERO revision queries")
                .isTrue();
    }

    // =========================================================================
    // 3. REVISION HISTORY LAZY LOAD & SCALE TEST (SECTION 5)
    // =========================================================================

    @Test
    @DisplayName("Revision history: 1 revision vs 10 revisions produces constant bounded query count without COUNT(*)")
    void revisionHistoryQueryCountRemainsConstantAndExcludesCount() {
        CommentTarget target = CommentTarget.novelChapter(chapterId);

        // Comment 1 with 1 revision
        UUID comment1Id = UUID.randomUUID();
        insertComment(comment1Id, chapterId, authorUserId, null, null, "Current Body 1", "ACTIVE", NOW, NOW.plusSeconds(10), null);
        insertRevision(UUID.randomUUID(), comment1Id, 1, "Original Body 1", NOW);

        StatementCounter.reset();
        statistics().clear();

        CommentRevisionSlice slice1 = getPublicCommentRevisionsUseCase.execute(comment1Id, target, 0, 20);

        assertThat(slice1.items()).hasSize(1);
        int queries1 = StatementCounter.totalCount();
        List<String> sql1 = StatementCounter.statements();

        // Must NOT issue COUNT(*) query
        assertThat(sql1.stream().noneMatch(s -> s.toUpperCase().contains("COUNT(")))
                .as("Slice revision queries must NOT issue COUNT(*)")
                .isTrue();

        // Comment 2 with 10 revisions
        UUID comment2Id = UUID.randomUUID();
        insertComment(comment2Id, chapterId, authorUserId, null, null, "Current Body 2", "ACTIVE", NOW, NOW.plusSeconds(100), null);
        for (int r = 1; r <= 10; r++) {
            insertRevision(UUID.randomUUID(), comment2Id, r, "Revision Body " + r, NOW.plusSeconds(r));
        }

        StatementCounter.reset();
        statistics().clear();

        CommentRevisionSlice slice2 = getPublicCommentRevisionsUseCase.execute(comment2Id, target, 0, 20);

        assertThat(slice2.items()).hasSize(10);
        int queries2 = StatementCounter.totalCount();
        List<String> sql2 = StatementCounter.statements();

        assertThat(queries2)
                .as("Query count for 10 revisions (%d) must equal query count for 1 revision (%d)", queries2, queries1)
                .isEqualTo(queries1);

        assertThat(sql2.stream().noneMatch(s -> s.toUpperCase().contains("COUNT(")))
                .as("Slice revision queries for 10 revisions must NOT issue COUNT(*)")
                .isTrue();
    }

    // =========================================================================
    // 4. AUTHOR PROFILE BATCHING TEST (SECTION 6)
    // =========================================================================

    @Test
    @DisplayName("Author profile lookup is strictly batched (1 SQL) regardless of number of distinct users")
    void authorProfileLookupIsSingleBatchedQuery() {
        // Create 20 roots with 20 distinct authors
        for (int i = 0; i < 20; i++) {
            UUID rootId = UUID.randomUUID();
            UUID userI = UUID.randomUUID();
            insertUser(userI, "batch_author_" + i + "@test.local", "Batch Author " + i);
            Instant time = NOW.plusSeconds(i * 10L);
            insertComment(rootId, chapterId, userI, null, null, "Root " + i, "ACTIVE", time, time, null);
            insertAnchor(rootId, chapterId, 1L, blockKey1, blockText1);
        }

        StatementCounter.reset();
        statistics().clear();

        ChapterDiscussionFeedResponseDTO response = feedCoordinator.getDiscussionFeed(chapterId, 0, 20);

        assertThat(response.items()).hasSize(20);

        long userQueries = StatementCounter.statements().stream()
                .filter(s -> s.toLowerCase().contains("identity_users"))
                .filter(s -> s.toUpperCase().startsWith("SELECT"))
                .count();

        assertThat(userQueries)
                .as("Exactly ONE batched query must be executed for all 20 author profiles (found %d)", userQueries)
                .isEqualTo(1);
    }

    // =========================================================================
    // 5. MUTATION QUERY SHAPES (SECTION 7)
    // =========================================================================

    @Test
    @DisplayName("Mutation query shapes: Create Root, Reply, Edit Changing, Edit No-op, Delete Reply, Delete Root")
    void mutationQueryShapesAreBoundedAndExact() {
        CommentTarget target = CommentTarget.novelChapter(chapterId);

        // A. CREATE ROOT
        StatementCounter.reset();
        statistics().clear();
        CreateRootCommentCommand createRootCmd = new CreateRootCommentCommand(authorUserId, target, "Brand new root body");
        Comment createdRoot = createRootCommentUseCase.execute(createRootCmd);

        int crSelects = StatementCounter.selectCount();
        int crInserts = StatementCounter.insertCount();
        int crUpdates = StatementCounter.updateCount();
        int crDeletes = StatementCounter.deleteCount();
        int crTotal = StatementCounter.totalCount();
        System.out.println("AUDIT_MUTATION CREATE_ROOT: sel=" + crSelects + " ins=" + crInserts + " upd=" + crUpdates + " del=" + crDeletes + " tot=" + crTotal);
        StatementCounter.statements().forEach(s -> System.out.println("  CR_SQL: " + s));

        assertThat(crSelects).as("Create root SELECT count").isEqualTo(2);
        assertThat(crInserts).as("Create root INSERT count").isEqualTo(1);
        assertThat(crUpdates).as("Create root UPDATE count").isZero();
        assertThat(crDeletes).as("Create root DELETE count").isZero();
        assertThat(crTotal).as("Create root TOTAL count").isEqualTo(3);

        // B. CREATE REPLY
        StatementCounter.reset();
        statistics().clear();
        ReplyCommentCommand replyCmd = new ReplyCommentCommand(authorUserId, createdRoot.getId(), "Brand new reply body");
        Comment createdReply = replyCommentUseCase.execute(replyCmd);

        int repSelects = StatementCounter.selectCount();
        int repInserts = StatementCounter.insertCount();
        int repUpdates = StatementCounter.updateCount();
        int repDeletes = StatementCounter.deleteCount();
        int repTotal = StatementCounter.totalCount();
        System.out.println("AUDIT_MUTATION CREATE_REPLY: sel=" + repSelects + " ins=" + repInserts + " upd=" + repUpdates + " del=" + repDeletes + " tot=" + repTotal);
        StatementCounter.statements().forEach(s -> System.out.println("  REP_SQL: " + s));

        assertThat(repSelects).as("Create reply SELECT count").isEqualTo(3);
        assertThat(repInserts).as("Create reply INSERT count").isEqualTo(1);
        assertThat(repUpdates).as("Create reply UPDATE count").isZero();
        assertThat(repDeletes).as("Create reply DELETE count").isZero();
        assertThat(repTotal).as("Create reply TOTAL count").isEqualTo(4);

        // C. EDIT CHANGING BODY
        StatementCounter.reset();
        statistics().clear();
        EditCommentCommand editChangeCmd = new EditCommentCommand(authorUserId, createdRoot.getId(), "Updated root body version 2");
        editCommentUseCase.execute(editChangeCmd);

        int ecSelects = StatementCounter.selectCount();
        int ecInserts = StatementCounter.insertCount();
        int ecUpdates = StatementCounter.updateCount();
        int ecDeletes = StatementCounter.deleteCount();
        int ecTotal = StatementCounter.totalCount();
        System.out.println("AUDIT_MUTATION EDIT_CHANGING: sel=" + ecSelects + " ins=" + ecInserts + " upd=" + ecUpdates + " del=" + ecDeletes + " tot=" + ecTotal);
        StatementCounter.statements().forEach(s -> System.out.println("  EC_SQL: " + s));

        assertThat(ecSelects).as("Changing edit SELECT count").isEqualTo(2);
        assertThat(ecInserts).as("Changing edit INSERT count").isEqualTo(1);
        assertThat(ecUpdates).as("Changing edit UPDATE count").isEqualTo(1);
        assertThat(ecDeletes).as("Changing edit DELETE count").isZero();
        assertThat(ecTotal).as("Changing edit TOTAL count").isEqualTo(4);

        // D. EDIT NORMALIZED NO-OP
        StatementCounter.reset();
        statistics().clear();
        EditCommentCommand editNoOpCmd = new EditCommentCommand(authorUserId, createdRoot.getId(), "  Updated root body version 2  ");
        editCommentUseCase.execute(editNoOpCmd);

        int enSelects = StatementCounter.selectCount();
        int enInserts = StatementCounter.insertCount();
        int enUpdates = StatementCounter.updateCount();
        int enDeletes = StatementCounter.deleteCount();
        int enTotal = StatementCounter.totalCount();
        System.out.println("AUDIT_MUTATION EDIT_NOOP: sel=" + enSelects + " ins=" + enInserts + " upd=" + enUpdates + " del=" + enDeletes + " tot=" + enTotal);
        StatementCounter.statements().forEach(s -> System.out.println("  EN_SQL: " + s));

        assertThat(enSelects).as("No-op edit SELECT count").isEqualTo(1);
        assertThat(enInserts).as("No-op edit INSERT count").isZero();
        assertThat(enUpdates).as("No-op edit UPDATE count").isZero();
        assertThat(enDeletes).as("No-op edit DELETE count").isZero();
        assertThat(enTotal).as("No-op edit TOTAL count").isEqualTo(1);

        // E. DELETE REPLY
        StatementCounter.reset();
        statistics().clear();
        DeleteCommentCommand deleteReplyCmd = new DeleteCommentCommand(authorUserId, createdReply.getId());
        deleteCommentUseCase.execute(deleteReplyCmd);

        int drSelects = StatementCounter.selectCount();
        int drInserts = StatementCounter.insertCount();
        int drUpdates = StatementCounter.updateCount();
        int drDeletes = StatementCounter.deleteCount();
        int drTotal = StatementCounter.totalCount();
        System.out.println("AUDIT_MUTATION DELETE_REPLY: sel=" + drSelects + " ins=" + drInserts + " upd=" + drUpdates + " del=" + drDeletes + " tot=" + drTotal);
        StatementCounter.statements().forEach(s -> System.out.println("  DR_SQL: " + s));

        assertThat(drSelects).as("Delete reply SELECT count (lock + 2 descendant checks)").isEqualTo(3);
        assertThat(drInserts).as("Delete reply INSERT count").isZero();
        assertThat(drUpdates).as("Delete reply UPDATE count").isZero();
        assertThat(drDeletes).as("Delete reply DELETE count (reactions, revisions, comment)").isEqualTo(3);
        assertThat(drTotal).as("Delete reply TOTAL count").isEqualTo(6);

        // F. DELETE ROOT (has 1 revision from edit step)
        StatementCounter.reset();
        statistics().clear();
        DeleteCommentCommand deleteRootCmd = new DeleteCommentCommand(authorUserId, createdRoot.getId());
        deleteCommentUseCase.execute(deleteRootCmd);

        int drootSelects = StatementCounter.selectCount();
        int drootInserts = StatementCounter.insertCount();
        int drootUpdates = StatementCounter.updateCount();
        int drootDeletes = StatementCounter.deleteCount();
        int drootTotal = StatementCounter.totalCount();
        System.out.println("AUDIT_MUTATION DELETE_ROOT: sel=" + drootSelects + " ins=" + drootInserts + " upd=" + drootUpdates + " del=" + drootDeletes + " tot=" + drootTotal);
        StatementCounter.statements().forEach(s -> System.out.println("  DROOT_SQL: " + s));

        assertThat(drootSelects).as("Delete root SELECT count (lock + 2 descendant checks)").isEqualTo(3);
        assertThat(drootInserts).as("Delete root INSERT count").isZero();
        assertThat(drootUpdates).as("Delete root UPDATE count").isZero();
        assertThat(drootDeletes).as("Delete root DELETE count (reactions, revisions, comment)").isEqualTo(3);
        assertThat(drootTotal).as("Delete root TOTAL count").isEqualTo(6);
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private void insertUser(UUID id, String email, String displayName) {
        jdbcTemplate.update("""
                INSERT INTO identity_users (id, email, password_hash, display_name, status, role, auth_provider, created_at, updated_at, avatar_customized)
                VALUES (?, ?, 'hash', ?, 'ACTIVE', 'USER', 'LOCAL', ?, ?, false)
                """, id.toString(), email, displayName, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertVolume(UUID id) {
        int sortOrder = COUNTER.incrementAndGet();
        jdbcTemplate.update("""
                INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, aggregate_version, persistence_version, created_at, updated_at, published_at)
                VALUES (?, 'Tập 1', ?, '', ?, 'PUBLISHED', ?, ?, ?, 1, 0, ?, ?, ?)
                """, id.toString(), "vol-perf-" + id, sortOrder, authorUserId.toString(), authorUserId.toString(), authorUserId.toString(), Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertChapter(UUID id, UUID volId, String content) {
        int chapterNumber = COUNTER.incrementAndGet();
        jdbcTemplate.update("""
                INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, created_by, updated_by, published_by, aggregate_version, persistence_version, content_version, created_at, updated_at, published_at)
                VALUES (?, ?, ?, 'Chương 1', ?, '', ?, 'PUBLISHED', ?, ?, ?, 1, 0, 1, ?, ?, ?)
                """, id.toString(), volId.toString(), chapterNumber, "chap-perf-" + id, content, authorUserId.toString(), authorUserId.toString(), authorUserId.toString(), Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertComment(UUID id, UUID chapId, UUID authorId, UUID parentId, UUID rootId, String body, String status, Instant created, Instant updated, Instant deleted) {
        jdbcTemplate.update("""
                INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at)
                VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), chapId.toString(), authorId.toString(),
                parentId != null ? parentId.toString() : null,
                rootId != null ? rootId.toString() : null,
                body, status, Timestamp.from(created), Timestamp.from(updated),
                deleted != null ? Timestamp.from(deleted) : null);
    }

    private void insertAnchor(UUID rootId, UUID chapId, long version, String blockKey, String text) {
        jdbcTemplate.update("""
                INSERT INTO novel_chapter_comment_anchors (root_comment_id, chapter_id, content_version, block_key, anchor_kind, start_offset, end_offset, selected_text, context_before, context_after, created_at)
                VALUES (?, ?, ?, ?, 'BLOCK', NULL, NULL, ?, '', '', ?)
                """, rootId.toString(), chapId.toString(), version, blockKey, text, Timestamp.from(NOW));
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
