package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.application.port.out.CommunityAuthorProfileSummary;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.contracts.dto.AuthorPendingCommunityPostDTO;
import com.universe.community.domain.CommunityPost;
import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Use case to retrieve pending review posts authored strictly by the authenticated actor.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Only returns posts with status {@code PENDING_REVIEW} where {@code authorUserId == actorUserId}.</li>
 *   <li>Ordered by {@code reviewRequestedAt DESC, id DESC}.</li>
 *   <li>Enriches author details via {@link CommunityAuthorProfilePort}.</li>
 *   <li>Null or invalid actor returns empty list without error.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class GetAuthorPendingCommunityPostsUseCase {

    private final CommunityPostRepositoryPort postRepositoryPort;
    private final CommunityAuthorProfilePort authorProfilePort;

    public GetAuthorPendingCommunityPostsUseCase(
            CommunityPostRepositoryPort postRepositoryPort,
            CommunityAuthorProfilePort authorProfilePort
    ) {
        this.postRepositoryPort = Objects.requireNonNull(postRepositoryPort, "CommunityPostRepositoryPort cannot be null.");
        this.authorProfilePort = Objects.requireNonNull(authorProfilePort, "CommunityAuthorProfilePort cannot be null.");
    }

    public List<AuthorPendingCommunityPostDTO> execute(UUID actorUserId) {
        if (actorUserId == null) {
            return List.of();
        }

        List<CommunityPost> posts = postRepositoryPort.findPendingReviewPostsByAuthor(actorUserId);
        if (posts.isEmpty()) {
            return List.of();
        }

        Map<UUID, CommunityAuthorProfileSummary> profileMap = authorProfilePort.findAuthorProfilesByIds(Set.of(actorUserId));
        CommunityAuthorProfileSummary summary = (profileMap != null) ? profileMap.get(actorUserId) : null;

        String displayName = (summary != null && summary.displayName() != null && !summary.displayName().isBlank())
                ? summary.displayName()
                : "Người dùng";
        String publicHandle = (summary != null) ? summary.publicHandle() : null;
        String avatarUrl = (summary != null) ? summary.avatarUrl() : null;

        List<AuthorPendingCommunityPostDTO> result = new ArrayList<>(posts.size());
        for (CommunityPost post : posts) {
            String imageUrl = (post.getImageMediaAssetId() != null)
                    ? MediaDeliveryUrlSupport.contentUrl(post.getImageMediaAssetId())
                    : null;

            result.add(new AuthorPendingCommunityPostDTO(
                    post.getId(),
                    post.getAuthorUserId(),
                    displayName,
                    publicHandle,
                    avatarUrl,
                    post.getCaption(),
                    post.getPendingCaption(),
                    post.getImageMediaAssetId(),
                    imageUrl,
                    post.getCreatedAt(),
                    post.getReviewRequestedAt(),
                    post.getPublishedAt(),
                    post.getStatus().name(),
                    post.getContentVersion()
            ));
        }

        return result;
    }
}
