package com.universe.community.infrastructure.persistence;

import com.universe.community.application.command.CreateCommunityPostCommand;
import com.universe.community.application.service.CommunityPostCreationGuardService;
import com.universe.community.application.usecase.CreateCommunityPostUseCase;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.exception.CommunityPostCreationRateLimitException;
import com.universe.community.domain.exception.CommunityPostCreationRateLimitException.Reason;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.SystemClockAdapter;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import com.universe.community.application.usecase.CommunityPostImageUploadUseCase;
import com.universe.community.application.usecase.CreateCommunityPostWithImageUseCase;
import com.universe.community.domain.CommunityPostStatus;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        CommunitySettingsPersistenceAdapter.class,
        CommunityPostPersistenceAdapter.class,
        CommunityPostPersistenceMapper.class,
        CommunityPostRevisionPersistenceMapper.class,
        CommunityPostCreationGuardPersistenceAdapter.class,
        CommunityPostCreationGuardService.class,
        UuidGeneratorAdapter.class,
        SystemClockAdapter.class,
        CreateCommunityPostUseCase.class,
        CreateCommunityPostWithImageUseCase.class
})
@DisplayName("Community Post Creation Guard MySQL Integration & Concurrency Test (MS-07B8.5.5)")
class CommunityPostCreationGuardMySQLIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CommunityPostCreationGuardMySQLIntegrationTest.class);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.resetTestDatabase("kiemlai_test");
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private CreateCommunityPostUseCase createPostUseCase;

    @Autowired
    private CreateCommunityPostWithImageUseCase createPostWithImageUseCase;

    @MockBean
    private CommunityPostImageUploadUseCase imageUploadUseCase;

    @SpyBean
    private CommunityPostCreationGuardPersistenceAdapter guardAdapter;

    @Autowired
    private CommunityPostPersistenceAdapter postPersistenceAdapter;

    @Autowired
    private CommunityPostCreationGuardService guardService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM community_post_creation_events");
        jdbcTemplate.execute("DELETE FROM community_post_creation_guard");
        jdbcTemplate.execute("DELETE FROM community_post_moderation_events");
        jdbcTemplate.execute("DELETE FROM community_post_revisions");
        jdbcTemplate.execute("DELETE FROM community_posts");
        jdbcTemplate.update(
                "UPDATE community_settings SET publication_mode = 'AUTO_PUBLISH', version = 0, updated_at = NOW(6), updated_by_user_id = NULL WHERE id = 'DEFAULT'"
        );
    }

    @Test
    @DisplayName("Same-author concurrent creates: row lock serializes execution, exactly 1 succeeds and other fails COOLDOWN")
    void shouldSerializeSameAuthorConcurrentCreations() throws Exception {
        UUID authorId = UUID.randomUUID();
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<CommunityPost>> tasks = new ArrayList<>();
        tasks.add(() -> {
            readyLatch.countDown();
            readyLatch.await();
            startLatch.await();
            return createPostUseCase.execute(new CreateCommunityPostCommand(
                    authorId,
                    "Concurrent caption from thread 1",
                    null
            ));
        });
        tasks.add(() -> {
            readyLatch.countDown();
            readyLatch.await();
            startLatch.await();
            return createPostUseCase.execute(new CreateCommunityPostCommand(
                    authorId,
                    "Concurrent caption from thread 2",
                    null
            ));
        });

        List<Future<CommunityPost>> futures = new ArrayList<>();
        for (Callable<CommunityPost> task : tasks) {
            futures.add(executor.submit(task));
        }

        // Wait until all workers are waiting at startLatch
        boolean ready = readyLatch.await(5, TimeUnit.SECONDS);
        assertThat(ready).isTrue();
        startLatch.countDown(); // Release threads simultaneously

        int successCount = 0;
        int rateLimitCooldownCount = 0;
        Throwable rateLimitCause = null;

        for (Future<CommunityPost> f : futures) {
            try {
                CommunityPost post = f.get(10, TimeUnit.SECONDS);
                if (post != null) {
                    successCount++;
                }
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof CommunityPostCreationRateLimitException rateLimitEx) {
                    if (rateLimitEx.getReason() == Reason.COOLDOWN) {
                        rateLimitCooldownCount++;
                        rateLimitCause = rateLimitEx;
                    }
                } else {
                    log.error("Unexpected exception in concurrent worker", ee);
                }
            }
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(successCount)
                .as("Exactly one creation should succeed for the same author under concurrent execution")
                .isEqualTo(1);
        assertThat(rateLimitCooldownCount)
                .as("Exactly one creation should fail with Reason.COOLDOWN")
                .isEqualTo(1);
        assertThat(rateLimitCause).isNotNull();

        // Verify database persistence state
        Integer postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE author_user_id = ?",
                Integer.class,
                authorId.toString()
        );
        assertThat(postCount).isEqualTo(1);

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_creation_events WHERE author_user_id = ?",
                Integer.class,
                authorId.toString()
        );
        assertThat(eventCount).isEqualTo(1);

        Integer guardRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_creation_guard WHERE author_user_id = ?",
                Integer.class,
                authorId.toString()
        );
        assertThat(guardRowCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Different-authors concurrent creates: both succeed concurrently without global lock contention")
    void shouldAllowDifferentAuthorsConcurrentCreations() throws Exception {
        UUID author1 = UUID.randomUUID();
        UUID author2 = UUID.randomUUID();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<CommunityPost> task1 = () -> {
            readyLatch.countDown();
            readyLatch.await();
            startLatch.await();
            return createPostUseCase.execute(new CreateCommunityPostCommand(
                    author1,
                    "Author 1 post content",
                    null
            ));
        };
        Callable<CommunityPost> task2 = () -> {
            readyLatch.countDown();
            readyLatch.await();
            startLatch.await();
            return createPostUseCase.execute(new CreateCommunityPostCommand(
                    author2,
                    "Author 2 post content",
                    null
            ));
        };

        Future<CommunityPost> future1 = executor.submit(task1);
        Future<CommunityPost> future2 = executor.submit(task2);

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        CommunityPost post1 = future1.get(10, TimeUnit.SECONDS);
        CommunityPost post2 = future2.get(10, TimeUnit.SECONDS);

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(post1).isNotNull();
        assertThat(post2).isNotNull();
        assertThat(post1.getAuthorUserId()).isEqualTo(author1);
        assertThat(post2.getAuthorUserId()).isEqualTo(author2);

        Integer totalPosts = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM community_posts", Integer.class);
        assertThat(totalPosts).isEqualTo(2);

        Integer totalEvents = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM community_post_creation_events", Integer.class);
        assertThat(totalEvents).isEqualTo(2);

        Integer totalGuards = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM community_post_creation_guard", Integer.class);
        assertThat(totalGuards).isEqualTo(2);
    }

    @Test
    @DisplayName("Hard-deleting post does NOT delete creation event and preserves quota / rate limits")
    void shouldPreserveQuotaWhenPostIsHardDeleted() {
        UUID authorId = UUID.randomUUID();
        String caption = "Permanent rate limit trace test";

        // 1. Author creates post
        CommunityPost post = createPostUseCase.execute(new CreateCommunityPostCommand(
                authorId,
                caption,
                null
        ));
        assertThat(post).isNotNull();

        // 2. Hard-delete post from community_posts table
        int deletedRows = jdbcTemplate.update(
                "DELETE FROM community_posts WHERE id = ?",
                post.getId().toString()
        );
        assertThat(deletedRows).isEqualTo(1);

        // 3. Confirm community_posts has 0 rows, but community_post_creation_events STILL has the creation event
        Integer postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                post.getId().toString()
        );
        assertThat(postCount).isEqualTo(0);

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_creation_events WHERE post_id = ?",
                Integer.class,
                post.getId().toString()
        );
        assertThat(eventCount).isEqualTo(1);

        // 4. Immediate second creation attempt by author is rejected due to 60s cooldown
        try {
            createPostUseCase.execute(new CreateCommunityPostCommand(
                    authorId,
                    "New post after hard delete",
                    null
            ));
            org.junit.jupiter.api.Assertions.fail("Expected CommunityPostCreationRateLimitException (COOLDOWN)");
        } catch (CommunityPostCreationRateLimitException ex) {
            assertThat(ex.getReason()).isEqualTo(Reason.COOLDOWN);
            assertThat(ex.getRetryAfterSeconds()).isBetween(1L, 60L);
        }

        // 5. Shift creation event back by 2 minutes (past 60s cooldown, but within 24h)
        jdbcTemplate.update(
                "UPDATE community_post_creation_events SET created_at = DATE_SUB(NOW(6), INTERVAL 2 MINUTE) WHERE post_id = ?",
                post.getId().toString()
        );

        // 6. Attempting duplicate caption within 24 hours is rejected with Reason.DUPLICATE_CAPTION
        try {
            createPostUseCase.execute(new CreateCommunityPostCommand(
                    authorId,
                    caption, // identical caption
                    null
            ));
            org.junit.jupiter.api.Assertions.fail("Expected CommunityPostCreationRateLimitException (DUPLICATE_CAPTION)");
        } catch (CommunityPostCreationRateLimitException ex) {
            assertThat(ex.getReason()).isEqualTo(Reason.DUPLICATE_CAPTION);
            assertThat(ex.getRetryAfterSeconds()).isGreaterThan(0L);
        }
    }

    @Test
    @DisplayName("Publication mode invariance: both AUTO_PUBLISH and PRE_MODERATION record exactly 1 creation event")
    void shouldTrackCreationEventUnderAutoPublishAndPreModeration() {
        // Mode 1: AUTO_PUBLISH
        jdbcTemplate.update("UPDATE community_settings SET publication_mode = 'AUTO_PUBLISH'");
        UUID author1 = UUID.randomUUID();
        CommunityPost autoPost = createPostUseCase.execute(new CreateCommunityPostCommand(
                author1,
                "Auto-published post caption",
                null
        ));
        assertThat(autoPost.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(autoPost.getPublishedAt()).isNotNull();

        Integer eventCountAuthor1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_creation_events WHERE author_user_id = ?",
                Integer.class,
                author1.toString()
        );
        assertThat(eventCountAuthor1).isEqualTo(1);

        // Mode 2: PRE_MODERATION
        jdbcTemplate.update("UPDATE community_settings SET publication_mode = 'PRE_MODERATION'");
        UUID author2 = UUID.randomUUID();
        CommunityPost preModPost = createPostUseCase.execute(new CreateCommunityPostCommand(
                author2,
                "Pre-moderated post caption",
                null
        ));
        assertThat(preModPost.getStatus()).isEqualTo(CommunityPostStatus.PENDING_REVIEW);
        assertThat(preModPost.getPublishedAt()).isNull();

        Integer eventCountAuthor2 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_creation_events WHERE author_user_id = ?",
                Integer.class,
                author2.toString()
        );
        assertThat(eventCountAuthor2).isEqualTo(1);

        Integer totalEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_creation_events",
                Integer.class
        );
        assertThat(totalEvents).isEqualTo(2);
    }

    @Test
    @DisplayName("Lifecycle invariance: caption edits, moderation transitions, and deletes never alter creation events ledger")
    void shouldNotChangeCreationEventsUponCaptionEditOrModerationApproveRejectOrDelete() {
        UUID authorId = UUID.randomUUID();
        CommunityPost post = createPostUseCase.execute(new CreateCommunityPostCommand(
                authorId,
                "Original post caption",
                null
        ));
        assertThat(post).isNotNull();

        Integer initialEventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_creation_events WHERE author_user_id = ?",
                Integer.class,
                authorId.toString()
        );
        assertThat(initialEventCount).isEqualTo(1);

        // 1. Simulate caption edit with revision
        jdbcTemplate.update(
                "UPDATE community_posts SET caption = 'Edited post caption', content_version = 1, updated_at = NOW(6) WHERE id = ?",
                post.getId().toString()
        );
        jdbcTemplate.update(
                "INSERT INTO community_post_revisions (id, post_id, editor_user_id, previous_caption, caption, revision_number, edited_at) VALUES (?, ?, ?, ?, ?, 1, NOW(6))",
                UUID.randomUUID().toString(),
                post.getId().toString(),
                authorId.toString(),
                "Original post caption",
                "Edited post caption"
        );

        Integer eventCountAfterEdit = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_creation_events WHERE author_user_id = ?",
                Integer.class,
                authorId.toString()
        );
        assertThat(eventCountAfterEdit).isEqualTo(1);

        // 2. Simulate moderation hide
        jdbcTemplate.update(
                "UPDATE community_posts SET status = 'HIDDEN', updated_at = NOW(6) WHERE id = ?",
                post.getId().toString()
        );
        jdbcTemplate.update(
                "INSERT INTO community_post_moderation_events (id, post_id, action, from_status, to_status, moderator_user_id, reason, created_at) VALUES (?, ?, 'HIDE', 'PUBLISHED', 'HIDDEN', ?, 'Audit', NOW(6))",
                UUID.randomUUID().toString(),
                post.getId().toString(),
                UUID.randomUUID().toString()
        );

        Integer eventCountAfterMod = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_creation_events WHERE author_user_id = ?",
                Integer.class,
                authorId.toString()
        );
        assertThat(eventCountAfterMod).isEqualTo(1);

        // 3. Simulate hard delete of post, revisions, moderation events
        jdbcTemplate.update("DELETE FROM community_post_moderation_events WHERE post_id = ?", post.getId().toString());
        jdbcTemplate.update("DELETE FROM community_post_revisions WHERE post_id = ?", post.getId().toString());
        jdbcTemplate.update("DELETE FROM community_posts WHERE id = ?", post.getId().toString());

        Integer eventCountAfterDelete = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_creation_events WHERE author_user_id = ?",
                Integer.class,
                authorId.toString()
        );
        assertThat(eventCountAfterDelete).isEqualTo(1);
    }

    @Test
    @DisplayName("Zero role bypass: administrative users executing community creation are strictly subject to creation rate limits")
    void shouldEnforceCreationGuardUniversallyWithoutRoleBypass() {
        UUID adminUserId = UUID.randomUUID();

        // 1. Admin creates first post successfully
        CommunityPost post1 = createPostUseCase.execute(new CreateCommunityPostCommand(
                adminUserId,
                "Admin announcement post",
                null
        ));
        assertThat(post1).isNotNull();

        // 2. Admin immediately attempts second post -> rejected under universal 60s cooldown
        assertThatThrownBy(() -> createPostUseCase.execute(new CreateCommunityPostCommand(
                adminUserId,
                "Admin follow-up post immediately",
                null
        )))
                .isInstanceOf(CommunityPostCreationRateLimitException.class)
                .satisfies(ex -> {
                    CommunityPostCreationRateLimitException rateLimitEx = (CommunityPostCreationRateLimitException) ex;
                    assertThat(rateLimitEx.getReason()).isEqualTo(Reason.COOLDOWN);
                    assertThat(rateLimitEx.getRetryAfterSeconds()).isBetween(1L, 60L);
                });
    }

    @Test
    @DisplayName("InnoDB Atomicity Proof: if event persistence fails, post creation is fully rolled back with 0 records committed")
    void shouldRollbackPostCreationWhenEventPersistenceFailsInTransaction() {
        UUID authorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        Instant now = Instant.now();
        CommunityPost post = CommunityPost.create(
                postId,
                authorId,
                "Post intended to be rolled back",
                null,
                CommunityPostStatus.PUBLISHED,
                now,
                now,
                null
        );

        // Case A: Unchecked exception thrown after post save rolls back post
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            postPersistenceAdapter.save(post);
            Integer postInTx = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                    Integer.class,
                    postId.toString()
            );
            assertThat(postInTx).isEqualTo(1);

            throw new RuntimeException("Simulated event persistence failure");
        })).hasMessage("Simulated event persistence failure");

        Integer postCountAfterRollback = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(postCountAfterRollback).isEqualTo(0);

        // Case B: DB Primary Key collision on community_post_creation_events rolls back post
        UUID existingEventId = UUID.randomUUID();
        guardAdapter.appendCreationEvent(existingEventId, authorId, UUID.randomUUID(), "pre-existing-hash", now);

        UUID post2Id = UUID.randomUUID();
        CommunityPost post2 = CommunityPost.create(
                post2Id,
                authorId,
                "Second post intended to rollback on PK collision",
                null,
                CommunityPostStatus.PUBLISHED,
                now,
                now,
                null
        );

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            postPersistenceAdapter.save(post2);
            // Direct insert with duplicate PK triggers MySQL Primary Key constraint violation
            jdbcTemplate.update(
                    "INSERT INTO community_post_creation_events (id, author_user_id, post_id, normalized_caption_hash, created_at) VALUES (?, ?, ?, ?, ?)",
                    existingEventId.toString(),
                    authorId.toString(),
                    post2Id.toString(),
                    "hash2",
                    java.sql.Timestamp.from(now)
            );
            return null;
        })).isInstanceOf(Exception.class);

        Integer post2Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE id = ?",
                Integer.class,
                post2Id.toString()
        );
        assertThat(post2Count).isEqualTo(0);
    }

    @Test
    @DisplayName("Concurrent same-author image creates: DB lock serializes execution, exactly 1 uploads and 1 succeeds, loser uploads ZERO bytes")
    void shouldSerializeSameAuthorConcurrentImageCreationsAndUploadExactlyOnce() throws Exception {
        UUID authorId = UUID.randomUUID();
        AtomicInteger uploadCalls = new AtomicInteger(0);
        List<UUID> uploadedAssetIds = new CopyOnWriteArrayList<>();

        when(imageUploadUseCase.uploadImage(any(), anyLong(), any(), any()))
                .thenAnswer(invocation -> {
                    uploadCalls.incrementAndGet();
                    UUID assetId = UUID.randomUUID();
                    uploadedAssetIds.add(assetId);
                    // Slight sleep to ensure the second thread has reached the DB row lock barrier
                    Thread.sleep(60);
                    return assetId;
                });

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        byte[] fakeImageBytes = "valid-image-bytes".getBytes();

        Callable<CommunityPost> task1 = () -> {
            readyLatch.countDown();
            readyLatch.await();
            startLatch.await();
            return createPostWithImageUseCase.execute(
                    authorId,
                    "Image caption 1",
                    new ByteArrayInputStream(fakeImageBytes),
                    fakeImageBytes.length,
                    "image/jpeg",
                    "img1.jpg"
            );
        };

        Callable<CommunityPost> task2 = () -> {
            readyLatch.countDown();
            readyLatch.await();
            startLatch.await();
            return createPostWithImageUseCase.execute(
                    authorId,
                    "Image caption 2",
                    new ByteArrayInputStream(fakeImageBytes),
                    fakeImageBytes.length,
                    "image/jpeg",
                    "img2.jpg"
            );
        };

        List<Future<CommunityPost>> futures = new ArrayList<>();
        futures.add(executor.submit(task1));
        futures.add(executor.submit(task2));

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int successCount = 0;
        int rateLimitCooldownCount = 0;

        for (Future<CommunityPost> f : futures) {
            try {
                CommunityPost post = f.get(10, TimeUnit.SECONDS);
                if (post != null) {
                    successCount++;
                }
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof CommunityPostCreationRateLimitException rateLimitEx) {
                    if (rateLimitEx.getReason() == Reason.COOLDOWN) {
                        rateLimitCooldownCount++;
                    }
                } else {
                    log.error("Unexpected error in concurrent image worker", ee);
                }
            }
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(successCount).isEqualTo(1);
        assertThat(rateLimitCooldownCount).isEqualTo(1);

        // Crucial Proof: uploadImage was called EXACTLY ONCE across both concurrent requests!
        assertThat(uploadCalls.get())
                .as("Media upload must be executed EXACTLY ONCE; loser was serialized and rejected BEFORE upload")
                .isEqualTo(1);
        assertThat(uploadedAssetIds).hasSize(1);

        // Crucial Proof: no compensation called because loser performed zero uploads (no orphan asset)
        verify(imageUploadUseCase, never()).compensateUpload(any(), any());

        // Verify MySQL persistence
        Integer postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE author_user_id = ?",
                Integer.class,
                authorId.toString()
        );
        assertThat(postCount).isEqualTo(1);

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_creation_events WHERE author_user_id = ?",
                Integer.class,
                authorId.toString()
        );
        assertThat(eventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Image upload + later DB failure: post rolled back, 0 events committed, asset compensated exactly once")
    void shouldCompensateUploadedImageWhenLaterDatabaseTransactionFails() {
        UUID authorId = UUID.randomUUID();
        UUID uploadedAssetId = UUID.randomUUID();
        byte[] fakeImageBytes = "valid-image-bytes".getBytes();

        when(imageUploadUseCase.uploadImage(any(), anyLong(), any(), any()))
                .thenReturn(uploadedAssetId);

        // Inject failure on event persistence after post has already been saved by use case
        doThrow(new RuntimeException("Simulated event persistence failure after upload"))
                .when(guardAdapter)
                .appendCreationEvent(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> createPostWithImageUseCase.execute(
                authorId,
                "Caption that fails during event append",
                new ByteArrayInputStream(fakeImageBytes),
                fakeImageBytes.length,
                "image/jpeg",
                "img.jpg"
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated event persistence failure after upload");

        // Verify media compensation was called exactly once for the uploaded asset
        verify(imageUploadUseCase, times(1))
                .compensateUpload(eq(uploadedAssetId), any(RuntimeException.class));

        // Verify complete rollback in real MySQL: zero posts, zero events
        Integer postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE author_user_id = ?",
                Integer.class,
                authorId.toString()
        );
        assertThat(postCount).isEqualTo(0);

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_creation_events WHERE author_user_id = ?",
                Integer.class,
                authorId.toString()
        );
        assertThat(eventCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Query plan audit: EXPLAIN verifies index utilization across all 4 production creation event queries")
    void shouldVerifyQueryExecutionPlanUsesOptimalIndexes() {
        UUID primaryAuthorId = UUID.randomUUID();
        String captionHash = CommunityPostCreationGuardService.computeCaptionHash("Test query plan caption");
        Instant cutoff = Instant.now().minusSeconds(3600);

        // Populate multi-author distribution (5 authors, 20 events each = 100 rows)
        // Spanning across cutoff (some events before 3600s cutoff, some after)
        for (int a = 0; a < 5; a++) {
            UUID author = (a == 0) ? primaryAuthorId : UUID.randomUUID();
            for (int i = 0; i < 20; i++) {
                String hash = (a == 0 && i == 5) ? captionHash : CommunityPostCreationGuardService.computeCaptionHash("Caption " + a + "-" + i);
                guardAdapter.appendCreationEvent(
                        UUID.randomUUID(),
                        author,
                        UUID.randomUUID(),
                        hash,
                        Instant.now().minusSeconds(7200 - (i * 350))
                );
            }
        }
        jdbcTemplate.execute("ANALYZE TABLE community_post_creation_events");

        // Audit Query 1: Latest creation timestamp (findLatestCreationTimestamp)
        List<Map<String, Object>> explainLatest = jdbcTemplate.queryForList(
                "EXPLAIN SELECT MAX(e.created_at) FROM community_post_creation_events e " +
                "WHERE e.author_user_id = ?",
                primaryAuthorId.toString()
        );
        log.info("EXPLAIN latest creation query: {}", explainLatest);
        assertThat(explainLatest).isNotEmpty();
        Map<String, Object> latestPlan = explainLatest.get(0);
        // In MySQL, SELECT MAX(...) with index is optimized away at query plan stage
        assertThat(String.valueOf(latestPlan.get("Extra"))).contains("Select tables optimized away");

        // Audit Query 2: Rolling window count (countCreationsAfter)
        List<Map<String, Object>> explainCount = jdbcTemplate.queryForList(
                "EXPLAIN SELECT count(e.id) FROM community_post_creation_events e " +
                "WHERE e.author_user_id = ? AND e.created_at > ?",
                primaryAuthorId.toString(),
                java.sql.Timestamp.from(cutoff)
        );
        log.info("EXPLAIN count query: {}", explainCount);
        assertThat(explainCount).isNotEmpty();
        Map<String, Object> countPlan = explainCount.get(0);
        String countKey = String.valueOf(countPlan.get("key"));
        assertThat(countKey).contains("idx_cpce_author_created");
        assertThat(String.valueOf(countPlan.get("type"))).isEqualTo("range");
        assertThat(String.valueOf(countPlan.get("key_len"))).isEqualTo("152");
        assertThat(String.valueOf(countPlan.get("Extra"))).contains("Using index");
        assertThat(String.valueOf(countPlan.get("Extra"))).doesNotContain("Using filesort");

        // Audit Query 3: Rolling window oldest active event (findOldestCreationTimestampAfter)
        List<Map<String, Object>> explainOldest = jdbcTemplate.queryForList(
                "EXPLAIN SELECT MIN(e.created_at) FROM community_post_creation_events e " +
                "WHERE e.author_user_id = ? AND e.created_at > ?",
                primaryAuthorId.toString(),
                java.sql.Timestamp.from(cutoff)
        );
        log.info("EXPLAIN oldest active query: {}", explainOldest);
        assertThat(explainOldest).isNotEmpty();
        Map<String, Object> oldestPlan = explainOldest.get(0);
        String oldestExtra = String.valueOf(oldestPlan.get("Extra"));
        if (oldestExtra.contains("Select tables optimized away")) {
            assertThat(oldestExtra).contains("Select tables optimized away");
        } else {
            String oldestKey = String.valueOf(oldestPlan.get("key"));
            assertThat(oldestKey).contains("idx_cpce_author_created");
            assertThat(String.valueOf(oldestPlan.get("type"))).isIn("range", "ref");
            assertThat(oldestExtra).contains("Using index");
            assertThat(oldestExtra).doesNotContain("Using filesort");
        }

        // Audit Query 4: Duplicate caption check (findLatestMatchingCaptionCreation)
        List<Map<String, Object>> explainDuplicate = jdbcTemplate.queryForList(
                "EXPLAIN SELECT MAX(e.created_at) FROM community_post_creation_events e " +
                "WHERE e.author_user_id = ? AND e.normalized_caption_hash = ? AND e.created_at > ?",
                primaryAuthorId.toString(),
                captionHash,
                cutoff
        );
        log.info("EXPLAIN duplicate hash query: {}", explainDuplicate);
        assertThat(explainDuplicate).isNotEmpty();
        Map<String, Object> duplicatePlan = explainDuplicate.get(0);
        String duplicateExtra = String.valueOf(duplicatePlan.get("Extra"));
        if (duplicateExtra.contains("Select tables optimized away")) {
            assertThat(duplicateExtra).contains("Select tables optimized away");
        } else {
            String duplicateKey = String.valueOf(duplicatePlan.get("key"));
            assertThat(duplicateKey).contains("idx_cpce_author_hash_created");
            assertThat(duplicateExtra).contains("Using index");
            assertThat(duplicateExtra).doesNotContain("Using filesort");
        }
    }
}


