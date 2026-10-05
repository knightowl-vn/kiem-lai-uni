package com.universe.wiki.entry.web;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.shared.security.AuthenticatedEmailResolver;
import com.universe.wiki.application.contribution.SubmitWikiContributionCommand;
import com.universe.wiki.application.contribution.SubmitWikiContributionResult;
import com.universe.wiki.application.contribution.SubmitWikiContributionUseCase;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.exceptions.WikiContributionSubmissionRateLimitedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PublicWikiContributionController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("PublicWikiContributionController WebMvc & Security Tests")
class PublicWikiContributionControllerTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String USER_EMAIL = "reader@universe.local";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

    @MockBean
    private CustomAuthenticationFailureHandler customAuthenticationFailureHandler;

    @MockBean
    private AccountStatusFilter accountStatusFilter;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private SubmitWikiContributionUseCase submitWikiContributionUseCase;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            chain.doFilter(request, response);
            return null;
        }).when(accountStatusFilter).doFilter(any(), any(), any());
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
        @DisplayName("Ném NullPointerException khi SubmitWikiContributionUseCase là null")
        void shouldThrowWhenUseCaseIsNull() {
            assertThatThrownBy(() -> new PublicWikiContributionController(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SubmitWikiContributionUseCase");
        }
    }

    @Nested
    @DisplayName("2. Spring Security & CSRF Enforcement Tests")
    class SecurityAndCsrfTests {

        @Test
        @WithMockUser
        @DisplayName("Người dùng đã xác thực kèm CSRF token hợp lệ -> vượt qua Security và đến controller thành công")
        void shouldSucceedWhenAuthenticatedWithValidCsrf() throws Exception {
            UUID contributionId = UUID.randomUUID();
            SubmitWikiContributionResult result = new SubmitWikiContributionResult(
                    contributionId,
                    "NEW",
                    false,
                    "Đóng góp của bạn đã được gửi thành công và đang chờ kiểm duyệt."
            );
            when(submitWikiContributionUseCase.execute(any())).thenReturn(result);

            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(csrf())
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": 1,
                                        "contextType": "GENERAL",
                                        "contributionType": "WORDING",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự."
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.contributionId").value(contributionId.toString()))
                    .andExpect(jsonPath("$.alreadySubmitted").value(false));

            verify(submitWikiContributionUseCase).execute(any());
        }

        @Test
        @WithMockUser
        @DisplayName("Người dùng đã xác thực nhưng THIẾU CSRF token -> bị Spring Security chặn và chuyển hướng sang /access-denied (302)")
        void shouldRedirectToAccessDeniedWhenAuthenticatedWithoutCsrf() throws Exception {
            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": 1,
                                        "contextType": "GENERAL",
                                        "contributionType": "WORDING",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự."
                                    }
                                    """))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/access-denied"));

            verifyNoInteractions(submitWikiContributionUseCase);
        }

        @Test
        @WithAnonymousUser
        @DisplayName("Người dùng ẩn danh (chưa đăng nhập) -> bị Spring Security chặn và chuyển hướng sang /login (302)")
        void shouldRedirectToLoginWhenAnonymous() throws Exception {
            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": 1,
                                        "contextType": "GENERAL",
                                        "contributionType": "WORDING",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự."
                                    }
                                    """))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));

            verifyNoInteractions(submitWikiContributionUseCase);
        }

        @Test
        @WithMockUser
        @DisplayName("Xác thực SecurityContext nhưng thiếu AuthenticatedRequestIdentity trong request attribute -> 401 Unauthorized từ controller")
        void shouldReturn401WhenRequestIdentityMissing() throws Exception {
            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": 1,
                                        "contextType": "GENERAL",
                                        "contributionType": "WORDING",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự."
                                    }
                                    """))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(submitWikiContributionUseCase);
        }
    }

    @Nested
    @DisplayName("3. Controller Payload Validation & Exception Mapping Tests")
    class ControllerPayloadValidationTests {

        @Test
        @WithMockUser
        @DisplayName("Không có request body -> 400 Bad Request")
        void shouldReturn400WhenBodyIsMissing() throws Exception {
            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(csrf())
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(submitWikiContributionUseCase);
        }

        @Test
        @WithMockUser
        @DisplayName("UUID trên URI không đúng định dạng -> 400 Bad Request")
        void shouldReturn400WhenArticleIdIsMalformed() throws Exception {
            mockMvc.perform(post("/wiki/articles/not-a-valid-uuid/contributions")
                            .with(csrf())
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": 1,
                                        "contextType": "GENERAL",
                                        "contributionType": "WORDING",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự."
                                    }
                                    """))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(submitWikiContributionUseCase);
        }

        @Test
        @WithMockUser
        @DisplayName("articleContentVersion là null trong request body -> ném IllegalArgumentException -> 400 Bad Request (không 500)")
        void shouldReturn400WhenArticleContentVersionIsNull() throws Exception {
            when(submitWikiContributionUseCase.execute(any()))
                    .thenThrow(new IllegalArgumentException("Phiên bản nội dung bài viết phải lớn hơn hoặc bằng 1."));

            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(csrf())
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": null,
                                        "contextType": "GENERAL",
                                        "contributionType": "WORDING",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự."
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser
        @DisplayName("contextType là enum không hợp lệ -> ném IllegalArgumentException -> 400 Bad Request")
        void shouldReturn400WhenContextTypeIsMalformed() throws Exception {
            when(submitWikiContributionUseCase.execute(any()))
                    .thenThrow(new IllegalArgumentException("Loại ngữ cảnh đóng góp không hợp lệ: SOMETHING_INVALID"));

            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(csrf())
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": 1,
                                        "contextType": "SOMETHING_INVALID",
                                        "contributionType": "WORDING",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự."
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser
        @DisplayName("contributionType là enum không hợp lệ -> ném IllegalArgumentException -> 400 Bad Request")
        void shouldReturn400WhenContributionTypeIsMalformed() throws Exception {
            when(submitWikiContributionUseCase.execute(any()))
                    .thenThrow(new IllegalArgumentException("Loại đóng góp không hợp lệ: NOT_A_TYPE"));

            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(csrf())
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": 1,
                                        "contextType": "GENERAL",
                                        "contributionType": "NOT_A_TYPE",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự."
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser
        @DisplayName("Bài viết không tồn tại hoặc chưa xuất bản -> 404 Not Found")
        void shouldReturn404WhenArticleNotFound() throws Exception {
            when(submitWikiContributionUseCase.execute(any()))
                    .thenThrow(new PublishedWikiArticleNotFoundException(ARTICLE_ID));

            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(csrf())
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": 1,
                                        "contextType": "GENERAL",
                                        "contributionType": "WORDING",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự."
                                    }
                                    """))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser
        @DisplayName("Use case ném IllegalArgumentException (validation failure) -> 400 Bad Request")
        void shouldReturn400WhenValidationFailsInUseCase() throws Exception {
            when(submitWikiContributionUseCase.execute(any()))
                    .thenThrow(new IllegalArgumentException("Dữ liệu không hợp lệ"));

            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(csrf())
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": 1,
                                        "contextType": "GENERAL",
                                        "contributionType": "WORDING",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự."
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser
        @DisplayName("Use case ném WikiContributionSubmissionRateLimitedException -> 429 Too Many Requests kèm Retry-After header và thông điệp thân thiện")
        void shouldReturn429WhenRateLimited() throws Exception {
            when(submitWikiContributionUseCase.execute(any()))
                    .thenThrow(new WikiContributionSubmissionRateLimitedException(275L));

            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(csrf())
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": 1,
                                        "contextType": "GENERAL",
                                        "contributionType": "WORDING",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự."
                                    }
                                    """))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "275"))
                    .andExpect(jsonPath("$.alreadySubmitted").value(false))
                    .andExpect(jsonPath("$.message").value("Bạn đang gửi đóng góp quá nhanh. Vui lòng thử lại sau ít phút."));
        }

        @Test
        @WithMockUser
        @DisplayName("Đóng góp mới hợp lệ -> 201 Created kèm payload phản hồi")
        void shouldReturn201WhenNewContributionCreated() throws Exception {
            UUID contributionId = UUID.randomUUID();
            SubmitWikiContributionResult result = new SubmitWikiContributionResult(
                    contributionId,
                    "NEW",
                    false,
                    "Đóng góp của bạn đã được gửi thành công và đang chờ kiểm duyệt."
            );
            when(submitWikiContributionUseCase.execute(any())).thenReturn(result);

            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(csrf())
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": 1,
                                        "contextType": "GENERAL",
                                        "contributionType": "WORDING",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                                        "sources": ["https://example.com/source"]
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.contributionId").value(contributionId.toString()))
                    .andExpect(jsonPath("$.status").value("NEW"))
                    .andExpect(jsonPath("$.alreadySubmitted").value(false))
                    .andExpect(jsonPath("$.message").value("Đóng góp của bạn đã được gửi thành công và đang chờ kiểm duyệt."));

            ArgumentCaptor<SubmitWikiContributionCommand> captor = ArgumentCaptor.forClass(SubmitWikiContributionCommand.class);
            verify(submitWikiContributionUseCase).execute(captor.capture());
            SubmitWikiContributionCommand captured = captor.getValue();
            assertThat(captured.articleId()).isEqualTo(ARTICLE_ID);
            assertThat(captured.submittedByUserId()).isEqualTo(USER_ID);
            assertThat(captured.articleContentVersion()).isEqualTo(1L);
            assertThat(captured.contextType()).isEqualTo("GENERAL");
            assertThat(captured.contributionType()).isEqualTo("WORDING");
            assertThat(captured.sources()).containsExactly("https://example.com/source");
        }

        @Test
        @WithMockUser
        @DisplayName("Đóng góp trùng lặp trong 60s -> 200 OK kèm alreadySubmitted=true")
        void shouldReturn200WhenDuplicateContributionDetected() throws Exception {
            UUID existingId = UUID.randomUUID();
            SubmitWikiContributionResult result = new SubmitWikiContributionResult(
                    existingId,
                    "NEW",
                    true,
                    "Đóng góp tương tự đã được gửi trước đó và đang chờ xử lý."
            );
            when(submitWikiContributionUseCase.execute(any())).thenReturn(result);

            mockMvc.perform(post("/wiki/articles/" + ARTICLE_ID + "/contributions")
                            .with(csrf())
                            .with(attachRequestIdentity(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "articleContentVersion": 1,
                                        "contextType": "GENERAL",
                                        "contributionType": "WORDING",
                                        "message": "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự."
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contributionId").value(existingId.toString()))
                    .andExpect(jsonPath("$.status").value("NEW"))
                    .andExpect(jsonPath("$.alreadySubmitted").value(true))
                    .andExpect(jsonPath("$.message").value("Đóng góp tương tự đã được gửi trước đó và đang chờ xử lý."));
        }
    }
}
