package com.universe.interaction.entry.admin;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.ports.InteractionReportQueueQueryPort;
import com.universe.interaction.application.query.InteractionReportQueueFilter;
import com.universe.interaction.application.query.InteractionReportQueueItem;
import com.universe.interaction.application.query.InteractionReportQueuePage;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.entry.admin.dto.AdminCommentReportQueueItemDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportQueuePageDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportTargetDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportUserDTO;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-context query composition coordinator for the Admin Comment Report Queue.
 *
 * <p>Enforces strict clean architecture boundaries and N+1 prevention:
 * <ul>
 *   <li>Lives in the entry boundary ({@code com.universe.interaction.entry.admin});</li>
 *   <li>{@code interaction.application} remains completely agnostic of Novel, Wiki, and Identity;</li>
 *   <li>Performs at most 1 bulk query to Identity, 1 bulk query to Novel, and 1 bulk query to Wiki per page;</li>
 *   <li>Skips foreign context calls entirely when page is empty or when specific target types are absent;</li>
 *   <li>Resilient to missing/unresolved foreign records (marked {@code resolved = false});</li>
 *   <li>Preserves original raw report queue ordering and pagination invariants.</li>
 * </ul>
 */
@Service
public class AdminCommentReportQueueCoordinator {

    private final InteractionReportQueueQueryPort queueQueryPort;
    private final UserIdentityContract userIdentityContract;
    private final ChapterListQueryPort chapterListQueryPort;
    private final WikiArticleQueryPort wikiArticleQueryPort;

    public AdminCommentReportQueueCoordinator(
            InteractionReportQueueQueryPort queueQueryPort,
            UserIdentityContract userIdentityContract,
            ChapterListQueryPort chapterListQueryPort,
            WikiArticleQueryPort wikiArticleQueryPort
    ) {
        this.queueQueryPort = Objects.requireNonNull(queueQueryPort, "InteractionReportQueueQueryPort cannot be null");
        this.userIdentityContract = Objects.requireNonNull(userIdentityContract, "UserIdentityContract cannot be null");
        this.chapterListQueryPort = Objects.requireNonNull(chapterListQueryPort, "ChapterListQueryPort cannot be null");
        this.wikiArticleQueryPort = Objects.requireNonNull(wikiArticleQueryPort, "WikiArticleQueryPort cannot be null");
    }

    /**
     * Retrieves a page of enriched comment reports for the Admin moderation queue.
     *
     * @param filter filter and pagination parameters (never null)
     * @return immutable {@link AdminCommentReportQueuePageDTO} containing composite items
     */
    public AdminCommentReportQueuePageDTO getReportQueue(InteractionReportQueueFilter filter) {
        Objects.requireNonNull(filter, "InteractionReportQueueFilter cannot be null");

        InteractionReportQueuePage rawPage = queueQueryPort.findQueueReports(filter);
        if (rawPage == null || rawPage.items().isEmpty()) {
            int page = rawPage != null ? rawPage.page() : filter.page();
            int size = rawPage != null ? rawPage.size() : filter.size();
            long total = rawPage != null ? rawPage.totalElements() : 0L;
            return new AdminCommentReportQueuePageDTO(List.of(), page, size, total);
        }

        List<InteractionReportQueueItem> rawItems = rawPage.items();

        // 1. Collect unique user IDs for bulk identity lookup
        Set<UUID> userIds = new HashSet<>();
        for (InteractionReportQueueItem item : rawItems) {
            if (item.reporterUserId() != null) {
                userIds.add(item.reporterUserId());
            }
            if (item.commentAuthorUserId() != null) {
                userIds.add(item.commentAuthorUserId());
            }
        }

        Map<UUID, UserPublicProfileDTO> profiles = userIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(userIdentityContract.findPublicProfilesByIds(userIds), Map.of());

        // 2. Partition target IDs by CommentTargetType
        Set<UUID> chapterIds = new HashSet<>();
        Set<UUID> articleIds = new HashSet<>();
        for (InteractionReportQueueItem item : rawItems) {
            if (item.targetType() == CommentTargetType.NOVEL_CHAPTER && item.targetId() != null) {
                chapterIds.add(item.targetId());
            } else if (item.targetType() == CommentTargetType.WIKI_ARTICLE && item.targetId() != null) {
                articleIds.add(item.targetId());
            }
        }

        // 3. Bulk lookup target display metadata only when target IDs are present
        Map<UUID, ChapterListItemDTO> chapters = chapterIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(chapterListQueryPort.findListItemsByIds(chapterIds), Map.of());

        Map<UUID, WikiArticleListItemDTO> articles = articleIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(wikiArticleQueryPort.findListItemsByIds(articleIds), Map.of());

        // 4. Compose enriched items in original queue order
        List<AdminCommentReportQueueItemDTO> enrichedItems = new ArrayList<>(rawItems.size());
        for (InteractionReportQueueItem rawItem : rawItems) {
            AdminCommentReportUserDTO reporter = composeUser(rawItem.reporterUserId(), profiles);
            AdminCommentReportUserDTO commentAuthor = composeUser(rawItem.commentAuthorUserId(), profiles);
            AdminCommentReportTargetDTO target = composeTarget(rawItem.targetType(), rawItem.targetId(), chapters, articles);

            enrichedItems.add(new AdminCommentReportQueueItemDTO(
                    rawItem.reportId(),
                    rawItem.commentId(),
                    reporter,
                    rawItem.reason(),
                    rawItem.description(),
                    rawItem.reportedBodySnapshot(),
                    rawItem.status(),
                    rawItem.createdAt(),
                    commentAuthor,
                    rawItem.commentStatus(),
                    target
            ));
        }

        return new AdminCommentReportQueuePageDTO(
                enrichedItems,
                rawPage.page(),
                rawPage.size(),
                rawPage.totalElements()
        );
    }

    private AdminCommentReportUserDTO composeUser(UUID userId, Map<UUID, UserPublicProfileDTO> profiles) {
        if (userId == null) {
            return null;
        }
        UserPublicProfileDTO profile = profiles.get(userId);
        if (profile != null) {
            return AdminCommentReportUserDTO.resolved(userId, profile.displayName(), profile.avatarUrl());
        }
        return AdminCommentReportUserDTO.unresolved(userId);
    }

    private AdminCommentReportTargetDTO composeTarget(
            CommentTargetType targetType,
            UUID targetId,
            Map<UUID, ChapterListItemDTO> chapters,
            Map<UUID, WikiArticleListItemDTO> articles
    ) {
        if (targetType == CommentTargetType.NOVEL_CHAPTER) {
            ChapterListItemDTO chapter = chapters.get(targetId);
            return AdminCommentReportTargetDTO.forNovelChapter(targetId, chapter);
        } else if (targetType == CommentTargetType.WIKI_ARTICLE) {
            WikiArticleListItemDTO article = articles.get(targetId);
            return AdminCommentReportTargetDTO.forWikiArticle(targetId, article);
        }
        return AdminCommentReportTargetDTO.unresolved(targetType, targetId);
    }
}
