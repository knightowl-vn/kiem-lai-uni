package com.universe.community.application.service;

import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.application.port.out.CommunityAuthorProfileSummary;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Application service responsible for bulk-enriching Community feed items with author profile summaries.
 */
@Service
public class CommunityPostFeedAuthorEnricher {

    private final CommunityAuthorProfilePort authorProfilePort;

    public CommunityPostFeedAuthorEnricher(CommunityAuthorProfilePort authorProfilePort) {
        this.authorProfilePort = Objects.requireNonNull(authorProfilePort, "CommunityAuthorProfilePort cannot be null.");
    }

    /**
     * Enriches a final page of feed items with author display name, public handle, and avatar URL.
     *
     * <p>Invariants:
     * <ul>
     *   <li>Empty input list results in zero outbound calls to Identity.</li>
     *   <li>Non-empty input list triggers exactly ONE bulk lookup for distinct author IDs.</li>
     *   <li>Preserves item ordering, ranking scores, and engagement counts identically.</li>
     *   <li>Missing author summaries do not remove posts; neutral nullable fields are exposed.</li>
     * </ul>
     *
     * @param items list of feed items to enrich
     * @return new list containing author-enriched feed item DTOs
     */
    public List<CommunityPostFeedItemDTO> enrich(List<CommunityPostFeedItemDTO> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        Set<UUID> authorUserIds = items.stream()
                .map(CommunityPostFeedItemDTO::authorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (authorUserIds.isEmpty()) {
            return items;
        }

        Map<UUID, CommunityAuthorProfileSummary> summaries = authorProfilePort.findAuthorProfilesByIds(authorUserIds);

        List<CommunityPostFeedItemDTO> enriched = new ArrayList<>(items.size());
        for (CommunityPostFeedItemDTO item : items) {
            CommunityAuthorProfileSummary summary = (summaries != null) ? summaries.get(item.authorUserId()) : null;
            String displayName = (summary != null) ? summary.displayName() : null;
            String publicHandle = (summary != null) ? summary.publicHandle() : null;
            String avatarUrl = (summary != null) ? summary.avatarUrl() : null;

            enriched.add(new CommunityPostFeedItemDTO(
                    item.id(),
                    item.authorUserId(),
                    displayName,
                    publicHandle,
                    avatarUrl,
                    item.caption(),
                    item.imageMediaAssetId(),
                    item.imageUrl(),
                    item.contentVersion(),
                    item.reactionCount(),
                    item.commentCount(),
                    item.engagementScore(),
                    item.createdAt(),
                    item.updatedAt()
            ));
        }

        return enriched;
    }
}
