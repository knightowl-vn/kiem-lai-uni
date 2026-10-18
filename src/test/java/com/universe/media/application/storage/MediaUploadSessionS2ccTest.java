package com.universe.media.application.storage;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.exceptions.StorageAmbiguousCompletionException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.exceptions.StorageUploadNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.SocketTimeoutException;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("S2C-C: CompleteMultipartUpload, Provenance Verification & Media Registration Tests")
class MediaUploadSessionS2ccTest {

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
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/ms08-s2cc.mp4");
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String PRINCIPAL = "user-creator-s2cc";
    private static final String CONSUMER = "SCREEN";
    private static final String TARGET_TYPE = "SCREEN_ENTRY";
    private static final String VALID_CONTENT_HASH = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @BeforeEach
    void setUp() {
        currentTime = new AtomicReference<>(NOW);
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

    private MediaUploadSession createCompletingSession(
            UUID sessionId,
            UUID allocationId,
            UUID targetId,
            long declaredSize,
            String providerUploadId,
            String manifestHash
    ) {
        Instant expiresAt = NOW.plus(Duration.ofDays(1));
        int expectedParts = (int) Math.ceil((double) declaredSize / MediaUploadSession.FIXED_PART_SIZE_BYTES);
        return MediaUploadSession.rehydrate(
                sessionId,
                PRINCIPAL,
                CONSUMER,
                TARGET_TYPE,
                targetId,
                "idem-key-" + sessionId,
                MediaType.VIDEO,
                "video/mp4",
                declaredSize,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                allocationId,
                providerUploadId,
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                expectedParts,
                VALID_CONTENT_HASH,
                "video.mp4",
                "screen-video",
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
                manifestHash
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
        return List.of(
                new MultipartManifestPart(1, 26214400L, "\"etag-part-1\""),
                new MultipartManifestPart(2, 26214400L, "\"etag-part-2\"")
        );
    }

    private List<MultipartUploadedPart> createStandard2UploadedParts() {
        return List.of(
                new MultipartUploadedPart(1, 26214400L, "\"etag-part-1\""),
                new MultipartUploadedPart(2, 26214400L, "\"etag-part-2\"")
        );
    }

    // =============================================================================================
    // 1. Happy Path & Atomic Registration Tests
    // =============================================================================================

    @Nested
    @DisplayName("1. Happy Path & Atomic Registration Tests")
    class HappyPathTests {

        @Test
        @DisplayName("Completes multipart upload, verifies provenance, and atomically registers MediaAsset & Version")
        void completesMultipartUploadSuccessfully() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L; // 50 MiB, 2 parts
            String providerUploadId = "b2-up-123";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession initialSession = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            String manifestFingerprint = MultipartManifestCanonicalizer.validateAndCanonicalize(initialSession, manifest);
            initialSession.recordManifestHash(manifestFingerprint);

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(initialSession));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completedObject = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag-50mib\""
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completedObject);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag-50mib\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    "ver-id-001"
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
            when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);
            when(mediaAssetVersionRepositoryPort.save(any(MediaAssetVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            // Execute completion
            MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

            // Assertions on completed session
            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
            assertThat(result.getRegisteredAssetId()).isNotNull();
            assertThat(result.getCompletedAt()).isEqualTo(NOW);
            assertThat(result.getLeaseOwner()).isNull();
            assertThat(result.getLeaseExpiresAt()).isNull();

            // Verify atomic registration calls
            ArgumentCaptor<MediaAsset> assetCaptor = ArgumentCaptor.forClass(MediaAsset.class);
            verify(mediaAssetRepositoryPort).save(assetCaptor.capture());
            MediaAsset savedAsset = assetCaptor.getValue();
            assertThat(savedAsset.getId()).isEqualTo(result.getRegisteredAssetId());
            assertThat(savedAsset.getMediaType()).isEqualTo(MediaType.VIDEO);
            assertThat(savedAsset.getVisibility()).isEqualTo(MediaVisibility.PRIVATE);
            assertThat(savedAsset.getClientTag()).isEqualTo("screen-video");
            assertThat(savedAsset.getCurrentVersionNumber()).isEqualTo(1);

            ArgumentCaptor<MediaAssetVersion> versionCaptor = ArgumentCaptor.forClass(MediaAssetVersion.class);
            verify(mediaAssetVersionRepositoryPort).save(versionCaptor.capture());
            MediaAssetVersion savedVersion = versionCaptor.getValue();
            assertThat(savedVersion.getAssetId()).isEqualTo(savedAsset.getId());
            assertThat(savedVersion.getVersionNumber()).isEqualTo(1);
            assertThat(savedVersion.getStorageLocation()).isEqualTo(StorageLocation.of(B2_PROVIDER, STORAGE_KEY));
            assertThat(savedVersion.getContentHash()).isEqualTo(ContentHash.of(VALID_CONTENT_HASH));
            assertThat(savedVersion.getContentHashStatus()).isEqualTo(ContentHashStatus.CLIENT_DECLARED_UNVERIFIED);
            assertThat(savedVersion.getSizeBytes()).isEqualTo(declaredSize);
            assertThat(savedVersion.getOriginalFilename()).isEqualTo("video.mp4");

            // Verify allocation ownership committed to ASSET_VERSION
            verify(storageKeyAllocationService).commitAllocation(allocId, StorageKeyAllocationType.ASSET_VERSION, savedVersion.getId());
        }

        @Test
        @DisplayName("Convenience overload without manifest lists parts from provider and completes successfully")
        void convenienceOverloadWithoutManifestCompletesSuccessfully() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-overload";

            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession initialSession = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            List<MultipartManifestPart> manifestFromParts = uploadedParts.stream().map(MultipartManifestPart::from).toList();
            String manifestFingerprint = MultipartManifestCanonicalizer.validateAndCanonicalize(initialSession, manifestFromParts);
            initialSession.recordManifestHash(manifestFingerprint);

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(initialSession));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completedObject = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "complete-etag-50mib"
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completedObject);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "complete-etag-50mib",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
            when(mediaAssetVersionRepositoryPort.save(any(MediaAssetVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL);

            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
            verify(storageKeyAllocationService).commitAllocation(eq(allocId), eq(StorageKeyAllocationType.ASSET_VERSION), any(UUID.class));
        }
    }

