package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommentThreadVisibilityResolver Unit Tests")
class CommentThreadVisibilityResolverTest {

    private CommentThreadVisibilityResolver resolver;

    private static final UUID ROOT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AUTHOR_ROOT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AUTHOR_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID AUTHOR_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID AUTHOR_D = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID AUTHOR_E = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    private static final CommentTarget TARGET = CommentTarget.novelChapter(UUID.fromString("99999999-9999-9999-9999-999999999999"));
    private static final Instant T0 = Instant.parse("2026-09-16T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-16T10:01:00Z");
    private static final Instant T2 = Instant.parse("2026-09-16T10:02:00Z");
    private static final Instant T3 = Instant.parse("2026-09-16T10:03:00Z");
    private static final Instant T4 = Instant.parse("2026-09-16T10:04:00Z");

    private Comment root;

    @BeforeEach
    void setUp() {
        resolver = new CommentThreadVisibilityResolver();
        root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ROOT, "Root body", T0);
    }

    // =========================================================================
    // VISIBILITY RESOLUTION RULES
    // =========================================================================

    @Test
    @DisplayName("1. All ACTIVE replies remain visible")
    void shouldKeepAllActiveRepliesVisible() {
        UUID replyBId = UUID.randomUUID();
        UUID replyCId = UUID.randomUUID();

        Comment replyB = Comment.createReply(replyBId, root, AUTHOR_B, "Reply B to root", T1);
        Comment replyC = Comment.createReply(replyCId, replyB, AUTHOR_C, "Reply C to B", T2);

        List<CommentReadItem> items = resolver.resolve(root, List.of(replyB, replyC));

        assertThat(items).hasSize(2);
        assertThat(items.get(0).getId()).isEqualTo(replyBId);
        assertThat(items.get(0).isTombstone()).isFalse();
        assertThat(items.get(0).getBody()).isEqualTo("Reply B to root");

        assertThat(items.get(1).getId()).isEqualTo(replyCId);
        assertThat(items.get(1).isTombstone()).isFalse();
        assertThat(items.get(1).getBody()).isEqualTo("Reply C to B");
    }

    @Test
    @DisplayName("2. Deleted leaf reply disappears completely")
    void shouldHideDeletedLeafReply() {
        UUID replyBId = UUID.randomUUID();
        Comment replyB = Comment.createReply(replyBId, root, AUTHOR_B, "Reply B to delete", T1);
        replyB.delete(T2);

        List<CommentReadItem> items = resolver.resolve(root, List.of(replyB));

        assertThat(items).isEmpty();
    }

    @Test
    @DisplayName("3. Deleted immediate parent of active child becomes tombstone")
    void shouldRetainDeletedImmediateParentAsTombstone() {
        UUID replyBId = UUID.randomUUID();
        UUID replyCId = UUID.randomUUID();

        Comment replyB = Comment.createReply(replyBId, root, AUTHOR_B, "Reply B", T1);
        Comment replyC = Comment.createReply(replyCId, replyB, AUTHOR_C, "Reply C", T2);
        replyB.delete(T3);

        List<CommentReadItem> items = resolver.resolve(root, List.of(replyB, replyC));

        assertThat(items).hasSize(2);

        CommentReadItem itemB = items.get(0);
        assertThat(itemB.getId()).isEqualTo(replyBId);
        assertThat(itemB.isTombstone()).isTrue();
        assertThat(itemB.getBody()).isNull();
        assertThat(itemB.getAuthorUserId()).isEqualTo(AUTHOR_B);
        assertThat(itemB.getParentCommentId()).isEqualTo(ROOT_ID);
        assertThat(itemB.getReplyToAuthorUserId()).isEqualTo(AUTHOR_ROOT);

        CommentReadItem itemC = items.get(1);
        assertThat(itemC.getId()).isEqualTo(replyCId);
        assertThat(itemC.isTombstone()).isFalse();
        assertThat(itemC.getBody()).isEqualTo("Reply C");
        assertThat(itemC.getParentCommentId()).isEqualTo(replyBId);
        assertThat(itemC.getReplyToAuthorUserId()).isEqualTo(AUTHOR_B);
    }

    @Test
    @DisplayName("4. Multi-level deleted ancestry: B deleted, C deleted -> B, D active -> C produces B tombstone, C tombstone, D active")
    void shouldRetainTransitiveDeletedAncestryAsTombstones() {
        UUID bId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID cId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID dId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        Comment b = Comment.createReply(bId, root, AUTHOR_B, "B body", T1);
        Comment c = Comment.createReply(cId, b, AUTHOR_C, "C body", T2);
        Comment d = Comment.createReply(dId, c, AUTHOR_D, "D body", T3);

        b.delete(T4);
        c.delete(T4);

        List<CommentReadItem> items = resolver.resolve(root, List.of(b, c, d));

        assertThat(items).hasSize(3);

        CommentReadItem itemB = items.get(0);
        assertThat(itemB.getId()).isEqualTo(bId);
        assertThat(itemB.isTombstone()).isTrue();
        assertThat(itemB.getBody()).isNull();
        assertThat(itemB.getReplyToAuthorUserId()).isEqualTo(AUTHOR_ROOT);

        CommentReadItem itemC = items.get(1);
        assertThat(itemC.getId()).isEqualTo(cId);
        assertThat(itemC.isTombstone()).isTrue();
        assertThat(itemC.getBody()).isNull();
        assertThat(itemC.getReplyToAuthorUserId()).isEqualTo(AUTHOR_B);

        CommentReadItem itemD = items.get(2);
        assertThat(itemD.getId()).isEqualTo(dId);
        assertThat(itemD.isTombstone()).isFalse();
        assertThat(itemD.getBody()).isEqualTo("D body");
        assertThat(itemD.getReplyToAuthorUserId()).isEqualTo(AUTHOR_C);
    }

    @Test
    @DisplayName("5. Unrelated deleted sibling branch remains hidden")
    void shouldHideUnrelatedDeletedSiblingBranch() {
        // Branch 1: B deleted, C active -> B
        UUID bId = UUID.randomUUID();
        UUID cId = UUID.randomUUID();
        Comment b = Comment.createReply(bId, root, AUTHOR_B, "B body", T1);
        Comment c = Comment.createReply(cId, b, AUTHOR_C, "C body", T2);
        b.delete(T3);

        // Branch 2: D -> root
        UUID dId = UUID.randomUUID();
        Comment d = Comment.createReply(dId, root, AUTHOR_D, "D body", T1);

        // Branch 3: E -> D (child of D)
        UUID eId = UUID.randomUUID();
        Comment e = Comment.createReply(eId, d, AUTHOR_E, "E body", T2);

        // Both D and E are deleted later (no active children)
        d.delete(T3);
        e.delete(T3);

        List<CommentReadItem> items = resolver.resolve(root, List.of(b, d, c, e));

        // Only B (tombstone) and C (active) are visible; D and E must be hidden
        assertThat(items).hasSize(2);
        assertThat(items.get(0).getId()).isEqualTo(bId);
        assertThat(items.get(0).isTombstone()).isTrue();
        assertThat(items.get(1).getId()).isEqualTo(cId);
        assertThat(items.get(1).isTombstone()).isFalse();
    }

    @Test
    @DisplayName("6. Visible output preserves original persistence input order")
    void shouldPreserveOriginalPersistenceOrdering() {
        UUID bId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID cId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID dId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        Comment b = Comment.createReply(bId, root, AUTHOR_B, "B body", T1);
        Comment c = Comment.createReply(cId, root, AUTHOR_C, "C body", T2);
        Comment d = Comment.createReply(dId, b, AUTHOR_D, "D body", T3);

        // Input order: B (t1), C (t2), D (t3)
        List<CommentReadItem> items = resolver.resolve(root, List.of(b, c, d));

        assertThat(items).extracting(CommentReadItem::getId)
                .containsExactly(bId, cId, dId);
    }

    @Test
    @DisplayName("7. Immediate parent author attribution: C -> B resolves replyToAuthorUserId from B, not root")
    void shouldAttributeReplyToImmediateParentAuthor() {
        UUID bId = UUID.randomUUID();
        UUID cId = UUID.randomUUID();

        Comment b = Comment.createReply(bId, root, AUTHOR_B, "B body", T1);
        Comment c = Comment.createReply(cId, b, AUTHOR_C, "C body", T2);

        List<CommentReadItem> items = resolver.resolve(root, List.of(b, c));

        assertThat(items.get(0).getReplyToAuthorUserId()).isEqualTo(AUTHOR_ROOT);
        assertThat(items.get(1).getReplyToAuthorUserId()).isEqualTo(AUTHOR_B);
    }

    @Test
    @DisplayName("8. Visible tombstone has body == null")
    void shouldEnsureTombstoneHasNullBody() {
        UUID bId = UUID.randomUUID();
        UUID cId = UUID.randomUUID();

        Comment b = Comment.createReply(bId, root, AUTHOR_B, "B body", T1);
        Comment c = Comment.createReply(cId, b, AUTHOR_C, "C body", T2);
        b.delete(T3);

        List<CommentReadItem> items = resolver.resolve(root, List.of(b, c));

        CommentReadItem tombstoneB = items.get(0);
        assertThat(tombstoneB.isTombstone()).isTrue();
        assertThat(tombstoneB.getBody()).isNull();
    }

    @Test
    @DisplayName("9. Resolver does not mutate original Comment aggregates or list")
    void shouldNotMutateOriginalCommentsOrList() {
        UUID bId = UUID.randomUUID();
        Comment b = Comment.createReply(bId, root, AUTHOR_B, "B body", T1);

        List<Comment> originalList = new ArrayList<>(List.of(b));
        resolver.resolve(root, originalList);

        assertThat(originalList).hasSize(1);
        assertThat(b.getBody()).isEqualTo("B body");
        assertThat(b.getStatus()).isEqualTo(CommentStatus.ACTIVE);
    }

    // =========================================================================
    // GRAPH INTEGRITY FAILURES
    // =========================================================================

    @Test
    @DisplayName("10. Reject duplicate reply ID")
    void shouldRejectDuplicateReplyId() {
        UUID bId = UUID.randomUUID();
        Comment b1 = Comment.createReply(bId, root, AUTHOR_B, "B1", T1);
        Comment b2 = Comment.createReply(bId, root, AUTHOR_C, "B2", T2);

        assertThatThrownBy(() -> resolver.resolve(root, List.of(b1, b2)))
                .isInstanceOf(CommentThreadIntegrityException.class)
                .hasMessageContaining("Duplicate reply ID found in thread");
    }

    @Test
    @DisplayName("11. Reject reply with wrong threadRootCommentId")
    void shouldRejectReplyWithWrongThreadRootCommentId() {
        UUID bId = UUID.randomUUID();
        UUID foreignRootId = UUID.randomUUID();

        // Corrupt fixture: threadRootCommentId does not match root.getId()
        Comment corruptReply = Comment.rehydrate(
                bId,
                TARGET,
                AUTHOR_B,
                ROOT_ID,
                foreignRootId,
                "Corrupt threadRoot",
                CommentStatus.ACTIVE,
                T1,
                T1,
                null
        );

        assertThatThrownBy(() -> resolver.resolve(root, List.of(corruptReply)))
                .isInstanceOf(CommentThreadIntegrityException.class)
                .hasMessageContaining("does not match root ID");
    }

    @Test
    @DisplayName("12. Reject reply when target differs from root target")
    void shouldRejectReplyWithMismatchingTarget() {
        UUID bId = UUID.randomUUID();
        CommentTarget foreignTarget = CommentTarget.wikiArticle(UUID.randomUUID());

        // Corrupt fixture: target does not match root.getTarget()
        Comment corruptReply = Comment.rehydrate(
                bId,
                foreignTarget,
                AUTHOR_B,
                ROOT_ID,
                ROOT_ID,
                "Foreign target reply",
                CommentStatus.ACTIVE,
                T1,
                T1,
                null
        );

        assertThatThrownBy(() -> resolver.resolve(root, List.of(corruptReply)))
                .isInstanceOf(CommentThreadIntegrityException.class)
                .hasMessageContaining("does not match root target");
    }

    @Test
    @DisplayName("13. Reject reply with missing immediate parent")
    void shouldRejectReplyWithMissingImmediateParent() {
        UUID bId = UUID.randomUUID();
        UUID missingParentId = UUID.randomUUID();

        // Parent is neither root nor in reply list
        Comment corruptReply = Comment.rehydrate(
                bId,
                TARGET,
                AUTHOR_B,
                missingParentId,
                ROOT_ID,
                "Orphan reply",
                CommentStatus.ACTIVE,
                T1,
                T1,
                null
        );

        assertThatThrownBy(() -> resolver.resolve(root, List.of(corruptReply)))
                .isInstanceOf(CommentThreadIntegrityException.class)
                .hasMessageContaining("Immediate parent");
    }

    @Test
    @DisplayName("14. Reject when supplied reply list contains a root row")
    void shouldRejectWhenReplyListContainsRootRow() {
        Comment anotherRoot = Comment.createRoot(UUID.randomUUID(), TARGET, AUTHOR_B, "Another root", T1);

        assertThatThrownBy(() -> resolver.resolve(root, List.of(anotherRoot)))
                .isInstanceOf(CommentThreadIntegrityException.class)
                .hasMessageContaining("contains a root comment");
    }

    @Test
    @DisplayName("15. Reject parent cycle in replies")
    void shouldRejectParentCycle() {
        UUID bId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID cId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        // Cycle: B's parent is C, and C's parent is B
        Comment b = Comment.rehydrate(
                bId,
                TARGET,
                AUTHOR_B,
                cId,
                ROOT_ID,
                "B in cycle",
                CommentStatus.ACTIVE,
                T1,
                T1,
                null
        );
        Comment c = Comment.rehydrate(
                cId,
                TARGET,
                AUTHOR_C,
                bId,
                ROOT_ID,
                "C in cycle",
                CommentStatus.ACTIVE,
                T2,
                T2,
                null
        );

        assertThatThrownBy(() -> resolver.resolve(root, List.of(b, c)))
                .isInstanceOf(CommentThreadIntegrityException.class)
                .hasMessageContaining("Parent cycle detected");
    }

    @Test
    @DisplayName("16. Reject non-root or deleted root comment passed to resolver")
    void shouldRejectInvalidRootPassedToResolver() {
        UUID replyId = UUID.randomUUID();
        Comment reply = Comment.createReply(replyId, root, AUTHOR_B, "Reply", T1);

        // Passed reply as root
        assertThatThrownBy(() -> resolver.resolve(reply, List.of()))
                .isInstanceOf(CommentThreadIntegrityException.class)
                .hasMessageContaining("Supplied root is not a root comment");

        // Passed deleted root
        Comment deletedRoot = Comment.createRoot(UUID.randomUUID(), TARGET, AUTHOR_ROOT, "Deleted root", T0);
        deletedRoot.delete(T1);

        assertThatThrownBy(() -> resolver.resolve(deletedRoot, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Root comment must be ACTIVE");
    }

    @Test
    @DisplayName("17. Reject null arguments")
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> resolver.resolve(null, List.of()))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> resolver.resolve(root, null))
                .isInstanceOf(NullPointerException.class);
    }
}
