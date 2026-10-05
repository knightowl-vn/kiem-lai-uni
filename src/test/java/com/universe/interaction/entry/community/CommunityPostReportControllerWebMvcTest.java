package com.universe.interaction.entry.community;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.exceptions.DuplicatePendingReportException;
import com.universe.interaction.application.exceptions.SelfReportNotAllowedException;
import com.universe.interaction.application.mutation.SubmitInteractionReportCommand;
import com.universe.interaction.application.mutation.SubmitInteractionReportUseCase;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;
import com.universe.shared.security.AuthenticatedEmailResolver;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CommunityPostReportController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("CommunityPostReportController WebMvc Integration Tests")
class CommunityPostReportControllerWebMvcTest {

    private static final UUID POST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REPORTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AUTHOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REPORT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

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
    private SubmitInteractionReportUseCase submitInteractionReportUseCase;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            ServletRequest req = invocation.getArgument(0);
            ServletResponse res = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(accountStatusFilter).doFilter(any(), any(), any());
    }

    private RequestPostProcessor authenticatedUser(UUID userId) {
        return request -> {
            AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                    userId,
                    "reporter@kiemlai.test",
                    "Reporter",
                    null,
                    "reporter_handle",
                    UserStatus.ACTIVE,
                    UserRole.USER
            );
            AuthenticatedRequestIdentityTestSupport.attach(request, identity);
            return request;
        };
    }

    @Test
    @WithMockUser(username = "reporter@kiemlai.test", roles = "USER")
    @DisplayName("POST /api/community/posts/{postId}/reports succeeds with 201 Created for authenticated non-owner")
    void shouldSubmitReportSuccessfullyWhenAuthenticatedNonOwner() throws Exception {
        InteractionReport report = InteractionReport.createPending(
                REPORT_ID,
                ReportTargetType.COMMUNITY_POST,
                POST_ID,
                REPORTER_ID,
                ReportReason.SPAM,
                "Commercial spam link",
                "Original post caption",
                null,
                NOW
        );
        when(submitInteractionReportUseCase.execute(any(SubmitInteractionReportCommand.class)))
                .thenReturn(report);

        mockMvc.perform(post("/api/community/posts/{postId}/reports", POST_ID)
                        .with(authenticatedUser(REPORTER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM",
                                    "description": "Commercial spam link"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").value(REPORT_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        ArgumentCaptor<SubmitInteractionReportCommand> captor = ArgumentCaptor.forClass(SubmitInteractionReportCommand.class);
        verify(submitInteractionReportUseCase).execute(captor.capture());
        assertThat(captor.getValue().targetType()).isEqualTo(ReportTargetType.COMMUNITY_POST);
        assertThat(captor.getValue().targetId()).isEqualTo(POST_ID);
        assertThat(captor.getValue().reporterUserId()).isEqualTo(REPORTER_ID);
        assertThat(captor.getValue().reason()).isEqualTo(ReportReason.SPAM);
        assertThat(captor.getValue().description()).isEqualTo("Commercial spam link");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("POST /api/community/posts/{postId}/reports rejects unauthenticated guest requests")
    void shouldRejectSubmissionWhenUnauthenticatedGuest() throws Exception {
        mockMvc.perform(post("/api/community/posts/{postId}/reports", POST_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM",
                                    "description": "Guest spam report"
                                }
                                """))
                .andExpect(status().is3xxRedirection()); // Redirects to /login by Spring Security

        verify(submitInteractionReportUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "author@kiemlai.test", roles = "USER")
    @DisplayName("POST /api/community/posts/{postId}/reports returns 403 Forbidden when author self-reports")
    void shouldReturnForbiddenWhenAuthorReportsOwnPost() throws Exception {
        when(submitInteractionReportUseCase.execute(any(SubmitInteractionReportCommand.class)))
                .thenThrow(new SelfReportNotAllowedException(ReportTargetType.COMMUNITY_POST, POST_ID, AUTHOR_ID));

        mockMvc.perform(post("/api/community/posts/{postId}/reports", POST_ID)
                        .with(authenticatedUser(AUTHOR_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "HARASSMENT",
                                    "description": "Self report"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot report their own")));
    }

    @Test
    @WithMockUser(username = "reporter@kiemlai.test", roles = "USER")
    @DisplayName("POST /api/community/posts/{postId}/reports returns 404 Not Found when post is missing or ineligible")
    void shouldReturnNotFoundWhenTargetPostNotEligibleOrMissing() throws Exception {
        when(submitInteractionReportUseCase.execute(any(SubmitInteractionReportCommand.class)))
                .thenThrow(new CommentTargetNotEligibleException(CommentTarget.communityPost(POST_ID)));

        mockMvc.perform(post("/api/community/posts/{postId}/reports", POST_ID)
                        .with(authenticatedUser(REPORTER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM",
                                    "description": "Report on missing post"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not eligible")));
    }

    @Test
    @WithMockUser(username = "reporter@kiemlai.test", roles = "USER")
    @DisplayName("POST /api/community/posts/{postId}/reports returns 409 Conflict on duplicate pending report")
    void shouldReturnConflictWhenPendingReportAlreadyExists() throws Exception {
        when(submitInteractionReportUseCase.execute(any(SubmitInteractionReportCommand.class)))
                .thenThrow(new DuplicatePendingReportException(ReportTargetType.COMMUNITY_POST, POST_ID, REPORTER_ID));

        mockMvc.perform(post("/api/community/posts/{postId}/reports", POST_ID)
                        .with(authenticatedUser(REPORTER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM",
                                    "description": "Duplicate report"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("A pending report already exists")));
    }

    @Test
    @WithMockUser(username = "reporter@kiemlai.test", roles = "USER")
    @DisplayName("POST /api/community/posts/{postId}/reports returns 400 Bad Request when reason is missing")
    void shouldReturnBadRequestWhenReasonIsNull() throws Exception {
        mockMvc.perform(post("/api/community/posts/{postId}/reports", POST_ID)
                        .with(authenticatedUser(REPORTER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": null,
                                    "description": "No reason supplied"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(submitInteractionReportUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "reporter@kiemlai.test", roles = "USER")
    @DisplayName("POST /api/community/posts/{postId}/reports returns 400 Bad Request when body is malformed JSON")
    void shouldReturnBadRequestWhenBodyIsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/community/posts/{postId}/reports", POST_ID)
                        .with(authenticatedUser(REPORTER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid json"))
                .andExpect(status().isBadRequest());

        verify(submitInteractionReportUseCase, never()).execute(any());
    }
}
