package com.universe.media.application.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.application.ports.storage.MultipartUploadedPart;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.MimeType;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("S2C-B: Multipart Initiation & Durable Completion-Intent Tests")
class MediaUploadSessionS2cbTest {

    @Mock
    private MediaUploadSessionRepositoryPort sessionRepositoryPort;

    @Mock
    private StorageKeyAllocationService storageKeyAllocationService;

    @Mock
    private MediaStorageRoutingService routingService;

    @Mock
    private MultipartStoragePort multipartStoragePort;

    private ClockPort clockPort;
    private PlatformTransactionManager transactionManager;
    private MediaUploadSessionService service;

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/ms08-test.mp4");
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final String PRINCIPAL = "user-creator-1";
    private static final String CONSUMER = "SCREEN";
    private static final String TARGET_TYPE = "ScreenEntry";

    @BeforeEach
    void setUp() {
        clockPort = () -> NOW;
        transactionManager = createTestTransactionManager();
        org.mockito.Mockito.lenient().when(multipartStoragePort.providerId()).thenReturn(StorageProviderId.of("b2"));
        service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                multipartStoragePort
        );
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

    private MediaUploadSession createAllocatedSession(UUID sessionId, UUID allocationId, UUID targetId, long declaredSize) {
        return MediaUploadSession.create(
                sessionId,
                PRINCIPAL,
                CONSUMER,
                TARGET_TYPE,
                targetId,
                "idem-key-" + sessionId,
                declaredSize,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                allocationId,
                "episode1.mp4",
                null,
                NOW.plus(Duration.ofDays(1)),
                NOW
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
                NOW
        );
    }

    // =============================================================================================
    // Requirement 1: Correct session/allocation metadata forwarded to provider initiation
    // =============================================================================================
    @Nested
    @DisplayName("1. Initiation Metadata Forwarding")
    class MetadataForwardingTests {

