package com.universe.media.application.asset;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.application.storage.MediaStorageCutoverBarrier;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.contracts.dto.MediaVersionUploadOutcome;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Media Writer Allocation Cutover Integration Tests")
class MediaWriterAllocationCutoverTest {

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaStorageRoutingService routingService;

    @Mock
    private StorageProviderResolverPort storageResolverPort;

    @Mock
    private BinaryStoragePort binaryStoragePort;

    @Mock
    private RegisterMediaAssetUseCase registerMediaAssetUseCase;

    @Mock
    private RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase;

    @Mock
    private RasterContentSignatureValidator rasterValidator;

    @Mock
    private VideoUploadValidator videoValidator;

    @Mock
    private StorageKeyAllocationService allocationService;

    @Mock
    private MediaStorageCutoverBarrier cutoverBarrier;

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/abc.mp4");
    private static final UUID ALLOCATION_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private StorageKeyAllocation mockAllocation;

    @BeforeEach
    void setUp() {
        mockAllocation = StorageKeyAllocation.reserve(
                ALLOCATION_ID,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                StorageKeyAllocationType.ASSET_VERSION,
                UUID.randomUUID(),
                NOW
        );

        lenient().when(routingService.resolveWriteProvider(any(), any())).thenReturn(B2_PROVIDER);
        lenient().when(routingService.generateStorageKey(any(), any(), any(), anyInt())).thenReturn(STORAGE_KEY);
        lenient().when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn(B2_BUCKET);
        lenient().when(storageResolverPort.resolve(B2_PROVIDER)).thenReturn(binaryStoragePort);
        lenient().when(binaryStoragePort.providerId()).thenReturn(B2_PROVIDER);
        lenient().when(allocationService.reserveKey(any(), any(), any(), any(), any())).thenReturn(mockAllocation);
        lenient().when(cutoverBarrier.isCutoverActive()).thenReturn(true);
    }

    // =============================================================================================
    // 1. UploadMediaAssetUseCase
    // =============================================================================================

    @Nested
    @DisplayName("UploadMediaAssetUseCase Allocation Lifecycle")
    class UploadMediaAssetAllocationTests {

        private UploadMediaAssetUseCase useCase;

        @BeforeEach
        void initUseCase() {
            useCase = new UploadMediaAssetUseCase(
                    routingService,
                    storageResolverPort,
                    registerMediaAssetUseCase,
                    rasterValidator,
                    videoValidator,
                    allocationService,
                    cutoverBarrier
            );
        }

        @Test
        @DisplayName("Cutover active: reserves key before store, and passes allocationId to register on success")
        void reservesAndCommitsOnSuccess() {
            byte[] content = "fake-video-content-longer-than-zero".getBytes(StandardCharsets.UTF_8);
            StoredBinaryObject stored = new StoredBinaryObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "https://cdn.example.com/video.mp4"
            );
            when(binaryStoragePort.store(eq(STORAGE_KEY), any(InputStream.class), eq((long) content.length), any(MimeType.class)))
                    .thenReturn(stored);

            UUID assetId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            RegisterMediaAssetResult regResult = new RegisterMediaAssetResult(
                    assetId,
                    versionId,
                    1,
                    MediaType.VIDEO,
                    MediaVisibility.PUBLIC,
                    MediaAssetStatus.ACTIVE,
                    NOW
            );
            when(registerMediaAssetUseCase.execute(any(RegisterMediaAssetCommand.class)))
                    .thenReturn(regResult);

            UploadMediaAssetCommand command = new UploadMediaAssetCommand(
                    new ByteArrayInputStream(content),
                    content.length,
                    "video/mp4",
                    MediaType.VIDEO,
                    MediaVisibility.PUBLIC,
                    "test.mp4",
                    "screen"
            );

            UploadMediaAssetResult result = useCase.execute(command);

            assertThat(result).isNotNull();
            assertThat(result.assetId()).isEqualTo(assetId);
            verify(allocationService).reserveKey(eq(B2_PROVIDER), eq(B2_BUCKET), eq(STORAGE_KEY), eq(StorageKeyAllocationType.ASSET_VERSION), any());
            verify(binaryStoragePort).store(eq(STORAGE_KEY), any(InputStream.class), eq((long) content.length), any(MimeType.class));
            verify(registerMediaAssetUseCase).execute(org.mockito.ArgumentMatchers.argThat(cmd -> ALLOCATION_ID.equals(cmd.expectedAllocationId())));
            verify(binaryStoragePort, never()).delete(any());
        }

