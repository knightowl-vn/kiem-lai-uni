package com.universe.interaction.entry.web.personal;

import com.universe.interaction.application.query.UserCommentContextFilter;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentItemDTO;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentPageDTO;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.entry.web.support.ArticleTypePathMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * View model representing a page of authored comments in the personal comments UI.
 */
public record UserAuthoredCommentPageViewModel(
        List<UserAuthoredCommentViewItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        UserCommentContextFilter activeFilter
) {
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    public UserAuthoredCommentPageViewModel {
        items = items != null ? List.copyOf(items) : List.of();
        activeFilter = activeFilter != null ? activeFilter : UserCommentContextFilter.ALL;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public static UserAuthoredCommentPageViewModel from(
            AuthoredCommentPageDTO pageDto,
            UserCommentContextFilter filter,
            Map<UUID, ChapterListItemDTO> liveChaptersMap,
            Map<UUID, WikiArticleListItemDTO> liveArticlesMap,
            ArticleTypePathMapper articleTypePathMapper
    ) {
        Objects.requireNonNull(pageDto, "pageDto cannot be null.");
        Map<UUID, ChapterListItemDTO> safeChapters = liveChaptersMap != null ? liveChaptersMap : Map.of();
        Map<UUID, WikiArticleListItemDTO> safeArticles = liveArticlesMap != null ? liveArticlesMap : Map.of();

        List<UserAuthoredCommentViewItem> viewItems = pageDto.items().stream()
                .map(item -> toViewItem(item, safeChapters, safeArticles, articleTypePathMapper))
                .toList();

        return new UserAuthoredCommentPageViewModel(
                viewItems,
                pageDto.page(),
                pageDto.size(),
                pageDto.totalElements(),
                pageDto.totalPages(),
                pageDto.first(),
                pageDto.last(),
                filter
        );
    }

    private static UserAuthoredCommentViewItem toViewItem(
            AuthoredCommentItemDTO item,
            Map<UUID, ChapterListItemDTO> liveChaptersMap,
            Map<UUID, WikiArticleListItemDTO> liveArticlesMap,
            ArticleTypePathMapper articleTypePathMapper
    ) {
        boolean isReply = item.isReply();
        String typeLabel = isReply ? "Phản hồi" : "Bình luận";

        if (item.targetType() == CommentTargetType.NOVEL_CHAPTER) {
            String contextLabel = "Novel";
            String targetUnavailableLabel = "Chương không còn khả dụng";
            ChapterListItemDTO chapter = liveChaptersMap.get(item.targetId());

            boolean isAvailable = chapter != null
                    && STATUS_PUBLISHED.equalsIgnoreCase(chapter.status())
                    && chapter.slug() != null
                    && !chapter.slug().isBlank();

            String targetTitle = null;
            String contextUrl = null;

            if (isAvailable) {
                String titlePart = chapter.title() != null ? chapter.title().trim() : "";
                targetTitle = chapter.chapterNumber() > 0
                        ? "Chương " + chapter.chapterNumber() + ": " + titlePart
                        : titlePart;
                UUID threadId = item.threadRootCommentId() != null ? item.threadRootCommentId() : item.commentId();
                contextUrl = "/novel/chapters/" + chapter.slug().trim()
                        + "?commentId=" + item.commentId()
                        + "&threadId=" + threadId
                        + "#novelChapterComments";
            }

            return new UserAuthoredCommentViewItem(
                    item.commentId(),
                    item.targetType(),
                    item.targetId(),
                    item.body(),
                    item.createdAt(),
                    item.updatedAt(),
                    isReply,
                    contextLabel,
                    typeLabel,
                    isAvailable,
                    targetTitle,
                    targetUnavailableLabel,
                    contextUrl,
                    item.threadRootCommentId()
            );
        }

        if (item.targetType() == CommentTargetType.WIKI_ARTICLE) {
            String contextLabel = "Wiki";
            String targetUnavailableLabel = "Bài viết không còn khả dụng";
            WikiArticleListItemDTO article = liveArticlesMap.get(item.targetId());

            boolean isAvailable = article != null
                    && STATUS_PUBLISHED.equalsIgnoreCase(article.status())
                    && article.slug() != null
                    && !article.slug().isBlank()
                    && article.articleType() != null
                    && !article.articleType().isBlank();

            String targetTitle = null;
            String contextUrl = null;

            if (isAvailable) {
                targetTitle = article.title() != null ? article.title().trim() : "";
                String typePath;
                try {
                    ArticleType articleType = ArticleType.valueOf(article.articleType().trim().toUpperCase(Locale.ROOT));
                    typePath = articleTypePathMapper != null ? articleTypePathMapper.toPath(articleType) : articleType.name().toLowerCase(Locale.ROOT);
                } catch (Exception e) {
                    typePath = article.articleType().trim().toLowerCase(Locale.ROOT).replace('_', '-');
                }
                UUID threadId = item.threadRootCommentId() != null ? item.threadRootCommentId() : item.commentId();
                contextUrl = "/wiki/" + typePath + "/" + article.slug().trim()
                        + "?commentId=" + item.commentId()
                        + "&threadId=" + threadId
                        + "#wikiDiscussion";
            }

            return new UserAuthoredCommentViewItem(
                    item.commentId(),
                    item.targetType(),
                    item.targetId(),
                    item.body(),
                    item.createdAt(),
                    item.updatedAt(),
                    isReply,
                    contextLabel,
                    typeLabel,
                    isAvailable,
                    targetTitle,
                    targetUnavailableLabel,
                    contextUrl,
                    item.threadRootCommentId()
            );
        }

        return new UserAuthoredCommentViewItem(
                item.commentId(),
                item.targetType(),
                item.targetId(),
                item.body(),
                item.createdAt(),
                item.updatedAt(),
                isReply,
                item.targetType().name(),
                typeLabel,
                false,
                null,
                "Nội dung không còn khả dụng",
                null,
                item.threadRootCommentId()
        );
    }
}
