package com.universe.media.infrastructure.storage.r2;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Cloudflare R2 implementation of {@link BinaryStoragePort} using the AWS SDK for Java v2 S3 API.
 * <p>
 * Core Contract Semantics:
 * <ul>
 *     <li>Provider ID: {@code r2}</li>
 *     <li>CREATE-ONLY uploads using conditional {@code If-None-Match: *} (no TOCTOU HEAD probe)</li>
 *     <li>Constant-memory local temporary file staging before remote PutObject</li>
 *     <li>Strict exact size verification during staging prior to S3 upload</li>
 *     <li>Caller stream ownership preservation (never closes store input stream)</li>
 *     <li>No ambiguous remote delete compensation</li>
 *     <li>Exact byte-range reads via native S3 Range header</li>
 *     <li>Idempotent deletions</li>
 *     <li>Full translation of SDK/HTTP errors to domain storage exceptions</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(
        name = "media.storage.provider",
        havingValue = "r2"
)
public class R2StorageAdapter implements BinaryStoragePort, AutoCloseable {

    public static final StorageProviderId PROVIDER_ID = StorageProviderId.of("r2");
    private static final int BUFFER_SIZE = 8192;

    private final String bucket;
    private final S3Client s3Client;
    private final boolean ownsS3Client;
    private final Path tempDirectory;

    @Autowired
    public R2StorageAdapter(R2StorageProperties properties) {
        this(
                Objects.requireNonNull(properties, "R2StorageProperties cannot be null.").bucket(),
                createS3Client(properties),
                true,
                null
        );
    }

    public R2StorageAdapter(String bucket, S3Client s3Client) {
        this(bucket, s3Client, false, null);
    }

    R2StorageAdapter(String bucket, S3Client s3Client, Path tempDirectory) {
        this(bucket, s3Client, false, tempDirectory);
    }

    private R2StorageAdapter(String bucket, S3Client s3Client, boolean ownsS3Client, Path tempDirectory) {
        this.bucket = Objects.requireNonNull(bucket, "Bucket cannot be null.");
        this.s3Client = Objects.requireNonNull(s3Client, "S3Client cannot be null.");
        this.ownsS3Client = ownsS3Client;
        this.tempDirectory = tempDirectory;
    }

    private static S3Client createS3Client(R2StorageProperties properties) {
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

    @Override
    public StorageProviderId providerId() {
        return PROVIDER_ID;
    }

    @Override
    public void store(
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
            return Files.createTempFile(tempDirectory, "r2-staging-", ".tmp");
        }
        return Files.createTempFile("r2-staging-", ".tmp");
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
    @PreDestroy
    public void close() {
        if (ownsS3Client && s3Client != null) {
            s3Client.close();
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
}
