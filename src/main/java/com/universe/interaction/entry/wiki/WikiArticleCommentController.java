package com.universe.interaction.entry.wiki;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
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
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.CommentCreatedResponse;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentReportResponseDTO;
import com.universe.interaction.entry.dto.CommentRevisionReadDTO;
import com.universe.interaction.entry.dto.CommentRevisionSliceResponseDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.CreateCommentRequest;
import com.universe.interaction.entry.dto.EditCommentRequest;
import com.universe.interaction.entry.dto.SubmitCommentReportRequest;
import com.universe.interaction.entry.wiki.dto.WikiDiscussionFeedResponseDTO;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * REST API controller exposing Interaction comment operations for Wiki articles.
 *
 * <p>Preserves clean architecture, security, and route integrity contracts:
 * <ul>
 *   <li>Actor identity is derived exclusively from {@link AuthenticatedRequestIdentityAccessor};</li>
 *   <li>Every endpoint verifies that the target Wiki article is published and readable, failing closed with 404;</li>
 *   <li>Strict target-scope validation ensures comments belonging to other targets cannot be read or mutated;</li>
 *   <li>Returns immutable entry DTOs, keeping domain and persistence models private.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/wiki/articles/{articleId}/comments")
public class WikiArticleCommentController {

    private static final Logger log = LoggerFactory.getLogger(WikiArticleCommentController.class);
    private static final int MAX_PAGE_SIZE = 50;

    private final WikiArticleQueryPort wikiArticleQueryPort;
    private final WikiArticleDiscussionQueryCoordinator wikiArticleDiscussionQueryCoordinator;
    private final CreateRootCommentUseCase createRootCommentUseCase;
    private final ReplyCommentUseCase replyCommentUseCase;
    private final EditCommentUseCase editCommentUseCase;
    private final DeleteCommentUseCase deleteCommentUseCase;
    private final ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;
    private final GetPublicCommentRevisionsUseCase getPublicCommentRevisionsUseCase;
    private final SubmitCommentReportUseCase submitCommentReportUseCase;

    public WikiArticleCommentController(
            WikiArticleQueryPort wikiArticleQueryPort,
            WikiArticleDiscussionQueryCoordinator wikiArticleDiscussionQueryCoordinator,
            CreateRootCommentUseCase createRootCommentUseCase,
            ReplyCommentUseCase replyCommentUseCase,
            EditCommentUseCase editCommentUseCase,
            DeleteCommentUseCase deleteCommentUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            GetPublicCommentRevisionsUseCase getPublicCommentRevisionsUseCase,
            SubmitCommentReportUseCase submitCommentReportUseCase
    ) {
        this.wikiArticleQueryPort = Objects.requireNonNull(wikiArticleQueryPort, "WikiArticleQueryPort cannot be null.");
        this.wikiArticleDiscussionQueryCoordinator = Objects.requireNonNull(wikiArticleDiscussionQueryCoordinator, "WikiArticleDiscussionQueryCoordinator cannot be null.");
        this.createRootCommentUseCase = Objects.requireNonNull(createRootCommentUseCase, "CreateRootCommentUseCase cannot be null.");
        this.replyCommentUseCase = Objects.requireNonNull(replyCommentUseCase, "ReplyCommentUseCase cannot be null.");
        this.editCommentUseCase = Objects.requireNonNull(editCommentUseCase, "EditCommentUseCase cannot be null.");
        this.deleteCommentUseCase = Objects.requireNonNull(deleteCommentUseCase, "DeleteCommentUseCase cannot be null.");
        this.validateCommentTargetScopeUseCase = Objects.requireNonNull(validateCommentTargetScopeUseCase, "ValidateCommentTargetScopeUseCase cannot be null.");
        this.getPublicCommentRevisionsUseCase = Objects.requireNonNull(getPublicCommentRevisionsUseCase, "GetPublicCommentRevisionsUseCase cannot be null.");
        this.submitCommentReportUseCase = Objects.requireNonNull(submitCommentReportUseCase, "SubmitCommentReportUseCase cannot be null.");
    }

