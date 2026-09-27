package com.universe.wiki.entry.web;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.wiki.application.appreciation.SetWikiAppreciationCommand;
import com.universe.wiki.application.appreciation.SetWikiAppreciationResult;
import com.universe.wiki.application.appreciation.SetWikiAppreciationUseCase;
import com.universe.wiki.application.exceptions.DuplicateWikiAppreciationException;
import com.universe.wiki.application.exceptions.WikiAppreciationTargetNotFoundException;
import com.universe.wiki.domain.appreciation.WikiAppreciationScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("WikiArticleAppreciationController Standalone Tests")
class WikiArticleAppreciationControllerTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String USER_EMAIL = "scholar@universe.local";

    @Mock
    private SetWikiAppreciationUseCase setWikiAppreciationUseCase;

    private WikiArticleAppreciationController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new WikiArticleAppreciationController(setWikiAppreciationUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private RequestPostProcessor attachRequestIdentity(UUID userId) {
        return request -> {
            AuthenticatedRequestIdentityTestSupport.attach(
                    request,
                    new AuthenticatedRequestIdentity(
                            userId,
                            USER_EMAIL,
                            "Scholar User",
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
        @DisplayName("Ném NullPointerException khi SetWikiAppreciationUseCase là null")
        void shouldThrowWhenUseCaseIsNull() {
            assertThatThrownBy(() -> new WikiArticleAppreciationController(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SetWikiAppreciationUseCase");
        }
    }

    @Nested
    @DisplayName("2. PUT /api/wiki/articles/{articleId}/appreciation")
    class SetAppreciationTests {

        @Test
        @DisplayName("A. Người dùng đã xác thực đánh giá bài CHARACTER hợp lệ -> 200 OK kèm payload tổng hợp")
        void shouldReturn200WhenAuthenticatedUserRatesCharacter() throws Exception {
            SetWikiAppreciationResult result = new SetWikiAppreciationResult(
                    ARTICLE_ID,
                    WikiAppreciationScore.fromStars(new BigDecimal("5.0")),
                    new BigDecimal("4.80"),
                    10L,
                    true
            );
            when(setWikiAppreciationUseCase.execute(any(SetWikiAppreciationCommand.class))).thenReturn(result);

            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 5.0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.wikiArticleId").value(ARTICLE_ID.toString()))
                    .andExpect(jsonPath("$.value").value(5.0))
                    .andExpect(jsonPath("$.average").value(4.80))
                    .andExpect(jsonPath("$.displayAverage").value("4.8"))
                    .andExpect(jsonPath("$.count").value(10))
                    .andExpect(jsonPath("$.changed").value(true));

            ArgumentCaptor<SetWikiAppreciationCommand> commandCaptor =
                    ArgumentCaptor.forClass(SetWikiAppreciationCommand.class);
            verify(setWikiAppreciationUseCase).execute(commandCaptor.capture());

            SetWikiAppreciationCommand command = commandCaptor.getValue();
            assertThat(command.wikiArticleId()).isEqualTo(ARTICLE_ID);
            assertThat(command.actorUserId()).isEqualTo(USER_ID);
            assertThat(command.score()).isEqualTo(WikiAppreciationScore.fromStars(new BigDecimal("5.0")));
        }

        @Test
        @DisplayName("B. Người dùng đã xác thực đánh giá bài FACTION hợp lệ -> 200 OK")
        void shouldReturn200WhenAuthenticatedUserRatesFaction() throws Exception {
            SetWikiAppreciationResult result = new SetWikiAppreciationResult(
                    ARTICLE_ID,
                    WikiAppreciationScore.fromStars(new BigDecimal("4.5")),
                    new BigDecimal("4.25"),
                    4L,
                    true
            );
            when(setWikiAppreciationUseCase.execute(any(SetWikiAppreciationCommand.class))).thenReturn(result);

            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 4.5}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.wikiArticleId").value(ARTICLE_ID.toString()))
                    .andExpect(jsonPath("$.value").value(4.5))
                    .andExpect(jsonPath("$.average").value(4.25))
                    .andExpect(jsonPath("$.displayAverage").value("4.3"))
                    .andExpect(jsonPath("$.count").value(4))
                    .andExpect(jsonPath("$.changed").value(true));
        }

        @Test
        @DisplayName("C. Bảo mật: Client gửi userId giả trong body/param bị bỏ qua hoàn toàn, lấy đúng actor từ server")
        void shouldIgnoreClientSuppliedUserId() throws Exception {
            UUID spoofedUserId = UUID.randomUUID();
            SetWikiAppreciationResult result = new SetWikiAppreciationResult(
                    ARTICLE_ID,
                    WikiAppreciationScore.fromStars(new BigDecimal("3.0")),
                    new BigDecimal("3.00"),
                    1L,
                    true
            );
            when(setWikiAppreciationUseCase.execute(any(SetWikiAppreciationCommand.class))).thenReturn(result);

            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 3.0, \"userId\": \"" + spoofedUserId + "\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<SetWikiAppreciationCommand> commandCaptor =
                    ArgumentCaptor.forClass(SetWikiAppreciationCommand.class);
            verify(setWikiAppreciationUseCase).execute(commandCaptor.capture());
            assertThat(commandCaptor.getValue().actorUserId()).isEqualTo(USER_ID);
            assertThat(commandCaptor.getValue().actorUserId()).isNotEqualTo(spoofedUserId);
            assertThat(commandCaptor.getValue().score()).isEqualTo(WikiAppreciationScore.fromStars(new BigDecimal("3.0")));
        }

        @Test
        @DisplayName("D. Chưa xác thực: trả về 401 UNAUTHORIZED khi không có AuthenticatedRequestIdentity")
        void shouldReturn401WhenAnonymous() throws Exception {
            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 5}"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(setWikiAppreciationUseCase);
        }

        @Test
        @DisplayName("F. Thiếu trường value trong request body -> 400 Bad Request")
        void shouldReturn400WhenValueIsMissing() throws Exception {
            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(setWikiAppreciationUseCase);
        }

        @Test
        @DisplayName("G. Điểm đánh giá value = 0 (< MIN_VALUE) -> 400 Bad Request")
        void shouldReturn400WhenValueIsZero() throws Exception {
            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 0}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(setWikiAppreciationUseCase);
        }

        @Test
        @DisplayName("H. Điểm đánh giá value = 6 (> MAX_VALUE) -> 400 Bad Request")
        void shouldReturn400WhenValueIsGreaterThanFive() throws Exception {
            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 6}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(setWikiAppreciationUseCase);
        }

        @Test
        @DisplayName("Điểm đánh giá âm -> 400 Bad Request")
        void shouldReturn400WhenValueIsNegative() throws Exception {
            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": -1}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(setWikiAppreciationUseCase);
        }

        @Test
        @DisplayName("Điểm đánh giá số thập phân không hợp lệ (như 0.5, 4.7, 4.9) -> 400 Bad Request và use case không bao giờ được gọi")
        void shouldReturn400WhenValueIsInvalidDecimal() throws Exception {
            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 0.5}"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 4.7}"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 4.9}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(setWikiAppreciationUseCase);
        }

        @Test
        @DisplayName("Điểm đánh giá dạng chuỗi (ví dụ \"4\") -> 400 Bad Request và use case không bao giờ được gọi")
        void shouldReturn400WhenValueIsString() throws Exception {
            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": \"4\"}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(setWikiAppreciationUseCase);
        }

        @Test
        @DisplayName("Body rỗng hoặc không đọc được JSON -> 400 Bad Request")
        void shouldReturn400WhenBodyIsMalformedOrMissing() throws Exception {
            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("invalid-json"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(setWikiAppreciationUseCase);
        }

        @Test
        @DisplayName("I. Path variable articleId không phải UUID -> 400 Bad Request")
        void shouldReturn400WhenArticleIdPathVariableIsMalformed() throws Exception {
            mockMvc.perform(put("/api/wiki/articles/khong-phai-uuid/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 5}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(setWikiAppreciationUseCase);
        }

        @Test
        @DisplayName("J. Bài viết không tồn tại / chưa xuất bản / sai ArticleType -> 404 Not Found")
        void shouldReturn404WhenTargetNotFoundOrIneligible() throws Exception {
            when(setWikiAppreciationUseCase.execute(any(SetWikiAppreciationCommand.class)))
                    .thenThrow(new WikiAppreciationTargetNotFoundException(ARTICLE_ID));

            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 5}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("K. Đánh giá cùng giá trị (same-value no-op) -> 200 OK với changed=false")
        void shouldReturn200WithChangedFalseOnSameValue() throws Exception {
            SetWikiAppreciationResult result = new SetWikiAppreciationResult(
                    ARTICLE_ID,
                    WikiAppreciationScore.fromStars(new BigDecimal("5.0")),
                    new BigDecimal("4.80"),
                    10L,
                    false
            );
            when(setWikiAppreciationUseCase.execute(any(SetWikiAppreciationCommand.class))).thenReturn(result);

            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 5.0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.value").value(5.0))
                    .andExpect(jsonPath("$.changed").value(false));
        }

        @Test
        @DisplayName("L. Tranh chấp trùng lặp không thể phục hồi (DuplicateWikiAppreciationException) -> 409 Conflict")
        void shouldReturn409WhenDuplicateWikiAppreciationExceptionOccurs() throws Exception {
            when(setWikiAppreciationUseCase.execute(any(SetWikiAppreciationCommand.class)))
                    .thenThrow(new DuplicateWikiAppreciationException(ARTICLE_ID, USER_ID, new RuntimeException("duplicate")));

            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 5}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("M. Phản hồi định dạng displayAverage chuỗi làm tròn HALF_UP (4.75 -> '4.8', 4.65 -> '4.7', 5.0 -> '5.0') và giữ nguyên BigDecimal average")
        void shouldFormatDisplayAverageCorrectlyPreservingOriginalAverage() throws Exception {
            // 4.75 -> "4.8"
            SetWikiAppreciationResult result475 = new SetWikiAppreciationResult(
                    ARTICLE_ID,
                    WikiAppreciationScore.fromStars(new BigDecimal("5.0")),
                    new BigDecimal("4.75"),
                    2L,
                    true
            );
            when(setWikiAppreciationUseCase.execute(any(SetWikiAppreciationCommand.class))).thenReturn(result475);

            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 5.0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.average").value(4.75))
                    .andExpect(jsonPath("$.displayAverage").value("4.8"));

            // 4.65 -> "4.7"
            SetWikiAppreciationResult result465 = new SetWikiAppreciationResult(
                    ARTICLE_ID,
                    WikiAppreciationScore.fromStars(new BigDecimal("4.5")),
                    new BigDecimal("4.65"),
                    2L,
                    true
            );
            when(setWikiAppreciationUseCase.execute(any(SetWikiAppreciationCommand.class))).thenReturn(result465);

            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 4.5}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.average").value(4.65))
                    .andExpect(jsonPath("$.displayAverage").value("4.7"));

            // 5.0 -> "5.0"
            SetWikiAppreciationResult result50 = new SetWikiAppreciationResult(
                    ARTICLE_ID,
                    WikiAppreciationScore.fromStars(new BigDecimal("5.0")),
                    new BigDecimal("5.0"),
                    1L,
                    true
            );
            when(setWikiAppreciationUseCase.execute(any(SetWikiAppreciationCommand.class))).thenReturn(result50);

            mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": 5.0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.average").value(5.0))
                    .andExpect(jsonPath("$.displayAverage").value("5.0"));
        }
    }
}