        @Test
        @DisplayName("Cutover active: on store failure tombstones allocation to prevent key reuse, without speculative delete")
        void tombstonesAllocationOnStoreFailureWithoutSpeculativeDelete() {
            byte[] content = "fake-video-content".getBytes(StandardCharsets.UTF_8);
            when(binaryStoragePort.store(any(), any(), anyLong(), any()))
                    .thenThrow(new StorageException("B2 store failed"));

            UploadMediaAssetCommand command = new UploadMediaAssetCommand(
                    new ByteArrayInputStream(content),
                    content.length,
                    "video/mp4",
                    MediaType.VIDEO,
                    MediaVisibility.PUBLIC,
                    "test.mp4",
                    "screen"
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(StorageException.class);

            verify(allocationService).reserveKey(any(), any(), any(), any(), any());
            verify(allocationService).tombstoneAllocation(ALLOCATION_ID);
            // Must NOT speculatively delete on store failure
            verify(binaryStoragePort, never()).delete(any());
            verify(registerMediaAssetUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Cutover active: compensates confirmed newly created binary and tombstones allocation when registration fails")
        void compensatesStorageAndTombstonesOnRegistrationFailure() {
            byte[] content = "fake-video-content".getBytes(StandardCharsets.UTF_8);
            StoredBinaryObject stored = new StoredBinaryObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "https://cdn.example.com/video.mp4"
            );
            when(binaryStoragePort.store(any(), any(), anyLong(), any()))
                    .thenReturn(stored);
            when(registerMediaAssetUseCase.execute(any()))
                    .thenThrow(new RuntimeException("DB down"));

            UploadMediaAssetCommand command = new UploadMediaAssetCommand(
                    new ByteArrayInputStream(content),
                    content.length,
                    "video/mp4",
                    MediaType.VIDEO,
                    MediaVisibility.PUBLIC,
                    "test.mp4",
                    "screen"
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("DB down");

            verify(binaryStoragePort).delete(STORAGE_KEY);
            verify(allocationService).tombstoneAllocation(ALLOCATION_ID);
        }

        @Test
        @DisplayName("Cutover OFF: legacy success operates without reservation or allocation ID")
        void legacySuccessWithoutAllocationWhenCutoverInactive() {
            when(cutoverBarrier.isCutoverActive()).thenReturn(false);

            byte[] content = "fake-video-content".getBytes(StandardCharsets.UTF_8);
            StoredBinaryObject stored = new StoredBinaryObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "https://cdn.example.com/video.mp4"
            );
            when(binaryStoragePort.store(any(), any(), anyLong(), any()))
                    .thenReturn(stored);

            UUID assetId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            RegisterMediaAssetResult regResult = new RegisterMediaAssetResult(
                    assetId,
                    versionId,
                    1,
                    MediaType.VIDEO,
                    MediaVisibility.PUBLIC,
                    MediaAssetStatus.ACTIVE,
                    NOW
            );
            when(registerMediaAssetUseCase.execute(any(RegisterMediaAssetCommand.class)))
                    .thenReturn(regResult);

            UploadMediaAssetCommand command = new UploadMediaAssetCommand(
                    new ByteArrayInputStream(content),
                    content.length,
                    "video/mp4",
                    MediaType.VIDEO,
                    MediaVisibility.PUBLIC,
                    "test.mp4",
                    "screen"
            );

            UploadMediaAssetResult result = useCase.execute(command);

            assertThat(result).isNotNull();
            verify(allocationService, never()).reserveKey(any(), any(), any(), any(), any());
            verify(allocationService, never()).tombstoneAllocation(any());
            verify(registerMediaAssetUseCase).execute(org.mockito.ArgumentMatchers.argThat(cmd -> cmd.expectedAllocationId() == null));
        }

        @Test
        @DisplayName("Cutover OFF: legacy store failure has ZERO speculative delete, zero allocation, zero tombstone")
        void legacyStoreFailureHasNoSideEffectsWhenCutoverInactive() {
            when(cutoverBarrier.isCutoverActive()).thenReturn(false);

            byte[] content = "fake-video-content".getBytes(StandardCharsets.UTF_8);
            when(binaryStoragePort.store(any(), any(), anyLong(), any()))
                    .thenThrow(new StorageException("B2 store failed"));

            UploadMediaAssetCommand command = new UploadMediaAssetCommand(
                    new ByteArrayInputStream(content),
                    content.length,
                    "video/mp4",
                    MediaType.VIDEO,
                    MediaVisibility.PUBLIC,
                    "test.mp4",
                    "screen"
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(StorageException.class)
                    .hasMessage("B2 store failed");

            // Strict legacy behavior: zero delete, zero reserve, zero tombstone
            verify(binaryStoragePort, never()).delete(any());
            verify(allocationService, never()).reserveKey(any(), any(), any(), any(), any());
            verify(allocationService, never()).tombstoneAllocation(any());
        }
    }

    // =============================================================================================
    // 2. UploadMediaAssetVersionUseCase
    // =============================================================================================

    @Nested
    @DisplayName("UploadMediaAssetVersionUseCase Allocation Lifecycle")
    class UploadMediaAssetVersionAllocationTests {

        private UploadMediaAssetVersionUseCase useCase;

        @BeforeEach
        void initUseCase() {
            useCase = new UploadMediaAssetVersionUseCase(
                    mediaAssetRepositoryPort,
                    routingService,
                    storageResolverPort,
                    registerMediaAssetVersionUseCase,
                    rasterValidator,
                    videoValidator,
                    allocationService,
                    cutoverBarrier
            );
        }

        @Test
        @DisplayName("Cutover active: on store failure tombstones allocation without speculative delete")
        void tombstonesOnStoreFailureWithoutSpeculativeDelete() {
            UUID assetId = UUID.randomUUID();
            MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
            when(mediaAssetRepositoryPort.findById(assetId)).thenReturn(Optional.of(asset));

            byte[] content = "fake-video-content".getBytes(StandardCharsets.UTF_8);
            when(binaryStoragePort.store(any(), any(), anyLong(), any()))
                    .thenThrow(new StorageException("B2 store failed"));

            UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                    assetId,
                    new ByteArrayInputStream(content),
                    content.length,
                    "video/mp4",
                    "test.mp4"
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(StorageException.class);

            verify(allocationService).reserveKey(any(), any(), any(), any(), any());
            verify(allocationService).tombstoneAllocation(ALLOCATION_ID);
            verify(binaryStoragePort, never()).delete(any());
            verify(registerMediaAssetVersionUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Cutover OFF: legacy store failure has ZERO speculative delete, zero allocation, zero tombstone")
        void legacyStoreFailureHasNoSideEffectsWhenCutoverInactive() {
            when(cutoverBarrier.isCutoverActive()).thenReturn(false);

            UUID assetId = UUID.randomUUID();
            MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
            when(mediaAssetRepositoryPort.findById(assetId)).thenReturn(Optional.of(asset));

            byte[] content = "fake-video-content".getBytes(StandardCharsets.UTF_8);
            when(binaryStoragePort.store(any(), any(), anyLong(), any()))
                    .thenThrow(new StorageException("B2 store failed"));

            UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                    assetId,
                    new ByteArrayInputStream(content),
                    content.length,
                    "video/mp4",
                    "test.mp4"
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(StorageException.class)
                    .hasMessage("B2 store failed");

            verify(binaryStoragePort, never()).delete(any());
            verify(allocationService, never()).reserveKey(any(), any(), any(), any(), any());
            verify(allocationService, never()).tombstoneAllocation(any());
        }
    }

    // =============================================================================================
    // 3. UploadMediaAssetVersionConditionalUseCase
    // =============================================================================================

    @Nested
    @DisplayName("UploadMediaAssetVersionConditionalUseCase Allocation Lifecycle")
    class ConditionalVersionAllocationTests {

        private UploadMediaAssetVersionConditionalUseCase conditionalUseCase;

        @BeforeEach
        void initUseCase() {
            conditionalUseCase = new UploadMediaAssetVersionConditionalUseCase(
                    mediaAssetRepositoryPort,
                    routingService,
                    storageResolverPort,
                    rasterValidator,
                    registerMediaAssetVersionUseCase,
                    videoValidator,
                    allocationService,
                    cutoverBarrier
            );
        }

        @Test
        @DisplayName("Zero allocation and zero store on authoritative duplicate match")
        void zeroAllocationOnDuplicate() {
            UUID assetId = UUID.randomUUID();
            MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
            when(mediaAssetRepositoryPort.findById(assetId)).thenReturn(Optional.of(asset));

            byte[] content = "unchanged-content".getBytes(StandardCharsets.UTF_8);
            AuthoritativeDuplicateProbeResult probeResult = AuthoritativeDuplicateProbeResult.duplicate(1);
            when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(eq(assetId), any()))
                    .thenReturn(probeResult);

            UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                    assetId,
                    new ByteArrayInputStream(content),
                    content.length,
                    "video/mp4",
                    "test.mp4"
            );

            UploadMediaAssetVersionConditionalResult outcome = conditionalUseCase.execute(command);

            assertThat(outcome.outcome()).isEqualTo(MediaVersionUploadOutcome.UNCHANGED);
            verify(allocationService, never()).reserveKey(any(), any(), any(), any(), any());
            verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("Cutover active: on store failure tombstones allocation without speculative delete")
        void tombstonesOnStoreFailureWithoutSpeculativeDelete() {
            UUID assetId = UUID.randomUUID();
            MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
            when(mediaAssetRepositoryPort.findById(assetId)).thenReturn(Optional.of(asset));

            byte[] content = "new-content".getBytes(StandardCharsets.UTF_8);
            AuthoritativeDuplicateProbeResult probeResult = AuthoritativeDuplicateProbeResult.different(1);
            when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(eq(assetId), any()))
                    .thenReturn(probeResult);

            when(binaryStoragePort.store(any(), any(), anyLong(), any()))
                    .thenThrow(new StorageException("B2 store error"));

            UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                    assetId,
                    new ByteArrayInputStream(content),
                    content.length,
                    "video/mp4",
                    "test.mp4"
            );

            assertThatThrownBy(() -> conditionalUseCase.execute(command))
                    .isInstanceOf(StorageException.class);

            verify(allocationService).reserveKey(any(), any(), any(), any(), any());
            verify(allocationService).tombstoneAllocation(ALLOCATION_ID);
            verify(binaryStoragePort, never()).delete(any());
        }

        @Test
        @DisplayName("Cutover OFF: legacy store failure has ZERO speculative delete, zero allocation, zero tombstone")
        void legacyStoreFailureHasNoSideEffectsWhenCutoverInactive() {
            when(cutoverBarrier.isCutoverActive()).thenReturn(false);

            UUID assetId = UUID.randomUUID();
            MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
            when(mediaAssetRepositoryPort.findById(assetId)).thenReturn(Optional.of(asset));

            byte[] content = "new-content".getBytes(StandardCharsets.UTF_8);
            AuthoritativeDuplicateProbeResult probeResult = AuthoritativeDuplicateProbeResult.different(1);
            when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(eq(assetId), any()))
                    .thenReturn(probeResult);

            when(binaryStoragePort.store(any(), any(), anyLong(), any()))
                    .thenThrow(new StorageException("B2 store error"));

            UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                    assetId,
                    new ByteArrayInputStream(content),
                    content.length,
                    "video/mp4",
                    "test.mp4"
            );

            assertThatThrownBy(() -> conditionalUseCase.execute(command))
                    .isInstanceOf(StorageException.class)
                    .hasMessage("B2 store error");

            verify(binaryStoragePort, never()).delete(any());
            verify(allocationService, never()).reserveKey(any(), any(), any(), any(), any());
            verify(allocationService, never()).tombstoneAllocation(any());
        }

