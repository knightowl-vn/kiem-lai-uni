package com.universe.media.infrastructure.storage.b2;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.ports.storage.StoredBinaryObject;
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

class B2StorageAdapterTest {

    private static final String BUCKET = "test-b2-bucket";

    @TempDir
    private Path testTempDir;

    private S3Client s3Client;
    private B2StorageAdapter adapter;

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
        adapter = new B2StorageAdapter(BUCKET, s3Client, testTempDir);
    }

    private void assertNoStagingFilesRemain() throws IOException {
        try (Stream<Path> stream = Files.walk(testTempDir)) {
            long count = stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("b2-staging-") && name.endsWith(".tmp"))
                    .count();
            assertThat(count)
                    .withFailMessage("Expected no temporary staging files to remain, but found %d", count)
                    .isZero();
        }
    }

    @Test
    @DisplayName("providerId returns StorageProviderId of 'b2'")
    void shouldReturnB2ProviderId() {
        assertThat(adapter.providerId()).isEqualTo(StorageProviderId.of("b2"));
    }

    @Nested
    @DisplayName("Store Tests")
    class StoreTests {

        @Test
        @DisplayName("successful store sends If-None-Match: * with exact staged bytes and cleans staging file")
        void shouldStoreSuccessfullyWithConditionalHeader() throws IOException {
            StorageKey key = StorageKey.of("videos/test-video-1234");
            byte[] payload = "test video binary payload data".getBytes(StandardCharsets.UTF_8);
            MimeType mimeType = MimeType.of("video/mp4");

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
                                    stream.anyMatch(p -> p.getFileName().toString().startsWith("b2-staging-"))
                            );
                        }
                        return PutObjectResponse.builder().build();
                    });

            StoredBinaryObject stored =
                    adapter.store(key, new ByteArrayInputStream(payload), payload.length, mimeType);

            assertThat(stored.location().providerId()).isEqualTo(StorageProviderId.of("b2"));
            assertThat(stored.location().key()).isEqualTo(key);
            assertThat(stored.publicUrl()).isNull();

            ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));

            PutObjectRequest captured = requestCaptor.getValue();
            assertThat(captured.bucket()).isEqualTo(BUCKET);
            assertThat(captured.key()).isEqualTo("videos/test-video-1234");
            assertThat(captured.contentLength()).isEqualTo((long) payload.length);
            assertThat(captured.contentType()).isEqualTo("video/mp4");

            // 1. successful store sends If-None-Match: *
            assertThat(captured.ifNoneMatch()).isEqualTo("*");

            // 2. staged payload bytes equal original payload
            assertThat(stagedBytesDuringPut.get()).isEqualTo(payload);
            assertThat(stagingFileExistedDuringPut.get()).isTrue();

            // 3. temporary staging cleanup occurs on success
            assertNoStagingFilesRemain();

            // Invariant: never deleteObject on store
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        }

        @Test
        @DisplayName("short input fails before s3Client.putObject, never deletes remote, and cleans staging file")
        void shouldFailBeforePutObjectWhenStreamIsShorterThanDeclaredSize() throws IOException {
            StorageKey key = StorageKey.of("videos/short-stream");
            byte[] payload = "short".getBytes(StandardCharsets.UTF_8);
            long declaredSize = 100L;

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(payload), declaredSize, MimeType.of("video/mp4"))
            ).isInstanceOf(StorageException.class)
                    .hasMessageContaining("Payload size mismatch");

            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));

            assertNoStagingFilesRemain();
        }

        @Test
        @DisplayName("long input fails before s3Client.putObject, never deletes remote, and cleans staging file")
        void shouldFailBeforePutObjectWhenStreamIsLongerThanDeclaredSize() throws IOException {
            StorageKey key = StorageKey.of("videos/long-stream");
            byte[] payload = "this stream has extra bytes beyond declared".getBytes(StandardCharsets.UTF_8);
            long declaredSize = 5L;

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(payload), declaredSize, MimeType.of("video/mp4"))
            ).isInstanceOf(StorageException.class)
                    .hasMessageContaining("Payload size exceeded");

            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));

            assertNoStagingFilesRemain();
        }

        @Test
        @DisplayName("store does not close caller-owned InputStream")
        void shouldNotCloseCallerOwnedInputStream() throws IOException {
            StorageKey key = StorageKey.of("videos/stream-test");
            byte[] payload = "video-bytes".getBytes(StandardCharsets.UTF_8);
            AtomicBoolean closed = new AtomicBoolean(false);

            InputStream callerStream = new FilterInputStream(new ByteArrayInputStream(payload)) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };

            adapter.store(key, callerStream, payload.length, MimeType.of("video/mp4"));

            assertThat(closed.get()).isFalse();
            assertNoStagingFilesRemain();
        }

        @Test
        @DisplayName("HTTP 412 maps to StorageObjectAlreadyExistsException, never invokes deleteObject, and cleans staging file")
        void shouldMapHttp412AndNeverInvokeDeleteObject() throws IOException {
            StorageKey key = StorageKey.of("videos/already-exists");
            byte[] payload = "data".getBytes(StandardCharsets.UTF_8);

            S3Exception preconditionFailedEx = (S3Exception) S3Exception.builder()
                    .statusCode(412)
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("PreconditionFailed").build())
                    .message("At least one precondition failed")
                    .build();

            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow(preconditionFailedEx);

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(payload), payload.length, MimeType.of("video/mp4"))
            ).isInstanceOf(StorageObjectAlreadyExistsException.class);

            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
            assertNoStagingFilesRemain();
        }

        @Test
        @DisplayName("HTTP 500 store failure maps to StorageException, never invokes deleteObject, and cleans staging file")
        void shouldMapHttp500AndNeverInvokeDeleteObject() throws IOException {
            StorageKey key = StorageKey.of("videos/error-500");
            byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);

            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow((S3Exception) S3Exception.builder().statusCode(500).message("Internal Server Error").build());

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(payload), payload.length, MimeType.of("video/mp4"))
            ).isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to store binary content");

            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
            assertNoStagingFilesRemain();
        }

        @Test
        @DisplayName("SDK/network store failure maps to StorageException, never invokes deleteObject, and cleans staging file")
        void shouldMapSdkClientExceptionAndNeverInvokeDeleteObject() throws IOException {
            StorageKey key = StorageKey.of("videos/network-error");
            byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);

            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow(SdkClientException.create("Connection reset / network timeout"));

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(payload), payload.length, MimeType.of("video/mp4"))
            ).isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to store binary content");

            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
            assertNoStagingFilesRemain();
        }

        @Test
        @DisplayName("store validates non-null parameters and non-negative size")
        void shouldValidateParameters() {
            StorageKey key = StorageKey.of("videos/valid-key");
            ByteArrayInputStream stream = new ByteArrayInputStream(new byte[0]);
            MimeType mime = MimeType.of("video/mp4");

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
            StorageKey key = StorageKey.of("videos/read-key");
            byte[] expectedData = "stored binary video payload".getBytes(StandardCharsets.UTF_8);

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
            assertThat(requestCaptor.getValue().key()).isEqualTo("videos/read-key");
        }

        @Test
        @DisplayName("open maps NoSuchKeyException to StorageObjectNotFoundException")
        void shouldMapNoSuchKeyToStorageObjectNotFoundException() {
            StorageKey key = StorageKey.of("videos/missing-key");

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().statusCode(404).message("The specified key does not exist.").build());

            assertThatThrownBy(() -> adapter.open(key))
                    .isInstanceOf(StorageObjectNotFoundException.class);
        }

        @Test
        @DisplayName("open maps S3 404 to StorageObjectNotFoundException")
        void shouldMapHttp404ToStorageObjectNotFoundException() {
            StorageKey key = StorageKey.of("videos/missing-404");

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow((S3Exception) S3Exception.builder().statusCode(404).message("Not Found").build());

            assertThatThrownBy(() -> adapter.open(key))
                    .isInstanceOf(StorageObjectNotFoundException.class);
        }

        @Test
        @DisplayName("open maps general S3/network failure to StorageException")
        void shouldMapGeneralErrorToStorageException() {
            StorageKey key = StorageKey.of("videos/network-error");

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(SdkClientException.create("Connection reset"));

            assertThatThrownBy(() -> adapter.open(key))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to open binary content");
        }

        @Test
        @DisplayName("open validates non-null key")
        void shouldValidateNonNullKey() {
            assertThatThrownBy(() -> adapter.open(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("OpenRange Tests")
    class OpenRangeTests {

        @Test
        @DisplayName("openRange constructs exact native Range header bytes=start-(start+length-1)")
        void shouldConstructExactRangeHeader() throws IOException {
            StorageKey key = StorageKey.of("videos/range-key");
            byte[] rangeData = "partial video chunk".getBytes(StandardCharsets.UTF_8);

            GetObjectResponse getResponse = GetObjectResponse.builder().build();
            ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                    getResponse,
                    AbortableInputStream.create(new ByteArrayInputStream(rangeData))
            );

            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

            long startInclusive = 1048576L;
            long length = 2097152L;

            try (InputStream resultStream = adapter.openRange(key, startInclusive, length)) {
                byte[] actual = resultStream.readAllBytes();
                assertThat(actual).isEqualTo(rangeData);
            }

            ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(s3Client).getObject(requestCaptor.capture());

            GetObjectRequest captured = requestCaptor.getValue();
            assertThat(captured.bucket()).isEqualTo(BUCKET);
            assertThat(captured.key()).isEqualTo("videos/range-key");
            // bytes=1048576-(1048576 + 2097152 - 1) = bytes=1048576-3145727
            assertThat(captured.range()).isEqualTo("bytes=1048576-3145727");
        }

        @Test
        @DisplayName("openRange preserves argument validation")
        void shouldValidateRangeArguments() {
            StorageKey key = StorageKey.of("videos/range-valid-key");

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
            StorageKey key = StorageKey.of("videos/missing-range-key");

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().statusCode(404).message("Key not found").build());

            assertThatThrownBy(() -> adapter.openRange(key, 0, 50))
                    .isInstanceOf(StorageObjectNotFoundException.class);
        }

        @Test
        @DisplayName("openRange maps HTTP 404 to StorageObjectNotFoundException")
        void shouldMapHttp404RangeToStorageObjectNotFoundException() {
            StorageKey key = StorageKey.of("videos/missing-404-range-key");

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow((S3Exception) S3Exception.builder().statusCode(404).message("Not Found").build());

            assertThatThrownBy(() -> adapter.openRange(key, 0, 50))
                    .isInstanceOf(StorageObjectNotFoundException.class);
        }

        @Test
        @DisplayName("openRange performs repeated independent range reads against the same key without stale state")
        void shouldPerformRepeatedIndependentRangeReadsOnSameKey() throws IOException {
            StorageKey key = StorageKey.of("videos/repeated-seek-video.mp4");
            byte[] chunk1 = "chunk-0-99".getBytes(StandardCharsets.UTF_8);
            byte[] chunk2 = "chunk-500-699".getBytes(StandardCharsets.UTF_8);
            byte[] chunk3 = "chunk-1024-2047".getBytes(StandardCharsets.UTF_8);

            ResponseInputStream<GetObjectResponse> resp1 = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(chunk1))
            );
            ResponseInputStream<GetObjectResponse> resp2 = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(chunk2))
            );
            ResponseInputStream<GetObjectResponse> resp3 = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(chunk3))
            );

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(resp1, resp2, resp3);

            // First seek-style range
            try (InputStream s1 = adapter.openRange(key, 0, 100)) {
                assertThat(s1.readAllBytes()).isEqualTo(chunk1);
            }

            // Second seek-style range
            try (InputStream s2 = adapter.openRange(key, 500, 200)) {
                assertThat(s2.readAllBytes()).isEqualTo(chunk2);
            }

            // Third seek-style range
            try (InputStream s3 = adapter.openRange(key, 1024, 1024)) {
                assertThat(s3.readAllBytes()).isEqualTo(chunk3);
            }

            ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(s3Client, org.mockito.Mockito.times(3)).getObject(captor.capture());

            java.util.List<GetObjectRequest> requests = captor.getAllValues();
            assertThat(requests.get(0).range()).isEqualTo("bytes=0-99");
            assertThat(requests.get(1).range()).isEqualTo("bytes=500-699");
            assertThat(requests.get(2).range()).isEqualTo("bytes=1024-2047");
        }

        @Test
        @DisplayName("closing caller-owned openRange stream closes underlying provider response stream")
        void shouldCloseUnderlyingResponseStreamWhenCallerCloses() throws IOException {
            StorageKey key = StorageKey.of("videos/cleanup-test.mp4");
            AtomicBoolean underlyingClosed = new AtomicBoolean(false);

            InputStream trackedStream = new FilterInputStream(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8))) {
                @Override
                public void close() throws IOException {
                    underlyingClosed.set(true);
                    super.close();
                }
            };

            ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    AbortableInputStream.create(trackedStream)
            );

            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

            InputStream callerStream = adapter.openRange(key, 0, 4);
            assertThat(underlyingClosed.get()).isFalse();

            callerStream.close();
            assertThat(underlyingClosed.get()).isTrue();
        }

        @Test
        @DisplayName("openRange maps provider error to StorageException")
        void shouldMapProviderErrorToStorageException() {
            StorageKey key = StorageKey.of("videos/error-range-key");

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
            StorageKey key = StorageKey.of("videos/to-delete");

            adapter.delete(key);

            ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3Client).deleteObject(captor.capture());
            assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
            assertThat(captor.getValue().key()).isEqualTo("videos/to-delete");
        }

        @Test
        @DisplayName("delete missing object is idempotent and does not throw exception")
        void shouldBeIdempotentOnMissingKey() {
            StorageKey key = StorageKey.of("videos/non-existent");

            when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

            assertThatCode(() -> adapter.delete(key)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("delete 404 is idempotent and does not throw exception")
        void shouldBeIdempotentOnHttp404() {
            StorageKey key = StorageKey.of("videos/non-existent-404");

            when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                    .thenThrow((S3Exception) S3Exception.builder().statusCode(404).build());

            assertThatCode(() -> adapter.delete(key)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("delete maps provider error to StorageException")
        void shouldMapProviderErrorToStorageException() {
            StorageKey key = StorageKey.of("videos/delete-error");

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

    @Nested
    @DisplayName("Lifecycle Close Tests")
    class CloseTests {

        @Test
        @DisplayName("close does not close unowned S3Client")
        void shouldNotCloseUnownedClient() {
            adapter.close();
            verify(s3Client, never()).close();
        }
    }
}
