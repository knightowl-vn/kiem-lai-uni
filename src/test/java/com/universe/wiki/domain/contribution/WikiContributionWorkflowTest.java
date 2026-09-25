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

        contribution.markReviewing(decisionAt);

        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(contribution.getUpdatedAt()).isEqualTo(decisionAt);
        assertThat(contribution.getResolutionNote()).isNull();
        assertThat(contribution.getResolvedByUserId()).isNull();
        assertThat(contribution.getResolvedAt()).isNull();
        assertThat(contribution.getResolvedArticleContentVersion()).isNull();
    }

    @Test
    @DisplayName("NEW -> RESOLVED transition succeeds with full decision metadata")
    void shouldTransitionFromNewToResolved() {
        WikiContribution contribution = createNewSample();

        contribution.resolve(adminId, "Đã cập nhật nội dung bài viết theo đóng góp.", 2L, decisionAt);

        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(contribution.getResolutionNote()).isEqualTo("Đã cập nhật nội dung bài viết theo đóng góp.");
        assertThat(contribution.getResolvedByUserId()).isEqualTo(adminId);
        assertThat(contribution.getResolvedAt()).isEqualTo(decisionAt);
        assertThat(contribution.getResolvedArticleContentVersion()).isEqualTo(2L);
        assertThat(contribution.getUpdatedAt()).isEqualTo(decisionAt);
    }

    @Test
    @DisplayName("NEW -> RESOLVED transition succeeds with null article version when article is unavailable")
    void shouldTransitionFromNewToResolvedWithNullArticleVersion() {
        WikiContribution contribution = createNewSample();

        contribution.resolve(adminId, "Ghi nhận đóng góp dù bài viết hiện không tồn tại.", null, decisionAt);

        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(contribution.getResolvedArticleContentVersion()).isNull();
    }

    @Test
    @DisplayName("NEW -> REJECTED transition succeeds and forces resolvedArticleContentVersion to null")
    void shouldTransitionFromNewToRejected() {
        WikiContribution contribution = createNewSample();

        contribution.reject(adminId, "Thông tin đóng góp không phù hợp với nguyên tác.", decisionAt);

        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.REJECTED);
        assertThat(contribution.getResolutionNote()).isEqualTo("Thông tin đóng góp không phù hợp với nguyên tác.");
        assertThat(contribution.getResolvedByUserId()).isEqualTo(adminId);
        assertThat(contribution.getResolvedAt()).isEqualTo(decisionAt);
        assertThat(contribution.getResolvedArticleContentVersion()).isNull();
    }

    @Test
    @DisplayName("REVIEWING -> RESOLVED transition succeeds")
    void shouldTransitionFromReviewingToResolved() {
        WikiContribution contribution = createNewSample();
        contribution.markReviewing(decisionAt);

        Instant resolvedAt = decisionAt.plusSeconds(60);
        contribution.resolve(adminId, "Xem xét xong và quyết định duyệt đóng góp.", 3L, resolvedAt);

        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(contribution.getResolutionNote()).isEqualTo("Xem xét xong và quyết định duyệt đóng góp.");
        assertThat(contribution.getResolvedArticleContentVersion()).isEqualTo(3L);
        assertThat(contribution.getResolvedAt()).isEqualTo(resolvedAt);
    }

    @Test
    @DisplayName("REVIEWING -> REJECTED transition succeeds")
    void shouldTransitionFromReviewingToRejected() {
        WikiContribution contribution = createNewSample();
        contribution.markReviewing(decisionAt);

        Instant rejectedAt = decisionAt.plusSeconds(60);
        contribution.reject(adminId, "Xem xét xong và quyết định từ chối do thiếu nguồn tin cậy.", rejectedAt);

        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.REJECTED);
        assertThat(contribution.getResolvedArticleContentVersion()).isNull();
    }

    @Test
    @DisplayName("Self transitions and invalid transitions fail closed")
    void shouldRejectInvalidTransitions() {
        WikiContribution contribution = createNewSample();
        contribution.markReviewing(decisionAt);

        // REVIEWING -> REVIEWING (self transition) fails
        assertThatThrownBy(() -> contribution.markReviewing(decisionAt.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class);

        // Terminal state RESOLVED cannot transition
        contribution.resolve(adminId, "Hợp lệ và đầy đủ.", 1L, decisionAt.plusSeconds(20));
        assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);

        assertThatThrownBy(() -> contribution.markReviewing(decisionAt.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> contribution.resolve(adminId, "Thử resolve lại", 1L, decisionAt.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> contribution.reject(adminId, "Thử reject", decisionAt.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);

        // Terminal state REJECTED cannot transition
        WikiContribution rejectedContribution = createNewSample();
        rejectedContribution.reject(adminId, "Từ chối đóng góp này.", decisionAt);

        assertThatThrownBy(() -> rejectedContribution.markReviewing(decisionAt.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rejectedContribution.resolve(adminId, "Duyệt lại", 1L, decisionAt.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rejectedContribution.reject(adminId, "Từ chối lần nữa", decisionAt.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Resolution note length bounds (5..2000 chars) are strictly enforced")
    void shouldEnforceResolutionNoteBounds() {
        WikiContribution contribution = createNewSample();

        // Null or empty
        assertThatThrownBy(() -> contribution.resolve(adminId, null, 1L, decisionAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contribution.resolve(adminId, "   ", 1L, decisionAt))
                .isInstanceOf(IllegalArgumentException.class);

        // Too short (< 5 chars)
        assertThatThrownBy(() -> contribution.resolve(adminId, "1234", 1L, decisionAt))
                .isInstanceOf(IllegalArgumentException.class);

        // Exactly 5 chars succeeds
        contribution.resolve(adminId, "12345", 1L, decisionAt);
        assertThat(contribution.getResolutionNote()).isEqualTo("12345");

        // Too long (> 2000 chars)
        WikiContribution c2 = createNewSample();
        String tooLongNote = "a".repeat(2001);
        assertThatThrownBy(() -> c2.reject(adminId, tooLongNote, decisionAt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Decision time cannot be before contribution createdAt")
    void shouldRejectDecisionTimeBeforeCreation() {
        WikiContribution contribution = createNewSample();
        Instant beforeCreated = createdAt.minusSeconds(10);

        assertThatThrownBy(() -> contribution.resolve(adminId, "Ghi chú hợp lệ", 1L, beforeCreated))
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
