package com.universe.media.infrastructure.storage.r2;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class R2StorageAdapterTest {

    private static final String BUCKET = "test-r2-bucket";

    @TempDir
    private Path testTempDir;

    private S3Client s3Client;
    private R2StorageAdapter adapter;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    if (body != null && body.contentStreamProvider() != null) {
                        try (InputStream in = body.contentStreamProvider().newStream()) {
                            in.readAllBytes();
                        }
                    }
                    return PutObjectResponse.builder().build();
                });
        adapter = new R2StorageAdapter(BUCKET, s3Client, testTempDir);
    }

    private void assertNoStagingFilesRemain() throws IOException {
        try (Stream<Path> stream = Files.walk(testTempDir)) {
            long count = stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("r2-staging-") && name.endsWith(".tmp"))
                    .count();
            assertThat(count)
                    .withFailMessage("Expected no temporary staging files to remain, but found %d", count)
                    .isZero();
        }
    }

    @Test
    @DisplayName("providerId returns StorageProviderId of 'r2'")
    void shouldReturnR2ProviderId() {
        assertThat(adapter.providerId()).isEqualTo(StorageProviderId.of("r2"));
    }

    @Nested
    @DisplayName("Store Tests")
    class StoreTests {

        @Test
        @DisplayName("successful store sends If-None-Match: * with exact staged bytes, no duplicate header, and cleans staging file")
        void shouldStoreSuccessfullyWithConditionalHeader() throws IOException {
            StorageKey key = StorageKey.of("objects/test-uuid-1234");
            byte[] payload = "test payload data".getBytes(StandardCharsets.UTF_8);
            MimeType mimeType = MimeType.of("image/webp");

            AtomicBoolean stagingFileExistedDuringPut = new AtomicBoolean(false);
            AtomicReference<byte[]> stagedBytesDuringPut = new AtomicReference<>();

            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenAnswer(invocation -> {
                        RequestBody body = invocation.getArgument(1);
                        if (body != null && body.contentStreamProvider() != null) {
                            try (InputStream in = body.contentStreamProvider().newStream()) {
                                stagedBytesDuringPut.set(in.readAllBytes());
                            }
                        }
                        try (Stream<Path> stream = Files.walk(testTempDir)) {
                            stagingFileExistedDuringPut.set(
                                    stream.anyMatch(p -> p.getFileName().toString().startsWith("r2-staging-"))
                            );
                        }
                        return PutObjectResponse.builder().build();
                    });

            adapter.store(key, new ByteArrayInputStream(payload), payload.length, mimeType);

            ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));

            PutObjectRequest captured = requestCaptor.getValue();
            assertThat(captured.bucket()).isEqualTo(BUCKET);
            assertThat(captured.key()).isEqualTo("objects/test-uuid-1234");
            assertThat(captured.contentLength()).isEqualTo((long) payload.length);
            assertThat(captured.contentType()).isEqualTo("image/webp");

            // 1. successful store sends If-None-Match: *
            assertThat(captured.ifNoneMatch()).isEqualTo("*");

            // 2. no duplicate custom If-None-Match header is required
            captured.overrideConfiguration().ifPresent(cfg ->
                    assertThat(cfg.headers().get("If-None-Match")).isNullOrEmpty()
            );

            // 3. staged payload bytes equal original payload
            assertThat(stagedBytesDuringPut.get()).isEqualTo(payload);
            assertThat(stagingFileExistedDuringPut.get()).isTrue();

            // 11. temporary staging cleanup occurs on success
            assertNoStagingFilesRemain();

            // Invariant: never deleteObject on store
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        }

        @Test
        @DisplayName("short input fails before s3Client.putObject, never deletes remote, and cleans staging file")
        void shouldFailBeforePutObjectWhenStreamIsShorterThanDeclaredSize() throws IOException {
            StorageKey key = StorageKey.of("objects/short-stream");
            byte[] payload = "short".getBytes(StandardCharsets.UTF_8);
            long declaredSize = 100L;

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(payload), declaredSize, MimeType.of("image/jpeg"))
            ).isInstanceOf(StorageException.class)
                    .hasMessageContaining("Payload size mismatch");

            // 4. short input fails before s3Client.putObject()
            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));

            // 12. temporary staging cleanup occurs on validation failure
            assertNoStagingFilesRemain();
        }

        @Test
        @DisplayName("long input fails before s3Client.putObject, never deletes remote, and cleans staging file")
        void shouldFailBeforePutObjectWhenStreamIsLongerThanDeclaredSize() throws IOException {
            StorageKey key = StorageKey.of("objects/long-stream");
            byte[] payload = "this stream has extra bytes beyond declared".getBytes(StandardCharsets.UTF_8);
            long declaredSize = 5L;

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(payload), declaredSize, MimeType.of("image/jpeg"))
            ).isInstanceOf(StorageException.class)
                    .hasMessageContaining("Payload size exceeded");

            // 5. long input fails before s3Client.putObject()
            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));

            // 12. temporary staging cleanup occurs on validation failure
            assertNoStagingFilesRemain();
        }

        @Test
        @DisplayName("store does not close caller-owned InputStream")
        void shouldNotCloseCallerOwnedInputStream() throws IOException {
            StorageKey key = StorageKey.of("objects/stream-test");
            byte[] payload = "audio-bytes".getBytes(StandardCharsets.UTF_8);
            AtomicBoolean closed = new AtomicBoolean(false);

            InputStream callerStream = new FilterInputStream(new ByteArrayInputStream(payload)) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };

            adapter.store(key, callerStream, payload.length, MimeType.of("audio/mpeg"));

            // 6. caller-owned input stream is never closed
            assertThat(closed.get()).isFalse();
            assertNoStagingFilesRemain();
        }

        @Test
        @DisplayName("HTTP 412 maps to StorageObjectAlreadyExistsException, never invokes deleteObject, and cleans staging file")
        void shouldMapHttp412AndNeverInvokeDeleteObject() throws IOException {
            StorageKey key = StorageKey.of("objects/already-exists");
            byte[] payload = "data".getBytes(StandardCharsets.UTF_8);

            S3Exception preconditionFailedEx = (S3Exception) S3Exception.builder()
                    .statusCode(412)
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("PreconditionFailed").build())
                    .message("At least one precondition failed")
                    .build();

            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow(preconditionFailedEx);

            // 7. HTTP 412 maps to StorageObjectAlreadyExistsException
            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(payload), payload.length, MimeType.of("image/png"))
            ).isInstanceOf(StorageObjectAlreadyExistsException.class);

            // 8. HTTP 412 never invokes deleteObject()
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));

            // 13. temporary staging cleanup occurs on provider failure
            assertNoStagingFilesRemain();
        }

        @Test
        @DisplayName("HTTP 500 store failure maps to StorageException, never invokes deleteObject, and cleans staging file")
        void shouldMapHttp500AndNeverInvokeDeleteObject() throws IOException {
            StorageKey key = StorageKey.of("objects/error-500");
            byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);

            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow((S3Exception) S3Exception.builder().statusCode(500).message("Internal Server Error").build());

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(payload), payload.length, MimeType.of("application/pdf"))
            ).isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to store binary content");

            // 9. HTTP 500 store failure never invokes deleteObject()
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));

            // 13. temporary staging cleanup occurs on provider failure
            assertNoStagingFilesRemain();
        }

        @Test
        @DisplayName("SDK/network store failure maps to StorageException, never invokes deleteObject, and cleans staging file")
        void shouldMapSdkClientExceptionAndNeverInvokeDeleteObject() throws IOException {
            StorageKey key = StorageKey.of("objects/network-error");
            byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);

            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow(SdkClientException.create("Connection reset / network timeout"));

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(payload), payload.length, MimeType.of("application/pdf"))
            ).isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to store binary content");

            // 10. SDK/network store failure never invokes deleteObject()
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));

            // 13. temporary staging cleanup occurs on provider failure
            assertNoStagingFilesRemain();
        }

        @Test
        @DisplayName("store validates non-null parameters and non-negative size")
        void shouldValidateParameters() {
            StorageKey key = StorageKey.of("objects/valid-key");
            ByteArrayInputStream stream = new ByteArrayInputStream(new byte[0]);
            MimeType mime = MimeType.of("text/plain");

            assertThatThrownBy(() -> adapter.store(null, stream, 0, mime))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> adapter.store(key, null, 0, mime))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> adapter.store(key, stream, 0, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> adapter.store(key, stream, -1L, mime))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Open Tests")
    class OpenTests {

        @Test
        @DisplayName("open returns streaming content from S3 GetObject")
        void shouldReturnStreamingContent() throws IOException {
            StorageKey key = StorageKey.of("objects/read-key");
            byte[] expectedData = "stored binary payload".getBytes(StandardCharsets.UTF_8);

            GetObjectResponse getResponse = GetObjectResponse.builder().build();
            ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                    getResponse,
                    AbortableInputStream.create(new ByteArrayInputStream(expectedData))
            );

            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

            try (InputStream resultStream = adapter.open(key)) {
                byte[] actualData = resultStream.readAllBytes();
                assertThat(actualData).isEqualTo(expectedData);
            }

            ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(s3Client).getObject(requestCaptor.capture());
            assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
            assertThat(requestCaptor.getValue().key()).isEqualTo("objects/read-key");
        }

        @Test
        @DisplayName("open maps NoSuchKeyException to StorageObjectNotFoundException")
        void shouldMapNoSuchKeyToStorageObjectNotFoundException() {
            StorageKey key = StorageKey.of("objects/missing-key");

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().statusCode(404).message("The specified key does not exist.").build());

            assertThatThrownBy(() -> adapter.open(key))
                    .isInstanceOf(StorageObjectNotFoundException.class);
        }

        @Test
        @DisplayName("open maps S3 404 to StorageObjectNotFoundException")
        void shouldMapHttp404ToStorageObjectNotFoundException() {
            StorageKey key = StorageKey.of("objects/missing-404");

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow((S3Exception) S3Exception.builder().statusCode(404).message("Not Found").build());

            assertThatThrownBy(() -> adapter.open(key))
                    .isInstanceOf(StorageObjectNotFoundException.class);
        }

        @Test
        @DisplayName("open maps general S3/network failure to StorageException")
        void shouldMapGeneralErrorToStorageException() {
            StorageKey key = StorageKey.of("objects/network-error");

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(SdkClientException.create("Connection reset"));

            assertThatThrownBy(() -> adapter.open(key))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to open binary content");
        }
    }

    @Nested
    @DisplayName("OpenRange Tests")
    class OpenRangeTests {

        @Test
        @DisplayName("openRange constructs exact native Range header bytes=start-(start+length-1)")
        void shouldConstructExactRangeHeader() throws IOException {
            StorageKey key = StorageKey.of("objects/range-key");
            byte[] rangeData = "partial content".getBytes(StandardCharsets.UTF_8);

            GetObjectResponse getResponse = GetObjectResponse.builder().build();
            ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                    getResponse,
                    AbortableInputStream.create(new ByteArrayInputStream(rangeData))
            );

            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

            long startInclusive = 1024L;
            long length = 2048L;

            try (InputStream resultStream = adapter.openRange(key, startInclusive, length)) {
                byte[] actual = resultStream.readAllBytes();
                assertThat(actual).isEqualTo(rangeData);
            }

            ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(s3Client).getObject(requestCaptor.capture());

            GetObjectRequest captured = requestCaptor.getValue();
            assertThat(captured.bucket()).isEqualTo(BUCKET);
            assertThat(captured.key()).isEqualTo("objects/range-key");
            assertThat(captured.range()).isEqualTo("bytes=1024-3071");
        }

        @Test
        @DisplayName("openRange preserves argument validation")
        void shouldValidateRangeArguments() {
            StorageKey key = StorageKey.of("objects/range-valid-key");

            assertThatThrownBy(() -> adapter.openRange(null, 0, 10))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> adapter.openRange(key, -1L, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startInclusive cannot be negative");
            assertThatThrownBy(() -> adapter.openRange(key, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("length must be positive");
            assertThatThrownBy(() -> adapter.openRange(key, 0, -5L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("length must be positive");
        }

        @Test
        @DisplayName("openRange maps missing object to StorageObjectNotFoundException")
        void shouldMapMissingRangeToStorageObjectNotFoundException() {
            StorageKey key = StorageKey.of("objects/missing-range-key");

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().statusCode(404).message("Key not found").build());

            assertThatThrownBy(() -> adapter.openRange(key, 0, 50))
                    .isInstanceOf(StorageObjectNotFoundException.class);
        }

        @Test
        @DisplayName("openRange maps provider error to StorageException")
        void shouldMapProviderErrorToStorageException() {
            StorageKey key = StorageKey.of("objects/error-range-key");

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow((S3Exception) S3Exception.builder().statusCode(500).message("Internal Error").build());

            assertThatThrownBy(() -> adapter.openRange(key, 0, 50))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to open binary range");
        }
    }

    @Nested
    @DisplayName("Delete Tests")
    class DeleteTests {

        @Test
        @DisplayName("delete succeeds and sends DeleteObjectRequest")
        void shouldDeleteSuccessfully() {
            StorageKey key = StorageKey.of("objects/to-delete");

            adapter.delete(key);

            ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3Client).deleteObject(captor.capture());
            assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
            assertThat(captor.getValue().key()).isEqualTo("objects/to-delete");
        }

        @Test
        @DisplayName("delete missing object is idempotent and does not throw exception")
        void shouldBeIdempotentOnMissingKey() {
            StorageKey key = StorageKey.of("objects/non-existent");

            when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

            assertThatCode(() -> adapter.delete(key)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("delete 404 is idempotent and does not throw exception")
        void shouldBeIdempotentOnHttp404() {
            StorageKey key = StorageKey.of("objects/non-existent-404");

            when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                    .thenThrow((S3Exception) S3Exception.builder().statusCode(404).build());

            assertThatCode(() -> adapter.delete(key)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("delete maps provider error to StorageException")
        void shouldMapProviderErrorToStorageException() {
            StorageKey key = StorageKey.of("objects/delete-error");

            when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                    .thenThrow((S3Exception) S3Exception.builder().statusCode(403).message("Access Denied").build());

            assertThatThrownBy(() -> adapter.delete(key))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to delete binary content");
        }

        @Test
        @DisplayName("delete requires non-null key")
        void shouldRequireNonNullKey() {
            assertThatThrownBy(() -> adapter.delete(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
