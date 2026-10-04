package com.universe.community.application.usecase;

import com.universe.community.application.command.ApproveCommunityPostCommand;
import com.universe.community.application.command.HideCommunityPostCommand;
import com.universe.community.application.command.RejectCommunityPostCommand;
import com.universe.community.application.command.ResolveCommunityPostReportCommand;
import com.universe.community.application.command.RestoreCommunityPostCommand;
import com.universe.community.application.port.out.CommunityPostModerationEventRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRevisionRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostRevision;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.moderation.CommunityPostModerationAction;
import com.universe.community.domain.moderation.CommunityPostModerationEvent;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.application.exceptions.UnsupportedReportModerationActionException;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Community Post Moderation Use Cases Test")
class CommunityPostModerationUseCaseTest {

    @Mock
    private CommunityPostRepositoryPort postRepositoryPort;

    @Mock
    private CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort;

    @Mock
    private InteractionReportRepositoryPort reportRepositoryPort;

    @Mock
    private ClockPort clockPort;

    @Mock
    private CommunityPostRevisionRepositoryPort revisionRepositoryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    private final Instant now = Instant.parse("2026-10-03T12:00:00Z");
    private final UUID postId = UUID.randomUUID();
    private final UUID authorUserId = UUID.randomUUID();
    private final UUID moderatorUserId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(clockPort.now()).thenReturn(now);
        lenient().when(idGeneratorPort.generate()).thenReturn(UUID.randomUUID());
    }

    @Nested
    @DisplayName("ApproveCommunityPostUseCase")
    class ApproveTests {

        private ApproveCommunityPostUseCase approveUseCase;

        @BeforeEach
        void init() {
            approveUseCase = new ApproveCommunityPostUseCase(
                    postRepositoryPort,
                    moderationEventRepositoryPort,
                    revisionRepositoryPort,
                    idGeneratorPort,
                    clockPort
            );
        }

        @Test
        @DisplayName("Successfully approves PENDING_REVIEW post to PUBLISHED and records event")
        void shouldApprovePendingPost() {
            CommunityPost post = CommunityPost.rehydrate(
                    postId, authorUserId, "Caption", null,
                    CommunityPostStatus.PENDING_REVIEW, 1, now.minusSeconds(100), now.minusSeconds(50),
                    null, now.minusSeconds(100)
            );
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.of(post));

            approveUseCase.execute(new ApproveCommunityPostCommand(postId, moderatorUserId, "Quality check passed"));

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            verify(postRepositoryPort).save(post);

            ArgumentCaptor<CommunityPostModerationEvent> eventCaptor = ArgumentCaptor.forClass(CommunityPostModerationEvent.class);
            verify(moderationEventRepositoryPort).save(eventCaptor.capture());
            CommunityPostModerationEvent event = eventCaptor.getValue();
            assertThat(event.postId()).isEqualTo(postId);
            assertThat(event.action()).isEqualTo(CommunityPostModerationAction.APPROVE);
            assertThat(event.fromStatus()).isEqualTo(CommunityPostStatus.PENDING_REVIEW);
            assertThat(event.toStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(event.moderatorUserId()).isEqualTo(moderatorUserId);
            assertThat(event.reason()).isEqualTo("Quality check passed");
            assertThat(event.createdAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("Fails if post not found")
        void shouldFailIfPostNotFound() {
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> approveUseCase.execute(new ApproveCommunityPostCommand(postId, moderatorUserId, null)))
                    .isInstanceOf(CommunityPostNotFoundException.class);
        }

        @Test
        @DisplayName("Fails if post status is not PENDING_REVIEW")
        void shouldFailIfNotPendingReview() {
            CommunityPost post = CommunityPost.rehydrate(
                    postId, authorUserId, "Caption", null,
                    CommunityPostStatus.PUBLISHED, 1, now.minusSeconds(100), now.minusSeconds(50),
                    now.minusSeconds(100), null
            );
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.of(post));

            assertThatThrownBy(() -> approveUseCase.execute(new ApproveCommunityPostCommand(postId, moderatorUserId, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot approve post with status: PUBLISHED");
        }

        @Test
        @DisplayName("Browser-relevant lifecycle contract: initial approval sets publishedAt, edit under PRE_MODERATION requires re-review and preserves publishedAt, re-approval does not bump publishedAt")
        void shouldHandleFullApprovalEditReReviewLifecycleWithPreservedTimestamps() {
            Instant submittedAt = Instant.parse("2026-10-04T15:00:00Z");
            Instant approvedAt = Instant.parse("2026-10-04T16:20:00Z");

            // A. Initial pending submission at 15:00
            CommunityPost post = CommunityPost.create(
                    postId, authorUserId, "Original post", null,
                    CommunityPostStatus.PENDING_REVIEW, submittedAt, null, submittedAt
            );
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.of(post));
            when(clockPort.now()).thenReturn(approvedAt);

            // B. Admin approves at 16:20
            approveUseCase.execute(new ApproveCommunityPostCommand(postId, moderatorUserId, "First approval"));

            // Assert after initial approval:
            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getCreatedAt()).isEqualTo(submittedAt); // 15:00
            assertThat(post.getPublishedAt()).isEqualTo(approvedAt); // 16:20
            assertThat(post.getReviewRequestedAt()).isNull();

            // C. Public DTO mapping uses approvedAt (16:20), NOT submittedAt (15:00)
            com.universe.community.contracts.dto.CommunityPostPublicDTO publicDto =
                    com.universe.community.application.mapper.CommunityPostDTOMapper.toPublicDTO(post);
            assertThat(publicDto.createdAt()).isEqualTo(submittedAt);
            assertThat(publicDto.publishedAt()).isEqualTo(approvedAt);

            // D. Effective edit under PRE_MODERATION at 16:40
            Instant editAt = Instant.parse("2026-10-04T16:40:00Z");
            boolean changed = post.editCaption(authorUserId, "Updated post caption", editAt, com.universe.community.domain.CommunityPublicationMode.PRE_MODERATION);
            assertThat(changed).isTrue();

            // Assert status after edit = PUBLISHED, caption = "Original post", pendingCaption = "Updated post caption", reviewRequestedAt = 16:40, publishedAt = 16:20
            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getCaption()).isEqualTo("Original post");
            assertThat(post.getPendingCaption()).isEqualTo("Updated post caption");
            assertThat(post.getReviewRequestedAt()).isEqualTo(editAt);
            assertThat(post.getPublishedAt()).isEqualTo(approvedAt); // strictly preserved!

            // E. Admin approves pending edit at 16:45
            Instant reApproveAt = Instant.parse("2026-10-04T16:45:00Z");
            when(clockPort.now()).thenReturn(reApproveAt);
            when(idGeneratorPort.generate()).thenReturn(UUID.randomUUID());

            approveUseCase.execute(new ApproveCommunityPostCommand(postId, moderatorUserId, "Approval after edit"));

            // Assert after edit approval: status = PUBLISHED, caption = "Updated post caption", pendingCaption = null, reviewRequestedAt = null, publishedAt remains 16:20
            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getCaption()).isEqualTo("Updated post caption");
            assertThat(post.getPendingCaption()).isNull();
            assertThat(post.getReviewRequestedAt()).isNull();
            assertThat(post.getPublishedAt()).isEqualTo(approvedAt); // STILL 16:20, NOT bumped to 16:45!
            assertThat(post.getContentVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("Successfully approves pending caption edit on PUBLISHED post and archives revision")
        void shouldApprovePendingCaptionEditAndArchiveRevision() {
            Instant publishedAt = now.minusSeconds(1000);
            Instant editAt = now.minusSeconds(200);
            UUID revisionId = UUID.randomUUID();

            CommunityPost post = CommunityPost.rehydrate(
                    postId, authorUserId, "Approved caption", "Proposed new caption",
                    null, CommunityPostStatus.PUBLISHED, 0, publishedAt, editAt,
                    publishedAt, editAt
            );
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.of(post));
            when(idGeneratorPort.generate()).thenReturn(revisionId);

            approveUseCase.execute(new ApproveCommunityPostCommand(postId, moderatorUserId, "Edit looks great"));

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getCaption()).isEqualTo("Proposed new caption");
            assertThat(post.getPendingCaption()).isNull();
            assertThat(post.getContentVersion()).isEqualTo(1);
            assertThat(post.getPublishedAt()).isEqualTo(publishedAt);
            assertThat(post.getReviewRequestedAt()).isNull();

            verify(postRepositoryPort).save(post);

            ArgumentCaptor<CommunityPostRevision> revCaptor = ArgumentCaptor.forClass(CommunityPostRevision.class);
            verify(revisionRepositoryPort).save(revCaptor.capture());
            CommunityPostRevision revision = revCaptor.getValue();
            assertThat(revision.id()).isEqualTo(revisionId);
            assertThat(revision.postId()).isEqualTo(postId);
            assertThat(revision.revisionNumber()).isEqualTo(1);
            assertThat(revision.editorUserId()).isEqualTo(authorUserId);
            assertThat(revision.previousCaption()).isEqualTo("Approved caption");
            assertThat(revision.caption()).isEqualTo("Proposed new caption");
            assertThat(revision.editedAt()).isEqualTo(now);

            ArgumentCaptor<CommunityPostModerationEvent> eventCaptor = ArgumentCaptor.forClass(CommunityPostModerationEvent.class);
            verify(moderationEventRepositoryPort).save(eventCaptor.capture());
            CommunityPostModerationEvent event = eventCaptor.getValue();
            assertThat(event.action()).isEqualTo(CommunityPostModerationAction.APPROVE);
            assertThat(event.fromStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(event.toStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        }
    }

    @Nested
    @DisplayName("RejectCommunityPostUseCase")
    class RejectTests {

        private RejectCommunityPostUseCase rejectUseCase;

        @BeforeEach
        void init() {
            rejectUseCase = new RejectCommunityPostUseCase(postRepositoryPort, moderationEventRepositoryPort, clockPort);
        }

        @Test
        @DisplayName("Successfully rejects PENDING_REVIEW post to REJECTED and records event")
        void shouldRejectPendingPost() {
            CommunityPost post = CommunityPost.rehydrate(
                    postId, authorUserId, "Caption", null,
                    CommunityPostStatus.PENDING_REVIEW, 1, now.minusSeconds(100), now.minusSeconds(50),
                    null, now.minusSeconds(100)
            );
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.of(post));

            rejectUseCase.execute(new RejectCommunityPostCommand(postId, moderatorUserId, "Inappropriate content"));

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.REJECTED);
            verify(postRepositoryPort).save(post);

            ArgumentCaptor<CommunityPostModerationEvent> eventCaptor = ArgumentCaptor.forClass(CommunityPostModerationEvent.class);
            verify(moderationEventRepositoryPort).save(eventCaptor.capture());
            CommunityPostModerationEvent event = eventCaptor.getValue();
            assertThat(event.postId()).isEqualTo(postId);
            assertThat(event.action()).isEqualTo(CommunityPostModerationAction.REJECT);
            assertThat(event.fromStatus()).isEqualTo(CommunityPostStatus.PENDING_REVIEW);
            assertThat(event.toStatus()).isEqualTo(CommunityPostStatus.REJECTED);
            assertThat(event.moderatorUserId()).isEqualTo(moderatorUserId);
            assertThat(event.reason()).isEqualTo("Inappropriate content");
        }

        @Test
        @DisplayName("Successfully rejects pending caption edit on PUBLISHED post: discards candidate, keeps original caption and PUBLISHED status")
        void shouldRejectPendingCaptionEditAndPreserveOriginalCaption() {
            Instant publishedAt = now.minusSeconds(1000);
            Instant editAt = now.minusSeconds(200);

            CommunityPost post = CommunityPost.rehydrate(
                    postId, authorUserId, "Approved caption", "Bad proposed caption",
                    null, CommunityPostStatus.PUBLISHED, 0, publishedAt, editAt,
                    publishedAt, editAt
            );
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.of(post));

            rejectUseCase.execute(new RejectCommunityPostCommand(postId, moderatorUserId, "Rejected edit"));

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getCaption()).isEqualTo("Approved caption");
            assertThat(post.getPendingCaption()).isNull();
            assertThat(post.getContentVersion()).isEqualTo(0);
            assertThat(post.getPublishedAt()).isEqualTo(publishedAt);
            assertThat(post.getReviewRequestedAt()).isNull();

            verify(postRepositoryPort).save(post);

            ArgumentCaptor<CommunityPostModerationEvent> eventCaptor = ArgumentCaptor.forClass(CommunityPostModerationEvent.class);
            verify(moderationEventRepositoryPort).save(eventCaptor.capture());
            CommunityPostModerationEvent event = eventCaptor.getValue();
            assertThat(event.action()).isEqualTo(CommunityPostModerationAction.REJECT);
            assertThat(event.fromStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(event.toStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        }
    }

    @Nested
    @DisplayName("HideCommunityPostUseCase")
    class HideTests {

        private HideCommunityPostUseCase hideUseCase;

        @BeforeEach
        void init() {
            hideUseCase = new HideCommunityPostUseCase(postRepositoryPort, moderationEventRepositoryPort, clockPort);
        }

        @Test
        @DisplayName("Successfully hides PUBLISHED post to HIDDEN and records event")
        void shouldHidePublishedPost() {
            CommunityPost post = CommunityPost.rehydrate(
                    postId, authorUserId, "Caption", null,
                    CommunityPostStatus.PUBLISHED, 1, now.minusSeconds(100), now.minusSeconds(50),
                    now.minusSeconds(100), null
            );
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.of(post));

            hideUseCase.execute(new HideCommunityPostCommand(postId, moderatorUserId, "Toxicity"));

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
            verify(postRepositoryPort).save(post);

            ArgumentCaptor<CommunityPostModerationEvent> eventCaptor = ArgumentCaptor.forClass(CommunityPostModerationEvent.class);
            verify(moderationEventRepositoryPort).save(eventCaptor.capture());
            CommunityPostModerationEvent event = eventCaptor.getValue();
            assertThat(event.postId()).isEqualTo(postId);
            assertThat(event.action()).isEqualTo(CommunityPostModerationAction.HIDE);
            assertThat(event.fromStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(event.toStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
        }

        @Test
        @DisplayName("Hide safety: hiding published post with pending caption edit clears candidate caption")
        void shouldHidePublishedPostWithPendingCaptionEditClearingCandidate() {
            Instant publishedAt = now.minusSeconds(1000);
            Instant editAt = now.minusSeconds(200);

            CommunityPost post = CommunityPost.rehydrate(
                    postId, authorUserId, "Approved caption", "Candidate to be cleared",
                    null, CommunityPostStatus.PUBLISHED, 0, publishedAt, editAt,
                    publishedAt, editAt
            );
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.of(post));

            hideUseCase.execute(new HideCommunityPostCommand(postId, moderatorUserId, "Toxicity"));

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
            assertThat(post.getCaption()).isEqualTo("Approved caption");
            assertThat(post.getPendingCaption()).isNull();
            assertThat(post.getReviewRequestedAt()).isNull();
            assertThat(post.getPublishedAt()).isEqualTo(publishedAt);

            verify(postRepositoryPort).save(post);
        }
    }

    @Nested
    @DisplayName("RestoreCommunityPostUseCase")
    class RestoreTests {

        private RestoreCommunityPostUseCase restoreUseCase;

        @BeforeEach
        void init() {
            restoreUseCase = new RestoreCommunityPostUseCase(postRepositoryPort, moderationEventRepositoryPort, clockPort);
        }

        @Test
        @DisplayName("Successfully restores HIDDEN post to PUBLISHED and records event")
        void shouldRestoreHiddenPost() {
            CommunityPost post = CommunityPost.rehydrate(
                    postId, authorUserId, "Caption", null,
                    CommunityPostStatus.HIDDEN, 1, now.minusSeconds(100), now.minusSeconds(50),
                    now.minusSeconds(100), null
            );
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.of(post));

            restoreUseCase.execute(new RestoreCommunityPostCommand(postId, moderatorUserId, "Appeal accepted"));

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            verify(postRepositoryPort).save(post);

            ArgumentCaptor<CommunityPostModerationEvent> eventCaptor = ArgumentCaptor.forClass(CommunityPostModerationEvent.class);
            verify(moderationEventRepositoryPort).save(eventCaptor.capture());
            CommunityPostModerationEvent event = eventCaptor.getValue();
            assertThat(event.postId()).isEqualTo(postId);
            assertThat(event.action()).isEqualTo(CommunityPostModerationAction.RESTORE);
            assertThat(event.fromStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
            assertThat(event.toStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        }
    }

    @Nested
    @DisplayName("ResolveCommunityPostReportUseCase")
    class ResolveReportTests {

        private ResolveCommunityPostReportUseCase resolveReportUseCase;

        @BeforeEach
        void init() {
            resolveReportUseCase = new ResolveCommunityPostReportUseCase(
                    reportRepositoryPort, postRepositoryPort, moderationEventRepositoryPort, clockPort
            );
        }

        @Test
        @DisplayName("Resolves report with NO_ACTION: does NOT alter post or record post moderation event")
        void shouldResolveReportWithNoAction() {
            InteractionReport report = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMUNITY_POST, postId, authorUserId,
                    ReportReason.SPAM, null, "Snapshot caption", null, now.minusSeconds(60)
            );
            when(reportRepositoryPort.findByIdForUpdate(reportId)).thenReturn(Optional.of(report));

            resolveReportUseCase.execute(new ResolveCommunityPostReportCommand(
                    reportId, moderatorUserId, ReportModerationAction.NO_ACTION, "Report invalid"
            ));

            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
            assertThat(report.getModerationAction()).isEqualTo(ReportModerationAction.NO_ACTION);
            assertThat(report.getResolvedByUserId()).isEqualTo(moderatorUserId);
            assertThat(report.getResolvedAt()).isEqualTo(now);

            verify(reportRepositoryPort).save(report);
            verify(postRepositoryPort, never()).findByIdForUpdate(any());
            verify(postRepositoryPort, never()).save(any());
            verify(moderationEventRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Resolves report with CONTENT_HIDDEN: locks post, transitions PUBLISHED->HIDDEN, records event and resolves report")
        void shouldResolveReportWithContentHidden() {
            InteractionReport report = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMUNITY_POST, postId, authorUserId,
                    ReportReason.HARASSMENT, null, "Violating caption", null, now.minusSeconds(60)
            );
            CommunityPost post = CommunityPost.rehydrate(
                    postId, authorUserId, "Violating caption", null,
                    CommunityPostStatus.PUBLISHED, 1, now.minusSeconds(120), now.minusSeconds(60),
                    now.minusSeconds(120), null
            );

            when(reportRepositoryPort.findTargetMetadataById(reportId)).thenReturn(
                    Optional.of(new InteractionReportRepositoryPort.ReportTargetMetadata(ReportTargetType.COMMUNITY_POST, postId))
            );
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.of(post));
            when(reportRepositoryPort.findByIdForUpdate(reportId)).thenReturn(Optional.of(report));

            resolveReportUseCase.execute(new ResolveCommunityPostReportCommand(
                    reportId, moderatorUserId, ReportModerationAction.CONTENT_HIDDEN, "Violates community policy"
            ));

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
            verify(postRepositoryPort).save(post);

            ArgumentCaptor<CommunityPostModerationEvent> eventCaptor = ArgumentCaptor.forClass(CommunityPostModerationEvent.class);
            verify(moderationEventRepositoryPort).save(eventCaptor.capture());
            CommunityPostModerationEvent event = eventCaptor.getValue();
            assertThat(event.postId()).isEqualTo(postId);
            assertThat(event.action()).isEqualTo(CommunityPostModerationAction.HIDE);
            assertThat(event.fromStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(event.toStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
            assertThat(event.moderatorUserId()).isEqualTo(moderatorUserId);
            assertThat(event.reason()).isEqualTo("Violates community policy");

            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
            assertThat(report.getModerationAction()).isEqualTo(ReportModerationAction.CONTENT_HIDDEN);
            assertThat(report.getResolvedByUserId()).isEqualTo(moderatorUserId);
            assertThat(report.getResolvedAt()).isEqualTo(now);
            verify(reportRepositoryPort).save(report);
        }

        @Test
        @DisplayName("Rejects CONTENT_HIDDEN if report is already resolved")
        void shouldRejectIfReportAlreadyResolved() {
            InteractionReport report = InteractionReport.reconstitute(
                    reportId, ReportTargetType.COMMUNITY_POST, postId, authorUserId,
                    ReportReason.HARASSMENT, null, "Violating caption", null,
                    ReportStatus.RESOLVED_ACTION_TAKEN, now.minusSeconds(100),
                    moderatorUserId, now.minusSeconds(10), ReportModerationAction.CONTENT_HIDDEN, null
            );
            CommunityPost post = CommunityPost.rehydrate(
                    postId, authorUserId, "Violating caption", null,
                    CommunityPostStatus.PUBLISHED, 1, now.minusSeconds(120), now.minusSeconds(60),
                    now.minusSeconds(120), null
            );

            when(reportRepositoryPort.findTargetMetadataById(reportId)).thenReturn(
                    Optional.of(new InteractionReportRepositoryPort.ReportTargetMetadata(ReportTargetType.COMMUNITY_POST, postId))
            );
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.of(post));
            when(reportRepositoryPort.findByIdForUpdate(reportId)).thenReturn(Optional.of(report));

            assertThatThrownBy(() -> resolveReportUseCase.execute(new ResolveCommunityPostReportCommand(
                    reportId, moderatorUserId, ReportModerationAction.CONTENT_HIDDEN, null
            ))).isInstanceOf(ReportAlreadyResolvedException.class);
        }

        @Test
        @DisplayName("Rejects CONTENT_HIDDEN if target post is not PUBLISHED")
        void shouldRejectIfPostNotPublished() {
            CommunityPost post = CommunityPost.rehydrate(
                    postId, authorUserId, "Violating caption", null,
                    CommunityPostStatus.HIDDEN, 1, now.minusSeconds(120), now.minusSeconds(60),
                    now.minusSeconds(120), null
            );

            when(reportRepositoryPort.findTargetMetadataById(reportId)).thenReturn(
                    Optional.of(new InteractionReportRepositoryPort.ReportTargetMetadata(ReportTargetType.COMMUNITY_POST, postId))
            );
            when(postRepositoryPort.findByIdForUpdate(postId)).thenReturn(Optional.of(post));

            assertThatThrownBy(() -> resolveReportUseCase.execute(new ResolveCommunityPostReportCommand(
                    reportId, moderatorUserId, ReportModerationAction.CONTENT_HIDDEN, null
            ))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot hide post with status: HIDDEN");
        }

        @Test
        @DisplayName("Rejects CONTENT_HIDDEN if report target type is not COMMUNITY_POST")
        void shouldRejectIfTargetNotCommunityPost() {
            when(reportRepositoryPort.findTargetMetadataById(reportId)).thenReturn(
                    Optional.of(new InteractionReportRepositoryPort.ReportTargetMetadata(ReportTargetType.COMMENT, postId))
            );

            assertThatThrownBy(() -> resolveReportUseCase.execute(new ResolveCommunityPostReportCommand(
                    reportId, moderatorUserId, ReportModerationAction.CONTENT_HIDDEN, null
            ))).isInstanceOf(UnsupportedReportModerationActionException.class);
        }
    }
}
