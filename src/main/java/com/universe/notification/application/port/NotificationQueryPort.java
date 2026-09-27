package com.universe.notification.application.port;

import com.universe.notification.application.model.NotificationFilter;
import com.universe.notification.contracts.dto.NotificationPageDTO;

import java.util.UUID;

/**
 * Outbound query port for retrieving paginated user notification feeds.
 */
public interface NotificationQueryPort {

    NotificationPageDTO findByRecipientUserId(
            UUID recipientUserId,
            NotificationFilter filter,
            int page,
            int size
    );
}
