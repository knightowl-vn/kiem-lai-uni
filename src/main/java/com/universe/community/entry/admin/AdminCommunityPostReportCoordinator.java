package com.universe.community.entry.admin;

import com.universe.community.application.port.out.CommunityPostModerationEventRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.moderation.CommunityPostModerationEvent;
import com.universe.community.entry.admin.dto.AdminCommunityPostModerationEventDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostReportDetailDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostReportItemDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostReportPageDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostUserDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cross-context query composition coordinator for the Admin Community Post Report Queue and Detail views.
 *
 * <p>Enforces clean architecture and N+1 prevention:
 * <ul>
 *   <li>Lives in entry boundary ({@code com.universe.community.entry.admin});</li>
 *   <li>Batches all user identity lookups in a single call to {@link UserIdentityContract};</li>
 *   <li>Batches all post lookups in a single call to {@link CommunityPostRepositoryPort};</li>
 *   <li>Resilient to deleted or missing posts/users;</li>
 *   <li>Preserves original report queue ordering and pagination invariants.</li>
 * </ul>
 */
@Service
public class AdminCommunityPostReportCoordinator {

    private final InteractionReportRepositoryPort reportRepositoryPort;
    private final CommunityPostRepositoryPort postRepositoryPort;
    private final CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort;
    private final UserIdentityContract userIdentityContract;

    public AdminCommunityPostReportCoordinator(
            InteractionReportRepositoryPort reportRepositoryPort,
            CommunityPostRepositoryPort postRepositoryPort,
            CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort,
            UserIdentityContract userIdentityContract
    ) {
        this.reportRepositoryPort = Objects.requireNonNull(reportRepositoryPort, "reportRepositoryPort cannot be null.");
        this.postRepositoryPort = Objects.requireNonNull(postRepositoryPort, "postRepositoryPort cannot be null.");
        this.moderationEventRepositoryPort = Objects.requireNonNull(moderationEventRepositoryPort, "moderationEventRepositoryPort cannot be null.");
        this.userIdentityContract = Objects.requireNonNull(userIdentityContract, "userIdentityContract cannot be null.");
    }

