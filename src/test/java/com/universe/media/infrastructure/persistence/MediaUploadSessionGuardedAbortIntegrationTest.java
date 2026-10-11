package com.universe.media.infrastructure.persistence;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.storage.MultipartAbortResult;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.MediaUploadSessionService;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real MySQL Integration Test Suite for MS-08E2 / S2D-2B2B:
 * Real MySQL Transaction & Concurrency Verification for Guarded Single-Session Abort Orchestration.
 *
 * <p>Verifies:
 * <ul>
 *     <li>Test A: TX-A cleanup claim commit visibility before provider invocation;</li>
 *     <li>Test B: Deterministic two-worker concurrent claim races with single-winner semantics;</li>
 *     <li>Test C: Fail-closed TX-A validation failures preventing provider calls;</li>
 *     <li>Test D: TX-B lease fencing, expiration, concurrent modification, and rollback safety;</li>
 *     <li>Test E: Conservative QUARANTINED outcome persistence for all provider responses and errors;</li>
 *     <li>Negative invariants: No automatic CLEANED_UP, no COMPLETED cleanup status, no allocation mutation.</li>
 * </ul>
 */
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
        MediaUploadSessionGuardedAbortIntegrationTest.TestConfig.class
})
@DisplayName("MS-08E2 / S2D-2B2B: Real MySQL Transaction & Concurrency Integration Tests")
class MediaUploadSessionGuardedAbortIntegrationTest {

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public MutableTestClock clockPort() {
            return new MutableTestClock(Instant.now());
        }

