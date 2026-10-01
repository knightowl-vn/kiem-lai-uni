package com.universe.interaction.entry.novel;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.interaction.application.exceptions.CommentHasRepliesException;
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
import com.universe.interaction.application.ports.CommentRevisionSlice;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.application.exceptions.CommentNotReportableException;
import com.universe.interaction.application.exceptions.DuplicatePendingReportException;
import com.universe.interaction.application.exceptions.SelfReportNotAllowedException;
import com.universe.interaction.application.mutation.SubmitCommentReportCommand;
import com.universe.interaction.application.mutation.SubmitCommentReportUseCase;
import com.universe.interaction.application.query.CountVisibleActiveRepliesByRootIdsUseCase;
import com.universe.interaction.application.query.FindVisibleRootCommentIdsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.GetPublicCommentRevisionsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.ChapterBlockDiscussionResponseDTO;
import com.universe.interaction.entry.dto.ChapterCommentBlockIndicatorDTO;
import com.universe.interaction.entry.dto.ChapterDiscussionFeedResponseDTO;
import com.universe.interaction.entry.dto.CommentCreatedResponse;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import com.universe.interaction.entry.dto.CommentReportResponseDTO;
import com.universe.interaction.entry.dto.CommentRevisionReadDTO;
import com.universe.interaction.entry.dto.CommentRevisionSliceResponseDTO;
import com.universe.interaction.entry.dto.CommentSliceResponseDTO;
import com.universe.interaction.entry.dto.SubmitCommentReportRequest;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.CreateCommentRequest;
import com.universe.interaction.entry.dto.CreateInlineCommentRequest;
import com.universe.interaction.entry.dto.EditCommentRequest;
import com.universe.interaction.entry.dto.InlineBlockAnchorRequest;
import com.universe.novel.application.anchor.ChapterAnchorResolutionBulkView;
import com.universe.novel.application.anchor.ResolveChapterCommentAnchorsForChapterUseCase;
import com.universe.novel.application.exceptions.ChapterCommentAnchorVersionConflictException;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ReaderBlockNotFoundException;
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
import java.util.stream.Collectors;

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
    private final CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase;
    private final ResolveChapterCommentAnchorsForChapterUseCase resolveChapterCommentAnchorsForChapterUseCase;
    private final CreateRootCommentUseCase createRootCommentUseCase;
    private final ReplyCommentUseCase replyCommentUseCase;
    private final EditCommentUseCase editCommentUseCase;
    private final DeleteCommentUseCase deleteCommentUseCase;
    private final NovelInlineCommentCreationCoordinator novelInlineCommentCreationCoordinator;
    private final NovelBlockDiscussionQueryCoordinator novelBlockDiscussionQueryCoordinator;
    private final NovelChapterDiscussionFeedQueryCoordinator novelChapterDiscussionFeedQueryCoordinator;
    private final GetPublicCommentRevisionsUseCase getPublicCommentRevisionsUseCase;
    private final SubmitCommentReportUseCase submitCommentReportUseCase;
    private final GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase;

    public NovelChapterCommentController(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            FindVisibleRootCommentIdsUseCase findVisibleRootCommentIdsUseCase,
            CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase,
            ResolveChapterCommentAnchorsForChapterUseCase resolveChapterCommentAnchorsForChapterUseCase,
            CreateRootCommentUseCase createRootCommentUseCase,
            ReplyCommentUseCase replyCommentUseCase,
            EditCommentUseCase editCommentUseCase,
            DeleteCommentUseCase deleteCommentUseCase,
            NovelInlineCommentCreationCoordinator novelInlineCommentCreationCoordinator,
            NovelBlockDiscussionQueryCoordinator novelBlockDiscussionQueryCoordinator,
            NovelChapterDiscussionFeedQueryCoordinator novelChapterDiscussionFeedQueryCoordinator,
            GetPublicCommentRevisionsUseCase getPublicCommentRevisionsUseCase,
            SubmitCommentReportUseCase submitCommentReportUseCase,
            GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(readerChapterAccessQueryPort, "ReaderChapterAccessQueryPort cannot be null.");
        this.listCommentRootsUseCase = Objects.requireNonNull(listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null.");
        this.getCommentThreadUseCase = Objects.requireNonNull(getCommentThreadUseCase, "GetCommentThreadUseCase cannot be null.");
        this.validateCommentTargetScopeUseCase = Objects.requireNonNull(validateCommentTargetScopeUseCase, "ValidateCommentTargetScopeUseCase cannot be null.");
        this.findVisibleRootCommentIdsUseCase = Objects.requireNonNull(findVisibleRootCommentIdsUseCase, "FindVisibleRootCommentIdsUseCase cannot be null.");
        this.countVisibleActiveRepliesByRootIdsUseCase = Objects.requireNonNull(countVisibleActiveRepliesByRootIdsUseCase, "CountVisibleActiveRepliesByRootIdsUseCase cannot be null.");
        this.resolveChapterCommentAnchorsForChapterUseCase = Objects.requireNonNull(resolveChapterCommentAnchorsForChapterUseCase, "ResolveChapterCommentAnchorsForChapterUseCase cannot be null.");
        this.createRootCommentUseCase = Objects.requireNonNull(createRootCommentUseCase, "CreateRootCommentUseCase cannot be null.");
        this.replyCommentUseCase = Objects.requireNonNull(replyCommentUseCase, "ReplyCommentUseCase cannot be null.");
        this.editCommentUseCase = Objects.requireNonNull(editCommentUseCase, "EditCommentUseCase cannot be null.");
        this.deleteCommentUseCase = Objects.requireNonNull(deleteCommentUseCase, "DeleteCommentUseCase cannot be null.");
        this.novelInlineCommentCreationCoordinator = Objects.requireNonNull(novelInlineCommentCreationCoordinator, "NovelInlineCommentCreationCoordinator cannot be null.");
        this.novelBlockDiscussionQueryCoordinator = Objects.requireNonNull(novelBlockDiscussionQueryCoordinator, "NovelBlockDiscussionQueryCoordinator cannot be null.");
        this.novelChapterDiscussionFeedQueryCoordinator = Objects.requireNonNull(novelChapterDiscussionFeedQueryCoordinator, "NovelChapterDiscussionFeedQueryCoordinator cannot be null.");
        this.getPublicCommentRevisionsUseCase = Objects.requireNonNull(getPublicCommentRevisionsUseCase, "GetPublicCommentRevisionsUseCase cannot be null.");
        this.submitCommentReportUseCase = Objects.requireNonNull(submitCommentReportUseCase, "SubmitCommentReportUseCase cannot be null.");
        this.getBatchReactionSummariesUseCase = Objects.requireNonNull(getBatchReactionSummariesUseCase, "GetBatchReactionSummariesUseCase cannot be null.");
    }

    /**
     * GET /api/novel/chapters/{chapterId}/comments
     * Returns a slice of active root comments for the published chapter.
     */
    @GetMapping
    public ResponseEntity<CommentSliceResponseDTO> listRootComments(
            @PathVariable UUID chapterId,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        if (chapterId == null || page < 0 || size <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }

        CommentSortMode sortMode = CommentSortMode.parseOrDefault(sort);

        if (readerChapterAccessQueryPort.findPublishedById(chapterId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElse(null);

        CommentTarget target = CommentTarget.novelChapter(chapterId);
        CommentReadSlice slice = listCommentRootsUseCase.execute(target, sortMode, page, size);

        List<UUID> activeIds = slice.items().stream()
                .filter(item -> !item.tombstone())
                .map(CommentReadItem::id)
                .toList();

        Map<UUID, ReactionSummaryResponseDTO> reactionSummaries = Map.of();
        if (!activeIds.isEmpty()) {
            try {
                Map<UUID, ReactionSummary> summaries = getBatchReactionSummariesUseCase.execute(
                        ReactionTargetType.COMMENT,
                        activeIds,
                        viewerUserId
                );
                if (summaries != null && !summaries.isEmpty()) {
                    reactionSummaries = summaries.entrySet().stream()
                            .filter(e -> e.getValue() != null)
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> ReactionSummaryResponseDTO.from(e.getValue())
                            ));
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to batch query reaction summaries for listRootComments: chapterId={}", chapterId, ex);
                reactionSummaries = Map.of();
            }
        }

        final Map<UUID, ReactionSummaryResponseDTO> finalSummaries = reactionSummaries;
        List<CommentReadDTO> items = slice.items().stream()
                .map(item -> CommentReadDTO.from(item, viewerUserId, finalSummaries.get(item.id())))
                .toList();

        return ResponseEntity.ok(new CommentSliceResponseDTO(items, slice.page(), slice.size(), slice.hasNext()));
    }

    /**
     * GET /api/novel/chapters/{chapterId}/comments/feed
     * Returns a slice of active root comments enriched with anchor passage excerpts and reply counts.
     */
    @GetMapping("/feed")
    public ResponseEntity<ChapterDiscussionFeedResponseDTO> getDiscussionFeed(
            @PathVariable UUID chapterId,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        if (chapterId == null || page < 0 || size <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }

        CommentSortMode sortMode = CommentSortMode.parseOrDefault(sort);

        if (readerChapterAccessQueryPort.findPublishedById(chapterId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElse(null);

        ChapterDiscussionFeedResponseDTO response = novelChapterDiscussionFeedQueryCoordinator.getDiscussionFeed(
                chapterId, page, size, viewerUserId, sortMode
        );

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/novel/chapters/{chapterId}/comments/indicators
     * Returns inline discussion indicators for Reader blocks in document order.
     *
     * <p>Preserves indicator invariants:
     * <ul>
     *   <li>threadCount = visible anchored ROOT discussion thread count;</li>
     *   <li>commentCount = total visible active comments (roots + visible active replies);</li>
     *   <li>Only CURRENT or RELOCATED anchors with non-null resolvedBlockKey are included;</li>
     *   <li>STALE, unanchored roots, deleted roots, and tombstones are ignored;</li>
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

        List<UUID> anchoredVisibleRootIds = new ArrayList<>();
        List<ChapterAnchorResolutionBulkView.ResolutionRow> anchoredResolutions = new ArrayList<>();
        for (ChapterAnchorResolutionBulkView.ResolutionRow res : bulkView.resolutions()) {
            if (visibleRootIds.contains(res.rootCommentId())
                    && (res.status() == ChapterCommentAnchorResolutionStatus.CURRENT
                    || res.status() == ChapterCommentAnchorResolutionStatus.RELOCATED)
                    && res.resolvedBlockKey() != null) {
                anchoredVisibleRootIds.add(res.rootCommentId());
                anchoredResolutions.add(res);
            }
        }

        if (anchoredResolutions.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // Single batch query for active replies across all anchored roots (zero N+1)
        Map<UUID, Long> replyCountsByRootId = countVisibleActiveRepliesByRootIdsUseCase.execute(anchoredVisibleRootIds);

        Map<String, Integer> threadCounts = new HashMap<>();
        Map<String, Integer> commentCounts = new HashMap<>();
        for (ChapterAnchorResolutionBulkView.ResolutionRow res : anchoredResolutions) {
            String blockKey = res.resolvedBlockKey();
            threadCounts.merge(blockKey, 1, Integer::sum);
            long activeReplies = replyCountsByRootId.getOrDefault(res.rootCommentId(), 0L);
            commentCounts.merge(blockKey, 1 + (int) activeReplies, Integer::sum);
        }

        List<ChapterCommentBlockIndicatorDTO> indicators = new ArrayList<>();
        for (String blockKey : bulkView.orderedBlockKeys()) {
            Integer tCount = threadCounts.get(blockKey);
            Integer cCount = commentCounts.get(blockKey);
            if (tCount != null && tCount > 0) {
                indicators.add(new ChapterCommentBlockIndicatorDTO(
                        blockKey,
                        tCount,
                        cCount != null ? cCount : tCount
                ));
            }
        }

        return ResponseEntity.ok(indicators);
    }

    /**
     * GET /api/novel/chapters/{chapterId}/comments/blocks/{blockKey}
     * Returns the block discussion response for a canonical Reader block in the current chapter snapshot.
     */
    @GetMapping("/blocks/{blockKey}")
    public ResponseEntity<ChapterBlockDiscussionResponseDTO> getBlockDiscussion(
            @PathVariable UUID chapterId,
            @PathVariable String blockKey,
            HttpServletRequest request
    ) {
        if (chapterId == null || blockKey == null || blockKey.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        if (readerChapterAccessQueryPort.findPublishedById(chapterId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElse(null);

        ChapterBlockDiscussionResponseDTO response =
                novelBlockDiscussionQueryCoordinator.getBlockDiscussion(chapterId, blockKey.trim(), viewerUserId);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/novel/chapters/{chapterId}/comments/{rootCommentId}/thread
     * Returns the full flat discussion thread (active root comment and visible replies).
     */
    @GetMapping("/{rootCommentId}/thread")
    public ResponseEntity<CommentThreadResponseDTO> getCommentThread(
            @PathVariable UUID chapterId,
            @PathVariable UUID rootCommentId,
            HttpServletRequest request
    ) {
        if (chapterId == null || rootCommentId == null) {
            return ResponseEntity.badRequest().build();
        }

        if (readerChapterAccessQueryPort.findPublishedById(chapterId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        CommentTarget expectedTarget = CommentTarget.novelChapter(chapterId);
        validateCommentTargetScopeUseCase.executeRoot(rootCommentId, expectedTarget);

        UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElse(null);

        CommentThreadView threadView = getCommentThreadUseCase.execute(rootCommentId);

        List<UUID> activeIds = new ArrayList<>();
        if (threadView.root() != null && !threadView.root().tombstone()) {
            activeIds.add(threadView.root().id());
        }
        if (threadView.replies() != null) {
            for (CommentReadItem reply : threadView.replies()) {
                if (reply != null && !reply.tombstone()) {
                    activeIds.add(reply.id());
                }
            }
        }

        Map<UUID, ReactionSummaryResponseDTO> reactionSummaries = Map.of();
        if (!activeIds.isEmpty()) {
            try {
                Map<UUID, ReactionSummary> summaries = getBatchReactionSummariesUseCase.execute(
                        ReactionTargetType.COMMENT,
                        activeIds,
                        viewerUserId
                );
                if (summaries != null && !summaries.isEmpty()) {
                    reactionSummaries = summaries.entrySet().stream()
                            .filter(e -> e.getValue() != null)
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> ReactionSummaryResponseDTO.from(e.getValue())
                            ));
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to batch query reaction summaries for getCommentThread: rootCommentId={}", rootCommentId, ex);
                reactionSummaries = Map.of();
            }
        }

        return ResponseEntity.ok(CommentThreadResponseDTO.from(threadView, viewerUserId, reactionSummaries));
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
     * Creates an inline comment with anchored block on the chapter.
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

        InlineBlockAnchorRequest anchor = requestBody.anchor();
        if (anchor.blockKey() == null || anchor.blockKey().trim().isEmpty() ||
                anchor.contentVersion() < 1L) {
            return ResponseEntity.badRequest().build();
        }

        UUID commentId = novelInlineCommentCreationCoordinator.createInlineComment(
                actorUserId,
                chapterId,
                requestBody.body(),
                anchor.contentVersion(),
                anchor.blockKey()
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

    /**
     * GET /api/novel/chapters/{chapterId}/comments/{commentId}/revisions
     * Returns a slice of public edit history for an active comment.
     */
    @GetMapping("/{commentId}/revisions")
    public ResponseEntity<CommentRevisionSliceResponseDTO> listCommentRevisions(
            @PathVariable UUID chapterId,
            @PathVariable UUID commentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (chapterId == null || commentId == null || page < 0 || size <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }

        if (readerChapterAccessQueryPort.findPublishedById(chapterId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        CommentTarget expectedTarget = CommentTarget.novelChapter(chapterId);
        CommentRevisionSlice slice = getPublicCommentRevisionsUseCase.execute(commentId, expectedTarget, page, size);

        List<CommentRevisionReadDTO> items = slice.items().stream()
                .map(CommentRevisionReadDTO::from)
                .toList();

        return ResponseEntity.ok(new CommentRevisionSliceResponseDTO(items, slice.page(), slice.size(), slice.hasNext()));
    }

    /**
     * POST /api/novel/chapters/{chapterId}/comments/{commentId}/reports
     * Submits a user report against an interaction comment on the chapter.
     */
    @PostMapping("/{commentId}/reports")
    public ResponseEntity<CommentReportResponseDTO> submitCommentReport(
            @PathVariable UUID chapterId,
            @PathVariable UUID commentId,
            @RequestBody(required = false) SubmitCommentReportRequest requestBody,
            HttpServletRequest request
    ) {
        if (chapterId == null || commentId == null || requestBody == null || requestBody.reason() == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID actorUserId = resolveAuthenticatedActor(request);

        CommentTarget expectedTarget = CommentTarget.novelChapter(chapterId);
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
            ReaderBlockNotFoundException.class,
            ChapterNotFoundException.class,
            CommentNotReportableException.class
    })
    public ResponseEntity<Void> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler({
            ChapterCommentAnchorVersionConflictException.class,
            DuplicatePendingReportException.class,
            CommentHasRepliesException.class
    })
    public ResponseEntity<Void> handleConflict(Exception ex) {
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
        log.error("Unexpected error in NovelChapterCommentController", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
