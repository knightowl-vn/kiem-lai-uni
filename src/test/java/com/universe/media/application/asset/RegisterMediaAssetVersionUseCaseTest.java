package com.universe.media.application.asset;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegisterMediaAssetVersionUseCaseTest {

    private static final Instant T0 =
            Instant.parse("2026-09-01T10:00:00Z");

    private static final Instant FIXED_NOW =
            Instant.parse("2026-09-01T12:00:00Z");

    private static final ClockPort FIXED_CLOCK =
            () -> FIXED_NOW;

    private static final String VALID_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    @Mock
    private com.universe.media.application.storage.StorageKeyAllocationService storageKeyAllocationService;

    @Mock
    private com.universe.media.application.storage.MediaStorageCutoverBarrier cutoverBarrier;

    private RegisterMediaAssetVersionUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RegisterMediaAssetVersionUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                FIXED_CLOCK,
                storageKeyAllocationService,
                cutoverBarrier
        );
    }

    private RegisterMediaAssetVersionCommand createValidCommand(UUID assetId) {
        return new RegisterMediaAssetVersionCommand(
                assetId,
                "cloudinary",
                "covers/novel-cover-v2.webp",
                "https://cdn.universe.com/covers/novel-cover-v2.webp",
                VALID_HASH,
                "image/webp",
                4096L,
                "novel-cover-v2.webp"
        );
    }

    @Test
    @DisplayName("successful next-version registration creates and persists updated asset and new version")
    void shouldRegisterNextVersionSuccessfully() {
        MediaAsset asset = MediaAsset.registerInitial(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                T0
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class)))
                .thenReturn(false);

        RegisterMediaAssetVersionCommand command = createValidCommand(ASSET_ID);
        RegisterMediaAssetVersionResult result = useCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.assetId()).isEqualTo(ASSET_ID);
        assertThat(result.versionId()).isNotNull();
        assertThat(result.newVersionNumber()).isEqualTo(2);
        assertThat(result.updatedAt()).isEqualTo(FIXED_NOW);

        ArgumentCaptor<MediaAsset> assetCaptor = ArgumentCaptor.forClass(MediaAsset.class);
        ArgumentCaptor<MediaAssetVersion> versionCaptor = ArgumentCaptor.forClass(MediaAssetVersion.class);

        verify(mediaAssetRepositoryPort).save(assetCaptor.capture());
        verify(mediaAssetVersionRepositoryPort).save(versionCaptor.capture());

        MediaAsset savedAsset = assetCaptor.getValue();
        assertThat(savedAsset.getId()).isEqualTo(ASSET_ID);
        assertThat(savedAsset.getCurrentVersionNumber()).isEqualTo(2);
        assertThat(savedAsset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(savedAsset.getUpdatedAt()).isEqualTo(FIXED_NOW);

        MediaAssetVersion savedVersion = versionCaptor.getValue();
        assertThat(savedVersion.getId()).isEqualTo(result.versionId());
        assertThat(savedVersion.getAssetId()).isEqualTo(ASSET_ID);
        assertThat(savedVersion.getVersionNumber()).isEqualTo(2);
        assertThat(savedVersion.getStorageLocation().providerId().value()).isEqualTo("cloudinary");
        assertThat(savedVersion.getStorageLocation().key().value()).isEqualTo("covers/novel-cover-v2.webp");
        assertThat(savedVersion.getPublicUrl()).isEqualTo("https://cdn.universe.com/covers/novel-cover-v2.webp");
        assertThat(savedVersion.getContentHash().value()).isEqualTo(VALID_HASH);
        assertThat(savedVersion.getMimeType().value()).isEqualTo("image/webp");
        assertThat(savedVersion.getSizeBytes()).isEqualTo(4096L);
        assertThat(savedVersion.getOriginalFilename()).isEqualTo("novel-cover-v2.webp");
        assertThat(savedVersion.getCreatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("asset not found throws MediaAssetNotFoundException")
    void shouldThrowWhenAssetNotFound() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.empty());

        RegisterMediaAssetVersionCommand command = createValidCommand(ASSET_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(MediaAssetNotFoundException.class)
                .hasMessageContaining(ASSET_ID.toString());

        verifyNoInteractions(mediaAssetVersionRepositoryPort);
        verify(mediaAssetRepositoryPort, never()).save(any(MediaAsset.class));
    }

    @Test
    @DisplayName("duplicate StorageLocation rejects before aggregate mutation or saves")
    void shouldRejectDuplicateStorageLocation() {
        MediaAsset asset = MediaAsset.registerInitial(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                T0
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class)))
                .thenReturn(true);

        RegisterMediaAssetVersionCommand command = createValidCommand(ASSET_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(DuplicateStorageLocationException.class)
                .hasMessageContaining("cloudinary")
                .hasMessageContaining("covers/novel-cover-v2.webp");

        assertThat(asset.getCurrentVersionNumber()).isEqualTo(1);
        verify(mediaAssetRepositoryPort, never()).save(any(MediaAsset.class));
        verify(mediaAssetVersionRepositoryPort, never()).save(any(MediaAssetVersion.class));
    }

    @Test
    @DisplayName("non-ACTIVE asset cannot register a new version and performs no saves")
    void shouldRejectVersionRegistrationOnNonActiveAsset() {
        MediaAsset asset = MediaAsset.registerInitial(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                T0
        );
        asset.archive(T0);

        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class)))
                .thenReturn(false);

        RegisterMediaAssetVersionCommand command = createValidCommand(ASSET_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCHIVED");

        verify(mediaAssetRepositoryPort, never()).save(any(MediaAsset.class));
        verify(mediaAssetVersionRepositoryPort, never()).save(any(MediaAssetVersion.class));
    }

    @Test
    @DisplayName("probeAuthoritativeDuplicate returns duplicate when hash matches current version under row lock")
    void probeAuthoritativeDuplicate_shouldReturnDuplicateWhenHashMatches() {
        MediaAsset asset = MediaAsset.registerInitial(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                T0
        );
        MediaAssetVersion version = MediaAssetVersion.create(
                UUID.randomUUID(),
                ASSET_ID,
                1,
                StorageLocation.of(StorageProviderId.of("cloudinary"), StorageKey.of("covers/c.webp")),
                null,
                com.universe.media.domain.ContentHash.of(VALID_HASH),
                com.universe.media.domain.MimeType.of("image/webp"),
                1024L,
                "c.webp",
                T0
        );

        when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));

        AuthoritativeDuplicateProbeResult result = useCase.probeAuthoritativeDuplicate(ASSET_ID, VALID_HASH);

        assertThat(result.isDuplicate()).isTrue();
        assertThat(result.currentVersionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("probeAuthoritativeDuplicate returns different when hash does not match current version")
    void probeAuthoritativeDuplicate_shouldReturnDifferentWhenHashDiffers() {
        MediaAsset asset = MediaAsset.registerInitial(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                T0
        );
        MediaAssetVersion version = MediaAssetVersion.create(
                UUID.randomUUID(),
                ASSET_ID,
                1,
                StorageLocation.of(StorageProviderId.of("cloudinary"), StorageKey.of("covers/c.webp")),
                null,
                com.universe.media.domain.ContentHash.of(VALID_HASH),
                com.universe.media.domain.MimeType.of("image/webp"),
                1024L,
                "c.webp",
                T0
        );

        when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));

        AuthoritativeDuplicateProbeResult result = useCase.probeAuthoritativeDuplicate(ASSET_ID, "differenthash1234567890abcdef");

        assertThat(result.isDuplicate()).isFalse();
        assertThat(result.currentVersionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("probeAuthoritativeDuplicate throws MediaAssetNotFoundException when asset missing")
    void probeAuthoritativeDuplicate_shouldThrowWhenAssetNotFound() {
        when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.probeAuthoritativeDuplicate(ASSET_ID, VALID_HASH))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    @DisplayName("probeAuthoritativeDuplicate throws IllegalStateException when asset is not ACTIVE")
    void probeAuthoritativeDuplicate_shouldThrowWhenAssetNotActive() {
        MediaAsset asset = MediaAsset.registerInitial(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                T0
        );
        asset.archive(T0);

        when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.probeAuthoritativeDuplicate(ASSET_ID, VALID_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCHIVED");
    }

    @Test
    @DisplayName("registerConditionalVersion returns UNCHANGED without writes when current version has same hash")
    void registerConditionalVersion_shouldReturnUnchangedWhenHashMatches() {
        MediaAsset asset = MediaAsset.registerInitial(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                T0
        );
        MediaAssetVersion version = MediaAssetVersion.create(
                UUID.randomUUID(),
                ASSET_ID,
                1,
                StorageLocation.of(StorageProviderId.of("cloudinary"), StorageKey.of("covers/c.webp")),
                null,
                com.universe.media.domain.ContentHash.of(VALID_HASH),
                com.universe.media.domain.MimeType.of("image/webp"),
                1024L,
                "c.webp",
                T0
        );

        when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));

        RegisterMediaAssetVersionCommand command = createValidCommand(ASSET_ID);
        RegisterMediaAssetVersionConditionalResult result = useCase.registerConditionalVersion(command);

        assertThat(result.outcome()).isEqualTo(com.universe.media.contracts.dto.MediaVersionUploadOutcome.UNCHANGED);
        assertThat(result.assetId()).isEqualTo(ASSET_ID);
        assertThat(result.versionNumber()).isEqualTo(1);
        verify(mediaAssetRepositoryPort, never()).save(any(MediaAsset.class));
        verify(mediaAssetVersionRepositoryPort, never()).save(any(MediaAssetVersion.class));
    }

    @Test
    @DisplayName("cutover active: throws IllegalStateException when storage key allocation is missing")
    void shouldRejectVersionRegistrationWhenAllocationMissingUnderCutover() {
        when(cutoverBarrier.isCutoverActive()).thenReturn(true);
        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, T0);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);
        when(storageKeyAllocationService.findByStorageLocation(any(), any())).thenReturn(Optional.empty());

        RegisterMediaAssetVersionCommand command = createValidCommand(ASSET_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active cutover barrier requires a valid storage key allocation");
    }

    @Test
    @DisplayName("cutover active: throws IllegalStateException when storage key allocation is tombstoned")
    void shouldRejectVersionRegistrationWhenAllocationIsTombstonedUnderCutover() {
        when(cutoverBarrier.isCutoverActive()).thenReturn(true);
        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, T0);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);

        UUID allocationId = UUID.randomUUID();
        com.universe.media.domain.StorageKeyAllocation allocation = com.universe.media.domain.StorageKeyAllocation.reserve(
                allocationId,
                StorageProviderId.of("cloudinary"),
                "NONE",
                StorageKey.of("covers/novel-cover-v2.webp"),
                com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION,
                UUID.randomUUID(),
                FIXED_NOW
        );
        allocation.tombstone(FIXED_NOW);
        when(storageKeyAllocationService.findByStorageLocation(any(), any())).thenReturn(Optional.of(allocation));

        RegisterMediaAssetVersionCommand command = createValidCommand(ASSET_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tombstoned storage key allocation");
    }

    @Test
    @DisplayName("cutover active: throws IllegalStateException when storage key allocation is already COMMITTED")
    void shouldRejectVersionRegistrationWhenAllocationAlreadyCommittedUnderCutover() {
        when(cutoverBarrier.isCutoverActive()).thenReturn(true);
        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, T0);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);

        UUID allocationId = UUID.randomUUID();
        com.universe.media.domain.StorageKeyAllocation allocation = com.universe.media.domain.StorageKeyAllocation.reserve(
                allocationId,
                StorageProviderId.of("cloudinary"),
                "NONE",
                StorageKey.of("covers/novel-cover-v2.webp"),
                com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION,
                UUID.randomUUID(),
                FIXED_NOW
        );
        allocation.commit(com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION, UUID.randomUUID(), FIXED_NOW);
        when(storageKeyAllocationService.findByStorageLocation(any(), any())).thenReturn(Optional.of(allocation));

        RegisterMediaAssetVersionCommand command = createValidCommand(ASSET_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already committed storage key allocation");
    }

    @Test
    @DisplayName("cutover active: throws IllegalStateException when expectedAllocationId does not match allocation id")
    void shouldRejectVersionRegistrationWhenExpectedAllocationIdMismatchesUnderCutover() {
        when(cutoverBarrier.isCutoverActive()).thenReturn(true);
        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, T0);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);

        UUID actualAllocationId = UUID.randomUUID();
        UUID expectedAllocationId = UUID.randomUUID();

        com.universe.media.domain.StorageKeyAllocation allocation = com.universe.media.domain.StorageKeyAllocation.reserve(
                actualAllocationId,
                StorageProviderId.of("cloudinary"),
                "NONE",
                StorageKey.of("covers/novel-cover-v2.webp"),
                com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION,
                UUID.randomUUID(),
                FIXED_NOW
        );
        when(storageKeyAllocationService.findByStorageLocation(any(), any())).thenReturn(Optional.of(allocation));

        RegisterMediaAssetVersionCommand command = new RegisterMediaAssetVersionCommand(
                ASSET_ID,
                "cloudinary",
                "covers/novel-cover-v2.webp",
                "https://cdn.universe.com/covers/novel-cover-v2.webp",
                VALID_HASH,
                "image/webp",
                4096L,
                "novel-cover-v2.webp",
                expectedAllocationId
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reservation ownership mismatch");
    }

    @Test
    @DisplayName("cutover active: throws IllegalStateException when reservationCorrelationId does not match allocatedById (ownership token mismatch)")
    void shouldRejectVersionRegistrationWhenReservationCorrelationIdMismatchesUnderCutover() {
        when(cutoverBarrier.isCutoverActive()).thenReturn(true);
        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, T0);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);

        UUID allocationId = UUID.randomUUID();
        UUID actualOwnerId = UUID.randomUUID();
        UUID rogueOwnerId = UUID.randomUUID();

        com.universe.media.domain.StorageKeyAllocation allocation = com.universe.media.domain.StorageKeyAllocation.reserve(
                allocationId,
                StorageProviderId.of("cloudinary"),
                "NONE",
                StorageKey.of("covers/novel-cover-v2.webp"),
                com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION,
                actualOwnerId,
                FIXED_NOW
        );
        when(storageKeyAllocationService.findByStorageLocation(any(), any())).thenReturn(Optional.of(allocation));

        RegisterMediaAssetVersionCommand command = new RegisterMediaAssetVersionCommand(
                ASSET_ID,
                "cloudinary",
                "covers/novel-cover-v2.webp",
                "https://cdn.universe.com/covers/novel-cover-v2.webp",
                VALID_HASH,
                "image/webp",
                4096L,
                "novel-cover-v2.webp",
                allocationId,
                rogueOwnerId
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caller does not hold the correlation token");
    }

    @Test
    @DisplayName("cutover active: commits allocation atomically upon successful version registration")
    void shouldCommitAllocationUnderCutover() {
        when(cutoverBarrier.isCutoverActive()).thenReturn(true);
        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, T0);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);

        UUID allocationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        com.universe.media.domain.StorageKeyAllocation allocation = com.universe.media.domain.StorageKeyAllocation.reserve(
                allocationId,
                StorageProviderId.of("cloudinary"),
                "NONE",
                StorageKey.of("covers/novel-cover-v2.webp"),
                com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION,
                ownerId,
                FIXED_NOW
        );
        when(storageKeyAllocationService.findByStorageLocation(any(), any())).thenReturn(Optional.of(allocation));

        RegisterMediaAssetVersionCommand command = new RegisterMediaAssetVersionCommand(
                ASSET_ID,
                "cloudinary",
                "covers/novel-cover-v2.webp",
                "https://cdn.universe.com/covers/novel-cover-v2.webp",
                VALID_HASH,
                "image/webp",
                4096L,
                "novel-cover-v2.webp",
                allocationId,
                ownerId
        );

        RegisterMediaAssetVersionResult result = useCase.execute(command);

        assertThat(result).isNotNull();
        verify(storageKeyAllocationService).commitAllocation(
                org.mockito.ArgumentMatchers.eq(allocationId),
                org.mockito.ArgumentMatchers.eq(com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION),
                org.mockito.ArgumentMatchers.eq(result.versionId())
        );
    }
}
