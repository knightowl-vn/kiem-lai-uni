package com.universe.media.infrastructure.storage.b2;

import com.universe.media.application.ports.storage.MultipartPartsObservation;
import com.universe.media.application.ports.storage.MultipartPartsSummary;
import com.universe.media.application.ports.storage.MultipartUploadObservationSnapshot;
import com.universe.media.application.ports.storage.MultipartUploadPresence;
import com.universe.media.application.ports.storage.MultipartUploadPresenceObservation;
import com.universe.media.application.ports.storage.StorageObjectPresence;
import com.universe.media.application.ports.storage.StorageObjectPresenceObservation;
import com.universe.media.domain.StorageKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic AWS SDK fake-HTTP client tests verifying that observation operations
 * use the general S3Client with dynamic request-level timeout clamping, preserve fail-closed
 * semantics on timeout/interruption, and never perform destructive operations.
 */
class B2StorageAdapterObservationTimeoutTest {

    private static final String BUCKET = "test-b2-bucket";
    private static final String ENDPOINT = "https://s3.us-west-004.backblazeb2.com";
    private static final String ACCESS_KEY = "test-access-key";
    private static final String SECRET_KEY = "test-secret-key";
    private static final String REGION = "us-west-004";

    private B2StorageProperties properties;
    private final StorageKey key = StorageKey.of("videos/test-obs-video.mp4");
    private final String uploadId = "upload-obs-sdk-test";

    @BeforeEach
    void setUp() {
        properties = new B2StorageProperties(BUCKET, ENDPOINT, ACCESS_KEY, SECRET_KEY, REGION);
    }

    @Test
    @DisplayName("General S3Client executes ListMultipartUploads HTTP request and parses active upload")
    void shouldExecuteListMultipartUploadsViaSdkHttpClient() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListMultipartUploadsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Bucket>test-b2-bucket</Bucket>
                    <KeyMarker></KeyMarker>
                    <UploadIdMarker></UploadIdMarker>
                    <MaxUploads>100</MaxUploads>
                    <IsTruncated>false</IsTruncated>
                    <Upload>
                        <Key>videos/test-obs-video.mp4</Key>
                        <UploadId>upload-obs-sdk-test</UploadId>
                        <Initiated>2026-10-12T12:00:00.000Z</Initiated>
                    </Upload>
                </ListMultipartUploadsResult>
                """;

        CountingHttpClient countingHttpClient = new CountingHttpClient(request ->
                HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(200).statusText("OK").build())
                        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))))
                        .build()
        );

        S3Client generalClient = B2StorageAdapter.createS3Client(properties, countingHttpClient);
        B2StorageAdapter adapter = new B2StorageAdapter(BUCKET, generalClient);

        MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

        assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.ACTIVE);
        assertThat(obs.safeDiagnostic()).isNull();
        assertThat(countingHttpClient.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SDK client catches SocketTimeoutException and returns UNKNOWN with TIMEOUT_UNCERTAINTY")
    void shouldHandleSocketTimeoutGracefullyAsUnknown() {
        CountingHttpClient countingHttpClient = new CountingHttpClient(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        S3Client generalClient = B2StorageAdapter.createS3Client(properties, countingHttpClient);
        B2StorageAdapter adapter = new B2StorageAdapter(BUCKET, generalClient);

        MultipartUploadPresenceObservation obs = adapter.observeMultipartUploadPresence(key, uploadId);

        assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.UNKNOWN);
        assertThat(obs.safeDiagnostic()).isEqualTo("TIMEOUT_UNCERTAINTY");
        assertThat(countingHttpClient.getRequestCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("HeadObject HTTP 404 with NoSuchKey is classified as NOT_FOUND")
    void shouldClassifyHeadObject404AsNotFound() {
        String errorXml = "<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>";
        CountingHttpClient countingHttpClient = new CountingHttpClient(request ->
                HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(404).statusText("Not Found").build())
                        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(errorXml.getBytes(StandardCharsets.UTF_8))))
                        .build()
        );

        S3Client generalClient = B2StorageAdapter.createS3Client(properties, countingHttpClient);
        B2StorageAdapter adapter = new B2StorageAdapter(BUCKET, generalClient);

        StorageObjectPresenceObservation obs = adapter.observeObjectPresence(key);

        assertThat(obs.presence()).isEqualTo(StorageObjectPresence.NOT_FOUND);
        assertThat(obs.safeDiagnostic()).isNull();
        assertThat(countingHttpClient.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Composite observation shares single monotonic budget and does not execute subsequent calls after timeout")
    void shouldShareMonotonicBudgetAcrossCompositeStepsWithSdkHttpClient() {
        String uploadsXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListMultipartUploadsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Bucket>test-b2-bucket</Bucket>
                    <IsTruncated>false</IsTruncated>
                    <Upload>
                        <Key>videos/test-obs-video.mp4</Key>
                        <UploadId>upload-obs-sdk-test</UploadId>
                    </Upload>
                </ListMultipartUploadsResult>
                """;

