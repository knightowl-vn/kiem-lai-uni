package com.universe.community.entry.web;

import com.universe.community.application.usecase.CreateCommunityPostWithImageUseCase;
import com.universe.community.application.usecase.DeleteCommunityPostUseCase;
import com.universe.community.application.usecase.GetCommunityNewestFeedUseCase;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.exception.CommunityPostUnauthorizedException;
import com.universe.community.domain.exception.CommunityPostValidationException;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CommunityPostController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("CommunityPostController WebMvc Real Spring Slice Integration Tests")
class CommunityPostControllerWebMvcTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID POST_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
    private CreateCommunityPostWithImageUseCase createCommunityPostWithImageUseCase;

    @MockBean
    private DeleteCommunityPostUseCase deleteCommunityPostUseCase;

    @MockBean
    private GetCommunityNewestFeedUseCase getCommunityNewestFeedUseCase;

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
                "author@universe.com",
                "Author User",
                "https://cdn.example.com/avatar.jpg",
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
    @DisplayName("Request missing identity accessor -> 401 Unauthorized")
    void shouldRejectUnauthenticatedRequestWith401() throws Exception {
        MockMultipartFile imagePart = new MockMultipartFile(
                "image",
                "test.jpg",
                "image/jpeg",
                "image-bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/community/posts")
                        .file(imagePart)
                        .param("caption", "Unauthenticated post")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("User must be authenticated to create a community post."));

        verify(createCommunityPostWithImageUseCase, never()).execute(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user request is redirected to login by security filter")
    void shouldRedirectAnonymousUserToLogin() throws Exception {
        mockMvc.perform(multipart("/api/community/posts")
                        .param("caption", "Anonymous post attempt")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(createCommunityPostWithImageUseCase, never()).execute(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    @WithMockUser
    @DisplayName("Authenticated user creating caption-only post -> 201 Created")
    void shouldCreateCaptionOnlyPostSuccessfully() throws Exception {
        String caption = "Caption-only post via Spring slice";
        UUID postId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-30T10:00:00Z");
        CommunityPost post = CommunityPost.create(postId, USER_ID, caption, null, createdAt);

        when(createCommunityPostWithImageUseCase.execute(
                eq(USER_ID),
                eq(caption),
                eq(null),
                eq(0L),
                eq(null),
                eq(null)
        )).thenReturn(post);

        mockMvc.perform(multipart("/api/community/posts")
                        .param("caption", caption)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(postId.toString()))
                .andExpect(jsonPath("$.authorUserId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.caption").value(caption))
                .andExpect(jsonPath("$.imageMediaAssetId").doesNotExist())
                .andExpect(jsonPath("$.imageUrl").doesNotExist())
                .andExpect(jsonPath("$.contentVersion").value(0));
    }

    @Test
    @WithMockUser
    @DisplayName("Authenticated user creating post with canonical image -> 201 Created")
    void shouldCreatePostWithImageSuccessfully() throws Exception {
        String caption = "Post with attached image";
        UUID postId = UUID.randomUUID();
        UUID imageAssetId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-30T10:00:00Z");
        CommunityPost post = CommunityPost.create(postId, USER_ID, caption, imageAssetId, createdAt);

        MockMultipartFile imagePart = new MockMultipartFile(
                "image",
                "scenery.jpg",
                "image/jpeg",
                "valid-jpeg-bytes".getBytes()
        );

        when(createCommunityPostWithImageUseCase.execute(
                eq(USER_ID),
                eq(caption),
                any(InputStream.class),
                eq((long) "valid-jpeg-bytes".getBytes().length),
                eq("image/jpeg"),
                eq("scenery.jpg")
        )).thenReturn(post);

        mockMvc.perform(multipart("/api/community/posts")
                        .file(imagePart)
                        .param("caption", caption)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(postId.toString()))
                .andExpect(jsonPath("$.authorUserId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.caption").value(caption))
                .andExpect(jsonPath("$.imageMediaAssetId").value(imageAssetId.toString()))
                .andExpect(jsonPath("$.imageUrl").value("/media/assets/" + imageAssetId + "/content"))
                .andExpect(jsonPath("$.contentVersion").value(0));
    }

    @Test
    @WithMockUser
    @DisplayName("Supplied 0-byte empty image -> 400 Bad Request")
    void shouldReturnBadRequestWhenImageIsEmpty() throws Exception {
        MockMultipartFile emptyImage = new MockMultipartFile(
                "image",
                "empty.jpg",
                "image/jpeg",
                new byte[0]
        );

        when(createCommunityPostWithImageUseCase.execute(
                eq(USER_ID),
                eq("Empty image caption"),
                any(InputStream.class),
                eq(0L),
                eq("image/jpeg"),
                eq("empty.jpg")
        )).thenThrow(new CommunityPostValidationException("Image file cannot be empty."));

        mockMvc.perform(multipart("/api/community/posts")
                        .file(emptyImage)
                        .param("caption", "Empty image caption")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Image file cannot be empty."));
    }

    @Test
    @WithMockUser
    @DisplayName("More than one image part -> 400 Bad Request")
    void shouldRejectMultipleImageParts() throws Exception {
        MockMultipartFile imagePart1 = new MockMultipartFile(
                "image",
                "first.jpg",
                "image/jpeg",
                "first-image-bytes".getBytes()
        );
        MockMultipartFile imagePart2 = new MockMultipartFile(
                "image",
                "second.png",
                "image/png",
                "second-image-bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/community/posts")
                        .file(imagePart1)
                        .file(imagePart2)
                        .param("caption", "Post with two images")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A community post can have at most one image attachment."));

        verify(createCommunityPostWithImageUseCase, never()).execute(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    @WithMockUser
    @DisplayName("Validation exception on post caption -> 400 Bad Request")
    void shouldReturnBadRequestOnValidationException() throws Exception {
        when(createCommunityPostWithImageUseCase.execute(any(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new CommunityPostValidationException("Post caption cannot be blank."));

        mockMvc.perform(multipart("/api/community/posts")
                        .param("caption", "   ")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Post caption cannot be blank."));
    }

    @Test
    @WithMockUser
    @DisplayName("Unauthorized exception -> 403 Forbidden")
    void shouldReturnForbiddenOnUnauthorizedException() throws Exception {
        when(createCommunityPostWithImageUseCase.execute(any(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new CommunityPostUnauthorizedException("Unauthorized action."));

        mockMvc.perform(multipart("/api/community/posts")
                        .param("caption", "caption")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Unauthorized action."));
    }

    @Test
    @WithMockUser
    @DisplayName("Illegal state exception -> 500 Internal Server Error")
    void shouldReturnInternalServerErrorOnIllegalStateException() throws Exception {
        when(createCommunityPostWithImageUseCase.execute(any(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("Failed to persist community post"));

        mockMvc.perform(multipart("/api/community/posts")
                        .param("caption", "caption")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Failed to persist community post"));
    }

    // =========================================================================
    // DELETE /api/community/posts/{postId} Slice Tests
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("DELETE /api/community/posts/{postId} without identity -> 401 Unauthorized")
    void shouldRejectUnauthenticatedDeleteWith401() throws Exception {
        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        verify(deleteCommunityPostUseCase, never()).execute(any(), any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("DELETE /api/community/posts/{postId} as anonymous -> 302 Redirection")
    void shouldRedirectAnonymousDeleteToLogin() throws Exception {
        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(deleteCommunityPostUseCase, never()).execute(any(), any());
    }

    @Test
    @WithMockUser
    @DisplayName("DELETE /api/community/posts/{postId} as owner -> 204 No Content")
    void shouldDeletePostSuccessfullyInSlice() throws Exception {
        doNothing().when(deleteCommunityPostUseCase).execute(USER_ID, POST_ID);

        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isNoContent());

        verify(deleteCommunityPostUseCase).execute(USER_ID, POST_ID);
    }

    @Test
    @WithMockUser
    @DisplayName("DELETE /api/community/posts/{postId} for missing post -> 404 Not Found")
    void shouldReturnNotFoundInSliceWhenPostMissing() throws Exception {
        doThrow(new CommunityPostNotFoundException(POST_ID))
                .when(deleteCommunityPostUseCase).execute(USER_ID, POST_ID);

        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Community post not found: " + POST_ID));
    }

    @Test
    @WithMockUser
    @DisplayName("DELETE /api/community/posts/{postId} as non-owner -> 403 Forbidden")
    void shouldReturnForbiddenInSliceWhenNonOwner() throws Exception {
        doThrow(new CommunityPostUnauthorizedException(USER_ID, POST_ID))
                .when(deleteCommunityPostUseCase).execute(USER_ID, POST_ID);

        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("User " + USER_ID + " is not authorized to modify post " + POST_ID));
    }

    @Test
    @WithMockUser
    @DisplayName("DELETE /api/community/posts/{postId} repeated delete in Spring slice: first -> 204, second -> 404")
    void shouldHandleRepeatedDeleteInWebMvcSlice() throws Exception {
        doNothing()
                .doThrow(new CommunityPostNotFoundException(POST_ID))
                .when(deleteCommunityPostUseCase).execute(USER_ID, POST_ID);

        // First attempt -> 204
        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isNoContent());

        // Second attempt -> 404
        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Community post not found: " + POST_ID));
    }

    // =========================================================================
    // GET /api/community/posts Slice & Security Tests
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/posts as anonymous guest -> 200 OK (permitAll)")
    void shouldAllowAnonymousGuestToReadFeed() throws Exception {
        UUID p1Id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");
        CommunityPostFeedItemDTO item = new CommunityPostFeedItemDTO(
                p1Id, USER_ID, "Guest feed post", null, null, 0,
                5L, 2L, 7L, now, now
        );
        CommunityNewestFeedResponseDTO responseDTO = new CommunityNewestFeedResponseDTO(
                List.of(item), "next-cursor", 20, true
        );

        when(getCommunityNewestFeedUseCase.execute(null, 20)).thenReturn(responseDTO);

        mockMvc.perform(get("/api/community/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(p1Id.toString()))
                .andExpect(jsonPath("$.items[0].caption").value("Guest feed post"))
                .andExpect(jsonPath("$.items[0].reactionCount").value(5))
                .andExpect(jsonPath("$.items[0].commentCount").value(2))
                .andExpect(jsonPath("$.items[0].engagementScore").value(7))
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.hasNext").value(true));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/community/posts as authenticated user -> 200 OK")
    void shouldAllowAuthenticatedUserToReadFeed() throws Exception {
        CommunityNewestFeedResponseDTO responseDTO = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );

        when(getCommunityNewestFeedUseCase.execute(null, 20)).thenReturn(responseDTO);

        mockMvc.perform(get("/api/community/posts")
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/posts with feed=NEWEST -> 200 OK")
    void shouldAcceptExplicitNewestFeedSelectorInSlice() throws Exception {
        CommunityNewestFeedResponseDTO responseDTO = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );

        when(getCommunityNewestFeedUseCase.execute(null, 20)).thenReturn(responseDTO);

        mockMvc.perform(get("/api/community/posts").param("feed", "NEWEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/posts with feed=newest (case-insensitive) -> 200 OK")
    void shouldAcceptCaseInsensitiveNewestFeedSelectorInSlice() throws Exception {
        CommunityNewestFeedResponseDTO responseDTO = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );

        when(getCommunityNewestFeedUseCase.execute(null, 20)).thenReturn(responseDTO);

        mockMvc.perform(get("/api/community/posts").param("feed", "newest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/posts with feed=FEATURED -> 400 Bad Request in B5.1")
    void shouldRejectFeaturedFeedWith400InSlice() throws Exception {
        mockMvc.perform(get("/api/community/posts").param("feed", "FEATURED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported feed selector: FEATURED"));

        verify(getCommunityNewestFeedUseCase, never()).execute(any(), any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/posts with feed=UNKNOWN -> 400 Bad Request")
    void shouldRejectUnknownFeedSelectorInSlice() throws Exception {
        mockMvc.perform(get("/api/community/posts").param("feed", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported feed selector: UNKNOWN"));

        verify(getCommunityNewestFeedUseCase, never()).execute(any(), any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/posts with malformed cursor -> 400 Bad Request")
    void shouldReturnBadRequestForMalformedCursorInSlice() throws Exception {
        when(getCommunityNewestFeedUseCase.execute(eq("bad-cursor"), eq(20)))
                .thenThrow(new CommunityPostValidationException("Invalid cursor format."));

        mockMvc.perform(get("/api/community/posts").param("cursor", "bad-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid cursor format."));
    }
}
