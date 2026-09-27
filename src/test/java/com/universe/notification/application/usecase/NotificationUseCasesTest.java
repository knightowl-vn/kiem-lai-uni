package com.universe.notification.application.usecase;

import com.universe.notification.application.exceptions.NotificationNotFoundException;
import com.universe.notification.application.model.NotificationFilter;
import com.universe.notification.application.port.NotificationQueryPort;
import com.universe.notification.application.port.NotificationRepositoryPort;
import com.universe.notification.contracts.command.NotificationDispatchCommand;
import com.universe.notification.contracts.dto.NotificationDTO;
import com.universe.notification.contracts.dto.NotificationPageDTO;
import com.universe.notification.contracts.dto.UnreadNotificationCountDTO;
import com.universe.notification.contracts.port.NotificationDispatchPort;
import com.universe.notification.domain.Notification;
import com.universe.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Notification Use Cases Unit Tests")
class NotificationUseCasesTest {

    @Mock
    private NotificationDispatchPort notificationDispatchPort;

    @Mock
    private NotificationRepositoryPort notificationRepositoryPort;

    @Mock
    private NotificationQueryPort notificationQueryPort;

    @Test
    @DisplayName("DispatchNotificationUseCase delegates command to dispatch port")
    void dispatchNotificationDelegates() {
        DispatchNotificationUseCase useCase = new DispatchNotificationUseCase(notificationDispatchPort);
        NotificationDispatchCommand cmd = new NotificationDispatchCommand(
                UUID.randomUUID(),
                NotificationType.COMMENT_REPLY,
                UUID.randomUUID(),
                "Actor",
                "NOVEL_CHAPTER",
                UUID.randomUUID(),
                "Chapter",
                UUID.randomUUID(),
                null,
                null,
                "dedupe-key-1"
        );

        useCase.execute(cmd);
        verify(notificationDispatchPort).dispatch(cmd);
    }

    @Test
    @DisplayName("GetUnreadNotificationCountUseCase retrieves count via repository port")
    void getUnreadCountRetrievesCount() {
        GetUnreadNotificationCountUseCase useCase = new GetUnreadNotificationCountUseCase(notificationRepositoryPort);
        UUID userId = UUID.randomUUID();

        when(notificationRepositoryPort.countUnreadByRecipientUserId(userId)).thenReturn(7L);

        UnreadNotificationCountDTO count = useCase.execute(userId);
        assertThat(count.unreadCount()).isEqualTo(7L);
    }

    @Test
    @DisplayName("ListUserNotificationsUseCase passes normalized pagination and filter to query port")
    void listUserNotificationsNormalizesPagination() {
        ListUserNotificationsUseCase useCase = new ListUserNotificationsUseCase(notificationQueryPort);
        UUID userId = UUID.randomUUID();

        NotificationPageDTO emptyPage = NotificationPageDTO.empty(0, 1);
        when(notificationQueryPort.findByRecipientUserId(userId, NotificationFilter.UNREAD, 0, 1))
                .thenReturn(emptyPage);

        NotificationPageDTO result = useCase.execute(userId, NotificationFilter.UNREAD, -1, 0);
        assertThat(result).isEqualTo(emptyPage);
        verify(notificationQueryPort).findByRecipientUserId(userId, NotificationFilter.UNREAD, 0, 1);
    }

    @Test
    @DisplayName("MarkNotificationReadUseCase marks read successfully when owned and unread")
    void markNotificationReadSuccess() {
        MarkNotificationReadUseCase useCase = new MarkNotificationReadUseCase(notificationRepositoryPort);
        UUID notifId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        when(notificationRepositoryPort.markAsRead(eq(notifId), eq(userId), any(Instant.class))).thenReturn(true);

        useCase.execute(notifId, userId, now);
        verify(notificationRepositoryPort).markAsRead(notifId, userId, now);
    }

    @Test
    @DisplayName("MarkNotificationReadUseCase throws NotificationNotFoundException when row does not exist")
    void markNotificationReadNotFound() {
        MarkNotificationReadUseCase useCase = new MarkNotificationReadUseCase(notificationRepositoryPort);
        UUID notifId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(notificationRepositoryPort.markAsRead(eq(notifId), eq(userId), any(Instant.class))).thenReturn(false);
        when(notificationRepositoryPort.findById(notifId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(notifId, userId, Instant.now()))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    @DisplayName("MarkNotificationReadUseCase throws NotificationNotFoundException when row belongs to another user")
    void markNotificationReadWrongOwner() {
        MarkNotificationReadUseCase useCase = new MarkNotificationReadUseCase(notificationRepositoryPort);
        UUID notifId = UUID.randomUUID();
        UUID callerUserId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();

        when(notificationRepositoryPort.markAsRead(eq(notifId), eq(callerUserId), any(Instant.class))).thenReturn(false);
        Notification otherNotification = Notification.create(
                notifId,
                otherUserId,
                NotificationType.COMMENT_REPLY,
                null, null, null, null, null, null, null, null, "dedupe", Instant.now()
        );
        when(notificationRepositoryPort.findById(notifId)).thenReturn(Optional.of(otherNotification));

        assertThatThrownBy(() -> useCase.execute(notifId, callerUserId, Instant.now()))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    @DisplayName("MarkNotificationReadUseCase completes silently when already read by owner")
    void markNotificationReadIdempotentAlreadyRead() {
        MarkNotificationReadUseCase useCase = new MarkNotificationReadUseCase(notificationRepositoryPort);
        UUID notifId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(notificationRepositoryPort.markAsRead(eq(notifId), eq(userId), any(Instant.class))).thenReturn(false);
        Notification alreadyRead = Notification.reconstitute(
                notifId,
                userId,
                NotificationType.COMMENT_REPLY,
                null, null, null, null, null, null, null, null, "dedupe",
                Instant.now(),
                Instant.now()
        );
        when(notificationRepositoryPort.findById(notifId)).thenReturn(Optional.of(alreadyRead));

        // Must succeed without throwing
        useCase.execute(notifId, userId, Instant.now());
    }

    @Test
    @DisplayName("MarkAllNotificationsReadUseCase delegates to repository port")
    void markAllNotificationsReadDelegates() {
        MarkAllNotificationsReadUseCase useCase = new MarkAllNotificationsReadUseCase(notificationRepositoryPort);
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        when(notificationRepositoryPort.markAllAsRead(eq(userId), any(Instant.class))).thenReturn(4);

        int count = useCase.execute(userId, now);
        assertThat(count).isEqualTo(4);
        verify(notificationRepositoryPort).markAllAsRead(userId, now);
    }
}
