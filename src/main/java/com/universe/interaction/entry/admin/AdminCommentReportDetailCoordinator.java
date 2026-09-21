package com.universe.interaction.entry.admin;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.GetInteractionReportDetailUseCase;
import com.universe.interaction.application.query.InteractionReportDetailResult;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.entry.admin.dto.AdminCommentReportDetailDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportTargetDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportUserDTO;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-context query composition coordinator for single Admin Comment Report Detail.
 *
 * <p>Enforces strict clean architecture boundaries and query budgeting:
 * <ul>
 *   <li>Lives in entry boundary ({@code com.universe.interaction.entry.admin});</li>
 *   <li>{@code interaction.application} remains completely agnostic of Novel, Wiki, and Identity;</li>
 *   <li>At most 1 deduplicated batch query to Identity per report;</li>
 *   <li>At most 1 targeted query to Novel OR Wiki (never both);</li>
 *   <li>Target and comment-author queries are completely bypassed when current comment is missing;</li>
 *   <li>Propagates {@link com.universe.interaction.application.exceptions.InteractionReportNotFoundException} directly to controller layer;</li>
 *   <li>Resilient to missing/unresolved foreign records (marked {@code resolved = false}).</li>
 * </ul>
 */
@Service
public class AdminCommentReportDetailCoordinator {

    private final GetInteractionReportDetailUseCase getReportDetailUseCase;
    private final UserIdentityContract userIdentityContract;
    private final ChapterListQueryPort chapterListQueryPort;
    private final WikiArticleQueryPort wikiArticleQueryPort;

    public AdminCommentReportDetailCoordinator(
            GetInteractionReportDetailUseCase getReportDetailUseCase,
            UserIdentityContract userIdentityContract,
            ChapterListQueryPort chapterListQueryPort,
            WikiArticleQueryPort wikiArticleQueryPort
    ) {
        this.getReportDetailUseCase = Objects.requireNonNull(getReportDetailUseCase, "GetInteractionReportDetailUseCase cannot be null");
        this.userIdentityContract = Objects.requireNonNull(userIdentityContract, "UserIdentityContract cannot be null");
        this.chapterListQueryPort = Objects.requireNonNull(chapterListQueryPort, "ChapterListQueryPort cannot be null");
        this.wikiArticleQueryPort = Objects.requireNonNull(wikiArticleQueryPort, "WikiArticleQueryPort cannot be null");
    }

    /**
     * Retrieves and composes the complete report detail for Admin moderation.
     *
     * @param reportId unique report ID (cannot be null)
     * @return presentation-ready {@link AdminCommentReportDetailDTO}
     */
    public AdminCommentReportDetailDTO getDetail(UUID reportId) {
        Objects.requireNonNull(reportId, "reportId cannot be null");

        // 1. Fetch raw Interaction report & comment state
        InteractionReportDetailResult raw = getReportDetailUseCase.execute(reportId);

        // 2. Collect unique non-null user IDs for a single Identity batch lookup
        Set<UUID> userIds = new HashSet<>();
        if (raw.reporterUserId() != null) {
            userIds.add(raw.reporterUserId());
        }
        if (raw.currentCommentAvailable() && raw.commentAuthorUserId() != null) {
            userIds.add(raw.commentAuthorUserId());
        }
        if (raw.resolvedByUserId() != null) {
            userIds.add(raw.resolvedByUserId());
        }

        Map<UUID, UserPublicProfileDTO> profiles = userIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(userIdentityContract.findPublicProfilesByIds(userIds), Map.of());

        // 3. Compose user projections
        AdminCommentReportUserDTO reporter = composeUser(raw.reporterUserId(), profiles);
        AdminCommentReportUserDTO commentAuthor = raw.currentCommentAvailable()
                ? composeUser(raw.commentAuthorUserId(), profiles)
                : null;
        AdminCommentReportUserDTO resolver = raw.resolvedByUserId() != null
                ? composeUser(raw.resolvedByUserId(), profiles)
                : null;

        // 4. Resolve target metadata only if current comment is available
        AdminCommentReportTargetDTO target = null;
        if (raw.currentCommentAvailable() && raw.targetType() != null && raw.targetId() != null) {
            if (raw.targetType() == CommentTargetType.NOVEL_CHAPTER) {
                Map<UUID, ChapterListItemDTO> chapters = Objects.requireNonNullElse(
                        chapterListQueryPort.findListItemsByIds(Set.of(raw.targetId())),
                        Map.of()
                );
                target = AdminCommentReportTargetDTO.forNovelChapter(raw.targetId(), chapters.get(raw.targetId()));
            } else if (raw.targetType() == CommentTargetType.WIKI_ARTICLE) {
                Map<UUID, WikiArticleListItemDTO> articles = Objects.requireNonNullElse(
                        wikiArticleQueryPort.findListItemsByIds(Set.of(raw.targetId())),
                        Map.of()
                );
                target = AdminCommentReportTargetDTO.forWikiArticle(raw.targetId(), articles.get(raw.targetId()));
            } else {
                target = AdminCommentReportTargetDTO.unresolved(raw.targetType(), raw.targetId());
            }
        }

        // 5. Assemble final composite DTO
        return new AdminCommentReportDetailDTO(
                raw.reportId(),
                raw.commentId(),
                raw.reason(),
                raw.description(),
                raw.reportedBodySnapshot(),
                raw.status(),
                raw.createdAt(),
                reporter,
                raw.resolvedByUserId(),
                resolver,
                raw.resolvedAt(),
                raw.currentCommentAvailable(),
                raw.currentCommentBody(),
                raw.currentCommentStatus(),
                raw.commentCreatedAt(),
                raw.commentUpdatedAt(),
                raw.commentDeletedAt(),
                commentAuthor,
                target
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
}
