package com.universe.community.entry.admin;

import com.universe.community.application.port.out.CommunityPostModerationEventRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.moderation.CommunityPostModerationAction;
import com.universe.community.domain.moderation.CommunityPostModerationEvent;
import com.universe.community.entry.admin.dto.AdminCommunityPostHiddenPageDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostPendingPageDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostReportDetailDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostReportPageDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Admin Community Post Coordinators Test")
class AdminCommunityPostCoordinatorsTest {

    @Mock
    private InteractionReportRepositoryPort reportRepositoryPort;

    @Mock
    private CommunityPostRepositoryPort postRepositoryPort;

    @Mock
    private CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort;

    @Mock
    private UserIdentityContract userIdentityContract;

    private AdminCommunityPostReportCoordinator reportCoordinator;
    private AdminCommunityPostReviewCoordinator reviewCoordinator;

    private final Instant now = Instant.parse("2026-10-03T12:00:00Z");
    private final UUID reporterId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final UUID moderatorId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reportCoordinator = new AdminCommunityPostReportCoordinator(
                reportRepositoryPort, postRepositoryPort, moderationEventRepositoryPort, userIdentityContract
        );
        reviewCoordinator = new AdminCommunityPostReviewCoordinator(
                postRepositoryPort, moderationEventRepositoryPort, userIdentityContract
        );
    }

    @Test
    @DisplayName("AdminCommunityPostReportCoordinator: retrieves and enriches report queue with zero N+1")
    void shouldGetReportQueueWithBatchEnrichment() {
        InteractionReport report = InteractionReport.createPending(
                reportId, ReportTargetType.COMMUNITY_POST, postId, reporterId,
                ReportReason.SPAM, "Spam description", "Snapshot", null, now
        );
        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Current caption", null,
                CommunityPostStatus.PUBLISHED, 1, now, now
        );

        when(reportRepositoryPort.findCommunityPostReports(eq(ReportStatus.PENDING), any(), anyBoolean(), eq(0), eq(20)))
                .thenReturn(new InteractionReportRepositoryPort.InteractionReportPage(List.of(report), 0, 20, 1));
        when(postRepositoryPort.findByIdIn(Set.of(postId))).thenReturn(List.of(post));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(reporterId, authorId))).thenReturn(Map.of(
                reporterId, new UserPublicProfileDTO(reporterId, "Reporter", "/reporter.jpg", "rep_user"),
                authorId, new UserPublicProfileDTO(authorId, "Author", "/author.jpg", "auth_user")
        ));

        AdminCommunityPostReportPageDTO page = reportCoordinator.getReportQueue(ReportStatus.PENDING, null, false, 0, 20);

        assertThat(page.items()).hasSize(1);
        var item = page.items().get(0);
        assertThat(item.reportId()).isEqualTo(reportId);
        assertThat(item.reporter().displayName()).isEqualTo("Reporter");
        assertThat(item.postExists()).isTrue();
        assertThat(item.postStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(item.postAuthor().displayName()).isEqualTo("Author");
    }

    @Test
    @DisplayName("AdminCommunityPostReportCoordinator: retrieves report detail with moderation history")
    void shouldGetReportDetail() {
        InteractionReport report = InteractionReport.createPending(
                reportId, ReportTargetType.COMMUNITY_POST, postId, reporterId,
                ReportReason.HARASSMENT, "Harassment description", "Snapshot", null, now
        );
        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Current caption", null,
                CommunityPostStatus.HIDDEN, 2, now.minusSeconds(100), now
        );
        CommunityPostModerationEvent event = new CommunityPostModerationEvent(
                UUID.randomUUID(), postId, CommunityPostModerationAction.HIDE,
                CommunityPostStatus.PUBLISHED, CommunityPostStatus.HIDDEN,
                moderatorId, "Policy violation", now
        );

        when(reportRepositoryPort.findById(reportId)).thenReturn(Optional.of(report));
        when(postRepositoryPort.findById(postId)).thenReturn(Optional.of(post));
        when(moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId)).thenReturn(List.of(event));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(reporterId, authorId, moderatorId))).thenReturn(Map.of(
                reporterId, new UserPublicProfileDTO(reporterId, "Reporter", null, "rep"),
                authorId, new UserPublicProfileDTO(authorId, "Author", null, "auth"),
                moderatorId, new UserPublicProfileDTO(moderatorId, "Mod", null, "mod")
        ));

        AdminCommunityPostReportDetailDTO detail = reportCoordinator.getReportDetail(reportId);

        assertThat(detail.reportId()).isEqualTo(reportId);
        assertThat(detail.postExists()).isTrue();
        assertThat(detail.postStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
        assertThat(detail.moderationHistory()).hasSize(1);
        assertThat(detail.moderationHistory().get(0).moderator().displayName()).isEqualTo("Mod");
    }

    @Test
    @DisplayName("AdminCommunityPostReviewCoordinator: retrieves pending review queue")
    void shouldGetPendingQueue() {
        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Pending caption", null,
                CommunityPostStatus.PENDING_REVIEW, 1, now, now
        );

        when(postRepositoryPort.findPendingReviewPosts(0, 20))
                .thenReturn(new CommunityPostRepositoryPort.CommunityPostPage(List.of(post), 0, 20, 1));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(authorId))).thenReturn(Map.of(
                authorId, new UserPublicProfileDTO(authorId, "Author User", "/avatar.png", "author_handle")
        ));

        AdminCommunityPostPendingPageDTO pendingPage = reviewCoordinator.getPendingQueue(0, 20);

        assertThat(pendingPage.items()).hasSize(1);
        assertThat(pendingPage.items().get(0).author().displayName()).isEqualTo("Author User");
        assertThat(pendingPage.items().get(0).caption()).isEqualTo("Pending caption");
    }

    @Test
    @DisplayName("AdminCommunityPostReviewCoordinator: retrieves hidden posts with moderation history")
    void shouldGetHiddenPosts() {
        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Hidden caption", null,
                CommunityPostStatus.HIDDEN, 1, now, now
        );
        CommunityPostModerationEvent event = new CommunityPostModerationEvent(
                UUID.randomUUID(), postId, CommunityPostModerationAction.HIDE,
                CommunityPostStatus.PUBLISHED, CommunityPostStatus.HIDDEN,
                moderatorId, "Toxic content", now
        );

        when(postRepositoryPort.findHiddenPosts(0, 20))
                .thenReturn(new CommunityPostRepositoryPort.CommunityPostPage(List.of(post), 0, 20, 1));
        when(moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId)).thenReturn(List.of(event));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(authorId, moderatorId))).thenReturn(Map.of(
                authorId, new UserPublicProfileDTO(authorId, "Author User", null, "author_handle"),
                moderatorId, new UserPublicProfileDTO(moderatorId, "Admin Mod", null, "mod_handle")
        ));

        AdminCommunityPostHiddenPageDTO hiddenPage = reviewCoordinator.getHiddenPosts(0, 20);

        assertThat(hiddenPage.items()).hasSize(1);
        assertThat(hiddenPage.items().get(0).author().displayName()).isEqualTo("Author User");
        assertThat(hiddenPage.items().get(0).moderationHistory()).hasSize(1);
        assertThat(hiddenPage.items().get(0).moderationHistory().get(0).moderator().displayName()).isEqualTo("Admin Mod");
    }
}
