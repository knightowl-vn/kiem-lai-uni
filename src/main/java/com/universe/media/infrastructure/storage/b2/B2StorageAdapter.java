package com.universe.media.infrastructure.storage.b2;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.MultipartAbortResult;
import com.universe.media.application.ports.storage.MultipartCompletedObject;
import com.universe.media.application.ports.storage.MultipartObjectMetadata;
import com.universe.media.application.ports.storage.MultipartPartETag;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.application.ports.storage.MultipartUploadedPart;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Backblaze B2 implementation of {@link BinaryStoragePort} and {@link MultipartStoragePort}
 * using the AWS SDK for Java v2 S3-compatible API.
 * <p>
 * Core Contract Semantics:
 * <ul>
 *     <li>Provider ID: {@code b2}</li>
 *     <li>CREATE-ONLY single-stream uploads using conditional {@code If-None-Match: *} (no TOCTOU HEAD probe)</li>
 *     <li>Constant-memory local temporary file staging before remote PutObject</li>
 *     <li>Strict exact size verification during staging prior to S3 upload</li>
 *     <li>Caller stream ownership preservation (never closes store input stream)</li>
 *     <li>No ambiguous remote delete compensation</li>
 *     <li>Exact byte-range reads via native S3 Range header</li>
 *     <li>Idempotent deletions and multipart aborts</li>
 *     <li>Full translation of SDK/HTTP errors to domain storage exceptions</li>
 *     <li>B2-orchestrated presigned multipart uploads up to 50 GiB</li>
 * </ul>
 */
@Component
@Conditional(B2StorageCondition.class)
public class B2StorageAdapter implements BinaryStoragePort, MultipartStoragePort, AutoCloseable {

    public static final StorageProviderId PROVIDER_ID = StorageProviderId.of("b2");
    private static final int BUFFER_SIZE = 8192;

    private final String bucket;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final boolean ownsS3Client;
    private final boolean ownsS3Presigner;
    private final Path tempDirectory;

    @Autowired
    public B2StorageAdapter(B2StorageProperties properties) {
        this(
                Objects.requireNonNull(properties, "B2StorageProperties cannot be null.").bucket(),
                createS3Client(properties),
                createS3Presigner(properties),
                true,
                true,
                null
        );
    }

    public B2StorageAdapter(String bucket, S3Client s3Client) {
        this(bucket, s3Client, null, false, false, null);
    }

    B2StorageAdapter(String bucket, S3Client s3Client, Path tempDirectory) {
        this(bucket, s3Client, null, false, false, tempDirectory);
    }

    public B2StorageAdapter(String bucket, S3Client s3Client, S3Presigner s3Presigner) {
        this(bucket, s3Client, s3Presigner, false, false, null);
    }

    B2StorageAdapter(String bucket, S3Client s3Client, S3Presigner s3Presigner, Path tempDirectory) {
        this(bucket, s3Client, s3Presigner, false, false, tempDirectory);
    }

    private B2StorageAdapter(
            String bucket,
            S3Client s3Client,
            S3Presigner s3Presigner,
            boolean ownsS3Client,
            boolean ownsS3Presigner,
            Path tempDirectory
    ) {
        this.bucket = Objects.requireNonNull(bucket, "Bucket cannot be null.");
        this.s3Client = Objects.requireNonNull(s3Client, "S3Client cannot be null.");
        this.s3Presigner = s3Presigner;
        this.ownsS3Client = ownsS3Client;
        this.ownsS3Presigner = ownsS3Presigner;
        this.tempDirectory = tempDirectory;
    }

