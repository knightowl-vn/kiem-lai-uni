package com.universe.interaction.application.mutation;

import com.universe.community.contracts.port.CommunityPostInteractionMutationPort;
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
import com.universe.interaction.domain.report.ReportTargetType;
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
@DisplayName("SubmitInteractionReportUseCase Unit Tests")
class SubmitInteractionReportUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    @Mock
    private CommunityPostInteractionMutationPort communityPostMutationPort;

    @Mock
    private InteractionReportRepositoryPort reportRepositoryPort;

    @Mock
    private CommentTargetEligibilityPort eligibilityPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private SubmitInteractionReportUseCase useCase;

    private static final UUID AUTHOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REPORTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID POST_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COMMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID REPORT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new SubmitInteractionReportUseCase(
                commentRepositoryPort,
                communityPostMutationPort,
                reportRepositoryPort,
                eligibilityPort,
                idGeneratorPort,
                clockPort
        );
    }

    @Nested
    @DisplayName("Community Post Target Reporting")
    class CommunityPostReportingTests {

        @Test
        @DisplayName("Successfully reports an eligible community post with locked caption snapshot")
        void shouldSuccessfullyReportCommunityPost() {
            when(reportRepositoryPort.existsPendingByTargetAndReporter(ReportTargetType.COMMUNITY_POST, POST_ID, REPORTER_ID))
                    .thenReturn(false);
            when(communityPostMutationPort.lockExistingPostForInteraction(POST_ID))
                    .thenReturn(Optional.of(new CommunityPostInteractionMutationPort.CommunityPostLockedView(
                            POST_ID, AUTHOR_ID, "Authoritative post caption"
                    )));
            when(idGeneratorPort.generate()).thenReturn(REPORT_ID);
            when(clockPort.now()).thenReturn(NOW);
            when(reportRepositoryPort.save(any(InteractionReport.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            SubmitInteractionReportCommand command = new SubmitInteractionReportCommand(
                    ReportTargetType.COMMUNITY_POST,
                    POST_ID,
                    REPORTER_ID,
                    ReportReason.SPAM,
                    "Spam post"
            );

            InteractionReport report = useCase.execute(command);

            assertThat(report).isNotNull();
            assertThat(report.getId()).isEqualTo(REPORT_ID);
            assertThat(report.getTargetType()).isEqualTo(ReportTargetType.COMMUNITY_POST);
            assertThat(report.getTargetId()).isEqualTo(POST_ID);
            assertThat(report.getReporterUserId()).isEqualTo(REPORTER_ID);
            assertThat(report.getReason()).isEqualTo(ReportReason.SPAM);
            assertThat(report.getDescription()).isEqualTo("Spam post");
            assertThat(report.getReportedContentSnapshot()).isEqualTo("Authoritative post caption");
            assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
            assertThat(report.getCreatedAt()).isEqualTo(NOW);

            ArgumentCaptor<InteractionReport> captor = ArgumentCaptor.forClass(InteractionReport.class);
            verify(reportRepositoryPort).save(captor.capture());
            assertThat(captor.getValue().getReportedContentSnapshot()).isEqualTo("Authoritative post caption");
        }

        @Test
        @DisplayName("Rejects reporting a missing community post")
        void shouldRejectReportingMissingCommunityPost() {
            when(reportRepositoryPort.existsPendingByTargetAndReporter(ReportTargetType.COMMUNITY_POST, POST_ID, REPORTER_ID))
                    .thenReturn(false);
            when(communityPostMutationPort.lockExistingPostForInteraction(POST_ID))
                    .thenReturn(Optional.empty());

            SubmitInteractionReportCommand command = new SubmitInteractionReportCommand(
                    ReportTargetType.COMMUNITY_POST,
                    POST_ID,
                    REPORTER_ID,
                    ReportReason.SPAM,
                    null
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(CommentTargetNotEligibleException.class)
                    .hasMessageContaining(POST_ID.toString());

            verify(reportRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Rejects self-reporting a community post")
        void shouldRejectSelfReportingCommunityPost() {
            when(reportRepositoryPort.existsPendingByTargetAndReporter(ReportTargetType.COMMUNITY_POST, POST_ID, AUTHOR_ID))
                    .thenReturn(false);
            when(communityPostMutationPort.lockExistingPostForInteraction(POST_ID))
                    .thenReturn(Optional.of(new CommunityPostInteractionMutationPort.CommunityPostLockedView(
                            POST_ID, AUTHOR_ID, "Authoritative post caption"
                    )));

            SubmitInteractionReportCommand command = new SubmitInteractionReportCommand(
                    ReportTargetType.COMMUNITY_POST,
                    POST_ID,
                    AUTHOR_ID, // author reporting own post
                    ReportReason.SPAM,
                    null
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(SelfReportNotAllowedException.class)
                    .hasMessageContaining("cannot report their own COMMUNITY_POST");

            verify(reportRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Rejects duplicate pending report on community post")
        void shouldRejectDuplicatePendingReportOnCommunityPost() {
            when(reportRepositoryPort.existsPendingByTargetAndReporter(ReportTargetType.COMMUNITY_POST, POST_ID, REPORTER_ID))
                    .thenReturn(true);

            SubmitInteractionReportCommand command = new SubmitInteractionReportCommand(
                    ReportTargetType.COMMUNITY_POST,
                    POST_ID,
                    REPORTER_ID,
                    ReportReason.SPAM,
                    null
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(DuplicatePendingReportException.class)
                    .hasMessageContaining(POST_ID.toString());

            verify(communityPostMutationPort, never()).lockExistingPostForInteraction(any());
            verify(reportRepositoryPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Comment Target Reporting")
    class CommentReportingTests {

        @Test
        @DisplayName("Successfully reports an active root comment with locked body snapshot")
        void shouldSuccessfullyReportComment() {
            Comment comment = Comment.createRoot(COMMENT_ID, CommentTarget.communityPost(POST_ID), AUTHOR_ID, "Comment body", NOW);

            when(reportRepositoryPort.existsPendingByTargetAndReporter(ReportTargetType.COMMENT, COMMENT_ID, REPORTER_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findById(COMMENT_ID))
                    .thenReturn(Optional.of(comment));
            when(commentRepositoryPort.findByIdForUpdate(COMMENT_ID))
                    .thenReturn(Optional.of(comment));
            when(eligibilityPort.isEligible(CommentTarget.communityPost(POST_ID)))
                    .thenReturn(true);
            when(idGeneratorPort.generate()).thenReturn(REPORT_ID);
            when(clockPort.now()).thenReturn(NOW);
            when(reportRepositoryPort.save(any(InteractionReport.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            SubmitInteractionReportCommand command = new SubmitInteractionReportCommand(
                    ReportTargetType.COMMENT,
                    COMMENT_ID,
                    REPORTER_ID,
                    ReportReason.HARASSMENT,
                    "Harassing comment"
            );

            InteractionReport report = useCase.execute(command);

            assertThat(report).isNotNull();
            assertThat(report.getId()).isEqualTo(REPORT_ID);
            assertThat(report.getTargetType()).isEqualTo(ReportTargetType.COMMENT);
            assertThat(report.getTargetId()).isEqualTo(COMMENT_ID);
            assertThat(report.getReportedContentSnapshot()).isEqualTo("Comment body");
            verify(reportRepositoryPort).save(any(InteractionReport.class));
        }

        @Test
        @DisplayName("Rejects report when comment is not found during initial discovery")
        void shouldRejectWhenCommentNotFound() {
            when(reportRepositoryPort.existsPendingByTargetAndReporter(ReportTargetType.COMMENT, COMMENT_ID, REPORTER_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findById(COMMENT_ID))
                    .thenReturn(Optional.empty());

            SubmitInteractionReportCommand command = new SubmitInteractionReportCommand(
                    ReportTargetType.COMMENT,
                    COMMENT_ID,
                    REPORTER_ID,
                    ReportReason.SPAM,
                    null
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(CommentNotFoundException.class)
                    .hasMessageContaining("Comment not found: " + COMMENT_ID);
        }

        @Test
        @DisplayName("Rejects self-reporting comment")
        void shouldRejectSelfReportingComment() {
            Comment comment = Comment.createRoot(COMMENT_ID, CommentTarget.communityPost(POST_ID), AUTHOR_ID, "Comment body", NOW);

            when(reportRepositoryPort.existsPendingByTargetAndReporter(ReportTargetType.COMMENT, COMMENT_ID, AUTHOR_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findById(COMMENT_ID))
                    .thenReturn(Optional.of(comment));
            when(commentRepositoryPort.findByIdForUpdate(COMMENT_ID))
                    .thenReturn(Optional.of(comment));

            SubmitInteractionReportCommand command = new SubmitInteractionReportCommand(
                    ReportTargetType.COMMENT,
                    COMMENT_ID,
                    AUTHOR_ID,
                    ReportReason.SPAM,
                    null
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(SelfReportNotAllowedException.class);
        }

        @Test
        @DisplayName("Successfully reports nested reply with canonical lock order when rootId < replyId")
        void shouldLockInCanonicalOrderWhenReportingNestedReplyWithRootIdLowerThanReplyId() {
            UUID rootId = UUID.fromString("11111111-1111-1111-1111-111111111111");
            UUID replyId = UUID.fromString("99999999-9999-9999-9999-999999999999");
            CommentTarget target = CommentTarget.communityPost(POST_ID);

            Comment root = Comment.createRoot(rootId, target, AUTHOR_ID, "Root body", NOW);
            Comment reply = Comment.createReply(replyId, root, AUTHOR_ID, "Reply body to report", NOW.plusSeconds(60));

            when(reportRepositoryPort.existsPendingByTargetAndReporter(ReportTargetType.COMMENT, replyId, REPORTER_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findById(replyId))
                    .thenReturn(Optional.of(reply));
            when(commentRepositoryPort.findByIdForUpdate(rootId))
                    .thenReturn(Optional.of(root));
            when(commentRepositoryPort.findByIdForUpdate(replyId))
                    .thenReturn(Optional.of(reply));
            when(eligibilityPort.isEligible(target))
                    .thenReturn(true);
            when(idGeneratorPort.generate()).thenReturn(REPORT_ID);
            when(clockPort.now()).thenReturn(NOW);
            when(reportRepositoryPort.save(any(InteractionReport.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            SubmitInteractionReportCommand command = new SubmitInteractionReportCommand(
                    ReportTargetType.COMMENT,
                    replyId,
                    REPORTER_ID,
                    ReportReason.HARASSMENT,
                    "Harassing reply"
            );

            InteractionReport report = useCase.execute(command);

            assertThat(report).isNotNull();
            assertThat(report.getId()).isEqualTo(REPORT_ID);
            assertThat(report.getTargetType()).isEqualTo(ReportTargetType.COMMENT);
            assertThat(report.getTargetId()).isEqualTo(replyId);
            assertThat(report.getReportedContentSnapshot()).isEqualTo("Reply body to report");

            // Verify strict canonical lock order: rootId (1111...) first, replyId (9999...) second
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(commentRepositoryPort);
            inOrder.verify(commentRepositoryPort).findById(replyId);
            inOrder.verify(commentRepositoryPort).findByIdForUpdate(rootId);
            inOrder.verify(commentRepositoryPort).findByIdForUpdate(replyId);
        }

        @Test
        @DisplayName("Successfully reports nested reply with canonical lock order when replyId < rootId")
        void shouldLockInCanonicalOrderWhenReportingNestedReplyWithReplyIdLowerThanRootId() {
            UUID replyId = UUID.fromString("11111111-1111-1111-1111-111111111111");
            UUID rootId = UUID.fromString("99999999-9999-9999-9999-999999999999");
            CommentTarget target = CommentTarget.communityPost(POST_ID);

            Comment root = Comment.createRoot(rootId, target, AUTHOR_ID, "Root body", NOW);
            Comment reply = Comment.createReply(replyId, root, AUTHOR_ID, "Reply body to report", NOW.plusSeconds(60));

            when(reportRepositoryPort.existsPendingByTargetAndReporter(ReportTargetType.COMMENT, replyId, REPORTER_ID))
                    .thenReturn(false);
            when(commentRepositoryPort.findById(replyId))
                    .thenReturn(Optional.of(reply));
            when(commentRepositoryPort.findByIdForUpdate(replyId))
                    .thenReturn(Optional.of(reply));
            when(commentRepositoryPort.findByIdForUpdate(rootId))
                    .thenReturn(Optional.of(root));
            when(eligibilityPort.isEligible(target))
                    .thenReturn(true);
            when(idGeneratorPort.generate()).thenReturn(REPORT_ID);
            when(clockPort.now()).thenReturn(NOW);
            when(reportRepositoryPort.save(any(InteractionReport.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            SubmitInteractionReportCommand command = new SubmitInteractionReportCommand(
                    ReportTargetType.COMMENT,
                    replyId,
                    REPORTER_ID,
                    ReportReason.HARASSMENT,
                    "Harassing reply"
            );

            InteractionReport report = useCase.execute(command);

            assertThat(report).isNotNull();
            assertThat(report.getId()).isEqualTo(REPORT_ID);
            assertThat(report.getTargetType()).isEqualTo(ReportTargetType.COMMENT);
            assertThat(report.getTargetId()).isEqualTo(replyId);
            assertThat(report.getReportedContentSnapshot()).isEqualTo("Reply body to report");

            // Verify strict canonical lock order: replyId (1111...) first, rootId (9999...) second
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(commentRepositoryPort);
            inOrder.verify(commentRepositoryPort).findById(replyId);
            inOrder.verify(commentRepositoryPort).findByIdForUpdate(replyId);
            inOrder.verify(commentRepositoryPort).findByIdForUpdate(rootId);
        }
    }
}
