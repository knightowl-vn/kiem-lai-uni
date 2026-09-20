package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentNotReportableException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.exceptions.DuplicatePendingReportException;
import com.universe.interaction.application.exceptions.SelfReportNotAllowedException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubmitCommentReportUseCase Unit Tests")
class SubmitCommentReportUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    @Mock
    private InteractionReportRepositoryPort reportRepositoryPort;

    @Mock
    private CommentTargetEligibilityPort eligibilityPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private SubmitCommentReportUseCase useCase;

    private static final UUID COMMENT_AUTHOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REPORTER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_COMMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REPLY_COMMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID GENERATED_REPORT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID CHAPTER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final CommentTarget TARGET = CommentTarget.novelChapter(CHAPTER_ID);
    private static final Instant T1 = Instant.parse("2026-09-19T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-19T10:30:00Z");

    @BeforeEach
    void setUp() {
        useCase = new SubmitCommentReportUseCase(
                commentRepositoryPort,
                reportRepositoryPort,
                eligibilityPort,
                idGeneratorPort,
                clockPort
        );
    }

    private Comment createActiveRootComment() {
        return Comment.createRoot(ROOT_COMMENT_ID, TARGET, COMMENT_AUTHOR_ID, "Authoritative root comment text", T1);
    }

    private Comment createActiveReplyComment(Comment root) {
        return Comment.createReply(REPLY_COMMENT_ID, root, COMMENT_AUTHOR_ID, "Authoritative reply comment text", T1);
    }

    @Nested
    @DisplayName("Success Cases")
    class SuccessTests {

        @Test
        @DisplayName("Active root comment can be reported successfully")
        void shouldSuccessfullyReportActiveRootComment() {
            Comment root = createActiveRootComment();
            when(reportRepositoryPort.existsPendingByCommentIdAndReporterUserId(ROOT_COMMENT_ID, REPORTER_USER_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findByIdForUpdate(ROOT_COMMENT_ID))
                    .thenReturn(Optional.of(root));
            when(eligibilityPort.isEligible(TARGET))
                    .thenReturn(true);
            when(idGeneratorPort.generate())
                    .thenReturn(GENERATED_REPORT_ID);
            when(clockPort.now())
                    .thenReturn(NOW);
            when(reportRepositoryPort.save(any(InteractionReport.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            SubmitCommentReportCommand command = new SubmitCommentReportCommand(
                    ROOT_COMMENT_ID,
                    REPORTER_USER_ID,
                    ReportReason.SPAM,
                    "Commercial link detected"
            );

            InteractionReport result = useCase.execute(command);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(GENERATED_REPORT_ID);
            assertThat(result.getCommentId()).isEqualTo(ROOT_COMMENT_ID);
            assertThat(result.getReporterUserId()).isEqualTo(REPORTER_USER_ID);
            assertThat(result.getReason()).isEqualTo(ReportReason.SPAM);
            assertThat(result.getDescription()).isEqualTo("Commercial link detected");
            assertThat(result.getReportedBodySnapshot()).isEqualTo("Authoritative root comment text");
            assertThat(result.getStatus()).isEqualTo(ReportStatus.PENDING);
            assertThat(result.getCreatedAt()).isEqualTo(NOW);
            assertThat(result.getResolvedByUserId()).isNull();
            assertThat(result.getResolvedAt()).isNull();

            ArgumentCaptor<InteractionReport> captor = ArgumentCaptor.forClass(InteractionReport.class);
            verify(reportRepositoryPort).save(captor.capture());
            InteractionReport saved = captor.getValue();
            assertThat(saved.getId()).isEqualTo(GENERATED_REPORT_ID);
            assertThat(saved.getReportedBodySnapshot()).isEqualTo("Authoritative root comment text");
        }

        @Test
        @DisplayName("Active reply under active thread root can be reported successfully")
        void shouldSuccessfullyReportActiveReplyUnderActiveRoot() {
            Comment root = createActiveRootComment();
            Comment reply = createActiveReplyComment(root);

            when(reportRepositoryPort.existsPendingByCommentIdAndReporterUserId(REPLY_COMMENT_ID, REPORTER_USER_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findByIdForUpdate(REPLY_COMMENT_ID))
                    .thenReturn(Optional.of(reply));
            when(commentRepositoryPort.findByIdForUpdate(ROOT_COMMENT_ID))
                    .thenReturn(Optional.of(root));
            when(eligibilityPort.isEligible(TARGET))
                    .thenReturn(true);
            when(idGeneratorPort.generate())
                    .thenReturn(GENERATED_REPORT_ID);
            when(clockPort.now())
                    .thenReturn(NOW);
            when(reportRepositoryPort.save(any(InteractionReport.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            SubmitCommentReportCommand command = new SubmitCommentReportCommand(
                    REPLY_COMMENT_ID,
                    REPORTER_USER_ID,
                    ReportReason.HARASSMENT,
                    null
            );

            InteractionReport result = useCase.execute(command);

            assertThat(result.getId()).isEqualTo(GENERATED_REPORT_ID);
            assertThat(result.getCommentId()).isEqualTo(REPLY_COMMENT_ID);
            assertThat(result.getReportedBodySnapshot()).isEqualTo("Authoritative reply comment text");
            assertThat(result.getStatus()).isEqualTo(ReportStatus.PENDING);
        }

        @Test
        @DisplayName("Active descendant reply under tombstoned intermediate reply can be reported successfully")
        void shouldSuccessfullyReportActiveDescendantUnderTombstonedIntermediateReply() {
            Comment root = createActiveRootComment();
            UUID replyAId = UUID.fromString("77777777-7777-7777-7777-777777777777");
            UUID replyBId = UUID.fromString("88888888-8888-8888-8888-888888888888");

            Comment replyA = Comment.createReply(replyAId, root, COMMENT_AUTHOR_ID, "Intermediate reply text", T1);
            Comment replyB = Comment.createReply(replyBId, replyA, COMMENT_AUTHOR_ID, "Active descendant text", T1.plusSeconds(5));
            replyA.delete(T1.plusSeconds(10));

            assertThat(replyA.isDeleted()).isTrue();
            assertThat(replyB.isDeleted()).isFalse();
            assertThat(replyB.getParentCommentId()).isEqualTo(replyAId);
            assertThat(replyB.getThreadRootCommentId()).isEqualTo(ROOT_COMMENT_ID);

            when(reportRepositoryPort.existsPendingByCommentIdAndReporterUserId(replyBId, REPORTER_USER_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findByIdForUpdate(replyBId))
                    .thenReturn(Optional.of(replyB));
            when(commentRepositoryPort.findByIdForUpdate(ROOT_COMMENT_ID))
                    .thenReturn(Optional.of(root));
            when(eligibilityPort.isEligible(TARGET))
                    .thenReturn(true);
            when(idGeneratorPort.generate())
                    .thenReturn(GENERATED_REPORT_ID);
            when(clockPort.now())
                    .thenReturn(NOW);
            when(reportRepositoryPort.save(any(InteractionReport.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            SubmitCommentReportCommand command = new SubmitCommentReportCommand(
                    replyBId,
                    REPORTER_USER_ID,
                    ReportReason.HARASSMENT,
                    "Harassing descendant reply under deleted parent"
            );

            InteractionReport result = useCase.execute(command);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(GENERATED_REPORT_ID);
            assertThat(result.getCommentId()).isEqualTo(replyBId);
            assertThat(result.getReporterUserId()).isEqualTo(REPORTER_USER_ID);
            assertThat(result.getReportedBodySnapshot()).isEqualTo("Active descendant text");
            assertThat(result.getStatus()).isEqualTo(ReportStatus.PENDING);

            ArgumentCaptor<InteractionReport> captor = ArgumentCaptor.forClass(InteractionReport.class);
            verify(reportRepositoryPort).save(captor.capture());
            InteractionReport saved = captor.getValue();
            assertThat(saved.getId()).isEqualTo(GENERATED_REPORT_ID);
            assertThat(saved.getCommentId()).isEqualTo(replyBId);
            assertThat(saved.getReportedBodySnapshot()).isEqualTo("Active descendant text");
        }
    }

    @Nested
    @DisplayName("Missing & Visibility Cases")
    class MissingAndVisibilityTests {

        @Test
        @DisplayName("Rejects report when comment is not found")
        void shouldRejectWhenCommentNotFound() {
            when(reportRepositoryPort.existsPendingByCommentIdAndReporterUserId(ROOT_COMMENT_ID, REPORTER_USER_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findByIdForUpdate(ROOT_COMMENT_ID))
                    .thenReturn(Optional.empty());

            SubmitCommentReportCommand command = new SubmitCommentReportCommand(
                    ROOT_COMMENT_ID,
                    REPORTER_USER_ID,
                    ReportReason.SPAM,
                    null
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(CommentNotFoundException.class)
                    .hasMessageContaining("Comment not found: " + ROOT_COMMENT_ID);

            verify(reportRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Rejects report when target comment itself is deleted")
        void shouldRejectWhenCommentIsDeleted() {
            Comment root = createActiveRootComment();
            root.delete(T1.plusSeconds(60));

            when(reportRepositoryPort.existsPendingByCommentIdAndReporterUserId(ROOT_COMMENT_ID, REPORTER_USER_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findByIdForUpdate(ROOT_COMMENT_ID))
                    .thenReturn(Optional.of(root));

            SubmitCommentReportCommand command = new SubmitCommentReportCommand(
                    ROOT_COMMENT_ID,
                    REPORTER_USER_ID,
                    ReportReason.SPAM,
                    null
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(CommentNotReportableException.class)
                    .hasMessageContaining("Comment is deleted");

            verify(reportRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Rejects report when reply's thread root is missing")
        void shouldRejectWhenReplyThreadRootMissing() {
            Comment root = createActiveRootComment();
            Comment reply = createActiveReplyComment(root);

            when(reportRepositoryPort.existsPendingByCommentIdAndReporterUserId(REPLY_COMMENT_ID, REPORTER_USER_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findByIdForUpdate(REPLY_COMMENT_ID))
                    .thenReturn(Optional.of(reply));
            when(commentRepositoryPort.findByIdForUpdate(ROOT_COMMENT_ID))
                    .thenReturn(Optional.empty());

            SubmitCommentReportCommand command = new SubmitCommentReportCommand(
                    REPLY_COMMENT_ID,
                    REPORTER_USER_ID,
                    ReportReason.SPAM,
                    null
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(CommentNotReportableException.class)
                    .hasMessageContaining("Thread root comment not found");

            verify(reportRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Rejects report when reply's thread root is deleted")
        void shouldRejectWhenReplyThreadRootDeleted() {
            Comment root = createActiveRootComment();
            Comment reply = createActiveReplyComment(root);
            root.delete(T1.plusSeconds(60));

            when(reportRepositoryPort.existsPendingByCommentIdAndReporterUserId(REPLY_COMMENT_ID, REPORTER_USER_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findByIdForUpdate(REPLY_COMMENT_ID))
                    .thenReturn(Optional.of(reply));
            when(commentRepositoryPort.findByIdForUpdate(ROOT_COMMENT_ID))
                    .thenReturn(Optional.of(root));

            SubmitCommentReportCommand command = new SubmitCommentReportCommand(
                    REPLY_COMMENT_ID,
                    REPORTER_USER_ID,
                    ReportReason.SPAM,
                    null
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(CommentNotReportableException.class)
                    .hasMessageContaining("Discussion thread is deleted");

            verify(reportRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Rejects report when comment target is not eligible")
        void shouldRejectWhenTargetNotEligible() {
            Comment root = createActiveRootComment();

            when(reportRepositoryPort.existsPendingByCommentIdAndReporterUserId(ROOT_COMMENT_ID, REPORTER_USER_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findByIdForUpdate(ROOT_COMMENT_ID))
                    .thenReturn(Optional.of(root));
            when(eligibilityPort.isEligible(TARGET))
                    .thenReturn(false);

            SubmitCommentReportCommand command = new SubmitCommentReportCommand(
                    ROOT_COMMENT_ID,
                    REPORTER_USER_ID,
                    ReportReason.SPAM,
                    null
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(CommentTargetNotEligibleException.class)
                    .hasMessageContaining("Comment target is not eligible");

            verify(reportRepositoryPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Authorization & Policies")
    class AuthorizationAndPolicyTests {

        @Test
        @DisplayName("Rejects reporting author's own comment")
        void shouldRejectSelfReport() {
            Comment root = createActiveRootComment();

            when(reportRepositoryPort.existsPendingByCommentIdAndReporterUserId(ROOT_COMMENT_ID, COMMENT_AUTHOR_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findByIdForUpdate(ROOT_COMMENT_ID))
                    .thenReturn(Optional.of(root));

            SubmitCommentReportCommand command = new SubmitCommentReportCommand(
                    ROOT_COMMENT_ID,
                    COMMENT_AUTHOR_ID,
                    ReportReason.SPAM,
                    null
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(SelfReportNotAllowedException.class)
                    .hasMessageContaining("cannot report their own comment");

            verify(reportRepositoryPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Duplicate Pending Pre-Check")
    class DuplicatePreCheckTests {

        @Test
        @DisplayName("Rejects when a pending report already exists for the same comment and reporter")
        void shouldRejectWhenPendingReportAlreadyExistsInPreCheck() {
            when(reportRepositoryPort.existsPendingByCommentIdAndReporterUserId(ROOT_COMMENT_ID, REPORTER_USER_ID))
                    .thenReturn(true);

            SubmitCommentReportCommand command = new SubmitCommentReportCommand(
                    ROOT_COMMENT_ID,
                    REPORTER_USER_ID,
                    ReportReason.SPAM,
                    null
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(DuplicatePendingReportException.class)
                    .hasMessageContaining(ROOT_COMMENT_ID.toString())
                    .hasMessageContaining(REPORTER_USER_ID.toString());

            // Asserts early exit: no comment fetch, no ID generation, no timestamp resolution, no save
            verify(commentRepositoryPort, never()).findByIdForUpdate(any());
            verify(idGeneratorPort, never()).generate();
            verify(clockPort, never()).now();
            verify(reportRepositoryPort, never()).save(any());
        }
    }
}