    private static S3Client createS3Client(B2StorageProperties properties) {
        S3Configuration serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build();

        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey())
                ))
                .serviceConfiguration(serviceConfig)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private static S3Presigner createS3Presigner(B2StorageProperties properties) {
        S3Configuration serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build();

        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey())
                ))
                .serviceConfiguration(serviceConfig)
                .build();
    }

    @Override
    public StorageProviderId providerId() {
        return PROVIDER_ID;
    }

    // ---------------------------------------------------------------------------------------------
    // Single-stream BinaryStoragePort implementation
    // ---------------------------------------------------------------------------------------------

    @Override
    public StoredBinaryObject store(
            StorageKey key,
            InputStream content,
            long sizeBytes,
            MimeType mimeType
    ) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        Objects.requireNonNull(content, "Content InputStream cannot be null.");
        Objects.requireNonNull(mimeType, "MimeType cannot be null.");

        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes cannot be negative: " + sizeBytes);
        }

        Path tempFile = null;
        try {
            tempFile = createTempFile();

            long bytesWritten = 0;
            byte[] buffer = new byte[BUFFER_SIZE];

            try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.WRITE)) {
                int read;
                while ((read = content.read(buffer)) != -1) {
                    bytesWritten += read;
                    if (bytesWritten > sizeBytes) {
                        throw new StorageException(
                                "Payload size exceeded declared sizeBytes: declared="
                                        + sizeBytes
                                        + ", received at least="
                                        + bytesWritten
                        );
                    }
                    out.write(buffer, 0, read);
                }
                out.flush();
            }

            if (bytesWritten != sizeBytes) {
                throw new StorageException(
                        "Payload size mismatch: declared="
                                + sizeBytes
                                + ", received="
                                + bytesWritten
                );
            }

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key.value())
                    .contentLength(sizeBytes)
                    .contentType(mimeType.value())
                    .ifNoneMatch("*")
                    .build();

            try {
                s3Client.putObject(putRequest, RequestBody.fromFile(tempFile));
                return new StoredBinaryObject(StorageLocation.of(PROVIDER_ID, key), null);
            } catch (S3Exception e) {
                if (isPreconditionFailed(e)) {
                    throw new StorageObjectAlreadyExistsException(key, e);
                }
                throw new StorageException("Failed to store binary content for key: " + key.value(), e);
            } catch (AwsServiceException | SdkClientException e) {
                throw new StorageException("Failed to store binary content for key: " + key.value(), e);
            }
        } catch (StorageException e) {
            throw e;
        } catch (IOException e) {
            throw new StorageException("Failed to stage binary content for key: " + key.value(), e);
        } catch (Exception e) {
            throw new StorageException("Failed to store binary content for key: " + key.value(), e);
        } finally {
            deleteQuietly(tempFile);
        }
    }

    private Path createTempFile() throws IOException {
        if (tempDirectory != null) {
            return Files.createTempFile(tempDirectory, "b2-staging-", ".tmp");
        }
        return Files.createTempFile("b2-staging-", ".tmp");
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Override
    public InputStream open(StorageKey key) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");

        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .build();

        try {
            return s3Client.getObject(getRequest);
        } catch (NoSuchKeyException e) {
            throw new StorageObjectNotFoundException(key, e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new StorageObjectNotFoundException(key, e);
            }
            throw new StorageException("Failed to open binary content for key: " + key.value(), e);
        } catch (AwsServiceException | SdkClientException e) {
            throw new StorageException("Failed to open binary content for key: " + key.value(), e);
        }
    }

    @Override
    public InputStream openRange(StorageKey key, long startInclusive, long length) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");

        if (startInclusive < 0) {
            throw new IllegalArgumentException("startInclusive cannot be negative: " + startInclusive);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }

        long endInclusive = Math.addExact(startInclusive, length - 1);
        String rangeHeader = "bytes=" + startInclusive + "-" + endInclusive;

        GetObjectRequest rangeRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .range(rangeHeader)
                .build();

        try {
            return s3Client.getObject(rangeRequest);
        } catch (NoSuchKeyException e) {
            throw new StorageObjectNotFoundException(key, e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new StorageObjectNotFoundException(key, e);
            }
            throw new StorageException("Failed to open binary range for key: " + key.value(), e);
        } catch (AwsServiceException | SdkClientException e) {
            throw new StorageException("Failed to open binary range for key: " + key.value(), e);
        }
    }

    @Override
    public void delete(StorageKey key) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");

        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .build();

        try {
            s3Client.deleteObject(deleteRequest);
        } catch (NoSuchKeyException e) {
            // Idempotent success
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                // Idempotent success
                return;
            }
            throw new StorageException("Failed to delete binary content for key: " + key.value(), e);
        } catch (AwsServiceException | SdkClientException e) {
            throw new StorageException("Failed to delete binary content for key: " + key.value(), e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // MultipartStoragePort implementation
    // ---------------------------------------------------------------------------------------------

    @Override
    public String initiateMultipartUpload(StorageKey key, MimeType mimeType) {
        return initiateMultipartUpload(key, mimeType, Map.of());
    }

    @Override
    public String initiateMultipartUpload(StorageKey key, MimeType mimeType, Map<String, String> metadata) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        Objects.requireNonNull(mimeType, "MimeType cannot be null.");
        Objects.requireNonNull(metadata, "Metadata cannot be null.");

        CreateMultipartUploadRequest.Builder createRequestBuilder = CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .contentType(mimeType.value());

        if (!metadata.isEmpty()) {
            createRequestBuilder.metadata(metadata);
        }

        try {
            CreateMultipartUploadResponse response = s3Client.createMultipartUpload(createRequestBuilder.build());
            return response.uploadId();
        } catch (AwsServiceException | SdkClientException e) {
            throw new StorageException("Failed to initiate multipart upload for key: " + key.value(), e);
        }
    }

    @Override
    public String generatePresignedPartUploadUrl(StorageKey key, String uploadId, int partNumber, Duration expiry) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        Objects.requireNonNull(uploadId, "uploadId cannot be null.");
        if (uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId cannot be blank.");
        }
        if (partNumber < 1 || partNumber > 10_000) {
            throw new IllegalArgumentException("partNumber must be between 1 and 10000, got: " + partNumber);
        }
        Objects.requireNonNull(expiry, "expiry cannot be null.");
        if (expiry.isNegative() || expiry.isZero()) {
            throw new IllegalArgumentException("expiry must be strictly positive, got: " + expiry);
        }
        if (s3Presigner == null) {
            throw new StorageException("S3Presigner is not configured on this B2StorageAdapter instance.");
        }

        try {
            UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(key.value())
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .build();

            UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                    .signatureDuration(expiry)
                    .uploadPartRequest(uploadPartRequest)
                    .build();

            PresignedUploadPartRequest presigned = s3Presigner.presignUploadPart(presignRequest);
            return presigned.url().toString();
        } catch (AwsServiceException | SdkClientException e) {
            throw new StorageException(
                    "Failed to generate presigned part upload URL for key: " + key.value() + ", part: " + partNumber,
                    e
            );
        }
    }

    @Override
    public List<MultipartUploadedPart> listUploadedParts(StorageKey key, String uploadId) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        Objects.requireNonNull(uploadId, "uploadId cannot be null.");
        if (uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId cannot be blank.");
        }

        List<MultipartUploadedPart> result = new ArrayList<>();
        Integer currentMarker = null;
        int lastPartNumber = 0;
        int pageCount = 0;
        final int maxAllowedPages = 10_000;

        try {
            while (true) {
                pageCount++;
                if (pageCount > maxAllowedPages) {
                    throw new StorageException(
                            "Exceeded maximum pagination page limit (" + maxAllowedPages + ") for upload: " + uploadId
                    );
                }

                ListPartsRequest.Builder requestBuilder = ListPartsRequest.builder()
                        .bucket(bucket)
                        .key(key.value())
                        .uploadId(uploadId);

                if (currentMarker != null) {
                    requestBuilder.partNumberMarker(currentMarker);
                }

                ListPartsResponse response = s3Client.listParts(requestBuilder.build());

                int partsReturnedThisPage = 0;
                if (response.hasParts() && response.parts() != null) {
                    for (Part part : response.parts()) {
                        if (part == null) {
                            throw new StorageException("Provider returned null part in listing for upload: " + uploadId);
                        }
                        if (part.partNumber() == null || part.partNumber() <= lastPartNumber) {
                            throw new StorageException(
                                    "Provider returned unordered or non-advancing part number ("
                                            + part.partNumber() + ") after " + lastPartNumber + " for upload: " + uploadId
                            );
                        }
                        lastPartNumber = part.partNumber();
                        partsReturnedThisPage++;
                        result.add(new MultipartUploadedPart(
                                part.partNumber(),
                                part.size() != null ? part.size() : 0L,
                                part.eTag()
                        ));
                    }
                }

                if (Boolean.TRUE.equals(response.isTruncated())) {
                    if (partsReturnedThisPage == 0) {
                        throw new StorageException(
                                "Provider returned truncated response with no parts for upload: " + uploadId
                        );
                    }
                    Integer nextMarker = response.nextPartNumberMarker();
                    if (nextMarker == null) {
                        throw new StorageException(
                                "Provider returned truncated response without nextPartNumberMarker for upload: " + uploadId
                        );
                    }
                    if (nextMarker < 1 || nextMarker > 10_000) {
                        throw new StorageException(
                                "Provider returned out-of-range nextPartNumberMarker (" + nextMarker + ") for upload: " + uploadId
                        );
                    }
                    if (currentMarker != null && nextMarker <= currentMarker) {
                        throw new StorageException(
                                "Provider returned non-advancing nextPartNumberMarker (" + nextMarker
                                        + " <= " + currentMarker + ") for upload: " + uploadId
                        );
                    }
                    if (!nextMarker.equals(lastPartNumber)) {
                        throw new StorageException(
                                "Provider returned nextPartNumberMarker (" + nextMarker
                                        + ") inconsistent with last returned part (" + lastPartNumber + ") for upload: " + uploadId
                        );
                    }
                    currentMarker = nextMarker;
                } else {
                    break;
                }
            }

            return List.copyOf(result);
        } catch (NoSuchUploadException e) {
            throw new StorageObjectNotFoundException(key, e);
        } catch (S3Exception e) {
            if (isNoSuchUpload(e)) {
                throw new StorageObjectNotFoundException(key, e);
            }
            throw new StorageException("Failed to list multipart upload parts for key: " + key.value(), e);
        } catch (StorageException e) {
            throw e;
        } catch (AwsServiceException | SdkClientException e) {
            throw new StorageException("Failed to list multipart upload parts for key: " + key.value(), e);
        }
    }

    @Override
    public MultipartCompletedObject completeMultipartUpload(StorageKey key, String uploadId, List<MultipartPartETag> parts) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        Objects.requireNonNull(uploadId, "uploadId cannot be null.");
        if (uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId cannot be blank.");
        }
        Objects.requireNonNull(parts, "parts cannot be null.");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("parts list cannot be empty.");
        }
        if (parts.size() > 10_000) {
            throw new IllegalArgumentException(
                    "parts list size (" + parts.size() + ") exceeds maximum allowed limit of 10000 parts."
            );
        }

        int previousPartNumber = 0;
        for (int i = 0; i < parts.size(); i++) {
            MultipartPartETag part = parts.get(i);
            if (part == null) {
                throw new IllegalArgumentException("parts list cannot contain null elements; index " + i + " is null.");
            }
            if (part.partNumber() < 1 || part.partNumber() > 10_000) {
                throw new IllegalArgumentException("partNumber must be between 1 and 10000, got: " + part.partNumber());
            }
            if (part.partNumber() <= previousPartNumber) {
                if (part.partNumber() == previousPartNumber) {
                    throw new IllegalArgumentException("Duplicate partNumber detected: " + part.partNumber());
                } else {
                    throw new IllegalArgumentException(
                            "parts list must be in strictly ascending partNumber order; got part "
                                    + part.partNumber() + " after " + previousPartNumber
                    );
                }
            }
            previousPartNumber = part.partNumber();
        }

        List<CompletedPart> completedParts = parts.stream()
                .map(p -> CompletedPart.builder()
                        .partNumber(p.partNumber())
                        .eTag(p.eTag())
                        .build())
                .toList();

        CompletedMultipartUpload completedMultipartUpload = CompletedMultipartUpload.builder()
                .parts(completedParts)
                .build();

        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .uploadId(uploadId)
                .multipartUpload(completedMultipartUpload)
                .build();

        try {
            CompleteMultipartUploadResponse response = s3Client.completeMultipartUpload(completeRequest);
            StorageLocation location = StorageLocation.of(PROVIDER_ID, key);
            return new MultipartCompletedObject(location, response.eTag(), response.versionId());
        } catch (NoSuchUploadException e) {
            throw new StorageObjectNotFoundException(key, e);
        } catch (S3Exception e) {
            if (isNoSuchUpload(e)) {
                throw new StorageObjectNotFoundException(key, e);
            }
            throw new StorageException("Failed to complete multipart upload for key: " + key.value(), e);
        } catch (AwsServiceException | SdkClientException e) {
            throw new StorageException("Failed to complete multipart upload for key: " + key.value(), e);
        }
    }

    @Override
    public MultipartAbortResult abortMultipartUpload(StorageKey key, String uploadId) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        Objects.requireNonNull(uploadId, "uploadId cannot be null.");
        if (uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId cannot be blank.");
        }

        AbortMultipartUploadRequest abortRequest = AbortMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .uploadId(uploadId)
                .build();

        try {
            AbortMultipartUploadResponse response = s3Client.abortMultipartUpload(abortRequest);
            if (response == null) {
                throw new StorageException("B2 abort multipart upload returned null response for key: " + key.value());
            }
            return MultipartAbortResult.ABORT_ACCEPTED;
        } catch (NoSuchUploadException e) {
            return MultipartAbortResult.UPLOAD_ALREADY_ABSENT;
        } catch (S3Exception e) {
            if (isNoSuchUpload(e)) {
                return MultipartAbortResult.UPLOAD_ALREADY_ABSENT;
            }
            throw new StorageException("Failed to abort multipart upload for key: " + key.value(), e);
        } catch (AwsServiceException | SdkClientException e) {
            throw new StorageException("Failed to abort multipart upload for key: " + key.value(), e);
        }
    }

    @Override
    public MultipartObjectMetadata inspectObjectMetadata(StorageKey key) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");

        HeadObjectRequest headRequest = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .build();

        try {
            HeadObjectResponse response = s3Client.headObject(headRequest);
            Map<String, String> userMetadata = (response.hasMetadata() && response.metadata() != null)
                    ? response.metadata()
                    : Map.of();
            return new MultipartObjectMetadata(
                    key,
                    response.contentLength(),
                    response.contentType(),
                    response.eTag(),
                    userMetadata,
                    response.versionId(),
                    PROVIDER_ID,
                    this.bucket
            );
        } catch (NoSuchKeyException e) {
            throw new StorageObjectNotFoundException(key, e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new StorageObjectNotFoundException(key, e);
            }
            throw new StorageException("Failed to inspect metadata for key: " + key.value(), e);
        } catch (AwsServiceException | SdkClientException e) {
            throw new StorageException("Failed to inspect metadata for key: " + key.value(), e);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (ownsS3Client && s3Client != null) {
            s3Client.close();
        }
        if (ownsS3Presigner && s3Presigner != null) {
            s3Presigner.close();
        }
    }

    private static boolean isPreconditionFailed(S3Exception e) {
        if (e.statusCode() == 412) {
            return true;
        }
        String errorCode = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : null;
        return "PreconditionFailed".equalsIgnoreCase(errorCode)
                || "AtLeastOneConditionFailed".equalsIgnoreCase(errorCode);
    }

    private static boolean isNoSuchUpload(S3Exception e) {
        if (e instanceof NoSuchUploadException) {
            return true;
        }
        String errorCode = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : null;
        return "NoSuchUpload".equalsIgnoreCase(errorCode);
    }
}
