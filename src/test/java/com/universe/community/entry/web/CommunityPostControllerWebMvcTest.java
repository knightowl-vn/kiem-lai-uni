package com.universe.community.entry.web;

import com.universe.community.application.usecase.CreateCommunityPostWithImageUseCase;
import com.universe.community.domain.CommunityPost;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
}
