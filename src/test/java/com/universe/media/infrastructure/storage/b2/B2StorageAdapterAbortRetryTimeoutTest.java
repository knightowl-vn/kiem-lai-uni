package com.universe.media.infrastructure.storage.b2;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.storage.MultipartAbortResult;
import com.universe.media.domain.StorageKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic AWS SDK-level tests verifying the dedicated abort client retry strategy,
 * call timeouts, and fail-closed non-destructive behavior.
 */
class B2StorageAdapterAbortRetryTimeoutTest {

    private static final String BUCKET = "test-b2-bucket";
    private static final String ENDPOINT = "https://s3.us-west-004.backblazeb2.com";
    private static final String ACCESS_KEY = "test-access-key";
    private static final String SECRET_KEY = "test-secret-key";
    private static final String REGION = "us-west-004";

    private B2StorageProperties properties;

    @BeforeEach
    void setUp() {
        properties = new B2StorageProperties(BUCKET, ENDPOINT, ACCESS_KEY, SECRET_KEY, REGION);
    }

    @Test
    @DisplayName("Abort client override configuration specifies bounded timeouts and maxAttempts = 1")
    void shouldVerifyAbortClientOverrideConfiguration() {
        ClientOverrideConfiguration config = B2StorageAdapter.createAbortClientOverrideConfiguration();

        assertThat(config.apiCallTimeout()).isPresent().contains(Duration.ofSeconds(30));
        assertThat(config.apiCallAttemptTimeout()).isPresent().contains(Duration.ofSeconds(25));
        assertThat(config.retryStrategy()).isPresent();
        assertThat(config.retryStrategy().get().maxAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("Abort attempt on HTTP 503 SlowDown executes exactly 1 HTTP attempt with no SDK retry")
    void shouldAttemptExactlyOnceOnHttp503AndNotRetry() {
        String errorXml = "<Error><Code>SlowDown</Code><Message>Please reduce your request rate.</Message></Error>";
        CountingHttpClient countingHttpClient = new CountingHttpClient(request ->
                HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(503).statusText("Slow Down").build())
                        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(errorXml.getBytes(StandardCharsets.UTF_8))))
                        .build()
        );

        S3Client abortClient = B2StorageAdapter.createAbortS3Client(properties, countingHttpClient);
        S3Client generalClient = B2StorageAdapter.createS3Client(properties, countingHttpClient);
        B2StorageAdapter adapter = new B2StorageAdapter(BUCKET, generalClient, abortClient, null, null);

        StorageKey key = StorageKey.of("videos/test-video.mp4");

