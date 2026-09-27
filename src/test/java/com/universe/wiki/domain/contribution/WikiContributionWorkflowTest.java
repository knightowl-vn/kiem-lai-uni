package com.universe.wiki.domain.contribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Wiki Contribution Workflow Domain State Machine Tests")
class WikiContributionWorkflowTest {

    private final Instant createdAt = Instant.parse("2026-09-25T10:00:00Z");
    private final Instant decisionAt = Instant.parse("2026-09-25T10:05:00Z");
    private final UUID adminId = UUID.randomUUID();

    private WikiContribution createNewSample() {
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
                createdAt
        );
    }

    @Test
    @DisplayName("NEW -> REVIEWING transition succeeds and preserves null resolution metadata")
    void shouldTransitionFromNewToReviewing() {
        WikiContribution contribution = createNewSample();
        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.NEW);

        contribution.startReview(adminId, 1L, decisionAt);

        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(contribution.getAssignedToUserId()).isEqualTo(adminId);
        assertThat(contribution.getUpdatedAt()).isEqualTo(decisionAt);
        assertThat(contribution.getResolutionNote()).isNull();
        assertThat(contribution.getResolvedByUserId()).isNull();
        assertThat(contribution.getResolvedAt()).isNull();
        assertThat(contribution.getResolvedArticleContentVersion()).isNull();
    }

    @Test
    @DisplayName("NEW cannot directly transition to RESOLVED or REJECTED")
    void shouldRejectDirectTransitionFromNewToTerminal() {
        WikiContribution contribution = createNewSample();

        assertThatThrownBy(() -> contribution.resolve(adminId, WikiContributionResolutionOutcome.APPLIED, "Thử giải quyết trực tiếp", 2L, decisionAt))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> contribution.reject(adminId, "Thử từ chối trực tiếp", decisionAt))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("startReview transitions NEW to REVIEWING with assignee and snapshot version")
    void shouldStartReviewWithAssignee() {
        WikiContribution contribution = createNewSample();

        contribution.startReview(adminId, 1L, decisionAt);

        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(contribution.getAssignedToUserId()).isEqualTo(adminId);
        assertThat(contribution.getAssignedAt()).isEqualTo(decisionAt);
        assertThat(contribution.getReviewStartedByUserId()).isEqualTo(adminId);
        assertThat(contribution.getReviewStartedAt()).isEqualTo(decisionAt);
        assertThat(contribution.getReviewStartedArticleContentVersion()).isEqualTo(1L);
        assertThat(contribution.getUpdatedAt()).isEqualTo(decisionAt);
    }

    private WikiContribution createLegacyUnassignedReviewing(Instant reviewingAt) {
        return WikiContribution.reconstitute(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                UUID.randomUUID(),
                WikiContributionContextType.GENERAL,
                WikiContributionType.MISSING_INFORMATION,
                "Nội dung đóng góp thông tin còn thiếu",
                null,
                null,
                null,
                null,
                WikiContributionStatus.REVIEWING,
                0L,
                createdAt,
                reviewingAt,
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
    @DisplayName("claim assigns unassigned REVIEWING contribution")
    void shouldClaimUnassignedReviewing() {
        WikiContribution contribution = createLegacyUnassignedReviewing(decisionAt);
        assertThat(contribution.getAssignedToUserId()).isNull();

        Instant claimAt = decisionAt.plusSeconds(30);
        contribution.claim(adminId, claimAt);

        assertThat(contribution.getAssignedToUserId()).isEqualTo(adminId);
        assertThat(contribution.getAssignedAt()).isEqualTo(claimAt);
        assertThat(contribution.getUpdatedAt()).isEqualTo(claimAt);

        // Cannot claim if already assigned
        assertThatThrownBy(() -> contribution.claim(UUID.randomUUID(), claimAt.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("reassign transfers REVIEWING contribution to new admin and preserves review starter metadata")
    void shouldReassignToNewAdmin() {
        // Unassigned REVIEWING cannot be reassigned; claim is required
        WikiContribution unassigned = createLegacyUnassignedReviewing(decisionAt);
        UUID newAdminId = UUID.randomUUID();
        assertThatThrownBy(() -> unassigned.reassign(newAdminId, decisionAt.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Đóng góp chưa có người phụ trách; phải tiếp nhận (claim) trước khi phân công lại.");

        WikiContribution contribution = createNewSample();
        contribution.startReview(adminId, 1L, decisionAt);

        Instant reassignAt = decisionAt.plusSeconds(60);
        contribution.reassign(newAdminId, reassignAt);

        assertThat(contribution.getAssignedToUserId()).isEqualTo(newAdminId);
        assertThat(contribution.getAssignedAt()).isEqualTo(reassignAt);
        assertThat(contribution.getUpdatedAt()).isEqualTo(reassignAt);
        // Preserves original review starter metadata
        assertThat(contribution.getReviewStartedByUserId()).isEqualTo(adminId);
        assertThat(contribution.getReviewStartedAt()).isEqualTo(decisionAt);
        assertThat(contribution.getReviewStartedArticleContentVersion()).isEqualTo(1L);

        // Cannot reassign to the same user
        assertThatThrownBy(() -> contribution.reassign(newAdminId, reassignAt.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("REVIEWING -> RESOLVED transition succeeds with APPLIED outcome when version advances")
    void shouldTransitionFromReviewingToResolvedWithApplied() {
        WikiContribution contribution = createNewSample();
        contribution.startReview(adminId, 1L, decisionAt);

        Instant resolvedAt = decisionAt.plusSeconds(60);
        contribution.resolve(adminId, WikiContributionResolutionOutcome.APPLIED, "Đã cập nhật bài viết theo đóng góp.", 2L, resolvedAt);

        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(contribution.getResolutionOutcome()).isEqualTo(WikiContributionResolutionOutcome.APPLIED);
        assertThat(contribution.getResolutionNote()).isEqualTo("Đã cập nhật bài viết theo đóng góp.");
        assertThat(contribution.getResolvedArticleContentVersion()).isEqualTo(2L);
        assertThat(contribution.getResolvedByUserId()).isEqualTo(adminId);
        assertThat(contribution.getResolvedAt()).isEqualTo(resolvedAt);
    }

    @Test
    @DisplayName("APPLIED outcome fails if resolved content version is not greater than base version")
    void shouldRejectAppliedWhenVersionDoesNotAdvance() {
        WikiContribution contribution = createNewSample();
        contribution.startReview(adminId, 2L, decisionAt);

        Instant resolvedAt = decisionAt.plusSeconds(60);
        // resolved version 2L <= base version 2L must fail
        assertThatThrownBy(() -> contribution.resolve(
                adminId, WikiContributionResolutionOutcome.APPLIED, "Chưa tăng version", 2L, resolvedAt
        )).isInstanceOf(IllegalArgumentException.class);

        // null resolved version must fail for APPLIED
        assertThatThrownBy(() -> contribution.resolve(
                adminId, WikiContributionResolutionOutcome.APPLIED, "Không có version", null, resolvedAt
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("REVIEWING -> RESOLVED transition succeeds with NO_CHANGE_NEEDED and DUPLICATE outcomes")
    void shouldTransitionFromReviewingToResolvedWithOtherOutcomes() {
        WikiContribution contribution1 = createNewSample();
        contribution1.startReview(adminId, 1L, decisionAt);
        Instant resolvedAt = decisionAt.plusSeconds(60);

        contribution1.resolve(adminId, WikiContributionResolutionOutcome.NO_CHANGE_NEEDED, "Bài viết đã chính xác và đầy đủ.", null, resolvedAt);
        assertThat(contribution1.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(contribution1.getResolutionOutcome()).isEqualTo(WikiContributionResolutionOutcome.NO_CHANGE_NEEDED);

        WikiContribution contribution2 = createNewSample();
        contribution2.startReview(adminId, 1L, decisionAt);
        contribution2.resolve(adminId, WikiContributionResolutionOutcome.DUPLICATE, "Đóng góp trùng với đóng góp trước đó.", 1L, resolvedAt);
        assertThat(contribution2.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(contribution2.getResolutionOutcome()).isEqualTo(WikiContributionResolutionOutcome.DUPLICATE);
    }

    @Test
    @DisplayName("Non-assignee cannot resolve or reject contribution")
    void shouldRejectMutationByNonAssignee() {
        WikiContribution contribution = createNewSample();
        contribution.startReview(adminId, 1L, decisionAt);
        UUID otherAdminId = UUID.randomUUID();

        Instant attemptAt = decisionAt.plusSeconds(60);
        assertThatThrownBy(() -> contribution.resolve(
                otherAdminId, WikiContributionResolutionOutcome.NO_CHANGE_NEEDED, "Cố gắng resolve", null, attemptAt
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> contribution.reject(
                otherAdminId, "Cố gắng reject", attemptAt
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("REVIEWING -> REJECTED transition succeeds by assignee")
    void shouldTransitionFromReviewingToRejected() {
        WikiContribution contribution = createNewSample();
        contribution.startReview(adminId, 1L, decisionAt);

        Instant rejectedAt = decisionAt.plusSeconds(60);
        contribution.reject(adminId, "Xem xét xong và quyết định từ chối do thiếu nguồn tin cậy.", rejectedAt);

        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.REJECTED);
        assertThat(contribution.getResolvedByUserId()).isEqualTo(adminId);
        assertThat(contribution.getResolvedAt()).isEqualTo(rejectedAt);
        assertThat(contribution.getResolvedArticleContentVersion()).isNull();
        assertThat(contribution.getResolutionOutcome()).isNull();
    }

    @Test
    @DisplayName("REVIEWING + unassigned requires explicit claim: direct reject or resolve fails")
    void shouldRejectTerminalActionWhenUnassigned() {
        WikiContribution contribution = createLegacyUnassignedReviewing(decisionAt);
        assertThat(contribution.getAssignedToUserId()).isNull();

        // Reject fails when unassigned
        assertThatThrownBy(() -> contribution.reject(adminId, "Từ chối khi chưa claim", decisionAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Chỉ người đang được phân công xử lý mới có quyền từ chối đóng góp.");

        // Resolve fails when unassigned
        assertThatThrownBy(() -> contribution.resolve(adminId, WikiContributionResolutionOutcome.NO_CHANGE_NEEDED, "Duyệt khi chưa claim", null, decisionAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Chỉ người đang được phân công xử lý mới có quyền giải quyết đóng góp.");
    }

    @Test
    @DisplayName("Self transitions and invalid transitions fail closed")
    void shouldRejectInvalidTransitions() {
        WikiContribution contribution = createNewSample();
        contribution.startReview(adminId, 1L, decisionAt);

        // REVIEWING -> REVIEWING (self transition) fails
        assertThatThrownBy(() -> contribution.startReview(adminId, 1L, decisionAt.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class);

        // Terminal state RESOLVED cannot transition
        contribution.resolve(adminId, WikiContributionResolutionOutcome.APPLIED, "Hợp lệ và đầy đủ.", 2L, decisionAt.plusSeconds(20));
        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);

        assertThatThrownBy(() -> contribution.startReview(adminId, 1L, decisionAt.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> contribution.resolve(adminId, WikiContributionResolutionOutcome.APPLIED, "Thử resolve lại", 3L, decisionAt.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> contribution.reject(adminId, "Thử reject", decisionAt.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);

        // Terminal state REJECTED cannot transition
        WikiContribution rejectedContribution = createNewSample();
        rejectedContribution.startReview(adminId, 1L, decisionAt);
        rejectedContribution.reject(adminId, "Từ chối đóng góp này.", decisionAt.plusSeconds(10));

        assertThatThrownBy(() -> rejectedContribution.startReview(adminId, 1L, decisionAt.plusSeconds(20)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rejectedContribution.resolve(adminId, WikiContributionResolutionOutcome.APPLIED, "Duyệt lại", 2L, decisionAt.plusSeconds(20)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rejectedContribution.reject(adminId, "Từ chối lần nữa", decisionAt.plusSeconds(20)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Resolution note length bounds (5..2000 chars) are strictly enforced")
    void shouldEnforceResolutionNoteBounds() {
        WikiContribution contribution = createNewSample();
        contribution.startReview(adminId, 1L, decisionAt);

        // Null or empty
        assertThatThrownBy(() -> contribution.resolve(adminId, WikiContributionResolutionOutcome.APPLIED, null, 2L, decisionAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contribution.resolve(adminId, WikiContributionResolutionOutcome.APPLIED, "   ", 2L, decisionAt))
                .isInstanceOf(IllegalArgumentException.class);

        // Too short (< 5 chars)
        assertThatThrownBy(() -> contribution.resolve(adminId, WikiContributionResolutionOutcome.APPLIED, "1234", 2L, decisionAt))
                .isInstanceOf(IllegalArgumentException.class);

        // Exactly 5 chars succeeds
        contribution.resolve(adminId, WikiContributionResolutionOutcome.APPLIED, "12345", 2L, decisionAt);
        assertThat(contribution.getResolutionNote()).isEqualTo("12345");

        // Too long (> 2000 chars)
        WikiContribution c2 = createNewSample();
        c2.startReview(adminId, 1L, decisionAt);
        String tooLongNote = "a".repeat(2001);
        assertThatThrownBy(() -> c2.reject(adminId, tooLongNote, decisionAt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Decision time cannot be before contribution createdAt")
    void shouldRejectDecisionTimeBeforeCreation() {
        WikiContribution contribution = createNewSample();
        contribution.startReview(adminId, 1L, createdAt);
        Instant beforeCreated = createdAt.minusSeconds(10);

        assertThatThrownBy(() -> contribution.resolve(adminId, WikiContributionResolutionOutcome.APPLIED, "Ghi chú hợp lệ", 2L, beforeCreated))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contribution.reject(adminId, "Ghi chú hợp lệ", beforeCreated))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Reconstituting NEW or REVIEWING with resolution metadata fails closed")
    void shouldFailReconstitutionWithInconsistentStatusMetadata() {
        UUID id = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // NEW cannot have resolution note
        assertThatThrownBy(() -> WikiContribution.reconstitute(
                id, articleId, "CHARACTER", "Tiêu đề", "slug", 1L, userId,
                WikiContributionContextType.GENERAL, WikiContributionType.OTHER, "Nội dung đóng góp hợp lệ từ người dùng",
                null, null, null, null, WikiContributionStatus.NEW, 0L, createdAt, createdAt,
                "Ghi chú sai", adminId, decisionAt, 1L
        )).isInstanceOf(IllegalArgumentException.class);

        // RESOLVED missing resolution note fails
        assertThatThrownBy(() -> WikiContribution.reconstitute(
                id, articleId, "CHARACTER", "Tiêu đề", "slug", 1L, userId,
                WikiContributionContextType.GENERAL, WikiContributionType.OTHER, "Nội dung đóng góp hợp lệ từ người dùng",
                null, null, null, null, WikiContributionStatus.RESOLVED, 0L, createdAt, decisionAt,
                null, adminId, decisionAt, 1L
        )).isInstanceOf(IllegalArgumentException.class);

        // REJECTED having resolvedArticleContentVersion fails
        assertThatThrownBy(() -> WikiContribution.reconstitute(
                id, articleId, "CHARACTER", "Tiêu đề", "slug", 1L, userId,
                WikiContributionContextType.GENERAL, WikiContributionType.OTHER, "Nội dung đóng góp hợp lệ từ người dùng",
                null, null, null, null, WikiContributionStatus.REJECTED, 0L, createdAt, decisionAt,
                "Lý do từ chối", adminId, decisionAt, 2L
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
