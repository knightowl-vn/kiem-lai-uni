package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.contracts.dto.MediaVersionUploadOutcome;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    private BinaryStoragePort binaryStoragePort;

    @Mock
    private RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase;

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
        RasterContentSignatureValidator validator = new RasterContentSignatureValidator();
        useCase = new UploadMediaAssetVersionConditionalUseCase(
                binaryStoragePort,
                validator,
                registerMediaAssetVersionUseCase
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
        when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);

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
        when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);

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
        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(eq(ASSET_ID), any()))
                .thenThrow(new MediaAssetNotFoundException(ASSET_ID));

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
        when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);

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
        when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);

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
    @DisplayName("Storage compensation deletes stored binary when registerConditionalVersion throws RuntimeException")
    void shouldCompensateStorageWhenRegistrationFails() {
        String hash2 = sha256Hex(VALID_PNG_BYTES_2);

        when(registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(ASSET_ID, hash2))
                .thenReturn(AuthoritativeDuplicateProbeResult.different(1));
        when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);

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
}
