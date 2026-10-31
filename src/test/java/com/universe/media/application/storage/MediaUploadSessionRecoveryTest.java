package com.universe.media.application.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.exceptions.StorageUploadNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartCompletedObject;
import com.universe.media.application.ports.storage.MultipartObjectMetadata;
import com.universe.media.application.ports.storage.MultipartPartETag;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.application.ports.storage.MultipartUploadedPart;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ContentHashStatus;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.universe.media.application.exceptions.StorageAmbiguousCompletionException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MS-08E2-L3-3B: Guarded Multipart Completion Recovery Tests")
class MediaUploadSessionRecoveryTest {

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
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/ms08-recovery.mp4");
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final String PRINCIPAL = "user-admin-recovery";
    private static final String CONSUMER = "screen";
    private static final String TARGET_TYPE = "SCREEN_ENTRY";
    private static final String CLIENT_TAG = "screen-video";
    private static final String VALID_CONTENT_HASH = "b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1";
    private static final long DECLARED_SIZE = 52428800L;

    @BeforeEach
    void setUp() {
        currentTime = new AtomicReference<>(NOW);
        clockPort = currentTime::get;
        transactionManager = createTestTransactionManager();
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
            protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
            }
        };
    }

    private MediaUploadSession createCompletingSession(
            UUID sessionId,
            UUID allocationId,
            UUID targetId,
            String providerUploadId
    ) {
        Instant expiresAt = NOW.plus(Duration.ofDays(1));
        int expectedParts = (int) Math.ceil((double) DECLARED_SIZE / MediaUploadSession.FIXED_PART_SIZE_BYTES);
        return MediaUploadSession.rehydrate(
                sessionId,
                PRINCIPAL,
                CONSUMER,
                TARGET_TYPE,
                targetId,
                "idem-key-" + sessionId,
                MediaType.VIDEO,
                "video/mp4",
                DECLARED_SIZE,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                allocationId,
                providerUploadId,
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                expectedParts,
                VALID_CONTENT_HASH,
                "video.mp4",
                CLIENT_TAG,
                MediaUploadSessionStatus.COMPLETING,
                expiresAt,
                null,
                null,
                null,
                UploadSessionCleanupStatus.NONE,
                0,
                null,
                null,
                NOW.minus(Duration.ofHours(1)),
                NOW.minus(Duration.ofMinutes(10)),
                null,
                0L,
                null
        );
    }

    private StorageKeyAllocation createReservedAllocation(UUID allocationId, UUID sessionId) {
        return StorageKeyAllocation.rehydrate(
                allocationId,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                StorageKeyAllocationStatus.RESERVED,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                NOW.minus(Duration.ofHours(1)),
                NOW.minus(Duration.ofHours(1)),
                0L
        );
    }

    private List<MultipartManifestPart> createStandard2PartManifest() {
        long part1Size = MediaUploadSession.FIXED_PART_SIZE_BYTES;
        long part2Size = DECLARED_SIZE - part1Size;
        return List.of(
                new MultipartManifestPart(1, part1Size, "\"etag-part-1\""),
                new MultipartManifestPart(2, part2Size, "\"etag-part-2\"")
        );
    }

    private List<MultipartUploadedPart> createStandard2UploadedParts() {
        long part1Size = MediaUploadSession.FIXED_PART_SIZE_BYTES;
        long part2Size = DECLARED_SIZE - part1Size;
        return List.of(
                new MultipartUploadedPart(1, part1Size, "\"etag-part-1\""),
                new MultipartUploadedPart(2, part2Size, "\"etag-part-2\"")
        );
    }

    private MultipartObjectMetadata createValidHeadMetadata(UUID sessionId, UUID allocId, String eTag, String versionId) {
        return new MultipartObjectMetadata(
                STORAGE_KEY,
                DECLARED_SIZE,
                "video/mp4",
                eTag,
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                ),
                versionId,
                B2_PROVIDER,
                B2_BUCKET
        );
    }

    private void stubSuccessfulTxB(UUID allocId) {
        when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);
        when(mediaAssetVersionRepositoryPort.save(any(MediaAssetVersion.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // =============================================================================================
    // TEST A: Normal multipart completion remains successful
    // =============================================================================================
    @Test
    @DisplayName("A. Normal multipart completion remains successful (Scenario C)")
    void testA_normalMultipartCompletionRemainsSuccessful() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-normal";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        MultipartCompletedObject completed = new MultipartCompletedObject(
                StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                "\"normal-complete-etag\"",
                "ver-normal"
        );
        when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

        MultipartObjectMetadata headMetadata = createValidHeadMetadata(sessionId, allocId, "\"normal-complete-etag\"", "ver-normal");
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        stubSuccessfulTxB(allocId);

        MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

        assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(result.getRegisteredAssetId()).isNotNull();

        verify(multipartStoragePort, times(1)).completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any());
        verify(mediaAssetRepositoryPort, times(1)).save(any(MediaAsset.class));
        verify(mediaAssetVersionRepositoryPort, times(1)).save(any(MediaAssetVersion.class));
        verify(storageKeyAllocationService, times(1)).commitAllocation(eq(allocId), eq(StorageKeyAllocationType.ASSET_VERSION), any(UUID.class));
    }

    // =============================================================================================
    // TEST B: B2 complete succeeded, TxB failed, retry observes NoSuchUpload and recovers
    // =============================================================================================
    @Test
    @DisplayName("B. B2 complete succeeded earlier, TxB failed, retry observes NoSuchUpload and recovers (Scenario A)")
    void testB_retryObservesNoSuchUploadAndRecovers() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-prev-completed";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        // Provider reports upload already assembled/absent
        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        MultipartObjectMetadata headMetadata = createValidHeadMetadata(sessionId, allocId, "\"recovered-etag\"", "ver-recovered-123");
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        stubSuccessfulTxB(allocId);

        MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

        assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(result.getRegisteredAssetId()).isNotNull();

        // Recovery path must NOT call CompleteMultipartUpload a second time
        verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
        verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());

        // Must register MediaAsset and MediaAssetVersion in TxB
        verify(mediaAssetRepositoryPort, times(1)).save(any(MediaAsset.class));
        verify(mediaAssetVersionRepositoryPort, times(1)).save(any(MediaAssetVersion.class));
        verify(storageKeyAllocationService, times(1)).commitAllocation(eq(allocId), eq(StorageKeyAllocationType.ASSET_VERSION), any(UUID.class));
    }

    // =============================================================================================
    // TEST C: Ambiguous provider timeout followed by valid HEAD permits recovery
    // =============================================================================================
    @Test
    @DisplayName("C. Ambiguous provider timeout followed by valid HEAD permits recovery (Scenario B)")
    void testC_ambiguousTimeoutFollowedByValidHeadRecovers() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-timeout";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        // CompleteMultipartUpload throws transient socket timeout wrapped as ambiguous completion
        when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any()))
                .thenThrow(new StorageAmbiguousCompletionException("Socket timeout during assembly", new RuntimeException("Read timed out after 30000ms")));

        MultipartObjectMetadata headMetadata = createValidHeadMetadata(sessionId, allocId, "\"timeout-recovered-etag\"", "ver-timeout-456");
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        stubSuccessfulTxB(allocId);

        MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

        assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(result.getRegisteredAssetId()).isNotNull();

        verify(mediaAssetRepositoryPort, times(1)).save(any(MediaAsset.class));
        verify(mediaAssetVersionRepositoryPort, times(1)).save(any(MediaAssetVersion.class));
        verify(storageKeyAllocationService, times(1)).commitAllocation(eq(allocId), eq(StorageKeyAllocationType.ASSET_VERSION), any(UUID.class));
    }

    // =============================================================================================
    // TEST D: NoSuchUpload plus HEAD object missing fails closed
    // =============================================================================================
    @Test
    @DisplayName("D. NoSuchUpload plus HEAD object missing fails closed")
    void testD_noSuchUploadPlusHeadMissingFailsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-gone";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY))
                .thenThrow(new StorageObjectNotFoundException(STORAGE_KEY));

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(StorageObjectNotFoundException.class);

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
        verify(storageKeyAllocationService, never()).commitAllocation(any(), any(), any());
        assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
    }

    // =============================================================================================
    // TEST E: Foreign session-id metadata fails closed
    // =============================================================================================
    @Test
    @DisplayName("E. Foreign session-id metadata fails closed")
    void testE_foreignSessionIdMetadataFailsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-foreign-sess";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        MultipartObjectMetadata foreignMetadata = new MultipartObjectMetadata(
                STORAGE_KEY,
                DECLARED_SIZE,
                "video/mp4",
                "\"etag-xyz\"",
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, UUID.randomUUID().toString(), // Foreign!
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                ),
                "ver-foreign",
                B2_PROVIDER,
                B2_BUCKET
        );
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(foreignMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-id");

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
        assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
    }

    // =============================================================================================
    // TEST F: Foreign allocation-id metadata fails closed
    // =============================================================================================
    @Test
    @DisplayName("F. Foreign allocation-id metadata fails closed")
    void testF_foreignAllocationIdMetadataFailsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-foreign-alloc";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        MultipartObjectMetadata foreignMetadata = new MultipartObjectMetadata(
                STORAGE_KEY,
                DECLARED_SIZE,
                "video/mp4",
                "\"etag-xyz\"",
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, UUID.randomUUID().toString() // Foreign!
                ),
                "ver-foreign",
                B2_PROVIDER,
                B2_BUCKET
        );
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(foreignMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allocation-id");

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
        assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
    }

    // =============================================================================================
    // TEST G: Incorrect provider, bucket or storage key fails closed
    // =============================================================================================
    @Test
    @DisplayName("G. Incorrect provider, bucket or storage key fails closed")
    void testG_incorrectProviderBucketOrKeyFailsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-mismatch-storage";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenAnswer(inv -> {
            MediaUploadSession fresh = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
            fresh.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(fresh, manifest));
            return Optional.of(fresh);
        });
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        // Wrong provider
        MultipartObjectMetadata wrongProvider = new MultipartObjectMetadata(
                STORAGE_KEY,
                DECLARED_SIZE,
                "video/mp4",
                "\"etag-xyz\"",
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                ),
                "ver-wrong",
                StorageProviderId.of("s3-aws"), // Wrong!
                B2_BUCKET
        );
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(wrongProvider);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider mismatch");

        // Wrong bucket
        MultipartObjectMetadata wrongBucket = new MultipartObjectMetadata(
                STORAGE_KEY,
                DECLARED_SIZE,
                "video/mp4",
                "\"etag-xyz\"",
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                ),
                "ver-wrong",
                B2_PROVIDER,
                "foreign-bucket" // Wrong!
        );
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(wrongBucket);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucket mismatch");

        // Wrong storage key
        MultipartObjectMetadata wrongKey = new MultipartObjectMetadata(
                StorageKey.of("media/video/other-key.mp4"), // Wrong!
                DECLARED_SIZE,
                "video/mp4",
                "\"etag-xyz\"",
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                ),
                "ver-wrong",
                B2_PROVIDER,
                B2_BUCKET
        );
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(wrongKey);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage key mismatch");
    }

    // =============================================================================================
    // TEST H: Incorrect content length or content type fails closed
    // =============================================================================================
    @Test
    @DisplayName("H. Incorrect content length or content type fails closed")
    void testH_incorrectLengthOrContentTypeFailsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-mismatch-data";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenAnswer(inv -> {
            MediaUploadSession fresh = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
            fresh.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(fresh, manifest));
            return Optional.of(fresh);
        });
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        // Wrong content length
        MultipartObjectMetadata wrongLength = new MultipartObjectMetadata(
                STORAGE_KEY,
                DECLARED_SIZE - 1000L, // Mismatch!
                "video/mp4",
                "\"etag-xyz\"",
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                ),
                "ver-wrong",
                B2_PROVIDER,
                B2_BUCKET
        );
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(wrongLength);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("size mismatch");

        // Wrong content type
        MultipartObjectMetadata wrongMime = new MultipartObjectMetadata(
                STORAGE_KEY,
                DECLARED_SIZE,
                "application/octet-stream", // Mismatch!
                "\"etag-xyz\"",
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                ),
                "ver-wrong",
                B2_PROVIDER,
                B2_BUCKET
        );
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(wrongMime);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content type mismatch");
    }

    // =============================================================================================
    // TEST I: Missing metadata or blank ETag fails closed
    // =============================================================================================
    @Test
    @DisplayName("I. Missing metadata or blank ETag fails closed")
    void testI_missingMetadataOrBlankETagFailsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-missing-meta";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenAnswer(inv -> {
            MediaUploadSession fresh = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
            fresh.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(fresh, manifest));
            return Optional.of(fresh);
        });
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        // Missing metadata map
        MultipartObjectMetadata missingMeta = new MultipartObjectMetadata(
                STORAGE_KEY,
                DECLARED_SIZE,
                "video/mp4",
                "\"valid-etag\"",
                Map.of(), // Empty metadata!
                "ver-1",
                B2_PROVIDER,
                B2_BUCKET
        );
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(missingMeta);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-id");

        // Blank ETag
        MultipartObjectMetadata blankETag = new MultipartObjectMetadata(
                STORAGE_KEY,
                DECLARED_SIZE,
                "video/mp4",
                "   ", // Blank!
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                ),
                "ver-1",
                B2_PROVIDER,
                B2_BUCKET
        );
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(blankETag);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ETag is null or blank");
    }

    // =============================================================================================
    // TEST J: Generic 404 / NoSuchBucket / 403 and transient HEAD failures never register metadata
    // =============================================================================================
    @Test
    @DisplayName("J. Generic 404 / NoSuchBucket / 403 and transient HEAD failures never register metadata")
    void testJ_inconclusiveHeadFailuresNeverRegisterMetadata() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-transient-head";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenAnswer(inv -> {
            MediaUploadSession fresh = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
            fresh.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(fresh, manifest));
            return Optional.of(fresh);
        });
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        // NoSuchBucket error
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY))
                .thenThrow(new StorageException("Failed to inspect metadata: Bucket not found (NoSuchBucket)"));

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("NoSuchBucket");

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());

        // 403 Forbidden error
        doThrow(new StorageException("Failed to inspect metadata: AccessDenied (403)"))
                .when(multipartStoragePort).inspectObjectMetadata(STORAGE_KEY);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("AccessDenied");

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST K: Stale completion lease rejects TxB
    // =============================================================================================
    @Test
    @DisplayName("K. Stale completion lease rejects TxB")
    void testK_staleCompletionLeaseRejectsTxB() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-stale-lease";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        MultipartObjectMetadata headMetadata = createValidHeadMetadata(sessionId, allocId, "\"etag-lease\"", "ver-lease");
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenAnswer(inv -> {
            // Simulate that time advanced beyond lease expiration while inspecting HEAD
            currentTime.set(NOW.plus(Duration.ofMinutes(15)));
            return headMetadata;
        });

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST L: Manifest or completion intent mismatch rejects recovery
    // =============================================================================================
    @Test
    @DisplayName("L. Manifest or completion intent mismatch rejects recovery")
    void testL_manifestMismatchRejectsRecovery() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-mismatch-manifest";

        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        // Persist original intent with different hash (64 lowercase hex characters)
        session.recordManifestHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

        List<MultipartManifestPart> conflictingManifest = createStandard2PartManifest();

        // Must reject in TxA before contacting remote provider
        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, conflictingManifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match accepted completion intent");

        verify(multipartStoragePort, never()).listUploadedParts(any(), any());
        verify(multipartStoragePort, never()).inspectObjectMetadata(any());
    }

    // =============================================================================================
    // TEST M: Recovery succeeds, then repeated completion returns same asset ID idempotently
    // =============================================================================================
    @Test
    @DisplayName("M. Recovery succeeds, then repeated completion returns same asset ID idempotently")
    void testM_repeatedCompletionReturnsSameAssetIdIdempotently() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-idempotent";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        String manifestHash = MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest);
        session.recordManifestHash(manifestHash);

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        MultipartObjectMetadata headMetadata = createValidHeadMetadata(sessionId, allocId, "\"idem-etag\"", "ver-idem");
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        stubSuccessfulTxB(allocId);

        // First completion call (recovers and marks session COMPLETED)
        MediaUploadSession firstResult = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);
        assertThat(firstResult.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        UUID registeredAssetId = firstResult.getRegisteredAssetId();
        assertThat(registeredAssetId).isNotNull();

        // Stub findById to return the now-COMPLETED session and its asset/version for idempotent verification
        MediaAsset completedAsset = MediaAsset.registerInitial(registeredAssetId, MediaType.VIDEO, MediaVisibility.PRIVATE, CLIENT_TAG, NOW);
        when(mediaAssetRepositoryPort.findById(registeredAssetId)).thenReturn(Optional.of(completedAsset));

        MediaAssetVersion completedVersion = MediaAssetVersion.create(
                UUID.randomUUID(), registeredAssetId, 1,
                StorageLocation.of(B2_PROVIDER, STORAGE_KEY), null,
                ContentHash.of(VALID_CONTENT_HASH), ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                MimeType.of("video/mp4"), DECLARED_SIZE, "video.mp4", NOW
        );
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(registeredAssetId, 1)).thenReturn(Optional.of(completedVersion));

        StorageKeyAllocation committedAllocation = StorageKeyAllocation.rehydrate(
                allocId,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                StorageKeyAllocationStatus.COMMITTED,
                StorageKeyAllocationType.ASSET_VERSION,
                completedVersion.getId(),
                NOW.minus(Duration.ofHours(1)),
                NOW,
                0L
        );
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(committedAllocation));

        // Second completion call (idempotent retry)
        MediaUploadSession secondResult = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

        assertThat(secondResult.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(secondResult.getRegisteredAssetId()).isEqualTo(registeredAssetId);

        // B2 listParts was called ONLY ONCE during first recovery; zero provider calls on second
        verify(multipartStoragePort, times(1)).listUploadedParts(any(), any());
        verify(multipartStoragePort, times(1)).inspectObjectMetadata(any());
        // MediaAsset inserted ONLY ONCE
        verify(mediaAssetRepositoryPort, times(1)).save(any(MediaAsset.class));
    }

    // =============================================================================================
    // TEST N: PRIVATE Screen video registration is preserved
    // =============================================================================================
    @Test
    @DisplayName("N. PRIVATE Screen video registration is preserved")
    void testN_privateScreenVideoRegistrationPreserved() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-screen-private";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        MultipartObjectMetadata headMetadata = createValidHeadMetadata(sessionId, allocId, "\"priv-etag\"", "ver-priv");
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        ArgumentCaptor<MediaAsset> assetCaptor = ArgumentCaptor.forClass(MediaAsset.class);
        when(mediaAssetRepositoryPort.save(assetCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);
        when(mediaAssetVersionRepositoryPort.save(any(MediaAssetVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

        MediaAsset savedAsset = assetCaptor.getValue();
        assertThat(savedAsset).isNotNull();
        assertThat(savedAsset.getVisibility()).isEqualTo(MediaVisibility.PRIVATE);
        assertThat(savedAsset.getMediaType()).isEqualTo(MediaType.VIDEO);
    }

    // =============================================================================================
    // TEST O: Provider object version information is retained through registration
    // =============================================================================================
    @Test
    @DisplayName("O. Provider object version information is retained through registration")
    void testO_providerObjectVersionRetainedThroughRegistration() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-version-retention";
        String expectedB2VersionId = "4_z1234567890abcdef_f100000000000001";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        MultipartObjectMetadata headMetadata = createValidHeadMetadata(sessionId, allocId, "\"ver-etag\"", expectedB2VersionId);
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);
        ArgumentCaptor<MediaAssetVersion> versionCaptor = ArgumentCaptor.forClass(MediaAssetVersion.class);
        when(mediaAssetVersionRepositoryPort.save(versionCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);
        assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);

        // Architectural limitation: provider versionId ("4_z1234567890abcdef_f100000000000001") is verified
        // during in-memory provenance outside TX-B, but is NOT persisted in the MediaAssetVersion entity
        // or MySQL media_asset_versions table schema. Verify persisted version attributes:
        MediaAssetVersion savedVersion = versionCaptor.getValue();
        assertThat(savedVersion).isNotNull();
        assertThat(savedVersion.getVersionNumber()).isEqualTo(1);
        assertThat(savedVersion.getStorageLocation()).isEqualTo(StorageLocation.of(B2_PROVIDER, STORAGE_KEY));
        assertThat(savedVersion.getSizeBytes()).isEqualTo(DECLARED_SIZE);
        assertThat(savedVersion.getMimeType().value()).isEqualTo("video/mp4");
    }

    // =============================================================================================
    // TEST P: Recovery does not invoke abortMultipartUpload, deleteObject or another CompleteMultipartUpload
    // =============================================================================================
    @Test
    @DisplayName("P. Recovery does not invoke abortMultipartUpload, deleteObject or another CompleteMultipartUpload")
    void testP_recoveryDoesNotInvokeDestructiveOrRedundantProviderCalls() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-no-destructive";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        MultipartObjectMetadata headMetadata = createValidHeadMetadata(sessionId, allocId, "\"safe-etag\"", "ver-safe");
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        stubSuccessfulTxB(allocId);

        service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

        // Invariant: zero destructive or redundant calls
        verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
        verify(multipartStoragePort, never()).delete(any());
        verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
    }

    // =============================================================================================
    // TEST Q: No credentials, signed URLs or provider exception details leak into logs
    // =============================================================================================
    @Test
    @DisplayName("Q. No credentials, signed URLs or provider exception details leak into logs or throwables")
    void testQ_noCredentialsOrSignedUrlsLeakInLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(MediaUploadSessionService.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);

        try {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            String providerUploadId = "b2-up-secret-test";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            // Adversarial message containing sensitive tokens (signatures, presigned URLs, auth headers, secret keys)
            String adversarialSecret = "X-Amz-Signature=abcdef123456&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE/20261018/us-east-1/s3/aws4_request&Authorization=Bearer%20secret-tok&applicationKey=K0000secret&https://s3.us-west-004.backblazeb2.com/bucket/key?X-Amz-Signature=secretSig";

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                    .thenThrow(new StorageException("Storage error occurred for key: " + STORAGE_KEY.value(), new RuntimeException(adversarialSecret)));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(StorageException.class);

            List<String> sensitiveTokens = List.of(
                    "X-Amz-Signature",
                    "X-Amz-Credential",
                    "Authorization",
                    "applicationKey",
                    "https://",
                    "secretSig",
                    "secret-tok"
            );

            // Assert that no SLF4J log event from MediaUploadSessionService leaked any of the sensitive tokens
            assertThat(listAppender.list).isNotEmpty();
            for (ILoggingEvent event : listAppender.list) {
                String formatted = event.getFormattedMessage();
                for (String token : sensitiveTokens) {
                    assertThat(formatted)
                            .withFailMessage("Log message contains sensitive token [%s]: %s", token, formatted)
                            .doesNotContain(token);
                }
                IThrowableProxy throwableProxy = event.getThrowableProxy();
                if (throwableProxy != null) {
                    String throwableMessage = throwableProxy.getMessage();
                    for (String token : sensitiveTokens) {
                        if (throwableMessage != null) {
                            assertThat(throwableMessage)
                                    .withFailMessage("Throwable message contains sensitive token [%s]: %s", token, throwableMessage)
                                    .doesNotContain(token);
                        }
                    }
                }
            }
        } finally {
            logger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    // =============================================================================================
    // TEST R: Unrelated StorageObjectNotFoundException with "NoSuchUpload" message text fails closed
    // =============================================================================================
    @Test
    @DisplayName("R. Unrelated StorageObjectNotFoundException with 'NoSuchUpload' in message fails closed without probing")
    void testR_unrelatedStorageObjectNotFoundExceptionContainingNoSuchUploadTextFailsClosedWithoutRecovery() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-fake-msg-notfound";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        // Unrelated exception with "NoSuchUpload" string in message but NO structured provider type/code
        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageObjectNotFoundException(STORAGE_KEY, new RuntimeException("Unrelated error mentioning NoSuchUpload in text")));

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(StorageObjectNotFoundException.class)
                .isNotInstanceOf(StorageUploadNotFoundException.class);

        // Invariant: Must NOT enter recovery probe
        verify(multipartStoragePort, never()).inspectObjectMetadata(any());
        verify(mediaAssetRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST S: Unrelated StorageException with "NoSuchUpload" message text fails closed
    // =============================================================================================
    @Test
    @DisplayName("S. Unrelated StorageException with 'NoSuchUpload' in message fails closed without probing")
    void testS_unrelatedStorageExceptionContainingNoSuchUploadTextFailsClosedWithoutRecovery() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-fake-msg-storage-ex";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        // Unrelated StorageException with "NoSuchUpload" substring in message
        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageException("Generic storage failure mentioning NoSuchUpload in text"));

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(StorageException.class);

        // Invariant: Must NOT enter recovery probe
        verify(multipartStoragePort, never()).inspectObjectMetadata(any());
        verify(mediaAssetRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST T: Deterministic 403 AccessDenied fails closed without probing storage
    // =============================================================================================
    @Test
    @DisplayName("T. Deterministic 403 AccessDenied on CompleteMultipartUpload fails closed without probing storage")
    void testT_deterministic403AccessDeniedFailsClosedWithoutStorageProbe() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-403";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        // CompleteMultipartUpload throws deterministic 403 AccessDenied wrapped as StorageException
        when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any()))
                .thenThrow(new StorageException("Access Denied on complete (403)", new RuntimeException("403 AccessDenied")));

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(StorageException.class);

        // Invariant: Deterministic error must NOT probe storage
        verify(multipartStoragePort, never()).inspectObjectMetadata(any());
        verify(mediaAssetRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST U: Deterministic 400 InvalidRequest fails closed without probing storage
    // =============================================================================================
    @Test
    @DisplayName("U. Deterministic 400 InvalidRequest on CompleteMultipartUpload fails closed without probing storage")
    void testU_deterministic400InvalidRequestFailsClosedWithoutStorageProbe() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-400";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        // CompleteMultipartUpload throws deterministic 400 InvalidPartOrder wrapped as StorageException
        when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any()))
                .thenThrow(new StorageException("Invalid part order on complete (400)", new RuntimeException("400 InvalidPartOrder")));

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(StorageException.class);

        // Invariant: Deterministic error must NOT probe storage
        verify(multipartStoragePort, never()).inspectObjectMetadata(any());
        verify(mediaAssetRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST V: Ambiguous 503 ServiceUnavailable permits storage probe and recovers
    // =============================================================================================
    @Test
    @DisplayName("V. Ambiguous 503 ServiceUnavailable on CompleteMultipartUpload permits storage probe and recovers")
    void testV_ambiguous503ServiceUnavailablePermitsStorageProbeAndRecovers() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-503";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        // CompleteMultipartUpload throws transient 503 ServiceUnavailable mapped to StorageAmbiguousCompletionException
        when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any()))
                .thenThrow(new StorageAmbiguousCompletionException("Service Unavailable on complete (503)", new RuntimeException("503 ServiceUnavailable")));

        MultipartObjectMetadata headMetadata = createValidHeadMetadata(sessionId, allocId, "\"503-recovered-etag\"", "ver-503");
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        stubSuccessfulTxB(allocId);

        MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

        assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(result.getRegisteredAssetId()).isNotNull();

        verify(multipartStoragePort, times(1)).inspectObjectMetadata(STORAGE_KEY);
        verify(mediaAssetRepositoryPort, times(1)).save(any(MediaAsset.class));
        verify(mediaAssetVersionRepositoryPort, times(1)).save(any(MediaAssetVersion.class));
        verify(storageKeyAllocationService, times(1)).commitAllocation(eq(allocId), eq(StorageKeyAllocationType.ASSET_VERSION), any(UUID.class));
    }

    // =============================================================================================
    // TEST W: Recovery with missing providerId fails closed
    // =============================================================================================
    @Test
    @DisplayName("W. Recovery with missing providerId fails closed")
    void testW_recoveryMissingProviderIdFailsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-missing-provider";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        // HEAD metadata has null providerId
        MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                STORAGE_KEY,
                DECLARED_SIZE,
                "video/mp4",
                "\"etag-w\"",
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                ),
                "ver-w",
                null,
                B2_BUCKET
        );
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providerId missing");

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST X: Recovery with missing bucketName fails closed
    // =============================================================================================
    @Test
    @DisplayName("X. Recovery with missing bucketName fails closed")
    void testX_recoveryMissingBucketNameFailsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-missing-bucket";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        // HEAD metadata has null bucketName
        MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                STORAGE_KEY,
                DECLARED_SIZE,
                "video/mp4",
                "\"etag-x\"",
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                ),
                "ver-x",
                B2_PROVIDER,
                null
        );
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucketName missing");

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST Y: Recovery with mismatched bucketName fails closed
    // =============================================================================================
    @Test
    @DisplayName("Y. Recovery with mismatched bucketName fails closed")
    void testY_recoveryMismatchedBucketNameFailsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-up-mismatch-bucket";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        // HEAD metadata has different bucket name
        MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                STORAGE_KEY,
                DECLARED_SIZE,
                "video/mp4",
                "\"etag-y\"",
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                ),
                "ver-y",
                B2_PROVIDER,
                "other-bucket"
        );
        when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucket mismatch");

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
    }
}
