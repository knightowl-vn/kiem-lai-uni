package com.universe.notification.infrastructure.persistence;

import com.universe.notification.application.model.NotificationFilter;
import com.universe.notification.application.port.NotificationQueryPort;
import com.universe.notification.contracts.dto.NotificationDTO;
import com.universe.notification.contracts.dto.NotificationPageDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Query persistence adapter implementing {@link NotificationQueryPort}.
 */
@Component
@Transactional(readOnly = true)
public class NotificationQueryPersistenceAdapter implements NotificationQueryPort {

    private final SpringDataNotificationRepository repository;

    public NotificationQueryPersistenceAdapter(SpringDataNotificationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataNotificationRepository cannot be null.");
    }

    @Override
    public NotificationPageDTO findByRecipientUserId(
            UUID recipientUserId,
            NotificationFilter filter,
            int page,
            int size
    ) {
        Objects.requireNonNull(recipientUserId, "Recipient user ID cannot be null.");
        Pageable pageable = PageRequest.of(page, size);
        String recipientIdStr = recipientUserId.toString();

        Page<NotificationJpaEntity> entityPage;
        if (filter == NotificationFilter.UNREAD) {
            entityPage = repository.findUnreadByRecipientUserId(recipientIdStr, pageable);
        } else {
            entityPage = repository.findAllByRecipientUserId(recipientIdStr, pageable);
        }

        List<NotificationDTO> items = entityPage.getContent().stream()
                .map(NotificationPersistenceMapper::toDTO)
                .toList();

        return new NotificationPageDTO(
                items,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements(),
                entityPage.getTotalPages(),
                entityPage.isFirst(),
                entityPage.isLast(),
                entityPage.hasNext()
        );
    }
}
