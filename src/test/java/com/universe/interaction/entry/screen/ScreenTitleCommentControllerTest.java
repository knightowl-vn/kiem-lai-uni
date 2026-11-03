package com.universe.interaction.entry.screen;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
import com.universe.interaction.application.exceptions.SelfReportNotAllowedException;
import com.universe.interaction.application.mutation.CreateRootCommentUseCase;
import com.universe.interaction.application.mutation.DeleteCommentUseCase;
import com.universe.interaction.application.mutation.EditCommentUseCase;
import com.universe.interaction.application.mutation.ReplyCommentUseCase;
import com.universe.interaction.application.mutation.SubmitCommentReportUseCase;
import com.universe.interaction.application.ports.CommentRevisionSlice;
import com.universe.interaction.application.query.GetPublicCommentRevisionsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentRevision;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.screen.dto.ScreenDiscussionFeedResponseDTO;
import com.universe.screen.application.exceptions.PublishedScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenTitle;
import com.universe.shared.security.AuthenticatedEmailResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ScreenTitleCommentController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-screen-comments-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("ScreenTitleCommentController Integration Tests")
class ScreenTitleCommentControllerTest {

    private static final UUID TITLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOT_COMMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REPLY_COMMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ACTOR_USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

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
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private ScreenTitleRepositoryPort screenTitleRepositoryPort;

    @MockBean
    private ScreenEntryRepositoryPort screenEntryRepositoryPort;

