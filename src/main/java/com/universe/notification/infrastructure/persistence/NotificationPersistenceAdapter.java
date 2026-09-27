package com.universe.notification.infrastructure.persistence;

import com.universe.notification.application.port.NotificationRepositoryPort;
import com.universe.notification.contracts.command.NotificationDispatchCommand;
import com.universe.notification.contracts.port.NotificationDispatchPort;
import com.universe.notification.domain.Notification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link NotificationRepositoryPort} and {@link NotificationDispatchPort}.
 */
@Component
@Transactional(readOnly = true)
public class NotificationPersistenceAdapter implements NotificationRepositoryPort, NotificationDispatchPort {

    private final SpringDataNotificationRepository repository;

    public NotificationPersistenceAdapter(SpringDataNotificationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataNotificationRepository cannot be null.");
    }

    @Override
    public Optional<Notification> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString())
                .map(NotificationPersistenceMapper::toDomain);
    }

    @Override
    @Transactional
    public void save(Notification notification) {
        Objects.requireNonNull(notification, "Notification cannot be null.");
        repository.save(NotificationPersistenceMapper.toEntity(notification));
    }

    @Override
    public long countUnreadByRecipientUserId(UUID recipientUserId) {
        if (recipientUserId == null) {
            return 0L;
        }
        return repository.countUnreadByRecipientUserId(recipientUserId.toString());
    }

    @Override
    @Transactional
    public boolean markAsRead(UUID id, UUID recipientUserId, Instant now) {
        if (id == null || recipientUserId == null) {
            return false;
        }
        Instant timestamp = now != null ? now : Instant.now();
        int rows = repository.markAsRead(id.toString(), recipientUserId.toString(), timestamp);
        return rows > 0;
    }

    @Override
    @Transactional
    public int markAllAsRead(UUID recipientUserId, Instant now) {
        if (recipientUserId == null) {
            return 0;
        }
        Instant timestamp = now != null ? now : Instant.now();
        return repository.markAllAsRead(recipientUserId.toString(), timestamp);
    }

    @Override
    @Transactional
    public void dispatch(NotificationDispatchCommand command) {
        Objects.requireNonNull(command, "NotificationDispatchCommand cannot be null.");

        // Clean Architecture Domain Invariant validation before dispatch
        Notification notification = Notification.create(
                UUID.randomUUID(),
                command.recipientUserId(),
                command.type(),
                command.actorUserId(),
                command.actorDisplayNameSnapshot(),
                command.targetType(),
                command.targetId(),
                command.targetTitleSnapshot(),
                command.commentId(),
                command.threadRootId(),
                command.detailSnapshot(),
                command.dedupeKey(),
                Instant.now()
        );

        repository.insertIdempotent(
                notification.getId().toString(),
                notification.getRecipientUserId().toString(),
                notification.getType().name(),
                notification.getActorUserId() != null ? notification.getActorUserId().toString() : null,
                notification.getActorDisplayNameSnapshot(),
                notification.getTargetType(),
                notification.getTargetId() != null ? notification.getTargetId().toString() : null,
                notification.getTargetTitleSnapshot(),
                notification.getCommentId() != null ? notification.getCommentId().toString() : null,
                notification.getThreadRootId() != null ? notification.getThreadRootId().toString() : null,
                notification.getDetailSnapshot(),
                notification.getDedupeKey(),
                notification.getReadAt(),
                notification.getCreatedAt()
        );
    }
}
