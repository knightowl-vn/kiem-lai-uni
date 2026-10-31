package com.universe.media.application.storage;

import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.exceptions.StorageUploadNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartCompletedObject;
import com.universe.media.application.ports.storage.MultipartObjectMetadata;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MS-08 R2-03: Cloudflare R2 Multipart Completion Provenance Tests.
 * <p>
 * Validates provider-aware provenance verification across Cloudflare R2 (which omits versionId on HEAD)
 * and Backblaze B2 (which strictly enforces versionId matching), along with strict failure modes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MS-08 R2-03: Cloudflare R2 Multipart Completion Provenance Verification")
class MediaUploadSessionR2ProvenanceTest {

    private static final StorageProviderId R2_MOVIE_PROVIDER = StorageProviderId.of("r2-movie");
    private static final String R2_MOVIE_BUCKET = "kiemlai-screen";
    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";

    private static final StorageKey STORAGE_KEY = StorageKey.of("media/screen/test-movie.mp4");
    private static final Instant NOW = Instant.parse("2026-10-31T20:37:57Z");
    private static final String PRINCIPAL = "user-admin-r2";
    private static final String CONSUMER = "screen";
    private static final String TARGET_TYPE = "SCREEN_ENTRY";
    private static final String CLIENT_TAG = "screen-video";
    private static final String VALID_CONTENT_HASH = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final long DECLARED_SIZE = 52428800L; // 50 MiB (2 parts of 25 MiB)

    private static final String R2_COMPLETION_VERSION_ID = "0123456789abcdef0123456789abcdef"; // 32-char R2 completion token
    private static final String B2_VERSION_ID = "4_z1234567890abcdef_f100000000000001";
    private static final String MATCHING_ETAG = "\"9b102ff6e40e6629e2ec01ed4cbd4dbd-2\"";

    @Mock
    private MediaUploadSessionRepositoryPort sessionRepositoryPort;

    @Mock
    private StorageKeyAllocationService storageKeyAllocationService;

    @Mock
    private MediaStorageRoutingService routingService;

    @Mock
    private MultipartStoragePort r2MoviePort;

    @Mock
    private MultipartStoragePort b2Port;

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    private AtomicReference<Instant> currentTime;
    private ClockPort clockPort;
    private PlatformTransactionManager transactionManager;
    private MediaUploadSessionService service;

    @BeforeEach
    void setUp() {
        currentTime = new AtomicReference<>(NOW);
        clockPort = currentTime::get;
        transactionManager = createTestTransactionManager();
        org.mockito.Mockito.lenient().when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);
        org.mockito.Mockito.lenient().when(b2Port.providerId()).thenReturn(B2_PROVIDER);
        service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(r2MoviePort, b2Port),
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
            String providerUploadId,
            StorageProviderId providerId,
            String bucket
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
                providerId,
                bucket,
                STORAGE_KEY,
                allocationId,
                providerUploadId,
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                expectedParts,
                VALID_CONTENT_HASH,
                "movie.mp4",
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

