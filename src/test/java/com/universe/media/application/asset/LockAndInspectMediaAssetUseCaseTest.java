package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LockAndInspectMediaAssetUseCase Unit Tests")
class LockAndInspectMediaAssetUseCaseTest {

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    private LockAndInspectMediaAssetUseCase useCase;

    private final UUID assetId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-19T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new LockAndInspectMediaAssetUseCase(mediaAssetRepositoryPort);
    }

    @Test
    @DisplayName("Lock & Inspect: successfully locks and inspects ACTIVE video asset")
    void shouldSuccessfullyLockAndInspectActiveVideoAsset() {
        MediaAsset asset = createAsset(assetId, MediaType.VIDEO, MediaAssetStatus.ACTIVE, 1);
        when(mediaAssetRepositoryPort.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));

        MediaAssetInspectionResult result = useCase.execute(assetId, MediaType.VIDEO);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(assetId);
        assertThat(result.mediaType()).isEqualTo(MediaType.VIDEO);
        assertThat(result.status()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(result.visibility()).isEqualTo(MediaVisibility.PRIVATE);
        assertThat(result.currentVersionNumber()).isEqualTo(1);

        verify(mediaAssetRepositoryPort).findByIdForUpdate(assetId);
    }

    @Test
    @DisplayName("Lock & Inspect: successfully locks and inspects DELETED video asset")
    void shouldSuccessfullyLockAndInspectDeletedVideoAsset() {
        MediaAsset asset = createAsset(assetId, MediaType.VIDEO, MediaAssetStatus.DELETED, 2);
        when(mediaAssetRepositoryPort.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));

        MediaAssetInspectionResult result = useCase.execute(assetId, MediaType.VIDEO);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(assetId);
        assertThat(result.mediaType()).isEqualTo(MediaType.VIDEO);
        assertThat(result.status()).isEqualTo(MediaAssetStatus.DELETED);
        assertThat(result.currentVersionNumber()).isEqualTo(2);

        verify(mediaAssetRepositoryPort).findByIdForUpdate(assetId);
    }

    @Test
    @DisplayName("Lock & Inspect: null expected type accepts any media type")
    void shouldAcceptAnyMediaTypeWhenExpectedTypeIsNull() {
        MediaAsset asset = createAsset(assetId, MediaType.IMAGE, MediaAssetStatus.ACTIVE, 1);
        when(mediaAssetRepositoryPort.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));

        MediaAssetInspectionResult result = useCase.execute(assetId, null);

        assertThat(result).isNotNull();
        assertThat(result.mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(result.status()).isEqualTo(MediaAssetStatus.ACTIVE);
    }

    @Test
    @DisplayName("Lock & Inspect: fails when asset does not exist in repository")
    void shouldFailWhenAssetNotFound() {
        when(mediaAssetRepositoryPort.findByIdForUpdate(assetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(assetId, MediaType.VIDEO))
                .isInstanceOf(MediaAssetNotFoundException.class);

        verify(mediaAssetRepositoryPort).findByIdForUpdate(assetId);
    }

    @Test
    @DisplayName("Lock & Inspect: fails when asset media type mismatches expected type")
    void shouldFailWhenMediaTypeMismatches() {
        MediaAsset asset = createAsset(assetId, MediaType.IMAGE, MediaAssetStatus.ACTIVE, 1);
        when(mediaAssetRepositoryPort.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(assetId, MediaType.VIDEO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IMAGE")
                .hasMessageContaining("VIDEO");
    }

    @Test
    @DisplayName("Lock & Inspect: fails when asset ID is null")
    void shouldFailWhenAssetIdIsNull() {
        assertThatThrownBy(() -> useCase.execute(null, MediaType.VIDEO))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Asset ID cannot be null");
    }

    @Test
    @DisplayName("Transaction Contract: execute method requires Propagation.MANDATORY")
    void shouldRequireMandatoryTransactionPropagation() throws NoSuchMethodException {
        Method method = LockAndInspectMediaAssetUseCase.class.getMethod("execute", UUID.class, MediaType.class);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private MediaAsset createAsset(UUID id, MediaType type, MediaAssetStatus status, int versionNumber) {
        return MediaAsset.rehydrate(
                id,
                type,
                MediaVisibility.PRIVATE,
                status,
                versionNumber,
                "smoke-tag",
                now,
                now
        );
    }
}
