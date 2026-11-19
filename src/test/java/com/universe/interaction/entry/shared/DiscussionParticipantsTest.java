package com.universe.interaction.entry.shared;

import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DiscussionParticipants Unit Tests")
class DiscussionParticipantsTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOT_1_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_2_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REPLY_1_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID TOMBSTONE_REPLY_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID AUTHOR_1_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AUTHOR_2_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TOMBSTONE_AUTHOR_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Test
    @DisplayName("Collects ordered active comment IDs and deduplicated author IDs while excluding tombstones")
    void shouldCollectParticipantsExcludingTombstonesInOrder() {
        CommentTarget target = CommentTarget.wikiArticle(TARGET_ID);
        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1", NOW);
        Comment root2 = Comment.createRoot(ROOT_2_ID, target, AUTHOR_1_ID, "Root 2", NOW.plusSeconds(1));
        Comment reply1 = Comment.createReply(REPLY_1_ID, root1, AUTHOR_2_ID, "Reply 1", NOW.plusSeconds(2));
        Comment tombstoneReply = Comment.createReply(TOMBSTONE_REPLY_ID, root1, TOMBSTONE_AUTHOR_ID, "Deleted", NOW.plusSeconds(3));
        tombstoneReply.delete(NOW.plusSeconds(4));

        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        CommentReadItem root2Item = CommentReadItem.fromRoot(root2);
        CommentReadItem reply1Item = CommentReadItem.fromActiveReply(reply1, AUTHOR_1_ID);
        CommentReadItem tombstoneItem = CommentReadItem.fromTombstoneReply(tombstoneReply, AUTHOR_1_ID);

        DiscussionParticipants participants = DiscussionParticipants.collect(
                List.of(ROOT_2_ID, ROOT_1_ID),
                Map.of(
                        ROOT_1_ID, new CommentThreadView(root1Item, List.of(tombstoneItem, reply1Item)),
                        ROOT_2_ID, new CommentThreadView(root2Item, List.of())
                )
        );

        assertThat(participants.authorUserIds())
                .containsExactlyInAnyOrder(AUTHOR_1_ID, AUTHOR_2_ID)
                .doesNotContain(TOMBSTONE_AUTHOR_ID);
        assertThat(participants.activeCommentIds())
                .containsExactly(ROOT_2_ID, ROOT_1_ID, REPLY_1_ID)
                .doesNotContain(TOMBSTONE_REPLY_ID);

        assertThatThrownBy(() -> participants.authorUserIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> participants.activeCommentIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Single-thread collector omits tombstones and handles null gracefully")
    void shouldCollectForSingleThreadSafely() {
        assertThat(DiscussionParticipants.collectForSingleThread(null)).isEqualTo(DiscussionParticipants.EMPTY);

        CommentTarget target = CommentTarget.screenTitle(TARGET_ID);
        Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root", NOW);
        Comment tombstoneReply = Comment.createReply(TOMBSTONE_REPLY_ID, root, TOMBSTONE_AUTHOR_ID, "Hidden", NOW.plusSeconds(1));
        tombstoneReply.delete(NOW.plusSeconds(2));

        CommentThreadView view = new CommentThreadView(
                CommentReadItem.fromRoot(root),
                List.of(CommentReadItem.fromTombstoneReply(tombstoneReply, AUTHOR_1_ID))
        );

        DiscussionParticipants single = DiscussionParticipants.collectForSingleThread(view);
        assertThat(single.authorUserIds()).containsExactly(AUTHOR_1_ID);
        assertThat(single.activeCommentIds()).containsExactly(ROOT_1_ID);
    }
}