    /**
     * GET /api/wiki/articles/{articleId}/comments
     * Returns a paginated slice of active root comment discussion threads for a published Wiki article.
     */
    @GetMapping({"", "/feed"})
    public ResponseEntity<WikiDiscussionFeedResponseDTO> getDiscussionFeed(
            @PathVariable UUID articleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        if (articleId == null || page < 0 || size <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }

        UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElse(null);

        WikiDiscussionFeedResponseDTO response =
                wikiArticleDiscussionQueryCoordinator.getDiscussionFeed(articleId, page, size, viewerUserId);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/wiki/articles/{articleId}/comments/{rootCommentId}/thread
     * Returns the full discussion thread (active root comment and visible flat replies) for a published Wiki article.
     */
    @GetMapping("/{rootCommentId}/thread")
    public ResponseEntity<CommentThreadResponseDTO> getCommentThread(
            @PathVariable UUID articleId,
            @PathVariable UUID rootCommentId,
            HttpServletRequest request
    ) {
        if (articleId == null || rootCommentId == null) {
            return ResponseEntity.badRequest().build();
        }

        UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElse(null);

        CommentThreadResponseDTO response =
                wikiArticleDiscussionQueryCoordinator.getCommentThread(articleId, rootCommentId, viewerUserId);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/wiki/articles/{articleId}/comments/{commentId}/revisions
     * Returns a slice of public edit history for an active comment under a published Wiki article.
     */
    @GetMapping("/{commentId}/revisions")
    public ResponseEntity<CommentRevisionSliceResponseDTO> listCommentRevisions(
            @PathVariable UUID articleId,
            @PathVariable UUID commentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (articleId == null || commentId == null || page < 0 || size <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }

        ensurePublishedArticle(articleId);

        CommentTarget expectedTarget = CommentTarget.wikiArticle(articleId);
        CommentRevisionSlice slice = getPublicCommentRevisionsUseCase.execute(commentId, expectedTarget, page, size);

        List<CommentRevisionReadDTO> items = slice.items().stream()
                .map(CommentRevisionReadDTO::from)
                .toList();

        return ResponseEntity.ok(new CommentRevisionSliceResponseDTO(items, slice.page(), slice.size(), slice.hasNext()));
    }

    /**
     * POST /api/wiki/articles/{articleId}/comments
     * Creates a new root comment on the published Wiki article.
     */
    @PostMapping
    public ResponseEntity<CommentCreatedResponse> createRootComment(
            @PathVariable UUID articleId,
            @RequestBody(required = false) CreateCommentRequest requestBody,
            HttpServletRequest request
    ) {
        if (articleId == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);
        validateBody(requestBody != null ? requestBody.body() : null);

        ensurePublishedArticle(articleId);

        CommentTarget target = CommentTarget.wikiArticle(articleId);
        CreateRootCommentCommand command = new CreateRootCommentCommand(actorUserId, target, requestBody.body());
        Comment created = createRootCommentUseCase.execute(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CommentCreatedResponse(created.getId()));
    }

    /**
     * POST /api/wiki/articles/{articleId}/comments/{parentCommentId}/replies
     * Creates a reply under the specified parent comment in the published Wiki article.
     */
    @PostMapping("/{parentCommentId}/replies")
    public ResponseEntity<CommentCreatedResponse> replyComment(
            @PathVariable UUID articleId,
            @PathVariable UUID parentCommentId,
            @RequestBody(required = false) CreateCommentRequest requestBody,
            HttpServletRequest request
    ) {
        if (articleId == null || parentCommentId == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);
        validateBody(requestBody != null ? requestBody.body() : null);

        ensurePublishedArticle(articleId);

        CommentTarget expectedTarget = CommentTarget.wikiArticle(articleId);
        validateCommentTargetScopeUseCase.execute(parentCommentId, expectedTarget);

        ReplyCommentCommand command = new ReplyCommentCommand(actorUserId, parentCommentId, requestBody.body());
        Comment reply = replyCommentUseCase.execute(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CommentCreatedResponse(reply.getId()));
    }

    /**
     * PATCH /api/wiki/articles/{articleId}/comments/{commentId}
     * Edits the body of an existing active comment owned by the caller.
     */
    @PatchMapping("/{commentId}")
    public ResponseEntity<Void> editComment(
            @PathVariable UUID articleId,
            @PathVariable UUID commentId,
            @RequestBody(required = false) EditCommentRequest requestBody,
            HttpServletRequest request
    ) {
        if (articleId == null || commentId == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);
        validateBody(requestBody != null ? requestBody.body() : null);

        ensurePublishedArticle(articleId);

        CommentTarget expectedTarget = CommentTarget.wikiArticle(articleId);
        validateCommentTargetScopeUseCase.execute(commentId, expectedTarget);

        EditCommentCommand command = new EditCommentCommand(actorUserId, commentId, requestBody.body());
        editCommentUseCase.execute(command);

        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /api/wiki/articles/{articleId}/comments/{commentId}
     * Soft-deletes (tombstones) an existing comment owned by the caller.
     */
    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable UUID articleId,
            @PathVariable UUID commentId,
            HttpServletRequest request
    ) {
        if (articleId == null || commentId == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);

        ensurePublishedArticle(articleId);

        CommentTarget expectedTarget = CommentTarget.wikiArticle(articleId);
        validateCommentTargetScopeUseCase.execute(commentId, expectedTarget);

        DeleteCommentCommand command = new DeleteCommentCommand(actorUserId, commentId);
        deleteCommentUseCase.execute(command);

        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/wiki/articles/{articleId}/comments/{commentId}/reports
     * Submits a user report against an interaction comment on the Wiki article.
     */
    @PostMapping("/{commentId}/reports")
    public ResponseEntity<CommentReportResponseDTO> submitCommentReport(
            @PathVariable UUID articleId,
            @PathVariable UUID commentId,
            @RequestBody(required = false) SubmitCommentReportRequest requestBody,
            HttpServletRequest request
    ) {
        if (articleId == null || commentId == null || requestBody == null || requestBody.reason() == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);

        ensurePublishedArticle(articleId);

        CommentTarget expectedTarget = CommentTarget.wikiArticle(articleId);
        validateCommentTargetScopeUseCase.execute(commentId, expectedTarget);

        SubmitCommentReportCommand command = new SubmitCommentReportCommand(
                commentId,
                actorUserId,
                requestBody.reason(),
                requestBody.description()
        );
        var report = submitCommentReportUseCase.execute(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommentReportResponseDTO.from(report));
    }

    private void ensurePublishedArticle(UUID articleId) {
        if (articleId == null || !wikiArticleQueryPort.isPublished(articleId)) {
            throw new PublishedWikiArticleNotFoundException(articleId);
        }
    }

    private UUID resolveAuthenticatedActor(HttpServletRequest request) {
        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOptional.isEmpty()) {
            throw new CommentMutationForbiddenException("Authentication is required for comment mutations.");
        }
        return identityOptional.get().userId();
    }

    private void validateBody(String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("Comment body cannot be blank.");
        }
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Void> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler({
            CommentNotFoundException.class,
            CommentTargetNotEligibleException.class,
            PublishedWikiArticleNotFoundException.class,
            WikiArticleNotFoundException.class,
            CommentNotReportableException.class
    })
    public ResponseEntity<Void> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(DuplicatePendingReportException.class)
    public ResponseEntity<Void> handleConflict(DuplicatePendingReportException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler({
            CommentMutationForbiddenException.class,
            SelfReportNotAllowedException.class
    })
    public ResponseEntity<Void> handleForbidden(Exception ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(CommentThreadIntegrityException.class)
    public ResponseEntity<Void> handleThreadIntegrity(CommentThreadIntegrityException ex) {
        log.error("Comment thread integrity failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Void> handleGenericException(Exception ex) {
        log.error("Unexpected error in WikiArticleCommentController", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
