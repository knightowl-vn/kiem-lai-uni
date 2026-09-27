package com.universe.interaction.application.mutation;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.infrastructure.eligibility.CommentTargetEligibilityAdapter;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.CommentRevisionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentRevisionPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceMapper;
import com.universe.notification.application.model.NotificationFilter;
import com.universe.notification.application.usecase.GetUnreadNotificationCountUseCase;
import com.universe.notification.application.usecase.ListUserNotificationsUseCase;
import com.universe.notification.application.usecase.MarkAllNotificationsReadUseCase;
import com.universe.notification.application.usecase.MarkNotificationReadUseCase;
import com.universe.notification.contracts.dto.NotificationDTO;
import com.universe.notification.contracts.dto.NotificationPageDTO;
import com.universe.notification.domain.NotificationType;
import com.universe.notification.infrastructure.persistence.NotificationPersistenceAdapter;
import com.universe.notification.infrastructure.persistence.NotificationPersistenceMapper;
import com.universe.notification.infrastructure.persistence.NotificationQueryPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.chapter.ChapterListQueryPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.reader.ReaderChapterAccessQueryPersistenceAdapter;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.SystemClockAdapter;
import com.universe.test.TestDatabaseSupport;
import com.universe.notification.contracts.command.NotificationDispatchCommand;
import com.universe.notification.contracts.port.NotificationDispatchPort;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        CommentPersistenceAdapter.class,
        CommentPersistenceMapper.class,
        CommentRevisionPersistenceAdapter.class,
        CommentRevisionPersistenceMapper.class,
        ReactionPersistenceAdapter.class,
        ReactionPersistenceMapper.class,
        CommentTargetEligibilityAdapter.class,
        ReaderChapterAccessQueryPersistenceAdapter.class,
        ChapterListQueryPersistenceAdapter.class,
        NotificationPersistenceAdapter.class,
        NotificationPersistenceMapper.class,
        NotificationQueryPersistenceAdapter.class,
        CreateRootCommentUseCase.class,
        ReplyCommentUseCase.class,
        ListUserNotificationsUseCase.class,
        GetUnreadNotificationCountUseCase.class,
        MarkNotificationReadUseCase.class,
        MarkAllNotificationsReadUseCase.class,
        UuidGeneratorAdapter.class,
        SystemClockAdapter.class,
        ArticleTypePathMapper.class
})
@DisplayName("Comment Reply Notification Integration Tests (MS-05K3)")
class CommentReplyNotificationIntegrationTest {

    private static final UUID USER_A_ID = UUID.fromString("1111bbbb-0000-0000-0000-000000000001");
    private static final UUID USER_B_ID = UUID.fromString("2222bbbb-0000-0000-0000-000000000002");
    private static final UUID USER_C_ID = UUID.fromString("3333bbbb-0000-0000-0000-000000000003");

    private static final UUID VOLUME_ID = UUID.fromString("aaaa2222-0000-0000-0000-000000000001");
    private static final UUID CH_ID = UUID.fromString("cccc2222-0000-0000-0000-000000000001");
    private static final int VOL_SORT_ORDER = 8_200_001;
    private static final int CH_NUM = 8_200_001;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private WikiArticleQueryPort wikiArticleQueryPort;

    @Autowired
    private CreateRootCommentUseCase createRootCommentUseCase;

    @Autowired
    private ReplyCommentUseCase replyCommentUseCase;

    @Autowired
    private ListUserNotificationsUseCase listUserNotificationsUseCase;

    @Autowired
    private GetUnreadNotificationCountUseCase getUnreadNotificationCountUseCase;

    @Autowired
    private MarkNotificationReadUseCase markNotificationReadUseCase;

    @Autowired
    private MarkAllNotificationsReadUseCase markAllNotificationsReadUseCase;

    @SpyBean
    private NotificationPersistenceAdapter notificationPersistenceAdapter;

    @BeforeEach
    void setUp() {
        cleanupDatabase();
        seedBaseData();
    }

    @AfterEach
    void tearDown() {
        cleanupDatabase();
    }