    public AdminCommunityPostReportPageDTO getReportQueue(
            ReportStatus status,
            ReportReason reason,
            boolean oldestFirst,
            int page,
            int size
    ) {
        InteractionReportRepositoryPort.InteractionReportPage reportPage =
                reportRepositoryPort.findCommunityPostReports(status, reason, oldestFirst, page, size);

        if (reportPage == null || reportPage.items().isEmpty()) {
            int p = reportPage != null ? reportPage.page() : page;
            int s = reportPage != null ? reportPage.size() : size;
            long total = reportPage != null ? reportPage.totalElements() : 0L;
            return new AdminCommunityPostReportPageDTO(List.of(), p, s, total);
        }

        List<InteractionReport> reports = reportPage.items();

        // 1. Batch lookup target Community Posts
        Set<UUID> postIds = reports.stream()
                .map(InteractionReport::getTargetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<CommunityPost> posts = postRepositoryPort.findByIdIn(postIds);
        Map<UUID, CommunityPost> postMap = posts.stream()
                .collect(Collectors.toMap(CommunityPost::getId, p -> p));

        // 2. Batch lookup user profiles (reporters, resolvers, post authors)
        Set<UUID> userIds = new HashSet<>();
        for (InteractionReport report : reports) {
            if (report.getReporterUserId() != null) userIds.add(report.getReporterUserId());
            if (report.getResolvedByUserId() != null) userIds.add(report.getResolvedByUserId());
            CommunityPost post = postMap.get(report.getTargetId());
            if (post != null && post.getAuthorUserId() != null) {
                userIds.add(post.getAuthorUserId());
            }
        }

        Map<UUID, UserPublicProfileDTO> profileMap = userIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(userIdentityContract.findPublicProfilesByIds(userIds), Map.of());

        // 3. Compose items preserving report ordering
        List<AdminCommunityPostReportItemDTO> items = new ArrayList<>(reports.size());
        for (InteractionReport report : reports) {
            AdminCommunityPostUserDTO reporter = composeUser(report.getReporterUserId(), profileMap);
            AdminCommunityPostUserDTO resolver = report.getResolvedByUserId() != null
                    ? composeUser(report.getResolvedByUserId(), profileMap)
                    : null;

            CommunityPost post = postMap.get(report.getTargetId());
            boolean postExists = post != null;
            AdminCommunityPostUserDTO author = post != null ? composeUser(post.getAuthorUserId(), profileMap) : null;

            items.add(new AdminCommunityPostReportItemDTO(
                    report.getId(),
                    reporter,
                    report.getReason(),
                    report.getDescription(),
                    report.getReportedContentSnapshot(),
                    report.getEvidenceMediaAssetId(),
                    report.getCreatedAt(),
                    report.getStatus(),
                    report.getTargetId(),
                    postExists,
                    post != null ? post.getStatus() : null,
                    post != null ? post.getCaption() : null,
                    post != null ? post.getImageMediaAssetId() : null,
                    author,
                    report.getModerationAction(),
                    resolver,
                    report.getResolvedAt()
            ));
        }

        return new AdminCommunityPostReportPageDTO(
                items,
                reportPage.page(),
                reportPage.size(),
                reportPage.totalElements()
        );
    }

    public AdminCommunityPostReportDetailDTO getReportDetail(UUID reportId) {
        Objects.requireNonNull(reportId, "reportId cannot be null.");

        InteractionReport report = reportRepositoryPort.findById(reportId)
                .orElseThrow(() -> new InteractionReportNotFoundException(reportId));

        Optional<CommunityPost> postOpt = postRepositoryPort.findById(report.getTargetId());
        List<CommunityPostModerationEvent> rawEvents = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(report.getTargetId());

        // Collect all user IDs for single batch profile query
        Set<UUID> userIds = new HashSet<>();
        if (report.getReporterUserId() != null) userIds.add(report.getReporterUserId());
        if (report.getResolvedByUserId() != null) userIds.add(report.getResolvedByUserId());
        postOpt.ifPresent(p -> userIds.add(p.getAuthorUserId()));
        for (CommunityPostModerationEvent ev : rawEvents) {
            if (ev.moderatorUserId() != null) userIds.add(ev.moderatorUserId());
        }

        Map<UUID, UserPublicProfileDTO> profileMap = userIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(userIdentityContract.findPublicProfilesByIds(userIds), Map.of());

        AdminCommunityPostUserDTO reporter = composeUser(report.getReporterUserId(), profileMap);
        AdminCommunityPostUserDTO resolver = report.getResolvedByUserId() != null
                ? composeUser(report.getResolvedByUserId(), profileMap)
                : null;

        boolean postExists = postOpt.isPresent();
        CommunityPost post = postOpt.orElse(null);
        AdminCommunityPostUserDTO author = post != null ? composeUser(post.getAuthorUserId(), profileMap) : null;

        List<AdminCommunityPostModerationEventDTO> moderationHistory = rawEvents.stream()
                .map(ev -> new AdminCommunityPostModerationEventDTO(
                        ev.id(),
                        ev.postId(),
                        ev.action(),
                        ev.fromStatus(),
                        ev.toStatus(),
                        composeUser(ev.moderatorUserId(), profileMap),
                        ev.reason(),
                        ev.createdAt()
                ))
                .toList();

        return new AdminCommunityPostReportDetailDTO(
                report.getId(),
                reporter,
                report.getReason(),
                report.getDescription(),
                report.getReportedContentSnapshot(),
                report.getEvidenceMediaAssetId(),
                report.getCreatedAt(),
                report.getStatus(),
                report.getTargetId(),
                postExists,
                post != null ? post.getStatus() : null,
                post != null ? post.getCaption() : null,
                post != null ? post.getImageMediaAssetId() : null,
                author,
                post != null ? post.getCreatedAt() : null,
                post != null ? post.getUpdatedAt() : null,
                post != null ? post.getContentVersion() : 0,
                report.getModerationAction(),
                resolver,
                report.getResolvedAt(),
                moderationHistory
        );
    }

    private AdminCommunityPostUserDTO composeUser(UUID userId, Map<UUID, UserPublicProfileDTO> profileMap) {
        if (userId == null) {
            return null;
        }
        UserPublicProfileDTO profile = profileMap.get(userId);
        if (profile != null) {
            return AdminCommunityPostUserDTO.resolved(
                    userId,
                    profile.displayName(),
                    profile.avatarUrl(),
                    profile.publicHandle()
            );
        }
        return AdminCommunityPostUserDTO.unresolved(userId);
    }
}
