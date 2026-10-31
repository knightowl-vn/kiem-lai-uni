package com.universe.media.infrastructure.storage.r2movie;

import com.universe.media.application.exceptions.StorageAmbiguousCompletionException;
import com.universe.media.application.exceptions.StorageAmbiguousInitiationException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageInitiationRejectedException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.exceptions.StorageUploadNotFoundException;
import com.universe.media.application.ports.storage.MultipartAbortResult;
import com.universe.media.application.ports.storage.MultipartCompletedObject;
import com.universe.media.application.ports.storage.MultipartObjectMetadata;
import com.universe.media.application.ports.storage.MultipartPartETag;
import com.universe.media.application.ports.storage.MultipartPartsObservation;
import com.universe.media.application.ports.storage.MultipartUploadObservationSnapshot;
import com.universe.media.application.ports.storage.MultipartUploadPresence;
import com.universe.media.application.ports.storage.MultipartUploadPresenceObservation;
import com.universe.media.application.ports.storage.MultipartUploadedPart;
import com.universe.media.application.ports.storage.StorageObjectPresence;
import com.universe.media.application.ports.storage.StorageObjectPresenceObservation;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
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
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class R2MovieStorageAdapterTest {

    private static final String BUCKET = "test-movie-r2-bucket";
    private static final StorageKey STORAGE_KEY = StorageKey.of("objects/movie/test-video.mp4");
    private static final MimeType MIME_MP4 = MimeType.of("video/mp4");

    private S3Client mockS3Client;
    private S3Client mockAbortS3Client;
    private S3Presigner mockS3Presigner;
    private R2MovieStorageAdapter adapter;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        mockS3Client = mock(S3Client.class);
        mockAbortS3Client = mock(S3Client.class);
        mockS3Presigner = mock(S3Presigner.class);
        adapter = new R2MovieStorageAdapter(
                BUCKET,
                mockS3Client,
                mockAbortS3Client,
                mockS3Presigner,
                tempDir,
                System::nanoTime
        );
    }

    @Nested
    @DisplayName("Metadata & Identity Tests")
    class MetadataTests {

        @Test
        @DisplayName("providerId returns r2-movie")
        void providerIdReturnsR2Movie() {
            assertThat(adapter.providerId()).isEqualTo(StorageProviderId.of("r2-movie"));
        }

        @Test
        @DisplayName("configuredBucketName returns configured bucket")
        void configuredBucketNameReturnsBucket() {
            assertThat(adapter.configuredBucketName()).isEqualTo(BUCKET);
        }
    }

    @Nested
    @DisplayName("Initiate Multipart Upload Tests")
    class InitiateTests {

        @Test
        @DisplayName("initiates multipart upload and returns uploadId")
        void initiatesMultipartUploadSuccessfully() {
            CreateMultipartUploadResponse response = CreateMultipartUploadResponse.builder()
                    .uploadId("test-upload-id-123")
                    .build();

            ArgumentCaptor<CreateMultipartUploadRequest> captor = ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
            when(mockS3Client.createMultipartUpload(captor.capture())).thenReturn(response);

            Map<String, String> metadata = Map.of("sessionId", "session-123");
            String uploadId = adapter.initiateMultipartUpload(STORAGE_KEY, MIME_MP4, metadata);

            assertThat(uploadId).isEqualTo("test-upload-id-123");
            CreateMultipartUploadRequest captured = captor.getValue();
            assertThat(captured.bucket()).isEqualTo(BUCKET);
            assertThat(captured.key()).isEqualTo(STORAGE_KEY.value());
            assertThat(captured.contentType()).isEqualTo("video/mp4");
            assertThat(captured.metadata()).containsEntry("sessionId", "session-123");
        }

        @Test
        @DisplayName("translates definitive rejection to StorageInitiationRejectedException")
        void translatesDefinitiveRejection() {
            S3Exception s3Ex = (S3Exception) S3Exception.builder()
                    .statusCode(403)
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                    .message("Access Denied")
                    .build();

            when(mockS3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class))).thenThrow(s3Ex);

            assertThatThrownBy(() -> adapter.initiateMultipartUpload(STORAGE_KEY, MIME_MP4))
                    .isInstanceOf(StorageInitiationRejectedException.class)
                    .hasMessageContaining("provider definitively rejected initiation");
        }

        @Test
        @DisplayName("translates 5xx error to StorageAmbiguousInitiationException")
        void translatesServerErrorToAmbiguousInitiation() {
            S3Exception s3Ex = (S3Exception) S3Exception.builder()
                    .statusCode(500)
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("InternalError").build())
                    .message("Internal Server Error")
                    .build();

            when(mockS3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class))).thenThrow(s3Ex);

            assertThatThrownBy(() -> adapter.initiateMultipartUpload(STORAGE_KEY, MIME_MP4))
                    .isInstanceOf(StorageAmbiguousInitiationException.class)
                    .hasMessageContaining("provider server error");
        }

        @Test
        @DisplayName("enforces non-retryable mutation override configuration on initiate request")
        void enforcesNonRetryableOverrideOnInitiate() {
            CreateMultipartUploadResponse response = CreateMultipartUploadResponse.builder()
                    .uploadId("test-up-retry")
                    .build();

            ArgumentCaptor<CreateMultipartUploadRequest> captor = ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
            when(mockS3Client.createMultipartUpload(captor.capture())).thenReturn(response);

            adapter.initiateMultipartUpload(STORAGE_KEY, MIME_MP4);

            CreateMultipartUploadRequest captured = captor.getValue();
            assertThat(captured.overrideConfiguration()).isPresent();
            assertThat(captured.overrideConfiguration().get().apiCallAttemptTimeout()).isPresent();
        }
    }

    @Nested
    @DisplayName("Presigned URL Generation Tests")
    class PresignTests {

        @Test
        @DisplayName("generates presigned part upload URL")
        void generatesPresignedPartUploadUrlSuccessfully() throws Exception {
            PresignedUploadPartRequest presigned = mock(PresignedUploadPartRequest.class);
            URL testUrl = URI.create("https://test-account.r2.cloudflarestorage.com/test-movie-r2-bucket/test.mp4?partNumber=1&uploadId=up-123").toURL();
            when(presigned.url()).thenReturn(testUrl);

            ArgumentCaptor<UploadPartPresignRequest> captor = ArgumentCaptor.forClass(UploadPartPresignRequest.class);
            when(mockS3Presigner.presignUploadPart(captor.capture())).thenReturn(presigned);

            String url = adapter.generatePresignedPartUploadUrl(STORAGE_KEY, "up-123", 1, Duration.ofMinutes(15));

            assertThat(url).isEqualTo(testUrl.toString());
            UploadPartPresignRequest captured = captor.getValue();
            assertThat(captured.signatureDuration()).isEqualTo(Duration.ofMinutes(15));
            assertThat(captured.uploadPartRequest().bucket()).isEqualTo(BUCKET);
            assertThat(captured.uploadPartRequest().key()).isEqualTo(STORAGE_KEY.value());
            assertThat(captured.uploadPartRequest().uploadId()).isEqualTo("up-123");
            assertThat(captured.uploadPartRequest().partNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects invalid partNumber and expiry")
        void validatesPresignArguments() {
            assertThatThrownBy(() -> adapter.generatePresignedPartUploadUrl(STORAGE_KEY, "up-123", 0, Duration.ofMinutes(15)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> adapter.generatePresignedPartUploadUrl(STORAGE_KEY, "up-123", 10001, Duration.ofMinutes(15)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> adapter.generatePresignedPartUploadUrl(STORAGE_KEY, "up-123", 1, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("List Uploaded Parts Tests")
    class ListPartsTests {

        @Test
        @DisplayName("lists uploaded parts and validates ascending order")
        void listsUploadedPartsSuccessfully() {
            Part part1 = Part.builder().partNumber(1).size(26214400L).eTag("\"etag1\"").build();
            Part part2 = Part.builder().partNumber(2).size(10485760L).eTag("\"etag2\"").build();

            ListPartsResponse response = ListPartsResponse.builder()
                    .parts(List.of(part1, part2))
                    .isTruncated(false)
                    .build();

            when(mockS3Client.listParts(any(ListPartsRequest.class))).thenReturn(response);

            List<MultipartUploadedPart> parts = adapter.listUploadedParts(STORAGE_KEY, "up-123");

            assertThat(parts).hasSize(2);
            assertThat(parts.get(0).partNumber()).isEqualTo(1);
            assertThat(parts.get(0).sizeBytes()).isEqualTo(26214400L);
            assertThat(parts.get(0).eTag()).isEqualTo("\"etag1\"");
            assertThat(parts.get(1).partNumber()).isEqualTo(2);
            assertThat(parts.get(1).sizeBytes()).isEqualTo(10485760L);
        }

        @Test
        @DisplayName("translates NoSuchUploadException to StorageUploadNotFoundException")
        void translatesNoSuchUpload() {
            NoSuchUploadException noSuchUploadEx = (NoSuchUploadException) NoSuchUploadException.builder()
                    .message("Upload does not exist")
                    .build();

            when(mockS3Client.listParts(any(ListPartsRequest.class))).thenThrow(noSuchUploadEx);

            assertThatThrownBy(() -> adapter.listUploadedParts(STORAGE_KEY, "up-missing"))
                    .isInstanceOf(StorageUploadNotFoundException.class);
        }

        @Test
        @DisplayName("pagination error messages redact sensitive opaque uploadId and include uploadIdLength")
        void paginationErrorRedactsUploadId() {
            String sensitiveOpaqueUploadId = "r2-very-sensitive-token-" + "x".repeat(300);
            ListPartsResponse responseWithNullPart = ListPartsResponse.builder()
                    .parts((Part) null)
                    .build();

            when(mockS3Client.listParts(any(ListPartsRequest.class))).thenReturn(responseWithNullPart);

            assertThatThrownBy(() -> adapter.listUploadedParts(STORAGE_KEY, sensitiveOpaqueUploadId))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("uploadIdLength=" + sensitiveOpaqueUploadId.length())
                    .hasMessageContaining(STORAGE_KEY.value())
                    .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(sensitiveOpaqueUploadId));
        }
    }

    @Nested
    @DisplayName("Complete Multipart Upload Tests")
    class CompleteTests {

        @Test
        @DisplayName("completes multipart upload and returns completed object")
        void completesMultipartUploadSuccessfully() {
            CompleteMultipartUploadResponse response = CompleteMultipartUploadResponse.builder()
                    .eTag("\"final-etag\"")
                    .versionId("v1")
                    .build();

            ArgumentCaptor<CompleteMultipartUploadRequest> captor = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
            when(mockS3Client.completeMultipartUpload(captor.capture())).thenReturn(response);

            List<MultipartPartETag> parts = List.of(
                    new MultipartPartETag(1, "\"etag1\""),
                    new MultipartPartETag(2, "\"etag2\"")
            );

            MultipartCompletedObject completed = adapter.completeMultipartUpload(STORAGE_KEY, "up-123", parts);

            assertThat(completed.location())
                    .isEqualTo(StorageLocation.of(StorageProviderId.of("r2-movie"), STORAGE_KEY));
            assertThat(completed.eTag()).isEqualTo("\"final-etag\"");
            assertThat(completed.versionId()).isEqualTo("v1");

            CompleteMultipartUploadRequest captured = captor.getValue();
            assertThat(captured.bucket()).isEqualTo(BUCKET);
            assertThat(captured.key()).isEqualTo(STORAGE_KEY.value());
            assertThat(captured.uploadId()).isEqualTo("up-123");
            assertThat(captured.multipartUpload().parts()).hasSize(2);
        }

        @Test
        @DisplayName("rejects unordered or duplicate parts")
        void validatesPartsListOrder() {
            List<MultipartPartETag> unordered = List.of(
                    new MultipartPartETag(2, "\"etag2\""),
                    new MultipartPartETag(1, "\"etag1\"")
            );
            assertThatThrownBy(() -> adapter.completeMultipartUpload(STORAGE_KEY, "up-123", unordered))
                    .isInstanceOf(IllegalArgumentException.class);

            List<MultipartPartETag> duplicate = List.of(
                    new MultipartPartETag(1, "\"etag1\""),
                    new MultipartPartETag(1, "\"etag1-dup\"")
            );
            assertThatThrownBy(() -> adapter.completeMultipartUpload(STORAGE_KEY, "up-123", duplicate))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("translates 500 error to StorageAmbiguousCompletionException")
        void translatesServerErrorToAmbiguousCompletion() {
            S3Exception s3Ex = (S3Exception) S3Exception.builder()
                    .statusCode(500)
                    .message("Internal Error")
                    .build();

            when(mockS3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class))).thenThrow(s3Ex);

            List<MultipartPartETag> parts = List.of(new MultipartPartETag(1, "\"etag1\""));

            assertThatThrownBy(() -> adapter.completeMultipartUpload(STORAGE_KEY, "up-123", parts))
                    .isInstanceOf(StorageAmbiguousCompletionException.class);
        }

        @Test
        @DisplayName("enforces non-retryable mutation override configuration on complete request")
        void enforcesNonRetryableOverrideOnComplete() {
            CompleteMultipartUploadResponse response = CompleteMultipartUploadResponse.builder()
                    .eTag("\"etag-complete\"")
                    .versionId("v1")
                    .build();

            ArgumentCaptor<CompleteMultipartUploadRequest> captor = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
            when(mockS3Client.completeMultipartUpload(captor.capture())).thenReturn(response);

            adapter.completeMultipartUpload(STORAGE_KEY, "up-123", List.of(new MultipartPartETag(1, "\"p1\"")));

            CompleteMultipartUploadRequest captured = captor.getValue();
            assertThat(captured.overrideConfiguration()).isPresent();
            assertThat(captured.overrideConfiguration().get().apiCallAttemptTimeout()).isPresent();
        }
    }

    @Nested
    @DisplayName("Purge Exact Key Tests")
    class PurgeTests {
        @Test
        @DisplayName("purgeExactKey explicitly throws UnsupportedOperationException explaining Cloudflare R2 unversioned storage")
        void purgeExactKeyThrowsUnsupportedOperationException() {
            assertThatThrownBy(() -> adapter.purgeExactKey(STORAGE_KEY))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("Version-level physical purge (purgeExactKey) is unsupported on Cloudflare R2 movie storage");
        }

        @Test
        @DisplayName("purgeExactKey rejects null storage key")
        void purgeExactKeyRejectsNull() {
            assertThatThrownBy(() -> adapter.purgeExactKey(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Abort Multipart Upload Tests")
    class AbortTests {

        @Test
        @DisplayName("aborts upload and returns ABORT_ACCEPTED")
        void abortsUploadSuccessfully() {
            AbortMultipartUploadResponse response = AbortMultipartUploadResponse.builder().build();
            when(mockAbortS3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class))).thenReturn(response);

            MultipartAbortResult result = adapter.abortMultipartUpload(STORAGE_KEY, "up-123");

            assertThat(result).isEqualTo(MultipartAbortResult.ABORT_ACCEPTED);
            verify(mockAbortS3Client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        }

        @Test
        @DisplayName("returns UPLOAD_ALREADY_ABSENT on NoSuchUploadException")
        void returnsAlreadyAbsentOnNoSuchUpload() {
            NoSuchUploadException ex = (NoSuchUploadException) NoSuchUploadException.builder().message("No such upload").build();
            when(mockAbortS3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class))).thenThrow(ex);

            MultipartAbortResult result = adapter.abortMultipartUpload(STORAGE_KEY, "up-absent");

            assertThat(result).isEqualTo(MultipartAbortResult.UPLOAD_ALREADY_ABSENT);
        }
    }

    @Nested
    @DisplayName("Inspect Metadata Tests")
    class InspectMetadataTests {

        @Test
        @DisplayName("inspects object metadata via HeadObject")
        void inspectsMetadataSuccessfully() {
            HeadObjectResponse response = HeadObjectResponse.builder()
                    .contentLength(1048576L)
                    .contentType("video/mp4")
                    .eTag("\"obj-etag\"")
                    .versionId("v1")
                    .metadata(Map.of("assetId", "asset-123"))
                    .build();

            when(mockS3Client.headObject(any(HeadObjectRequest.class))).thenReturn(response);

            MultipartObjectMetadata metadata = adapter.inspectObjectMetadata(STORAGE_KEY);

            assertThat(metadata.key()).isEqualTo(STORAGE_KEY);
            assertThat(metadata.contentLength()).isEqualTo(1048576L);
            assertThat(metadata.contentType()).isEqualTo("video/mp4");
            assertThat(metadata.eTag()).isEqualTo("\"obj-etag\"");
            assertThat(metadata.versionId()).isEqualTo("v1");
            assertThat(metadata.userMetadata()).containsEntry("assetId", "asset-123");
            assertThat(metadata.providerId()).isEqualTo(StorageProviderId.of("r2-movie"));
            assertThat(metadata.bucketName()).isEqualTo(BUCKET);
        }

        @Test
        @DisplayName("translates 404 to StorageObjectNotFoundException")
        void translatesNotFoundOnHeadObject() {
            NoSuchKeyException ex = (NoSuchKeyException) NoSuchKeyException.builder().statusCode(404).message("Not Found").build();
            when(mockS3Client.headObject(any(HeadObjectRequest.class))).thenThrow(ex);

            assertThatThrownBy(() -> adapter.inspectObjectMetadata(STORAGE_KEY))
                    .isInstanceOf(StorageObjectNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Binary Streaming & Range Tests")
    class BinaryStreamingTests {

        @Test
        @DisplayName("opens binary range stream with Range header")
        void opensRangeStreamSuccessfully() {
            byte[] data = "video-byte-range-data".getBytes(StandardCharsets.UTF_8);
            ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                    GetObjectResponse.builder().contentLength((long) data.length).build(),
                    AbortableInputStream.create(new ByteArrayInputStream(data))
            );

            ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
            when(mockS3Client.getObject(captor.capture())).thenReturn(responseStream);

            try (InputStream is = adapter.openRange(STORAGE_KEY, 100, 500)) {
                assertThat(is).isNotNull();
            } catch (Exception e) {
                org.junit.jupiter.api.Assertions.fail(e);
            }

            GetObjectRequest captured = captor.getValue();
            assertThat(captured.bucket()).isEqualTo(BUCKET);
            assertThat(captured.key()).isEqualTo(STORAGE_KEY.value());
            assertThat(captured.range()).isEqualTo("bytes=100-599");
        }

        @Test
        @DisplayName("stores binary content with ifNoneMatch(*) create-only semantics")
        void storesBinaryCreateOnly() {
            byte[] data = "movie-binary-data".getBytes(StandardCharsets.UTF_8);

            ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
            when(mockS3Client.putObject(captor.capture(), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            StoredBinaryObject stored = adapter.store(
                    STORAGE_KEY,
                    new ByteArrayInputStream(data),
                    data.length,
                    MIME_MP4
            );

            assertThat(stored.location())
                    .isEqualTo(StorageLocation.of(StorageProviderId.of("r2-movie"), STORAGE_KEY));

            PutObjectRequest captured = captor.getValue();
            assertThat(captured.bucket()).isEqualTo(BUCKET);
            assertThat(captured.key()).isEqualTo(STORAGE_KEY.value());
            assertThat(captured.ifNoneMatch()).isEqualTo("*");
            assertThat(captured.contentLength()).isEqualTo((long) data.length);
        }

        @Test
        @DisplayName("translates 412 PreconditionFailed to StorageObjectAlreadyExistsException")
        void translatesPreconditionFailedOnStore() {
            byte[] data = "data".getBytes(StandardCharsets.UTF_8);
            S3Exception s3Ex = (S3Exception) S3Exception.builder()
                    .statusCode(412)
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("PreconditionFailed").build())
                    .message("Precondition Failed")
                    .build();

            when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(s3Ex);

            assertThatThrownBy(() -> adapter.store(STORAGE_KEY, new ByteArrayInputStream(data), data.length, MIME_MP4))
                    .isInstanceOf(StorageObjectAlreadyExistsException.class);
        }

        @Test
        @DisplayName("delete succeeds idempotently")
        void deletesObjectSuccessfully() {
            adapter.delete(STORAGE_KEY);
            verify(mockS3Client).deleteObject(any(DeleteObjectRequest.class));
        }
    }

    @Nested
    @DisplayName("Observation Primitives Tests")
    class ObservationTests {

        @Test
        @DisplayName("observeMultipartUploadPresence returns ACTIVE when upload listed")
        void observesActiveMultipartUpload() {
            MultipartUpload upload = MultipartUpload.builder()
                    .key(STORAGE_KEY.value())
                    .uploadId("up-123")
                    .build();

            ListMultipartUploadsResponse response = ListMultipartUploadsResponse.builder()
                    .uploads(List.of(upload))
                    .isTruncated(false)
                    .build();

            when(mockS3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenReturn(response);

            MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(STORAGE_KEY, "up-123");

            assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.ACTIVE);
            assertThat(obs.storageKey()).isEqualTo(STORAGE_KEY);
            assertThat(obs.uploadId()).isEqualTo("up-123");
        }

        @Test
        @DisplayName("observeMultipartUploadPresence returns NOT_LISTED when upload absent from non-truncated listing")
        void observesNotListedMultipartUpload() {
            ListMultipartUploadsResponse response = ListMultipartUploadsResponse.builder()
                    .uploads(List.of())
                    .isTruncated(false)
                    .build();

            when(mockS3Client.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenReturn(response);

            MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(STORAGE_KEY, "up-123");

            assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.NOT_LISTED);
        }

        @Test
        @DisplayName("observeObjectPresence returns PRESENT when object exists")
        void observesObjectPresence() {
            HeadObjectResponse response = HeadObjectResponse.builder()
                    .contentLength(1024L)
                    .eTag("\"etag-obs\"")
                    .versionId("v1")
                    .build();

            when(mockS3Client.headObject(any(HeadObjectRequest.class))).thenReturn(response);

            StorageObjectPresenceObservation obs = adapter.observeObjectPresence(STORAGE_KEY);

            assertThat(obs.presence()).isEqualTo(StorageObjectPresence.PRESENT);
            assertThat(obs.metadata()).isNotNull();
            assertThat(obs.metadata().contentLength()).isEqualTo(1024L);
            assertThat(obs.metadata().eTag()).isEqualTo("\"etag-obs\"");
        }

        @Test
        @DisplayName("observeObjectPresence returns NOT_FOUND when object missing")
        void observesObjectNotFound() {
            NoSuchKeyException ex = (NoSuchKeyException) NoSuchKeyException.builder().statusCode(404).message("Not Found").build();
            when(mockS3Client.headObject(any(HeadObjectRequest.class))).thenThrow(ex);

            StorageObjectPresenceObservation obs = adapter.observeObjectPresence(STORAGE_KEY);

            assertThat(obs.presence()).isEqualTo(StorageObjectPresence.NOT_FOUND);
        }
    }
}
