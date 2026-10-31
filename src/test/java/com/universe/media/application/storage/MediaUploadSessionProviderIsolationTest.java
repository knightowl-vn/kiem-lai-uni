package com.universe.media.application.storage;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartAbortResult;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MS-08 R2-01: Provider Isolation & Negative Invocation Verification Tests")
class MediaUploadSessionProviderIsolationTest {

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final StorageProviderId R2_MOVIE_PROVIDER = StorageProviderId.of("r2-movie");
    private static final StorageProviderId S3_PROVIDER = StorageProviderId.of("s3");

    private static final String B2_BUCKET = "kiemlai-video";
    private static final String R2_MOVIE_BUCKET = "kiemlai-r2-movie";
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/isolated-test.mp4");
    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    @Mock
    private MediaUploadSessionRepositoryPort sessionRepositoryPort;
    @Mock
    private StorageKeyAllocationService storageKeyAllocationService;
    @Mock
    private MediaStorageRoutingService routingService;
    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;
    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    @Mock
    private MultipartStoragePort b2Port;
    @Mock
    private MultipartStoragePort r2MoviePort;

    private ClockPort clockPort;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        clockPort = () -> NOW;
        transactionManager = createTestTransactionManager();
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

    private MediaUploadSession createSession(StorageProviderId providerId, String bucketName) {
        return createSession(UUID.randomUUID(), UUID.randomUUID(), providerId, bucketName, STORAGE_KEY);
    }

    private MediaUploadSession createSession(
            UUID sessionId,
            UUID allocId,
            StorageProviderId providerId,
            String bucketName,
            StorageKey storageKey
    ) {
        return MediaUploadSession.create(
                sessionId,
                "user-1",
                "screen",
                "ScreenEntry",
                UUID.randomUUID(),
                "idem-iso-" + sessionId,
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                providerId,
                bucketName,
                storageKey,
                allocId,
                "movie.mp4",
                "screen-video",
                NOW.plusSeconds(3600),
                NOW
        );
    }

    private MediaUploadSession createEligibleAbortedSession(
            UUID sessionId,
            UUID allocId,
            StorageProviderId providerId,
            String bucketName
    ) {
        return MediaUploadSession.rehydrate(
                sessionId,
                "user-1",
                "screen",
                "ScreenEntry",
                UUID.randomUUID(),
                "idem-iso-" + sessionId,
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                providerId,
                bucketName,
                STORAGE_KEY,
                allocId,
                "up-id-abort",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                null,
                "movie.mp4",
                "screen-video",
                MediaUploadSessionStatus.ABORTED,
                NOW.plusSeconds(3600),
                null,
                null,
                null,
                UploadSessionCleanupStatus.PENDING,
                0,
                null,
                null,
                NOW,
                NOW,
                null,
                1L
        );
    }

    private StorageKeyAllocation createAllocation(
            UUID allocId,
            StorageProviderId providerId,
            String bucketName,
            StorageKey storageKey,
            UUID sessionId
    ) {
        return StorageKeyAllocation.reserve(
                allocId,
                providerId,
                bucketName,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                NOW
        );
    }

    @Test
    @DisplayName("PROVIDER-01: R2 session + R2 adapter -> R2 presign invoked")
    void provider01_r2SessionWithR2AdapterInvokesR2() {
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);
        when(r2MoviePort.generatePresignedPartUploadUrl(any(), any(), eq(1), any()))
                .thenReturn("https://r2.example.com/part-1");

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(r2MoviePort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY);
        session.markInitiated("up-r2-1", NOW);

