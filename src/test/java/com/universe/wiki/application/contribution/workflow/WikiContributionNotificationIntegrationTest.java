package com.universe.wiki.application.contribution.workflow;

import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.notification.application.model.NotificationFilter;
import com.universe.notification.application.usecase.GetUnreadNotificationCountUseCase;
import com.universe.notification.application.usecase.ListUserNotificationsUseCase;
import com.universe.notification.application.usecase.MarkAllNotificationsReadUseCase;
import com.universe.notification.application.usecase.MarkNotificationReadUseCase;
import com.universe.notification.contracts.command.NotificationDispatchCommand;
import com.universe.notification.contracts.dto.NotificationDTO;
import com.universe.notification.contracts.dto.NotificationPageDTO;
import com.universe.notification.domain.NotificationType;
import com.universe.notification.infrastructure.persistence.NotificationPersistenceAdapter;
import com.universe.notification.infrastructure.persistence.NotificationPersistenceMapper;
import com.universe.notification.infrastructure.persistence.NotificationQueryPersistenceAdapter;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.SystemClockAdapter;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.infrastructure.persistence.article.WikiArticlePersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.contribution.WikiContributionPersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.contribution.WikiContributionWorkflowEventPersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.revision.WikiArticleRevisionPersistenceAdapter;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        WikiContributionPersistenceAdapter.class,
        WikiContributionWorkflowEventPersistenceAdapter.class,
        WikiArticlePersistenceAdapter.class,
        WikiArticleRevisionPersistenceAdapter.class,
        NotificationPersistenceAdapter.class,
        NotificationPersistenceMapper.class,
        NotificationQueryPersistenceAdapter.class,
        AdminWikiContributionWorkflowUseCase.class,
        ListUserNotificationsUseCase.class,
        GetUnreadNotificationCountUseCase.class,
        MarkNotificationReadUseCase.class,
        MarkAllNotificationsReadUseCase.class,
        UuidGeneratorAdapter.class,
        SystemClockAdapter.class,
        ArticleTypePathMapper.class
})
@DisplayName("Wiki Contribution Notification Integration Tests (MS-05K4)")
class WikiContributionNotificationIntegrationTest {

    private static final UUID CONTRIBUTOR_USER_ID = UUID.fromString("1111cccc-0000-0000-0000-000000000001");
    private static final UUID ADMIN_USER_ID = UUID.fromString("2222cccc-0000-0000-0000-000000000002");
    private static final UUID ARTICLE_ID = UUID.fromString("aaaa3333-0000-0000-0000-000000000001");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private UserIdentityContract userIdentityContract;

    @MockBean
    private com.universe.wiki.infrastructure.persistence.image.WikiImageReferenceSynchronizer wikiImageReferenceSynchronizer;

    @MockBean
    private ChapterListQueryPort chapterListQueryPort;

    @MockBean
    private WikiArticleQueryPort wikiArticleQueryPort;

    @Autowired
    private AdminWikiContributionWorkflowUseCase adminWorkflowUseCase;

    @Autowired
    private ListUserNotificationsUseCase listUserNotificationsUseCase;

    @Autowired
    private GetUnreadNotificationCountUseCase getUnreadNotificationCountUseCase;

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
        jdbcTemplate.execute("DELETE FROM wiki_contribution_workflow_events;");
        jdbcTemplate.execute("DELETE FROM wiki_contributions;");
        jdbcTemplate.execute("DELETE FROM wiki_article_revisions;");
        jdbcTemplate.execute("DELETE FROM wiki_articles;");
        jdbcTemplate.update(
                "DELETE FROM identity_users WHERE id IN (?, ?)",
                CONTRIBUTOR_USER_ID.toString(), ADMIN_USER_ID.toString()
        );
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. Users
        seedUser(CONTRIBUTOR_USER_ID, "contributor@kiemlai.local", "Contributor Reader", "USER");
        seedUser(ADMIN_USER_ID, "admin-reviewer@kiemlai.local", "Admin Reviewer", "ADMIN");

