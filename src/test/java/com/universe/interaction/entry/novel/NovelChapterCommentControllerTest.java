package com.universe.interaction.entry.novel;

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
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
import com.universe.interaction.application.mutation.CreateRootCommentCommand;
import com.universe.interaction.application.mutation.CreateRootCommentUseCase;
import com.universe.interaction.application.mutation.DeleteCommentCommand;
import com.universe.interaction.application.mutation.DeleteCommentUseCase;
import com.universe.interaction.application.mutation.EditCommentCommand;
import com.universe.interaction.application.mutation.EditCommentUseCase;
import com.universe.interaction.application.mutation.ReplyCommentCommand;
import com.universe.interaction.application.mutation.ReplyCommentUseCase;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.FindVisibleRootCommentIdsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.novel.application.anchor.ChapterAnchorResolutionBulkView;
import com.universe.novel.application.anchor.ResolveChapterCommentAnchorsForChapterUseCase;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableChapterReference;
import com.universe.novel.application.exceptions.ChapterCommentAnchorVersionConflictException;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolutionStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NovelChapterCommentController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("NovelChapterCommentController Integration Tests")
class NovelChapterCommentControllerTest {

    private static final UUID CHAPTER_A_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_B_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_1_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID USER_2_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ROOT_COMMENT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID REPLY_COMMENT_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID COMMENT_FROM_B_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

    @MockBean
    private AccountStatusFilter accountStatusFilter;

    @MockBean
    private CustomAuthenticationFailureHandler authenticationFailureHandler;

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private com.universe.shared.security.AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;

    @MockBean
    private ListCommentRootsUseCase listCommentRootsUseCase;

    @MockBean
    private GetCommentThreadUseCase getCommentThreadUseCase;

    @MockBean
    private ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;

    @MockBean
    private CreateRootCommentUseCase createRootCommentUseCase;

    @MockBean
    private ReplyCommentUseCase replyCommentUseCase;

    @MockBean
    private EditCommentUseCase editCommentUseCase;

    @MockBean
    private DeleteCommentUseCase deleteCommentUseCase;

    @MockBean
    private FindVisibleRootCommentIdsUseCase findVisibleRootCommentIdsUseCase;

    @MockBean
    private ResolveChapterCommentAnchorsForChapterUseCase resolveChapterCommentAnchorsForChapterUseCase;