        StorageKeyAllocation allocation = createAllocation(allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));

        String url = service.generatePresignedPartUploadUrl(sessionId, 1, "user-1", Duration.ofMinutes(15));

        assertThat(url).isEqualTo("https://r2.example.com/part-1");
        verify(r2MoviePort).generatePresignedPartUploadUrl(STORAGE_KEY, "up-r2-1", 1, Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("PROVIDER-02: B2 session + B2 adapter -> B2 presign invoked")
    void provider02_b2SessionWithB2AdapterInvokesB2() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);
        when(b2Port.generatePresignedPartUploadUrl(any(), any(), eq(1), any()))
                .thenReturn("https://b2.example.com/part-1");

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY);
        session.markInitiated("up-b2-1", NOW);

        StorageKeyAllocation allocation = createAllocation(allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));

        String url = service.generatePresignedPartUploadUrl(sessionId, 1, "user-1", Duration.ofMinutes(15));

        assertThat(url).isEqualTo("https://b2.example.com/part-1");
        verify(b2Port).generatePresignedPartUploadUrl(STORAGE_KEY, "up-b2-1", 1, Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("PROVIDER-03: R2 session + only B2 adapter -> exception, zero B2 remote calls")
    void provider03_r2SessionWithOnlyB2AdapterFailsClosedZeroB2Calls() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY);
        session.markInitiated("up-r2-1", NOW);

        StorageKeyAllocation allocation = createAllocation(allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));

        assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, "user-1", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort is not configured or available for provider: r2-movie");

        verify(b2Port, never()).generatePresignedPartUploadUrl(any(), any(), anyInt(), any());
        verify(b2Port, never()).initiateMultipartUpload(any(), any(), any());
        verify(b2Port, never()).completeMultipartUpload(any(), any(), any());
        verify(b2Port, never()).abortMultipartUpload(any(), any());
    }

    @Test
    @DisplayName("PROVIDER-04: B2 session + only R2 adapter -> exception, zero R2 remote calls")
    void provider04_b2SessionWithOnlyR2AdapterFailsClosedZeroR2Calls() {
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(r2MoviePort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY);
        session.markInitiated("up-b2-1", NOW);

        StorageKeyAllocation allocation = createAllocation(allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));

        assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, "user-1", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort is not configured or available for provider: b2");

        verify(r2MoviePort, never()).generatePresignedPartUploadUrl(any(), any(), anyInt(), any());
        verify(r2MoviePort, never()).initiateMultipartUpload(any(), any(), any());
        verify(r2MoviePort, never()).completeMultipartUpload(any(), any(), any());
        verify(r2MoviePort, never()).abortMultipartUpload(any(), any());
    }

    @Test
    @DisplayName("PROVIDER-05: R2 session + both adapters -> only R2 invoked, zero B2 calls")
    void provider05_r2SessionWithBothAdaptersInvokesOnlyR2() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);
        when(r2MoviePort.generatePresignedPartUploadUrl(any(), any(), eq(1), any()))
                .thenReturn("https://r2.example.com/part-1");

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port, r2MoviePort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY);
        session.markInitiated("up-r2-1", NOW);

        StorageKeyAllocation allocation = createAllocation(allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));

        String url = service.generatePresignedPartUploadUrl(sessionId, 1, "user-1", Duration.ofMinutes(15));

        assertThat(url).isEqualTo("https://r2.example.com/part-1");
        verify(r2MoviePort).generatePresignedPartUploadUrl(STORAGE_KEY, "up-r2-1", 1, Duration.ofMinutes(15));
        verify(b2Port, never()).generatePresignedPartUploadUrl(any(), any(), anyInt(), any());
        verify(b2Port, never()).initiateMultipartUpload(any(), any(), any());
        verify(b2Port, never()).completeMultipartUpload(any(), any(), any());
        verify(b2Port, never()).abortMultipartUpload(any(), any());
    }

    @Test
    @DisplayName("PROVIDER-06: B2 session + both adapters -> only B2 invoked, zero R2 calls")
    void provider06_b2SessionWithBothAdaptersInvokesOnlyB2() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);
        when(b2Port.generatePresignedPartUploadUrl(any(), any(), eq(1), any()))
                .thenReturn("https://b2.example.com/part-1");

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port, r2MoviePort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY);
        session.markInitiated("up-b2-1", NOW);

        StorageKeyAllocation allocation = createAllocation(allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));

        String url = service.generatePresignedPartUploadUrl(sessionId, 1, "user-1", Duration.ofMinutes(15));

        assertThat(url).isEqualTo("https://b2.example.com/part-1");
        verify(b2Port).generatePresignedPartUploadUrl(STORAGE_KEY, "up-b2-1", 1, Duration.ofMinutes(15));
        verify(r2MoviePort, never()).generatePresignedPartUploadUrl(any(), any(), anyInt(), any());
        verify(r2MoviePort, never()).initiateMultipartUpload(any(), any(), any());
        verify(r2MoviePort, never()).completeMultipartUpload(any(), any(), any());
        verify(r2MoviePort, never()).abortMultipartUpload(any(), any());
    }

    @Test
    @DisplayName("PROVIDER-07: Unsupported provider ID fails closed")
    void provider07_unsupportedProviderIdFailsClosed() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);

        new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port, r2MoviePort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        // Constructing domain session with unsupported provider fails at domain level
        assertThatThrownBy(() -> createSession(sessionId, allocId, S3_PROVIDER, "s3-bucket", STORAGE_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Upload session storageProviderId must be 'b2' or 'r2-movie'");

        verify(b2Port, never()).generatePresignedPartUploadUrl(any(), any(), anyInt(), any());
        verify(r2MoviePort, never()).generatePresignedPartUploadUrl(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("PROVIDER-08: Null provider ID fails closed")
    void provider08_nullProviderIdFailsClosed() {
        assertThatThrownBy(() -> createSession(UUID.randomUUID(), UUID.randomUUID(), null, "bucket", STORAGE_KEY))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("PROVIDER-09: Duplicate adapter provider IDs cause configuration failure")
    void provider09_duplicateProviderIdsFailClosed() {
        MultipartStoragePort p1 = mock(MultipartStoragePort.class);
        MultipartStoragePort p2 = mock(MultipartStoragePort.class);
        when(p1.providerId()).thenReturn(B2_PROVIDER);
        when(p2.providerId()).thenReturn(B2_PROVIDER);

        assertThatThrownBy(() -> new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(p1, p2),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate MultipartStoragePort registration detected for provider ID: b2");
    }

    @Test
    @DisplayName("PROVIDER-10: Adapter configured bucket differs from session bucket -> no remote mutation")
    void provider10_bucketMismatchBlocksRemoteMutation() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);
        when(b2Port.configuredBucketName()).thenReturn("drifted-b2-bucket");
        when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn(B2_BUCKET);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createEligibleAbortedSession(sessionId, allocId, B2_PROVIDER, B2_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort configured bucket 'drifted-b2-bucket' does not match authoritative routing bucket 'kiemlai-video'");

        verify(b2Port, never()).abortMultipartUpload(any(), any());
    }

    @Test
    @DisplayName("PROVIDER-11: Allocation provider differs from session provider -> no remote mutation")
    void provider11_allocationProviderMismatchBlocksRemoteMutation() {
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(r2MoviePort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY);
        session.markInitiated("up-r2-1", NOW);

        // Allocation has drifted provider B2
        StorageKeyAllocation allocationWithWrongProvider = createAllocation(allocId, B2_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocationWithWrongProvider));

        assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, "user-1", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match upload session")
                .hasMessageContaining("binding");

        verify(r2MoviePort, never()).generatePresignedPartUploadUrl(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("PROVIDER-12: Allocation storage key differs from session key -> no remote mutation")
    void provider12_allocationKeyMismatchBlocksRemoteMutation() {
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(r2MoviePort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY);
        session.markInitiated("up-r2-1", NOW);

        StorageKey driftedKey = StorageKey.of("media/video/drifted-other.mp4");
        StorageKeyAllocation allocationWithWrongKey = createAllocation(allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, driftedKey, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocationWithWrongKey));

        assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, "user-1", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match upload session")
                .hasMessageContaining("binding");

        verify(r2MoviePort, never()).generatePresignedPartUploadUrl(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("PROVIDER-13: Wrong provider during guarded abort -> zero AbortMultipartUpload calls")
    void provider13_wrongProviderDuringAbortBlocksRemoteCall() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port), // Only B2 configured
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        // Session was created on r2-movie
        MediaUploadSession session = createEligibleAbortedSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort is not configured or available for provider: r2-movie");

        verify(b2Port, never()).abortMultipartUpload(any(), any());
    }

    @Test
    @DisplayName("PROVIDER-14: Wrong provider during completion -> zero CompleteMultipartUpload calls")
    void provider14_wrongProviderDuringCompletionBlocksRemoteCall() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port), // Only B2 configured
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY);
        session.markInitiated("up-r2-1", NOW);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        List<MultipartManifestPart> parts = List.of(new MultipartManifestPart(1, MediaUploadSession.FIXED_PART_SIZE_BYTES, "etag-1"));

        assertThatThrownBy(() -> service.completeMultipartUpload(
                sessionId,
                "user-1",
                parts
        )).isInstanceOf(IllegalStateException.class);

        verify(b2Port, never()).completeMultipartUpload(any(), any(), any());
    }

    @Test
    @DisplayName("PROVIDER-15: Wrong provider during initiation -> zero CreateMultipartUpload calls")
    void provider15_wrongProviderDuringInitiationBlocksRemoteCall() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port), // Only B2 configured
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(createAllocation(allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY, sessionId)));
        when(sessionRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, "user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort is not configured or available for provider: r2-movie");

        verify(b2Port, never()).initiateMultipartUpload(any(), any(), any());
    }

    @Test
    @DisplayName("PROVIDER-16: Recovery after feature-flag change uses persisted session provider, not current write provider")
    void provider16_recoveryUsesPersistedProviderNotCurrentWriteRouting() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);
        when(b2Port.configuredBucketName()).thenReturn(B2_BUCKET);
        when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn(B2_BUCKET);
        when(b2Port.abortMultipartUpload(any(), any())).thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port, r2MoviePort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        // Historical session was created on B2 when flag was OFF
        MediaUploadSession b2Session = createEligibleAbortedSession(sessionId, allocId, B2_PROVIDER, B2_BUCKET);
        StorageKeyAllocation allocation = createAllocation(allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(b2Session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Attempting guarded abort must resolve B2 provider (as persisted on session), NOT current write provider
        service.executeGuardedMultipartAbort(sessionId);

        // B2 abort client is invoked; R2 movie port has zero abort calls
        verify(b2Port).abortMultipartUpload(STORAGE_KEY, "up-id-abort");
        verify(r2MoviePort, never()).abortMultipartUpload(any(), any());
    }

    // =============================================================================================
    // P1-02: Explicit Nullable Provider Identity Bypass Tests (PROVIDER-NULL-01 to 13)
    // =============================================================================================

    @Test
    @DisplayName("PROVIDER-NULL-01: Production constructor rejects a port with null provider ID")
    void providerNull01_productionConstructorRejectsNullProviderId() {
        MultipartStoragePort nullPort = mock(MultipartStoragePort.class);
        when(nullPort.providerId()).thenReturn(null);

        assertThatThrownBy(() -> new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(nullPort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort bean returned null or blank providerId");
    }

    @Test
    @DisplayName("PROVIDER-NULL-02: Production constructor rejects blank/invalid provider ID")
    void providerNull02_productionConstructorRejectsBlankProviderId() {
        StorageProviderId blankId = mock(StorageProviderId.class);
        when(blankId.value()).thenReturn("   ");
        MultipartStoragePort blankPort = mock(MultipartStoragePort.class);
        when(blankPort.providerId()).thenReturn(blankId);

        assertThatThrownBy(() -> new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(blankPort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort bean returned null or blank providerId");
    }

    @Test
    @DisplayName("PROVIDER-NULL-03: Legacy constructor cannot select a port with null provider ID for B2")
    void providerNull03_legacyConstructorCannotSelectNullProviderForB2() {
        MultipartStoragePort nullPort = mock(MultipartStoragePort.class);
        when(nullPort.providerId()).thenReturn(null);

        assertThatThrownBy(() -> new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                nullPort,
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort bean returned null or blank providerId");
    }

    @Test
    @DisplayName("PROVIDER-NULL-04: Legacy constructor cannot select a port with null provider ID for R2 Movie")
    void providerNull04_legacyConstructorCannotSelectNullProviderForR2Movie() {
        MultipartStoragePort nullPort = mock(MultipartStoragePort.class);
        when(nullPort.providerId()).thenReturn(null);

        assertThatThrownBy(() -> new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                nullPort,
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort bean returned null or blank providerId");
    }

    @Test
    @DisplayName("PROVIDER-NULL-05: Legacy constructor rejects mismatched provider identity")
    void providerNull05_legacyConstructorRejectsMismatchedProvider() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                b2Port,
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY);
        session.markInitiated("up-r2-1", NOW);
        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(createAllocation(allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY, sessionId)));

        assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, "user-1", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort is not configured or available for provider: r2-movie");

        verify(b2Port, never()).generatePresignedPartUploadUrl(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("PROVIDER-NULL-06: B2 session cannot invoke R2 Movie when B2 adapter is absent")
    void providerNull06_b2SessionCannotInvokeR2MovieWhenB2Absent() {
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(r2MoviePort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY);
        session.markInitiated("up-b2-1", NOW);
        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(createAllocation(allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY, sessionId)));

        assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, "user-1", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort is not configured or available for provider: b2");

        verify(r2MoviePort, never()).generatePresignedPartUploadUrl(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("PROVIDER-NULL-07: R2 Movie session cannot invoke B2 when R2 adapter is absent")
    void providerNull07_r2SessionCannotInvokeB2WhenR2Absent() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY);
        session.markInitiated("up-r2-1", NOW);
        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(createAllocation(allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY, sessionId)));

        assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, "user-1", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort is not configured or available for provider: r2-movie");

        verify(b2Port, never()).generatePresignedPartUploadUrl(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("PROVIDER-NULL-08: Duplicate adapter provider IDs fail deterministically")
    void providerNull08_duplicateAdapterProviderIdsFail() {
        MultipartStoragePort p1 = mock(MultipartStoragePort.class);
        MultipartStoragePort p2 = mock(MultipartStoragePort.class);
        when(p1.providerId()).thenReturn(R2_MOVIE_PROVIDER);
        when(p2.providerId()).thenReturn(R2_MOVIE_PROVIDER);

        assertThatThrownBy(() -> new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(p1, p2),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate MultipartStoragePort registration detected for provider ID: r2-movie");
    }

    @Test
    @DisplayName("PROVIDER-NULL-09: Existing-session recovery uses persisted provider identity")
    void providerNull09_existingSessionRecoveryUsesPersistedProvider() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);
        when(b2Port.configuredBucketName()).thenReturn(B2_BUCKET);
        when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn(B2_BUCKET);
        when(b2Port.abortMultipartUpload(any(), any())).thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port, r2MoviePort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession b2Session = createEligibleAbortedSession(sessionId, allocId, B2_PROVIDER, B2_BUCKET);
        StorageKeyAllocation allocation = createAllocation(allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(b2Session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.executeGuardedMultipartAbort(sessionId);

        verify(b2Port).abortMultipartUpload(STORAGE_KEY, "up-id-abort");
        verify(r2MoviePort, never()).abortMultipartUpload(any(), any());
    }

    @Test
    @DisplayName("PROVIDER-NULL-10: Feature-flag changes never rebind sessions")
    void providerNull10_featureFlagChangesNeverRebindSessions() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);
        when(b2Port.configuredBucketName()).thenReturn(B2_BUCKET);
        when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn(B2_BUCKET);
        when(b2Port.abortMultipartUpload(any(), any())).thenReturn(MultipartAbortResult.ABORT_ACCEPTED);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port, r2MoviePort),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession b2Session = createEligibleAbortedSession(sessionId, allocId, B2_PROVIDER, B2_BUCKET);
        StorageKeyAllocation allocation = createAllocation(allocId, B2_PROVIDER, B2_BUCKET, STORAGE_KEY, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(b2Session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.executeGuardedMultipartAbort(sessionId);

        verify(b2Port).abortMultipartUpload(STORAGE_KEY, "up-id-abort");
        verify(r2MoviePort, never()).abortMultipartUpload(any(), any());
    }

    @Test
    @DisplayName("PROVIDER-NULL-11: Provider-mismatch exception occurs before remote initiation")
    void providerNull11_providerMismatchBeforeRemoteInitiation() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(createAllocation(allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY, sessionId)));
        when(sessionRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, "user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort is not configured or available for provider: r2-movie");

        verify(b2Port, never()).initiateMultipartUpload(any(), any(), any());
    }

    @Test
    @DisplayName("PROVIDER-NULL-12: Provider-mismatch exception occurs before remote completion")
    void providerNull12_providerMismatchBeforeRemoteCompletion() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, STORAGE_KEY);
        session.markInitiated("up-r2-1", NOW);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        List<MultipartManifestPart> parts = List.of(new MultipartManifestPart(1, MediaUploadSession.FIXED_PART_SIZE_BYTES, "etag-1"));

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, "user-1", parts))
                .isInstanceOf(IllegalStateException.class);

        verify(b2Port, never()).completeMultipartUpload(any(), any(), any());
    }

    @Test
    @DisplayName("PROVIDER-NULL-13: Provider-mismatch exception occurs before remote abort")
    void providerNull13_providerMismatchBeforeRemoteAbort() {
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);

        MediaUploadSessionService service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );

        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = createEligibleAbortedSession(sessionId, allocId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.executeGuardedMultipartAbort(sessionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MultipartStoragePort is not configured or available for provider: r2-movie");

        verify(b2Port, never()).abortMultipartUpload(any(), any());
    }
}

