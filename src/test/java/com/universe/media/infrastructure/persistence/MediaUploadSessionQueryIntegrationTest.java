package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-MySQL integration tests verifying the three S2D-1 JPQL queries on real database schemas:
 * 1. findEligibleForCleanup
 * 2. findExpired
 * 3. findWithExpiredLeases
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        MediaUploadSessionPersistenceAdapter.class,
        StorageKeyAllocationPersistenceAdapter.class,
        MediaUploadSessionQueryIntegrationTest.TestConfig.class
})
@DisplayName("MediaUploadSession MySQL Query & Boundary Integration Test")
class MediaUploadSessionQueryIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }
    }

    @Autowired
    private MediaUploadSessionPersistenceAdapter sessionAdapter;

    @Autowired
    private SpringDataMediaUploadSessionJpaRepository sessionRepository;

    @Autowired
    private StorageKeyAllocationPersistenceAdapter allocationAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";

    private final List<UUID> createdSessionIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdAllocationIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdAssetIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void tearDown() {
        for (UUID id : createdSessionIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_upload_sessions WHERE id = ?", id.toString());
            } catch (Exception ignored) {
            }
        }
        for (UUID assetId : createdAssetIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", assetId.toString());
            } catch (Exception ignored) {
            }
        }
        for (UUID allocId : createdAllocationIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_storage_key_allocations WHERE id = ?", allocId.toString());
            } catch (Exception ignored) {
            }
        }
    }

    private StorageKeyAllocation createAndSaveAllocation(StorageKey storageKey) {
        UUID allocId = UUID.randomUUID();
        StorageKeyAllocation alloc = StorageKeyAllocation.reserve(
                allocId,
                B2_PROVIDER,
                B2_BUCKET,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                UUID.randomUUID(),
                Instant.now()
        );
        allocationAdapter.save(alloc);
        createdAllocationIds.add(allocId);
        return alloc;
    }

    private MediaUploadSessionJpaEntity createSessionEntity(
            UUID id,
            MediaUploadSessionStatus status,
            UploadSessionCleanupStatus cleanupStatus,
            int cleanupRetryCount,
            Instant cleanupLastAttemptAt,
            Instant expiresAt,
            String leaseOwner,
            Instant leaseExpiresAt,
            Instant createdAt
    ) {
        return createSessionEntity(
                id,
                status,
                cleanupStatus,
                cleanupRetryCount,
                cleanupLastAttemptAt,
                expiresAt,
                leaseOwner,
                leaseExpiresAt,
                createdAt,
                null
        );
    }

    private MediaUploadSessionJpaEntity createSessionEntity(
            UUID id,
            MediaUploadSessionStatus status,
            UploadSessionCleanupStatus cleanupStatus,
            int cleanupRetryCount,
            Instant cleanupLastAttemptAt,
            Instant expiresAt,
            String leaseOwner,
            Instant leaseExpiresAt,
            Instant createdAt,
            String declaredContentHash
    ) {
        StorageKey storageKey = StorageKey.of("media/video/qtest_" + id + ".mp4");
        StorageKeyAllocation alloc = createAndSaveAllocation(storageKey);

        MediaUploadSessionJpaEntity entity = new MediaUploadSessionJpaEntity();
        entity.setId(id.toString());
        entity.setInitiatorPrincipalId("user-qtest");
        entity.setConsumerContext("screen");
        entity.setTargetEntityType("ScreenItem");
        entity.setTargetEntityId(UUID.randomUUID().toString());
        entity.setIdempotencyKey("idem-" + id);
        entity.setMediaType("VIDEO");
        entity.setDeclaredMimeType("video/mp4");
        entity.setDeclaredSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES * 2);
        entity.setStorageProviderId("b2");
        entity.setStorageBucketName(B2_BUCKET);
        entity.setStorageKey(storageKey.value());
        entity.setStorageAllocationId(alloc.getId().toString());
        entity.setProviderUploadId("upload-" + id);
        entity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        entity.setExpectedPartCount(2);
        entity.setStatus(status);
        entity.setExpiresAt(expiresAt != null ? expiresAt : Instant.now().plus(24, ChronoUnit.HOURS));
        entity.setLeaseOwner(leaseOwner);
        entity.setLeaseExpiresAt(leaseExpiresAt);
        entity.setCleanupStatus(cleanupStatus);
        entity.setCleanupRetryCount(cleanupRetryCount);
        entity.setCleanupLastAttemptAt(cleanupLastAttemptAt);
        entity.setCreatedAt(createdAt != null ? createdAt : Instant.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        entity.setOriginalFilename("test.mp4");
        entity.setClientTag("screen");
        entity.setDeclaredContentHash(declaredContentHash);

        if (status == MediaUploadSessionStatus.COMPLETED) {
            UUID assetId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO media_assets (id, media_type, visibility, client_tag, status, current_version_number, created_at, updated_at, persistence_version) " +
                            "VALUES (?, 'VIDEO', 'PUBLIC', 'screen', 'ACTIVE', 1, NOW(6), NOW(6), 0)",
                    assetId.toString()
            );
            createdAssetIds.add(assetId);
            entity.setRegisteredAssetId(assetId.toString());
            entity.setDeclaredContentHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
            entity.setCompletedAt(Instant.now());
        }

        sessionRepository.saveAndFlush(entity);
        createdSessionIds.add(id);
        return entity;
    }

    @Test
    @DisplayName("Cleanup query filters correctly on cleanup status and safe lifecycle status")
    void shouldFilterCorrectCleanupStatusAndLifecycle() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. ABORTED with PENDING cleanup -> eligible
        createSessionEntity(id1, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING, 0, null, null, null, null, now.minus(2, ChronoUnit.HOURS));

        // 2. EXPIRED with FAILED cleanup -> eligible
        createSessionEntity(id2, MediaUploadSessionStatus.EXPIRED, UploadSessionCleanupStatus.FAILED, 1, now.minus(30, ChronoUnit.MINUTES), null, null, null, now.minus(1, ChronoUnit.HOURS));

        List<MediaUploadSession> results = sessionAdapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.ABORTED, MediaUploadSessionStatus.EXPIRED),
                Set.of(UploadSessionCleanupStatus.PENDING, UploadSessionCleanupStatus.FAILED),
                5,
                now,
                10
        );

        List<UUID> resultIds = results.stream().map(MediaUploadSession::getId).toList();
        assertThat(resultIds).contains(id1, id2);
    }

    @Test
    @DisplayName("Exclusion of COMPLETING, COMPLETED, and CLEANED_UP from cleanup candidates via fail-closed JPQL")
    void shouldExcludeCompletingCompletedAndCleanedUpFromCleanup() {
        UUID completingId = UUID.randomUUID();
        UUID completedId = UUID.randomUUID();
        UUID cleanedUpId = UUID.randomUUID();
        Instant now = Instant.now();

        // COMPLETING with PENDING cleanup
        createSessionEntity(completingId, MediaUploadSessionStatus.COMPLETING, UploadSessionCleanupStatus.PENDING, 0, null, null, "claim-token", now.plus(10, ChronoUnit.MINUTES), now);

        // COMPLETED with PENDING cleanup
        createSessionEntity(completedId, MediaUploadSessionStatus.COMPLETED, UploadSessionCleanupStatus.PENDING, 0, null, null, null, null, now);

        // CLEANED_UP with PENDING cleanup
        createSessionEntity(cleanedUpId, MediaUploadSessionStatus.CLEANED_UP, UploadSessionCleanupStatus.PENDING, 0, null, null, null, null, now);

        // Querying repository directly with all statuses verifies JPQL exclusion:
        List<MediaUploadSessionJpaEntity> repoResults = sessionRepository.findEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.COMPLETING, MediaUploadSessionStatus.COMPLETED, MediaUploadSessionStatus.CLEANED_UP),
                Set.of(UploadSessionCleanupStatus.PENDING),
                5,
                now,
                PageRequest.of(0, 10)
        );

        assertThat(repoResults).isEmpty();
    }

    @Test
    @DisplayName("Ambiguous completion (FAILED status) must NOT be returned by default cleanup discovery and caller cannot force it")
    void shouldPreserveAmbiguousCompletionFromAutomaticDeletion() {
        UUID failedId = UUID.randomUUID();
        Instant now = Instant.now();

        // FAILED status (e.g. timeout during CompleteMultipartUpload) with PENDING cleanup
        createSessionEntity(failedId, MediaUploadSessionStatus.FAILED, UploadSessionCleanupStatus.PENDING, 0, null, null, null, null, now);

        // Default cleanup discovery uses APPROVED_CLEANUP_CANDIDATE_STATUSES (ABORTED, EXPIRED) only
        List<MediaUploadSession> results = sessionAdapter.findSessionsEligibleForCleanup(3, now, 10);

        List<UUID> resultIds = results.stream().map(MediaUploadSession::getId).toList();
        assertThat(resultIds).doesNotContain(failedId);

        // Explicit caller attempt to query FAILED status is strictly rejected fail-closed
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionAdapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.FAILED),
                Set.of(UploadSessionCleanupStatus.PENDING),
                3,
                now,
                10
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unapproved lifecycle statuses");
    }

    @Test
    @DisplayName("Exclusion of sessions with persisted completion intent even if status is ABORTED or EXPIRED")
    void shouldExcludeSessionsWithPersistedCompletionIntentEvenIfAbortedOrExpired() {
        UUID expiredWithIntent = UUID.randomUUID();
        UUID abortedWithIntent = UUID.randomUUID();
        UUID cleanAborted = UUID.randomUUID();
        UUID cleanExpired = UUID.randomUUID();
        Instant now = Instant.now();
        String contentHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        // 1. COMPLETING -> EXPIRED (has declaredContentHash)
        createSessionEntity(expiredWithIntent, MediaUploadSessionStatus.EXPIRED, UploadSessionCleanupStatus.PENDING, 0, null, now.minus(1, ChronoUnit.HOURS), null, null, now.minus(24, ChronoUnit.HOURS), contentHash);

        // 2. COMPLETING -> FAILED -> ABORTED (has declaredContentHash)
        createSessionEntity(abortedWithIntent, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING, 0, null, now.plus(1, ChronoUnit.HOURS), null, null, now.minus(2, ChronoUnit.HOURS), contentHash);

        // 3. INITIATED -> ABORTED (clean, no completion intent)
        createSessionEntity(cleanAborted, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING, 0, null, now.plus(1, ChronoUnit.HOURS), null, null, now.minus(2, ChronoUnit.HOURS), null);

        // 4. INITIATED -> EXPIRED (clean, no completion intent)
        createSessionEntity(cleanExpired, MediaUploadSessionStatus.EXPIRED, UploadSessionCleanupStatus.PENDING, 0, null, now.minus(1, ChronoUnit.HOURS), null, null, now.minus(25, ChronoUnit.HOURS), null);

        List<MediaUploadSession> results = sessionAdapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.ABORTED, MediaUploadSessionStatus.EXPIRED),
                Set.of(UploadSessionCleanupStatus.PENDING),
                5,
                now,
                10
        );

        List<UUID> resultIds = results.stream().map(MediaUploadSession::getId).toList();
        // Clean sessions without completion intent are eligible
        assertThat(resultIds).contains(cleanAborted, cleanExpired);
        // Sessions with persisted completion intent are strictly EXCLUDED from cleanup discovery
        assertThat(resultIds).doesNotContain(expiredWithIntent, abortedWithIntent);
    }

    @Test
    @DisplayName("Explicit caller attempts to include unsafe or unapproved statuses are rejected fail-closed")
    void shouldRejectExplicitCallerAttemptsToIncludeUnapprovedStatuses() {
        Instant now = Instant.now();
        Set<UploadSessionCleanupStatus> cleanupStatuses = Set.of(UploadSessionCleanupStatus.PENDING);

        // COMPLETING
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionAdapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.COMPLETING), cleanupStatuses, 3, now, 10
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unapproved lifecycle statuses");

        // COMPLETED
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionAdapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.COMPLETED), cleanupStatuses, 3, now, 10
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unapproved lifecycle statuses");

        // CLEANED_UP
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionAdapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.CLEANED_UP), cleanupStatuses, 3, now, 10
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unapproved lifecycle statuses");

        // FAILED
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionAdapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.FAILED), cleanupStatuses, 3, now, 10
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unapproved lifecycle statuses");

        // INITIATED
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionAdapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.INITIATED), cleanupStatuses, 3, now, 10
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unapproved lifecycle statuses");

        // ALLOCATED
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionAdapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.ALLOCATED), cleanupStatuses, 3, now, 10
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unapproved lifecycle statuses");

        // Unapproved cleanup statuses: NONE, COMPLETED, IN_PROGRESS
        Set<MediaUploadSessionStatus> safeLifecycle = Set.of(MediaUploadSessionStatus.ABORTED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionAdapter.findSessionsEligibleForCleanup(
                safeLifecycle, Set.of(UploadSessionCleanupStatus.NONE), 3, now, 10
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unapproved cleanup statuses");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionAdapter.findSessionsEligibleForCleanup(
                safeLifecycle, Set.of(UploadSessionCleanupStatus.COMPLETED), 3, now, 10
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unapproved cleanup statuses");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionAdapter.findSessionsEligibleForCleanup(
                safeLifecycle, Set.of(UploadSessionCleanupStatus.IN_PROGRESS), 3, now, 10
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unapproved cleanup statuses");
    }

    @Test
    @DisplayName("Retry and last-attempt cutoff boundaries, including null timestamps")
    void shouldEnforceRetryAndLastAttemptCutoffs() {
        UUID nullAttemptId = UUID.randomUUID();
        UUID beforeCutoffId = UUID.randomUUID();
        UUID afterCutoffId = UUID.randomUUID();
        UUID exceededRetryId = UUID.randomUUID();

        Instant now = Instant.now();
        Instant cutoff = now.minus(15, ChronoUnit.MINUTES);

        // 1. null lastAttempt (never attempted) -> eligible
        createSessionEntity(nullAttemptId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING, 0, null, null, null, null, now.minus(4, ChronoUnit.HOURS));

        // 2. lastAttempt <= cutoff (20m ago <= 15m ago cutoff) -> eligible
        createSessionEntity(beforeCutoffId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.FAILED, 1, now.minus(20, ChronoUnit.MINUTES), null, null, null, now.minus(3, ChronoUnit.HOURS));

        // 3. lastAttempt > cutoff (5m ago > 15m ago cutoff) -> NOT eligible (in backoff)
        createSessionEntity(afterCutoffId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.FAILED, 1, now.minus(5, ChronoUnit.MINUTES), null, null, null, now.minus(2, ChronoUnit.HOURS));

        // 4. retryCount = 4 > maxRetryCount = 3 -> NOT eligible
        createSessionEntity(exceededRetryId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.FAILED, 4, now.minus(30, ChronoUnit.MINUTES), null, null, null, now.minus(1, ChronoUnit.HOURS));

        List<MediaUploadSession> results = sessionAdapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.ABORTED),
                Set.of(UploadSessionCleanupStatus.PENDING, UploadSessionCleanupStatus.FAILED),
                3, // maxRetryCount
                cutoff,
                10
        );

        List<UUID> resultIds = results.stream().map(MediaUploadSession::getId).toList();
        assertThat(resultIds).contains(nullAttemptId, beforeCutoffId);
        assertThat(resultIds).doesNotContain(afterCutoffId, exceededRetryId);
    }

    @Test
    @DisplayName("Expiration cutoffs filter correctly on expiresAt <= now")
    void shouldEnforceExpirationCutoffs() {
        UUID expiredId = UUID.randomUUID();
        UUID notExpiredId = UUID.randomUUID();
        Instant now = Instant.now();

        // Expired (10 minutes ago)
        createSessionEntity(expiredId, MediaUploadSessionStatus.INITIATED, UploadSessionCleanupStatus.NONE, 0, null, now.minus(10, ChronoUnit.MINUTES), null, null, now.minus(25, ChronoUnit.HOURS));

        // Not expired (10 minutes in the future)
        createSessionEntity(notExpiredId, MediaUploadSessionStatus.INITIATED, UploadSessionCleanupStatus.NONE, 0, null, now.plus(10, ChronoUnit.MINUTES), null, null, now.minus(23, ChronoUnit.HOURS));

        List<MediaUploadSession> results = sessionAdapter.findExpiredSessions(
                Set.of(MediaUploadSessionStatus.INITIATED),
                now,
                10
        );

        List<UUID> resultIds = results.stream().map(MediaUploadSession::getId).toList();
        assertThat(resultIds).contains(expiredId);
        assertThat(resultIds).doesNotContain(notExpiredId);
    }

    @Test
    @DisplayName("Lease cutoffs filter correctly on leaseExpiresAt <= now and not null")
    void shouldEnforceLeaseExpirationCutoffs() {
        UUID expiredLeaseId = UUID.randomUUID();
        UUID activeLeaseId = UUID.randomUUID();
        UUID noLeaseId = UUID.randomUUID();
        Instant now = Instant.now();

        // Lease expired 5 minutes ago
        createSessionEntity(expiredLeaseId, MediaUploadSessionStatus.COMPLETING, UploadSessionCleanupStatus.NONE, 0, null, null, "claim-stale", now.minus(5, ChronoUnit.MINUTES), now.minus(1, ChronoUnit.HOURS));

        // Lease active (5 minutes in future)
        createSessionEntity(activeLeaseId, MediaUploadSessionStatus.COMPLETING, UploadSessionCleanupStatus.NONE, 0, null, null, "claim-active", now.plus(5, ChronoUnit.MINUTES), now.minus(1, ChronoUnit.HOURS));

        // No lease (leaseExpiresAt is null)
        createSessionEntity(noLeaseId, MediaUploadSessionStatus.COMPLETING, UploadSessionCleanupStatus.NONE, 0, null, null, null, null, now.minus(1, ChronoUnit.HOURS));

        List<MediaUploadSession> results = sessionAdapter.findSessionsWithExpiredLeases(
                Set.of(MediaUploadSessionStatus.COMPLETING),
                now,
                10
        );

        List<UUID> resultIds = results.stream().map(MediaUploadSession::getId).toList();
        assertThat(resultIds).contains(expiredLeaseId);
        assertThat(resultIds).doesNotContain(activeLeaseId, noLeaseId);
    }

    @Test
    @DisplayName("Identical timestamps sort deterministically by id ASC")
    void shouldOrderIdenticalTimestampsDeterministicallyById() {
        Instant identicalTime = Instant.parse("2026-10-10T12:00:00Z");

        UUID idA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID idC = UUID.fromString("00000000-0000-0000-0000-000000000003");

        // Insert in reverse order
        createSessionEntity(idC, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING, 0, null, null, null, null, identicalTime);
        createSessionEntity(idA, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING, 0, null, null, null, null, identicalTime);
        createSessionEntity(idB, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING, 0, null, null, null, null, identicalTime);

        List<MediaUploadSession> results = sessionAdapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.ABORTED),
                Set.of(UploadSessionCleanupStatus.PENDING),
                5,
                null,
                10
        );

        List<UUID> matchedIds = results.stream()
                .map(MediaUploadSession::getId)
                .filter(id -> id.equals(idA) || id.equals(idB) || id.equals(idC))
                .toList();

        assertThat(matchedIds).containsExactly(idA, idB, idC);
    }

    @Test
    @DisplayName("Batch limits restrict returned results and empty results are handled safely")
    void shouldEnforceBatchLimitsAndHandleEmptyResults() {
        Instant now = Instant.now();

        // Insert 3 candidates
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        createSessionEntity(id1, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING, 0, null, null, null, null, now.minus(3, ChronoUnit.HOURS));
        createSessionEntity(id2, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING, 0, null, null, null, null, now.minus(2, ChronoUnit.HOURS));
        createSessionEntity(id3, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING, 0, null, null, null, null, now.minus(1, ChronoUnit.HOURS));

        // Query with limit 2
        List<MediaUploadSession> batch = sessionAdapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.ABORTED),
                Set.of(UploadSessionCleanupStatus.PENDING),
                5,
                now,
                2
        );

        assertThat(batch).hasSize(2);

        // Query for non-existent status returns empty list without error
        List<MediaUploadSession> emptyResults = sessionAdapter.findSessionsWithExpiredLeases(
                Set.of(MediaUploadSessionStatus.ALLOCATED),
                now.minus(100, ChronoUnit.DAYS),
                10
        );

        assertThat(emptyResults).isEmpty();
    }
}
