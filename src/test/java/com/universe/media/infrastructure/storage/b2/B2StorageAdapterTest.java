package com.universe.media.infrastructure.storage.b2;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.ports.storage.MultipartCompletedObject;
import com.universe.media.application.ports.storage.MultipartObjectMetadata;
import com.universe.media.application.ports.storage.MultipartPartETag;
import com.universe.media.application.ports.storage.MultipartUploadedPart;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
    private S3Presigner s3Presigner;
    private B2StorageAdapter adapter;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        s3Presigner = mock(S3Presigner.class);
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
        adapter = new B2StorageAdapter(BUCKET, s3Client, s3Presigner, testTempDir);
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
        @DisplayName("HTTP 501 Not Implemented on store maps to StorageException rather than being misclassified")
        void shouldPropagateHttp501OnStoreAsStorageException() throws IOException {
            StorageKey key = StorageKey.of("videos/501-unsupported");
            byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);

            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow((S3Exception) S3Exception.builder().statusCode(501).message("Not Implemented").build());

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(payload), payload.length, MimeType.of("video/mp4"))
            ).isInstanceOf(StorageException.class)
                    .isNotInstanceOf(StorageObjectAlreadyExistsException.class)
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
    @DisplayName("Multipart Storage Tests")
    class MultipartTests {

        @Nested
        @DisplayName("Initiate Multipart Upload Tests")
        class InitiateTests {

            @Test
            @DisplayName("initiate sends CreateMultipartUploadRequest and returns uploadId")
            void shouldInitiateMultipartUploadSuccessfully() {
                StorageKey key = StorageKey.of("videos/large-mp4-video.mp4");
                MimeType mimeType = MimeType.of("video/mp4");

                when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                        .thenReturn(CreateMultipartUploadResponse.builder().uploadId("b2-upload-id-98765").build());

                String uploadId = adapter.initiateMultipartUpload(key, mimeType);

                assertThat(uploadId).isEqualTo("b2-upload-id-98765");

                ArgumentCaptor<CreateMultipartUploadRequest> captor = ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
                verify(s3Client).createMultipartUpload(captor.capture());

                CreateMultipartUploadRequest captured = captor.getValue();
                assertThat(captured.bucket()).isEqualTo(BUCKET);
                assertThat(captured.key()).isEqualTo("videos/large-mp4-video.mp4");
                assertThat(captured.contentType()).isEqualTo("video/mp4");
            }

            @Test
            @DisplayName("initiate maps S3 error to StorageException")
            void shouldMapInitiateErrorToStorageException() {
                StorageKey key = StorageKey.of("videos/err.mp4");
                MimeType mime = MimeType.of("video/mp4");

                when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder().statusCode(500).message("B2 Error").build());

                assertThatThrownBy(() -> adapter.initiateMultipartUpload(key, mime))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("Failed to initiate multipart upload");
            }

            @Test
            @DisplayName("initiate with metadata forwards bound metadata in CreateMultipartUploadRequest")
            void shouldInitiateMultipartUploadWithMetadata() {
                StorageKey key = StorageKey.of("videos/meta-video.mp4");
                MimeType mimeType = MimeType.of("video/mp4");
                java.util.Map<String, String> metadata = java.util.Map.of(
                        "session-id", "s-123",
                        "allocation-id", "a-456"
                );

                when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                        .thenReturn(CreateMultipartUploadResponse.builder().uploadId("b2-meta-upload-id").build());

                String uploadId = adapter.initiateMultipartUpload(key, mimeType, metadata);

                assertThat(uploadId).isEqualTo("b2-meta-upload-id");

                ArgumentCaptor<CreateMultipartUploadRequest> captor = ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
                verify(s3Client).createMultipartUpload(captor.capture());

                CreateMultipartUploadRequest captured = captor.getValue();
                assertThat(captured.bucket()).isEqualTo(BUCKET);
                assertThat(captured.key()).isEqualTo("videos/meta-video.mp4");
                assertThat(captured.contentType()).isEqualTo("video/mp4");
                assertThat(captured.metadata()).containsEntry("session-id", "s-123");
                assertThat(captured.metadata()).containsEntry("allocation-id", "a-456");
            }

            @Test
            @DisplayName("initiate validates non-null arguments")
            void shouldValidateInitiateArguments() {
                StorageKey key = StorageKey.of("videos/k.mp4");
                MimeType mime = MimeType.of("video/mp4");

                assertThatThrownBy(() -> adapter.initiateMultipartUpload(null, mime))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.initiateMultipartUpload(key, null))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.initiateMultipartUpload(key, mime, null))
                        .isInstanceOf(NullPointerException.class);
            }
        }

        @Nested
        @DisplayName("Generate Presigned Part Upload URL Tests")
        class PresignPartUrlTests {

            @Test
            @DisplayName("generatePresignedPartUploadUrl returns presigned URL for part upload")
            void shouldGeneratePresignedPartUploadUrlSuccessfully() throws Exception {
                StorageKey key = StorageKey.of("videos/large-mp4-video.mp4");
                String uploadId = "upload-123";
                int partNumber = 42;
                Duration expiry = Duration.ofMinutes(15);

                PresignedUploadPartRequest presignedResult = mock(PresignedUploadPartRequest.class);
                when(presignedResult.url()).thenReturn(URI.create("https://b2.example.com/test-b2-bucket/videos/large-mp4-video.mp4?partNumber=42&uploadId=upload-123&token=xyz").toURL());
                when(s3Presigner.presignUploadPart(any(UploadPartPresignRequest.class))).thenReturn(presignedResult);

                String url = adapter.generatePresignedPartUploadUrl(key, uploadId, partNumber, expiry);

                assertThat(url).contains("partNumber=42").contains("uploadId=upload-123");

                ArgumentCaptor<UploadPartPresignRequest> captor = ArgumentCaptor.forClass(UploadPartPresignRequest.class);
                verify(s3Presigner).presignUploadPart(captor.capture());

                UploadPartPresignRequest captured = captor.getValue();
                assertThat(captured.signatureDuration()).isEqualTo(expiry);
                assertThat(captured.uploadPartRequest().bucket()).isEqualTo(BUCKET);
                assertThat(captured.uploadPartRequest().key()).isEqualTo("videos/large-mp4-video.mp4");
                assertThat(captured.uploadPartRequest().uploadId()).isEqualTo("upload-123");
                assertThat(captured.uploadPartRequest().partNumber()).isEqualTo(42);
            }

            @ParameterizedTest(name = "rejects invalid partNumber: {0}")
            @ValueSource(ints = {0, -1, 10_001, -100})
            void shouldRejectInvalidPartNumbers(int invalidPartNumber) {
                StorageKey key = StorageKey.of("videos/large.mp4");
                assertThatThrownBy(() -> adapter.generatePresignedPartUploadUrl(key, "u-1", invalidPartNumber, Duration.ofMinutes(5)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("partNumber must be between 1 and 10000");
            }

            @Test
            @DisplayName("presign validates non-null, non-blank, and positive expiry arguments")
            void shouldValidatePresignArguments() {
                StorageKey key = StorageKey.of("videos/large.mp4");

                assertThatThrownBy(() -> adapter.generatePresignedPartUploadUrl(null, "u-1", 1, Duration.ofMinutes(5)))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.generatePresignedPartUploadUrl(key, null, 1, Duration.ofMinutes(5)))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.generatePresignedPartUploadUrl(key, "  ", 1, Duration.ofMinutes(5)))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> adapter.generatePresignedPartUploadUrl(key, "u-1", 1, null))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.generatePresignedPartUploadUrl(key, "u-1", 1, Duration.ZERO))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> adapter.generatePresignedPartUploadUrl(key, "u-1", 1, Duration.ofSeconds(-10)))
                        .isInstanceOf(IllegalArgumentException.class);
            }

            @Test
            @DisplayName("presign throws StorageException when S3Presigner is not configured")
            void shouldThrowWhenPresignerIsNull() {
                B2StorageAdapter adapterWithoutPresigner = new B2StorageAdapter(BUCKET, s3Client, (S3Presigner) null);
                StorageKey key = StorageKey.of("videos/large.mp4");

                assertThatThrownBy(() -> adapterWithoutPresigner.generatePresignedPartUploadUrl(key, "u-1", 1, Duration.ofMinutes(5)))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("S3Presigner is not configured");
            }

            @Test
            @DisplayName("presign maps SDK error to StorageException")
            void shouldMapPresignErrorToStorageException() {
                StorageKey key = StorageKey.of("videos/large.mp4");
                when(s3Presigner.presignUploadPart(any(UploadPartPresignRequest.class)))
                        .thenThrow(SdkClientException.create("Presigning failed"));

                assertThatThrownBy(() -> adapter.generatePresignedPartUploadUrl(key, "u-1", 1, Duration.ofMinutes(5)))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("Failed to generate presigned part upload URL");
            }
        }

        @Nested
        @DisplayName("List Uploaded Parts Tests")
        class ListPartsTests {

            @Test
            @DisplayName("listUploadedParts aggregates parts across paginated responses in order")
            void shouldListPartsAcrossMultiplePages() {
                StorageKey key = StorageKey.of("videos/paginated.mp4");
                String uploadId = "upload-paginated-id";

                // Page 1: parts 1 and 2, isTruncated = true, nextPartNumberMarker = 2
                Part p1 = Part.builder().partNumber(1).size(26214400L).eTag("\"etag-1\"").build();
                Part p2 = Part.builder().partNumber(2).size(26214400L).eTag("\"etag-2\"").build();
                ListPartsResponse page1 = ListPartsResponse.builder()
                        .parts(List.of(p1, p2))
                        .isTruncated(true)
                        .nextPartNumberMarker(2)
                        .build();

                // Page 2: part 3, isTruncated = false
                Part p3 = Part.builder().partNumber(3).size(10485760L).eTag("\"etag-3\"").build();
                ListPartsResponse page2 = ListPartsResponse.builder()
                        .parts(List.of(p3))
                        .isTruncated(false)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2);

                List<MultipartUploadedPart> parts = adapter.listUploadedParts(key, uploadId);

                assertThat(parts).hasSize(3);
                assertThat(parts.get(0)).isEqualTo(new MultipartUploadedPart(1, 26214400L, "\"etag-1\""));
                assertThat(parts.get(1)).isEqualTo(new MultipartUploadedPart(2, 26214400L, "\"etag-2\""));
                assertThat(parts.get(2)).isEqualTo(new MultipartUploadedPart(3, 10485760L, "\"etag-3\""));

                ArgumentCaptor<ListPartsRequest> captor = ArgumentCaptor.forClass(ListPartsRequest.class);
                verify(s3Client, org.mockito.Mockito.times(2)).listParts(captor.capture());

                List<ListPartsRequest> capturedRequests = captor.getAllValues();
                assertThat(capturedRequests.get(0).partNumberMarker()).isNull();
                assertThat(capturedRequests.get(1).partNumberMarker()).isEqualTo(2);
            }

            @Test
            @DisplayName("listUploadedParts correctly handles complete listing of more than 1,000 parts")
            void shouldListMoreThan1000PartsAcrossMultiplePages() {
                StorageKey key = StorageKey.of("videos/huge-parts.mp4");
                String uploadId = "upload-huge-parts-id";

                java.util.List<Part> page1Parts = new java.util.ArrayList<>(1000);
                for (int i = 1; i <= 1000; i++) {
                    page1Parts.add(Part.builder().partNumber(i).size(26214400L).eTag("\"etag-" + i + "\"").build());
                }

                java.util.List<Part> page2Parts = new java.util.ArrayList<>(50);
                for (int i = 1001; i <= 1050; i++) {
                    page2Parts.add(Part.builder().partNumber(i).size(10485760L).eTag("\"etag-" + i + "\"").build());
                }

                ListPartsResponse page1 = ListPartsResponse.builder()
                        .parts(page1Parts)
                        .isTruncated(true)
                        .nextPartNumberMarker(1000)
                        .build();

                ListPartsResponse page2 = ListPartsResponse.builder()
                        .parts(page2Parts)
                        .isTruncated(false)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2);

                List<MultipartUploadedPart> parts = adapter.listUploadedParts(key, uploadId);

                assertThat(parts).hasSize(1050);
                assertThat(parts.get(0).partNumber()).isEqualTo(1);
                assertThat(parts.get(999).partNumber()).isEqualTo(1000);
                assertThat(parts.get(1049).partNumber()).isEqualTo(1050);

                ArgumentCaptor<ListPartsRequest> captor = ArgumentCaptor.forClass(ListPartsRequest.class);
                verify(s3Client, org.mockito.Mockito.times(2)).listParts(captor.capture());

                List<ListPartsRequest> capturedRequests = captor.getAllValues();
                assertThat(capturedRequests.get(0).partNumberMarker()).isNull();
                assertThat(capturedRequests.get(1).partNumberMarker()).isEqualTo(1000);
            }

            @Test
            @DisplayName("listUploadedParts fails closed when truncated page lacks next marker")
            void shouldFailClosedWhenTruncatedResponseHasNullMarker() {
                StorageKey key = StorageKey.of("videos/truncated-no-marker.mp4");
                String uploadId = "u-no-marker";

                Part p1 = Part.builder().partNumber(1).size(26214400L).eTag("\"etag-1\"").build();
                ListPartsResponse truncatedWithoutMarker = ListPartsResponse.builder()
                        .parts(List.of(p1))
                        .isTruncated(true)
                        .nextPartNumberMarker(null)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class))).thenReturn(truncatedWithoutMarker);

                assertThatThrownBy(() -> adapter.listUploadedParts(key, uploadId))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("without nextPartNumberMarker");
            }

            @Test
            @DisplayName("listUploadedParts fails closed when truncated page contains no parts")
            void shouldFailClosedWhenTruncatedPageContainsNoParts() {
                StorageKey key = StorageKey.of("videos/empty-truncated.mp4");
                String uploadId = "u-empty-truncated";

                ListPartsResponse emptyTruncated = ListPartsResponse.builder()
                        .parts(List.of())
                        .isTruncated(true)
                        .nextPartNumberMarker(1)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class))).thenReturn(emptyTruncated);

                assertThatThrownBy(() -> adapter.listUploadedParts(key, uploadId))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("truncated response with no parts");
            }

            @Test
            @DisplayName("listUploadedParts fails closed when next marker is greater than last returned part")
            void shouldFailClosedWhenMarkerIsGreaterThanLastReturnedPart() {
                StorageKey key = StorageKey.of("videos/skipped-parts.mp4");
                String uploadId = "u-skipped";

                Part p1 = Part.builder().partNumber(1).size(26214400L).eTag("\"etag-1\"").build();
                Part p2 = Part.builder().partNumber(2).size(26214400L).eTag("\"etag-2\"").build();
                ListPartsResponse skippedResponse = ListPartsResponse.builder()
                        .parts(List.of(p1, p2))
                        .isTruncated(true)
                        .nextPartNumberMarker(5) // skipped parts 3 and 4!
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class))).thenReturn(skippedResponse);

                assertThatThrownBy(() -> adapter.listUploadedParts(key, uploadId))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("inconsistent with last returned part");
            }

            @Test
            @DisplayName("listUploadedParts fails closed when next marker repeats")
            void shouldFailClosedWhenMarkerIsRepeated() {
                StorageKey key = StorageKey.of("videos/repeated-marker.mp4");
                String uploadId = "u-repeated";

                Part p1 = Part.builder().partNumber(1).size(26214400L).eTag("\"etag-1\"").build();
                Part p2 = Part.builder().partNumber(2).size(26214400L).eTag("\"etag-2\"").build();
                ListPartsResponse page1 = ListPartsResponse.builder()
                        .parts(List.of(p1, p2))
                        .isTruncated(true)
                        .nextPartNumberMarker(2)
                        .build();

                Part p3 = Part.builder().partNumber(3).size(26214400L).eTag("\"etag-3\"").build();
                ListPartsResponse page2 = ListPartsResponse.builder()
                        .parts(List.of(p3))
                        .isTruncated(true)
                        .nextPartNumberMarker(2) // non-advancing repeated marker
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class))).thenReturn(page1).thenReturn(page2);

                assertThatThrownBy(() -> adapter.listUploadedParts(key, uploadId))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("non-advancing nextPartNumberMarker");
            }

            @Test
            @DisplayName("listUploadedParts fails closed when next marker moves backward")
            void shouldFailClosedWhenMarkerMovesBackward() {
                StorageKey key = StorageKey.of("videos/backward-marker.mp4");
                String uploadId = "u-backward";

                Part p1 = Part.builder().partNumber(1).size(26214400L).eTag("\"etag-1\"").build();
                Part p2 = Part.builder().partNumber(10).size(26214400L).eTag("\"etag-10\"").build();
                ListPartsResponse page1 = ListPartsResponse.builder()
                        .parts(List.of(p1, p2))
                        .isTruncated(true)
                        .nextPartNumberMarker(10)
                        .build();

                Part p3 = Part.builder().partNumber(11).size(26214400L).eTag("\"etag-11\"").build();
                ListPartsResponse page2 = ListPartsResponse.builder()
                        .parts(List.of(p3))
                        .isTruncated(true)
                        .nextPartNumberMarker(8) // backward marker
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class))).thenReturn(page1).thenReturn(page2);

                assertThatThrownBy(() -> adapter.listUploadedParts(key, uploadId))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("non-advancing nextPartNumberMarker");
            }

            @Test
            @DisplayName("listUploadedParts fails closed when provider returns unordered part numbers")
            void shouldFailClosedWhenProviderReturnsInconsistentPartOrdering() {
                StorageKey key = StorageKey.of("videos/unordered-parts.mp4");
                String uploadId = "u-unordered";

                Part p1 = Part.builder().partNumber(5).size(26214400L).eTag("\"etag-5\"").build();
                Part p2 = Part.builder().partNumber(2).size(26214400L).eTag("\"etag-2\"").build(); // out of order
                ListPartsResponse unorderedResponse = ListPartsResponse.builder()
                        .parts(List.of(p1, p2))
                        .isTruncated(false)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class))).thenReturn(unorderedResponse);

                assertThatThrownBy(() -> adapter.listUploadedParts(key, uploadId))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("unordered or non-advancing part number");
            }

            @Test
            @DisplayName("listUploadedParts maps typed NoSuchUploadException to StorageObjectNotFoundException")
            void shouldMapNoSuchUploadExceptionToStorageObjectNotFoundException() {
                StorageKey key = StorageKey.of("videos/missing.mp4");

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenThrow(NoSuchUploadException.builder().statusCode(404).message("The specified upload does not exist.").build());

                assertThatThrownBy(() -> adapter.listUploadedParts(key, "invalid-upload"))
                        .isInstanceOf(StorageObjectNotFoundException.class);
            }

            @Test
            @DisplayName("listUploadedParts maps generic S3Exception with exact NoSuchUpload code to StorageObjectNotFoundException")
            void shouldMapGenericS3ExceptionWithNoSuchUploadCodeToStorageObjectNotFoundException() {
                StorageKey key = StorageKey.of("videos/missing-code.mp4");

                S3Exception noSuchUploadEx = (S3Exception) S3Exception.builder()
                        .statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchUpload").build())
                        .message("The specified upload does not exist.")
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class))).thenThrow(noSuchUploadEx);

                assertThatThrownBy(() -> adapter.listUploadedParts(key, "invalid-upload"))
                        .isInstanceOf(StorageObjectNotFoundException.class);
            }

            @Test
            @DisplayName("listUploadedParts maps unrelated HTTP 404 to StorageException")
            void shouldMapUnrelatedHttp404ToStorageException() {
                StorageKey key = StorageKey.of("videos/missing-404.mp4");

                S3Exception unrelated404 = (S3Exception) S3Exception.builder()
                        .statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
                        .message("The specified bucket does not exist.")
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class))).thenThrow(unrelated404);

                assertThatThrownBy(() -> adapter.listUploadedParts(key, "invalid-upload"))
                        .isInstanceOf(StorageException.class)
                        .isNotInstanceOf(StorageObjectNotFoundException.class)
                        .hasMessageContaining("Failed to list multipart upload parts");
            }

            @Test
            @DisplayName("listUploadedParts maps general S3 error to StorageException")
            void shouldMapGeneralErrorToStorageException() {
                StorageKey key = StorageKey.of("videos/err.mp4");

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder().statusCode(500).message("B2 Internal Error").build());

                assertThatThrownBy(() -> adapter.listUploadedParts(key, "u-1"))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("Failed to list multipart upload parts");
            }

            @Test
            @DisplayName("listUploadedParts validates non-null and non-blank arguments")
            void shouldValidateListPartsArguments() {
                StorageKey key = StorageKey.of("videos/v.mp4");

                assertThatThrownBy(() -> adapter.listUploadedParts(null, "u-1"))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.listUploadedParts(key, null))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.listUploadedParts(key, "  "))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Nested
        @DisplayName("Complete Multipart Upload Tests")
        class CompleteTests {

            @Test
            @DisplayName("completeMultipartUpload sends CompleteMultipartUploadRequest with all parts and returns MultipartCompletedObject")
            void shouldCompleteMultipartUploadSuccessfully() {
                StorageKey key = StorageKey.of("videos/completed-video.mp4");
                String uploadId = "u-completed-123";
                List<MultipartPartETag> parts = List.of(
                        new MultipartPartETag(1, "\"etag-1\""),
                        new MultipartPartETag(2, "\"etag-2\"")
                );

                when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                        .thenReturn(CompleteMultipartUploadResponse.builder()
                                .bucket(BUCKET)
                                .key(key.value())
                                .eTag("\"final-multipart-etag-2\"")
                                .build());

                MultipartCompletedObject completed = adapter.completeMultipartUpload(key, uploadId, parts);

                assertThat(completed.location().providerId()).isEqualTo(StorageProviderId.of("b2"));
                assertThat(completed.location().key()).isEqualTo(key);
                assertThat(completed.eTag()).isEqualTo("\"final-multipart-etag-2\"");

                ArgumentCaptor<CompleteMultipartUploadRequest> captor = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
                verify(s3Client).completeMultipartUpload(captor.capture());

                CompleteMultipartUploadRequest captured = captor.getValue();
                assertThat(captured.bucket()).isEqualTo(BUCKET);
                assertThat(captured.key()).isEqualTo(key.value());
                assertThat(captured.uploadId()).isEqualTo(uploadId);
                assertThat(captured.ifNoneMatch()).isNull();
                assertThat(captured.multipartUpload().parts()).hasSize(2);
                assertThat(captured.multipartUpload().parts().get(0).partNumber()).isEqualTo(1);
                assertThat(captured.multipartUpload().parts().get(0).eTag()).isEqualTo("\"etag-1\"");
                assertThat(captured.multipartUpload().parts().get(1).partNumber()).isEqualTo(2);
                assertThat(captured.multipartUpload().parts().get(1).eTag()).isEqualTo("\"etag-2\"");
            }

            @Test
            @DisplayName("complete rejects empty parts list and never calls provider")
            void shouldRejectEmptyPartsListAndNeverCallProvider() {
                StorageKey key = StorageKey.of("videos/empty.mp4");

                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-1", List.of()))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("parts list cannot be empty");

                verify(s3Client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
            }

            @Test
            @DisplayName("complete rejects null elements in parts list and never calls provider")
            void shouldRejectNullElementInPartsListAndNeverCallProvider() {
                StorageKey key = StorageKey.of("videos/null-element.mp4");
                List<MultipartPartETag> partsWithNull = java.util.Arrays.asList(
                        new MultipartPartETag(1, "\"etag-1\""),
                        null
                );

                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-1", partsWithNull))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("parts list cannot contain null elements");

                verify(s3Client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
            }

            @Test
            @DisplayName("complete rejects duplicate part numbers and never calls provider")
            void shouldRejectDuplicatePartNumbersAndNeverCallProvider() {
                StorageKey key = StorageKey.of("videos/duplicate.mp4");
                List<MultipartPartETag> duplicateParts = List.of(
                        new MultipartPartETag(1, "\"etag-1\""),
                        new MultipartPartETag(1, "\"etag-1-alt\"")
                );

                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-1", duplicateParts))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Duplicate partNumber detected");

                verify(s3Client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
            }

            @Test
            @DisplayName("complete requires strictly ascending part numbers and never calls provider")
            void shouldRequireStrictlyAscendingPartNumbersAndNeverCallProvider() {
                StorageKey key = StorageKey.of("videos/descending.mp4");
                List<MultipartPartETag> unorderedParts = List.of(
                        new MultipartPartETag(2, "\"etag-2\""),
                        new MultipartPartETag(1, "\"etag-1\"")
                );

                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-1", unorderedParts))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("strictly ascending partNumber order");

                verify(s3Client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
            }

            @Test
            @DisplayName("complete rejects parts list exceeding 10,000 parts and never calls provider")
            void shouldRejectPartsListExceeding10000AndNeverCallProvider() {
                StorageKey key = StorageKey.of("videos/too-many-parts.mp4");

                // Mock list of 10,001 parts
                java.util.List<MultipartPartETag> tooManyParts = new java.util.ArrayList<>(10001);
                for (int i = 1; i <= 10001; i++) {
                    tooManyParts.add(mock(MultipartPartETag.class));
                }

                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-1", tooManyParts))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("exceeds maximum allowed limit of 10000 parts");

                verify(s3Client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
            }

            @Test
            @DisplayName("complete maps typed NoSuchUploadException to StorageObjectNotFoundException")
            void shouldMapNoSuchUploadOnCompleteToStorageObjectNotFoundException() {
                StorageKey key = StorageKey.of("videos/missing.mp4");
                List<MultipartPartETag> parts = List.of(new MultipartPartETag(1, "\"etag-1\""));

                when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                        .thenThrow(NoSuchUploadException.builder().statusCode(404).message("The specified upload does not exist.").build());

                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-invalid", parts))
                        .isInstanceOf(StorageObjectNotFoundException.class);
            }

            @Test
            @DisplayName("complete maps generic S3Exception with exact NoSuchUpload code to StorageObjectNotFoundException")
            void shouldMapGenericS3ExceptionWithNoSuchUploadCodeOnCompleteToStorageObjectNotFoundException() {
                StorageKey key = StorageKey.of("videos/missing-code.mp4");
                List<MultipartPartETag> parts = List.of(new MultipartPartETag(1, "\"etag-1\""));

                S3Exception noSuchUploadEx = (S3Exception) S3Exception.builder()
                        .statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchUpload").build())
                        .message("The specified upload does not exist.")
                        .build();

                when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class))).thenThrow(noSuchUploadEx);

                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-invalid", parts))
                        .isInstanceOf(StorageObjectNotFoundException.class);
            }

            @Test
            @DisplayName("complete maps unrelated HTTP 404 to StorageException")
            void shouldMapUnrelatedHttp404OnCompleteToStorageException() {
                StorageKey key = StorageKey.of("videos/unrelated-404.mp4");
                List<MultipartPartETag> parts = List.of(new MultipartPartETag(1, "\"etag-1\""));

                S3Exception unrelated404 = (S3Exception) S3Exception.builder()
                        .statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
                        .message("Bucket does not exist.")
                        .build();

                when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class))).thenThrow(unrelated404);

                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-1", parts))
                        .isInstanceOf(StorageException.class)
                        .isNotInstanceOf(StorageObjectNotFoundException.class)
                        .hasMessageContaining("Failed to complete multipart upload");
            }

            @Test
            @DisplayName("complete maps S3 500 error to StorageException")
            void shouldMapCompleteErrorToStorageException() {
                StorageKey key = StorageKey.of("videos/err.mp4");
                List<MultipartPartETag> parts = List.of(new MultipartPartETag(1, "\"etag-1\""));

                when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder().statusCode(500).message("B2 complete error").build());

                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-1", parts))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("Failed to complete multipart upload");
            }

            @Test
            @DisplayName("complete maps HTTP 501 Not Implemented to StorageException rather than misclassifying")
            void shouldPropagateHttp501OnCompleteAsStorageException() {
                StorageKey key = StorageKey.of("videos/complete-501.mp4");
                List<MultipartPartETag> parts = List.of(new MultipartPartETag(1, "\"etag-1\""));

                S3Exception notImplementedEx = (S3Exception) S3Exception.builder()
                        .statusCode(501)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NotImplemented").build())
                        .message("Not Implemented")
                        .build();

                when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                        .thenThrow(notImplementedEx);

                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-complete", parts))
                        .isInstanceOf(StorageException.class)
                        .isNotInstanceOf(StorageObjectNotFoundException.class)
                        .isNotInstanceOf(StorageObjectAlreadyExistsException.class)
                        .hasMessageContaining("Failed to complete multipart upload");

                verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
            }

            @Test
            @DisplayName("complete maps provider HTTP 412 error to generic StorageException")
            void shouldPropagateHttp412OnCompleteAsGenericStorageException() {
                StorageKey key = StorageKey.of("videos/err-412.mp4");
                List<MultipartPartETag> parts = List.of(new MultipartPartETag(1, "\"etag-1\""));

                S3Exception preconditionFailed = (S3Exception) S3Exception.builder()
                        .statusCode(412)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("PreconditionFailed").build())
                        .message("Precondition Failed")
                        .build();

                when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                        .thenThrow(preconditionFailed);

                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-complete", parts))
                        .isInstanceOf(StorageException.class)
                        .isNotInstanceOf(StorageObjectAlreadyExistsException.class)
                        .hasMessageContaining("Failed to complete multipart upload");

                verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
            }

            @Test
            @DisplayName("complete maps provider HTTP 409 error to generic StorageException")
            void shouldPropagateHttp409OnCompleteAsGenericStorageException() {
                StorageKey key = StorageKey.of("videos/err-409.mp4");
                List<MultipartPartETag> parts = List.of(new MultipartPartETag(1, "\"etag-1\""));

                S3Exception conflictEx = (S3Exception) S3Exception.builder()
                        .statusCode(409)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("Conflict").build())
                        .message("Conflict")
                        .build();

                when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                        .thenThrow(conflictEx);

                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-complete", parts))
                        .isInstanceOf(StorageException.class)
                        .isNotInstanceOf(StorageObjectAlreadyExistsException.class)
                        .hasMessageContaining("Failed to complete multipart upload");

                verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
            }

            @Test
            @DisplayName("complete validates non-null and non-blank arguments and never calls provider")
            void shouldValidateCompleteArguments() {
                StorageKey key = StorageKey.of("videos/v.mp4");
                List<MultipartPartETag> parts = List.of(new MultipartPartETag(1, "\"etag-1\""));

                assertThatThrownBy(() -> adapter.completeMultipartUpload(null, "u-1", parts))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, null, parts))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "   ", parts))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> adapter.completeMultipartUpload(key, "u-1", null))
                        .isInstanceOf(NullPointerException.class);

                verify(s3Client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
            }
        }

        @Nested
        @DisplayName("Abort Multipart Upload Tests")
        class AbortTests {

            @Test
            @DisplayName("abortMultipartUpload sends AbortMultipartUploadRequest")
            void shouldAbortMultipartUploadSuccessfully() {
                StorageKey key = StorageKey.of("videos/abort-target.mp4");
                String uploadId = "u-abort-123";

                when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                        .thenReturn(AbortMultipartUploadResponse.builder().build());

                adapter.abortMultipartUpload(key, uploadId);

                ArgumentCaptor<AbortMultipartUploadRequest> captor = ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
                verify(s3Client).abortMultipartUpload(captor.capture());

                AbortMultipartUploadRequest captured = captor.getValue();
                assertThat(captured.bucket()).isEqualTo(BUCKET);
                assertThat(captured.key()).isEqualTo(key.value());
                assertThat(captured.uploadId()).isEqualTo(uploadId);
            }

            @Test
            @DisplayName("abort missing or already completed upload is idempotent on typed NoSuchUploadException")
            void shouldBeIdempotentOnTypedNoSuchUploadException() {
                StorageKey key = StorageKey.of("videos/already-done.mp4");

                when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                        .thenThrow(NoSuchUploadException.builder().statusCode(404).build());

                assertThatCode(() -> adapter.abortMultipartUpload(key, "u-done")).doesNotThrowAnyException();
            }

            @Test
            @DisplayName("abort is idempotent on generic S3Exception with exact NoSuchUpload errorCode")
            void shouldBeIdempotentOnGenericS3ExceptionWithExactNoSuchUploadCode() {
                StorageKey key = StorageKey.of("videos/already-done-code.mp4");

                S3Exception noSuchUploadEx = (S3Exception) S3Exception.builder()
                        .statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchUpload").build())
                        .message("The specified upload does not exist.")
                        .build();

                when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class))).thenThrow(noSuchUploadEx);

                assertThatCode(() -> adapter.abortMultipartUpload(key, "u-done")).doesNotThrowAnyException();
            }

            @Test
            @DisplayName("abort propagates unrelated HTTP 404 with different error code as StorageException")
            void shouldPropagateUnrelatedHttp404WithDifferentErrorCodeAsStorageException() {
                StorageKey key = StorageKey.of("videos/unrelated-404-bucket.mp4");

                S3Exception unrelated404 = (S3Exception) S3Exception.builder()
                        .statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
                        .message("Bucket does not exist.")
                        .build();

                when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class))).thenThrow(unrelated404);

                assertThatThrownBy(() -> adapter.abortMultipartUpload(key, "u-bucket-missing"))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("Failed to abort multipart upload");
            }

            @Test
            @DisplayName("abort propagates generic HTTP 404 without NoSuchUpload code as StorageException")
            void shouldPropagateGenericHttp404WithoutNoSuchUploadCodeAsStorageException() {
                StorageKey key = StorageKey.of("videos/generic-404.mp4");

                S3Exception generic404 = (S3Exception) S3Exception.builder()
                        .statusCode(404)
                        .message("Not Found")
                        .build();

                when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class))).thenThrow(generic404);

                assertThatThrownBy(() -> adapter.abortMultipartUpload(key, "u-generic-404"))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("Failed to abort multipart upload");
            }

            @Test
            @DisplayName("abort maps provider error (HTTP 403) to StorageException")
            void shouldMap403OnAbortToStorageException() {
                StorageKey key = StorageKey.of("videos/abort-403.mp4");

                when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder().statusCode(403).message("Access Denied").build());

                assertThatThrownBy(() -> adapter.abortMultipartUpload(key, "u-403"))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("Failed to abort multipart upload");
            }

            @Test
            @DisplayName("abort maps provider error (HTTP 500) to StorageException")
            void shouldMap500OnAbortToStorageException() {
                StorageKey key = StorageKey.of("videos/abort-500.mp4");

                when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder().statusCode(500).message("Internal Error").build());

                assertThatThrownBy(() -> adapter.abortMultipartUpload(key, "u-500"))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("Failed to abort multipart upload");
            }

            @Test
            @DisplayName("abort maps SDK/network error to StorageException")
            void shouldMapNetworkErrorOnAbortToStorageException() {
                StorageKey key = StorageKey.of("videos/abort-net.mp4");

                when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                        .thenThrow(SdkClientException.create("Network timeout"));

                assertThatThrownBy(() -> adapter.abortMultipartUpload(key, "u-net"))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("Failed to abort multipart upload");
            }

            @Test
            @DisplayName("abort validates non-null and non-blank arguments")
            void shouldValidateAbortArguments() {
                StorageKey key = StorageKey.of("videos/v.mp4");

                assertThatThrownBy(() -> adapter.abortMultipartUpload(null, "u-1"))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.abortMultipartUpload(key, null))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.abortMultipartUpload(key, "   "))
                        .isInstanceOf(IllegalArgumentException.class);

                verify(s3Client, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
            }
        }

        @Nested
        @DisplayName("Inspect Object Metadata Tests")
        class InspectMetadataTests {

            @Test
            @DisplayName("inspectObjectMetadata returns metadata from S3 HeadObject")
            void shouldInspectObjectMetadataSuccessfully() {
                StorageKey key = StorageKey.of("videos/inspect-target.mp4");

                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenReturn(HeadObjectResponse.builder()
                                .contentLength(104857600L)
                                .contentType("video/mp4")
                                .eTag("\"head-etag-abc\"")
                                .build());

                MultipartObjectMetadata metadata = adapter.inspectObjectMetadata(key);

                assertThat(metadata.key()).isEqualTo(key);
                assertThat(metadata.contentLength()).isEqualTo(104857600L);
                assertThat(metadata.contentType()).isEqualTo("video/mp4");
                assertThat(metadata.eTag()).isEqualTo("\"head-etag-abc\"");

                ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
                verify(s3Client).headObject(captor.capture());

                HeadObjectRequest captured = captor.getValue();
                assertThat(captured.bucket()).isEqualTo(BUCKET);
                assertThat(captured.key()).isEqualTo(key.value());
            }

            @Test
            @DisplayName("inspectObjectMetadata maps NoSuchKeyException to StorageObjectNotFoundException")
            void shouldMapNoSuchKeyToStorageObjectNotFoundException() {
                StorageKey key = StorageKey.of("videos/missing-inspect.mp4");

                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow(NoSuchKeyException.builder().statusCode(404).message("Key not found").build());

                assertThatThrownBy(() -> adapter.inspectObjectMetadata(key))
                        .isInstanceOf(StorageObjectNotFoundException.class);
            }

            @Test
            @DisplayName("inspectObjectMetadata maps HTTP 404 to StorageObjectNotFoundException")
            void shouldMapHttp404ToStorageObjectNotFoundException() {
                StorageKey key = StorageKey.of("videos/missing-inspect-404.mp4");

                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder().statusCode(404).message("Not Found").build());

                assertThatThrownBy(() -> adapter.inspectObjectMetadata(key))
                        .isInstanceOf(StorageObjectNotFoundException.class);
            }

            @Test
            @DisplayName("inspectObjectMetadata maps general S3 error to StorageException")
            void shouldMapInspectErrorToStorageException() {
                StorageKey key = StorageKey.of("videos/inspect-error.mp4");

                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder().statusCode(500).message("B2 Head error").build());

                assertThatThrownBy(() -> adapter.inspectObjectMetadata(key))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("Failed to inspect metadata");
            }

            @Test
            @DisplayName("inspectObjectMetadata validates non-null key")
            void shouldValidateInspectKey() {
                assertThatThrownBy(() -> adapter.inspectObjectMetadata(null))
                        .isInstanceOf(NullPointerException.class);
            }
        }
    }

    @Nested
    @DisplayName("Lifecycle Close Tests")
    class CloseTests {

        @Test
        @DisplayName("close does not close unowned S3Client or S3Presigner")
        void shouldNotCloseUnownedClient() {
            adapter.close();
            verify(s3Client, never()).close();
            verify(s3Presigner, never()).close();
        }
    }
}
