package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.ports.MediaAssetContentDeliveryQueryPort;
import com.universe.media.application.ports.MediaAssetContentDeliveryQueryPort.MediaAssetContentDeliverySnapshot;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetMediaAssetContentUseCaseTest {

    private static final UUID ASSET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID VERSION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String HASH =
            "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e";
    private static final String OTHER_HASH =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
    private static final StorageKey STORAGE_KEY = StorageKey.of("objects/chapter.mp3");

    @Mock
    private MediaAssetContentDeliveryQueryPort contentDeliveryQueryPort;

    @Mock
    private BinaryStoragePort binaryStoragePort;

    private GetMediaAssetContentUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetMediaAssetContentUseCase(contentDeliveryQueryPort, binaryStoragePort);
    }

    @Test
    void executeUsesOneResolvedSnapshotAndOpensTheFullBinary() {
        InputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        stubSnapshot(activePublicSnapshot());
        when(binaryStoragePort.open(STORAGE_KEY)).thenReturn(stream);

        GetMediaAssetContentResult result = useCase.execute(new GetMediaAssetContentQuery(ASSET_ID));

        assertThat(result.content()).isSameAs(stream);
        assertThat(result.sizeBytes()).isEqualTo(1024L);
        assertThat(result.mimeType()).isEqualTo("audio/mpeg");
        assertThat(result.contentHash()).isEqualTo(HASH);
        verify(contentDeliveryQueryPort).findByAssetId(ASSET_ID);
        verify(binaryStoragePort).open(STORAGE_KEY);
        verify(binaryStoragePort, never()).openRange(any(), anyLong(), anyLong());
    }

    @Test
    void resolveMetadataUsesOneSnapshotAndDoesNotOpenBinaryStorage() {
        stubSnapshot(activePublicSnapshot());

        GetMediaAssetContentMetadataResult result = useCase.resolveMetadata(
                new GetMediaAssetContentQuery(ASSET_ID)
        );

        assertThat(result).isEqualTo(metadata());
        verify(contentDeliveryQueryPort).findByAssetId(ASSET_ID);
        verifyNoStorageOpen();
    }

    @Test
    void rangeOpenRevalidatesFreshSnapshotAndUsesOpenRangeOnly() {
        InputStream stream = new ByteArrayInputStream(new byte[]{4, 5, 6});
        stubSnapshot(activePublicSnapshot());
        when(binaryStoragePort.openRange(STORAGE_KEY, 100, 3)).thenReturn(stream);

        InputStream result = useCase.openRange(metadata(), 100, 3);

        assertThat(result).isSameAs(stream);
        verify(contentDeliveryQueryPort).findByAssetId(ASSET_ID);
        verify(binaryStoragePort).openRange(STORAGE_KEY, 100, 3);
        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    void separateMetadataAndBodyCallsResolveFreshSnapshots() {
        when(contentDeliveryQueryPort.findByAssetId(ASSET_ID))
                .thenReturn(Optional.of(activePublicSnapshot()), Optional.of(activePublicSnapshot()));
        when(binaryStoragePort.providerId()).thenReturn(StorageProviderId.of("local"));
        when(binaryStoragePort.open(STORAGE_KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        GetMediaAssetContentMetadataResult resolved = useCase.resolveMetadata(
                new GetMediaAssetContentQuery(ASSET_ID)
        );
        useCase.open(resolved);

        verify(contentDeliveryQueryPort, times(2)).findByAssetId(ASSET_ID);
        verify(binaryStoragePort).open(STORAGE_KEY);
    }

    @Test
    void invalidRangeBoundsFailBeforeQueryOrStorageAccess() {
        assertThatThrownBy(() -> useCase.openRange(metadata(), -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.openRange(metadata(), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);

        verify(contentDeliveryQueryPort, never()).findByAssetId(any());
        verifyNoStorageOpen();
    }

    @Test
    void missingAssetThrowsNotFoundWithoutStorageAccess() {
        when(contentDeliveryQueryPort.findByAssetId(ASSET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.resolveMetadata(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetNotFoundException.class);

        verifyNoStorageOpen();
    }

    @ParameterizedTest
    @MethodSource("ineligibleStates")
    void ineligibleAssetFailsClosedDuringMetadataResolution(
            MediaAssetStatus status,
            MediaVisibility visibility
    ) {
        stubSnapshot(snapshot(status, visibility, 1, VERSION_ID, ASSET_ID, 1,
                "local", STORAGE_KEY.value(), HASH, "audio/mpeg", 1024L));

        assertThatThrownBy(() -> useCase.resolveMetadata(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetNotFoundException.class);

        verifyNoStorageOpen();
    }

    @Test
    void missingOrInconsistentDeclaredCurrentVersionThrowsVersionNotFound() {
        stubSnapshot(snapshot(MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 2,
                null, null, null, null, null, null, null, null));

        assertThatThrownBy(() -> useCase.resolveMetadata(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetVersionNotFoundException.class)
                .hasMessageContaining("2");

        verifyNoStorageOpen();
    }

    @Test
    void wrongVersionOwnershipThrowsVersionNotFound() {
        stubSnapshot(snapshot(MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1,
                VERSION_ID, UUID.randomUUID(), 1, "local", STORAGE_KEY.value(), HASH,
                "audio/mpeg", 1024L));

        assertThatThrownBy(() -> useCase.resolveMetadata(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetVersionNotFoundException.class);
        verifyNoStorageOpen();
    }

    @Test
    void invalidTechnicalMetadataFailsClosed() {
        stubSnapshot(snapshot(MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1,
                VERSION_ID, ASSET_ID, 1, "local", STORAGE_KEY.value(), null,
                "audio/mpeg", 1024L));

        assertThatThrownBy(() -> useCase.resolveMetadata(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Invalid current Media delivery metadata");
        verifyNoStorageOpen();
    }

    @Test
    void providerMismatchFailsBeforeStorageOpen() {
        stubSnapshot(snapshot(MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1,
                VERSION_ID, ASSET_ID, 1, "s3", STORAGE_KEY.value(), HASH,
                "audio/mpeg", 1024L));

        assertThatThrownBy(() -> useCase.resolveMetadata(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Storage provider mismatch");
        verifyNoStorageOpen();
    }

    @Test
    void currentVersionAdvanceBetweenPhasesFailsClosed() {
        MediaAssetContentDeliverySnapshot changed = snapshot(
                MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 2, UUID.randomUUID(), ASSET_ID, 2,
                "local", "objects/chapter-v2.mp3", OTHER_HASH, "audio/mpeg", 2048L
        );
        stubSnapshot(changed);

        assertThatThrownBy(() -> useCase.open(metadata()))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("changed after delivery metadata was resolved");
        verifyNoStorageOpen();
    }

    @ParameterizedTest
    @MethodSource("phaseTwoRevocations")
    void lifecycleOrVisibilityRevocationBetweenPhasesFailsClosed(
            MediaAssetStatus status,
            MediaVisibility visibility
    ) {
        stubSnapshot(snapshot(status, visibility, 1, VERSION_ID, ASSET_ID, 1,
                "local", STORAGE_KEY.value(), HASH, "audio/mpeg", 1024L));

        assertThatThrownBy(() -> useCase.open(metadata()))
                .isInstanceOf(MediaAssetNotFoundException.class);
        verifyNoStorageOpen();
    }

    @Test
    void declaredCurrentVersionDisappearingBetweenPhasesFailsClosed() {
        stubSnapshot(snapshot(MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1,
                null, null, null, null, null, null, null, null));

        assertThatThrownBy(() -> useCase.open(metadata()))
                .isInstanceOf(MediaAssetVersionNotFoundException.class);
        verifyNoStorageOpen();
    }

    @Test
    void providerChangingBetweenPhasesFailsClosed() {
        stubSnapshot(snapshot(MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1,
                VERSION_ID, ASSET_ID, 1, "s3", STORAGE_KEY.value(), HASH,
                "audio/mpeg", 1024L));

        assertThatThrownBy(() -> useCase.open(metadata()))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Storage provider mismatch");
        verifyNoStorageOpen();
    }

    @ParameterizedTest
    @MethodSource("changedRepresentations")
    void publicRepresentationChangeBetweenPhasesFailsClosed(
            String hash,
            String mimeType,
            long sizeBytes
    ) {
        stubSnapshot(snapshot(MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1,
                VERSION_ID, ASSET_ID, 1, "local", STORAGE_KEY.value(), hash, mimeType, sizeBytes));

        assertThatThrownBy(() -> useCase.openRange(metadata(), 0, 10))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("changed after delivery metadata was resolved");
        verifyNoStorageOpen();
    }

    @Test
    void storageObjectNotFoundStillPropagates() {
        stubSnapshot(activePublicSnapshot());
        when(binaryStoragePort.open(STORAGE_KEY))
                .thenThrow(new StorageObjectNotFoundException(STORAGE_KEY));

        assertThatThrownBy(() -> useCase.open(metadata()))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }

    private void stubSnapshot(MediaAssetContentDeliverySnapshot snapshot) {
        when(contentDeliveryQueryPort.findByAssetId(ASSET_ID)).thenReturn(Optional.of(snapshot));
        if (snapshot.storageProviderId() != null) {
            lenient().when(binaryStoragePort.providerId()).thenReturn(StorageProviderId.of("local"));
        }
    }

    private MediaAssetContentDeliverySnapshot activePublicSnapshot() {
        return snapshot(MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1,
                VERSION_ID, ASSET_ID, 1, "local", STORAGE_KEY.value(), HASH,
                "audio/mpeg", 1024L);
    }

    private GetMediaAssetContentMetadataResult metadata() {
        return new GetMediaAssetContentMetadataResult(ASSET_ID, 1, 1024L, "audio/mpeg", HASH);
    }

    private MediaAssetContentDeliverySnapshot snapshot(
            MediaAssetStatus status,
            MediaVisibility visibility,
            int currentVersionNumber,
            UUID versionId,
            UUID versionAssetId,
            Integer versionNumber,
            String storageProviderId,
            String storageKey,
            String contentHash,
            String mimeType,
            Long sizeBytes
    ) {
        return new MediaAssetContentDeliverySnapshot(
                ASSET_ID, status, visibility, currentVersionNumber,
                versionId, versionAssetId, versionNumber,
                storageProviderId, storageKey, contentHash, mimeType, sizeBytes
        );
    }

    private void verifyNoStorageOpen() {
        verify(binaryStoragePort, never()).open(any());
        verify(binaryStoragePort, never()).openRange(any(), anyLong(), anyLong());
    }

    private static Stream<Arguments> ineligibleStates() {
        return Stream.of(
                Arguments.of(MediaAssetStatus.ARCHIVED, MediaVisibility.PUBLIC),
                Arguments.of(MediaAssetStatus.DELETED, MediaVisibility.PUBLIC),
                Arguments.of(MediaAssetStatus.ACTIVE, MediaVisibility.PRIVATE),
                Arguments.of(MediaAssetStatus.ACTIVE, MediaVisibility.RESTRICTED)
        );
    }

    private static Stream<Arguments> phaseTwoRevocations() {
        return ineligibleStates();
    }

    private static Stream<Arguments> changedRepresentations() {
        return Stream.of(
                Arguments.of(OTHER_HASH, "audio/mpeg", 1024L),
                Arguments.of(HASH, "audio/ogg", 1024L),
                Arguments.of(HASH, "audio/mpeg", 2048L)
        );
    }
}
