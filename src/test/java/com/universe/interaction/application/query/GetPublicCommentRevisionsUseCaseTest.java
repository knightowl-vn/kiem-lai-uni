package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionSlice;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentRevision;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetPublicCommentRevisionsUseCase Unit Tests")
class GetPublicCommentRevisionsUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    @Mock
    private CommentRevisionRepositoryPort commentRevisionRepositoryPort;

    private GetPublicCommentRevisionsUseCase useCase;

    private static final UUID CHAPTER_1_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_2_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final CommentTarget TARGET_CH1 = CommentTarget.novelChapter(CHAPTER_1_ID);
    private static final CommentTarget TARGET_CH2 = CommentTarget.novelChapter(CHAPTER_2_ID);

    private static final UUID ROOT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REPLY_A_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID REPLY_B_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID AUTHOR_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private static final Instant T1 = Instant.parse("2026-09-18T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-18T10:15:00Z");
    private static final Instant T3 = Instant.parse("2026-09-18T10:30:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetPublicCommentRevisionsUseCase(commentRepositoryPort, commentRevisionRepositoryPort);
    }

    @Test
    @DisplayName("Should return newest-first revision slice for ACTIVE root comment on matching target")
    void shouldReturnRevisionsForActiveRootComment() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET_CH1, AUTHOR_ID, "Root body", T1);
        CommentRevision rev1 = new CommentRevision(UUID.randomUUID(), ROOT_ID, 1, "Prior root body", T1);
        CommentRevisionSlice expectedSlice = new CommentRevisionSlice(List.of(rev1), 0, 20, false);

        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(root));
        when(commentRevisionRepositoryPort.findSliceByCommentId(ROOT_ID, 0, 20)).thenReturn(expectedSlice);

        CommentRevisionSlice actual = useCase.execute(ROOT_ID, TARGET_CH1, 0, 20);

        assertThat(actual).isSameAs(expectedSlice);
        assertThat(actual.items()).hasSize(1);
        assertThat(actual.items().get(0).getBody()).isEqualTo("Prior root body");

        verify(commentRevisionRepositoryPort).findSliceByCommentId(ROOT_ID, 0, 20);
    }

    @Test
    @DisplayName("Should return revision slice for ACTIVE reply under ACTIVE root")
    void shouldReturnRevisionsForActiveReplyUnderActiveRoot() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET_CH1, AUTHOR_ID, "Root body", T1);
        Comment reply = Comment.createReply(REPLY_A_ID, root, AUTHOR_ID, "Reply body", T2);
        CommentRevision rev1 = new CommentRevision(UUID.randomUUID(), REPLY_A_ID, 1, "Prior reply body", T2);
        CommentRevisionSlice expectedSlice = new CommentRevisionSlice(List.of(rev1), 0, 20, false);

        when(commentRepositoryPort.findById(REPLY_A_ID)).thenReturn(Optional.of(reply));
        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(root));
        when(commentRevisionRepositoryPort.findSliceByCommentId(REPLY_A_ID, 0, 20)).thenReturn(expectedSlice);

        CommentRevisionSlice actual = useCase.execute(REPLY_A_ID, TARGET_CH1, 0, 20);

        assertThat(actual).isSameAs(expectedSlice);
        verify(commentRevisionRepositoryPort).findSliceByCommentId(REPLY_A_ID, 0, 20);
    }

    @Test
    @DisplayName("Should allow history for ACTIVE descendant under ACTIVE root even if an intermediate parent is deleted")
    void shouldReturnRevisionsForActiveDescendantEvenIfIntermediateParentDeleted() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET_CH1, AUTHOR_ID, "Root body", T1);
        Comment replyA = Comment.createReply(REPLY_A_ID, root, AUTHOR_ID, "Reply A body", T2);
        Comment replyB = Comment.createReply(REPLY_B_ID, replyA, AUTHOR_ID, "Reply B body", T3);

        // Intermediate replyA is deleted (tombstone)
        replyA.delete(Instant.parse("2026-09-18T10:45:00Z"));

        CommentRevision rev1 = new CommentRevision(UUID.randomUUID(), REPLY_B_ID, 1, "Prior B body", T3);
        CommentRevisionSlice expectedSlice = new CommentRevisionSlice(List.of(rev1), 0, 20, false);

        // When requesting history for Reply B:
        when(commentRepositoryPort.findById(REPLY_B_ID)).thenReturn(Optional.of(replyB));
        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(root));
        when(commentRevisionRepositoryPort.findSliceByCommentId(REPLY_B_ID, 0, 20)).thenReturn(expectedSlice);

        CommentRevisionSlice actual = useCase.execute(REPLY_B_ID, TARGET_CH1, 0, 20);

        assertThat(actual).isSameAs(expectedSlice);
        verify(commentRevisionRepositoryPort).findSliceByCommentId(REPLY_B_ID, 0, 20);
    }

    @Test
    @DisplayName("Should reject DELETED root comment with CommentNotFoundException")
    void shouldRejectDeletedRootCommentWithNotFound() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET_CH1, AUTHOR_ID, "Root body", T1);
        root.delete(T2);

        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> useCase.execute(ROOT_ID, TARGET_CH1, 0, 20))
                .isInstanceOf(CommentNotFoundException.class);

        verify(commentRevisionRepositoryPort, never()).findSliceByCommentId(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Should reject ACTIVE reply under DELETED root with CommentNotFoundException")
    void shouldRejectActiveReplyUnderDeletedRootWithNotFound() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET_CH1, AUTHOR_ID, "Root body", T1);
        Comment reply = Comment.createReply(REPLY_A_ID, root, AUTHOR_ID, "Reply body", T2);
        root.delete(T3); // Root was deleted

        when(commentRepositoryPort.findById(REPLY_A_ID)).thenReturn(Optional.of(reply));
        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> useCase.execute(REPLY_A_ID, TARGET_CH1, 0, 20))
                .isInstanceOf(CommentNotFoundException.class);

        verify(commentRevisionRepositoryPort, never()).findSliceByCommentId(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Should reject DELETED reply under ACTIVE root with CommentNotFoundException")
    void shouldRejectDeletedReplyWithNotFound() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET_CH1, AUTHOR_ID, "Root body", T1);
        Comment reply = Comment.createReply(REPLY_A_ID, root, AUTHOR_ID, "Reply body", T2);
        reply.delete(T3);

        when(commentRepositoryPort.findById(REPLY_A_ID)).thenReturn(Optional.of(reply));

        assertThatThrownBy(() -> useCase.execute(REPLY_A_ID, TARGET_CH1, 0, 20))
                .isInstanceOf(CommentNotFoundException.class);

        verify(commentRevisionRepositoryPort, never()).findSliceByCommentId(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Should reject nonexistent comment with CommentNotFoundException")
    void shouldRejectNonexistentCommentWithNotFound() {
        UUID missingId = UUID.randomUUID();
        when(commentRepositoryPort.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(missingId, TARGET_CH1, 0, 20))
                .isInstanceOf(CommentNotFoundException.class);

        verify(commentRevisionRepositoryPort, never()).findSliceByCommentId(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Should reject comment belonging to different target with CommentNotFoundException (cross-chapter protection)")
    void shouldRejectCommentBelongingToDifferentTargetWithNotFound() {
        // Comment belongs to chapter 2
        Comment rootCh2 = Comment.createRoot(ROOT_ID, TARGET_CH2, AUTHOR_ID, "Ch 2 Root", T1);

        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(rootCh2));

        // Requesting under chapter 1 route
        assertThatThrownBy(() -> useCase.execute(ROOT_ID, TARGET_CH1, 0, 20))
                .isInstanceOf(CommentNotFoundException.class);

        verify(commentRevisionRepositoryPort, never()).findSliceByCommentId(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Should reject reply whose thread root belongs to different target with CommentNotFoundException")
    void shouldRejectReplyWhoseRootBelongsToDifferentTargetWithNotFound() {
        Comment rootCh2 = Comment.createRoot(ROOT_ID, TARGET_CH2, AUTHOR_ID, "Ch 2 Root", T1);
        Comment replyOnCh1 = Comment.rehydrate(
                REPLY_A_ID,
                TARGET_CH1,
                AUTHOR_ID,
                ROOT_ID,
                ROOT_ID,
                "Reply body",
                CommentStatus.ACTIVE,
                T2,
                T2,
                null
        );

        when(commentRepositoryPort.findById(REPLY_A_ID)).thenReturn(Optional.of(replyOnCh1));
        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(rootCh2));

        assertThatThrownBy(() -> useCase.execute(REPLY_A_ID, TARGET_CH1, 0, 20))
                .isInstanceOf(CommentNotFoundException.class);

        verify(commentRevisionRepositoryPort, never()).findSliceByCommentId(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Should reject reply whose thread root does not exist with CommentNotFoundException")
    void shouldRejectReplyWhoseRootDoesNotExistWithNotFound() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET_CH1, AUTHOR_ID, "Root body", T1);
        Comment reply = Comment.createReply(REPLY_A_ID, root, AUTHOR_ID, "Reply body", T2);

        when(commentRepositoryPort.findById(REPLY_A_ID)).thenReturn(Optional.of(reply));
        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(REPLY_A_ID, TARGET_CH1, 0, 20))
                .isInstanceOf(CommentNotFoundException.class);

        verify(commentRevisionRepositoryPort, never()).findSliceByCommentId(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Should reject reply whose referenced thread root is itself a reply with CommentNotFoundException")
    void shouldRejectReplyWhoseReferencedThreadRootIsAReplyWithNotFound() {
        Comment realRoot = Comment.createRoot(ROOT_ID, TARGET_CH1, AUTHOR_ID, "Real Root", T1);
        Comment referencedReply = Comment.createReply(REPLY_A_ID, realRoot, AUTHOR_ID, "Referenced Reply", T2);

        Comment targetReplyWithCorruptRoot = Comment.rehydrate(
                REPLY_B_ID,
                TARGET_CH1,
                AUTHOR_ID,
                REPLY_A_ID,
                REPLY_A_ID,
                "Target reply pointing to another reply as root",
                CommentStatus.ACTIVE,
                T3,
                T3,
                null
        );

        when(commentRepositoryPort.findById(REPLY_B_ID)).thenReturn(Optional.of(targetReplyWithCorruptRoot));
        when(commentRepositoryPort.findById(REPLY_A_ID)).thenReturn(Optional.of(referencedReply));

        assertThatThrownBy(() -> useCase.execute(REPLY_B_ID, TARGET_CH1, 0, 20))
                .isInstanceOf(CommentNotFoundException.class);

        verify(commentRevisionRepositoryPort, never()).findSliceByCommentId(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Should validate parameters strictly")
    void shouldValidateParametersStrictly() {
        assertThatThrownBy(() -> useCase.execute(null, TARGET_CH1, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(ROOT_ID, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(ROOT_ID, TARGET_CH1, -1, 20))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(ROOT_ID, TARGET_CH1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(ROOT_ID, TARGET_CH1, 0, -5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