    private StorageKeyAllocation createReservedAllocation(
            UUID allocationId,
            UUID sessionId,
            StorageProviderId providerId,
            String bucket
    ) {
        return StorageKeyAllocation.rehydrate(
                allocationId,
                providerId,
                bucket,
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

    private MultipartObjectMetadata createHeadMetadata(
            UUID sessionId,
            UUID allocId,
            String eTag,
            String versionId,
            StorageProviderId providerId,
            String bucket,
            long sizeBytes,
            String mimeType,
            StorageKey key
    ) {
        return new MultipartObjectMetadata(
                key,
                sizeBytes,
                mimeType,
                eTag,
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                ),
                versionId,
                providerId,
                bucket
        );
    }

    private void stubSuccessfulTxB() {
        when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);
        when(mediaAssetVersionRepositoryPort.save(any(MediaAssetVersion.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // =============================================================================================
    // TEST 1: R2 completion returns versionId, HEAD versionId is absent (null); passes provenance
    // =============================================================================================
    @Test
    @DisplayName("Case 1: R2 completion returns versionId, HEAD versionId is absent (null) -> PASSES provenance")
    void test1_r2CompletionVersionIdPresent_headVersionIdAbsent_passesProvenance() {
        UUID sessionId = UUID.fromString("24b7539d-22a0-4e0c-beba-799fd0c7dd46");
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "r2-upload-session-long-id-123456789";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(r2MoviePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        // Provider completion returns 32-character versionId token
        MultipartCompletedObject completed = new MultipartCompletedObject(
                StorageLocation.of(R2_MOVIE_PROVIDER, STORAGE_KEY),
                MATCHING_ETAG,
                R2_COMPLETION_VERSION_ID
        );
        when(r2MoviePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

        // HEAD metadata from Cloudflare R2 has versionId == null
        MultipartObjectMetadata headMetadata = createHeadMetadata(
                sessionId, allocId, MATCHING_ETAG, null, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, DECLARED_SIZE, "video/mp4", STORAGE_KEY
        );
        when(r2MoviePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        stubSuccessfulTxB();

        MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

        assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(result.getRegisteredAssetId()).isNotNull();
        verify(mediaAssetRepositoryPort, times(1)).save(any(MediaAsset.class));
        verify(mediaAssetVersionRepositoryPort, times(1)).save(any(MediaAssetVersion.class));
    }

    // =============================================================================================
    // TEST 2: R2 HEAD versionId is present and matches -> PASSES
    // =============================================================================================
    @Test
    @DisplayName("Case 2: R2 HEAD versionId is present and matches -> PASSES")
    void test2_r2HeadVersionIdPresentAndMatches_passes() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "r2-upload-matching-ver";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(r2MoviePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        MultipartCompletedObject completed = new MultipartCompletedObject(
                StorageLocation.of(R2_MOVIE_PROVIDER, STORAGE_KEY),
                MATCHING_ETAG,
                R2_COMPLETION_VERSION_ID
        );
        when(r2MoviePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

        // HEAD metadata has matching versionId
        MultipartObjectMetadata headMetadata = createHeadMetadata(
                sessionId, allocId, MATCHING_ETAG, R2_COMPLETION_VERSION_ID, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, DECLARED_SIZE, "video/mp4", STORAGE_KEY
        );
        when(r2MoviePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        stubSuccessfulTxB();

        MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

        assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(result.getRegisteredAssetId()).isNotNull();
    }

    // =============================================================================================
    // TEST 3: R2 HEAD versionId is present but mismatched -> FAILS closed
    // =============================================================================================
    @Test
    @DisplayName("Case 3: R2 HEAD versionId is present but mismatched -> FAILS closed")
    void test3_r2HeadVersionIdMismatched_failsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "r2-upload-mismatched-ver";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(r2MoviePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        MultipartCompletedObject completed = new MultipartCompletedObject(
                StorageLocation.of(R2_MOVIE_PROVIDER, STORAGE_KEY),
                MATCHING_ETAG,
                R2_COMPLETION_VERSION_ID
        );
        when(r2MoviePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

        // Mismatched version ID on HEAD
        MultipartObjectMetadata headMetadata = createHeadMetadata(
                sessionId, allocId, MATCHING_ETAG, "different-version-id-999999", R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, DECLARED_SIZE, "video/mp4", STORAGE_KEY
        );
        when(r2MoviePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Completed object versionId mismatch (potential concurrent overwrite)");

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST 4: HEAD object is missing -> FAILS closed
    // =============================================================================================
    @Test
    @DisplayName("Case 4: HEAD object is missing -> FAILS closed")
    void test4_headObjectMissing_failsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "r2-upload-head-missing";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(r2MoviePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        MultipartCompletedObject completed = new MultipartCompletedObject(
                StorageLocation.of(R2_MOVIE_PROVIDER, STORAGE_KEY),
                MATCHING_ETAG,
                R2_COMPLETION_VERSION_ID
        );
        when(r2MoviePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

        // HEAD object missing
        when(r2MoviePort.inspectObjectMetadata(STORAGE_KEY))
                .thenThrow(new StorageObjectNotFoundException(STORAGE_KEY));

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(StorageObjectNotFoundException.class);

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST 5: HEAD object size mismatches expected size -> FAILS closed
    // =============================================================================================
    @Test
    @DisplayName("Case 5: HEAD object size mismatches expected size -> FAILS closed")
    void test5_headObjectSizeMismatch_failsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "r2-upload-size-mismatch";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(r2MoviePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        MultipartCompletedObject completed = new MultipartCompletedObject(
                StorageLocation.of(R2_MOVIE_PROVIDER, STORAGE_KEY),
                MATCHING_ETAG,
                R2_COMPLETION_VERSION_ID
        );
        when(r2MoviePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

        // Truncated size (e.g. 1000 bytes instead of 52428800)
        MultipartObjectMetadata headMetadata = createHeadMetadata(
                sessionId, allocId, MATCHING_ETAG, null, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, 1000L, "video/mp4", STORAGE_KEY
        );
        when(r2MoviePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Completed object size mismatch");

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST 6: ETag mismatches when ETag is part of verification contract -> FAILS closed
    // =============================================================================================
    @Test
    @DisplayName("Case 6: ETag mismatches when ETag is part of verification contract -> FAILS closed")
    void test6_eTagMismatch_failsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "r2-upload-etag-mismatch";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(r2MoviePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        MultipartCompletedObject completed = new MultipartCompletedObject(
                StorageLocation.of(R2_MOVIE_PROVIDER, STORAGE_KEY),
                MATCHING_ETAG,
                R2_COMPLETION_VERSION_ID
        );
        when(r2MoviePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

        // Foreign ETag on HEAD
        MultipartObjectMetadata headMetadata = createHeadMetadata(
                sessionId, allocId, "\"foreign-etag-12345678-2\"", null, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, DECLARED_SIZE, "video/mp4", STORAGE_KEY
        );
        when(r2MoviePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Completed object ETag mismatch (potential concurrent overwrite)");

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST 7: Provider key or allocation identity mismatches -> FAILS closed
    // =============================================================================================
    @Test
    @DisplayName("Case 7a: Storage key mismatch -> FAILS closed")
    void test7a_storageKeyMismatch_failsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "r2-upload-key-mismatch";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(r2MoviePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        MultipartCompletedObject completed = new MultipartCompletedObject(
                StorageLocation.of(R2_MOVIE_PROVIDER, STORAGE_KEY),
                MATCHING_ETAG,
                R2_COMPLETION_VERSION_ID
        );
        when(r2MoviePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

        // Storage key mismatch
        StorageKey foreignKey = StorageKey.of("media/screen/different-file.mp4");
        MultipartObjectMetadata headMetadata = createHeadMetadata(
                sessionId, allocId, MATCHING_ETAG, null, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, DECLARED_SIZE, "video/mp4", foreignKey
        );
        when(r2MoviePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Completed object storage key mismatch");

        verify(mediaAssetRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Case 7b: Allocation-id metadata mismatch -> FAILS closed")
    void test7b_allocationIdMismatch_failsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID foreignAllocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "r2-upload-alloc-mismatch";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(r2MoviePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        MultipartCompletedObject completed = new MultipartCompletedObject(
                StorageLocation.of(R2_MOVIE_PROVIDER, STORAGE_KEY),
                MATCHING_ETAG,
                R2_COMPLETION_VERSION_ID
        );
        when(r2MoviePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

        // Allocation-id metadata mismatch
        MultipartObjectMetadata headMetadata = createHeadMetadata(
                sessionId, foreignAllocId, MATCHING_ETAG, null, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, DECLARED_SIZE, "video/mp4", STORAGE_KEY
        );
        when(r2MoviePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allocation-id");

        verify(mediaAssetRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Case 7c: Session-id metadata mismatch -> FAILS closed")
    void test7c_sessionIdMismatch_failsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID foreignSessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "r2-upload-session-mismatch";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(r2MoviePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        MultipartCompletedObject completed = new MultipartCompletedObject(
                StorageLocation.of(R2_MOVIE_PROVIDER, STORAGE_KEY),
                MATCHING_ETAG,
                R2_COMPLETION_VERSION_ID
        );
        when(r2MoviePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

        // Session-id metadata mismatch
        MultipartObjectMetadata headMetadata = createHeadMetadata(
                foreignSessionId, allocId, MATCHING_ETAG, null, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, DECLARED_SIZE, "video/mp4", STORAGE_KEY
        );
        when(r2MoviePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-id");

        verify(mediaAssetRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST 8: B2 retains its existing strict provenance requirements (missing HEAD versionId FAILS closed)
    // =============================================================================================
    @Test
    @DisplayName("Case 8: B2 completion with missing HEAD versionId STRICTLY FAILS closed")
    void test8_b2MissingHeadVersionId_strictlyFailsClosed() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "b2-upload-strict-version";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId, B2_PROVIDER, B2_BUCKET);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, B2_PROVIDER, B2_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(b2Port.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        MultipartCompletedObject completed = new MultipartCompletedObject(
                StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                MATCHING_ETAG,
                B2_VERSION_ID
        );
        when(b2Port.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

        // HEAD metadata from B2 is missing versionId (null) -> B2 must fail closed!
        MultipartObjectMetadata headMetadata = createHeadMetadata(
                sessionId, allocId, MATCHING_ETAG, null, B2_PROVIDER, B2_BUCKET, DECLARED_SIZE, "video/mp4", STORAGE_KEY
        );
        when(b2Port.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Completed object versionId missing on HEAD metadata: expected " + B2_VERSION_ID);

        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
    }

    // =============================================================================================
    // TEST 9: Repeated completion requests never blindly repeat an already successful remote completion
    // =============================================================================================
    @Test
    @DisplayName("Case 9a: Already COMPLETED session returns immediately without remote calls")
    void test9a_alreadyCompletedSession_idempotentWithoutRemoteCalls() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        String providerUploadId = "r2-upload-already-completed";

        MediaUploadSession completedSession = MediaUploadSession.rehydrate(
                sessionId,
                PRINCIPAL,
                CONSUMER,
                TARGET_TYPE,
                targetId,
                "idem-key-" + sessionId,
                MediaType.VIDEO,
                "video/mp4",
                DECLARED_SIZE,
                R2_MOVIE_PROVIDER,
                R2_MOVIE_BUCKET,
                STORAGE_KEY,
                allocId,
                providerUploadId,
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                VALID_CONTENT_HASH,
                "movie.mp4",
                CLIENT_TAG,
                MediaUploadSessionStatus.COMPLETED,
                NOW.plus(Duration.ofDays(1)),
                null,
                null,
                assetId,
                UploadSessionCleanupStatus.NONE,
                0,
                null,
                null,
                NOW.minus(Duration.ofHours(1)),
                NOW.minus(Duration.ofMinutes(10)),
                NOW,
                0L,
                VALID_CONTENT_HASH
        );

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(completedSession));

        // Stub existing registered asset and version for idempotent verification
        MediaAsset completedAsset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PRIVATE, CLIENT_TAG, NOW);
        when(mediaAssetRepositoryPort.findById(assetId)).thenReturn(Optional.of(completedAsset));

        MediaAssetVersion completedVersion = MediaAssetVersion.create(
                UUID.randomUUID(), assetId, 1,
                StorageLocation.of(R2_MOVIE_PROVIDER, STORAGE_KEY), null,
                ContentHash.of(VALID_CONTENT_HASH), ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                MimeType.of("video/mp4"), DECLARED_SIZE, "movie.mp4", NOW
        );
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(assetId, 1)).thenReturn(Optional.of(completedVersion));

        StorageKeyAllocation committedAllocation = StorageKeyAllocation.rehydrate(
                allocId,
                R2_MOVIE_PROVIDER,
                R2_MOVIE_BUCKET,
                STORAGE_KEY,
                StorageKeyAllocationStatus.COMMITTED,
                StorageKeyAllocationType.ASSET_VERSION,
                completedVersion.getId(),
                NOW.minus(Duration.ofHours(1)),
                NOW,
                0L
        );
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(committedAllocation));

        MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, null);

        assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(result.getRegisteredAssetId()).isEqualTo(assetId);

        // Never invoked remote methods on either provider
        verify(r2MoviePort, never()).completeMultipartUpload(any(), any(), any());
        verify(r2MoviePort, never()).listUploadedParts(any(), any());
        verify(r2MoviePort, never()).inspectObjectMetadata(any());
        verify(b2Port, never()).completeMultipartUpload(any(), any(), any());
    }

    @Test
    @DisplayName("Case 9b: Retry after successful remote completion recovers without duplicate completeMultipartUpload (Scenario A)")
    void test9b_retryAfterRemoteCompletion_recoversWithoutDuplicateComplete() {
        UUID sessionId = UUID.fromString("24b7539d-22a0-4e0c-beba-799fd0c7dd46");
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "r2-already-assembled-upload";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        // Provider returns NoSuchUpload because remote upload already completed
        when(r2MoviePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

        // HEAD confirms object exists with correct metadata
        MultipartObjectMetadata headMetadata = createHeadMetadata(
                sessionId, allocId, MATCHING_ETAG, null, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, DECLARED_SIZE, "video/mp4", STORAGE_KEY
        );
        when(r2MoviePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        stubSuccessfulTxB();

        MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

        assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(result.getRegisteredAssetId()).isNotNull();

        // completeMultipartUpload must NEVER be called again during Scenario A recovery
        verify(r2MoviePort, never()).completeMultipartUpload(any(), any(), any());
        verify(mediaAssetRepositoryPort, times(1)).save(any(MediaAsset.class));
        verify(mediaAssetVersionRepositoryPort, times(1)).save(any(MediaAssetVersion.class));
    }

    // =============================================================================================
    // TEST 10: Failed verification does not accidentally attach a media asset or mark session COMPLETED
    // =============================================================================================
    @Test
    @DisplayName("Case 10: Failed verification never invokes TX-B and session remains COMPLETING")
    void test10_failedVerificationNeverPersistsAssetOrMarksCompleted() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String providerUploadId = "r2-upload-failed-verification";

        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, providerUploadId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
        session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
        StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
        when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

        when(r2MoviePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

        MultipartCompletedObject completed = new MultipartCompletedObject(
                StorageLocation.of(R2_MOVIE_PROVIDER, STORAGE_KEY),
                MATCHING_ETAG,
                R2_COMPLETION_VERSION_ID
        );
        when(r2MoviePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

        // Invalid MIME type on HEAD metadata
        MultipartObjectMetadata headMetadata = createHeadMetadata(
                sessionId, allocId, MATCHING_ETAG, null, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET, DECLARED_SIZE, "application/octet-stream", STORAGE_KEY
        );
        when(r2MoviePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

        assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Completed object content type mismatch");

        // Fencing check: TX-B was never executed
        verify(mediaAssetRepositoryPort, never()).save(any());
        verify(mediaAssetVersionRepositoryPort, never()).save(any());
        assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
        assertThat(session.getRegisteredAssetId()).isNull();
    }

    // =============================================================================================
    // Unit Test: isProviderOmittingHeadVersionId helper logic
    // =============================================================================================
    @Test
    @DisplayName("Helper: isProviderOmittingHeadVersionId restricts omitted HEAD versionId strictly to r2-movie")
    void testHelper_isProviderOmittingHeadVersionId() {
        assertThat(MediaUploadSessionService.isProviderOmittingHeadVersionId(StorageProviderId.of("r2-movie"))).isTrue();
        assertThat(MediaUploadSessionService.isProviderOmittingHeadVersionId(StorageProviderId.of("R2-MOVIE"))).isTrue();

        assertThat(MediaUploadSessionService.isProviderOmittingHeadVersionId(StorageProviderId.of("r2"))).isFalse();
        assertThat(MediaUploadSessionService.isProviderOmittingHeadVersionId(StorageProviderId.of("r2-screen"))).isFalse();
        assertThat(MediaUploadSessionService.isProviderOmittingHeadVersionId(StorageProviderId.of("r2-images"))).isFalse();
        assertThat(MediaUploadSessionService.isProviderOmittingHeadVersionId(StorageProviderId.of("b2"))).isFalse();
        assertThat(MediaUploadSessionService.isProviderOmittingHeadVersionId(StorageProviderId.of("s3"))).isFalse();
        assertThat(MediaUploadSessionService.isProviderOmittingHeadVersionId(StorageProviderId.of("local"))).isFalse();
        assertThat(MediaUploadSessionService.isProviderOmittingHeadVersionId(null)).isFalse();
    }
}
