package com.universe.media.application.storage;

import com.universe.media.application.exceptions.StorageAmbiguousInitiationException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageInitiationRejectedException;
import com.universe.media.application.exceptions.UploadSessionInitiationConflictException;
import com.universe.media.application.exceptions.UploadSessionUnconfirmedInitiationException;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import com.universe.screen.entry.admin.AdminScreenEntryVideoController;
import com.universe.screen.entry.admin.dto.ScreenVideoErrorResponse;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaUploadSessionInitiationRecoveryTest {

    private static final String PRINCIPAL = "admin-actor-uuid";
    private static final String CONSUMER = "SCREEN";
    private static final String TARGET_TYPE = "SCREEN_ENTRY";
    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "universe-media-vault";
    private static final StorageKey STORAGE_KEY = StorageKey.of("video/2026/10/screen-entry-video-v1.mp4");
    private static final Instant NOW = Instant.parse("2026-10-24T21:00:00Z");

    @Mock
    private MediaUploadSessionRepositoryPort sessionRepositoryPort;
    @Mock
    private StorageKeyAllocationService storageKeyAllocationService;
    @Mock
    private MediaStorageRoutingService mediaStorageRoutingService;
    @Mock
    private MultipartStoragePort multipartStoragePort;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private ClockPort clockPort;

    private MediaUploadSessionService service;

    @BeforeEach
    void setUp() {
        when(clockPort.now()).thenReturn(NOW);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        org.mockito.Mockito.lenient().when(multipartStoragePort.providerId()).thenReturn(StorageProviderId.of("b2"));

        service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                mediaStorageRoutingService,
                clockPort,
                transactionManager,
                multipartStoragePort
        );
    }

    private MediaUploadSession createAllocatedSession(UUID sessionId, UUID allocId, UUID targetId) {
        return createAllocatedSession(sessionId, allocId, targetId, NOW);
    }

    private MediaUploadSession createAllocatedSession(UUID sessionId, UUID allocId, UUID targetId, Instant createdAt) {
        return MediaUploadSession.create(
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
                createdAt
        );
    }

    private MediaUploadSession rehydrateCopy(MediaUploadSession s) {
        return MediaUploadSession.rehydrate(
                s.getId(),
                s.getInitiatorPrincipalId(),
                s.getConsumerContext(),
                s.getTargetEntityType(),
                s.getTargetEntityId(),
                s.getIdempotencyKey(),
                s.getMediaType(),
                s.getDeclaredMimeType(),
                s.getDeclaredSizeBytes(),
                s.getStorageProviderId(),
                s.getStorageBucketName(),
                s.getStorageKey(),
                s.getStorageAllocationId(),
                s.getProviderUploadId(),
                s.getPartSizeBytes(),
                s.getExpectedPartCount(),
                s.getDeclaredContentHash(),
                s.getOriginalFilename(),
                s.getClientTag(),
                s.getStatus(),
                s.getExpiresAt(),
                s.getLeaseOwner(),
                s.getLeaseExpiresAt(),
                s.getRegisteredAssetId(),
                s.getCleanupStatus(),
                s.getCleanupRetryCount(),
                s.getCleanupLastAttemptAt(),
                s.getCleanupErrorDetail(),
                s.getCreatedAt(),
                s.getUpdatedAt(),
                s.getCompletedAt(),
                s.getPersistenceVersion(),
                s.getManifestHash()
        );
    }

    private StorageKeyAllocation createReservedAllocation(UUID allocId, UUID sessionId) {
        return StorageKeyAllocation.rehydrate(
                allocId,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                StorageKeyAllocationStatus.RESERVED,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                NOW,
                NOW,
                1L
        );
    }

    // =============================================================================================
    // T1: Definitive provider rejection before remote initiation
    // =============================================================================================
    @Test
    @DisplayName("T1: Definitive provider rejection releases initiation claim so session remains safely retryable")
    void t1_definitiveProviderRejectionReleasesInitiationClaim() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.initiateMultipartUpload(eq(STORAGE_KEY), eq(MimeType.of("video/mp4")), any()))
                .thenThrow(new StorageInitiationRejectedException("Provider definitively rejected multipart initiation (MalformedAccessKeyId)"));

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                .isInstanceOf(StorageInitiationRejectedException.class)
                .hasMessageContaining("MalformedAccessKeyId");

        // The session's initiation claim must have been released by the compensation transaction
        assertThat(session.getLeaseOwner()).isNull();
        assertThat(session.getLeaseExpiresAt()).isNull();
        assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.ALLOCATED);

        // Saved twice: once for TX-A claim, once for compensation release
        verify(sessionRepositoryPort, times(2)).save(session);
        // Never speculatively abort
        verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
    }

    // =============================================================================================
    // T2: Ambiguous provider timeout retains safe claim
    // =============================================================================================
    @Test
    @DisplayName("T2: Ambiguous provider timeout retains initiation claim for reconciliation without speculative clearing")
    void t2_ambiguousProviderTimeoutRetainsSafeClaim() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any()))
                .thenThrow(new StorageAmbiguousInitiationException("Provider call timed out during CreateMultipartUpload"));

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                .isInstanceOf(StorageAmbiguousInitiationException.class)
                .hasMessageContaining("timed out");

        // Initiation claim MUST remain held because outcome is ambiguous
        assertThat(session.getLeaseOwner()).startsWith("INITIATION_CLAIM:");
        assertThat(session.getLeaseExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.ALLOCATED);

        // Saved only once (for TX-A)
        verify(sessionRepositoryPort, times(1)).save(session);
    }

    // =============================================================================================
    // T3: Provider success followed by persistence failure does not create an unsafe duplicate on retry
    // =============================================================================================
    @Test
    @DisplayName("T3: Provider success followed by persistence failure retains unconfirmed claim and blocks duplicate initiation on retry")
    void t3_providerSuccessFollowedByPersistenceFailureBlocksDuplicateOnRetry() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        AtomicReference<MediaUploadSession> committedSession = new AtomicReference<>(session);
        when(sessionRepositoryPort.findById(sessionId)).thenAnswer(inv -> Optional.of(rehydrateCopy(committedSession.get())));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));

        // TX-A succeeds, but TX-B save throws OptimisticLockingFailureException
        when(sessionRepositoryPort.save(any(MediaUploadSession.class)))
                .thenAnswer(inv -> {
                    MediaUploadSession saved = inv.getArgument(0);
                    committedSession.set(rehydrateCopy(saved));
                    return saved;
                }) // for TX-A
                .thenThrow(new OptimisticLockingFailureException("Simulated optimistic lock failure during TX-B"));

        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any())).thenReturn("b2-upload-orphaned-123");

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // Verify the claim was set by TX-A and preserved in the committed state
        assertThat(committedSession.get().getLeaseOwner()).startsWith("INITIATION_CLAIM:");

        // Now advance clock past 5 minutes to simulate a subsequent retry attempt
        Instant futureNow = NOW.plus(Duration.ofMinutes(10));
        when(clockPort.now()).thenReturn(futureNow);

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                .isInstanceOf(UploadSessionUnconfirmedInitiationException.class)
                .hasMessageContaining("unconfirmed earlier initiation claim with missing providerUploadId");

        // Multipart initiation was invoked ONLY ONCE (during the first attempt) and NEVER on retry
        verify(multipartStoragePort, times(1)).initiateMultipartUpload(any(), any(), any());
    }

    // =============================================================================================
    // T4: Concurrent requests using the same idempotency key cannot create duplicate provider uploads
    // =============================================================================================
    @Test
    @DisplayName("T4: Concurrent request while initiation lease is active throws UploadSessionInitiationConflictException")
    void t4_concurrentRequestWhileLeaseActiveThrowsConflict() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        // Active lease held by another worker
        session.claimInitiation("INITIATION_CLAIM:worker-1", NOW.plus(Duration.ofMinutes(4)), NOW);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                .isInstanceOf(UploadSessionInitiationConflictException.class)
                .hasMessageContaining("Concurrent initiation in progress");

        verify(multipartStoragePort, never()).initiateMultipartUpload(any(), any(), any());
    }

    // =============================================================================================
    // T5: Expired lease with unresolved initiation is not blindly reset
    // =============================================================================================
    @Test
    @DisplayName("T5: Expired lease with unconfirmed initiation claim is not blindly reset on retry")
    void t5_expiredLeaseWithUnconfirmedClaimIsNotBlindlyReset() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, NOW.minus(Duration.ofHours(1)));
        // Lease was acquired 15 minutes ago, expired 10 minutes ago
        session.claimInitiation("INITIATION_CLAIM:worker-crashed", NOW.minus(Duration.ofMinutes(10)), NOW.minus(Duration.ofMinutes(15)));

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                .isInstanceOf(UploadSessionUnconfirmedInitiationException.class)
                .hasMessageContaining("unconfirmed earlier initiation claim with missing providerUploadId");

        // Claim must NOT be reset or overwritten
        assertThat(session.getLeaseOwner()).isEqualTo("INITIATION_CLAIM:worker-crashed");
        verify(multipartStoragePort, never()).initiateMultipartUpload(any(), any(), any());
    }

    // =============================================================================================
    // T6: Definitively rejected initiation can retry safely with the same key
    // =============================================================================================
    @Test
    @DisplayName("T6: Definitively rejected initiation can retry safely with the same key once credentials are fixed")
    void t6_definitivelyRejectedInitiationCanRetrySafelyWithSameKey() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        // Attempt 1: Definitive rejection (e.g. MalformedAccessKeyId), followed by Attempt 2: success
        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any()))
                .thenThrow(new StorageInitiationRejectedException("Provider rejected (MalformedAccessKeyId)"))
                .thenReturn("b2-upload-valid-456");

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                .isInstanceOf(StorageInitiationRejectedException.class);

        // Verification after Attempt 1: Lease owner cleared by compensation transaction
        assertThat(session.getLeaseOwner()).isNull();
        assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.ALLOCATED);

        // Attempt 2: Human corrected credentials to Standard Application Key; retry succeeds!
        MediaUploadSession initiatedSession = service.initiateMultipartUpload(sessionId, PRINCIPAL);

        // Verification after Attempt 2: Successfully transitioned to INITIATED
        assertThat(initiatedSession.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
        assertThat(initiatedSession.getProviderUploadId()).isEqualTo("b2-upload-valid-456");
        assertThat(initiatedSession.getLeaseOwner()).isNull();
    }

    // =============================================================================================
    // T7: Historical claim without providerUploadId follows explicit safe recovery or remains blocked
    // =============================================================================================
    @Test
    @DisplayName("T7: Historical claim without providerUploadId remains blocked with actionable classification")
    void t7_historicalClaimWithoutProviderUploadIdRemainsBlockedWithActionableClassification() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId, NOW.minus(Duration.ofHours(3)));
        session.claimInitiation("INITIATION_CLAIM:historical-failed", NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(2)));

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        UploadSessionUnconfirmedInitiationException ex = null;
        try {
            service.initiateMultipartUpload(sessionId, PRINCIPAL);
        } catch (UploadSessionUnconfirmedInitiationException e) {
            ex = e;
        }

        assertThat(ex).isNotNull();
        assertThat(ex.getSessionId()).isEqualTo(sessionId);
        assertThat(ex.getLeaseOwner()).isEqualTo("INITIATION_CLAIM:historical-failed");
        assertThat(ex.getErrorCode()).isEqualTo("MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM");
        assertThat(ex.getMessage()).contains("reconciliation");
    }

    // =============================================================================================
    // T9: Wrong actor or wrong Entry cannot initiate or access another user's upload session
    // =============================================================================================
    @Test
    @DisplayName("T9: Request from unauthorized principal is rejected before acquiring initiation claim")
    void t9_wrongActorCannotInitiateUploadSession() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, "unauthorized-intruder"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not authorized");

        assertThat(session.getLeaseOwner()).isNull();
        verify(multipartStoragePort, never()).initiateMultipartUpload(any(), any(), any());
    }

    // =============================================================================================
    // T10: Domain releaseInitiationClaim invariant enforcement
    // =============================================================================================
    @Test
    @DisplayName("T10: Domain releaseInitiationClaim only permitted on ALLOCATED sessions")
    void t10_releaseInitiationClaimPermittedOnlyOnAllocatedSessions() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        session.claimInitiation("INITIATION_CLAIM:test", NOW.plus(Duration.ofMinutes(5)), NOW);

        // Allowed on ALLOCATED
        session.releaseInitiationClaim(NOW);
        assertThat(session.getLeaseOwner()).isNull();

        // Prohibited once INITIATED
        session.markInitiated("b2-upload-id", NOW);
        assertThatThrownBy(() -> session.releaseInitiationClaim(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot release initiation claim on session with status: INITIATED");
    }

    // =============================================================================================
    // TASK B: Domain Initiation Claim Ownership Invariant Tests (B1 - B6)
    // =============================================================================================

    @Test
    @DisplayName("B1: Correct token + ALLOCATED releases initiation claim successfully")
    void b1_correctTokenAndAllocatedReleasesClaimSuccessfully() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        String token = "INITIATION_CLAIM:" + UUID.randomUUID();
        session.claimInitiation(token, NOW.plus(Duration.ofMinutes(5)), NOW);

        session.releaseInitiationClaim(token, NOW);

        assertThat(session.getLeaseOwner()).isNull();
        assertThat(session.getLeaseExpiresAt()).isNull();
    }

    @Test
    @DisplayName("B2: Null lease owner rejected with IllegalStateException on release")
    void b2_nullLeaseOwnerRejectedOnRelease() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);

        assertThatThrownBy(() -> session.releaseInitiationClaim("INITIATION_CLAIM:test", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot release initiation claim: expected owner 'INITIATION_CLAIM:test' but found 'null'");

        assertThatThrownBy(() -> session.releaseInitiationClaim(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot release initiation claim when leaseOwner is null.");
    }

    @Test
    @DisplayName("B3: Different initiation token rejected without releasing lease")
    void b3_differentInitiationTokenRejectedWithoutRelease() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        session.claimInitiation("INITIATION_CLAIM:worker-1", NOW.plus(Duration.ofMinutes(5)), NOW);

        assertThatThrownBy(() -> session.releaseInitiationClaim("INITIATION_CLAIM:worker-2", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected owner 'INITIATION_CLAIM:worker-2' but found 'INITIATION_CLAIM:worker-1'");

        // Original worker's lease must remain held!
        assertThat(session.getLeaseOwner()).isEqualTo("INITIATION_CLAIM:worker-1");
    }

    @Test
    @DisplayName("B4: Cleanup-owned token rejected from initiation release")
    void b4_cleanupOwnedTokenRejectedFromInitiationRelease() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        String cleanupToken = "CLEANUP_CLAIM:" + UUID.randomUUID();
        MediaUploadSession sessionWithCleanupLease = MediaUploadSession.rehydrate(
                sessionId,
                session.getInitiatorPrincipalId(),
                session.getConsumerContext(),
                session.getTargetEntityType(),
                session.getTargetEntityId(),
                session.getIdempotencyKey(),
                session.getMediaType(),
                session.getDeclaredMimeType(),
                session.getDeclaredSizeBytes(),
                session.getStorageProviderId(),
                session.getStorageBucketName(),
                session.getStorageKey(),
                session.getStorageAllocationId(),
                session.getProviderUploadId(),
                session.getPartSizeBytes(),
                session.getExpectedPartCount(),
                session.getDeclaredContentHash(),
                session.getOriginalFilename(),
                session.getClientTag(),
                MediaUploadSessionStatus.ALLOCATED,
                session.getExpiresAt(),
                cleanupToken,
                NOW.plus(Duration.ofMinutes(4)),
                session.getRegisteredAssetId(),
                session.getCleanupStatus(),
                0,
                null,
                null,
                session.getCreatedAt(),
                session.getUpdatedAt(),
                null,
                1L,
                null
        );

        assertThatThrownBy(() -> sessionWithCleanupLease.releaseInitiationClaim(cleanupToken, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot release initiation claim while cleanup lease is held");
    }

    @Test
    @DisplayName("B5: Session status changed (not ALLOCATED) rejected with IllegalStateException")
    void b5_sessionStatusChangedRejectedOnRelease() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        String token = "INITIATION_CLAIM:" + UUID.randomUUID();
        session.claimInitiation(token, NOW.plus(Duration.ofMinutes(5)), NOW);
        session.markInitiated("b2-upload-123", NOW);

        assertThatThrownBy(() -> session.releaseInitiationClaim(token, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot release initiation claim on session with status: INITIATED");
    }

    @Test
    @DisplayName("B6: Invalid expected claim token (null or blank) rejected with IllegalArgumentException")
    void b6_invalidExpectedClaimTokenRejected() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        session.claimInitiation("INITIATION_CLAIM:worker-1", NOW.plus(Duration.ofMinutes(5)), NOW);

        assertThatThrownBy(() -> session.releaseInitiationClaim(null, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> session.releaseInitiationClaim("   ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =============================================================================================
    // TASK C: Compensation Failure Must Be Fail-Closed Tests (C2 - C5, C12)
    // =============================================================================================

    @Test
    @DisplayName("C2: Compensation throws persistence error -> throws UploadSessionUnconfirmedInitiationException (fail-closed)")
    void c2_compensationThrowsPersistenceErrorYieldsUnconfirmedOutcome() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));

        when(sessionRepositoryPort.save(any(MediaUploadSession.class)))
                .thenAnswer(inv -> inv.getArgument(0)) // for TX-A
                .thenThrow(new RuntimeException("Database connection pool exhausted during compensation save"));

        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any()))
                .thenThrow(new StorageInitiationRejectedException("Provider definitively rejected (MalformedAccessKeyId)"));

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                .isInstanceOf(UploadSessionUnconfirmedInitiationException.class)
                .hasMessageContaining("claim compensation transaction failed");
    }

    @Test
    @DisplayName("C3: Compensation token mismatch -> throws UploadSessionUnconfirmedInitiationException (fail-closed)")
    void c3_compensationTokenMismatchYieldsUnconfirmedOutcome() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        MediaUploadSession concurrentSession = createAllocatedSession(sessionId, allocId, targetId);
        concurrentSession.claimInitiation("INITIATION_CLAIM:concurrent-hijacker", NOW.plus(Duration.ofMinutes(5)), NOW);

        when(sessionRepositoryPort.findById(sessionId))
                .thenReturn(Optional.of(session)) // for TX-A
                .thenReturn(Optional.of(concurrentSession)); // for compensation
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any()))
                .thenThrow(new StorageInitiationRejectedException("Provider definitively rejected (MalformedAccessKeyId)"));

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                .isInstanceOf(UploadSessionUnconfirmedInitiationException.class)
                .hasMessageContaining("outcome: TOKEN_MISMATCH");
    }

    @Test
    @DisplayName("C4: Optimistic locking conflict during compensation -> throws UploadSessionUnconfirmedInitiationException")
    void c4_optimisticLockingConflictDuringCompensationYieldsUnconfirmedOutcome() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));

        when(sessionRepositoryPort.save(any(MediaUploadSession.class)))
                .thenAnswer(inv -> inv.getArgument(0)) // for TX-A
                .thenThrow(new OptimisticLockingFailureException("Simulated optimistic lock failure during compensation"));

        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any()))
                .thenThrow(new StorageInitiationRejectedException("Provider definitively rejected (InvalidAccessKeyId)"));

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                .isInstanceOf(UploadSessionUnconfirmedInitiationException.class)
                .hasMessageContaining("claim compensation transaction failed");
    }

    @Test
    @DisplayName("C5: Transaction commit failure / rollback during compensation -> throws UploadSessionUnconfirmedInitiationException")
    void c5_compensationCommitFailureYieldsUnconfirmedOutcome() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.initiateMultipartUpload(any(), any(), any()))
                .thenThrow(new StorageInitiationRejectedException("Provider definitively rejected (SignatureDoesNotMatch)"));

        org.mockito.Mockito.doNothing() // TX-A commit
                .doThrow(new org.springframework.transaction.TransactionSystemException("Simulated commit failure")) // compensation commit
                .when(transactionManager).commit(any());

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                .isInstanceOf(UploadSessionUnconfirmedInitiationException.class)
                .hasMessageContaining("claim compensation transaction failed");
    }

    @Test
    @DisplayName("C12: Concurrent request cannot release another request's claim")
    void c12_concurrentRequestCannotReleaseAnotherRequestsClaim() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession session = createAllocatedSession(sessionId, allocId, targetId);
        String worker1Token = "INITIATION_CLAIM:worker-1";
        session.claimInitiation(worker1Token, NOW.plus(Duration.ofMinutes(5)), NOW);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> session.releaseInitiationClaim("INITIATION_CLAIM:worker-2", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected owner 'INITIATION_CLAIM:worker-2' but found 'INITIATION_CLAIM:worker-1'");

        assertThat(session.getLeaseOwner()).isEqualTo(worker1Token);
    }

    // =============================================================================================
    // T11: Controller error mapping tests
    // =============================================================================================
    @Test
    @DisplayName("T11: AdminScreenEntryVideoController maps initiation exceptions to appropriate HTTP responses")
    void t11_controllerMapsInitiationExceptionsToSanitizedResponses() {
        AdminScreenEntryVideoController controller = new AdminScreenEntryVideoController(
                org.mockito.Mockito.mock(com.universe.screen.application.usecase.GetScreenTitleDetailUseCase.class),
                org.mockito.Mockito.mock(com.universe.screen.application.usecase.GetScreenEntryDetailUseCase.class),
                org.mockito.Mockito.mock(com.universe.screen.application.usecase.AttachScreenEntryVideoUseCase.class),
                org.mockito.Mockito.mock(com.universe.screen.application.usecase.DetachScreenEntryVideoUseCase.class),
                org.mockito.Mockito.mock(com.universe.screen.application.usecase.ReplaceScreenEntryVideoUseCase.class),
                org.mockito.Mockito.mock(com.universe.media.contracts.interfaces.MediaContract.class),
                org.mockito.Mockito.mock(com.universe.screen.application.usecase.DiscardScreenEntryVideoUploadUseCase.class)
        );
        UUID sessionId = UUID.randomUUID();

        // 1. Conflict on in-progress initiation
        UploadSessionInitiationConflictException conflictEx =
                new UploadSessionInitiationConflictException(sessionId, "INITIATION_CLAIM:worker", "Concurrent initiation in progress");
        ResponseEntity<ScreenVideoErrorResponse> conflictResp = controller.handleInitiationConflict(conflictEx);
        assertThat(conflictResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflictResp.getBody()).isNotNull();
        assertThat(conflictResp.getBody().error()).isEqualTo("MEDIA_UPLOAD_INITIATION_IN_PROGRESS");
        assertThat(conflictResp.getBody().message()).doesNotContain("INITIATION_CLAIM");

        // 2. Conflict on historical unconfirmed claim requiring reconciliation
        UploadSessionUnconfirmedInitiationException unconfirmedEx =
                new UploadSessionUnconfirmedInitiationException(sessionId, "INITIATION_CLAIM:worker", "Unconfirmed earlier initiation");
        ResponseEntity<ScreenVideoErrorResponse> unconfirmedResp = controller.handleUnconfirmedInitiation(unconfirmedEx);
        assertThat(unconfirmedResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(unconfirmedResp.getBody()).isNotNull();
        assertThat(unconfirmedResp.getBody().error()).isEqualTo("MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM");
        assertThat(unconfirmedResp.getBody().message()).contains("đối soát an toàn");

        // 3. Bad Gateway on definitive provider rejection
        StorageInitiationRejectedException rejectedEx =
                new StorageInitiationRejectedException("Provider rejected (MalformedAccessKeyId)");
        ResponseEntity<ScreenVideoErrorResponse> rejectedResp = controller.handleStorageInitiationRejected(rejectedEx);
        assertThat(rejectedResp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(rejectedResp.getBody()).isNotNull();
        assertThat(rejectedResp.getBody().error()).isEqualTo("MEDIA_STORAGE_INITIATION_REJECTED");
        assertThat(rejectedResp.getBody().message()).doesNotContain("MalformedAccessKeyId");

        // 4. Gateway Timeout on ambiguous provider timeout
        StorageAmbiguousInitiationException timeoutEx =
                new StorageAmbiguousInitiationException("Provider call timed out");
        ResponseEntity<ScreenVideoErrorResponse> timeoutResp = controller.handleStorageAmbiguousInitiation(timeoutEx);
        assertThat(timeoutResp.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(timeoutResp.getBody()).isNotNull();
        assertThat(timeoutResp.getBody().error()).isEqualTo("MEDIA_STORAGE_INITIATION_AMBIGUOUS");
        assertThat(timeoutResp.getBody().message()).contains("đối soát");
    }
}