        // 2. Article
        jdbcTemplate.update(
                "INSERT INTO wiki_articles (id, title, slug, article_type, summary, content, status, cover_position_x, cover_position_y, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, content_version, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Trần Bình An', 'tran-binh-an', 'CHARACTER', 'Tóm tắt nhân vật', 'Nội dung chi tiết', 'PUBLISHED', 50, 50, ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 1, 0)",
                ARTICLE_ID.toString(), ADMIN_USER_ID.toString(), ADMIN_USER_ID.toString(), ADMIN_USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
    }

    private void seedUser(UUID id, String email, String displayName, String role) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, '$2a$10$hash', ?, 'ACTIVE', ?, 1, 0, ?, ?)",
                id.toString(), email, displayName, role, Timestamp.from(now), Timestamp.from(now)
        );
    }

    private UUID seedContribution(UUID submittedBy) {
        Instant now = Instant.now();
        UUID contribId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO wiki_contributions (id, article_id, article_type_snapshot, article_title_snapshot, article_slug_snapshot, article_content_version, submitted_by_user_id, context_type, contribution_type, message, status, version, created_at, updated_at) " +
                        "VALUES (?, ?, 'CHARACTER', 'Trần Bình An', 'tran-binh-an', 1, ?, 'GENERAL', 'INCORRECT_INFORMATION', 'Thông tin cảnh giới nhân vật chưa đầy đủ.', 'NEW', 0, ?, ?)",
                contribId.toString(), ARTICLE_ID.toString(), submittedBy.toString(),
                Timestamp.from(now), Timestamp.from(now)
        );
        return contribId;
    }

    @Test
    @DisplayName("Review workflow transitions NEW to REVIEWING and dispatches WIKI_CONTRIBUTION_REVIEWING notification")
    void reviewWorkflowDispatchesReviewingNotification() {
        UUID contribId = seedContribution(CONTRIBUTOR_USER_ID);

        // Initially 0 notifications
        assertThat(getUnreadNotificationCountUseCase.execute(CONTRIBUTOR_USER_ID).unreadCount()).isZero();

        // Admin reviews contribution
        WikiContribution result = adminWorkflowUseCase.review(
                new ReviewWikiContributionCommand(contribId, ADMIN_USER_ID, 0L)
        );

        assertThat(result.getStatus()).isEqualTo(WikiContributionStatus.REVIEWING);

        // Contributor receives 1 unread notification
        assertThat(getUnreadNotificationCountUseCase.execute(CONTRIBUTOR_USER_ID).unreadCount()).isEqualTo(1L);

        // Verify physical row
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM notifications WHERE recipient_user_id = ?",
                CONTRIBUTOR_USER_ID.toString()
        );
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("type")).isEqualTo("WIKI_CONTRIBUTION_REVIEWING");
        assertThat(row.get("actor_user_id")).isEqualTo(ADMIN_USER_ID.toString());
        assertThat(row.get("target_type")).isEqualTo("WIKI_CONTRIBUTION");
        assertThat(row.get("target_id")).isEqualTo(contribId.toString());
        assertThat(row.get("target_title_snapshot")).isEqualTo("Trần Bình An");
        assertThat(row.get("detail_snapshot")).isNull();
        assertThat(row.get("dedupe_key")).isEqualTo("WIKI_CONTRIBUTION:" + contribId + ":REVIEWING");
        assertThat(row.get("read_at")).isNull();

        // Query notification feed and verify /wiki/contributions deep link
        NotificationPageDTO page = listUserNotificationsUseCase.execute(CONTRIBUTOR_USER_ID, NotificationFilter.ALL, 0, 20);
        assertThat(page.items()).hasSize(1);
        NotificationDTO item = page.items().get(0);
        assertThat(item.type()).isEqualTo(NotificationType.WIKI_CONTRIBUTION_REVIEWING);
        assertThat(item.targetTitleSnapshot()).isEqualTo("Trần Bình An");
        assertThat(item.actionUrl()).isEqualTo("/wiki/contributions");
        assertThat(item.unread()).isTrue();

        // Zero query lookups for contribution notification enrichment
        verifyNoInteractions(chapterListQueryPort);
        verifyNoInteractions(wikiArticleQueryPort);
    }

    @Test
    @DisplayName("Resolve workflow with NO_CHANGE_NEEDED transitions to RESOLVED and dispatches WIKI_CONTRIBUTION_RESOLVED notification with detail snapshot")
    void resolveWorkflowDispatchesResolvedNotificationWithDetail() {
        UUID contribId = seedContribution(CONTRIBUTOR_USER_ID);
        adminWorkflowUseCase.review(new ReviewWikiContributionCommand(contribId, ADMIN_USER_ID, 0L));

        String note = "Ban biên tập đã ghi nhận và đối chiếu với nguyên tác, thông tin hiện tại đã chính xác.";
        WikiContribution resolved = adminWorkflowUseCase.resolve(new ResolveWikiContributionCommand(
                contribId, ADMIN_USER_ID, 1L, WikiContributionResolutionOutcome.NO_CHANGE_NEEDED, note
        ));

        assertThat(resolved.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);

        // Total 2 notifications for contributor (REVIEWING + RESOLVED)
        assertThat(getUnreadNotificationCountUseCase.execute(CONTRIBUTOR_USER_ID).unreadCount()).isEqualTo(2L);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM notifications WHERE recipient_user_id = ? AND type = 'WIKI_CONTRIBUTION_RESOLVED'",
                CONTRIBUTOR_USER_ID.toString()
        );
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("target_title_snapshot")).isEqualTo("Trần Bình An");
        assertThat(row.get("detail_snapshot")).isEqualTo(note);
        assertThat(row.get("dedupe_key")).isEqualTo("WIKI_CONTRIBUTION:" + contribId + ":RESOLVED");

        // Feed check
        NotificationPageDTO page = listUserNotificationsUseCase.execute(CONTRIBUTOR_USER_ID, NotificationFilter.ALL, 0, 20);
        assertThat(page.items()).hasSize(2);
        NotificationDTO resolvedItem = page.items().get(0); // Latest first
        assertThat(resolvedItem.type()).isEqualTo(NotificationType.WIKI_CONTRIBUTION_RESOLVED);
        assertThat(resolvedItem.detailSnapshot()).isEqualTo(note);
        assertThat(resolvedItem.actionUrl()).isEqualTo("/wiki/contributions");
    }

    @Test
    @DisplayName("Reject workflow transitions to REJECTED and dispatches WIKI_CONTRIBUTION_REJECTED notification with detail snapshot")
    void rejectWorkflowDispatchesRejectedNotificationWithDetail() {
        UUID contribId = seedContribution(CONTRIBUTOR_USER_ID);
        adminWorkflowUseCase.review(new ReviewWikiContributionCommand(contribId, ADMIN_USER_ID, 0L));

        String rejectNote = "Nội dung đóng góp chứa phỏng đoán chưa được xác nhận trong chương mới nhất.";
        WikiContribution rejected = adminWorkflowUseCase.reject(new RejectWikiContributionCommand(
                contribId, ADMIN_USER_ID, 1L, rejectNote
        ));

        assertThat(rejected.getStatus()).isEqualTo(WikiContributionStatus.REJECTED);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM notifications WHERE recipient_user_id = ? AND type = 'WIKI_CONTRIBUTION_REJECTED'",
                CONTRIBUTOR_USER_ID.toString()
        );
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("target_title_snapshot")).isEqualTo("Trần Bình An");
        assertThat(row.get("detail_snapshot")).isEqualTo(rejectNote);
        assertThat(row.get("dedupe_key")).isEqualTo("WIKI_CONTRIBUTION:" + contribId + ":REJECTED");

        NotificationPageDTO page = listUserNotificationsUseCase.execute(CONTRIBUTOR_USER_ID, NotificationFilter.ALL, 0, 20);
        assertThat(page.items()).hasSize(2);
        NotificationDTO rejectedItem = page.items().get(0);
        assertThat(rejectedItem.type()).isEqualTo(NotificationType.WIKI_CONTRIBUTION_REJECTED);
        assertThat(rejectedItem.detailSnapshot()).isEqualTo(rejectNote);
        assertThat(rejectedItem.actionUrl()).isEqualTo("/wiki/contributions");
    }

    @Test
    @DisplayName("Self-review suppression: Admin acting on own contribution dispatches zero notifications")
    void selfReviewSuppressesNotification() {
        UUID adminContribId = seedContribution(ADMIN_USER_ID);

        // Admin reviews their own contribution
        adminWorkflowUseCase.review(new ReviewWikiContributionCommand(adminContribId, ADMIN_USER_ID, 0L));

        // Notifications table must remain completely empty
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        assertThat(count).isZero();
        assertThat(getUnreadNotificationCountUseCase.execute(ADMIN_USER_ID).unreadCount()).isZero();

        // Admin resolves own contribution
        adminWorkflowUseCase.resolve(new ResolveWikiContributionCommand(
                adminContribId, ADMIN_USER_ID, 1L, WikiContributionResolutionOutcome.NO_CHANGE_NEEDED, "Tự giải quyết đóng góp của mình."
        ));

        count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Transaction rolls back completely when notification dispatch fails (non-dedupe)")
    void transactionRollsBackWhenNotificationDispatchFailsNonDedupe() {
        UUID contribId = seedContribution(CONTRIBUTOR_USER_ID);

        doThrow(new RuntimeException("Simulated dispatch failure"))
                .when(notificationPersistenceAdapter).dispatch(any());

        assertThatThrownBy(() -> adminWorkflowUseCase.review(
                new ReviewWikiContributionCommand(contribId, ADMIN_USER_ID, 0L)
        )).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated dispatch failure");

        // Contribution status must STILL be NEW in DB
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM wiki_contributions WHERE id = ?",
                String.class,
                contribId.toString()
        );
        assertThat(status).isEqualTo("NEW");

        // Workflow events table must have NO rows
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_contribution_workflow_events WHERE contribution_id = ?",
                Integer.class,
                contribId.toString()
        );
        assertThat(eventCount).isZero();

        // Notifications table must have NO rows
        Integer notifCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        assertThat(notifCount).isZero();
    }

    @Test
    @DisplayName("Duplicate notification dispatch leaves transaction healthy and creates no duplicate rows")
    void duplicateNotificationDispatchLeavesTransactionHealthy() {
        UUID contribId = seedContribution(CONTRIBUTOR_USER_ID);

        // 1. Normal review creates 1 notification row
        adminWorkflowUseCase.review(new ReviewWikiContributionCommand(contribId, ADMIN_USER_ID, 0L));

        Integer notifCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?",
                Integer.class,
                CONTRIBUTOR_USER_ID.toString()
        );
        assertThat(notifCount).isEqualTo(1);

        // 2. Duplicate dispatch with same dedupe_key
        NotificationDispatchCommand duplicateCommand = new NotificationDispatchCommand(
                CONTRIBUTOR_USER_ID,
                NotificationType.WIKI_CONTRIBUTION_REVIEWING,
                ADMIN_USER_ID,
                null,
                "WIKI_CONTRIBUTION",
                contribId,
                "Trần Bình An",
                null,
                null,
                null,
                "WIKI_CONTRIBUTION:" + contribId + ":REVIEWING"
        );

        notificationPersistenceAdapter.dispatch(duplicateCommand);

        // 3. Exactly 1 notification row still exists
        Integer countAfterDuplicate = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?",
                Integer.class,
                CONTRIBUTOR_USER_ID.toString()
        );
        assertThat(countAfterDuplicate).isEqualTo(1);
    }
}
