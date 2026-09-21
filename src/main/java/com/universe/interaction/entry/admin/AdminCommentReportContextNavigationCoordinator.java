package com.universe.interaction.entry.admin;

import com.universe.interaction.application.query.GetInteractionReportDetailUseCase;
import com.universe.interaction.application.query.InteractionReportDetailResult;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.entry.admin.dto.AdminCommentReportContextNavigationDTO;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-context navigation coordinator for locating reported comments in public discussion.
 *
 * <p>Enforces strict clean architecture boundaries and navigation availability rules:
 * <ul>
 *   <li>Lives in entry boundary ({@code com.universe.interaction.entry.admin});</li>
 *   <li>Acts as an entry/admin cross-context composer outputting structural metadata rather than public URL representations;</li>
 *   <li>Target queries are completely bypassed when the current comment is unavailable;</li>
 *   <li>At most 1 targeted batch query to Novel OR Wiki (never both);</li>
 *   <li>Navigation is available only when target is published and slug/metadata are present;</li>
 *   <li>Propagates {@link com.universe.interaction.application.exceptions.InteractionReportNotFoundException} to controller layer;</li>
 *   <li>Deleted comments remain navigable as long as the comment record is available and target is published.</li>
 * </ul>
 */
@Service
public class AdminCommentReportContextNavigationCoordinator {

    private static final String STATUS_PUBLISHED = "PUBLISHED";

    private final GetInteractionReportDetailUseCase getReportDetailUseCase;
    private final ChapterListQueryPort chapterListQueryPort;
    private final WikiArticleQueryPort wikiArticleQueryPort;

    public AdminCommentReportContextNavigationCoordinator(
            GetInteractionReportDetailUseCase getReportDetailUseCase,
            ChapterListQueryPort chapterListQueryPort,
            WikiArticleQueryPort wikiArticleQueryPort
    ) {
        this.getReportDetailUseCase = Objects.requireNonNull(
                getReportDetailUseCase,
                "GetInteractionReportDetailUseCase cannot be null"
        );
        this.chapterListQueryPort = Objects.requireNonNull(
                chapterListQueryPort,
                "ChapterListQueryPort cannot be null"
        );
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort,
                "WikiArticleQueryPort cannot be null"
        );
    }

    /**
     * Resolves context navigation metadata for locating a reported comment in live public discussion.
     *
     * @param reportId unique report ID (cannot be null)
     * @return {@link AdminCommentReportContextNavigationDTO} with structural routing data or marked unavailable
     */
    public AdminCommentReportContextNavigationDTO resolveNavigation(UUID reportId) {
        Objects.requireNonNull(reportId, "reportId cannot be null");

        // 1. Fetch raw Interaction report & comment state
        InteractionReportDetailResult raw = getReportDetailUseCase.execute(reportId);

        // 2. Gating: comment must be available
        if (!raw.currentCommentAvailable()) {
            return AdminCommentReportContextNavigationDTO.unavailable(reportId);
        }

        // 3. Gating: target reference must exist
        if (raw.targetType() == null || raw.targetId() == null) {
            return AdminCommentReportContextNavigationDTO.unavailable(reportId);
        }

        // 4. Thread ID resolution: root comment uses commentId; reply uses root UUID
        UUID exactCommentId = raw.commentId();
        UUID threadId = raw.currentCommentThreadRootCommentId() != null
                ? raw.currentCommentThreadRootCommentId()
                : raw.commentId();

        // 5. Query target metadata by target type
        if (raw.targetType() == CommentTargetType.NOVEL_CHAPTER) {
            Map<UUID, ChapterListItemDTO> chapters = Objects.requireNonNullElse(
                    chapterListQueryPort.findListItemsByIds(Set.of(raw.targetId())),
                    Map.of()
            );
            ChapterListItemDTO chapter = chapters.get(raw.targetId());
            if (chapter == null) {
                return AdminCommentReportContextNavigationDTO.unavailable(reportId);
            }
            if (!STATUS_PUBLISHED.equalsIgnoreCase(chapter.status())) {
                return AdminCommentReportContextNavigationDTO.unavailable(reportId);
            }
            if (chapter.slug() == null || chapter.slug().isBlank()) {
                return AdminCommentReportContextNavigationDTO.unavailable(reportId);
            }
            return AdminCommentReportContextNavigationDTO.available(
                    reportId,
                    exactCommentId,
                    threadId,
                    CommentTargetType.NOVEL_CHAPTER,
                    chapter.slug().trim(),
                    null
            );
        }

        if (raw.targetType() == CommentTargetType.WIKI_ARTICLE) {
            Map<UUID, WikiArticleListItemDTO> articles = Objects.requireNonNullElse(
                    wikiArticleQueryPort.findListItemsByIds(Set.of(raw.targetId())),
                    Map.of()
            );
            WikiArticleListItemDTO article = articles.get(raw.targetId());
            if (article == null) {
                return AdminCommentReportContextNavigationDTO.unavailable(reportId);
            }
            if (!STATUS_PUBLISHED.equalsIgnoreCase(article.status())) {
                return AdminCommentReportContextNavigationDTO.unavailable(reportId);
            }
            if (article.slug() == null || article.slug().isBlank()) {
                return AdminCommentReportContextNavigationDTO.unavailable(reportId);
            }
            if (article.articleType() == null || article.articleType().isBlank()) {
                return AdminCommentReportContextNavigationDTO.unavailable(reportId);
            }
            return AdminCommentReportContextNavigationDTO.available(
                    reportId,
                    exactCommentId,
                    threadId,
                    CommentTargetType.WIKI_ARTICLE,
                    article.slug().trim(),
                    article.articleType().trim()
            );
        }

        // Unsupported target types are not navigable
        return AdminCommentReportContextNavigationDTO.unavailable(reportId);
    }
}
