package com.universe.novel.entry.reader;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.reader.ListUserReadingHistoryUseCase;
import com.universe.novel.application.reader.RecordReadingHistoryCommand;
import com.universe.novel.application.reader.RecordReadingHistoryUseCase;
import com.universe.novel.contracts.dto.reader.ReaderReadingHistoryDTO;
import com.universe.shared.security.AuthenticatedEmailResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderReadingHistoryControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String USER_EMAIL = "reader-history@example.com";

    @Mock
    private RecordReadingHistoryUseCase recordReadingHistoryUseCase;

    @Mock
    private ListUserReadingHistoryUseCase listUserReadingHistoryUseCase;

    @Mock
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private Authentication authentication;

    private ReaderReadingHistoryController controller;

    @BeforeEach
    void setUp() {
        controller = new ReaderReadingHistoryController(
                recordReadingHistoryUseCase,
                listUserReadingHistoryUseCase,
                authenticatedEmailResolver,
                userIdentityContract
        );
    }

    private UserDTO createTestUser() {
        return new UserDTO(
                USER_ID,
                USER_EMAIL,
                "Reader User",
                null,
                "ACTIVE",
                "USER",
                Instant.now()
        );
    }

    @Nested
    @DisplayName("1. POST /novel/chapters/{id}/history")
    class RecordHistoryTests {

        @Test
        @DisplayName("Returns 401 Unauthorized for anonymous requests")
        void shouldReturn401WhenAnonymous() {
            when(authenticatedEmailResolver.resolve(authentication)).thenReturn(Optional.empty());

            ResponseEntity<Void> response = controller.recordHistory(CHAPTER_ID, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(recordReadingHistoryUseCase);
        }

        @Test
        @DisplayName("Returns 400 Bad Request when chapterId is null")
        void shouldReturn400WhenChapterIdNull() {
            ResponseEntity<Void> response = controller.recordHistory(null, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(recordReadingHistoryUseCase);
        }

        @Test
        @DisplayName("Records history and returns 204 No Content for authenticated reader")
        void shouldRecordHistoryAndReturn204() {
            UserDTO user = createTestUser();
            when(authenticatedEmailResolver.resolve(authentication)).thenReturn(Optional.of(USER_EMAIL));
            when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));

            ResponseEntity<Void> response = controller.recordHistory(CHAPTER_ID, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            ArgumentCaptor<RecordReadingHistoryCommand> captor =
                    ArgumentCaptor.forClass(RecordReadingHistoryCommand.class);
            verify(recordReadingHistoryUseCase).execute(captor.capture());

            RecordReadingHistoryCommand command = captor.getValue();
            assertThat(command.userId()).isEqualTo(USER_ID);
            assertThat(command.chapterId()).isEqualTo(CHAPTER_ID);
        }

        @Test
        @DisplayName("Returns 404 Not Found when chapter is not found or not published")
        void shouldReturn404WhenChapterNotFound() {
            UserDTO user = createTestUser();
            when(authenticatedEmailResolver.resolve(authentication)).thenReturn(Optional.of(USER_EMAIL));
            when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
            doThrow(new ChapterNotFoundException(CHAPTER_ID))
                    .when(recordReadingHistoryUseCase)
                    .execute(any());

            ResponseEntity<Void> response = controller.recordHistory(CHAPTER_ID, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("2. GET /novel/history")
    class HistoryPageTests {

        @Test
        @DisplayName("Redirects to /login when unauthenticated")
        void shouldRedirectToLoginWhenAnonymous() {
            when(authenticatedEmailResolver.resolve(authentication)).thenReturn(Optional.empty());

            Model model = new ConcurrentModel();
            String view = controller.historyPage(authentication, model);

            assertThat(view).isEqualTo("redirect:/login");
            verifyNoInteractions(listUserReadingHistoryUseCase);
        }

        @Test
        @DisplayName("Renders history list page for authenticated reader")
        void shouldRenderHistoryListPage() {
            UserDTO user = createTestUser();
            when(authenticatedEmailResolver.resolve(authentication)).thenReturn(Optional.of(USER_EMAIL));
            when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));

            List<ReaderReadingHistoryDTO> list = List.of(
                    new ReaderReadingHistoryDTO(
                            CHAPTER_ID,
                            1,
                            "Chương 1",
                            "chuong-1",
                            "Quyển 1",
                            Instant.now()
                    )
            );
            when(listUserReadingHistoryUseCase.execute(USER_ID)).thenReturn(list);

            Model model = new ConcurrentModel();
            String view = controller.historyPage(authentication, model);

            assertThat(view).isEqualTo("novel/reader/history");
            assertThat(model.getAttribute("historyList")).isSameAs(list);
            assertThat(model.getAttribute("pageTitle")).isEqualTo("Lịch sử đọc");

            verify(listUserReadingHistoryUseCase).execute(USER_ID);
        }
    }
}
