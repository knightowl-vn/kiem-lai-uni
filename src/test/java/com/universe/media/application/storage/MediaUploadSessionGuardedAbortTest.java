package com.universe.media.application.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartAbortResult;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused deterministic unit test suite for MS-08E2 / S2D-2B2A:
 * Guarded Single-Session Abort Orchestration in {@link MediaUploadSessionService}.
 * <p>
 * Tests:
 * <ul>
 *     <li>Local eligibility predicates and completion marker rejections</li>
 *     <li>Transaction boundaries (TX-A claim, provider call outside TX, TX-B conservative quarantine)</li>
 *     <li>Provider results classification (ABORT_ACCEPTED, UPLOAD_ALREADY_ABSENT, errors -> QUARANTINED)</li>
 *     <li>Fencing and lease expiration fail-closed safety</li>
 *     <li>Strict negative safety invariants (no CLEANED_UP, no markCleanupCompleted, no allocation mutations)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MS-08E2 / S2D-2B2A: Guarded Single-Session Abort Orchestration Tests")
class MediaUploadSessionGuardedAbortTest {

    @Mock
    private MediaUploadSessionRepositoryPort sessionRepositoryPort;

    @Mock
    private StorageKeyAllocationService storageKeyAllocationService;

    @Mock
    private MediaStorageRoutingService routingService;

