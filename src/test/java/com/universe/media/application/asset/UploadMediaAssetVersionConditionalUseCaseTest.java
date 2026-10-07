package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.contracts.dto.MediaVersionUploadOutcome;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadMediaAssetVersionConditionalUseCaseTest {

    private static final StorageProviderId LOCAL_PROVIDER = StorageProviderId.of("local");
    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VERSION_2_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    private static final byte[] VALID_PNG_BYTES_1 = createPngBytes("image-binary-1");
    private static final byte[] VALID_PNG_BYTES_2 = createPngBytes("image-binary-2-different");

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaStorageRoutingService mediaStorageRoutingService;

    @Mock
    private StorageProviderResolverPort storageProviderResolverPort;

    @Mock
    private BinaryStoragePort binaryStoragePort;

    @Mock
    private RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase;

    private RasterContentSignatureValidator rasterValidator;
    private UploadMediaAssetVersionConditionalUseCase useCase;

    private static byte[] createPngBytes(String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] payload = new byte[header.length + textBytes.length];
        System.arraycopy(header, 0, payload, 0, header.length);
        System.arraycopy(textBytes, 0, payload, header.length, textBytes.length);
        return payload;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        rasterValidator = spy(new RasterContentSignatureValidator());
        lenient().when(mediaStorageRoutingService.resolveWriteProvider(any(), any())).thenReturn(LOCAL_PROVIDER);
        lenient().when(mediaStorageRoutingService.generateStorageKey(any(), any(), any(), anyInt()))
                .thenAnswer(inv -> StorageKey.of("objects/" + UUID.randomUUID()));
        lenient().when(storageProviderResolverPort.resolve(LOCAL_PROVIDER)).thenReturn(binaryStoragePort);
        lenient().when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);
        lenient().when(binaryStoragePort.store(any(), any(), anyLong(), any()))
                .thenAnswer(inv -> {
                    StorageKey key = inv.getArgument(0);
                    return new StoredBinaryObject(StorageLocation.of(LOCAL_PROVIDER, key), null);
                });

        MediaAsset defaultImageAsset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, NOW);
        lenient().when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(defaultImageAsset));

        VideoUploadValidator videoUploadValidator = new VideoUploadValidator();

        useCase = new UploadMediaAssetVersionConditionalUseCase(
                mediaAssetRepositoryPort,
                mediaStorageRoutingService,
                storageProviderResolverPort,
                rasterValidator,
                registerMediaAssetVersionUseCase,
                videoUploadValidator
        );
    }

    @Test
    @DisplayName("Test A: Same current binary returns UNCHANGED with zero storage writes and zero version increments")
    void shouldReturnUnchangedWhenUploadedBinaryMatchesCurrentVersion() {
        String hash1 = sha256Hex(VALID_PNG_BYTES_1);

        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(ASSET_ID, hash1))
                .thenReturn(AuthoritativeDuplicateProbeResult.duplicate(1));

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(VALID_PNG_BYTES_1),
                VALID_PNG_BYTES_1.length,
                "image/png",
                "cover.png"
        );

        UploadMediaAssetVersionConditionalResult result = useCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.UNCHANGED);
        assertThat(result.assetId()).isEqualTo(ASSET_ID);
        assertThat(result.versionNumber()).isEqualTo(1);
        assertThat(result.versionId()).isNull();

        // Authoritative probe was called with the exact hash
        verify(registerMediaAssetVersionUseCase).probeAuthoritativeDuplicate(ASSET_ID, hash1);

        // Storage and registration must NEVER be called
        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
        verify(registerMediaAssetVersionUseCase, never()).registerConditionalVersion(any());
        verify(registerMediaAssetVersionUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Test B: Different binary writes storage, registers N+1 version, and returns VERSION_CREATED")
    void shouldReturnVersionCreatedWhenUploadedBinaryDiffersFromCurrentVersion() {
        String hash2 = sha256Hex(VALID_PNG_BYTES_2);

        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(ASSET_ID, hash2))
                .thenReturn(AuthoritativeDuplicateProbeResult.different(1));

        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        RegisterMediaAssetVersionConditionalResult registerResult =
                RegisterMediaAssetVersionConditionalResult.versionCreated(ASSET_ID, VERSION_2_ID, 2, NOW);
        when(registerMediaAssetVersionUseCase.registerConditionalVersion(any(RegisterMediaAssetVersionCommand.class)))
                .thenReturn(registerResult);

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(VALID_PNG_BYTES_2),
                VALID_PNG_BYTES_2.length,
                "image/png",
                "cover_new.png"
        );

        UploadMediaAssetVersionConditionalResult result = useCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.VERSION_CREATED);
        assertThat(result.assetId()).isEqualTo(ASSET_ID);
        assertThat(result.versionNumber()).isEqualTo(2);
        assertThat(result.versionId()).isEqualTo(VERSION_2_ID);

        // Verify storage store was invoked
        verify(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), eq((long) VALID_PNG_BYTES_2.length), eq(MimeType.of("image/png")));

        // Verify registerConditionalVersion command captured correct hash
        ArgumentCaptor<RegisterMediaAssetVersionCommand> captor = ArgumentCaptor.forClass(RegisterMediaAssetVersionCommand.class);
        verify(registerMediaAssetVersionUseCase).registerConditionalVersion(captor.capture());
        assertThat(captor.getValue().assetId()).isEqualTo(ASSET_ID);
        assertThat(captor.getValue().contentHash()).isEqualTo(hash2);
        assertThat(captor.getValue().sizeBytes()).isEqualTo((long) VALID_PNG_BYTES_2.length);
        assertThat(captor.getValue().mimeType()).isEqualTo("image/png");
        assertThat(captor.getValue().originalFilename()).isEqualTo("cover_new.png");
    }

    @Test
    @DisplayName("Test C: Same bytes with different filename returns UNCHANGED (content-driven dedupe)")
    void shouldReturnUnchangedWhenUploadedBytesAreIdenticalEvenWithDifferentFilename() {
        String hash1 = sha256Hex(VALID_PNG_BYTES_1);

        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(ASSET_ID, hash1))
                .thenReturn(AuthoritativeDuplicateProbeResult.duplicate(1));

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(VALID_PNG_BYTES_1),
                VALID_PNG_BYTES_1.length,
                "image/png",
                "brand_new_name.png"
        );

        UploadMediaAssetVersionConditionalResult result = useCase.execute(command);

        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.UNCHANGED);
        assertThat(result.versionNumber()).isEqualTo(1);
        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("Test D: Same filename with different bytes returns VERSION_CREATED")
    void shouldReturnVersionCreatedWhenSameFilenameHasDifferentBytes() {
        String hash2 = sha256Hex(VALID_PNG_BYTES_2);

        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(ASSET_ID, hash2))
                .thenReturn(AuthoritativeDuplicateProbeResult.different(1));

        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        RegisterMediaAssetVersionConditionalResult registerResult =
                RegisterMediaAssetVersionConditionalResult.versionCreated(ASSET_ID, VERSION_2_ID, 2, NOW);
        when(registerMediaAssetVersionUseCase.registerConditionalVersion(any(RegisterMediaAssetVersionCommand.class)))
                .thenReturn(registerResult);

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(VALID_PNG_BYTES_2),
                VALID_PNG_BYTES_2.length,
                "image/png",
                "same_name.png"
        );

        UploadMediaAssetVersionConditionalResult result = useCase.execute(command);

        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.VERSION_CREATED);
        assertThat(result.versionNumber()).isEqualTo(2);
        verify(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), eq((long) VALID_PNG_BYTES_2.length), eq(MimeType.of("image/png")));
    }

    @Test
    @DisplayName("Test E: Missing asset throws MediaAssetNotFoundException")
    void shouldThrowMediaAssetNotFoundExceptionWhenAssetDoesNotExist() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.empty());

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(VALID_PNG_BYTES_1),
                VALID_PNG_BYTES_1.length,
                "image/png",
                "cover.png"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(MediaAssetNotFoundException.class)
                .hasMessageContaining(ASSET_ID.toString());

        verify(registerMediaAssetVersionUseCase, never()).probeAuthoritativeDuplicate(any(), any());
        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("Test F: Inactive/deleted status preserves replacement validation throwing IllegalStateException")
    void shouldThrowIllegalStateExceptionWhenAssetIsNotActive() {
        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(eq(ASSET_ID), any()))
                .thenThrow(new IllegalStateException("Cannot register a new version for a media asset with status: ARCHIVED"));

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(VALID_PNG_BYTES_1),
                VALID_PNG_BYTES_1.length,
                "image/png",
                "cover.png"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCHIVED");

        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("Probe returning different triggers normal version replacement")
    void shouldCreateNewVersionWhenProbeReturnsDifferent() {
        String hash1 = sha256Hex(VALID_PNG_BYTES_1);

        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(ASSET_ID, hash1))
                .thenReturn(AuthoritativeDuplicateProbeResult.different(1));

        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        RegisterMediaAssetVersionConditionalResult registerResult =
                RegisterMediaAssetVersionConditionalResult.versionCreated(ASSET_ID, VERSION_2_ID, 2, NOW);
        when(registerMediaAssetVersionUseCase.registerConditionalVersion(any(RegisterMediaAssetVersionCommand.class)))
                .thenReturn(registerResult);

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(VALID_PNG_BYTES_1),
                VALID_PNG_BYTES_1.length,
                "image/png",
                "cover.png"
        );

        UploadMediaAssetVersionConditionalResult result = useCase.execute(command);

        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.VERSION_CREATED);
        assertThat(result.versionNumber()).isEqualTo(2);
        verify(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), eq((long) VALID_PNG_BYTES_1.length), eq(MimeType.of("image/png")));
    }

    @Test
    @DisplayName("Concurrent race reconciliation: if registerConditionalVersion returns UNCHANGED, storage object is deleted")
    void shouldCompensateStorageWhenConcurrentRaceReturnsUnchanged() {
        String hash2 = sha256Hex(VALID_PNG_BYTES_2);

        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(ASSET_ID, hash2))
                .thenReturn(AuthoritativeDuplicateProbeResult.different(1));

        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        // Simulate concurrent worker already committed hash2 in DB:
        RegisterMediaAssetVersionConditionalResult concurrentUnchanged =
                RegisterMediaAssetVersionConditionalResult.unchanged(ASSET_ID, 2, NOW);
        when(registerMediaAssetVersionUseCase.registerConditionalVersion(any(RegisterMediaAssetVersionCommand.class)))
                .thenReturn(concurrentUnchanged);

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(VALID_PNG_BYTES_2),
                VALID_PNG_BYTES_2.length,
                "image/png",
                "cover.png"
        );

        UploadMediaAssetVersionConditionalResult result = useCase.execute(command);

        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.UNCHANGED);
        assertThat(result.versionNumber()).isEqualTo(2);

        // Verify storage store occurred, but delete was immediately called to clean up redundant object
        verify(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), eq((long) VALID_PNG_BYTES_2.length), eq(MimeType.of("image/png")));
        verify(binaryStoragePort).delete(any(StorageKey.class));
    }

    @Test
    @DisplayName("Concurrent race reconciliation: if registerConditionalVersion returns UNCHANGED and cleanup fails, storage failure reaches caller")
    void shouldFailAndNotReturnUnchangedWhenConcurrentRaceCleanupFails() {
        String hash2 = sha256Hex(VALID_PNG_BYTES_2);

        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(ASSET_ID, hash2))
                .thenReturn(AuthoritativeDuplicateProbeResult.different(1));

        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        // Simulate concurrent worker already committed hash2 in DB:
        RegisterMediaAssetVersionConditionalResult concurrentUnchanged =
                RegisterMediaAssetVersionConditionalResult.unchanged(ASSET_ID, 2, NOW);
        when(registerMediaAssetVersionUseCase.registerConditionalVersion(any(RegisterMediaAssetVersionCommand.class)))
                .thenReturn(concurrentUnchanged);

        StorageKey expectedKey = StorageKey.of("kiemlai/images/" + ASSET_ID + "_v2.png");
        when(mediaStorageRoutingService.generateStorageKey(any(), any(), any(), eq(2)))
                .thenReturn(expectedKey);
        doThrow(new StorageException("Simulated B2 delete network failure on race cleanup"))
                .when(binaryStoragePort).delete(expectedKey);

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(VALID_PNG_BYTES_2),
                VALID_PNG_BYTES_2.length,
                "image/png",
                "cover.png"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Simulated B2 delete network failure on race cleanup");

        verify(binaryStoragePort).store(eq(expectedKey), any(InputStream.class), eq((long) VALID_PNG_BYTES_2.length), eq(MimeType.of("image/png")));
        verify(binaryStoragePort).delete(expectedKey);
    }

    @Test
    @DisplayName("Storage compensation deletes stored binary when registerConditionalVersion throws RuntimeException")
    void shouldCompensateStorageWhenRegistrationFails() {
        String hash2 = sha256Hex(VALID_PNG_BYTES_2);

        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(ASSET_ID, hash2))
                .thenReturn(AuthoritativeDuplicateProbeResult.different(1));

        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        when(registerMediaAssetVersionUseCase.registerConditionalVersion(any(RegisterMediaAssetVersionCommand.class)))
                .thenThrow(new IllegalStateException("DB failure during version registration"));

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(VALID_PNG_BYTES_2),
                VALID_PNG_BYTES_2.length,
                "image/png",
                "cover.png"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB failure");

        // Verify stored binary was compensated (deleted)
        verify(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), eq((long) VALID_PNG_BYTES_2.length), eq(MimeType.of("image/png")));
        verify(binaryStoragePort).delete(any(StorageKey.class));
    }

    @Test
    @DisplayName("Preserves asset clientTag when resolving write provider and storage key on conditional version upload")
    void shouldPreserveClientTagOnConditionalVersionUpload() {
        String hash2 = sha256Hex(VALID_PNG_BYTES_2);
        String clientTag = "kiemlai/wiki/covers/great-ruler";

        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(ASSET_ID, hash2))
                .thenReturn(AuthoritativeDuplicateProbeResult.different(1));

        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, clientTag, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        RegisterMediaAssetVersionConditionalResult registerResult =
                RegisterMediaAssetVersionConditionalResult.versionCreated(ASSET_ID, VERSION_2_ID, 2, NOW);
        when(registerMediaAssetVersionUseCase.registerConditionalVersion(any(RegisterMediaAssetVersionCommand.class)))
                .thenReturn(registerResult);

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(VALID_PNG_BYTES_2),
                VALID_PNG_BYTES_2.length,
                "image/png",
                "cover_new.png"
        );

        UploadMediaAssetVersionConditionalResult result = useCase.execute(command);

        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.VERSION_CREATED);
        verify(mediaStorageRoutingService).resolveWriteProvider(MediaType.IMAGE, clientTag);
        verify(mediaStorageRoutingService).generateStorageKey(MediaType.IMAGE, clientTag, ASSET_ID, 2);
    }

    @Test
    @DisplayName("Conditional VIDEO version upload succeeds for valid video/mp4 when content differs")
    void shouldUploadConditionalVideoVersionWhenContentDiffers() {
        byte[] videoBytes = "simulated-video-v2-mp4-payload".getBytes(StandardCharsets.UTF_8);
        String hash = sha256Hex(videoBytes);

        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(ASSET_ID, hash))
                .thenReturn(AuthoritativeDuplicateProbeResult.different(1));

        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        RegisterMediaAssetVersionConditionalResult registerResult =
                RegisterMediaAssetVersionConditionalResult.versionCreated(ASSET_ID, VERSION_2_ID, 2, NOW);
        when(registerMediaAssetVersionUseCase.registerConditionalVersion(any(RegisterMediaAssetVersionCommand.class)))
                .thenReturn(registerResult);

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(videoBytes),
                videoBytes.length,
                "video/mp4",
                "episode1_v2.mp4"
        );

        UploadMediaAssetVersionConditionalResult result = useCase.execute(command);

        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.VERSION_CREATED);
        assertThat(result.versionNumber()).isEqualTo(2);
        verify(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), eq((long) videoBytes.length), eq(MimeType.of("video/mp4")));
        verify(rasterValidator, never()).validateAndPrepareStream(any(), anyLong(), any());
    }

    @Test
    @DisplayName("Conditional VIDEO version upload rejects non-mp4 MIME before storage write")
    void shouldRejectConditionalVideoVersionWhenMimeIsNotMp4() {
        MediaAsset videoAsset = MediaAsset.registerInitial(ASSET_ID, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(videoAsset));

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(new byte[16]),
                16L,
                "video/webm",
                "episode1.webm"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(com.universe.media.application.exceptions.UploadContentMimeMismatchException.class)
                .hasMessageContaining("Unsupported MIME type 'video/webm' for VIDEO");

        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
        verify(rasterValidator, never()).validateAndPrepareStream(any(), anyLong(), any());
        verify(registerMediaAssetVersionUseCase, never()).probeAuthoritativeDuplicate(any(), any());
    }

    @Test
    @DisplayName("Conditional VIDEO version upload rejects size exceeding 4 GiB before storage write")
    void shouldRejectConditionalVideoVersionWhenSizeExceeds4GiB() {
        MediaAsset videoAsset = MediaAsset.registerInitial(ASSET_ID, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(videoAsset));

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(new byte[16]),
                4_294_967_297L,
                "video/mp4",
                "huge_episode.mp4"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum allowed size of 4294967296 bytes (4 GiB)");

        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
        verify(rasterValidator, never()).validateAndPrepareStream(any(), anyLong(), any());
        verify(registerMediaAssetVersionUseCase, never()).probeAuthoritativeDuplicate(any(), any());
    }

    @Test
    @DisplayName("Conditional VIDEO version upload rejects image/png before storage write and never calls raster validator")
    void shouldRejectConditionalVideoVersionWithImagePng() {
        MediaAsset videoAsset = MediaAsset.registerInitial(ASSET_ID, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(videoAsset));

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),
                8L,
                "image/png",
                "v2.png"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(com.universe.media.application.exceptions.UploadContentMimeMismatchException.class)
                .hasMessageContaining("Unsupported MIME type 'image/png' for VIDEO");

        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
        verify(rasterValidator, never()).validateAndPrepareStream(any(), anyLong(), any());
        verify(registerMediaAssetVersionUseCase, never()).probeAuthoritativeDuplicate(any(), any());
    }

    @Test
    @DisplayName("Conditional VIDEO version upload rejects audio/mpeg before storage write and never calls raster validator")
    void shouldRejectConditionalVideoVersionWithAudioMpeg() {
        MediaAsset videoAsset = MediaAsset.registerInitial(ASSET_ID, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(videoAsset));

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(new byte[16]),
                16L,
                "audio/mpeg",
                "v2.mp3"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(com.universe.media.application.exceptions.UploadContentMimeMismatchException.class)
                .hasMessageContaining("Unsupported MIME type 'audio/mpeg' for VIDEO");

        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
        verify(rasterValidator, never()).validateAndPrepareStream(any(), anyLong(), any());
        verify(registerMediaAssetVersionUseCase, never()).probeAuthoritativeDuplicate(any(), any());
    }

    @Test
    @DisplayName("Conditional duplicate-content path with invalid VIDEO MIME rejects upfront and never probes duplicate")
    void shouldRejectConditionalDuplicateWhenMimeIsInvalidForVideoAsset() {
        MediaAsset videoAsset = MediaAsset.registerInitial(ASSET_ID, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(videoAsset));

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(new byte[16]),
                16L,
                "video/webm",
                "duplicate.webm"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(com.universe.media.application.exceptions.UploadContentMimeMismatchException.class)
                .hasMessageContaining("Unsupported MIME type 'video/webm' for VIDEO");

        verify(registerMediaAssetVersionUseCase, never()).probeAuthoritativeDuplicate(any(), any());
        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
        verify(rasterValidator, never()).validateAndPrepareStream(any(), anyLong(), any());
    }

    @Test
    @DisplayName("Valid VIDEO conditional duplicate with video/mp4 returns UNCHANGED with zero storage writes")
    void shouldReturnUnchangedForValidVideoDuplicate() {
        byte[] videoBytes = "sample-mp4-video-duplicate-bytes".getBytes(StandardCharsets.UTF_8);
        String hash = sha256Hex(videoBytes);

        MediaAsset videoAsset = MediaAsset.registerInitial(ASSET_ID, MediaType.VIDEO, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(videoAsset));
        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(ASSET_ID, hash))
                .thenReturn(AuthoritativeDuplicateProbeResult.duplicate(1));

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(videoBytes),
                videoBytes.length,
                "video/mp4",
                "duplicate.mp4"
        );

        UploadMediaAssetVersionConditionalResult result = useCase.execute(command);

        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.UNCHANGED);
        assertThat(result.versionNumber()).isEqualTo(1);
        verify(registerMediaAssetVersionUseCase).probeAuthoritativeDuplicate(ASSET_ID, hash);
        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
        verify(rasterValidator, never()).validateAndPrepareStream(any(), anyLong(), any());
    }

    @Test
    @DisplayName("Conditional upload on IMAGE asset rejects video/mp4 MIME before spooling and duplicate probe")
    void shouldRejectVideoMimeOnConditionalImageAssetUpload() {
        MediaAsset imageAsset = MediaAsset.registerInitial(ASSET_ID, MediaType.IMAGE, MediaVisibility.PUBLIC, NOW);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(imageAsset));

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                ASSET_ID,
                new ByteArrayInputStream(new byte[16]),
                16L,
                "video/mp4",
                "movie.mp4"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(com.universe.media.application.exceptions.UploadContentMimeMismatchException.class)
                .hasMessageContaining("Cannot upload video to non-VIDEO asset of type: IMAGE");

        verify(registerMediaAssetVersionUseCase, never()).probeAuthoritativeDuplicate(any(), any());
        verify(binaryStoragePort, never()).store(any(), any(), anyLong(), any());
        verify(rasterValidator, never()).validateAndPrepareStream(any(), anyLong(), any());
    }
}
