package com.universe.notification.entry.api;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.notification.application.exceptions.NotificationNotFoundException;
import com.universe.notification.application.model.NotificationFilter;
import com.universe.notification.application.usecase.GetUnreadNotificationCountUseCase;
import com.universe.notification.application.usecase.ListUserNotificationsUseCase;
import com.universe.notification.application.usecase.MarkAllNotificationsReadUseCase;
import com.universe.notification.application.usecase.MarkNotificationReadUseCase;
import com.universe.notification.contracts.dto.NotificationDTO;
import com.universe.notification.contracts.dto.NotificationPageDTO;
import com.universe.notification.contracts.dto.UnreadNotificationCountDTO;
import com.universe.notification.domain.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationApiController REST Tests")
class NotificationApiControllerTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String USER_EMAIL = "user@kiemlai.local";

    @Mock
    private GetUnreadNotificationCountUseCase getUnreadNotificationCountUseCase;

    @Mock
    private ListUserNotificationsUseCase listUserNotificationsUseCase;

    @Mock
    private MarkNotificationReadUseCase markNotificationReadUseCase;

    @Mock
    private MarkAllNotificationsReadUseCase markAllNotificationsReadUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        NotificationApiController controller = new NotificationApiController(
                getUnreadNotificationCountUseCase,
                listUserNotificationsUseCase,
                markNotificationReadUseCase,
                markAllNotificationsReadUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private RequestPostProcessor authenticatedUser() {
        return request -> {
            AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                    USER_ID,
                    USER_EMAIL,
                    "Test User",
                    null,
                    UserStatus.ACTIVE,
                    UserRole.USER
            );
            AuthenticatedRequestIdentityTestSupport.attach(request, identity);
            return request;
        };
    }

    @Nested
    @DisplayName("GET /api/notifications/unread-count")
    class UnreadCountTests {

        @Test
        @DisplayName("Returns 401 Unauthorized when unauthenticated")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/notifications/unread-count"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(getUnreadNotificationCountUseCase);
        }

        @Test
        @DisplayName("Returns 200 with unread count for authenticated user")
        void authenticatedReturnsCount() throws Exception {
            when(getUnreadNotificationCountUseCase.execute(USER_ID))
                    .thenReturn(new UnreadNotificationCountDTO(5L));

            mockMvc.perform(get("/api/notifications/unread-count").with(authenticatedUser()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unreadCount").value(5));

            verify(getUnreadNotificationCountUseCase).execute(USER_ID);
        }
    }

    @Nested
    @DisplayName("GET /api/notifications")
    class ListNotificationsTests {

        @Test
        @DisplayName("Returns 401 Unauthorized when unauthenticated")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/notifications"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(listUserNotificationsUseCase);
        }

        @Test
        @DisplayName("Returns 200 with default pagination and filter=ALL")
        void returnsPaginatedFeedDefault() throws Exception {
            UUID notifId = UUID.randomUUID();
            NotificationDTO item = new NotificationDTO(
                    notifId,
                    NotificationType.COMMENT_REPLY,
                    "Actor User",
                    "NOVEL_CHAPTER",
                    "Chapter 10",
                    null,
                    true,
                    null,
                    Instant.parse("2026-09-27T08:00:00Z"),
                    null
            );
            NotificationPageDTO pageDTO = new NotificationPageDTO(
                    List.of(item),
                    0,
                    20,
                    1L,
                    1,
                    true,
                    true,
                    false
            );

            when(listUserNotificationsUseCase.execute(USER_ID, NotificationFilter.ALL, 0, 20))
                    .thenReturn(pageDTO);

            mockMvc.perform(get("/api/notifications").with(authenticatedUser()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].id").value(notifId.toString()))
                    .andExpect(jsonPath("$.items[0].type").value("COMMENT_REPLY"))
                    .andExpect(jsonPath("$.items[0].actorDisplayNameSnapshot").value("Actor User"))
                    .andExpect(jsonPath("$.items[0].unread").value(true))
                    .andExpect(jsonPath("$.items[0].actionUrl").isEmpty())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1));

            verify(listUserNotificationsUseCase).execute(USER_ID, NotificationFilter.ALL, 0, 20);
        }

        @Test
        @DisplayName("Applies filter=unread when requested")
        void appliesUnreadFilter() throws Exception {
            NotificationPageDTO emptyPage = NotificationPageDTO.empty(1, 10);
            when(listUserNotificationsUseCase.execute(USER_ID, NotificationFilter.UNREAD, 1, 10))
                    .thenReturn(emptyPage);

            mockMvc.perform(get("/api/notifications")
                            .param("filter", "unread")
                            .param("page", "1")
                            .param("size", "10")
                            .with(authenticatedUser()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.size").value(10));

            verify(listUserNotificationsUseCase).execute(USER_ID, NotificationFilter.UNREAD, 1, 10);
        }
    }

    @Nested
    @DisplayName("PUT /api/notifications/{id}/read")
    class MarkOneReadTests {

        @Test
        @DisplayName("Returns 401 Unauthorized when unauthenticated")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(put("/api/notifications/" + UUID.randomUUID() + "/read"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(markNotificationReadUseCase);
        }

        @Test
        @DisplayName("Returns 204 No Content on successful mark as read")
        void marksAsReadSuccessfully() throws Exception {
            UUID notifId = UUID.randomUUID();
            doNothing().when(markNotificationReadUseCase).execute(eq(notifId), eq(USER_ID), any());

            mockMvc.perform(put("/api/notifications/" + notifId + "/read")
                            .with(authenticatedUser())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNoContent());

            verify(markNotificationReadUseCase).execute(eq(notifId), eq(USER_ID), any());
        }

        @Test
        @DisplayName("Returns 404 Not Found when notification is not found or not owned")
        void notFoundReturns404() throws Exception {
            UUID notifId = UUID.randomUUID();
            doThrow(new NotificationNotFoundException(notifId))
                    .when(markNotificationReadUseCase).execute(eq(notifId), eq(USER_ID), any());

            mockMvc.perform(put("/api/notifications/" + notifId + "/read")
                            .with(authenticatedUser())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PUT /api/notifications/read-all")
    class MarkAllReadTests {

        @Test
        @DisplayName("Returns 401 Unauthorized when unauthenticated")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(put("/api/notifications/read-all"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(markAllNotificationsReadUseCase);
        }

        @Test
        @DisplayName("Returns 204 No Content on mark all read")
        void markAllReadSuccess() throws Exception {
            when(markAllNotificationsReadUseCase.execute(eq(USER_ID), any())).thenReturn(3);

            mockMvc.perform(put("/api/notifications/read-all")
                            .with(authenticatedUser())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNoContent());

            verify(markAllNotificationsReadUseCase).execute(eq(USER_ID), any());
        }
    }
}
