package com.universe.media.infrastructure.storage.r2movie;

import com.universe.media.application.exceptions.StorageAmbiguousCompletionException;
import com.universe.media.application.exceptions.StorageAmbiguousInitiationException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageInitiationRejectedException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.exceptions.StorageUploadNotFoundException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
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
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
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
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Cloudflare R2 implementation of {@link BinaryStoragePort} and {@link com.universe.media.application.ports.storage.MultipartStoragePort}
 * for dedicated Screen Movie storage (MS-08 R2-01).
 * <p>
 * Core Contract Semantics:
 * <ul>
 *     <li>Provider ID: {@code r2-movie}</li>
 *     <li>CREATE-ONLY single-stream uploads using conditional {@code If-None-Match: *} (no TOCTOU HEAD probe)</li>
 *     <li>Constant-memory local temporary file staging before remote PutObject</li>
 *     <li>Strict exact size verification during staging prior to S3 upload</li>
 *     <li>Caller stream ownership preservation (never closes store input stream)</li>
 *     <li>Exact byte-range reads via native S3 Range header</li>
 *     <li>Idempotent deletions and multipart aborts</li>
 *     <li>Full translation of SDK/HTTP errors to domain storage exceptions</li>
 *     <li>R2-orchestrated presigned multipart uploads up to 50 GiB</li>
 * </ul>
 */
@Component
@Conditional(R2MovieStorageCondition.class)
public class R2MovieStorageAdapter implements BinaryStoragePort, com.universe.media.application.ports.storage.MultipartStoragePort, AutoCloseable {

    public static final StorageProviderId PROVIDER_ID = StorageProviderId.of("r2-movie");
    private static final int BUFFER_SIZE = 8192;

    public static final Duration ABORT_API_CALL_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration ABORT_API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(25);
    public static final int ABORT_MAX_ATTEMPTS = 1;

    public static final Duration OBSERVATION_API_CALL_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration OBSERVATION_API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(12);
    public static final Duration OBSERVATION_DEFAULT_OPERATION_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration OBSERVATION_MIN_DISPATCH_THRESHOLD = Duration.ofMillis(200);
    public static final Duration OBSERVATION_ATTEMPT_TIMEOUT_MARGIN = Duration.ofMillis(50);
    public static final int OBSERVATION_MAX_PAGES = 10;
    public static final int OBSERVATION_MAX_UPLOADS_PER_PAGE = 100;
    public static final int OBSERVATION_MAX_PARTS_PER_PAGE = 1000;

    private final String bucket;
    private final S3Client s3Client;
    private final S3Client abortS3Client;
    private final S3Presigner s3Presigner;
    private final boolean ownsS3Client;
    private final boolean ownsAbortS3Client;
    private final boolean ownsS3Presigner;
    private final Path tempDirectory;
    private final LongSupplier nanoTimeSupplier;

