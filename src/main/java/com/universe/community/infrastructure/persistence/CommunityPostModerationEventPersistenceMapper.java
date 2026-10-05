package com.universe.community.infrastructure.persistence;

import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.moderation.CommunityPostModerationAction;
import com.universe.community.domain.moderation.CommunityPostModerationEvent;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Persistence mapper converting between domain {@link CommunityPostModerationEvent} and {@link CommunityPostModerationEventJpaEntity}.
 */
@Component
public class CommunityPostModerationEventPersistenceMapper {

    public CommunityPostModerationEventJpaEntity toJpaEntity(CommunityPostModerationEvent domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain moderation event cannot be null.");
        }

        return new CommunityPostModerationEventJpaEntity(
                domain.id().toString(),
                domain.postId().toString(),
                domain.action().name(),
                domain.fromStatus().name(),
                domain.toStatus().name(),
                domain.moderatorUserId().toString(),
                domain.reason(),
                domain.createdAt()
        );
    }

    public CommunityPostModerationEvent toDomain(CommunityPostModerationEventJpaEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("CommunityPostModerationEventJpaEntity cannot be null.");
        }

        return new CommunityPostModerationEvent(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getPostId()),
                CommunityPostModerationAction.valueOf(entity.getAction()),
                CommunityPostStatus.valueOf(entity.getFromStatus()),
                CommunityPostStatus.valueOf(entity.getToStatus()),
                UUID.fromString(entity.getModeratorUserId()),
                entity.getReason(),
                entity.getCreatedAt()
        );
    }
}
