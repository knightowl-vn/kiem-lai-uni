package com.universe.interaction.entry.wiki;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.interaction.application.exceptions.CommentHasRepliesException;
import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentNotReportableException;
import com.universe.interaction.application.exceptions.DuplicatePendingReportException;
import com.universe.interaction.application.exceptions.SelfReportNotAllowedException;
import com.universe.interaction.application.mutation.CreateRootCommentCommand;
import com.universe.interaction.application.mutation.CreateRootCommentUseCase;
import com.universe.interaction.application.mutation.DeleteCommentCommand;
import com.universe.interaction.application.mutation.DeleteCommentUseCase;
import com.universe.interaction.application.mutation.EditCommentCommand;
import com.universe.interaction.application.mutation.EditCommentUseCase;
import com.universe.interaction.application.mutation.ReplyCommentCommand;
import com.universe.interaction.application.mutation.ReplyCommentUseCase;
import com.universe.interaction.application.mutation.SubmitCommentReportCommand;
import com.universe.interaction.application.mutation.SubmitCommentReportUseCase;
import com.universe.interaction.application.ports.CommentRevisionSlice;
import com.universe.interaction.application.query.GetPublicCommentRevisionsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;
import com.universe.interaction.domain.CommentRevision;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.wiki.dto.WikiDiscussionFeedResponseDTO;
import com.universe.shared.security.AuthenticatedEmailResolver;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WikiArticleCommentController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("WikiArticleCommentController Integration Tests")
class WikiArticleCommentControllerIntegrationTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ARTICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_1_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOT_COMMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID REPLY_COMMENT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
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
    private WikiArticleQueryPort wikiArticleQueryPort;

    @MockBean
    private WikiArticleDiscussionQueryCoordinator wikiArticleDiscussionQueryCoordinator;

    @MockBean
    private CreateRootCommentUseCase createRootCommentUseCase;

    @MockBean
    private ReplyCommentUseCase replyCommentUseCase;

    @MockBean
    private EditCommentUseCase editCommentUseCase;

    @MockBean
    private DeleteCommentUseCase deleteCommentUseCase;

    @MockBean
    private ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;

    @MockBean
    private GetPublicCommentRevisionsUseCase getPublicCommentRevisionsUseCase;

    @MockBean
    private SubmitCommentReportUseCase submitCommentReportUseCase;

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
                "scholar@universe.local",
                "Scholar User",
                null,
                "scholar_user",
                UserStatus.ACTIVE,
                UserRole.USER
        );
        return request -> {
            AuthenticatedRequestIdentityTestSupport.attach(request, identity);
            return request;
        };
    }

    // =========================================================================
    // 1. PUBLIC READ TESTS
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user can list discussion feed on published article")
    void shouldListDiscussionFeedOnPublishedArticle() throws Exception {
        CommentReadDTO rootDTO = new CommentReadDTO(
                ROOT_COMMENT_ID,
                USER_1_ID,
                null,
                null,
                "First root comment",
                false,
                NOW,
                NOW,
                new CommentAuthorDTO(USER_1_ID, "Scholar User", null, "scholar_user"),
                false,
                false
        );
        CommentThreadResponseDTO thread = new CommentThreadResponseDTO(rootDTO, List.of());
        WikiDiscussionFeedResponseDTO feedResponse = new WikiDiscussionFeedResponseDTO(
                List.of(thread), 1, 1, 0, 20, false
        );

        when(wikiArticleDiscussionQueryCoordinator.getDiscussionFeed(ARTICLE_ID, 0, 20, null, CommentSortMode.FEATURED))
                .thenReturn(feedResponse);

        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadCount").value(1))
                .andExpect(jsonPath("$.commentCount").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.threads[0].root.id").value(ROOT_COMMENT_ID.toString()))
                .andExpect(jsonPath("$.threads[0].root.body").value("First root comment"))
                .andExpect(jsonPath("$.threads[0].root.author.displayName").value("Scholar User"))
                .andExpect(jsonPath("$.threads[0].root.canEdit").value(false))
                .andExpect(jsonPath("$.threads[0].root.canDelete").value(false));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Explicit sort param is forwarded to wiki coordinator")
    void shouldForwardExplicitSortParam() throws Exception {
        WikiDiscussionFeedResponseDTO feedResponse = new WikiDiscussionFeedResponseDTO(
                List.of(), 0, 0, 0, 20, false
        );

        when(wikiArticleDiscussionQueryCoordinator.getDiscussionFeed(ARTICLE_ID, 0, 20, null, CommentSortMode.NEWEST))
                .thenReturn(feedResponse);
        when(wikiArticleDiscussionQueryCoordinator.getDiscussionFeed(ARTICLE_ID, 0, 20, null, CommentSortMode.FEATURED))
                .thenReturn(feedResponse);

        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .param("sort", "NEWEST"))
                .andExpect(status().isOk());

        verify(wikiArticleDiscussionQueryCoordinator).getDiscussionFeed(ARTICLE_ID, 0, 20, null, CommentSortMode.NEWEST);

        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .param("sort", "FEATURED"))
                .andExpect(status().isOk());

        verify(wikiArticleDiscussionQueryCoordinator).getDiscussionFeed(ARTICLE_ID, 0, 20, null, CommentSortMode.FEATURED);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Invalid sort param returns 400 Bad Request on wiki comments")
    void shouldReturn400ForInvalidSortParam() throws Exception {
        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .param("sort", "INVALID_SORT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user can fetch single thread on published article")
    void shouldFetchSingleThreadOnPublishedArticle() throws Exception {
        CommentReadDTO rootDTO = new CommentReadDTO(
                ROOT_COMMENT_ID,
                USER_1_ID,
                null,
                null,
                "Root body",
                false,
                NOW,
                NOW,
                new CommentAuthorDTO(USER_1_ID, "Scholar User", null, "scholar_user"),
                false,
                false
        );
        CommentThreadResponseDTO thread = new CommentThreadResponseDTO(rootDTO, List.of());

        when(wikiArticleDiscussionQueryCoordinator.getCommentThread(ARTICLE_ID, ROOT_COMMENT_ID, null))
                .thenReturn(thread);

        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/thread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root.id").value(ROOT_COMMENT_ID.toString()))
                .andExpect(jsonPath("$.root.body").value("Root body"))
                .andExpect(jsonPath("$.replies").isEmpty());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user can list public revisions on published article")
    void shouldListCommentRevisionsOnPublishedArticle() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);

        CommentRevision revision = new CommentRevision(
                UUID.randomUUID(), ROOT_COMMENT_ID, 1, "Old body", NOW
        );
        CommentRevisionSlice slice = new CommentRevisionSlice(List.of(revision), 0, 20, false);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        when(getPublicCommentRevisionsUseCase.execute(ROOT_COMMENT_ID, target, 0, 20))
                .thenReturn(slice);

        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].body").value("Old body"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    // =========================================================================
    // 2. PUBLICATION GATE FAIL-CLOSED TESTS
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("Feed returns 404 when Wiki article is not published or missing")
    void shouldFailClosedOnFeedWhenArticleUnpublished() throws Exception {
        when(wikiArticleDiscussionQueryCoordinator.getDiscussionFeed(eq(ARTICLE_ID), any(Integer.class), any(Integer.class), any(), any()))
                .thenThrow(new com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException(ARTICLE_ID));

        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Create root returns 404 when Wiki article is not published or missing")
    void shouldFailClosedOnCreateRootWhenArticleUnpublished() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(false);

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Test comment\"}"))
                .andExpect(status().isNotFound());

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Reply returns 404 when Wiki article is not published or missing")
    void shouldFailClosedOnReplyWhenArticleUnpublished() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(false);

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Test reply\"}"))
                .andExpect(status().isNotFound());

        verify(replyCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Edit returns 404 when Wiki article is not published or missing")
    void shouldFailClosedOnEditWhenArticleUnpublished() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(false);

        mockMvc.perform(patch("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Updated body\"}"))
                .andExpect(status().isNotFound());

        verify(editCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Delete returns 404 when Wiki article is not published or missing")
    void shouldFailClosedOnDeleteWhenArticleUnpublished() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(false);

        mockMvc.perform(delete("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID)))
                .andExpect(status().isNotFound());

        verify(deleteCommentUseCase, never()).execute(any());
    }

    // =========================================================================
    // 3. AUTHENTICATED MUTATIONS
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("Authenticated user can create root comment on published article")
    void shouldCreateRootComment() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);

        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);
        Comment created = Comment.createRoot(ROOT_COMMENT_ID, target, USER_1_ID, "Valid comment body", NOW);

        when(createRootCommentUseCase.execute(any(CreateRootCommentCommand.class))).thenReturn(created);

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Valid comment body\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(ROOT_COMMENT_ID.toString()));

        ArgumentCaptor<CreateRootCommentCommand> captor = ArgumentCaptor.forClass(CreateRootCommentCommand.class);
        verify(createRootCommentUseCase).execute(captor.capture());
        assertThat(captor.getValue().actorUserId()).isEqualTo(USER_1_ID);
        assertThat(captor.getValue().target()).isEqualTo(target);
        assertThat(captor.getValue().body()).isEqualTo("Valid comment body");
    }

    @Test
    @WithMockUser
    @DisplayName("Authenticated user can create reply under root in published article")
    void shouldCreateReply() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        Comment root = Comment.createRoot(ROOT_COMMENT_ID, target, USER_1_ID, "Root body", NOW);
        Comment reply = Comment.createReply(REPLY_COMMENT_ID, root, USER_1_ID, "Reply body", NOW);

        when(replyCommentUseCase.execute(any(ReplyCommentCommand.class))).thenReturn(reply);

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Reply body\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(REPLY_COMMENT_ID.toString()));

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);
        ArgumentCaptor<ReplyCommentCommand> captor = ArgumentCaptor.forClass(ReplyCommentCommand.class);
        verify(replyCommentUseCase).execute(captor.capture());
        assertThat(captor.getValue().parentCommentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(captor.getValue().actorUserId()).isEqualTo(USER_1_ID);
        assertThat(captor.getValue().body()).isEqualTo("Reply body");
    }

    @Test
    @WithMockUser
    @DisplayName("Authenticated owner can edit their comment with PATCH")
    void shouldEditComment() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        mockMvc.perform(patch("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"New body\"}"))
                .andExpect(status().isNoContent());

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);
        ArgumentCaptor<EditCommentCommand> captor = ArgumentCaptor.forClass(EditCommentCommand.class);
        verify(editCommentUseCase).execute(captor.capture());
        assertThat(captor.getValue().commentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(captor.getValue().actorUserId()).isEqualTo(USER_1_ID);
        assertThat(captor.getValue().newBody()).isEqualTo("New body");
    }

    @Test
    @WithMockUser
    @DisplayName("PUT to comment edit endpoint returns 405 Method Not Allowed")
    void shouldRejectPutOnEditEndpoint() throws Exception {
        mockMvc.perform(put("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"PUT not allowed\"}"))
                .andExpect(status().isMethodNotAllowed());

        verify(editCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Authenticated owner can delete their comment with 204 No Content")
    void shouldDeleteComment() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        mockMvc.perform(delete("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID)))
                .andExpect(status().isNoContent());

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);
        ArgumentCaptor<DeleteCommentCommand> captor = ArgumentCaptor.forClass(DeleteCommentCommand.class);
        verify(deleteCommentUseCase).execute(captor.capture());
        assertThat(captor.getValue().commentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(captor.getValue().actorUserId()).isEqualTo(USER_1_ID);
    }

    @Test
    @WithMockUser
    @DisplayName("Should reject delete with 409 Conflict when comment has replies on Wiki")
    void shouldRejectDeleteWith409ConflictWhenCommentHasReplies() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        doThrow(new CommentHasRepliesException(ROOT_COMMENT_ID))
                .when(deleteCommentUseCase).execute(any(DeleteCommentCommand.class));

        mockMvc.perform(delete("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID)))
                .andExpect(status().isConflict());

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);
        verify(deleteCommentUseCase).execute(any(DeleteCommentCommand.class));
    }

    // =========================================================================
    // 4. SECURITY / AUTHORIZATION / CSRF
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user attempting POST root is denied")
    void shouldRejectAnonymousPostRoot() throws Exception {
        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Anonymous attempt\"}"))
                .andExpect(status().is3xxRedirection()); // Redirects to /login
    }

    @Test
    @WithMockUser
    @DisplayName("Mutation request without CSRF is redirected to /access-denied")
    void shouldRejectMutationWithoutCsrf() throws Exception {
        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"CSRF missing\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser
    @DisplayName("Non-owner editing comment receives 403 Forbidden")
    void shouldRejectNonOwnerEdit() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        doThrow(new CommentMutationForbiddenException("User is not the author"))
                .when(editCommentUseCase).execute(any());

        mockMvc.perform(patch("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Hacker edit\"}"))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 5. VALIDATION & SCOPE MISMATCH
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("Create root returns 400 Bad Request when body exceeds 2000 characters")
    void shouldRejectRootCommentWhenBodyExceeds2000Chars() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        String body2001 = "a".repeat(2001);

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body2001 + "\"}"))
                .andExpect(status().isBadRequest());

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Reply returns 400 Bad Request when body exceeds 2000 characters")
    void shouldRejectReplyWhenBodyExceeds2000Chars() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        String body2001 = "a".repeat(2001);

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body2001 + "\"}"))
                .andExpect(status().isBadRequest());

        verify(replyCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Edit returns 400 Bad Request when body exceeds 2000 characters")
    void shouldRejectEditWhenBodyExceeds2000Chars() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        String body2001 = "a".repeat(2001);

        mockMvc.perform(patch("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body2001 + "\"}"))
                .andExpect(status().isBadRequest());

        verify(editCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Blank body on create root returns 400 Bad Request")
    void shouldRejectBlankBodyOnCreate() throws Exception {
        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser
    @DisplayName("Cross-target comment mutation returns 404 Not Found")
    void shouldRejectCrossTargetMutation() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        doThrow(new CommentNotFoundException("Target mismatch"))
                .when(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        mockMvc.perform(delete("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID)))
                .andExpect(status().isNotFound());

        verify(deleteCommentUseCase, never()).execute(any());
    }

    // =========================================================================
    // 6. PAGINATION BOUNDS & REVISION HTTP METHODS
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("Discussion feed with negative page returns 400 Bad Request")
    void shouldRejectNegativePageOnFeed() throws Exception {
        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .param("page", "-1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Discussion feed with non-positive size returns 400 Bad Request")
    void shouldRejectNonPositiveSizeOnFeed() throws Exception {
        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .param("page", "0")
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Discussion feed clamps page size exceeding MAX_PAGE_SIZE to 50")
    void shouldClampMaxPageSizeOnFeed() throws Exception {
        WikiDiscussionFeedResponseDTO feedResponse = new WikiDiscussionFeedResponseDTO(
                List.of(), 0, 0, 0, 50, false
        );
        when(wikiArticleDiscussionQueryCoordinator.getDiscussionFeed(ARTICLE_ID, 0, 50, null, CommentSortMode.FEATURED))
                .thenReturn(feedResponse);

        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments")
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk());

        verify(wikiArticleDiscussionQueryCoordinator).getDiscussionFeed(ARTICLE_ID, 0, 50, null, CommentSortMode.FEATURED);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Comment revisions with negative page returns 400 Bad Request")
    void shouldRejectNegativePageOnRevisions() throws Exception {
        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/revisions")
                        .param("page", "-1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Comment revisions with non-positive size returns 400 Bad Request")
    void shouldRejectNonPositiveSizeOnRevisions() throws Exception {
        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/revisions")
                        .param("page", "0")
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Comment revisions clamps page size exceeding MAX_PAGE_SIZE to 50")
    void shouldClampMaxPageSizeOnRevisions() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentRevisionSlice slice = new CommentRevisionSlice(List.of(), 0, 50, false);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        when(getPublicCommentRevisionsUseCase.execute(ROOT_COMMENT_ID, target, 0, 50))
                .thenReturn(slice);

        mockMvc.perform(get("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/revisions")
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk());

        verify(getPublicCommentRevisionsUseCase).execute(ROOT_COMMENT_ID, target, 0, 50);
    }

    @Test
    @WithMockUser
    @DisplayName("Non-GET HTTP method on revisions endpoint returns 405 Method Not Allowed")
    void shouldRejectNonGetOnRevisionsEndpoint() throws Exception {
        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/revisions")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID)))
                .andExpect(status().isMethodNotAllowed());
    }

    // =========================================================================
    // COMMENT REPORTING TESTS
    // =========================================================================

    @Test
    @WithMockUser(username = "scholar@universe.local", roles = "USER")
    @DisplayName("Should submit comment report with 201 Created and minimal public response DTO on Wiki article")
    void shouldSubmitWikiCommentReportSuccessfully() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);

        UUID reportId = UUID.randomUUID();
        InteractionReport report = InteractionReport.createPending(
                reportId,
                ReportTargetType.COMMENT,
                ROOT_COMMENT_ID,
                USER_1_ID,
                ReportReason.SPAM,
                "Spam comment on wiki",
                "Authoritative wiki comment body snapshot",
                null,
                NOW
        );

        when(submitCommentReportUseCase.execute(any(SubmitCommentReportCommand.class))).thenReturn(report);

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM",
                                    "description": "Spam comment on wiki"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").value(reportId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").exists())
                // Assert privacy: internal and snapshot fields must not leak
                .andExpect(jsonPath("$.reportedBodySnapshot").doesNotExist())
                .andExpect(jsonPath("$.snapshot").doesNotExist())
                .andExpect(jsonPath("$.reporterUserId").doesNotExist())
                .andExpect(jsonPath("$.actorUserId").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.resolution").doesNotExist());

        ArgumentCaptor<SubmitCommentReportCommand> captor = ArgumentCaptor.forClass(SubmitCommentReportCommand.class);
        verify(submitCommentReportUseCase).execute(captor.capture());
        SubmitCommentReportCommand cmd = captor.getValue();
        assertThat(cmd.commentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(cmd.reporterUserId()).isEqualTo(USER_1_ID);
        assertThat(cmd.reason()).isEqualTo(ReportReason.SPAM);
        assertThat(cmd.description()).isEqualTo("Spam comment on wiki");

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, CommentTarget.wikiArticle(ARTICLE_ID));
    }

    @Test
    @WithMockUser(username = "scholar@universe.local", roles = "USER")
    @DisplayName("Should ignore client-supplied actor ID or snapshot and derive actor strictly from authenticated identity")
    void shouldIgnoreClientSuppliedActorAndSnapshotInWikiReport() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);

        UUID reportId = UUID.randomUUID();
        InteractionReport report = InteractionReport.createPending(
                reportId,
                ReportTargetType.COMMENT,
                ROOT_COMMENT_ID,
                USER_1_ID,
                ReportReason.HARASSMENT,
                "Valid description",
                "Real server snapshot",
                null,
                NOW
        );

        when(submitCommentReportUseCase.execute(any(SubmitCommentReportCommand.class))).thenReturn(report);

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "HARASSMENT",
                                    "description": "Valid description",
                                    "reporterUserId": "00000000-0000-0000-0000-000000000000",
                                    "reportedBodySnapshot": "Hacked snapshot"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").value(reportId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        ArgumentCaptor<SubmitCommentReportCommand> captor = ArgumentCaptor.forClass(SubmitCommentReportCommand.class);
        verify(submitCommentReportUseCase).execute(captor.capture());
        assertThat(captor.getValue().reporterUserId()).isEqualTo(USER_1_ID);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user cannot submit report on Wiki article (redirected by security)")
    void shouldRedirectAnonymousWhenSubmittingWikiReport() throws Exception {
        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM"
                                }
                                """))
                .andExpect(status().is3xxRedirection());

        verify(submitCommentReportUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "scholar@universe.local", roles = "USER")
    @DisplayName("Unauthenticated request missing identity accessor returns 403 Forbidden")
    void shouldRejectUnauthenticatedWithoutIdentityOnWiki() throws Exception {
        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM"
                                }
                                """))
                .andExpect(status().isForbidden());

        verify(submitCommentReportUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "scholar@universe.local", roles = "USER")
    @DisplayName("Report submission without CSRF is rejected by security")
    void shouldRejectReportSubmissionWhenCsrfMissingOnWiki() throws Exception {
        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM"
                                }
                                """))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/access-denied"));

        verify(submitCommentReportUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "scholar@universe.local", roles = "USER")
    @DisplayName("Should return 404 when Wiki article is not published")
    void shouldReturn404WhenWikiArticleNotPublished() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(false);

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM"
                                }
                                """))
                .andExpect(status().isNotFound());

        verify(validateCommentTargetScopeUseCase, never()).execute(any(), any());
        verify(submitCommentReportUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "scholar@universe.local", roles = "USER")
    @DisplayName("Should return 404 when target scope validation fails on Wiki")
    void shouldReturn404WhenTargetScopeMismatchOnWiki() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        doThrow(new CommentNotFoundException(ROOT_COMMENT_ID))
                .when(validateCommentTargetScopeUseCase)
                .execute(ROOT_COMMENT_ID, CommentTarget.wikiArticle(ARTICLE_ID));

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM"
                                }
                                """))
                .andExpect(status().isNotFound());

        verify(submitCommentReportUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "scholar@universe.local", roles = "USER")
    @DisplayName("Should return 409 Conflict when duplicate pending report exists on Wiki")
    void shouldReturn409WhenDuplicatePendingReportOnWiki() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        when(submitCommentReportUseCase.execute(any(SubmitCommentReportCommand.class)))
                .thenThrow(new DuplicatePendingReportException(ROOT_COMMENT_ID, USER_1_ID));

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "scholar@universe.local", roles = "USER")
    @DisplayName("Should return 403 Forbidden when user attempts self-report on Wiki")
    void shouldReturn403WhenSelfReportingOnWiki() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        when(submitCommentReportUseCase.execute(any(SubmitCommentReportCommand.class)))
                .thenThrow(new SelfReportNotAllowedException(ROOT_COMMENT_ID, USER_1_ID));

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "scholar@universe.local", roles = "USER")
    @DisplayName("Should return 404 Not Found when comment is not reportable on Wiki")
    void shouldReturn404WhenCommentNotReportableOnWiki() throws Exception {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        when(submitCommentReportUseCase.execute(any(SubmitCommentReportCommand.class)))
                .thenThrow(new CommentNotReportableException(ROOT_COMMENT_ID, "Comment is deleted."));

        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "SPAM"
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "scholar@universe.local", roles = "USER")
    @DisplayName("Should return 400 Bad Request when request body is empty or reason is null on Wiki")
    void shouldReturn400WhenReasonMissingOnWiki() throws Exception {
        // Missing body
        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // Null reason
        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Missing reason field\"}"))
                .andExpect(status().isBadRequest());

        // Invalid enum string
        mockMvc.perform(post("/api/wiki/articles/" + ARTICLE_ID + "/comments/" + ROOT_COMMENT_ID + "/reports")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"INVALID_REASON\"}"))
                .andExpect(status().isBadRequest());

        verify(submitCommentReportUseCase, never()).execute(any());
    }
}
