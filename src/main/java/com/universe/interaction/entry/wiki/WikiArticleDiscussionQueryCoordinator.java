package com.universe.interaction.entry.wiki;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.wiki.dto.WikiDiscussionFeedResponseDTO;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
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
 * Cross-context query composition coordinator for reading Wiki article discussion feeds.
 *
 * <p>Preserves bounded-context boundaries and performance contracts:
 * <ul>
 *   <li>Interaction application has ZERO Wiki imports;</li>
 *   <li>Wiki application has ZERO Interaction imports;</li>
 *   <li>Entry-layer composition uses only scalar cross-context references (UUIDs);</li>
 *   <li>One publication check + one target metrics aggregate query + one root Slice query + one batch visible thread query for current page roots + one Identity profile query;</li>
 *   <li>Zero per-root N+1 access loops;</li>
 *   <li>Read-only operation without write transactional overhead.</li>
 * </ul>
 */
@Service
public class WikiArticleDiscussionQueryCoordinator {

    private final WikiArticleQueryPort wikiArticleQueryPort;
    private final GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase;
    private final ListCommentRootsUseCase listCommentRootsUseCase;
    private final GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase;
    private final GetCommentThreadUseCase getCommentThreadUseCase;
    private final ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;
    private final UserIdentityContract userIdentityContract;

    public WikiArticleDiscussionQueryCoordinator(
            WikiArticleQueryPort wikiArticleQueryPort,
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            UserIdentityContract userIdentityContract
    ) {
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort, "WikiArticleQueryPort cannot be null"
        );
        this.getCommentTargetMetricsUseCase = Objects.requireNonNull(
                getCommentTargetMetricsUseCase, "GetCommentTargetMetricsUseCase cannot be null"
        );
        this.listCommentRootsUseCase = Objects.requireNonNull(
                listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null"
        );
        this.getCommentThreadsByRootIdsUseCase = Objects.requireNonNull(
                getCommentThreadsByRootIdsUseCase, "GetCommentThreadsByRootIdsUseCase cannot be null"
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
    }

    /**
     * Resolves a paginated slice of Wiki article discussion feed items for anonymous guests.
     *
     * @param articleId scalar UUID of the Wiki article
     * @param page zero-based page index
     * @param size page size
     * @return immutable {@link WikiDiscussionFeedResponseDTO}
     */
    public WikiDiscussionFeedResponseDTO getDiscussionFeed(UUID articleId, int page, int size) {
        return getDiscussionFeed(articleId, page, size, null);
    }