        @Test
        @DisplayName("Forwards session-id and allocation-id metadata to MultipartStoragePort.initiateMultipartUpload")
        void forwardsSessionAndAllocationMetadata() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 52428800L); // 50 MiB = 2 parts
            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));
            when(multipartStoragePort.initiateMultipartUpload(eq(STORAGE_KEY), eq(MimeType.of("video/mp4")), any()))
                    .thenReturn("b2-upload-xyz-123");

            MediaUploadSession result = service.initiateMultipartUpload(sessionId, PRINCIPAL);

            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            assertThat(result.getProviderUploadId()).isEqualTo("b2-upload-xyz-123");
            assertThat(result.getLeaseOwner()).isNull();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(multipartStoragePort).initiateMultipartUpload(eq(STORAGE_KEY), eq(MimeType.of("video/mp4")), metadataCaptor.capture());

            Map<String, String> metadata = metadataCaptor.getValue();
            assertThat(metadata)
                    .containsEntry(MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString())
                    .containsEntry(MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString());
        }
    }

    // =============================================================================================
    // Requirement 2: Strict Transaction Boundary Enforcement (Correction 1)
    // =============================================================================================
    @Nested
    @DisplayName("2. Transaction Boundary Verification (Correction 1)")
    class TransactionBoundaryTests {

        @Test
        @DisplayName("Nontransactional caller succeeds cleanly adhering to TX-A -> Provider -> TX-B ordering")
        void nontransactionalCallerSucceeds() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            AtomicBoolean inTransactionDuringProviderCall = new AtomicBoolean(false);
            AtomicInteger saveCountBeforeProvider = new AtomicInteger(0);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> {
                saveCountBeforeProvider.incrementAndGet();
                return inv.getArgument(0);
            });

            when(multipartStoragePort.initiateMultipartUpload(any(), any(), any())).thenAnswer(inv -> {
                inTransactionDuringProviderCall.set(TransactionSynchronizationManager.isActualTransactionActive());
                return "b2-upload-outside-tx";
            });

            MediaUploadSession result = service.initiateMultipartUpload(sessionId, PRINCIPAL);

            // Verified: Provider ran outside any active transaction
            assertThat(inTransactionDuringProviderCall.get()).isFalse();
            // Verified: TX-A saved initiation claim before provider call
            assertThat(saveCountBeforeProvider.get()).isGreaterThanOrEqualTo(2); // TX-A save + TX-B save
            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            assertThat(result.getProviderUploadId()).isEqualTo("b2-upload-outside-tx");
        }

        @Test
        @DisplayName("Spring-proxied transactional caller is rejected with IllegalTransactionStateException (Propagation.NEVER)")
        void springProxiedTransactionalCallerRejected() {
            UUID sessionId = UUID.randomUUID();

            // Set up Spring AOP proxy enforcing @Transactional annotations
            ProxyFactory proxyFactory = new ProxyFactory(service);
            proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
            MediaUploadSessionService proxiedService = (MediaUploadSessionService) proxyFactory.getProxy();

            TransactionTemplate outerTx = new TransactionTemplate(transactionManager);

            // Execute inside an ambient Spring transaction
            assertThatThrownBy(() -> outerTx.execute(status -> {
                proxiedService.initiateMultipartUpload(sessionId, PRINCIPAL);
                return null;
            })).isInstanceOf(IllegalTransactionStateException.class)
               .hasMessageContaining("Existing transaction found for transaction marked with propagation 'never'");

            // Provider must NEVER be called
            verify(multipartStoragePort, never()).initiateMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("Direct invocation bypassing proxy is rejected when ambient transaction is active")
        void directInvocationRejectedWhenAmbientTransactionActive() {
            UUID sessionId = UUID.randomUUID();

            TransactionTemplate outerTx = new TransactionTemplate(transactionManager);

            // Call direct unproxied service bean inside an active transaction
            assertThatThrownBy(() -> outerTx.execute(status -> {
                service.initiateMultipartUpload(sessionId, PRINCIPAL);
                return null;
            })).isInstanceOf(IllegalStateException.class)
               .hasMessageContaining("initiateMultipartUpload cannot be invoked from an active ambient transaction");

            verify(multipartStoragePort, never()).initiateMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("Missing PlatformTransactionManager fails closed")
        void missingTransactionManagerFailsClosed() {
            UUID sessionId = UUID.randomUUID();

            MediaUploadSessionService serviceWithoutTxManager = new MediaUploadSessionService(
                    sessionRepositoryPort,
                    storageKeyAllocationService,
                    routingService,
                    clockPort,
                    null, // null transaction manager
                    multipartStoragePort
            );

            assertThatThrownBy(() -> serviceWithoutTxManager.initiateMultipartUpload(sessionId, PRINCIPAL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PlatformTransactionManager is required for multipart initiation");

            verify(multipartStoragePort, never()).initiateMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("TX-A is committed before provider call and records initiation claim")
        void txACommittedBeforeProviderCall() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            AtomicBoolean txAClaimRecorded = new AtomicBoolean(false);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> {
                MediaUploadSession saved = inv.getArgument(0);
                if (saved.getLeaseOwner() != null && saved.getLeaseOwner().startsWith("INITIATION_CLAIM:")) {
                    txAClaimRecorded.set(true);
                }
                return saved;
            });

            when(multipartStoragePort.initiateMultipartUpload(any(), any(), any())).thenAnswer(inv -> {
                // Assert TX-A already committed its claim before provider is invoked
                assertThat(txAClaimRecorded.get()).isTrue();
                return "b2-upload-verified";
            });

            service.initiateMultipartUpload(sessionId, PRINCIPAL);

            assertThat(txAClaimRecorded.get()).isTrue();
        }
    }

    // =============================================================================================
    // Requirement 3: Concurrent initiation claims
    // =============================================================================================
    @Nested
    @DisplayName("3. Concurrent Initiation Claims")
    class ConcurrentInitiationTests {

        @Test
        @DisplayName("Rejects initiation if another worker currently holds an active initiation lease")
        void rejectsIfActiveLeaseHeld() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.claimInitiation("INITIATION_CLAIM:worker-other", NOW.plus(Duration.ofMinutes(4)), NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Concurrent initiation in progress");

            verify(multipartStoragePort, never()).initiateMultipartUpload(any(), any(), any());
        }
    }

    // =============================================================================================
    // Requirement 4: Provider success followed by TX-B persistence failure
    // =============================================================================================
    @Nested
    @DisplayName("4. Provider Success Followed by TX-B Persistence Failure")
    class TxBFailureTests {

        @Test
        @DisplayName("When provider succeeds but TX-B fails, preserves initiation claim without speculative deletion")
        void preservesClaimWhenTxBFails() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));

            // TX-A save succeeds
            // TX-B save throws OptimisticLockingFailureException
            when(sessionRepositoryPort.save(any(MediaUploadSession.class)))
                    .thenAnswer(inv -> inv.getArgument(0)) // TX-A save
                    .thenThrow(new OptimisticLockingFailureException("Simulated concurrent version conflict in TX-B"));

            when(multipartStoragePort.initiateMultipartUpload(any(), any(), any())).thenReturn("b2-upload-created-999");

            assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                    .isInstanceOf(OptimisticLockingFailureException.class);

            // Invariant: NEVER speculative delete or abort provider upload on ambiguous outcome
            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
        }
    }

    // =============================================================================================
    // Requirement 5: Provider timeout with unknown remote outcome
    // =============================================================================================
    @Nested
    @DisplayName("5. Provider Timeout With Unknown Remote Outcome")
    class ProviderTimeoutTests {

        @Test
        @DisplayName("When provider times out, preserves initiation claim for reconciliation without speculative deletion")
        void preservesStateOnProviderTimeout() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.initiateMultipartUpload(any(), any(), any()))
                    .thenThrow(new StorageException("Remote socket timeout during CreateMultipartUpload"));

            assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("timeout");

            // TX-B was never called (save called only once for TX-A)
            verify(sessionRepositoryPort).save(any(MediaUploadSession.class));
            // No speculative abort
            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        }
    }

    // =============================================================================================
    // Requirement 6: Retry with missing providerUploadId
    // =============================================================================================
    @Nested
    @DisplayName("6. Retry With Missing providerUploadId")
    class RetryMissingUploadIdTests {

        @Test
        @DisplayName("Rejects initiation retry when previous initiation claim exists and providerUploadId is missing, preventing duplicate remote uploads")
        void rejectsRetryWhenPriorClaimExistsAndUploadIdMissing() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            Instant pastCreated = NOW.minus(Duration.ofHours(1));

            MediaUploadSession session = MediaUploadSession.create(
                    sessionId,
                    PRINCIPAL,
                    CONSUMER,
                    TARGET_TYPE,
                    targetId,
                    "idem-key-" + sessionId,
                    26214400L,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    allocId,
                    "episode1.mp4",
                    null,
                    NOW.plus(Duration.ofDays(1)),
                    pastCreated
            );
            session.claimInitiation("INITIATION_CLAIM:worker-crashed", NOW.minus(Duration.ofMinutes(10)), NOW.minus(Duration.ofMinutes(15)));

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unconfirmed earlier initiation claim with missing providerUploadId");

            verify(multipartStoragePort, never()).initiateMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("Idempotent initiation retry succeeds if session is already INITIATED")
        void idempotentSuccessIfAlreadyInitiated() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-upload-existing-123", NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            MediaUploadSession result = service.initiateMultipartUpload(sessionId, PRINCIPAL);

            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            assertThat(result.getProviderUploadId()).isEqualTo("b2-upload-existing-123");
            verify(multipartStoragePort, never()).initiateMultipartUpload(any(), any(), any());
        }
    }

    // =============================================================================================
    // Requirement 7 & 8: Identical manifest retry vs Different-manifest conflict
    // =============================================================================================
    @Nested
    @DisplayName("7 & 8. Completion Intent Retries and Manifest Conflicts")
    class CompletionIntentRetryTests {

        @Test
        @DisplayName("Identical manifest retry succeeds idempotently on COMPLETING session")
        void identicalManifestRetrySucceeds() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long size = 52428800L; // 50 MiB = 2 parts

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, size);
            session.markInitiated("b2-up-1", NOW);

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            List<MultipartManifestPart> manifest = List.of(
                    new MultipartManifestPart(1, 26214400L, "\"etag-part-1\""),
                    new MultipartManifestPart(2, 26214400L, "\"etag-part-2\"")
            );

            MediaUploadSession firstResult = service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, "a".repeat(64), manifest
            );
            assertThat(firstResult.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
            String firstHash = firstResult.getManifestHash();
            assertThat(firstHash).isNotNull();

            MediaUploadSession retryResult = service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, "a".repeat(64), manifest
            );
            assertThat(retryResult.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
            assertThat(retryResult.getManifestHash()).isEqualTo(firstHash);
        }

        @Test
        @DisplayName("Different-manifest conflict is rejected without mutating original intent")
        void differentManifestConflictRejected() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long size = 52428800L;

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, size);
            session.markInitiated("b2-up-1", NOW);

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            List<MultipartManifestPart> manifestA = List.of(
                    new MultipartManifestPart(1, 26214400L, "\"etag-part-1\""),
                    new MultipartManifestPart(2, 26214400L, "\"etag-part-2\"")
            );

            MediaUploadSession firstResult = service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, "a".repeat(64), manifestA
            );
            String originalHash = firstResult.getManifestHash();

            List<MultipartManifestPart> manifestB = List.of(
                    new MultipartManifestPart(1, 26214400L, "\"etag-part-1\""),
                    new MultipartManifestPart(2, 26214400L, "\"different-etag-2\"")
            );

            assertThatThrownBy(() -> service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, "a".repeat(64), manifestB
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("Conflicting manifest");

            assertThat(firstResult.getManifestHash()).isEqualTo(originalHash);
        }

        @Test
        @DisplayName("Conflicting declared content hash with same manifest is rejected without mutating original intent")
        void conflictingDeclaredHashRejectedWithoutMutating() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long size = 52428800L;

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, size);
            session.markInitiated("b2-up-1", NOW);

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            List<MultipartManifestPart> manifest = List.of(
                    new MultipartManifestPart(1, 26214400L, "\"etag-part-1\""),
                    new MultipartManifestPart(2, 26214400L, "\"etag-part-2\"")
            );

            String declaredHashA = "a".repeat(64);
            MediaUploadSession firstResult = service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, declaredHashA, manifest
            );
            assertThat(firstResult.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
            assertThat(firstResult.getDeclaredContentHash()).isEqualTo(declaredHashA);

            String declaredHashB = "b".repeat(64);
            assertThatThrownBy(() -> service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, declaredHashB, manifest
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("Conflicting declared content hash");

            assertThat(firstResult.getDeclaredContentHash()).isEqualTo(declaredHashA);
        }

        @Test
        @DisplayName("Rejects malformed declaredContentHash that does not match 64-char hex pattern")
        void rejectsMalformedDeclaredContentHash() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            List<MultipartManifestPart> manifest = List.of(
                    new MultipartManifestPart(1, 26214400L, "etag-1")
            );

            assertThatThrownBy(() -> service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, "not-a-sha256-hash", manifest
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("declaredContentHash must be a 64-character lowercase hex string");
        }

        @Test
        @DisplayName("Rejects completion claim when allocation status is not RESERVED")
        void rejectsCompletionClaimWhenAllocationNotReserved() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);

            StorageKeyAllocation nonReservedAllocation = StorageKeyAllocation.rehydrate(
                    allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY,
                    StorageKeyAllocationStatus.COMMITTED,
                    StorageKeyAllocationType.UPLOAD_SESSION, sessionId,
                    NOW, NOW
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(nonReservedAllocation));

            List<MultipartManifestPart> manifest = List.of(new MultipartManifestPart(1, 26214400L, "etag-1"));

            assertThatThrownBy(() -> service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, "a".repeat(64), manifest
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("is not in RESERVED status");

            verify(sessionRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Rejects completion claim when allocation provider does not match session")
        void rejectsCompletionClaimWhenAllocationProviderMismatched() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);

            StorageKeyAllocation mismatchedAllocation = StorageKeyAllocation.rehydrate(
                    allocId, StorageProviderId.of("r2"), B2_BUCKET, STORAGE_KEY,
                    StorageKeyAllocationStatus.RESERVED,
                    StorageKeyAllocationType.UPLOAD_SESSION, sessionId,
                    NOW, NOW
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(mismatchedAllocation));

            List<MultipartManifestPart> manifest = List.of(new MultipartManifestPart(1, 26214400L, "etag-1"));

            assertThatThrownBy(() -> service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, "a".repeat(64), manifest
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("does not match upload session");

            verify(sessionRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Rejects completion claim when allocation bucket does not match session")
        void rejectsCompletionClaimWhenAllocationBucketMismatched() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);

            StorageKeyAllocation mismatchedAllocation = StorageKeyAllocation.rehydrate(
                    allocId, B2_PROVIDER, "other-bucket", STORAGE_KEY,
                    StorageKeyAllocationStatus.RESERVED,
                    StorageKeyAllocationType.UPLOAD_SESSION, sessionId,
                    NOW, NOW
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(mismatchedAllocation));

            List<MultipartManifestPart> manifest = List.of(new MultipartManifestPart(1, 26214400L, "etag-1"));

            assertThatThrownBy(() -> service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, "a".repeat(64), manifest
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("does not match upload session");

            verify(sessionRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Rejects completion claim when allocation storage key does not match session")
        void rejectsCompletionClaimWhenAllocationKeyMismatched() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);

            StorageKeyAllocation mismatchedAllocation = StorageKeyAllocation.rehydrate(
                    allocId, B2_PROVIDER, B2_BUCKET, StorageKey.of("other/video.mp4"),
                    StorageKeyAllocationStatus.RESERVED,
                    StorageKeyAllocationType.UPLOAD_SESSION, sessionId,
                    NOW, NOW
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(mismatchedAllocation));

            List<MultipartManifestPart> manifest = List.of(new MultipartManifestPart(1, 26214400L, "etag-1"));

            assertThatThrownBy(() -> service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, "a".repeat(64), manifest
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("does not match upload session");

            verify(sessionRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Rejects completion claim when allocation type is not UPLOAD_SESSION")
        void rejectsCompletionClaimWhenAllocationTypeMismatched() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);

            StorageKeyAllocation mismatchedAllocation = StorageKeyAllocation.rehydrate(
                    allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY,
                    StorageKeyAllocationStatus.RESERVED,
                    StorageKeyAllocationType.ASSET_VERSION, sessionId,
                    NOW, NOW
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(mismatchedAllocation));

            List<MultipartManifestPart> manifest = List.of(new MultipartManifestPart(1, 26214400L, "etag-1"));

            assertThatThrownBy(() -> service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, "a".repeat(64), manifest
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("does not match upload session");

            verify(sessionRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Rejects completion claim when allocation belongs to foreign session ID")
        void rejectsCompletionClaimWhenAllocationForeignSessionId() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID foreignSessionId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);

            StorageKeyAllocation foreignAllocation = StorageKeyAllocation.rehydrate(
                    allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY,
                    StorageKeyAllocationStatus.RESERVED,
                    StorageKeyAllocationType.UPLOAD_SESSION, foreignSessionId,
                    NOW, NOW
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(foreignAllocation));

            List<MultipartManifestPart> manifest = List.of(new MultipartManifestPart(1, 26214400L, "etag-1"));

            assertThatThrownBy(() -> service.claimCompletionIntent(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, "a".repeat(64), manifest
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("does not match upload session");

            verify(sessionRepositoryPort, never()).save(any());
        }
    }

    // =============================================================================================
    // Requirement 9, 10, 11, 12: Unambiguous Manifest Encoding & Strict Normalization (Correction 2)
    // =============================================================================================
    @Nested
    @DisplayName("9, 10, 11, 12. Unambiguous Canonical Manifest Encoding & Token Security (Correction 2)")
    class CanonicalEncodingSecurityTests {

        @Test
        @DisplayName("Unordered input produces identical canonical manifest fingerprint")
        void unorderedProducesIdenticalFingerprint() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long size = 78643200L; // 75 MiB = 3 parts

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, size);

            List<MultipartManifestPart> ordered = List.of(
                    new MultipartManifestPart(1, 26214400L, "etag-1"),
                    new MultipartManifestPart(2, 26214400L, "etag-2"),
                    new MultipartManifestPart(3, 26214400L, "etag-3")
            );

            List<MultipartManifestPart> unordered = List.of(
                    new MultipartManifestPart(3, 26214400L, "etag-3"),
                    new MultipartManifestPart(1, 26214400L, "etag-1"),
                    new MultipartManifestPart(2, 26214400L, "etag-2")
            );

            String hash1 = MultipartManifestCanonicalizer.validateAndCanonicalize(session, ordered);
            String hash2 = MultipartManifestCanonicalizer.validateAndCanonicalize(session, unordered);

            assertThat(hash1).isEqualTo(hash2);
            assertThat(hash1).hasSize(64).matches("^[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("Canonical byte stream begins with version header and uses length-prefixed entries")
        void canonicalBytesFollowStrictLengthPrefixedFormat() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);

            List<MultipartManifestPart> manifest = List.of(
                    new MultipartManifestPart(1, 26214400L, "\"token-part-1\"")
            );

            byte[] canonicalBytes = MultipartManifestCanonicalizer.canonicalizeToBytes(session, manifest);
            String text = new String(canonicalBytes, StandardCharsets.UTF_8);

            assertThat(text).startsWith("kiemlai-part-manifest-v2\n");
            // Format: 1:26214400:12:token-part-1\n
            assertThat(text).contains("1:26214400:12:token-part-1\n");
        }

        @Test
        @DisplayName("ETags containing colons cannot create delimiter or parsing collision")
        void colonsInETagDoNotCollide() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long size = 52428800L;

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, size);

            List<MultipartManifestPart> manifestWithColons = List.of(
                    new MultipartManifestPart(1, 26214400L, "foo:bar"),
                    new MultipartManifestPart(2, 26214400L, "baz")
            );

            List<MultipartManifestPart> manifestDistinct = List.of(
                    new MultipartManifestPart(1, 26214400L, "foo"),
                    new MultipartManifestPart(2, 26214400L, "bar:baz")
            );

            String hashA = MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifestWithColons);
            String hashB = MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifestDistinct);

            assertThat(hashA).isNotEqualTo(hashB);
        }

        @Test
        @DisplayName("Preserves ETag casing, non-hexadecimal tokens, and weak W/ prefix while removing outer transport quotes")
        void preservesCaseAndWeakPrefix() {
            assertThat(MultipartManifestCanonicalizer.normalizeETag("\"MyOpaqueToken-123\""))
                    .isEqualTo("MyOpaqueToken-123");

            assertThat(MultipartManifestCanonicalizer.normalizeETag("W/\"MyWeakToken-ABC\""))
                    .isEqualTo("W/MyWeakToken-ABC");

            assertThat(MultipartManifestCanonicalizer.normalizeETag("W/unquotedWeakToken"))
                    .isEqualTo("W/unquotedWeakToken");

            assertThat(MultipartManifestCanonicalizer.normalizeETag("AlreadyUnquoted-xyz"))
                    .isEqualTo("AlreadyUnquoted-xyz");
        }

        @Test
        @DisplayName("Case difference in ETag yields different canonical fingerprints")
        void caseDifferenceYieldsDifferentHashes() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long size = 26214400L;

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, size);

            List<MultipartManifestPart> upper = List.of(new MultipartManifestPart(1, 26214400L, "UPPERCASE-ETAG"));
            List<MultipartManifestPart> lower = List.of(new MultipartManifestPart(1, 26214400L, "uppercase-etag"));

            String hashUpper = MultipartManifestCanonicalizer.validateAndCanonicalize(session, upper);
            String hashLower = MultipartManifestCanonicalizer.validateAndCanonicalize(session, lower);

            assertThat(hashUpper).isNotEqualTo(hashLower);
        }

        @Test
        @DisplayName("Strictly rejects CR, LF, NUL, DEL, and control characters in ETags")
        void rejectsControlCharactersInETag() {
            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("etag\rvalue"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("control character");

            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("etag\nvalue"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("control character");

            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("etag\0value"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("control character");

            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("etag\u007fvalue"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("control character");
        }

        @Test
        @DisplayName("Strictly rejects Unicode surrogate characters (high, low, and paired) in ETags")
        void rejectsSurrogateCharactersInETag() {
            // High surrogate (\uD800)
            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("etag\uD800token"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("surrogate");

            // Low surrogate (\uDC00)
            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("etag\uDC00token"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("surrogate");

            // Emoji / surrogate pair (\uD83D\uDE00)
            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("etag\uD83D\uDE00token"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("surrogate");
        }

        @Test
        @DisplayName("Strictly rejects malformed or ambiguous quote formatting")
        void rejectsAmbiguousQuotes() {
            // Missing trailing quote
            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("\"unmatched"))
                    .isInstanceOf(IllegalArgumentException.class);

            // Unmatched trailing quote
            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("unmatched\""))
                    .isInstanceOf(IllegalArgumentException.class);

            // Embedded quote inside quoted token
            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("\"foo\"bar\""))
                    .isInstanceOf(IllegalArgumentException.class);

            // Empty quoted token
            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("\"\""))
                    .isInstanceOf(IllegalArgumentException.class);

            // Weak with missing trailing quote
            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("W/\"unclosed"))
                    .isInstanceOf(IllegalArgumentException.class);

            // Weak with empty payload
            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("W/\"\""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Strictly rejects leading or trailing whitespace in ETag tokens")
        void rejectsWhitespaceInETag() {
            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag(" etag"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("whitespace");

            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag("etag "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("whitespace");
        }

        @Test
        @DisplayName("Rejects oversized ETag exceeding MAX_ETAG_LENGTH")
        void rejectsOversizedETag() {
            String oversized = "a".repeat(MultipartManifestCanonicalizer.MAX_ETAG_LENGTH + 1);
            assertThatThrownBy(() -> MultipartManifestCanonicalizer.normalizeETag(oversized))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("byte length must be between 1 and 256");
        }

        @Test
        @DisplayName("Rejects duplicate part numbers")
        void rejectsDuplicateParts() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 52428800L); // expects 2 parts

            List<MultipartManifestPart> duplicate = List.of(
                    new MultipartManifestPart(1, 26214400L, "etag-1"),
                    new MultipartManifestPart(1, 26214400L, "etag-1-alt")
            );

            assertThatThrownBy(() -> MultipartManifestCanonicalizer.validateAndCanonicalize(session, duplicate))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate part number");
        }

        @Test
        @DisplayName("Rejects missing part numbers")
        void rejectsMissingParts() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 52428800L); // expects 2 parts

            List<MultipartManifestPart> missing = List.of(
                    new MultipartManifestPart(1, 26214400L, "etag-1"),
                    new MultipartManifestPart(3, 26214400L, "etag-3") // part 2 missing, part 3 out of range
            );

            assertThatThrownBy(() -> MultipartManifestCanonicalizer.validateAndCanonicalize(session, missing))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing or out-of-range part number");
        }

        @Test
        @DisplayName("Rejects part count mismatch")
        void rejectsPartCountMismatch() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 52428800L); // expects 2 parts

            List<MultipartManifestPart> onlyOne = List.of(
                    new MultipartManifestPart(1, 26214400L, "etag-1")
            );

            assertThatThrownBy(() -> MultipartManifestCanonicalizer.validateAndCanonicalize(session, onlyOne))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Manifest part count mismatch");
        }

        @Test
        @DisplayName("Rejects non-final part whose size is not 25 MiB")
        void rejectsNonFinalPartSizeMismatch() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long size = 52428800L; // 50 MiB = two 25 MiB parts

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, size);

            List<MultipartManifestPart> invalidPart1 = List.of(
                    new MultipartManifestPart(1, 20000000L, "etag-1"), // not 26214400
                    new MultipartManifestPart(2, 32428800L, "etag-2")
            );

            assertThatThrownBy(() -> MultipartManifestCanonicalizer.validateAndCanonicalize(session, invalidPart1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Part 1 size must be exactly 26214400 bytes");
        }

        @Test
        @DisplayName("Rejects final part whose size does not match expected remainder")
        void rejectsFinalPartSizeMismatch() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long size = 31457280L; // 30 MiB = part 1 (25 MiB), part 2 (5 MiB = 5242880 bytes)

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, size);

            List<MultipartManifestPart> invalidPart2 = List.of(
                    new MultipartManifestPart(1, 26214400L, "etag-1"),
                    new MultipartManifestPart(2, 4000000L, "etag-2") // expected 5242880
            );

            assertThatThrownBy(() -> MultipartManifestCanonicalizer.validateAndCanonicalize(session, invalidPart2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Last part 2 size must be exactly 5242880 bytes");
        }

        @Test
        @DisplayName("Validates exactly 50 GiB boundary with 2048 parts")
        void validates50GiBBoundary() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long max50GiB = MediaUploadSession.MAX_VIDEO_SIZE_BYTES; // 53687091200L

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, max50GiB);
            assertThat(session.getExpectedPartCount()).isEqualTo(2048);
        }

        @Test
        @DisplayName("Rejects declared size exceeding 50 GiB")
        void rejectsExceeding50GiB() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long overLimit = MediaUploadSession.MAX_VIDEO_SIZE_BYTES + 1L;

            assertThatThrownBy(() -> createAllocatedSession(sessionId, allocId, targetId, overLimit))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("declaredSizeBytes must be between 1 and 53687091200");
        }
    }

    // =============================================================================================
    // Requirement 13: Closing Legacy markInitiated Bypass (Correction 3)
    // =============================================================================================
    @Nested
    @DisplayName("13. Legacy Initiation Bypass Protection (Correction 3)")
    class LegacyInitiationBypassTests {

        @Test
        @DisplayName("Calling legacy 3-arg markInitiated without claim token throws UnsupportedOperationException")
        void legacy3ArgThrowsUnsupportedOperationException() {
            UUID sessionId = UUID.randomUUID();
            assertThatThrownBy(() -> service.markInitiated(sessionId, "b2-upload-bypass", PRINCIPAL))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("Direct initiation without a verified initiation claim token is prohibited");
        }

        @Test
        @DisplayName("Calling 4-arg markInitiated without active initiation lease throws IllegalStateException")
        void markInitiatedWithoutActiveLeaseThrows() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            // Session has NO active initiation lease
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.markInitiated(sessionId, "b2-upload-bypass", PRINCIPAL, "GUESSED-TOKEN"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not have an active initiation lease");
        }

        @Test
        @DisplayName("Calling 4-arg markInitiated with mismatched claim token throws IllegalStateException")
        void markInitiatedWithMismatchedClaimTokenThrows() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.claimInitiation("LEGITIMATE-CLAIM-TOKEN", NOW.plus(Duration.ofMinutes(5)), NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.markInitiated(sessionId, "b2-upload-bypass", PRINCIPAL, "WRONG-CLAIM-TOKEN"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Initiation claim token mismatch");
        }

        @Test
        @DisplayName("Calling 4-arg markInitiated with expired initiation lease throws IllegalStateException")
        void markInitiatedWithExpiredLeaseThrows() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            Instant past = NOW.minus(Duration.ofHours(1));
            MediaUploadSession session = MediaUploadSession.create(
                    sessionId,
                    PRINCIPAL,
                    CONSUMER,
                    TARGET_TYPE,
                    targetId,
                    "idem-key-" + sessionId,
                    26214400L,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    allocId,
                    "episode1.mp4",
                    null,
                    NOW.plus(Duration.ofDays(1)),
                    past
            );
            // Expired 1 minute ago (leaseExpiresAt < NOW)
            session.claimInitiation("TOKEN-EXPIRED", NOW.minus(Duration.ofMinutes(1)), NOW.minus(Duration.ofMinutes(6)));

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.markInitiated(sessionId, "b2-upload-bypass", PRINCIPAL, "TOKEN-EXPIRED"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not have an active initiation lease");
        }

        @Test
        @DisplayName("Calling 4-arg markInitiated with unauthorized principal throws IllegalStateException")
        void markInitiatedWithUnauthorizedPrincipalThrows() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.claimInitiation("CLAIM-TOKEN-1", NOW.plus(Duration.ofMinutes(5)), NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.markInitiated(sessionId, "b2-upload-bypass", "intruder-user", "CLAIM-TOKEN-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not authorized to transition upload session");
        }

        @Test
        @DisplayName("Calling 4-arg markInitiated on non-ALLOCATED session throws IllegalStateException")
        void markInitiatedOnNonAllocatedSessionThrows() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-already-initiated", NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.markInitiated(sessionId, "b2-upload-bypass", PRINCIPAL, "CLAIM-TOKEN-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot persist providerUploadId on session");
        }

        @Test
        @DisplayName("Calling 4-arg markInitiated with non-RESERVED allocation throws IllegalStateException")
        void markInitiatedWithNonReservedAllocationThrows() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            String claimToken = "CLAIM-TOKEN-OK";
            session.claimInitiation(claimToken, NOW.plus(Duration.ofMinutes(5)), NOW);

            // Allocation is TOMBSTONE instead of RESERVED
            StorageKeyAllocation allocation = StorageKeyAllocation.rehydrate(
                    allocId,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    StorageKeyAllocationStatus.TOMBSTONE,
                    StorageKeyAllocationType.UPLOAD_SESSION,
                    sessionId,
                    NOW,
                    NOW,
                    0L
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));

            assertThatThrownBy(() -> service.markInitiated(sessionId, "b2-upload-1", PRINCIPAL, claimToken))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not in RESERVED status");
        }

        @Test
        @DisplayName("Calling 4-arg markInitiated with matching active claim token and valid allocation succeeds")
        void markInitiatedWithValidClaimTokenSucceeds() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            String claimToken = "CLAIM-VALID-123";
            session.claimInitiation(claimToken, NOW.plus(Duration.ofMinutes(5)), NOW);

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession result = service.markInitiated(sessionId, "b2-upload-legit", PRINCIPAL, claimToken);

            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            assertThat(result.getProviderUploadId()).isEqualTo("b2-upload-legit");
            assertThat(result.getLeaseOwner()).isNull(); // Lease cleared
            verify(sessionRepositoryPort).save(session);
        }
    }

    // =============================================================================================
    // Requirement 14: Completion claim versus ABORT
    // =============================================================================================
    @Nested
    @DisplayName("14. Initiation Claim and Completion Claim vs ABORT Protection (Correction 1)")
    class InitiationAndCompletionVsAbortTests {

        @Test
        @DisplayName("Prevents user abort when session has active initiation claim (ALLOCATED with lease)")
        void preventsUserAbortOnActiveInitiationClaim() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.claimInitiation("LEASE-ACTIVE", NOW.plus(Duration.ofMinutes(5)), NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.abortSession(sessionId, PRINCIPAL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unresolved initiation claim");

            verify(sessionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
        }

        @Test
        @DisplayName("Prevents user abort when session has expired initiation claim (expired lease does not prove B2 failed)")
        void preventsUserAbortOnExpiredInitiationClaim() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            Instant past = NOW.minus(Duration.ofHours(1));
            MediaUploadSession session = MediaUploadSession.create(
                    sessionId,
                    PRINCIPAL,
                    CONSUMER,
                    TARGET_TYPE,
                    targetId,
                    "idem-key-" + sessionId,
                    26214400L,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    allocId,
                    "video.mp4",
                    null,
                    NOW.plus(Duration.ofDays(1)),
                    past
            );
            session.claimInitiation("LEASE-EXPIRED", NOW.minus(Duration.ofMinutes(1)), NOW.minus(Duration.ofMinutes(6)));

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.abortSession(sessionId, PRINCIPAL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unresolved initiation claim");

            verify(sessionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
        }

        @Test
        @DisplayName("Prevents system abort when session has active initiation claim")
        void preventsSystemAbortOnActiveInitiationClaim() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.claimInitiation("LEASE-ACTIVE", NOW.plus(Duration.ofMinutes(5)), NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.abortBySystem(sessionId, "expired-lease"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unresolved initiation claim");

            verify(sessionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
        }

        @Test
        @DisplayName("Prevents system abort when session has expired-but-unconfirmed initiation claim")
        void preventsSystemAbortOnExpiredInitiationClaim() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            Instant past = NOW.minus(Duration.ofHours(1));
            MediaUploadSession session = MediaUploadSession.create(
                    sessionId,
                    PRINCIPAL,
                    CONSUMER,
                    TARGET_TYPE,
                    targetId,
                    "idem-key-" + sessionId,
                    26214400L,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    allocId,
                    "video.mp4",
                    null,
                    NOW.plus(Duration.ofDays(1)),
                    past
            );
            session.claimInitiation("LEASE-EXPIRED", NOW.minus(Duration.ofMinutes(1)), NOW.minus(Duration.ofMinutes(6)));

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.abortBySystem(sessionId, "reconciliation-timeout"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unresolved initiation claim");

            verify(sessionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
        }

        @Test
        @DisplayName("Domain entity abort directly throws IllegalStateException when active initiation claim exists")
        void domainAbortThrowsOnActiveInitiationClaim() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.claimInitiation("LEASE-ACTIVE", NOW.plus(Duration.ofMinutes(5)), NOW);

            assertThatThrownBy(() -> session.abort(NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unresolved initiation claim");
        }

        @Test
        @DisplayName("Domain entity abort directly throws IllegalStateException when expired initiation claim exists")
        void domainAbortThrowsOnExpiredInitiationClaim() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            Instant past = NOW.minus(Duration.ofHours(1));
            MediaUploadSession session = MediaUploadSession.create(
                    sessionId,
                    PRINCIPAL,
                    CONSUMER,
                    TARGET_TYPE,
                    targetId,
                    "idem-key-" + sessionId,
                    26214400L,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    allocId,
                    "video.mp4",
                    null,
                    NOW.plus(Duration.ofDays(1)),
                    past
            );
            session.claimInitiation("LEASE-EXPIRED", NOW.minus(Duration.ofMinutes(1)), NOW.minus(Duration.ofMinutes(6)));

            assertThatThrownBy(() -> session.abort(NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unresolved initiation claim");
        }

        @Test
        @DisplayName("Permits user abort on truly unclaimed ALLOCATED session and tombstones allocation")
        void permitsUserAbortOnUnclaimedAllocatedSession() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            assertThat(session.getLeaseOwner()).isNull();

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession result = service.abortSession(sessionId, PRINCIPAL);

            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            verify(sessionRepositoryPort).save(session);
            verify(storageKeyAllocationService).tombstoneAllocation(allocId);
        }

        @Test
        @DisplayName("Permits system abort on truly unclaimed ALLOCATED session and tombstones allocation")
        void permitsSystemAbortOnUnclaimedAllocatedSession() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            assertThat(session.getLeaseOwner()).isNull();

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession result = service.abortBySystem(sessionId, "expired-unclaimed-session");

            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            verify(sessionRepositoryPort).save(session);
            verify(storageKeyAllocationService).tombstoneAllocation(allocId);
        }

        @Test
        @DisplayName("Permits user abort on legitimate INITIATED session, transitions to ABORTED and marks cleanup PENDING")
        void permitsUserAbortOnInitiatedSession() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession result = service.abortSession(sessionId, PRINCIPAL);

            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            assertThat(result.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.PENDING);
            verify(sessionRepositoryPort).save(session);
            verify(storageKeyAllocationService).tombstoneAllocation(allocId);
        }

        @Test
        @DisplayName("Prevents user abort request when completion intent has been claimed (COMPLETING)")
        void preventsUserAbortOnCompleting() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);
            session.markCompleting("b".repeat(64), NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.abortSession(sessionId, PRINCIPAL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot abort session with active completion claim");

            verify(sessionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
        }

        @Test
        @DisplayName("Prevents system abort request when completion intent has been claimed (COMPLETING)")
        void preventsSystemAbortOnCompleting() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);
            session.markCompleting("b".repeat(64), NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.abortBySystem(sessionId, "expired-lease"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot abort session with active completion claim");

            verify(sessionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
        }

        @Test
        @DisplayName("Domain entity abort directly throws IllegalStateException when in COMPLETING state")
        void domainAbortThrowsOnCompleting() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);
            session.markCompleting("c".repeat(64), NOW);

            assertThatThrownBy(() -> session.abort(NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot abort session with active completion claim");
        }
    }

    // =============================================================================================
    // Requirement 15: Concurrency Conflicts and Stale Ownership
    // =============================================================================================
    @Nested
    @DisplayName("15. Concurrency Conflicts and Stale Ownership")
    class ConcurrencyAndOwnershipTests {

        @Test
        @DisplayName("Rejects initiation if requesting principal is not the session initiator")
        void rejectsUnauthorizedInitiator() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, "intruder-user"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not authorized to initiate upload session");

            verify(multipartStoragePort, never()).initiateMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("Rejects completion claim if requesting principal is not the session initiator")
        void rejectsUnauthorizedCompletionClaim() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            List<MultipartManifestPart> manifest = List.of(new MultipartManifestPart(1, 26214400L, "etag-1"));

            assertThatThrownBy(() -> service.claimCompletionIntent(
                    sessionId, "intruder-user", TARGET_TYPE, targetId, "d".repeat(64), manifest
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("is not authorized to claim completion");
        }

        @Test
        @DisplayName("Rejects completion claim if target entity does not match session target")
        void rejectsTargetMismatch() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            List<MultipartManifestPart> manifest = List.of(new MultipartManifestPart(1, 26214400L, "etag-1"));

            assertThatThrownBy(() -> service.claimCompletionIntent(
                    sessionId, PRINCIPAL, "OtherEntity", targetId, "d".repeat(64), manifest
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("Target mismatch");
        }

        @Test
        @DisplayName("Convenience overload converts MultipartUploadedPart to manifest parts cleanly")
        void convenienceOverloadConvertsUploadedParts() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, 26214400L);
            session.markInitiated("b2-up-1", NOW);
            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            List<MultipartUploadedPart> uploaded = List.of(
                    new MultipartUploadedPart(1, 26214400L, "\"etag-1\"")
            );

            MediaUploadSession result = service.claimCompletionIntentFromUploadedParts(
                    sessionId, PRINCIPAL, TARGET_TYPE, targetId, "e".repeat(64), uploaded
            );

            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
            assertThat(result.getManifestHash()).isNotNull();
        }
    }
}
