package com.universe.notification.infrastructure.persistence;

import com.universe.notification.contracts.dto.NotificationDTO;
import com.universe.notification.domain.Notification;
import com.universe.notification.domain.NotificationType;

import java.util.UUID;

/**
 * Mapper converting between JPA entity, Domain model, and external DTOs.
 */
public final class NotificationPersistenceMapper {

    private NotificationPersistenceMapper() {
    }

    public static Notification toDomain(NotificationJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        UUID id = UUID.fromString(entity.getId());
        UUID recipientUserId = UUID.fromString(entity.getRecipientUserId());
        NotificationType type = NotificationType.valueOf(entity.getType());
        UUID actorUserId = entity.getActorUserId() != null ? UUID.fromString(entity.getActorUserId()) : null;
        UUID targetId = entity.getTargetId() != null ? UUID.fromString(entity.getTargetId()) : null;
        UUID commentId = entity.getCommentId() != null ? UUID.fromString(entity.getCommentId()) : null;
        UUID threadRootId = entity.getThreadRootId() != null ? UUID.fromString(entity.getThreadRootId()) : null;

        return Notification.reconstitute(
                id,
                recipientUserId,
                type,
                actorUserId,
                entity.getActorDisplayNameSnapshot(),
                entity.getTargetType(),
                targetId,
                entity.getTargetTitleSnapshot(),
                commentId,
                threadRootId,
                entity.getDetailSnapshot(),
                entity.getDedupeKey(),
                entity.getReadAt(),
                entity.getCreatedAt()
        );
    }

    public static NotificationJpaEntity toEntity(Notification domain) {
        if (domain == null) {
            return null;
        }

        return new NotificationJpaEntity(
                domain.getId().toString(),
                domain.getRecipientUserId().toString(),
                domain.getType().name(),
                domain.getActorUserId() != null ? domain.getActorUserId().toString() : null,
                domain.getActorDisplayNameSnapshot(),
                domain.getTargetType(),
                domain.getTargetId() != null ? domain.getTargetId().toString() : null,
                domain.getTargetTitleSnapshot(),
                domain.getCommentId() != null ? domain.getCommentId().toString() : null,
                domain.getThreadRootId() != null ? domain.getThreadRootId().toString() : null,
                domain.getDetailSnapshot(),
                domain.getDedupeKey(),
                domain.getReadAt(),
                domain.getCreatedAt()
        );
    }

    public static NotificationDTO toDTO(NotificationJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        NotificationType type = NotificationType.valueOf(entity.getType());
        UUID id = UUID.fromString(entity.getId());

        return new NotificationDTO(
                id,
                type,
                entity.getActorDisplayNameSnapshot(),
                entity.getTargetType(),
                entity.getTargetTitleSnapshot(),
                entity.getDetailSnapshot(),
                entity.getReadAt() == null,
                entity.getReadAt(),
                entity.getCreatedAt(),
                null
        );
    }

    public static NotificationDTO toDTO(Notification domain) {
        if (domain == null) {
            return null;
        }

        return new NotificationDTO(
                domain.getId(),
                domain.getType(),
                domain.getActorDisplayNameSnapshot(),
                domain.getTargetType(),
                domain.getTargetTitleSnapshot(),
                domain.getDetailSnapshot(),
                domain.isUnread(),
                domain.getReadAt(),
                domain.getCreatedAt(),
                null
        );
    }
}
