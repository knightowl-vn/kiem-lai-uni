package com.universe.community.application.usecase;

import com.universe.community.application.cursor.CommunityPostKeysetCursor;
import com.universe.community.application.cursor.CommunityPostKeysetCursorCodec;
import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort;
import com.universe.community.application.service.CommunityPostFeedAuthorEnricher;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.community.domain.exception.CommunityPostValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case to retrieve a paginated slice of Community posts authored by a specific user using keyset pagination with author enrichment.
 */
@Service
@Transactional(readOnly = true)
public class GetCommunityAuthorPostsUseCase {

    public static final int DEFAULT_SIZE = 20;
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 50;

    private final CommunityPostQueryPort postQueryPort;
    private final CommunityPostEngagementMetricsPort engagementMetricsPort;
    private final CommunityPostKeysetCursorCodec cursorCodec;
    private final CommunityPostFeedAuthorEnricher authorEnricher;

    public GetCommunityAuthorPostsUseCase(
            CommunityPostQueryPort postQueryPort,
            CommunityPostEngagementMetricsPort engagementMetricsPort,
            CommunityPostKeysetCursorCodec cursorCodec,
            CommunityPostFeedAuthorEnricher authorEnricher
    ) {
        this.postQueryPort = Objects.requireNonNull(postQueryPort, "CommunityPostQueryPort cannot be null.");
        this.engagementMetricsPort = Objects.requireNonNull(engagementMetricsPort, "CommunityPostEngagementMetricsPort cannot be null.");
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "CommunityPostKeysetCursorCodec cannot be null.");
        this.authorEnricher = Objects.requireNonNull(authorEnricher, "CommunityPostFeedAuthorEnricher cannot be null.");
    }

    public CommunityNewestFeedResponseDTO execute(UUID authorUserId, String rawCursor, Integer requestedSize) {
        if (authorUserId == null) {
            throw new CommunityPostValidationException("Author user ID cannot be null.");
        }

        int size = (requestedSize != null) ? requestedSize : DEFAULT_SIZE;
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new CommunityPostValidationException("Size must be between " + MIN_SIZE + " and " + MAX_SIZE + ".");
        }

        CommunityPostKeysetCursor cursor = cursorCodec.decode(rawCursor);

        // Fetch size + 1 to determine hasNext
        int fetchLimit = size + 1;
        List<CommunityPostPublicDTO> fetchedPosts = postQueryPort.findAuthoredPostsKeyset(
                authorUserId,
                cursor != null ? cursor.createdAt() : null,
                cursor != null ? cursor.postId() : null,
                fetchLimit
        );

        boolean hasNext = fetchedPosts.size() > size;
        List<CommunityPostPublicDTO> pagePosts = hasNext ? fetchedPosts.subList(0, size) : fetchedPosts;

        if (pagePosts.isEmpty()) {
            return new CommunityNewestFeedResponseDTO(List.of(), null, size, false);
        }

        List<UUID> postIds = pagePosts.stream().map(CommunityPostPublicDTO::id).toList();
        Map<UUID, CommunityPostEngagementMetricsPort.PostEngagementMetrics> metricsMap =
                engagementMetricsPort.getEngagementMetricsForPosts(postIds);

        List<CommunityPostFeedItemDTO> feedItems = new ArrayList<>(pagePosts.size());
        for (CommunityPostPublicDTO post : pagePosts) {
            CommunityPostEngagementMetricsPort.PostEngagementMetrics metrics = (metricsMap != null)
                    ? metricsMap.getOrDefault(post.id(), new CommunityPostEngagementMetricsPort.PostEngagementMetrics(0L, 0L))
                    : new CommunityPostEngagementMetricsPort.PostEngagementMetrics(0L, 0L);

            long reactionCount = metrics.reactionCount();
            long commentCount = metrics.commentCount();
            long engagementScore = reactionCount + commentCount;

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
                    reactionCount,
                    commentCount,
                    engagementScore,
                    post.createdAt(),
                    post.updatedAt()
            ));
        }

        List<CommunityPostFeedItemDTO> enrichedItems = authorEnricher.enrich(feedItems);

        String nextCursor = null;
        if (hasNext) {
            CommunityPostPublicDTO lastPost = pagePosts.get(pagePosts.size() - 1);
            nextCursor = cursorCodec.encode(new CommunityPostKeysetCursor(lastPost.createdAt(), lastPost.id()));
        }

        return new CommunityNewestFeedResponseDTO(enrichedItems, nextCursor, size, hasNext);
    }
}
