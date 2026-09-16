package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.application.ports.CommentSlice;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTarget;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        CommentPersistenceAdapter.class,
        CommentPersistenceMapper.class
})
@DisplayName("CommentPersistenceAdapter Real MySQL Integration Tests")
class CommentPersistenceAdapterMySQLTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CommentPersistenceAdapter adapter;

    @BeforeEach
    @AfterEach
    void cleanData() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbcTemplate.execute("DELETE FROM interaction_comments;");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    // =========================================================================
    // 1. SAVE & FIND BY ID ROUND-TRIP
    // =========================================================================

    @Test
    @DisplayName("Should round-trip save and findById for active root comment")
    void shouldSaveAndFindActiveRootComment() {
        UUID commentId = UUID.randomUUID();
        CommentTarget target = CommentTarget.novelChapter(UUID.randomUUID());
        UUID authorId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-16T10:00:00Z");

        Comment root = Comment.createRoot(commentId, target, authorId, "Hello chapter discussion", createdAt);
        Comment saved = adapter.save(root);

        assertThat(saved.getId()).isEqualTo(commentId);

        Optional<Comment> found = adapter.findById(commentId);
        assertThat(found).isPresent();
        Comment reloaded = found.get();
        assertThat(reloaded.getId()).isEqualTo(commentId);
        assertThat(reloaded.getTarget()).isEqualTo(target);
        assertThat(reloaded.getAuthorUserId()).isEqualTo(authorId);
        assertThat(reloaded.getParentCommentId()).isNull();
        assertThat(reloaded.getThreadRootCommentId()).isNull();
        assertThat(reloaded.isRoot()).isTrue();
        assertThat(reloaded.getBody()).isEqualTo("Hello chapter discussion");
        assertThat(reloaded.getStatus()).isEqualTo(CommentStatus.ACTIVE);
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("Should round-trip save and findById for direct and nested replies")
    void shouldSaveAndFindDirectAndNestedReplies() {
        UUID rootId = UUID.randomUUID();
        CommentTarget target = CommentTarget.novelChapter(UUID.randomUUID());
        UUID author1 = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-16T10:00:00Z");
        Comment root = Comment.createRoot(rootId, target, author1, "Root comment", t1);
        adapter.save(root);

        // Direct reply (B -> A)
        UUID reply1Id = UUID.randomUUID();
        UUID author2 = UUID.randomUUID();
        Instant t2 = Instant.parse("2026-09-16T10:05:00Z");
        Comment directReply = Comment.createReply(reply1Id, root, author2, "Direct reply to root", t2);
        adapter.save(directReply);

        Optional<Comment> foundDirect = adapter.findById(reply1Id);
        assertThat(foundDirect).isPresent();
        assertThat(foundDirect.get().getParentCommentId()).isEqualTo(rootId);
        assertThat(foundDirect.get().getThreadRootCommentId()).isEqualTo(rootId);
        assertThat(foundDirect.get().isReply()).isTrue();

        // Nested reply (C -> B)
        UUID reply2Id = UUID.randomUUID();
        UUID author3 = UUID.randomUUID();
        Instant t3 = Instant.parse("2026-09-16T10:10:00Z");
        Comment nestedReply = Comment.createReply(reply2Id, directReply, author3, "Nested reply to direct reply", t3);
        adapter.save(nestedReply);

        Optional<Comment> foundNested = adapter.findById(reply2Id);
        assertThat(foundNested).isPresent();
        assertThat(foundNested.get().getParentCommentId()).isEqualTo(reply1Id);
        assertThat(foundNested.get().getThreadRootCommentId()).isEqualTo(rootId);
        assertThat(foundNested.get().isReply()).isTrue();
    }

    @Test
    @DisplayName("Should update existing comment body and preserve timestamps")
    void shouldUpdateExistingCommentBody() {
        UUID commentId = UUID.randomUUID();
        CommentTarget target = CommentTarget.wikiArticle(UUID.randomUUID());
        UUID authorId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-16T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-16T10:30:00Z");

        Comment root = Comment.createRoot(commentId, target, authorId, "Original body", t1);
        adapter.save(root);

        root.edit("Updated body content", t2);
        adapter.save(root);

        Optional<Comment> reloaded = adapter.findById(commentId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getBody()).isEqualTo("Updated body content");
        assertThat(reloaded.get().getCreatedAt()).isEqualTo(t1);
        assertThat(reloaded.get().getUpdatedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("Should save and find soft-deleted tombstone preserving ancestry and nullifying body")
    void shouldSaveAndFindSoftDeletedTombstone() {
        UUID rootId = UUID.randomUUID();
        CommentTarget target = CommentTarget.novelChapter(UUID.randomUUID());
        UUID authorId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-16T10:00:00Z");
        Comment root = Comment.createRoot(rootId, target, authorId, "Root", t1);
        adapter.save(root);

        UUID replyId = UUID.randomUUID();
        Instant t2 = Instant.parse("2026-09-16T10:05:00Z");
        Comment reply = Comment.createReply(replyId, root, authorId, "Reply to delete", t2);
        adapter.save(reply);

        Instant t3 = Instant.parse("2026-09-16T11:00:00Z");
        reply.delete(t3);
        adapter.save(reply);

        Optional<Comment> reloaded = adapter.findById(replyId);
        assertThat(reloaded).isPresent();
        Comment tombstone = reloaded.get();
        assertThat(tombstone.getStatus()).isEqualTo(CommentStatus.DELETED);
        assertThat(tombstone.isDeleted()).isTrue();
        assertThat(tombstone.getBody()).isNull();
        assertThat(tombstone.getParentCommentId()).isEqualTo(rootId);
        assertThat(tombstone.getThreadRootCommentId()).isEqualTo(rootId);
        assertThat(tombstone.getCreatedAt()).isEqualTo(t2);
        assertThat(tombstone.getUpdatedAt()).isEqualTo(t3);
        assertThat(tombstone.getDeletedAt()).isEqualTo(t3);
    }

    @Test
    @DisplayName("Should return Optional.empty() for non-existent comment ID and throw for null ID")
    void shouldHandleNonExistentAndNullCommentId() {
        assertThat(adapter.findById(UUID.randomUUID())).isEmpty();

        assertThatThrownBy(() -> adapter.findById(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Comment ID cannot be null.");
    }

    // =========================================================================
    // 2. ROOT QUERY FILTERING & DETERMINISTIC ORDERING
    // =========================================================================

    @Test
    @DisplayName("Should filter active roots by target, excluding other targets, replies, and deleted roots")
    void shouldFilterActiveRootsCorrectly() {
        CommentTarget target1 = CommentTarget.novelChapter(UUID.randomUUID());
        CommentTarget sameTypeDiffIdTarget = CommentTarget.novelChapter(UUID.randomUUID());
        CommentTarget target2 = CommentTarget.wikiArticle(UUID.randomUUID());
        UUID authorId = UUID.randomUUID();

        // 1. Active root for target 1
        UUID root1Id = UUID.randomUUID();
        Comment root1 = Comment.createRoot(root1Id, target1, authorId, "Target 1 active root", Instant.parse("2026-09-16T10:00:00Z"));
        adapter.save(root1);

        // 2. Reply under root 1 (must be excluded from root query)
        UUID reply1Id = UUID.randomUUID();
        Comment reply1 = Comment.createReply(reply1Id, root1, authorId, "Reply under root 1", Instant.parse("2026-09-16T10:01:00Z"));
        adapter.save(reply1);

        // 3. Deleted root for target 1 (must be excluded from active roots)
        UUID deletedRootId = UUID.randomUUID();
        Comment deletedRoot = Comment.createRoot(deletedRootId, target1, authorId, "Deleted root", Instant.parse("2026-09-16T09:00:00Z"));
        deletedRoot.delete(Instant.parse("2026-09-16T09:30:00Z"));
        adapter.save(deletedRoot);

        // 4. Active root with SAME CommentTargetType but DIFFERENT targetId (must be excluded when querying target 1)
        UUID sameTypeDiffIdRootId = UUID.randomUUID();
        Comment sameTypeDiffIdRoot = Comment.createRoot(sameTypeDiffIdRootId, sameTypeDiffIdTarget, authorId, "Same type diff id active root", Instant.parse("2026-09-16T10:00:00Z"));
        adapter.save(sameTypeDiffIdRoot);

        // 5. Active root for target 2 (different CommentTargetType WIKI_ARTICLE) (must be excluded when querying target 1)
        UUID root2Id = UUID.randomUUID();
        Comment root2 = Comment.createRoot(root2Id, target2, authorId, "Target 2 active root", Instant.parse("2026-09-16T10:00:00Z"));
        adapter.save(root2);

        CommentSlice sliceTarget1 = adapter.findActiveRoots(target1, 0, 10);
        assertThat(sliceTarget1.items()).hasSize(1);
        assertThat(sliceTarget1.items().get(0).getId()).isEqualTo(root1Id);

        CommentSlice sliceSameTypeDiffId = adapter.findActiveRoots(sameTypeDiffIdTarget, 0, 10);
        assertThat(sliceSameTypeDiffId.items()).hasSize(1);
        assertThat(sliceSameTypeDiffId.items().get(0).getId()).isEqualTo(sameTypeDiffIdRootId);

        CommentSlice sliceTarget2 = adapter.findActiveRoots(target2, 0, 10);
        assertThat(sliceTarget2.items()).hasSize(1);
        assertThat(sliceTarget2.items().get(0).getId()).isEqualTo(root2Id);
    }

    @Test
    @DisplayName("Should order active roots deterministically by createdAt DESC, id DESC with tie-breaker")
    void shouldOrderActiveRootsDeterministically() {
        CommentTarget target = CommentTarget.novelChapter(UUID.randomUUID());
        UUID authorId = UUID.randomUUID();

        Instant t1 = Instant.parse("2026-09-16T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-16T11:00:00Z");
        Instant t3 = Instant.parse("2026-09-16T12:00:00Z");

        UUID idOld = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID idNewest = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        // Two roots with identical timestamp t2, different IDs for tie-breaker
        UUID idTieLow = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID idTieHigh = UUID.fromString("99999999-9999-9999-9999-999999999999");

        adapter.save(Comment.createRoot(idOld, target, authorId, "Oldest", t1));
        adapter.save(Comment.createRoot(idTieLow, target, authorId, "Tie Low", t2));
        adapter.save(Comment.createRoot(idTieHigh, target, authorId, "Tie High", t2));
        adapter.save(Comment.createRoot(idNewest, target, authorId, "Newest", t3));

        CommentSlice slice = adapter.findActiveRoots(target, 0, 10);

        // Expected order:
        // 1. idNewest (t3)
        // 2. idTieHigh (t2, idTieHigh > idTieLow)
        // 3. idTieLow  (t2)
        // 4. idOld     (t1)
        List<UUID> orderedIds = slice.items().stream().map(Comment::getId).toList();
        assertThat(orderedIds).containsExactly(idNewest, idTieHigh, idTieLow, idOld);
    }

    // =========================================================================
    // 3. ROOT SLICE PAGINATION
    // =========================================================================

    @Test
    @DisplayName("Should paginate active roots across consecutive slices without duplicates or dropped items")
    void shouldPaginateActiveRootsCorrectly() {
        CommentTarget target = CommentTarget.novelChapter(UUID.randomUUID());
        UUID authorId = UUID.randomUUID();

        // Create 5 active roots with increasing timestamps
        Instant base = Instant.parse("2026-09-16T10:00:00Z");
        for (int i = 1; i <= 5; i++) {
            UUID id = UUID.fromString(String.format("00000000-0000-0000-0000-00000000000%d", i));
            adapter.save(Comment.createRoot(id, target, authorId, "Root " + i, base.plusSeconds(i * 60)));
        }

        // Page 0 (size 2) -> roots 5, 4; hasNext = true
        CommentSlice page0 = adapter.findActiveRoots(target, 0, 2);
        assertThat(page0.page()).isEqualTo(0);
        assertThat(page0.size()).isEqualTo(2);
        assertThat(page0.hasNext()).isTrue();
        assertThat(page0.items()).hasSize(2);
        assertThat(page0.items().get(0).getBody()).isEqualTo("Root 5");
        assertThat(page0.items().get(1).getBody()).isEqualTo("Root 4");

        // Page 1 (size 2) -> roots 3, 2; hasNext = true
        CommentSlice page1 = adapter.findActiveRoots(target, 1, 2);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.size()).isEqualTo(2);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.items().get(0).getBody()).isEqualTo("Root 3");
        assertThat(page1.items().get(1).getBody()).isEqualTo("Root 2");

        // Page 2 (size 2) -> root 1; hasNext = false (last page)
        CommentSlice page2 = adapter.findActiveRoots(target, 2, 2);
        assertThat(page2.page()).isEqualTo(2);
        assertThat(page2.size()).isEqualTo(2);
        assertThat(page2.hasNext()).isFalse();
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).getBody()).isEqualTo("Root 1");

        // Page 3 (size 2) -> beyond total items -> empty list; hasNext = false
        CommentSlice page3 = adapter.findActiveRoots(target, 3, 2);
        assertThat(page3.page()).isEqualTo(3);
        assertThat(page3.size()).isEqualTo(2);
        assertThat(page3.hasNext()).isFalse();
        assertThat(page3.items()).isEmpty();
    }

    @Test
    @DisplayName("Should validate pagination input arguments")
    void shouldValidatePaginationArguments() {
        CommentTarget target = CommentTarget.novelChapter(UUID.randomUUID());

        assertThatThrownBy(() -> adapter.findActiveRoots(null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CommentTarget cannot be null");

        assertThatThrownBy(() -> adapter.findActiveRoots(target, -1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page index cannot be negative");

        assertThatThrownBy(() -> adapter.findActiveRoots(target, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be greater than zero");

        assertThatThrownBy(() -> adapter.findActiveRoots(target, 0, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be greater than zero");
    }

    // =========================================================================
    // 4. FLAT THREAD REPLIES QUERY
    // =========================================================================

    @Test
    @DisplayName("Should return all multi-level replies in a flat list ordered createdAt ASC, id ASC, including tombstones and excluding roots/other threads")
    void shouldReturnFlatThreadRepliesWithCorrectOrderingAndInclusions() {
        CommentTarget target = CommentTarget.novelChapter(UUID.randomUUID());
        UUID authorId = UUID.randomUUID();

        // 1. Thread A root
        UUID rootAId = UUID.randomUUID();
        Comment rootA = Comment.createRoot(rootAId, target, authorId, "Thread A Root", Instant.parse("2026-09-16T10:00:00Z"));
        adapter.save(rootA);

        // 2. Reply B -> A (direct reply) at t1
        UUID replyBId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Instant t1 = Instant.parse("2026-09-16T10:05:00Z");
        Comment replyB = Comment.createReply(replyBId, rootA, authorId, "Reply B to A", t1);
        adapter.save(replyB);

        // 3. Reply C -> B (nested reply) at t2
        UUID replyCId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        Instant t2 = Instant.parse("2026-09-16T10:10:00Z");
        Comment replyC = Comment.createReply(replyCId, replyB, authorId, "Reply C to B", t2);
        adapter.save(replyC);

        // 4. Reply D -> C (nested reply replying to active C) at t3
        UUID replyDId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        Instant t3 = Instant.parse("2026-09-16T10:20:00Z");
        Comment replyD = Comment.createReply(replyDId, replyC, authorId, "Reply D to C", t3);
        adapter.save(replyD);

        // Soft-delete C after D has already replied to it, updating C in DB to tombstone
        replyC.delete(Instant.parse("2026-09-16T10:25:00Z"));
        adapter.save(replyC);

        // 5. Reply E -> A (another direct reply to root A) at t3 (same timestamp as D, different ID for ASC tie-breaker)
        // D ID: "dddd...", E ID: "1111..." -> E is lower than D, so E comes before D under ASC!
        UUID replyEId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Comment replyE = Comment.createReply(replyEId, rootA, authorId, "Reply E to A", t3);
        adapter.save(replyE);

        // 6. Unrelated Thread X and Reply Y -> X
        UUID rootXId = UUID.randomUUID();
        Comment rootX = Comment.createRoot(rootXId, target, authorId, "Thread X Root", Instant.parse("2026-09-16T10:00:00Z"));
        adapter.save(rootX);

        UUID replyYId = UUID.randomUUID();
        Comment replyY = Comment.createReply(replyYId, rootX, authorId, "Reply Y to X", Instant.parse("2026-09-16T10:02:00Z"));
        adapter.save(replyY);

        // Query flat replies for Thread A
        List<Comment> threadAReplies = adapter.findThreadReplies(rootAId);

        // Assertions:
        // - Root A is NOT in replies list
        // - Reply Y is NOT in replies list
        // - Contains exactly 4 replies: B, C, E, D
        // - Includes DELETED reply C as tombstone
        // - Ordered by createdAt ASC, id ASC:
        //   1. B (t1 = 10:05)
        //   2. C (t2 = 10:10, DELETED)
        //   3. E (t3 = 10:20, id: 1111...)
        //   4. D (t3 = 10:20, id: dddd...)
        assertThat(threadAReplies).hasSize(4);

        List<UUID> replyIds = threadAReplies.stream().map(Comment::getId).toList();
        assertThat(replyIds).containsExactly(replyBId, replyCId, replyEId, replyDId);

        // Verify flat ancestry for every reply:
        // B: parent = A, threadRoot = A
        Comment replyBResult = threadAReplies.get(0);
        assertThat(replyBResult.getId()).isEqualTo(replyBId);
        assertThat(replyBResult.getParentCommentId()).isEqualTo(rootAId);
        assertThat(replyBResult.getThreadRootCommentId()).isEqualTo(rootAId);

        // C: parent = B, threadRoot = A
        Comment replyCResult = threadAReplies.get(1);
        assertThat(replyCResult.getId()).isEqualTo(replyCId);
        assertThat(replyCResult.getParentCommentId()).isEqualTo(replyBId);
        assertThat(replyCResult.getThreadRootCommentId()).isEqualTo(rootAId);

        // E: parent = A, threadRoot = A
        Comment replyEResult = threadAReplies.get(2);
        assertThat(replyEResult.getId()).isEqualTo(replyEId);
        assertThat(replyEResult.getParentCommentId()).isEqualTo(rootAId);
        assertThat(replyEResult.getThreadRootCommentId()).isEqualTo(rootAId);

        // D: parent = C, threadRoot = A
        Comment replyDResult = threadAReplies.get(3);
        assertThat(replyDResult.getId()).isEqualTo(replyDId);
        assertThat(replyDResult.getParentCommentId()).isEqualTo(replyCId);
        assertThat(replyDResult.getThreadRootCommentId()).isEqualTo(rootAId);

        // Verify deleted reply tombstone integrity
        assertThat(replyCResult.getStatus()).isEqualTo(CommentStatus.DELETED);
        assertThat(replyCResult.getBody()).isNull();
    }

    @Test
    @DisplayName("Should return empty list for non-existent thread root ID and throw for null ID")
    void shouldHandleMissingAndNullThreadRootId() {
        assertThat(adapter.findThreadReplies(UUID.randomUUID())).isEmpty();

        assertThatThrownBy(() -> adapter.findThreadReplies(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Thread root comment ID cannot be null.");
    }
}
