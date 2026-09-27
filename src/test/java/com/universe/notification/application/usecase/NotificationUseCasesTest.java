package com.universe.notification.application.usecase;

import com.universe.notification.application.exceptions.NotificationNotFoundException;
import com.universe.notification.application.model.NotificationFilter;
import com.universe.notification.application.model.NotificationSlice;
import com.universe.notification.application.port.NotificationQueryPort;
import com.universe.notification.application.port.NotificationRepositoryPort;
import com.universe.notification.contracts.command.NotificationDispatchCommand;
import com.universe.notification.contracts.dto.NotificationDTO;
import com.universe.notification.contracts.dto.NotificationPageDTO;
import com.universe.notification.contracts.dto.UnreadNotificationCountDTO;
import com.universe.notification.contracts.port.NotificationDispatchPort;
import com.universe.notification.domain.Notification;
import com.universe.notification.domain.NotificationType;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Notification Use Cases Unit Tests")
class NotificationUseCasesTest {

    @Mock
    private NotificationDispatchPort notificationDispatchPort;

    @Mock
    private NotificationRepositoryPort notificationRepositoryPort;

    @Mock
    private NotificationQueryPort notificationQueryPort;

    @Mock
    private ChapterListQueryPort chapterListQueryPort;

    @Mock
    private WikiArticleQueryPort wikiArticleQueryPort;

    private final ArticleTypePathMapper articleTypePathMapper = new ArticleTypePathMapper();

    @Test
    @DisplayName("DispatchNotificationUseCase delegates command to dispatch port")
    void dispatchNotificationDelegates() {
        DispatchNotificationUseCase useCase = new DispatchNotificationUseCase(notificationDispatchPort);
        NotificationDispatchCommand cmd = new NotificationDispatchCommand(
                UUID.randomUUID(),
                NotificationType.COMMENT_REPLY,
                UUID.randomUUID(),
                "Actor",
                "NOVEL_CHAPTER",
                UUID.randomUUID(),
                "Chapter",
                UUID.randomUUID(),
                null,
                null,
                "dedupe-key-1"
        );

        useCase.execute(cmd);
        verify(notificationDispatchPort).dispatch(cmd);
    }

    @Test
    @DisplayName("GetUnreadNotificationCountUseCase retrieves count via repository port")
    void getUnreadCountRetrievesCount() {
        GetUnreadNotificationCountUseCase useCase = new GetUnreadNotificationCountUseCase(notificationRepositoryPort);
        UUID userId = UUID.randomUUID();

        when(notificationRepositoryPort.countUnreadByRecipientUserId(userId)).thenReturn(7L);

        UnreadNotificationCountDTO count = useCase.execute(userId);
        assertThat(count.unreadCount()).isEqualTo(7L);
    }

    @Test
    @DisplayName("ListUserNotificationsUseCase returns empty page when no notifications exist")
    void listUserNotificationsEmptyPage() {
        ListUserNotificationsUseCase useCase = new ListUserNotificationsUseCase(
                notificationQueryPort,
                chapterListQueryPort,
                wikiArticleQueryPort,
                articleTypePathMapper
        );
        UUID userId = UUID.randomUUID();

        NotificationSlice emptySlice = new NotificationSlice(List.of(), 0, 1, 0L, 0, true, true, false);
        when(notificationQueryPort.findByRecipientUserId(userId, NotificationFilter.UNREAD, 0, 1))
                .thenReturn(emptySlice);

        NotificationPageDTO result = useCase.execute(userId, NotificationFilter.UNREAD, -1, 0);
        assertThat(result.items()).isEmpty();
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(1);
        verify(notificationQueryPort).findByRecipientUserId(userId, NotificationFilter.UNREAD, 0, 1);
        verifyNoInteractions(chapterListQueryPort);
        verifyNoInteractions(wikiArticleQueryPort);
    }

    @Test
    @DisplayName("ListUserNotificationsUseCase batch resolves Novel chapter deep links for COMMENT_REPLY")
    void listUserNotificationsResolvesNovelChapterDeepLinks() {
        ListUserNotificationsUseCase useCase = new ListUserNotificationsUseCase(
                notificationQueryPort,
                chapterListQueryPort,
                wikiArticleQueryPort,
                articleTypePathMapper
        );
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T10:00:00Z");

        Notification notif = Notification.create(
                notifId,
                userId,
                NotificationType.COMMENT_REPLY,
                UUID.randomUUID(),
                "Actor User",
                "NOVEL_CHAPTER",
                chapterId,
                null,
                replyId,
                rootId,
                null,
                "COMMENT_REPLY:" + replyId,
                now
        );

        NotificationSlice slice = new NotificationSlice(List.of(notif), 0, 20, 1L, 1, true, true, false);
        when(notificationQueryPort.findByRecipientUserId(userId, NotificationFilter.ALL, 0, 20))
                .thenReturn(slice);

        ChapterListItemDTO chapterItem = new ChapterListItemDTO(
                chapterId,
                15,
                "Đại Chiến Hắc Ám",
                "dai-chien-hac-am",
                "PUBLISHED",
                now
        );
        when(chapterListQueryPort.findListItemsByIds(Set.of(chapterId)))
                .thenReturn(Map.of(chapterId, chapterItem));

        NotificationPageDTO result = useCase.execute(userId, NotificationFilter.ALL, 0, 20);

        assertThat(result.items()).hasSize(1);
        NotificationDTO item = result.items().get(0);
        assertThat(item.id()).isEqualTo(notifId);
        assertThat(item.type()).isEqualTo(NotificationType.COMMENT_REPLY);
        assertThat(item.targetTitleSnapshot()).isEqualTo("Chương 15: Đại Chiến Hắc Ám");
        assertThat(item.actionUrl()).isEqualTo("/novel/chapters/dai-chien-hac-am?commentId=" + replyId + "&threadId=" + rootId + "#novelChapterComments");
        assertThat(item.unread()).isTrue();

        verify(chapterListQueryPort).findListItemsByIds(Set.of(chapterId));
        verifyNoInteractions(wikiArticleQueryPort);
    }

