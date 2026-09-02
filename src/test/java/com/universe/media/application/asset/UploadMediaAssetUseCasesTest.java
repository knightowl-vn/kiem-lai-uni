package com.universe.media.application.asset;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadMediaAssetUseCasesTest {

    @Mock
    private BinaryStoragePort binaryStoragePort;

    @Mock
    private RegisterMediaAssetUseCase registerMediaAssetUseCase;

    @Mock
    private RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase;

    private UploadMediaAssetUseCase uploadMediaAssetUseCase;
    private UploadMediaAssetVersionUseCase uploadMediaAssetVersionUseCase;

    private static final StorageProviderId LOCAL_PROVIDER =
            StorageProviderId.of("local");

    @BeforeEach
    void setUp() {
        uploadMediaAssetUseCase = new UploadMediaAssetUseCase(
                binaryStoragePort,
                registerMediaAssetUseCase
        );
        uploadMediaAssetVersionUseCase = new UploadMediaAssetVersionUseCase(
                binaryStoragePort,
                registerMediaAssetVersionUseCase
        );
    }

    @Nested
    @DisplayName("Upload Initial Media Asset Tests")
    class UploadInitialAssetTests {

        @Test
        @DisplayName("successful upload streams binary, computes SHA-256, registers asset, and returns result")
        void shouldUploadInitialAssetSuccessfully() throws Exception {
            when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);

            byte[] data = "Hello, Universe Media!".getBytes(StandardCharsets.UTF_8);
            String expectedSha256 = computeSha256(data);

            doAnswer(invocation -> {
                InputStream in = invocation.getArgument(1);
                in.readAllBytes();
                return null;
            }).when(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), eq((long) data.length), any(MimeType.class));

            UUID expectedAssetId = UUID.randomUUID();
            UUID expectedVersionId = UUID.randomUUID();
            Instant now = Instant.parse("2026-09-02T16:00:00Z");

            when(registerMediaAssetUseCase.execute(any(RegisterMediaAssetCommand.class)))
                    .thenReturn(new RegisterMediaAssetResult(
                            expectedAssetId,
                            expectedVersionId,
                            1,
                            MediaType.IMAGE,
                            MediaVisibility.PUBLIC,
                            MediaAssetStatus.ACTIVE,
                            now
                    ));

            UploadMediaAssetCommand command = new UploadMediaAssetCommand(
                    new ByteArrayInputStream(data),
                    data.length,
                    "image/webp",
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    "universe.webp"
            );

            UploadMediaAssetResult result = uploadMediaAssetUseCase.execute(command);

            assertThat(result.assetId()).isEqualTo(expectedAssetId);
            assertThat(result.versionId()).isEqualTo(expectedVersionId);
            assertThat(result.versionNumber()).isEqualTo(1);
            assertThat(result.mediaType()).isEqualTo(MediaType.IMAGE);
            assertThat(result.visibility()).isEqualTo(MediaVisibility.PUBLIC);
            assertThat(result.status()).isEqualTo(MediaAssetStatus.ACTIVE);
            assertThat(result.createdAt()).isEqualTo(now);

            ArgumentCaptor<StorageKey> keyCaptor = ArgumentCaptor.forClass(StorageKey.class);
            verify(binaryStoragePort).store(keyCaptor.capture(), any(InputStream.class), eq((long) data.length), eq(MimeType.of("image/webp")));
            assertThat(keyCaptor.getValue().value()).startsWith("objects/");

            ArgumentCaptor<RegisterMediaAssetCommand> registerCommandCaptor =
                    ArgumentCaptor.forClass(RegisterMediaAssetCommand.class);
            verify(registerMediaAssetUseCase).execute(registerCommandCaptor.capture());
            RegisterMediaAssetCommand captured = registerCommandCaptor.getValue();
            assertThat(captured.storageProviderId()).isEqualTo("local");
            assertThat(captured.storageKey()).isEqualTo(keyCaptor.getValue().value());
            assertThat(captured.contentHash()).isEqualTo(expectedSha256);
            assertThat(captured.publicUrl()).isNull();
            assertThat(captured.sizeBytes()).isEqualTo(data.length);
            assertThat(captured.originalFilename()).isEqualTo("universe.webp");
        }

        @Test
        @DisplayName("storage failure aborts flow and never calls metadata registration")
        void shouldAbortWhenStorageFails() {
            when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);

            byte[] data = "sample data".getBytes(StandardCharsets.UTF_8);
            doThrow(new StorageException("Disk full"))
                    .when(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), anyLong(), any(MimeType.class));

            UploadMediaAssetCommand command = new UploadMediaAssetCommand(
                    new ByteArrayInputStream(data),
                    data.length,
                    "image/webp",
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    "test.webp"
            );

            assertThatThrownBy(() -> uploadMediaAssetUseCase.execute(command))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Disk full");

            verify(registerMediaAssetUseCase, never()).execute(any());
            verify(binaryStoragePort, never()).delete(any());
        }

        @Test
        @DisplayName("metadata registration failure triggers storage compensation delete with exact stored key")
        void shouldCompensateStorageWhenMetadataRegistrationFails() throws IOException {
            when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);

            byte[] data = "payload data".getBytes(StandardCharsets.UTF_8);
            doAnswer(invocation -> {
                InputStream in = invocation.getArgument(1);
                in.readAllBytes();
                return null;
            }).when(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), eq((long) data.length), any(MimeType.class));

            RuntimeException primaryException = new RuntimeException("Database constraint violation");
            when(registerMediaAssetUseCase.execute(any(RegisterMediaAssetCommand.class)))
                    .thenThrow(primaryException);

            UploadMediaAssetCommand command = new UploadMediaAssetCommand(
                    new ByteArrayInputStream(data),
                    data.length,
                    "image/webp",
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    "test.webp"
            );

            assertThatThrownBy(() -> uploadMediaAssetUseCase.execute(command))
                    .isSameAs(primaryException);

            ArgumentCaptor<StorageKey> storedKeyCaptor = ArgumentCaptor.forClass(StorageKey.class);
            verify(binaryStoragePort).store(storedKeyCaptor.capture(), any(InputStream.class), eq((long) data.length), any(MimeType.class));

            ArgumentCaptor<StorageKey> deletedKeyCaptor = ArgumentCaptor.forClass(StorageKey.class);
            verify(binaryStoragePort).delete(deletedKeyCaptor.capture());
            assertThat(deletedKeyCaptor.getValue()).isEqualTo(storedKeyCaptor.getValue());
            assertThat(deletedKeyCaptor.getValue().value()).startsWith("objects/");
        }

        @Test
        @DisplayName("cleanup failure suppresses cleanup exception and preserves primary exception")
        void shouldPreservePrimaryExceptionWhenCompensationAlsoFails() throws IOException {
            when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);

            byte[] data = "payload data".getBytes(StandardCharsets.UTF_8);
            doAnswer(invocation -> {
                InputStream in = invocation.getArgument(1);
                in.readAllBytes();
                return null;
            }).when(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), eq((long) data.length), any(MimeType.class));

            RuntimeException primaryException = new RuntimeException("Primary DB failure");
            when(registerMediaAssetUseCase.execute(any(RegisterMediaAssetCommand.class)))
                    .thenThrow(primaryException);

            StorageException cleanupException = new StorageException("Cleanup delete failed");
            doThrow(cleanupException).when(binaryStoragePort).delete(any(StorageKey.class));

            UploadMediaAssetCommand command = new UploadMediaAssetCommand(
                    new ByteArrayInputStream(data),
                    data.length,
                    "image/webp",
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    "test.webp"
            );

            assertThatThrownBy(() -> uploadMediaAssetUseCase.execute(command))
                    .isSameAs(primaryException)
                    .satisfies(thrown -> {
                        assertThat(thrown.getSuppressed()).contains(cleanupException);
                    });

            ArgumentCaptor<StorageKey> storedKeyCaptor = ArgumentCaptor.forClass(StorageKey.class);
            verify(binaryStoragePort).store(storedKeyCaptor.capture(), any(InputStream.class), eq((long) data.length), any(MimeType.class));

            ArgumentCaptor<StorageKey> deletedKeyCaptor = ArgumentCaptor.forClass(StorageKey.class);
            verify(binaryStoragePort).delete(deletedKeyCaptor.capture());
            assertThat(deletedKeyCaptor.getValue()).isEqualTo(storedKeyCaptor.getValue());
        }

        @Test
        @DisplayName("caller InputStream is NOT closed by upload orchestration")
        void shouldNotCloseCallerInputStream() throws IOException {
            when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);

            byte[] data = "stream data".getBytes(StandardCharsets.UTF_8);
            doAnswer(invocation -> {
                InputStream in = invocation.getArgument(1);
                in.readAllBytes();
                return null;
            }).when(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), eq((long) data.length), any(MimeType.class));

            when(registerMediaAssetUseCase.execute(any(RegisterMediaAssetCommand.class)))
                    .thenReturn(new RegisterMediaAssetResult(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            1,
                            MediaType.IMAGE,
                            MediaVisibility.PUBLIC,
                            MediaAssetStatus.ACTIVE,
                            Instant.now()
                    ));

            AtomicBoolean closed = new AtomicBoolean(false);
            InputStream trackableStream = new FilterInputStream(new ByteArrayInputStream(data)) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };

            UploadMediaAssetCommand command = new UploadMediaAssetCommand(
                    trackableStream,
                    data.length,
                    "image/webp",
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    "stream.webp"
            );

            uploadMediaAssetUseCase.execute(command);

            assertThat(closed.get()).isFalse();
        }

        @ParameterizedTest(name = "UploadMediaAssetCommand rejects non-positive size: {0}")
        @ValueSource(longs = {0L, -1L, -100L})
        void shouldRejectNonPositiveSizeInUploadMediaAssetCommand(long invalidSize) {
            assertThatThrownBy(() -> new UploadMediaAssetCommand(
                    new ByteArrayInputStream(new byte[1]),
                    invalidSize,
                    "image/webp",
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    "file.webp"
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sizeBytes must be positive");
        }
    }

    @Nested
    @DisplayName("Upload Media Asset Version Tests")
    class UploadVersionTests {

        @Test
        @DisplayName("successful version upload streams binary, hashes, and registers new version")
        void shouldUploadVersionSuccessfully() throws Exception {
            when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);

            byte[] data = "Version 2 binary data".getBytes(StandardCharsets.UTF_8);
            String expectedSha256 = computeSha256(data);

            doAnswer(invocation -> {
                InputStream in = invocation.getArgument(1);
                in.readAllBytes();
                return null;
            }).when(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), eq((long) data.length), any(MimeType.class));

            UUID assetId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            Instant now = Instant.parse("2026-09-02T16:30:00Z");

            when(registerMediaAssetVersionUseCase.execute(any(RegisterMediaAssetVersionCommand.class)))
                    .thenReturn(new RegisterMediaAssetVersionResult(
                            assetId,
                            versionId,
                            2,
                            now
                    ));

            UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                    assetId,
                    new ByteArrayInputStream(data),
                    data.length,
                    "image/webp",
                    "version2.webp"
            );

            UploadMediaAssetVersionResult result = uploadMediaAssetVersionUseCase.execute(command);

            assertThat(result.assetId()).isEqualTo(assetId);
            assertThat(result.versionId()).isEqualTo(versionId);
            assertThat(result.versionNumber()).isEqualTo(2);
            assertThat(result.registeredAt()).isEqualTo(now);

            ArgumentCaptor<RegisterMediaAssetVersionCommand> captor =
                    ArgumentCaptor.forClass(RegisterMediaAssetVersionCommand.class);
            verify(registerMediaAssetVersionUseCase).execute(captor.capture());
            RegisterMediaAssetVersionCommand captured = captor.getValue();
            assertThat(captured.assetId()).isEqualTo(assetId);
            assertThat(captured.storageProviderId()).isEqualTo("local");
            assertThat(captured.contentHash()).isEqualTo(expectedSha256);
            assertThat(captured.publicUrl()).isNull();
            assertThat(captured.sizeBytes()).isEqualTo(data.length);
        }

        @Test
        @DisplayName("version metadata failure triggers storage compensation delete with exact stored key")
        void shouldCompensateStorageWhenVersionMetadataRegistrationFails() throws IOException {
            when(binaryStoragePort.providerId()).thenReturn(LOCAL_PROVIDER);

            byte[] data = "version payload".getBytes(StandardCharsets.UTF_8);
            doAnswer(invocation -> {
                InputStream in = invocation.getArgument(1);
                in.readAllBytes();
                return null;
            }).when(binaryStoragePort).store(any(StorageKey.class), any(InputStream.class), eq((long) data.length), any(MimeType.class));

            RuntimeException primaryException = new RuntimeException("Asset is not active");
            when(registerMediaAssetVersionUseCase.execute(any(RegisterMediaAssetVersionCommand.class)))
                    .thenThrow(primaryException);

            UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                    UUID.randomUUID(),
                    new ByteArrayInputStream(data),
                    data.length,
                    "image/webp",
                    "v2.webp"
            );

            assertThatThrownBy(() -> uploadMediaAssetVersionUseCase.execute(command))
                    .isSameAs(primaryException);

            ArgumentCaptor<StorageKey> storedKeyCaptor = ArgumentCaptor.forClass(StorageKey.class);
            verify(binaryStoragePort).store(storedKeyCaptor.capture(), any(InputStream.class), eq((long) data.length), any(MimeType.class));

            ArgumentCaptor<StorageKey> deletedKeyCaptor = ArgumentCaptor.forClass(StorageKey.class);
            verify(binaryStoragePort).delete(deletedKeyCaptor.capture());
            assertThat(deletedKeyCaptor.getValue()).isEqualTo(storedKeyCaptor.getValue());
            assertThat(deletedKeyCaptor.getValue().value()).startsWith("objects/");
        }

        @ParameterizedTest(name = "UploadMediaAssetVersionCommand rejects non-positive size: {0}")
        @ValueSource(longs = {0L, -1L, -100L})
        void shouldRejectNonPositiveSizeInUploadMediaAssetVersionCommand(long invalidSize) {
            assertThatThrownBy(() -> new UploadMediaAssetVersionCommand(
                    UUID.randomUUID(),
                    new ByteArrayInputStream(new byte[1]),
                    invalidSize,
                    "image/webp",
                    "file.webp"
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sizeBytes must be positive");
        }
    }

    private String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
