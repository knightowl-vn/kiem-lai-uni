package com.universe.interaction.entry.novel;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
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
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.FindVisibleRootCommentIdsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.ChapterCommentBlockIndicatorDTO;
import com.universe.interaction.entry.dto.CommentCreatedResponse;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentSliceResponseDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.CreateCommentRequest;
import com.universe.interaction.entry.dto.CreateInlineCommentRequest;
import com.universe.interaction.entry.dto.EditCommentRequest;
import com.universe.interaction.entry.dto.InlineTextAnchorRequest;
import com.universe.novel.application.anchor.ChapterAnchorResolutionBulkView;
import com.universe.novel.application.anchor.ResolveChapterCommentAnchorsForChapterUseCase;
import com.universe.novel.application.exceptions.ChapterCommentAnchorVersionConflictException;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolutionStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * REST API controller exposing Interaction comment operations for Novel Reader chapters.
 *
 * <p>Preserves clean architecture, security, and route integrity contracts:
 * <ul>
 *   <li>Actor identity is derived exclusively from {@link AuthenticatedRequestIdentityAccessor};</li>
 *   <li>Public reads require the chapter to satisfy Novel's canonical Reader publication predicate;</li>
 *   <li>Strict target-scope validation ensures comments belonging to other chapters cannot be read or mutated;</li>
 *   <li>Editing and soft-deleting existing owned comments do not require current chapter publication eligibility;</li>
 *   <li>Returns immutable entry DTOs, keeping domain and persistence models private.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/novel/chapters/{chapterId}/comments")
public class NovelChapterCommentController {

    private static final Logger log = LoggerFactory.getLogger(NovelChapterCommentController.class);
    private static final int MAX_PAGE_SIZE = 50;

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final ListCommentRootsUseCase listCommentRootsUseCase;
    private final GetCommentThreadUseCase getCommentThreadUseCase;
    private final ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;
    private final FindVisibleRootCommentIdsUseCase findVisibleRootCommentIdsUseCase;
    private final ResolveChapterCommentAnchorsForChapterUseCase resolveChapterCommentAnchorsForChapterUseCase;
    private final CreateRootCommentUseCase createRootCommentUseCase;
    private final ReplyCommentUseCase replyCommentUseCase;
    private final EditCommentUseCase editCommentUseCase;
    private final DeleteCommentUseCase deleteCommentUseCase;
    private final NovelInlineCommentCreationCoordinator novelInlineCommentCreationCoordinator;