    @Test
    @DisplayName("ListUserNotificationsUseCase batch resolves Wiki article deep links for COMMENT_REPLY")
    void listUserNotificationsResolvesWikiArticleDeepLinks() {
        ListUserNotificationsUseCase useCase = new ListUserNotificationsUseCase(
                notificationQueryPort,
                chapterListQueryPort,
                wikiArticleQueryPort,
                articleTypePathMapper
        );
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T10:00:00Z");

        // threadRootId is null -> uses replyId as threadId fallback
        Notification notif = Notification.create(
                notifId,
                userId,
                NotificationType.COMMENT_REPLY,
                UUID.randomUUID(),
                "Actor User",
                "WIKI_ARTICLE",
                articleId,
                null,
                replyId,
                null,
                null,
                "COMMENT_REPLY:" + replyId,
                now
        );

        NotificationSlice slice = new NotificationSlice(List.of(notif), 0, 20, 1L, 1, true, true, false);
        when(notificationQueryPort.findByRecipientUserId(userId, NotificationFilter.ALL, 0, 20))
                .thenReturn(slice);

        WikiArticleListItemDTO articleItem = new WikiArticleListItemDTO(
                articleId,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "PUBLISHED",
                UUID.randomUUID(),
                now,
                now,
                1L
        );
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(articleId)))
                .thenReturn(Map.of(articleId, articleItem));

        NotificationPageDTO result = useCase.execute(userId, NotificationFilter.ALL, 0, 20);

        assertThat(result.items()).hasSize(1);
        NotificationDTO item = result.items().get(0);
        assertThat(item.id()).isEqualTo(notifId);
        assertThat(item.type()).isEqualTo(NotificationType.COMMENT_REPLY);
        assertThat(item.targetTitleSnapshot()).isEqualTo("Trần Bình An");
        assertThat(item.actionUrl()).isEqualTo("/wiki/character/tran-binh-an?commentId=" + replyId + "&threadId=" + replyId + "#wikiDiscussion");

        verify(wikiArticleQueryPort).findListItemsByIds(Set.of(articleId));
        verifyNoInteractions(chapterListQueryPort);
    }

    @Test
    @DisplayName("ListUserNotificationsUseCase sets actionUrl=null when target is unpublished or deleted")
    void listUserNotificationsSetsActionUrlNullWhenUnpublished() {
        ListUserNotificationsUseCase useCase = new ListUserNotificationsUseCase(
                notificationQueryPort,
                chapterListQueryPort,
                wikiArticleQueryPort,
                articleTypePathMapper
        );
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T10:00:00Z");

        Notification notif = Notification.create(
                notifId,
                userId,
                NotificationType.COMMENT_REPLY,
                UUID.randomUUID(),
                "Actor User",
                "NOVEL_CHAPTER",
                chapterId,
                "Draft Chapter Title",
                replyId,
                replyId,
                null,
                "COMMENT_REPLY:" + replyId,
                now
        );

        NotificationSlice slice = new NotificationSlice(List.of(notif), 0, 20, 1L, 1, true, true, false);
        when(notificationQueryPort.findByRecipientUserId(userId, NotificationFilter.ALL, 0, 20))
                .thenReturn(slice);

        // Chapter is in DRAFT status
        ChapterListItemDTO draftChapter = new ChapterListItemDTO(
                chapterId,
                1,
                "Bản Thảo",
                "ban-thao",
                "DRAFT",
                now
        );
        when(chapterListQueryPort.findListItemsByIds(Set.of(chapterId)))
                .thenReturn(Map.of(chapterId, draftChapter));

        NotificationPageDTO result = useCase.execute(userId, NotificationFilter.ALL, 0, 20);

        assertThat(result.items()).hasSize(1);
        NotificationDTO item = result.items().get(0);
        assertThat(item.targetTitleSnapshot()).isEqualTo("Draft Chapter Title");
        assertThat(item.actionUrl()).isNull();
    }

    @Test
    @DisplayName("ListUserNotificationsUseCase resolves Wiki contribution notification with /wiki/contributions and zero query lookups")
    void listUserNotificationsResolvesWikiContributionNotifications() {
        ListUserNotificationsUseCase useCase = new ListUserNotificationsUseCase(
                notificationQueryPort,
                chapterListQueryPort,
                wikiArticleQueryPort,
                articleTypePathMapper
        );
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        UUID contribId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T10:00:00Z");

        Notification notif = Notification.create(
                notifId,
                userId,
                NotificationType.WIKI_CONTRIBUTION_RESOLVED,
                UUID.randomUUID(),
                null,
                "WIKI_CONTRIBUTION",
                contribId,
                "Kiếm Các Bài Viết",
                null,
                null,
                "Đã áp dụng thay đổi thành công.",
                "WIKI_CONTRIBUTION:" + contribId + ":RESOLVED",
                now
        );

        NotificationSlice slice = new NotificationSlice(List.of(notif), 0, 20, 1L, 1, true, true, false);
        when(notificationQueryPort.findByRecipientUserId(userId, NotificationFilter.ALL, 0, 20))
                .thenReturn(slice);

        NotificationPageDTO result = useCase.execute(userId, NotificationFilter.ALL, 0, 20);

        assertThat(result.items()).hasSize(1);
        NotificationDTO item = result.items().get(0);
        assertThat(item.id()).isEqualTo(notifId);
        assertThat(item.type()).isEqualTo(NotificationType.WIKI_CONTRIBUTION_RESOLVED);
        assertThat(item.targetTitleSnapshot()).isEqualTo("Kiếm Các Bài Viết");
        assertThat(item.detailSnapshot()).isEqualTo("Đã áp dụng thay đổi thành công.");
        assertThat(item.actionUrl()).isEqualTo("/wiki/contributions");
        assertThat(item.unread()).isTrue();

        // Strict verification: zero live queries to chapter or wiki article ports!
        verifyNoInteractions(chapterListQueryPort);
        verifyNoInteractions(wikiArticleQueryPort);
    }

    @Test
    @DisplayName("MarkNotificationReadUseCase marks read successfully when owned and unread")
    void markNotificationReadSuccess() {
        MarkNotificationReadUseCase useCase = new MarkNotificationReadUseCase(notificationRepositoryPort);
        UUID notifId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        when(notificationRepositoryPort.markAsRead(eq(notifId), eq(userId), any(Instant.class))).thenReturn(true);

        useCase.execute(notifId, userId, now);
        verify(notificationRepositoryPort).markAsRead(notifId, userId, now);
    }

    @Test
    @DisplayName("MarkNotificationReadUseCase throws NotificationNotFoundException when row does not exist")
    void markNotificationReadNotFound() {
        MarkNotificationReadUseCase useCase = new MarkNotificationReadUseCase(notificationRepositoryPort);
        UUID notifId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(notificationRepositoryPort.markAsRead(eq(notifId), eq(userId), any(Instant.class))).thenReturn(false);
        when(notificationRepositoryPort.findById(notifId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(notifId, userId, Instant.now()))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    @DisplayName("MarkNotificationReadUseCase throws NotificationNotFoundException when row belongs to another user")
    void markNotificationReadWrongOwner() {
        MarkNotificationReadUseCase useCase = new MarkNotificationReadUseCase(notificationRepositoryPort);
        UUID notifId = UUID.randomUUID();
        UUID callerUserId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();

        when(notificationRepositoryPort.markAsRead(eq(notifId), eq(callerUserId), any(Instant.class))).thenReturn(false);
        Notification otherNotification = Notification.create(
                notifId,
                otherUserId,
                NotificationType.COMMENT_REPLY,
                null, null, null, null, null, null, null, null, "dedupe", Instant.now()
        );
        when(notificationRepositoryPort.findById(notifId)).thenReturn(Optional.of(otherNotification));

        assertThatThrownBy(() -> useCase.execute(notifId, callerUserId, Instant.now()))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    @DisplayName("MarkNotificationReadUseCase completes silently when already read by owner")
    void markNotificationReadIdempotentAlreadyRead() {
        MarkNotificationReadUseCase useCase = new MarkNotificationReadUseCase(notificationRepositoryPort);
        UUID notifId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(notificationRepositoryPort.markAsRead(eq(notifId), eq(userId), any(Instant.class))).thenReturn(false);
        Notification alreadyRead = Notification.reconstitute(
                notifId,
                userId,
                NotificationType.COMMENT_REPLY,
                null, null, null, null, null, null, null, null, "dedupe",
                Instant.now(),
                Instant.now()
        );
        when(notificationRepositoryPort.findById(notifId)).thenReturn(Optional.of(alreadyRead));

        // Must succeed without throwing
        useCase.execute(notifId, userId, Instant.now());
    }

    @Test
    @DisplayName("MarkAllNotificationsReadUseCase delegates to repository port")
    void markAllNotificationsReadDelegates() {
        MarkAllNotificationsReadUseCase useCase = new MarkAllNotificationsReadUseCase(notificationRepositoryPort);
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        when(notificationRepositoryPort.markAllAsRead(eq(userId), any(Instant.class))).thenReturn(4);

        int count = useCase.execute(userId, now);
        assertThat(count).isEqualTo(4);
        verify(notificationRepositoryPort).markAllAsRead(userId, now);
    }
}
