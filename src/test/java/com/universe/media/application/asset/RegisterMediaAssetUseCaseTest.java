package com.universe.media.application.asset;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageLocation;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegisterMediaAssetUseCaseTest {

    private static final Instant FIXED_NOW =
            Instant.parse("2026-09-01T12:00:00Z");

    private static final ClockPort FIXED_CLOCK =
            () -> FIXED_NOW;

    private static final String VALID_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    @Mock
    private com.universe.media.application.storage.StorageKeyAllocationService storageKeyAllocationService;

    @Mock
    private com.universe.media.application.storage.MediaStorageCutoverBarrier cutoverBarrier;

    private RegisterMediaAssetUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RegisterMediaAssetUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                FIXED_CLOCK,
                storageKeyAllocationService,
                cutoverBarrier
        );
    }

    private RegisterMediaAssetCommand createValidCommand() {
        return new RegisterMediaAssetCommand(
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                "cloudinary",
                "covers/novel-cover.webp",
                "https://cdn.universe.com/covers/novel-cover.webp",
                VALID_HASH,
                "image/webp",
                2048L,
                "novel-cover.webp"
        );
    }

    @Test
    @DisplayName("successful registration creates and persists MediaAsset and MediaAssetVersion v1")
    void shouldRegisterInitialMediaAssetSuccessfully() {
        RegisterMediaAssetCommand command = createValidCommand();
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class)))
                .thenReturn(false);

        RegisterMediaAssetResult result = useCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.assetId()).isNotNull();
        assertThat(result.versionId()).isNotNull();
        assertThat(result.assetId()).isNotEqualTo(result.versionId());
        assertThat(result.versionNumber()).isEqualTo(1);
        assertThat(result.mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(result.visibility()).isEqualTo(MediaVisibility.PUBLIC);
        assertThat(result.status()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(result.createdAt()).isEqualTo(FIXED_NOW);

        ArgumentCaptor<MediaAsset> assetCaptor = ArgumentCaptor.forClass(MediaAsset.class);
        ArgumentCaptor<MediaAssetVersion> versionCaptor = ArgumentCaptor.forClass(MediaAssetVersion.class);

        verify(mediaAssetRepositoryPort).save(assetCaptor.capture());
        verify(mediaAssetVersionRepositoryPort).save(versionCaptor.capture());

        MediaAsset savedAsset = assetCaptor.getValue();
        assertThat(savedAsset.getId()).isEqualTo(result.assetId());
        assertThat(savedAsset.getMediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(savedAsset.getVisibility()).isEqualTo(MediaVisibility.PUBLIC);
        assertThat(savedAsset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(savedAsset.getCurrentVersionNumber()).isEqualTo(1);
        assertThat(savedAsset.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(savedAsset.getUpdatedAt()).isEqualTo(FIXED_NOW);

        MediaAssetVersion savedVersion = versionCaptor.getValue();
        assertThat(savedVersion.getId()).isEqualTo(result.versionId());
        assertThat(savedVersion.getAssetId()).isEqualTo(result.assetId());
        assertThat(savedVersion.getVersionNumber()).isEqualTo(1);
        assertThat(savedVersion.getStorageLocation().providerId().value()).isEqualTo("cloudinary");
        assertThat(savedVersion.getStorageLocation().key().value()).isEqualTo("covers/novel-cover.webp");
        assertThat(savedVersion.getPublicUrl()).isEqualTo("https://cdn.universe.com/covers/novel-cover.webp");
        assertThat(savedVersion.getContentHash().value()).isEqualTo(VALID_HASH);
        assertThat(savedVersion.getMimeType().value()).isEqualTo("image/webp");
        assertThat(savedVersion.getSizeBytes()).isEqualTo(2048L);
        assertThat(savedVersion.getOriginalFilename()).isEqualTo("novel-cover.webp");
        assertThat(savedVersion.getCreatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("duplicate StorageLocation throws DuplicateStorageLocationException and performs no saves")
    void shouldRejectDuplicateStorageLocation() {
        RegisterMediaAssetCommand command = createValidCommand();
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class)))
                .thenReturn(true);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(DuplicateStorageLocationException.class)
                .hasMessageContaining("cloudinary")
                .hasMessageContaining("covers/novel-cover.webp");

        verifyNoInteractions(mediaAssetRepositoryPort);
        verify(mediaAssetVersionRepositoryPort, never()).save(any(MediaAssetVersion.class));
    }

    @Test
    @DisplayName("cutover active: throws IllegalStateException when storage key allocation is missing")
    void shouldRejectRegistrationWhenAllocationMissingUnderCutover() {
        when(cutoverBarrier.isCutoverActive()).thenReturn(true);
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);
        when(storageKeyAllocationService.findByStorageLocation(any(), any())).thenReturn(java.util.Optional.empty());

        RegisterMediaAssetCommand command = createValidCommand();

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active cutover barrier requires a valid storage key allocation");
    }

    @Test
    @DisplayName("cutover active: throws IllegalStateException when storage key allocation is tombstoned")
    void shouldRejectRegistrationWhenAllocationIsTombstonedUnderCutover() {
        when(cutoverBarrier.isCutoverActive()).thenReturn(true);
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);

        java.util.UUID allocationId = java.util.UUID.randomUUID();
        com.universe.media.domain.StorageKeyAllocation allocation = com.universe.media.domain.StorageKeyAllocation.reserve(
                allocationId,
                com.universe.media.domain.StorageProviderId.of("cloudinary"),
                "NONE",
                com.universe.media.domain.StorageKey.of("covers/novel-cover.webp"),
                com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION,
                java.util.UUID.randomUUID(),
                FIXED_NOW
        );
        allocation.tombstone(FIXED_NOW);
        when(storageKeyAllocationService.findByStorageLocation(any(), any())).thenReturn(java.util.Optional.of(allocation));

        RegisterMediaAssetCommand command = createValidCommand();

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tombstoned storage key allocation");
    }

    @Test
    @DisplayName("cutover active: throws IllegalStateException when storage key allocation is already COMMITTED (reuse rejection)")
    void shouldRejectRegistrationWhenAllocationAlreadyCommittedUnderCutover() {
        when(cutoverBarrier.isCutoverActive()).thenReturn(true);
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);

        java.util.UUID allocationId = java.util.UUID.randomUUID();
        com.universe.media.domain.StorageKeyAllocation allocation = com.universe.media.domain.StorageKeyAllocation.reserve(
                allocationId,
                com.universe.media.domain.StorageProviderId.of("cloudinary"),
                "NONE",
                com.universe.media.domain.StorageKey.of("covers/novel-cover.webp"),
                com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION,
                java.util.UUID.randomUUID(),
                FIXED_NOW
        );
        allocation.commit(com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION, java.util.UUID.randomUUID(), FIXED_NOW);
        when(storageKeyAllocationService.findByStorageLocation(any(), any())).thenReturn(java.util.Optional.of(allocation));

        RegisterMediaAssetCommand command = createValidCommand();

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already committed storage key allocation");
    }

    @Test
    @DisplayName("cutover active: throws IllegalStateException when expectedAllocationId does not match allocation id (reservation hijacking prevented)")
    void shouldRejectRegistrationWhenExpectedAllocationIdMismatchesUnderCutover() {
        when(cutoverBarrier.isCutoverActive()).thenReturn(true);
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);

        java.util.UUID actualAllocationId = java.util.UUID.randomUUID();
        java.util.UUID expectedAllocationId = java.util.UUID.randomUUID();

        com.universe.media.domain.StorageKeyAllocation allocation = com.universe.media.domain.StorageKeyAllocation.reserve(
                actualAllocationId,
                com.universe.media.domain.StorageProviderId.of("cloudinary"),
                "NONE",
                com.universe.media.domain.StorageKey.of("covers/novel-cover.webp"),
                com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION,
                java.util.UUID.randomUUID(),
                FIXED_NOW
        );
        when(storageKeyAllocationService.findByStorageLocation(any(), any())).thenReturn(java.util.Optional.of(allocation));

        RegisterMediaAssetCommand command = new RegisterMediaAssetCommand(
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                "cloudinary",
                "covers/novel-cover.webp",
                "https://cdn.universe.com/covers/novel-cover.webp",
                VALID_HASH,
                "image/webp",
                2048L,
                "novel-cover.webp",
                expectedAllocationId
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reservation ownership mismatch");
    }

    @Test
    @DisplayName("cutover active: throws IllegalStateException when reservationCorrelationId does not match allocatedById (ownership token mismatch)")
    void shouldRejectRegistrationWhenReservationCorrelationIdMismatchesUnderCutover() {
        when(cutoverBarrier.isCutoverActive()).thenReturn(true);
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);

        java.util.UUID allocationId = java.util.UUID.randomUUID();
        java.util.UUID actualOwnerId = java.util.UUID.randomUUID();
        java.util.UUID rogueOwnerId = java.util.UUID.randomUUID();

        com.universe.media.domain.StorageKeyAllocation allocation = com.universe.media.domain.StorageKeyAllocation.reserve(
                allocationId,
                com.universe.media.domain.StorageProviderId.of("cloudinary"),
                "NONE",
                com.universe.media.domain.StorageKey.of("covers/novel-cover.webp"),
                com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION,
                actualOwnerId,
                FIXED_NOW
        );
        when(storageKeyAllocationService.findByStorageLocation(any(), any())).thenReturn(java.util.Optional.of(allocation));

        RegisterMediaAssetCommand command = new RegisterMediaAssetCommand(
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                "cloudinary",
                "covers/novel-cover.webp",
                "https://cdn.universe.com/covers/novel-cover.webp",
                VALID_HASH,
                "image/webp",
                2048L,
                "novel-cover.webp",
                null,
                allocationId,
                rogueOwnerId
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caller does not hold the correlation token");
    }

    @Test
    @DisplayName("cutover active: commits allocation atomically upon successful registration")
    void shouldCommitAllocationUnderCutover() {
        when(cutoverBarrier.isCutoverActive()).thenReturn(true);
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class))).thenReturn(false);

        java.util.UUID allocationId = java.util.UUID.randomUUID();
        java.util.UUID ownerId = java.util.UUID.randomUUID();
        com.universe.media.domain.StorageKeyAllocation allocation = com.universe.media.domain.StorageKeyAllocation.reserve(
                allocationId,
                com.universe.media.domain.StorageProviderId.of("cloudinary"),
                "NONE",
                com.universe.media.domain.StorageKey.of("covers/novel-cover.webp"),
                com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION,
                ownerId,
                FIXED_NOW
        );
        when(storageKeyAllocationService.findByStorageLocation(any(), any())).thenReturn(java.util.Optional.of(allocation));

        RegisterMediaAssetCommand command = new RegisterMediaAssetCommand(
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                "cloudinary",
                "covers/novel-cover.webp",
                "https://cdn.universe.com/covers/novel-cover.webp",
                VALID_HASH,
                "image/webp",
                2048L,
                "novel-cover.webp",
                null,
                allocationId,
                ownerId
        );

        RegisterMediaAssetResult result = useCase.execute(command);

        assertThat(result).isNotNull();
        verify(storageKeyAllocationService).commitAllocation(
                org.mockito.ArgumentMatchers.eq(allocationId),
                org.mockito.ArgumentMatchers.eq(com.universe.media.domain.StorageKeyAllocationType.ASSET_VERSION),
                org.mockito.ArgumentMatchers.eq(result.versionId())
        );
    }
}
