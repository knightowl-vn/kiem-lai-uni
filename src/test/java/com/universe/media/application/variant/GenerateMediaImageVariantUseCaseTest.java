package com.universe.media.application.variant;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.UnsupportedImageFormatException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.application.ports.image.ImageProcessorPort;
import com.universe.media.application.ports.image.ProcessedImageResource;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.MediaAsset;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateMediaImageVariantUseCaseTest {

    private MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort;
    private BinaryStoragePort binaryStoragePort;
    private ImageProcessorPort imageProcessorPort;
    private ClockPort clockPort;

    private GenerateMediaImageVariantUseCase useCase;

    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VERSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final StorageProviderId PROVIDER_ID = StorageProviderId.of("local");
    private static final StorageKey SOURCE_KEY = StorageKey.of("objects/source.jpg");
    private static final StorageLocation SOURCE_LOCATION = StorageLocation.of(PROVIDER_ID, SOURCE_KEY);
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @BeforeEach
    void setUp() {
        mediaAssetRepositoryPort = mock(MediaAssetRepositoryPort.class);
        mediaAssetVersionRepositoryPort = mock(MediaAssetVersionRepositoryPort.class);
        mediaImageVariantRepositoryPort = mock(MediaImageVariantRepositoryPort.class);
        binaryStoragePort = mock(BinaryStoragePort.class);
        imageProcessorPort = mock(ImageProcessorPort.class);
        clockPort = mock(ClockPort.class);

        when(clockPort.now()).thenReturn(NOW);
        when(binaryStoragePort.providerId()).thenReturn(PROVIDER_ID);

        useCase = new GenerateMediaImageVariantUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                mediaImageVariantRepositoryPort,
                binaryStoragePort,
                imageProcessorPort,
                clockPort
        );
    }

    private MediaAsset createAsset(MediaType type) {
        return MediaAsset.registerInitial(ASSET_ID, type, MediaVisibility.PUBLIC, NOW);
    }

    private MediaAssetVersion createVersion(MimeType mimeType) {
        return MediaAssetVersion.create(
                VERSION_ID,
                ASSET_ID,
                1,
                SOURCE_LOCATION,
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                mimeType,
                100000L,
                "photo.jpg",
                NOW
        );
    }

    @Test
    @DisplayName("generates JPEG variant successfully, consumes derivative stream, and proves ContentHash matches derivative SHA-256")
    void shouldGenerateJpegVariantSuccessfullyAndProveSha256() throws NoSuchAlgorithmException {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300")).thenReturn(Optional.empty());

        AtomicBoolean sourceStreamClosed = new AtomicBoolean(false);
        InputStream sourceStream = new FilterInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3})) {
            @Override
            public void close() throws IOException {
                sourceStreamClosed.set(true);
                super.close();
            }
        };
        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(sourceStream);

        byte[] derivativePayload = new byte[]{10, 20, 30, 40, 50, 60, 70, 80};
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(derivativePayload));

        AtomicBoolean resourceClosed = new AtomicBoolean(false);
        AtomicBoolean processedStreamClosed = new AtomicBoolean(false);
        ProcessedImageResource resource = new ProcessedImageResource() {
            @Override
            public MimeType mimeType() {
                return MimeType.of("image/jpeg");
            }

            @Override
            public long sizeBytes() {
                return (long) derivativePayload.length;
            }

            @Override
            public int width() {
                return 300;
            }

            @Override
            public int height() {
                return 200;
            }

            @Override
            public InputStream openStream() {
                return new FilterInputStream(new ByteArrayInputStream(derivativePayload)) {
                    @Override
                    public void close() throws IOException {
                        processedStreamClosed.set(true);
                        super.close();
                    }
                };
            }

            @Override
            public void close() {
                resourceClosed.set(true);
            }
        };

        when(imageProcessorPort.process(any(), eq(MimeType.of("image/jpeg")), eq(spec))).thenReturn(resource);

        // Make mock BinaryStoragePort.store actually consume the supplied stream
        doAnswer(invocation -> {
            InputStream in = invocation.getArgument(1);
            in.readAllBytes();
            return null;
        }).when(binaryStoragePort).store(any(), any(), anyLong(), any());

        when(mediaImageVariantRepositoryPort.save(any(MediaImageVariant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, 1, spec);
        GenerateMediaImageVariantResult result = useCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.assetId()).isEqualTo(ASSET_ID);
        assertThat(result.versionId()).isEqualTo(VERSION_ID);
        assertThat(result.versionNumber()).isEqualTo(1);
        assertThat(result.variantKey()).isEqualTo("w300");
        assertThat(result.targetWidth()).isEqualTo(300);
        assertThat(result.mimeType()).isEqualTo("image/jpeg");
        assertThat(result.sizeBytes()).isEqualTo((long) derivativePayload.length);
        assertThat(result.width()).isEqualTo(300);
        assertThat(result.height()).isEqualTo(200);
        assertThat(result.createdAt()).isEqualTo(NOW);

        // Capture saved variant and prove SHA-256 hash equality
        ArgumentCaptor<MediaImageVariant> variantCaptor = ArgumentCaptor.forClass(MediaImageVariant.class);
        verify(mediaImageVariantRepositoryPort).save(variantCaptor.capture());
        MediaImageVariant savedVariant = variantCaptor.getValue();
        assertThat(savedVariant.getContentHash().value()).isEqualTo(expectedHash);

        // Verify storage was invoked with unique derivative key
        ArgumentCaptor<StorageKey> keyCaptor = ArgumentCaptor.forClass(StorageKey.class);
        verify(binaryStoragePort).store(keyCaptor.capture(), any(), eq((long) derivativePayload.length), eq(MimeType.of("image/jpeg")));
        assertThat(keyCaptor.getValue().value()).startsWith("objects/variants/");

        // Derivative binary must NOT be deleted after successful persistence
        verify(binaryStoragePort, never()).delete(any());

        // Verify stream and resource closures
        assertThat(sourceStreamClosed.get()).isTrue();
        assertThat(processedStreamClosed.get()).isTrue();
        assertThat(resourceClosed.get()).isTrue();
    }

    @Test
    @DisplayName("generates PNG image variant successfully")
    void shouldGeneratePngVariantSuccessfully() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/png"));
        ImageVariantSpec spec = ImageVariantSpec.of(200);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w200")).thenReturn(Optional.empty());

        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));

        ProcessedImageResource resource = mock(ProcessedImageResource.class);
        when(resource.mimeType()).thenReturn(MimeType.of("image/png"));
        when(resource.sizeBytes()).thenReturn(12000L);
        when(resource.width()).thenReturn(200);
        when(resource.height()).thenReturn(200);
        when(resource.openStream()).thenReturn(new ByteArrayInputStream(new byte[]{3, 4}));

        when(imageProcessorPort.process(any(), eq(MimeType.of("image/png")), eq(spec))).thenReturn(resource);
        when(mediaImageVariantRepositoryPort.save(any(MediaImageVariant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, spec);
        GenerateMediaImageVariantResult result = useCase.execute(command);

        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(result.variantKey()).isEqualTo("w200");
        assertThat(result.width()).isEqualTo(200);
        assertThat(result.height()).isEqualTo(200);
    }

    @Test
    @DisplayName("idempotency: returns existing variant immediately without reading source, processing, or writing storage")
    void shouldReturnExistingVariantWithoutProcessingOrStorage() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        MediaImageVariant existingVariant = MediaImageVariant.create(
                UUID.randomUUID(),
                VERSION_ID,
                spec,
                StorageLocation.of("local", "objects/variants/existing.jpg"),
                ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/jpeg"),
                18000L,
                300,
                200,
                NOW
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300")).thenReturn(Optional.of(existingVariant));

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, 1, spec);
        GenerateMediaImageVariantResult result = useCase.execute(command);

        assertThat(result.variantId()).isEqualTo(existingVariant.getId());
        assertThat(result.variantKey()).isEqualTo("w300");

        verify(binaryStoragePort, never()).open(any());
        verify(imageProcessorPort, never()).process(any(), any(), any());
        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
        verify(mediaImageVariantRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("source close failure after successful processing closes ProcessedImageResource")
    void shouldCloseProcessedImageResourceWhenSourceStreamCloseFails() throws IOException {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300")).thenReturn(Optional.empty());

        InputStream sourceStream = mock(InputStream.class);
        doThrow(new IOException("Disk error on source close")).when(sourceStream).close();
        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(sourceStream);

        ProcessedImageResource resource = mock(ProcessedImageResource.class);
        when(imageProcessorPort.process(any(), any(), any())).thenReturn(resource);

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, spec);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Failed to close source binary stream");

        // Verify ProcessedImageResource was closed
        verify(resource).close();
        // Derivative storage was never reached
        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("if source close fails and resource cleanup also fails, cleanup failure is suppressed on primary StorageException")
    void shouldSuppressResourceCleanupFailureWhenSourceCloseFails() throws IOException {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300")).thenReturn(Optional.empty());

        InputStream sourceStream = mock(InputStream.class);
        doThrow(new IOException("Disk error on source close")).when(sourceStream).close();
        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(sourceStream);

        ProcessedImageResource resource = mock(ProcessedImageResource.class);
        RuntimeException cleanupEx = new RuntimeException("Temp file delete failed");
        doThrow(cleanupEx).when(resource).close();
        when(imageProcessorPort.process(any(), any(), any())).thenReturn(resource);

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, spec);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Failed to close source binary stream")
                .satisfies(ex -> assertThat(ex.getSuppressed()).contains(cleanupEx));
    }

    @Test
    @DisplayName("processing failure leaves source version and storage intact and does not close nonexistent resource")
    void shouldLeaveSourceIntactWhenProcessingFails() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300")).thenReturn(Optional.empty());

        AtomicBoolean sourceClosed = new AtomicBoolean(false);
        InputStream sourceStream = new FilterInputStream(new ByteArrayInputStream(new byte[]{1, 2})) {
            @Override
            public void close() throws IOException {
                sourceClosed.set(true);
                super.close();
            }
        };
        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(sourceStream);

        when(imageProcessorPort.process(any(), any(), any()))
                .thenThrow(new UnsupportedImageFormatException("Unsupported format"));

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, spec);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageContaining("Unsupported format");

        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
        verify(mediaImageVariantRepositoryPort, never()).save(any());
        verify(binaryStoragePort, never()).delete(any());
        assertThat(sourceClosed.get()).isTrue();
    }

    @Test
    @DisplayName("storage failure does not compensate derivative and does not persist variant")
    void shouldNotPersistVariantAndNotCompensateWhenStorageFails() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300")).thenReturn(Optional.empty());
        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        ProcessedImageResource resource = mock(ProcessedImageResource.class);
        when(resource.mimeType()).thenReturn(MimeType.of("image/jpeg"));
        when(resource.sizeBytes()).thenReturn(1000L);
        when(resource.openStream()).thenReturn(new ByteArrayInputStream(new byte[]{2}));
        when(imageProcessorPort.process(any(), any(), any())).thenReturn(resource);

        doThrow(new StorageException("S3 write failed"))
                .when(binaryStoragePort).store(any(), any(), anyLong(), any());

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, spec);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("S3 write failed");

        verify(mediaImageVariantRepositoryPort, never()).save(any());
        // Storage failure itself does NOT compensate (storage adapter owns failed write cleanup)
        verify(binaryStoragePort, never()).delete(any());
        verify(resource).close();
    }

    @Test
    @DisplayName("store succeeds but processed stream close fails -> derivative is deleted and repository save is not called")
    void shouldCompensateDerivativeWhenProcessedStreamCloseFailsAfterStore() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300")).thenReturn(Optional.empty());
        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        InputStream failingStream = new FilterInputStream(new ByteArrayInputStream(new byte[]{2})) {
            @Override
            public void close() throws IOException {
                throw new IOException("Stream close failure after store");
            }
        };

        ProcessedImageResource resource = mock(ProcessedImageResource.class);
        when(resource.mimeType()).thenReturn(MimeType.of("image/jpeg"));
        when(resource.sizeBytes()).thenReturn(1000L);
        when(resource.openStream()).thenReturn(failingStream);
        when(imageProcessorPort.process(any(), any(), any())).thenReturn(resource);

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, spec);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Failed to stream processed variant binary");

        // Derivative binary was compensated because store succeeded
        verify(binaryStoragePort).delete(any(StorageKey.class));
        verify(mediaImageVariantRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("compensation failure when processed stream close fails is suppressed on primary StorageException")
    void shouldSuppressCompensationFailureWhenProcessedStreamCloseFails() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300")).thenReturn(Optional.empty());
        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        InputStream failingStream = new FilterInputStream(new ByteArrayInputStream(new byte[]{2})) {
            @Override
            public void close() throws IOException {
                throw new IOException("Stream close failure");
            }
        };

        ProcessedImageResource resource = mock(ProcessedImageResource.class);
        when(resource.mimeType()).thenReturn(MimeType.of("image/jpeg"));
        when(resource.sizeBytes()).thenReturn(1000L);
        when(resource.openStream()).thenReturn(failingStream);
        when(imageProcessorPort.process(any(), any(), any())).thenReturn(resource);

        StorageException deleteError = new StorageException("S3 delete failed during cleanup");
        doThrow(deleteError).when(binaryStoragePort).delete(any());

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, spec);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Failed to stream processed variant binary")
                .satisfies(ex -> assertThat(ex.getSuppressed()).contains(deleteError));
    }

    @Test
    @DisplayName("post-store ClockPort or domain failure compensates derivative storage and leaves source intact")
    void shouldCompensateDerivativeStorageWhenPostStoreClockOrDomainFails() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300")).thenReturn(Optional.empty());
        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        ProcessedImageResource resource = mock(ProcessedImageResource.class);
        when(resource.mimeType()).thenReturn(MimeType.of("image/jpeg"));
        when(resource.sizeBytes()).thenReturn(1000L);
        when(resource.width()).thenReturn(300);
        when(resource.height()).thenReturn(200);
        when(resource.openStream()).thenReturn(new ByteArrayInputStream(new byte[]{2}));
        when(imageProcessorPort.process(any(), any(), any())).thenReturn(resource);

        RuntimeException clockError = new RuntimeException("Clock failure");
        when(clockPort.now()).thenThrow(clockError);

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, spec);

        assertThatThrownBy(() -> useCase.execute(command))
                .isSameAs(clockError);

        // Derivative binary was compensated
        verify(binaryStoragePort).delete(any(StorageKey.class));
        verify(binaryStoragePort, never()).delete(SOURCE_KEY);
        verify(mediaImageVariantRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("ordinary non-integrity persistence failure compensates derivative storage and NEVER attempts winner lookup")
    void shouldCompensateDerivativeStorageOnOrdinaryPersistenceFailureAndNeverAttemptWinnerLookup() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300")).thenReturn(Optional.empty());
        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        ProcessedImageResource resource = mock(ProcessedImageResource.class);
        when(resource.mimeType()).thenReturn(MimeType.of("image/jpeg"));
        when(resource.sizeBytes()).thenReturn(1000L);
        when(resource.width()).thenReturn(300);
        when(resource.height()).thenReturn(200);
        when(resource.openStream()).thenReturn(new ByteArrayInputStream(new byte[]{2}));
        when(imageProcessorPort.process(any(), any(), any())).thenReturn(resource);

        RuntimeException genericDbError = new RuntimeException("Database connection timeout");
        when(mediaImageVariantRepositoryPort.save(any())).thenThrow(genericDbError);

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, spec);

        assertThatThrownBy(() -> useCase.execute(command))
                .isSameAs(genericDbError);

        // Verify derivative binary was compensated
        ArgumentCaptor<StorageKey> deletedKeyCaptor = ArgumentCaptor.forClass(StorageKey.class);
        verify(binaryStoragePort).delete(deletedKeyCaptor.capture());
        assertThat(deletedKeyCaptor.getValue().value()).startsWith("objects/variants/");

        // Verify source storage key was never deleted
        verify(binaryStoragePort, never()).delete(SOURCE_KEY);

        // Verify findByVersionIdAndVariantKey was called ONLY once (initial check), NEVER for winner lookup
        verify(mediaImageVariantRepositoryPort, times(1)).findByVersionIdAndVariantKey(VERSION_ID, "w300");
    }

    @Test
    @DisplayName("qualifying duplicate collision compensates loser derivative storage and returns winner")
    void shouldCompensateLoserAndReturnWinnerOnQualifyingDuplicateCollision() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        MediaImageVariant winnerVariant = MediaImageVariant.create(
                UUID.randomUUID(),
                VERSION_ID,
                spec,
                StorageLocation.of("local", "objects/variants/winner.jpg"),
                ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/jpeg"),
                20000L,
                300,
                200,
                NOW
        );

        // Initial check returns empty, second check (after collision) returns winner
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerVariant));

        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        ProcessedImageResource resource = mock(ProcessedImageResource.class);
        when(resource.mimeType()).thenReturn(MimeType.of("image/jpeg"));
        when(resource.sizeBytes()).thenReturn(20000L);
        when(resource.width()).thenReturn(300);
        when(resource.height()).thenReturn(200);
        when(resource.openStream()).thenReturn(new ByteArrayInputStream(new byte[]{2}));
        when(imageProcessorPort.process(any(), any(), any())).thenReturn(resource);

        when(mediaImageVariantRepositoryPort.save(any()))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key uq_media_image_variants_version_key"));

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, spec);
        GenerateMediaImageVariantResult result = useCase.execute(command);

        assertThat(result.variantId()).isEqualTo(winnerVariant.getId());
        assertThat(result.variantKey()).isEqualTo("w300");

        // Verify loser binary was deleted
        verify(binaryStoragePort).delete(any(StorageKey.class));
        // Verify 2 lookups occurred: pre-check and post-collision winner resolution
        verify(mediaImageVariantRepositoryPort, times(2)).findByVersionIdAndVariantKey(VERSION_ID, "w300");
    }

    @Test
    @DisplayName("duplicate collision with compensation failure rethrows primary exception with suppressed cleanup and NEVER returns winner")
    void shouldRethrowPrimaryWithSuppressedCleanupWhenCompensationFailsDuringDuplicateCollision() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300")).thenReturn(Optional.empty());
        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        ProcessedImageResource resource = mock(ProcessedImageResource.class);
        when(resource.mimeType()).thenReturn(MimeType.of("image/jpeg"));
        when(resource.sizeBytes()).thenReturn(1000L);
        when(resource.width()).thenReturn(300);
        when(resource.height()).thenReturn(200);
        when(resource.openStream()).thenReturn(new ByteArrayInputStream(new byte[]{2}));
        when(imageProcessorPort.process(any(), any(), any())).thenReturn(resource);

        DataIntegrityViolationException integrityError = new DataIntegrityViolationException("Duplicate key");
        when(mediaImageVariantRepositoryPort.save(any())).thenThrow(integrityError);

        StorageException cleanupError = new StorageException("Delete failed during compensation");
        doThrow(cleanupError).when(binaryStoragePort).delete(any());

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, spec);

        assertThatThrownBy(() -> useCase.execute(command))
                .isSameAs(integrityError)
                .satisfies(ex -> assertThat(ex.getSuppressed()).contains(cleanupError));

        // Winner lookup must NEVER be attempted when compensation fails
        verify(mediaImageVariantRepositoryPort, times(1)).findByVersionIdAndVariantKey(VERSION_ID, "w300");
    }

    @Test
    @DisplayName("duplicate collision with winner lookup failure preserves primary exception with suppressed lookup failure")
    void shouldPreservePrimaryWithSuppressedLookupFailureWhenWinnerLookupFails() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = createVersion(MimeType.of("image/jpeg"));
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        RuntimeException lookupError = new RuntimeException("DB error during winner lookup");
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300"))
                .thenReturn(Optional.empty())
                .thenThrow(lookupError);

        when(binaryStoragePort.open(SOURCE_KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        ProcessedImageResource resource = mock(ProcessedImageResource.class);
        when(resource.mimeType()).thenReturn(MimeType.of("image/jpeg"));
        when(resource.sizeBytes()).thenReturn(1000L);
        when(resource.width()).thenReturn(300);
        when(resource.height()).thenReturn(200);
        when(resource.openStream()).thenReturn(new ByteArrayInputStream(new byte[]{2}));
        when(imageProcessorPort.process(any(), any(), any())).thenReturn(resource);

        DataIntegrityViolationException integrityError = new DataIntegrityViolationException("Duplicate entry");
        when(mediaImageVariantRepositoryPort.save(any())).thenThrow(integrityError);

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, spec);

        assertThatThrownBy(() -> useCase.execute(command))
                .isSameAs(integrityError)
                .satisfies(ex -> assertThat(ex.getSuppressed()).contains(lookupError));
    }

    @Test
    @DisplayName("validates required non-null command fields in the use case")
    void shouldValidateRequiredCommandFieldsInUseCase() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("GenerateMediaImageVariantCommand cannot be null.");

        GenerateMediaImageVariantCommand nullAssetCommand = new GenerateMediaImageVariantCommand(null, 1, ImageVariantSpec.of(300));
        assertThatThrownBy(() -> useCase.execute(nullAssetCommand))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Asset ID cannot be null.");

        GenerateMediaImageVariantCommand nullSpecCommand = new GenerateMediaImageVariantCommand(ASSET_ID, 1, null);
        assertThatThrownBy(() -> useCase.execute(nullSpecCommand))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ImageVariantSpec cannot be null.");
    }

    @Test
    @DisplayName("rejects non-IMAGE asset type with IllegalArgumentException")
    void shouldRejectNonImageAsset() {
        MediaAsset asset = createAsset(MediaType.AUDIO);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, ImageVariantSpec.of(300));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is of type AUDIO, but IMAGE is required");
    }

    @Test
    @DisplayName("fails when asset is not found")
    void shouldThrowWhenAssetNotFound() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.empty());

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, ImageVariantSpec.of(300));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    @DisplayName("fails when asset version is not found")
    void shouldThrowWhenVersionNotFound() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.empty());

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, 1, ImageVariantSpec.of(300));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(MediaAssetVersionNotFoundException.class);
    }

    @Test
    @DisplayName("fails when storage provider mismatches")
    void shouldThrowWhenStorageProviderMismatch() {
        MediaAsset asset = createAsset(MediaType.IMAGE);
        MediaAssetVersion version = MediaAssetVersion.create(
                VERSION_ID,
                ASSET_ID,
                1,
                StorageLocation.of("s3", "objects/source.jpg"),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/jpeg"),
                100000L,
                "photo.jpg",
                NOW
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_ID, "w300")).thenReturn(Optional.empty());

        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(ASSET_ID, 1, ImageVariantSpec.of(300));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Storage provider mismatch");
    }
}
