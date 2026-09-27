package com.universe.wiki.application.contribution.workflow;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.notification.contracts.command.NotificationDispatchCommand;
import com.universe.notification.contracts.port.NotificationDispatchPort;
import com.universe.notification.domain.NotificationType;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiContributionNotFoundException;
import com.universe.wiki.application.exceptions.WikiContributionStaleMutationException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionWorkflowEventRepositoryPort;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionEventType;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.domain.contribution.WikiContributionWorkflowEvent;
import com.universe.wiki.domain.revision.WikiArticleRevision;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Admin Wiki Contribution Workflow Use Case Tests")
class AdminWikiContributionWorkflowUseCaseTest {

    @Mock
    private WikiContributionRepositoryPort contributionRepository;

    @Mock
    private WikiArticleRepositoryPort articleRepository;

    @Mock
    private WikiArticleRevisionRepositoryPort revisionRepository;

    @Mock
    private WikiContributionWorkflowEventRepositoryPort workflowEventRepository;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    @Mock
    private NotificationDispatchPort notificationDispatchPort;

    private AdminWikiContributionWorkflowUseCase useCase;

    private final Instant now = Instant.parse("2026-09-25T11:00:00Z");
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(idGeneratorPort.generate()).thenReturn(UUID.randomUUID());
        useCase = new AdminWikiContributionWorkflowUseCase(
                contributionRepository,
                articleRepository,
                revisionRepository,
                workflowEventRepository,
                userIdentityContract,
                idGeneratorPort,
                clockPort,
                notificationDispatchPort
        );
    }

    private WikiContribution createSampleContribution() {
        return WikiContribution.createGeneral(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                UUID.randomUUID(),
                WikiContributionType.INCORRECT_INFORMATION,
                "Đóng góp thông tin cập nhật nhân vật này",
                now.minusSeconds(300)
        );
    }

    @Test
    @DisplayName("Review action transitions NEW to REVIEWING and persists")
    void shouldExecuteReviewWorkflow() {
        WikiContribution contribution = createSampleContribution();
        UUID id = contribution.getId();

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(articleRepository.findById(contribution.getArticleId())).thenReturn(Optional.empty());
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        WikiContribution result = useCase.review(new ReviewWikiContributionCommand(id, actorId, 0L));

        assertThat(result.getStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(result.getAssignedToUserId()).isEqualTo(actorId);
        assertThat(result.getAssignedAt()).isEqualTo(now);
        assertThat(result.getReviewStartedByUserId()).isEqualTo(actorId);
        assertThat(result.getReviewStartedAt()).isEqualTo(now);
        assertThat(result.getUpdatedAt()).isEqualTo(now);
        verify(contributionRepository).save(contribution);
        verify(workflowEventRepository).save(any(WikiContributionWorkflowEvent.class));

        ArgumentCaptor<NotificationDispatchCommand> notifCaptor = ArgumentCaptor.forClass(NotificationDispatchCommand.class);
        verify(notificationDispatchPort).dispatch(notifCaptor.capture());
        NotificationDispatchCommand notifCmd = notifCaptor.getValue();
        assertThat(notifCmd.recipientUserId()).isEqualTo(contribution.getSubmittedByUserId());
        assertThat(notifCmd.type()).isEqualTo(NotificationType.WIKI_CONTRIBUTION_REVIEWING);
        assertThat(notifCmd.actorUserId()).isEqualTo(actorId);
        assertThat(notifCmd.actorDisplayNameSnapshot()).isNull();
        assertThat(notifCmd.targetType()).isEqualTo("WIKI_CONTRIBUTION");
        assertThat(notifCmd.targetId()).isEqualTo(id);
        assertThat(notifCmd.targetTitleSnapshot()).isEqualTo(contribution.getArticleTitleSnapshot());
        assertThat(notifCmd.commentId()).isNull();
        assertThat(notifCmd.threadRootId()).isNull();
        assertThat(notifCmd.detailSnapshot()).isNull();
        assertThat(notifCmd.dedupeKey()).isEqualTo("WIKI_CONTRIBUTION:" + id + ":REVIEWING");
    }

    @Test
    @DisplayName("Review action with mismatched expectedVersion throws WikiContributionStaleMutationException")
    void shouldThrowStaleExceptionWhenExpectedVersionMismatches() {
        WikiContribution contribution = createSampleContribution();
        UUID id = contribution.getId();

        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));

        assertThatThrownBy(() -> useCase.review(new ReviewWikiContributionCommand(id, actorId, 99L)))
                .isInstanceOf(WikiContributionStaleMutationException.class);
    }

    private WikiContribution createLegacyUnassignedReviewingContribution() {
        return WikiContribution.reconstitute(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                UUID.randomUUID(),
                com.universe.wiki.domain.contribution.WikiContributionContextType.GENERAL,
                WikiContributionType.INCORRECT_INFORMATION,
                "Đóng góp thông tin cập nhật nhân vật này",
                null,
                null,
                null,
                null,
                WikiContributionStatus.REVIEWING,
                0L,
                now.minusSeconds(300),
                now.minusSeconds(100),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    @Test
    @DisplayName("Claim action transitions legacy unassigned REVIEWING contribution to assigned and persists")
    void shouldExecuteClaimWorkflow() {
        WikiContribution contribution = createLegacyUnassignedReviewingContribution();
        UUID id = contribution.getId();

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        WikiContribution result = useCase.claim(new ClaimWikiContributionCommand(id, actorId, 0L));

        assertThat(result.getStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(result.getAssignedToUserId()).isEqualTo(actorId);
        assertThat(result.getAssignedAt()).isEqualTo(now);
        verify(contributionRepository).save(contribution);
        verify(workflowEventRepository).save(any(WikiContributionWorkflowEvent.class));
    }

    @Test
    @DisplayName("Reassign action reassigns active admin/super_admin and persists")
    void shouldExecuteReassignWorkflow() {
        WikiContribution contribution = createSampleContribution();
        contribution.startReview(UUID.randomUUID(), 1L, now.minusSeconds(200));
        UUID id = contribution.getId();
        UUID newAdminId = UUID.randomUUID();

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(userIdentityContract.findById(newAdminId)).thenReturn(Optional.of(
                new UserDTO(newAdminId, "admin2@universe.com", "Admin Two", null, "ACTIVE", "ADMIN", now)
        ));
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        WikiContribution result = useCase.reassign(new ReassignWikiContributionCommand(id, actorId, newAdminId, "Chuyển giao việc", 0L));

        assertThat(result.getAssignedToUserId()).isEqualTo(newAdminId);
        assertThat(result.getAssignedAt()).isEqualTo(now);
        verify(contributionRepository).save(contribution);
        verify(workflowEventRepository).save(any(WikiContributionWorkflowEvent.class));
    }

    @Test
    @DisplayName("Reassign action fails closed when contribution is unassigned")
    void shouldRejectReassignWhenUnassigned() {
        WikiContribution unassigned = createLegacyUnassignedReviewingContribution();
        UUID id = unassigned.getId();
        UUID newAdminId = UUID.randomUUID();

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(unassigned));
        when(userIdentityContract.findById(newAdminId)).thenReturn(Optional.of(
                new UserDTO(newAdminId, "admin2@universe.com", "Admin Two", null, "ACTIVE", "ADMIN", now)
        ));

        assertThatThrownBy(() -> useCase.reassign(new ReassignWikiContributionCommand(id, actorId, newAdminId, "Chuyển việc", 0L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Đóng góp chưa có người phụ trách; phải tiếp nhận (claim) trước khi phân công lại.");

        verify(contributionRepository, never()).save(any());
        verify(workflowEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Resolve action with APPLIED outcome requires linked revision and persists RESOLVED")
    void shouldExecuteResolveWorkflowWithArticleVersion() {
        WikiContribution contribution = createSampleContribution();
        contribution.startReview(actorId, 1L, now.minusSeconds(100));
        UUID id = contribution.getId();

        WikiArticleRevision linkedRevision = mock(WikiArticleRevision.class);
        when(linkedRevision.articleId()).thenReturn(contribution.getArticleId());
        when(linkedRevision.contentVersion()).thenReturn(3L);

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(revisionRepository.findLatestBySourceContributionId(id)).thenReturn(Optional.of(linkedRevision));
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        WikiContribution result = useCase.resolve(new ResolveWikiContributionCommand(
                id, actorId, 0L, WikiContributionResolutionOutcome.APPLIED, "Đã cập nhật bài viết theo thông tin chính xác."
        ));

        assertThat(result.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(result.getResolutionOutcome()).isEqualTo(WikiContributionResolutionOutcome.APPLIED);
        assertThat(result.getResolutionNote()).isEqualTo("Đã cập nhật bài viết theo thông tin chính xác.");
        assertThat(result.getResolvedByUserId()).isEqualTo(actorId);
        assertThat(result.getResolvedAt()).isEqualTo(now);
        assertThat(result.getResolvedArticleContentVersion()).isEqualTo(3L);
        verify(contributionRepository).save(contribution);

        ArgumentCaptor<WikiContributionWorkflowEvent> eventCaptor = ArgumentCaptor.forClass(WikiContributionWorkflowEvent.class);
        verify(workflowEventRepository).save(eventCaptor.capture());
        WikiContributionWorkflowEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(WikiContributionEventType.RESOLVED);
        assertThat(event.getFromStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(event.getToStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(event.getResolutionOutcome()).isEqualTo(WikiContributionResolutionOutcome.APPLIED);

        ArgumentCaptor<NotificationDispatchCommand> notifCaptor = ArgumentCaptor.forClass(NotificationDispatchCommand.class);
        verify(notificationDispatchPort).dispatch(notifCaptor.capture());
        NotificationDispatchCommand notifCmd = notifCaptor.getValue();
        assertThat(notifCmd.recipientUserId()).isEqualTo(contribution.getSubmittedByUserId());
        assertThat(notifCmd.type()).isEqualTo(NotificationType.WIKI_CONTRIBUTION_RESOLVED);
        assertThat(notifCmd.actorUserId()).isEqualTo(actorId);
        assertThat(notifCmd.actorDisplayNameSnapshot()).isNull();
        assertThat(notifCmd.targetType()).isEqualTo("WIKI_CONTRIBUTION");
        assertThat(notifCmd.targetId()).isEqualTo(id);
        assertThat(notifCmd.targetTitleSnapshot()).isEqualTo(contribution.getArticleTitleSnapshot());
        assertThat(notifCmd.commentId()).isNull();
        assertThat(notifCmd.threadRootId()).isNull();
        assertThat(notifCmd.detailSnapshot()).isEqualTo("Đã cập nhật bài viết theo thông tin chính xác.");
        assertThat(notifCmd.dedupeKey()).isEqualTo("WIKI_CONTRIBUTION:" + id + ":RESOLVED");
    }

    @Test
    @DisplayName("APPLIED outcome uses latest revision when multiple revisions linked to contribution exist (v5 and v6 -> uses v6)")
    void shouldResolveAppliedWithLatestRevisionWhenMultipleLinkedRevisionsExist() {
        WikiContribution contribution = createSampleContribution();
        contribution.startReview(actorId, 1L, now.minusSeconds(100));
        UUID id = contribution.getId();

        // Given multiple revisions v5 and v6 linked to this contribution, repository returns highest (v6)
        WikiArticleRevision linkedRevisionV6 = mock(WikiArticleRevision.class);
        when(linkedRevisionV6.articleId()).thenReturn(contribution.getArticleId());
        when(linkedRevisionV6.contentVersion()).thenReturn(6L);

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(revisionRepository.findLatestBySourceContributionId(id)).thenReturn(Optional.of(linkedRevisionV6));
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        WikiContribution result = useCase.resolve(new ResolveWikiContributionCommand(
                id, actorId, 0L, WikiContributionResolutionOutcome.APPLIED, "Đã áp dụng các bản cập nhật liên kết v5 và v6."
        ));

        assertThat(result.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(result.getResolvedArticleContentVersion()).isEqualTo(6L);
        verify(revisionRepository).findLatestBySourceContributionId(id);
    }

    @Test
    @DisplayName("APPLIED outcome fails when newer article revision does not match sourceContributionId")
    void shouldRejectAppliedWhenNoRevisionMatchesSourceContributionIdEvenIfArticleHasNewerRevisions() {
        WikiContribution contribution = createSampleContribution();
        contribution.startReview(actorId, 1L, now.minusSeconds(100));
        UUID id = contribution.getId();

        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        // Newer revision v7 exists on article but has NO sourceContributionId == id -> repository returns empty
        when(revisionRepository.findLatestBySourceContributionId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.resolve(new ResolveWikiContributionCommand(
                id, actorId, 0L, WikiContributionResolutionOutcome.APPLIED, "Thử áp dụng khi không có revision liên kết."
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Không thể hoàn tất với kết quả ĐÃ ÁP DỤNG khi chưa có bản sửa bài viết nào được liên kết");
    }

    @Test
    @DisplayName("Resolve action with NO_CHANGE_NEEDED outcome succeeds without linked revision")
    void shouldExecuteResolveWorkflowWithNoChangeNeeded() {
        WikiContribution contribution = createSampleContribution();
        contribution.startReview(actorId, 1L, now.minusSeconds(100));
        UUID id = contribution.getId();

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(articleRepository.findById(contribution.getArticleId())).thenReturn(Optional.empty());
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        WikiContribution result = useCase.resolve(new ResolveWikiContributionCommand(
                id, actorId, 0L, WikiContributionResolutionOutcome.NO_CHANGE_NEEDED, "Bài viết đã đầy đủ, không cần chỉnh sửa thêm."
        ));

        assertThat(result.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(result.getResolutionOutcome()).isEqualTo(WikiContributionResolutionOutcome.NO_CHANGE_NEEDED);
        assertThat(result.getResolvedArticleContentVersion()).isNull();
        verify(workflowEventRepository).save(any(WikiContributionWorkflowEvent.class));
    }

    @Test
    @DisplayName("Reject action transitions to REJECTED with null resolvedArticleContentVersion")
    void shouldExecuteRejectWorkflow() {
        WikiContribution contribution = createSampleContribution();
        contribution.startReview(actorId, 1L, now.minusSeconds(100));
        UUID id = contribution.getId();

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        WikiContribution result = useCase.reject(new RejectWikiContributionCommand(
                id, actorId, 0L, "Từ chối đóng góp do nội dung không chính xác."
        ));

        assertThat(result.getStatus()).isEqualTo(WikiContributionStatus.REJECTED);
        assertThat(result.getResolutionNote()).isEqualTo("Từ chối đóng góp do nội dung không chính xác.");
        assertThat(result.getResolvedByUserId()).isEqualTo(actorId);
        assertThat(result.getResolvedAt()).isEqualTo(now);
        assertThat(result.getResolvedArticleContentVersion()).isNull();
        ArgumentCaptor<WikiContributionWorkflowEvent> eventCaptor = ArgumentCaptor.forClass(WikiContributionWorkflowEvent.class);
        verify(workflowEventRepository).save(eventCaptor.capture());
        WikiContributionWorkflowEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(WikiContributionEventType.REJECTED);
        assertThat(event.getFromStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(event.getToStatus()).isEqualTo(WikiContributionStatus.REJECTED);

        ArgumentCaptor<NotificationDispatchCommand> notifCaptor = ArgumentCaptor.forClass(NotificationDispatchCommand.class);
        verify(notificationDispatchPort).dispatch(notifCaptor.capture());
        NotificationDispatchCommand notifCmd = notifCaptor.getValue();
        assertThat(notifCmd.recipientUserId()).isEqualTo(contribution.getSubmittedByUserId());
        assertThat(notifCmd.type()).isEqualTo(NotificationType.WIKI_CONTRIBUTION_REJECTED);
        assertThat(notifCmd.actorUserId()).isEqualTo(actorId);
        assertThat(notifCmd.actorDisplayNameSnapshot()).isNull();
        assertThat(notifCmd.targetType()).isEqualTo("WIKI_CONTRIBUTION");
        assertThat(notifCmd.targetId()).isEqualTo(id);
        assertThat(notifCmd.targetTitleSnapshot()).isEqualTo(contribution.getArticleTitleSnapshot());
        assertThat(notifCmd.commentId()).isNull();
        assertThat(notifCmd.threadRootId()).isNull();
        assertThat(notifCmd.detailSnapshot()).isEqualTo("Từ chối đóng góp do nội dung không chính xác.");
        assertThat(notifCmd.dedupeKey()).isEqualTo("WIKI_CONTRIBUTION:" + id + ":REJECTED");
    }

    @Test
    @DisplayName("Self-action suppression: admin reviewing, resolving or rejecting their own contribution does NOT dispatch notification")
    void shouldSuppressNotificationWhenAdminActsOnOwnContribution() {
        WikiContribution contribution = WikiContribution.createGeneral(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CHARACTER",
                "Tiêu Đề",
                "tieu-de",
                1L,
                actorId, // Submitted by same admin!
                WikiContributionType.INCORRECT_INFORMATION,
                "Đóng góp từ chính admin này",
                now.minusSeconds(300)
        );
        UUID id = contribution.getId();

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(articleRepository.findById(contribution.getArticleId())).thenReturn(Optional.empty());
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Review by self -> no notification
        useCase.review(new ReviewWikiContributionCommand(id, actorId, 0L));
        verify(notificationDispatchPort, never()).dispatch(any());

        // 2. Resolve by self -> no notification
        useCase.resolve(new ResolveWikiContributionCommand(
                id, actorId, 0L, WikiContributionResolutionOutcome.NO_CHANGE_NEEDED, "Ghi chú hợp lệ từ admin."
        ));
        verify(notificationDispatchPort, never()).dispatch(any());
    }

    @Test
    @DisplayName("Claim and reassign do NOT dispatch notifications")
    void shouldNotDispatchNotificationsOnClaimOrReassign() {
        WikiContribution contribution = createLegacyUnassignedReviewingContribution();
        UUID id = contribution.getId();
        UUID newAdminId = UUID.randomUUID();

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        // Claim
        useCase.claim(new ClaimWikiContributionCommand(id, actorId, 0L));
        verify(notificationDispatchPort, never()).dispatch(any());

        // Reassign
        when(userIdentityContract.findById(newAdminId)).thenReturn(Optional.of(
                new UserDTO(newAdminId, "admin2@universe.com", "Admin Two", null, "ACTIVE", "ADMIN", now)
        ));
        useCase.reassign(new ReassignWikiContributionCommand(id, actorId, newAdminId, "Chuyển việc", 0L));
        verify(notificationDispatchPort, never()).dispatch(any());
    }

    @Test
    @DisplayName("Resolve or reject by non-assignee throws IllegalStateException")
    void shouldRejectMutationByNonAssignee() {
        WikiContribution contribution = createSampleContribution();
        UUID otherAdminId = UUID.randomUUID();
        contribution.startReview(actorId, 1L, now.minusSeconds(100));
        UUID id = contribution.getId();

        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));

        assertThatThrownBy(() -> useCase.resolve(new ResolveWikiContributionCommand(
                id, otherAdminId, 0L, WikiContributionResolutionOutcome.DUPLICATE, "Trùng lặp với đóng góp trước đó."
        ))).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> useCase.reject(new RejectWikiContributionCommand(
                id, otherAdminId, 0L, "Từ chối đóng góp này."
        ))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Resolve or reject directly on NEW throws IllegalStateException")
    void shouldRejectDirectResolveOrRejectOnNew() {
        WikiContribution contribution = createSampleContribution();
        UUID id = contribution.getId();

        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));

        assertThatThrownBy(() -> useCase.resolve(new ResolveWikiContributionCommand(
                id, actorId, 0L, WikiContributionResolutionOutcome.DUPLICATE, "Trùng lặp đóng góp."
        ))).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> useCase.reject(new RejectWikiContributionCommand(
                id, actorId, 0L, "Từ chối đóng góp."
        ))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Workflow throws WikiContributionNotFoundException when contribution does not exist")
    void shouldThrowNotFoundWhenContributionDoesNotExist() {
        UUID unknownId = UUID.randomUUID();
        when(contributionRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.review(new ReviewWikiContributionCommand(unknownId, actorId, 0L)))
                .isInstanceOf(WikiContributionNotFoundException.class);
        assertThatThrownBy(() -> useCase.resolve(new ResolveWikiContributionCommand(unknownId, actorId, 0L, "Ghi chú hợp lệ")))
                .isInstanceOf(WikiContributionNotFoundException.class);
        assertThatThrownBy(() -> useCase.reject(new RejectWikiContributionCommand(unknownId, actorId, 0L, "Ghi chú hợp lệ")))
                .isInstanceOf(WikiContributionNotFoundException.class);
    }
}
