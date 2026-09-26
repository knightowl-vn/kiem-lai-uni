package com.universe.interaction.entry;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.interaction.application.mutation.RemoveReactionUseCase;
import com.universe.interaction.application.mutation.SetReactionCommand;
import com.universe.interaction.application.mutation.SetReactionUseCase;
import com.universe.interaction.application.query.GetReactionSummaryUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.shared.security.AuthenticatedEmailResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicInteractionReactionController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-reaction-integration-test",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("PublicInteractionReactionController Security Integration Tests")
class PublicInteractionReactionControllerSecurityIntegrationTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

    @MockBean
    private AccountStatusFilter accountStatusFilter;

    @MockBean
    private CustomAuthenticationFailureHandler authenticationFailureHandler;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private GetReactionSummaryUseCase getReactionSummaryUseCase;

    @MockBean
    private SetReactionUseCase setReactionUseCase;

    @MockBean
    private RemoveReactionUseCase removeReactionUseCase;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
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
                            "user@universe.local",
                            "Active User",
                            null,
                            UserStatus.ACTIVE,
                            UserRole.USER
                    )
            );
            return request;
        };
    }

    private Map<ReactionType, Long> defaultCounts(long love, long fire, long haha, long sad) {
        Map<ReactionType, Long> counts = new EnumMap<>(ReactionType.class);
        counts.put(ReactionType.LOVE, love);
        counts.put(ReactionType.FIRE, fire);
        counts.put(ReactionType.HAHA, haha);
        counts.put(ReactionType.SAD, sad);
        return counts;
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user can access GET /api/interaction/reactions (permitAll)")
    void shouldAllowAnonymousAccessToGetReactions() throws Exception {
        ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
        ReactionSummary summary = ReactionSummary.of(
                target,
                defaultCounts(2, 0, 0, 0),
                null
        );

        when(getReactionSummaryUseCase.execute(eq(target), eq(null))).thenReturn(summary);

        mockMvc.perform(get("/api/interaction/reactions")
                        .param("targetType", "NOVEL_CHAPTER")
                        .param("targetId", TARGET_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetType").value("NOVEL_CHAPTER"))
                .andExpect(jsonPath("$.counts.LOVE").value(2))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.currentUserReaction").doesNotExist());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user is blocked when performing PUT /api/interaction/reactions (redirects to /login)")
    void shouldBlockAnonymousPutReactions() throws Exception {
        String requestJson = """
                {
                    "targetType": "NOVEL_CHAPTER",
                    "targetId": "%s",
                    "reactionType": "LOVE"
                }
                """.formatted(TARGET_ID);

        mockMvc.perform(put("/api/interaction/reactions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(setReactionUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Authenticated user with valid CSRF token can perform PUT /api/interaction/reactions")
    void shouldAllowAuthenticatedPutWithCsrf() throws Exception {
        ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
        Reaction reaction = Reaction.create(
                UUID.randomUUID(),
                USER_ID,
                target,
                ReactionType.LOVE,
                Instant.now()
        );

        when(setReactionUseCase.execute(any(SetReactionCommand.class))).thenReturn(reaction);

        ReactionSummary updatedSummary = ReactionSummary.of(
                target,
                defaultCounts(1, 0, 0, 0),
                ReactionType.LOVE
        );
        when(getReactionSummaryUseCase.execute(eq(target), eq(USER_ID))).thenReturn(updatedSummary);

        String requestJson = """
                {
                    "targetType": "NOVEL_CHAPTER",
                    "targetId": "%s",
                    "reactionType": "LOVE"
                }
                """.formatted(TARGET_ID);

        mockMvc.perform(put("/api/interaction/reactions")
                        .with(csrf())
                        .with(attachRequestIdentity(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetType").value("NOVEL_CHAPTER"))
                .andExpect(jsonPath("$.counts.LOVE").value(1))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.currentUserReaction").value("LOVE"));

        verify(setReactionUseCase).execute(any());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Authenticated user without CSRF token is rejected when performing PUT /api/interaction/reactions (redirects to /access-denied)")
    void shouldRejectAuthenticatedPutWithoutCsrf() throws Exception {
        String requestJson = """
                {
                    "targetType": "NOVEL_CHAPTER",
                    "targetId": "%s",
                    "reactionType": "LOVE"
                }
                """.formatted(TARGET_ID);

        mockMvc.perform(put("/api/interaction/reactions")
                        .with(attachRequestIdentity(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(setReactionUseCase, never()).execute(any());
    }
}
