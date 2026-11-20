package com.universe.community.application.service;

import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.application.port.out.CommunityAuthorProfileSummary;
import com.universe.community.application.port.out.CommunityPostImageDeliveryPort;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Application service responsible for bulk-enriching Community feed items with author profile summaries
 * and direct public post image delivery URLs.
 */
@Service
public class CommunityPostFeedAuthorEnricher {

    private final CommunityAuthorProfilePort authorProfilePort;
    private final CommunityPostImageDeliveryPort imageDeliveryPort;

    public CommunityPostFeedAuthorEnricher(CommunityAuthorProfilePort authorProfilePort) {
        this(authorProfilePort, null);
    }

    @Autowired
    public CommunityPostFeedAuthorEnricher(
            CommunityAuthorProfilePort authorProfilePort,
            @Autowired(required = false) CommunityPostImageDeliveryPort imageDeliveryPort
    ) {
        this.authorProfilePort = Objects.requireNonNull(authorProfilePort, "CommunityAuthorProfilePort cannot be null.");
        this.imageDeliveryPort = imageDeliveryPort;
    }

    /**
     * Enriches a final page of feed items with author display name, public handle, avatar URL,
     * and batched direct image delivery URLs (falling back to existing {@code imageUrl} when unresolved).
     *
     * <p>Invariants:
     * <ul>
     *   <li>Empty input list results in zero outbound calls to Identity and Media.</li>
     *   <li>Non-empty input list triggers at most ONE bulk lookup per needed collaborator for distinct IDs.</li>
     *   <li>Preserves item ordering, ranking scores, and engagement counts identically.</li>
     *   <li>Missing author summaries do not remove posts; neutral nullable fields are exposed.</li>
     *   <li>Unresolved image assets preserve existing fallback delivery URLs.</li>
     * </ul>
     *
     * @param items list of feed items to enrich
     * @return new list containing enriched feed item DTOs
     */
    public List<CommunityPostFeedItemDTO> enrich(List<CommunityPostFeedItemDTO> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        Set<UUID> authorUserIds = items.stream()
                .map(CommunityPostFeedItemDTO::authorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<UUID> imageAssetIds = (imageDeliveryPort == null)
                ? Set.of()
                : items.stream()
                        .map(CommunityPostFeedItemDTO::imageMediaAssetId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

        if (authorUserIds.isEmpty() && imageAssetIds.isEmpty()) {
            return items;
        }

        Map<UUID, CommunityAuthorProfileSummary> summaries = authorUserIds.isEmpty()
                ? Map.of()
                : authorProfilePort.findAuthorProfilesByIds(authorUserIds);

        Map<UUID, String> directImageUrls = resolveDirectImageUrls(imageAssetIds);

        List<CommunityPostFeedItemDTO> enriched = new ArrayList<>(items.size());
        for (CommunityPostFeedItemDTO item : items) {
            CommunityAuthorProfileSummary summary = (summaries != null) ? summaries.get(item.authorUserId()) : null;
            String displayName = (summary != null) ? summary.displayName() : null;
            String publicHandle = (summary != null) ? summary.publicHandle() : null;
            String avatarUrl = (summary != null) ? summary.avatarUrl() : null;
            String resolvedImageUrl = resolveItemImageUrl(item, directImageUrls);

            enriched.add(new CommunityPostFeedItemDTO(
                    item.id(),
                    item.authorUserId(),
                    displayName,
                    publicHandle,
                    avatarUrl,
                    item.caption(),
                    item.imageMediaAssetId(),
                    resolvedImageUrl,
                    item.contentVersion(),
                    item.reactionCount(),
                    item.commentCount(),
                    item.engagementScore(),
                    item.createdAt(),
                    item.updatedAt(),
                    item.currentUserReaction(),
                    item.publishedAt()
            ));
        }

        return enriched;
    }

    private Map<UUID, String> resolveDirectImageUrls(Set<UUID> imageAssetIds) {
        if (imageDeliveryPort == null || imageAssetIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> resolved = imageDeliveryPort.resolvePublicImageUrls(imageAssetIds);
        return resolved != null ? resolved : Map.of();
    }

    private static String resolveItemImageUrl(
            CommunityPostFeedItemDTO item,
            Map<UUID, String> directImageUrls
    ) {
        if (item.imageMediaAssetId() == null) {
            return item.imageUrl();
        }
        String directUrl = directImageUrls.get(item.imageMediaAssetId());
        return (directUrl != null && !directUrl.isBlank()) ? directUrl : item.imageUrl();
    }
}