        assertThatThrownBy(() -> adapter.abortMultipartUpload(key, "upload-503"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Failed to abort multipart upload");

        assertThat(countingHttpClient.getRequestCount())
                .withFailMessage("Expected exactly 1 HTTP attempt for abort client on 503, but saw %d", countingHttpClient.getRequestCount())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Abort attempt on SocketTimeoutException executes exactly 1 HTTP attempt with no SDK retry")
    void shouldAttemptExactlyOnceOnSocketTimeoutAndNotRetry() {
        CountingHttpClient countingHttpClient = new CountingHttpClient(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        S3Client abortClient = B2StorageAdapter.createAbortS3Client(properties, countingHttpClient);
        S3Client generalClient = B2StorageAdapter.createS3Client(properties, countingHttpClient);
        B2StorageAdapter adapter = new B2StorageAdapter(BUCKET, generalClient, abortClient, null, null);

        StorageKey key = StorageKey.of("videos/test-video.mp4");

        assertThatThrownBy(() -> adapter.abortMultipartUpload(key, "upload-timeout"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Failed to abort multipart upload");

        assertThat(countingHttpClient.getRequestCount())
                .withFailMessage("Expected exactly 1 HTTP attempt for abort client on timeout, but saw %d", countingHttpClient.getRequestCount())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Standard general S3 client retries on HTTP 503, confirming abort client single-attempt contrast")
    void shouldVerifyStandardGeneralClientRetriesOnHttp503() {
        String errorXml = "<Error><Code>SlowDown</Code><Message>Please reduce your request rate.</Message></Error>";
        CountingHttpClient countingHttpClient = new CountingHttpClient(request ->
                HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(503).statusText("Slow Down").build())
                        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(errorXml.getBytes(StandardCharsets.UTF_8))))
                        .build()
        );

        S3Client generalClient = B2StorageAdapter.createS3Client(properties, countingHttpClient);

        ListPartsRequest listPartsRequest = ListPartsRequest.builder()
                .bucket(BUCKET)
                .key("videos/test-video.mp4")
                .uploadId("upload-general-503")
                .build();

        assertThatThrownBy(() -> generalClient.listParts(listPartsRequest))
                .isInstanceOf(S3Exception.class);

        assertThat(countingHttpClient.getRequestCount())
                .withFailMessage("Expected general S3 client to perform multiple retry attempts on 503, but saw %d", countingHttpClient.getRequestCount())
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("Abort on HTTP 204 succeeds as ABORT_ACCEPTED with exactly 1 HTTP request and no destructive operations")
    void shouldSucceedWhenAbortReturnsHttp204NoContent() {
        CountingHttpClient countingHttpClient = new CountingHttpClient(request ->
                HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(204).statusText("No Content").build())
                        .build()
        );

        S3Client abortClient = B2StorageAdapter.createAbortS3Client(properties, countingHttpClient);
        S3Client generalClient = B2StorageAdapter.createS3Client(properties, countingHttpClient);
        B2StorageAdapter adapter = new B2StorageAdapter(BUCKET, generalClient, abortClient, null, null);

        StorageKey key = StorageKey.of("videos/test-video.mp4");
        MultipartAbortResult result = adapter.abortMultipartUpload(key, "upload-success-204");

        assertThat(result).isEqualTo(MultipartAbortResult.ABORT_ACCEPTED);
        assertThat(result.isAbortAccepted()).isTrue();
        assertThat(result.isUploadAlreadyAbsent()).isFalse();

        assertThat(countingHttpClient.getRequestCount()).isEqualTo(1);

        HttpExecuteRequest recordedRequest = countingHttpClient.getRecordedRequests().get(0);
        assertThat(recordedRequest.httpRequest().method().name()).isEqualTo("DELETE");
        assertThat(recordedRequest.httpRequest().rawQueryParameters()).containsKey("uploadId");
    }

    @Test
    @DisplayName("Abort on HTTP 404 NoSuchUpload returns UPLOAD_ALREADY_ABSENT with exactly 1 HTTP attempt")
    void shouldReturnUploadAlreadyAbsentWhenAbortReturnsHttp404NoSuchUpload() {
        String errorXml = "<Error><Code>NoSuchUpload</Code><Message>The specified upload does not exist.</Message></Error>";
        CountingHttpClient countingHttpClient = new CountingHttpClient(request ->
                HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(404).statusText("Not Found").build())
                        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(errorXml.getBytes(StandardCharsets.UTF_8))))
                        .build()
        );

        S3Client abortClient = B2StorageAdapter.createAbortS3Client(properties, countingHttpClient);
        S3Client generalClient = B2StorageAdapter.createS3Client(properties, countingHttpClient);
        B2StorageAdapter adapter = new B2StorageAdapter(BUCKET, generalClient, abortClient, null, null);

        StorageKey key = StorageKey.of("videos/test-video.mp4");
        MultipartAbortResult result = adapter.abortMultipartUpload(key, "upload-absent-404");

        assertThat(result).isEqualTo(MultipartAbortResult.UPLOAD_ALREADY_ABSENT);
        assertThat(result.isUploadAlreadyAbsent()).isTrue();
        assertThat(result.isAbortAccepted()).isFalse();

        assertThat(countingHttpClient.getRequestCount()).isEqualTo(1);
    }

    /**
     * Test SdkHttpClient implementation that counts HTTP attempts and records executed requests.
     */
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