    private void cleanupDatabase() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbcTemplate.execute("DELETE FROM notifications;");
        jdbcTemplate.execute("DELETE FROM interaction_comment_revisions;");
        jdbcTemplate.execute("DELETE FROM interaction_comments;");
        jdbcTemplate.execute("DELETE FROM novel_chapters;");
        jdbcTemplate.execute("DELETE FROM novel_volumes;");
        jdbcTemplate.update(
                "DELETE FROM identity_users WHERE id IN (?, ?, ?)",
                USER_A_ID.toString(), USER_B_ID.toString(), USER_C_ID.toString()
        );
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. Users A, B, C
        seedUser(USER_A_ID, "notif-user-a@kiemlai.local", "User A");
        seedUser(USER_B_ID, "notif-user-b@kiemlai.local", "User B");
        seedUser(USER_C_ID, "notif-user-c@kiemlai.local", "User C");

        // 2. Volume & Chapter
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Thông Báo', 'quyen-thong-bao', 'Mô tả', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), VOL_SORT_ORDER, USER_A_ID.toString(), USER_A_ID.toString(), USER_A_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, content_version, aggregate_version, persistence_version) " +
                        "VALUES (?, ?, ?, 'Chương 1 Đấu Trí', 'chuong-1-dau-tri', 'Tóm tắt', 'Nội dung', 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 1, 0)",
                CH_ID.toString(), VOLUME_ID.toString(), CH_NUM,
                USER_A_ID.toString(), USER_A_ID.toString(), USER_A_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
    }

    private void seedUser(UUID id, String email, String displayName) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, '$2a$10$hash', ?, 'ACTIVE', 'USER', 1, 0, ?, ?)",
                id.toString(), email, displayName, Timestamp.from(now), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("Direct reply to root comment creates notification for root author with hard-delete privacy and canonical URL")
    void directReplyCreatesNotificationAtomically() {
        // 1. User A posts root comment
        CommentTarget target = CommentTarget.novelChapter(CH_ID);
        Comment root = createRootCommentUseCase.execute(
                new CreateRootCommentCommand(USER_A_ID, target, "Bình luận gốc từ User A")
        );

        // Verify initially 0 notifications
        assertThat(getUnreadNotificationCountUseCase.execute(USER_A_ID).unreadCount()).isZero();

        // 2. User B replies to User A's root comment
        ReplyCommentCommand replyCmd = new ReplyCommentCommand(USER_B_ID, root.getId(), "User B trả lời User A");
        Comment reply = replyCommentUseCase.execute(replyCmd);

        // 3. User A unread count is now 1
        assertThat(getUnreadNotificationCountUseCase.execute(USER_A_ID).unreadCount()).isEqualTo(1L);
        assertThat(getUnreadNotificationCountUseCase.execute(USER_B_ID).unreadCount()).isZero();

        // 4. Verify physical database row in notifications
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM notifications WHERE recipient_user_id = ?",
                USER_A_ID.toString()
        );
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("type")).isEqualTo("COMMENT_REPLY");
        assertThat(row.get("actor_user_id")).isEqualTo(USER_B_ID.toString());
        assertThat(row.get("target_type")).isEqualTo("NOVEL_CHAPTER");
        assertThat(row.get("target_id")).isEqualTo(CH_ID.toString());
        assertThat(row.get("comment_id")).isEqualTo(reply.getId().toString());
        assertThat(row.get("thread_root_id")).isEqualTo(root.getId().toString());
        assertThat(row.get("detail_snapshot")).isNull(); // Hard-delete privacy invariant
        assertThat(row.get("dedupe_key")).isEqualTo("COMMENT_REPLY:" + reply.getId());
        assertThat(row.get("read_at")).isNull();

        // 5. Query notifications feed for User A
        NotificationPageDTO page = listUserNotificationsUseCase.execute(USER_A_ID, NotificationFilter.ALL, 0, 20);
        assertThat(page.items()).hasSize(1);
        NotificationDTO item = page.items().get(0);
        assertThat(item.type()).isEqualTo(NotificationType.COMMENT_REPLY);
        assertThat(item.unread()).isTrue();
        assertThat(item.targetTitleSnapshot()).isEqualTo("Chương " + CH_NUM + ": Chương 1 Đấu Trí");
        assertThat(item.actionUrl()).isEqualTo(
                "/novel/chapters/chuong-1-dau-tri?commentId=" + reply.getId() + "&threadId=" + root.getId() + "#novelChapterComments"
        );
    }

    @Test
    @DisplayName("Self-reply suppression: User replying to own comment dispatches NO notification")
    void selfReplyDispatchesNoNotification() {
        CommentTarget target = CommentTarget.novelChapter(CH_ID);
        Comment root = createRootCommentUseCase.execute(
                new CreateRootCommentCommand(USER_A_ID, target, "User A viết gốc")
        );

        // User A replies to their own root comment
        ReplyCommentCommand selfReplyCmd = new ReplyCommentCommand(USER_A_ID, root.getId(), "User A tự trả lời mình");
        replyCommentUseCase.execute(selfReplyCmd);

        // Notifications table must remain completely empty
        Integer totalNotifs = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        assertThat(totalNotifs).isZero();
        assertThat(getUnreadNotificationCountUseCase.execute(USER_A_ID).unreadCount()).isZero();
    }

    @Test
    @DisplayName("Nested reply notifies only immediate parent author, NOT the thread root author")
    void nestedReplyNotifiesOnlyImmediateParentAuthor() {
        // 1. User A posts root comment
        CommentTarget target = CommentTarget.novelChapter(CH_ID);
        Comment root = createRootCommentUseCase.execute(
                new CreateRootCommentCommand(USER_A_ID, target, "Root by A")
        );

        // 2. User B replies to root
        Comment replyB = replyCommentUseCase.execute(
                new ReplyCommentCommand(USER_B_ID, root.getId(), "Reply by B to A")
        );

        // User A received 1 notification for replyB
        assertThat(getUnreadNotificationCountUseCase.execute(USER_A_ID).unreadCount()).isEqualTo(1L);

        // 3. User C replies to User B's reply (nested reply)
        Comment replyC = replyCommentUseCase.execute(
                new ReplyCommentCommand(USER_C_ID, replyB.getId(), "Reply by C to B")
        );

        // User B must receive 1 notification (as immediate parent author)
        assertThat(getUnreadNotificationCountUseCase.execute(USER_B_ID).unreadCount()).isEqualTo(1L);

        // User A must STILL have only 1 notification (not notified for reply to B!)
        assertThat(getUnreadNotificationCountUseCase.execute(USER_A_ID).unreadCount()).isEqualTo(1L);

        // User C has 0 notifications
        assertThat(getUnreadNotificationCountUseCase.execute(USER_C_ID).unreadCount()).isZero();

        // Check User B's notification action URL
        NotificationPageDTO pageB = listUserNotificationsUseCase.execute(USER_B_ID, NotificationFilter.ALL, 0, 20);
        assertThat(pageB.items()).hasSize(1);
        NotificationDTO notifB = pageB.items().get(0);
        assertThat(notifB.actionUrl()).isEqualTo(
                "/novel/chapters/chuong-1-dau-tri?commentId=" + replyC.getId() + "&threadId=" + root.getId() + "#novelChapterComments"
        );
    }

    @Test
    @DisplayName("Wiki comment reply notification resolves wiki canonical deep link")
    void wikiReplyResolvesWikiDeepLink() {
        UUID wikiArticleId = UUID.fromString("ffff1111-0000-0000-0000-000000000001");
        Instant now = Instant.now();

        when(wikiArticleQueryPort.isPublished(wikiArticleId)).thenReturn(true);
        when(wikiArticleQueryPort.findListItemsByIds(anySet())).thenReturn(Map.of(
                wikiArticleId,
                new WikiArticleListItemDTO(
                        wikiArticleId,
                        "Lý Bảo Bình",
                        "ly-bao-binh",
                        "CHARACTER",
                        "PUBLISHED",
                        USER_A_ID,
                        now,
                        now,
                        1L
                )
        ));

        // 1. User A posts root comment on Wiki
        CommentTarget wikiTarget = CommentTarget.wikiArticle(wikiArticleId);
        Comment root = createRootCommentUseCase.execute(
                new CreateRootCommentCommand(USER_A_ID, wikiTarget, "Wiki discussion root")
        );

        // 2. User B replies to wiki root
        Comment reply = replyCommentUseCase.execute(
                new ReplyCommentCommand(USER_B_ID, root.getId(), "Wiki reply by B")
        );

        // 3. User A unread count = 1
        assertThat(getUnreadNotificationCountUseCase.execute(USER_A_ID).unreadCount()).isEqualTo(1L);

        // 4. Query feed and verify canonical action URL
        NotificationPageDTO page = listUserNotificationsUseCase.execute(USER_A_ID, NotificationFilter.ALL, 0, 20);
        assertThat(page.items()).hasSize(1);
        NotificationDTO item = page.items().get(0);
        assertThat(item.targetTitleSnapshot()).isEqualTo("Lý Bảo Bình");
        assertThat(item.actionUrl()).isEqualTo(
                "/wiki/character/ly-bao-binh?commentId=" + reply.getId() + "&threadId=" + root.getId() + "#wikiDiscussion"
        );
    }

    @Test
    @DisplayName("Read state mutation transitions unread count and filters correctly")
    void readStateMutationTransitions() {
        CommentTarget target = CommentTarget.novelChapter(CH_ID);
        Comment root = createRootCommentUseCase.execute(
                new CreateRootCommentCommand(USER_A_ID, target, "Root by A")
        );
        replyCommentUseCase.execute(
                new ReplyCommentCommand(USER_B_ID, root.getId(), "Reply by B")
        );

        NotificationPageDTO unreadPageBefore = listUserNotificationsUseCase.execute(USER_A_ID, NotificationFilter.UNREAD, 0, 20);
        assertThat(unreadPageBefore.items()).hasSize(1);
        UUID notifId = unreadPageBefore.items().get(0).id();

        // Mark as read
        markNotificationReadUseCase.execute(notifId, USER_A_ID, Instant.now());

        // Unread count is now 0
        assertThat(getUnreadNotificationCountUseCase.execute(USER_A_ID).unreadCount()).isZero();

        // UNREAD filter returns 0 items
        NotificationPageDTO unreadPageAfter = listUserNotificationsUseCase.execute(USER_A_ID, NotificationFilter.UNREAD, 0, 20);
        assertThat(unreadPageAfter.items()).isEmpty();

        // ALL filter returns 1 item with unread = false
        NotificationPageDTO allPage = listUserNotificationsUseCase.execute(USER_A_ID, NotificationFilter.ALL, 0, 20);
        assertThat(allPage.items()).hasSize(1);
        assertThat(allPage.items().get(0).unread()).isFalse();
        assertThat(allPage.items().get(0).readAt()).isNotNull();
    }

    @Test
    @DisplayName("Transaction rolls back completely and persists no comment when notification dispatch fails (non-dedupe)")
    void transactionRollsBackWhenNotificationDispatchFailsNonDedupe() {
        CommentTarget target = CommentTarget.novelChapter(CH_ID);
        Comment root = createRootCommentUseCase.execute(
                new CreateRootCommentCommand(USER_A_ID, target, "Root for rollback test")
        );

        doThrow(new RuntimeException("Simulated notification dispatch infrastructure failure"))
                .when(notificationPersistenceAdapter).dispatch(any());

        ReplyCommentCommand replyCmd = new ReplyCommentCommand(USER_B_ID, root.getId(), "This reply should roll back");

        assertThatThrownBy(() -> replyCommentUseCase.execute(replyCmd))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated notification dispatch infrastructure failure");

        // Verify comment reply was NOT persisted in interaction_comments
        Integer replyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE parent_comment_id = ?",
                Integer.class,
                root.getId().toString()
        );
        assertThat(replyCount).isZero();

        // Verify notifications table has no rows
        Integer notifCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        assertThat(notifCount).isZero();
    }

    @Test
    @DisplayName("Duplicate notification dispatch leaves transaction healthy and creates no duplicate rows")
    void duplicateNotificationDispatchLeavesTransactionHealthyAndCreatesNoDuplicate() {
        CommentTarget target = CommentTarget.novelChapter(CH_ID);
        Comment root = createRootCommentUseCase.execute(
                new CreateRootCommentCommand(USER_A_ID, target, "Root for dedupe test")
        );

        // 1. Initial reply creates 1 notification row
        Comment reply = replyCommentUseCase.execute(
                new ReplyCommentCommand(USER_B_ID, root.getId(), "Reply for dedupe test")
        );

        Integer notifCountAfterFirst = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?",
                Integer.class,
                USER_A_ID.toString()
        );
        assertThat(notifCountAfterFirst).isEqualTo(1);

        // 2. Direct duplicate dispatch with the same dedupe_key (e.g. at-least-once retry)
        NotificationDispatchCommand duplicateCommand = new NotificationDispatchCommand(
                USER_A_ID,
                NotificationType.COMMENT_REPLY,
                USER_B_ID,
                null,
                "NOVEL_CHAPTER",
                CH_ID,
                null,
                reply.getId(),
                root.getId(),
                null,
                "COMMENT_REPLY:" + reply.getId()
        );

        // Should complete without throwing DataIntegrityViolationException
        notificationPersistenceAdapter.dispatch(duplicateCommand);

        // 3. Verify exactly 1 notification row still exists
        Integer notifCountAfterDuplicate = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?",
                Integer.class,
                USER_A_ID.toString()
        );
        assertThat(notifCountAfterDuplicate).isEqualTo(1);
    }

    @Test
    @DisplayName("Draft or unpublished target does not leak live title or actionUrl when snapshot is null")
    void draftTargetDoesNotLeakLiveTitleOrUrlWhenSnapshotIsNull() {
        // 1. Novel chapter in DRAFT state
        UUID draftChapterId = UUID.fromString("cccc3333-0000-0000-0000-000000000001");
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, content_version, aggregate_version, persistence_version) " +
                        "VALUES (?, ?, 9999, 'Bí Mật Bản Thảo', 'bi-mat-ban-thao', 'Tóm tắt', 'Nội dung', 'DRAFT', ?, ?, NULL, NULL, ?, ?, NULL, NULL, 1, 1, 0)",
                draftChapterId.toString(), VOLUME_ID.toString(),
                USER_A_ID.toString(), USER_A_ID.toString(),
                Timestamp.from(now), Timestamp.from(now)
        );

        // Insert notification pointing to draft chapter without title snapshot
        UUID draftNotifId = UUID.fromString("dddd1111-0000-0000-0000-000000000001");
        jdbcTemplate.update(
                "INSERT INTO notifications (id, recipient_user_id, type, actor_user_id, actor_display_name_snapshot, target_type, target_id, target_title_snapshot, comment_id, thread_root_id, detail_snapshot, dedupe_key, read_at, created_at) " +
                        "VALUES (?, ?, 'COMMENT_REPLY', ?, NULL, 'NOVEL_CHAPTER', ?, NULL, NULL, NULL, NULL, 'DRAFT_TEST_KEY', NULL, ?)",
                draftNotifId.toString(), USER_A_ID.toString(), USER_B_ID.toString(), draftChapterId.toString(), Timestamp.from(now)
        );

        NotificationPageDTO page = listUserNotificationsUseCase.execute(USER_A_ID, NotificationFilter.ALL, 0, 20);
        assertThat(page.items()).hasSize(1);
        NotificationDTO item = page.items().get(0);

        // Invariant: actionUrl and targetTitleSnapshot MUST be null for unpublished/draft targets
        assertThat(item.actionUrl()).isNull();
        assertThat(item.targetTitleSnapshot()).isNull();
    }
}
