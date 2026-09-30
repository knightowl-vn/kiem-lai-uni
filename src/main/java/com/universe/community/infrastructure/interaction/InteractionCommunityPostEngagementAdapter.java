package com.universe.community.infrastructure.interaction;

import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort;
import com.universe.interaction.application.ports.CommunityPostEngagementQueryPort;
import com.universe.interaction.application.query.CommunityPostEngagementCountsDTO;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Infrastructure adapter implementing {@link CommunityPostEngagementMetricsPort} by delegating
 * to the Interaction module's exported {@link CommunityPostEngagementQueryPort}.
 */
@Component
public class InteractionCommunityPostEngagementAdapter implements CommunityPostEngagementMetricsPort {

    private final CommunityPostEngagementQueryPort communityPostEngagementQueryPort;

    public InteractionCommunityPostEngagementAdapter(
            CommunityPostEngagementQueryPort communityPostEngagementQueryPort
    ) {
        this.communityPostEngagementQueryPort = Objects.requireNonNull(
                communityPostEngagementQueryPort,
                "CommunityPostEngagementQueryPort cannot be null."
        );
    }

    @Override
    public Map<UUID, PostEngagementMetrics> getEngagementMetricsForPosts(Collection<UUID> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, CommunityPostEngagementCountsDTO> countsMap =
                communityPostEngagementQueryPort.getEngagementCountsForPosts(postIds);

        Map<UUID, PostEngagementMetrics> result = new HashMap<>();
        if (countsMap != null) {
            for (Map.Entry<UUID, CommunityPostEngagementCountsDTO> entry : countsMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(
                            entry.getKey(),
                            new PostEngagementMetrics(
                                    entry.getValue().reactionCount(),
                                    entry.getValue().commentCount()
                            )
                    );
                }
            }
        }

        return result;
    }
}
