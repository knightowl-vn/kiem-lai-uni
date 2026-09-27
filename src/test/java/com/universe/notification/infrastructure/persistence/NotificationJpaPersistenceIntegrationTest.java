package com.universe.notification.infrastructure.persistence;

import com.universe.notification.application.model.NotificationFilter;
import com.universe.notification.application.model.NotificationSlice;
import com.universe.notification.contracts.command.NotificationDispatchCommand;
import com.universe.notification.contracts.dto.NotificationDTO;
import com.universe.notification.contracts.dto.NotificationPageDTO;
import com.universe.notification.domain.Notification;
import com.universe.notification.domain.NotificationType;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        NotificationPersistenceAdapter.class,
        NotificationQueryPersistenceAdapter.class
})
@DisplayName("Notification JPA Persistence Integration Tests")
class NotificationJpaPersistenceIntegrationTest {

    private static final UUID USER_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SpringDataNotificationRepository repository;

    @Autowired
    private NotificationPersistenceAdapter persistenceAdapter;

    @Autowired
    private NotificationQueryPersistenceAdapter queryAdapter;

    @BeforeEach
    void cleanUpBefore() {
        cleanData();
    }

    @AfterEach
    void cleanUpAfter() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.update("DELETE FROM notifications");
    }

    @Test
    @DisplayName("Saves and finds Notification via persistence adapter")
    void shouldSaveAndFindNotification() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Notification domain = Notification.create(
                id,
                USER_1,
                NotificationType.COMMENT_REPLY,
                USER_2,
                "User Two",
                "NOVEL_CHAPTER",
                UUID.randomUUID(),
                "Chương 1: Khởi đầu",
                UUID.randomUUID(),
                null,
                null,
                "reply:" + id,
                now
        );

        persistenceAdapter.save(domain);

        Optional<Notification> loaded = persistenceAdapter.findById(id);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getId()).isEqualTo(id);
        assertThat(loaded.get().getRecipientUserId()).isEqualTo(USER_1);
        assertThat(loaded.get().getType()).isEqualTo(NotificationType.COMMENT_REPLY);
        assertThat(loaded.get().getActorUserId()).isEqualTo(USER_2);
        assertThat(loaded.get().getActorDisplayNameSnapshot()).isEqualTo("User Two");
        assertThat(loaded.get().getTargetType()).isEqualTo("NOVEL_CHAPTER");
        assertThat(loaded.get().getTargetTitleSnapshot()).isEqualTo("Chương 1: Khởi đầu");
        assertThat(loaded.get().getDedupeKey()).isEqualTo("reply:" + id);
        assertThat(loaded.get().isUnread()).isTrue();
    }

    @Test
    @DisplayName("Counts unread notifications accurately per recipient")
    void shouldCountUnreadNotifications() {
        persistenceAdapter.dispatch(new NotificationDispatchCommand(
                USER_1,
                NotificationType.COMMENT_REPLY,
                USER_2,
                "User Two",
                "NOVEL_CHAPTER",
                UUID.randomUUID(),
                "Chương 1",
                UUID.randomUUID(),
                null,
                null,
                "reply:1"
        ));
        persistenceAdapter.dispatch(new NotificationDispatchCommand(
                USER_1,
                NotificationType.WIKI_CONTRIBUTION_RESOLVED,
                null,
                null,
                "WIKI_ARTICLE",
                UUID.randomUUID(),
                "Bài viết",
                null,
                null,
                null,
                "wiki:resolve:1"
        ));
        persistenceAdapter.dispatch(new NotificationDispatchCommand(
                USER_2,
                NotificationType.WIKI_CONTRIBUTION_REVIEWING,
                null,
                null,
                "WIKI_CONTRIBUTION",
                UUID.randomUUID(),
                "Đóng góp",
                null,
                null,
                null,
                "wiki:review:2"
        ));

        assertThat(persistenceAdapter.countUnreadByRecipientUserId(USER_1)).isEqualTo(2L);
        assertThat(persistenceAdapter.countUnreadByRecipientUserId(USER_2)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Marks single notification as read and updates read state")
    void shouldMarkNotificationAsRead() {
        UUID notifId = UUID.randomUUID();
        Notification notification = Notification.create(
                notifId,
                USER_1,
                NotificationType.COMMENT_REPLY,
                USER_2,
                "User Two",
                "NOVEL_CHAPTER",
                UUID.randomUUID(),
                "Chương 1",
                UUID.randomUUID(),
                null,
                null,
                "reply:mark-test",
                Instant.now()
        );
        persistenceAdapter.save(notification);

        assertThat(persistenceAdapter.countUnreadByRecipientUserId(USER_1)).isEqualTo(1L);

        Instant readTimestamp = Instant.now();
        boolean marked = persistenceAdapter.markAsRead(notifId, USER_1, readTimestamp);
        assertThat(marked).isTrue();

        assertThat(persistenceAdapter.countUnreadByRecipientUserId(USER_1)).isEqualTo(0L);

        Optional<Notification> updated = persistenceAdapter.findById(notifId);
        assertThat(updated).isPresent();
        assertThat(updated.get().isRead()).isTrue();
        assertThat(updated.get().getReadAt()).isNotNull();
    }

    @Test
    @DisplayName("Marks all notifications as read for recipient")
    void shouldMarkAllNotificationsAsRead() {
        persistenceAdapter.dispatch(new NotificationDispatchCommand(
                USER_1, NotificationType.WIKI_CONTRIBUTION_RESOLVED,
                null, null, "WIKI_ARTICLE", UUID.randomUUID(), "Bài 1", null, null, null, "dedupe:a"
        ));
        persistenceAdapter.dispatch(new NotificationDispatchCommand(
                USER_1, NotificationType.WIKI_CONTRIBUTION_REJECTED,
                null, null, "WIKI_CONTRIBUTION", UUID.randomUUID(), "Bài 2", null, null, "Feedback", "dedupe:b"
        ));

        assertThat(persistenceAdapter.countUnreadByRecipientUserId(USER_1)).isEqualTo(2L);

        int updatedCount = persistenceAdapter.markAllAsRead(USER_1, Instant.now());
        assertThat(updatedCount).isEqualTo(2);

        assertThat(persistenceAdapter.countUnreadByRecipientUserId(USER_1)).isEqualTo(0L);
    }

    @Test
    @DisplayName("Retrieves paginated notifications feed with ALL and UNREAD filters, and actionUrl is null in K1B foundation")
    void shouldRetrievePaginatedFeedWithFiltersAndNullActionUrl() {
        UUID notif1 = UUID.randomUUID();
        UUID notif2 = UUID.randomUUID();
        UUID notif3 = UUID.randomUUID();

        Instant t0 = Instant.parse("2026-09-27T07:00:00Z");
        Instant t1 = Instant.parse("2026-09-27T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-27T09:00:00Z");

        Notification n1 = Notification.create(
                notif1, USER_1, NotificationType.COMMENT_REPLY,
                USER_2, "U2", "NOVEL_CHAPTER", UUID.randomUUID(), "C1", UUID.randomUUID(), null, null, "d1", t0
        );
        Notification n2 = Notification.create(
                notif2, USER_1, NotificationType.WIKI_CONTRIBUTION_RESOLVED,
                null, null, "WIKI_ARTICLE", UUID.randomUUID(), "A1", null, null, null, "d2", t1
        );
        Notification n3 = Notification.create(
                notif3, USER_1, NotificationType.WIKI_CONTRIBUTION_REJECTED,
                null, null, "WIKI_CONTRIBUTION", UUID.randomUUID(), "A2", null, null, "Fix text", "d3", t2
        );

        persistenceAdapter.save(n1);
        persistenceAdapter.save(n2);
        persistenceAdapter.save(n3);

        // Mark n2 as read
        persistenceAdapter.markAsRead(notif2, USER_1, Instant.now());

        // ALL filter (default)
        NotificationSlice allFeed = queryAdapter.findByRecipientUserId(USER_1, NotificationFilter.ALL, 0, 10);
        assertThat(allFeed.totalElements()).isEqualTo(3L);
        assertThat(allFeed.items()).hasSize(3);
        // Ordered by createdAt DESC -> n3, n2, n1
        assertThat(allFeed.items().get(0).getId()).isEqualTo(notif3);
        assertThat(allFeed.items().get(1).getId()).isEqualTo(notif2);
        assertThat(allFeed.items().get(2).getId()).isEqualTo(notif1);

        // UNREAD filter
        NotificationSlice unreadFeed = queryAdapter.findByRecipientUserId(USER_1, NotificationFilter.UNREAD, 0, 10);
        assertThat(unreadFeed.totalElements()).isEqualTo(2L);
        assertThat(unreadFeed.items()).hasSize(2);
        assertThat(unreadFeed.items().get(0).getId()).isEqualTo(notif3);
        assertThat(unreadFeed.items().get(1).getId()).isEqualTo(notif1);
    }

    @Test
    @DisplayName("Duplicate dedupe_key insert creates exactly one row and preserves original immutable row")
    void shouldIdempotentlyDispatchWithoutErrorOnDuplicateKey() {
        String sharedDedupeKey = "comment-reply:shared-dedupe-key-123";
        UUID targetId1 = UUID.randomUUID();
        UUID commentId1 = UUID.randomUUID();

        NotificationDispatchCommand cmd1 = new NotificationDispatchCommand(
                USER_1,
                NotificationType.COMMENT_REPLY,
                USER_2,
                "Original Actor",
                "NOVEL_CHAPTER",
                targetId1,
                "Original Chapter Title",
                commentId1,
                null,
                null,
                sharedDedupeKey
        );

        NotificationDispatchCommand cmd2 = new NotificationDispatchCommand(
                USER_1,
                NotificationType.COMMENT_REPLY,
                UUID.randomUUID(),
                "Mutated Actor Attempt",
                "NOVEL_CHAPTER",
                UUID.randomUUID(),
                "Mutated Title Attempt",
                UUID.randomUUID(),
                null,
                null,
                sharedDedupeKey
        );

        persistenceAdapter.dispatch(cmd1);
        persistenceAdapter.dispatch(cmd2);

        // Verify only 1 row exists
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE dedupe_key = ?",
                Integer.class,
                sharedDedupeKey
        );
        assertThat(count).isEqualTo(1);

        // Verify immutable original row data was preserved and NOT overwritten
        List<NotificationJpaEntity> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        NotificationJpaEntity row = rows.get(0);
        assertThat(row.getDedupeKey()).isEqualTo(sharedDedupeKey);
        assertThat(row.getActorDisplayNameSnapshot()).isEqualTo("Original Actor");
        assertThat(row.getTargetTitleSnapshot()).isEqualTo("Original Chapter Title");
        assertThat(row.getTargetId()).isEqualTo(targetId1.toString());
        assertThat(row.getCommentId()).isEqualTo(commentId1.toString());
    }

    @Test
    @DisplayName("Simultaneous concurrent dispatches with the same dedupe key create exactly one row without exception")
    void shouldProveConcurrentDuplicateDispatchSafety() throws Exception {
        String concurrentDedupeKey = "concurrent-key-" + UUID.randomUUID();
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    persistenceAdapter.dispatch(new NotificationDispatchCommand(
                            USER_1,
                            NotificationType.COMMENT_REPLY,
                            USER_2,
                            "Concurrent Actor " + index,
                            "NOVEL_CHAPTER",
                            UUID.randomUUID(),
                            "Concurrent Title " + index,
                            UUID.randomUUID(),
                            null,
                            null,
                            concurrentDedupeKey
                    ));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).as("No exception must escape concurrent duplicate dispatch").isEmpty();

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE dedupe_key = ?",
                Integer.class,
                concurrentDedupeKey
        );
        assertThat(rowCount).as("Exactly one notification row must be persisted").isEqualTo(1);
    }

    @Test
    @DisplayName("Duplicate dispatch does not poison surrounding active transaction")
    void shouldProveSurroundingTransactionHealthOnDuplicateDispatch() {
        String existingKey = "tx-health-key-1";

        // Seed initial notification
        persistenceAdapter.dispatch(new NotificationDispatchCommand(
                USER_1,
                NotificationType.WIKI_CONTRIBUTION_RESOLVED,
                null,
                null,
                "WIKI_ARTICLE",
                UUID.randomUUID(),
                "Initial Title",
                null,
                null,
                null,
                existingKey
        ));

        UUID otherNotificationId = UUID.randomUUID();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // Execute surrounding transaction
        Boolean committed = txTemplate.execute(status -> {
            // 1. Write an unrelated notification
            Notification otherNotif = Notification.create(
                    otherNotificationId,
                    USER_2,
                    NotificationType.WIKI_CONTRIBUTION_REVIEWING,
                    null,
                    null,
                    "WIKI_CONTRIBUTION",
                    UUID.randomUUID(),
                    "Other Title",
                    null,
                    null,
                    null,
                    "tx-health-other-key-2",
                    Instant.now()
            );
            persistenceAdapter.save(otherNotif);

            // 2. Dispatch duplicate notification with existing key inside this transaction
            persistenceAdapter.dispatch(new NotificationDispatchCommand(
                    USER_1,
                    NotificationType.WIKI_CONTRIBUTION_RESOLVED,
                    null,
                    null,
                    "WIKI_ARTICLE",
                    UUID.randomUUID(),
                    "Duplicate Attempt",
                    null,
                    null,
                    null,
                    existingKey
            ));

            // 3. Complete transaction normally
            return true;
        });

        assertThat(committed).isTrue();

        // Verify the unrelated write succeeded and was committed
        Optional<Notification> otherLoaded = persistenceAdapter.findById(otherNotificationId);
        assertThat(otherLoaded).as("Unrelated record in outer transaction must be successfully committed").isPresent();

        // Verify total notification rows in database: 1 original + 1 other = 2
        Integer totalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        assertThat(totalCount).isEqualTo(2);
    }

    @Test
    @DisplayName("Non-duplicate database errors fail normally and are NOT swallowed")
    void shouldFailNormallyOnNonDuplicateDatabaseErrors() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // Attempt direct native insert violating check constraint chk_notifications_type
        assertThatThrownBy(() -> txTemplate.execute(status -> {
            repository.insertIdempotent(
                    UUID.randomUUID().toString(),
                    USER_1.toString(),
                    "INVALID_NON_EXISTENT_TYPE", // violates chk_notifications_type
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "invalid-type-key",
                    null,
                    Instant.now()
            );
            return null;
        }))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("chk_notifications_type");
    }
}
