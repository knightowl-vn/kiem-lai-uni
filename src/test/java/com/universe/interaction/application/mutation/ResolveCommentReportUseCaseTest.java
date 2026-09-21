package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
@DisplayName("ResolveCommentReportUseCase Unit Tests")
class ResolveCommentReportUseCaseTest {

    @Mock
    private InteractionReportRepositoryPort reportRepositoryPort;

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    @Mock
    private CommentRevisionRepositoryPort commentRevisionRepositoryPort;

    private static final Instant FIXED_NOW = Instant.parse("2026-09-21T10:00:00Z");
    private static final Instant REPORT_CREATED_AT = Instant.parse("2026-09-21T09:00:00Z");
    private static final Instant COMMENT_CREATED_AT = Instant.parse("2026-09-21T08:00:00Z");

    private static final UUID REPORT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SIBLING_REPORT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REPORTER_USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID SIBLING_REPORTER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID MODERATOR_USER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID COMMENT_AUTHOR_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private Clock fixedClock;
    private ResolveCommentReportUseCase useCase;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        useCase = new ResolveCommentReportUseCase(
                reportRepositoryPort,
                commentRepositoryPort,
                commentRevisionRepositoryPort,
                fixedClock
        );
    }

    private InteractionReport createPendingReport(UUID reportId, UUID commentId, UUID reporterId) {
        return InteractionReport.createPending(
                reportId,
                commentId,
                reporterId,
                ReportReason.SPAM,
                "Spam comment text",
                "Offending comment body",
                REPORT_CREATED_AT
        );
    }

    private Comment createActiveComment(UUID commentId) {
        return Comment.createRoot(
                commentId,
                CommentTarget.novelChapter(UUID.randomUUID()),
                COMMENT_AUTHOR_ID,
                "Offending comment body",
                COMMENT_CREATED_AT
        );
    }

    @Nested
    @DisplayName("Constructor and Command Validations")
    class ValidationTests {

        @Test
        @DisplayName("Should reject null dependencies in constructor")
        void shouldRejectNullDependencies() {
            assertThatThrownBy(() -> new ResolveCommentReportUseCase(null, commentRepositoryPort, commentRevisionRepositoryPort, fixedClock))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("reportRepositoryPort cannot be null");

            assertThatThrownBy(() -> new ResolveCommentReportUseCase(reportRepositoryPort, null, commentRevisionRepositoryPort, fixedClock))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("commentRepositoryPort cannot be null");

            assertThatThrownBy(() -> new ResolveCommentReportUseCase(reportRepositoryPort, commentRepositoryPort, null, fixedClock))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("commentRevisionRepositoryPort cannot be null");

            assertThatThrownBy(() -> new ResolveCommentReportUseCase(reportRepositoryPort, commentRepositoryPort, commentRevisionRepositoryPort, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("clock cannot be null");
        }

        @Test
        @DisplayName("Should reject null command")
        void shouldRejectNullCommand() {
            assertThatThrownBy(() -> useCase.execute(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ResolveCommentReportCommand cannot be null");
        }
    }

    @Nested
    @DisplayName("Report Lookup and Guard Tests")
    class ReportLookupTests {

        @Test
        @DisplayName("Should throw InteractionReportNotFoundException when report is not found")
        void shouldThrowWhenReportNotFound() {
            ResolveCommentReportCommand command = new ResolveCommentReportCommand(
                    REPORT_ID,
                    MODERATOR_USER_ID,
                    ReportModerationAction.DELETE_COMMENT
            );

            when(reportRepositoryPort.findByIdForUpdate(REPORT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(InteractionReportNotFoundException.class)
                    .hasMessageContaining(REPORT_ID.toString());

            verify(reportRepositoryPort).findByIdForUpdate(REPORT_ID);
            verify(reportRepositoryPort, never()).save(any());
            verifyNoInteractions(commentRepositoryPort);
            verifyNoInteractions(commentRevisionRepositoryPort);
        }

        @Test
        @DisplayName("Should throw ReportAlreadyResolvedException when report is already in RESOLVED_ACTION_TAKEN")
        void shouldThrowWhenReportAlreadyResolvedActionTaken() {
            InteractionReport report = createPendingReport(REPORT_ID, COMMENT_ID, REPORTER_USER_ID);
            report.resolveActionTaken(MODERATOR_USER_ID, FIXED_NOW);

            ResolveCommentReportCommand command = new ResolveCommentReportCommand(
                    REPORT_ID,
                    MODERATOR_USER_ID,
                    ReportModerationAction.DELETE_COMMENT
            );

            when(reportRepositoryPort.findByIdForUpdate(REPORT_ID)).thenReturn(Optional.of(report));

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(ReportAlreadyResolvedException.class)
                    .hasMessageContaining(REPORT_ID.toString())
                    .hasMessageContaining("RESOLVED_ACTION_TAKEN");

            verify(reportRepositoryPort).findByIdForUpdate(REPORT_ID);
            verify(reportRepositoryPort, never()).save(any());
            verifyNoInteractions(commentRepositoryPort);
            verifyNoInteractions(commentRevisionRepositoryPort);
        }

        @Test
        @DisplayName("Should throw ReportAlreadyResolvedException when report is already in RESOLVED_NO_ACTION")
        void shouldThrowWhenReportAlreadyResolvedNoAction() {
            InteractionReport report = createPendingReport(REPORT_ID, COMMENT_ID, REPORTER_USER_ID);
            report.resolveNoAction(MODERATOR_USER_ID, FIXED_NOW);

            ResolveCommentReportCommand command = new ResolveCommentReportCommand(
                    REPORT_ID,
                    MODERATOR_USER_ID,
                    ReportModerationAction.NO_ACTION
            );

            when(reportRepositoryPort.findByIdForUpdate(REPORT_ID)).thenReturn(Optional.of(report));

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(ReportAlreadyResolvedException.class)
                    .hasMessageContaining(REPORT_ID.toString())
                    .hasMessageContaining("RESOLVED_NO_ACTION");

            verify(reportRepositoryPort).findByIdForUpdate(REPORT_ID);
            verify(reportRepositoryPort, never()).save(any());
            verifyNoInteractions(commentRepositoryPort);
            verifyNoInteractions(commentRevisionRepositoryPort);
        }
    }

    @Nested
    @DisplayName("DELETE_COMMENT Action Tests")
    class DeleteCommentActionTests {

        @Test
        @DisplayName("Should soft-delete active comment, purge revisions, save comment, and resolve report as ACTION_TAKEN")
        void shouldSuccessfullyDeleteActiveCommentAndResolveReport() {
            InteractionReport report = createPendingReport(REPORT_ID, COMMENT_ID, REPORTER_USER_ID);
            Comment comment = createActiveComment(COMMENT_ID);

            ResolveCommentReportCommand command = new ResolveCommentReportCommand(
                    REPORT_ID,
                    MODERATOR_USER_ID,
                    ReportModerationAction.DELETE_COMMENT
            );

            when(reportRepositoryPort.findByIdForUpdate(REPORT_ID)).thenReturn(Optional.of(report));
            when(commentRepositoryPort.findByIdForUpdate(COMMENT_ID)).thenReturn(Optional.of(comment));
            when(commentRepositoryPort.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(reportRepositoryPort.save(any(InteractionReport.class))).thenAnswer(inv -> inv.getArgument(0));

            useCase.execute(command);

            // Verify comment mutation & persistence
            verify(commentRepositoryPort).findByIdForUpdate(COMMENT_ID);
            verify(commentRevisionRepositoryPort).deleteAllByCommentId(COMMENT_ID);

            ArgumentCaptor<Comment> commentCaptor = ArgumentCaptor.forClass(Comment.class);
            verify(commentRepositoryPort).save(commentCaptor.capture());
            Comment savedComment = commentCaptor.getValue();
            assertThat(savedComment.getStatus()).isEqualTo(CommentStatus.DELETED);
            assertThat(savedComment.isDeleted()).isTrue();
            assertThat(savedComment.getBody()).isNull();
            assertThat(savedComment.getDeletedAt()).isEqualTo(FIXED_NOW);
            assertThat(savedComment.getUpdatedAt()).isEqualTo(FIXED_NOW);

            // Verify report mutation & persistence
            ArgumentCaptor<InteractionReport> reportCaptor = ArgumentCaptor.forClass(InteractionReport.class);
            verify(reportRepositoryPort).save(reportCaptor.capture());
            InteractionReport savedReport = reportCaptor.getValue();
            assertThat(savedReport.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
            assertThat(savedReport.getModerationAction()).isEqualTo(ReportModerationAction.DELETE_COMMENT);
            assertThat(savedReport.getResolvedByUserId()).isEqualTo(MODERATOR_USER_ID);
            assertThat(savedReport.getResolvedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("Should preserve idempotency when comment is already DELETED without re-deleting or purging revisions")
        void shouldBeIdempotentWhenCommentAlreadyDeleted() {
            InteractionReport report = createPendingReport(REPORT_ID, COMMENT_ID, REPORTER_USER_ID);
            Comment comment = createActiveComment(COMMENT_ID);
            Instant originalDeletedAt = Instant.parse("2026-09-21T08:30:00Z");
            comment.delete(originalDeletedAt);

            ResolveCommentReportCommand command = new ResolveCommentReportCommand(
                    REPORT_ID,
                    MODERATOR_USER_ID,
                    ReportModerationAction.DELETE_COMMENT
            );

            when(reportRepositoryPort.findByIdForUpdate(REPORT_ID)).thenReturn(Optional.of(report));
            when(commentRepositoryPort.findByIdForUpdate(COMMENT_ID)).thenReturn(Optional.of(comment));
            when(reportRepositoryPort.save(any(InteractionReport.class))).thenAnswer(inv -> inv.getArgument(0));

            useCase.execute(command);

            // Comment repository and revision repository should NOT be mutated or saved
            verify(commentRepositoryPort).findByIdForUpdate(COMMENT_ID);
            verify(commentRevisionRepositoryPort, never()).deleteAllByCommentId(any());
            verify(commentRepositoryPort, never()).save(any());

            // Comment timestamps should remain untouched
            assertThat(comment.getDeletedAt()).isEqualTo(originalDeletedAt);
            assertThat(comment.getUpdatedAt()).isEqualTo(originalDeletedAt);

            // Report should still be resolved as RESOLVED_ACTION_TAKEN
            ArgumentCaptor<InteractionReport> reportCaptor = ArgumentCaptor.forClass(InteractionReport.class);
            verify(reportRepositoryPort).save(reportCaptor.capture());
            InteractionReport savedReport = reportCaptor.getValue();
            assertThat(savedReport.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
            assertThat(savedReport.getModerationAction()).isEqualTo(ReportModerationAction.DELETE_COMMENT);
            assertThat(savedReport.getResolvedByUserId()).isEqualTo(MODERATOR_USER_ID);
            assertThat(savedReport.getResolvedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("Should throw CommentNotFoundException and rollback without saving report when comment does not exist")
        void shouldThrowWhenCommentNotFoundOnDeleteAction() {
            InteractionReport report = createPendingReport(REPORT_ID, COMMENT_ID, REPORTER_USER_ID);

            ResolveCommentReportCommand command = new ResolveCommentReportCommand(
                    REPORT_ID,
                    MODERATOR_USER_ID,
                    ReportModerationAction.DELETE_COMMENT
            );

            when(reportRepositoryPort.findByIdForUpdate(REPORT_ID)).thenReturn(Optional.of(report));
            when(commentRepositoryPort.findByIdForUpdate(COMMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(CommentNotFoundException.class)
                    .hasMessageContaining(COMMENT_ID.toString());

            // Report is NOT resolved and NOT saved
            assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
            verify(reportRepositoryPort, never()).save(any());
            verify(commentRevisionRepositoryPort, never()).deleteAllByCommentId(any());
            verify(commentRepositoryPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("NO_ACTION Action Tests")
    class NoActionActionTests {

        @Test
        @DisplayName("Should resolve report as NO_ACTION and NEVER touch comment or revision repositories")
        void shouldSuccessfullyResolveReportWithNoAction() {
            InteractionReport report = createPendingReport(REPORT_ID, COMMENT_ID, REPORTER_USER_ID);

            ResolveCommentReportCommand command = new ResolveCommentReportCommand(
                    REPORT_ID,
                    MODERATOR_USER_ID,
                    ReportModerationAction.NO_ACTION
            );

            when(reportRepositoryPort.findByIdForUpdate(REPORT_ID)).thenReturn(Optional.of(report));
            when(reportRepositoryPort.save(any(InteractionReport.class))).thenAnswer(inv -> inv.getArgument(0));

            useCase.execute(command);

            // Report should be saved with RESOLVED_NO_ACTION
            ArgumentCaptor<InteractionReport> reportCaptor = ArgumentCaptor.forClass(InteractionReport.class);
            verify(reportRepositoryPort).save(reportCaptor.capture());
            InteractionReport savedReport = reportCaptor.getValue();
            assertThat(savedReport.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
            assertThat(savedReport.getModerationAction()).isEqualTo(ReportModerationAction.NO_ACTION);
            assertThat(savedReport.getResolvedByUserId()).isEqualTo(MODERATOR_USER_ID);
            assertThat(savedReport.getResolvedAt()).isEqualTo(FIXED_NOW);

            // Absolutely ZERO interaction with comment repositories
            verifyNoInteractions(commentRepositoryPort);
            verifyNoInteractions(commentRevisionRepositoryPort);
        }
    }

    @Nested
    @DisplayName("Invariants and Structural Tests")
    class StructuralAndInvariantTests {

        @Test
        @DisplayName("Should maintain timestamp consistency between comment delete and report resolution")
        void shouldMaintainTimestampConsistency() {
            InteractionReport report = createPendingReport(REPORT_ID, COMMENT_ID, REPORTER_USER_ID);
            Comment comment = createActiveComment(COMMENT_ID);

            ResolveCommentReportCommand command = new ResolveCommentReportCommand(
                    REPORT_ID,
                    MODERATOR_USER_ID,
                    ReportModerationAction.DELETE_COMMENT
            );

            when(reportRepositoryPort.findByIdForUpdate(REPORT_ID)).thenReturn(Optional.of(report));
            when(commentRepositoryPort.findByIdForUpdate(COMMENT_ID)).thenReturn(Optional.of(comment));
            when(commentRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(reportRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

            useCase.execute(command);

            assertThat(comment.getDeletedAt()).isEqualTo(report.getResolvedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("Should leave sibling reports on same comment untouched")
        void shouldLeaveSiblingReportsUntouched() {
            InteractionReport targetReport = createPendingReport(REPORT_ID, COMMENT_ID, REPORTER_USER_ID);
            InteractionReport siblingReport = createPendingReport(SIBLING_REPORT_ID, COMMENT_ID, SIBLING_REPORTER_ID);
            Comment comment = createActiveComment(COMMENT_ID);

            ResolveCommentReportCommand command = new ResolveCommentReportCommand(
                    REPORT_ID,
                    MODERATOR_USER_ID,
                    ReportModerationAction.DELETE_COMMENT
            );

            when(reportRepositoryPort.findByIdForUpdate(REPORT_ID)).thenReturn(Optional.of(targetReport));
            when(commentRepositoryPort.findByIdForUpdate(COMMENT_ID)).thenReturn(Optional.of(comment));
            when(commentRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(reportRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

            useCase.execute(command);

            // Only REPORT_ID was loaded and saved
            verify(reportRepositoryPort).findByIdForUpdate(REPORT_ID);
            verify(reportRepositoryPort, never()).findByIdForUpdate(SIBLING_REPORT_ID);

            assertThat(targetReport.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
            assertThat(siblingReport.getStatus()).isEqualTo(ReportStatus.PENDING);
            assertThat(siblingReport.getResolvedByUserId()).isNull();
            assertThat(siblingReport.getResolvedAt()).isNull();
        }

        @Test
        @DisplayName("Should have Spring @Service and @Transactional annotations")
        void shouldHaveServiceAndTransactionalAnnotations() {
            assertThat(ResolveCommentReportUseCase.class.isAnnotationPresent(Service.class)).isTrue();
            assertThat(ResolveCommentReportUseCase.class.isAnnotationPresent(Transactional.class)).isTrue();
        }
    }
}
