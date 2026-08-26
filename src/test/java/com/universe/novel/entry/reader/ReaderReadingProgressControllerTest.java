package com.universe.novel.entry.reader;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.reader.RecordReadingProgressCommand;
import com.universe.novel.application.reader.RecordReadingProgressUseCase;
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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderReadingProgressControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String USER_EMAIL = "reader@example.com";

    @Mock
    private RecordReadingProgressUseCase recordReadingProgressUseCase;

    @Mock
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private Authentication authentication;

    private ReaderReadingProgressController controller;

    @BeforeEach
    void setUp() {
        controller = new ReaderReadingProgressController(
                recordReadingProgressUseCase,
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
    @DisplayName("1. Unauthenticated / Anonymous Access")
    class UnauthenticatedTests {

        @Test
        @DisplayName("Returns 401 Unauthorized when email cannot be resolved (anonymous)")
        void shouldReturn401WhenEmailNotResolved() {
            when(authenticatedEmailResolver.resolve(authentication))
                    .thenReturn(Optional.empty());

            ResponseEntity<Void> response = controller.recordProgress(CHAPTER_ID, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(recordReadingProgressUseCase);
        }

        @Test
        @DisplayName("Returns 401 Unauthorized when user is not found by resolved email")
        void shouldReturn401WhenUserNotFound() {
            when(authenticatedEmailResolver.resolve(authentication))
                    .thenReturn(Optional.of(USER_EMAIL));
            when(userIdentityContract.findByEmail(USER_EMAIL))
                    .thenReturn(Optional.empty());

            ResponseEntity<Void> response = controller.recordProgress(CHAPTER_ID, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(recordReadingProgressUseCase);
        }
    }

    @Nested
    @DisplayName("2. Successful Progress Mutation")
    class SuccessfulMutationTests {

        @Test
        @DisplayName("Records progress and returns 204 No Content for authenticated user")
        void shouldRecordProgressAndReturn204() {
            UserDTO user = createTestUser();
            when(authenticatedEmailResolver.resolve(authentication))
                    .thenReturn(Optional.of(USER_EMAIL));
            when(userIdentityContract.findByEmail(USER_EMAIL))
                    .thenReturn(Optional.of(user));

            ResponseEntity<Void> response = controller.recordProgress(CHAPTER_ID, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            ArgumentCaptor<RecordReadingProgressCommand> captor =
                    ArgumentCaptor.forClass(RecordReadingProgressCommand.class);
            verify(recordReadingProgressUseCase).execute(captor.capture());

            RecordReadingProgressCommand command = captor.getValue();
            assertThat(command.userId()).isEqualTo(USER_ID);
            assertThat(command.chapterId()).isEqualTo(CHAPTER_ID);
        }
    }

    @Nested
    @DisplayName("3. Error and Edge Cases")
    class ErrorTests {

        @Test
        @DisplayName("Returns 400 Bad Request when chapterId is null")
        void shouldReturn400WhenChapterIdIsNull() {
            ResponseEntity<Void> response = controller.recordProgress(null, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(recordReadingProgressUseCase);
        }

        @Test
        @DisplayName("Returns 404 Not Found when chapter is not found or not published")
        void shouldReturn404WhenChapterNotFound() {
            UserDTO user = createTestUser();
            when(authenticatedEmailResolver.resolve(authentication))
                    .thenReturn(Optional.of(USER_EMAIL));
            when(userIdentityContract.findByEmail(USER_EMAIL))
                    .thenReturn(Optional.of(user));
            doThrow(new ChapterNotFoundException(CHAPTER_ID))
                    .when(recordReadingProgressUseCase)
                    .execute(any());

            ResponseEntity<Void> response = controller.recordProgress(CHAPTER_ID, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Returns 500 Internal Server Error when unexpected exception occurs")
        void shouldReturn500OnUnexpectedException() {
            UserDTO user = createTestUser();
            when(authenticatedEmailResolver.resolve(authentication))
                    .thenReturn(Optional.of(USER_EMAIL));
            when(userIdentityContract.findByEmail(USER_EMAIL))
                    .thenReturn(Optional.of(user));
            doThrow(new RuntimeException("DB down"))
                    .when(recordReadingProgressUseCase)
                    .execute(any());

            ResponseEntity<Void> response = controller.recordProgress(CHAPTER_ID, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