    @MockBean
    private NovelInlineCommentCreationCoordinator novelInlineCommentCreationCoordinator;

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
                "reader@universe.local",
                "Reader User",
                null,
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
    @DisplayName("Anonymous user can list active root comments on published chapter")
    void shouldListRootCommentsOnPublishedChapter() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_A_ID, 1)));

        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        Comment root = Comment.createRoot(ROOT_COMMENT_ID, target, USER_1_ID, "Root comment body", NOW);
        CommentReadItem item = CommentReadItem.fromRoot(root);
        CommentReadSlice slice = new CommentReadSlice(List.of(item), 0, 20, false);

        when(listCommentRootsUseCase.execute(target, 0, 20)).thenReturn(slice);

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.items[0].id").value(ROOT_COMMENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].authorUserId").value(USER_1_ID.toString()))
                .andExpect(jsonPath("$.items[0].body").value("Root comment body"))
                .andExpect(jsonPath("$.items[0].parentCommentId").doesNotExist())
                .andExpect(jsonPath("$.items[0].replyToAuthorUserId").doesNotExist())
                .andExpect(jsonPath("$.items[0].tombstone").value(false));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Should return 404 when listing comments on unpublished or missing chapter")
    void shouldReturn404WhenChapterUnpublishedOnList() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments"))
                .andExpect(status().isNotFound());

        verify(listCommentRootsUseCase, never()).execute(any(), any(int.class), any(int.class));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user can read thread for published chapter and valid root")
    void shouldGetCommentThreadSuccessfully() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_A_ID, 1)));

        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        doNothing().when(validateCommentTargetScopeUseCase).executeRoot(ROOT_COMMENT_ID, target);

        Comment root = Comment.createRoot(ROOT_COMMENT_ID, target, USER_1_ID, "Root body", NOW);
        Comment reply = Comment.createReply(REPLY_COMMENT_ID, root, USER_2_ID, "Reply body", NOW.plusSeconds(60));

        CommentReadItem rootItem = CommentReadItem.fromRoot(root);
        CommentReadItem replyItem = CommentReadItem.fromActiveReply(reply, USER_1_ID);
        CommentThreadView threadView = new CommentThreadView(rootItem, List.of(replyItem));

        when(getCommentThreadUseCase.execute(ROOT_COMMENT_ID)).thenReturn(threadView);

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID + "/thread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root.id").value(ROOT_COMMENT_ID.toString()))
                .andExpect(jsonPath("$.root.body").value("Root body"))
                .andExpect(jsonPath("$.replies[0].id").value(REPLY_COMMENT_ID.toString()))
                .andExpect(jsonPath("$.replies[0].parentCommentId").value(ROOT_COMMENT_ID.toString()))
                .andExpect(jsonPath("$.replies[0].replyToAuthorUserId").value(USER_1_ID.toString()))
                .andExpect(jsonPath("$.replies[0].body").value("Reply body"))
                .andExpect(jsonPath("$.replies[0].tombstone").value(false));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Should return 404 when reading thread on unpublished chapter")
    void shouldReturn404WhenChapterUnpublishedOnThread() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID + "/thread"))
                .andExpect(status().isNotFound());

        verify(getCommentThreadUseCase, never()).execute(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Should return 404 when thread root belongs to a different chapter")
    void shouldReturn404WhenThreadRootBelongsToDifferentChapter() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_A_ID, 1)));

        CommentTarget expectedTarget = CommentTarget.novelChapter(CHAPTER_A_ID);
        doThrow(new CommentNotFoundException("Comment " + COMMENT_FROM_B_ID + " does not belong to target " + expectedTarget))
                .when(validateCommentTargetScopeUseCase).executeRoot(COMMENT_FROM_B_ID, expectedTarget);

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + COMMENT_FROM_B_ID + "/thread"))
                .andExpect(status().isNotFound());

        verify(getCommentThreadUseCase, never()).execute(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Should return 404 and not invoke getCommentThreadUseCase when supplied comment ID is a reply not a root")
    void shouldReturn404WhenSuppliedCommentIdIsReplyNotRoot() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_A_ID, 1)));

        CommentTarget expectedTarget = CommentTarget.novelChapter(CHAPTER_A_ID);
        doThrow(new CommentNotFoundException("Comment " + REPLY_COMMENT_ID + " is not a thread root."))
                .when(validateCommentTargetScopeUseCase).executeRoot(REPLY_COMMENT_ID, expectedTarget);

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + REPLY_COMMENT_ID + "/thread"))
                .andExpect(status().isNotFound());

        verify(getCommentThreadUseCase, never()).execute(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Should return 500 when real CommentThreadIntegrityException is thrown by GetCommentThreadUseCase")
    void shouldReturn500WhenThreadIntegrityExceptionOccurs() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_A_ID, 1)));

        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        doNothing().when(validateCommentTargetScopeUseCase).executeRoot(ROOT_COMMENT_ID, target);
        when(getCommentThreadUseCase.execute(ROOT_COMMENT_ID))
                .thenThrow(new CommentThreadIntegrityException("Corrupted thread hierarchy"));

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID + "/thread"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Should return 404 when thread root is deleted")
    void shouldReturn404WhenThreadRootIsDeleted() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_A_ID, 1)));

        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        doNothing().when(validateCommentTargetScopeUseCase).executeRoot(ROOT_COMMENT_ID, target);
        when(getCommentThreadUseCase.execute(ROOT_COMMENT_ID))
                .thenThrow(new CommentNotFoundException(ROOT_COMMENT_ID));

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID + "/thread"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Should return 400 when pagination arguments are invalid")
    void shouldReturn400ForInvalidPagination() throws Exception {
        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments")
                        .param("page", "-1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments")
                        .param("page", "0")
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Should clamp requested page size to maximum 50")
    void shouldClampPageSizeTo50() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_A_ID, 1)));

        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        when(listCommentRootsUseCase.execute(target, 0, 50))
                .thenReturn(new CommentReadSlice(List.of(), 0, 50, false));

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments")
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk());

        verify(listCommentRootsUseCase).execute(target, 0, 50);
    }

    // =========================================================================
    // 2. CREATE ROOT MUTATION TESTS
    // =========================================================================

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should create root comment with 201 Created and trusted actor")
    void shouldCreateRootCommentSuccessfully() throws Exception {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        Comment created = Comment.createRoot(ROOT_COMMENT_ID, target, USER_1_ID, "My comment body", NOW);

        when(createRootCommentUseCase.execute(any(CreateRootCommentCommand.class))).thenReturn(created);

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"My comment body\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(ROOT_COMMENT_ID.toString()));

        ArgumentCaptor<CreateRootCommentCommand> captor = ArgumentCaptor.forClass(CreateRootCommentCommand.class);
        verify(createRootCommentUseCase).execute(captor.capture());
        assertThat(captor.getValue().actorUserId()).isEqualTo(USER_1_ID);
        assertThat(captor.getValue().target()).isEqualTo(target);
        assertThat(captor.getValue().body()).isEqualTo("My comment body");
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reject root comment with 404 when target is not eligible")
    void shouldRejectRootCommentWhenTargetNotEligible() throws Exception {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        when(createRootCommentUseCase.execute(any(CreateRootCommentCommand.class)))
                .thenThrow(new CommentTargetNotEligibleException(target));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Draft chapter comment\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reject root comment with 400 when body is blank")
    void shouldRejectRootCommentWhenBodyIsBlank() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"   \"}"))
                .andExpect(status().isBadRequest());

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reject root comment when CSRF is missing according to Spring Security behavior")
    void shouldRejectRootCommentWhenCsrfMissing() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments")
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"CSRF missing body\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user cannot create root comment (redirected to login)")
    void shouldRedirectAnonymousWhenCreatingRootComment() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Anonymous comment\"}"))
                .andExpect(status().is3xxRedirection());

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should return 400 when request body contains malformed JSON")
    void shouldReturn400WhenRequestBodyIsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": unclosed-json"))
                .andExpect(status().isBadRequest());

        verify(createRootCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should ignore or reject malicious actorUserId in request JSON and preserve trusted actor")
    void shouldPreserveTrustedActorWhenMaliciousActorInJson() throws Exception {
        UUID maliciousUserId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        Comment created = Comment.createRoot(ROOT_COMMENT_ID, target, USER_1_ID, "Legitimate comment", NOW);
        when(createRootCommentUseCase.execute(any(CreateRootCommentCommand.class))).thenReturn(created);

        var resultActions = mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Legitimate comment\", \"actorUserId\": \"" + maliciousUserId + "\"}"));

        int status = resultActions.andReturn().getResponse().getStatus();
        if (status == 201) {
            ArgumentCaptor<CreateRootCommentCommand> captor = ArgumentCaptor.forClass(CreateRootCommentCommand.class);
            verify(createRootCommentUseCase).execute(captor.capture());
            assertThat(captor.getValue().actorUserId()).isEqualTo(USER_1_ID);
            assertThat(captor.getValue().actorUserId()).isNotEqualTo(maliciousUserId);
        } else if (status == 400) {
            verify(createRootCommentUseCase, never()).execute(any());
        } else {
            org.junit.jupiter.api.Assertions.fail("Expected HTTP 201 with trusted actor or HTTP 400 rejection, but got " + status);
        }
    }

    // =========================================================================
    // 3. REPLY MUTATION TESTS
    // =========================================================================

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reply to comment with 201 Created and trusted actor")
    void shouldReplyCommentSuccessfully() throws Exception {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        doNothing().when(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        Comment root = Comment.createRoot(ROOT_COMMENT_ID, target, USER_1_ID, "Root body", NOW);
        Comment reply = Comment.createReply(REPLY_COMMENT_ID, root, USER_2_ID, "My reply body", NOW.plusSeconds(30));

        when(replyCommentUseCase.execute(any(ReplyCommentCommand.class))).thenReturn(reply);

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_2_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"My reply body\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(REPLY_COMMENT_ID.toString()));

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        ArgumentCaptor<ReplyCommentCommand> captor = ArgumentCaptor.forClass(ReplyCommentCommand.class);
        verify(replyCommentUseCase).execute(captor.capture());
        assertThat(captor.getValue().actorUserId()).isEqualTo(USER_2_ID);
        assertThat(captor.getValue().parentCommentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(captor.getValue().body()).isEqualTo("My reply body");
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reject reply with 404 when parent comment belongs to a different chapter")
    void shouldRejectReplyWhenParentBelongsToDifferentChapter() throws Exception {
        CommentTarget expectedTarget = CommentTarget.novelChapter(CHAPTER_A_ID);
        doThrow(new CommentNotFoundException("Target mismatch"))
                .when(validateCommentTargetScopeUseCase).execute(COMMENT_FROM_B_ID, expectedTarget);

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + COMMENT_FROM_B_ID + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_2_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Cross-chapter reply\"}"))
                .andExpect(status().isNotFound());

        verify(replyCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reject reply with 403 Forbidden when parent comment is deleted")
    void shouldRejectReplyWhenParentIsDeleted() throws Exception {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        doNothing().when(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        when(replyCommentUseCase.execute(any(ReplyCommentCommand.class)))
                .thenThrow(new CommentMutationForbiddenException("Cannot reply to a deleted comment"));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_2_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Reply to deleted parent\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reject reply with 404 when chapter is not eligible")
    void shouldRejectReplyWhenChapterNotEligible() throws Exception {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        doNothing().when(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        when(replyCommentUseCase.execute(any(ReplyCommentCommand.class)))
                .thenThrow(new CommentTargetNotEligibleException(target));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_2_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Reply on unpublished chapter\"}"))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 4. EDIT MUTATION TESTS
    // =========================================================================

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should edit comment with 204 No Content for author")
    void shouldEditCommentSuccessfully() throws Exception {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        doNothing().when(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        Comment edited = Comment.createRoot(ROOT_COMMENT_ID, target, USER_1_ID, "Edited body", NOW);
        when(editCommentUseCase.execute(any(EditCommentCommand.class))).thenReturn(edited);

        mockMvc.perform(patch("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Edited body\"}"))
                .andExpect(status().isNoContent());

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        ArgumentCaptor<EditCommentCommand> captor = ArgumentCaptor.forClass(EditCommentCommand.class);
        verify(editCommentUseCase).execute(captor.capture());
        assertThat(captor.getValue().actorUserId()).isEqualTo(USER_1_ID);
        assertThat(captor.getValue().commentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(captor.getValue().newBody()).isEqualTo("Edited body");
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reject edit with 403 Forbidden when user is not author")
    void shouldRejectEditWhenNotAuthor() throws Exception {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        doNothing().when(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        when(editCommentUseCase.execute(any(EditCommentCommand.class)))
                .thenThrow(new CommentMutationForbiddenException("User is not the author"));

        mockMvc.perform(patch("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_2_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Malicious edit\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reject edit with 404 when comment belongs to a different chapter")
    void shouldRejectEditWhenCommentBelongsToDifferentChapter() throws Exception {
        CommentTarget expectedTarget = CommentTarget.novelChapter(CHAPTER_A_ID);
        doThrow(new CommentNotFoundException("Target mismatch"))
                .when(validateCommentTargetScopeUseCase).execute(COMMENT_FROM_B_ID, expectedTarget);

        mockMvc.perform(patch("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + COMMENT_FROM_B_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Cross-chapter edit\"}"))
                .andExpect(status().isNotFound());

        verify(editCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Author can edit comment even if chapter is unpublished (no publication check on edit)")
    void shouldAllowEditOnUnpublishedChapter() throws Exception {
        // ReaderChapterAccessQueryPort is intentionally NOT checked on edit
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        doNothing().when(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        Comment edited = Comment.createRoot(ROOT_COMMENT_ID, target, USER_1_ID, "Edited on unpublished", NOW);
        when(editCommentUseCase.execute(any(EditCommentCommand.class))).thenReturn(edited);

        mockMvc.perform(patch("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Edited on unpublished\"}"))
                .andExpect(status().isNoContent());

        verify(readerChapterAccessQueryPort, never()).findPublishedById(any());
        verify(editCommentUseCase).execute(any());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reject comment edit when CSRF is missing")
    void shouldRejectEditCommentWhenCsrfMissing() throws Exception {
        mockMvc.perform(patch("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Updated content without CSRF\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(editCommentUseCase, never()).execute(any());
        verify(validateCommentTargetScopeUseCase, never()).execute(any(), any());
    }

    // =========================================================================
    // 5. DELETE MUTATION TESTS
    // =========================================================================

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should delete comment with 204 No Content for author")
    void shouldDeleteCommentSuccessfully() throws Exception {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        doNothing().when(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        Comment deleted = Comment.createRoot(ROOT_COMMENT_ID, target, USER_1_ID, "Body", NOW);
        deleted.delete(NOW.plusSeconds(10));
        when(deleteCommentUseCase.execute(any(DeleteCommentCommand.class))).thenReturn(deleted);

        mockMvc.perform(delete("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID)))
                .andExpect(status().isNoContent());

        verify(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        ArgumentCaptor<DeleteCommentCommand> captor = ArgumentCaptor.forClass(DeleteCommentCommand.class);
        verify(deleteCommentUseCase).execute(captor.capture());
        assertThat(captor.getValue().actorUserId()).isEqualTo(USER_1_ID);
        assertThat(captor.getValue().commentId()).isEqualTo(ROOT_COMMENT_ID);
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reject delete with 403 Forbidden when user is not author")
    void shouldRejectDeleteWhenNotAuthor() throws Exception {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        doNothing().when(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        when(deleteCommentUseCase.execute(any(DeleteCommentCommand.class)))
                .thenThrow(new CommentMutationForbiddenException("User is not the author"));

        mockMvc.perform(delete("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_2_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reject delete with 404 when comment belongs to a different chapter")
    void shouldRejectDeleteWhenCommentBelongsToDifferentChapter() throws Exception {
        CommentTarget expectedTarget = CommentTarget.novelChapter(CHAPTER_A_ID);
        doThrow(new CommentNotFoundException("Target mismatch"))
                .when(validateCommentTargetScopeUseCase).execute(COMMENT_FROM_B_ID, expectedTarget);

        mockMvc.perform(delete("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + COMMENT_FROM_B_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID)))
                .andExpect(status().isNotFound());

        verify(deleteCommentUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Author can delete comment even if chapter is unpublished (no publication check on delete)")
    void shouldAllowDeleteOnUnpublishedChapter() throws Exception {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        doNothing().when(validateCommentTargetScopeUseCase).execute(ROOT_COMMENT_ID, target);

        Comment deleted = Comment.createRoot(ROOT_COMMENT_ID, target, USER_1_ID, "Body", NOW);
        deleted.delete(NOW.plusSeconds(10));
        when(deleteCommentUseCase.execute(any(DeleteCommentCommand.class))).thenReturn(deleted);

        mockMvc.perform(delete("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID)))
                .andExpect(status().isNoContent());

        verify(readerChapterAccessQueryPort, never()).findPublishedById(any());
        verify(deleteCommentUseCase).execute(any());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Should reject comment deletion when CSRF is missing")
    void shouldRejectDeleteCommentWhenCsrfMissing() throws Exception {
        mockMvc.perform(delete("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + ROOT_COMMENT_ID)
                        .with(authenticatedIdentity(USER_1_ID)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(deleteCommentUseCase, never()).execute(any());
        verify(validateCommentTargetScopeUseCase, never()).execute(any(), any());
    }

    // =========================================================================
    // 6. TARGET-SCOPE CROSS-CHAPTER SECURITY REGRESSION
    // =========================================================================

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Target scope regression: Chapter A route must NEVER touch Chapter B comment across thread, reply, edit, delete")
    void shouldEnforceTargetScopeIntegrityAcrossAllOperations() throws Exception {
        CommentTarget targetA = CommentTarget.novelChapter(CHAPTER_A_ID);

        doThrow(new CommentNotFoundException("Comment " + COMMENT_FROM_B_ID + " does not belong to target " + targetA))
                .when(validateCommentTargetScopeUseCase).execute(COMMENT_FROM_B_ID, targetA);
        doThrow(new CommentNotFoundException("Comment " + COMMENT_FROM_B_ID + " does not belong to target " + targetA))
                .when(validateCommentTargetScopeUseCase).executeRoot(COMMENT_FROM_B_ID, targetA);

        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_A_ID, 1)));

        // 1. Thread read rejected
        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + COMMENT_FROM_B_ID + "/thread"))
                .andExpect(status().isNotFound());

        // 2. Reply rejected
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + COMMENT_FROM_B_ID + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Reply to B from A\"}"))
                .andExpect(status().isNotFound());

        // 3. Edit rejected
        mockMvc.perform(patch("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + COMMENT_FROM_B_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Edit B from A\"}"))
                .andExpect(status().isNotFound());

        // 4. Delete rejected
        mockMvc.perform(delete("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/" + COMMENT_FROM_B_ID)
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID)))
                .andExpect(status().isNotFound());

        verify(getCommentThreadUseCase, never()).execute(any());
        verify(replyCommentUseCase, never()).execute(any());
        verify(editCommentUseCase, never()).execute(any());
        verify(deleteCommentUseCase, never()).execute(any());
    }

    // =========================================================================
    // GET /api/novel/chapters/{chapterId}/comments/indicators
    // =========================================================================

    @Test
    @DisplayName("GET indicators: returns 404 when chapter is not published")
    void shouldReturn404WhenChapterNotPublishedForIndicators() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/indicators"))
                .andExpect(status().isNotFound());

        verify(findVisibleRootCommentIdsUseCase, never()).execute(any());
        verify(resolveChapterCommentAnchorsForChapterUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("GET indicators: returns empty array when no active root comments exist")
    void shouldReturnEmptyListWhenNoActiveRoots() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_A_ID, 1)));

        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        when(findVisibleRootCommentIdsUseCase.execute(target)).thenReturn(java.util.Set.of());

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/indicators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        verify(resolveChapterCommentAnchorsForChapterUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("GET indicators: returns empty array when active roots have no anchors")
    void shouldReturnEmptyListWhenNoAnchors() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_A_ID, 1)));

        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        when(findVisibleRootCommentIdsUseCase.execute(target)).thenReturn(java.util.Set.of(ROOT_COMMENT_ID));

        when(resolveChapterCommentAnchorsForChapterUseCase.execute(CHAPTER_A_ID))
                .thenReturn(new ChapterAnchorResolutionBulkView(List.of(), List.of("blk-1")));

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/indicators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET indicators: returns indicators in Reader block document order, correctly counting visible roots")
    void shouldReturnIndicatorsInReaderBlockDocumentOrderWithCorrectCounts() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_A_ID, 1)));

        UUID root1 = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID root2 = UUID.fromString("20000000-0000-0000-0000-000000000002");
        UUID root3 = UUID.fromString("30000000-0000-0000-0000-000000000003");
        UUID root4Stale = UUID.fromString("40000000-0000-0000-0000-000000000004");
        UUID rootUnanchored = UUID.fromString("50000000-0000-0000-0000-000000000005");
        UUID rootDeleted = UUID.fromString("60000000-0000-0000-0000-000000000006");

        // Active roots contains root1, root2, root3, root4Stale, rootUnanchored (NOT rootDeleted)
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        when(findVisibleRootCommentIdsUseCase.execute(target))
                .thenReturn(java.util.Set.of(root1, root2, root3, root4Stale, rootUnanchored));

        // Reader block document order: blk-doc-1, blk-doc-2, blk-doc-3
        List<String> orderedBlocks = List.of("blk-doc-1", "blk-doc-2", "blk-doc-3");

        // Resolutions:
        // root1: CURRENT on blk-doc-2
        // root2: CURRENT on blk-doc-1
        // root3: RELOCATED on blk-doc-2 (same block as root1: one CURRENT + one RELOCATED -> threadCount == 2)
        // root4Stale: STALE (no resolved block)
        // rootDeleted: CURRENT on blk-doc-1 (should be filtered out because not in active roots)
        List<ChapterAnchorResolutionBulkView.ResolutionRow> resolutions = List.of(
                new ChapterAnchorResolutionBulkView.ResolutionRow(root1, ChapterCommentAnchorResolutionStatus.CURRENT, "blk-doc-2"),
                new ChapterAnchorResolutionBulkView.ResolutionRow(root2, ChapterCommentAnchorResolutionStatus.CURRENT, "blk-doc-1"),
                new ChapterAnchorResolutionBulkView.ResolutionRow(root3, ChapterCommentAnchorResolutionStatus.RELOCATED, "blk-doc-2"),
                new ChapterAnchorResolutionBulkView.ResolutionRow(root4Stale, ChapterCommentAnchorResolutionStatus.STALE, null),
                new ChapterAnchorResolutionBulkView.ResolutionRow(rootDeleted, ChapterCommentAnchorResolutionStatus.CURRENT, "blk-doc-1")
        );

        when(resolveChapterCommentAnchorsForChapterUseCase.execute(CHAPTER_A_ID))
                .thenReturn(new ChapterAnchorResolutionBulkView(resolutions, orderedBlocks));

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/indicators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                // Document order item 0: blk-doc-1 (threadCount = 1)
                .andExpect(jsonPath("$[0].blockKey").value("blk-doc-1"))
                .andExpect(jsonPath("$[0].threadCount").value(1))
                // Document order item 1: blk-doc-2 (threadCount = 2)
                .andExpect(jsonPath("$[1].blockKey").value("blk-doc-2"))
                .andExpect(jsonPath("$[1].threadCount").value(2));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET indicators: allows anonymous access without redirect")
    void shouldAllowAnonymousAccessToIndicators() throws Exception {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_A_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_A_ID, 1)));

        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        when(findVisibleRootCommentIdsUseCase.execute(target)).thenReturn(java.util.Set.of());

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/indicators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // =========================================================================
    // 7. CREATE INLINE COMMENT MUTATION TESTS
    // =========================================================================

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST inline comment: creates inline comment with 201 Created and trusted actor")
    void shouldCreateInlineCommentSuccessfully() throws Exception {
        when(novelInlineCommentCreationCoordinator.createInlineComment(
                eq(USER_1_ID),
                eq(CHAPTER_A_ID),
                eq("Inline comment text"),
                eq(1L),
                eq("blk-1"),
                eq(5),
                eq(15)
        )).thenReturn(ROOT_COMMENT_ID);

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "body": "Inline comment text",
                                  "anchor": {
                                    "contentVersion": 1,
                                    "blockKey": "blk-1",
                                    "startOffset": 5,
                                    "endOffset": 15
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(ROOT_COMMENT_ID.toString()));

        verify(novelInlineCommentCreationCoordinator).createInlineComment(
                USER_1_ID, CHAPTER_A_ID, "Inline comment text", 1L, "blk-1", 5, 15
        );
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST inline comment: ignores client-provided actor identity and uses authenticated principal")
    void shouldIgnoreClientActorIdentityWhenCreatingInlineComment() throws Exception {
        when(novelInlineCommentCreationCoordinator.createInlineComment(
                any(), any(), any(), any(long.class), any(), any(int.class), any(int.class)
        )).thenReturn(ROOT_COMMENT_ID);

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorUserId": "99999999-9999-9999-9999-999999999999",
                                  "body": "Inline comment text",
                                  "anchor": {
                                    "contentVersion": 1,
                                    "blockKey": "blk-1",
                                    "startOffset": 5,
                                    "endOffset": 15
                                  }
                                }
                                """))
                .andExpect(status().isCreated());

        verify(novelInlineCommentCreationCoordinator).createInlineComment(
                eq(USER_1_ID), eq(CHAPTER_A_ID), eq("Inline comment text"), eq(1L), eq("blk-1"), eq(5), eq(15)
        );
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST inline comment: rejects when CSRF token is missing")
    void shouldRejectInlineCommentWhenCsrfMissing() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "body": "CSRF missing body",
                                  "anchor": {
                                    "contentVersion": 1,
                                    "blockKey": "blk-1",
                                    "startOffset": 0,
                                    "endOffset": 5
                                  }
                                }
                                """))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(novelInlineCommentCreationCoordinator, never()).createInlineComment(any(), any(), any(), any(long.class), any(), any(int.class), any(int.class));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("POST inline comment: rejects anonymous user")
    void shouldRedirectAnonymousWhenCreatingInlineComment() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "body": "Anonymous comment",
                                  "anchor": {
                                    "contentVersion": 1,
                                    "blockKey": "blk-1",
                                    "startOffset": 0,
                                    "endOffset": 5
                                  }
                                }
                                """))
                .andExpect(status().is3xxRedirection());

        verify(novelInlineCommentCreationCoordinator, never()).createInlineComment(any(), any(), any(), any(long.class), any(), any(int.class), any(int.class));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST inline comment: rejects 400 when body is blank")
    void shouldRejectInlineCommentWhenBodyIsBlank() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "body": "   ",
                                  "anchor": {
                                    "contentVersion": 1,
                                    "blockKey": "blk-1",
                                    "startOffset": 0,
                                    "endOffset": 5
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(novelInlineCommentCreationCoordinator, never()).createInlineComment(any(), any(), any(), any(long.class), any(), any(int.class), any(int.class));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST inline comment: rejects 400 when anchor is missing")
    void shouldRejectInlineCommentWhenAnchorMissing() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "body": "Valid body"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(novelInlineCommentCreationCoordinator, never()).createInlineComment(any(), any(), any(), any(long.class), any(), any(int.class), any(int.class));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST inline comment: rejects 400 when anchor contentVersion is invalid")
    void shouldRejectInlineCommentWhenContentVersionInvalid() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "body": "Valid body",
                                  "anchor": {
                                    "contentVersion": 0,
                                    "blockKey": "blk-1",
                                    "startOffset": 0,
                                    "endOffset": 5
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(novelInlineCommentCreationCoordinator, never()).createInlineComment(any(), any(), any(), any(long.class), any(), any(int.class), any(int.class));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST inline comment: rejects 400 when anchor blockKey is blank")
    void shouldRejectInlineCommentWhenBlockKeyBlank() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "body": "Valid body",
                                  "anchor": {
                                    "contentVersion": 1,
                                    "blockKey": "   ",
                                    "startOffset": 0,
                                    "endOffset": 5
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(novelInlineCommentCreationCoordinator, never()).createInlineComment(any(), any(), any(), any(long.class), any(), any(int.class), any(int.class));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST inline comment: rejects 400 when startOffset is negative")
    void shouldRejectInlineCommentWhenStartOffsetNegative() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "body": "Valid body",
                                  "anchor": {
                                    "contentVersion": 1,
                                    "blockKey": "blk-1",
                                    "startOffset": -1,
                                    "endOffset": 5
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(novelInlineCommentCreationCoordinator, never()).createInlineComment(any(), any(), any(), any(long.class), any(), any(int.class), any(int.class));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST inline comment: rejects 400 when startOffset >= endOffset")
    void shouldRejectInlineCommentWhenOffsetsInvalid() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "body": "Valid body",
                                  "anchor": {
                                    "contentVersion": 1,
                                    "blockKey": "blk-1",
                                    "startOffset": 5,
                                    "endOffset": 5
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(novelInlineCommentCreationCoordinator, never()).createInlineComment(any(), any(), any(), any(long.class), any(), any(int.class), any(int.class));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST inline comment: rejects 400 when payload is malformed JSON")
    void shouldRejectInlineCommentWhenMalformedJson() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest());

        verify(novelInlineCommentCreationCoordinator, never()).createInlineComment(any(), any(), any(), any(long.class), any(), any(int.class), any(int.class));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST inline comment: returns 409 Conflict when anchor version conflict occurs")
    void shouldReturn409WhenAnchorVersionConflict() throws Exception {
        when(novelInlineCommentCreationCoordinator.createInlineComment(
                eq(USER_1_ID), eq(CHAPTER_A_ID), any(), eq(1L), any(), any(int.class), any(int.class)
        )).thenThrow(new ChapterCommentAnchorVersionConflictException(CHAPTER_A_ID, 1L, 2L));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "body": "Inline comment",
                                  "anchor": {
                                    "contentVersion": 1,
                                    "blockKey": "blk-1",
                                    "startOffset": 0,
                                    "endOffset": 5
                                  }
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST inline comment: returns 404 Not Found when chapter is not eligible")
    void shouldReturn404WhenChapterNotEligibleForInlineComment() throws Exception {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);
        when(novelInlineCommentCreationCoordinator.createInlineComment(
                eq(USER_1_ID), eq(CHAPTER_A_ID), any(), any(long.class), any(), any(int.class), any(int.class)
        )).thenThrow(new CommentTargetNotEligibleException(target));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_A_ID + "/comments/inline")
                        .with(csrf())
                        .with(authenticatedIdentity(USER_1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "body": "Inline comment",
                                  "anchor": {
                                    "contentVersion": 1,
                                    "blockKey": "blk-1",
                                    "startOffset": 0,
                                    "endOffset": 5
                                  }
                                }
                                """))
                .andExpect(status().isNotFound());
    }
}
