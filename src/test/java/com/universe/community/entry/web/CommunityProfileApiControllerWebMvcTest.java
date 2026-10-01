package com.universe.community.entry.web;

import com.universe.community.application.usecase.GetCommunityPublicProfilePostsUseCase;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.domain.exception.CommunityPostValidationException;
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
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CommunityProfileApiController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("CommunityProfileApiController WebMvc Real Spring Slice Tests")
class CommunityProfileApiControllerWebMvcTest {

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
    private GetCommunityPublicProfilePostsUseCase getCommunityPublicProfilePostsUseCase;

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
    @DisplayName("GET /api/community/profiles/{publicHandle}/posts as anonymous guest -> 200 OK (permitAll)")
    void shouldAllowAnonymousGuestToReadProfilePosts() throws Exception {
        String handle = "linh_dao";
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        UUID imageId = UUID.randomUUID();
        CommunityPostFeedItemDTO item = new CommunityPostFeedItemDTO(
                postId, authorId, "Tác phẩm mới", imageId, "/media/assets/" + imageId + "/content", 0,
                15L, 3L, 18L, now, now
        );
        CommunityNewestFeedResponseDTO responseDTO = new CommunityNewestFeedResponseDTO(
                List.of(item), "next-cur", 20, true
        );

        when(getCommunityPublicProfilePostsUseCase.execute(eq(handle), eq(null), eq(null)))
                .thenReturn(Optional.of(responseDTO));

        mockMvc.perform(get("/api/community/profiles/{publicHandle}/posts", handle))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items[0].id").value(postId.toString()))
                .andExpect(jsonPath("$.items[0].caption").value("Tác phẩm mới"))
                .andExpect(jsonPath("$.items[0].imageUrl").value("/media/assets/" + imageId + "/content"))
                .andExpect(jsonPath("$.items[0].reactionCount").value(15))
                .andExpect(jsonPath("$.items[0].commentCount").value(3))
                .andExpect(jsonPath("$.items[0].engagementScore").value(18))
                .andExpect(jsonPath("$.nextCursor").value("next-cur"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.size").value(20));

        verify(getCommunityPublicProfilePostsUseCase).execute(handle, null, null);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/community/profiles/{publicHandle}/posts with cursor and size -> 200 OK with forwarded parameters")
    void shouldForwardPaginationParameters() throws Exception {
        String handle = "linh_dao";
        String cursor = "cursor-token-123";
        Integer size = 10;

        CommunityNewestFeedResponseDTO responseDTO = new CommunityNewestFeedResponseDTO(
                List.of(), null, 10, false
        );

        when(getCommunityPublicProfilePostsUseCase.execute(eq(handle), eq(cursor), eq(size)))
                .thenReturn(Optional.of(responseDTO));

        mockMvc.perform(get("/api/community/profiles/{publicHandle}/posts", handle)
                        .param("cursor", cursor)
                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.size").value(10));

        verify(getCommunityPublicProfilePostsUseCase).execute(handle, cursor, size);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/profiles/{publicHandle}/posts for nonexistent profile -> 404 Not Found")
    void shouldReturn404WhenProfileDoesNotExist() throws Exception {
        String handle = "ghost";

        when(getCommunityPublicProfilePostsUseCase.execute(eq(handle), eq(null), eq(null)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/community/profiles/{publicHandle}/posts", handle))
                .andExpect(status().isNotFound());

        verify(getCommunityPublicProfilePostsUseCase).execute(handle, null, null);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/profiles/{publicHandle}/posts with malformed cursor -> 400 Bad Request")
    void shouldReturn400WhenCursorIsMalformed() throws Exception {
        String handle = "linh_dao";
        String badCursor = "invalid-cursor";

        when(getCommunityPublicProfilePostsUseCase.execute(eq(handle), eq(badCursor), eq(null)))
                .thenThrow(new CommunityPostValidationException("Invalid cursor format."));

        mockMvc.perform(get("/api/community/profiles/{publicHandle}/posts", handle)
                        .param("cursor", badCursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid cursor format."));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/profiles/{publicHandle}/posts with invalid size -> 400 Bad Request")
    void shouldReturn400WhenSizeIsInvalid() throws Exception {
        String handle = "linh_dao";

        when(getCommunityPublicProfilePostsUseCase.execute(eq(handle), eq(null), eq(100)))
                .thenThrow(new CommunityPostValidationException("Size must be between 1 and 50."));

        mockMvc.perform(get("/api/community/profiles/{publicHandle}/posts", handle)
                        .param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Size must be between 1 and 50."));
    }
}