    @Mock
    private MultipartStoragePort multipartStoragePort;

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    private AtomicReference<Instant> currentTime;
    private ClockPort clockPort;
    private PlatformTransactionManager transactionManager;
    private MediaUploadSessionService service;

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/ms08-abort.mp4");
    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");
    private static final String PROVIDER_UPLOAD_ID = "b2-upload-abort-12345";
    private static final String VALID_HASH = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @BeforeEach
    void setUp() {
        currentTime = new AtomicReference<>(T0);
        clockPort = currentTime::get;
        transactionManager = createTestTransactionManager();
        service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                multipartStoragePort,
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );
        lenient().when(multipartStoragePort.providerId()).thenReturn(B2_PROVIDER);
        lenient().when(multipartStoragePort.configuredBucketName()).thenReturn(B2_BUCKET);
        lenient().when(routingService.resolveBucketName(any())).thenReturn(B2_BUCKET);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private PlatformTransactionManager createTestTransactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected boolean isExistingTransaction(Object transaction) {
                return TransactionSynchronizationManager.isActualTransactionActive();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                TransactionSynchronizationManager.setActualTransactionActive(true);
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        };
    }

    // =============================================================================================
    // Helper Factories
    // =============================================================================================

    private MediaUploadSession buildSession(
            UUID sessionId,
            UUID allocationId,
            MediaUploadSessionStatus status,
            UploadSessionCleanupStatus cleanupStatus,
            String providerUploadId,
            String leaseOwner,
            Instant leaseExpiresAt,
            String declaredContentHash,
            String manifestHash,
            UUID registeredAssetId,
            Instant completedAt,
            StorageProviderId providerId,
            String bucketName,
            StorageKey key
    ) {
        String effectiveContentHash = declaredContentHash;
        UUID effectiveRegisteredAssetId = registeredAssetId;
        Instant effectiveCompletedAt = completedAt;
        if (status == MediaUploadSessionStatus.COMPLETED) {
            if (effectiveContentHash == null) {
                effectiveContentHash = VALID_HASH;
            }
            if (effectiveRegisteredAssetId == null) {
                effectiveRegisteredAssetId = UUID.randomUUID();
            }
            if (effectiveCompletedAt == null) {
                effectiveCompletedAt = T0;
            }
        }

        return MediaUploadSession.rehydrate(
                sessionId,
                "user-principal-abort",
                "screen",
                "ScreenEntry",
                UUID.randomUUID(),
                "idem-" + UUID.randomUUID(),
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                providerId != null ? providerId : B2_PROVIDER,
                bucketName != null ? bucketName : B2_BUCKET,
                key != null ? key : STORAGE_KEY,
                allocationId,
                providerUploadId,
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                effectiveContentHash,
                "test-video.mp4",
                "screen-tag",
                status,
                T0.plus(24, ChronoUnit.HOURS),
                leaseOwner,
                leaseExpiresAt,
                effectiveRegisteredAssetId,
                cleanupStatus,
                0,
                null,
                null,
                T0,
                T0,
                effectiveCompletedAt,
                1L,
                manifestHash
        );
    }

    private MediaUploadSession createEligibleAbortedSession(UUID sessionId, UUID allocationId) {
        return buildSession(
                sessionId,
                allocationId,
                MediaUploadSessionStatus.ABORTED,
                UploadSessionCleanupStatus.PENDING,
                PROVIDER_UPLOAD_ID,
                null,
                null,
                null,
                null,
                null,
                null,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY
        );
    }

    private MediaUploadSession createEligibleExpiredSession(UUID sessionId, UUID allocationId) {
        return buildSession(
                sessionId,
                allocationId,
                MediaUploadSessionStatus.EXPIRED,
                UploadSessionCleanupStatus.PENDING,
                PROVIDER_UPLOAD_ID,
                null,
                null,
                null,
                null,
                null,
                null,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY
        );
    }

    private StorageKeyAllocation createReservedAllocation(UUID allocationId, UUID sessionId) {
        return StorageKeyAllocation.reserve(
                allocationId,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                T0
        );
    }

    private StorageKeyAllocation createTombstoneAllocation(UUID allocationId, UUID sessionId) {
        StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);
        allocation.tombstone(T0);
        return allocation;
    }

    // =============================================================================================
    // 1. Local Eligibility Tests
    // =============================================================================================

    @Nested
    @DisplayName("1. Local Eligibility & Validation")
    class LocalEligibilityTests {

        @Test
        @DisplayName("Eligible ABORTED + PENDING with RESERVED allocation executes successfully to QUARANTINED")
        void eligibleAbortedSessionSucceeds() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            assertThat(result.getLeaseOwner()).isNull();
            assertThat(result.getLeaseExpiresAt()).isNull();
            assertThat(result.getCleanupErrorDetail()).contains("Provider abort acknowledged; physical cleanup verification pending (S2D-3)");
        }

        @Test
        @DisplayName("Eligible EXPIRED + PENDING with TOMBSTONE allocation executes successfully to QUARANTINED")
        void eligibleExpiredSessionWithTombstoneAllocationSucceeds() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleExpiredSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createTombstoneAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.EXPIRED);
            assertThat(result.getLeaseOwner()).isNull();
        }

        @ParameterizedTest
        @EnumSource(value = MediaUploadSessionStatus.class, names = {"ALLOCATED", "INITIATED", "COMPLETING", "COMPLETED", "FAILED", "CLEANED_UP"})
        @DisplayName("Rejects ineligible lifecycle statuses")
        void rejectsIneligibleLifecycleStatuses(MediaUploadSessionStatus status) {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = buildSession(
                    sessionId, allocationId, status, UploadSessionCleanupStatus.PENDING,
                    PROVIDER_UPLOAD_ID, null, null, null, null, null, null,
                    B2_PROVIDER, B2_BUCKET, STORAGE_KEY
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lifecycle status must be ABORTED or EXPIRED");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @ParameterizedTest
        @EnumSource(value = UploadSessionCleanupStatus.class, names = {"NONE", "IN_PROGRESS", "COMPLETED", "FAILED", "QUARANTINED"})
        @DisplayName("Rejects non-PENDING cleanup statuses (including FAILED, QUARANTINED, IN_PROGRESS)")
        void rejectsNonPendingCleanupStatuses(UploadSessionCleanupStatus cleanupStatus) {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = buildSession(
                    sessionId, allocationId, MediaUploadSessionStatus.ABORTED, cleanupStatus,
                    PROVIDER_UPLOAD_ID, null, null, null, null, null, null,
                    B2_PROVIDER, B2_BUCKET, STORAGE_KEY
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cleanup status must be PENDING");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects session with declaredContentHash marker")
        void rejectsDeclaredContentHash() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = buildSession(
                    sessionId, allocationId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING,
                    PROVIDER_UPLOAD_ID, null, null, VALID_HASH, null, null, null,
                    B2_PROVIDER, B2_BUCKET, STORAGE_KEY
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("declaredContentHash is present");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects session with manifestHash marker")
        void rejectsManifestHash() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = buildSession(
                    sessionId, allocationId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING,
                    PROVIDER_UPLOAD_ID, null, null, null, VALID_HASH, null, null,
                    B2_PROVIDER, B2_BUCKET, STORAGE_KEY
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("manifestHash is present");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects session with registeredAssetId marker")
        void rejectsRegisteredAssetId() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = buildSession(
                    sessionId, allocationId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING,
                    PROVIDER_UPLOAD_ID, null, null, null, null, UUID.randomUUID(), null,
                    B2_PROVIDER, B2_BUCKET, STORAGE_KEY
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("registeredAssetId is present");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects session with completedAt marker")
        void rejectsCompletedAt() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = buildSession(
                    sessionId, allocationId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING,
                    PROVIDER_UPLOAD_ID, null, null, null, null, null, T0,
                    B2_PROVIDER, B2_BUCKET, STORAGE_KEY
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completedAt is present");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects session with active lease (leaseOwner != null)")
        void rejectsActiveLeaseOwner() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = buildSession(
                    sessionId, allocationId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING,
                    PROVIDER_UPLOAD_ID, "OTHER_CLAIM", T0.plus(5, ChronoUnit.MINUTES), null, null, null, null,
                    B2_PROVIDER, B2_BUCKET, STORAGE_KEY
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unresolved lease");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects session with expired unresolved lease (leaseExpiresAt in past)")
        void rejectsExpiredUnresolvedLease() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = buildSession(
                    sessionId, allocationId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING,
                    PROVIDER_UPLOAD_ID, null, T0.minus(10, ChronoUnit.MINUTES), null, null, null, null,
                    B2_PROVIDER, B2_BUCKET, STORAGE_KEY
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unresolved lease");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects missing or blank providerUploadId")
        void rejectsMissingProviderUploadId() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = buildSession(
                    sessionId, allocationId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING,
                    null, null, null, null, null, null, null,
                    B2_PROVIDER, B2_BUCKET, STORAGE_KEY
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("providerUploadId is missing or blank");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects non-b2 storage provider")
        void rejectsNonB2StorageProvider() {
            UUID sessionId = UUID.randomUUID();
            MediaUploadSession session = mock(MediaUploadSession.class);
            when(session.getStatus()).thenReturn(MediaUploadSessionStatus.ABORTED);
            when(session.getCleanupStatus()).thenReturn(UploadSessionCleanupStatus.PENDING);
            when(session.getProviderUploadId()).thenReturn(PROVIDER_UPLOAD_ID);
            when(session.getStorageProviderId()).thenReturn(StorageProviderId.of("s3"));

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Storage provider mismatch");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects sentinel bucket name")
        void rejectsSentinelBucketName() {
            UUID sessionId = UUID.randomUUID();
            MediaUploadSession session = mock(MediaUploadSession.class);
            when(session.getStatus()).thenReturn(MediaUploadSessionStatus.ABORTED);
            when(session.getCleanupStatus()).thenReturn(UploadSessionCleanupStatus.PENDING);
            when(session.getProviderUploadId()).thenReturn(PROVIDER_UPLOAD_ID);
            when(session.getStorageProviderId()).thenReturn(B2_PROVIDER);
            when(session.getStorageBucketName()).thenReturn(StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("authentic non-sentinel bucket name");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects when MultipartStoragePort provider does not match session provider")
        void rejectsMultipartStoragePortProviderMismatch() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(multipartStoragePort.providerId()).thenReturn(StorageProviderId.of("s3"));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MultipartStoragePort provider 's3' does not match session storage provider 'b2'");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects when session bucket does not match authoritative routing service bucket")
        void rejectsSessionBucketMismatchWithRoutingService() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = buildSession(
                    sessionId, allocationId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING,
                    PROVIDER_UPLOAD_ID, null, null, null, null, null, null,
                    B2_PROVIDER, "rogue-bucket", STORAGE_KEY
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn(B2_BUCKET);

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bucket 'rogue-bucket' does not match authoritative configured provider bucket 'kiemlai-video'");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects when allocation bucket does not match authoritative configured bucket")
        void rejectsAllocationBucketMismatchWithConfiguredBucket() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                    allocationId, B2_PROVIDER, "other-bucket", STORAGE_KEY,
                    StorageKeyAllocationType.UPLOAD_SESSION, sessionId, T0
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not match authoritative configured bucket 'kiemlai-video'");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects when allocation provider does not match session provider")
        void rejectsAllocationProviderMismatchWithSessionProvider() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                    allocationId, StorageProviderId.of("s3"), B2_BUCKET, STORAGE_KEY,
                    StorageKeyAllocationType.UPLOAD_SESSION, sessionId, T0
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not match session provider 'b2'");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects when MultipartStoragePort configured bucket is null")
        void rejectsNullAdapterConfiguredBucketName() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(multipartStoragePort.configuredBucketName()).thenReturn(null);

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("has null or blank configured bucket name");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects when MultipartStoragePort configured bucket is blank")
        void rejectsBlankAdapterConfiguredBucketName() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(multipartStoragePort.configuredBucketName()).thenReturn("   ");

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("has null or blank configured bucket name");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects when MultipartStoragePort configured bucket does not match authoritative routing bucket")
        void rejectsAdapterBucketMismatchWithRoutingBucket() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(multipartStoragePort.configuredBucketName()).thenReturn("rogue-adapter-bucket");

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("configured bucket 'rogue-adapter-bucket' does not match authoritative routing bucket 'kiemlai-video'");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects when session and allocation agree with each other but differ from adapter bucket")
        void rejectsSessionAndAllocationAgreeingWithEachOtherButDifferingFromAdapterBucket() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = buildSession(
                    sessionId, allocationId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.PENDING,
                    PROVIDER_UPLOAD_ID, null, null, null, null, null, null,
                    B2_PROVIDER, "other-bucket", STORAGE_KEY
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn("other-bucket");
            when(multipartStoragePort.configuredBucketName()).thenReturn(B2_BUCKET);

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("configured bucket 'kiemlai-video' does not match authoritative routing bucket 'other-bucket'");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects when allocation is not found")
        void rejectsAllocationNotFound() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Storage key allocation not found for session");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects when allocation is COMMITTED (must be RESERVED or TOMBSTONE)")
        void rejectsCommittedAllocation() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);
            allocation.commit(StorageKeyAllocationType.ASSET_VERSION, UUID.randomUUID(), T0);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("status must be RESERVED or TOMBSTONE (found: COMMITTED)");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects when allocation owner ID does not match session ID")
        void rejectsAllocationOwnerMismatch() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            UUID otherOwner = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                    allocationId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY,
                    StorageKeyAllocationType.UPLOAD_SESSION, otherOwner, T0
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not match upload session");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Rejects non-existent session with IllegalArgumentException")
        void rejectsNonExistentSession() {
            UUID sessionId = UUID.randomUUID();
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Upload session not found for ID");
        }

        @Test
        @DisplayName("Rejects null session ID with NullPointerException")
        void rejectsNullSessionId() {
            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =============================================================================================
    // 2. Transaction Boundaries Tests
    // =============================================================================================

    @Nested
    @DisplayName("2. Transaction Boundaries & Ordering")
    class TransactionBoundaryTests {

        @Test
        @DisplayName("TX-A claim is persisted before remote provider abort call")
        void txAClaimPersistedBeforeProviderCall() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));

            AtomicBoolean claimPersistedBeforeProvider = new AtomicBoolean(false);
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> {
                MediaUploadSession s = inv.getArgument(0);
                if (s.getCleanupStatus() == UploadSessionCleanupStatus.IN_PROGRESS) {
                    claimPersistedBeforeProvider.set(true);
                }
                return s;
            });

            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID)).thenAnswer(inv -> {
                assertThat(claimPersistedBeforeProvider.get())
                        .as("TX-A claim must be persisted before provider call")
                        .isTrue();
                return MultipartAbortResult.ABORT_ACCEPTED;
            });

            service.executeGuardedMultipartAbort(sessionId);

            verify(sessionRepositoryPort, times(2)).save(any(MediaUploadSession.class));
            verify(multipartStoragePort, times(1)).abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID);
        }

        @Test
        @DisplayName("Provider call executes strictly outside a database transaction")
        void providerExecutesOutsideTransaction() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            AtomicBoolean wasTransactionActiveDuringProviderCall = new AtomicBoolean(true);
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID)).thenAnswer(inv -> {
                wasTransactionActiveDuringProviderCall.set(TransactionSynchronizationManager.isActualTransactionActive());
                return MultipartAbortResult.ABORT_ACCEPTED;
            });

            service.executeGuardedMultipartAbort(sessionId);

            assertThat(wasTransactionActiveDuringProviderCall.get())
                    .as("Provider abort must not run inside a database transaction")
                    .isFalse();
        }

        @Test
        @DisplayName("Concurrent claim collision in TX-A fails closed (OptimisticLockingFailureException)")
        void concurrentClaimCollisionFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class)))
                    .thenThrow(new OptimisticLockingFailureException("Session was concurrently modified"));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(OptimisticLockingFailureException.class);

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Direct ambient transaction is rejected by programmatic check")
        void directAmbientTransactionRejected() {
            UUID sessionId = UUID.randomUUID();
            TransactionSynchronizationManager.setActualTransactionActive(true);

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be invoked from an active ambient transaction");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("Spring AOP proxy with active transaction throws IllegalTransactionStateException via Propagation.NEVER")
        void proxiedAmbientTransactionThrowsIllegalTransactionStateException() {
            ProxyFactory proxyFactory = new ProxyFactory(service);
            AnnotationTransactionAttributeSource tas = new AnnotationTransactionAttributeSource();
            TransactionInterceptor ti = new TransactionInterceptor(transactionManager, tas);
            proxyFactory.addAdvice(ti);
            MediaUploadSessionService proxiedService = (MediaUploadSessionService) proxyFactory.getProxy();

            TransactionSynchronizationManager.setActualTransactionActive(true);

            UUID sessionId = UUID.randomUUID();
            assertThatThrownBy(() -> proxiedService.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @DisplayName("Provider call cannot run when TX-A fails")
        void providerCannotRunWhenTxAFails() {
            UUID sessionId = UUID.randomUUID();
            when(sessionRepositoryPort.findById(sessionId)).thenThrow(new RuntimeException("Database error in TX-A"));

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Database error in TX-A");

            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("TX-B failure does not trigger a second provider attempt")
        void txBFailureDoesNotTriggerSecondProviderAttempt() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            AtomicBoolean firstSave = new AtomicBoolean(true);
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> {
                if (firstSave.compareAndSet(true, false)) {
                    return inv.getArgument(0); // TX-A succeeds
                }
                throw new OptimisticLockingFailureException("Concurrent conflict in TX-B");
            });

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(OptimisticLockingFailureException.class);

            verify(multipartStoragePort, times(1)).abortMultipartUpload(any(), any());
        }
    }

    // =============================================================================================
    // 3. Provider Results & Quarantine Classification Tests
    // =============================================================================================

    @Nested
    @DisplayName("3. Provider Results & Quarantine Classification")
    class ProviderResultsTests {

        @Test
        @DisplayName("ABORT_ACCEPTED transitions to QUARANTINED with acknowledgment reason")
        void abortAcceptedTransitionsToQuarantined() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            assertThat(result.getCleanupErrorDetail())
                    .isEqualTo("Provider abort acknowledged; physical cleanup verification pending (S2D-3)");
        }

        @Test
        @DisplayName("UPLOAD_ALREADY_ABSENT transitions to QUARANTINED with history ambiguity reason")
        void uploadAlreadyAbsentTransitionsToQuarantined() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.UPLOAD_ALREADY_ABSENT);

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            assertThat(result.getCleanupErrorDetail())
                    .isEqualTo("Provider reported upload already absent (NoSuchUpload); unresolved upload-history ambiguity pending reconciliation (S2D-3)");
        }

        @Test
        @DisplayName("Provider timeout (within unexpired lease) transitions to QUARANTINED")
        void providerTimeoutTransitionsToQuarantinedWhenLeaseValid() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            // Simulating timeout after 30 seconds (lease is 5 minutes)
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID)).thenAnswer(inv -> {
                currentTime.set(T0.plusSeconds(30));
                throw new StorageException("SocketTimeoutException: Read timed out after 30000ms");
            });

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            assertThat(result.getCleanupErrorDetail())
                    .isEqualTo("Provider multipart abort failed with StorageException (TIMEOUT_UNCERTAINTY); physical cleanup outcome unresolved, pending reconciliation (S2D-3)");
        }

        @Test
        @DisplayName("Provider HTTP 400/403 transitions to QUARANTINED with safe diagnostic")
        void provider4xxTransitionsToQuarantined() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenThrow(new StorageException("B2 403 Forbidden: Bad request credentials"));

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getCleanupErrorDetail())
                    .isEqualTo("Provider multipart abort failed with StorageException (PROVIDER_AUTHENTICATION_OR_ACCESS_DENIED); physical cleanup outcome unresolved, pending reconciliation (S2D-3)");
        }

        @Test
        @DisplayName("Provider HTTP 500/503 transitions to QUARANTINED with safe diagnostic")
        void provider5xxTransitionsToQuarantined() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenThrow(new StorageException("B2 503 Service Unavailable"));

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getCleanupErrorDetail())
                    .isEqualTo("Provider multipart abort failed with StorageException (PROVIDER_SERVICE_UNAVAILABLE); physical cleanup outcome unresolved, pending reconciliation (S2D-3)");
        }

        @Test
        @DisplayName("Sanitizes sensitive information (URLs, credentials, signatures) from exception diagnostic")
        void sanitizesSensitiveInformationFromExceptionDiagnostic() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            String sensitiveUrl = "https://kiemlai-video.s3.us-west-004.backblazeb2.com/media/video/ms08-abort.mp4?uploadId=123&X-Amz-Signature=secret_signature_98765";
            String sensitiveCreds = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261011/us-west-004/s3/aws4_request";
            String leakedMessage = "Call to " + sensitiveUrl + " failed with 403 Forbidden: auth header " + sensitiveCreds;

            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenThrow(new StorageException(leakedMessage));

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            String detail = result.getCleanupErrorDetail();
            assertThat(detail).isEqualTo("Provider multipart abort failed with StorageException (PROVIDER_AUTHENTICATION_OR_ACCESS_DENIED); physical cleanup outcome unresolved, pending reconciliation (S2D-3)");
            assertThat(detail).doesNotContain("https://");
            assertThat(detail).doesNotContain("X-Amz-Signature");
            assertThat(detail).doesNotContain("secret_signature_98765");
            assertThat(detail).doesNotContain("AKIAIOSFODNN7EXAMPLE");
            assertThat(detail).doesNotContain("aws4_request");
            assertThat(detail).doesNotContain("kiemlai-video.s3.us-west-004.backblazeb2.com");
        }

        @Test
        @DisplayName("Provider connection failure transitions to QUARANTINED with CONNECTION_FAILURE category")
        void providerConnectionFailureTransitionsToQuarantined() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenThrow(new StorageException("Connection reset by peer"));

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getCleanupErrorDetail())
                    .isEqualTo("Provider multipart abort failed with StorageException (CONNECTION_FAILURE); physical cleanup outcome unresolved, pending reconciliation (S2D-3)");
        }

        @Test
        @DisplayName("Provider 400 Bad Request transitions to QUARANTINED with PROVIDER_BAD_REQUEST category")
        void providerBadRequestTransitionsToQuarantined() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenThrow(new StorageException("400 Bad Request: InvalidPartOrder"));

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getCleanupErrorDetail())
                    .isEqualTo("Provider multipart abort failed with StorageException (PROVIDER_BAD_REQUEST); physical cleanup outcome unresolved, pending reconciliation (S2D-3)");
        }

        @Test
        @DisplayName("Provider 500 Internal Error transitions to QUARANTINED with PROVIDER_INTERNAL_SERVER_ERROR category")
        void providerInternalServerErrorTransitionsToQuarantined() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenThrow(new StorageException("500 Internal Error: Server encountered unexpected error"));

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(result.getCleanupErrorDetail())
                    .isEqualTo("Provider multipart abort failed with StorageException (PROVIDER_INTERNAL_SERVER_ERROR); physical cleanup outcome unresolved, pending reconciliation (S2D-3)");
        }

        @Test
        @DisplayName("Null provider result fails closed without mutating to QUARANTINED")
        void nullProviderResultFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID)).thenReturn(null);

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Provider abortMultipartUpload returned null or unrecognized result");

            // Only TX-A save occurred; TX-B was never invoked
            verify(sessionRepositoryPort, times(1)).save(any(MediaUploadSession.class));
        }
    }

    // =============================================================================================
    // 4. Fencing & Lease Expiration Tests
    // =============================================================================================

    @Nested
    @DisplayName("4. Fencing & Lease Expiration Safety")
    class FencingTests {

        @Test
        @DisplayName("Lease expiry during provider call fails closed before TX-B without writing QUARANTINED")
        void leaseExpiryDuringProviderCallFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            // Simulating remote provider call taking 6 minutes (lease was 5 minutes)
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID)).thenAnswer(inv -> {
                currentTime.set(T0.plus(6, ChronoUnit.MINUTES));
                return MultipartAbortResult.ABORT_ACCEPTED;
            });

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cleanup lease expired during provider abort call");

            // TX-B must NOT have been called; only TX-A save occurred
            verify(sessionRepositoryPort, times(1)).save(any(MediaUploadSession.class));
        }

        @Test
        @DisplayName("Wrong claim token in TX-B blocks outcome persistence")
        void wrongTokenInTxBBlocksPersistence() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            AtomicBoolean firstFind = new AtomicBoolean(true);
            when(sessionRepositoryPort.findById(sessionId)).thenAnswer(inv -> {
                if (firstFind.compareAndSet(true, false)) {
                    return Optional.of(session);
                }
                // Concurrently, another worker replaced the leaseOwner
                MediaUploadSession modified = buildSession(
                        sessionId, allocationId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.IN_PROGRESS,
                        PROVIDER_UPLOAD_ID, MediaUploadSession.CLEANUP_CLAIM_PREFIX + UUID.randomUUID(),
                        T0.plus(5, ChronoUnit.MINUTES), null, null, null, null,
                        B2_PROVIDER, B2_BUCKET, STORAGE_KEY
                );
                return Optional.of(modified);
            });
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TX-B abort claim token mismatch");

            // Only TX-A save occurred
            verify(sessionRepositoryPort, times(1)).save(any(MediaUploadSession.class));
        }

        @Test
        @DisplayName("Completion evidence introduced before TX-B blocks quarantine mutation")
        void completionEvidenceIntroducedBeforeTxBBlocksMutation() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            AtomicReference<String> activeClaimToken = new AtomicReference<>();
            AtomicBoolean firstFind = new AtomicBoolean(true);

            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> {
                MediaUploadSession s = inv.getArgument(0);
                activeClaimToken.set(s.getLeaseOwner());
                return s;
            });

            when(sessionRepositoryPort.findById(sessionId)).thenAnswer(inv -> {
                if (firstFind.compareAndSet(true, false)) {
                    return Optional.of(session);
                }
                // Completion intent marker was somehow written concurrently
                MediaUploadSession racingSession = buildSession(
                        sessionId, allocationId, MediaUploadSessionStatus.ABORTED, UploadSessionCleanupStatus.IN_PROGRESS,
                        PROVIDER_UPLOAD_ID, activeClaimToken.get(), T0.plus(5, ChronoUnit.MINUTES),
                        VALID_HASH, null, null, null,
                        B2_PROVIDER, B2_BUCKET, STORAGE_KEY
                );
                return Optional.of(racingSession);
            });

            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TX-B abort detected declaredContentHash");

            verify(sessionRepositoryPort, times(1)).save(any(MediaUploadSession.class));
        }

        @Test
        @DisplayName("Allocation status changed before TX-B blocks mutation")
        void allocationChangedBeforeTxBBlocksMutation() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation initialAllocation = createReservedAllocation(allocationId, sessionId);

            AtomicBoolean firstFind = new AtomicBoolean(true);
            when(storageKeyAllocationService.findById(allocationId)).thenAnswer(inv -> {
                if (firstFind.compareAndSet(true, false)) {
                    return Optional.of(initialAllocation);
                }
                // Concurrently committed
                StorageKeyAllocation committed = createReservedAllocation(allocationId, sessionId);
                committed.commit(StorageKeyAllocationType.ASSET_VERSION, UUID.randomUUID(), T0);
                return Optional.of(committed);
            });

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("status must be RESERVED or TOMBSTONE (found: COMMITTED)");

            verify(sessionRepositoryPort, times(1)).save(any(MediaUploadSession.class));
        }

        @Test
        @DisplayName("Allocation bucket drifted before TX-B blocks mutation")
        void allocationBucketDriftBeforeTxBBlocksMutation() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation initialAllocation = createReservedAllocation(allocationId, sessionId);

            AtomicBoolean firstFind = new AtomicBoolean(true);
            when(storageKeyAllocationService.findById(allocationId)).thenAnswer(inv -> {
                if (firstFind.compareAndSet(true, false)) {
                    return Optional.of(initialAllocation);
                }
                // Concurrently bucket drifted
                StorageKeyAllocation drifted = StorageKeyAllocation.reserve(
                        allocationId, B2_PROVIDER, "drifted-bucket", STORAGE_KEY,
                        StorageKeyAllocationType.UPLOAD_SESSION, sessionId, T0
                );
                return Optional.of(drifted);
            });

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not match authoritative configured bucket 'kiemlai-video'");

            verify(sessionRepositoryPort, times(1)).save(any(MediaUploadSession.class));
        }

        @Test
        @DisplayName("Allocation provider drifted before TX-B blocks mutation")
        void allocationProviderDriftBeforeTxBBlocksMutation() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation initialAllocation = createReservedAllocation(allocationId, sessionId);

            AtomicBoolean firstFind = new AtomicBoolean(true);
            when(storageKeyAllocationService.findById(allocationId)).thenAnswer(inv -> {
                if (firstFind.compareAndSet(true, false)) {
                    return Optional.of(initialAllocation);
                }
                // Concurrently provider drifted
                StorageKeyAllocation drifted = StorageKeyAllocation.reserve(
                        allocationId, StorageProviderId.of("s3"), B2_BUCKET, STORAGE_KEY,
                        StorageKeyAllocationType.UPLOAD_SESSION, sessionId, T0
                );
                return Optional.of(drifted);
            });

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not match session provider 'b2'");

            verify(sessionRepositoryPort, times(1)).save(any(MediaUploadSession.class));
        }

        @Test
        @DisplayName("Adapter bucket drifted before TX-B blocks mutation")
        void adapterBucketDriftBeforeTxBBlocksMutation() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            AtomicBoolean firstBucketCall = new AtomicBoolean(true);
            when(multipartStoragePort.configuredBucketName()).thenAnswer(inv -> {
                if (firstBucketCall.compareAndSet(true, false)) {
                    return B2_BUCKET;
                }
                return "drifted-adapter-bucket";
            });

            assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TX-B abort MultipartStoragePort configured bucket 'drifted-adapter-bucket' does not match authoritative routing bucket 'kiemlai-video'");

            verify(sessionRepositoryPort, times(1)).save(any(MediaUploadSession.class));
        }
    }

    // =============================================================================================
    // 5. Negative Safety Invariant Assertions
    // =============================================================================================

    @Nested
    @DisplayName("5. Negative Safety Invariant Assertions")
    class NegativeSafetyAssertionsTests {

        @Test
        @DisplayName("Under NO circumstance does executeGuardedMultipartAbort mark CLEANED_UP")
        void neverMarksCleanedUp() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getStatus())
                    .isNotEqualTo(MediaUploadSessionStatus.CLEANED_UP)
                    .isEqualTo(MediaUploadSessionStatus.ABORTED);
        }

        @Test
        @DisplayName("Under NO circumstance does executeGuardedMultipartAbort mark cleanupStatus COMPLETED")
        void neverMarksCleanupCompleted() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            MediaUploadSession result = service.executeGuardedMultipartAbort(sessionId);

            assertThat(result.getCleanupStatus())
                    .isNotEqualTo(UploadSessionCleanupStatus.COMPLETED)
                    .isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
        }

        @Test
        @DisplayName("Under NO circumstance does executeGuardedMultipartAbort call tombstoneAllocation")
        void neverCallsTombstoneAllocation() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            service.executeGuardedMultipartAbort(sessionId);

            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
            verify(storageKeyAllocationService, never()).tombstoneAllocationInNewTransaction(any());
            verify(storageKeyAllocationService, never()).tombstoneAllocationByLocation(any(), any());
        }

        @Test
        @DisplayName("Under NO circumstance does executeGuardedMultipartAbort call deleteObject")
        void neverCallsDeleteObject() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createEligibleAbortedSession(sessionId, allocationId);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.abortMultipartUpload(STORAGE_KEY, PROVIDER_UPLOAD_ID))
                    .thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

            service.executeGuardedMultipartAbort(sessionId);

            verify(multipartStoragePort, never()).delete(any());
        }
    }
}
