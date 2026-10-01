package com.universe.community.entry.web;

import com.universe.community.application.usecase.GetCommunityPublicProfileUseCase;
import com.universe.community.contracts.dto.CommunityAuthorProfileDTO;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                .andExpect(model().attribute("activeNav", "community"));

        verify(getCommunityPublicProfileUseCase).execute(handle);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /community/@{publicHandle} as authenticated user -> 200 OK with profile view")
    void shouldRenderProfilePageForAuthenticatedUser() throws Exception {
        String handle = "tien_nghich";
        CommunityNewestFeedResponseDTO feedDTO = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );
        CommunityAuthorProfileDTO profileDTO = new CommunityAuthorProfileDTO(
                handle,
                "Tiên Nghịch",
                null,
                null,
                feedDTO
        );

        when(getCommunityPublicProfileUseCase.execute(eq(handle))).thenReturn(Optional.of(profileDTO));

        mockMvc.perform(get("/community/@{publicHandle}", handle))
                .andExpect(status().isOk())
                .andExpect(view().name("community/profile"))
                .andExpect(model().attribute("profile", profileDTO))
                .andExpect(model().attribute("activeNav", "community"));

        verify(getCommunityPublicProfileUseCase).execute(handle);
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
}
