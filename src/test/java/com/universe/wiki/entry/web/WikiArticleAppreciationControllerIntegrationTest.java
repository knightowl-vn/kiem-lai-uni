package com.universe.wiki.entry.web;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.shared.security.AuthenticatedEmailResolver;
import com.universe.wiki.application.appreciation.SetWikiAppreciationCommand;
import com.universe.wiki.application.appreciation.SetWikiAppreciationResult;
import com.universe.wiki.application.appreciation.SetWikiAppreciationUseCase;
import com.universe.wiki.application.exceptions.WikiAppreciationTargetNotFoundException;
import com.universe.wiki.domain.appreciation.WikiAppreciationScore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WikiArticleAppreciationController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("WikiArticleAppreciationController Integration Tests")
class WikiArticleAppreciationControllerIntegrationTest {

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
    private SetWikiAppreciationUseCase setWikiAppreciationUseCase;

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

    private RequestPostProcessor authenticatedIdentity(UUID userId) {
        AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                userId,
                USER_EMAIL,
                "Reader User",
                null,
                "reader_user",
                UserStatus.ACTIVE,
                UserRole.USER
        );
        return request -> {
            AuthenticatedRequestIdentityTestSupport.attach(request, identity);
            return request;
        };
    }

    @Test
    @WithMockUser
    @DisplayName("Người dùng đã xác thực gửi kèm CSRF hợp lệ -> 200 OK")
    void shouldSetAppreciationSuccessfullyWithCsrf() throws Exception {
        SetWikiAppreciationResult result = new SetWikiAppreciationResult(
                ARTICLE_ID,
                WikiAppreciationScore.fromStars(new BigDecimal("4.5")),
                new BigDecimal("4.80"),
                10L,
                true
        );
        when(setWikiAppreciationUseCase.execute(any(SetWikiAppreciationCommand.class))).thenReturn(result);

        mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 4.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wikiArticleId").value(ARTICLE_ID.toString()))
                .andExpect(jsonPath("$.value").value(4.5))
                .andExpect(jsonPath("$.average").value(4.80))
                .andExpect(jsonPath("$.displayAverage").value("4.8"))
                .andExpect(jsonPath("$.count").value(10))
                .andExpect(jsonPath("$.changed").value(true));

        ArgumentCaptor<SetWikiAppreciationCommand> commandCaptor =
                ArgumentCaptor.forClass(SetWikiAppreciationCommand.class);
        verify(setWikiAppreciationUseCase).execute(commandCaptor.capture());
        assertThat(commandCaptor.getValue().wikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(commandCaptor.getValue().actorUserId()).isEqualTo(USER_ID);
        assertThat(commandCaptor.getValue().score()).isEqualTo(WikiAppreciationScore.fromStars(new BigDecimal("4.5")));
    }

    @Test
    @WithMockUser
    @DisplayName("Từ chối khi thiếu CSRF token (Spring Security AccessDeniedHandler điều hướng sang /access-denied)")
    void shouldRejectAppreciationWhenCsrfMissing() throws Exception {
        mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                        .with(authenticatedIdentity(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 5}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/access-denied"));

        verify(setWikiAppreciationUseCase, never()).execute(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Người dùng ẩn danh bị chặn khi cố gắng gọi mutation (redirect hoặc unauthorized)")
    void shouldRejectAnonymousMutation() throws Exception {
        mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 5}"))
                .andExpect(status().is3xxRedirection());

        verify(setWikiAppreciationUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Bài viết không đủ điều kiện trả về 404 Not Found")
    void shouldReturn404WhenArticleIneligible() throws Exception {
        when(setWikiAppreciationUseCase.execute(any(SetWikiAppreciationCommand.class)))
                .thenThrow(new WikiAppreciationTargetNotFoundException(ARTICLE_ID));

        mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 5}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Điểm đánh giá số thập phân không theo bước 0.5 (2.7) -> 400 Bad Request và không gọi use case")
    void shouldRejectInvalidStepValueWithBadRequest() throws Exception {
        mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/appreciation")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 2.7}"))
                .andExpect(status().isBadRequest());

        verify(setWikiAppreciationUseCase, never()).execute(any());
    }
}
