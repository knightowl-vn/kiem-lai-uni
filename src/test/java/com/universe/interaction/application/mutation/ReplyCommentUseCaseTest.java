package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTarget;
import com.universe.notification.contracts.command.NotificationDispatchCommand;
import com.universe.notification.contracts.port.NotificationDispatchPort;
import com.universe.notification.domain.NotificationType;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReplyCommentUseCase Unit Tests")
class ReplyCommentUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    @Mock
    private CommentTargetEligibilityPort eligibilityPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    @Mock
    private NotificationDispatchPort notificationDispatchPort;

    private ReplyCommentUseCase useCase;

    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOT_AUTHOR_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID PARENT_AUTHOR_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID ROOT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REPLY_B_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REPLY_C_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CHAPTER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final CommentTarget TARGET = CommentTarget.novelChapter(CHAPTER_ID);
    private static final Instant T1 = Instant.parse("2026-09-16T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-16T10:05:00Z");
    private static final Instant T3 = Instant.parse("2026-09-16T10:10:00Z");

    @BeforeEach
    void setUp() {
        useCase = new ReplyCommentUseCase(
                commentRepositoryPort,
                eligibilityPort,
                idGeneratorPort,
                clockPort,
                notificationDispatchPort
        );
    }

    @Test
    @DisplayName("Should create direct reply to active root comment and dispatch notification to root author")
    void shouldCreateDirectReplyToRootSuccessfully() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, ROOT_AUTHOR_ID, "Root body", T1);
        ReplyCommentCommand command = new ReplyCommentCommand(ACTOR_ID, ROOT_ID, "Direct reply body");

        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(root));
        when(eligibilityPort.isEligible(TARGET)).thenReturn(true);
        when(clockPort.now()).thenReturn(T2);
        when(idGeneratorPort.generate()).thenReturn(REPLY_B_ID);
        when(commentRepositoryPort.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        Comment reply = useCase.execute(command);

        assertThat(reply.getId()).isEqualTo(REPLY_B_ID);
        assertThat(reply.getParentCommentId()).isEqualTo(ROOT_ID);
        assertThat(reply.getThreadRootCommentId()).isEqualTo(ROOT_ID);
        assertThat(reply.getTarget()).isEqualTo(TARGET);
        assertThat(reply.getAuthorUserId()).isEqualTo(ACTOR_ID);
        assertThat(reply.getBody()).isEqualTo("Direct reply body");
        assertThat(reply.getStatus()).isEqualTo(CommentStatus.ACTIVE);
        assertThat(reply.isReply()).isTrue();

        verify(commentRepositoryPort).findByIdForUpdate(ROOT_ID);
        verify(commentRepositoryPort).save(reply);

        ArgumentCaptor<NotificationDispatchCommand> notifCaptor = ArgumentCaptor.forClass(NotificationDispatchCommand.class);
        verify(notificationDispatchPort).dispatch(notifCaptor.capture());
        NotificationDispatchCommand captured = notifCaptor.getValue();
        assertThat(captured.recipientUserId()).isEqualTo(ROOT_AUTHOR_ID);
        assertThat(captured.type()).isEqualTo(NotificationType.COMMENT_REPLY);
        assertThat(captured.actorUserId()).isEqualTo(ACTOR_ID);
        assertThat(captured.targetType()).isEqualTo(TARGET.type().name());
        assertThat(captured.targetId()).isEqualTo(CHAPTER_ID);
        assertThat(captured.commentId()).isEqualTo(REPLY_B_ID);
        assertThat(captured.threadRootId()).isEqualTo(ROOT_ID);
        assertThat(captured.detailSnapshot()).isNull();
        assertThat(captured.dedupeKey()).isEqualTo("COMMENT_REPLY:" + REPLY_B_ID);
    }

    @Test
    @DisplayName("Should create nested reply and notify ONLY direct parent comment author, not root thread author")
    void shouldCreateNestedReplySuccessfully() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, ROOT_AUTHOR_ID, "Root body", T1);
        Comment directReply = Comment.createReply(REPLY_B_ID, root, PARENT_AUTHOR_ID, "Direct reply", T2);
        ReplyCommentCommand command = new ReplyCommentCommand(ACTOR_ID, REPLY_B_ID, "Nested reply body");

        when(commentRepositoryPort.findByIdForUpdate(REPLY_B_ID)).thenReturn(Optional.of(directReply));
        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(root));
        when(eligibilityPort.isEligible(TARGET)).thenReturn(true);
        when(clockPort.now()).thenReturn(T3);
        when(idGeneratorPort.generate()).thenReturn(REPLY_C_ID);
        when(commentRepositoryPort.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        Comment nestedReply = useCase.execute(command);

        assertThat(nestedReply.getId()).isEqualTo(REPLY_C_ID);
        assertThat(nestedReply.getParentCommentId()).isEqualTo(REPLY_B_ID);
        assertThat(nestedReply.getThreadRootCommentId()).isEqualTo(ROOT_ID);
        assertThat(nestedReply.getTarget()).isEqualTo(TARGET);
        assertThat(nestedReply.getAuthorUserId()).isEqualTo(ACTOR_ID);
        assertThat(nestedReply.getBody()).isEqualTo("Nested reply body");
        assertThat(nestedReply.getStatus()).isEqualTo(CommentStatus.ACTIVE);
        assertThat(nestedReply.isReply()).isTrue();

        verify(commentRepositoryPort).findByIdForUpdate(REPLY_B_ID);
        verify(commentRepositoryPort).findByIdForUpdate(ROOT_ID);
        verify(commentRepositoryPort).save(nestedReply);

        ArgumentCaptor<NotificationDispatchCommand> notifCaptor = ArgumentCaptor.forClass(NotificationDispatchCommand.class);
        verify(notificationDispatchPort).dispatch(notifCaptor.capture());
        NotificationDispatchCommand captured = notifCaptor.getValue();
        assertThat(captured.recipientUserId()).isEqualTo(PARENT_AUTHOR_ID);
        assertThat(captured.recipientUserId()).isNotEqualTo(ROOT_AUTHOR_ID);
        assertThat(captured.type()).isEqualTo(NotificationType.COMMENT_REPLY);
        assertThat(captured.actorUserId()).isEqualTo(ACTOR_ID);
        assertThat(captured.commentId()).isEqualTo(REPLY_C_ID);
        assertThat(captured.threadRootId()).isEqualTo(ROOT_ID);
        assertThat(captured.detailSnapshot()).isNull();
        assertThat(captured.dedupeKey()).isEqualTo("COMMENT_REPLY:" + REPLY_C_ID);
    }

    @Test
    @DisplayName("Should suppress notification when actor replies to their own comment (self-reply)")
    void shouldSuppressNotificationOnSelfReply() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, ACTOR_ID, "Root by actor", T1);
        ReplyCommentCommand command = new ReplyCommentCommand(ACTOR_ID, ROOT_ID, "Actor replying to themselves");

        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(root));
        when(eligibilityPort.isEligible(TARGET)).thenReturn(true);
        when(clockPort.now()).thenReturn(T2);
        when(idGeneratorPort.generate()).thenReturn(REPLY_B_ID);
        when(commentRepositoryPort.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        Comment reply = useCase.execute(command);

        assertThat(reply).isNotNull();
        verify(notificationDispatchPort, never()).dispatch(any());
    }

    @Test
    @DisplayName("Should reject reply when immediate parent is not found and dispatch nothing")
    void shouldRejectWhenParentNotFound() {
        UUID missingParentId = UUID.randomUUID();
        ReplyCommentCommand command = new ReplyCommentCommand(ACTOR_ID, missingParentId, "Reply body");

        when(commentRepositoryPort.findByIdForUpdate(missingParentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining("Parent comment not found");

        verifyNoInteractions(notificationDispatchPort);
    }

    @Test
    @DisplayName("Should reject reply when immediate parent is deleted and dispatch nothing")
    void shouldRejectWhenImmediateParentIsDeleted() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, UUID.randomUUID(), "Root body", T1);
        root.delete(T2);
        ReplyCommentCommand command = new ReplyCommentCommand(ACTOR_ID, ROOT_ID, "Reply to tombstone");

        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentMutationForbiddenException.class)
                .hasMessageContaining("Cannot reply to a deleted comment");

        verifyNoInteractions(notificationDispatchPort);
    }

    @Test
    @DisplayName("Should reject nested reply when thread root is deleted even if parent is active")
    void shouldRejectWhenThreadRootIsDeleted() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, UUID.randomUUID(), "Root body", T1);
        Comment directReply = Comment.createReply(REPLY_B_ID, root, UUID.randomUUID(), "Direct reply", T2);
        root.delete(T3);

        ReplyCommentCommand command = new ReplyCommentCommand(ACTOR_ID, REPLY_B_ID, "Nested reply");

        when(commentRepositoryPort.findByIdForUpdate(REPLY_B_ID)).thenReturn(Optional.of(directReply));
        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentMutationForbiddenException.class)
                .hasMessageContaining("Cannot reply in a deleted discussion thread");

        verifyNoInteractions(notificationDispatchPort);
    }

    @Test
    @DisplayName("Should reject nested reply when thread root row is missing (integrity failure)")
    void shouldRejectWhenThreadRootIsMissing() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, UUID.randomUUID(), "Root body", T1);
        Comment directReply = Comment.createReply(REPLY_B_ID, root, UUID.randomUUID(), "Direct reply", T2);

        ReplyCommentCommand command = new ReplyCommentCommand(ACTOR_ID, REPLY_B_ID, "Nested reply");

        when(commentRepositoryPort.findByIdForUpdate(REPLY_B_ID)).thenReturn(Optional.of(directReply));
        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentThreadIntegrityException.class)
                .hasMessageContaining("Thread root comment not found for reply");

        verifyNoInteractions(notificationDispatchPort);
    }

    @Test
    @DisplayName("Should reject nested reply when resolved thread root is not a root comment (integrity failure)")
    void shouldRejectWhenResolvedThreadRootIsNotRoot() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, UUID.randomUUID(), "Root body", T1);
        Comment directReply = Comment.createReply(REPLY_B_ID, root, UUID.randomUUID(), "Direct reply", T2);

        Comment ancestor = Comment.createRoot(UUID.randomUUID(), TARGET, UUID.randomUUID(), "Ancestor", T1);
        Comment fakeRootThatIsReply = Comment.createReply(ROOT_ID, ancestor, UUID.randomUUID(), "Corrupt root", T1);

        ReplyCommentCommand command = new ReplyCommentCommand(ACTOR_ID, REPLY_B_ID, "Nested reply");

        when(commentRepositoryPort.findByIdForUpdate(REPLY_B_ID)).thenReturn(Optional.of(directReply));
        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(fakeRootThatIsReply));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentThreadIntegrityException.class)
                .hasMessageContaining("Resolved thread root is not a root comment");

        verifyNoInteractions(notificationDispatchPort);
    }

    @Test
    @DisplayName("Should reject nested reply when parent and thread root targets do not match (integrity failure)")
    void shouldRejectWhenParentAndRootTargetMismatch() {
        CommentTarget otherTarget = CommentTarget.wikiArticle(UUID.randomUUID());
        Comment root = Comment.createRoot(ROOT_ID, otherTarget, UUID.randomUUID(), "Root on wiki", T1);
        Comment directReply = Comment.createReply(REPLY_B_ID, Comment.createRoot(ROOT_ID, TARGET, UUID.randomUUID(), "Root on chapter", T1), UUID.randomUUID(), "Reply", T2);

        ReplyCommentCommand command = new ReplyCommentCommand(ACTOR_ID, REPLY_B_ID, "Nested reply");

        when(commentRepositoryPort.findByIdForUpdate(REPLY_B_ID)).thenReturn(Optional.of(directReply));
        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentThreadIntegrityException.class)
                .hasMessageContaining("Parent target does not match thread root target");

        verifyNoInteractions(notificationDispatchPort);
    }

    @Test
    @DisplayName("Should reject reply when target is not eligible")
    void shouldRejectWhenTargetNotEligible() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, UUID.randomUUID(), "Root body", T1);
        ReplyCommentCommand command = new ReplyCommentCommand(ACTOR_ID, ROOT_ID, "Reply body");

        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(root));
        when(eligibilityPort.isEligible(TARGET)).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentTargetNotEligibleException.class)
                .hasMessageContaining("Comment target is not eligible for comments");

        verifyNoInteractions(notificationDispatchPort);
    }

    @Test
    @DisplayName("Should validate command inputs")
    void shouldValidateCommandInputs() {
        assertThatThrownBy(() -> new ReplyCommentCommand(null, ROOT_ID, "Body"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ReplyCommentCommand(ACTOR_ID, null, "Body"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ReplyCommentCommand(ACTOR_ID, ROOT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ReplyCommentCommand(ACTOR_ID, ROOT_ID, "   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class);
    }
}
