package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UploadChapterNarrationPlaybackMediaUseCase Tests")
class UploadChapterNarrationPlaybackMediaUseCaseTest {

    private static final byte[] MP3_BYTES = new byte[]{'I', 'D', '3', 4, 0, 0, 1, 2, 3};
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final String ORIGINAL_FILENAME = "chapter-42-voice-7.mp3";

    @Mock
    private MediaContract mediaContract;

    private UploadChapterNarrationPlaybackMediaUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new UploadChapterNarrationPlaybackMediaUseCase(mediaContract);
    }

    @Test
    @DisplayName("uploads MP3 as one new public audio asset and preserves caller resource ownership")
    void shouldUploadNewPublicAudioAssetAndPreserveResourceOwnership() throws IOException {
        TrackingEncodedResource resource = new TrackingEncodedResource("audio/mpeg", MP3_BYTES.length, MP3_BYTES);
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenAnswer(invocation -> {
                    UploadMediaAssetRequestDTO request = invocation.getArgument(0);
                    assertThat(request.content().readAllBytes()).isEqualTo(MP3_BYTES);
                    return new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID);
                });

        UploadChapterNarrationPlaybackMediaResult result = useCase.execute(
                new UploadChapterNarrationPlaybackMediaCommand(resource, ORIGINAL_FILENAME)
        );

        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        ArgumentCaptor<UploadMediaAssetRequestDTO> captor =
                ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract, times(1)).uploadAsset(captor.capture());
        UploadMediaAssetRequestDTO request = captor.getValue();
        assertThat(request.sizeBytes()).isEqualTo(MP3_BYTES.length);
        assertThat(request.mimeType()).isEqualTo("audio/mpeg");
        assertThat(request.mediaType()).isEqualTo(MediaTypeDTO.AUDIO);
        assertThat(request.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(request.originalFilename()).isEqualTo(ORIGINAL_FILENAME);
        verify(mediaContract, never()).uploadVersion(any());
        assertThat(resource.openCount()).isEqualTo(1);
        assertThat(resource.lastOpenedStreamClosed()).isTrue();
        assertThat(resource.resourceClosed()).isFalse();

        try (InputStream reusableStream = resource.openStream()) {
            assertThat(reusableStream.readAllBytes()).isEqualTo(MP3_BYTES);
        }
        assertThat(resource.resourceClosed()).isFalse();
        resource.close();
        assertThat(resource.resourceClosed()).isTrue();
    }

    @Test
    @DisplayName("upload failure propagates while closing only the opened stream")
    void shouldCloseOpenedStreamButNotResourceWhenUploadFails() {
        TrackingEncodedResource resource = new TrackingEncodedResource("audio/mpeg", MP3_BYTES.length, MP3_BYTES);
        RuntimeException uploadFailure = new RuntimeException("Media upload failed");
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class))).thenThrow(uploadFailure);

        assertThatThrownBy(() -> useCase.execute(
                new UploadChapterNarrationPlaybackMediaCommand(resource, ORIGINAL_FILENAME)
        )).isSameAs(uploadFailure);

        verify(mediaContract, times(1)).uploadAsset(any(UploadMediaAssetRequestDTO.class));
        verify(mediaContract, never()).uploadVersion(any());
        verify(mediaContract, never()).delete(any());
        assertThat(resource.openCount()).isEqualTo(1);
        assertThat(resource.lastOpenedStreamClosed()).isTrue();
        assertThat(resource.resourceClosed()).isFalse();
    }

    @Test
    @DisplayName("upload and stream close failure preserves upload failure and suppresses close failure")
    void shouldPreserveUploadFailureAndSuppressCloseFailure() {
        RuntimeException uploadFailure = new RuntimeException("Media upload failed");
        IOException closeFailure = new IOException("stream close failed");
        TrackingEncodedResource resource = new TrackingEncodedResource(
                "audio/mpeg",
                MP3_BYTES.length,
                MP3_BYTES,
                null,
                closeFailure
        );
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class))).thenThrow(uploadFailure);

        assertThatThrownBy(() -> useCase.execute(
                new UploadChapterNarrationPlaybackMediaCommand(resource, ORIGINAL_FILENAME)
        )).isSameAs(uploadFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(closeFailure));

        verify(mediaContract, never()).delete(any());
        assertThat(resource.lastOpenedStreamClosed()).isTrue();
        assertThat(resource.resourceClosed()).isFalse();
    }

    @Test
    @DisplayName("successful upload returns its asset identity when stream close fails")
    void shouldReturnMediaAssetIdWhenCloseFailsAfterSuccessfulUpload() {
        IOException closeFailure = new IOException("stream close failed");
        TrackingEncodedResource resource = new TrackingEncodedResource(
                "audio/mpeg",
                MP3_BYTES.length,
                MP3_BYTES,
                null,
                closeFailure
        );
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));

        UploadChapterNarrationPlaybackMediaResult result = useCase.execute(
                new UploadChapterNarrationPlaybackMediaCommand(resource, ORIGINAL_FILENAME)
        );

        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        verify(mediaContract, never()).delete(any());
        assertThat(resource.lastOpenedStreamClosed()).isTrue();
        assertThat(resource.resourceClosed()).isFalse();
    }

    @Test
    @DisplayName("rejects a non-H.9D2 MIME type before opening or calling Media")
    void shouldRejectInvalidMimeBeforeMediaCall() {
        TrackingEncodedResource resource = new TrackingEncodedResource("audio/wav", MP3_BYTES.length, MP3_BYTES);

        assertThatThrownBy(() -> useCase.execute(
                new UploadChapterNarrationPlaybackMediaCommand(resource, ORIGINAL_FILENAME)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audio/mpeg");

        assertThat(resource.openCount()).isZero();
        assertThat(resource.resourceClosed()).isFalse();
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("rejects non-positive encoded sizes before opening or calling Media")
    void shouldRejectNonPositiveSizeBeforeMediaCall() {
        for (long invalidSize : new long[]{0, -1}) {
            TrackingEncodedResource resource = new TrackingEncodedResource("audio/mpeg", invalidSize, MP3_BYTES);

            assertThatThrownBy(() -> useCase.execute(
                    new UploadChapterNarrationPlaybackMediaCommand(resource, ORIGINAL_FILENAME)
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sizeBytes");

            assertThat(resource.openCount()).isZero();
            assertThat(resource.resourceClosed()).isFalse();
        }
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("opening failure propagates without calling Media or closing the resource")
    void shouldPreserveResourceOwnershipWhenOpeningFails() {
        RuntimeException openingFailure = new RuntimeException("open failed");
        TrackingEncodedResource resource = new TrackingEncodedResource(
                "audio/mpeg",
                MP3_BYTES.length,
                MP3_BYTES,
                openingFailure
        );

        assertThatThrownBy(() -> useCase.execute(
                new UploadChapterNarrationPlaybackMediaCommand(resource, ORIGINAL_FILENAME)
        )).isSameAs(openingFailure);

        assertThat(resource.openCount()).isEqualTo(1);
        assertThat(resource.resourceClosed()).isFalse();
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("null resource is rejected by the use case before Media interaction")
    void shouldRejectNullResourceBeforeMediaInteraction() {
        UploadChapterNarrationPlaybackMediaCommand command =
                new UploadChapterNarrationPlaybackMediaCommand(null, ORIGINAL_FILENAME);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resource");

        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("blank original filename is rejected before opening or calling Media")
    void shouldRejectBlankOriginalFilenameBeforeOpeningOrMediaInteraction() {
        TrackingEncodedResource resource = new TrackingEncodedResource("audio/mpeg", MP3_BYTES.length, MP3_BYTES);

        assertThatThrownBy(() -> useCase.execute(
                new UploadChapterNarrationPlaybackMediaCommand(resource, "   ")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalFilename");

        assertThat(resource.openCount()).isZero();
        assertThat(resource.resourceClosed()).isFalse();
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("null Media response fails clearly after closing the opened stream")
    void shouldRejectNullMediaResponse() {
        TrackingEncodedResource resource = new TrackingEncodedResource("audio/mpeg", MP3_BYTES.length, MP3_BYTES);
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class))).thenReturn(null);

        assertThatThrownBy(() -> useCase.execute(
                new UploadChapterNarrationPlaybackMediaCommand(resource, ORIGINAL_FILENAME)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null response");

        assertThat(resource.lastOpenedStreamClosed()).isTrue();
        assertThat(resource.resourceClosed()).isFalse();
    }

    @Test
    @DisplayName("null Media asset identity fails clearly after closing the opened stream")
    void shouldRejectNullMediaAssetId() {
        TrackingEncodedResource resource = new TrackingEncodedResource("audio/mpeg", MP3_BYTES.length, MP3_BYTES);
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(null));

        assertThatThrownBy(() -> useCase.execute(
                new UploadChapterNarrationPlaybackMediaCommand(resource, ORIGINAL_FILENAME)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null asset ID");

        assertThat(resource.lastOpenedStreamClosed()).isTrue();
        assertThat(resource.resourceClosed()).isFalse();
    }

    private static final class TrackingEncodedResource implements ChapterAudioBinaryResource {

        private final String mimeType;
        private final long sizeBytes;
        private final byte[] bytes;
        private final RuntimeException openingFailure;
        private final IOException closeFailure;
        private int openCount;
        private boolean lastOpenedStreamClosed;
        private boolean resourceClosed;

        private TrackingEncodedResource(String mimeType, long sizeBytes, byte[] bytes) {
            this(mimeType, sizeBytes, bytes, null, null);
        }

        private TrackingEncodedResource(
                String mimeType,
                long sizeBytes,
                byte[] bytes,
                RuntimeException openingFailure
        ) {
            this(mimeType, sizeBytes, bytes, openingFailure, null);
        }

        private TrackingEncodedResource(
                String mimeType,
                long sizeBytes,
                byte[] bytes,
                RuntimeException openingFailure,
                IOException closeFailure
        ) {
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
            this.bytes = bytes.clone();
            this.openingFailure = openingFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public String mimeType() {
            return mimeType;
        }

        @Override
        public long sizeBytes() {
            return sizeBytes;
        }

        @Override
        public InputStream openStream() {
            if (resourceClosed) {
                throw new IllegalStateException("resource already closed");
            }
            openCount++;
            if (openingFailure != null) {
                throw openingFailure;
            }
            lastOpenedStreamClosed = false;
            return new FilterInputStream(new ByteArrayInputStream(bytes)) {
                private boolean closed;

                @Override
                public void close() throws IOException {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    try {
                        super.close();
                    } finally {
                        lastOpenedStreamClosed = true;
                    }
                    if (closeFailure != null) {
                        throw closeFailure;
                    }
                }
            };
        }

        @Override
        public void close() {
            resourceClosed = true;
        }

        private int openCount() {
            return openCount;
        }

        private boolean lastOpenedStreamClosed() {
            return lastOpenedStreamClosed;
        }

        private boolean resourceClosed() {
            return resourceClosed;
        }
    }
}
