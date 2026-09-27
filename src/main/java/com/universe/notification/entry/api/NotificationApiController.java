package com.universe.notification.entry.api;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.notification.application.exceptions.NotificationNotFoundException;
import com.universe.notification.application.model.NotificationFilter;
import com.universe.notification.application.usecase.GetUnreadNotificationCountUseCase;
import com.universe.notification.application.usecase.ListUserNotificationsUseCase;
import com.universe.notification.application.usecase.MarkAllNotificationsReadUseCase;
import com.universe.notification.application.usecase.MarkNotificationReadUseCase;
import com.universe.notification.contracts.dto.NotificationPageDTO;
import com.universe.notification.contracts.dto.UnreadNotificationCountDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller serving in-app notification badge counts, paginated feeds,
 * and read-state mutations.
 *
 * <p>Security & Access Control:
 * <ul>
 *   <li>All endpoints require an authenticated user;</li>
 *   <li>The current user's ID is retrieved strictly via {@link AuthenticatedRequestIdentityAccessor};</li>
 *   <li>Mutations enforce ownership and return 404 for unknown or foreign notification IDs.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationApiController {

    private final GetUnreadNotificationCountUseCase getUnreadNotificationCountUseCase;
    private final ListUserNotificationsUseCase listUserNotificationsUseCase;
    private final MarkNotificationReadUseCase markNotificationReadUseCase;
    private final MarkAllNotificationsReadUseCase markAllNotificationsReadUseCase;

    public NotificationApiController(
            GetUnreadNotificationCountUseCase getUnreadNotificationCountUseCase,
            ListUserNotificationsUseCase listUserNotificationsUseCase,
            MarkNotificationReadUseCase markNotificationReadUseCase,
            MarkAllNotificationsReadUseCase markAllNotificationsReadUseCase
    ) {
        this.getUnreadNotificationCountUseCase = Objects.requireNonNull(
                getUnreadNotificationCountUseCase,
                "GetUnreadNotificationCountUseCase cannot be null."
        );
        this.listUserNotificationsUseCase = Objects.requireNonNull(
                listUserNotificationsUseCase,
                "ListUserNotificationsUseCase cannot be null."
        );
        this.markNotificationReadUseCase = Objects.requireNonNull(
                markNotificationReadUseCase,
                "MarkNotificationReadUseCase cannot be null."
        );
        this.markAllNotificationsReadUseCase = Objects.requireNonNull(
                markAllNotificationsReadUseCase,
                "MarkAllNotificationsReadUseCase cannot be null."
        );
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadNotificationCountDTO> getUnreadCount(HttpServletRequest request) {
        Optional<AuthenticatedRequestIdentity> identity =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UnreadNotificationCountDTO result = getUnreadNotificationCountUseCase.execute(identity.get().userId());
        return ResponseEntity.ok(result);
    }

    @GetMapping
    public ResponseEntity<NotificationPageDTO> listNotifications(
            @RequestParam(name = "filter", defaultValue = "all") String filter,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        Optional<AuthenticatedRequestIdentity> identity =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        NotificationFilter notificationFilter = NotificationFilter.fromString(filter);
        NotificationPageDTO pageDTO = listUserNotificationsUseCase.execute(
                identity.get().userId(),
                notificationFilter,
                page,
                size
        );
        return ResponseEntity.ok(pageDTO);
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable UUID id,
            HttpServletRequest request
    ) {
        if (id == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<AuthenticatedRequestIdentity> identity =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            markNotificationReadUseCase.execute(id, identity.get().userId(), Instant.now());
            return ResponseEntity.noContent().build();
        } catch (NotificationNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(HttpServletRequest request) {
        Optional<AuthenticatedRequestIdentity> identity =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        markAllNotificationsReadUseCase.execute(identity.get().userId(), Instant.now());
        return ResponseEntity.noContent().build();
    }
}