    /**
     * Resolves a paginated slice of Wiki article discussion feed items with optional viewer capabilities.
     *
     * @param articleId scalar UUID of the Wiki article
     * @param page zero-based page index
     * @param size page size
     * @param viewerUserId optional scalar UUID of the authenticated viewer (null for guests)
     * @return immutable {@link WikiDiscussionFeedResponseDTO}
     */
    public WikiDiscussionFeedResponseDTO getDiscussionFeed(UUID articleId, int page, int size, UUID viewerUserId) {
        if (articleId == null) {
            throw new IllegalArgumentException("articleId cannot be null");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater than zero: " + size);
        }

        // 1. Publication gate: fail closed if article is missing or not published
        if (!wikiArticleQueryPort.isPublished(articleId)) {
            throw new PublishedWikiArticleNotFoundException(articleId);
        }

        CommentTarget target = CommentTarget.wikiArticle(articleId);

        // 2. Direct persistence aggregate: compute article metrics without loading root IDs or replies
        CommentTargetMetrics metrics = getCommentTargetMetricsUseCase.execute(target);
        int threadCount = metrics.threadCount();
        int commentCount = metrics.commentCount();

        // 3. Interaction query: load active root comments slice for this article
        CommentReadSlice rootSlice = listCommentRootsUseCase.execute(target, page, size);
        if (rootSlice.items().isEmpty()) {
            return new WikiDiscussionFeedResponseDTO(
                    List.of(),
                    threadCount,
                    commentCount,
                    rootSlice.page(),
                    rootSlice.size(),
                    rootSlice.hasNext()
            );
        }

        List<CommentReadItem> roots = rootSlice.items();
        List<UUID> rootIds = roots.stream().map(CommentReadItem::id).toList();

        // 4. Interaction query: batch retrieve visible threads for all roots in this slice
        List<CommentThreadView> threadViews = getCommentThreadsByRootIdsUseCase.execute(target, rootIds);
        Map<UUID, CommentThreadView> threadViewsByRootId = threadViews.stream()
                .collect(Collectors.toMap(
                        tv -> tv.root().id(),
                        tv -> tv,
                        (existing, replacement) -> existing
                ));

        // 5. Concurrent delete safety: preserve slice ordering among surviving roots only
        List<UUID> survivingRootIds = rootIds.stream()
                .filter(threadViewsByRootId::containsKey)
                .toList();

        if (survivingRootIds.isEmpty()) {
            return new WikiDiscussionFeedResponseDTO(
                    List.of(),
                    threadCount,
                    commentCount,
                    rootSlice.page(),
                    rootSlice.size(),
                    rootSlice.hasNext()
            );
        }

        // 6. Identity contract: batch lookup public profiles for unique authors (roots + active replies)
        Set<UUID> authorUserIds = new HashSet<>();
        for (UUID rootId : survivingRootIds) {
            CommentThreadView threadView = threadViewsByRootId.get(rootId);
            if (threadView.root() != null && threadView.root().authorUserId() != null) {
                authorUserIds.add(threadView.root().authorUserId());
            }
            if (threadView.replies() != null) {
                for (CommentReadItem reply : threadView.replies()) {
                    if (reply != null && !reply.tombstone() && reply.authorUserId() != null) {
                        authorUserIds.add(reply.authorUserId());
                    }
                }
            }
        }

        Map<UUID, UserPublicProfileDTO> authorsMap = authorUserIds.isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(authorUserIds);

        // 7. Assemble threads preserving original slice ordering
        List<CommentThreadResponseDTO> threads = new ArrayList<>(survivingRootIds.size());
        for (UUID rootId : survivingRootIds) {
            CommentThreadView threadView = threadViewsByRootId.get(rootId);
            CommentReadItem authoritativeRoot = threadView.root();

            UserPublicProfileDTO rootProfile = (authorsMap != null && authoritativeRoot.authorUserId() != null)
                    ? authorsMap.get(authoritativeRoot.authorUserId())
                    : null;
            CommentAuthorDTO rootAuthorDTO = (rootProfile != null)
                    ? new CommentAuthorDTO(authoritativeRoot.authorUserId(), rootProfile.displayName(), rootProfile.avatarUrl())
                    : CommentAuthorDTO.fallback(authoritativeRoot.authorUserId());

            CommentReadDTO rootReadDTO = CommentReadDTO.from(authoritativeRoot, rootAuthorDTO, viewerUserId);

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
                                ? new CommentAuthorDTO(replyItem.authorUserId(), replyProfile.displayName(), replyProfile.avatarUrl())
                                : CommentAuthorDTO.fallback(replyItem.authorUserId());
                        replyDTOs.add(CommentReadDTO.from(replyItem, replyAuthorDTO, viewerUserId));
                    }
                }
            }

            threads.add(new CommentThreadResponseDTO(rootReadDTO, replyDTOs));
        }

        return new WikiDiscussionFeedResponseDTO(
                threads,
                threadCount,
                commentCount,
                rootSlice.page(),
                rootSlice.size(),
                rootSlice.hasNext()
        );
    }

    /**
     * Resolves a single discussion thread by root comment ID under a published Wiki article.
     *
     * @param articleId scalar UUID of the Wiki article
     * @param rootCommentId scalar UUID of the root comment
     * @param viewerUserId optional scalar UUID of the authenticated viewer (null for guests)
     * @return immutable {@link CommentThreadResponseDTO}
     */
    public CommentThreadResponseDTO getCommentThread(UUID articleId, UUID rootCommentId, UUID viewerUserId) {
        if (articleId == null) {
            throw new IllegalArgumentException("articleId cannot be null");
        }
        if (rootCommentId == null) {
            throw new IllegalArgumentException("rootCommentId cannot be null");
        }

        if (!wikiArticleQueryPort.isPublished(articleId)) {
            throw new PublishedWikiArticleNotFoundException(articleId);
        }

        CommentTarget expectedTarget = CommentTarget.wikiArticle(articleId);
        validateCommentTargetScopeUseCase.executeRoot(rootCommentId, expectedTarget);

        CommentThreadView threadView = getCommentThreadUseCase.execute(rootCommentId);

        Set<UUID> authorUserIds = new HashSet<>();
        if (threadView.root() != null && threadView.root().authorUserId() != null) {
            authorUserIds.add(threadView.root().authorUserId());
        }
        if (threadView.replies() != null) {
            for (CommentReadItem reply : threadView.replies()) {
                if (reply != null && !reply.tombstone() && reply.authorUserId() != null) {
                    authorUserIds.add(reply.authorUserId());
                }
            }
        }

        Map<UUID, UserPublicProfileDTO> authorsMap = authorUserIds.isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(authorUserIds);

        CommentReadItem authoritativeRoot = threadView.root();
        UserPublicProfileDTO rootProfile = (authorsMap != null && authoritativeRoot.authorUserId() != null)
                ? authorsMap.get(authoritativeRoot.authorUserId())
                : null;
        CommentAuthorDTO rootAuthorDTO = (rootProfile != null)
                ? new CommentAuthorDTO(authoritativeRoot.authorUserId(), rootProfile.displayName(), rootProfile.avatarUrl())
                : CommentAuthorDTO.fallback(authoritativeRoot.authorUserId());

        CommentReadDTO rootReadDTO = CommentReadDTO.from(authoritativeRoot, rootAuthorDTO, viewerUserId);

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
                            ? new CommentAuthorDTO(replyItem.authorUserId(), replyProfile.displayName(), replyProfile.avatarUrl())
                            : CommentAuthorDTO.fallback(replyItem.authorUserId());
                    replyDTOs.add(CommentReadDTO.from(replyItem, replyAuthorDTO, viewerUserId));
                }
            }
        }

        return new CommentThreadResponseDTO(rootReadDTO, replyDTOs);
    }
}
