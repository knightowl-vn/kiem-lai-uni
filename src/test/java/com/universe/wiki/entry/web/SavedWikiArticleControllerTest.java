package com.universe.wiki.entry.web;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.saved.SaveWikiArticleCommand;
import com.universe.wiki.application.saved.SaveWikiArticleUseCase;
import com.universe.wiki.application.saved.UnsaveWikiArticleCommand;
import com.universe.wiki.application.saved.UnsaveWikiArticleUseCase;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("SavedWikiArticleController Tests")
class SavedWikiArticleControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ARTICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String USER_EMAIL = "reader@universe.local";

    @Mock
    private SaveWikiArticleUseCase saveWikiArticleUseCase;

    @Mock
    private UnsaveWikiArticleUseCase unsaveWikiArticleUseCase;

    private SavedWikiArticleController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new SavedWikiArticleController(
                saveWikiArticleUseCase,
                unsaveWikiArticleUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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

    private RequestPostProcessor attachRequestIdentity(UUID userId) {
        return request -> {
            AuthenticatedRequestIdentityTestSupport.attach(
                    request,
                    new AuthenticatedRequestIdentity(
                            userId,
                            USER_EMAIL,
                            "Reader User",
                            null,
                            UserStatus.ACTIVE,
                            UserRole.USER
                    )
            );
            return request;
        };
    }

    @Nested
    @DisplayName("1. Constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Ném NullPointerException khi SaveWikiArticleUseCase là null")
        void shouldThrowWhenSaveUseCaseIsNull() {
            assertThatThrownBy(() -> new SavedWikiArticleController(null, unsaveWikiArticleUseCase))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SaveWikiArticleUseCase");
        }

        @Test
        @DisplayName("Ném NullPointerException khi UnsaveWikiArticleUseCase là null")
        void shouldThrowWhenUnsaveUseCaseIsNull() {
            assertThatThrownBy(() -> new SavedWikiArticleController(saveWikiArticleUseCase, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("UnsaveWikiArticleUseCase");
        }
    }

    @Nested
    @DisplayName("2. POST /wiki/articles/{articleId}/save")
    class SaveArticleTests {

        @Test
        @DisplayName("Thành công: người dùng đã xác thực lưu bài viết hợp lệ -> 204 No Content")
        void shouldReturn204WhenAuthenticatedUserSavesArticle() {
            MockHttpServletRequest request = authenticatedRequest();

            ResponseEntity<Void> response = controller.saveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            ArgumentCaptor<SaveWikiArticleCommand> commandCaptor =
                    ArgumentCaptor.forClass(SaveWikiArticleCommand.class);
            verify(saveWikiArticleUseCase).execute(commandCaptor.capture());

            SaveWikiArticleCommand command = commandCaptor.getValue();
            assertThat(command.userId()).isEqualTo(USER_ID);
            assertThat(command.articleId()).isEqualTo(ARTICLE_ID);
        }

        @Test
        @DisplayName("Bảo mật: Actor ID luôn lấy từ AuthenticatedRequestIdentity, bỏ qua hoàn toàn tham số client")
        void shouldIgnoreClientSuppliedUserIdAndUseRequestIdentity() {
            MockHttpServletRequest request = authenticatedRequest();
            request.setParameter("userId", UUID.randomUUID().toString());

            ResponseEntity<Void> response = controller.saveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            ArgumentCaptor<SaveWikiArticleCommand> commandCaptor =
                    ArgumentCaptor.forClass(SaveWikiArticleCommand.class);
            verify(saveWikiArticleUseCase).execute(commandCaptor.capture());
            assertThat(commandCaptor.getValue().userId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("Chưa xác thực: trả về 401 UNAUTHORIZED khi không có AuthenticatedRequestIdentity")
        void shouldReturn401WhenAnonymous() {
            MockHttpServletRequest request = new MockHttpServletRequest();

            ResponseEntity<Void> response = controller.saveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(saveWikiArticleUseCase);
        }

        @Test
        @DisplayName("Lưu bài viết không tồn tại hoặc chưa xuất bản: trả về 404 NOT_FOUND không làm lộ chi tiết")
        void shouldReturn404WhenArticleNotPublishedOrNotFound() {
            MockHttpServletRequest request = authenticatedRequest();
            doThrow(new PublishedWikiArticleNotFoundException(ARTICLE_ID))
                    .when(saveWikiArticleUseCase)
                    .execute(any(SaveWikiArticleCommand.class));

            ResponseEntity<Void> response = controller.saveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Idempotent: lưu lại bài viết đã lưu trả về 204 No Content")
        void shouldReturn204WhenArticleAlreadySaved() {
            MockHttpServletRequest request = authenticatedRequest();
            doNothing().when(saveWikiArticleUseCase).execute(any(SaveWikiArticleCommand.class));

            ResponseEntity<Void> response = controller.saveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        @DisplayName("Tham số không hợp lệ: trả về 400 Bad Request khi articleId là null")
        void shouldReturn400WhenArticleIdIsNull() {
            MockHttpServletRequest request = authenticatedRequest();

            ResponseEntity<Void> response = controller.saveArticle(null, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(saveWikiArticleUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Spring MVC mapping: POST /wiki/articles/{articleId}/save định tuyến thành công")
        void shouldRoutePostSaveViaMockMvc() throws Exception {
            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/save")
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isNoContent());

            verify(saveWikiArticleUseCase).execute(new SaveWikiArticleCommand(USER_ID, ARTICLE_ID));
        }

        @Test
        @DisplayName("Spring MVC mapping: trả về 400 Bad Request khi articleId trên path không phải định dạng UUID")
        void shouldReturn400WhenArticleIdPathVariableIsMalformed() throws Exception {
            mockMvc.perform(post("/wiki/articles/khong-phai-uuid/save")
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(saveWikiArticleUseCase);
        }
    }

    @Nested
    @DisplayName("3. DELETE /wiki/articles/{articleId}/save")
    class UnsaveArticleTests {

        @Test
        @DisplayName("Thành công: người dùng đã xác thực bỏ lưu bài viết -> 204 No Content")
        void shouldReturn204WhenAuthenticatedUserUnsavesArticle() {
            MockHttpServletRequest request = authenticatedRequest();

            ResponseEntity<Void> response = controller.unsaveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            ArgumentCaptor<UnsaveWikiArticleCommand> commandCaptor =
                    ArgumentCaptor.forClass(UnsaveWikiArticleCommand.class);
            verify(unsaveWikiArticleUseCase).execute(commandCaptor.capture());

            UnsaveWikiArticleCommand command = commandCaptor.getValue();
            assertThat(command.userId()).isEqualTo(USER_ID);
            assertThat(command.articleId()).isEqualTo(ARTICLE_ID);
        }

        @Test
        @DisplayName("Bảo mật: Actor ID bỏ lưu luôn lấy từ AuthenticatedRequestIdentity, bỏ qua tham số client")
        void shouldIgnoreClientSuppliedUserIdOnUnsave() {
            MockHttpServletRequest request = authenticatedRequest();
            request.setParameter("userId", UUID.randomUUID().toString());

            ResponseEntity<Void> response = controller.unsaveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            ArgumentCaptor<UnsaveWikiArticleCommand> commandCaptor =
                    ArgumentCaptor.forClass(UnsaveWikiArticleCommand.class);
            verify(unsaveWikiArticleUseCase).execute(commandCaptor.capture());
            assertThat(commandCaptor.getValue().userId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("Chưa xác thực: trả về 401 UNAUTHORIZED khi bỏ lưu mà không có danh tính")
        void shouldReturn401WhenAnonymousOnUnsave() {
            MockHttpServletRequest request = new MockHttpServletRequest();

            ResponseEntity<Void> response = controller.unsaveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(unsaveWikiArticleUseCase);
        }

        @Test
        @DisplayName("Tham số không hợp lệ: trả về 400 Bad Request khi articleId bỏ lưu là null")
        void shouldReturn400WhenArticleIdIsNullOnUnsave() {
            MockHttpServletRequest request = authenticatedRequest();

            ResponseEntity<Void> response = controller.unsaveArticle(null, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(unsaveWikiArticleUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Spring MVC mapping: DELETE /wiki/articles/{articleId}/save định tuyến thành công")
        void shouldRouteDeleteSaveViaMockMvc() throws Exception {
            mockMvc.perform(delete("/wiki/articles/" + ARTICLE_ID + "/save")
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isNoContent());

            verify(unsaveWikiArticleUseCase).execute(new UnsaveWikiArticleCommand(USER_ID, ARTICLE_ID));
        }

        @Test
        @DisplayName("Spring MVC mapping: trả về 400 Bad Request khi articleId bỏ lưu không phải UUID")
        void shouldReturn400WhenDeleteArticleIdPathVariableIsMalformed() throws Exception {
            mockMvc.perform(delete("/wiki/articles/khong-phai-uuid/save")
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(unsaveWikiArticleUseCase);
        }
    }
}
