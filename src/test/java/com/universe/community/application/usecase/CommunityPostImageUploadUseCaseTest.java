package com.universe.community.application.usecase;

import com.universe.community.domain.exception.CommunityPostValidationException;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityPostImageUploadUseCaseTest {

    private MediaContract mediaContract;
    private CommunityPostImageUploadUseCase uploadUseCase;

    @BeforeEach
    void setUp() {
        mediaContract = mock(MediaContract.class);
        uploadUseCase = new CommunityPostImageUploadUseCase(mediaContract);
    }

    @Test
    @DisplayName("Should successfully spool and upload valid JPEG image")
    void shouldUploadValidJpegImage() {
        byte[] content = "fake-jpeg-binary-data".getBytes();
        UUID expectedAssetId = UUID.randomUUID();

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(expectedAssetId));

        UUID actualAssetId = uploadUseCase.uploadImage(
                new ByteArrayInputStream(content),
                content.length,
                "image/jpeg",
                "sample.jpg"
        );

        assertThat(actualAssetId).isEqualTo(expectedAssetId);

        ArgumentCaptor<UploadMediaAssetRequestDTO> captor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(captor.capture());

        UploadMediaAssetRequestDTO request = captor.getValue();
        assertThat(request.sizeBytes()).isEqualTo(content.length);
        assertThat(request.mimeType()).isEqualTo("image/jpeg");
        assertThat(request.mediaType()).isEqualTo(MediaTypeDTO.IMAGE);
        assertThat(request.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(request.clientTag()).isEqualTo(CommunityPostImageUploadUseCase.CLIENT_TAG);
        assertThat(request.originalFilename()).isEqualTo("sample.jpg");
    }

    @ParameterizedTest
    @CsvSource({
            "image/jpeg, test.jpg",
            "image/png, test.png",
            "image/webp, test.webp"
    })
    @DisplayName("Should accept all supported MIME types with matching extensions")
    void shouldAcceptSupportedMimeTypes(String mimeType, String filename) {
        byte[] content = "valid-binary-content".getBytes();
        UUID expectedAssetId = UUID.randomUUID();

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(expectedAssetId));

        UUID actualAssetId = uploadUseCase.uploadImage(
                new ByteArrayInputStream(content),
                content.length,
                mimeType,
                filename
        );

        assertThat(actualAssetId).isEqualTo(expectedAssetId);
    }

    @Test
    @DisplayName("Should reject unsupported MIME type such as image/gif or video/mp4")
    void shouldRejectUnsupportedMimeType() {
        byte[] content = "gif-data".getBytes();

        assertThatThrownBy(() -> uploadUseCase.uploadImage(
                new ByteArrayInputStream(content),
                content.length,
                "image/gif",
                "animation.gif"
        ))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessageContaining("Unsupported image content type");

        verify(mediaContract, never()).uploadAsset(any());
    }

    @ParameterizedTest
    @CsvSource({
            "image/jpeg, upload",
            "image/png, blob",
            "image/webp, image_without_extension",
            "image/jpeg, photo.jfif",
            "image/png, photo.xyz",
            "image/jpeg, PHOTO.JPG"
    })
    @DisplayName("Should accept valid image even when filename has no extension or non-standard extension")
    void shouldAcceptFilenameWithoutExtensionOrNonStandardExtension(String mimeType, String filename) {
        byte[] content = "valid-binary-content".getBytes();
        UUID expectedAssetId = UUID.randomUUID();

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(expectedAssetId));

        UUID actualAssetId = uploadUseCase.uploadImage(
                new ByteArrayInputStream(content),
                content.length,
                mimeType,
                filename
        );

        assertThat(actualAssetId).isEqualTo(expectedAssetId);

        ArgumentCaptor<UploadMediaAssetRequestDTO> captor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(captor.capture());
        assertThat(captor.getValue().originalFilename()).isEqualTo(filename);
    }

    @Test
    @DisplayName("Should reject null input stream")
    void shouldRejectNullInputStream() {
        assertThatThrownBy(() -> uploadUseCase.uploadImage(
                null,
                100,
                "image/jpeg",
                "test.jpg"
        ))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    @DisplayName("Should reject declared size of zero or negative")
    void shouldRejectZeroOrNegativeDeclaredSize() {
        byte[] content = "some-data".getBytes();

        assertThatThrownBy(() -> uploadUseCase.uploadImage(
                new ByteArrayInputStream(content),
                0,
                "image/jpeg",
                "test.jpg"
        ))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessageContaining("cannot be empty");

        assertThatThrownBy(() -> uploadUseCase.uploadImage(
                new ByteArrayInputStream(content),
                -5,
                "image/jpeg",
                "test.jpg"
        ))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessageContaining("cannot be empty");
    }

    @Test
    @DisplayName("Should reject declared size exceeding 10 MB limit")
    void shouldRejectDeclaredSizeExceeding10MB() {
        byte[] content = "dummy".getBytes();
        long oversized = 10L * 1024 * 1024 + 1; // 10 MiB + 1 byte

        assertThatThrownBy(() -> uploadUseCase.uploadImage(
                new ByteArrayInputStream(content),
                oversized,
                "image/jpeg",
                "large.jpg"
        ))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessageContaining("exceeds maximum limit of 10485760 bytes (10 MB)");
    }

    @Test
    @DisplayName("Should accept exactly 10 MB declared and actual size")
    void shouldAcceptExactly10MB() {
        long exact10MB = 10L * 1024 * 1024;
        UUID expectedAssetId = UUID.randomUUID();

        InputStream stream10MB = new InputStream() {
            private long bytesRead = 0;

            @Override
            public int read() {
                if (bytesRead >= exact10MB) {
                    return -1;
                }
                bytesRead++;
                return 0x5A;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (bytesRead >= exact10MB) {
                    return -1;
                }
                int toRead = (int) Math.min(len, exact10MB - bytesRead);
                for (int i = 0; i < toRead; i++) {
                    b[off + i] = 0x5A;
                }
                bytesRead += toRead;
                return toRead;
            }
        };

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(expectedAssetId));

        UUID actualAssetId = uploadUseCase.uploadImage(
                stream10MB,
                exact10MB,
                "image/jpeg",
                "exact10mb.jpg"
        );

        assertThat(actualAssetId).isEqualTo(expectedAssetId);
    }

    @Test
    @DisplayName("Should reject actual stream size exceeding 10 MB during spooling")
    void shouldRejectActualStreamSizeExceeding10MB() {
        long declaredSize = 10L * 1024 * 1024;
        long actualSize = 10L * 1024 * 1024 + 500;

        InputStream oversizedStream = new InputStream() {
            private long bytesRead = 0;

            @Override
            public int read() {
                if (bytesRead >= actualSize) {
                    return -1;
                }
                bytesRead++;
                return 0x5A;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (bytesRead >= actualSize) {
                    return -1;
                }
                int toRead = (int) Math.min(len, actualSize - bytesRead);
                for (int i = 0; i < toRead; i++) {
                    b[off + i] = 0x5A;
                }
                bytesRead += toRead;
                return toRead;
            }
        };

        assertThatThrownBy(() -> uploadUseCase.uploadImage(
                oversizedStream,
                declaredSize,
                "image/jpeg",
                "oversized.jpg"
        ))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessageContaining("exceeds maximum limit of 10485760 bytes (10 MB)");
    }

    @Test
    @DisplayName("Should reject empty stream (0 actual bytes)")
    void shouldRejectEmptyStream() {
        byte[] emptyContent = new byte[0];

        assertThatThrownBy(() -> uploadUseCase.uploadImage(
                new ByteArrayInputStream(emptyContent),
                100,
                "image/jpeg",
                "empty.jpg"
        ))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessageContaining("cannot be empty");
    }

    @Test
    @DisplayName("Should execute compensation delete cleanly")
    void shouldExecuteCompensationDeleteCleanly() {
        UUID assetId = UUID.randomUUID();
        RuntimeException primaryEx = new RuntimeException("Primary DB failure");

        uploadUseCase.compensateUpload(assetId, primaryEx);

        verify(mediaContract).delete(assetId);
        assertThat(primaryEx.getSuppressed()).isEmpty();
    }

    @Test
    @DisplayName("Should attach compensation failure as suppressed exception")
    void shouldAttachCompensationFailureAsSuppressed() {
        UUID assetId = UUID.randomUUID();
        RuntimeException primaryEx = new RuntimeException("Primary DB failure");
        RuntimeException compEx = new RuntimeException("Media delete network failure");

        org.mockito.Mockito.doThrow(compEx).when(mediaContract).delete(assetId);

        uploadUseCase.compensateUpload(assetId, primaryEx);

        verify(mediaContract).delete(assetId);
        assertThat(primaryEx.getSuppressed()).containsExactly(compEx);
    }

    @Test
    @DisplayName("Should reject upload through real Media RasterContentSignatureValidator when bytes mismatch declared MIME")
    void shouldRejectWhenRealMediaRasterSignatureValidatorDetectsMimeMismatch() {
        com.universe.media.application.storage.MediaStorageRoutingService routingService =
                mock(com.universe.media.application.storage.MediaStorageRoutingService.class);
        com.universe.media.application.ports.storage.StorageProviderResolverPort storageResolver =
                mock(com.universe.media.application.ports.storage.StorageProviderResolverPort.class);
        com.universe.media.application.asset.RegisterMediaAssetUseCase registerUseCase =
                mock(com.universe.media.application.asset.RegisterMediaAssetUseCase.class);
        com.universe.media.application.asset.RasterContentSignatureValidator realValidator =
                new com.universe.media.application.asset.RasterContentSignatureValidator();

        com.universe.media.application.asset.UploadMediaAssetUseCase realUploadUseCase =
                new com.universe.media.application.asset.UploadMediaAssetUseCase(
                        routingService,
                        storageResolver,
                        registerUseCase,
                        realValidator
                );

        MediaContract realMediaContract = mock(MediaContract.class);
        when(realMediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class))).thenAnswer(invocation -> {
            UploadMediaAssetRequestDTO req = invocation.getArgument(0);
            com.universe.media.application.asset.UploadMediaAssetCommand cmd =
                    new com.universe.media.application.asset.UploadMediaAssetCommand(
                            req.content(),
                            req.sizeBytes(),
                            req.mimeType(),
                            com.universe.media.domain.MediaType.IMAGE,
                            com.universe.media.domain.MediaVisibility.PUBLIC,
                            req.originalFilename(),
                            req.clientTag()
                    );
            realUploadUseCase.execute(cmd);
            return null;
        });

        CommunityPostImageUploadUseCase pipeline = new CommunityPostImageUploadUseCase(realMediaContract);

        // PNG header bytes but declared as image/jpeg
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00};

        assertThatThrownBy(() -> pipeline.uploadImage(
                new ByteArrayInputStream(pngBytes),
                pngBytes.length,
                "image/jpeg",
                "mismatched.jpg"
        ))
                .isInstanceOf(com.universe.media.application.exceptions.UploadContentMimeMismatchException.class)
                .hasMessageContaining("does not match declared raster MIME type");
    }
}