    // =============================================================================================
    // 2. Pagination Boundaries Tests (999, 1000, 1001, 2048 parts)
    // =============================================================================================

    @Nested
    @DisplayName("2. Pagination Boundaries Tests")
    class PaginationBoundaryTests {

        private void runBoundaryTest(int partCount) {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long fixedPartSize = MediaUploadSession.FIXED_PART_SIZE_BYTES;
            long declaredSize = (long) partCount * fixedPartSize;
            String providerUploadId = "b2-boundary-" + partCount;

            List<MultipartManifestPart> manifest = new ArrayList<>(partCount);
            List<MultipartUploadedPart> uploadedParts = new ArrayList<>(partCount);
            for (int i = 1; i <= partCount; i++) {
                String etag = "\"etag-part-" + i + "\"";
                manifest.add(new MultipartManifestPart(i, fixedPartSize, etag));
                uploadedParts.add(new MultipartUploadedPart(i, fixedPartSize, etag));
            }

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            String manifestFingerprint = MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest);
            session.recordManifestHash(manifestFingerprint);

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-boundary-" + partCount + "\""
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-boundary-" + partCount + "\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    "boundary-ver"
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
            when(mediaAssetVersionRepositoryPort.save(any(MediaAssetVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);
            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);

            ArgumentCaptor<List<MultipartPartETag>> partsCaptor = ArgumentCaptor.forClass(List.class);
            verify(multipartStoragePort).completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), partsCaptor.capture());
            assertThat(partsCaptor.getValue()).hasSize(partCount);
            assertThat(partsCaptor.getValue().get(0).partNumber()).isEqualTo(1);
            assertThat(partsCaptor.getValue().get(partCount - 1).partNumber()).isEqualTo(partCount);
        }

        @Test
        @DisplayName("Successfully verifies and completes 999 parts (just below default page boundary)")
        void completes999Parts() {
            runBoundaryTest(999);
        }

        @Test
        @DisplayName("Successfully verifies and completes 1000 parts (exact single page boundary)")
        void completes1000Parts() {
            runBoundaryTest(1000);
        }

        @Test
        @DisplayName("Successfully verifies and completes 1001 parts (crossing boundary into second page)")
        void completes1001Parts() {
            runBoundaryTest(1001);
        }

        @Test
        @DisplayName("Successfully verifies and completes 2048 parts (maximum allowed 50 GiB limit)")
        void completes2048Parts() {
            runBoundaryTest(2048);
        }
    }

    // =============================================================================================
    // 3. Manifest & Provider Parts Mismatch Tests
    // =============================================================================================

    @Nested
    @DisplayName("3. Manifest & Provider Parts Mismatch Tests")
    class ManifestAndPartMismatchTests {

        @Test
        @DisplayName("Rejects completion when submitted manifest does not match previously accepted manifestHash")
        void rejectsConflictingSubmittedManifest() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-conflict";

            List<MultipartManifestPart> acceptedManifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            String acceptedFingerprint = MultipartManifestCanonicalizer.validateAndCanonicalize(session, acceptedManifest);
            session.recordManifestHash(acceptedFingerprint);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            List<MultipartManifestPart> conflictingManifest = List.of(
                    new MultipartManifestPart(1, 26214400L, "\"different-etag-1\""),
                    new MultipartManifestPart(2, 26214400L, "\"etag-part-2\"")
            );

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, conflictingManifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Submitted manifest hash")
                    .hasMessageContaining("does not match accepted completion intent");

            verify(multipartStoragePort, never()).listUploadedParts(any(), any());
            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("Rejects completion when provider returned fewer parts than expected manifest")
        void rejectsWhenProviderHasMissingParts() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-missing-part";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            // Provider only has 1 part uploaded
            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(List.of(
                    new MultipartUploadedPart(1, 26214400L, "\"etag-part-1\"")
            ));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Provider uploaded part count (1) does not match manifest part count (2)");

            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("Rejects completion when provider part size differs from manifest part size")
        void rejectsWhenProviderPartSizeMismatches() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-size-mismatch";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            // Part 1 size is 1000 bytes instead of 26214400 bytes
            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(List.of(
                    new MultipartUploadedPart(1, 1000L, "\"etag-part-1\""),
                    new MultipartUploadedPart(2, 26214400L, "\"etag-part-2\"")
            ));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Part size mismatch for part 1");

            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("Rejects completion when provider part ETag differs from manifest part ETag")
        void rejectsWhenProviderPartETagMismatches() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-etag-mismatch";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            // Part 2 has different ETag
            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(List.of(
                    new MultipartUploadedPart(1, 26214400L, "\"etag-part-1\""),
                    new MultipartUploadedPart(2, 26214400L, "\"corrupted-etag\"")
            ));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Part ETag mismatch for part 2");

            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("Valid shuffled manifest is accepted and completed in ascending part order")
        void validShuffledManifestAcceptedAndCompletedInAscendingOrder() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-shuffled-manifest";

            // Unordered input: part 2 before part 1
            List<MultipartManifestPart> shuffledManifest = List.of(
                    new MultipartManifestPart(2, 26214400L, "\"etag-part-2\""),
                    new MultipartManifestPart(1, 26214400L, "\"etag-part-1\"")
            );

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, shuffledManifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            // Provider parts in natural order [1, 2]
            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\""
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null,
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
            when(mediaAssetVersionRepositoryPort.save(any(MediaAssetVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, shuffledManifest);

            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);

            // Verify completeMultipartUpload received parts sorted in ascending part number order: [1, 2]
            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<MultipartPartETag>> partsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(multipartStoragePort).completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), partsCaptor.capture());
            List<MultipartPartETag> capturedParts = partsCaptor.getValue();
            assertThat(capturedParts).hasSize(2);
            assertThat(capturedParts.get(0).partNumber()).isEqualTo(1);
            assertThat(capturedParts.get(1).partNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("Duplicate part number in provider parts fails closed")
        void providerPartsDuplicatePartNumberFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-dup-part";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            // Provider has two part 1s and no part 2
            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(List.of(
                    new MultipartUploadedPart(1, 26214400L, "\"etag-part-1\""),
                    new MultipartUploadedPart(1, 26214400L, "\"etag-part-1-dup\"")
            ));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class);

            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("Missing part number in provider parts fails closed")
        void providerPartsMissingPartNumberFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-missing-part";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            // Provider has part 1 and part 3 instead of part 1 and part 2
            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(List.of(
                    new MultipartUploadedPart(1, 26214400L, "\"etag-part-1\""),
                    new MultipartUploadedPart(3, 26214400L, "\"etag-part-3\"")
            ));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Part number mismatch at index 1: manifest part 2, provider part 3");

            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
        }
    }

    // =============================================================================================
    // 4. Remote Failure & Ambiguity Matrix Tests
    // =============================================================================================

    @Nested
    @DisplayName("4. Remote Failure & Ambiguity Matrix Tests")
    class RemoteFailureMatrixTests {

        @Test
        @DisplayName("ListParts failure propagates StorageException and preserves state in COMPLETING without abort or deletion")
        void listPartsFailurePropagatesAndPreservesState() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-list-fail";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                    .thenThrow(new StorageException("B2 list parts network timeout"));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("B2 list parts network timeout");

            // Verify no remote complete and no abort/tombstone
            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
        }

        @Test
        @DisplayName("CompleteMultipartUpload timeout: preserves ambiguous state without automatic registration even if probe sees object")
        void completeTimeoutPreservesAmbiguityWithoutAutomaticRegistration() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-timeout-probe-ambiguous";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            // completeMultipartUpload throws timeout wrapped as ambiguous completion
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any()))
                    .thenThrow(new StorageAmbiguousCompletionException("Socket timeout during assembly", new SocketTimeoutException("Read timed out")));

            // Diagnostic probe sees object in storage is not present
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY))
                    .thenThrow(new StorageObjectNotFoundException(STORAGE_KEY));

            // Invariant: MUST fail closed by rethrowing the StorageException; must NEVER proceed to TX-B registration
            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Socket timeout during assembly");

            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(mediaAssetVersionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).commitAllocation(any(), any(), any());
            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
        }

        @Test
        @DisplayName("CompleteMultipartUpload timeout: propagates error and preserves state when probe fails (ambiguous outcome)")
        void completeTimeoutPropagatesWhenProbeFails() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-timeout-probe-fail";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any()))
                    .thenThrow(new StorageAmbiguousCompletionException("Assembly timeout", new SocketTimeoutException("Read timed out")));

            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY))
                    .thenThrow(new StorageObjectNotFoundException(STORAGE_KEY));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Assembly timeout");

            // Preserves state in COMPLETING without abort or deletion
            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(mediaAssetVersionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).commitAllocation(any(), any(), any());
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
        }

        @Test
        @DisplayName("NoSuchUpload error on CompleteMultipartUpload: preserves ambiguous state without automatic registration")
        void completeNoSuchUploadPreservesAmbiguityWithoutAutomaticRegistration() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-nosuchupload";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any()))
                    .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

            // Diagnostic probe sees object in storage is not present
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY))
                    .thenThrow(new StorageObjectNotFoundException(STORAGE_KEY));

            // Invariant: MUST fail closed and preserve COMPLETING; must NOT register asset
            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(StorageUploadNotFoundException.class)
                    .hasMessageContaining(STORAGE_KEY.value());

            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(mediaAssetVersionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).commitAllocation(any(), any(), any());
            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
        }

        @Test
        @DisplayName("5xx error on CompleteMultipartUpload: preserves ambiguous state without automatic registration")
        void complete5xxErrorPreservesAmbiguityWithoutAutomaticRegistration() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-5xx-error";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            S3Exception s3Ex = (S3Exception) S3Exception.builder()
                    .statusCode(503)
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("ServiceUnavailable").errorMessage("Server busy").build())
                    .build();
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any()))
                    .thenThrow(new StorageAmbiguousCompletionException("503 Service Unavailable: Backblaze B2 temporary failure", s3Ex));

            // Diagnostic probe sees object in storage is not present
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY))
                    .thenThrow(new StorageObjectNotFoundException(STORAGE_KEY));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("503 Service Unavailable");

            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(mediaAssetVersionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).commitAllocation(any(), any(), any());
            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
        }

        @Test
        @DisplayName("CompleteMultipartUpload success with acknowledged provider response: registers asset and completes session")
        void completeSuccessWithAcknowledgedProviderResponseCompletesAndRegisters() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-ack-success";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"acknowledged-etag\"",
                    "ver-acknowledged"
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"acknowledged-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    "ver-acknowledged",
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
            when(mediaAssetVersionRepositoryPort.save(any(MediaAssetVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
            verify(storageKeyAllocationService).commitAllocation(eq(allocId), eq(StorageKeyAllocationType.ASSET_VERSION), any(UUID.class));
            verify(mediaAssetRepositoryPort).save(any(MediaAsset.class));
            verify(mediaAssetVersionRepositoryPort).save(any(MediaAssetVersion.class));
        }

        @Test
        @DisplayName("Delayed object visibility (HEAD 404 after CompleteMultipartUpload): fails closed and preserves state")
        void delayedObjectVisibilityFailsClosedWithoutAbort() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-head-404";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\""
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            // HEAD 404: delayed visibility
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY))
                    .thenThrow(new StorageObjectNotFoundException(STORAGE_KEY));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(StorageObjectNotFoundException.class);

            // Do NOT register media asset, do NOT abort session
            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).commitAllocation(any(), any(), any());
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
        }

        @Test
        @DisplayName("ListParts unrelated StorageObjectNotFoundException does not enter recovery from HEAD-only evidence")
        void listPartsUnrelatedNotFoundDoesNotEnterRecovery() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-list-unrelated-notfound";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            // listUploadedParts throws unrelated StorageObjectNotFoundException without NoSuchUpload evidence
            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                    .thenThrow(new StorageObjectNotFoundException(STORAGE_KEY));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(StorageObjectNotFoundException.class);

            // Invariant: no recovery or registration on unrelated StorageObjectNotFoundException
            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(mediaAssetVersionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).commitAllocation(any(), any(), any());
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
        }

        @Test
        @DisplayName("ListParts NoSuchUpload with HEAD 404 preserves ambiguous state without abort or deletion")
        void listPartsNoSuchUploadWithHead404PreservesAmbiguityWithoutAbort() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-list-nosuch-head-404";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
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

            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
            verify(mediaAssetRepositoryPort, never()).save(any());
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
        }

        @Test
        @DisplayName("ListParts NoSuchUpload with foreign provenance metadata preserves ambiguous state without registering MediaAsset")
        void listPartsNoSuchUploadWithForeignMetadataPreservesAmbiguityWithoutRegistration() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-list-nosuch-foreign";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                    .thenThrow(new StorageUploadNotFoundException(STORAGE_KEY, providerUploadId));

            MultipartObjectMetadata foreignMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"foreign-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, UUID.randomUUID().toString(), // Foreign session ID
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
            verify(storageKeyAllocationService, never()).commitAllocation(any(), any(), any());
            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
        }

        @Test
        @DisplayName("Generic ListParts storage failure preserves ambiguous state without probing HEAD or completing")
        void genericListPartsStorageExceptionPreservesAmbiguityWithoutProbingHead() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-list-generic-fail";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId))
                    .thenThrow(new StorageException("Connection reset by peer"));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Connection reset by peer");

            verify(multipartStoragePort, never()).inspectObjectMetadata(any());
            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
        }

        @Test
        @DisplayName("Retry after rolled-back TX-B executes clean registration of MediaAsset and MediaAssetVersion")
        void retryAfterRolledBackTxBRegistersAssetAndVersionCleanly() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-retry-clean";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(createStandard2UploadedParts());

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"retry-clean-etag\"",
                    "ver-retry-clean"
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"retry-clean-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    "ver-retry-clean",
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
            when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);
            when(mediaAssetVersionRepositoryPort.save(any(MediaAssetVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
            assertThat(result.getRegisteredAssetId()).isNotNull();

            // Invariant: exactly 1 MediaAsset and 1 MediaAssetVersion are saved
            verify(mediaAssetRepositoryPort, times(1)).save(any(MediaAsset.class));
            verify(mediaAssetVersionRepositoryPort, times(1)).save(any(MediaAssetVersion.class));
            verify(storageKeyAllocationService, times(1)).commitAllocation(eq(allocId), eq(StorageKeyAllocationType.ASSET_VERSION), any(UUID.class));
        }
    }

    // =============================================================================================
    // 5. Mandatory Provenance Metadata Fail-Closed Tests
    // =============================================================================================

    @Nested
    @DisplayName("5. Mandatory Provenance Metadata Fail-Closed Tests")
    class ProvenanceMetadataFailClosedTests {

        private void setupCommonPreconditions(
                UUID sessionId,
                UUID allocId,
                String providerUploadId,
                long declaredSize,
                List<MultipartManifestPart> manifest,
                List<MultipartUploadedPart> uploadedParts
        ) {
            setupCommonPreconditions(sessionId, allocId, providerUploadId, declaredSize, manifest, uploadedParts, true);
        }

        private void setupCommonPreconditions(
                UUID sessionId,
                UUID allocId,
                String providerUploadId,
                long declaredSize,
                List<MultipartManifestPart> manifest,
                List<MultipartUploadedPart> uploadedParts,
                boolean stubDefaultComplete
        ) {
            MediaUploadSession session = createCompletingSession(sessionId, allocId, UUID.randomUUID(), declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            if (stubDefaultComplete) {
                MultipartCompletedObject completed = new MultipartCompletedObject(
                        StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                        "\"complete-etag\""
                );
                when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);
            }
        }

        @Test
        @DisplayName("Missing session-id in userMetadata fails closed without registering MediaAsset")
        void missingSessionIdFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-missing-sess", declaredSize, manifest, uploadedParts);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()), // missing session-id
                    null
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Completed object provenance metadata [session-id] mismatch or missing");

            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(mediaAssetVersionRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Foreign or mismatched session-id in userMetadata fails closed")
        void foreignSessionIdFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-foreign-sess", declaredSize, manifest, uploadedParts);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, UUID.randomUUID().toString(), // foreign session ID!
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Completed object provenance metadata [session-id] mismatch or missing");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Missing allocation-id in userMetadata fails closed")
        void missingAllocationIdFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-missing-alloc", declaredSize, manifest, uploadedParts);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString()), // missing allocation-id
                    null
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Completed object provenance metadata [allocation-id] mismatch or missing");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Foreign allocation-id in userMetadata fails closed")
        void foreignAllocationIdFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-foreign-alloc", declaredSize, manifest, uploadedParts);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, UUID.randomUUID().toString() // foreign alloc ID
                    ),
                    null
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Completed object provenance metadata [allocation-id] mismatch or missing");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Completed object size mismatch against declaredSizeBytes fails closed")
        void completedObjectSizeMismatchFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-size-mismatch", declaredSize, manifest, uploadedParts);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    52428000L, // 800 bytes less than declared!
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Completed object size mismatch");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Completed object MIME type mismatch (not video/mp4) fails closed")
        void completedObjectMimeMismatchFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-mime-mismatch", declaredSize, manifest, uploadedParts);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/x-matroska", // not video/mp4
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Completed object content type mismatch");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Completed object ETag mismatch against CompleteMultipartUpload response detects overwrite race and fails closed")
        void completedObjectETagMismatchDetectsOverwriteRace() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-etag-race", declaredSize, manifest, uploadedParts);

            // HeadObject returns a different ETag than completedObject ("complete-etag")
            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"different-overwritten-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Completed object ETag mismatch (potential concurrent overwrite)");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Completed object provider mismatch fails closed")
        void completedObjectProviderMismatchFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-prov-mismatch", declaredSize, manifest, uploadedParts);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null,
                    StorageProviderId.of("r2"), // Mismatched provider!
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Completed object provider mismatch");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Completed object bucket mismatch fails closed")
        void completedObjectBucketMismatchFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-bucket-mismatch", declaredSize, manifest, uploadedParts);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null,
                    B2_PROVIDER,
                    "foreign-bucket" // Mismatched bucket!
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Completed object bucket mismatch");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Conflicting metadata alias values fail closed immediately")
        void conflictingMetadataAliasesFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-alias-conflict", declaredSize, manifest, uploadedParts);

            // User metadata contains conflicting values for session-id
            Map<String, String> conflictingMeta = Map.of(
                    "session-id", sessionId.toString(),
                    "x-amz-meta-session-id", UUID.randomUUID().toString(), // Conflicting alias value!
                    MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
            );

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    conflictingMeta,
                    null,
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Conflicting metadata values found for key [session-id]");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("VersionId mismatch between CompleteMultipartUpload and HeadObject detects overwrite race and fails closed")
        void versionIdMismatchBetweenCompleteAndHeadFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, UUID.randomUUID(), declaredSize, "up-ver-race", null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, "up-ver-race")).thenReturn(uploadedParts);

            // CompleteMultipartUpload returns version "ver-1"
            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\"",
                    "ver-1"
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq("up-ver-race"), any())).thenReturn(completed);

            // HeadObject returns different version "ver-2" (e.g. race replacement in bucket)
            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    "ver-2", // Mismatched versionId!
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Completed object versionId mismatch (potential concurrent overwrite)");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Expected versionId present from provider but missing on HEAD fails closed")
        void expectedVersionIdPresentHeadMissingFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-ver-missing-head", declaredSize, manifest, uploadedParts, false);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\"",
                    "ver-expected-123"
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq("up-ver-missing-head"), any())).thenReturn(completed);

            // HEAD returns null versionId
            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null, // Missing HEAD versionId!
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Completed object versionId missing on HEAD metadata: expected ver-expected-123");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Matching expected and HEAD versionId succeeds")
        void expectedVersionIdMatchesHeadSuccess() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-ver-match", declaredSize, manifest, uploadedParts, false);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\"",
                    "ver-matching-999"
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq("up-ver-match"), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    "ver-matching-999",
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
            when(mediaAssetVersionRepositoryPort.save(any(MediaAssetVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);
            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        }

        @Test
        @DisplayName("Null expected versionId preserves safe behavior for unversioned providers")
        void expectedVersionIdNullPreservesSafeBehavior() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-ver-null-expected", declaredSize, manifest, uploadedParts, false);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\"",
                    null // Provider returned no versionId
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq("up-ver-null-expected"), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    "ver-b2-ignored",
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
            when(mediaAssetVersionRepositoryPort.save(any(MediaAssetVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);
            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        }

        @Test
        @DisplayName("Completed object location provider mismatch fails closed")
        void completedObjectLocationProviderMismatchFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-loc-prov-mismatch", declaredSize, manifest, uploadedParts, false);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(StorageProviderId.of("s3"), STORAGE_KEY), // Provider is s3 instead of b2!
                    "\"complete-etag\"",
                    "ver-1"
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq("up-loc-prov-mismatch"), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    "ver-1",
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Provider completed object location provider mismatch");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Completed object location key mismatch fails closed")
        void completedObjectLocationKeyMismatchFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            long declaredSize = 52428800L;
            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            setupCommonPreconditions(sessionId, allocId, "up-loc-key-mismatch", declaredSize, manifest, uploadedParts, false);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, StorageKey.of("other/wrong-key.mp4")), // Key mismatch!
                    "\"complete-etag\"",
                    "ver-1"
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq("up-loc-key-mismatch"), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    "ver-1",
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Provider completed object location key mismatch");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }
    }

    // =============================================================================================
    // 6. Concurrency, Fencing & Competing Workers Tests
    // =============================================================================================

    @Nested
    @DisplayName("6. Concurrency, Fencing & Competing Workers Tests")
    class ConcurrencyAndFencingTests {

        @Test
        @DisplayName("Competing worker is rejected when another worker holds an active completion lease")
        void competingWorkerRejectedWhenActiveLeaseHeld() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, "b2-up-competing", null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            // Session already has an active lease held by worker-1
            session.claimCompletion("COMPLETION_CLAIM:worker-1", NOW.plus(Duration.ofMinutes(5)), NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            // Worker 2 attempts completion
            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Concurrent completion in progress")
                    .hasMessageContaining("lease held by COMPLETION_CLAIM:worker-1");

            verify(multipartStoragePort, never()).listUploadedParts(any(), any());
            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("Reclaims completion lease when earlier worker lease has expired")
        void reclaimsExpiredLease() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-expired-lease";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            // Earlier lease expired 1 minute ago
            session.claimCompletion("COMPLETION_CLAIM:dead-worker", NOW.minus(Duration.ofMinutes(1)), NOW.minus(Duration.ofMinutes(6)));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\""
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
            when(mediaAssetVersionRepositoryPort.save(any(MediaAssetVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);
            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        }

        @Test
        @DisplayName("Cannot abort session when session has an active completion claim")
        void cannotAbortCompletingSession() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, "b2-up-abort-race", null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.abortSession(sessionId, PRINCIPAL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot abort session with active completion claim");
        }

        @Test
        @DisplayName("Lease expiration before TX-B registration fails closed against stale worker")
        void leaseExpiredBeforeTxBRegistrationFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-stale-worker";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\""
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null,
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenAnswer(inv -> {
                // Remote complete / inspection took 10 minutes: lease expired before TX-B!
                currentTime.set(NOW.plus(Duration.ofMinutes(10)));
                return headMetadata;
            });

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Stale worker cannot commit registration");

            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(mediaAssetVersionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).commitAllocation(any(), any(), any());
        }

        @Test
        @DisplayName("Reclaim by competing worker causes stale worker claim token mismatch in TX-B")
        void reclaimByCompetingWorkerRejectsStaleWorkerInTxB() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-reclaimed-worker";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            // In TX-A, findById returns session. In TX-B, competing worker has reclaimed the lease!
            when(sessionRepositoryPort.findById(sessionId)).thenAnswer(inv -> {
                if (session.getLeaseOwner() != null) {
                    // Simulating competing worker claiming lease while original worker was in B2 call
                    session.claimCompletion("COMPLETION_CLAIM:competing-worker-2", NOW.plus(Duration.ofMinutes(15)), NOW.plus(Duration.ofMinutes(6)));
                }
                return Optional.of(session);
            });
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\""
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null,
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Completion claim token mismatch");

            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(mediaAssetVersionRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Inconsistent COMMITTED allocation state while session is still COMPLETING fails closed in TX-B")
        void inconsistentCommittedAllocationDuringTxBFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-inconsistent-alloc";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            // In TX-A, allocation is RESERVED. But by TX-B, allocation is already COMMITTED!
            StorageKeyAllocation reservedAllocation = createReservedAllocation(allocId, sessionId);
            StorageKeyAllocation committedAllocation = createReservedAllocation(allocId, sessionId);
            committedAllocation.commit(StorageKeyAllocationType.ASSET_VERSION, UUID.randomUUID(), NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId))
                    .thenReturn(Optional.of(reservedAllocation)) // First call in TX-A
                    .thenReturn(Optional.of(committedAllocation)); // Second call in TX-B
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\""
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null,
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already COMMITTED but upload session")
                    .hasMessageContaining("is still in status COMPLETING. Inconsistent state.");

            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(mediaAssetVersionRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("COMPLETING session with unexpected pre-existing registeredAssetId fails closed in TX-B")
        void completingSessionWithUnexpectedPreExistingRegisteredAssetIdFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID unexpectedAssetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-unexpected-asset";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));
            // Simulate corrupted state: session in COMPLETING has unexpected registeredAssetId
            org.springframework.test.util.ReflectionTestUtils.setField(session, "registeredAssetId", unexpectedAssetId);

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\"",
                    "ver-1"
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    "ver-1",
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already references registeredAssetId")
                    .hasMessageContaining("Inconsistent partial registration state");

            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(mediaAssetVersionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).commitAllocation(any(), any(), any());
        }

        @Test
        @DisplayName("Unexpected existing version 1 for newly created asset fails closed in TX-B")
        void unexpectedExistingVersionOneForNewAssetFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID existingVersionId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-unexpected-version";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\"",
                    "ver-1"
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    "ver-1",
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            MediaAsset newlySavedAsset = MediaAsset.registerInitial(UUID.randomUUID(), MediaType.VIDEO, MediaVisibility.PUBLIC, "screen", NOW);
            when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenReturn(newlySavedAsset);

            MediaAssetVersion existingVersion = MediaAssetVersion.create(
                    existingVersionId,
                    newlySavedAsset.getId(),
                    1,
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    null,
                    ContentHash.of(VALID_CONTENT_HASH),
                    ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                    MimeType.of("video/mp4"),
                    declaredSize,
                    "video.mp4",
                    NOW
            );
            // Simulate unexpected pre-existing version 1 for this asset
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(newlySavedAsset.getId(), 1))
                    .thenReturn(Optional.of(existingVersion));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unexpected existing MediaAssetVersion 1 found for newly created asset");

            verify(mediaAssetVersionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).commitAllocation(any(), any(), any());
        }

        @Test
        @DisplayName("Duplicate storage location for new MediaAssetVersion fails closed with DuplicateStorageLocationException")
        void duplicateStorageLocationForNewVersionFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-dup-location";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\"",
                    "ver-1"
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    "ver-1",
                    B2_PROVIDER,
                    B2_BUCKET
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            MediaAsset newlySavedAsset = MediaAsset.registerInitial(UUID.randomUUID(), MediaType.VIDEO, MediaVisibility.PUBLIC, "screen", NOW);
            when(mediaAssetRepositoryPort.save(any(MediaAsset.class))).thenReturn(newlySavedAsset);

            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(any(UUID.class), eq(1))).thenReturn(Optional.empty());
            when(mediaAssetVersionRepositoryPort.existsByStorageLocation(StorageLocation.of(B2_PROVIDER, STORAGE_KEY))).thenReturn(true);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(DuplicateStorageLocationException.class);

            verify(mediaAssetVersionRepositoryPort, never()).save(any());
            verify(storageKeyAllocationService, never()).commitAllocation(any(), any(), any());
        }
    }

    // =============================================================================================
    // 7. Idempotency & Retry Tests
    // =============================================================================================

    @Nested
    @DisplayName("7. Idempotency & Retry Tests")
    class IdempotencyAndRetryTests {

        @Test
        @DisplayName("Repeated completion on already-COMPLETED session returns idempotently when graph is consistent")
        void repeatedCompletionReturnsIdempotently() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID assetId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            long declaredSize = 52428800L;

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession completedSession = createCompletingSession(sessionId, allocId, targetId, declaredSize, "b2-up-done", null);
            String manifestFingerprint = MultipartManifestCanonicalizer.validateAndCanonicalize(completedSession, manifest);
            completedSession.recordManifestHash(manifestFingerprint);
            completedSession.markCompleted(assetId, NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(completedSession));

            // Mock consistent relational graph
            MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, "screen", NOW);
            when(mediaAssetRepositoryPort.findById(assetId)).thenReturn(Optional.of(asset));

            MediaAssetVersion version = MediaAssetVersion.create(
                    versionId, assetId, 1,
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    null,
                    ContentHash.of(VALID_CONTENT_HASH),
                    ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                    MimeType.of("video/mp4"),
                    declaredSize,
                    "video.mp4",
                    NOW
            );
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(assetId, 1)).thenReturn(Optional.of(version));

            StorageKeyAllocation committedAllocation = createReservedAllocation(allocId, sessionId);
            committedAllocation.commit(StorageKeyAllocationType.ASSET_VERSION, versionId, NOW);
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(committedAllocation));

            // Call completeMultipartUpload on already COMPLETED session
            MediaUploadSession result = service.completeMultipartUpload(sessionId, PRINCIPAL, manifest);

            assertThat(result.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
            assertThat(result.getRegisteredAssetId()).isEqualTo(assetId);

            // Verifies NO remote provider calls made
            verify(multipartStoragePort, never()).listUploadedParts(any(), any());
            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
            verify(multipartStoragePort, never()).inspectObjectMetadata(any());
            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Idempotent check on COMPLETED session fails closed when MediaAsset is missing")
        void idempotentCompletionFailsClosedWhenAssetMissing() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID assetId = UUID.randomUUID();
            long declaredSize = 52428800L;

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession completedSession = createCompletingSession(sessionId, allocId, targetId, declaredSize, "b2-up-done", null);
            String manifestFingerprint = MultipartManifestCanonicalizer.validateAndCanonicalize(completedSession, manifest);
            completedSession.recordManifestHash(manifestFingerprint);
            completedSession.markCompleted(assetId, NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(completedSession));
            when(mediaAssetRepositoryPort.findById(assetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Registered MediaAsset not found for completed session");
        }

        @Test
        @DisplayName("Idempotent check on COMPLETED session fails closed when storage allocation is not committed to version")
        void idempotentCompletionFailsClosedWhenAllocationInconsistent() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID assetId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            long declaredSize = 52428800L;

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession completedSession = createCompletingSession(sessionId, allocId, targetId, declaredSize, "b2-up-done", null);
            String manifestFingerprint = MultipartManifestCanonicalizer.validateAndCanonicalize(completedSession, manifest);
            completedSession.recordManifestHash(manifestFingerprint);
            completedSession.markCompleted(assetId, NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(completedSession));

            MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, "screen", NOW);
            when(mediaAssetRepositoryPort.findById(assetId)).thenReturn(Optional.of(asset));

            MediaAssetVersion version = MediaAssetVersion.create(
                    versionId, assetId, 1,
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    null,
                    ContentHash.of(VALID_CONTENT_HASH),
                    ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                    MimeType.of("video/mp4"),
                    declaredSize,
                    "video.mp4",
                    NOW
            );
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(assetId, 1)).thenReturn(Optional.of(version));

            // Allocation is still RESERVED instead of COMMITTED!
            StorageKeyAllocation reservedAllocation = createReservedAllocation(allocId, sessionId);
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(reservedAllocation));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Storage allocation")
                    .hasMessageContaining("state is inconsistent with completed session");
        }

        @Test
        @DisplayName("Repeated completion with conflicting manifest on COMPLETED session is rejected")
        void conflictingManifestOnCompletedSessionRejected() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID assetId = UUID.randomUUID();
            long declaredSize = 52428800L;

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession completedSession = createCompletingSession(sessionId, allocId, targetId, declaredSize, "b2-up-done", null);
            String manifestFingerprint = MultipartManifestCanonicalizer.validateAndCanonicalize(completedSession, manifest);
            completedSession.recordManifestHash(manifestFingerprint);
            completedSession.markCompleted(assetId, NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(completedSession));

            List<MultipartManifestPart> conflictingManifest = List.of(
                    new MultipartManifestPart(1, 26214400L, "\"wrong-etag-1\""),
                    new MultipartManifestPart(2, 26214400L, "\"etag-part-2\"")
            );

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, conflictingManifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Submitted manifest does not match completed session manifestHash");
        }

        @Test
        @DisplayName("Completion request from unauthorized principal is rejected")
        void unauthorizedPrincipalRejected() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, "b2-up-auth", null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, "unauthorized-hacker", manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Principal 'unauthorized-hacker' is not authorized to complete upload session");

            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("Idempotent check on COMPLETED session fails closed when allocation provider mismatches")
        void idempotentSessionAllocationProviderMismatchFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID assetId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            long declaredSize = 52428800L;

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession completedSession = createCompletingSession(sessionId, allocId, targetId, declaredSize, "b2-up-done", null);
            String manifestFingerprint = MultipartManifestCanonicalizer.validateAndCanonicalize(completedSession, manifest);
            completedSession.recordManifestHash(manifestFingerprint);
            completedSession.markCompleted(assetId, NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(completedSession));

            MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, "screen", NOW);
            when(mediaAssetRepositoryPort.findById(assetId)).thenReturn(Optional.of(asset));

            MediaAssetVersion version = MediaAssetVersion.create(
                    versionId, assetId, 1,
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    null,
                    ContentHash.of(VALID_CONTENT_HASH),
                    ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                    MimeType.of("video/mp4"),
                    declaredSize,
                    "video.mp4",
                    NOW
            );
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(assetId, 1)).thenReturn(Optional.of(version));

            // Allocation has mismatched provider (s3 instead of b2)
            StorageKeyAllocation mismatchedAllocation = StorageKeyAllocation.rehydrate(
                    allocId, StorageProviderId.of("s3"), B2_BUCKET, STORAGE_KEY,
                    StorageKeyAllocationStatus.COMMITTED, StorageKeyAllocationType.ASSET_VERSION, versionId,
                    NOW.minus(Duration.ofHours(1)), NOW, 0L
            );
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(mismatchedAllocation));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("provider/bucket/key")
                    .hasMessageContaining("does not match session");
        }

        @Test
        @DisplayName("Idempotent check on COMPLETED session fails closed when version content hash mismatches")
        void idempotentSessionVersionContentHashMismatchFailsClosed() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID assetId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            long declaredSize = 52428800L;

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            MediaUploadSession completedSession = createCompletingSession(sessionId, allocId, targetId, declaredSize, "b2-up-done", null);
            String manifestFingerprint = MultipartManifestCanonicalizer.validateAndCanonicalize(completedSession, manifest);
            completedSession.recordManifestHash(manifestFingerprint);
            completedSession.markCompleted(assetId, NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(completedSession));

            MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, "screen", NOW);
            when(mediaAssetRepositoryPort.findById(assetId)).thenReturn(Optional.of(asset));

            // Version has different content hash
            MediaAssetVersion version = MediaAssetVersion.create(
                    versionId, assetId, 1,
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    null,
                    ContentHash.of("0000000000000000000000000000000000000000000000000000000000000000"), // Hash mismatch!
                    ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                    MimeType.of("video/mp4"),
                    declaredSize,
                    "video.mp4",
                    NOW
            );
            when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(assetId, 1)).thenReturn(Optional.of(version));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MediaAssetVersion content hash does not match session declared hash");
        }
    }

    // =============================================================================================
    // 8. Transactional Integrity, Rollback & Transaction Guards Tests
    // =============================================================================================

    @Nested
    @DisplayName("8. Transactional Integrity, Rollback & Transaction Guards Tests")
    class TransactionalIntegrityTests {

        @Test
        @DisplayName("TX-B failure rolls back atomically without remote compensating deletion, preserving state")
        void txBFailureRollsBackWithoutRemoteDeletion() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            long declaredSize = 52428800L;
            String providerUploadId = "b2-up-txb-fail";

            List<MultipartManifestPart> manifest = createStandard2PartManifest();
            List<MultipartUploadedPart> uploadedParts = createStandard2UploadedParts();

            MediaUploadSession session = createCompletingSession(sessionId, allocId, targetId, declaredSize, providerUploadId, null);
            session.recordManifestHash(MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest));

            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any(MediaUploadSession.class))).thenAnswer(inv -> inv.getArgument(0));

            when(multipartStoragePort.listUploadedParts(STORAGE_KEY, providerUploadId)).thenReturn(uploadedParts);

            MultipartCompletedObject completed = new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "\"complete-etag\""
            );
            when(multipartStoragePort.completeMultipartUpload(eq(STORAGE_KEY), eq(providerUploadId), any())).thenReturn(completed);

            MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                    STORAGE_KEY,
                    declaredSize,
                    "video/mp4",
                    "\"complete-etag\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, sessionId.toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, allocId.toString()
                    ),
                    null
            );
            when(multipartStoragePort.inspectObjectMetadata(STORAGE_KEY)).thenReturn(headMetadata);

            // Database failure during MediaAsset registration in TX-B
            when(mediaAssetRepositoryPort.save(any(MediaAsset.class)))
                    .thenThrow(new RuntimeException("Database connection failure during MediaAsset insert"));

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, manifest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Database connection failure during MediaAsset insert");

            // CRITICAL INVARIANT: No remote deletion of the completed object!
            verify(multipartStoragePort, never()).delete(any());
            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());
            // No tombstone of allocation
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
        }

        @Test
        @DisplayName("Invocation under active ambient transaction is rejected (Direct call)")
        void directCallUnderAmbientTransactionRejected() {
            UUID sessionId = UUID.randomUUID();
            TransactionSynchronizationManager.setActualTransactionActive(true);

            assertThatThrownBy(() -> service.completeMultipartUpload(sessionId, PRINCIPAL, List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completeMultipartUpload cannot be invoked from an active ambient transaction");
        }

        @Test
        @DisplayName("Spring-proxied caller under active transaction is rejected with IllegalTransactionStateException (Propagation.NEVER)")
        void proxiedCallerUnderAmbientTransactionRejected() {
            UUID sessionId = UUID.randomUUID();

            ProxyFactory proxyFactory = new ProxyFactory(service);
            AnnotationTransactionAttributeSource tas = new AnnotationTransactionAttributeSource();
            TransactionInterceptor ti = new TransactionInterceptor(transactionManager, tas);
            proxyFactory.addAdvice(ti);
            MediaUploadSessionService proxiedService = (MediaUploadSessionService) proxyFactory.getProxy();

            TransactionSynchronizationManager.setActualTransactionActive(true);

            assertThatThrownBy(() -> proxiedService.completeMultipartUpload(sessionId, PRINCIPAL, List.of()))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @DisplayName("Validates non-null sessionId and non-blank requestingPrincipalId")
        void validatesInputArguments() {
            assertThatThrownBy(() -> service.completeMultipartUpload(null, PRINCIPAL, List.of()))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> service.completeMultipartUpload(UUID.randomUUID(), "   ", List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requestingPrincipalId");
        }
    }
}
