package com.universe.interaction.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentTest {

    private static final UUID ROOT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID REPLY_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CHAPTER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID ARTICLE_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final UUID AUTHOR_USER_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static final UUID REPLY_AUTHOR_USER_ID =
            UUID.fromString("66666666-6666-6666-6666-666666666666");

    private static final Instant T1 =
            Instant.parse("2026-09-16T10:00:00Z");

    private static final Instant T2 =
            Instant.parse("2026-09-16T11:00:00Z");

    private static final Instant T3 =
            Instant.parse("2026-09-16T12:00:00Z");

    private static final Instant T4 =
            Instant.parse("2026-09-16T13:00:00Z");

    private static final CommentTarget NOVEL_CHAPTER_TARGET =
            CommentTarget.novelChapter(CHAPTER_ID);

    private static final CommentTarget WIKI_ARTICLE_TARGET =
            CommentTarget.wikiArticle(ARTICLE_ID);

    @Nested
    @DisplayName("1. Root Creation")
    class RootCreationTests {

        @Test
        @DisplayName("createRoot creates an ACTIVE root comment retaining all fields and timestamps")
        void shouldCreateRootCommentSuccessfully() {
            Comment comment = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "This is a root comment on a novel chapter.",
                    T1
            );

            assertThat(comment.getId()).isEqualTo(ROOT_ID);
            assertThat(comment.getTarget()).isEqualTo(NOVEL_CHAPTER_TARGET);
            assertThat(comment.getTargetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
            assertThat(comment.getTargetId()).isEqualTo(CHAPTER_ID);
            assertThat(comment.getAuthorUserId()).isEqualTo(AUTHOR_USER_ID);
            assertThat(comment.getParentCommentId()).isNull();
            assertThat(comment.getBody()).isEqualTo("This is a root comment on a novel chapter.");
            assertThat(comment.getStatus()).isEqualTo(CommentStatus.ACTIVE);
            assertThat(comment.getCreatedAt()).isEqualTo(T1);
            assertThat(comment.getUpdatedAt()).isEqualTo(T1);
            assertThat(comment.getDeletedAt()).isNull();
            assertThat(comment.isActive()).isTrue();
            assertThat(comment.isDeleted()).isFalse();
            assertThat(comment.isRoot()).isTrue();
            assertThat(comment.isReply()).isFalse();
        }
    }

    @Nested
    @DisplayName("2. Reply Creation")
    class ReplyCreationTests {

        @Test
        @DisplayName("createReply creates an ACTIVE reply pointing to root ID with same target")
        void shouldCreateReplyCommentSuccessfully() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Root comment",
                    T1
            );

            Comment reply = Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "This is a reply to the root comment.",
                    T2
            );

            assertThat(reply.getId()).isEqualTo(REPLY_ID);
            assertThat(reply.getTarget()).isEqualTo(NOVEL_CHAPTER_TARGET);
            assertThat(reply.getTargetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
            assertThat(reply.getTargetId()).isEqualTo(CHAPTER_ID);
            assertThat(reply.getAuthorUserId()).isEqualTo(REPLY_AUTHOR_USER_ID);
            assertThat(reply.getParentCommentId()).isEqualTo(ROOT_ID);
            assertThat(reply.getBody()).isEqualTo("This is a reply to the root comment.");
            assertThat(reply.getStatus()).isEqualTo(CommentStatus.ACTIVE);
            assertThat(reply.getCreatedAt()).isEqualTo(T2);
            assertThat(reply.getUpdatedAt()).isEqualTo(T2);
            assertThat(reply.getDeletedAt()).isNull();
            assertThat(reply.isActive()).isTrue();
            assertThat(reply.isDeleted()).isFalse();
            assertThat(reply.isRoot()).isFalse();
            assertThat(reply.isReply()).isTrue();
        }
    }

    @Nested
    @DisplayName("3. Reply-to-Reply Rejection")
    class ReplyToReplyRejectionTests {

        @Test
        @DisplayName("createReply rejects replying to a comment that is already a reply")
        void shouldRejectReplyToReply() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Root comment",
                    T1
            );

            Comment reply = Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply comment",
                    T2
            );

            UUID nestedReplyId = UUID.fromString("77777777-7777-7777-7777-777777777777");

            assertThatThrownBy(() -> Comment.createReply(
                    nestedReplyId,
                    reply,
                    AUTHOR_USER_ID,
                    "Attempted nested reply",
                    T3
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot reply to a reply");
        }
    }

    @Nested
    @DisplayName("4. Reply to DELETED Root Rejection")
    class ReplyToDeletedRootRejectionTests {

        @Test
        @DisplayName("createReply rejects replying to a DELETED root comment")
        void shouldRejectReplyToDeletedRoot() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Root comment",
                    T1
            );

            root.delete(T2);

            assertThatThrownBy(() -> Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply to deleted root",
                    T3
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot reply to a deleted root comment");
        }
    }

    @Nested
    @DisplayName("5. Mismatched Reply Target Rejection")
    class MismatchedReplyTargetRejectionTests {

        @Test
        @DisplayName("createReply with explicit target rejects a target different from root target")
        void shouldRejectMismatchedReplyTarget() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Root on novel chapter",
                    T1
            );

            assertThatThrownBy(() -> Comment.createReply(
                    REPLY_ID,
                    root,
                    WIKI_ARTICLE_TARGET,
                    REPLY_AUTHOR_USER_ID,
                    "Reply with mismatched wiki target",
                    T2
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Reply target must match root comment target");
        }
    }

    @Nested
    @DisplayName("6. Predicates")
    class PredicateTests {

        @Test
        @DisplayName("isRoot and isReply correctly distinguish root comments from replies")
        void shouldReportCorrectRootAndReplyPredicates() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Root comment",
                    T1
            );

            Comment reply = Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply comment",
                    T2
            );

            assertThat(root.isRoot()).isTrue();
            assertThat(root.isReply()).isFalse();

            assertThat(reply.isRoot()).isFalse();
            assertThat(reply.isReply()).isTrue();
        }
    }

    @Nested
    @DisplayName("7. Edit ACTIVE Comment")
    class EditActiveCommentTests {

        @Test
        @DisplayName("edit updates body and updatedAt while preserving structural identity")
        void shouldEditActiveCommentSuccessfullyWhilePreservingStructuralIdentity() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Original body",
                    T1
            );

            root.edit("Updated body text\nwith second line", T2);

            assertThat(root.getBody()).isEqualTo("Updated body text\nwith second line");
            assertThat(root.getUpdatedAt()).isEqualTo(T2);

            // Structural metadata preserved
            assertThat(root.getId()).isEqualTo(ROOT_ID);
            assertThat(root.getTarget()).isEqualTo(NOVEL_CHAPTER_TARGET);
            assertThat(root.getTargetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
            assertThat(root.getTargetId()).isEqualTo(CHAPTER_ID);
            assertThat(root.getAuthorUserId()).isEqualTo(AUTHOR_USER_ID);
            assertThat(root.getParentCommentId()).isNull();
            assertThat(root.getCreatedAt()).isEqualTo(T1);
            assertThat(root.getStatus()).isEqualTo(CommentStatus.ACTIVE);
            assertThat(root.getDeletedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("8. Edit DELETED Comment Rejection")
    class EditDeletedCommentRejectionTests {

        @Test
        @DisplayName("edit rejects editing an already DELETED comment")
        void shouldRejectEditOnDeletedComment() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Original body",
                    T1
            );

            root.delete(T2);

            assertThatThrownBy(() -> root.edit("Attempted edit on deleted comment", T3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot edit a deleted comment");
        }
    }

    @Nested
    @DisplayName("9. Delete ACTIVE Comment")
    class DeleteActiveCommentTests {

        @Test
        @DisplayName("delete transitions ACTIVE comment to DELETED with deletion timestamp")
        void shouldDeleteActiveCommentTransitioningToDeletedWithTimestamp() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Original body",
                    T1
            );

            root.delete(T2);

            assertThat(root.getStatus()).isEqualTo(CommentStatus.DELETED);
            assertThat(root.isActive()).isFalse();
            assertThat(root.isDeleted()).isTrue();
            assertThat(root.getBody()).isNull();
            assertThat(root.getDeletedAt()).isEqualTo(T2);
            assertThat(root.getUpdatedAt()).isEqualTo(T2);

            // Structural metadata remains unchanged
            assertThat(root.getId()).isEqualTo(ROOT_ID);
            assertThat(root.getTarget()).isEqualTo(NOVEL_CHAPTER_TARGET);
            assertThat(root.getTargetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
            assertThat(root.getTargetId()).isEqualTo(CHAPTER_ID);
            assertThat(root.getAuthorUserId()).isEqualTo(AUTHOR_USER_ID);
            assertThat(root.getParentCommentId()).isNull();
            assertThat(root.getCreatedAt()).isEqualTo(T1);
        }

        @Test
        @DisplayName("delete on a reply transitions it to DELETED while preserving parentCommentId and author")
        void shouldDeleteReplyCommentPreservingParentRelationship() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Root body",
                    T1
            );

            Comment reply = Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply body",
                    T2
            );

            reply.delete(T3);

            assertThat(reply.getStatus()).isEqualTo(CommentStatus.DELETED);
            assertThat(reply.isDeleted()).isTrue();
            assertThat(reply.getBody()).isNull();
            assertThat(reply.getParentCommentId()).isEqualTo(ROOT_ID);
            assertThat(reply.getAuthorUserId()).isEqualTo(REPLY_AUTHOR_USER_ID);
            assertThat(reply.getDeletedAt()).isEqualTo(T3);
            assertThat(reply.getUpdatedAt()).isEqualTo(T3);
            assertThat(reply.getCreatedAt()).isEqualTo(T2);
        }
    }

    @Nested
    @DisplayName("10. Repeated Delete Idempotency")
    class RepeatedDeleteIdempotencyTests {

        @Test
        @DisplayName("repeated delete is idempotent and does not corrupt timestamps or state")
        void shouldHandleRepeatedDeleteIdempotentlyWithoutCorruptingTimestampsOrState() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Original body",
                    T1
            );

            root.delete(T2);
            assertThat(root.getStatus()).isEqualTo(CommentStatus.DELETED);
            assertThat(root.getBody()).isNull();
            assertThat(root.getDeletedAt()).isEqualTo(T2);
            assertThat(root.getUpdatedAt()).isEqualTo(T2);

            // Second delete with later timestamp must be a no-op
            root.delete(T3);
            assertThat(root.getStatus()).isEqualTo(CommentStatus.DELETED);
            assertThat(root.getBody()).isNull();
            assertThat(root.getDeletedAt()).isEqualTo(T2);
            assertThat(root.getUpdatedAt()).isEqualTo(T2);

            // Third delete with even later timestamp must also be a no-op
            root.delete(T4);
            assertThat(root.getStatus()).isEqualTo(CommentStatus.DELETED);
            assertThat(root.getBody()).isNull();
            assertThat(root.getDeletedAt()).isEqualTo(T2);
            assertThat(root.getUpdatedAt()).isEqualTo(T2);
        }
    }

    @Nested
    @DisplayName("11. Blank/Null Body on Create")
    class BlankNullBodyOnCreateTests {

        @Test
        @DisplayName("createRoot rejects null body")
        void shouldRejectNullBodyOnCreateRoot() {
            assertThatThrownBy(() -> Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    null,
                    T1
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Comment body cannot be null");
        }

        @Test
        @DisplayName("createRoot rejects empty body")
        void shouldRejectEmptyBodyOnCreateRoot() {
            assertThatThrownBy(() -> Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "",
                    T1
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Comment body cannot be blank");
        }

        @Test
        @DisplayName("createRoot rejects whitespace-only body")
        void shouldRejectWhitespaceOnlyBodyOnCreateRoot() {
            assertThatThrownBy(() -> Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "   \t\n  \r ",
                    T1
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Comment body cannot be blank");
        }

        @Test
        @DisplayName("createReply rejects blank or null body")
        void shouldRejectBlankOrNullBodyOnCreateReply() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Root body",
                    T1
            );

            assertThatThrownBy(() -> Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    null,
                    T2
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Comment body cannot be null");

            assertThatThrownBy(() -> Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "   ",
                    T2
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Comment body cannot be blank");
        }

        @Test
        @DisplayName("body trims leading and trailing whitespace while preserving internal text and newlines")
        void shouldPreserveInternalFormattingWhileTrimmingOuterWhitespace() {
            Comment comment = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "  Line 1\n\n  Line 2 with   spaces  ",
                    T1
            );

            assertThat(comment.getBody()).isEqualTo("Line 1\n\n  Line 2 with   spaces");
        }
    }

    @Nested
    @DisplayName("12. Blank/Null Body on Edit")
    class BlankNullBodyOnEditTests {

        @Test
        @DisplayName("edit rejects null body")
        void shouldRejectNullBodyOnEdit() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Initial body",
                    T1
            );

            assertThatThrownBy(() -> root.edit(null, T2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Comment body cannot be null");
        }

        @Test
        @DisplayName("edit rejects empty or whitespace-only body")
        void shouldRejectBlankBodyOnEdit() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Initial body",
                    T1
            );

            assertThatThrownBy(() -> root.edit("", T2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Comment body cannot be blank");

            assertThatThrownBy(() -> root.edit("   \t  \n ", T2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Comment body cannot be blank");
        }
    }

    @Nested
    @DisplayName("13. Target Type Contract")
    class TargetTypeContractTests {

        @Test
        @DisplayName("CommentTargetType is limited strictly to NOVEL_CHAPTER and WIKI_ARTICLE")
        void shouldLimitTargetTypeStrictlyToApprovedTargets() {
            CommentTargetType[] values = CommentTargetType.values();
            assertThat(values).containsExactlyInAnyOrder(
                    CommentTargetType.NOVEL_CHAPTER,
                    CommentTargetType.WIKI_ARTICLE
            );
            assertThat(values).hasSize(2);
        }

        @Test
        @DisplayName("CommentTarget correctly represents novel chapter and wiki article")
        void shouldCreateValidTargets() {
            CommentTarget novelTarget = CommentTarget.novelChapter(CHAPTER_ID);
            assertThat(novelTarget.type()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
            assertThat(novelTarget.targetId()).isEqualTo(CHAPTER_ID);

            CommentTarget wikiTarget = CommentTarget.wikiArticle(ARTICLE_ID);
            assertThat(wikiTarget.type()).isEqualTo(CommentTargetType.WIKI_ARTICLE);
            assertThat(wikiTarget.targetId()).isEqualTo(ARTICLE_ID);
        }
    }

    @Nested
    @DisplayName("14. Null and Invalid Argument Validation")
    class NullAndInvalidArgumentValidationTests {

        @Test
        @DisplayName("createRoot rejects null arguments")
        void shouldRejectNullArgumentsOnCreateRoot() {
            assertThatThrownBy(() -> Comment.createRoot(
                    null,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Body",
                    T1
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> Comment.createRoot(
                    ROOT_ID,
                    null,
                    AUTHOR_USER_ID,
                    "Body",
                    T1
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    null,
                    "Body",
                    T1
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Body",
                    null
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("createReply rejects null arguments and self-referencing ID")
        void shouldRejectNullArgumentsAndSelfReferencingIdOnCreateReply() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Root body",
                    T1
            );

            assertThatThrownBy(() -> Comment.createReply(
                    null,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply body",
                    T2
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> Comment.createReply(
                    REPLY_ID,
                    null,
                    REPLY_AUTHOR_USER_ID,
                    "Reply body",
                    T2
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> Comment.createReply(
                    REPLY_ID,
                    root,
                    null,
                    "Reply body",
                    T2
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply body",
                    null
            )).isInstanceOf(NullPointerException.class);

            // Self-referencing reply ID rejected
            assertThatThrownBy(() -> Comment.createReply(
                    ROOT_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply body with root ID",
                    T2
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Reply ID cannot be the same as root comment ID");
        }

        @Test
        @DisplayName("CommentTarget rejects null arguments")
        void shouldRejectNullArgumentsOnCommentTarget() {
            assertThatThrownBy(() -> new CommentTarget(null, CHAPTER_ID))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new CommentTarget(CommentTargetType.NOVEL_CHAPTER, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("edit and delete reject null timestamps")
        void shouldRejectNullTimestampsOnEditAndDelete() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Body",
                    T1
            );

            assertThatThrownBy(() -> root.edit("New body", null))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> root.delete(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("15. Temporal Regression Rejection")
    class TemporalRegressionRejectionTests {

        @Test
        @DisplayName("edit rejects timestamp before updatedAt")
        void shouldRejectTemporalRegressionOnEdit() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Body",
                    T2
            );

            Instant earlierTime = T1; // T1 is before T2

            assertThatThrownBy(() -> root.edit("Earlier edit", earlierTime))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Edit timestamp cannot be before the last updated timestamp");
        }

        @Test
        @DisplayName("delete rejects timestamp before updatedAt")
        void shouldRejectTemporalRegressionOnDelete() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Body",
                    T2
            );

            Instant earlierTime = T1; // T1 is before T2

            assertThatThrownBy(() -> root.delete(earlierTime))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Delete timestamp cannot be before the last updated timestamp");
        }

        @Test
        @DisplayName("createReply rejects createdAt before current root updatedAt")
        void shouldRejectTemporalRegressionOnCreateReply() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Root body at T1",
                    T1
            );

            root.edit("Edited root body at T3", T3);

            // T2 is after root.createdAt (T1) but before root.updatedAt (T3) => rejected
            assertThatThrownBy(() -> Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply at T2 before root updatedAt",
                    T2
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Reply createdAt cannot be before root updatedAt");

            // Reply at T3 (matching root.updatedAt) => accepted
            Comment replyAtT3 = Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply at T3 matching root updatedAt",
                    T3
            );
            assertThat(replyAtT3.getCreatedAt()).isEqualTo(T3);

            // Reply at T4 (after root.updatedAt) => accepted
            UUID replyAtT4Id = UUID.fromString("77777777-7777-7777-7777-777777777777");
            Comment replyAtT4 = Comment.createReply(
                    replyAtT4Id,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply at T4 after root updatedAt",
                    T4
            );
            assertThat(replyAtT4.getCreatedAt()).isEqualTo(T4);
        }
    }

    @Nested
    @DisplayName("16. Rehydration and Entity Invariants")
    class RehydrationTests {

        @Test
        @DisplayName("rehydrate reconstitutes an ACTIVE comment")
        void shouldRehydrateActiveComment() {
            Comment comment = Comment.rehydrate(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    null,
                    "Rehydrated body",
                    CommentStatus.ACTIVE,
                    T1,
                    T2,
                    null
            );

            assertThat(comment.getId()).isEqualTo(ROOT_ID);
            assertThat(comment.getStatus()).isEqualTo(CommentStatus.ACTIVE);
            assertThat(comment.getBody()).isEqualTo("Rehydrated body");
            assertThat(comment.getCreatedAt()).isEqualTo(T1);
            assertThat(comment.getUpdatedAt()).isEqualTo(T2);
            assertThat(comment.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("rehydrate reconstitutes a DELETED comment with null body")
        void shouldRehydrateDeletedComment() {
            Comment comment = Comment.rehydrate(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    null,
                    null,
                    CommentStatus.DELETED,
                    T1,
                    T3,
                    T3
            );

            assertThat(comment.getId()).isEqualTo(ROOT_ID);
            assertThat(comment.getStatus()).isEqualTo(CommentStatus.DELETED);
            assertThat(comment.getBody()).isNull();
            assertThat(comment.getCreatedAt()).isEqualTo(T1);
            assertThat(comment.getUpdatedAt()).isEqualTo(T3);
            assertThat(comment.getDeletedAt()).isEqualTo(T3);
            assertThat(comment.isDeleted()).isTrue();
        }

        @Test
        @DisplayName("rehydrate rejects DELETED comment with non-null body")
        void shouldRejectDeletedRehydrateWithNonNullBody() {
            assertThatThrownBy(() -> Comment.rehydrate(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    null,
                    "Non-null body on deleted",
                    CommentStatus.DELETED,
                    T1,
                    T3,
                    T3
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Body must be null for a DELETED comment");
        }

        @Test
        @DisplayName("rehydrate rejects ACTIVE comment with null or blank body")
        void shouldRejectActiveRehydrateWithNullOrBlankBody() {
            assertThatThrownBy(() -> Comment.rehydrate(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    null,
                    null,
                    CommentStatus.ACTIVE,
                    T1,
                    T1,
                    null
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Comment body cannot be null");

            assertThatThrownBy(() -> Comment.rehydrate(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    null,
                    "   ",
                    CommentStatus.ACTIVE,
                    T1,
                    T1,
                    null
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Comment body cannot be blank");
        }

        @Test
        @DisplayName("rehydrate rejects self-parent comment")
        void shouldRejectSelfParentOnRehydrate() {
            assertThatThrownBy(() -> Comment.rehydrate(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    ROOT_ID,
                    "Self parent body",
                    CommentStatus.ACTIVE,
                    T1,
                    T1,
                    null
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("A comment cannot be its own parent");
        }

        @Test
        @DisplayName("rehydrate rejects DELETED comment when updatedAt does not equal deletedAt")
        void shouldRejectDeletedRehydrateWhenUpdatedAtDoesNotEqualDeletedAt() {
            // updatedAt (T3) != deletedAt (T2)
            assertThatThrownBy(() -> Comment.rehydrate(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    null,
                    null,
                    CommentStatus.DELETED,
                    T1,
                    T3,
                    T2
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("UpdatedAt timestamp must equal deletedAt timestamp for a DELETED comment");

            // updatedAt (T2) != deletedAt (T3)
            assertThatThrownBy(() -> Comment.rehydrate(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    null,
                    null,
                    CommentStatus.DELETED,
                    T1,
                    T2,
                    T3
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("UpdatedAt timestamp must equal deletedAt timestamp for a DELETED comment");
        }

        @Test
        @DisplayName("rehydrate validates temporal consistency and deletedAt requirements")
        void shouldValidateRehydrateInvariants() {
            // updatedAt before createdAt
            assertThatThrownBy(() -> Comment.rehydrate(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    null,
                    "Body",
                    CommentStatus.ACTIVE,
                    T2,
                    T1,
                    null
            )).isInstanceOf(IllegalArgumentException.class);

            // ACTIVE with non-null deletedAt
            assertThatThrownBy(() -> Comment.rehydrate(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    null,
                    "Body",
                    CommentStatus.ACTIVE,
                    T1,
                    T2,
                    T2
            )).isInstanceOf(IllegalArgumentException.class);

            // DELETED with null deletedAt
            assertThatThrownBy(() -> Comment.rehydrate(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    null,
                    null,
                    CommentStatus.DELETED,
                    T1,
                    T2,
                    null
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("equals and hashCode are based on Comment ID")
        void shouldRespectEqualsAndHashCodeContract() {
            Comment comment1 = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Body 1",
                    T1
            );

            Comment comment2 = Comment.createRoot(
                    ROOT_ID,
                    WIKI_ARTICLE_TARGET,
                    REPLY_AUTHOR_USER_ID,
                    "Body 2",
                    T2
            );

            Comment differentComment = Comment.createRoot(
                    REPLY_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Body 1",
                    T1
            );

            assertThat(comment1).isEqualTo(comment2);
            assertThat(comment1.hashCode()).isEqualTo(comment2.hashCode());
            assertThat(comment1).isNotEqualTo(differentComment);
        }
    }
}
