package com.universe.notification.application.port;

import com.universe.notification.application.model.NotificationFilter;
import com.universe.notification.application.model.NotificationSlice;

import java.util.UUID;

/**
 * Outbound query port for retrieving paginated user notification feeds.
 */
public interface NotificationQueryPort {

    NotificationSlice findByRecipientUserId(
            UUID recipientUserId,
            NotificationFilter filter,
            int page,
            int size
    );
}
