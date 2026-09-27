package com.universe.notification.application.usecase;

import com.universe.notification.application.model.NotificationFilter;
import com.universe.notification.application.model.NotificationSlice;
import com.universe.notification.application.port.NotificationQueryPort;
import com.universe.notification.contracts.dto.NotificationDTO;
import com.universe.notification.contracts.dto.NotificationPageDTO;
import com.universe.notification.domain.Notification;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import com.universe.wiki.domain.article.ArticleType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Use case retrieving a paginated feed of notifications for an authenticated user
 * with canonical live navigation URL enrichment.
 */
@Service
public class ListUserNotificationsUseCase {

    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String TARGET_NOVEL_CHAPTER = "NOVEL_CHAPTER";
    private static final String TARGET_WIKI_ARTICLE = "WIKI_ARTICLE";

    private final NotificationQueryPort notificationQueryPort;
    private final ChapterListQueryPort chapterListQueryPort;
    private final WikiArticleQueryPort wikiArticleQueryPort;
    private final ArticleTypePathMapper articleTypePathMapper;

    public ListUserNotificationsUseCase(
            NotificationQueryPort notificationQueryPort,
            ChapterListQueryPort chapterListQueryPort,
            WikiArticleQueryPort wikiArticleQueryPort,
            ArticleTypePathMapper articleTypePathMapper
    ) {
        this.notificationQueryPort = Objects.requireNonNull(
                notificationQueryPort,
                "NotificationQueryPort cannot be null."
        );
        this.chapterListQueryPort = Objects.requireNonNull(
                chapterListQueryPort,
                "ChapterListQueryPort cannot be null."
        );
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort,
                "WikiArticleQueryPort cannot be null."
        );
        this.articleTypePathMapper = Objects.requireNonNull(
                articleTypePathMapper,
                "ArticleTypePathMapper cannot be null."
        );
    }

    @Transactional(readOnly = true)
    public NotificationPageDTO execute(
            UUID recipientUserId,
            NotificationFilter filter,
            int page,
            int size
    ) {
        Objects.requireNonNull(recipientUserId, "Recipient user ID cannot be null.");
        NotificationFilter safeFilter = filter != null ? filter : NotificationFilter.ALL;
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));

        NotificationSlice slice = notificationQueryPort.findByRecipientUserId(
                recipientUserId,
                safeFilter,
                safePage,
                safeSize
        );

        List<Notification> items = slice.items();
        if (items.isEmpty()) {
            return new NotificationPageDTO(
                    List.of(),
                    slice.page(),
                    slice.size(),
                    slice.totalElements(),
                    slice.totalPages(),
                    slice.first(),
                    slice.last(),
                    slice.hasNext()
            );
        }

        // 1. Collect unique target IDs by context
        Set<UUID> novelChapterIds = items.stream()
                .filter(n -> TARGET_NOVEL_CHAPTER.equalsIgnoreCase(n.getTargetType()) && n.getTargetId() != null)
                .map(Notification::getTargetId)
                .collect(Collectors.toSet());

        Set<UUID> wikiArticleIds = items.stream()
                .filter(n -> TARGET_WIKI_ARTICLE.equalsIgnoreCase(n.getTargetType()) && n.getTargetId() != null)
                .map(Notification::getTargetId)
                .collect(Collectors.toSet());

        // 2. Batch resolve context (at most 1 query per context, skipped if empty)
        Map<UUID, ChapterListItemDTO> liveChaptersMap = novelChapterIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(chapterListQueryPort.findListItemsByIds(novelChapterIds), Map.of());

        Map<UUID, WikiArticleListItemDTO> liveArticlesMap = wikiArticleIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(wikiArticleQueryPort.findListItemsByIds(wikiArticleIds), Map.of());

        // 3. Map to DTOs with canonical live action URLs
        List<NotificationDTO> dtoList = items.stream()
                .map(notification -> toDTO(notification, liveChaptersMap, liveArticlesMap))
                .toList();

        return new NotificationPageDTO(
                dtoList,
                slice.page(),
                slice.size(),
                slice.totalElements(),
                slice.totalPages(),
                slice.first(),
                slice.last(),
                slice.hasNext()
        );
    }

    private NotificationDTO toDTO(
            Notification notification,
            Map<UUID, ChapterListItemDTO> liveChaptersMap,
            Map<UUID, WikiArticleListItemDTO> liveArticlesMap
    ) {
        String actionUrl = resolveActionUrl(notification, liveChaptersMap, liveArticlesMap);
        String targetTitle = resolveTargetTitle(notification, liveChaptersMap, liveArticlesMap);

        return new NotificationDTO(
                notification.getId(),
                notification.getType(),
                notification.getActorDisplayNameSnapshot(),
                notification.getTargetType(),
                targetTitle,
                notification.getDetailSnapshot(),
                notification.isUnread(),
                notification.getReadAt(),
                notification.getCreatedAt(),
                actionUrl
        );
    }

    private String resolveTargetTitle(
            Notification notification,
            Map<UUID, ChapterListItemDTO> liveChaptersMap,
            Map<UUID, WikiArticleListItemDTO> liveArticlesMap
    ) {
        if (notification.getTargetTitleSnapshot() != null && !notification.getTargetTitleSnapshot().isBlank()) {
            return notification.getTargetTitleSnapshot();
        }

        if (notification.getTargetId() == null || notification.getTargetType() == null) {
            return null;
        }

        if (TARGET_NOVEL_CHAPTER.equalsIgnoreCase(notification.getTargetType())) {
            ChapterListItemDTO chapter = liveChaptersMap.get(notification.getTargetId());
            if (chapter != null && STATUS_PUBLISHED.equalsIgnoreCase(chapter.status())) {
                String titlePart = chapter.title() != null ? chapter.title().trim() : "";
                return chapter.chapterNumber() > 0
                        ? "Chương " + chapter.chapterNumber() + ": " + titlePart
                        : titlePart;
            }
        } else if (TARGET_WIKI_ARTICLE.equalsIgnoreCase(notification.getTargetType())) {
            WikiArticleListItemDTO article = liveArticlesMap.get(notification.getTargetId());
            if (article != null && STATUS_PUBLISHED.equalsIgnoreCase(article.status())) {
                return article.title() != null ? article.title().trim() : "";
            }
        }

        return null;
    }

    private String resolveActionUrl(
            Notification notification,
            Map<UUID, ChapterListItemDTO> liveChaptersMap,
            Map<UUID, WikiArticleListItemDTO> liveArticlesMap
    ) {
        if (notification.getTargetId() == null || notification.getTargetType() == null) {
            return null;
        }

        if (TARGET_NOVEL_CHAPTER.equalsIgnoreCase(notification.getTargetType())) {
            ChapterListItemDTO chapter = liveChaptersMap.get(notification.getTargetId());
            if (chapter == null
                    || !STATUS_PUBLISHED.equalsIgnoreCase(chapter.status())
                    || chapter.slug() == null
                    || chapter.slug().isBlank()) {
                return null;
            }

            if (notification.getCommentId() != null) {
                UUID threadId = notification.getThreadRootId() != null
                        ? notification.getThreadRootId()
                        : notification.getCommentId();
                return "/novel/chapters/" + chapter.slug().trim()
                        + "?commentId=" + notification.getCommentId()
                        + "&threadId=" + threadId
                        + "#novelChapterComments";
            }
            return "/novel/chapters/" + chapter.slug().trim();
        }

        if (TARGET_WIKI_ARTICLE.equalsIgnoreCase(notification.getTargetType())) {
            WikiArticleListItemDTO article = liveArticlesMap.get(notification.getTargetId());
            if (article == null
                    || !STATUS_PUBLISHED.equalsIgnoreCase(article.status())
                    || article.slug() == null
                    || article.slug().isBlank()
                    || article.articleType() == null
                    || article.articleType().isBlank()) {
                return null;
            }

            String typePath;
            try {
                ArticleType articleType = ArticleType.valueOf(article.articleType().trim().toUpperCase(Locale.ROOT));
                typePath = articleTypePathMapper.toPath(articleType);
            } catch (Exception e) {
                typePath = article.articleType().trim().toLowerCase(Locale.ROOT).replace('_', '-');
            }

            if (notification.getCommentId() != null) {
                UUID threadId = notification.getThreadRootId() != null
                        ? notification.getThreadRootId()
                        : notification.getCommentId();
                return "/wiki/" + typePath + "/" + article.slug().trim()
                        + "?commentId=" + notification.getCommentId()
                        + "&threadId=" + threadId
                        + "#wikiDiscussion";
            }
            return "/wiki/" + typePath + "/" + article.slug().trim();
        }

        return null;
    }
}
