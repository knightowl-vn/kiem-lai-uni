package com.universe.novel.entry.reader;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.reader.RecordReadingProgressCommand;
import com.universe.novel.application.reader.RecordReadingProgressUseCase;
import org.springframework.mock.web.MockHttpServletRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReaderReadingProgressControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String USER_EMAIL = "reader@example.com";

    @Mock
    private RecordReadingProgressUseCase recordReadingProgressUseCase;

    private ReaderReadingProgressController controller;

    @BeforeEach
    void setUp() {
        controller = new ReaderReadingProgressController(
                recordReadingProgressUseCase
        );
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuthenticatedRequestIdentityTestSupport.attach(
                request,
                new AuthenticatedRequestIdentity(
                        USER_ID,
                        USER_EMAIL,
                        "Reader User",
                        null,
                        UserStatus.ACTIVE,
                        UserRole.USER
                )
        );
        return request;
    }

    @Nested
    @DisplayName("1. Unauthenticated / Anonymous Access")
    class UnauthenticatedTests {

        @Test
        @DisplayName("Returns 401 Unauthorized when the request identity is absent")
        void shouldReturn401WhenRequestIdentityIsAbsent() {
            ResponseEntity<Void> response = controller.recordProgress(
                    CHAPTER_ID,
                    new MockHttpServletRequest()
            );

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
            ResponseEntity<Void> response = controller.recordProgress(
                    CHAPTER_ID,
                    authenticatedRequest()
            );

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
            ResponseEntity<Void> response = controller.recordProgress(
                    null,
                    new MockHttpServletRequest()
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(recordReadingProgressUseCase);
        }

        @Test
        @DisplayName("Returns 404 Not Found when chapter is not found or not published")
        void shouldReturn404WhenChapterNotFound() {
            doThrow(new ChapterNotFoundException(CHAPTER_ID))
                    .when(recordReadingProgressUseCase)
                    .execute(any());

            ResponseEntity<Void> response = controller.recordProgress(
                    CHAPTER_ID,
                    authenticatedRequest()
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Returns 500 Internal Server Error when unexpected exception occurs")
        void shouldReturn500OnUnexpectedException() {
            doThrow(new RuntimeException("DB down"))
                    .when(recordReadingProgressUseCase)
                    .execute(any());

            ResponseEntity<Void> response = controller.recordProgress(
                    CHAPTER_ID,
                    authenticatedRequest()
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
