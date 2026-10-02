package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort;
import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort.PostEngagementMetrics;
import com.universe.community.application.service.CommunityPostFeedAuthorEnricher;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application use case to retrieve a single public Community post enriched with
 * author presentation metadata, engagement counts, and viewer reaction state.
 */
@Service
@Transactional(readOnly = true)
public class GetCommunityPostDetailUseCase {

    private final CommunityPostQueryPort postQueryPort;
    private final CommunityPostEngagementMetricsPort engagementMetricsPort;
    private final CommunityPostFeedAuthorEnricher authorEnricher;

    public GetCommunityPostDetailUseCase(
            CommunityPostQueryPort postQueryPort,
            CommunityPostEngagementMetricsPort engagementMetricsPort,
            CommunityPostFeedAuthorEnricher authorEnricher
    ) {
        this.postQueryPort = Objects.requireNonNull(postQueryPort, "CommunityPostQueryPort cannot be null.");
        this.engagementMetricsPort = Objects.requireNonNull(engagementMetricsPort, "CommunityPostEngagementMetricsPort cannot be null.");
        this.authorEnricher = Objects.requireNonNull(authorEnricher, "CommunityPostFeedAuthorEnricher cannot be null.");
    }

    public Optional<CommunityPostFeedItemDTO> execute(UUID postId) {
        return execute(postId, null);
    }

    public Optional<CommunityPostFeedItemDTO> execute(UUID postId, UUID viewerUserId) {
        if (postId == null) {
            return Optional.empty();
        }

        Optional<CommunityPostPublicDTO> postOpt = postQueryPort.findPublicPostById(postId);
        if (postOpt.isEmpty()) {
            return Optional.empty();
        }

        CommunityPostPublicDTO post = postOpt.get();

        Map<UUID, PostEngagementMetrics> metricsMap = (viewerUserId != null)
                ? engagementMetricsPort.getEngagementMetricsForPosts(List.of(postId), viewerUserId)
                : engagementMetricsPort.getEngagementMetricsForPosts(List.of(postId));

        PostEngagementMetrics metrics = (metricsMap != null)
                ? metricsMap.getOrDefault(postId, new PostEngagementMetrics(0L, 0L))
                : new PostEngagementMetrics(0L, 0L);

        long reactionCount = metrics.reactionCount();
        long commentCount = metrics.commentCount();
        long engagementScore = reactionCount + commentCount;

        CommunityPostFeedItemDTO rawItem = new CommunityPostFeedItemDTO(
                post.id(),
                post.authorUserId(),
                null,
                null,
                null,
                post.caption(),
                post.imageMediaAssetId(),
                post.imageUrl(),
                post.contentVersion(),
                reactionCount,
                commentCount,
                engagementScore,
                post.createdAt(),
                post.updatedAt(),
                metrics.currentUserReaction()
        );

        List<CommunityPostFeedItemDTO> enrichedList = authorEnricher.enrich(List.of(rawItem));
        if (enrichedList.isEmpty()) {
            return Optional.of(rawItem);
        }

        return Optional.of(enrichedList.get(0));
    }
}
