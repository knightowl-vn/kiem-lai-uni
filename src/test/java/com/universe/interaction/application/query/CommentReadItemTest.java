package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommentReadItem and Read Models Unit Tests")
class CommentReadItemTest {

    private static final UUID ROOT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AUTHOR_ROOT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AUTHOR_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final CommentTarget TARGET = CommentTarget.novelChapter(UUID.fromString("99999999-9999-9999-9999-999999999999"));
    private static final Instant T0 = Instant.parse("2026-09-16T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-16T10:01:00Z");

    @Test
    @DisplayName("Should create CommentReadItem from active root")
    void shouldCreateFromRoot() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ROOT, "Root body", T0);

        CommentReadItem item = CommentReadItem.fromRoot(root);

        assertThat(item.getId()).isEqualTo(ROOT_ID);
        assertThat(item.getAuthorUserId()).isEqualTo(AUTHOR_ROOT);
        assertThat(item.getParentCommentId()).isNull();
        assertThat(item.getReplyToAuthorUserId()).isNull();
        assertThat(item.getBody()).isEqualTo("Root body");
        assertThat(item.isTombstone()).isFalse();
        assertThat(item.isRoot()).isTrue();
        assertThat(item.isReply()).isFalse();
        assertThat(item.getCreatedAt()).isEqualTo(T0);
        assertThat(item.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Should reject creating CommentReadItem from root if comment is actually a reply")
    void shouldRejectFromRootWhenGivenReply() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ROOT, "Root body", T0);
        UUID replyId = UUID.randomUUID();
        Comment reply = Comment.createReply(replyId, root, AUTHOR_B, "Reply", T1);

        assertThatThrownBy(() -> CommentReadItem.fromRoot(reply))
                .isInstanceOf(CommentThreadIntegrityException.class)
                .hasMessageContaining("not a root comment");
    }

    @Test
    @DisplayName("Should reject creating CommentReadItem from root if root is deleted")
    void shouldRejectFromRootWhenRootIsDeleted() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ROOT, "Root body", T0);
        root.delete(T1);

        assertThatThrownBy(() -> CommentReadItem.fromRoot(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Deleted root comment cannot be mapped");
    }

    @Test
    @DisplayName("Should create CommentReadItem from active reply with immediate parent attribution")
    void shouldCreateFromActiveReply() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ROOT, "Root body", T0);
        UUID replyId = UUID.randomUUID();
        Comment reply = Comment.createReply(replyId, root, AUTHOR_B, "Active reply", T1);

        CommentReadItem item = CommentReadItem.fromActiveReply(reply, AUTHOR_ROOT);

        assertThat(item.getId()).isEqualTo(replyId);
        assertThat(item.getAuthorUserId()).isEqualTo(AUTHOR_B);
        assertThat(item.getParentCommentId()).isEqualTo(ROOT_ID);
        assertThat(item.getReplyToAuthorUserId()).isEqualTo(AUTHOR_ROOT);
        assertThat(item.getBody()).isEqualTo("Active reply");
        assertThat(item.isTombstone()).isFalse();
        assertThat(item.isRoot()).isFalse();
        assertThat(item.isReply()).isTrue();
    }

    @Test
    @DisplayName("Should create CommentReadItem from tombstone reply with null body and tombstone true")
    void shouldCreateFromTombstoneReply() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ROOT, "Root body", T0);
        UUID replyId = UUID.randomUUID();
        Comment reply = Comment.createReply(replyId, root, AUTHOR_B, "To delete", T0);
        reply.delete(T1);

        CommentReadItem item = CommentReadItem.fromTombstoneReply(reply, AUTHOR_ROOT);

        assertThat(item.getId()).isEqualTo(replyId);
        assertThat(item.getAuthorUserId()).isEqualTo(AUTHOR_B);
        assertThat(item.getParentCommentId()).isEqualTo(ROOT_ID);
        assertThat(item.getReplyToAuthorUserId()).isEqualTo(AUTHOR_ROOT);
        assertThat(item.getBody()).isNull();
        assertThat(item.isTombstone()).isTrue();
        assertThat(item.isRoot()).isFalse();
        assertThat(item.isReply()).isTrue();
        assertThat(item.getCreatedAt()).isEqualTo(T0);
        assertThat(item.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("Should enforce invariant that tombstone items must have null body")
    void shouldEnforceTombstoneMustHaveNullBody() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new CommentReadItem(id, AUTHOR_B, ROOT_ID, AUTHOR_ROOT, "Not null body", true, T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Body must be null for tombstone item");
    }

    @Test
    @DisplayName("Should enforce invariant that active items must have non-null body")
    void shouldEnforceActiveMustHaveNonNullBody() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new CommentReadItem(id, AUTHOR_B, ROOT_ID, AUTHOR_ROOT, null, false, T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Body cannot be null for active item");
    }

    @Test
    @DisplayName("Should enforce invariant that root comments cannot have replyToAuthorUserId or be tombstones")
    void shouldEnforceRootInvariants() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new CommentReadItem(id, AUTHOR_B, null, AUTHOR_ROOT, "Body", false, T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Root comment cannot have replyToAuthorUserId");

        assertThatThrownBy(() -> new CommentReadItem(id, AUTHOR_B, null, null, null, true, T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Root comment cannot be a tombstone");
    }

    @Test
    @DisplayName("Should enforce invariant that reply comments must have replyToAuthorUserId")
    void shouldEnforceReplyInvariants() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new CommentReadItem(id, AUTHOR_B, ROOT_ID, null, "Body", false, T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reply comment must have replyToAuthorUserId");
    }

    @Test
    @DisplayName("CommentThreadView defensively copies replies list")
    void shouldDefensivelyCopyRepliesInThreadView() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ROOT, "Root body", T0);
        CommentReadItem rootItem = CommentReadItem.fromRoot(root);

        List<CommentReadItem> mutableReplies = new ArrayList<>();
        CommentThreadView view = new CommentThreadView(rootItem, mutableReplies);

        assertThat(view.getReplies()).isEmpty();
        assertThatThrownBy(() -> view.getReplies().add(rootItem))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("CommentReadSlice defensively copies items list and validates page arguments")
    void shouldDefensivelyCopyItemsInReadSlice() {
        List<CommentReadItem> mutableItems = new ArrayList<>();
        CommentReadSlice slice = new CommentReadSlice(mutableItems, 0, 10, false);

        assertThat(slice.getItems()).isEmpty();
        assertThatThrownBy(() -> slice.getItems().add(null))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> new CommentReadSlice(List.of(), -1, 10, false))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new CommentReadSlice(List.of(), 0, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
