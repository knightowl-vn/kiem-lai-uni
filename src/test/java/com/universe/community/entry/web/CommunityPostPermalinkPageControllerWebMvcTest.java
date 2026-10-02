package com.universe.community.entry.web;

import com.universe.community.application.usecase.GetCommunityPostDetailUseCase;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.contracts.currentuser.CurrentUserView;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = CommunityPostPermalinkPageController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("CommunityPostPermalinkPageController WebMvc Real Spring Slice Tests")
class CommunityPostPermalinkPageControllerWebMvcTest {

    private static final UUID POST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID AUTHOR_ID = UUID.fromString("660e8400-e29b-41d4-a716-446655440001");
    private static final UUID VIEWER_ID = UUID.fromString("770e8400-e29b-41d4-a716-446655440002");

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
    private GetCommunityPostDetailUseCase getCommunityPostDetailUseCase;

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

    private CommunityPostFeedItemDTO samplePost(String reaction) {
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        return new CommunityPostFeedItemDTO(
                POST_ID,
                AUTHOR_ID,
                "Tiêu Viêm",
                "tieu_viem",
                "/avatars/tv.png",
                "Chào mừng đến với Già Nam Viện!",
                null,
                "/media/assets/img-1/content",
                0,
                15L,
                8L,
                23L,
                now,
                now,
                reaction
        );
    }

    private RequestPostProcessor authenticatedIdentity(UUID userId, String email, String displayName) {
        AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                userId,
                email,
                displayName,
                "https://cdn.example.com/avatar.png",
                UserStatus.ACTIVE,
                UserRole.USER
        );
        return request -> {
            AuthenticatedRequestIdentityTestSupport.attach(request, identity);
            return request;
        };
    }

    @Test
    @WithAnonymousUser
    @DisplayName("1. Anonymous GET existing post -> 200, view community/post, model item, returnTo=/community/posts/{postId}")
    void anonymousGetExistingPostReturns200WithModel() throws Exception {
        when(getCommunityPostDetailUseCase.execute(eq(POST_ID))).thenReturn(Optional.of(samplePost(null)));

        mockMvc.perform(get("/community/posts/{postId}", POST_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("community/post"))
                .andExpect(model().attributeExists("item"))
                .andExpect(model().attribute("returnTo", "/community/posts/" + POST_ID))
                .andExpect(model().attribute("activeNav", "community"))
                .andExpect(content().string(containsString("Chào mừng đến với Già Nam Viện!")))
                .andExpect(content().string(containsString("href=\"/login?returnTo=/community/posts/" + POST_ID + "\"")))
                .andExpect(content().string(not(containsString("name=\"current-user-id\""))))
                .andExpect(content().string(not(containsString("kl-reaction-widget"))));

        verify(getCommunityPostDetailUseCase).execute(POST_ID);
    }

    @Test
    @WithMockUser(username = "viewer@example.com")
    @DisplayName("2. Authenticated GET existing post -> 200, passes viewerUserId, includes reaction widget")
    void authenticatedGetExistingPostPassesViewerUserId() throws Exception {
        CurrentUserView viewer = new CurrentUserView(
                VIEWER_ID.toString(),
                "viewer@example.com",
                "Hàn Lập",
                "https://cdn.example.com/avatar.png",
                "Bio",
                "ACTIVE",
                "USER",
                "LOCAL",
                Instant.now(),
                true
        );
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of("viewer@example.com"));
        when(currentUserQueryPort.findByEmail("viewer@example.com")).thenReturn(Optional.of(viewer));

        when(getCommunityPostDetailUseCase.execute(eq(POST_ID), eq(VIEWER_ID)))
                .thenReturn(Optional.of(samplePost("LIKE")));

        mockMvc.perform(get("/community/posts/{postId}", POST_ID)
                        .with(authenticatedIdentity(VIEWER_ID, "viewer@example.com", "Hàn Lập")))
                .andExpect(status().isOk())
                .andExpect(view().name("community/post"))
                .andExpect(model().attributeExists("item"))
                .andExpect(model().attribute("returnTo", "/community/posts/" + POST_ID))
                .andExpect(content().string(containsString("<meta name=\"current-user-id\" content=\"" + VIEWER_ID + "\">")))
                .andExpect(content().string(containsString("data-reaction-current=\"LIKE\"")))
                .andExpect(content().string(containsString("kl-reaction-widget")))
                .andExpect(content().string(not(containsString("data-action=\"edit-post\""))));

        verify(getCommunityPostDetailUseCase).execute(POST_ID, VIEWER_ID);
    }

    @Test
    @WithMockUser(username = "author@example.com")
    @DisplayName("3. Owner authenticated GET -> renders owner dropdown with edit and delete actions")
    void ownerAuthenticatedGetRendersOwnerActions() throws Exception {
        CurrentUserView owner = new CurrentUserView(
                AUTHOR_ID.toString(),
                "author@example.com",
                "Tiêu Viêm",
                "https://cdn.example.com/avatar.png",
                "Bio",
                "ACTIVE",
                "USER",
                "LOCAL",
                Instant.now(),
                true
        );
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of("author@example.com"));
        when(currentUserQueryPort.findByEmail("author@example.com")).thenReturn(Optional.of(owner));

        when(getCommunityPostDetailUseCase.execute(eq(POST_ID), eq(AUTHOR_ID)))
                .thenReturn(Optional.of(samplePost(null)));

        mockMvc.perform(get("/community/posts/{postId}", POST_ID)
                        .with(authenticatedIdentity(AUTHOR_ID, "author@example.com", "Tiêu Viêm")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("post-actions-dropdown")))
                .andExpect(content().string(containsString("data-action=\"edit-post\" data-post-id=\"" + POST_ID + "\"")))
                .andExpect(content().string(containsString("data-action=\"delete-post\" data-post-id=\"" + POST_ID + "\"")));

        verify(getCommunityPostDetailUseCase).execute(POST_ID, AUTHOR_ID);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("4. Missing UUID returns 404 NOT_FOUND")
    void missingUuidReturns404() throws Exception {
        when(getCommunityPostDetailUseCase.execute(eq(POST_ID))).thenReturn(Optional.empty());

        mockMvc.perform(get("/community/posts/{postId}", POST_ID))
                .andExpect(status().isNotFound());

        verify(getCommunityPostDetailUseCase).execute(POST_ID);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("5. Malformed UUID yields 400 Bad Request")
    void malformedUuidReturns400() throws Exception {
        mockMvc.perform(get("/community/posts/not-a-valid-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("6. Public security -> anonymous request succeeds without redirect to /login")
    void publicSecurityAllowsAnonymousAccess() throws Exception {
        when(getCommunityPostDetailUseCase.execute(eq(POST_ID))).thenReturn(Optional.of(samplePost(null)));

        mockMvc.perform(get("/community/posts/{postId}", POST_ID))
                .andExpect(status().isOk());
    }
}
