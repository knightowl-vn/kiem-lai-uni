package com.universe.media.application.storage;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MediaUploadSessionService: Safe Session Expiration Tests (MS-08E2-L3-2)")
class MediaUploadSessionServiceExpireTest {

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
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/ms08-expire.mp4");
    private static final Instant T0 = Instant.parse("2026-10-18T12:00:00Z");
    private static final String VALID_HASH = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @BeforeEach
    void setUp() {
        currentTime = new AtomicReference<>(T0);
        clockPort = currentTime::get;
        transactionManager = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };

        org.mockito.Mockito.lenient().when(multipartStoragePort.providerId()).thenReturn(StorageProviderId.of("b2"));

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
    }

    private MediaUploadSession buildSession(
            UUID sessionId,
            UUID allocationId,
            MediaUploadSessionStatus status,
            UploadSessionCleanupStatus cleanupStatus,
            String providerUploadId,
            Instant expiresAt,
            String leaseOwner,
            Instant leaseExpiresAt,
            String declaredContentHash,
            String manifestHash,
            UUID registeredAssetId,
            Instant completedAt
    ) {
        return MediaUploadSession.rehydrate(
                sessionId,
                "user-principal-expire",
                "screen",
                "ScreenEntry",
                UUID.randomUUID(),
                "idem-" + UUID.randomUUID(),
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                allocationId,
                providerUploadId,
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                declaredContentHash,
                "video.mp4",
                "screen-video",
                status,
                expiresAt,
                leaseOwner,
                leaseExpiresAt,
                registeredAssetId,
                cleanupStatus,
                0,
                null,
                null,
                T0.minus(2, ChronoUnit.HOURS),
                T0.minus(1, ChronoUnit.HOURS),
                completedAt,
                1L,
                manifestHash
        );
    }

    @Test
    @DisplayName("Successfully expires an active INITIATED session past expiresAt, setting status=EXPIRED, cleanupStatus=PENDING, and tombstoning allocation")
    void shouldExpireInitiatedSessionPastExpiresAt() {
        UUID sessionId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        Instant pastExpiry = T0.minus(10, ChronoUnit.MINUTES);

        MediaUploadSession session = buildSession(
                sessionId, allocationId, MediaUploadSessionStatus.INITIATED, UploadSessionCleanupStatus.NONE,
                "b2-upload-123", pastExpiry, null, null, null, null, null, null
        );

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<MediaUploadSession> result = service.expireSession(sessionId);

        assertThat(result).isPresent();
        MediaUploadSession expired = result.get();
        assertThat(expired.getStatus()).isEqualTo(MediaUploadSessionStatus.EXPIRED);
        assertThat(expired.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.PENDING);

        verify(sessionRepositoryPort).save(any(MediaUploadSession.class));
        verify(storageKeyAllocationService).tombstoneAllocation(allocationId);
    }

    @Test
    @DisplayName("Successfully expires an active ALLOCATED session past expiresAt without providerUploadId, setting status=EXPIRED, cleanupStatus=NONE, and tombstoning allocation")
    void shouldExpireAllocatedSessionPastExpiresAt() {
        UUID sessionId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        Instant pastExpiry = T0.minus(10, ChronoUnit.MINUTES);

        MediaUploadSession session = buildSession(
                sessionId, allocationId, MediaUploadSessionStatus.ALLOCATED, UploadSessionCleanupStatus.NONE,
                null, pastExpiry, null, null, null, null, null, null
        );

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<MediaUploadSession> result = service.expireSession(sessionId);

        assertThat(result).isPresent();
        MediaUploadSession expired = result.get();
        assertThat(expired.getStatus()).isEqualTo(MediaUploadSessionStatus.EXPIRED);
        assertThat(expired.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.NONE);

        verify(sessionRepositoryPort).save(any(MediaUploadSession.class));
        verify(storageKeyAllocationService).tombstoneAllocation(allocationId);
    }

    @Test
    @DisplayName("Skips session if expiresAt is still in the future")
    void shouldSkipSessionIfNotYetExpired() {
        UUID sessionId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        Instant futureExpiry = T0.plus(10, ChronoUnit.MINUTES);

        MediaUploadSession session = buildSession(
                sessionId, allocationId, MediaUploadSessionStatus.INITIATED, UploadSessionCleanupStatus.NONE,
                "b2-upload-123", futureExpiry, null, null, null, null, null, null
        );

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        Optional<MediaUploadSession> result = service.expireSession(sessionId);

        assertThat(result).isEmpty();
        verify(sessionRepositoryPort, never()).save(any());
        verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
    }

    @Test
    @DisplayName("Skips session if status is COMPLETING")
    void shouldSkipSessionInCompletingStatus() {
        UUID sessionId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        Instant pastExpiry = T0.minus(10, ChronoUnit.MINUTES);

        MediaUploadSession session = buildSession(
                sessionId, allocationId, MediaUploadSessionStatus.COMPLETING, UploadSessionCleanupStatus.NONE,
                "b2-upload-123", pastExpiry, null, null, null, null, null, null
        );

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        Optional<MediaUploadSession> result = service.expireSession(sessionId);

        assertThat(result).isEmpty();
        verify(sessionRepositoryPort, never()).save(any());
        verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
    }

    @Test
    @DisplayName("Skips session if status is COMPLETED")
    void shouldSkipSessionInCompletedStatus() {
        UUID sessionId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        Instant pastExpiry = T0.minus(10, ChronoUnit.MINUTES);

        MediaUploadSession session = buildSession(
                sessionId, allocationId, MediaUploadSessionStatus.COMPLETED, UploadSessionCleanupStatus.COMPLETED,
                "b2-upload-123", pastExpiry, null, null, VALID_HASH, null, UUID.randomUUID(), T0.minus(1, ChronoUnit.HOURS)
        );

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        Optional<MediaUploadSession> result = service.expireSession(sessionId);

        assertThat(result).isEmpty();
        verify(sessionRepositoryPort, never()).save(any());
        verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
    }

    @Test
    @DisplayName("Skips session if completion evidence (declaredContentHash, manifestHash, registeredAssetId) is present")
    void shouldSkipSessionWithCompletionEvidence() {
        UUID sessionId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        Instant pastExpiry = T0.minus(10, ChronoUnit.MINUTES);

        MediaUploadSession session = buildSession(
                sessionId, allocationId, MediaUploadSessionStatus.INITIATED, UploadSessionCleanupStatus.NONE,
                "b2-upload-123", pastExpiry, null, null, VALID_HASH, null, null, null
        );

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        Optional<MediaUploadSession> result = service.expireSession(sessionId);

        assertThat(result).isEmpty();
        verify(sessionRepositoryPort, never()).save(any());
        verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
    }

    @Test
    @DisplayName("Skips session if active unexpired initiation lease is held")
    void shouldSkipSessionWithActiveInitiationLease() {
        UUID sessionId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        Instant pastExpiry = T0.minus(10, ChronoUnit.MINUTES);
        Instant activeLeaseExpiry = T0.plus(5, ChronoUnit.MINUTES);

        MediaUploadSession session = buildSession(
                sessionId, allocationId, MediaUploadSessionStatus.ALLOCATED, UploadSessionCleanupStatus.NONE,
                null, pastExpiry, "INIT_CLAIM:token-1", activeLeaseExpiry, null, null, null, null
        );

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        Optional<MediaUploadSession> result = service.expireSession(sessionId);

        assertThat(result).isEmpty();
        verify(sessionRepositoryPort, never()).save(any());
        verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
    }

    @Test
    @DisplayName("Throws IllegalArgumentException if session is not found")
    void shouldThrowWhenSessionNotFound() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.expireSession(sessionId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Upload session not found for ID: " + sessionId);
    }
}
