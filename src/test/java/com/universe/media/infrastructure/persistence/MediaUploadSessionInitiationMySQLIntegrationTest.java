package com.universe.media.infrastructure.persistence;

import com.universe.media.application.exceptions.StorageInitiationRejectedException;
import com.universe.media.application.exceptions.UploadSessionInitiationConflictException;
import com.universe.media.application.exceptions.UploadSessionUnconfirmedInitiationException;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.MediaUploadSessionService;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.StorageProviderId;
import com.universe.screen.infrastructure.persistence.ScreenTestDatabaseTargetGuard;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Real Physical MySQL Integration & Concurrency Verification Test Suite for
 * Multipart Initiation Claim Compensation, Lock Contention, and Idempotent Retry (MS-08E3-S4-MYSQL-C1/C2).
 *
 * <p>Scope and Invariant Coverage:
 * <ul>
 *   <li><b>MYSQL-01:</b> Definitive provider rejection compensates a committed claim (status remains ALLOCATED, lease cleared, retryable).</li>
 *   <li><b>MYSQL-02:</b> Provider call occurs outside an active DB transaction with TxA claim committed and visible.</li>
 *   <li><b>MYSQL-03:</b> Two simultaneous initiation requests allow exactly one winner and prevent duplicate provider uploads.</li>
 *   <li><b>MYSQL-04:</b> Expired unconfirmed initiation claim rejects re-initiation without calling provider.</li>
 *   <li><b>MYSQL-05:</b> Same-session retry after confirmed compensation succeeds cleanly with original idempotency semantics.</li>
 *   <li><b>MYSQL-06:</b> Wrong claim ownership cannot release an active initiation claim (TOKEN_MISMATCH yields unconfirmed).</li>
 *   <li><b>MYSQL-07:</b> Status change during provider call prevents compensation release (INVALID_STATUS yields unconfirmed and retains claim).</li>
 *   <li><b>MYSQL-08:</b> Provider success followed by competing lease change prevents TxB persistence without speculative deletion.</li>
 *   <li><b>MYSQL-09:</b> Principal authorization mismatch rejects initiation without modifying MySQL state or invoking provider.</li>
 * </ul>
 *
 * <p>SAFETY STATUS: PREPARED FOR HUMAN EXECUTION IN ISOLATED TEST ENVIRONMENT — NOT EXECUTED DURING AGENT IMPLEMENTATION.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
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
        MediaUploadSessionInitiationMySQLIntegrationTest.TestConfig.class
})
@DisplayName("MediaUploadSession Initiation Compensation & Concurrency Real MySQL Integration Tests")
class MediaUploadSessionInitiationMySQLIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        // Enforce single-pass resolution and strict validation of host, port, dbName, and Flyway pinning
        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                ScreenTestDatabaseTargetGuard.resolveAndValidateTarget();
        target.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }

        @Bean
        public MultipartStoragePort multipartStoragePort() {
            return org.mockito.Mockito.mock(MultipartStoragePort.class, invocation -> {
                if ("providerId".equals(invocation.getMethod().getName())) {
                    return StorageProviderId.of("b2");
                }
                return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
        }
    }

    @Autowired
    private MediaUploadSessionService sessionService;

    @Autowired
    private MediaUploadSessionPersistenceAdapter sessionPersistenceAdapter;

    @Autowired
    private ClockPort clockPort;

    @Autowired
    private MultipartStoragePort multipartStoragePort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final MediaUploadTestLifecycleCoordinator lifecycleCoordinator = new MediaUploadTestLifecycleCoordinator();

    @BeforeEach
    void setUp() {
        lifecycleCoordinator.resetForNewTest(() -> {
            reset(multipartStoragePort);
            org.mockito.Mockito.lenient().when(multipartStoragePort.providerId()).thenReturn(StorageProviderId.of("b2"));
        });
    }

    @AfterEach
    void tearDown() {
        lifecycleCoordinator.executeSafeTeardown(
                () -> reset(multipartStoragePort),
                sessionId -> jdbcTemplate.update("DELETE FROM media_upload_sessions WHERE id = ?", sessionId.toString()),
                allocId -> jdbcTemplate.update("DELETE FROM media_storage_key_allocations WHERE id = ?", allocId.toString())
        );
    }

    @AfterAll
    void afterAll() {
        if (lifecycleCoordinator.isQuarantined()) {
            // Diagnostic reporting: does NOT clear quarantine, does NOT conceal failure
            System.err.println(
                    "MYSQL-C6 CLASS-LEVEL SAFETY QUARANTINE ACTIVE: Worker lifecycle was quarantined. "
                            + "Preserved uncleaned session IDs: " + lifecycleCoordinator.getPreservedUncleanedSessionIds()
                            + ", allocation IDs: " + lifecycleCoordinator.getPreservedUncleanedAllocationIds()
            );
        }
    }

    private MediaUploadSession allocateTestSession(String principalId, String idempotencyKey) {
        MediaUploadSession session = sessionService.allocateSession(
                principalId,
                "screen",
                "ScreenEntry",
                UUID.randomUUID(),
                idempotencyKey,
                52428800L,
                "video.mp4",
                "screen"
        );
        lifecycleCoordinator.registerCreatedSession(session.getId());
        lifecycleCoordinator.registerCreatedAllocation(session.getStorageAllocationId());
        return session;
    }

    // =============================================================================================
    // MYSQL-01: Definitive Rejection Compensates Committed Claim
    // =============================================================================================

    @Test
    @DisplayName("MYSQL-01: Definitive rejection compensates a committed claim (status remains ALLOCATED, lease cleared, retryable)")
    void definitiveRejectionCompensatesCommittedInitiationClaim() {
        String principalId = "actor-init-01-" + UUID.randomUUID();
        String idempotencyKey = "idem-init-01-" + UUID.randomUUID();
        MediaUploadSession session = allocateTestSession(principalId, idempotencyKey);

        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any()))
                .thenThrow(new StorageInitiationRejectedException("Provider definitively rejected initiation [errorCode=MalformedAccessKeyId]"));

        assertThatThrownBy(() -> sessionService.initiateMultipartUpload(session.getId(), principalId))
                .isInstanceOf(StorageInitiationRejectedException.class)
                .hasMessageContaining("MalformedAccessKeyId");

        // Authoritative independent JDBC verification of MySQL row state after compensation transaction commits
        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, lease_owner, lease_expires_at, provider_upload_id, persistence_version FROM media_upload_sessions WHERE id = ?",
                session.getId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo("ALLOCATED");
        assertThat(sessionRow.get("lease_owner")).isNull();
        assertThat(sessionRow.get("lease_expires_at")).isNull();
        assertThat(sessionRow.get("provider_upload_id")).isNull();

        Number version = (Number) sessionRow.get("persistence_version");
        assertThat(version.longValue()).isGreaterThanOrEqualTo(1L);

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                session.getStorageAllocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");
    }

    // =============================================================================================
    // MYSQL-02: Provider Call Occurs Outside Active DB Transaction
    // =============================================================================================

    @Test
    @DisplayName("MYSQL-02: Provider call occurs outside an active DB transaction with TxA claim committed and visible")
    void providerCallOccursOutsideActiveDatabaseTransactionWithCommittedTxAClaim() {
        String principalId = "actor-init-02-" + UUID.randomUUID();
        String idempotencyKey = "idem-init-02-" + UUID.randomUUID();
        MediaUploadSession session = allocateTestSession(principalId, idempotencyKey);

        AtomicBoolean noActiveTxObserved = new AtomicBoolean(false);
        AtomicReference<String> committedLeaseOwnerObserved = new AtomicReference<>();

        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any())).thenAnswer(inv -> {
            // 1. Verify no ambient Spring/JPA transaction is active across provider call
            noActiveTxObserved.set(!TransactionSynchronizationManager.isActualTransactionActive());

            // 2. Query MySQL through independent connection; TxA claim MUST be committed and visible
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT status, lease_owner, lease_expires_at FROM media_upload_sessions WHERE id = ?",
                    session.getId().toString()
            );
            committedLeaseOwnerObserved.set((String) row.get("lease_owner"));
            return "b2-provider-upload-outside-tx-" + UUID.randomUUID();
        });

        MediaUploadSession result = sessionService.initiateMultipartUpload(session.getId(), principalId);

        assertThat(noActiveTxObserved.get()).isTrue();
        assertThat(committedLeaseOwnerObserved.get()).isNotNull().startsWith("INITIATION_CLAIM:");
        assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
        assertThat(result.getProviderUploadId()).isNotNull();

        // Verify final row state in MySQL
        Map<String, Object> finalRow = jdbcTemplate.queryForMap(
                "SELECT status, lease_owner, provider_upload_id FROM media_upload_sessions WHERE id = ?",
                session.getId().toString()
        );
        assertThat(finalRow.get("status")).isEqualTo("INITIATED");
        assertThat(finalRow.get("lease_owner")).isNull();
        assertThat(finalRow.get("provider_upload_id")).isEqualTo(result.getProviderUploadId());
    }

    // =============================================================================================
    // MYSQL-03: Two Simultaneous Initiation Requests
    // =============================================================================================

    @Test
    @DisplayName("MYSQL-03: Two simultaneous initiation requests allow exactly one winner and prevent duplicate provider uploads")
    void simultaneousInitiationRequestsAllowExactlyOneWinnerAndPreventDuplicateUpload() throws Exception {
        String principalId = "actor-init-03-" + UUID.randomUUID();
        String idempotencyKey = "idem-init-03-" + UUID.randomUUID();
        MediaUploadSession session = allocateTestSession(principalId, idempotencyKey);

        CountDownLatch thread1InProviderLatch = new CountDownLatch(1);
        CountDownLatch thread2AttemptedLatch = new CountDownLatch(1);
        AtomicReference<Throwable> thread2Error = new AtomicReference<>();
        String uploadId = "b2-upload-single-winner-" + UUID.randomUUID();

        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any())).thenAnswer(inv -> {
            thread1InProviderLatch.countDown();
            // Bounded wait for competing Thread 2 to encounter the active claim
            boolean awaited = thread2AttemptedLatch.await(5, TimeUnit.SECONDS);
            assertThat(awaited).isTrue();
            return uploadId;
        });

        List<String> workerNames = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private final AtomicInteger threadIndex = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                String name = "mysql-03-worker-" + threadIndex.getAndIncrement();
                workerNames.add(name);
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        });
        List<Future<?>> activeFutures = new CopyOnWriteArrayList<>();
        lifecycleCoordinator.markWorkerStarted();
        try {
            // Thread 1: Winning initiation request
            Future<MediaUploadSession> future1 = executor.submit(() ->
                    sessionService.initiateMultipartUpload(session.getId(), principalId)
            );
            activeFutures.add(future1);

            // Thread 2: Competing initiation request for the same session ID
            Future<?> future2 = executor.submit(() -> {
                try {
                    boolean thread1Entered = thread1InProviderLatch.await(5, TimeUnit.SECONDS);
                    if (!thread1Entered) {
                        throw new IllegalStateException("Timed out waiting for Thread 1 to enter provider call");
                    }
                    sessionService.initiateMultipartUpload(session.getId(), principalId);
                } catch (Throwable t) {
                    thread2Error.set(t);
                } finally {
                    thread2AttemptedLatch.countDown();
                }
            });
            activeFutures.add(future2);

            future2.get(8, TimeUnit.SECONDS);
            MediaUploadSession winningSession = future1.get(8, TimeUnit.SECONDS);

            // 1. Thread 2 was rejected due to concurrent initiation lease
            assertThat(thread2Error.get())
                    .isInstanceOf(UploadSessionInitiationConflictException.class);
            UploadSessionInitiationConflictException conflictEx =
                    (UploadSessionInitiationConflictException) thread2Error.get();
            assertThat(conflictEx.getErrorCode()).isEqualTo("MEDIA_UPLOAD_INITIATION_IN_PROGRESS");

            // 2. Exactly one remote multipart upload was created
            verify(multipartStoragePort, times(1)).initiateMultipartUpload(any(), any(), any());

            // 3. Winning thread transitioned session to INITIATED in MySQL
            assertThat(winningSession.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                    "SELECT status, lease_owner, provider_upload_id FROM media_upload_sessions WHERE id = ?",
                    session.getId().toString()
            );
            assertThat(sessionRow.get("status")).isEqualTo("INITIATED");
            assertThat(sessionRow.get("lease_owner")).isNull();
            assertThat(sessionRow.get("provider_upload_id")).isEqualTo(winningSession.getProviderUploadId());
        } finally {
            for (Future<?> f : activeFutures) {
                if (!f.isDone()) {
                    f.cancel(true);
                }
            }
            executor.shutdownNow();
            boolean terminated = false;
            try {
                terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                terminated = false;
            }
            if (terminated) {
                lifecycleCoordinator.markWorkerSettled();
            } else {
                lifecycleCoordinator.markWorkerQuarantined();
                throw new AssertionError(
                        "MYSQL-03 SAFETY QUARANTINE: Worker threads " + workerNames
                                + " failed to terminate within 5 seconds after shutdownNow(). "
                                + "Later tests must not reset the shared mock or delete test-owned fixtures."
                );
            }
        }
    }

    // =============================================================================================
    // MYSQL-04: Expired Unconfirmed Initiation Claim
    // =============================================================================================

    @Test
    @DisplayName("MYSQL-04: Expired unconfirmed initiation claim rejects re-initiation without calling provider")
    void expiredUnconfirmedInitiationClaimRejectsReinitiationWithoutProviderCall() {
        String principalId = "actor-init-04-" + UUID.randomUUID();
        String idempotencyKey = "idem-init-04-" + UUID.randomUUID();
        MediaUploadSession session = allocateTestSession(principalId, idempotencyKey);

        // Manually arrange a historical unconfirmed initiation claim in MySQL whose lease expired 10 minutes ago.
        // UTC_TIMESTAMP(6) guarantees the timestamp is written in UTC matching serverTimezone=UTC and Instant::now.
        String historicalClaimToken = "INITIATION_CLAIM:historical-worker-" + UUID.randomUUID();
        int updatedRows = jdbcTemplate.update(
                "UPDATE media_upload_sessions SET lease_owner = ?, lease_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE) WHERE id = ?",
                historicalClaimToken, session.getId().toString()
        );
        assertThat(updatedRows).isEqualTo(1);

        // Verify the persisted expiration through the same JPA mapping used by the service
        MediaUploadSession persistedSession = sessionPersistenceAdapter.findById(session.getId())
                .orElseThrow(() -> new AssertionError("Session not found after fixture update: " + session.getId()));
        Instant now = clockPort.now();
        assertThat(persistedSession.getLeaseExpiresAt())
                .as("Persisted lease_expires_at must be strictly earlier than application clock")
                .isNotNull()
                .isBefore(now);
        assertThat(persistedSession.getLeaseOwner())
                .as("Persisted lease_owner must match historical claim token")
                .isEqualTo(historicalClaimToken);
        assertThat(persistedSession.getStatus())
                .as("Persisted status must remain ALLOCATED")
                .isEqualTo(MediaUploadSessionStatus.ALLOCATED);
        assertThat(persistedSession.hasActiveInitiationLease(now))
                .as("Lease must not be active")
                .isFalse();
        assertThat(persistedSession.hasUnconfirmedInitiationClaim(now))
                .as("Unconfirmed initiation claim must be detected")
                .isTrue();

        assertThatThrownBy(() -> sessionService.initiateMultipartUpload(session.getId(), principalId))
                .isInstanceOf(UploadSessionUnconfirmedInitiationException.class)
                .hasMessageContaining("unconfirmed earlier initiation claim");

        // Verify provider was NEVER called
        verify(multipartStoragePort, never()).initiateMultipartUpload(any(), any(), any());

        // Verify row remains ALLOCATED and historical claim owner is preserved for reconciliation
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, lease_owner, provider_upload_id FROM media_upload_sessions WHERE id = ?",
                session.getId().toString()
        );
        assertThat(row.get("status")).isEqualTo("ALLOCATED");
        assertThat(row.get("lease_owner")).isEqualTo(historicalClaimToken);
        assertThat(row.get("provider_upload_id")).isNull();
    }

    // =============================================================================================
    // MYSQL-05: Same-Session Retry After Confirmed Compensation
    // =============================================================================================

    @Test
    @DisplayName("MYSQL-05: Same-session retry after confirmed compensation succeeds cleanly with original idempotency semantics")
    void sameSessionRetryAfterConfirmedCompensationSucceedsCleanly() {
        String principalId = "actor-init-05-" + UUID.randomUUID();
        String idempotencyKey = "idem-init-05-" + UUID.randomUUID();
        MediaUploadSession session = allocateTestSession(principalId, idempotencyKey);

        String successfulUploadId = "b2-upload-retry-success-" + UUID.randomUUID();

        // Sequential stubbing: first attempt rejects definitively, second attempt succeeds
        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any()))
                .thenThrow(new StorageInitiationRejectedException("Provider definitively rejected [errorCode=InvalidAccessKeyId]"))
                .thenReturn(successfulUploadId);

        // 1. First attempt fails with definitive provider rejection
        assertThatThrownBy(() -> sessionService.initiateMultipartUpload(session.getId(), principalId))
                .isInstanceOf(StorageInitiationRejectedException.class)
                .hasMessageContaining("InvalidAccessKeyId");

        // Confirm compensation transaction committed in MySQL
        Map<String, Object> intermediateRow = jdbcTemplate.queryForMap(
                "SELECT status, lease_owner, provider_upload_id, storage_allocation_id, storage_key FROM media_upload_sessions WHERE id = ?",
                session.getId().toString()
        );
        assertThat(intermediateRow.get("status")).isEqualTo("ALLOCATED");
        assertThat(intermediateRow.get("lease_owner")).isNull();
        assertThat(intermediateRow.get("provider_upload_id")).isNull();

        // 2. Second attempt succeeds with new provider upload ID
        MediaUploadSession retryResult = sessionService.initiateMultipartUpload(session.getId(), principalId);

        assertThat(retryResult.getId()).isEqualTo(session.getId());
        assertThat(retryResult.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
        assertThat(retryResult.getProviderUploadId()).isEqualTo(successfulUploadId);
        assertThat(retryResult.getStorageAllocationId()).isEqualTo(session.getStorageAllocationId());
        assertThat(retryResult.getStorageKey()).isEqualTo(session.getStorageKey());
        assertThat(retryResult.getTargetEntityType()).isEqualTo(session.getTargetEntityType());
        assertThat(retryResult.getTargetEntityId()).isEqualTo(session.getTargetEntityId());

        // Assert exactly 1 session row exists in MySQL (no duplicate or bypass rows created)
        Integer sessionRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_upload_sessions WHERE id = ?",
                Integer.class,
                session.getId().toString()
        );
        assertThat(sessionRowCount).isEqualTo(1);

        Integer allocationRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_storage_key_allocations WHERE id = ?",
                Integer.class,
                session.getStorageAllocationId().toString()
        );
        assertThat(allocationRowCount).isEqualTo(1);

        Map<String, Object> finalRow = jdbcTemplate.queryForMap(
                "SELECT status, lease_owner, provider_upload_id, storage_allocation_id, storage_key FROM media_upload_sessions WHERE id = ?",
                session.getId().toString()
        );
        assertThat(finalRow.get("status")).isEqualTo("INITIATED");
        assertThat(finalRow.get("lease_owner")).isNull();
        assertThat(finalRow.get("provider_upload_id")).isEqualTo(successfulUploadId);
        assertThat(finalRow.get("storage_allocation_id")).isEqualTo(session.getStorageAllocationId().toString());
        assertThat(finalRow.get("storage_key")).isEqualTo(session.getStorageKey().toString());

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                session.getStorageAllocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo("RESERVED");

        verify(multipartStoragePort, times(2)).initiateMultipartUpload(any(), any(), any());
    }

    // =============================================================================================
    // MYSQL-06: Wrong Claim Ownership
    // =============================================================================================

    @Test
    @DisplayName("MYSQL-06: Wrong claim ownership cannot release an active initiation claim (TOKEN_MISMATCH yields unconfirmed)")
    void wrongClaimOwnershipCannotReleaseActiveInitiationClaim() {
        String principalId = "actor-init-06-" + UUID.randomUUID();
        String idempotencyKey = "idem-init-06-" + UUID.randomUUID();
        MediaUploadSession session = allocateTestSession(principalId, idempotencyKey);

        String foreignClaimToken = "INITIATION_CLAIM:foreign-worker-" + UUID.randomUUID();

        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any())).thenAnswer(inv -> {
            // While Worker 1 holds its claim and is in the provider call, simulate that lease owner
            // in MySQL was altered by another process
            jdbcTemplate.update(
                    "UPDATE media_upload_sessions SET lease_owner = ? WHERE id = ?",
                    foreignClaimToken, session.getId().toString()
            );
            throw new StorageInitiationRejectedException("Provider definitively rejected (SignatureDoesNotMatch)");
        });

        // Compensation discovers lease owner does not match Worker 1's expected token,
        // returns TOKEN_MISMATCH, and throws UploadSessionUnconfirmedInitiationException
        assertThatThrownBy(() -> sessionService.initiateMultipartUpload(session.getId(), principalId))
                .isInstanceOf(UploadSessionUnconfirmedInitiationException.class)
                .hasMessageContaining("claim compensation could not be applied")
                .hasMessageContaining("TOKEN_MISMATCH");

        // Direct JDBC check: Worker 2's lease was NOT released by Worker 1's compensation
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, lease_owner FROM media_upload_sessions WHERE id = ?",
                session.getId().toString()
        );
        assertThat(row.get("status")).isEqualTo("ALLOCATED");
        assertThat(row.get("lease_owner")).isEqualTo(foreignClaimToken);
    }

    // =============================================================================================
    // MYSQL-07: Competing State Change During Provider Call (INVALID_STATUS)
    // =============================================================================================

    /**
     * MYSQL-07: Verifies fail-closed compensation behavior when MySQL state encounters a competing update
     * during the provider call (e.g. session status changed to ABORTED).
     *
     * <p>Compensation verifies status == ALLOCATED before releasing lease. If status is invalid,
     * outcome is INVALID_STATUS, throwing UploadSessionUnconfirmedInitiationException and retaining
     * claim state for reconciliation.
     *
     * <p><b>Test Seam & Transaction Scope Note:</b>
     * Genuine mid-transaction database rollback (e.g. database disconnect during JPA flush/commit)
     * is classified as <i>NOT YET VERIFIED</i> at the physical MySQL layer because no test seam exists in
     * {@link MediaUploadSessionService} without altering production code. Mock-level rollback handling is
     * authoritatively verified in {@code MediaUploadSessionInitiationRecoveryTest#c2_compensationThrowsPersistenceErrorYieldsUnconfirmedOutcome}
     * and {@code #c4_optimisticLockingConflictDuringCompensationYieldsUnconfirmedOutcome}.
     */
    @Test
    @DisplayName("MYSQL-07: Status change during provider call prevents compensation release (INVALID_STATUS yields unconfirmed and retains claim)")
    void statusChangeDuringProviderCallPreventsCompensationReleaseAndRetainsClaim() {
        String principalId = "actor-init-07-" + UUID.randomUUID();
        String idempotencyKey = "idem-init-07-" + UUID.randomUUID();
        MediaUploadSession session = allocateTestSession(principalId, idempotencyKey);

        AtomicReference<String> workerToken = new AtomicReference<>();

        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any())).thenAnswer(inv -> {
            // Capture TxA committed claim token
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT lease_owner FROM media_upload_sessions WHERE id = ?",
                    session.getId().toString()
            );
            workerToken.set((String) row.get("lease_owner"));

            // Concurrently change status in MySQL (e.g. administrative abort) while provider is executing
            jdbcTemplate.update(
                    "UPDATE media_upload_sessions SET status = 'ABORTED' WHERE id = ?",
                    session.getId().toString()
            );

            throw new StorageInitiationRejectedException("Provider definitively rejected (MalformedAccessKeyId)");
        });

        // Compensation transaction encounters INVALID_STATUS, returns INVALID_STATUS outcome,
        // and throws UploadSessionUnconfirmedInitiationException (preserving claim)
        assertThatThrownBy(() -> sessionService.initiateMultipartUpload(session.getId(), principalId))
                .isInstanceOf(UploadSessionUnconfirmedInitiationException.class)
                .hasMessageContaining("outcome: INVALID_STATUS");

        // Direct JDBC check: Status remains ABORTED; lease_owner was NOT wiped; claim preserved
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, lease_owner, provider_upload_id FROM media_upload_sessions WHERE id = ?",
                session.getId().toString()
        );
        assertThat(row.get("status")).isEqualTo("ABORTED");
        assertThat(row.get("lease_owner")).isEqualTo(workerToken.get());
        assertThat(row.get("provider_upload_id")).isNull();
    }

    // =============================================================================================
    // MYSQL-08: Provider Success Followed by Competing State Modification (TxB Failure)
    // =============================================================================================

    /**
     * MYSQL-08: Verifies fail-closed behavior when remote provider initiation succeeds but TxB persistence
     * fails due to competing lease ownership modification in MySQL.
     *
     * <p>Core Invariants:
     * <ul>
     *   <li>Session is NOT transitioned to INITIATED (status remains ALLOCATED).</li>
     *   <li>providerUploadId is NOT committed to MySQL.</li>
     *   <li>Storage provider multipart upload is NOT speculatively aborted.</li>
     *   <li>State is preserved fail-closed for reconciliation.</li>
     * </ul>
     *
     * <p><b>Test Seam & Transaction Scope Note:</b>
     * Genuine Hibernate {@code OptimisticLockingFailureException} at the MySQL persistence layer is verified in
     * {@link MediaUploadSessionPersistenceIntegrationTest#shouldDetectOptimisticLockingFailure}.
     * Mock-level TxB persistence failure handling is verified in {@code MediaUploadSessionInitiationRecoveryTest#t3_providerSuccessFollowedByPersistenceFailureBlocksDuplicateOnRetry}.
     * In this integration test, TxB failure is triggered via competing lease ownership modification in MySQL.
     */
    @Test
    @DisplayName("MYSQL-08: Provider success followed by competing lease change prevents TxB persistence without speculative deletion")
    void competingLeaseChangeBeforeTxBPreventsPersistenceWithoutSpeculativeDeletion() {
        String principalId = "actor-init-08-" + UUID.randomUUID();
        String idempotencyKey = "idem-init-08-" + UUID.randomUUID();
        MediaUploadSession session = allocateTestSession(principalId, idempotencyKey);

        String remoteUploadId = "b2-upload-txb-competing-" + UUID.randomUUID();
        String foreignClaimToken = "INITIATION_CLAIM:foreign-worker-" + UUID.randomUUID();

        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any())).thenAnswer(inv -> {
            // While in provider call, simulate competing lease ownership modification in MySQL
            jdbcTemplate.update(
                    "UPDATE media_upload_sessions SET lease_owner = ? WHERE id = ?",
                    foreignClaimToken, session.getId().toString()
            );
            return remoteUploadId;
        });

        // TxB markInitiated detects claim token mismatch and throws IllegalStateException
        assertThatThrownBy(() -> sessionService.initiateMultipartUpload(session.getId(), principalId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Initiation claim token mismatch");

        // Assert fail-closed invariants in MySQL:
        // 1. Session is NOT marked INITIATED (status remains ALLOCATED)
        // 2. provider_upload_id is NOT committed (remains null)
        // 3. MySQL row preserves competing lease state
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, lease_owner, provider_upload_id FROM media_upload_sessions WHERE id = ?",
                session.getId().toString()
        );
        assertThat(row.get("status")).isEqualTo("ALLOCATED");
        assertThat(row.get("lease_owner")).isEqualTo(foreignClaimToken);
        assertThat(row.get("provider_upload_id")).isNull();

        // 4. Remote provider upload was NOT speculatively aborted (never call abortMultipartUpload)
        verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
    }

    // =============================================================================================
    // MYSQL-09: Authorization and Principal Boundaries
    // =============================================================================================

    @Test
    @DisplayName("MYSQL-09: Principal authorization mismatch rejects initiation without modifying MySQL state or invoking provider")
    void principalMismatchRejectsInitiationWithoutModifyingDatabaseOrInvokingProvider() {
        String initiatorPrincipal = "actor-alice-" + UUID.randomUUID();
        String unauthorizedPrincipal = "actor-bob-" + UUID.randomUUID();
        String idempotencyKey = "idem-init-09-" + UUID.randomUUID();
        MediaUploadSession session = allocateTestSession(initiatorPrincipal, idempotencyKey);

        assertThatThrownBy(() -> sessionService.initiateMultipartUpload(session.getId(), unauthorizedPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not authorized to initiate upload session");

        // Verify provider was NEVER called
        verify(multipartStoragePort, never()).initiateMultipartUpload(any(), any(), any());

        // Verify MySQL state remains untouched: ALLOCATED, lease_owner is null, provider_upload_id is null
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, lease_owner, provider_upload_id FROM media_upload_sessions WHERE id = ?",
                session.getId().toString()
        );
        assertThat(row.get("status")).isEqualTo("ALLOCATED");
        assertThat(row.get("lease_owner")).isNull();
        assertThat(row.get("provider_upload_id")).isNull();
    }
}