    @Autowired
    public R2MovieStorageAdapter(R2MovieStorageProperties properties) {
        Objects.requireNonNull(properties, "R2MovieStorageProperties cannot be null.");
        S3Client generalClient = null;
        S3Client abortClient = null;
        S3Presigner presigner = null;
        try {
            generalClient = createS3Client(properties);
            abortClient = createAbortS3Client(properties);
            presigner = createS3Presigner(properties);

            this.bucket = properties.bucket();
            this.s3Client = generalClient;
            this.abortS3Client = abortClient;
            this.s3Presigner = presigner;
            this.ownsS3Client = true;
            this.ownsAbortS3Client = true;
            this.ownsS3Presigner = true;
            this.tempDirectory = null;
            this.nanoTimeSupplier = System::nanoTime;
        } catch (RuntimeException e) {
            if (generalClient != null) {
                try { generalClient.close(); } catch (Exception ignored) {}
            }
            if (abortClient != null) {
                try { abortClient.close(); } catch (Exception ignored) {}
            }
            if (presigner != null) {
                try { presigner.close(); } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    public R2MovieStorageAdapter(String bucket, S3Client s3Client) {
        this(bucket, s3Client, s3Client, null, false, false, false, null, System::nanoTime);
    }

    public R2MovieStorageAdapter(String bucket, S3Client s3Client, S3Presigner s3Presigner) {
        this(bucket, s3Client, s3Client, s3Presigner, false, false, false, null, System::nanoTime);
    }

    public R2MovieStorageAdapter(String bucket, S3Client s3Client, S3Client abortS3Client, S3Presigner s3Presigner) {
        this(bucket, s3Client, abortS3Client, s3Presigner, false, false, false, null, System::nanoTime);
    }

    R2MovieStorageAdapter(String bucket, S3Client s3Client, Path tempDirectory) {
        this(bucket, s3Client, s3Client, null, false, false, false, tempDirectory, System::nanoTime);
    }

    R2MovieStorageAdapter(String bucket, S3Client s3Client, S3Presigner s3Presigner, Path tempDirectory) {
        this(bucket, s3Client, s3Client, s3Presigner, false, false, false, tempDirectory, System::nanoTime);
    }

    R2MovieStorageAdapter(String bucket, S3Client s3Client, S3Presigner s3Presigner, Path tempDirectory, LongSupplier nanoTimeSupplier) {
        this(bucket, s3Client, s3Client, s3Presigner, false, false, false, tempDirectory, nanoTimeSupplier);
    }

    R2MovieStorageAdapter(String bucket, S3Client s3Client, S3Client abortS3Client, S3Presigner s3Presigner, Path tempDirectory, LongSupplier nanoTimeSupplier) {
        this(bucket, s3Client, abortS3Client, s3Presigner, false, false, false, tempDirectory, nanoTimeSupplier);
    }

    private R2MovieStorageAdapter(
            String bucket,
            S3Client s3Client,
            S3Client abortS3Client,
            S3Presigner s3Presigner,
            boolean ownsS3Client,
            boolean ownsAbortS3Client,
            boolean ownsS3Presigner,
            Path tempDirectory,
            LongSupplier nanoTimeSupplier
    ) {
        this.bucket = Objects.requireNonNull(bucket, "Bucket cannot be null.");
        this.s3Client = Objects.requireNonNull(s3Client, "S3Client cannot be null.");
        this.abortS3Client = Objects.requireNonNull(abortS3Client, "Abort S3Client cannot be null.");
        this.s3Presigner = s3Presigner;
        this.ownsS3Client = ownsS3Client;
        this.ownsAbortS3Client = ownsAbortS3Client;
        this.ownsS3Presigner = ownsS3Presigner;
        this.tempDirectory = tempDirectory;
        this.nanoTimeSupplier = (nanoTimeSupplier != null) ? nanoTimeSupplier : System::nanoTime;
    }

    static final class ObservationBudget {
        private final LongSupplier nanoTimeSupplier;
        private final long startNanos;
        private final long totalBudgetNanos;

        private ObservationBudget(LongSupplier nanoTimeSupplier, Duration totalBudget) {
            this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier cannot be null");
            Objects.requireNonNull(totalBudget, "totalBudget cannot be null");
            if (totalBudget.isNegative() || totalBudget.isZero()) {
                throw new IllegalArgumentException("totalBudget must be positive, got: " + totalBudget);
            }
            this.totalBudgetNanos = totalBudget.toNanos();
            this.startNanos = nanoTimeSupplier.getAsLong();
        }

        public static ObservationBudget start(LongSupplier nanoTimeSupplier, Duration totalBudget) {
            return new ObservationBudget(nanoTimeSupplier, totalBudget);
        }

        public Duration remaining() {
            long elapsedNanos = nanoTimeSupplier.getAsLong() - startNanos;
            long remNanos = totalBudgetNanos - elapsedNanos;
            return remNanos > 0 ? Duration.ofNanos(remNanos) : Duration.ZERO;
        }

        public boolean isExhausted() {
            return (nanoTimeSupplier.getAsLong() - startNanos) >= totalBudgetNanos;
        }

        public Optional<ObservationRequestPlan> planRequest() {
            Duration rem = remaining();
            if (rem.compareTo(OBSERVATION_MIN_DISPATCH_THRESHOLD) <= 0) {
                return Optional.empty();
            }

            Duration callTimeout = rem.compareTo(OBSERVATION_API_CALL_TIMEOUT) < 0
                    ? rem
                    : OBSERVATION_API_CALL_TIMEOUT;

            Duration maxAllowedAttempt = callTimeout.minus(OBSERVATION_ATTEMPT_TIMEOUT_MARGIN);
            if (maxAllowedAttempt.compareTo(Duration.ofMillis(100)) <= 0) {
                return Optional.empty();
            }

            Duration boundedAttempt = OBSERVATION_API_CALL_ATTEMPT_TIMEOUT.compareTo(maxAllowedAttempt) > 0
                    ? maxAllowedAttempt
                    : OBSERVATION_API_CALL_ATTEMPT_TIMEOUT;

            Duration attemptTimeout = boundedAttempt.compareTo(OBSERVATION_API_CALL_ATTEMPT_TIMEOUT) < 0
                    ? boundedAttempt
                    : OBSERVATION_API_CALL_ATTEMPT_TIMEOUT;

            AwsRequestOverrideConfiguration overrideConfig = AwsRequestOverrideConfiguration.builder()
                    .apiCallTimeout(callTimeout)
                    .apiCallAttemptTimeout(attemptTimeout)
                    .build();

            return Optional.of(new ObservationRequestPlan(overrideConfig, rem, callTimeout, attemptTimeout));
        }
    }

    record ObservationRequestPlan(
            AwsRequestOverrideConfiguration overrideConfiguration,
            Duration remaining,
            Duration apiCallTimeout,
            Duration apiCallAttemptTimeout
    ) {
        ObservationRequestPlan {
            Objects.requireNonNull(overrideConfiguration, "overrideConfiguration cannot be null");
            Objects.requireNonNull(remaining, "remaining cannot be null");
            Objects.requireNonNull(apiCallTimeout, "apiCallTimeout cannot be null");
            Objects.requireNonNull(apiCallAttemptTimeout, "apiCallAttemptTimeout cannot be null");
            if (apiCallTimeout.isNegative() || apiCallTimeout.isZero()) {
                throw new IllegalArgumentException("apiCallTimeout must be strictly positive: " + apiCallTimeout);
            }
            if (apiCallAttemptTimeout.isNegative() || apiCallAttemptTimeout.isZero()) {
                throw new IllegalArgumentException("apiCallAttemptTimeout must be strictly positive: " + apiCallAttemptTimeout);
            }
            if (apiCallAttemptTimeout.compareTo(apiCallTimeout) >= 0) {
                throw new IllegalArgumentException("apiCallAttemptTimeout must be strictly less than apiCallTimeout");
            }
        }
    }

    static ClientOverrideConfiguration createAbortClientOverrideConfiguration() {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(ABORT_API_CALL_TIMEOUT)
                .apiCallAttemptTimeout(ABORT_API_CALL_ATTEMPT_TIMEOUT)
                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(ABORT_MAX_ATTEMPTS).build())
                .build();
    }

    static ClientOverrideConfiguration createGeneralClientOverrideConfiguration() {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(30))
                .apiCallAttemptTimeout(Duration.ofSeconds(15))
                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                .build();
    }

    private static final AwsRequestOverrideConfiguration NON_RETRYABLE_MUTATION_OVERRIDE =
            AwsRequestOverrideConfiguration.builder()
                    .apiCallAttemptTimeout(Duration.ofSeconds(15))
                    .apiCallTimeout(Duration.ofSeconds(20))
                    .build();

    private static S3Client createS3Client(R2MovieStorageProperties properties) {
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
                .overrideConfiguration(createGeneralClientOverrideConfiguration())
                .build();
    }

    static S3Client createS3Client(R2MovieStorageProperties properties, SdkHttpClient httpClient) {
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
                .httpClient(httpClient)
                .overrideConfiguration(createGeneralClientOverrideConfiguration())
                .build();
    }

    static S3Client createAbortS3Client(R2MovieStorageProperties properties) {
        return createAbortS3Client(properties, UrlConnectionHttpClient.builder());
    }

    static S3Client createAbortS3Client(R2MovieStorageProperties properties, SdkHttpClient httpClient) {
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
                .httpClient(httpClient)
                .overrideConfiguration(createAbortClientOverrideConfiguration())
                .build();
    }

    static S3Client createAbortS3Client(R2MovieStorageProperties properties, SdkHttpClient.Builder<?> httpClientBuilder) {
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
                .httpClientBuilder(httpClientBuilder)
                .overrideConfiguration(createAbortClientOverrideConfiguration())
                .build();
    }

    private static S3Presigner createS3Presigner(R2MovieStorageProperties properties) {
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
            return Files.createTempFile(tempDirectory, "r2movie-staging-", ".tmp");
        }
        return Files.createTempFile("r2movie-staging-", ".tmp");
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

    @Override
    public void purgeExactKey(StorageKey key) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        throw new UnsupportedOperationException(
                "Version-level physical purge (purgeExactKey) is unsupported on Cloudflare R2 movie storage. "
                + "Cloudflare R2 does not support S3 version listing or version-specific deletion semantics."
        );
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
                .contentType(mimeType.value())
                .overrideConfiguration(NON_RETRYABLE_MUTATION_OVERRIDE);

        if (!metadata.isEmpty()) {
            createRequestBuilder.metadata(metadata);
        }

        try {
            CreateMultipartUploadResponse response = s3Client.createMultipartUpload(createRequestBuilder.build());
            return response.uploadId();
        } catch (S3Exception e) {
            if (isDefinitiveInitiationRejection(e)) {
                String errorCode = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : "Rejected";
                throw new StorageInitiationRejectedException("Failed to initiate multipart upload: provider definitively rejected initiation [errorCode=" + errorCode + "]", e);
            }
            if (isTimeoutOrTransportException(e)) {
                throw new StorageAmbiguousInitiationException("Failed to initiate multipart upload: provider initiation timed out or connection was interrupted", e);
            }
            if (is5xx(e)) {
                throw new StorageAmbiguousInitiationException("Failed to initiate multipart upload: provider server error (" + e.statusCode() + ")", e);
            }
            String errorCode = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : "Unknown";
            throw new StorageAmbiguousInitiationException("Failed to initiate multipart upload: ambiguous provider failure (status " + e.statusCode() + ", code " + errorCode + ")", e);
        } catch (AwsServiceException | SdkClientException e) {
            if (isTimeoutOrTransportException(e)) {
                throw new StorageAmbiguousInitiationException("Failed to initiate multipart upload: provider initiation timed out or connection was interrupted", e);
            }
            throw new StorageAmbiguousInitiationException("Failed to initiate multipart upload: ambiguous SDK client failure", e);
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
            throw new StorageException("S3Presigner is not configured on this R2MovieStorageAdapter instance.");
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
        final int uploadIdLength = uploadId.length();

        try {
            while (true) {
                pageCount++;
                if (pageCount > maxAllowedPages) {
                    throw new StorageException(
                            "Exceeded maximum pagination page limit (" + maxAllowedPages + ") for key: " + key.value()
                                    + " (uploadIdLength=" + uploadIdLength + ")"
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
                            throw new StorageException(
                                    "Provider returned null part in listing for key: " + key.value()
                                            + " (uploadIdLength=" + uploadIdLength + ")"
                            );
                        }
                        if (part.partNumber() == null || part.partNumber() <= lastPartNumber) {
                            throw new StorageException(
                                     "Provider returned unordered or non-advancing part number ("
                                             + part.partNumber() + ") after " + lastPartNumber
                                             + " for key: " + key.value() + " (uploadIdLength=" + uploadIdLength + ")"
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
                                "Provider returned truncated response with no parts for key: " + key.value()
                                        + " (uploadIdLength=" + uploadIdLength + ")"
                        );
                    }
                    Integer nextMarker = response.nextPartNumberMarker();
                    if (nextMarker == null) {
                        throw new StorageException(
                                "Provider returned truncated response without nextPartNumberMarker for key: " + key.value()
                                        + " (uploadIdLength=" + uploadIdLength + ")"
                        );
                    }
                    if (nextMarker < 1 || nextMarker > 10_000) {
                        throw new StorageException(
                                "Provider returned out-of-range nextPartNumberMarker (" + nextMarker
                                        + ") for key: " + key.value() + " (uploadIdLength=" + uploadIdLength + ")"
                        );
                    }
                    if (currentMarker != null && nextMarker <= currentMarker) {
                        throw new StorageException(
                                "Provider returned non-advancing nextPartNumberMarker (" + nextMarker
                                        + " <= " + currentMarker + ") for key: " + key.value()
                                        + " (uploadIdLength=" + uploadIdLength + ")"
                        );
                    }
                    if (!nextMarker.equals(lastPartNumber)) {
                        throw new StorageException(
                                "Provider returned nextPartNumberMarker (" + nextMarker
                                        + ") inconsistent with last returned part (" + lastPartNumber
                                        + ") for key: " + key.value() + " (uploadIdLength=" + uploadIdLength + ")"
                        );
                    }
                    currentMarker = nextMarker;
                } else {
                    break;
                }
            }

            return List.copyOf(result);
        } catch (NoSuchUploadException e) {
            throw new StorageUploadNotFoundException(key, uploadId, e);
        } catch (S3Exception e) {
            if (isNoSuchUpload(e)) {
                throw new StorageUploadNotFoundException(key, uploadId, e);
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
                .overrideConfiguration(NON_RETRYABLE_MUTATION_OVERRIDE)
                .build();

        try {
            CompleteMultipartUploadResponse response = s3Client.completeMultipartUpload(completeRequest);
            StorageLocation location = StorageLocation.of(PROVIDER_ID, key);
            return new MultipartCompletedObject(location, response.eTag(), response.versionId());
        } catch (NoSuchUploadException e) {
            throw new StorageUploadNotFoundException(key, uploadId, e);
        } catch (S3Exception e) {
            if (isNoSuchUpload(e)) {
                throw new StorageUploadNotFoundException(key, uploadId, e);
            }
            if (is5xx(e) || isTimeoutOrTransportException(e)) {
                throw new StorageAmbiguousCompletionException("Ambiguous multipart completion outcome for key: " + key.value(), e);
            }
            throw new StorageException("Failed to complete multipart upload for key: " + key.value(), e);
        } catch (AwsServiceException | SdkClientException e) {
            if (isTimeoutOrTransportException(e)) {
                throw new StorageAmbiguousCompletionException("Ambiguous multipart completion outcome for key: " + key.value(), e);
            }
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
            AbortMultipartUploadResponse response = abortS3Client.abortMultipartUpload(abortRequest);
            if (response == null) {
                throw new StorageException("R2 Movie abort multipart upload returned null response for key: " + key.value());
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
    public String configuredBucketName() {
        return this.bucket;
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
            if (isAuthoritativeNoSuchKey(e)) {
                throw new StorageObjectNotFoundException(key, e);
            }
            throw new StorageException("Failed to inspect metadata for key: " + key.value(), e);
        } catch (AwsServiceException | SdkClientException e) {
            throw new StorageException("Failed to inspect metadata for key: " + key.value(), e);
        }
    }

    @Override
    public MultipartUploadPresenceObservation observeMultipartUploadPresence(StorageKey key, String uploadId) {
        ObservationBudget budget = ObservationBudget.start(this.nanoTimeSupplier, OBSERVATION_DEFAULT_OPERATION_TIMEOUT);
        return observeMultipartUploadPresenceInternal(key, uploadId, budget);
    }

    private MultipartUploadPresenceObservation observeMultipartUploadPresenceInternal(
            StorageKey key,
            String uploadId,
            ObservationBudget budget
    ) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        Objects.requireNonNull(uploadId, "uploadId cannot be null.");
        if (uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId cannot be blank.");
        }

        try {
            String currentKeyMarker = null;
            String currentUploadIdMarker = null;
            Set<String> seenMarkerPairs = new HashSet<>();

            for (int page = 1; page <= OBSERVATION_MAX_PAGES; page++) {
                if (Thread.currentThread().isInterrupted()) {
                    return new MultipartUploadPresenceObservation(
                            key,
                            uploadId,
                            MultipartUploadPresence.UNKNOWN,
                            Instant.now(),
                            "OBSERVATION_INTERRUPTED"
                    );
                }

                Optional<ObservationRequestPlan> planOpt = budget.planRequest();
                if (planOpt.isEmpty()) {
                    return new MultipartUploadPresenceObservation(
                            key,
                            uploadId,
                            MultipartUploadPresence.UNKNOWN,
                            Instant.now(),
                            "OBSERVATION_TIMEOUT_EXCEEDED"
                    );
                }
                ObservationRequestPlan plan = planOpt.get();

                ListMultipartUploadsRequest.Builder requestBuilder = ListMultipartUploadsRequest.builder()
                        .bucket(this.bucket)
                        .prefix(key.value())
                        .maxUploads(OBSERVATION_MAX_UPLOADS_PER_PAGE)
                        .overrideConfiguration(plan.overrideConfiguration());

                if (currentKeyMarker != null) {
                    requestBuilder.keyMarker(currentKeyMarker);
                }
                if (currentUploadIdMarker != null) {
                    requestBuilder.uploadIdMarker(currentUploadIdMarker);
                }

                ListMultipartUploadsResponse response = s3Client.listMultipartUploads(requestBuilder.build());

                if (budget.isExhausted()) {
                    return new MultipartUploadPresenceObservation(
                            key,
                            uploadId,
                            MultipartUploadPresence.UNKNOWN,
                            Instant.now(),
                            "OBSERVATION_TIMEOUT_EXCEEDED"
                    );
                }

                if (response.uploads() != null) {
                    for (MultipartUpload upload : response.uploads()) {
                        if (upload == null
                                || upload.key() == null
                                || upload.key().isBlank()
                                || upload.uploadId() == null
                                || upload.uploadId().isBlank()) {
                            return new MultipartUploadPresenceObservation(
                                    key,
                                    uploadId,
                                    MultipartUploadPresence.UNKNOWN,
                                    Instant.now(),
                                    "MALFORMED_PAGINATION: malformed upload record with missing key or uploadId"
                            );
                        }
                    }
                    for (MultipartUpload upload : response.uploads()) {
                        if (key.value().equals(upload.key()) && uploadId.equals(upload.uploadId())) {
                            return new MultipartUploadPresenceObservation(
                                    key,
                                    uploadId,
                                    MultipartUploadPresence.ACTIVE,
                                    Instant.now(),
                                    null
                            );
                        }
                    }
                }

                Boolean isTruncated = response.isTruncated();
                if (isTruncated == null) {
                    return new MultipartUploadPresenceObservation(
                            key,
                            uploadId,
                            MultipartUploadPresence.UNKNOWN,
                            Instant.now(),
                            "AMBIGUOUS_PAGINATION: missing IsTruncated in response"
                    );
                }

                if (!isTruncated) {
                    return new MultipartUploadPresenceObservation(
                            key,
                            uploadId,
                            MultipartUploadPresence.NOT_LISTED,
                            Instant.now(),
                            null
                    );
                }

                if (page == OBSERVATION_MAX_PAGES) {
                    return new MultipartUploadPresenceObservation(
                            key,
                            uploadId,
                            MultipartUploadPresence.UNKNOWN,
                            Instant.now(),
                            "PAGINATION_BUDGET_EXCEEDED: listings truncated beyond " + OBSERVATION_MAX_PAGES + " pages"
                    );
                }

                if (!response.hasUploads() || response.uploads() == null || response.uploads().isEmpty()) {
                    return new MultipartUploadPresenceObservation(
                            key,
                            uploadId,
                            MultipartUploadPresence.UNKNOWN,
                            Instant.now(),
                            "MALFORMED_PAGINATION: truncated listing with empty uploads"
                    );
                }

                String nextKeyMarker = response.nextKeyMarker();
                String nextUploadIdMarker = response.nextUploadIdMarker();

                if (nextKeyMarker == null || nextKeyMarker.isBlank()) {
                    return new MultipartUploadPresenceObservation(
                            key,
                            uploadId,
                            MultipartUploadPresence.UNKNOWN,
                            Instant.now(),
                            "MALFORMED_PAGINATION: truncated listing missing NextKeyMarker"
                    );
                }
                if (nextUploadIdMarker == null || nextUploadIdMarker.isBlank()) {
                    return new MultipartUploadPresenceObservation(
                            key,
                            uploadId,
                            MultipartUploadPresence.UNKNOWN,
                            Instant.now(),
                            "MALFORMED_PAGINATION: truncated listing missing NextUploadIdMarker"
                    );
                }

                String markerPair = nextKeyMarker + "\0" + nextUploadIdMarker;
                if (!seenMarkerPairs.add(markerPair)) {
                    return new MultipartUploadPresenceObservation(
                            key,
                            uploadId,
                            MultipartUploadPresence.UNKNOWN,
                            Instant.now(),
                            "NON_PROGRESSING_PAGINATION: repeated marker pair"
                    );
                }

                if (currentKeyMarker != null) {
                    int keyCmp = nextKeyMarker.compareTo(currentKeyMarker);
                    if (keyCmp < 0) {
                        return new MultipartUploadPresenceObservation(
                                key,
                                uploadId,
                                MultipartUploadPresence.UNKNOWN,
                                Instant.now(),
                                "NON_PROGRESSING_PAGINATION: regressing key marker"
                        );
                    }
                    if (keyCmp == 0) {
                        int uploadIdCmp = nextUploadIdMarker.compareTo(currentUploadIdMarker);
                        if (uploadIdCmp < 0) {
                            return new MultipartUploadPresenceObservation(
                                    key,
                                    uploadId,
                                    MultipartUploadPresence.UNKNOWN,
                                    Instant.now(),
                                    "NON_PROGRESSING_PAGINATION: regressing upload ID marker"
                            );
                        }
                        if (uploadIdCmp == 0) {
                            return new MultipartUploadPresenceObservation(
                                    key,
                                    uploadId,
                                    MultipartUploadPresence.UNKNOWN,
                                    Instant.now(),
                                    "NON_PROGRESSING_PAGINATION: repeated marker pair"
                            );
                        }
                    }
                }

                currentKeyMarker = nextKeyMarker;
                currentUploadIdMarker = nextUploadIdMarker;
            }

            return new MultipartUploadPresenceObservation(
                    key,
                    uploadId,
                    MultipartUploadPresence.UNKNOWN,
                    Instant.now(),
                    "PAGINATION_BUDGET_EXCEEDED: listings truncated beyond " + OBSERVATION_MAX_PAGES + " pages"
            );
        } catch (S3Exception e) {
            if (isInterrupted(e)) {
                Thread.currentThread().interrupt();
                return new MultipartUploadPresenceObservation(
                        key,
                        uploadId,
                        MultipartUploadPresence.UNKNOWN,
                        Instant.now(),
                        "OBSERVATION_INTERRUPTED"
                );
            }
            if (budget.isExhausted()) {
                return new MultipartUploadPresenceObservation(
                        key,
                        uploadId,
                        MultipartUploadPresence.UNKNOWN,
                        Instant.now(),
                        "OBSERVATION_TIMEOUT_EXCEEDED"
                );
            }
            return new MultipartUploadPresenceObservation(
                    key,
                    uploadId,
                    MultipartUploadPresence.UNKNOWN,
                    Instant.now(),
                    classifySafeObservationError(e)
            );
        } catch (AwsServiceException | SdkClientException e) {
            if (isInterrupted(e)) {
                Thread.currentThread().interrupt();
                return new MultipartUploadPresenceObservation(
                        key,
                        uploadId,
                        MultipartUploadPresence.UNKNOWN,
                        Instant.now(),
                        "OBSERVATION_INTERRUPTED"
                );
            }
            if (budget.isExhausted()) {
                return new MultipartUploadPresenceObservation(
                        key,
                        uploadId,
                        MultipartUploadPresence.UNKNOWN,
                        Instant.now(),
                        "OBSERVATION_TIMEOUT_EXCEEDED"
                );
            }
            return new MultipartUploadPresenceObservation(
                    key,
                    uploadId,
                    MultipartUploadPresence.UNKNOWN,
                    Instant.now(),
                    classifySafeObservationError(e)
            );
        }
    }

    @Override
    public StorageObjectPresenceObservation observeObjectPresence(StorageKey key) {
        ObservationBudget budget = ObservationBudget.start(this.nanoTimeSupplier, OBSERVATION_DEFAULT_OPERATION_TIMEOUT);
        return observeObjectPresenceInternal(key, budget);
    }

    private StorageObjectPresenceObservation observeObjectPresenceInternal(StorageKey key, ObservationBudget budget) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");

        if (Thread.currentThread().isInterrupted()) {
            return new StorageObjectPresenceObservation(
                    key,
                    StorageObjectPresence.UNKNOWN,
                    null,
                    Instant.now(),
                    "OBSERVATION_INTERRUPTED"
            );
        }

        Optional<ObservationRequestPlan> planOpt = budget.planRequest();
        if (planOpt.isEmpty()) {
            return new StorageObjectPresenceObservation(
                    key,
                    StorageObjectPresence.UNKNOWN,
                    null,
                    Instant.now(),
                    "OBSERVATION_TIMEOUT_EXCEEDED"
            );
        }
        ObservationRequestPlan plan = planOpt.get();

        HeadObjectRequest headRequest = HeadObjectRequest.builder()
                .bucket(this.bucket)
                .key(key.value())
                .overrideConfiguration(plan.overrideConfiguration())
                .build();

        try {
            HeadObjectResponse response = s3Client.headObject(headRequest);
            if (budget.isExhausted()) {
                return new StorageObjectPresenceObservation(
                        key,
                        StorageObjectPresence.UNKNOWN,
                        null,
                        Instant.now(),
                        "OBSERVATION_TIMEOUT_EXCEEDED"
                );
            }
            Map<String, String> userMetadata = (response.hasMetadata() && response.metadata() != null)
                    ? response.metadata()
                    : Map.of();
            MultipartObjectMetadata metadata = new MultipartObjectMetadata(
                    key,
                    response.contentLength(),
                    response.contentType(),
                    response.eTag(),
                    userMetadata,
                    response.versionId(),
                    PROVIDER_ID,
                    this.bucket
            );
            return new StorageObjectPresenceObservation(
                    key,
                    StorageObjectPresence.PRESENT,
                    metadata,
                    Instant.now(),
                    null
            );
        } catch (NoSuchKeyException e) {
            if (budget.isExhausted()) {
                return new StorageObjectPresenceObservation(
                        key,
                        StorageObjectPresence.UNKNOWN,
                        null,
                        Instant.now(),
                        "OBSERVATION_TIMEOUT_EXCEEDED"
                );
            }
            return new StorageObjectPresenceObservation(
                    key,
                    StorageObjectPresence.NOT_FOUND,
                    null,
                    Instant.now(),
                    null
            );
        } catch (S3Exception e) {
            if (isInterrupted(e)) {
                Thread.currentThread().interrupt();
                return new StorageObjectPresenceObservation(
                        key,
                        StorageObjectPresence.UNKNOWN,
                        null,
                        Instant.now(),
                        "OBSERVATION_INTERRUPTED"
                );
            }
            if (budget.isExhausted()) {
                return new StorageObjectPresenceObservation(
                        key,
                        StorageObjectPresence.UNKNOWN,
                        null,
                        Instant.now(),
                        "OBSERVATION_TIMEOUT_EXCEEDED"
                );
            }
            if (isExplicitNoSuchKey(e)) {
                return new StorageObjectPresenceObservation(
                        key,
                        StorageObjectPresence.NOT_FOUND,
                        null,
                        Instant.now(),
                        null
                );
            }
            return new StorageObjectPresenceObservation(
                    key,
                    StorageObjectPresence.UNKNOWN,
                    null,
                    Instant.now(),
                    classifySafeObservationError(e)
            );
        } catch (AwsServiceException | SdkClientException e) {
            if (isInterrupted(e)) {
                Thread.currentThread().interrupt();
                return new StorageObjectPresenceObservation(
                        key,
                        StorageObjectPresence.UNKNOWN,
                        null,
                        Instant.now(),
                        "OBSERVATION_INTERRUPTED"
                );
            }
            if (budget.isExhausted()) {
                return new StorageObjectPresenceObservation(
                        key,
                        StorageObjectPresence.UNKNOWN,
                        null,
                        Instant.now(),
                        "OBSERVATION_TIMEOUT_EXCEEDED"
                );
            }
            return new StorageObjectPresenceObservation(
                    key,
                    StorageObjectPresence.UNKNOWN,
                    null,
                    Instant.now(),
                    classifySafeObservationError(e)
            );
        }
    }

    @Override
    public MultipartPartsObservation observeUploadedParts(StorageKey key, String uploadId) {
        ObservationBudget budget = ObservationBudget.start(this.nanoTimeSupplier, OBSERVATION_DEFAULT_OPERATION_TIMEOUT);
        return observeUploadedPartsInternal(key, uploadId, budget);
    }

    private MultipartPartsObservation observeUploadedPartsInternal(
            StorageKey key,
            String uploadId,
            ObservationBudget budget
    ) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        Objects.requireNonNull(uploadId, "uploadId cannot be blank.");
        if (uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId cannot be blank.");
        }

        try {
            List<MultipartUploadedPart> result = new ArrayList<>();
            Integer currentMarker = null;
            Set<Integer> seenPartMarkers = new HashSet<>();

            for (int page = 1; page <= OBSERVATION_MAX_PAGES; page++) {
                if (Thread.currentThread().isInterrupted()) {
                    return new MultipartPartsObservation(
                            key,
                            uploadId,
                            MultipartPartsSummary.UNKNOWN,
                            List.of(),
                            Instant.now(),
                            "OBSERVATION_INTERRUPTED"
                    );
                }

                Optional<ObservationRequestPlan> planOpt = budget.planRequest();
                if (planOpt.isEmpty()) {
                    return new MultipartPartsObservation(
                            key,
                            uploadId,
                            MultipartPartsSummary.UNKNOWN,
                            List.of(),
                            Instant.now(),
                            "OBSERVATION_TIMEOUT_EXCEEDED"
                    );
                }
                ObservationRequestPlan plan = planOpt.get();

                ListPartsRequest.Builder requestBuilder = ListPartsRequest.builder()
                        .bucket(this.bucket)
                        .key(key.value())
                        .uploadId(uploadId)
                        .maxParts(OBSERVATION_MAX_PARTS_PER_PAGE)
                        .overrideConfiguration(plan.overrideConfiguration());

                if (currentMarker != null) {
                    requestBuilder.partNumberMarker(currentMarker);
                }

                ListPartsResponse response = s3Client.listParts(requestBuilder.build());

                if (budget.isExhausted()) {
                    return new MultipartPartsObservation(
                            key,
                            uploadId,
                            MultipartPartsSummary.UNKNOWN,
                            List.of(),
                            Instant.now(),
                            "OBSERVATION_TIMEOUT_EXCEEDED"
                    );
                }

                int partsOnThisPage = 0;
                Integer lastPartOnPage = null;

                if (response.parts() != null) {
                    for (Part part : response.parts()) {
                        if (part == null || part.partNumber() == null) {
                            return new MultipartPartsObservation(
                                    key,
                                    uploadId,
                                    MultipartPartsSummary.UNKNOWN,
                                    List.of(),
                                    Instant.now(),
                                    "MALFORMED_PAGINATION: malformed or null part record"
                            );
                        }
                        if (part.partNumber() < 1 || part.partNumber() > 10_000) {
                            return new MultipartPartsObservation(
                                    key,
                                    uploadId,
                                    MultipartPartsSummary.UNKNOWN,
                                    List.of(),
                                    Instant.now(),
                                    "MALFORMED_PAGINATION: part number out of range (" + part.partNumber() + ")"
                            );
                        }
                        if (lastPartOnPage != null && part.partNumber() <= lastPartOnPage) {
                            return new MultipartPartsObservation(
                                    key,
                                    uploadId,
                                    MultipartPartsSummary.UNKNOWN,
                                    List.of(),
                                    Instant.now(),
                                    "MALFORMED_PAGINATION: parts out of order on page"
                            );
                        }
                        if (currentMarker != null && part.partNumber() <= currentMarker) {
                            return new MultipartPartsObservation(
                                    key,
                                    uploadId,
                                    MultipartPartsSummary.UNKNOWN,
                                    List.of(),
                                    Instant.now(),
                                    "NON_PROGRESSING_PAGINATION: part number not strictly advancing ("
                                            + part.partNumber() + " <= " + currentMarker + ")"
                            );
                        }

                        lastPartOnPage = part.partNumber();
                        partsOnThisPage++;
                        result.add(new MultipartUploadedPart(
                                part.partNumber(),
                                part.size() != null ? part.size() : 0L,
                                part.eTag()
                        ));
                    }
                }

                Boolean isTruncated = response.isTruncated();
                if (isTruncated == null) {
                    return new MultipartPartsObservation(
                            key,
                            uploadId,
                            MultipartPartsSummary.UNKNOWN,
                            List.of(),
                            Instant.now(),
                            "AMBIGUOUS_PAGINATION: missing IsTruncated in response"
                    );
                }

                if (!isTruncated) {
                    MultipartPartsSummary summary = result.isEmpty()
                            ? MultipartPartsSummary.ZERO_PARTS_OBSERVED
                            : MultipartPartsSummary.PARTS_RETURNED;
                    return new MultipartPartsObservation(
                            key,
                            uploadId,
                            summary,
                            result,
                            Instant.now(),
                            null
                    );
                }

                if (page == OBSERVATION_MAX_PAGES) {
                    return new MultipartPartsObservation(
                            key,
                            uploadId,
                            MultipartPartsSummary.UNKNOWN,
                            List.of(),
                            Instant.now(),
                            "PAGINATION_BUDGET_EXCEEDED: parts truncated beyond " + OBSERVATION_MAX_PAGES + " pages"
                    );
                }

                if (partsOnThisPage == 0) {
                    return new MultipartPartsObservation(
                            key,
                            uploadId,
                            MultipartPartsSummary.UNKNOWN,
                            List.of(),
                            Instant.now(),
                            "MALFORMED_PAGINATION: truncated parts listing with empty parts"
                    );
                }

                Integer nextMarker = response.nextPartNumberMarker();
                if (nextMarker == null) {
                    return new MultipartPartsObservation(
                            key,
                            uploadId,
                            MultipartPartsSummary.UNKNOWN,
                            List.of(),
                            Instant.now(),
                            "MALFORMED_PAGINATION: truncated parts listing missing NextPartNumberMarker"
                    );
                }

                if (nextMarker < 1 || nextMarker > 10_000) {
                    return new MultipartPartsObservation(
                            key,
                            uploadId,
                            MultipartPartsSummary.UNKNOWN,
                            List.of(),
                            Instant.now(),
                            "MALFORMED_PAGINATION: NextPartNumberMarker out of range (" + nextMarker + ")"
                    );
                }

                if (!seenPartMarkers.add(nextMarker)) {
                    return new MultipartPartsObservation(
                            key,
                            uploadId,
                            MultipartPartsSummary.UNKNOWN,
                            List.of(),
                            Instant.now(),
                            "NON_PROGRESSING_PAGINATION: repeated part marker"
                    );
                }

                if (currentMarker != null) {
                    if (nextMarker.equals(currentMarker)) {
                        return new MultipartPartsObservation(
                                key,
                                uploadId,
                                MultipartPartsSummary.UNKNOWN,
                                List.of(),
                                Instant.now(),
                                "NON_PROGRESSING_PAGINATION: repeated part marker"
                        );
                    }
                    if (nextMarker < currentMarker) {
                        return new MultipartPartsObservation(
                                key,
                                uploadId,
                                MultipartPartsSummary.UNKNOWN,
                                List.of(),
                                Instant.now(),
                                "NON_PROGRESSING_PAGINATION: regressing part marker"
                        );
                    }
                }

                if (!nextMarker.equals(lastPartOnPage)) {
                    return new MultipartPartsObservation(
                            key,
                            uploadId,
                            MultipartPartsSummary.UNKNOWN,
                            List.of(),
                            Instant.now(),
                            "MALFORMED_PAGINATION: NextPartNumberMarker (" + nextMarker
                                    + ") does not match last part on page (" + lastPartOnPage + ")"
                    );
                }

                currentMarker = nextMarker;
            }

            return new MultipartPartsObservation(
                    key,
                    uploadId,
                    MultipartPartsSummary.UNKNOWN,
                    List.of(),
                    Instant.now(),
                    "PAGINATION_BUDGET_EXCEEDED: parts truncated beyond " + OBSERVATION_MAX_PAGES + " pages"
            );
        } catch (NoSuchUploadException e) {
            if (budget.isExhausted()) {
                return new MultipartPartsObservation(
                        key,
                        uploadId,
                        MultipartPartsSummary.UNKNOWN,
                        List.of(),
                        Instant.now(),
                        "OBSERVATION_TIMEOUT_EXCEEDED"
                );
            }
            return new MultipartPartsObservation(
                    key,
                    uploadId,
                    MultipartPartsSummary.NO_SUCH_UPLOAD,
                    List.of(),
                    Instant.now(),
                    null
            );
        } catch (S3Exception e) {
            if (isInterrupted(e)) {
                Thread.currentThread().interrupt();
                return new MultipartPartsObservation(
                        key,
                        uploadId,
                        MultipartPartsSummary.UNKNOWN,
                        List.of(),
                        Instant.now(),
                        "OBSERVATION_INTERRUPTED"
                );
            }
            if (budget.isExhausted()) {
                return new MultipartPartsObservation(
                        key,
                        uploadId,
                        MultipartPartsSummary.UNKNOWN,
                        List.of(),
                        Instant.now(),
                        "OBSERVATION_TIMEOUT_EXCEEDED"
                );
            }
            if (isNoSuchUpload(e)) {
                return new MultipartPartsObservation(
                        key,
                        uploadId,
                        MultipartPartsSummary.NO_SUCH_UPLOAD,
                        List.of(),
                        Instant.now(),
                        null
                );
            }
            return new MultipartPartsObservation(
                    key,
                    uploadId,
                    MultipartPartsSummary.UNKNOWN,
                    List.of(),
                    Instant.now(),
                    classifySafeObservationError(e)
            );
        } catch (AwsServiceException | SdkClientException e) {
            if (isInterrupted(e)) {
                Thread.currentThread().interrupt();
                return new MultipartPartsObservation(
                        key,
                        uploadId,
                        MultipartPartsSummary.UNKNOWN,
                        List.of(),
                        Instant.now(),
                        "OBSERVATION_INTERRUPTED"
                );
            }
            if (budget.isExhausted()) {
                return new MultipartPartsObservation(
                        key,
                        uploadId,
                        MultipartPartsSummary.UNKNOWN,
                        List.of(),
                        Instant.now(),
                        "OBSERVATION_TIMEOUT_EXCEEDED"
                );
            }
            return new MultipartPartsObservation(
                    key,
                    uploadId,
                    MultipartPartsSummary.UNKNOWN,
                    List.of(),
                    Instant.now(),
                    classifySafeObservationError(e)
            );
        }
    }

    @Override
    public MultipartUploadObservationSnapshot observeMultipartUpload(StorageKey key, String uploadId) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        Objects.requireNonNull(uploadId, "uploadId cannot be null.");
        if (uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId cannot be blank.");
        }

        ObservationBudget budget = ObservationBudget.start(this.nanoTimeSupplier, OBSERVATION_DEFAULT_OPERATION_TIMEOUT);

        MultipartUploadPresenceObservation presenceObs = observeMultipartUploadPresenceInternal(key, uploadId, budget);
        StorageObjectPresenceObservation objectObs = observeObjectPresenceInternal(key, budget);
        MultipartPartsObservation partsObs = observeUploadedPartsInternal(key, uploadId, budget);

        String consolidatedDiagnostic = buildConsolidatedDiagnostic(presenceObs, objectObs, partsObs);

        return new MultipartUploadObservationSnapshot(
                key,
                uploadId,
                presenceObs.presence(),
                objectObs.presence(),
                partsObs.summary(),
                partsObs.parts(),
                objectObs.metadata(),
                Instant.now(),
                consolidatedDiagnostic
        );
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle / Resource Cleanup
    // ---------------------------------------------------------------------------------------------

    @PreDestroy
    @Override
    public void close() {
        if (ownsS3Client) {
            s3Client.close();
        }
        if (ownsAbortS3Client) {
            abortS3Client.close();
        }
        if (ownsS3Presigner && s3Presigner != null) {
            s3Presigner.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helper error classifiers
    // ---------------------------------------------------------------------------------------------

    private static boolean isPreconditionFailed(S3Exception e) {
        return e.statusCode() == 412 || "PreconditionFailed".equalsIgnoreCase(e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null);
    }

    private static boolean isNoSuchUpload(S3Exception e) {
        if (e.statusCode() == 404) {
            String errorCode = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : null;
            return "NoSuchUpload".equalsIgnoreCase(errorCode);
        }
        return false;
    }

    private static boolean isExplicitNoSuchKey(S3Exception e) {
        if (e instanceof NoSuchKeyException) {
            return true;
        }
        String errorCode = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : null;
        return "NoSuchKey".equalsIgnoreCase(errorCode);
    }

    private static boolean isAuthoritativeNoSuchKey(S3Exception e) {
        if (e.statusCode() == 404) {
            String errorCode = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : null;
            return errorCode == null || "NoSuchKey".equalsIgnoreCase(errorCode);
        }
        return false;
    }

    private static boolean isDefinitiveInitiationRejection(S3Exception e) {
        int sc = e.statusCode();
        String code = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : null;
        if (sc == 400 || sc == 401 || sc == 403 || sc == 404 || sc == 405 || sc == 409 || sc == 411 || sc == 412 || sc == 416) {
            return true;
        }
        if ("AccessDenied".equalsIgnoreCase(code)
                || "InvalidBucketName".equalsIgnoreCase(code)
                || "NoSuchBucket".equalsIgnoreCase(code)
                || "MethodNotAllowed".equalsIgnoreCase(code)
                || "InvalidArgument".equalsIgnoreCase(code)) {
            return true;
        }
        return false;
    }

    private static boolean is5xx(S3Exception e) {
        return e.statusCode() >= 500 && e.statusCode() < 600;
    }

    private static boolean isTimeoutOrTransportException(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof SocketException
                    || current instanceof ApiCallTimeoutException
                    || current instanceof ApiCallAttemptTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isInterrupted(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof InterruptedException || current instanceof InterruptedIOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String buildConsolidatedDiagnostic(
            MultipartUploadPresenceObservation presenceObs,
            StorageObjectPresenceObservation objectObs,
            MultipartPartsObservation partsObs
    ) {
        List<String> diagnostics = new ArrayList<>();
        if (presenceObs.safeDiagnostic() != null) {
            diagnostics.add("uploadPresence: " + presenceObs.safeDiagnostic());
        }
        if (objectObs.safeDiagnostic() != null) {
            diagnostics.add("objectPresence: " + objectObs.safeDiagnostic());
        }
        if (partsObs.safeDiagnostic() != null) {
            diagnostics.add("partsSummary: " + partsObs.safeDiagnostic());
        }
        return diagnostics.isEmpty() ? null : String.join("; ", diagnostics);
    }

    private static String classifySafeObservationError(Throwable ex) {
        if (ex == null) {
            return "PROVIDER_ERROR";
        }
        if (ex instanceof S3Exception s3e) {
            int statusCode = s3e.statusCode();
            String errorCode = (s3e.awsErrorDetails() != null) ? s3e.awsErrorDetails().errorCode() : null;

            if (statusCode == 403 || "AccessDenied".equalsIgnoreCase(errorCode)) {
                return "PROVIDER_ACCESS_DENIED";
            }
            if (statusCode == 401) {
                return "PROVIDER_UNAUTHORIZED";
            }
            if ("NoSuchBucket".equalsIgnoreCase(errorCode)) {
                return "PROVIDER_BUCKET_NOT_FOUND";
            }
            if ("NoSuchKey".equalsIgnoreCase(errorCode)) {
                return "PROVIDER_KEY_NOT_FOUND";
            }
            if ("NoSuchUpload".equalsIgnoreCase(errorCode)) {
                return "PROVIDER_UPLOAD_NOT_FOUND";
            }
            if (statusCode == 404) {
                return "PROVIDER_RESOURCE_NOT_FOUND";
            }
            if (statusCode == 503 || "ServiceUnavailable".equalsIgnoreCase(errorCode) || "SlowDown".equalsIgnoreCase(errorCode)) {
                return "PROVIDER_SERVICE_UNAVAILABLE";
            }
            if (statusCode == 500) {
                return "PROVIDER_ERROR";
            }
            if (statusCode >= 500 && statusCode < 600) {
                return "PROVIDER_SERVER_ERROR";
            }
            if (statusCode == 400 || "InvalidRequest".equalsIgnoreCase(errorCode)) {
                return "PROVIDER_BAD_REQUEST";
            }
            if (statusCode == 409 || "Conflict".equalsIgnoreCase(errorCode)) {
                return "PROVIDER_CONFLICT";
            }
            if (statusCode == 412 || "PreconditionFailed".equalsIgnoreCase(errorCode)) {
                return "PROVIDER_PRECONDITION_FAILED";
            }
            if (statusCode >= 400 && statusCode < 500) {
                return "PROVIDER_CLIENT_ERROR";
            }
            return "PROVIDER_ERROR";
        }

        Throwable current = ex;
        while (current != null) {
            String name = current.getClass().getSimpleName().toLowerCase();
            String msg = (current.getMessage() != null) ? current.getMessage().toLowerCase() : "";
            if (name.contains("timeout") || msg.contains("timed out") || msg.contains("timeout")) {
                return "TIMEOUT_UNCERTAINTY";
            }
            if (name.contains("connect") || msg.contains("connection refused") || msg.contains("connection reset")) {
                return "CONNECTION_FAILURE";
            }
            current = current.getCause();
        }

        return "PROVIDER_ERROR";
    }
}
