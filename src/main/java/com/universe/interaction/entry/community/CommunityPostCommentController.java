package com.universe.interaction.entry.community;

import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.interaction.application.exceptions.CommentHasRepliesException;
import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
import com.universe.interaction.application.mutation.CreateRootCommentCommand;
import com.universe.interaction.application.mutation.CreateRootCommentUseCase;
import com.universe.interaction.application.mutation.ReplyCommentCommand;
import com.universe.interaction.application.mutation.ReplyCommentUseCase;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.community.dto.CommunityCommentCreatedResponseDTO;
import com.universe.interaction.entry.community.dto.CommunityDiscussionFeedResponseDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.CreateCommentRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * REST API controller exposing Interaction comment operations for Community posts.
 *
 * <p>Preserves clean architecture, security, and route integrity contracts:
 * <ul>
 *   <li>Actor identity is derived exclusively from {@link AuthenticatedRequestIdentityAccessor};</li>
 *   <li>Every endpoint verifies that the target Community post exists and is public, failing closed with 404;</li>
 *   <li>Strict target-scope validation ensures comments belonging to other targets cannot be read or replied to;</li>
 *   <li>Returns immutable entry DTOs, keeping domain and persistence models private;</li>
 *   <li>Creation responses return the authoritative updated comment count for live client synchronisation.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/community/posts/{postId}/comments")
public class CommunityPostCommentController {

    private static final Logger log = LoggerFactory.getLogger(CommunityPostCommentController.class);
    private static final int MAX_PAGE_SIZE = 50;

    private final CommunityPostQueryPort communityPostQueryPort;
    private final CommunityPostDiscussionQueryCoordinator communityPostDiscussionQueryCoordinator;
    private final CreateRootCommentUseCase createRootCommentUseCase;
    private final ReplyCommentUseCase replyCommentUseCase;
    private final ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;
    private final GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase;

    public CommunityPostCommentController(
            CommunityPostQueryPort communityPostQueryPort,
            CommunityPostDiscussionQueryCoordinator communityPostDiscussionQueryCoordinator,
            CreateRootCommentUseCase createRootCommentUseCase,
            ReplyCommentUseCase replyCommentUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase
    ) {
        this.communityPostQueryPort = Objects.requireNonNull(
                communityPostQueryPort, "CommunityPostQueryPort cannot be null."
        );
        this.communityPostDiscussionQueryCoordinator = Objects.requireNonNull(
                communityPostDiscussionQueryCoordinator, "CommunityPostDiscussionQueryCoordinator cannot be null."
        );
        this.createRootCommentUseCase = Objects.requireNonNull(
                createRootCommentUseCase, "CreateRootCommentUseCase cannot be null."
        );
        this.replyCommentUseCase = Objects.requireNonNull(
                replyCommentUseCase, "ReplyCommentUseCase cannot be null."
        );
        this.validateCommentTargetScopeUseCase = Objects.requireNonNull(
                validateCommentTargetScopeUseCase, "ValidateCommentTargetScopeUseCase cannot be null."
        );
        this.getCommentTargetMetricsUseCase = Objects.requireNonNull(
                getCommentTargetMetricsUseCase, "GetCommentTargetMetricsUseCase cannot be null."
        );
    }

    /**
     * GET /api/community/posts/{postId}/comments
     * Returns a paginated slice of active root comment items with reply counts for a public Community post.
     */
    @GetMapping
    public ResponseEntity<CommunityDiscussionFeedResponseDTO> getDiscussionFeed(
            @PathVariable UUID postId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request
    ) {
        if (postId == null || page < 0 || size <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }

        UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElse(null);

        CommunityDiscussionFeedResponseDTO response =
                communityPostDiscussionQueryCoordinator.getDiscussionFeed(postId, page, size, viewerUserId);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/community/posts/{postId}/comments/{rootCommentId}/thread
     * Returns the full discussion thread (active root comment and visible flat replies) for a public Community post.
     */
    @GetMapping("/{rootCommentId}/thread")
    public ResponseEntity<CommentThreadResponseDTO> getCommentThread(
            @PathVariable UUID postId,
            @PathVariable UUID rootCommentId,
            HttpServletRequest request
    ) {
        if (postId == null || rootCommentId == null) {
            return ResponseEntity.badRequest().build();
        }

        UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElse(null);

        CommentThreadResponseDTO response =
                communityPostDiscussionQueryCoordinator.getCommentThread(postId, rootCommentId, viewerUserId);

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/community/posts/{postId}/comments
     * Creates an active root comment on a public Community post.
     */
    @PostMapping
    public ResponseEntity<CommunityCommentCreatedResponseDTO> createRootComment(
            @PathVariable UUID postId,
            @RequestBody(required = false) CreateCommentRequest requestBody,
            HttpServletRequest request
    ) {
        if (postId == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);
        validateBody(requestBody != null ? requestBody.body() : null);

        ensurePublicPost(postId);

        CommentTarget target = CommentTarget.communityPost(postId);
        CreateRootCommentCommand command = new CreateRootCommentCommand(actorUserId, target, requestBody.body());
        Comment created = createRootCommentUseCase.execute(command);

        long updatedCount = getUpdatedCommentCount(postId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CommunityCommentCreatedResponseDTO(created.getId(), updatedCount));
    }

    /**
     * POST /api/community/posts/{postId}/comments/{parentCommentId}/replies
     * Creates a reply under the specified parent comment in the public Community post.
     */
    @PostMapping("/{parentCommentId}/replies")
    public ResponseEntity<CommunityCommentCreatedResponseDTO> replyComment(
            @PathVariable UUID postId,
            @PathVariable UUID parentCommentId,
            @RequestBody(required = false) CreateCommentRequest requestBody,
            HttpServletRequest request
    ) {
        if (postId == null || parentCommentId == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);
        validateBody(requestBody != null ? requestBody.body() : null);

        ensurePublicPost(postId);

        CommentTarget expectedTarget = CommentTarget.communityPost(postId);
        validateCommentTargetScopeUseCase.execute(parentCommentId, expectedTarget);

        ReplyCommentCommand command = new ReplyCommentCommand(actorUserId, parentCommentId, requestBody.body());
        Comment reply = replyCommentUseCase.execute(command);

        long updatedCount = getUpdatedCommentCount(postId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CommunityCommentCreatedResponseDTO(reply.getId(), updatedCount));
    }

    private void ensurePublicPost(UUID postId) {
        if (postId == null || communityPostQueryPort.findPublicPostById(postId).isEmpty()) {
            throw new CommentTargetNotEligibleException(CommentTarget.communityPost(postId));
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
        if (body.trim().length() > Comment.MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("Comment body cannot exceed " + Comment.MAX_BODY_LENGTH + " characters.");
        }
    }

    private long getUpdatedCommentCount(UUID postId) {
        return getCommentTargetMetricsUseCase
                .execute(CommentTarget.communityPost(postId))
                .commentCount();
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
            CommentTargetNotEligibleException.class
    })
    public ResponseEntity<Void> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler({
            CommentHasRepliesException.class
    })
    public ResponseEntity<Void> handleConflict(Exception ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler({
            CommentMutationForbiddenException.class
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
        log.error("Unexpected error in CommunityPostCommentController", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
