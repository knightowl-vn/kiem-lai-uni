package com.universe.interaction.entry.community;

import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.CountVisibleActiveRepliesByRootIdsUseCase;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.entry.community.dto.CommunityDiscussionFeedResponseDTO;
import com.universe.interaction.entry.community.dto.CommunityRootCommentDTO;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cross-context query composition coordinator for reading Community post discussion feeds and threads.
 *
 * <p>Preserves bounded-context boundaries and performance contracts:
 * <ul>
 *   <li>Interaction application has ZERO Community entity/write imports;</li>
 *   <li>Community application has ZERO Interaction entity/write imports;</li>
 *   <li>Entry-layer composition uses only scalar cross-context references (UUIDs);</li>
 *   <li>Root discussion feed retrieves ONLY root comments and indicator reply counts without materializing reply entities;</li>
 *   <li>Root feed Identity bulk profile lookup resolves ONLY root comment authors on the current page;</li>
 *   <li>Thread endpoint resolves root comment and flat visible replies on demand with deduplicated bulk author lookup;</li>
 *   <li>Read-only operation without write transactional overhead.</li>
 * </ul>
 */
@Service
public class CommunityPostDiscussionQueryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(CommunityPostDiscussionQueryCoordinator.class);

    private final CommunityPostQueryPort communityPostQueryPort;
    private final GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase;
    private final ListCommentRootsUseCase listCommentRootsUseCase;
    private final CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase;
    private final GetCommentThreadUseCase getCommentThreadUseCase;
    private final ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;
    private final UserIdentityContract userIdentityContract;
    private final GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase;

    public CommunityPostDiscussionQueryCoordinator(
            CommunityPostQueryPort communityPostQueryPort,
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            UserIdentityContract userIdentityContract,
            GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase
    ) {
        this.communityPostQueryPort = Objects.requireNonNull(
                communityPostQueryPort, "CommunityPostQueryPort cannot be null"
        );
        this.getCommentTargetMetricsUseCase = Objects.requireNonNull(
                getCommentTargetMetricsUseCase, "GetCommentTargetMetricsUseCase cannot be null"
        );
        this.listCommentRootsUseCase = Objects.requireNonNull(
                listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null"
        );
        this.countVisibleActiveRepliesByRootIdsUseCase = Objects.requireNonNull(
                countVisibleActiveRepliesByRootIdsUseCase, "CountVisibleActiveRepliesByRootIdsUseCase cannot be null"
        );
        this.getCommentThreadUseCase = Objects.requireNonNull(
                getCommentThreadUseCase, "GetCommentThreadUseCase cannot be null"
        );
        this.validateCommentTargetScopeUseCase = Objects.requireNonNull(
                validateCommentTargetScopeUseCase, "ValidateCommentTargetScopeUseCase cannot be null"
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract, "UserIdentityContract cannot be null"
        );
        this.getBatchReactionSummariesUseCase = Objects.requireNonNull(
                getBatchReactionSummariesUseCase, "GetBatchReactionSummariesUseCase cannot be null"
        );
    }

    /**
     * Resolves a paginated slice of Community post discussion feed items for anonymous guests.
     *
     * @param postId scalar UUID of the Community post
     * @param page zero-based page index
     * @param size page size
     * @return immutable {@link CommunityDiscussionFeedResponseDTO}
     */
    public CommunityDiscussionFeedResponseDTO getDiscussionFeed(UUID postId, int page, int size) {
        return getDiscussionFeed(postId, page, size, null);
    }

    /**
     * Resolves a paginated slice of Community post discussion feed items with optional viewer capabilities.
     *
     * <p>Loads ONLY root comments and indicator reply counts. Does NOT materialize reply bodies.
     * Resolves authors strictly for root comments present in the returned slice.
     *
     * @param postId scalar UUID of the Community post
     * @param page zero-based page index
     * @param size page size
     * @param viewerUserId optional scalar UUID of the authenticated viewer (null for guests)
     * @return immutable {@link CommunityDiscussionFeedResponseDTO}
     */
    public CommunityDiscussionFeedResponseDTO getDiscussionFeed(UUID postId, int page, int size, UUID viewerUserId) {
        if (postId == null) {
            throw new IllegalArgumentException("postId cannot be null");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater than zero: " + size);
        }

        CommentTarget target = CommentTarget.communityPost(postId);

        // 1. Post existence gate: fail closed if post is missing or not public
        communityPostQueryPort.findPublicPostById(postId)
                .orElseThrow(() -> new CommentTargetNotEligibleException(target));

        // 2. Authoritative active comment count via application query use case
        long commentCount = getCommentTargetMetricsUseCase.execute(target).commentCount();

        // 3. Load active root comments slice for this post (roots only, no reply hydration)
        CommentReadSlice rootSlice = listCommentRootsUseCase.execute(target, page, size);
        if (rootSlice.items().isEmpty()) {
            return new CommunityDiscussionFeedResponseDTO(
                    List.of(),
                    commentCount,
                    rootSlice.page(),
                    rootSlice.size(),
                    rootSlice.hasNext()
            );
        }

        List<CommentReadItem> roots = rootSlice.items();
        List<UUID> rootIds = roots.stream().map(CommentReadItem::id).toList();

        // 4. Batch retrieve active reply counts for the roots in this slice (zero reply bodies)
        Map<UUID, Long> replyCountsByRootId = countVisibleActiveRepliesByRootIdsUseCase.execute(rootIds);

        // 5. Identity bulk lookup strictly for ROOT authors returned on this page
        Set<UUID> rootAuthorUserIds = roots.stream()
                .map(CommentReadItem::authorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, UserPublicProfileDTO> authorsMap = rootAuthorUserIds.isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(rootAuthorUserIds);

        // 6. Batch reaction summaries for active root comments only
        List<UUID> activeRootIds = roots.stream()
                .filter(r -> !r.tombstone())
                .map(CommentReadItem::id)
                .toList();

        Map<UUID, ReactionSummaryResponseDTO> reactionSummariesByCommentId =
                loadReactionSummaries(activeRootIds, viewerUserId, postId);

        // 7. Assemble root comment DTOs preserving slice ordering
        List<CommunityRootCommentDTO> rootDTOs = new ArrayList<>(roots.size());
        for (CommentReadItem rootItem : roots) {
            UserPublicProfileDTO rootProfile = (authorsMap != null && rootItem.authorUserId() != null)
                    ? authorsMap.get(rootItem.authorUserId())
                    : null;
            CommentAuthorDTO rootAuthorDTO = (rootProfile != null)
                    ? new CommentAuthorDTO(rootItem.authorUserId(), rootProfile.displayName(), rootProfile.avatarUrl(), rootProfile.publicHandle())
                    : CommentAuthorDTO.fallback(rootItem.authorUserId());

            ReactionSummaryResponseDTO reactionSummary = reactionSummariesByCommentId.get(rootItem.id());
            CommentReadDTO rootReadDTO = CommentReadDTO.from(rootItem, rootAuthorDTO, viewerUserId, reactionSummary);
            long replyCount = replyCountsByRootId.getOrDefault(rootItem.id(), 0L);

            rootDTOs.add(new CommunityRootCommentDTO(rootReadDTO, replyCount));
        }

        return new CommunityDiscussionFeedResponseDTO(
                rootDTOs,
                commentCount,
                rootSlice.page(),
                rootSlice.size(),
                rootSlice.hasNext()
        );
    }

    /**
     * Resolves a single discussion thread by root comment ID under a public Community post.
     *
     * <p>Loads the root comment and its flat visible replies on demand. Performs deduplicated
     * single bulk Identity lookup across root author and returned reply authors.
     *
     * @param postId scalar UUID of the Community post
     * @param rootCommentId scalar UUID of the root comment
     * @param viewerUserId optional scalar UUID of the authenticated viewer (null for guests)
     * @return immutable {@link CommentThreadResponseDTO}
     */
    public CommentThreadResponseDTO getCommentThread(UUID postId, UUID rootCommentId, UUID viewerUserId) {
        if (postId == null) {
            throw new IllegalArgumentException("postId cannot be null");
        }
        if (rootCommentId == null) {
            throw new IllegalArgumentException("rootCommentId cannot be null");
        }

        CommentTarget expectedTarget = CommentTarget.communityPost(postId);
        communityPostQueryPort.findPublicPostById(postId)
                .orElseThrow(() -> new CommentTargetNotEligibleException(expectedTarget));

        validateCommentTargetScopeUseCase.executeRoot(rootCommentId, expectedTarget);

        CommentThreadView threadView = getCommentThreadUseCase.execute(rootCommentId);

        Set<UUID> authorUserIds = new HashSet<>();
        List<UUID> activeCommentIds = new ArrayList<>();

        if (threadView.root() != null) {
            if (!threadView.root().tombstone()) {
                activeCommentIds.add(threadView.root().id());
            }
            if (threadView.root().authorUserId() != null) {
                authorUserIds.add(threadView.root().authorUserId());
            }
        }
        if (threadView.replies() != null) {
            for (CommentReadItem reply : threadView.replies()) {
                if (reply != null && !reply.tombstone()) {
                    activeCommentIds.add(reply.id());
                    if (reply.authorUserId() != null) {
                        authorUserIds.add(reply.authorUserId());
                    }
                }
            }
        }

        Map<UUID, UserPublicProfileDTO> authorsMap = authorUserIds.isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(authorUserIds);

        Map<UUID, ReactionSummaryResponseDTO> reactionSummariesByCommentId =
                loadReactionSummaries(activeCommentIds, viewerUserId, postId);

        CommentReadItem authoritativeRoot = threadView.root();
        UserPublicProfileDTO rootProfile = (authorsMap != null && authoritativeRoot.authorUserId() != null)
                ? authorsMap.get(authoritativeRoot.authorUserId())
                : null;
        CommentAuthorDTO rootAuthorDTO = (rootProfile != null)
                ? new CommentAuthorDTO(authoritativeRoot.authorUserId(), rootProfile.displayName(), rootProfile.avatarUrl(), rootProfile.publicHandle())
                : CommentAuthorDTO.fallback(authoritativeRoot.authorUserId());

        ReactionSummaryResponseDTO rootReactionSummary = reactionSummariesByCommentId.get(authoritativeRoot.id());
        CommentReadDTO rootReadDTO = CommentReadDTO.from(authoritativeRoot, rootAuthorDTO, viewerUserId, rootReactionSummary);

        List<CommentReadDTO> replyDTOs = new ArrayList<>();
        if (threadView.replies() != null) {
            for (CommentReadItem replyItem : threadView.replies()) {
                if (replyItem == null) {
                    continue;
                }
                if (replyItem.tombstone()) {
                    replyDTOs.add(CommentReadDTO.from(replyItem, viewerUserId));
                } else {
                    UserPublicProfileDTO replyProfile = (authorsMap != null && replyItem.authorUserId() != null)
                            ? authorsMap.get(replyItem.authorUserId())
                            : null;
                    CommentAuthorDTO replyAuthorDTO = (replyProfile != null)
                            ? new CommentAuthorDTO(replyItem.authorUserId(), replyProfile.displayName(), replyProfile.avatarUrl(), replyProfile.publicHandle())
                            : CommentAuthorDTO.fallback(replyItem.authorUserId());

                    ReactionSummaryResponseDTO replyReactionSummary = reactionSummariesByCommentId.get(replyItem.id());
                    replyDTOs.add(CommentReadDTO.from(replyItem, replyAuthorDTO, viewerUserId, replyReactionSummary));
                }
            }
        }

        return new CommentThreadResponseDTO(rootReadDTO, replyDTOs);
    }

    private Map<UUID, ReactionSummaryResponseDTO> loadReactionSummaries(
            List<UUID> activeCommentIds,
            UUID viewerUserId,
            UUID postId
    ) {
        if (activeCommentIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<UUID, ReactionSummary> summaries = getBatchReactionSummariesUseCase.execute(
                ReactionTargetType.COMMENT,
                activeCommentIds,
                viewerUserId
            );
            if (summaries != null && !summaries.isEmpty()) {
                return summaries.entrySet().stream()
                        .filter(e -> e.getValue() != null)
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> ReactionSummaryResponseDTO.from(e.getValue())
                        ));
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to load reaction summaries for community post discussion: postId={}", postId, ex);
        }
        return Map.of();
    }
}
