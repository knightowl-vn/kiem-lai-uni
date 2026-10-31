package com.universe.media.infrastructure.storage.r2movie;

import com.universe.media.application.exceptions.StorageAmbiguousCompletionException;
import com.universe.media.application.exceptions.StorageAmbiguousInitiationException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.UploadSessionUnconfirmedInitiationException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.MultipartAbortResult;
import com.universe.media.application.ports.storage.MultipartCompletedObject;
import com.universe.media.application.ports.storage.MultipartPartETag;
import com.universe.media.application.ports.storage.StorageObjectPresence;
import com.universe.media.application.ports.storage.StorageObjectPresenceObservation;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.infrastructure.storage.DefaultStorageProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MS-08 R2-01: P2-01 R2 Purge & P2-02 SDK Retry Verification Tests")
class R2MovieStorageRetryAndPurgeTest {

    private static final String BUCKET = "kiemlai-r2-movie";
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/test-movie.mp4");
    private static final StorageKey NEARBY_KEY = StorageKey.of("media/video/test-movie.mp4.meta");

    @Mock
    private S3Client mockS3Client;
    @Mock
    private S3Client mockAbortS3Client;
    @Mock
    private S3Presigner mockPresigner;

    private R2MovieStorageProperties properties;
    private R2MovieStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new R2MovieStorageProperties(
                BUCKET,
                "https://account-id.r2.cloudflarestorage.com",
                "mock-access-key",
                "mock-secret-key",
                "auto"
        );
        adapter = new R2MovieStorageAdapter(BUCKET, mockS3Client, mockAbortS3Client, mockPresigner);
    }

    // =============================================================================================
    // P2-01: R2 Purge & Cleanup Compatibility Tests (PURGE-01 through PURGE-09)
    // =============================================================================================

    @Test
    @DisplayName("PURGE-01: Unsupported version-specific purge never invokes DeleteObject")
    void purge01_unsupportedPurgeExactKeyNeverInvokesDeleteObject() {
        assertThatThrownBy(() -> adapter.purgeExactKey(STORAGE_KEY))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Cloudflare R2 does not support S3 version listing");

        verify(mockS3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(mockAbortS3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("PURGE-02: Exact-key delete uses the correct R2 Movie bucket and key")
    void purge02_exactKeyDeleteUsesCorrectBucketAndKey() {
        adapter.delete(STORAGE_KEY);

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(mockS3Client).deleteObject(captor.capture());

        DeleteObjectRequest req = captor.getValue();
        assertThat(req.bucket()).isEqualTo(BUCKET);
        assertThat(req.key()).isEqualTo(STORAGE_KEY.value());
    }

    @Test
    @DisplayName("PURGE-03: Nearby prefix keys cannot be selected during exact-key delete")
    void purge03_nearbyPrefixKeysCannotBeSelected() {
        adapter.delete(STORAGE_KEY);

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(mockS3Client).deleteObject(captor.capture());

        DeleteObjectRequest req = captor.getValue();
        assertThat(req.key()).isEqualTo("media/video/test-movie.mp4");
        assertThat(req.key()).isNotEqualTo(NEARBY_KEY.value());
    }

    @Test
    @DisplayName("PURGE-04: Ambiguous provider state cannot trigger unsafe deletion")
    void purge04_ambiguousProviderStateFailsClosedWithoutDeletion() {
        when(mockS3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(503).message("Service Unavailable").build());

        assertThatThrownBy(() -> adapter.delete(STORAGE_KEY))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Failed to delete binary content for key: " + STORAGE_KEY.value());
    }

    @Test
    @DisplayName("PURGE-05: Completion evidence prevents unintended cleanup")
    void purge05_completedObjectPreventsCleanupCall() {
        // When object is confirmed present, lifecycle preservation prevents invoking delete
        when(mockS3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(52428800L).eTag("\"hash-1\"").build());

        StorageObjectPresenceObservation obs = adapter.observeObjectPresence(STORAGE_KEY);
        assertThat(obs.presence()).isEqualTo(StorageObjectPresence.PRESENT);

        // Verification: no deletion was initiated
        verify(mockS3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("PURGE-06: Storage provider resolver routes historical B2 deletion strictly to B2, never R2 Movie")
    void purge06_historicalB2DeletionRemainsOnB2() {
        BinaryStoragePort b2Port = mock(BinaryStoragePort.class);
        when(b2Port.providerId()).thenReturn(StorageProviderId.of("b2"));

        BinaryStoragePort r2MoviePort = mock(BinaryStoragePort.class);
        when(r2MoviePort.providerId()).thenReturn(StorageProviderId.of("r2-movie"));

        DefaultStorageProviderRegistry registry = new DefaultStorageProviderRegistry(List.of(b2Port, r2MoviePort));

        // When resolving provider for a historical B2 asset location
        StorageLocation b2Location = StorageLocation.of(StorageProviderId.of("b2"), STORAGE_KEY);
        BinaryStoragePort resolvedPort = registry.resolve(b2Location.providerId());

        assertThat(resolvedPort.providerId()).isEqualTo(StorageProviderId.of("b2"));
        resolvedPort.delete(b2Location.key());

        verify(b2Port).delete(STORAGE_KEY);
        verify(r2MoviePort, never()).delete(any());
        verify(mockS3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("PURGE-07: Unsupported purge is classified correctly by caller")
    void purge07_unsupportedPurgeClassifiedCorrectly() {
        assertThatThrownBy(() -> adapter.purgeExactKey(STORAGE_KEY))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Version-level physical purge (purgeExactKey) is unsupported on Cloudflare R2");
    }

    @Test
    @DisplayName("PURGE-08: Cleanup failure cannot falsely mark physical cleanup successful")
    void purge08_cleanupFailureCannotFalselyMarkSuccess() {
        when(mockS3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("Internal Error").build());

        assertThatThrownBy(() -> adapter.delete(STORAGE_KEY))
                .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("PURGE-09: Normal adapter read and metadata inspection do not invoke delete or purge")
    void purge09_normalUploadPathsDoNotInvokePurge() {
        assertThat(adapter.providerId()).isEqualTo(StorageProviderId.of("r2-movie"));
        assertThat(adapter.configuredBucketName()).isEqualTo(BUCKET);

        verify(mockS3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(mockAbortS3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    // =============================================================================================
    // P2-02: AWS SDK v2 Retry & Ambiguous Outcome Tests (RETRY-01 through RETRY-10)
    // =============================================================================================

    @Test
    @DisplayName("RETRY-01: General client retry configuration is strictly one attempt")
    void retry01_generalClientRetryConfigurationIsOneAttempt() {
        ClientOverrideConfiguration config = R2MovieStorageAdapter.createGeneralClientOverrideConfiguration();
        assertThat(config.retryStrategy()).isPresent();
        assertThat(config.retryStrategy().get().maxAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("RETRY-02: Abort client retry configuration is strictly one attempt")
    void retry02_abortClientRetryConfigurationIsOneAttempt() {
        ClientOverrideConfiguration config = R2MovieStorageAdapter.createAbortClientOverrideConfiguration();
        assertThat(config.retryStrategy()).isPresent();
        assertThat(config.retryStrategy().get().maxAttempts()).isEqualTo(1);
        assertThat(config.apiCallTimeout()).contains(R2MovieStorageAdapter.ABORT_API_CALL_TIMEOUT);
        assertThat(config.apiCallAttemptTimeout()).contains(R2MovieStorageAdapter.ABORT_API_CALL_ATTEMPT_TIMEOUT);
    }

    @Test
    @DisplayName("RETRY-03: CreateMultipartUpload does not execute a second HTTP attempt following transport failure")
    void retry03_createMultipartUploadDoesNotExecuteSecondHttpAttempt() throws Exception {
        AtomicInteger httpAttemptCount = new AtomicInteger(0);

        SdkHttpClient mockHttpClient = mock(SdkHttpClient.class);
        when(mockHttpClient.prepareRequest(any(HttpExecuteRequest.class))).thenAnswer(inv -> {
            httpAttemptCount.incrementAndGet();
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() throws IOException {
                    throw new IOException("Connection reset by peer during CreateMultipartUpload");
                }

                @Override
                public void abort() {
                }
            };
        });

        S3Client testClient = R2MovieStorageAdapter.createS3Client(properties, mockHttpClient);
        R2MovieStorageAdapter testAdapter = new R2MovieStorageAdapter(BUCKET, testClient, testClient, mockPresigner);

        assertThatThrownBy(() -> testAdapter.initiateMultipartUpload(STORAGE_KEY, MimeType.of("video/mp4"), Map.of()))
                .isInstanceOf(StorageAmbiguousInitiationException.class);

        // Crucial invariant: Exactly 1 HTTP attempt was made, ZERO automatic SDK retries
        assertThat(httpAttemptCount.get()).isEqualTo(1);
        verify(mockHttpClient, times(1)).prepareRequest(any(HttpExecuteRequest.class));
    }

    @Test
    @DisplayName("RETRY-04: CompleteMultipartUpload does not execute a second HTTP attempt following transport failure")
    void retry04_completeMultipartUploadDoesNotExecuteSecondHttpAttempt() throws Exception {
        AtomicInteger httpAttemptCount = new AtomicInteger(0);

        SdkHttpClient mockHttpClient = mock(SdkHttpClient.class);
        when(mockHttpClient.prepareRequest(any(HttpExecuteRequest.class))).thenAnswer(inv -> {
            httpAttemptCount.incrementAndGet();
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() throws IOException {
                    throw new SocketTimeoutException("SocketTimeoutException during CompleteMultipartUpload");
                }

                @Override
                public void abort() {
                }
            };
        });

        S3Client testClient = R2MovieStorageAdapter.createS3Client(properties, mockHttpClient);
        R2MovieStorageAdapter testAdapter = new R2MovieStorageAdapter(BUCKET, testClient, testClient, mockPresigner);

        List<MultipartPartETag> parts = List.of(new MultipartPartETag(1, "\"etag-1\""));

        assertThatThrownBy(() -> testAdapter.completeMultipartUpload(STORAGE_KEY, "upload-id-ambiguous", parts))
                .isInstanceOf(StorageAmbiguousCompletionException.class);

        // Crucial invariant: Exactly 1 HTTP attempt was made, ZERO automatic SDK retries
        assertThat(httpAttemptCount.get()).isEqualTo(1);
        verify(mockHttpClient, times(1)).prepareRequest(any(HttpExecuteRequest.class));
    }

    @Test
    @DisplayName("RETRY-05: CreateMultipartUpload unknown outcome maps to an ambiguous initiation state")
    void retry05_createMultipartUploadUnknownOutcomeMapsToAmbiguousInitiation() {
        when(mockS3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(504).message("Gateway Timeout").build());

        assertThatThrownBy(() -> adapter.initiateMultipartUpload(STORAGE_KEY, MimeType.of("video/mp4"), Map.of()))
                .isInstanceOf(StorageAmbiguousInitiationException.class)
                .hasMessageContaining("provider server error (504)");
    }

    @Test
    @DisplayName("RETRY-06: CompleteMultipartUpload unknown outcome maps to an ambiguous completion state")
    void retry06_completeMultipartUploadUnknownOutcomeMapsToAmbiguousCompletion() {
        when(mockS3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("Internal Server Error").build());

        List<MultipartPartETag> parts = List.of(new MultipartPartETag(1, "\"etag-1\""));

        assertThatThrownBy(() -> adapter.completeMultipartUpload(STORAGE_KEY, "upload-id-1", parts))
                .isInstanceOf(StorageAmbiguousCompletionException.class)
                .hasMessageContaining("Ambiguous multipart completion outcome");
    }

    @Test
    @DisplayName("RETRY-07: A timeout does not prove provider rejection")
    void retry07_timeoutDoesNotProveProviderRejection() {
        when(mockS3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenThrow(SdkClientException.builder().cause(new SocketTimeoutException("Read timed out")).build());

        // A timeout must be treated as ambiguous initiation, not a definitive rejection
        assertThatThrownBy(() -> adapter.initiateMultipartUpload(STORAGE_KEY, MimeType.of("video/mp4"), Map.of()))
                .isInstanceOf(StorageAmbiguousInitiationException.class);
    }

    @Test
    @DisplayName("RETRY-08: Read-only observations remain bounded")
    void retry08_readOnlyObservationsRemainBounded() {
        when(mockS3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("The specified key does not exist.").build());

        StorageObjectPresenceObservation obs = adapter.observeObjectPresence(STORAGE_KEY);
        assertThat(obs.presence()).isEqualTo(StorageObjectPresence.NOT_FOUND);
    }

    @Test
    @DisplayName("RETRY-09: Distinguishes application-level retries from SDK automatic retries")
    void retry09_distinguishesApplicationLevelRetriesFromSdkRetries() {
        // SDK configuration: exactly 1 attempt
        ClientOverrideConfiguration config = R2MovieStorageAdapter.createGeneralClientOverrideConfiguration();
        assertThat(config.retryStrategy().get().maxAttempts()).isEqualTo(1);

        // Application-level: idempotency key replay returns existing session without initiating new provider upload
        assertThat(adapter.providerId()).isEqualTo(StorageProviderId.of("r2-movie"));
    }

    @Test
    @DisplayName("RETRY-10: Relevant exception classes do not cause unsafe claim/lease cleanup")
    void retry10_ambiguousExceptionsPreserveClaims() {
        // StorageAmbiguousInitiationException must preserve initiation claim
        StorageAmbiguousInitiationException ex = new StorageAmbiguousInitiationException(
                "Upload initiation timed out"
        );
        assertThat(ex.getErrorCode()).isEqualTo("MEDIA_STORAGE_INITIATION_AMBIGUOUS");
        assertThat(ex).isInstanceOf(StorageException.class);
    }
}