    public NovelChapterCommentController(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            FindVisibleRootCommentIdsUseCase findVisibleRootCommentIdsUseCase,
            ResolveChapterCommentAnchorsForChapterUseCase resolveChapterCommentAnchorsForChapterUseCase,
            CreateRootCommentUseCase createRootCommentUseCase,
            ReplyCommentUseCase replyCommentUseCase,
            EditCommentUseCase editCommentUseCase,
            DeleteCommentUseCase deleteCommentUseCase,
            NovelInlineCommentCreationCoordinator novelInlineCommentCreationCoordinator
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(readerChapterAccessQueryPort, "ReaderChapterAccessQueryPort cannot be null.");
        this.listCommentRootsUseCase = Objects.requireNonNull(listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null.");
        this.getCommentThreadUseCase = Objects.requireNonNull(getCommentThreadUseCase, "GetCommentThreadUseCase cannot be null.");
        this.validateCommentTargetScopeUseCase = Objects.requireNonNull(validateCommentTargetScopeUseCase, "ValidateCommentTargetScopeUseCase cannot be null.");
        this.findVisibleRootCommentIdsUseCase = Objects.requireNonNull(findVisibleRootCommentIdsUseCase, "FindVisibleRootCommentIdsUseCase cannot be null.");
        this.resolveChapterCommentAnchorsForChapterUseCase = Objects.requireNonNull(resolveChapterCommentAnchorsForChapterUseCase, "ResolveChapterCommentAnchorsForChapterUseCase cannot be null.");
        this.createRootCommentUseCase = Objects.requireNonNull(createRootCommentUseCase, "CreateRootCommentUseCase cannot be null.");
        this.replyCommentUseCase = Objects.requireNonNull(replyCommentUseCase, "ReplyCommentUseCase cannot be null.");
        this.editCommentUseCase = Objects.requireNonNull(editCommentUseCase, "EditCommentUseCase cannot be null.");
        this.deleteCommentUseCase = Objects.requireNonNull(deleteCommentUseCase, "DeleteCommentUseCase cannot be null.");
        this.novelInlineCommentCreationCoordinator = Objects.requireNonNull(novelInlineCommentCreationCoordinator, "NovelInlineCommentCreationCoordinator cannot be null.");
    }

    /**
     * GET /api/novel/chapters/{chapterId}/comments
     * Returns a slice of active root comments for the published chapter.
     */
    @GetMapping
    public ResponseEntity<CommentSliceResponseDTO> listRootComments(
            @PathVariable UUID chapterId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (chapterId == null || page < 0 || size <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }

        if (readerChapterAccessQueryPort.findPublishedById(chapterId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        CommentTarget target = CommentTarget.novelChapter(chapterId);
        CommentReadSlice slice = listCommentRootsUseCase.execute(target, page, size);

        List<CommentReadDTO> items = slice.items().stream()
                .map(CommentReadDTO::from)
                .toList();

        return ResponseEntity.ok(new CommentSliceResponseDTO(items, slice.page(), slice.size(), slice.hasNext()));
    }

    /**
     * GET /api/novel/chapters/{chapterId}/comments/indicators
     * Returns inline discussion indicators for Reader blocks in document order.
     *
     * <p>Preserves indicator invariants:
     * <ul>
     *   <li>Indicator count = visible anchored ROOT discussion thread count;</li>
     *   <li>Only CURRENT or RELOCATED anchors with non-null resolvedBlockKey are included;</li>
     *   <li>STALE, unanchored roots, deleted roots, and replies are ignored;</li>
     *   <li>Ordered by current Reader block document order;</li>
     *   <li>Blocks with 0 count are omitted.</li>
     * </ul>
     */
    @GetMapping("/indicators")
    public ResponseEntity<List<ChapterCommentBlockIndicatorDTO>> getCommentIndicators(
            @PathVariable UUID chapterId
    ) {
        if (chapterId == null) {
            return ResponseEntity.badRequest().build();
        }

        if (readerChapterAccessQueryPort.findPublishedById(chapterId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        CommentTarget target = CommentTarget.novelChapter(chapterId);
        Set<UUID> visibleRootIds = findVisibleRootCommentIdsUseCase.execute(target);
        if (visibleRootIds.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        ChapterAnchorResolutionBulkView bulkView = resolveChapterCommentAnchorsForChapterUseCase.execute(chapterId);
        if (bulkView.resolutions().isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        Map<String, Integer> counts = new HashMap<>();
        for (ChapterAnchorResolutionBulkView.ResolutionRow res : bulkView.resolutions()) {
            if (visibleRootIds.contains(res.rootCommentId())
                    && (res.status() == ChapterCommentAnchorResolutionStatus.CURRENT
                    || res.status() == ChapterCommentAnchorResolutionStatus.RELOCATED)
                    && res.resolvedBlockKey() != null) {
                counts.merge(res.resolvedBlockKey(), 1, Integer::sum);
            }
        }

        List<ChapterCommentBlockIndicatorDTO> indicators = new ArrayList<>();
        for (String blockKey : bulkView.orderedBlockKeys()) {
            Integer count = counts.get(blockKey);
            if (count != null && count > 0) {
                indicators.add(new ChapterCommentBlockIndicatorDTO(blockKey, count));
            }
        }

        return ResponseEntity.ok(indicators);
    }

    /**
     * GET /api/novel/chapters/{chapterId}/comments/{rootCommentId}/thread
     * Returns the full flat discussion thread (active root comment and visible replies).
     */
    @GetMapping("/{rootCommentId}/thread")
    public ResponseEntity<CommentThreadResponseDTO> getCommentThread(
            @PathVariable UUID chapterId,
            @PathVariable UUID rootCommentId
    ) {
        if (chapterId == null || rootCommentId == null) {
            return ResponseEntity.badRequest().build();
        }

        if (readerChapterAccessQueryPort.findPublishedById(chapterId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        CommentTarget expectedTarget = CommentTarget.novelChapter(chapterId);
        validateCommentTargetScopeUseCase.executeRoot(rootCommentId, expectedTarget);

        CommentThreadView threadView = getCommentThreadUseCase.execute(rootCommentId);
        return ResponseEntity.ok(CommentThreadResponseDTO.from(threadView));
    }

    /**
     * POST /api/novel/chapters/{chapterId}/comments
     * Creates a new root comment on the chapter.
     */
    @PostMapping
    public ResponseEntity<CommentCreatedResponse> createRootComment(
            @PathVariable UUID chapterId,
            @RequestBody(required = false) CreateCommentRequest requestBody,
            HttpServletRequest request
    ) {
        if (chapterId == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);
        validateBody(requestBody != null ? requestBody.body() : null);

        CommentTarget target = CommentTarget.novelChapter(chapterId);
        CreateRootCommentCommand command = new CreateRootCommentCommand(actorUserId, target, requestBody.body());
        Comment created = createRootCommentUseCase.execute(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CommentCreatedResponse(created.getId()));
    }

    /**
     * POST /api/novel/chapters/{chapterId}/comments/inline
     * Creates an inline comment with anchored selected text on the chapter.
     */
    @PostMapping("/inline")
    public ResponseEntity<CommentCreatedResponse> createInlineComment(
            @PathVariable UUID chapterId,
            @RequestBody(required = false) CreateInlineCommentRequest requestBody,
            HttpServletRequest request
    ) {
        if (chapterId == null) {
            return ResponseEntity.badRequest().build();
        }
        if (requestBody == null || requestBody.anchor() == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);
        validateBody(requestBody.body());

        InlineTextAnchorRequest anchor = requestBody.anchor();
        if (anchor.blockKey() == null || anchor.blockKey().trim().isEmpty() ||
                anchor.contentVersion() < 1L ||
                anchor.startOffset() < 0 ||
                anchor.endOffset() <= anchor.startOffset()) {
            return ResponseEntity.badRequest().build();
        }

        UUID commentId = novelInlineCommentCreationCoordinator.createInlineComment(
                actorUserId,
                chapterId,
                requestBody.body(),
                anchor.contentVersion(),
                anchor.blockKey(),
                anchor.startOffset(),
                anchor.endOffset()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CommentCreatedResponse(commentId));
    }

    /**
     * POST /api/novel/chapters/{chapterId}/comments/{parentCommentId}/replies
     * Creates a reply under the specified parent comment.
     */
    @PostMapping("/{parentCommentId}/replies")
    public ResponseEntity<CommentCreatedResponse> replyComment(
            @PathVariable UUID chapterId,
            @PathVariable UUID parentCommentId,
            @RequestBody(required = false) CreateCommentRequest requestBody,
            HttpServletRequest request
    ) {
        if (chapterId == null || parentCommentId == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);
        validateBody(requestBody != null ? requestBody.body() : null);

        CommentTarget expectedTarget = CommentTarget.novelChapter(chapterId);
        validateCommentTargetScopeUseCase.execute(parentCommentId, expectedTarget);

        ReplyCommentCommand command = new ReplyCommentCommand(actorUserId, parentCommentId, requestBody.body());
        Comment reply = replyCommentUseCase.execute(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CommentCreatedResponse(reply.getId()));
    }

    /**
     * PATCH /api/novel/chapters/{chapterId}/comments/{commentId}
     * Edits the body of an existing active comment owned by the caller.
     */
    @PatchMapping("/{commentId}")
    public ResponseEntity<Void> editComment(
            @PathVariable UUID chapterId,
            @PathVariable UUID commentId,
            @RequestBody(required = false) EditCommentRequest requestBody,
            HttpServletRequest request
    ) {
        if (chapterId == null || commentId == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);
        validateBody(requestBody != null ? requestBody.body() : null);

        CommentTarget expectedTarget = CommentTarget.novelChapter(chapterId);
        validateCommentTargetScopeUseCase.execute(commentId, expectedTarget);

        EditCommentCommand command = new EditCommentCommand(actorUserId, commentId, requestBody.body());
        editCommentUseCase.execute(command);

        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /api/novel/chapters/{chapterId}/comments/{commentId}
     * Soft-deletes (tombstones) an existing comment owned by the caller.
     */
    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable UUID chapterId,
            @PathVariable UUID commentId,
            HttpServletRequest request
    ) {
        if (chapterId == null || commentId == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);

        CommentTarget expectedTarget = CommentTarget.novelChapter(chapterId);
        validateCommentTargetScopeUseCase.execute(commentId, expectedTarget);

        DeleteCommentCommand command = new DeleteCommentCommand(actorUserId, commentId);
        deleteCommentUseCase.execute(command);

        return ResponseEntity.noContent().build();
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

    @ExceptionHandler({CommentNotFoundException.class, CommentTargetNotEligibleException.class})
    public ResponseEntity<Void> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(ChapterCommentAnchorVersionConflictException.class)
    public ResponseEntity<Void> handleVersionConflict(ChapterCommentAnchorVersionConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(CommentMutationForbiddenException.class)
    public ResponseEntity<Void> handleForbidden(CommentMutationForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(CommentThreadIntegrityException.class)
    public ResponseEntity<Void> handleThreadIntegrity(CommentThreadIntegrityException ex) {
        log.error("Comment thread integrity failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Void> handleGenericException(Exception ex) {
        log.error("Unexpected error in NovelChapterCommentController", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
