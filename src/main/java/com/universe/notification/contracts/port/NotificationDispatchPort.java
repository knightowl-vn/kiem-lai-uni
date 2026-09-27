package com.universe.notification.contracts.port;

import com.universe.notification.contracts.command.NotificationDispatchCommand;

/**
 * Port contract for publishing in-app notifications from producer contexts.
 */
public interface NotificationDispatchPort {

    /**
     * Idempotently dispatches an in-app notification.
     *
     * <p>If a notification with the given {@code dedupeKey} already exists, this method completes
     * as a no-op without raising an exception and without marking the surrounding transaction
     * rollback-only.
     *
     * @param command dispatch payload
     */
    void dispatch(NotificationDispatchCommand command);
}
