package com.universe.community.entry.web;

import com.universe.community.application.usecase.CreateCommunityPostWithImageUseCase;
import com.universe.community.application.usecase.DeleteCommunityPostUseCase;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.exception.CommunityPostUnauthorizedException;
import com.universe.community.domain.exception.CommunityPostValidationException;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityPostController Standalone Unit Tests")
class CommunityPostControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID POST_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private CreateCommunityPostWithImageUseCase createCommunityPostWithImageUseCase;

    @Mock
    private DeleteCommunityPostUseCase deleteCommunityPostUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CommunityPostController controller = new CommunityPostController(
                createCommunityPostWithImageUseCase,
                deleteCommunityPostUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private AuthenticatedRequestIdentity createActiveUserIdentity() {
        return new AuthenticatedRequestIdentity(
                USER_ID,
                "author@universe.com",
                "Author User",
                "https://cdn.example.com/avatar.jpg",
                UserStatus.ACTIVE,
                UserRole.USER
        );
    }

    @Test
    @DisplayName("POST /api/community/posts should reject unauthenticated request with 401 Unauthorized")
    void shouldRejectUnauthenticatedRequest() throws Exception {
        MockMultipartFile imagePart = new MockMultipartFile(
                "image",
                "test.jpg",
                "image/jpeg",
                "image-bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/community/posts")
                        .file(imagePart)
                        .param("caption", "Hello world from unauthenticated guest"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("User must be authenticated to create a community post."));
    }

    @Test
    @DisplayName("POST /api/community/posts should successfully create caption-only post for authenticated user")
    void shouldCreateCaptionOnlyPost() throws Exception {
        String caption = "This is a caption-only post";
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
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(postId.toString()))
                .andExpect(jsonPath("$.authorUserId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.caption").value(caption))
                .andExpect(jsonPath("$.imageMediaAssetId").doesNotExist())
                .andExpect(jsonPath("$.imageUrl").doesNotExist())
                .andExpect(jsonPath("$.contentVersion").value(0));
    }

    @Test
    @DisplayName("POST /api/community/posts should successfully create post with image and return imageUrl")
    void shouldCreatePostWithImage() throws Exception {
        String caption = "Post with attached photo";
        UUID postId = UUID.randomUUID();
        UUID imageAssetId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-30T10:00:00Z");
        CommunityPost post = CommunityPost.create(postId, USER_ID, caption, imageAssetId, createdAt);

        MockMultipartFile imagePart = new MockMultipartFile(
                "image",
                "scenery.jpg",
                "image/jpeg",
                "jpeg-content-bytes".getBytes()
        );

        when(createCommunityPostWithImageUseCase.execute(
                eq(USER_ID),
                eq(caption),
                any(InputStream.class),
                eq((long) "jpeg-content-bytes".getBytes().length),
                eq("image/jpeg"),
                eq("scenery.jpg")
        )).thenReturn(post);

        mockMvc.perform(multipart("/api/community/posts")
                        .file(imagePart)
                        .param("caption", caption)
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(postId.toString()))
                .andExpect(jsonPath("$.authorUserId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.caption").value(caption))
                .andExpect(jsonPath("$.imageMediaAssetId").value(imageAssetId.toString()))
                .andExpect(jsonPath("$.imageUrl").value("/media/assets/" + imageAssetId + "/content"))
                .andExpect(jsonPath("$.contentVersion").value(0));
    }

    @Test
    @DisplayName("POST /api/community/posts should return 400 Bad Request when 0-byte image is attached")
    void shouldReturnBadRequestWhenImageIsEmpty() throws Exception {
        MockMultipartFile emptyImage = new MockMultipartFile(
                "image",
                "empty.jpg",
                "image/jpeg",
                new byte[0]
        );

        when(createCommunityPostWithImageUseCase.execute(
                eq(USER_ID),
                eq("some caption"),
                any(InputStream.class),
                eq(0L),
                eq("image/jpeg"),
                eq("empty.jpg")
        )).thenThrow(new CommunityPostValidationException("Image file cannot be empty."));

        mockMvc.perform(multipart("/api/community/posts")
                        .file(emptyImage)
                        .param("caption", "some caption")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Image file cannot be empty."));
    }

    @Test
    @DisplayName("POST /api/community/posts should return 400 Bad Request when multiple images are attached")
    void shouldReturnBadRequestWhenMultipleImagesAreAttached() throws Exception {
        MockMultipartFile image1 = new MockMultipartFile(
                "image",
                "first.jpg",
                "image/jpeg",
                "image-1-bytes".getBytes()
        );
        MockMultipartFile image2 = new MockMultipartFile(
                "image",
                "second.png",
                "image/png",
                "image-2-bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/community/posts")
                        .file(image1)
                        .file(image2)
                        .param("caption", "Multiple images post")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A community post can have at most one image attachment."));

        verify(createCommunityPostWithImageUseCase, never()).execute(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("POST /api/community/posts should return 400 Bad Request on CommunityPostValidationException")
    void shouldReturnBadRequestOnValidationException() throws Exception {
        when(createCommunityPostWithImageUseCase.execute(any(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new CommunityPostValidationException("Post caption cannot be blank."));

        mockMvc.perform(multipart("/api/community/posts")
                        .param("caption", "   ")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Post caption cannot be blank."));
    }

    @Test
    @DisplayName("POST /api/community/posts should return 400 Bad Request on IllegalArgumentException")
    void shouldReturnBadRequestOnIllegalArgumentException() throws Exception {
        when(createCommunityPostWithImageUseCase.execute(any(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid argument provided."));

        mockMvc.perform(multipart("/api/community/posts")
                        .param("caption", "valid caption")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid argument provided."));
    }

    @Test
    @DisplayName("POST /api/community/posts should return 403 Forbidden on CommunityPostUnauthorizedException")
    void shouldReturnForbiddenOnUnauthorizedException() throws Exception {
        when(createCommunityPostWithImageUseCase.execute(any(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new CommunityPostUnauthorizedException("Unauthorized action."));

        mockMvc.perform(multipart("/api/community/posts")
                        .param("caption", "caption")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Unauthorized action."));
    }

    @Test
    @DisplayName("POST /api/community/posts should return 500 Internal Server Error on IllegalStateException")
    void shouldReturnInternalServerErrorOnIllegalStateException() throws Exception {
        when(createCommunityPostWithImageUseCase.execute(any(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("Failed to persist community post"));

        mockMvc.perform(multipart("/api/community/posts")
                        .param("caption", "caption")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Failed to persist community post"));
    }

    // =========================================================================
    // DELETE /api/community/posts/{postId} Tests
    // =========================================================================

    @Test
    @DisplayName("DELETE /api/community/posts/{postId} should reject unauthenticated request with 401 Unauthorized")
    void shouldRejectUnauthenticatedDeleteRequest() throws Exception {
        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID))
                .andExpect(status().isUnauthorized());

        verify(deleteCommunityPostUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("DELETE /api/community/posts/{postId} should successfully delete post for authenticated owner -> 204 No Content")
    void shouldDeletePostSuccessfullyForOwner() throws Exception {
        doNothing().when(deleteCommunityPostUseCase).execute(USER_ID, POST_ID);

        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isNoContent());

        verify(deleteCommunityPostUseCase).execute(USER_ID, POST_ID);
    }

    @Test
    @DisplayName("DELETE /api/community/posts/{postId} should return 404 Not Found when post does not exist")
    void shouldReturnNotFoundWhenDeletingNonExistentPost() throws Exception {
        doThrow(new CommunityPostNotFoundException(POST_ID))
                .when(deleteCommunityPostUseCase).execute(USER_ID, POST_ID);

        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Community post not found: " + POST_ID));
    }

    @Test
    @DisplayName("DELETE /api/community/posts/{postId} should return 403 Forbidden when actor is not post author")
    void shouldReturnForbiddenWhenDeletingPostAsNonOwner() throws Exception {
        doThrow(new CommunityPostUnauthorizedException(USER_ID, POST_ID))
                .when(deleteCommunityPostUseCase).execute(USER_ID, POST_ID);

        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("User " + USER_ID + " is not authorized to modify post " + POST_ID));
    }

    @Test
    @DisplayName("DELETE /api/community/posts/{postId} should return 500 Internal Server Error on unexpected failure")
    void shouldReturnInternalServerErrorOnUnexpectedDeleteFailure() throws Exception {
        doThrow(new IllegalStateException("Database lock error"))
                .when(deleteCommunityPostUseCase).execute(USER_ID, POST_ID);

        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Database lock error"));
    }

    @Test
    @DisplayName("DELETE /api/community/posts/{postId} repeated delete: first -> 204, second -> 404")
    void shouldHandleRepeatedDeleteInController() throws Exception {
        doNothing()
                .doThrow(new CommunityPostNotFoundException(POST_ID))
                .when(deleteCommunityPostUseCase).execute(USER_ID, POST_ID);

        // First attempt -> 204
        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isNoContent());

        // Second attempt -> 404
        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Community post not found: " + POST_ID));
    }
}
