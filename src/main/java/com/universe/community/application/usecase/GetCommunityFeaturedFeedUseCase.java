package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort;
import com.universe.community.application.service.CommunityPostFeedAuthorEnricher;
import com.universe.community.contracts.dto.CommunityFeaturedFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRankingCandidateDTO;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.community.domain.exception.CommunityPostValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Use case to retrieve a paginated slice of Community posts for the ALL-TIME FEATURED feed.
 * <p>
 * Evaluates global engagement score (active reactions + active comments/replies) across all live Community posts,
 * sorts globally in memory, slices the requested page window, and bulk-hydrates & enriches only winning posts.
 */
@Service
@Transactional(readOnly = true)
public class GetCommunityFeaturedFeedUseCase {

    public static final int DEFAULT_PAGE = 0;
    public static final int MIN_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 50;
    public static final int CHUNK_SIZE = 500;

    private static final Comparator<ScoredCandidate> RANKING_COMPARATOR = Comparator
            .comparingLong(ScoredCandidate::engagementScore).reversed()
            .thenComparing(ScoredCandidate::createdAt, Comparator.reverseOrder())
            .thenComparing(c -> c.postId().toString(), Comparator.reverseOrder());

    private final CommunityPostQueryPort postQueryPort;
    private final CommunityPostEngagementMetricsPort engagementMetricsPort;
    private final CommunityPostFeedAuthorEnricher authorEnricher;

    public GetCommunityFeaturedFeedUseCase(
            CommunityPostQueryPort postQueryPort,
            CommunityPostEngagementMetricsPort engagementMetricsPort,
            CommunityPostFeedAuthorEnricher authorEnricher
    ) {
        this.postQueryPort = Objects.requireNonNull(postQueryPort, "CommunityPostQueryPort cannot be null.");
        this.engagementMetricsPort = Objects.requireNonNull(engagementMetricsPort, "CommunityPostEngagementMetricsPort cannot be null.");
        this.authorEnricher = Objects.requireNonNull(authorEnricher, "CommunityPostFeedAuthorEnricher cannot be null.");
    }

    public CommunityFeaturedFeedResponseDTO execute(Integer requestedPage, Integer requestedSize) {
        int page = (requestedPage != null) ? requestedPage : DEFAULT_PAGE;
        if (page < MIN_PAGE) {
            throw new CommunityPostValidationException("Page cannot be negative: " + page);
        }

        int size = (requestedSize != null) ? requestedSize : DEFAULT_SIZE;
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new CommunityPostValidationException("Size must be between " + MIN_SIZE + " and " + MAX_SIZE + ".");
        }

        List<CommunityPostRankingCandidateDTO> candidates = postQueryPort.findAllRankingCandidates();
        if (candidates.isEmpty()) {
            return new CommunityFeaturedFeedResponseDTO(List.of(), page, size, 0L, 0, false);
        }

        // Chunked batch fetching of engagement metrics for all candidate IDs
        List<UUID> candidateIds = candidates.stream().map(CommunityPostRankingCandidateDTO::postId).toList();
        Map<UUID, CommunityPostEngagementMetricsPort.PostEngagementMetrics> metricsMap = new HashMap<>(candidateIds.size());

        for (int i = 0; i < candidateIds.size(); i += CHUNK_SIZE) {
            List<UUID> chunk = candidateIds.subList(i, Math.min(i + CHUNK_SIZE, candidateIds.size()));
            Map<UUID, CommunityPostEngagementMetricsPort.PostEngagementMetrics> chunkMetrics =
                    engagementMetricsPort.getEngagementMetricsForPosts(chunk);
            if (chunkMetrics != null) {
                metricsMap.putAll(chunkMetrics);
            }
        }

        // Score candidates
        List<ScoredCandidate> scoredCandidates = new ArrayList<>(candidates.size());
        for (CommunityPostRankingCandidateDTO candidate : candidates) {
            CommunityPostEngagementMetricsPort.PostEngagementMetrics metrics = metricsMap.get(candidate.postId());
            long reactionCount = (metrics != null) ? metrics.reactionCount() : 0L;
            long commentCount = (metrics != null) ? metrics.commentCount() : 0L;
            long engagementScore = reactionCount + commentCount;

            scoredCandidates.add(new ScoredCandidate(
                    candidate.postId(),
                    candidate.createdAt(),
                    reactionCount,
                    commentCount,
                    engagementScore
            ));
        }

        scoredCandidates.sort(RANKING_COMPARATOR);

        long totalItems = scoredCandidates.size();
        int totalPages = (totalItems == 0) ? 0 : (int) ((totalItems + size - 1) / size);
        long fromOffset = (long) page * size;

        if (fromOffset >= totalItems) {
            return new CommunityFeaturedFeedResponseDTO(List.of(), page, size, totalItems, totalPages, false);
        }

        int fromIndex = (int) fromOffset;
        int toIndex = (int) Math.min(fromOffset + size, totalItems);

        List<ScoredCandidate> winningCandidates = scoredCandidates.subList(fromIndex, toIndex);
        boolean hasNext = toIndex < totalItems;

        // Bulk hydrate only the winning page candidates
        List<UUID> winningIds = winningCandidates.stream().map(ScoredCandidate::postId).toList();
        List<CommunityPostPublicDTO> hydratedPosts = postQueryPort.findPublicPostsByIds(winningIds);
        Map<UUID, CommunityPostPublicDTO> postMap = hydratedPosts.stream()
                .collect(Collectors.toMap(CommunityPostPublicDTO::id, p -> p, (p1, p2) -> p1));

        // Assemble DTOs preserving exact computed ranking order
        List<CommunityPostFeedItemDTO> feedItems = new ArrayList<>(winningCandidates.size());
        for (ScoredCandidate candidate : winningCandidates) {
            CommunityPostPublicDTO post = postMap.get(candidate.postId());
            if (post != null) {
                feedItems.add(new CommunityPostFeedItemDTO(
                        post.id(),
                        post.authorUserId(),
                        null,
                        null,
                        null,
                        post.caption(),
                        post.imageMediaAssetId(),
                        post.imageUrl(),
                        post.contentVersion(),
                        candidate.reactionCount(),
                        candidate.commentCount(),
                        candidate.engagementScore(),
                        post.createdAt(),
                        post.updatedAt()
                ));
            }
        }

        List<CommunityPostFeedItemDTO> enrichedItems = authorEnricher.enrich(feedItems);

        return new CommunityFeaturedFeedResponseDTO(enrichedItems, page, size, totalItems, totalPages, hasNext);
    }

    private record ScoredCandidate(
            UUID postId,
            Instant createdAt,
            long reactionCount,
            long commentCount,
            long engagementScore
    ) {
    }
}