    @MockBean
    private ScreenTitleDiscussionQueryCoordinator screenTitleDiscussionQueryCoordinator;

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
                "fan@universe.local",
                "Screen Fan",
                null,
                "screen_fan",
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
    @DisplayName("Guest can read discussion feed for published title")
    void guestCanReadDiscussionFeedForPublishedTitle() throws Exception {
        ScreenDiscussionFeedResponseDTO feedResponse = new ScreenDiscussionFeedResponseDTO(
                List.of(),
                0,
                0,
                0,
                20,
                false
        );

        when(screenTitleDiscussionQueryCoordinator.getDiscussionFeed(
                eq(TITLE_ID), eq(0), eq(20), eq(null), eq(CommentSortMode.FEATURED)
        )).thenReturn(feedResponse);

        mockMvc.perform(get("/api/screen/titles/{titleId}/comments", TITLE_ID)
                        .param("sort", "FEATURED")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadCount").value(0))
                .andExpect(jsonPath("$.commentCount").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Guest reading comments feed on unknown or unpublished title returns 404")
    void guestReadingCommentsFeedOnUnpublishedTitleReturns404() throws Exception {
        when(screenTitleDiscussionQueryCoordinator.getDiscussionFeed(
                eq(TITLE_ID), any(Integer.class), any(Integer.class), any(), any()
        )).thenThrow(new PublishedScreenTitleNotFoundException(TITLE_ID.toString()));

        mockMvc.perform(get("/api/screen/titles/{titleId}/comments", TITLE_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Guest can read single comment thread for published title")
    void guestCanReadSingleCommentThreadForPublishedTitle() throws Exception {
        CommentReadDTO rootDTO = new CommentReadDTO(
                ROOT_COMMENT_ID,
                ACTOR_USER_ID,
                null,
                null,
                "Root comment body",
                false,
                NOW,
                NOW,
                new CommentAuthorDTO(ACTOR_USER_ID, "Screen Fan", null, "screen_fan"),
                false,
                false
        );
        CommentThreadResponseDTO threadResponse = new CommentThreadResponseDTO(rootDTO, List.of());

        when(screenTitleDiscussionQueryCoordinator.getCommentThread(eq(TITLE_ID), eq(ROOT_COMMENT_ID), eq(null)))
                .thenReturn(threadResponse);

        mockMvc.perform(get("/api/screen/titles/{titleId}/comments/{rootCommentId}/thread", TITLE_ID, ROOT_COMMENT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root.id").value(ROOT_COMMENT_ID.toString()))
                .andExpect(jsonPath("$.root.body").value("Root comment body"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Guest can list comment revisions for published title")
    void guestCanListCommentRevisionsForPublishedTitle() throws Exception {
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));

        CommentRevision rev = new CommentRevision(
                UUID.randomUUID(),
                ROOT_COMMENT_ID,
                1,
                "Old revision body",
                NOW
        );
        CommentRevisionSlice slice = new CommentRevisionSlice(List.of(rev), 0, 20, false);
        when(getPublicCommentRevisionsUseCase.execute(eq(ROOT_COMMENT_ID), eq(CommentTarget.screenTitle(TITLE_ID)), eq(0), eq(20)))
                .thenReturn(slice);

        mockMvc.perform(get("/api/screen/titles/{titleId}/comments/{commentId}/revisions", TITLE_ID, ROOT_COMMENT_ID)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].body").value("Old revision body"))
                .andExpect(jsonPath("$.items[0].revisionNumber").value(1));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Guest reading revisions on non-existent or unpublished title returns 404")
    void guestReadingRevisionsOnUnpublishedTitleReturns404() throws Exception {
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/screen/titles/{titleId}/comments/{commentId}/revisions", TITLE_ID, ROOT_COMMENT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 2. GUEST MUTATION REJECTION (AUTHENTICATION REQUIRED)
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("Guest cannot post root comment: rejected with 302 redirect to login")
    void guestCannotPostRootComment() throws Exception {
        mockMvc.perform(post("/api/screen/titles/{titleId}/comments", TITLE_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Hello guest\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Guest cannot reply: rejected with 302 redirect to login")
    void guestCannotReplyComment() throws Exception {
        mockMvc.perform(post("/api/screen/titles/{titleId}/comments/{parentCommentId}/replies", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Reply guest\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(replyCommentUseCase, never()).execute(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Guest cannot edit comment: rejected with 302 redirect to login")
    void guestCannotEditComment() throws Exception {
        mockMvc.perform(patch("/api/screen/titles/{titleId}/comments/{commentId}", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Edited body\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(editCommentUseCase, never()).execute(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Guest cannot delete comment: rejected with 302 redirect to login")
    void guestCannotDeleteComment() throws Exception {
        mockMvc.perform(delete("/api/screen/titles/{titleId}/comments/{commentId}", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(deleteCommentUseCase, never()).execute(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Guest cannot report comment: rejected with 302 redirect to login")
    void guestCannotReportComment() throws Exception {
        mockMvc.perform(post("/api/screen/titles/{titleId}/comments/{commentId}/reports", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SPAM\",\"description\":\"Spam\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(submitCommentReportUseCase, never()).execute(any());
    }

    // =========================================================================
    // 3. AUTHENTICATED MUTATIONS (SUCCESS CASES)
    // =========================================================================

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Authenticated user can post root comment on published title")
    void authenticatedUserCanPostRootComment() throws Exception {
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));

        Comment created = Comment.createRoot(
                ROOT_COMMENT_ID,
                CommentTarget.screenTitle(TITLE_ID),
                ACTOR_USER_ID,
                "New root comment",
                NOW
        );
        when(createRootCommentUseCase.execute(any())).thenReturn(created);

        mockMvc.perform(post("/api/screen/titles/{titleId}/comments", TITLE_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"New root comment\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(ROOT_COMMENT_ID.toString()));
    }

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Authenticated user can reply to comment on published title")
    void authenticatedUserCanReplyComment() throws Exception {
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));

        Comment root = Comment.createRoot(
                ROOT_COMMENT_ID,
                CommentTarget.screenTitle(TITLE_ID),
                ACTOR_USER_ID,
                "Root body",
                NOW
        );
        Comment reply = Comment.createReply(
                REPLY_COMMENT_ID,
                root,
                ACTOR_USER_ID,
                "A reply comment",
                NOW
        );
        when(replyCommentUseCase.execute(any())).thenReturn(reply);

        mockMvc.perform(post("/api/screen/titles/{titleId}/comments/{parentCommentId}/replies", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"A reply comment\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(REPLY_COMMENT_ID.toString()));

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, CommentTarget.screenTitle(TITLE_ID));
    }

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Authenticated user can post root comment with valid episode origin")
    void authenticatedUserCanPostRootCommentWithValidEpisodeOrigin() throws Exception {
        UUID entryId = UUID.randomUUID();
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));

        ScreenEntry mockEntry = Mockito.mock(ScreenEntry.class);
        when(mockEntry.isPublished()).thenReturn(true);
        when(mockEntry.getTitleId()).thenReturn(TITLE_ID);
        when(screenEntryRepositoryPort.findPublishedById(entryId)).thenReturn(Optional.of(mockEntry));

        Comment created = Comment.createRoot(
                ROOT_COMMENT_ID,
                CommentTarget.screenTitle(TITLE_ID),
                ACTOR_USER_ID,
                "Episode 1 discussion",
                NOW,
                entryId
        );
        when(createRootCommentUseCase.execute(any())).thenReturn(created);

        mockMvc.perform(post("/api/screen/titles/{titleId}/comments", TITLE_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Episode 1 discussion\",\"originScreenEntryId\":\"" + entryId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(ROOT_COMMENT_ID.toString()));
    }

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Post root comment with invalid originScreenEntry fails with Bad Request")
    void postRootCommentWithInvalidOriginScreenEntryFailsBadRequest() throws Exception {
        UUID entryId = UUID.randomUUID();
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));
        when(screenEntryRepositoryPort.findPublishedById(entryId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/screen/titles/{titleId}/comments", TITLE_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Invalid origin\",\"originScreenEntryId\":\"" + entryId + "\"}"))
                .andExpect(status().isBadRequest());

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Authenticated user can reply comment with valid episode origin")
    void authenticatedUserCanReplyCommentWithValidEpisodeOrigin() throws Exception {
        UUID entryId = UUID.randomUUID();
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));

        ScreenEntry mockEntry = Mockito.mock(ScreenEntry.class);
        when(mockEntry.isPublished()).thenReturn(true);
        when(mockEntry.getTitleId()).thenReturn(TITLE_ID);
        when(screenEntryRepositoryPort.findPublishedById(entryId)).thenReturn(Optional.of(mockEntry));

        Comment root = Comment.createRoot(
                ROOT_COMMENT_ID,
                CommentTarget.screenTitle(TITLE_ID),
                ACTOR_USER_ID,
                "Root body",
                NOW
        );
        Comment reply = Comment.createReply(
                REPLY_COMMENT_ID,
                root,
                ACTOR_USER_ID,
                "A reply comment on episode",
                NOW,
                entryId
        );
        when(replyCommentUseCase.execute(any())).thenReturn(reply);

        mockMvc.perform(post("/api/screen/titles/{titleId}/comments/{parentCommentId}/replies", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"A reply comment on episode\",\"originScreenEntryId\":\"" + entryId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(REPLY_COMMENT_ID.toString()));
    }

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Reply comment with unpublished originScreenEntry fails with Bad Request")
    void replyCommentWithUnpublishedOriginScreenEntryFailsBadRequest() throws Exception {
        UUID entryId = UUID.randomUUID();
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));
        when(screenEntryRepositoryPort.findPublishedById(entryId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/screen/titles/{titleId}/comments/{parentCommentId}/replies", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"A reply on draft\",\"originScreenEntryId\":\"" + entryId + "\"}"))
                .andExpect(status().isBadRequest());

        verify(replyCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Authenticated author can edit their comment")
    void authenticatedAuthorCanEditComment() throws Exception {
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));
        when(editCommentUseCase.execute(any())).thenReturn(Mockito.mock(Comment.class));

        mockMvc.perform(patch("/api/screen/titles/{titleId}/comments/{commentId}", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Updated content\"}"))
                .andExpect(status().isNoContent());

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, CommentTarget.screenTitle(TITLE_ID));
    }

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Authenticated author can delete their comment")
    void authenticatedAuthorCanDeleteComment() throws Exception {
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));
        doNothing().when(deleteCommentUseCase).execute(any());

        mockMvc.perform(delete("/api/screen/titles/{titleId}/comments/{commentId}", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID)))
                .andExpect(status().isNoContent());

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, CommentTarget.screenTitle(TITLE_ID));
    }

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Authenticated user can report a comment on published title")
    void authenticatedUserCanReportComment() throws Exception {
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));

        UUID reportId = UUID.randomUUID();
        InteractionReport report = InteractionReport.createPending(
                reportId,
                ReportTargetType.COMMENT,
                ROOT_COMMENT_ID,
                ACTOR_USER_ID,
                ReportReason.SPAM,
                "Spam comment",
                "Root comment body",
                null,
                NOW
        );
        when(submitCommentReportUseCase.execute(any())).thenReturn(report);

        mockMvc.perform(post("/api/screen/titles/{titleId}/comments/{commentId}/reports", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SPAM\",\"description\":\"Spam comment\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").value(reportId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, CommentTarget.screenTitle(TITLE_ID));
    }

    // =========================================================================
    // 4. SECURITY & SCOPE REJECTIONS
    // =========================================================================

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Mutation on unpublished title returns 404")
    void mutationOnUnpublishedTitleReturns404() throws Exception {
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/screen/titles/{titleId}/comments", TITLE_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Hello\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Cross-target comment mutation throws CommentThreadIntegrityException: returns 400")
    void crossTargetCommentMutationReturns400() throws Exception {
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));

        doThrow(new CommentThreadIntegrityException("Comment does not belong to this ScreenTitle"))
                .when(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, CommentTarget.screenTitle(TITLE_ID));

        mockMvc.perform(delete("/api/screen/titles/{titleId}/comments/{commentId}", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Non-author edit throws CommentMutationForbiddenException: returns 403")
    void nonAuthorEditCommentReturns403() throws Exception {
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));

        doThrow(new CommentMutationForbiddenException("Only author can edit comment"))
                .when(editCommentUseCase).execute(any());

        mockMvc.perform(patch("/api/screen/titles/{titleId}/comments/{commentId}", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Unauthorized edit\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Non-author delete throws CommentMutationForbiddenException: returns 403")
    void nonAuthorDeleteCommentReturns403() throws Exception {
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));

        doThrow(new CommentMutationForbiddenException("Only author can delete comment"))
                .when(deleteCommentUseCase).execute(any());

        mockMvc.perform(delete("/api/screen/titles/{titleId}/comments/{commentId}", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "screen_fan", roles = "USER")
    @DisplayName("Self-report throws SelfReportNotAllowedException: returns 400")
    void selfReportReturns400() throws Exception {
        ScreenTitle mockTitle = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(mockTitle));

        doThrow(new SelfReportNotAllowedException("Users cannot report their own comments"))
                .when(submitCommentReportUseCase).execute(any());

        mockMvc.perform(post("/api/screen/titles/{titleId}/comments/{commentId}/reports", TITLE_ID, ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(ACTOR_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SPAM\",\"description\":\"Self report\"}"))
                .andExpect(status().isBadRequest());
    }
}
