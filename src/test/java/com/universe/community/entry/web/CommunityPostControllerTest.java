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
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.exception.CommunityPostPendingReportConflictException;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

    @Mock
    private EditCommunityPostCaptionUseCase editCommunityPostCaptionUseCase;

    @Mock
    private GetCommunityNewestFeedUseCase getCommunityNewestFeedUseCase;

    @Mock
    private GetCommunityFeaturedFeedUseCase getCommunityFeaturedFeedUseCase;

    @Mock
    private GetCommunityPostRevisionsUseCase getCommunityPostRevisionsUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CommunityPostController controller = new CommunityPostController(
                createCommunityPostWithImageUseCase,
                deleteCommunityPostUseCase,
                editCommunityPostCaptionUseCase,
                getCommunityNewestFeedUseCase,
                getCommunityFeaturedFeedUseCase,
                getCommunityPostRevisionsUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private AuthenticatedRequestIdentity createActiveUserIdentity() {
        return new AuthenticatedRequestIdentity(
                USER_ID,
                "author@universe.com",
                "Author User",
                "https://cdn.example.com/avatar.jpg",
                "author_user",
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
    @DisplayName("DELETE /api/community/posts/{postId} with pending reports returns 409 Conflict")
    void shouldReturnConflictWhenPostHasPendingReports() throws Exception {
        doThrow(new CommunityPostPendingReportConflictException(POST_ID))
                .when(deleteCommunityPostUseCase).execute(USER_ID, POST_ID);

        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(POST_ID.toString())));
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

    // =========================================================================
    // PATCH /api/community/posts/{postId} Tests
    // =========================================================================

    @Test
    @DisplayName("PATCH /api/community/posts/{postId} should reject unauthenticated request with 401 Unauthorized")
    void shouldRejectUnauthenticatedEditRequest() throws Exception {
        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"New caption\"}"))
                .andExpect(status().isUnauthorized());

        verify(editCommunityPostCaptionUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("PATCH /api/community/posts/{postId} should successfully update caption for authenticated owner -> 200 OK")
    void shouldEditCaptionSuccessfullyForOwner() throws Exception {
        String newCaption = "Updated post caption text";
        Instant createdAt = Instant.parse("2026-09-30T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-09-30T11:00:00Z");
        CommunityPost post = CommunityPost.rehydrate(POST_ID, USER_ID, newCaption, null, 1, createdAt, updatedAt);

        when(editCommunityPostCaptionUseCase.execute(eq(new EditCommunityPostCaptionCommand(POST_ID, USER_ID, newCaption))))
                .thenReturn(post);

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"" + newCaption + "\"}")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(POST_ID.toString()))
                .andExpect(jsonPath("$.authorUserId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.caption").value(newCaption))
                .andExpect(jsonPath("$.contentVersion").value(1))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        verify(editCommunityPostCaptionUseCase).execute(new EditCommunityPostCaptionCommand(POST_ID, USER_ID, newCaption));
    }

    @Test
    @DisplayName("PATCH /api/community/posts/{postId} no-op edit (identical text) still returns 200 OK")
    void shouldReturnOkOnNoOpEdit() throws Exception {
        String caption = "Same caption unchanged";
        Instant createdAt = Instant.parse("2026-09-30T10:00:00Z");
        CommunityPost post = CommunityPost.create(POST_ID, USER_ID, caption, null, createdAt);

        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class)))
                .thenReturn(post);

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"  " + caption + "  \"}")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caption").value(caption))
                .andExpect(jsonPath("$.contentVersion").value(0));
    }

    @Test
    @DisplayName("PATCH /api/community/posts/{postId} with null caption field -> 400 Bad Request")
    void shouldReturnBadRequestWhenCaptionIsNull() throws Exception {
        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":null}")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Post caption cannot be null."));

        verify(editCommunityPostCaptionUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("PATCH /api/community/posts/{postId} with blank caption -> 400 Bad Request")
    void shouldReturnBadRequestWhenCaptionIsBlank() throws Exception {
        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class)))
                .thenThrow(new CommunityPostValidationException("Post caption cannot be blank."));

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"   \"}")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Post caption cannot be blank."));
    }

    @Test
    @DisplayName("PATCH /api/community/posts/{postId} with >2000 chars -> 400 Bad Request")
    void shouldReturnBadRequestWhenCaptionExceedsMaxLength() throws Exception {
        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class)))
                .thenThrow(new CommunityPostValidationException("Post caption length (2001) exceeds maximum limit of 2000 characters."));

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"" + "a".repeat(2001) + "\"}")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Post caption length (2001) exceeds maximum limit of 2000 characters."));
    }

    @Test
    @DisplayName("PATCH /api/community/posts/{postId} for non-existent post -> 404 Not Found")
    void shouldReturnNotFoundWhenEditingNonExistentPost() throws Exception {
        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class)))
                .thenThrow(new CommunityPostNotFoundException(POST_ID));

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"Valid caption\"}")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Community post not found: " + POST_ID));
    }

    @Test
    @DisplayName("PATCH /api/community/posts/{postId} as non-owner -> 403 Forbidden")
    void shouldReturnForbiddenWhenEditingPostAsNonOwner() throws Exception {
        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class)))
                .thenThrow(new CommunityPostUnauthorizedException(USER_ID, POST_ID));

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"Valid caption\"}")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("User " + USER_ID + " is not authorized to modify post " + POST_ID));
    }

    @Test
    @DisplayName("PATCH /api/community/posts/{postId} on unexpected error -> 500 Internal Server Error")
    void shouldReturnInternalServerErrorOnUnexpectedEditFailure() throws Exception {
        when(editCommunityPostCaptionUseCase.execute(any(EditCommunityPostCaptionCommand.class)))
                .thenThrow(new IllegalStateException("Failed to update post"));

        mockMvc.perform(patch("/api/community/posts/{postId}", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\":\"Valid caption\"}")
                        .with(request -> {
                            AuthenticatedRequestIdentityTestSupport.attach(request, createActiveUserIdentity());
                            return request;
                        }))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Failed to update post"));
    }

    // =========================================================================
    // GET /api/community/posts Feed Tests
    // =========================================================================

    @Test
    @DisplayName("GET /api/community/posts should return 200 OK with default NEWEST feed for guest")
    void shouldReturnDefaultNewestFeedForGuest() throws Exception {
        UUID p1Id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");
        CommunityPostFeedItemDTO item = new CommunityPostFeedItemDTO(
                p1Id, USER_ID, "Author User", "author_user", "https://cdn.example.com/avatar.jpg", "Feed post caption",
                null, null, 0,
                3L, 2L, 5L, now, now
        );
        CommunityNewestFeedResponseDTO responseDTO = new CommunityNewestFeedResponseDTO(
                List.of(item), "next-cursor-token", 20, true
        );

        when(getCommunityNewestFeedUseCase.execute(null, 20)).thenReturn(responseDTO);

        mockMvc.perform(get("/api/community/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(p1Id.toString()))
                .andExpect(jsonPath("$.items[0].authorUserId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.items[0].caption").value("Feed post caption"))
                .andExpect(jsonPath("$.items[0].reactionCount").value(3))
                .andExpect(jsonPath("$.items[0].commentCount").value(2))
                .andExpect(jsonPath("$.items[0].engagementScore").value(5))
                .andExpect(jsonPath("$.nextCursor").value("next-cursor-token"))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasNext").value(true));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @DisplayName("GET /api/community/posts?feed=NEWEST&cursor=xxx&size=10 should pass params to use case")
    void shouldPassExplicitCursorAndSizeParams() throws Exception {
        CommunityNewestFeedResponseDTO responseDTO = new CommunityNewestFeedResponseDTO(
                List.of(), null, 10, false
        );

        when(getCommunityNewestFeedUseCase.execute("custom-cursor", 10)).thenReturn(responseDTO);

        mockMvc.perform(get("/api/community/posts")
                        .param("feed", "NEWEST")
                        .param("cursor", "custom-cursor")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.hasNext").value(false));

        verify(getCommunityNewestFeedUseCase).execute("custom-cursor", 10);
    }

    @Test
    @DisplayName("GET /api/community/posts?feed=newest (case-insensitive) should pass to use case")
    void shouldAcceptCaseInsensitiveNewestFeedSelector() throws Exception {
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
    @DisplayName("GET /api/community/posts?feed=FEATURED should return 200 OK with featured feed")
    void shouldReturnFeaturedFeed() throws Exception {
        UUID postId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");
        CommunityPostFeedItemDTO item = new CommunityPostFeedItemDTO(
                postId, USER_ID, "Author User", "author_user", "https://cdn.example.com/avatar.jpg", "Featured post",
                null, null, 0,
                10L, 5L, 15L, now, now
        );
        CommunityFeaturedFeedResponseDTO responseDTO = new CommunityFeaturedFeedResponseDTO(
                List.of(item), 0, 20, 1L, 1, false
        );

        when(getCommunityFeaturedFeedUseCase.execute(0, 20)).thenReturn(responseDTO);

        mockMvc.perform(get("/api/community/posts").param("feed", "FEATURED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(postId.toString()))
                .andExpect(jsonPath("$.items[0].reactionCount").value(10))
                .andExpect(jsonPath("$.items[0].commentCount").value(5))
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
    @DisplayName("GET /api/community/posts?feed=featured (case-insensitive) & page=1 & size=10 should pass params to use case")
    void shouldAcceptCaseInsensitiveFeaturedFeedWithPageAndSize() throws Exception {
        CommunityFeaturedFeedResponseDTO responseDTO = new CommunityFeaturedFeedResponseDTO(
                List.of(), 1, 10, 5L, 1, false
        );

        when(getCommunityFeaturedFeedUseCase.execute(1, 10)).thenReturn(responseDTO);

        mockMvc.perform(get("/api/community/posts")
                        .param("feed", "featured")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10));

        verify(getCommunityFeaturedFeedUseCase).execute(1, 10);
    }

    @Test
    @DisplayName("GET /api/community/posts?feed=FEATURED&cursor=xxx should reject with 400 Bad Request")
    void shouldRejectCursorOnFeaturedFeed() throws Exception {
        mockMvc.perform(get("/api/community/posts")
                        .param("feed", "FEATURED")
                        .param("cursor", "some-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cursor pagination is only supported for NEWEST feed."));

        verifyNoInteractions(getCommunityFeaturedFeedUseCase);
        verifyNoInteractions(getCommunityNewestFeedUseCase);
    }

    @Test
    @DisplayName("GET /api/community/posts?feed=NEWEST&page=1 should reject with 400 Bad Request")
    void shouldRejectPageOnNewestFeed() throws Exception {
        mockMvc.perform(get("/api/community/posts")
                        .param("feed", "NEWEST")
                        .param("page", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page pagination is only supported for FEATURED feed."));

        verifyNoInteractions(getCommunityFeaturedFeedUseCase);
        verifyNoInteractions(getCommunityNewestFeedUseCase);
    }

    @Test
    @DisplayName("GET /api/community/posts?feed=UNKNOWN should return 400 Bad Request")
    void shouldRejectUnknownFeedSelector() throws Exception {
        mockMvc.perform(get("/api/community/posts").param("feed", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported feed selector: UNKNOWN"));

        verifyNoInteractions(getCommunityFeaturedFeedUseCase);
        verifyNoInteractions(getCommunityNewestFeedUseCase);
    }

    @Test
    @DisplayName("GET /api/community/posts with malformed cursor should return 400 Bad Request")
    void shouldReturnBadRequestOnMalformedCursor() throws Exception {
        when(getCommunityNewestFeedUseCase.execute(eq("invalid-cursor"), eq(20)))
                .thenThrow(new CommunityPostValidationException("Invalid cursor format."));

        mockMvc.perform(get("/api/community/posts").param("cursor", "invalid-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid cursor format."));
    }
}