        @Bean
        public MultipartStoragePort multipartStoragePort() {
            MultipartStoragePort mock = org.mockito.Mockito.mock(MultipartStoragePort.class);
            when(mock.providerId()).thenReturn(B2_PROVIDER);
            when(mock.configuredBucketName()).thenReturn(B2_BUCKET);
            return mock;
        }
    }

    static class MutableTestClock implements ClockPort {
        private final AtomicReference<Instant> currentInstant;

        MutableTestClock(Instant initial) {
            this.currentInstant = new AtomicReference<>(initial);
        }

        @Override
        public Instant now() {
            return currentInstant.get();
        }

        void set(Instant instant) {
            currentInstant.set(instant);
        }

        void advance(Duration duration) {
            currentInstant.updateAndGet(t -> t.plus(duration));
        }
    }

    @Autowired
    private MediaUploadSessionService sessionService;

    @Autowired
    private MediaUploadSessionPersistenceAdapter sessionAdapter;

    @Autowired
    private StorageKeyAllocationPersistenceAdapter allocationAdapter;

    @Autowired
    private MultipartStoragePort multipartStoragePort;

    @Autowired
    private MutableTestClock testClock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> createdSessionIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdAllocationIds = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        testClock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        reset(multipartStoragePort);
        when(multipartStoragePort.providerId()).thenReturn(B2_PROVIDER);
        when(multipartStoragePort.configuredBucketName()).thenReturn(B2_BUCKET);
    }

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
    }

    /**
     * Paired fixture holder ensuring strict 1:1 allocatedById and storageKey binding between session and allocation.
     */
    static class SessionAllocationPair {
        final MediaUploadSession session;
        final StorageKeyAllocation allocation;

        SessionAllocationPair(MediaUploadSession session, StorageKeyAllocation allocation) {
            this.session = session;
            this.allocation = allocation;
        }
    }

    private SessionAllocationPair createAndSaveEligibleSession(StorageKey storageKey) {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = testClock.now();

        // Ensure allocatedById is precisely sessionId, and allocatedByType is UPLOAD_SESSION
        StorageKeyAllocation alloc = StorageKeyAllocation.reserve(
                allocId,
                B2_PROVIDER,
                B2_BUCKET,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );
        allocationAdapter.save(alloc);
        createdAllocationIds.add(allocId);

        MediaUploadSession session = MediaUploadSession.create(
                sessionId,
                "user-" + UUID.randomUUID(),
                "screen",
                "ScreenItem",
                targetId,
                "idem-" + UUID.randomUUID(),
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 3,
                B2_PROVIDER,
                B2_BUCKET,
                storageKey,
                alloc.getId(),
                "video.mp4",
                "screen",
                now.plus(24, ChronoUnit.HOURS),
                now
        );
        session.markInitiated("b2-upload-" + UUID.randomUUID(), now);
        session.abort(now);
        MediaUploadSession saved = sessionAdapter.save(session);
        createdSessionIds.add(sessionId);

        return new SessionAllocationPair(saved, alloc);
    }

    private SessionAllocationPair createAndSaveExpiredSession(StorageKey storageKey) {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = testClock.now();

        StorageKeyAllocation alloc = StorageKeyAllocation.reserve(
                allocId,
                B2_PROVIDER,
                B2_BUCKET,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now.minus(25, ChronoUnit.HOURS)
        );
        allocationAdapter.save(alloc);
        createdAllocationIds.add(allocId);

        MediaUploadSession session = MediaUploadSession.create(
                sessionId,
                "user-" + UUID.randomUUID(),
                "screen",
                "ScreenItem",
                targetId,
                "idem-" + UUID.randomUUID(),
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 3,
                B2_PROVIDER,
                B2_BUCKET,
                storageKey,
                alloc.getId(),
                "video.mp4",
                "screen",
                now.minus(1, ChronoUnit.HOURS),
                now.minus(25, ChronoUnit.HOURS)
        );
        session.markInitiated("b2-upload-" + UUID.randomUUID(), now.minus(24, ChronoUnit.HOURS));
        session.expire(now);
        MediaUploadSession saved = sessionAdapter.save(session);
        createdSessionIds.add(sessionId);

        return new SessionAllocationPair(saved, alloc);
    }

    // =============================================================================================
    // PHASE 4 — TEST A: TX-A CLAIM COMMIT ORDER & VISIBILITY
    // =============================================================================================

    @Nested
    @DisplayName("Phase 4 — Test A: TX-A Claim Commit Order & Visibility")
    class TestATxAPhaseCommitOrderTests {

        @Test
        @DisplayName("TX-A cleanup claim commits durably to MySQL before provider abort call, followed by TX-B quarantine")
        void shouldCommitTxAClaimBeforeProviderInvocationAndPersistQuarantinedOutcome() {
            StorageKey storageKey = StorageKey.of("media/screen/test_a_aborted_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();
            UUID allocId = pair.allocation.getId();

            AtomicBoolean verifiedInsideProvider = new AtomicBoolean(false);

            when(multipartStoragePort.abortMultipartUpload(any(StorageKey.class), any(String.class)))
                    .thenAnswer(invocation -> {
                        // 1. Verify that NO Spring transaction is active on the calling thread during remote provider invocation
                        boolean txActive = TransactionSynchronizationManager.isActualTransactionActive();
                        assertThat(txActive)
                                .withFailMessage("Database transaction must NOT be active during remote provider call")
                                .isFalse();

                        // 2. Read session directly from real MySQL using independent JDBC connection
                        Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                                "SELECT cleanup_status, lease_owner, lease_expires_at, status FROM media_upload_sessions WHERE id = ?",
                                sessionId.toString()
                        );

                        // 3. Verify that TX-A is already committed and durably visible in MySQL
                        assertThat(dbRow.get("cleanup_status")).isEqualTo("IN_PROGRESS");
                        assertThat((String) dbRow.get("lease_owner")).startsWith(MediaUploadSession.CLEANUP_CLAIM_PREFIX);
                        assertThat(dbRow.get("lease_expires_at")).isNotNull();
                        assertThat(dbRow.get("status")).isEqualTo("ABORTED");

                        verifiedInsideProvider.set(true);
                        return MultipartAbortResult.ABORT_ACCEPTED;
                    });

            // Execute the guarded abort orchestration
            MediaUploadSession result = sessionService.executeGuardedMultipartAbort(sessionId);

            assertThat(verifiedInsideProvider.get()).isTrue();
            assertThat(result).isNotNull();
            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);

            // Read final state from MySQL after TX-B commit
            Map<String, Object> finalDbRow = jdbcTemplate.queryForMap(
                    "SELECT cleanup_status, cleanup_error_detail, lease_owner, lease_expires_at, status FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );

            // Verify TX-B persisted QUARANTINED and cleared lease
            assertThat(finalDbRow.get("cleanup_status")).isEqualTo("QUARANTINED");
            assertThat((String) finalDbRow.get("cleanup_error_detail"))
                    .contains("Provider abort acknowledged; physical cleanup verification pending (S2D-3)");
            assertThat(finalDbRow.get("lease_owner")).isNull();
            assertThat(finalDbRow.get("lease_expires_at")).isNull();
            assertThat(finalDbRow.get("status")).isEqualTo("ABORTED");

            // Mandatory negative assertions
            assertThat(finalDbRow.get("status")).isNotEqualTo("CLEANED_UP");
            assertThat(finalDbRow.get("cleanup_status")).isNotEqualTo("COMPLETED");

            // Verify storage allocation status was NOT released or tombstoned
            Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                    "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                    allocId.toString()
            );
            assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("TX-A cleanup claim commits durably to MySQL for EXPIRED session before provider invocation")
        void shouldCommitTxAClaimForExpiredSessionBeforeProviderInvocation() {
            StorageKey storageKey = StorageKey.of("media/screen/test_a_expired_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveExpiredSession(storageKey);
            UUID sessionId = pair.session.getId();
            UUID allocId = pair.allocation.getId();

            AtomicBoolean verifiedInsideProvider = new AtomicBoolean(false);

            when(multipartStoragePort.abortMultipartUpload(any(StorageKey.class), any(String.class)))
                    .thenAnswer(invocation -> {
                        boolean txActive = TransactionSynchronizationManager.isActualTransactionActive();
                        assertThat(txActive).isFalse();

                        Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                                "SELECT cleanup_status, lease_owner, status FROM media_upload_sessions WHERE id = ?",
                                sessionId.toString()
                        );
                        assertThat(dbRow.get("cleanup_status")).isEqualTo("IN_PROGRESS");
                        assertThat((String) dbRow.get("lease_owner")).startsWith(MediaUploadSession.CLEANUP_CLAIM_PREFIX);
                        assertThat(dbRow.get("status")).isEqualTo("EXPIRED");

                        verifiedInsideProvider.set(true);
                        return MultipartAbortResult.ABORT_ACCEPTED;
                    });

            MediaUploadSession result = sessionService.executeGuardedMultipartAbort(sessionId);

            assertThat(verifiedInsideProvider.get()).isTrue();
            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);

            Map<String, Object> finalDbRow = jdbcTemplate.queryForMap(
                    "SELECT cleanup_status, status FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );
            assertThat(finalDbRow.get("cleanup_status")).isEqualTo("QUARANTINED");
            assertThat(finalDbRow.get("status")).isEqualTo("EXPIRED");
            assertThat(finalDbRow.get("status")).isNotEqualTo("CLEANED_UP");

            Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                    "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                    allocId.toString()
            );
            assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");
        }
    }

    // =============================================================================================
    // PHASE 5 — TEST B: TWO CONCURRENT CLAIMS RACE & OPTIMISTIC LOCKING
    // =============================================================================================

    @Nested
    @DisplayName("Phase 5 — Test B: Two Concurrent Claims")
    class TestBConcurrentClaimsTests {

        @Test
        @DisplayName("Overlapping claim race: Worker 2 rejected fail-closed while Worker 1 is in provider phase")
        void shouldAllowOnlyOneWorkerToAcquireClaimWhenConcurrentClaimsOverlapDuringProviderPhase() throws Exception {
            StorageKey storageKey = StorageKey.of("media/screen/test_b_overlap_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();
            UUID allocId = pair.allocation.getId();

            CountDownLatch providerEnteredLatch = new CountDownLatch(1);
            CountDownLatch worker2CompletedLatch = new CountDownLatch(1);

            AtomicReference<MediaUploadSession> worker1Result = new AtomicReference<>();
            AtomicReference<Throwable> worker1Error = new AtomicReference<>();
            AtomicReference<MediaUploadSession> worker2Result = new AtomicReference<>();
            AtomicReference<Throwable> worker2Error = new AtomicReference<>();

            when(multipartStoragePort.abortMultipartUpload(any(StorageKey.class), any(String.class)))
                    .thenAnswer(invocation -> {
                        providerEnteredLatch.countDown();
                        // Wait for Worker 2 to attempt its claim while Worker 1 is in provider call
                        boolean worker2Ran = worker2CompletedLatch.await(10, TimeUnit.SECONDS);
                        assertThat(worker2Ran).isTrue();
                        return MultipartAbortResult.ABORT_ACCEPTED;
                    });

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                // Thread 1: Worker 1 executes guarded abort
                executor.submit(() -> {
                    try {
                        worker1Result.set(sessionService.executeGuardedMultipartAbort(sessionId));
                    } catch (Throwable t) {
                        worker1Error.set(t);
                    }
                });

                // Thread 2: Worker 2 attempts guarded abort AFTER Worker 1's TX-A has committed
                executor.submit(() -> {
                    try {
                        assertThat(providerEnteredLatch.await(10, TimeUnit.SECONDS)).isTrue();
                        worker2Result.set(sessionService.executeGuardedMultipartAbort(sessionId));
                    } catch (Throwable t) {
                        worker2Error.set(t);
                    } finally {
                        worker2CompletedLatch.countDown();
                    }
                });

                executor.shutdown();
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

                // Worker 1 succeeded
                assertThat(worker1Error.get()).isNull();
                assertThat(worker1Result.get()).isNotNull();
                assertThat(worker1Result.get().getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);

                // Worker 2 was rejected fail-closed because session is already claimed (IN_PROGRESS)
                assertThat(worker2Result.get()).isNull();
                assertThat(worker2Error.get())
                        .isNotNull()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("cleanup status must be PENDING");

                // Exactly ONE provider abort call was dispatched
                verify(multipartStoragePort, times(1)).abortMultipartUpload(any(StorageKey.class), any(String.class));

                // Verify MySQL final state
                Map<String, Object> finalDbRow = jdbcTemplate.queryForMap(
                        "SELECT cleanup_status, status, lease_owner FROM media_upload_sessions WHERE id = ?",
                        sessionId.toString()
                );
                assertThat(finalDbRow.get("cleanup_status")).isEqualTo("QUARANTINED");
                assertThat(finalDbRow.get("lease_owner")).isNull();
                assertThat(finalDbRow.get("status")).isNotEqualTo("CLEANED_UP");

                Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                        "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                        allocId.toString()
                );
                assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("Simultaneous race: CyclicBarrier synchronization ensures single winner and at most 1 provider abort")
        void shouldHandleSimultaneousConcurrentClaimRaceWithDeterministicSingleWinner() throws Exception {
            StorageKey storageKey = StorageKey.of("media/screen/test_b_barrier_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();
            UUID allocId = pair.allocation.getId();

            when(multipartStoragePort.abortMultipartUpload(any(StorageKey.class), any(String.class)))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            CountDownLatch completionLatch = new CountDownLatch(2);

            AtomicReference<MediaUploadSession> winningResult = new AtomicReference<>();
            List<Throwable> observedErrors = Collections.synchronizedList(new ArrayList<>());

            try {
                for (int i = 0; i < 2; i++) {
                    executor.submit(() -> {
                        try {
                            barrier.await(10, TimeUnit.SECONDS);
                            MediaUploadSession res = sessionService.executeGuardedMultipartAbort(sessionId);
                            winningResult.set(res);
                        } catch (Throwable t) {
                            observedErrors.add(t);
                        } finally {
                            completionLatch.countDown();
                        }
                    });
                }

                assertThat(completionLatch.await(15, TimeUnit.SECONDS)).isTrue();
                executor.shutdown();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

                // Exactly ONE worker must succeed, and exactly ONE worker must fail
                assertThat(winningResult.get()).isNotNull();
                assertThat(winningResult.get().getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
                assertThat(observedErrors).hasSize(1);

                Throwable losingError = observedErrors.get(0);
                // Losing error must be fail-closed: OptimisticLockingFailureException or IllegalStateException
                assertThat(
                        losingError instanceof OptimisticLockingFailureException
                                || losingError instanceof IllegalStateException
                ).withFailMessage("Expected losing worker to fail with OptimisticLockingFailureException or IllegalStateException, got: " + losingError)
                        .isTrue();

                // At most 1 provider abort call dispatched
                verify(multipartStoragePort, times(1)).abortMultipartUpload(any(StorageKey.class), any(String.class));

                // Verify MySQL final state
                Map<String, Object> finalDbRow = jdbcTemplate.queryForMap(
                        "SELECT cleanup_status, status, lease_owner FROM media_upload_sessions WHERE id = ?",
                        sessionId.toString()
                );
                assertThat(finalDbRow.get("cleanup_status")).isEqualTo("QUARANTINED");
                assertThat(finalDbRow.get("lease_owner")).isNull();
                assertThat(finalDbRow.get("status")).isNotEqualTo("CLEANED_UP");

                Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                        "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                        allocId.toString()
                );
                assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("TX-A demonstrates real JPA optimistic-locking rejection when competing transaction increments persistence_version")
        void shouldFailWithOptimisticLockingWhenConcurrentClaimAttemptsToPersistStaleVersion() {
            StorageKey storageKey = StorageKey.of("media/screen/test_b_optlock_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();
            UUID allocId = pair.allocation.getId();

            // 1. Two concurrent workers both load the same session snapshot at version 0
            MediaUploadSession worker1Snapshot = sessionAdapter.findById(sessionId).orElseThrow();
            MediaUploadSession worker2Snapshot = sessionAdapter.findById(sessionId).orElseThrow();
            assertThat(worker1Snapshot.getPersistenceVersion()).isEqualTo(0L);
            assertThat(worker2Snapshot.getPersistenceVersion()).isEqualTo(0L);

            // 2. Worker 1 claims cleanup with a valid UUID token and commits to MySQL (bumps persistence_version to 1)
            String worker1Token = "CLEANUP_CLAIM:" + UUID.randomUUID();
            worker1Snapshot.claimCleanup(worker1Token, testClock.now().plus(Duration.ofMinutes(5)), testClock.now());
            MediaUploadSession committedWorker1 = sessionAdapter.save(worker1Snapshot);
            assertThat(committedWorker1.getPersistenceVersion()).isEqualTo(1L);

            // 3. Worker 2 attempts to claim cleanup using its stale version-0 snapshot with a valid UUID token
            String worker2Token = "CLEANUP_CLAIM:" + UUID.randomUUID();
            worker2Snapshot.claimCleanup(worker2Token, testClock.now().plus(Duration.ofMinutes(5)), testClock.now());

            // 4. Worker 2's attempt to persist must fail closed with OptimisticLockingFailureException
            assertThatThrownBy(() -> sessionAdapter.save(worker2Snapshot))
                    .isInstanceOf(OptimisticLockingFailureException.class)
                    .hasMessageContaining("Upload session " + sessionId + " was concurrently modified. Expected version 0 but found 1.");

            // 5. Verify durable MySQL state preserves Worker 1's claim and was NOT corrupted by Worker 2
            Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                    "SELECT persistence_version, lease_owner, cleanup_status FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );
            Number version = (Number) dbRow.get("persistence_version");
            assertThat(version.longValue()).isEqualTo(1L);
            assertThat(dbRow.get("lease_owner")).isEqualTo(worker1Token);
            assertThat(dbRow.get("cleanup_status")).isEqualTo("IN_PROGRESS");

            // 6. Verify allocation state remains untouched
            Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                    "SELECT allocation_status, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                    allocId.toString()
            );
            assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");
            assertThat(allocRow.get("allocated_by_id")).isEqualTo(sessionId.toString());
        }
    }

    // =============================================================================================
    // PHASE 6 — TEST C: TX-A FAILURE BEFORE PROVIDER
    // =============================================================================================

    @Nested
    @DisplayName("Phase 6 — Test C: TX-A Failure Before Provider")
    class TestCTxAFailureTests {

        @Test
        @DisplayName("TX-A rejects session with lifecycle status INITIATED, never invoking provider")
        void shouldRejectTxAAndNeverInvokeProviderWhenLifecycleStatusIsInitiated() {
            StorageKey storageKey = StorageKey.of("media/screen/test_c_init_" + UUID.randomUUID() + ".mp4");
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            Instant now = testClock.now();

            StorageKeyAllocation alloc = StorageKeyAllocation.reserve(
                    allocId, B2_PROVIDER, B2_BUCKET, storageKey, StorageKeyAllocationType.UPLOAD_SESSION, sessionId, now
            );
            allocationAdapter.save(alloc);
            createdAllocationIds.add(allocId);

            MediaUploadSession session = MediaUploadSession.create(
                    sessionId, "user-c1", "screen", "ScreenItem", UUID.randomUUID(), "idem-c1",
                    104857600L, B2_PROVIDER, B2_BUCKET, storageKey, alloc.getId(), "v.mp4", "screen",
                    now.plus(24, ChronoUnit.HOURS), now
            );
            session.markInitiated("upload-c1", now);
            sessionAdapter.save(session);
            createdSessionIds.add(sessionId);

            assertThatThrownBy(() -> sessionService.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lifecycle status must be ABORTED or EXPIRED, found INITIATED");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());

            Map<String, Object> dbRow = jdbcTemplate.queryForMap("SELECT status, cleanup_status FROM media_upload_sessions WHERE id = ?", sessionId.toString());
            assertThat(dbRow.get("status")).isEqualTo("INITIATED");
            assertThat(dbRow.get("cleanup_status")).isEqualTo("NONE");
        }

        @Test
        @DisplayName("TX-A rejects session with cleanupStatus QUARANTINED, never invoking provider")
        void shouldRejectTxAAndNeverInvokeProviderWhenCleanupStatusIsAlreadyQuarantined() {
            StorageKey storageKey = StorageKey.of("media/screen/test_c_quar_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();

            // Set to QUARANTINED in database
            jdbcTemplate.update("UPDATE media_upload_sessions SET cleanup_status = 'QUARANTINED' WHERE id = ?", sessionId.toString());

            assertThatThrownBy(() -> sessionService.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cleanup status must be PENDING, found QUARANTINED");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("TX-A rejects session with cleanupStatus IN_PROGRESS, never invoking provider")
        void shouldRejectTxAAndNeverInvokeProviderWhenCleanupStatusIsAlreadyInProgress() {
            StorageKey storageKey = StorageKey.of("media/screen/test_c_prog_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();

            String priorToken = "CLEANUP_CLAIM:" + UUID.randomUUID();
            jdbcTemplate.update("UPDATE media_upload_sessions SET cleanup_status = 'IN_PROGRESS', lease_owner = ? WHERE id = ?", priorToken, sessionId.toString());

            assertThatThrownBy(() -> sessionService.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cleanup status must be PENDING, found IN_PROGRESS");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("TX-A rejects session with unresolved lease, never invoking provider")
        void shouldRejectTxAAndNeverInvokeProviderWhenUnresolvedLeaseExists() {
            StorageKey storageKey = StorageKey.of("media/screen/test_c_lease_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();

            jdbcTemplate.update("UPDATE media_upload_sessions SET lease_owner = 'STALE_LEASE:123' WHERE id = ?", sessionId.toString());

            assertThatThrownBy(() -> sessionService.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("has unresolved lease");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("TX-A rejects session with completion intent evidence (declaredContentHash), never invoking provider")
        void shouldRejectTxAAndNeverInvokeProviderWhenCompletionIntentDeclaredContentHashIsPresent() {
            StorageKey storageKey = StorageKey.of("media/screen/test_c_hash_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();

            jdbcTemplate.update("UPDATE media_upload_sessions SET declared_content_hash = 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789' WHERE id = ?", sessionId.toString());

            assertThatThrownBy(() -> sessionService.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("declaredContentHash is present");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("TX-A rejects session when storage allocation status is COMMITTED, never invoking provider")
        void shouldRejectTxAAndNeverInvokeProviderWhenAllocationStatusIsCommitted() {
            StorageKey storageKey = StorageKey.of("media/screen/test_c_alloc_comm_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();
            UUID allocId = pair.allocation.getId();

            // Set allocation_status to 'COMMITTED' (valid in MySQL schema constraint, but forbidden for cleanup abort)
            jdbcTemplate.update("UPDATE media_storage_key_allocations SET allocation_status = 'COMMITTED' WHERE id = ?", allocId.toString());

            assertThatThrownBy(() -> sessionService.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be RESERVED or TOMBSTONE (found: COMMITTED)");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("TX-A accepts session when storage allocation is legitimately in TOMBSTONE status")
        void shouldAcceptTxAWhenAllocationStatusIsLegitimatelyTombstone() {
            StorageKey storageKey = StorageKey.of("media/screen/test_c_tomb_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();
            UUID allocId = pair.allocation.getId();

            // Legitimate TOMBSTONE status is accepted by validateAllocationBindingForCleanup
            jdbcTemplate.update("UPDATE media_storage_key_allocations SET allocation_status = 'TOMBSTONE' WHERE id = ?", allocId.toString());

            when(multipartStoragePort.abortMultipartUpload(any(), any()))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            MediaUploadSession result = sessionService.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            verify(multipartStoragePort, times(1)).abortMultipartUpload(any(), any());

            // Allocation remains in TOMBSTONE, not mutated further
            Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                    "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                    allocId.toString()
            );
            assertThat(allocRow.get("allocation_status")).isEqualTo("TOMBSTONE");
        }

        @Test
        @DisplayName("TX-A rejects provider execution when adapter bucket does not match authoritative routing bucket")
        void shouldRejectTxAAndNeverInvokeProviderWhenAdapterBucketMismatchesRouting() {
            StorageKey storageKey = StorageKey.of("media/screen/test_c_bucket_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();

            // Configure adapter mock with mismatching bucket name
            when(multipartStoragePort.configuredBucketName()).thenReturn("mismatched-bucket");

            assertThatThrownBy(() -> sessionService.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not match authoritative routing bucket");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }
    }

    // =============================================================================================
    // PHASE 7 — TEST D: TX-B FENCING AND ROLLBACK
    // =============================================================================================

    @Nested
    @DisplayName("Phase 7 — Test D: TX-B Fencing and Rollback")
    class TestDTxBFencingRollbackTests {

        @Test
        @DisplayName("TX-B fails closed when cleanup lease claim token is modified while provider abort was in flight")
        void shouldFailClosedInTxBWhenClaimTokenIsModifiedDuringProviderPhase() {
            StorageKey storageKey = StorageKey.of("media/screen/test_d_fenced_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();

            String hijackedToken = "CLEANUP_CLAIM:" + UUID.randomUUID();
            when(multipartStoragePort.abortMultipartUpload(any(StorageKey.class), any(String.class)))
                    .thenAnswer(invocation -> {
                        // While provider is running, mutate lease_owner in MySQL
                        jdbcTemplate.update("UPDATE media_upload_sessions SET lease_owner = ? WHERE id = ?", hijackedToken, sessionId.toString());
                        return MultipartAbortResult.ABORT_ACCEPTED;
                    });

            assertThatThrownBy(() -> sessionService.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TX-B abort claim token mismatch");

            // Verify in MySQL: TX-B did NOT overwrite the state with QUARANTINED
            Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                    "SELECT cleanup_status, lease_owner, status FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );
            assertThat(dbRow.get("cleanup_status")).isEqualTo("IN_PROGRESS");
            assertThat(dbRow.get("lease_owner")).isEqualTo(hijackedToken);
            assertThat(dbRow.get("status")).isNotEqualTo("CLEANED_UP");
        }

        @Test
        @DisplayName("TX-B fails closed when cleanup lease expires before TX-B can execute")
        void shouldFailClosedWhenCleanupLeaseExpiresBeforeTxB() {
            StorageKey storageKey = StorageKey.of("media/screen/test_d_lease_exp_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();

            when(multipartStoragePort.abortMultipartUpload(any(StorageKey.class), any(String.class)))
                    .thenAnswer(invocation -> {
                        // Advance clock by 10 minutes (lease is 5 minutes)
                        testClock.advance(Duration.ofMinutes(10));
                        return MultipartAbortResult.ABORT_ACCEPTED;
                    });

            assertThatThrownBy(() -> sessionService.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cleanup lease expired during provider abort call");

            // Verify in MySQL: session remains in IN_PROGRESS with claim token preserved for reconciliation
            Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                    "SELECT cleanup_status, lease_owner FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );
            assertThat(dbRow.get("cleanup_status")).isEqualTo("IN_PROGRESS");
            assertThat((String) dbRow.get("lease_owner")).startsWith(MediaUploadSession.CLEANUP_CLAIM_PREFIX);
        }

        @Test
        @DisplayName("TX-B fails closed when cleanup status is altered away from IN_PROGRESS while provider was in flight")
        void shouldFailClosedInTxBWhenCleanupStatusAlteredDuringProviderCall() {
            StorageKey storageKey = StorageKey.of("media/screen/test_d_status_mod_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();

            when(multipartStoragePort.abortMultipartUpload(any(StorageKey.class), any(String.class)))
                    .thenAnswer(invocation -> {
                        // While provider is running, simulate an external failure handler setting cleanup_status to FAILED
                        jdbcTemplate.update("UPDATE media_upload_sessions SET cleanup_status = 'FAILED' WHERE id = ?", sessionId.toString());
                        return MultipartAbortResult.ABORT_ACCEPTED;
                    });

            assertThatThrownBy(() -> sessionService.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TX-B abort cleanup status is not IN_PROGRESS");

            Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                    "SELECT cleanup_status FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );
            assertThat(dbRow.get("cleanup_status")).isEqualTo("FAILED");
            assertThat(dbRow.get("cleanup_status")).isNotEqualTo("QUARANTINED");
        }

        @Test
        @DisplayName("TX-B fails closed when session lifecycle status is altered away from ABORTED/EXPIRED during provider call")
        void shouldFailClosedInTxBWhenLifecycleStatusAlteredDuringProviderCall() {
            StorageKey storageKey = StorageKey.of("media/screen/test_d_life_mod_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();

            when(multipartStoragePort.abortMultipartUpload(any(StorageKey.class), any(String.class)))
                    .thenAnswer(invocation -> {
                        // While provider is running, mutate lifecycle status to CLEANED_UP
                        jdbcTemplate.update("UPDATE media_upload_sessions SET status = 'CLEANED_UP' WHERE id = ?", sessionId.toString());
                        return MultipartAbortResult.ABORT_ACCEPTED;
                    });

            assertThatThrownBy(() -> sessionService.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TX-B abort lifecycle status is not ABORTED or EXPIRED");

            Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                    "SELECT cleanup_status, status FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );
            assertThat(dbRow.get("cleanup_status")).isNotEqualTo("QUARANTINED");
        }
    }

    // =============================================================================================
    // PHASE 8 — TEST E: CONSERVATIVE PROVIDER OUTCOMES
    // =============================================================================================

    @Nested
    @DisplayName("Phase 8 — Test E: Conservative Provider Outcomes")
    class TestEConservativeProviderOutcomesTests {

        @Test
        @DisplayName("Provider ABORT_ACCEPTED persists QUARANTINED in MySQL")
        void shouldPersistQuarantinedWhenProviderAcknowledgesAbort() {
            StorageKey storageKey = StorageKey.of("media/screen/test_e_accepted_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();
            UUID allocId = pair.allocation.getId();

            when(multipartStoragePort.abortMultipartUpload(any(), any()))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            MediaUploadSession result = sessionService.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getCleanupErrorDetail())
                    .contains("Provider abort acknowledged; physical cleanup verification pending (S2D-3)");

            Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                    "SELECT cleanup_status, cleanup_error_detail, status FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );
            assertThat(dbRow.get("cleanup_status")).isEqualTo("QUARANTINED");
            assertThat(dbRow.get("status")).isEqualTo("ABORTED");
            assertThat(dbRow.get("status")).isNotEqualTo("CLEANED_UP");

            Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                    "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                    allocId.toString()
            );
            assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("Provider UPLOAD_ALREADY_ABSENT persists QUARANTINED in MySQL")
        void shouldPersistQuarantinedWhenProviderReportsUploadAlreadyAbsent() {
            StorageKey storageKey = StorageKey.of("media/screen/test_e_absent_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();

            when(multipartStoragePort.abortMultipartUpload(any(), any()))
                    .thenReturn(MultipartAbortResult.UPLOAD_ALREADY_ABSENT);

            MediaUploadSession result = sessionService.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getCleanupErrorDetail())
                    .contains("Provider reported upload already absent (NoSuchUpload); unresolved upload-history ambiguity pending reconciliation (S2D-3)");

            Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                    "SELECT cleanup_status, cleanup_error_detail FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );
            assertThat(dbRow.get("cleanup_status")).isEqualTo("QUARANTINED");
        }

        @Test
        @DisplayName("Provider network timeout persists QUARANTINED with safe diagnostic code in MySQL")
        void shouldPersistQuarantinedWhenProviderThrowsNetworkTimeout() {
            StorageKey storageKey = StorageKey.of("media/screen/test_e_timeout_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();
            UUID allocId = pair.allocation.getId();

            when(multipartStoragePort.abortMultipartUpload(any(), any()))
                    .thenThrow(new StorageException("Connection timed out"));

            MediaUploadSession result = sessionService.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getCleanupErrorDetail())
                    .contains("StorageException")
                    .contains("TIMEOUT_UNCERTAINTY")
                    .contains("physical cleanup outcome unresolved, pending reconciliation (S2D-3)")
                    .doesNotContain("success");
            assertThat(result.getCleanupErrorDetail().length()).isLessThanOrEqualTo(1000);

            Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                    "SELECT cleanup_status, cleanup_error_detail, status FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );
            assertThat(dbRow.get("cleanup_status")).isEqualTo("QUARANTINED");
            assertThat(dbRow.get("status")).isEqualTo("ABORTED");
            assertThat(dbRow.get("status")).isNotEqualTo("CLEANED_UP");
            assertThat((String) dbRow.get("cleanup_error_detail"))
                    .contains("StorageException")
                    .contains("TIMEOUT_UNCERTAINTY")
                    .contains("physical cleanup outcome unresolved, pending reconciliation (S2D-3)");

            Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                    "SELECT allocation_status, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                    allocId.toString()
            );
            assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");
            assertThat(allocRow.get("allocated_by_id")).isEqualTo(sessionId.toString());

            verify(multipartStoragePort, times(1)).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Provider HTTP 403 Forbidden persists QUARANTINED with safe diagnostic code in MySQL")
        void shouldPersistQuarantinedWhenProviderThrows403Forbidden() {
            StorageKey storageKey = StorageKey.of("media/screen/test_e_403_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();
            UUID allocId = pair.allocation.getId();

            when(multipartStoragePort.abortMultipartUpload(any(), any()))
                    .thenThrow(new StorageException("Forbidden", S3Exception.builder().statusCode(403).message("Access Denied").build()));

            MediaUploadSession result = sessionService.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getCleanupErrorDetail())
                    .contains("StorageException")
                    .contains("PROVIDER_AUTHENTICATION_OR_ACCESS_DENIED")
                    .contains("physical cleanup outcome unresolved, pending reconciliation (S2D-3)")
                    .doesNotContain("Access Denied");
            assertThat(result.getCleanupErrorDetail().length()).isLessThanOrEqualTo(1000);

            Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                    "SELECT cleanup_status, cleanup_error_detail, status FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );
            assertThat(dbRow.get("cleanup_status")).isEqualTo("QUARANTINED");
            assertThat(dbRow.get("status")).isEqualTo("ABORTED");
            assertThat(dbRow.get("status")).isNotEqualTo("CLEANED_UP");
            assertThat((String) dbRow.get("cleanup_error_detail"))
                    .contains("StorageException")
                    .contains("PROVIDER_AUTHENTICATION_OR_ACCESS_DENIED")
                    .contains("physical cleanup outcome unresolved, pending reconciliation (S2D-3)");

            Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                    "SELECT allocation_status, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                    allocId.toString()
            );
            assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");
            assertThat(allocRow.get("allocated_by_id")).isEqualTo(sessionId.toString());

            verify(multipartStoragePort, times(1)).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Provider HTTP 500 persists QUARANTINED with safe diagnostic code in MySQL")
        void shouldPersistQuarantinedWhenProviderThrows500InternalError() {
            StorageKey storageKey = StorageKey.of("media/screen/test_e_500_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();
            UUID allocId = pair.allocation.getId();

            when(multipartStoragePort.abortMultipartUpload(any(), any()))
                    .thenThrow(new StorageException("Internal error", S3Exception.builder().statusCode(500).message("Server error").build()));

            MediaUploadSession result = sessionService.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getCleanupErrorDetail())
                    .contains("StorageException")
                    .contains("PROVIDER_INTERNAL_SERVER_ERROR")
                    .contains("physical cleanup outcome unresolved, pending reconciliation (S2D-3)")
                    .doesNotContain("Server error");
            assertThat(result.getCleanupErrorDetail().length()).isLessThanOrEqualTo(1000);

            Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                    "SELECT cleanup_status, cleanup_error_detail, status FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );
            assertThat(dbRow.get("cleanup_status")).isEqualTo("QUARANTINED");
            assertThat(dbRow.get("status")).isEqualTo("ABORTED");
            assertThat(dbRow.get("status")).isNotEqualTo("CLEANED_UP");
            assertThat((String) dbRow.get("cleanup_error_detail"))
                    .contains("StorageException")
                    .contains("PROVIDER_INTERNAL_SERVER_ERROR")
                    .contains("physical cleanup outcome unresolved, pending reconciliation (S2D-3)");

            Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                    "SELECT allocation_status, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                    allocId.toString()
            );
            assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");
            assertThat(allocRow.get("allocated_by_id")).isEqualTo(sessionId.toString());

            verify(multipartStoragePort, times(1)).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Stored diagnostics in MySQL never contain raw credentials, tokens, or URL paths")
        void shouldSanitizeStoredDiagnosticsAndNeverPersistSensitiveDetails() {
            StorageKey storageKey = StorageKey.of("media/screen/test_e_sanitize_" + UUID.randomUUID() + ".mp4");
            SessionAllocationPair pair = createAndSaveEligibleSession(storageKey);
            UUID sessionId = pair.session.getId();
            UUID allocId = pair.allocation.getId();

            when(multipartStoragePort.abortMultipartUpload(any(), any()))
                    .thenThrow(new StorageException("Failed to abort at https://secret-token:pass@b2.com/video.mp4 with key AKIAEXAMPLE and header Authorization: Bearer eyJhbGciOi"));

            MediaUploadSession result = sessionService.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);

            Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                    "SELECT cleanup_status, cleanup_error_detail, status FROM media_upload_sessions WHERE id = ?",
                    sessionId.toString()
            );
            String errorDetail = (String) dbRow.get("cleanup_error_detail");

            assertThat(dbRow.get("cleanup_status")).isEqualTo("QUARANTINED");
            assertThat(dbRow.get("status")).isEqualTo("ABORTED");
            assertThat(dbRow.get("status")).isNotEqualTo("CLEANED_UP");

            // Diagnostic contains safe exception classification, not raw message
            assertThat(errorDetail)
                    .contains("StorageException")
                    .contains("GENERAL_PROVIDER_ERROR")
                    .contains("physical cleanup outcome unresolved, pending reconciliation (S2D-3)");

            // Diagnostic length satisfies schema limit
            assertThat(errorDetail.length()).isLessThanOrEqualTo(1000);

            // Strict secrecy assertions: raw credentials, tokens, and sensitive URL fragments must never be persisted
            assertThat(errorDetail).doesNotContain("https://");
            assertThat(errorDetail).doesNotContain("secret-token");
            assertThat(errorDetail).doesNotContain("pass@");
            assertThat(errorDetail).doesNotContain("b2.com");
            assertThat(errorDetail).doesNotContain("AKIAEXAMPLE");
            assertThat(errorDetail).doesNotContain("Authorization");
            assertThat(errorDetail).doesNotContain("Bearer");
            assertThat(errorDetail).doesNotContain("eyJhbGci");
            assertThat(errorDetail).doesNotContain("Failed to abort");

            Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                    "SELECT allocation_status, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                    allocId.toString()
            );
            assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");
            assertThat(allocRow.get("allocated_by_id")).isEqualTo(sessionId.toString());

            verify(multipartStoragePort, times(1)).abortMultipartUpload(any(), any());
        }
    }
}
