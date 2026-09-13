package com.universe.novel.entry.reader;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.reader.BookmarkChapterCommand;
import com.universe.novel.application.reader.BookmarkChapterUseCase;
import com.universe.novel.application.reader.ListUserBookmarkedChaptersUseCase;
import com.universe.novel.application.reader.UnbookmarkChapterCommand;
import com.universe.novel.application.reader.UnbookmarkChapterUseCase;
import com.universe.novel.contracts.dto.reader.ReaderBookmarkedChapterDTO;

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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderBookmarkControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String USER_EMAIL = "reader@example.com";

    @Mock
    private BookmarkChapterUseCase bookmarkChapterUseCase;

    @Mock
    private UnbookmarkChapterUseCase unbookmarkChapterUseCase;

    @Mock
    private ListUserBookmarkedChaptersUseCase listUserBookmarkedChaptersUseCase;

    private ReaderBookmarkController controller;

    @BeforeEach
    void setUp() {
        controller = new ReaderBookmarkController(
                bookmarkChapterUseCase,
                unbookmarkChapterUseCase,
                listUserBookmarkedChaptersUseCase
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
    @DisplayName("1. POST /novel/chapters/{id}/bookmark")
    class BookmarkTests {

        @Test
        @DisplayName("Returns 401 Unauthorized for anonymous requests")
        void shouldReturn401WhenAnonymous() {
            ResponseEntity<Void> response = controller.bookmarkChapter(
                    CHAPTER_ID,
                    new MockHttpServletRequest()
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(bookmarkChapterUseCase);
        }

        @Test
        @DisplayName("Returns 400 Bad Request when chapterId is null")
        void shouldReturn400WhenChapterIdNull() {
            ResponseEntity<Void> response = controller.bookmarkChapter(
                    null,
                    new MockHttpServletRequest()
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(bookmarkChapterUseCase);
        }

        @Test
        @DisplayName("Bookmarks chapter and returns 204 No Content for authenticated user")
        void shouldBookmarkChapterAndReturn204() {
            ResponseEntity<Void> response = controller.bookmarkChapter(
                    CHAPTER_ID,
                    authenticatedRequest()
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            ArgumentCaptor<BookmarkChapterCommand> captor = ArgumentCaptor.forClass(BookmarkChapterCommand.class);
            verify(bookmarkChapterUseCase).execute(captor.capture());

            BookmarkChapterCommand command = captor.getValue();
            assertThat(command.userId()).isEqualTo(USER_ID);
            assertThat(command.chapterId()).isEqualTo(CHAPTER_ID);
        }

        @Test
        @DisplayName("Returns 404 Not Found when chapter is not publicly readable or does not exist")
        void shouldReturn404WhenChapterNotFound() {
            doThrow(new ChapterNotFoundException(CHAPTER_ID))
                    .when(bookmarkChapterUseCase)
                    .execute(any());

            ResponseEntity<Void> response = controller.bookmarkChapter(
                    CHAPTER_ID,
                    authenticatedRequest()
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Returns 409 Conflict when user reaches bookmark limit (100 bookmarks)")
        void shouldReturn409WhenBookmarkLimitExceeded() {
            doThrow(new com.universe.novel.application.exceptions.BookmarkLimitExceededException(USER_ID, 100))
                    .when(bookmarkChapterUseCase)
                    .execute(any());

            ResponseEntity<Void> response = controller.bookmarkChapter(
                    CHAPTER_ID,
                    authenticatedRequest()
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    @DisplayName("2. DELETE /novel/chapters/{id}/bookmark")
    class UnbookmarkTests {

        @Test
        @DisplayName("Returns 401 Unauthorized for anonymous requests")
        void shouldReturn401WhenAnonymous() {
            ResponseEntity<Void> response = controller.unbookmarkChapter(
                    CHAPTER_ID,
                    new MockHttpServletRequest()
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(unbookmarkChapterUseCase);
        }

        @Test
        @DisplayName("Returns 400 Bad Request when chapterId is null")
        void shouldReturn400WhenChapterIdNull() {
            ResponseEntity<Void> response = controller.unbookmarkChapter(
                    null,
                    new MockHttpServletRequest()
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(unbookmarkChapterUseCase);
        }

        @Test
        @DisplayName("Unbookmarks chapter and returns 204 No Content for authenticated user")
        void shouldUnbookmarkChapterAndReturn204() {
            ResponseEntity<Void> response = controller.unbookmarkChapter(
                    CHAPTER_ID,
                    authenticatedRequest()
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            ArgumentCaptor<UnbookmarkChapterCommand> captor = ArgumentCaptor.forClass(UnbookmarkChapterCommand.class);
            verify(unbookmarkChapterUseCase).execute(captor.capture());

            UnbookmarkChapterCommand command = captor.getValue();
            assertThat(command.userId()).isEqualTo(USER_ID);
            assertThat(command.chapterId()).isEqualTo(CHAPTER_ID);
        }
    }

    @Nested
    @DisplayName("3. GET /novel/bookmarks")
    class BookmarksPageTests {

        @Test
        @DisplayName("Redirects to /login when unauthenticated")
        void shouldRedirectToLoginWhenAnonymous() {
            Model model = new ConcurrentModel();
            String view = controller.bookmarksPage(
                    new MockHttpServletRequest(),
                    model
            );

            assertThat(view).isEqualTo("redirect:/login");
            verifyNoInteractions(listUserBookmarkedChaptersUseCase);
        }

        @Test
        @DisplayName("Renders bookmarks list page for authenticated reader")
        void shouldRenderBookmarksListPage() {
            List<ReaderBookmarkedChapterDTO> list = List.of(
                    new ReaderBookmarkedChapterDTO(
                            CHAPTER_ID,
                            1,
                            "Chương 1",
                            "chuong-1",
                            "Quyển 1",
                            Instant.now()
                    )
            );
            when(listUserBookmarkedChaptersUseCase.execute(USER_ID)).thenReturn(list);

            Model model = new ConcurrentModel();
            String view = controller.bookmarksPage(authenticatedRequest(), model);

            assertThat(view).isEqualTo("novel/reader/bookmarks");
            assertThat(model.getAttribute("bookmarks")).isSameAs(list);
            assertThat(model.getAttribute("pageTitle")).isEqualTo("Dấu trang chương");

            verify(listUserBookmarkedChaptersUseCase).execute(USER_ID);
        }
    }
}
