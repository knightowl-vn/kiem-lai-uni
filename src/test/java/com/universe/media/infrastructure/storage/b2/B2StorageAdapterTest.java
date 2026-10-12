package com.universe.media.infrastructure.storage.b2;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.ports.storage.MultipartAbortResult;
import com.universe.media.application.ports.storage.MultipartCompletedObject;
import com.universe.media.application.ports.storage.MultipartObjectMetadata;
import com.universe.media.application.ports.storage.MultipartPartETag;
import com.universe.media.application.ports.storage.MultipartPartsObservation;
import com.universe.media.application.ports.storage.MultipartPartsSummary;
import com.universe.media.application.ports.storage.MultipartUploadObservationSnapshot;
import com.universe.media.application.ports.storage.MultipartUploadPresence;
import com.universe.media.application.ports.storage.MultipartUploadPresenceObservation;
import com.universe.media.application.ports.storage.MultipartUploadedPart;
import com.universe.media.application.ports.storage.StorageObjectPresence;
import com.universe.media.application.ports.storage.StorageObjectPresenceObservation;
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
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
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
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    @DisplayName("configuredBucketName returns configured bucket")
    void shouldReturnConfiguredBucketName() {
        assertThat(adapter.configuredBucketName()).isEqualTo(BUCKET);
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
                                .versionId("b2-ver-cm-1")
                                .build());

                MultipartCompletedObject completed = adapter.completeMultipartUpload(key, uploadId, parts);

                assertThat(completed.location().providerId()).isEqualTo(StorageProviderId.of("b2"));
                assertThat(completed.location().key()).isEqualTo(key);
                assertThat(completed.eTag()).isEqualTo("\"final-multipart-etag-2\"");
                assertThat(completed.versionId()).isEqualTo("b2-ver-cm-1");

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

                MultipartAbortResult result = adapter.abortMultipartUpload(key, uploadId);

                ArgumentCaptor<AbortMultipartUploadRequest> captor = ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
                verify(s3Client).abortMultipartUpload(captor.capture());

                AbortMultipartUploadRequest captured = captor.getValue();
                assertThat(captured.bucket()).isEqualTo(BUCKET);
                assertThat(captured.key()).isEqualTo(key.value());
                assertThat(captured.uploadId()).isEqualTo(uploadId);
                assertThat(result).isEqualTo(MultipartAbortResult.ABORT_ACCEPTED);
                assertThat(result.isAbortAccepted()).isTrue();
                assertThat(result.isUploadAlreadyAbsent()).isFalse();
            }

            @Test
            @DisplayName("abort throws StorageException when S3 client unexpectedly returns null response")
            void shouldThrowStorageExceptionWhenAbortReturnsNullResponse() {
                StorageKey key = StorageKey.of("videos/null-abort.mp4");
                String uploadId = "u-null-123";

                when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                        .thenReturn(null);

                assertThatThrownBy(() -> adapter.abortMultipartUpload(key, uploadId))
                        .isInstanceOf(StorageException.class)
                        .hasMessageContaining("returned null response");
            }

            @Test
            @DisplayName("abort missing or already completed upload is idempotent on typed NoSuchUploadException")
            void shouldBeIdempotentOnTypedNoSuchUploadException() {
                StorageKey key = StorageKey.of("videos/already-done.mp4");

                when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                        .thenThrow(NoSuchUploadException.builder().statusCode(404).build());

                MultipartAbortResult result = adapter.abortMultipartUpload(key, "u-done");
                assertThat(result).isEqualTo(MultipartAbortResult.UPLOAD_ALREADY_ABSENT);
                assertThat(result.isUploadAlreadyAbsent()).isTrue();
                assertThat(result.isAbortAccepted()).isFalse();
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

                MultipartAbortResult result = adapter.abortMultipartUpload(key, "u-done");
                assertThat(result).isEqualTo(MultipartAbortResult.UPLOAD_ALREADY_ABSENT);
                assertThat(result.isUploadAlreadyAbsent()).isTrue();
                assertThat(result.isAbortAccepted()).isFalse();
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

            @Test
            @DisplayName("abort routes to abortS3Client when separate abort client is provided")
            void shouldRouteAbortToDedicatedAbortS3Client() {
                S3Client separateAbortClient = mock(S3Client.class);
                B2StorageAdapter dualAdapter = new B2StorageAdapter(BUCKET, s3Client, separateAbortClient, s3Presigner, testTempDir);
                StorageKey key = StorageKey.of("videos/dedicated-abort.mp4");
                String uploadId = "u-dedicated-1";

                when(separateAbortClient.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                        .thenReturn(AbortMultipartUploadResponse.builder().build());

                MultipartAbortResult result = dualAdapter.abortMultipartUpload(key, uploadId);

                assertThat(result).isEqualTo(MultipartAbortResult.ABORT_ACCEPTED);
                verify(separateAbortClient).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
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
            @DisplayName("inspectObjectMetadata extracts userMetadata and versionId when present")
            void shouldExtractUserMetadataAndVersionId() {
                StorageKey key = StorageKey.of("videos/inspect-provenance.mp4");

                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenReturn(HeadObjectResponse.builder()
                                .contentLength(52428800L)
                                .contentType("video/mp4")
                                .eTag("\"head-etag-prov\"")
                                .metadata(java.util.Map.of("session-id", "sess-123", "allocation-id", "alloc-456"))
                                .versionId("b2-ver-789")
                                .build());

                MultipartObjectMetadata metadata = adapter.inspectObjectMetadata(key);

                assertThat(metadata.key()).isEqualTo(key);
                assertThat(metadata.contentLength()).isEqualTo(52428800L);
                assertThat(metadata.contentType()).isEqualTo("video/mp4");
                assertThat(metadata.eTag()).isEqualTo("\"head-etag-prov\"");
                assertThat(metadata.userMetadata()).containsEntry("session-id", "sess-123");
                assertThat(metadata.userMetadata()).containsEntry("allocation-id", "alloc-456");
                assertThat(metadata.versionId()).isEqualTo("b2-ver-789");
                assertThat(metadata.providerId()).isEqualTo(StorageProviderId.of("b2"));
                assertThat(metadata.bucketName()).isEqualTo(BUCKET);
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

        @Nested
        @DisplayName("Multipart Observation Tests")
        class MultipartObservationTests {

            private final StorageKey key = StorageKey.of("media/sessions/test-obs.mp4");
            private final String uploadId = "obs-upload-123";

            @Test
            @DisplayName("observeMultipartUploadPresence returns ACTIVE on exact bucket/key/uploadId match")
            void shouldReturnActiveWhenExactMatchInListing() {
                MultipartUpload upload = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId(uploadId)
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(ListMultipartUploadsResponse.builder()
                                .uploads(List.of(upload))
                                .isTruncated(false)
                                .build());

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.storageKey()).isEqualTo(key);
                assertThat(obs.uploadId()).isEqualTo(uploadId);
                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.ACTIVE);
                assertThat(obs.observedAt()).isNotNull();
                assertThat(obs.safeDiagnostic()).isNull();

                ArgumentCaptor<ListMultipartUploadsRequest> captor = ArgumentCaptor.forClass(ListMultipartUploadsRequest.class);
                verify(s3Client).listMultipartUploads(captor.capture());
                assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
                assertThat(captor.getValue().prefix()).isEqualTo(key.value());
                assertThat(captor.getValue().overrideConfiguration().flatMap(c -> c.apiCallTimeout()))
                        .contains(B2StorageAdapter.OBSERVATION_API_CALL_TIMEOUT);
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns NOT_LISTED when uploadId differs under same prefix")
            void shouldReturnNotListedWhenUploadIdDiffers() {
                MultipartUpload otherUpload = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("different-upload-id")
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(ListMultipartUploadsResponse.builder()
                                .uploads(List.of(otherUpload))
                                .isTruncated(false)
                                .build());

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.NOT_LISTED);
                assertThat(obs.safeDiagnostic()).isNull();
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns NOT_LISTED when key differs")
            void shouldReturnNotListedWhenKeyDiffers() {
                MultipartUpload otherUpload = MultipartUpload.builder()
                        .key("media/sessions/other-key.mp4")
                        .uploadId(uploadId)
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(ListMultipartUploadsResponse.builder()
                                .uploads(List.of(otherUpload))
                                .isTruncated(false)
                                .build());

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.NOT_LISTED);
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns NOT_LISTED on empty listing")
            void shouldReturnNotListedOnEmptyListing() {
                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(ListMultipartUploadsResponse.builder()
                                .uploads(List.of())
                                .isTruncated(false)
                                .build());

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.NOT_LISTED);
                assertThat(obs.safeDiagnostic()).isNull();
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns UNKNOWN on truncated listing missing NextKeyMarker")
            void shouldReturnUnknownWhenTruncatedListingMissingNextKeyMarker() {
                MultipartUpload otherUpload = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("other-id")
                        .build();

                ListMultipartUploadsResponse page1 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(otherUpload))
                        .isTruncated(true)
                        .nextKeyMarker(null)
                        .nextUploadIdMarker("upload-marker-1")
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(page1);

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.safeDiagnostic()).contains("MALFORMED_PAGINATION: truncated listing missing NextKeyMarker");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns UNKNOWN on truncated listing missing NextUploadIdMarker")
            void shouldReturnUnknownWhenTruncatedListingMissingNextUploadIdMarker() {
                MultipartUpload otherUpload = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("other-id")
                        .build();

                ListMultipartUploadsResponse page1 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(otherUpload))
                        .isTruncated(true)
                        .nextKeyMarker("key-marker-1")
                        .nextUploadIdMarker(null)
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(page1);

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.safeDiagnostic()).contains("MALFORMED_PAGINATION: truncated listing missing NextUploadIdMarker");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns UNKNOWN on truncated listing with empty uploads")
            void shouldReturnUnknownWhenTruncatedListingHasEmptyUploads() {
                ListMultipartUploadsResponse truncatedEmpty = ListMultipartUploadsResponse.builder()
                        .uploads(List.of())
                        .isTruncated(true)
                        .nextKeyMarker("key-marker-1")
                        .nextUploadIdMarker("upload-marker-1")
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(truncatedEmpty);

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.safeDiagnostic()).contains("MALFORMED_PAGINATION: truncated listing with empty uploads");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns UNKNOWN when marker pair is repeated")
            void shouldReturnUnknownWhenMarkerPairIsRepeated() {
                MultipartUpload otherUpload1 = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("id-1")
                        .build();
                MultipartUpload otherUpload2 = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("id-2")
                        .build();

                ListMultipartUploadsResponse page1 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(otherUpload1))
                        .isTruncated(true)
                        .nextKeyMarker("same-key")
                        .nextUploadIdMarker("same-upload-id")
                        .build();

                ListMultipartUploadsResponse page2 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(otherUpload2))
                        .isTruncated(true)
                        .nextKeyMarker("same-key")
                        .nextUploadIdMarker("same-upload-id")
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2);

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.safeDiagnostic()).contains("NON_PROGRESSING_PAGINATION: repeated marker pair");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns UNKNOWN when key marker regresses")
            void shouldReturnUnknownWhenKeyMarkerRegresses() {
                MultipartUpload otherUpload = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("id-1")
                        .build();

                ListMultipartUploadsResponse page1 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(otherUpload))
                        .isTruncated(true)
                        .nextKeyMarker("key-b")
                        .nextUploadIdMarker("upload-1")
                        .build();

                ListMultipartUploadsResponse page2 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(otherUpload))
                        .isTruncated(true)
                        .nextKeyMarker("key-a")
                        .nextUploadIdMarker("upload-1")
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2);

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.safeDiagnostic()).contains("NON_PROGRESSING_PAGINATION: regressing key marker");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns UNKNOWN when uploadId marker regresses for same key")
            void shouldReturnUnknownWhenUploadIdMarkerRegressesForSameKey() {
                MultipartUpload otherUpload = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("id-1")
                        .build();

                ListMultipartUploadsResponse page1 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(otherUpload))
                        .isTruncated(true)
                        .nextKeyMarker("key-a")
                        .nextUploadIdMarker("upload-z")
                        .build();

                ListMultipartUploadsResponse page2 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(otherUpload))
                        .isTruncated(true)
                        .nextKeyMarker("key-a")
                        .nextUploadIdMarker("upload-a")
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2);

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.safeDiagnostic()).contains("NON_PROGRESSING_PAGINATION: regressing upload ID marker");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence propagates pagination markers across pages and returns ACTIVE")
            void shouldTraversePaginationAndPropagateMarkers() {
                MultipartUpload upload1 = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("earlier-id")
                        .build();
                MultipartUpload upload2 = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId(uploadId)
                        .build();

                ListMultipartUploadsResponse page1 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(upload1))
                        .isTruncated(true)
                        .nextKeyMarker("marker-key-1")
                        .nextUploadIdMarker("marker-upload-1")
                        .build();

                ListMultipartUploadsResponse page2 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(upload2))
                        .isTruncated(false)
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2);

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.ACTIVE);

                ArgumentCaptor<ListMultipartUploadsRequest> captor = ArgumentCaptor.forClass(ListMultipartUploadsRequest.class);
                verify(s3Client, org.mockito.Mockito.times(2)).listMultipartUploads(captor.capture());

                List<ListMultipartUploadsRequest> capturedRequests = captor.getAllValues();
                assertThat(capturedRequests.get(0).keyMarker()).isNull();
                assertThat(capturedRequests.get(0).uploadIdMarker()).isNull();
                assertThat(capturedRequests.get(1).keyMarker()).isEqualTo("marker-key-1");
                assertThat(capturedRequests.get(1).uploadIdMarker()).isEqualTo("marker-upload-1");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns NOT_LISTED when multi-page traversal is exhausted")
            void shouldReturnNotListedWhenMultiPageTraversalIsExhausted() {
                MultipartUpload upload1 = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("other-id-1")
                        .build();
                MultipartUpload upload2 = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("other-id-2")
                        .build();

                ListMultipartUploadsResponse page1 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(upload1))
                        .isTruncated(true)
                        .nextKeyMarker("key-1")
                        .nextUploadIdMarker("upload-1")
                        .build();

                ListMultipartUploadsResponse page2 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(upload2))
                        .isTruncated(false)
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2);

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.NOT_LISTED);
                assertThat(obs.safeDiagnostic()).isNull();
            }

            @Test
            @DisplayName("observeMultipartUploadPresence never infers NOT_LISTED when traversal fails midway")
            void shouldNeverInferNotListedWhenTraversalIsIncompleteDueToError() {
                MultipartUpload upload1 = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("other-id-1")
                        .build();

                ListMultipartUploadsResponse page1 = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(upload1))
                        .isTruncated(true)
                        .nextKeyMarker("key-1")
                        .nextUploadIdMarker("upload-1")
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(page1)
                        .thenThrow((S3Exception) S3Exception.builder().statusCode(500).message("Server error").build());

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.presence()).isNotEqualTo(MultipartUploadPresence.NOT_LISTED);
                assertThat(obs.safeDiagnostic()).isEqualTo("PROVIDER_ERROR");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns UNKNOWN when pagination budget is exhausted")
            void shouldReturnUnknownWhenPaginationBudgetExhausted() {
                MultipartUpload otherUpload = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("unrelated-id")
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenAnswer(invocation -> {
                            ListMultipartUploadsRequest req = invocation.getArgument(0);
                            String currentKey = req.keyMarker() != null ? req.keyMarker() : "key-0";
                            int nextIdx = Integer.parseInt(currentKey.substring("key-".length())) + 1;
                            return ListMultipartUploadsResponse.builder()
                                    .uploads(List.of(otherUpload))
                                    .isTruncated(true)
                                    .nextKeyMarker("key-" + nextIdx)
                                    .nextUploadIdMarker("upload-" + nextIdx)
                                    .build();
                        });

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.safeDiagnostic()).contains("PAGINATION_BUDGET_EXCEEDED");
                verify(s3Client, org.mockito.Mockito.times(B2StorageAdapter.OBSERVATION_MAX_PAGES)).listMultipartUploads(any(ListMultipartUploadsRequest.class));
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns UNKNOWN with TIMEOUT_UNCERTAINTY on SdkClientException timeout")
            void shouldReturnUnknownOnTimeout() {
                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenThrow(SdkClientException.create("Read timed out"));

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.safeDiagnostic()).isEqualTo("TIMEOUT_UNCERTAINTY");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns UNKNOWN with PROVIDER_ACCESS_DENIED on HTTP 403")
            void shouldReturnUnknownOnAccessDenied() {
                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder()
                                .statusCode(403)
                                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                                .message("Access Denied")
                                .build());

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.safeDiagnostic()).isEqualTo("PROVIDER_ACCESS_DENIED");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns UNKNOWN on 500 and 503")
            void shouldReturnUnknownOnServerErrorOrUnavailable() {
                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder()
                                .statusCode(500)
                                .message("Internal Server Error")
                                .build());

                MultipartUploadPresenceObservation obs500 = adapter.observeMultipartUploadPresence(key, uploadId);
                assertThat(obs500.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs500.safeDiagnostic()).isEqualTo("PROVIDER_ERROR");

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder()
                                .statusCode(503)
                                .awsErrorDetails(AwsErrorDetails.builder().errorCode("SlowDown").build())
                                .message("Slow Down")
                                .build());

                MultipartUploadPresenceObservation obs503 = adapter.observeMultipartUploadPresence(key, uploadId);
                assertThat(obs503.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs503.safeDiagnostic()).isEqualTo("PROVIDER_SERVICE_UNAVAILABLE");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence validates parameters")
            void shouldValidateParametersForUploadPresence() {
                assertThatThrownBy(() -> adapter.observeMultipartUploadPresence(null, uploadId))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.observeMultipartUploadPresence(key, null))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.observeMultipartUploadPresence(key, "   "))
                        .isInstanceOf(IllegalArgumentException.class);
            }

            @Test
            @DisplayName("observeObjectPresence returns PRESENT when HEAD 200 succeeds")
            void shouldReturnPresentWhenHeadObjectSucceeds() {
                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenReturn(HeadObjectResponse.builder()
                                .contentLength(10485760L)
                                .contentType("video/mp4")
                                .eTag("\"etag-head-present\"")
                                .versionId("v1")
                                .build());

                StorageObjectPresenceObservation obs = adapter.observeObjectPresence(key);

                assertThat(obs.storageKey()).isEqualTo(key);
                assertThat(obs.presence()).isEqualTo(StorageObjectPresence.PRESENT);
                assertThat(obs.metadata()).isNotNull();
                assertThat(obs.metadata().contentLength()).isEqualTo(10485760L);
                assertThat(obs.metadata().contentType()).isEqualTo("video/mp4");
                assertThat(obs.metadata().eTag()).isEqualTo("\"etag-head-present\"");
                assertThat(obs.safeDiagnostic()).isNull();

                ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
                verify(s3Client).headObject(captor.capture());
                assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
                assertThat(captor.getValue().key()).isEqualTo(key.value());
                assertThat(captor.getValue().overrideConfiguration().flatMap(c -> c.apiCallTimeout()))
                        .contains(B2StorageAdapter.OBSERVATION_API_CALL_TIMEOUT);
            }

            @Test
            @DisplayName("observeObjectPresence returns NOT_FOUND when HEAD throws NoSuchKeyException or explicit NoSuchKey S3Exception")
            void shouldReturnNotFoundWhenExplicitNoSuchKey() {
                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow(NoSuchKeyException.builder().statusCode(404).message("Key not found").build());

                StorageObjectPresenceObservation obs1 = adapter.observeObjectPresence(key);
                assertThat(obs1.presence()).isEqualTo(StorageObjectPresence.NOT_FOUND);
                assertThat(obs1.metadata()).isNull();
                assertThat(obs1.safeDiagnostic()).isNull();

                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder()
                                .statusCode(404)
                                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchKey").build())
                                .message("NoSuchKey")
                                .build());

                StorageObjectPresenceObservation obs2 = adapter.observeObjectPresence(key);
                assertThat(obs2.presence()).isEqualTo(StorageObjectPresence.NOT_FOUND);
                assertThat(obs2.metadata()).isNull();
                assertThat(obs2.safeDiagnostic()).isNull();
            }

            @Test
            @DisplayName("observeObjectPresence returns UNKNOWN when HEAD throws NoSuchBucket with HTTP 404")
            void shouldReturnUnknownWhenHeadObjectThrowsNoSuchBucketWithHttp404() {
                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder()
                                .statusCode(404)
                                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
                                .message("The specified bucket does not exist")
                                .build());

                StorageObjectPresenceObservation obs = adapter.observeObjectPresence(key);

                assertThat(obs.presence()).isEqualTo(StorageObjectPresence.UNKNOWN);
                assertThat(obs.presence()).isNotEqualTo(StorageObjectPresence.NOT_FOUND);
                assertThat(obs.metadata()).isNull();
                assertThat(obs.safeDiagnostic()).isEqualTo("PROVIDER_BUCKET_NOT_FOUND");
            }

            @Test
            @DisplayName("observeObjectPresence returns UNKNOWN when HEAD throws generic unexplained HTTP 404")
            void shouldReturnUnknownWhenHeadObjectThrowsGenericHttp404WithoutExplicitNoSuchKey() {
                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder()
                                .statusCode(404)
                                .message("Not Found")
                                .build());

                StorageObjectPresenceObservation obs = adapter.observeObjectPresence(key);

                assertThat(obs.presence()).isEqualTo(StorageObjectPresence.UNKNOWN);
                assertThat(obs.presence()).isNotEqualTo(StorageObjectPresence.NOT_FOUND);
                assertThat(obs.metadata()).isNull();
                assertThat(obs.safeDiagnostic()).isEqualTo("PROVIDER_RESOURCE_NOT_FOUND");
            }

            @Test
            @DisplayName("observeObjectPresence returns UNKNOWN on 403, 500, or timeout")
            void shouldReturnUnknownOnHeadObjectError() {
                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder().statusCode(403).message("Forbidden").build());

                StorageObjectPresenceObservation obs403 = adapter.observeObjectPresence(key);
                assertThat(obs403.presence()).isEqualTo(StorageObjectPresence.UNKNOWN);
                assertThat(obs403.safeDiagnostic()).isEqualTo("PROVIDER_ACCESS_DENIED");

                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow(SdkClientException.create("connect timed out"));

                StorageObjectPresenceObservation obsTimeout = adapter.observeObjectPresence(key);
                assertThat(obsTimeout.presence()).isEqualTo(StorageObjectPresence.UNKNOWN);
                assertThat(obsTimeout.safeDiagnostic()).isEqualTo("TIMEOUT_UNCERTAINTY");
            }

            @Test
            @DisplayName("observeObjectPresence validates non-null key")
            void shouldValidateParametersForObjectPresence() {
                assertThatThrownBy(() -> adapter.observeObjectPresence(null))
                        .isInstanceOf(NullPointerException.class);
            }

            @Test
            @DisplayName("observeUploadedParts returns PARTS_RETURNED when parts exist")
            void shouldReturnPartsReturnedWhenPartsExist() {
                Part p1 = Part.builder().partNumber(1).size(5242880L).eTag("\"p1-etag\"").build();
                Part p2 = Part.builder().partNumber(2).size(5242880L).eTag("\"p2-etag\"").build();

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(ListPartsResponse.builder()
                                .parts(List.of(p1, p2))
                                .isTruncated(false)
                                .build());

                MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs.storageKey()).isEqualTo(key);
                assertThat(obs.uploadId()).isEqualTo(uploadId);
                assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.PARTS_RETURNED);
                assertThat(obs.parts()).hasSize(2);
                assertThat(obs.parts().get(0).partNumber()).isEqualTo(1);
                assertThat(obs.parts().get(0).sizeBytes()).isEqualTo(5242880L);
                assertThat(obs.parts().get(0).eTag()).isEqualTo("\"p1-etag\"");
                assertThat(obs.safeDiagnostic()).isNull();

                ArgumentCaptor<ListPartsRequest> captor = ArgumentCaptor.forClass(ListPartsRequest.class);
                verify(s3Client).listParts(captor.capture());
                assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
                assertThat(captor.getValue().key()).isEqualTo(key.value());
                assertThat(captor.getValue().uploadId()).isEqualTo(uploadId);
                assertThat(captor.getValue().overrideConfiguration().flatMap(c -> c.apiCallTimeout()))
                        .contains(B2StorageAdapter.OBSERVATION_API_CALL_TIMEOUT);
            }

            @Test
            @DisplayName("observeUploadedParts returns ZERO_PARTS_OBSERVED when listing is empty")
            void shouldReturnZeroPartsObservedWhenListingEmpty() {
                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(ListPartsResponse.builder()
                                .parts(List.of())
                                .isTruncated(false)
                                .build());

                MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.ZERO_PARTS_OBSERVED);
                assertThat(obs.parts()).isEmpty();
                assertThat(obs.safeDiagnostic()).isNull();
            }

            @Test
            @DisplayName("observeUploadedParts returns NO_SUCH_UPLOAD distinctly on NoSuchUploadException or S3Exception")
            void shouldReturnNoSuchUploadDistinctly() {
                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenThrow(NoSuchUploadException.builder().statusCode(404).message("The specified upload does not exist").build());

                MultipartPartsObservation obs1 = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs1.summary()).isEqualTo(MultipartPartsSummary.NO_SUCH_UPLOAD);
                assertThat(obs1.parts()).isEmpty();
                assertThat(obs1.safeDiagnostic()).isNull();

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder()
                                .statusCode(404)
                                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchUpload").build())
                                .message("NoSuchUpload")
                                .build());

                MultipartPartsObservation obs2 = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs2.summary()).isEqualTo(MultipartPartsSummary.NO_SUCH_UPLOAD);
                assertThat(obs2.parts()).isEmpty();
                assertThat(obs2.safeDiagnostic()).isNull();
            }

            @Test
            @DisplayName("observeUploadedParts returns UNKNOWN when truncated parts listing has missing NextPartNumberMarker")
            void shouldReturnUnknownWhenTruncatedPartsListingMissingNextPartMarker() {
                Part p1 = Part.builder().partNumber(1).size(5242880L).eTag("\"p1\"").build();

                ListPartsResponse truncatedMissingMarker = ListPartsResponse.builder()
                        .parts(List.of(p1))
                        .isTruncated(true)
                        .nextPartNumberMarker(null)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(truncatedMissingMarker);

                MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obs.safeDiagnostic()).contains("MALFORMED_PAGINATION: truncated parts listing missing NextPartNumberMarker");
            }

            @Test
            @DisplayName("observeUploadedParts returns UNKNOWN when part marker is repeated")
            void shouldReturnUnknownWhenPartMarkerIsRepeated() {
                Part p1 = Part.builder().partNumber(1).size(5242880L).eTag("\"p1\"").build();
                Part p2 = Part.builder().partNumber(2).size(5242880L).eTag("\"p2\"").build();

                ListPartsResponse page1 = ListPartsResponse.builder()
                        .parts(List.of(p1))
                        .isTruncated(true)
                        .nextPartNumberMarker(1)
                        .build();

                ListPartsResponse page2 = ListPartsResponse.builder()
                        .parts(List.of(p2))
                        .isTruncated(true)
                        .nextPartNumberMarker(1)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2);

                MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obs.safeDiagnostic()).contains("NON_PROGRESSING_PAGINATION: repeated part marker");
            }

            @Test
            @DisplayName("observeUploadedParts returns UNKNOWN when part marker regresses")
            void shouldReturnUnknownWhenPartMarkerRegresses() {
                Part p1 = Part.builder().partNumber(5).size(5242880L).eTag("\"p5\"").build();
                Part p2 = Part.builder().partNumber(6).size(5242880L).eTag("\"p6\"").build();

                ListPartsResponse page1 = ListPartsResponse.builder()
                        .parts(List.of(p1))
                        .isTruncated(true)
                        .nextPartNumberMarker(5)
                        .build();

                ListPartsResponse page2 = ListPartsResponse.builder()
                        .parts(List.of(p2))
                        .isTruncated(true)
                        .nextPartNumberMarker(3)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2);

                MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obs.safeDiagnostic()).contains("NON_PROGRESSING_PAGINATION: regressing part marker");
            }

            @Test
            @DisplayName("observeUploadedParts propagates partNumberMarker across pages and returns PARTS_RETURNED")
            void shouldTraversePaginationForUploadedParts() {
                Part p1 = Part.builder().partNumber(1).size(5242880L).eTag("\"p1\"").build();
                Part p2 = Part.builder().partNumber(2).size(5242880L).eTag("\"p2\"").build();

                ListPartsResponse page1 = ListPartsResponse.builder()
                        .parts(List.of(p1))
                        .isTruncated(true)
                        .nextPartNumberMarker(1)
                        .build();

                ListPartsResponse page2 = ListPartsResponse.builder()
                        .parts(List.of(p2))
                        .isTruncated(false)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2);

                MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.PARTS_RETURNED);
                assertThat(obs.parts()).hasSize(2);

                ArgumentCaptor<ListPartsRequest> captor = ArgumentCaptor.forClass(ListPartsRequest.class);
                verify(s3Client, org.mockito.Mockito.times(2)).listParts(captor.capture());
                List<ListPartsRequest> requests = captor.getAllValues();
                assertThat(requests.get(0).partNumberMarker()).isNull();
                assertThat(requests.get(1).partNumberMarker()).isEqualTo(1);
            }

            @Test
            @DisplayName("observeUploadedParts never returns ZERO_PARTS_OBSERVED when pagination is truncated and empty or fails")
            void shouldNeverReturnZeroPartsObservedWhenPaginationIsIncomplete() {
                ListPartsResponse truncatedEmpty = ListPartsResponse.builder()
                        .parts(List.of())
                        .isTruncated(true)
                        .nextPartNumberMarker(1)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(truncatedEmpty);

                MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obs.summary()).isNotEqualTo(MultipartPartsSummary.ZERO_PARTS_OBSERVED);
                assertThat(obs.safeDiagnostic()).contains("MALFORMED_PAGINATION: truncated parts listing with empty parts");
            }

            @Test
            @DisplayName("observeUploadedParts returns UNKNOWN when pagination budget is exceeded")
            void shouldReturnUnknownWhenPartsPaginationBudgetExceeded() {
                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenAnswer(invocation -> {
                            ListPartsRequest req = invocation.getArgument(0);
                            int marker = req.partNumberMarker() != null ? req.partNumberMarker() : 0;
                            int nextPart = marker + 1;
                            Part part = Part.builder().partNumber(nextPart).size(5242880L).eTag("\"p" + nextPart + "\"").build();
                            return ListPartsResponse.builder()
                                    .parts(List.of(part))
                                    .isTruncated(true)
                                    .nextPartNumberMarker(nextPart)
                                    .build();
                        });

                MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obs.safeDiagnostic()).contains("PAGINATION_BUDGET_EXCEEDED");
                verify(s3Client, org.mockito.Mockito.times(B2StorageAdapter.OBSERVATION_MAX_PAGES)).listParts(any(ListPartsRequest.class));
            }

            @Test
            @DisplayName("observeUploadedParts returns UNKNOWN on S3Exception or timeout")
            void shouldReturnUnknownOnPartsError() {
                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder().statusCode(500).message("Server error").build());

                MultipartPartsObservation obs500 = adapter.observeUploadedParts(key, uploadId);
                assertThat(obs500.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obs500.safeDiagnostic()).isEqualTo("PROVIDER_ERROR");

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenThrow(SdkClientException.create("Request timeout"));

                MultipartPartsObservation obsTimeout = adapter.observeUploadedParts(key, uploadId);
                assertThat(obsTimeout.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obsTimeout.safeDiagnostic()).isEqualTo("TIMEOUT_UNCERTAINTY");
            }

            @Test
            @DisplayName("observeUploadedParts validates parameters")
            void shouldValidateParametersForUploadedParts() {
                assertThatThrownBy(() -> adapter.observeUploadedParts(null, uploadId))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.observeUploadedParts(key, null))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.observeUploadedParts(key, "   "))
                        .isInstanceOf(IllegalArgumentException.class);
            }

            @Test
            @DisplayName("safe diagnostic never echoes unknown provider error codes or sensitive values")
            void shouldNeverEchoUnknownProviderErrorCodeOrSensitiveValuesInDiagnostic() {
                String sensitiveCode = "InternalError: Token=SECRET_API_TOKEN_999&sig=ABC123XYZ&signedUrl=https://b2.backblaze.com/v1/auth?sig=topsecret";

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder()
                                .statusCode(500)
                                .awsErrorDetails(AwsErrorDetails.builder().errorCode(sensitiveCode).build())
                                .message("Sensitive raw message: " + sensitiveCode)
                                .build());

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.safeDiagnostic()).isEqualTo("PROVIDER_ERROR");
                assertThat(obs.safeDiagnostic()).doesNotContain("SECRET_API_TOKEN");
                assertThat(obs.safeDiagnostic()).doesNotContain("ABC123XYZ");
                assertThat(obs.safeDiagnostic()).doesNotContain("signedUrl");
                assertThat(obs.safeDiagnostic()).doesNotContain("backblaze");
                assertThat(obs.safeDiagnostic()).doesNotContain("topsecret");
                assertThat(obs.safeDiagnostic()).doesNotContain("InternalError");
            }

            @Test
            @DisplayName("safe diagnostic categories are deterministic and bounded")
            void shouldProduceDeterministicSafeDiagnosticCategories() {
                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder()
                                .statusCode(403)
                                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                                .build());

                StorageObjectPresenceObservation obsDenied = adapter.observeObjectPresence(key);
                assertThat(obsDenied.safeDiagnostic()).isEqualTo("PROVIDER_ACCESS_DENIED");

                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder()
                                .statusCode(503)
                                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ServiceUnavailable").build())
                                .build());

                StorageObjectPresenceObservation obs503 = adapter.observeObjectPresence(key);
                assertThat(obs503.safeDiagnostic()).isEqualTo("PROVIDER_SERVICE_UNAVAILABLE");
            }

            @Test
            @DisplayName("observeMultipartUpload allows PRESENT object and ACTIVE upload to coexist independently")
            void shouldAllowPresentObjectAndActiveUploadToCoexist() {
                MultipartUpload upload = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId(uploadId)
                        .build();
                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(ListMultipartUploadsResponse.builder()
                                .uploads(List.of(upload))
                                .isTruncated(false)
                                .build());

                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenReturn(HeadObjectResponse.builder()
                                .contentLength(20971520L)
                                .contentType("video/mp4")
                                .eTag("\"coexist-head-etag\"")
                                .build());

                Part part = Part.builder().partNumber(1).size(5242880L).eTag("\"p1-etag\"").build();
                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(ListPartsResponse.builder()
                                .parts(List.of(part))
                                .isTruncated(false)
                                .build());

                MultipartUploadObservationSnapshot snapshot = adapter.observeMultipartUpload(key, uploadId);

                assertThat(snapshot.storageKey()).isEqualTo(key);
                assertThat(snapshot.uploadId()).isEqualTo(uploadId);
                assertThat(snapshot.uploadPresence()).isEqualTo(MultipartUploadPresence.ACTIVE);
                assertThat(snapshot.objectPresence()).isEqualTo(StorageObjectPresence.PRESENT);
                assertThat(snapshot.partsSummary()).isEqualTo(MultipartPartsSummary.PARTS_RETURNED);
                assertThat(snapshot.parts()).hasSize(1);
                assertThat(snapshot.objectMetadata()).isNotNull();
                assertThat(snapshot.objectMetadata().eTag()).isEqualTo("\"coexist-head-etag\"");
                assertThat(snapshot.safeDiagnostic()).isNull();

                verify(s3Client, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
                verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
            }

            @Test
            @DisplayName("observeMultipartUpload preserves NOT_FOUND and NO_SUCH_UPLOAD independently without aggregate cleanup")
            void shouldPreserveNotFoundAndNoSuchUploadIndependently() {
                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(ListMultipartUploadsResponse.builder()
                                .uploads(List.of())
                                .isTruncated(false)
                                .build());

                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow(NoSuchKeyException.builder().statusCode(404).message("NoSuchKey").build());

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenThrow(NoSuchUploadException.builder().statusCode(404).message("NoSuchUpload").build());

                MultipartUploadObservationSnapshot snapshot = adapter.observeMultipartUpload(key, uploadId);

                assertThat(snapshot.uploadPresence()).isEqualTo(MultipartUploadPresence.NOT_LISTED);
                assertThat(snapshot.objectPresence()).isEqualTo(StorageObjectPresence.NOT_FOUND);
                assertThat(snapshot.partsSummary()).isEqualTo(MultipartPartsSummary.NO_SUCH_UPLOAD);
                assertThat(snapshot.parts()).isEmpty();
                assertThat(snapshot.objectMetadata()).isNull();

                verify(s3Client, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
                verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
            }

            @Test
            @DisplayName("observeMultipartUpload captures partial diagnostics when one operation fails and never invokes abort or delete")
            void shouldCapturePartialDiagnosticsAndNeverMutateState() {
                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(ListMultipartUploadsResponse.builder()
                                .uploads(List.of())
                                .isTruncated(false)
                                .build());

                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow((S3Exception) S3Exception.builder().statusCode(403).message("Access denied").build());

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenThrow(SdkClientException.create("connect timed out"));

                MultipartUploadObservationSnapshot snapshot = adapter.observeMultipartUpload(key, uploadId);

                assertThat(snapshot.uploadPresence()).isEqualTo(MultipartUploadPresence.NOT_LISTED);
                assertThat(snapshot.objectPresence()).isEqualTo(StorageObjectPresence.UNKNOWN);
                assertThat(snapshot.partsSummary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(snapshot.safeDiagnostic()).contains("objectPresence: PROVIDER_ACCESS_DENIED");
                assertThat(snapshot.safeDiagnostic()).contains("partsSummary: TIMEOUT_UNCERTAINTY");

                verify(s3Client, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
                verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
                verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
            }

            @Test
            @DisplayName("observeMultipartUpload validates parameters")
            void shouldValidateParametersForCompositeObservation() {
                assertThatThrownBy(() -> adapter.observeMultipartUpload(null, uploadId))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.observeMultipartUpload(key, null))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> adapter.observeMultipartUpload(key, "   "))
                        .isInstanceOf(IllegalArgumentException.class);
            }

            @Test
            @DisplayName("observeUploadedParts returns UNKNOWN when NextPartNumberMarker skips beyond last part on page")
            void shouldReturnUnknownWhenNextPartNumberMarkerSkipsBeyondLastPart() {
                Part p1 = Part.builder().partNumber(1).size(5242880L).eTag("\"p1\"").build();

                ListPartsResponse page1 = ListPartsResponse.builder()
                        .parts(List.of(p1))
                        .isTruncated(true)
                        .nextPartNumberMarker(100)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class))).thenReturn(page1);

                MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obs.parts()).isEmpty();
                assertThat(obs.safeDiagnostic()).contains("MALFORMED_PAGINATION: NextPartNumberMarker (100) inconsistent with last part on page (1)");
            }

            @Test
            @DisplayName("observeUploadedParts accepts valid multi-page listing with gaps between part numbers")
            void shouldAcceptValidMultiPagePartsListingWithGaps() {
                Part p1 = Part.builder().partNumber(1).size(5242880L).eTag("\"p1\"").build();
                Part p100 = Part.builder().partNumber(100).size(5242880L).eTag("\"p100\"").build();

                ListPartsResponse page1 = ListPartsResponse.builder()
                        .parts(List.of(p1))
                        .isTruncated(true)
                        .nextPartNumberMarker(1)
                        .build();

                ListPartsResponse page2 = ListPartsResponse.builder()
                        .parts(List.of(p100))
                        .isTruncated(false)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2);

                MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.PARTS_RETURNED);
                assertThat(obs.parts()).hasSize(2);
                assertThat(obs.parts().get(0).partNumber()).isEqualTo(1);
                assertThat(obs.parts().get(1).partNumber()).isEqualTo(100);
                assertThat(obs.safeDiagnostic()).isNull();
            }

            @Test
            @DisplayName("observeUploadedParts returns UNKNOWN when second page returns part number <= requested marker")
            void shouldReturnUnknownWhenSecondPageReturnsPartNumberLessThanOrEqualToMarker() {
                Part p5 = Part.builder().partNumber(5).size(5242880L).eTag("\"p5\"").build();
                Part p5Equal = Part.builder().partNumber(5).size(5242880L).eTag("\"p5-equal\"").build();

                ListPartsResponse page1 = ListPartsResponse.builder()
                        .parts(List.of(p5))
                        .isTruncated(true)
                        .nextPartNumberMarker(5)
                        .build();

                ListPartsResponse page2 = ListPartsResponse.builder()
                        .parts(List.of(p5Equal))
                        .isTruncated(false)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2);

                MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obs.parts()).isEmpty();
                assertThat(obs.safeDiagnostic()).contains("MALFORMED_PAGINATION: part number (5) not greater than marker (5)");

                Part p2Regressed = Part.builder().partNumber(2).size(5242880L).eTag("\"p2\"").build();
                ListPartsResponse page2Regressed = ListPartsResponse.builder()
                        .parts(List.of(p2Regressed))
                        .isTruncated(false)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(page1)
                        .thenReturn(page2Regressed);

                MultipartPartsObservation obsRegressed = adapter.observeUploadedParts(key, uploadId);

                assertThat(obsRegressed.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obsRegressed.parts()).isEmpty();
                assertThat(obsRegressed.safeDiagnostic()).contains("MALFORMED_PAGINATION: part number (2) not greater than marker (5)");
            }

            @Test
            @DisplayName("observeUploadedParts returns UNKNOWN and never ZERO_PARTS_OBSERVED when response contains Part with missing part number")
            void shouldReturnUnknownAndNeverZeroPartsWhenPartHasMissingPartNumber() {
                Part nullPartNumber = Part.builder().partNumber(null).size(5242880L).eTag("\"p-null\"").build();

                ListPartsResponse response = ListPartsResponse.builder()
                        .parts(List.of(nullPartNumber))
                        .isTruncated(false)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class))).thenReturn(response);

                MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obs.summary()).isNotEqualTo(MultipartPartsSummary.ZERO_PARTS_OBSERVED);
                assertThat(obs.parts()).isEmpty();
                assertThat(obs.safeDiagnostic()).contains("MALFORMED_PAGINATION: malformed or null part record");
            }

            @Test
            @DisplayName("observeUploadedParts returns UNKNOWN when response contains Part with invalid part number")
            void shouldReturnUnknownWhenPartNumberOutOfRange() {
                Part p0 = Part.builder().partNumber(0).size(5242880L).eTag("\"p0\"").build();

                ListPartsResponse response0 = ListPartsResponse.builder()
                        .parts(List.of(p0))
                        .isTruncated(false)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class))).thenReturn(response0);

                MultipartPartsObservation obs0 = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs0.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obs0.parts()).isEmpty();
                assertThat(obs0.safeDiagnostic()).contains("MALFORMED_PAGINATION: part number out of range (0)");

                Part p10001 = Part.builder().partNumber(10_001).size(5242880L).eTag("\"p10001\"").build();

                ListPartsResponse response10001 = ListPartsResponse.builder()
                        .parts(List.of(p10001))
                        .isTruncated(false)
                        .build();

                when(s3Client.listParts(any(ListPartsRequest.class))).thenReturn(response10001);

                MultipartPartsObservation obs10001 = adapter.observeUploadedParts(key, uploadId);

                assertThat(obs10001.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                assertThat(obs10001.parts()).isEmpty();
                assertThat(obs10001.safeDiagnostic()).contains("MALFORMED_PAGINATION: part number out of range (10001)");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns UNKNOWN not NOT_LISTED when upload record lacks usable uploadId")
            void shouldReturnUnknownWhenUploadRecordLacksUsableUploadId() {
                MultipartUpload uploadNullId = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId(null)
                        .build();

                ListMultipartUploadsResponse response = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(uploadNullId))
                        .isTruncated(false)
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenReturn(response);

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.presence()).isNotEqualTo(MultipartUploadPresence.NOT_LISTED);
                assertThat(obs.safeDiagnostic()).contains("MALFORMED_PAGINATION: malformed upload record with missing key or uploadId");

                MultipartUpload uploadBlankId = MultipartUpload.builder()
                        .key(key.value())
                        .uploadId("   ")
                        .build();

                ListMultipartUploadsResponse responseBlank = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(uploadBlankId))
                        .isTruncated(false)
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenReturn(responseBlank);

                MultipartUploadPresenceObservation obsBlank = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obsBlank.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obsBlank.presence()).isNotEqualTo(MultipartUploadPresence.NOT_LISTED);
                assertThat(obsBlank.safeDiagnostic()).contains("MALFORMED_PAGINATION: malformed upload record with missing key or uploadId");
            }

            @Test
            @DisplayName("observeMultipartUploadPresence returns UNKNOWN when upload record lacks usable key")
            void shouldReturnUnknownWhenUploadRecordLacksUsableKey() {
                MultipartUpload uploadNullKey = MultipartUpload.builder()
                        .key(null)
                        .uploadId("some-upload-id")
                        .build();

                ListMultipartUploadsResponse responseNullKey = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(uploadNullKey))
                        .isTruncated(false)
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenReturn(responseNullKey);

                MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obs.presence()).isNotEqualTo(MultipartUploadPresence.NOT_LISTED);
                assertThat(obs.safeDiagnostic()).contains("MALFORMED_PAGINATION: malformed upload record with missing key or uploadId");

                MultipartUpload uploadBlankKey = MultipartUpload.builder()
                        .key("  ")
                        .uploadId("some-upload-id")
                        .build();

                ListMultipartUploadsResponse responseBlankKey = ListMultipartUploadsResponse.builder()
                        .uploads(List.of(uploadBlankKey))
                        .isTruncated(false)
                        .build();

                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenReturn(responseBlankKey);

                MultipartUploadPresenceObservation obsBlank = adapter.observeMultipartUploadPresence(key, uploadId);

                assertThat(obsBlank.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                assertThat(obsBlank.presence()).isNotEqualTo(MultipartUploadPresence.NOT_LISTED);
                assertThat(obsBlank.safeDiagnostic()).contains("MALFORMED_PAGINATION: malformed upload record with missing key or uploadId");
            }

            @Test
            @DisplayName("observation operations never invoke AbortMultipartUpload or DeleteObject")
            void shouldNeverInvokeAbortMultipartUploadOrDeleteObjectDuringObservation() {
                when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                        .thenReturn(ListMultipartUploadsResponse.builder().uploads(List.of()).isTruncated(false).build());
                when(s3Client.headObject(any(HeadObjectRequest.class)))
                        .thenThrow(NoSuchKeyException.builder().statusCode(404).message("Not Found").build());
                when(s3Client.listParts(any(ListPartsRequest.class)))
                        .thenReturn(ListPartsResponse.builder().parts(List.of()).isTruncated(false).build());

                MultipartUploadObservationSnapshot snapshot = adapter.observeMultipartUpload(key, uploadId);

                assertThat(snapshot).isNotNull();
                verify(s3Client, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
                verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));

                S3Client separateAbortClient = mock(S3Client.class);
                B2StorageAdapter dualAdapter = new B2StorageAdapter(BUCKET, s3Client, separateAbortClient, s3Presigner, testTempDir);
                MultipartUploadObservationSnapshot dualSnapshot = dualAdapter.observeMultipartUpload(key, uploadId);
                assertThat(dualSnapshot).isNotNull();
                verify(separateAbortClient, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
                verify(separateAbortClient, never()).deleteObject(any(DeleteObjectRequest.class));
            }

            @Nested
            @DisplayName("Bounded Observation Budget and Timeout Execution Tests")
            class BoundedObservationExecutionTests {

                private final StorageKey key = StorageKey.of("media/sessions/bounded-test.mp4");
                private final String uploadId = "bounded-upload-999";

                private AtomicLong virtualNanoTime;
                private B2StorageAdapter adapterWithClock;

                @BeforeEach
                void setUpClock() {
                    virtualNanoTime = new AtomicLong(1_000_000_000L);
                    adapterWithClock = new B2StorageAdapter(
                            BUCKET,
                            s3Client,
                            s3Presigner,
                            testTempDir,
                            () -> virtualNanoTime.get()
                    );
                }

                @Test
                @DisplayName("1. One successful observation within the 30s budget")
                void shouldSucceedWithinOperationBudget() {
                    MultipartUpload upload = MultipartUpload.builder().key(key.value()).uploadId(uploadId).build();
                    when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenAnswer(inv -> {
                        virtualNanoTime.addAndGet(Duration.ofSeconds(1).toNanos());
                        return ListMultipartUploadsResponse.builder().uploads(List.of(upload)).isTruncated(false).build();
                    });

                    MultipartUploadPresenceObservation obs = adapterWithClock.observeMultipartUploadPresence(key, uploadId);

                    assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.ACTIVE);
                    assertThat(obs.safeDiagnostic()).isNull();
                }

                @Test
                @DisplayName("2. Valid two-page listing within a shared budget")
                void shouldCompleteTwoPageListingWithinBudget() {
                    MultipartUpload otherUpload = MultipartUpload.builder().key(key.value()).uploadId("other-1").build();
                    MultipartUpload targetUpload = MultipartUpload.builder().key(key.value()).uploadId(uploadId).build();

                    when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenAnswer(inv -> {
                        ListMultipartUploadsRequest req = inv.getArgument(0);
                        virtualNanoTime.addAndGet(Duration.ofSeconds(2).toNanos());
                        if (req.uploadIdMarker() == null) {
                            return ListMultipartUploadsResponse.builder()
                                    .uploads(List.of(otherUpload))
                                    .isTruncated(true)
                                    .nextKeyMarker(key.value())
                                    .nextUploadIdMarker("other-1")
                                    .build();
                        } else {
                            return ListMultipartUploadsResponse.builder()
                                    .uploads(List.of(targetUpload))
                                    .isTruncated(false)
                                    .build();
                        }
                    });

                    MultipartUploadPresenceObservation obs = adapterWithClock.observeMultipartUploadPresence(key, uploadId);

                    assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.ACTIVE);
                    assertThat(obs.safeDiagnostic()).isNull();
                    verify(s3Client, times(2)).listMultipartUploads(any(ListMultipartUploadsRequest.class));
                }

                @Test
                @DisplayName("3. Deadline exhausted before page two => UNKNOWN")
                void shouldReturnUnknownWhenDeadlineExhaustedBeforePageTwo() {
                    MultipartUpload otherUpload = MultipartUpload.builder().key(key.value()).uploadId("other-1").build();

                    when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenAnswer(inv -> {
                        virtualNanoTime.addAndGet(Duration.ofSeconds(31).toNanos());
                        return ListMultipartUploadsResponse.builder()
                                .uploads(List.of(otherUpload))
                                .isTruncated(true)
                                .nextKeyMarker(key.value())
                                .nextUploadIdMarker("other-1")
                                .build();
                    });

                    MultipartUploadPresenceObservation obs = adapterWithClock.observeMultipartUploadPresence(key, uploadId);

                    assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                    assertThat(obs.safeDiagnostic()).isEqualTo("OBSERVATION_TIMEOUT_EXCEEDED");
                    verify(s3Client, times(1)).listMultipartUploads(any(ListMultipartUploadsRequest.class));
                }

                @Test
                @DisplayName("4. No SDK call after deadline exhaustion")
                void shouldNotDispatchSdkCallIfAlreadyExhaustedAtStart() {
                    AtomicInteger clockCalls = new AtomicInteger();
                    B2StorageAdapter fastExpiringAdapter = new B2StorageAdapter(
                            BUCKET,
                            s3Client,
                            s3Presigner,
                            testTempDir,
                            () -> {
                                int c = clockCalls.incrementAndGet();
                                return c == 1 ? 1_000_000_000L : 32_000_000_000L;
                            }
                    );

                    MultipartUploadPresenceObservation obs = fastExpiringAdapter.observeMultipartUploadPresence(key, uploadId);

                    assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                    assertThat(obs.safeDiagnostic()).isEqualTo("OBSERVATION_TIMEOUT_EXCEEDED");
                    verify(s3Client, never()).listMultipartUploads(any(ListMultipartUploadsRequest.class));
                }

                @Test
                @DisplayName("5, 6, 7. Composite presence succeeds, deadline expires before HEAD, dimensions skipped, ACTIVE preserved")
                void shouldPreserveActivePresenceWhenDeadlineExpiresBeforeHeadInComposite() {
                    MultipartUpload upload = MultipartUpload.builder().key(key.value()).uploadId(uploadId).build();
                    AtomicInteger clockReads = new AtomicInteger();
                    B2StorageAdapter compositeExpiryAdapter = new B2StorageAdapter(
                            BUCKET,
                            s3Client,
                            s3Presigner,
                            testTempDir,
                            () -> {
                                int r = clockReads.incrementAndGet();
                                return (r <= 3) ? 1_000_000_000L : 35_000_000_000L;
                            }
                    );

                    when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                            .thenReturn(ListMultipartUploadsResponse.builder().uploads(List.of(upload)).isTruncated(false).build());

                    MultipartUploadObservationSnapshot snapshot = compositeExpiryAdapter.observeMultipartUpload(key, uploadId);

                    assertThat(snapshot.uploadPresence()).isEqualTo(MultipartUploadPresence.ACTIVE);
                    assertThat(snapshot.objectPresence()).isEqualTo(StorageObjectPresence.UNKNOWN);
                    assertThat(snapshot.partsSummary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                    assertThat(snapshot.parts()).isEmpty();
                    assertThat(snapshot.objectMetadata()).isNull();
                    assertThat(snapshot.safeDiagnostic())
                            .contains("objectPresence: OBSERVATION_TIMEOUT_EXCEEDED")
                            .contains("partsSummary: OBSERVATION_TIMEOUT_EXCEEDED");

                    verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
                    verify(s3Client, never()).listParts(any(ListPartsRequest.class));
                }

                @Test
                @DisplayName("8, 9. SDK call timeout clamped to remaining time and attempt timeout safely bounded")
                void shouldClampCallAndAttemptTimeoutToRemainingBudget() {
                    AtomicInteger clockCalls = new AtomicInteger();
                    B2StorageAdapter clampingAdapter = new B2StorageAdapter(
                            BUCKET,
                            s3Client,
                            s3Presigner,
                            testTempDir,
                            () -> {
                                int c = clockCalls.incrementAndGet();
                                return (c == 1) ? 1_000_000_000L : 21_000_000_000L;
                            }
                    );

                    when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                            .thenReturn(ListMultipartUploadsResponse.builder().uploads(List.of()).isTruncated(false).build());

                    clampingAdapter.observeMultipartUploadPresence(key, uploadId);

                    ArgumentCaptor<ListMultipartUploadsRequest> captor = ArgumentCaptor.forClass(ListMultipartUploadsRequest.class);
                    verify(s3Client).listMultipartUploads(captor.capture());

                    AwsRequestOverrideConfiguration override = captor.getValue().overrideConfiguration().orElseThrow();
                    assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(10));
                    assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofMillis(9950));
                    assertThat(override.apiCallAttemptTimeout().orElseThrow())
                            .isLessThan(override.apiCallTimeout().orElseThrow());
                }

                @Test
                @DisplayName("10, 11. Deadline expires while provider request executes; cannot yield false NOT_LISTED")
                void shouldReturnUnknownWhenDeadlineExpiresDuringListingExecution() {
                    when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenAnswer(inv -> {
                        virtualNanoTime.addAndGet(Duration.ofSeconds(32).toNanos());
                        return ListMultipartUploadsResponse.builder().uploads(List.of()).isTruncated(false).build();
                    });

                    MultipartUploadPresenceObservation obs = adapterWithClock.observeMultipartUploadPresence(key, uploadId);

                    assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                    assertThat(obs.presence()).isNotEqualTo(MultipartUploadPresence.NOT_LISTED);
                    assertThat(obs.safeDiagnostic()).isEqualTo("OBSERVATION_TIMEOUT_EXCEEDED");
                }

                @Test
                @DisplayName("12. Late completion cannot yield false complete parts observation")
                void shouldReturnUnknownWhenDeadlineExpiresDuringListPartsExecution() {
                    when(s3Client.listParts(any(ListPartsRequest.class))).thenAnswer(inv -> {
                        virtualNanoTime.addAndGet(Duration.ofSeconds(32).toNanos());
                        return ListPartsResponse.builder().parts(List.of()).isTruncated(false).build();
                    });

                    MultipartPartsObservation obs = adapterWithClock.observeUploadedParts(key, uploadId);

                    assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                    assertThat(obs.summary()).isNotEqualTo(MultipartPartsSummary.ZERO_PARTS_OBSERVED);
                    assertThat(obs.summary()).isNotEqualTo(MultipartPartsSummary.PARTS_RETURNED);
                    assertThat(obs.safeDiagnostic()).isEqualTo("OBSERVATION_TIMEOUT_EXCEEDED");
                }

                @Test
                @DisplayName("13. Standalone observations have independent budgets")
                void shouldGrantIndependentBudgetsToStandaloneCalls() {
                    when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenAnswer(inv -> {
                        virtualNanoTime.addAndGet(Duration.ofSeconds(25).toNanos());
                        return ListMultipartUploadsResponse.builder().uploads(List.of()).isTruncated(false).build();
                    });

                    MultipartUploadPresenceObservation obs1 = adapterWithClock.observeMultipartUploadPresence(key, uploadId);
                    assertThat(obs1.presence()).isEqualTo(MultipartUploadPresence.NOT_LISTED);

                    when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(inv -> {
                        virtualNanoTime.addAndGet(Duration.ofSeconds(10).toNanos());
                        return HeadObjectResponse.builder().contentLength(1024L).contentType("video/mp4").eTag("\"tag\"").build();
                    });

                    StorageObjectPresenceObservation obs2 = adapterWithClock.observeObjectPresence(key);
                    assertThat(obs2.presence()).isEqualTo(StorageObjectPresence.PRESENT);
                }

                @Test
                @DisplayName("14. Interrupted thread produces UNKNOWN without dispatch")
                void shouldReturnUnknownOnInterruptedThreadWithoutDispatchingSdkCalls() {
                    Thread.currentThread().interrupt();
                    try {
                        MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);
                        assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                        assertThat(obs.safeDiagnostic()).isEqualTo("OBSERVATION_INTERRUPTED");
                        assertThat(Thread.currentThread().isInterrupted()).isTrue();
                        verify(s3Client, never()).listMultipartUploads(any(ListMultipartUploadsRequest.class));
                    } finally {
                        Thread.interrupted();
                    }

                    Thread.currentThread().interrupt();
                    try {
                        StorageObjectPresenceObservation obs = adapter.observeObjectPresence(key);
                        assertThat(obs.presence()).isEqualTo(StorageObjectPresence.UNKNOWN);
                        assertThat(obs.safeDiagnostic()).isEqualTo("OBSERVATION_INTERRUPTED");
                        assertThat(Thread.currentThread().isInterrupted()).isTrue();
                        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
                    } finally {
                        Thread.interrupted();
                    }

                    Thread.currentThread().interrupt();
                    try {
                        MultipartPartsObservation obs = adapter.observeUploadedParts(key, uploadId);
                        assertThat(obs.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                        assertThat(obs.safeDiagnostic()).isEqualTo("OBSERVATION_INTERRUPTED");
                        assertThat(Thread.currentThread().isInterrupted()).isTrue();
                        verify(s3Client, never()).listParts(any(ListPartsRequest.class));
                    } finally {
                        Thread.interrupted();
                    }
                }

                @Test
                @DisplayName("15. Repeated marker pair/cycle produces UNKNOWN")
                void shouldDetectMarkerCyclesAndReturnUnknown() {
                    MultipartUpload up1 = MultipartUpload.builder().key(key.value()).uploadId("other-1").build();
                    MultipartUpload up2 = MultipartUpload.builder().key(key.value()).uploadId("other-2").build();
                    AtomicInteger pageCount = new AtomicInteger();

                    when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenAnswer(inv -> {
                        int p = pageCount.incrementAndGet();
                        if (p == 1) {
                            return ListMultipartUploadsResponse.builder()
                                    .uploads(List.of(up1))
                                    .isTruncated(true)
                                    .nextKeyMarker(key.value())
                                    .nextUploadIdMarker("other-1")
                                    .build();
                        } else if (p == 2) {
                            return ListMultipartUploadsResponse.builder()
                                    .uploads(List.of(up2))
                                    .isTruncated(true)
                                    .nextKeyMarker(key.value())
                                    .nextUploadIdMarker("other-2")
                                    .build();
                        } else {
                            return ListMultipartUploadsResponse.builder()
                                    .uploads(List.of(up1))
                                    .isTruncated(true)
                                    .nextKeyMarker(key.value())
                                    .nextUploadIdMarker("other-1")
                                    .build();
                        }
                    });

                    MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

                    assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                    assertThat(obs.safeDiagnostic()).isEqualTo("NON_PROGRESSING_PAGINATION: repeated marker pair");
                }

                @Test
                @DisplayName("16. Valid multi-page pagination remains supported within budget")
                void shouldSupportValidThreePagePagination() {
                    Part p1 = Part.builder().partNumber(1).size(500L).eTag("\"e1\"").build();
                    Part p2 = Part.builder().partNumber(2).size(500L).eTag("\"e2\"").build();
                    Part p3 = Part.builder().partNumber(3).size(500L).eTag("\"e3\"").build();
                    AtomicInteger partsPage = new AtomicInteger();

                    when(s3Client.listParts(any(ListPartsRequest.class))).thenAnswer(inv -> {
                        int p = partsPage.incrementAndGet();
                        if (p == 1) {
                            return ListPartsResponse.builder().parts(List.of(p1)).isTruncated(true).nextPartNumberMarker(1).build();
                        } else if (p == 2) {
                            return ListPartsResponse.builder().parts(List.of(p2)).isTruncated(true).nextPartNumberMarker(2).build();
                        } else {
                            return ListPartsResponse.builder().parts(List.of(p3)).isTruncated(false).build();
                        }
                    });

                    MultipartPartsObservation partsObs = adapter.observeUploadedParts(key, uploadId);

                    assertThat(partsObs.summary()).isEqualTo(MultipartPartsSummary.PARTS_RETURNED);
                    assertThat(partsObs.parts()).hasSize(3);
                    assertThat(partsObs.safeDiagnostic()).isNull();
                }

                @Test
                @DisplayName("18, 19. Observation methods never invoke AbortMultipartUpload or DeleteObject")
                void shouldNeverInvokeAbortOrDeleteDuringObservationLifecycle() {
                    when(s3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                            .thenReturn(ListMultipartUploadsResponse.builder().uploads(List.of()).isTruncated(false).build());
                    when(s3Client.headObject(any(HeadObjectRequest.class)))
                            .thenThrow(NoSuchKeyException.builder().statusCode(404).message("Not Found").build());
                    when(s3Client.listParts(any(ListPartsRequest.class)))
                            .thenReturn(ListPartsResponse.builder().parts(List.of()).isTruncated(false).build());

                    MultipartUploadObservationSnapshot snapshot = adapter.observeMultipartUpload(key, uploadId);

                    assertThat(snapshot).isNotNull();
                    verify(s3Client, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
                    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
                }

                @Test
                @DisplayName("20. ObservationBudget monotonic calculation, clamping, and zero-leak guarantees")
                void shouldVerifyObservationBudgetCalculations() {
                    AtomicLong clock = new AtomicLong(100_000_000L);
                    B2StorageAdapter.ObservationBudget budget = B2StorageAdapter.ObservationBudget.start(
                            clock::get,
                            Duration.ofSeconds(30)
                    );

                    assertThat(budget.remaining()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(budget.isExhausted()).isFalse();
                    assertThat(budget.hasSufficientTime(Duration.ofMillis(200))).isTrue();

                    clock.addAndGet(Duration.ofSeconds(15).toNanos());
                    assertThat(budget.remaining()).isEqualTo(Duration.ofSeconds(15));
                    assertThat(budget.isExhausted()).isFalse();

                    clock.addAndGet(Duration.ofSeconds(15).toNanos());
                    assertThat(budget.remaining()).isEqualTo(Duration.ZERO);
                    assertThat(budget.isExhausted()).isTrue();
                    assertThat(budget.hasSufficientTime(Duration.ofMillis(200))).isFalse();

                    clock.addAndGet(Duration.ofSeconds(1).toNanos());
                    assertThat(budget.remaining()).isEqualTo(Duration.ZERO);
                    assertThat(budget.isExhausted()).isTrue();
                    assertThat(budget.hasSufficientTime(Duration.ofMillis(200))).isFalse();
                }

                @Test
                @DisplayName("A. With 250ms remaining, derive valid positive call and attempt timeouts strictly positive and bounded")
                void shouldDeriveValidPositiveTimeoutsWhen250MsRemaining() {
                    AtomicLong clock = new AtomicLong(1_000_000_000L);
                    B2StorageAdapter.ObservationBudget budget = B2StorageAdapter.ObservationBudget.start(
                            clock::get,
                            Duration.ofSeconds(30)
                    );
                    clock.set(1_000_000_000L + Duration.ofSeconds(30).minusMillis(250).toNanos());

                    Optional<B2StorageAdapter.ObservationRequestPlan> planOpt = budget.planRequest();
                    assertThat(planOpt).isPresent();

                    B2StorageAdapter.ObservationRequestPlan plan = planOpt.get();
                    assertThat(plan.remaining()).isEqualTo(Duration.ofMillis(250));
                    assertThat(plan.apiCallTimeout()).isEqualTo(Duration.ofMillis(250));
                    assertThat(plan.apiCallAttemptTimeout()).isEqualTo(Duration.ofMillis(200));
                    assertThat(plan.apiCallTimeout()).isGreaterThan(Duration.ZERO);
                    assertThat(plan.apiCallAttemptTimeout()).isGreaterThan(Duration.ZERO);
                    assertThat(plan.apiCallAttemptTimeout()).isLessThan(plan.apiCallTimeout());
                    assertThat(plan.overrideConfiguration().apiCallTimeout()).contains(Duration.ofMillis(250));
                    assertThat(plan.overrideConfiguration().apiCallAttemptTimeout()).contains(Duration.ofMillis(200));
                }

                @Test
                @DisplayName("B. With 199ms remaining, return UNKNOWN without dispatch")
                void shouldReturnUnknownWithoutDispatchWhen199MsRemaining() {
                    AtomicInteger clockReads = new AtomicInteger();
                    B2StorageAdapter adapter199 = new B2StorageAdapter(
                            BUCKET,
                            s3Client,
                            s3Presigner,
                            testTempDir,
                            () -> {
                                int r = clockReads.incrementAndGet();
                                return (r == 1) ? 1_000_000_000L : 1_000_000_000L + Duration.ofSeconds(30).minusMillis(199).toNanos();
                            }
                    );

                    MultipartUploadPresenceObservation obs = adapter199.observeMultipartUploadPresence(key, uploadId);
                    assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                    assertThat(obs.safeDiagnostic()).isEqualTo("OBSERVATION_TIMEOUT_EXCEEDED");
                    verify(s3Client, never()).listMultipartUploads(any(ListMultipartUploadsRequest.class));
                }

                @Test
                @DisplayName("C. With zero remaining, return UNKNOWN without constructing an invalid SDK override")
                void shouldReturnUnknownWithoutInvalidOverrideWhenZeroRemaining() {
                    AtomicInteger clockReads = new AtomicInteger();
                    B2StorageAdapter adapterZero = new B2StorageAdapter(
                            BUCKET,
                            s3Client,
                            s3Presigner,
                            testTempDir,
                            () -> {
                                int r = clockReads.incrementAndGet();
                                return (r == 1) ? 1_000_000_000L : 1_000_000_000L + Duration.ofSeconds(30).toNanos();
                            }
                    );

                    MultipartUploadPresenceObservation obs = adapterZero.observeMultipartUploadPresence(key, uploadId);
                    assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                    assertThat(obs.safeDiagnostic()).isEqualTo("OBSERVATION_TIMEOUT_EXCEEDED");
                    verify(s3Client, never()).listMultipartUploads(any(ListMultipartUploadsRequest.class));
                }

                @Test
                @DisplayName("D. Clock jump from available time to exhausted time at planning boundary; no SDK call occurs")
                void shouldNotDispatchSdkCallWhenClockJumpsToExhaustedAtPlanningBoundary() {
                    AtomicInteger clockReads = new AtomicInteger();
                    B2StorageAdapter jumpingAdapter = new B2StorageAdapter(
                            BUCKET,
                            s3Client,
                            s3Presigner,
                            testTempDir,
                            () -> {
                                int r = clockReads.incrementAndGet();
                                return (r == 1) ? 1_000_000_000L : 36_000_000_000L;
                            }
                    );

                    MultipartUploadPresenceObservation obs = jumpingAdapter.observeMultipartUploadPresence(key, uploadId);
                    assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                    assertThat(obs.safeDiagnostic()).isEqualTo("OBSERVATION_TIMEOUT_EXCEEDED");
                    verify(s3Client, never()).listMultipartUploads(any(ListMultipartUploadsRequest.class));
                }

                @Test
                @DisplayName("E. Verify exactly one clock reading is used for dispatch decision and timeout computation")
                void shouldVerifyExactlyOneClockReadingPerPlannedRequest() {
                    AtomicInteger tickerCalls = new AtomicInteger();
                    B2StorageAdapter.ObservationBudget budget = B2StorageAdapter.ObservationBudget.start(
                            () -> {
                                tickerCalls.incrementAndGet();
                                return 10_000_000_000L;
                            },
                            Duration.ofSeconds(30)
                    );
                    assertThat(tickerCalls.get()).isEqualTo(1);

                    tickerCalls.set(0);
                    Optional<B2StorageAdapter.ObservationRequestPlan> plan = budget.planRequest();
                    assertThat(plan).isPresent();
                    assertThat(tickerCalls.get()).isEqualTo(1);
                }

                @Test
                @DisplayName("F. Both listing methods and HEAD use the same safe planning path")
                void shouldVerifyAllThreeObservationMethodsUseSamePlanningPathUnderBudgetExhaustion() {
                    // 1. observeMultipartUploadPresence
                    AtomicInteger clock1 = new AtomicInteger();
                    B2StorageAdapter adapter1 = new B2StorageAdapter(
                            BUCKET, s3Client, s3Presigner, testTempDir,
                            () -> (clock1.incrementAndGet() == 1) ? 1_000_000_000L : 1_000_000_000L + Duration.ofSeconds(30).minusMillis(100).toNanos()
                    );
                    MultipartUploadPresenceObservation presenceObs = adapter1.observeMultipartUploadPresence(key, uploadId);
                    assertThat(presenceObs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
                    assertThat(presenceObs.safeDiagnostic()).isEqualTo("OBSERVATION_TIMEOUT_EXCEEDED");
                    verify(s3Client, never()).listMultipartUploads(any(ListMultipartUploadsRequest.class));

                    // 2. observeObjectPresence
                    AtomicInteger clock2 = new AtomicInteger();
                    B2StorageAdapter adapter2 = new B2StorageAdapter(
                            BUCKET, s3Client, s3Presigner, testTempDir,
                            () -> (clock2.incrementAndGet() == 1) ? 1_000_000_000L : 1_000_000_000L + Duration.ofSeconds(30).minusMillis(100).toNanos()
                    );
                    StorageObjectPresenceObservation objObs = adapter2.observeObjectPresence(key);
                    assertThat(objObs.presence()).isEqualTo(StorageObjectPresence.UNKNOWN);
                    assertThat(objObs.safeDiagnostic()).isEqualTo("OBSERVATION_TIMEOUT_EXCEEDED");
                    verify(s3Client, never()).headObject(any(HeadObjectRequest.class));

                    // 3. observeUploadedParts
                    AtomicInteger clock3 = new AtomicInteger();
                    B2StorageAdapter adapter3 = new B2StorageAdapter(
                            BUCKET, s3Client, s3Presigner, testTempDir,
                            () -> (clock3.incrementAndGet() == 1) ? 1_000_000_000L : 1_000_000_000L + Duration.ofSeconds(30).minusMillis(100).toNanos()
                    );
                    MultipartPartsObservation partsObs = adapter3.observeUploadedParts(key, uploadId);
                    assertThat(partsObs.summary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
                    assertThat(partsObs.safeDiagnostic()).isEqualTo("OBSERVATION_TIMEOUT_EXCEEDED");
                    verify(s3Client, never()).listParts(any(ListPartsRequest.class));
                }

                @Test
                @DisplayName("G. Valid two-page pagination recomputes plan and succeeds")
                void shouldRecomputePlanPerPaginationPageAndSucceed() {
                    Part p1 = Part.builder().partNumber(1).size(500L).eTag("\"e1\"").build();
                    Part p2 = Part.builder().partNumber(2).size(500L).eTag("\"e2\"").build();
                    AtomicInteger pageNum = new AtomicInteger();

                    when(s3Client.listParts(any(ListPartsRequest.class))).thenAnswer(inv -> {
                        int p = pageNum.incrementAndGet();
                        if (p == 1) {
                            return ListPartsResponse.builder().parts(List.of(p1)).isTruncated(true).nextPartNumberMarker(1).build();
                        } else {
                            return ListPartsResponse.builder().parts(List.of(p2)).isTruncated(false).build();
                        }
                    });

                    MultipartPartsObservation partsObs = adapter.observeUploadedParts(key, uploadId);
                    assertThat(partsObs.summary()).isEqualTo(MultipartPartsSummary.PARTS_RETURNED);
                    assertThat(partsObs.parts()).hasSize(2);
                    assertThat(partsObs.safeDiagnostic()).isNull();

                    ArgumentCaptor<ListPartsRequest> captor = ArgumentCaptor.forClass(ListPartsRequest.class);
                    verify(s3Client, times(2)).listParts(captor.capture());
                    List<ListPartsRequest> capturedRequests = captor.getAllValues();
                    assertThat(capturedRequests.get(0).partNumberMarker()).isNull();
                    assertThat(capturedRequests.get(1).partNumberMarker()).isEqualTo(1);
                    assertThat(capturedRequests.get(0).overrideConfiguration()).isPresent();
                    assertThat(capturedRequests.get(1).overrideConfiguration()).isPresent();
                }
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

        @Test
        @DisplayName("close does not close unowned abort S3 client")
        void shouldNotCloseUnownedAbortClient() {
            S3Client separateAbortClient = mock(S3Client.class);
            B2StorageAdapter dualAdapter = new B2StorageAdapter(BUCKET, s3Client, separateAbortClient, s3Presigner, testTempDir);
            dualAdapter.close();
            verify(s3Client, never()).close();
            verify(separateAbortClient, never()).close();
            verify(s3Presigner, never()).close();
        }
    }
}
