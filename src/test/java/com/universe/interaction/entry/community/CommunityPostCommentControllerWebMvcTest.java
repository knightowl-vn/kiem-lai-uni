package com.universe.interaction.entry.community;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.mutation.CreateRootCommentCommand;
import com.universe.interaction.application.mutation.CreateRootCommentUseCase;
import com.universe.interaction.application.mutation.ReplyCommentCommand;
import com.universe.interaction.application.mutation.ReplyCommentUseCase;
import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.community.dto.CommunityDiscussionFeedResponseDTO;
import com.universe.interaction.entry.community.dto.CommunityRootCommentDTO;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.shared.security.AuthenticatedEmailResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CommunityPostCommentController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("CommunityPostCommentController WebMvc Integration Tests")
class CommunityPostCommentControllerWebMvcTest {

    private static final UUID POST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_1_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_COMMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REPLY_COMMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

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
    private CommunityPostQueryPort communityPostQueryPort;

    @MockBean
    private CommunityPostDiscussionQueryCoordinator communityPostDiscussionQueryCoordinator;

    @MockBean
    private CreateRootCommentUseCase createRootCommentUseCase;

    @MockBean
    private ReplyCommentUseCase replyCommentUseCase;

    @MockBean
    private ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;

    @MockBean
    private GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase;

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
                "user@universe.local",
                "Community Member",
                null,
                "community_member",
                UserStatus.ACTIVE,
                UserRole.USER
        );
        return request -> {
            AuthenticatedRequestIdentityTestSupport.attach(request, identity);
            return request;
        };
    }

    private CommunityPostPublicDTO mockPublicPost() {
        return new CommunityPostPublicDTO(
                POST_ID,
                USER_1_ID,
                "Public post caption",
                null,
                1,
                NOW,
                NOW
        );
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user can fetch discussion feed on public post")
    void shouldFetchDiscussionFeedAnonymously() throws Exception {
        CommentReadDTO rootDTO = new CommentReadDTO(
                ROOT_COMMENT_ID,
                USER_1_ID,
                null,
                null,
                "First community root comment",
                false,
                NOW,
                NOW,
                new CommentAuthorDTO(USER_1_ID, "Author One", "https://img/a1.png", "author_one"),
                false,
                false
        );
        CommunityRootCommentDTO rootRow = new CommunityRootCommentDTO(rootDTO, 2L);
        CommunityDiscussionFeedResponseDTO feedResponse = new CommunityDiscussionFeedResponseDTO(
                List.of(rootRow), 3L, 0, 10, false
        );

        when(communityPostDiscussionQueryCoordinator.getDiscussionFeed(POST_ID, 0, 10, null, CommentSortMode.FEATURED))
                .thenReturn(feedResponse);

        mockMvc.perform(get("/api/community/posts/" + POST_ID + "/comments")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentCount").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.roots[0].root.id").value(ROOT_COMMENT_ID.toString()))
                .andExpect(jsonPath("$.roots[0].root.body").value("First community root comment"))
                .andExpect(jsonPath("$.roots[0].root.author.displayName").value("Author One"))
                .andExpect(jsonPath("$.roots[0].root.author.publicHandle").value("author_one"))
                .andExpect(jsonPath("$.roots[0].replyCount").value(2));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Removed /feed URL alias returns 404 Not Found")
    void shouldReturn404ForRemovedFeedAlias() throws Exception {
        mockMvc.perform(get("/api/community/posts/" + POST_ID + "/comments/feed"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Explicit sort param is forwarded to coordinator")
    void shouldForwardExplicitSortParam() throws Exception {
        CommunityDiscussionFeedResponseDTO feedResponse = new CommunityDiscussionFeedResponseDTO(
                List.of(), 0L, 0, 10, false
        );

        when(communityPostDiscussionQueryCoordinator.getDiscussionFeed(POST_ID, 0, 10, null, CommentSortMode.NEWEST))
                .thenReturn(feedResponse);
        when(communityPostDiscussionQueryCoordinator.getDiscussionFeed(POST_ID, 0, 10, null, CommentSortMode.FEATURED))
                .thenReturn(feedResponse);

        mockMvc.perform(get("/api/community/posts/" + POST_ID + "/comments")
                        .param("sort", "NEWEST"))
                .andExpect(status().isOk());

        verify(communityPostDiscussionQueryCoordinator).getDiscussionFeed(POST_ID, 0, 10, null, CommentSortMode.NEWEST);

        mockMvc.perform(get("/api/community/posts/" + POST_ID + "/comments")
                        .param("sort", "FEATURED"))
                .andExpect(status().isOk());

        verify(communityPostDiscussionQueryCoordinator).getDiscussionFeed(POST_ID, 0, 10, null, CommentSortMode.FEATURED);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Invalid sort param returns 400 Bad Request")
    void shouldReturn400ForInvalidSortParam() throws Exception {
        mockMvc.perform(get("/api/community/posts/" + POST_ID + "/comments")
                        .param("sort", "INVALID_SORT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user can fetch single thread on public post")
    void shouldFetchSingleThreadAnonymously() throws Exception {
        CommentReadDTO rootDTO = new CommentReadDTO(
                ROOT_COMMENT_ID,
                USER_1_ID,
                null,
                null,
                "Root comment body",
                false,
                NOW,
                NOW,
                new CommentAuthorDTO(USER_1_ID, "Author One", null, "author_one"),
                false,
                false
        );
        CommentThreadResponseDTO thread = new CommentThreadResponseDTO(rootDTO, List.of());

        when(communityPostDiscussionQueryCoordinator.getCommentThread(POST_ID, ROOT_COMMENT_ID, null))
                .thenReturn(thread);

        mockMvc.perform(get("/api/community/posts/" + POST_ID + "/comments/" + ROOT_COMMENT_ID + "/thread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root.id").value(ROOT_COMMENT_ID.toString()))
                .andExpect(jsonPath("$.root.body").value("Root comment body"))
                .andExpect(jsonPath("$.replies").isEmpty());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user attempting POST root is redirected to /login")
    void shouldDenyAnonymousRootCommentCreation() throws Exception {
        mockMvc.perform(post("/api/community/posts/" + POST_ID + "/comments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Should fail\"}"))
                .andExpect(status().is3xxRedirection());

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Mutation request without CSRF is redirected to /access-denied")
    void shouldDenyRootCommentCreationWithoutCsrf() throws Exception {
        mockMvc.perform(post("/api/community/posts/" + POST_ID + "/comments")
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Should fail without CSRF\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/access-denied"));

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Authenticated user can create root comment and receives 201 with updated comment count")
    void shouldCreateRootCommentSuccessfully() throws Exception {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(mockPublicPost()));

        Comment createdComment = Comment.createRoot(
                ROOT_COMMENT_ID,
                CommentTarget.communityPost(POST_ID),
                USER_1_ID,
                "Valid root comment content",
                NOW
        );
        when(createRootCommentUseCase.execute(any(CreateRootCommentCommand.class))).thenReturn(createdComment);
        when(getCommentTargetMetricsUseCase.execute(CommentTarget.communityPost(POST_ID)))
                .thenReturn(new CommentTargetMetrics(1L, 5L));

        mockMvc.perform(post("/api/community/posts/" + POST_ID + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Valid root comment content\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(ROOT_COMMENT_ID.toString()))
                .andExpect(jsonPath("$.updatedCommentCount").value(5));

        ArgumentCaptor<CreateRootCommentCommand> captor = ArgumentCaptor.forClass(CreateRootCommentCommand.class);
        verify(createRootCommentUseCase).execute(captor.capture());
        assertThat(captor.getValue().actorUserId()).isEqualTo(USER_1_ID);
        assertThat(captor.getValue().target()).isEqualTo(CommentTarget.communityPost(POST_ID));
        assertThat(captor.getValue().body()).isEqualTo("Valid root comment content");
    }

    @Test
    @WithMockUser
    @DisplayName("Creating root comment with blank body returns 400 Bad Request")
    void shouldRejectBlankBodyOnCreateRootComment() throws Exception {
        mockMvc.perform(post("/api/community/posts/" + POST_ID + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Creating root comment on non-existent post returns 404 Not Found")
    void shouldReturn404WhenPostDoesNotExistOnCreateComment() throws Exception {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/community/posts/" + POST_ID + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Valid comment body\"}"))
                .andExpect(status().isNotFound());

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user attempting POST reply is redirected to /login")
    void shouldDenyAnonymousReplyCreation() throws Exception {
        mockMvc.perform(post("/api/community/posts/" + POST_ID + "/comments/" + ROOT_COMMENT_ID + "/replies")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Anonymous reply\"}"))
                .andExpect(status().is3xxRedirection());

        verify(replyCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Replying without CSRF is redirected to /access-denied")
    void shouldDenyReplyWithoutCsrf() throws Exception {
        mockMvc.perform(post("/api/community/posts/" + POST_ID + "/comments/" + ROOT_COMMENT_ID + "/replies")
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Reply without CSRF\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/access-denied"));

        verify(replyCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Authenticated user can reply to a comment and receives 201 with updated comment count")
    void shouldReplyToCommentSuccessfully() throws Exception {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(mockPublicPost()));

        Comment rootComment = Comment.createRoot(ROOT_COMMENT_ID, CommentTarget.communityPost(POST_ID), USER_1_ID, "Root body", NOW);
        Comment replyComment = Comment.createReply(REPLY_COMMENT_ID, rootComment, USER_1_ID, "Reply text", NOW.plusSeconds(10));
        when(replyCommentUseCase.execute(any(ReplyCommentCommand.class))).thenReturn(replyComment);
        when(getCommentTargetMetricsUseCase.execute(CommentTarget.communityPost(POST_ID)))
                .thenReturn(new CommentTargetMetrics(1L, 6L));

        mockMvc.perform(post("/api/community/posts/" + POST_ID + "/comments/" + ROOT_COMMENT_ID + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Reply text\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(REPLY_COMMENT_ID.toString()))
                .andExpect(jsonPath("$.updatedCommentCount").value(6));

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, CommentTarget.communityPost(POST_ID));
        ArgumentCaptor<ReplyCommentCommand> captor = ArgumentCaptor.forClass(ReplyCommentCommand.class);
        verify(replyCommentUseCase).execute(captor.capture());
        assertThat(captor.getValue().actorUserId()).isEqualTo(USER_1_ID);
        assertThat(captor.getValue().parentCommentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(captor.getValue().body()).isEqualTo("Reply text");
    }

    @Test
    @WithMockUser
    @DisplayName("Replying to comment belonging to different target returns 404 Not Found")
    void shouldReturn404WhenTargetMismatchOnReply() throws Exception {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(mockPublicPost()));

        doThrow(new CommentNotFoundException("Target mismatch"))
                .when(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, CommentTarget.communityPost(POST_ID));

        mockMvc.perform(post("/api/community/posts/" + POST_ID + "/comments/" + ROOT_COMMENT_ID + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Reply to wrong target\"}"))
                .andExpect(status().isNotFound());

        verify(replyCommentUseCase, never()).execute(any());
    }
}
