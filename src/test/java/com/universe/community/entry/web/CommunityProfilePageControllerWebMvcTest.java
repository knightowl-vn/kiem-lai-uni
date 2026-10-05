package com.universe.community.entry.web;

import com.universe.community.application.usecase.GetCommunityPublicProfileUseCase;
import com.universe.community.contracts.dto.CommunityAuthorProfileDTO;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.currentuser.CurrentUserView;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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

@WebMvcTest(controllers = CommunityProfilePageController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("CommunityProfilePageController WebMvc Real Spring Slice Tests")
class CommunityProfilePageControllerWebMvcTest {

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
    private GetCommunityPublicProfileUseCase getCommunityPublicProfileUseCase;

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

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community/@{publicHandle} as anonymous guest -> 200 OK with profile view")
    void shouldRenderProfilePageForAnonymousGuest() throws Exception {
        String handle = "linh_dao";
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        CommunityPostFeedItemDTO postItem = new CommunityPostFeedItemDTO(
                UUID.randomUUID(), authorId, "Linh Đạo", "linh_dao", "https://cdn.example.com/avatar.jpg", "Post 1",
                null, null, 0,
                3L, 1L, 4L, now, now
        );
        CommunityNewestFeedResponseDTO feedDTO = new CommunityNewestFeedResponseDTO(
                List.of(postItem), "next-cur", 20, true
        );
        CommunityAuthorProfileDTO profileDTO = new CommunityAuthorProfileDTO(
                handle,
                "Linh Đạo",
                "https://cdn.example.com/avatar.jpg",
                "Tiểu thuyết gia",
                feedDTO
        );

        when(getCommunityPublicProfileUseCase.execute(eq(handle))).thenReturn(Optional.of(profileDTO));

        mockMvc.perform(get("/community/@{publicHandle}", handle))
                .andExpect(status().isOk())
                .andExpect(view().name("community/profile"))
                .andExpect(model().attributeExists("profile"))
                .andExpect(model().attribute("profile", profileDTO))
                .andExpect(model().attribute("activeNav", "community"))
                .andExpect(model().attribute("returnTo", "/community/@" + handle))
                .andExpect(content().string(containsString("href=\"/login?returnTo=/community/@linh_dao\"")))
                .andExpect(content().string(not(containsString("name=\"current-user-id\""))))
                .andExpect(content().string(not(containsString("data-reaction-current"))))
                .andExpect(content().string(not(containsString("kl-reaction-widget"))))
                .andExpect(content().string(containsString("post-metric--login-link")))
                .andExpect(content().string(not(containsString("post-actions-dropdown"))))
                .andExpect(content().string(not(containsString("data-action=\"edit-post\""))));

        verify(getCommunityPublicProfileUseCase).execute(handle);
    }

    @Test
    @WithMockUser(username = "user@universe.com")
    @DisplayName("GET /community/@{publicHandle} as authenticated user -> 200 OK with profile view and owner edit affordance rendered")
    void shouldRenderProfilePageForAuthenticatedUser() throws Exception {
        String handle = "tien_nghich";
        UUID currentUserId = UUID.randomUUID();
        CurrentUserView currentUser = new CurrentUserView(
                currentUserId.toString(),
                "user@universe.com",
                "Tiên Nghịch",
                "https://cdn.example.com/me.png",
                "tien_nghich",
                "Bio",
                "ACTIVE",
                "USER",
                "LOCAL",
                Instant.now(),
                true
        );
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of("user@universe.com"));
        when(currentUserQueryPort.findByEmail("user@universe.com")).thenReturn(Optional.of(currentUser));

        UUID ownerPostId = UUID.randomUUID();
        UUID otherPostId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        CommunityPostFeedItemDTO ownerPost = new CommunityPostFeedItemDTO(
                ownerPostId, currentUserId, "Tiên Nghịch", "tien_nghich", null, "Owner post caption",
                null, null, 0,
                3L, 1L, 4L, now, now, "LIKE"
        );
        CommunityPostFeedItemDTO otherPost = new CommunityPostFeedItemDTO(
                otherPostId, UUID.randomUUID(), "Other Author", "other_author", null, "Other post caption",
                null, null, 0,
                1L, 0L, 1L, now, now, null
        );

        CommunityNewestFeedResponseDTO feedDTO = new CommunityNewestFeedResponseDTO(
                List.of(ownerPost, otherPost), null, 20, false
        );
        CommunityAuthorProfileDTO profileDTO = new CommunityAuthorProfileDTO(
                handle,
                "Tiên Nghịch",
                null,
                null,
                feedDTO
        );

        when(getCommunityPublicProfileUseCase.execute(eq(handle), eq(currentUserId))).thenReturn(Optional.of(profileDTO));

        mockMvc.perform(get("/community/@{publicHandle}", handle)
                        .with(authenticatedIdentity(currentUserId)))
                .andExpect(status().isOk())
                .andExpect(view().name("community/profile"))
                .andExpect(model().attribute("profile", profileDTO))
                .andExpect(model().attribute("activeNav", "community"))
                .andExpect(content().string(containsString("<meta name=\"current-user-id\" content=\"" + currentUserId + "\">")))
                .andExpect(content().string(containsString("data-current-user-id=\"" + currentUserId + "\"")))
                .andExpect(content().string(containsString("data-reaction-widget")))
                .andExpect(content().string(containsString("data-reaction-target-id=\"" + ownerPostId + "\"")))
                .andExpect(content().string(containsString("data-reaction-total=\"3\"")))
                .andExpect(content().string(containsString("data-reaction-current=\"LIKE\"")))
                .andExpect(content().string(containsString("data-action=\"edit-post\" data-post-id=\"" + ownerPostId + "\"")))
                .andExpect(content().string(containsString("Chỉnh sửa bài viết")))
                .andExpect(content().string(not(containsString("data-action=\"edit-post\" data-post-id=\"" + otherPostId + "\""))));

        verify(getCommunityPublicProfileUseCase).execute(handle, currentUserId);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community/@{publicHandle} for nonexistent handle -> 404 Not Found")
    void shouldReturn404WhenProfileNotFound() throws Exception {
        when(getCommunityPublicProfileUseCase.execute(eq("unknown_handle"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/community/@{publicHandle}", "unknown_handle"))
                .andExpect(status().isNotFound());

        verify(getCommunityPublicProfileUseCase).execute("unknown_handle");
    }

    private RequestPostProcessor authenticatedIdentity(UUID userId) {
        AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                userId,
                "user@universe.com",
                "Tiên Nghịch",
                "https://cdn.example.com/me.png",
                "tien_nghich",
                UserStatus.ACTIVE,
                UserRole.USER
        );
        return request -> {
            AuthenticatedRequestIdentityTestSupport.attach(request, identity);
            return request;
        };
    }
}
