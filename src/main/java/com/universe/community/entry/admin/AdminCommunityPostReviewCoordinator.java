package com.universe.community.entry.admin;

import com.universe.community.application.port.out.CommunityPostModerationEventRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.moderation.CommunityPostModerationEvent;
import com.universe.community.entry.admin.dto.AdminCommunityPostHiddenItemDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostHiddenPageDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostModerationEventDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostPendingItemDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostPendingPageDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostUserDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-context query composition coordinator for Admin Pending Review and Hidden Posts views.
 */
@Service
public class AdminCommunityPostReviewCoordinator {

    private final CommunityPostRepositoryPort postRepositoryPort;
    private final CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort;
    private final UserIdentityContract userIdentityContract;

    public AdminCommunityPostReviewCoordinator(
            CommunityPostRepositoryPort postRepositoryPort,
            CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort,
            UserIdentityContract userIdentityContract
    ) {
        this.postRepositoryPort = Objects.requireNonNull(postRepositoryPort, "postRepositoryPort cannot be null.");
        this.moderationEventRepositoryPort = Objects.requireNonNull(moderationEventRepositoryPort, "moderationEventRepositoryPort cannot be null.");
        this.userIdentityContract = Objects.requireNonNull(userIdentityContract, "userIdentityContract cannot be null.");
    }

    public AdminCommunityPostPendingPageDTO getPendingQueue(int page, int size) {
        CommunityPostRepositoryPort.CommunityPostPage postPage =
                postRepositoryPort.findPendingReviewPosts(page, size);

        if (postPage == null || postPage.items().isEmpty()) {
            int p = postPage != null ? postPage.page() : page;
            int s = postPage != null ? postPage.size() : size;
            long total = postPage != null ? postPage.totalElements() : 0L;
            return new AdminCommunityPostPendingPageDTO(List.of(), p, s, total);
        }

        List<CommunityPost> posts = postPage.items();

        // Collect author user IDs
        Set<UUID> authorUserIds = new HashSet<>();
        for (CommunityPost post : posts) {
            if (post.getAuthorUserId() != null) {
                authorUserIds.add(post.getAuthorUserId());
            }
        }

        Map<UUID, UserPublicProfileDTO> profileMap = authorUserIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(userIdentityContract.findPublicProfilesByIds(authorUserIds), Map.of());

        List<AdminCommunityPostPendingItemDTO> items = new ArrayList<>(posts.size());
        for (CommunityPost post : posts) {
            AdminCommunityPostUserDTO author = composeUser(post.getAuthorUserId(), profileMap);
            items.add(new AdminCommunityPostPendingItemDTO(
                    post.getId(),
                    author,
                    post.getCaption(),
                    post.getPendingCaption(),
                    post.getImageMediaAssetId(),
                    post.getCreatedAt(),
                    post.getReviewRequestedAt(),
                    post.getPublishedAt(),
                    post.getContentVersion()
            ));
        }

        return new AdminCommunityPostPendingPageDTO(
                items,
                postPage.page(),
                postPage.size(),
                postPage.totalElements()
        );
    }

    public AdminCommunityPostHiddenPageDTO getHiddenPosts(int page, int size) {
        CommunityPostRepositoryPort.CommunityPostPage postPage =
                postRepositoryPort.findHiddenPosts(page, size);

        if (postPage == null || postPage.items().isEmpty()) {
            int p = postPage != null ? postPage.page() : page;
            int s = postPage != null ? postPage.size() : size;
            long total = postPage != null ? postPage.totalElements() : 0L;
            return new AdminCommunityPostHiddenPageDTO(List.of(), p, s, total);
        }

        List<CommunityPost> posts = postPage.items();

        // Collect events and user IDs
        Map<UUID, List<CommunityPostModerationEvent>> postEventsMap = new HashMap<>();
        Set<UUID> allUserIds = new HashSet<>();

        for (CommunityPost post : posts) {
            if (post.getAuthorUserId() != null) {
                allUserIds.add(post.getAuthorUserId());
            }
            List<CommunityPostModerationEvent> events = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(post.getId());
            postEventsMap.put(post.getId(), events);
            for (CommunityPostModerationEvent ev : events) {
                if (ev.moderatorUserId() != null) {
                    allUserIds.add(ev.moderatorUserId());
                }
            }
        }

        Map<UUID, UserPublicProfileDTO> profileMap = allUserIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(userIdentityContract.findPublicProfilesByIds(allUserIds), Map.of());

        List<AdminCommunityPostHiddenItemDTO> items = new ArrayList<>(posts.size());
        for (CommunityPost post : posts) {
            AdminCommunityPostUserDTO author = composeUser(post.getAuthorUserId(), profileMap);
            List<CommunityPostModerationEvent> events = postEventsMap.getOrDefault(post.getId(), List.of());
            List<AdminCommunityPostModerationEventDTO> history = events.stream()
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

            items.add(new AdminCommunityPostHiddenItemDTO(
                    post.getId(),
                    author,
                    post.getCaption(),
                    post.getImageMediaAssetId(),
                    post.getCreatedAt(),
                    post.getUpdatedAt(),
                    post.getContentVersion(),
                    history
            ));
        }

        return new AdminCommunityPostHiddenPageDTO(
                items,
                postPage.page(),
                postPage.size(),
                postPage.totalElements()
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
