package com.universe.community.entry.web;

import com.universe.community.application.usecase.GetCommunityFeaturedFeedUseCase;
import com.universe.community.application.usecase.GetCommunityNewestFeedUseCase;
import com.universe.community.contracts.dto.CommunityFeaturedFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.contracts.currentuser.CurrentUserView;
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

@WebMvcTest(controllers = CommunityFeedPageController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("CommunityFeedPageController WebMvc Real Spring Slice Tests")
class CommunityFeedPageControllerWebMvcTest {

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
    private GetCommunityNewestFeedUseCase getCommunityNewestFeedUseCase;

    @MockBean
    private GetCommunityFeaturedFeedUseCase getCommunityFeaturedFeedUseCase;

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
    @DisplayName("GET /community as anonymous guest -> 200 OK with default NEWEST feed and activeNav=community")
    void shouldRenderCommunityFeedPageForAnonymousGuest() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        CommunityPostFeedItemDTO item = new CommunityPostFeedItemDTO(
                postId, authorId, "Tác giả", "tac_gia", null, "Hello Community",
                null, null, 0,
                5L, 2L, 7L, now, now
        );
        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(item), "next-cursor-token", 20, true
        );

        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20))).thenReturn(newestFeed);

        mockMvc.perform(get("/community"))
                .andExpect(status().isOk())
                .andExpect(view().name("community/index"))
                .andExpect(model().attribute("selectedFeed", "NEWEST"))
                .andExpect(model().attribute("activeNav", "community"))
                .andExpect(model().attribute("items", List.of(item)))
                .andExpect(model().attribute("nextCursor", "next-cursor-token"))
                .andExpect(model().attribute("hasNext", true));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithMockUser(username = "user@universe.com")
    @DisplayName("GET /community as authenticated user -> 200 OK with currentUser populated")
    void shouldRenderCommunityFeedPageForAuthenticatedUser() throws Exception {
        CurrentUserView currentUser = new CurrentUserView(
                UUID.randomUUID().toString(),
                "user@universe.com",
                "Đạo Hữu",
                "https://cdn.example.com/me.png",
                "Bio",
                "ACTIVE",
                "USER",
                "LOCAL",
                Instant.now(),
                true
        );
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of("user@universe.com"));
        when(currentUserQueryPort.findByEmail("user@universe.com")).thenReturn(Optional.of(currentUser));

        CommunityNewestFeedResponseDTO emptyFeed = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );
        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20))).thenReturn(emptyFeed);

        mockMvc.perform(get("/community"))
                .andExpect(status().isOk())
                .andExpect(view().name("community/index"))
                .andExpect(model().attribute("currentUser", currentUser))
                .andExpect(model().attribute("selectedFeed", "NEWEST"))
                .andExpect(model().attribute("activeNav", "community"))
                .andExpect(model().attribute("items", List.of()))
                .andExpect(model().attribute("hasNext", false));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=FEATURED -> 200 OK with FEATURED feed and page metadata")
    void shouldRenderFeaturedFeed() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        CommunityPostFeedItemDTO item = new CommunityPostFeedItemDTO(
                postId, authorId, "Bình An", "binh_an", "https://cdn.example.com/ba.jpg", "Top Featured Post",
                null, null, 0,
                50L, 20L, 70L, now, now
        );
        CommunityFeaturedFeedResponseDTO featuredFeed = new CommunityFeaturedFeedResponseDTO(
                List.of(item), 0, 20, 25L, 2, true
        );

        when(getCommunityFeaturedFeedUseCase.execute(eq(0), eq(20))).thenReturn(featuredFeed);

        mockMvc.perform(get("/community").param("feed", "FEATURED"))
                .andExpect(status().isOk())
                .andExpect(view().name("community/index"))
                .andExpect(model().attribute("selectedFeed", "FEATURED"))
                .andExpect(model().attribute("activeNav", "community"))
                .andExpect(model().attribute("items", List.of(item)))
                .andExpect(model().attribute("nextPage", 1))
                .andExpect(model().attribute("hasNext", true));

        verify(getCommunityFeaturedFeedUseCase).execute(0, 20);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=featured (case-insensitive) & page=1 & size=10 -> 200 OK")
    void shouldAcceptCaseInsensitiveFeaturedFeedWithCustomPageAndSize() throws Exception {
        CommunityFeaturedFeedResponseDTO featuredFeed = new CommunityFeaturedFeedResponseDTO(
                List.of(), 1, 10, 5L, 1, false
        );
        when(getCommunityFeaturedFeedUseCase.execute(eq(1), eq(10))).thenReturn(featuredFeed);

        mockMvc.perform(get("/community")
                        .param("feed", "featured")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedFeed", "FEATURED"))
                .andExpect(model().attribute("nextPage", org.hamcrest.Matchers.nullValue()))
                .andExpect(model().attribute("hasNext", false));

        verify(getCommunityFeaturedFeedUseCase).execute(1, 10);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=NEWEST&cursor=xxx&size=10 -> 200 OK")
    void shouldAcceptNewestFeedWithCustomCursorAndSize() throws Exception {
        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(), "next-token", 10, true
        );
        when(getCommunityNewestFeedUseCase.execute(eq("custom-cursor"), eq(10))).thenReturn(newestFeed);

        mockMvc.perform(get("/community")
                        .param("feed", "NEWEST")
                        .param("cursor", "custom-cursor")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedFeed", "NEWEST"))
                .andExpect(model().attribute("nextCursor", "next-token"));

        verify(getCommunityNewestFeedUseCase).execute("custom-cursor", 10);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=FEATURED&cursor=xxx -> 400 Bad Request")
    void shouldRejectCursorOnFeaturedFeed() throws Exception {
        mockMvc.perform(get("/community")
                        .param("feed", "FEATURED")
                        .param("cursor", "some-cursor"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=NEWEST&page=1 -> 400 Bad Request")
    void shouldRejectPageOnNewestFeed() throws Exception {
        mockMvc.perform(get("/community")
                        .param("feed", "NEWEST")
                        .param("page", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=INVALID -> 400 Bad Request")
    void shouldRejectInvalidFeedSelector() throws Exception {
        mockMvc.perform(get("/community").param("feed", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=not-a-feed -> 400 Bad Request")
    void shouldRejectNotAFeedSelector() throws Exception {
        mockMvc.perform(get("/community").param("feed", "not-a-feed"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=newest (lowercase) -> 200 OK with NEWEST feed")
    void shouldAcceptCaseInsensitiveNewestFeed() throws Exception {
        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );
        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20))).thenReturn(newestFeed);

        mockMvc.perform(get("/community").param("feed", "newest"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedFeed", "NEWEST"));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=   (blank) -> 200 OK defaulting to NEWEST feed")
    void shouldAcceptBlankFeedParamAsNewest() throws Exception {
        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );
        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20))).thenReturn(newestFeed);

        mockMvc.perform(get("/community").param("feed", "   "))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedFeed", "NEWEST"));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }
}
