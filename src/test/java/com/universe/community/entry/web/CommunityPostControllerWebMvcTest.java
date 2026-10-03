package com.universe.community.entry.web;

import com.universe.community.application.command.EditCommunityPostCaptionCommand;
import com.universe.community.application.usecase.CreateCommunityPostWithImageUseCase;
import com.universe.community.application.usecase.DeleteCommunityPostUseCase;
import com.universe.community.application.usecase.EditCommunityPostCaptionUseCase;
import com.universe.community.application.usecase.GetCommunityFeaturedFeedUseCase;
import com.universe.community.application.usecase.GetCommunityNewestFeedUseCase;
import com.universe.community.application.usecase.GetCommunityPostRevisionsUseCase;
import com.universe.community.contracts.dto.CommunityFeaturedFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.contracts.dto.CommunityPostRevisionPublicDTO;
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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import static org.mockito.ArgumentMatchers.argThat;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
    private EditCommunityPostCaptionUseCase editCommunityPostCaptionUseCase;

    @MockBean
    private GetCommunityNewestFeedUseCase getCommunityNewestFeedUseCase;

    @MockBean
    private GetCommunityFeaturedFeedUseCase getCommunityFeaturedFeedUseCase;

    @MockBean
    private GetCommunityPostRevisionsUseCase getCommunityPostRevisionsUseCase;

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
                "author_user",
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

    @Test
    @WithMockUser
    @DisplayName("DELETE /api/community/posts/{postId} without CSRF -> rejected by Spring Security (redirected to /access-denied)")
    void shouldRejectDeleteWhenCsrfMissingInSlice() throws Exception {
        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(deleteCommunityPostUseCase, never()).execute(any(), any());
    }

    // =========================================================================
    // PATCH /api/community/posts/{postId} Slice Tests
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("PATCH /api/community/posts/{postId} 1. owner + valid caption -> 200 OK & 2. response uses CommunityPostPublicDTO contract")
    void shouldEditCaptionSuccessfullyInSlice() throws Exception {
        String newCaption = "Updated caption through WebMvc slice";
        Instant createdAt = Instant.parse("2026-09-30T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-09-30T11:00:00Z");
        CommunityPost post = CommunityPost.rehydrate(POST_ID, USER_ID, newCaption, null, 1, createdAt, updatedAt);

        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class))).thenReturn(post);

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"" + newCaption + "\"}")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(POST_ID.toString()))
                .andExpect(jsonPath("$.authorUserId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.caption").value(newCaption))
                .andExpect(jsonPath("$.imageMediaAssetId").doesNotExist())
                .andExpect(jsonPath("$.imageUrl").doesNotExist())
                .andExpect(jsonPath("$.contentVersion").value(1))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        verify(editCommunityPostCaptionUseCase).execute(any(EditCommunityPostCaptionCommand.class));
    }

    @Test
    @WithMockUser
    @DisplayName("PATCH /api/community/posts/{postId} 3. raw caption sent to use case correctly")
    void shouldSendRawCaptionToUseCaseWithoutClientNormalization() throws Exception {
        String rawCaption = "  Raw caption with leading and trailing spaces  ";
        Instant createdAt = Instant.parse("2026-09-30T10:00:00Z");
        CommunityPost post = CommunityPost.create(POST_ID, USER_ID, "Raw caption with leading and trailing spaces", null, createdAt);

        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class))).thenReturn(post);

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"" + rawCaption + "\"}")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isOk());

        verify(editCommunityPostCaptionUseCase).execute(argThat(cmd ->
                cmd.postId().equals(POST_ID)
                        && cmd.actorUserId().equals(USER_ID)
                        && cmd.newCaption().equals(rawCaption)
        ));
    }

    @Test
    @WithMockUser
    @DisplayName("PATCH /api/community/posts/{postId} 4. blank caption -> 400 Bad Request")
    void shouldReturnBadRequestWhenCaptionIsBlankInSlice() throws Exception {
        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class)))
                .thenThrow(new CommunityPostValidationException("Post caption cannot be blank."));

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"   \"}")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Post caption cannot be blank."));
    }

    @Test
    @WithMockUser
    @DisplayName("PATCH /api/community/posts/{postId} 5. caption > 2000 chars -> 400 Bad Request")
    void shouldReturnBadRequestWhenCaptionExceeds2000CharsInSlice() throws Exception {
        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class)))
                .thenThrow(new CommunityPostValidationException("Post caption length (2001) exceeds maximum limit of 2000 characters."));

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"" + "a".repeat(2001) + "\"}")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Post caption length (2001) exceeds maximum limit of 2000 characters."));
    }

    @Test
    @WithMockUser
    @DisplayName("PATCH /api/community/posts/{postId} 6a. guest with missing identity accessor -> 401 Unauthorized")
    void shouldRejectEditWhenUnauthenticatedInSlice() throws Exception {
        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"Valid caption\"}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        verify(editCommunityPostCaptionUseCase, never()).execute(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("PATCH /api/community/posts/{postId} 6b. anonymous user -> 302 Redirection to login")
    void shouldRedirectAnonymousEditToLoginInSlice() throws Exception {
        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"Valid caption\"}")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(editCommunityPostCaptionUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("PATCH /api/community/posts/{postId} 7. non-owner -> 403 Forbidden")
    void shouldReturnForbiddenWhenNonOwnerEditsPostInSlice() throws Exception {
        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class)))
                .thenThrow(new CommunityPostUnauthorizedException(USER_ID, POST_ID));

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"Valid caption\"}")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("User " + USER_ID + " is not authorized to modify post " + POST_ID));
    }

    @Test
    @WithMockUser
    @DisplayName("PATCH /api/community/posts/{postId} 8. missing post -> 404 Not Found")
    void shouldReturnNotFoundWhenPostMissingInSlice() throws Exception {
        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class)))
                .thenThrow(new CommunityPostNotFoundException(POST_ID));

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"Valid caption\"}")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Community post not found: " + POST_ID));
    }

    @Test
    @WithMockUser
    @DisplayName("PATCH /api/community/posts/{postId} 9. CSRF missing -> rejected by Spring Security (redirected to /access-denied)")
    void shouldRejectEditWhenCsrfMissingInSlice() throws Exception {
        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"Valid caption\"}")
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(editCommunityPostCaptionUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("PATCH /api/community/posts/{postId} 10. normalized-identical/no-op still returns 200 through existing use case")
    void shouldReturnOkOnNoOpEditInSlice() throws Exception {
        String caption = "Identical normalized caption";
        Instant createdAt = Instant.parse("2026-09-30T10:00:00Z");
        CommunityPost post = CommunityPost.create(POST_ID, USER_ID, caption, null, createdAt);

        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class)))
                .thenReturn(post);

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"" + caption + "\"}")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caption").value(caption))
                .andExpect(jsonPath("$.contentVersion").value(0));
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
                p1Id, USER_ID, "Author User", "author_user", "https://cdn.example.com/avatar.jpg", "Guest feed post",
                null, null, 0,
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
                .andExpect(jsonPath("$.items[0].currentUserReaction").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.hasNext").value(true));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/community/posts as authenticated user -> 200 OK")
    void shouldAllowAuthenticatedUserToReadFeed() throws Exception {
        UUID postId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");
        CommunityPostFeedItemDTO reactedItem = new CommunityPostFeedItemDTO(
                postId, USER_ID, "Author", "author", null, "Caption",
                null, null, 0,
                1L, 0L, 1L, now, now, "LIKE"
        );
        CommunityNewestFeedResponseDTO responseDTO = new CommunityNewestFeedResponseDTO(
                List.of(reactedItem), null, 20, false
        );

        when(getCommunityNewestFeedUseCase.execute(null, 20, USER_ID)).thenReturn(responseDTO);

        mockMvc.perform(get("/api/community/posts")
                        .with(authenticatedIdentity(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(jsonPath("$.items[0].currentUserReaction").value("LIKE"))
                .andExpect(jsonPath("$.hasNext").value(false));

        verify(getCommunityNewestFeedUseCase).execute(null, 20, USER_ID);
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
    @DisplayName("GET /api/community/posts with feed=FEATURED -> 200 OK")
    void shouldAcceptFeaturedFeedSelectorInSlice() throws Exception {
        UUID p1Id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");
        CommunityPostFeedItemDTO item = new CommunityPostFeedItemDTO(
                p1Id, USER_ID, "Author User", "author_user", "https://cdn.example.com/avatar.jpg", "Featured post",
                null, null, 0,
                10L, 5L, 15L, now, now
        );
        CommunityFeaturedFeedResponseDTO responseDTO = new CommunityFeaturedFeedResponseDTO(
                List.of(item), 0, 20, 1L, 1, false
        );

        when(getCommunityFeaturedFeedUseCase.execute(0, 20)).thenReturn(responseDTO);

        mockMvc.perform(get("/api/community/posts").param("feed", "FEATURED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(p1Id.toString()))
                .andExpect(jsonPath("$.items[0].engagementScore").value(15))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));

        verify(getCommunityFeaturedFeedUseCase).execute(0, 20);
        verify(getCommunityNewestFeedUseCase, never()).execute(any(), any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/posts with feed=featured (case-insensitive) and page=2 -> 200 OK")
    void shouldAcceptCaseInsensitiveFeaturedFeedSelectorWithCustomPageInSlice() throws Exception {
        CommunityFeaturedFeedResponseDTO responseDTO = new CommunityFeaturedFeedResponseDTO(
                List.of(), 2, 10, 25L, 3, false
        );

        when(getCommunityFeaturedFeedUseCase.execute(2, 10)).thenReturn(responseDTO);

        mockMvc.perform(get("/api/community/posts")
                        .param("feed", "featured")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalItems").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(false));

        verify(getCommunityFeaturedFeedUseCase).execute(2, 10);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/posts with feed=FEATURED and cursor -> 400 Bad Request (exclusivity)")
    void shouldRejectFeaturedFeedWithCursorInSlice() throws Exception {
        mockMvc.perform(get("/api/community/posts")
                        .param("feed", "FEATURED")
                        .param("cursor", "some-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cursor pagination is only supported for NEWEST feed."));

        verify(getCommunityFeaturedFeedUseCase, never()).execute(any(), any());
        verify(getCommunityNewestFeedUseCase, never()).execute(any(), any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/posts with feed=NEWEST and page -> 400 Bad Request (exclusivity)")
    void shouldRejectNewestFeedWithPageInSlice() throws Exception {
        mockMvc.perform(get("/api/community/posts")
                        .param("feed", "NEWEST")
                        .param("page", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page pagination is only supported for FEATURED feed."));

        verify(getCommunityFeaturedFeedUseCase, never()).execute(any(), any());
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
        verify(getCommunityFeaturedFeedUseCase, never()).execute(any(), any());
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

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/posts/{postId}/revisions as guest -> 200 OK with ordered revisions list")
    void guestGetPostRevisions_ShouldReturn200WithRevisionsList() throws Exception {
        Instant t1 = Instant.parse("2026-10-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-02T11:00:00Z");

        CommunityPostRevisionPublicDTO rev2 = new CommunityPostRevisionPublicDTO(
                UUID.randomUUID(),
                POST_ID,
                2,
                USER_ID,
                "Caption v1",
                "Caption v2",
                t2
        );
        CommunityPostRevisionPublicDTO rev1 = new CommunityPostRevisionPublicDTO(
                UUID.randomUUID(),
                POST_ID,
                1,
                USER_ID,
                "Caption v0",
                "Caption v1",
                t1
        );

        when(getCommunityPostRevisionsUseCase.execute(POST_ID)).thenReturn(List.of(rev2, rev1));

        mockMvc.perform(get("/api/community/posts/{postId}/revisions", POST_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].revisionNumber").value(2))
                .andExpect(jsonPath("$[0].previousCaption").value("Caption v1"))
                .andExpect(jsonPath("$[0].caption").value("Caption v2"))
                .andExpect(jsonPath("$[0].editedAt").value(t2.toString()))
                .andExpect(jsonPath("$[1].revisionNumber").value(1))
                .andExpect(jsonPath("$[1].previousCaption").value("Caption v0"))
                .andExpect(jsonPath("$[1].caption").value("Caption v1"))
                .andExpect(jsonPath("$[1].editedAt").value(t1.toString()));

        verify(getCommunityPostRevisionsUseCase).execute(POST_ID);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/posts/{postId}/revisions for unedited post -> 200 OK with empty array")
    void guestGetPostRevisions_WhenZeroRevisions_ShouldReturn200EmptyArray() throws Exception {
        when(getCommunityPostRevisionsUseCase.execute(POST_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/community/posts/{postId}/revisions", POST_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(0)));

        verify(getCommunityPostRevisionsUseCase).execute(POST_ID);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/community/posts/{postId}/revisions for missing post -> 404 Not Found")
    void guestGetPostRevisions_WhenPostNotFound_ShouldReturn404() throws Exception {
        when(getCommunityPostRevisionsUseCase.execute(POST_ID))
                .thenThrow(new CommunityPostNotFoundException(POST_ID));

        mockMvc.perform(get("/api/community/posts/{postId}/revisions", POST_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(Matchers.containsString(POST_ID.toString())));

        verify(getCommunityPostRevisionsUseCase).execute(POST_ID);
    }
}