        AtomicInteger clockReads = new AtomicInteger();
        CountingHttpClient countingHttpClient = new CountingHttpClient(request ->
                HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(200).statusText("OK").build())
                        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(uploadsXml.getBytes(StandardCharsets.UTF_8))))
                        .build()
        );

        S3Client generalClient = B2StorageAdapter.createS3Client(properties, countingHttpClient);
        B2StorageAdapter adapter = new B2StorageAdapter(
                BUCKET,
                generalClient,
                null,
                null,
                () -> {
                    int r = clockReads.incrementAndGet();
                    return (r <= 3) ? 1_000_000_000L : 35_000_000_000L;
                }
        );

        MultipartUploadObservationSnapshot snapshot = adapter.observeMultipartUpload(key, uploadId);

        assertThat(snapshot.uploadPresence()).isEqualTo(MultipartUploadPresence.ACTIVE);
        assertThat(snapshot.objectPresence()).isEqualTo(StorageObjectPresence.UNKNOWN);
        assertThat(snapshot.partsSummary()).isEqualTo(MultipartPartsSummary.UNKNOWN);
        assertThat(snapshot.safeDiagnostic())
                .contains("objectPresence: OBSERVATION_TIMEOUT_EXCEEDED")
                .contains("partsSummary: OBSERVATION_TIMEOUT_EXCEEDED");

        // Verify only 1 HTTP request (ListMultipartUploads) was executed; HeadObject and ListParts were skipped!
        assertThat(countingHttpClient.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SDK fake-HTTP execution accepts configured short positive timeouts without error")
    void shouldAcceptShortPositiveTimeoutsInSdkHttpClient() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListMultipartUploadsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Bucket>test-b2-bucket</Bucket>
                    <KeyMarker></KeyMarker>
                    <UploadIdMarker></UploadIdMarker>
                    <MaxUploads>100</MaxUploads>
                    <IsTruncated>false</IsTruncated>
                    <Upload>
                        <Key>videos/test-obs-video.mp4</Key>
                        <UploadId>upload-obs-sdk-test</UploadId>
                    </Upload>
                </ListMultipartUploadsResult>
                """;

        CountingHttpClient countingHttpClient = new CountingHttpClient(request ->
                HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(200).statusText("OK").build())
                        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))))
                        .build()
        );

        AtomicInteger clockReads = new AtomicInteger();
        S3Client generalClient = B2StorageAdapter.createS3Client(properties, countingHttpClient);
        B2StorageAdapter adapterWithShortTimeout = new B2StorageAdapter(
                BUCKET,
                generalClient,
                null,
                null,
                () -> {
                    int r = clockReads.incrementAndGet();
                    return (r == 1) ? 1_000_000_000L : 1_000_000_000L + Duration.ofMillis(29_750).toNanos();
                }
        );

        MultipartUploadPresenceObservation obs = adapterWithShortTimeout.observeMultipartUploadPresence(key, uploadId);

        assertThat(obs.presence()).isEqualTo(MultipartUploadPresence.ACTIVE);
        assertThat(obs.safeDiagnostic()).isNull();
        assertThat(countingHttpClient.getRequestCount()).isEqualTo(1);
    }

    private static class CountingHttpClient implements SdkHttpClient {

        @FunctionalInterface
        interface ResponseHandler {
            HttpExecuteResponse handle(HttpExecuteRequest request) throws IOException;
        }

        private final ResponseHandler handler;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final List<HttpExecuteRequest> recordedRequests = Collections.synchronizedList(new ArrayList<>());

        CountingHttpClient(ResponseHandler handler) {
            this.handler = handler;
        }

        int getRequestCount() {
            return requestCount.get();
        }

        List<HttpExecuteRequest> getRecordedRequests() {
            return recordedRequests;
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() throws IOException {
                    requestCount.incrementAndGet();
                    recordedRequests.add(request);
                    return handler.handle(request);
                }

                @Override
                public void abort() {
                }
            };
        }

        @Override
        public void close() {
        }
    }
}
