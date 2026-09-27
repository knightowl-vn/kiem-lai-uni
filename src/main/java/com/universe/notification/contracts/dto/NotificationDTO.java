package com.universe.notification.contracts.dto;

import com.universe.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

/**
 * Frontend-oriented DTO representing a notification item in the user's feed.
 *
 * <p>Privacy & Architecture Rules:
 * <ul>
 *   <li>Does NOT leak the internal recipientUserId to the client;</li>
 *   <li>Exposes only presentation snapshots and clean read/unread status;</li>
 *   <li>actionUrl is resolved when applicable or null.</li>
 * </ul>
 */
public record NotificationDTO(
        UUID id,
        NotificationType type,
        String actorDisplayNameSnapshot,
        String targetType,
        String targetTitleSnapshot,
        String detailSnapshot,
        boolean unread,
        Instant readAt,
        Instant createdAt,
        String actionUrl
) {
}
