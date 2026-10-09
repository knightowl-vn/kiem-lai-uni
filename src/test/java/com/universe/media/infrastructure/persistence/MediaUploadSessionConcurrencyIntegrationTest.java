package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.MediaUploadSessionService;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
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
        "spring.flyway.enabled=true",
        "media.storage.b2.bucket=kiemlai-video",
        "media.storage.r2.bucket=kiemlai-r2"
})
@Import({
        MediaUploadSessionPersistenceAdapter.class,
        StorageKeyAllocationPersistenceAdapter.class,
        StorageKeyAllocationService.class,
        MediaStorageRoutingService.class,
        MediaUploadSessionService.class,
        MediaUploadSessionConcurrencyIntegrationTest.TestConfig.class
})
@DisplayName("MediaUploadSession Real MySQL Service-Level Concurrency & Abort Transaction Integration Test")
class MediaUploadSessionConcurrencyIntegrationTest {

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
    private MediaUploadSessionService sessionService;

    @Autowired
    private MediaUploadSessionPersistenceAdapter sessionAdapter;

    @Autowired
    private StorageKeyAllocationService allocationService;

    @Autowired
    private MediaStorageRoutingService routingService;

    @Autowired
    private ClockPort clockPort;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdSessionIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdAllocationIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdAssetIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void tearDown() {
        for (UUID sessionId : createdSessionIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_upload_sessions WHERE id = ?", sessionId.toString());
            } catch (Exception ignored) {
            }
        }
        for (UUID allocId : createdAllocationIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_storage_key_allocations WHERE id = ?", allocId.toString());
            } catch (Exception ignored) {
            }
        }
        for (UUID assetId : createdAssetIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", assetId.toString());
            } catch (Exception ignored) {
            }
        }
    }

    // =============================================================================================
    // 1. Idempotency Concurrency Tests
    // =============================================================================================

    @Test
    @DisplayName("Concurrent allocateSession calls accept legitimate 1 or 2 allocation outcomes, with zero leaked reservations")
    void shouldHandleConcurrentAllocateSessionRaceCleanly() throws Exception {
        Set<String> preExistingAllocationIds = new HashSet<>(
                jdbcTemplate.queryForList("SELECT id FROM media_storage_key_allocations", String.class)
        );

        String initiatorPrincipalId = "user-race-" + UUID.randomUUID();
        String idempotencyKey = "idem-race-" + UUID.randomUUID();
        String consumerContext = "screen";
        String targetEntityType = "ScreenItem";
        UUID targetEntityId = UUID.randomUUID();
        long declaredSizeBytes = 104857600L; // 100 MB
        String originalFilename = "concurrent_video_test.mp4";
        String clientTag = "screen";

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        try {
            Callable<MediaUploadSession> task = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return sessionService.allocateSession(
                        initiatorPrincipalId,
                        consumerContext,
                        targetEntityType,
                        targetEntityId,
                        idempotencyKey,
                        declaredSizeBytes,
                        originalFilename,
                        clientTag
                );
            };

            Future<MediaUploadSession> future1 = executor.submit(task);
            Future<MediaUploadSession> future2 = executor.submit(task);

            MediaUploadSession session1 = future1.get(15, TimeUnit.SECONDS);
            MediaUploadSession session2 = future2.get(15, TimeUnit.SECONDS);

            // 1. Both calls complete successfully and return the exact same session ID and allocation ID
            assertThat(session1).isNotNull();
            assertThat(session2).isNotNull();
            assertThat(session1.getId()).isEqualTo(session2.getId());
            assertThat(session1.getStorageAllocationId()).isEqualTo(session2.getStorageAllocationId());
            assertThat(session1.getStorageKey()).isEqualTo(session2.getStorageKey());
            assertThat(session1.getInitiatorPrincipalId()).isEqualTo(initiatorPrincipalId);
            assertThat(session1.getIdempotencyKey()).isEqualTo(idempotencyKey);

            createdSessionIds.add(session1.getId());

            // 2. Exactly one row exists in media_upload_sessions
            List<Map<String, Object>> sessionRows = jdbcTemplate.queryForList(
                    "SELECT id, storage_allocation_id, storage_key FROM media_upload_sessions WHERE initiator_principal_id = ? AND idempotency_key = ?",
                    initiatorPrincipalId,
                    idempotencyKey
            );
            assertThat(sessionRows).hasSize(1);
            assertThat(sessionRows.get(0).get("id")).isEqualTo(session1.getId().toString());
            assertThat(sessionRows.get(0).get("storage_allocation_id")).isEqualTo(session1.getStorageAllocationId().toString());

            // 3. Allocations check: accept either 1 allocation (if second observed committed session early)
            // or 2 allocations (if both reserved before insert collision)
            List<Map<String, Object>> allAllocations = jdbcTemplate.queryForList(
                    "SELECT id, allocation_status, allocated_by_id, storage_key FROM media_storage_key_allocations"
            );
            List<Map<String, Object>> testAllocations = allAllocations.stream()
                    .filter(row -> !preExistingAllocationIds.contains(row.get("id").toString()))
                    .toList();

            for (Map<String, Object> alloc : testAllocations) {
                createdAllocationIds.add(UUID.fromString((String) alloc.get("id")));
            }

            assertThat(testAllocations.size()).isBetween(1, 2);

            Map<String, Object> winnerAllocation = testAllocations.stream()
                    .filter(row -> row.get("id").toString().equals(session1.getStorageAllocationId().toString()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Winning allocation row not found in database"));

            assertThat(winnerAllocation.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.RESERVED.name());
            assertThat(winnerAllocation.get("allocated_by_id")).isEqualTo(session1.getId().toString());

            if (testAllocations.size() == 2) {
                Map<String, Object> loserAllocation = testAllocations.stream()
                        .filter(row -> !row.get("id").toString().equals(session1.getStorageAllocationId().toString()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Loser allocation row not found in database"));

                // Loser allocation must be TOMBSTONE
                assertThat(loserAllocation.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE.name());
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Separately synchronized test forces conflicting idempotency INSERT race and proves loser recovery")
    void shouldForceConflictingInsertAndRecoverLosingTransaction() throws Exception {
        Set<String> preExistingAllocationIds = new HashSet<>(
                jdbcTemplate.queryForList("SELECT id FROM media_storage_key_allocations", String.class)
        );

        String initiatorPrincipalId = "user-forced-" + UUID.randomUUID();
        String idempotencyKey = "idem-forced-" + UUID.randomUUID();
        String consumerContext = "screen";
        String targetEntityType = "ScreenItem";
        UUID targetEntityId = UUID.randomUUID();
        long declaredSizeBytes = 104857600L;
        String originalFilename = "forced_race_video.mp4";
        String clientTag = "screen";

        // Synchronize both threads right before session repository save to guarantee an actual MySQL unique collision
        CyclicBarrier preSaveBarrier = new CyclicBarrier(2);
        MediaUploadSessionRepositoryPort synchronizedPort = new MediaUploadSessionRepositoryPort() {
            @Override
            public MediaUploadSession save(MediaUploadSession session) {
                try {
                    preSaveBarrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return sessionAdapter.save(session);
            }

            @Override
            public Optional<MediaUploadSession> findById(UUID id) {
                return sessionAdapter.findById(id);
            }

            @Override
            public Optional<MediaUploadSession> findByIdempotencyKey(String principalId, String idemKey) {
                return sessionAdapter.findByIdempotencyKey(principalId, idemKey);
            }

            @Override
            public Optional<MediaUploadSession> findByStorageAllocationId(UUID storageAllocationId) {
                return sessionAdapter.findByStorageAllocationId(storageAllocationId);
            }

            @Override
            public Optional<MediaUploadSession> findByStorageLocation(StorageProviderId providerId, StorageKey storageKey) {
                return sessionAdapter.findByStorageLocation(providerId, storageKey);
            }
        };

        MediaUploadSessionService forcedRaceService = new MediaUploadSessionService(
                synchronizedPort,
                allocationService,
                routingService,
                clockPort,
                transactionManager
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Callable<MediaUploadSession> task = () -> forcedRaceService.allocateSession(
                    initiatorPrincipalId,
                    consumerContext,
                    targetEntityType,
                    targetEntityId,
                    idempotencyKey,
                    declaredSizeBytes,
                    originalFilename,
                    clientTag
            );

            Future<MediaUploadSession> future1 = executor.submit(task);
            Future<MediaUploadSession> future2 = executor.submit(task);

            MediaUploadSession session1 = future1.get(15, TimeUnit.SECONDS);
            MediaUploadSession session2 = future2.get(15, TimeUnit.SECONDS);

            // Both threads receive the identical winning session
            assertThat(session1).isNotNull();
            assertThat(session2).isNotNull();
            assertThat(session1.getId()).isEqualTo(session2.getId());
            assertThat(session1.getStorageAllocationId()).isEqualTo(session2.getStorageAllocationId());

            createdSessionIds.add(session1.getId());

            // Exactly 1 row in media_upload_sessions
            List<Map<String, Object>> sessionRows = jdbcTemplate.queryForList(
                    "SELECT id, storage_allocation_id FROM media_upload_sessions WHERE initiator_principal_id = ? AND idempotency_key = ?",
                    initiatorPrincipalId,
                    idempotencyKey
            );
            assertThat(sessionRows).hasSize(1);

            // Because both threads passed the probe before saving, exactly 2 allocations were created
            List<Map<String, Object>> allAllocations = jdbcTemplate.queryForList(
                    "SELECT id, allocation_status, allocated_by_id FROM media_storage_key_allocations"
            );
            List<Map<String, Object>> testAllocations = allAllocations.stream()
                    .filter(row -> !preExistingAllocationIds.contains(row.get("id").toString()))
                    .toList();

            for (Map<String, Object> alloc : testAllocations) {
                createdAllocationIds.add(UUID.fromString((String) alloc.get("id")));
            }

            assertThat(testAllocations).hasSize(2);

            Map<String, Object> winnerAllocation = testAllocations.stream()
                    .filter(row -> row.get("id").toString().equals(session1.getStorageAllocationId().toString()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Winning allocation row not found"));

            Map<String, Object> loserAllocation = testAllocations.stream()
                    .filter(row -> !row.get("id").toString().equals(session1.getStorageAllocationId().toString()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Loser allocation row not found"));

            // Winner is RESERVED
            assertThat(winnerAllocation.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.RESERVED.name());
            assertThat(winnerAllocation.get("allocated_by_id")).isEqualTo(session1.getId().toString());

            // Loser is strictly TOMBSTONE (safely recovered)
            assertThat(loserAllocation.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE.name());
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // =============================================================================================
    // 2. Abort Transaction Consistency & Rollback Failure-Injection Tests
    // =============================================================================================

    @Test
    @DisplayName("Abort commits atomically: session is ABORTED and allocation is TOMBSTONE")
    void shouldAtomicallyAbortSessionAndTombstoneAllocationOnSuccess() {
        String principalId = "user-abort-" + UUID.randomUUID();
        String idempotencyKey = "idem-abort-" + UUID.randomUUID();

        MediaUploadSession session = sessionService.allocateSession(
                principalId,
                "screen",
                "ScreenItem",
                UUID.randomUUID(),
                idempotencyKey,
                52428800L,
                "video.mp4",
                "screen"
        );
        createdSessionIds.add(session.getId());
        createdAllocationIds.add(session.getStorageAllocationId());

        sessionService.markInitiated(session.getId(), "b2-upload-123", principalId);

        // Execute abort
        MediaUploadSession aborted = sessionService.abortSession(session.getId(), principalId);
        assertThat(aborted.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
        assertThat(aborted.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.PENDING);

        // Verify MySQL database state
        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, cleanup_status FROM media_upload_sessions WHERE id = ?",
                session.getId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo("ABORTED");
        assertThat(sessionRow.get("cleanup_status")).isEqualTo("PENDING");

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                session.getStorageAllocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo("TOMBSTONE");
    }

    @Test
    @DisplayName("Failure during abort transaction rolls back both session and allocation: zero contradiction")
    void shouldAtomicallyRollbackBothSessionAndAllocationWhenAbortTransactionFails() {
        String principalId = "user-abort-fail-" + UUID.randomUUID();
        String idempotencyKey = "idem-abort-fail-" + UUID.randomUUID();

        MediaUploadSession session = sessionService.allocateSession(
                principalId,
                "screen",
                "ScreenItem",
                UUID.randomUUID(),
                idempotencyKey,
                52428800L,
                "video.mp4",
                "screen"
        );
        createdSessionIds.add(session.getId());
        createdAllocationIds.add(session.getStorageAllocationId());

        sessionService.markInitiated(session.getId(), "b2-upload-fail", principalId);

        // Inject a simulated failure in a composite transaction wrapping abortSession
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> txTemplate.execute(status -> {
            sessionService.abortSession(session.getId(), principalId);
            throw new RuntimeException("Simulated post-abort failure in business transaction boundary");
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated post-abort failure");

        // Verify in MySQL: because abortSession and tombstoneAllocation participate in the same transaction,
        // BOTH rolled back! Neither row remains in an inconsistent state.
        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, cleanup_status FROM media_upload_sessions WHERE id = ?",
                session.getId().toString()
        );
        // Still INITIATED, NOT ABORTED
        assertThat(sessionRow.get("status")).isEqualTo("INITIATED");
        assertThat(sessionRow.get("cleanup_status")).isEqualTo("NONE");

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                session.getStorageAllocationId().toString()
        );
        // Still RESERVED, NOT TOMBSTONE! Zero contradiction!
        assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("Completed session rejects abort and cannot be cleaned up")
    void shouldRejectAbortOnCompletedSession() {
        String principalId = "user-abort-completed-" + UUID.randomUUID();
        String idempotencyKey = "idem-abort-completed-" + UUID.randomUUID();

        MediaUploadSession session = sessionService.allocateSession(
                principalId,
                "screen",
                "ScreenItem",
                UUID.randomUUID(),
                idempotencyKey,
                52428800L,
                "video.mp4",
                "screen"
        );
        createdSessionIds.add(session.getId());
        createdAllocationIds.add(session.getStorageAllocationId());

        sessionService.markInitiated(session.getId(), "b2-upload-comp", principalId);

        UUID assetId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version) VALUES (?, 'VIDEO', 'PUBLIC', 'ACTIVE', 1, NOW(6), NOW(6), 0)",
                assetId.toString()
        );
        createdAssetIds.add(assetId);

        // Transition to COMPLETED in database with valid 64-character lowercase hex hash
        jdbcTemplate.update(
                "UPDATE media_upload_sessions SET status = 'COMPLETED', declared_content_hash = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', registered_asset_id = ?, completed_at = NOW(6) WHERE id = ?",
                assetId.toString(),
                session.getId().toString()
        );
        jdbcTemplate.update(
                "UPDATE media_storage_key_allocations SET allocation_status = 'COMMITTED' WHERE id = ?",
                session.getStorageAllocationId().toString()
        );

        // Attempting to abort a completed session must throw IllegalStateException
        assertThatThrownBy(() -> sessionService.abortSession(session.getId(), principalId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot abort session in terminal state: COMPLETED");

        // Verify in MySQL: remains COMPLETED and COMMITTED
        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status FROM media_upload_sessions WHERE id = ?",
                session.getId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo("COMPLETED");

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                session.getStorageAllocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo("COMMITTED");
    }
}