        @Test
        @DisplayName("Race condition returning UNCHANGED compensates newly stored binary and tombstones new allocation")
        void raceConditionCompensatesNewAndTombstones() {
            UUID assetId = UUID.randomUUID();
            MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
            when(mediaAssetRepositoryPort.findById(assetId)).thenReturn(Optional.of(asset));

            byte[] content = "racing-content".getBytes(StandardCharsets.UTF_8);
            AuthoritativeDuplicateProbeResult probeResult = AuthoritativeDuplicateProbeResult.different(1);
            when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(eq(assetId), any()))
                    .thenReturn(probeResult);

            StoredBinaryObject stored = new StoredBinaryObject(
                    StorageLocation.of(B2_PROVIDER, STORAGE_KEY),
                    "https://cdn.example.com/video.mp4"
            );
            when(binaryStoragePort.store(any(), any(), anyLong(), any())).thenReturn(stored);

            RegisterMediaAssetVersionConditionalResult raceOutcome = RegisterMediaAssetVersionConditionalResult.unchanged(assetId, 1, NOW);
            when(registerMediaAssetVersionUseCase.registerConditionalVersion(any())).thenReturn(raceOutcome);

            UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                    assetId,
                    new ByteArrayInputStream(content),
                    content.length,
                    "video/mp4",
                    "test.mp4"
            );

            UploadMediaAssetVersionConditionalResult result = conditionalUseCase.execute(command);

            assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.UNCHANGED);
            // Confirmed newly stored candidate object is compensated, and allocation tombstoned
            verify(binaryStoragePort).delete(STORAGE_KEY);
            verify(allocationService).tombstoneAllocation(ALLOCATION_ID);
        }
    }
}
