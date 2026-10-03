package com.universe.wiki.entry.web;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.wiki.application.appreciation.GetWikiAppreciationSummariesUseCase;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.saved.ListSavedWikiArticlesUseCase;
import com.universe.wiki.application.saved.SaveWikiArticleCommand;
import com.universe.wiki.application.saved.SaveWikiArticleUseCase;
import com.universe.wiki.application.saved.UnsaveWikiArticleCommand;
import com.universe.wiki.application.saved.UnsaveWikiArticleUseCase;
import com.universe.wiki.contracts.dto.saved.SavedWikiArticleItemDTO;
import com.universe.wiki.contracts.dto.saved.SavedWikiArticlePageDTO;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
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
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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

    @Mock
    private ListSavedWikiArticlesUseCase listSavedWikiArticlesUseCase;

    @Mock
    private GetWikiAppreciationSummariesUseCase getWikiAppreciationSummariesUseCase;

    private ArticleTypePathMapper articleTypePathMapper;
    private SavedWikiArticleController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        articleTypePathMapper = new ArticleTypePathMapper();
        controller = new SavedWikiArticleController(
                saveWikiArticleUseCase,
                unsaveWikiArticleUseCase,
                listSavedWikiArticlesUseCase,
                getWikiAppreciationSummariesUseCase,
                articleTypePathMapper
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
                        "reader_user",
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
                            "reader_user",
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
            assertThatThrownBy(() -> new SavedWikiArticleController(null, unsaveWikiArticleUseCase, listSavedWikiArticlesUseCase, getWikiAppreciationSummariesUseCase, articleTypePathMapper))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SaveWikiArticleUseCase");
        }

        @Test
        @DisplayName("Ném NullPointerException khi UnsaveWikiArticleUseCase là null")
        void shouldThrowWhenUnsaveUseCaseIsNull() {
            assertThatThrownBy(() -> new SavedWikiArticleController(saveWikiArticleUseCase, null, listSavedWikiArticlesUseCase, getWikiAppreciationSummariesUseCase, articleTypePathMapper))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("UnsaveWikiArticleUseCase");
        }

        @Test
        @DisplayName("Ném NullPointerException khi ListSavedWikiArticlesUseCase là null")
        void shouldThrowWhenListUseCaseIsNull() {
            assertThatThrownBy(() -> new SavedWikiArticleController(saveWikiArticleUseCase, unsaveWikiArticleUseCase, null, getWikiAppreciationSummariesUseCase, articleTypePathMapper))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ListSavedWikiArticlesUseCase");
        }

        @Test
        @DisplayName("Ném NullPointerException khi GetWikiAppreciationSummariesUseCase là null")
        void shouldThrowWhenAppreciationUseCaseIsNull() {
            assertThatThrownBy(() -> new SavedWikiArticleController(saveWikiArticleUseCase, unsaveWikiArticleUseCase, listSavedWikiArticlesUseCase, null, articleTypePathMapper))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("GetWikiAppreciationSummariesUseCase");
        }

        @Test
        @DisplayName("Ném NullPointerException khi ArticleTypePathMapper là null")
        void shouldThrowWhenArticleTypePathMapperIsNull() {
            assertThatThrownBy(() -> new SavedWikiArticleController(saveWikiArticleUseCase, unsaveWikiArticleUseCase, listSavedWikiArticlesUseCase, getWikiAppreciationSummariesUseCase, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ArticleTypePathMapper");
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

            controller.saveArticle(ARTICLE_ID, request);

            ArgumentCaptor<SaveWikiArticleCommand> commandCaptor =
                    ArgumentCaptor.forClass(SaveWikiArticleCommand.class);
            verify(saveWikiArticleUseCase).execute(commandCaptor.capture());
            assertThat(commandCaptor.getValue().userId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("Chưa xác thực: trả về 401 Unauthorized khi không có danh tính đăng nhập")
        void shouldReturn401WhenUserNotAuthenticated() {
            MockHttpServletRequest request = new MockHttpServletRequest();

            ResponseEntity<Void> response = controller.saveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(saveWikiArticleUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Bài viết không tồn tại / chưa xuất bản: ném PublishedWikiArticleNotFoundException -> 404 Not Found")
        void shouldReturn404WhenArticleNotPublished() {
            MockHttpServletRequest request = authenticatedRequest();
            doThrow(new PublishedWikiArticleNotFoundException(ARTICLE_ID))
                    .when(saveWikiArticleUseCase).execute(any(SaveWikiArticleCommand.class));

            ResponseEntity<Void> response = controller.saveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Lũy suy (Idempotent): lưu lại bài viết đã lưu vẫn trả về 204 No Content")
        void shouldReturn204WhenSavingAlreadySavedArticle() {
            MockHttpServletRequest request = authenticatedRequest();
            doNothing().when(saveWikiArticleUseCase).execute(any(SaveWikiArticleCommand.class));

            ResponseEntity<Void> response = controller.saveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        @DisplayName("Validate đầu vào: trả về 400 Bad Request khi articleId là null")
        void shouldReturn400WhenArticleIdIsNull() {
            MockHttpServletRequest request = authenticatedRequest();

            ResponseEntity<Void> response = controller.saveArticle(null, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(saveWikiArticleUseCase);
        }

        @Test
        @DisplayName("Spring MVC mapping: POST /wiki/articles/{articleId}/save định tuyến thành công với MockMvc")
        void shouldRoutePostSaveViaMockMvc() throws Exception {
            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/save")
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isNoContent());

            verify(saveWikiArticleUseCase).execute(any(SaveWikiArticleCommand.class));
        }

        @Test
        @DisplayName("Spring MVC mapping: trả về 400 Bad Request khi articleId không phải định dạng UUID")
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
        void shouldReturn204WhenAuthenticatedUserUnsavedArticle() {
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
        @DisplayName("Bảo mật: Actor ID luôn lấy từ AuthenticatedRequestIdentity khi bỏ lưu")
        void shouldIgnoreClientSuppliedUserIdAndUseRequestIdentityForUnsave() {
            MockHttpServletRequest request = authenticatedRequest();

            controller.unsaveArticle(ARTICLE_ID, request);

            ArgumentCaptor<UnsaveWikiArticleCommand> commandCaptor =
                    ArgumentCaptor.forClass(UnsaveWikiArticleCommand.class);
            verify(unsaveWikiArticleUseCase).execute(commandCaptor.capture());
            assertThat(commandCaptor.getValue().userId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("Chưa xác thực: trả về 401 Unauthorized khi bỏ lưu không có phiên đăng nhập")
        void shouldReturn401WhenUnsaveWithoutAuthentication() {
            MockHttpServletRequest request = new MockHttpServletRequest();

            ResponseEntity<Void> response = controller.unsaveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(unsaveWikiArticleUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Lũy suy (Idempotent): bỏ lưu bài viết chưa từng lưu hoặc đã bỏ lưu trước đó vẫn trả về 204")
        void shouldReturn204WhenUnsavingNonSavedArticle() {
            MockHttpServletRequest request = authenticatedRequest();
            doNothing().when(unsaveWikiArticleUseCase).execute(any(UnsaveWikiArticleCommand.class));

            ResponseEntity<Void> response = controller.unsaveArticle(ARTICLE_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        @DisplayName("Validate đầu vào: trả về 400 Bad Request khi articleId bỏ lưu là null")
        void shouldReturn400WhenUnsaveArticleIdIsNull() {
            MockHttpServletRequest request = authenticatedRequest();

            ResponseEntity<Void> response = controller.unsaveArticle(null, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(unsaveWikiArticleUseCase);
        }

        @Test
        @DisplayName("Spring MVC mapping: DELETE /wiki/articles/{articleId}/save định tuyến thành công với MockMvc")
        void shouldRouteDeleteSaveViaMockMvc() throws Exception {
            mockMvc.perform(delete("/wiki/articles/" + ARTICLE_ID + "/save")
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isNoContent());

            verify(unsaveWikiArticleUseCase).execute(any(UnsaveWikiArticleCommand.class));
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

    @Nested
    @DisplayName("4. GET /wiki/saved")
    class GetSavedArticlesPageTests {

        @Test
        @DisplayName("Thành công: người dùng đã đăng nhập xem trang bài viết đã lưu, giải quyết canonical articleTypePath và appreciation summaries")
        void shouldReturnSavedArticlesViewWhenAuthenticated() {
            MockHttpServletRequest request = authenticatedRequest();
            Model model = new ConcurrentModel();
            UUID coverId = UUID.randomUUID();

            SavedWikiArticleItemDTO availableItem = SavedWikiArticleItemDTO.available(
                    UUID.randomUUID(),
                    ARTICLE_ID,
                    Instant.now(),
                    "Tiêu Hạnh",
                    "tieu-hanh",
                    "CHARACTER",
                    "Tóm tắt Tiêu Hạnh",
                    coverId,
                    40,
                    60
            );
            SavedWikiArticlePageDTO expectedPage = new SavedWikiArticlePageDTO(
                    List.of(availableItem), 0, 20, 1, 1, true, true
            );
            when(listSavedWikiArticlesUseCase.execute(USER_ID, 0, 20))
                    .thenReturn(expectedPage);
            when(getWikiAppreciationSummariesUseCase.execute(List.of(ARTICLE_ID)))
                    .thenReturn(Map.of(ARTICLE_ID, new WikiAppreciationSummary(ARTICLE_ID, BigDecimal.valueOf(4.5), 10L)));

            String view = controller.savedArticlesPage(0, request, model);

            assertThat(view).isEqualTo("wiki/public/saved");
            SavedWikiArticlePageViewModel savedPage =
                    (SavedWikiArticlePageViewModel) model.getAttribute("savedPage");
            assertThat(savedPage).isNotNull();
            assertThat(savedPage.items()).hasSize(1);
            SavedWikiArticleViewItem viewItem = savedPage.items().get(0);
            assertThat(viewItem.articleTypePath()).isEqualTo("character");
            assertThat(viewItem.title()).isEqualTo("Tiêu Hạnh");
            assertThat(viewItem.coverMediaAssetId()).isEqualTo(coverId);
            assertThat(viewItem.coverObjectPosition()).isEqualTo("40% 60%");
            assertThat(model.getAttribute("pageTitle")).isEqualTo("Bài viết Wiki đã lưu");

            @SuppressWarnings("unchecked")
            Map<UUID, WikiAppreciationSummary> summaries =
                    (Map<UUID, WikiAppreciationSummary>) model.getAttribute("appreciationSummaries");
            assertThat(summaries).isNotNull();
            assertThat(summaries.get(ARTICLE_ID)).isNotNull();
            assertThat(summaries.get(ARTICLE_ID).count()).isEqualTo(10L);

            verify(listSavedWikiArticlesUseCase).execute(USER_ID, 0, 20);
            verify(getWikiAppreciationSummariesUseCase).execute(List.of(ARTICLE_ID));
        }

        @Test
        @DisplayName("Chuẩn hóa phân trang: số trang âm tự động được giới hạn về 0")
        void shouldClampNegativePageToZero() {
            MockHttpServletRequest request = authenticatedRequest();
            Model model = new ConcurrentModel();

            SavedWikiArticlePageDTO expectedPage = new SavedWikiArticlePageDTO(
                    List.of(), 0, 20, 0, 0, true, true
            );
            when(listSavedWikiArticlesUseCase.execute(USER_ID, 0, 20))
                    .thenReturn(expectedPage);

            String view = controller.savedArticlesPage(-5, request, model);

            assertThat(view).isEqualTo("wiki/public/saved");
            verify(listSavedWikiArticlesUseCase).execute(USER_ID, 0, 20);
        }

        @Test
        @DisplayName("Chưa đăng nhập: chuyển hướng về /login khi không có danh tính trong request")
        void shouldRedirectToLoginWhenAnonymous() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            Model model = new ConcurrentModel();

            String view = controller.savedArticlesPage(0, request, model);

            assertThat(view).isEqualTo("redirect:/login");
            verifyNoInteractions(listSavedWikiArticlesUseCase);
        }

        @Test
        @DisplayName("Spring MVC mapping: GET /wiki/saved định tuyến thành công với MockMvc")
        void shouldRouteGetSavedViaMockMvc() throws Exception {
            SavedWikiArticlePageDTO expectedPage = new SavedWikiArticlePageDTO(
                    List.of(), 1, 20, 30, 2, false, true
            );
            when(listSavedWikiArticlesUseCase.execute(USER_ID, 1, 20))
                    .thenReturn(expectedPage);

            mockMvc.perform(get("/wiki/saved")
                            .param("page", "1")
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isOk())
                    .andExpect(view().name("wiki/public/saved"))
                    .andExpect(model().attributeExists("savedPage"))
                    .andExpect(model().attribute("pageTitle", "Bài viết Wiki đã lưu"));

            verify(listSavedWikiArticlesUseCase).execute(USER_ID, 1, 20);
        }
    }
}
