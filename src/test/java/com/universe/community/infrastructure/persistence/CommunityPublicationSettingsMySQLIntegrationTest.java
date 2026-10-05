package com.universe.community.infrastructure.persistence;

import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.CommunityPublicationMode;
import com.universe.community.domain.CommunitySettings;
import com.universe.test.TestDatabaseSupport;
import com.universe.community.application.command.CreateCommunityPostCommand;
import com.universe.community.application.command.UpdateCommunitySettingsCommand;
import com.universe.community.application.service.CommunityPostCreationGuardService;
import com.universe.community.application.usecase.CreateCommunityPostUseCase;
import com.universe.community.application.usecase.UpdateCommunitySettingsUseCase;
import com.universe.community.domain.exception.CommunitySettingsOptimisticLockException;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.SystemClockAdapter;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
        CommunitySettingsPersistenceAdapter.class,
        CommunityPostPersistenceAdapter.class,
        CommunityPostPersistenceMapper.class,
        CommunityPostRevisionPersistenceMapper.class,
        CommunityPostCreationGuardPersistenceAdapter.class,
        CommunityPostCreationGuardService.class,
        UuidGeneratorAdapter.class,
        SystemClockAdapter.class,
        CreateCommunityPostUseCase.class,
        UpdateCommunitySettingsUseCase.class
})
@DisplayName("Community Publication Settings & Timestamps MySQL Integration Test (V83/V84)")
class CommunityPublicationSettingsMySQLIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.resetTestDatabase("kiemlai_test");
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private CommunitySettingsPersistenceAdapter settingsAdapter;

    @Autowired
    private CommunityPostPersistenceAdapter postAdapter;

    @Autowired
    private CreateCommunityPostUseCase createPostUseCase;

    @Autowired
    private UpdateCommunitySettingsUseCase updateSettingsUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSettings() {
        // Ensure singleton settings is in clean baseline state with null updated_by_user_id
        jdbcTemplate.update(
                "UPDATE community_settings SET publication_mode = 'AUTO_PUBLISH', version = 0, updated_at = NOW(6), updated_by_user_id = NULL WHERE id = 'DEFAULT'"
        );
    }

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
    @DisplayName("V84: Singleton community_settings row exists with default AUTO_PUBLISH, version 0, and NULL updater")
    void shouldVerifyInitialSingletonSettings() {
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM community_settings WHERE id = 'DEFAULT'");
        assertThat(row.get("id")).isEqualTo("DEFAULT");
        assertThat(row.get("publication_mode")).isEqualTo("AUTO_PUBLISH");
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(0L);
        assertThat(row.get("updated_by_user_id")).isNull();
        assertThat(row.get("updated_at")).isNotNull();

        CommunitySettings settings = settingsAdapter.getSettings();
        assertThat(settings.getId()).isEqualTo("DEFAULT");
        assertThat(settings.getPublicationMode()).isEqualTo(CommunityPublicationMode.AUTO_PUBLISH);
        assertThat(settings.getVersion()).isEqualTo(0L);
        assertThat(settings.getUpdatedByUserId()).isNull();
        assertThat(settings.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("V84: Optimistic update succeeds when expected version matches, increments version")
    void shouldUpdateSettingsOptimistically() {
        UUID adminId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        boolean updated = settingsAdapter.updateSettings(
                CommunityPublicationMode.PRE_MODERATION,
                0L,
                adminId,
                now
        );

        assertThat(updated).isTrue();

        CommunitySettings freshSettings = settingsAdapter.getSettings();
        assertThat(freshSettings.getPublicationMode()).isEqualTo(CommunityPublicationMode.PRE_MODERATION);
        assertThat(freshSettings.getVersion()).isEqualTo(1L);
        assertThat(freshSettings.getUpdatedByUserId()).isEqualTo(adminId);
    }

    @Test
    @DisplayName("V84: Optimistic update fails when expected version is stale (returns false, 0 rows modified)")
    void shouldRejectUpdateOnStaleVersion() {
        UUID admin1 = UUID.randomUUID();
        UUID admin2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // First update advances version to 1
        boolean firstUpdate = settingsAdapter.updateSettings(
                CommunityPublicationMode.PRE_MODERATION,
                0L,
                admin1,
                now
        );
        assertThat(firstUpdate).isTrue();

        // Second update attempts with stale version 0 -> must return false
        boolean staleUpdate = settingsAdapter.updateSettings(
                CommunityPublicationMode.AUTO_PUBLISH,
                0L,
                admin2,
                now.plusSeconds(5)
        );
        assertThat(staleUpdate).isFalse();

        // State remains at version 1
        CommunitySettings current = settingsAdapter.getSettings();
        assertThat(current.getVersion()).isEqualTo(1L);
        assertThat(current.getPublicationMode()).isEqualTo(CommunityPublicationMode.PRE_MODERATION);
        assertThat(current.getUpdatedByUserId()).isEqualTo(admin1);
    }

    @Test
    @DisplayName("V83: community_posts persists and hydrates published_at and review_requested_at accurately")
    void shouldPersistAndHydratePublicationTimestamps() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-04T08:00:00.123456Z");
        Instant publishedAt = Instant.parse("2026-10-04T08:30:00.654321Z");

        CommunityPost post = CommunityPost.create(
                postId,
                authorId,
                "Testing publication timestamps on real DB",
                null,
                CommunityPostStatus.PUBLISHED,
                createdAt,
                publishedAt,
                null
        );

        postAdapter.save(post);

        // Direct DB inspection
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT published_at, review_requested_at FROM community_posts WHERE id = ?",
                postId.toString()
        );
        assertThat(row.get("published_at")).isNotNull();
        assertThat(row.get("review_requested_at")).isNull();

        // Domain rehydration inspection
        Optional<CommunityPost> loadedOpt = postAdapter.findById(postId);
        assertThat(loadedOpt).isPresent();
        CommunityPost loaded = loadedOpt.get();
        assertThat(loaded.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(loaded.getReviewRequestedAt()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
    }

    @Test
    @DisplayName("V83: PENDING_REVIEW post persists review_requested_at and nullable published_at")
    void shouldPersistPendingReviewPostTimestamps() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-04T09:00:00.111111Z");
        Instant reviewRequestedAt = Instant.parse("2026-10-04T09:00:00.111111Z");

        CommunityPost pendingPost = CommunityPost.create(
                postId,
                authorId,
                "New post pending review",
                null,
                CommunityPostStatus.PENDING_REVIEW,
                createdAt,
                null,
                reviewRequestedAt
        );

        postAdapter.save(pendingPost);

        Optional<CommunityPost> loadedOpt = postAdapter.findById(postId);
        assertThat(loadedOpt).isPresent();
        CommunityPost loaded = loadedOpt.get();
        assertThat(loaded.getStatus()).isEqualTo(CommunityPostStatus.PENDING_REVIEW);
        assertThat(loaded.getPublishedAt()).isNull();
        assertThat(loaded.getReviewRequestedAt()).isEqualTo(reviewRequestedAt);
    }

    @Test
    @DisplayName("V83: All 4 status backfill cases on real MySQL execute accurately")
    void shouldVerifyV83AllFourStatusBackfillCases() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        UUID p4 = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        String tCreated = "2026-10-01 10:00:00.000000";

        // Insert legacy rows without publication timestamps
        jdbcTemplate.update("INSERT INTO community_posts (id, author_user_id, caption, content_version, status, created_at, updated_at) VALUES (?, ?, 'P1', 0, 'PUBLISHED', ?, ?)", p1.toString(), author.toString(), tCreated, tCreated);
        jdbcTemplate.update("INSERT INTO community_posts (id, author_user_id, caption, content_version, status, created_at, updated_at) VALUES (?, ?, 'P2', 0, 'HIDDEN', ?, ?)", p2.toString(), author.toString(), tCreated, tCreated);
        jdbcTemplate.update("INSERT INTO community_posts (id, author_user_id, caption, content_version, status, created_at, updated_at) VALUES (?, ?, 'P3', 0, 'PENDING_REVIEW', ?, ?)", p3.toString(), author.toString(), tCreated, tCreated);
        jdbcTemplate.update("INSERT INTO community_posts (id, author_user_id, caption, content_version, status, created_at, updated_at) VALUES (?, ?, 'P4', 0, 'REJECTED', ?, ?)", p4.toString(), author.toString(), tCreated, tCreated);

        // Execute exact V83 backfill updates
        jdbcTemplate.update("UPDATE community_posts SET published_at = created_at, review_requested_at = NULL WHERE status IN ('PUBLISHED', 'HIDDEN')");
        jdbcTemplate.update("UPDATE community_posts SET published_at = NULL, review_requested_at = created_at WHERE status = 'PENDING_REVIEW'");
        jdbcTemplate.update("UPDATE community_posts SET published_at = NULL, review_requested_at = NULL WHERE status = 'REJECTED'");

        // Verify PUBLISHED
        Map<String, Object> r1 = jdbcTemplate.queryForMap("SELECT published_at, review_requested_at FROM community_posts WHERE id = ?", p1.toString());
        assertThat(r1.get("published_at")).isNotNull();
        assertThat(r1.get("review_requested_at")).isNull();

        // Verify HIDDEN
        Map<String, Object> r2 = jdbcTemplate.queryForMap("SELECT published_at, review_requested_at FROM community_posts WHERE id = ?", p2.toString());
        assertThat(r2.get("published_at")).isNotNull();
        assertThat(r2.get("review_requested_at")).isNull();

        // Verify PENDING_REVIEW
        Map<String, Object> r3 = jdbcTemplate.queryForMap("SELECT published_at, review_requested_at FROM community_posts WHERE id = ?", p3.toString());
        assertThat(r3.get("published_at")).isNull();
        assertThat(r3.get("review_requested_at")).isNotNull();

        // Verify REJECTED
        Map<String, Object> r4 = jdbcTemplate.queryForMap("SELECT published_at, review_requested_at FROM community_posts WHERE id = ?", p4.toString());
        assertThat(r4.get("published_at")).isNull();
        assertThat(r4.get("review_requested_at")).isNull();
    }

    @Test
    @DisplayName("V83: Verify no DB defaults on published_at/review_requested_at and required indexes exist")
    void shouldVerifyV83NoTimestampDefaultsAndRequiredIndexes() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'community_posts' AND COLUMN_NAME IN ('published_at', 'review_requested_at')"
        );
        assertThat(columns).hasSize(2);
        for (Map<String, Object> col : columns) {
            assertThat(col.get("IS_NULLABLE")).isEqualTo("YES");
            assertThat(col.get("COLUMN_DEFAULT")).isNull();
        }

        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'community_posts'",
                String.class
        );
        assertThat(indexes).contains(
                "idx_community_posts_status_published",
                "idx_community_posts_author_status_published",
                "idx_community_posts_status_review_requested_asc"
        );
    }

    @Test
    @DisplayName("V84: Exact CHECK constraints enforced and no FK to identity")
    void shouldVerifyV84SchemaConstraints() {
        // CHECK constraint on id: only 'DEFAULT' allowed
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO community_settings (id, publication_mode, version, updated_at, updated_by_user_id) " +
                        "VALUES ('OTHER', 'AUTO_PUBLISH', 0, NOW(6), NULL)"
        )).hasMessageContaining("chk_community_settings_id");

        // CHECK constraint on publication_mode: only 'AUTO_PUBLISH' or 'PRE_MODERATION' allowed
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE community_settings SET publication_mode = 'INVALID_MODE' WHERE id = 'DEFAULT'"
        )).hasMessageContaining("chk_community_settings_publication_mode");

        // Verify NO foreign key constraint on community_settings
        List<Map<String, Object>> fks = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'community_settings' AND CONSTRAINT_TYPE = 'FOREIGN KEY'"
        );
        assertThat(fks).isEmpty();
    }

    @Test
    @DisplayName("Mode Toggle Semantics: toggling settings preserves existing posts and does NOT bulk-approve")
    void shouldVerifyModeToggleSemantics() {
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Create 1 published post and 1 pending review post
        CommunityPost published = CommunityPost.create(UUID.randomUUID(), authorId, "Published Post", null, CommunityPostStatus.PUBLISHED, now, now, null);
        CommunityPost pending = CommunityPost.create(UUID.randomUUID(), authorId, "Pending Post", null, CommunityPostStatus.PENDING_REVIEW, now, null, now);
        postAdapter.save(published);
        postAdapter.save(pending);

        // Toggle AUTO_PUBLISH -> PRE_MODERATION
        updateSettingsUseCase.execute(new UpdateCommunitySettingsCommand(CommunityPublicationMode.PRE_MODERATION, 0L, UUID.randomUUID()));

        assertThat(postAdapter.findById(published.getId()).orElseThrow().getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(postAdapter.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(CommunityPostStatus.PENDING_REVIEW);

        // Toggle PRE_MODERATION -> AUTO_PUBLISH
        updateSettingsUseCase.execute(new UpdateCommunitySettingsCommand(CommunityPublicationMode.AUTO_PUBLISH, 1L, UUID.randomUUID()));

        // Crucial invariant: existing pending post is NOT auto-approved!
        CommunityPost reloadedPending = postAdapter.findById(pending.getId()).orElseThrow();
        assertThat(reloadedPending.getStatus()).isEqualTo(CommunityPostStatus.PENDING_REVIEW);
        assertThat(reloadedPending.getPublishedAt()).isNull();
        assertThat(reloadedPending.getReviewRequestedAt()).isEqualTo(now);

        // Zero moderation events generated by settings update
        Integer eventCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM community_post_moderation_events", Integer.class);
        assertThat(eventCount).isZero();
    }

    @Test
    @DisplayName("Concurrency: Two admins update version N concurrently: exactly one succeeds, one gets stale-version failure")
    void shouldVerifyTwoAdminsConcurrentUpdateDeterministicStaleFailure() throws Exception {
        UUID admin1 = UUID.randomUUID();
        UUID admin2 = UUID.randomUUID();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Boolean> task1 = () -> {
            startLatch.await();
            return settingsAdapter.updateSettings(CommunityPublicationMode.PRE_MODERATION, 0L, admin1, Instant.now());
        };

        Callable<Boolean> task2 = () -> {
            startLatch.await();
            return settingsAdapter.updateSettings(CommunityPublicationMode.PRE_MODERATION, 0L, admin2, Instant.now());
        };

        Future<Boolean> f1 = executor.submit(task1);
        Future<Boolean> f2 = executor.submit(task2);

        startLatch.countDown();

        boolean r1 = f1.get();
        boolean r2 = f2.get();
        executor.shutdown();

        // Exactly one succeeds, one fails
        assertThat(r1 ^ r2).isTrue();

        CommunitySettings current = settingsAdapter.getSettings();
        assertThat(current.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Concurrency: Racing post creation and settings update yields strictly coherent post state")
    void shouldVerifyRacingCreateAndSettingsUpdateYieldsCoherentPost() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<CommunityPost>> postFutures = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            final int idx = i;
            final UUID authorId = UUID.randomUUID();
            postFutures.add(executor.submit(() -> {
                startLatch.await();
                return createPostUseCase.execute(new CreateCommunityPostCommand(authorId, "Racing post " + idx, null));
            }));
        }

        // 2 threads attempting settings toggles concurrently with creates
        Future<?> updateFuture1 = executor.submit(() -> {
            startLatch.await();
            try {
                updateSettingsUseCase.execute(new UpdateCommunitySettingsCommand(CommunityPublicationMode.PRE_MODERATION, 0L, UUID.randomUUID()));
            } catch (CommunitySettingsOptimisticLockException ignored) {
            }
            return null;
        });

        startLatch.countDown();

        updateFuture1.get();

        for (Future<CommunityPost> future : postFutures) {
            CommunityPost post = future.get();
            // Validate post status invariant: strictly coherent
            if (post.getStatus() == CommunityPostStatus.PUBLISHED) {
                assertThat(post.getPublishedAt()).isNotNull();
                assertThat(post.getReviewRequestedAt()).isNull();
            } else if (post.getStatus() == CommunityPostStatus.PENDING_REVIEW) {
                assertThat(post.getPublishedAt()).isNull();
                assertThat(post.getReviewRequestedAt()).isNotNull();
            } else {
                throw new AssertionError("Unexpected post status: " + post.getStatus());
            }
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("Settings toggle to AUTO_PUBLISH does NOT auto-promote existing pendingCaption")
    void shouldPreservePendingCaptionAndNotAutoPromoteWhenSettingsToggledToAutoPublish() {
        // 1. Initial settings: PRE_MODERATION
        jdbcTemplate.update(
                "UPDATE community_settings SET publication_mode = 'PRE_MODERATION', version = 0, updated_at = NOW(6), updated_by_user_id = NULL WHERE id = 'DEFAULT'"
        );

        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t1 = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        // 2. Published post with pendingCaption edit under PRE_MODERATION
        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Old public caption", "New candidate caption awaiting review", null,
                CommunityPostStatus.PUBLISHED, 0, t0, t1, t0, t1
        );
        postAdapter.save(post);

        // Verify initial state
        CommunityPost initial = postAdapter.findById(postId).orElseThrow();
        assertThat(initial.getCaption()).isEqualTo("Old public caption");
        assertThat(initial.getPendingCaption()).isEqualTo("New candidate caption awaiting review");
        assertThat(initial.getPublishedAt()).isEqualTo(t0);
        assertThat(initial.getReviewRequestedAt()).isEqualTo(t1);
        assertThat(initial.getContentVersion()).isEqualTo(0);

        // 3. Toggle settings to AUTO_PUBLISH
        UUID adminUserId = UUID.randomUUID();
        updateSettingsUseCase.execute(new UpdateCommunitySettingsCommand(
                CommunityPublicationMode.AUTO_PUBLISH,
                0L,
                adminUserId
        ));

        // 4. Assert immediately after toggle: post state is UNCHANGED
        CommunityPost afterToggle = postAdapter.findById(postId).orElseThrow();
        assertThat(afterToggle.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(afterToggle.getCaption()).isEqualTo("Old public caption");
        assertThat(afterToggle.getPendingCaption()).isEqualTo("New candidate caption awaiting review");
        assertThat(afterToggle.getPublishedAt()).isEqualTo(t0);
        assertThat(afterToggle.getReviewRequestedAt()).isEqualTo(t1);
        assertThat(afterToggle.getContentVersion()).isEqualTo(0);

        // Assert NO moderation event was generated
        int eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_moderation_events WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(eventCount).isEqualTo(0);

        // Assert NO revision was generated
        int revisionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_revisions WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(revisionCount).isEqualTo(0);
    }
}
