package com.universe.media.application.asset;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaImageVariant;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurgeDeletedMediaAssetUseCaseTest {

    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID VERSION_1_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID VERSION_2_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID VARIANT_1_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final UUID VARIANT_2_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static final Instant DELETED_AT =
            Instant.parse("2026-09-01T00:00:00Z");

    private static final Instant FIXED_NOW =
            Instant.parse("2026-09-09T00:00:00Z"); // 8 days after DELETED_AT (> 7 days grace)

    private static final ClockPort FIXED_CLOCK =
            () -> FIXED_NOW;

    private static final StorageProviderId LOCAL_PROVIDER =
            StorageProviderId.of("local");

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    @Mock
    private MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort;

    @Mock
    private BinaryStoragePort binaryStoragePort;

    @Mock
    private PurgeMediaAssetMetadataService purgeMediaAssetMetadataService;

    private PurgeDeletedMediaAssetUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new PurgeDeletedMediaAssetUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                mediaImageVariantRepositoryPort,
                binaryStoragePort,
                purgeMediaAssetMetadataService,
                FIXED_CLOCK
        );
    }

    private MediaAsset createDeletedAsset(Instant deletedAt) {
        return MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                MediaAssetStatus.DELETED,
                2,
                deletedAt.minus(Duration.ofDays(10)),
                deletedAt
        );
    }

    private MediaAssetVersion createVersion(UUID versionId, int versionNumber, String keyPath) {
        return MediaAssetVersion.rehydrate(
                versionId,
                ASSET_ID,
                versionNumber,
                StorageLocation.of(LOCAL_PROVIDER, StorageKey.of(keyPath)),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/png"),
                1024L,
                "image.png",
                DELETED_AT.minus(Duration.ofDays(5))
        );
    }

    private MediaImageVariant createVariant(UUID variantId, UUID versionId, String variantKey, int targetWidth, String keyPath) {
        return MediaImageVariant.rehydrate(
                variantId,
                versionId,
                variantKey,
                targetWidth,
                StorageLocation.of(LOCAL_PROVIDER, StorageKey.of(keyPath)),
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/webp"),
                512L,
                targetWidth,
                targetWidth,
                DELETED_AT.minus(Duration.ofDays(4))
        );
    }

    @Nested
    @DisplayName("Eligibility & Preconditions")
    class EligibilityTests {

        @Test
        @DisplayName("returns zero-count result idempotently when asset is already missing (already purged)")
        void shouldReturnZeroCountResultWhenAssetAlreadyMissing() {
            when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.empty());

            PurgeDeletedMediaAssetResult result = useCase.execute(new PurgeDeletedMediaAssetCommand(ASSET_ID));

            assertThat(result.assetId()).isEqualTo(ASSET_ID);
            assertThat(result.purgedVersionsCount()).isEqualTo(0);
            assertThat(result.purgedVariantsCount()).isEqualTo(0);
            assertThat(result.purgedAt()).isEqualTo(FIXED_NOW);

            verify(binaryStoragePort, never()).delete(any());
            verify(purgeMediaAssetMetadataService, never()).execute(any());
        }

        @Test
        @DisplayName("throws IllegalStateException when asset is ACTIVE")
        void shouldRejectPurgeWhenAssetIsActive() {
            MediaAsset activeAsset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    DELETED_AT
            );
            when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(activeAsset));

            assertThatThrownBy(() -> useCase.execute(new PurgeDeletedMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ACTIVE")
                    .hasMessageContaining("not eligible for purge");

            verify(binaryStoragePort, never()).delete(any());
            verify(purgeMediaAssetMetadataService, never()).execute(any());
        }

        @Test
        @DisplayName("throws IllegalStateException when asset is ARCHIVED")
        void shouldRejectPurgeWhenAssetIsArchived() {
            MediaAsset archivedAsset = MediaAsset.rehydrate(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    MediaAssetStatus.ARCHIVED,
                    1,
                    DELETED_AT.minus(Duration.ofDays(10)),
                    DELETED_AT
            );
            when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(archivedAsset));

            assertThatThrownBy(() -> useCase.execute(new PurgeDeletedMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ARCHIVED")
                    .hasMessageContaining("not eligible for purge");

            verify(binaryStoragePort, never()).delete(any());
            verify(purgeMediaAssetMetadataService, never()).execute(any());
        }

        @Test
        @DisplayName("throws IllegalStateException when asset was deleted within 7-day retention grace period")
        void shouldRejectPurgeWhenWithinGracePeriod() {
            // Deleted 3 days ago (retention is 7 days)
            Instant recentDeletedAt = FIXED_NOW.minus(Duration.ofDays(3));
            MediaAsset recentDeletedAsset = createDeletedAsset(recentDeletedAt);
            when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(recentDeletedAsset));

            assertThatThrownBy(() -> useCase.execute(new PurgeDeletedMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not eligible for purge");

            verify(binaryStoragePort, never()).delete(any());
            verify(purgeMediaAssetMetadataService, never()).execute(any());
        }
    }

    @Nested
    @DisplayName("Purge Execution & Ordering")
    class PurgeExecutionTests {

        @Test
        @DisplayName("successful purge deletes variants first, then versions from storage, then deletes metadata bottom-up")
        void shouldPurgeExpiredDeletedAssetInStrictOrder() {
            MediaAsset asset = createDeletedAsset(DELETED_AT);
            MediaAssetVersion v1 = createVersion(VERSION_1_ID, 1, "objects/v1.png");
            MediaAssetVersion v2 = createVersion(VERSION_2_ID, 2, "objects/v2.png");
            MediaImageVariant var1 = createVariant(VARIANT_1_ID, VERSION_1_ID, "w300", 300, "variants/v1_w300.webp");
            MediaImageVariant var2 = createVariant(VARIANT_2_ID, VERSION_2_ID, "w800", 800, "variants/v2_w800.webp");

            when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
            when(mediaAssetVersionRepositoryPort.findAllByAssetId(ASSET_ID)).thenReturn(List.of(v1, v2));
            when(mediaImageVariantRepositoryPort.findAllByVersionIds(List.of(VERSION_1_ID, VERSION_2_ID)))
                    .thenReturn(List.of(var1, var2));

            PurgeDeletedMediaAssetResult result = useCase.execute(new PurgeDeletedMediaAssetCommand(ASSET_ID));

            assertThat(result.assetId()).isEqualTo(ASSET_ID);
            assertThat(result.purgedVersionsCount()).isEqualTo(2);
            assertThat(result.purgedVariantsCount()).isEqualTo(2);
            assertThat(result.purgedAt()).isEqualTo(FIXED_NOW);

            // Verify strict ordering: variants deleted from storage first, then versions, then metadata service
            InOrder inOrder = inOrder(binaryStoragePort, purgeMediaAssetMetadataService);
            inOrder.verify(binaryStoragePort).delete(StorageKey.of("variants/v1_w300.webp"));
            inOrder.verify(binaryStoragePort).delete(StorageKey.of("variants/v2_w800.webp"));
            inOrder.verify(binaryStoragePort).delete(StorageKey.of("objects/v1.png"));
            inOrder.verify(binaryStoragePort).delete(StorageKey.of("objects/v2.png"));
            inOrder.verify(purgeMediaAssetMetadataService).execute(ASSET_ID);
        }

        @Test
        @DisplayName("purges asset deleted exactly 7 days ago (boundary check)")
        void shouldPurgeAssetDeletedExactlySevenDaysAgo() {
            Instant exactSevenDaysAgo = FIXED_NOW.minus(Duration.ofDays(7));
            MediaAsset asset = createDeletedAsset(exactSevenDaysAgo);
            MediaAssetVersion v1 = createVersion(VERSION_1_ID, 1, "objects/v1.png");

            when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
            when(mediaAssetVersionRepositoryPort.findAllByAssetId(ASSET_ID)).thenReturn(List.of(v1));
            when(mediaImageVariantRepositoryPort.findAllByVersionIds(List.of(VERSION_1_ID))).thenReturn(List.of());

            PurgeDeletedMediaAssetResult result = useCase.execute(new PurgeDeletedMediaAssetCommand(ASSET_ID));

            assertThat(result.assetId()).isEqualTo(ASSET_ID);
            assertThat(result.purgedVersionsCount()).isEqualTo(1);
            assertThat(result.purgedVariantsCount()).isEqualTo(0);

            verify(binaryStoragePort).delete(StorageKey.of("objects/v1.png"));
            verify(purgeMediaAssetMetadataService).execute(ASSET_ID);
        }

        @Test
        @DisplayName("handles asset with zero versions gracefully")
        void shouldHandleAssetWithZeroVersions() {
            MediaAsset asset = createDeletedAsset(DELETED_AT);

            when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
            when(mediaAssetVersionRepositoryPort.findAllByAssetId(ASSET_ID)).thenReturn(List.of());

            PurgeDeletedMediaAssetResult result = useCase.execute(new PurgeDeletedMediaAssetCommand(ASSET_ID));

            assertThat(result.assetId()).isEqualTo(ASSET_ID);
            assertThat(result.purgedVersionsCount()).isEqualTo(0);
            assertThat(result.purgedVariantsCount()).isEqualTo(0);

            verify(binaryStoragePort, never()).delete(any());
            verify(purgeMediaAssetMetadataService).execute(ASSET_ID);
        }
    }

    @Nested
    @DisplayName("Failure Preservation & Idempotency")
    class FailureAndIdempotencyTests {

        @Test
        @DisplayName("when storage delete fails on variant, aborts immediately and preserves all metadata for retry")
        void shouldPreserveMetadataWhenVariantStorageDeleteFails() {
            MediaAsset asset = createDeletedAsset(DELETED_AT);
            MediaAssetVersion v1 = createVersion(VERSION_1_ID, 1, "objects/v1.png");
            MediaImageVariant var1 = createVariant(VARIANT_1_ID, VERSION_1_ID, "w300", 300, "variants/v1_w300.webp");

            when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
            when(mediaAssetVersionRepositoryPort.findAllByAssetId(ASSET_ID)).thenReturn(List.of(v1));
            when(mediaImageVariantRepositoryPort.findAllByVersionIds(List.of(VERSION_1_ID))).thenReturn(List.of(var1));

            doThrow(new StorageException("Disk I/O error"))
                    .when(binaryStoragePort).delete(StorageKey.of("variants/v1_w300.webp"));

            assertThatThrownBy(() -> useCase.execute(new PurgeDeletedMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Disk I/O error");

            // Version storage delete and metadata purge must NEVER be called
            verify(binaryStoragePort, never()).delete(StorageKey.of("objects/v1.png"));
            verify(purgeMediaAssetMetadataService, never()).execute(any());
        }

        @Test
        @DisplayName("when storage delete fails on version, aborts immediately and preserves metadata for retry")
        void shouldPreserveMetadataWhenVersionStorageDeleteFails() {
            MediaAsset asset = createDeletedAsset(DELETED_AT);
            MediaAssetVersion v1 = createVersion(VERSION_1_ID, 1, "objects/v1.png");

            when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
            when(mediaAssetVersionRepositoryPort.findAllByAssetId(ASSET_ID)).thenReturn(List.of(v1));
            when(mediaImageVariantRepositoryPort.findAllByVersionIds(List.of(VERSION_1_ID))).thenReturn(List.of());

            doThrow(new StorageException("Storage unreachable"))
                    .when(binaryStoragePort).delete(StorageKey.of("objects/v1.png"));

            assertThatThrownBy(() -> useCase.execute(new PurgeDeletedMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Storage unreachable");

            // Metadata purge must NEVER be called
            verify(purgeMediaAssetMetadataService, never()).execute(any());
        }
    }
}
