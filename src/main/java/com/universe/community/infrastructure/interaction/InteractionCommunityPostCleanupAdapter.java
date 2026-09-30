package com.universe.community.infrastructure.interaction;

import com.universe.community.application.port.out.CommunityPostInteractionCleanupPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Infrastructure adapter implementing {@link CommunityPostInteractionCleanupPort} by delegating
 * to Interaction context's public cleanup port.
 */
@Component
public class InteractionCommunityPostCleanupAdapter implements CommunityPostInteractionCleanupPort {

    private final com.universe.interaction.application.ports.CommunityPostInteractionCleanupPort interactionCleanupPort;

    public InteractionCommunityPostCleanupAdapter(
            com.universe.interaction.application.ports.CommunityPostInteractionCleanupPort interactionCleanupPort
    ) {
        this.interactionCleanupPort = Objects.requireNonNull(
                interactionCleanupPort,
                "Interaction CommunityPostInteractionCleanupPort cannot be null."
        );
    }

    @Override
    public void cleanupCommunityPostInteractions(UUID postId, Instant deletedAt) {
        interactionCleanupPort.cleanupCommunityPostInteractions(postId, deletedAt);
    }
}
