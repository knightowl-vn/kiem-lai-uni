package com.universe.media.infrastructure.storage.r2hls;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.ports.storage.HlsArtifactDescriptor;
import com.universe.media.application.ports.storage.HlsArtifactStreamSource;
import com.universe.media.application.ports.storage.HlsArtifactType;
import com.universe.media.application.ports.storage.HlsExpectedArtifactManifest;
import com.universe.media.application.ports.storage.HlsPackageIdentity;
import com.universe.media.application.ports.storage.HlsPackageKeyValidator;
import com.universe.media.application.ports.storage.HlsPackageUploadCommand;
import com.universe.media.application.ports.storage.HlsRemoteIntegrityProof;
import com.universe.media.application.ports.storage.HlsRemotePackageVerificationCommand;
import com.universe.media.application.ports.storage.HlsUploadStage;
import com.universe.media.application.ports.storage.HlsUploadedPackageVerification;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MS-08 HLS-2B2B: R2 HLS Package Storage Adapter Tests")
class R2HlsPackageStorageAdapterTest {

    private static final String BUCKET = "kiemlai-r2-hls-bucket";
    private static final StorageProviderId PROVIDER_ID = StorageProviderId.of("r2-hls");

    private S3Client mockS3Client;
    private R2HlsStorageProperties properties;
    private R2HlsPackageStorageAdapter adapter;

    private UUID assetId;
    private UUID packageUuid;
    private int sourceVersion;
    private StorageKey basePrefix;
    private StorageKey masterKey;
    private HlsPackageIdentity identity;

    private Map<StorageKey, byte[]> mockArtifactBytes;
    private HlsExpectedArtifactManifest manifest;

    @BeforeEach
    void setUp() throws Exception {
        mockS3Client = mock(S3Client.class);
        properties = new R2HlsStorageProperties(
                BUCKET,
                "https://account.r2.cloudflarestorage.com",
                "test-access-key",
                "test-secret-key",
                "auto",
                4,
                30,
                120,
                3,
                64L * 1024L * 1024L,
                5000,
                60,
                1000,
                5000,
                false
        );
        adapter = new R2HlsPackageStorageAdapter(properties, mockS3Client);

        assetId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        packageUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        sourceVersion = 1;
        basePrefix = HlsPackageKeyValidator.buildCanonicalPrefix(assetId, sourceVersion, packageUuid);
        masterKey = HlsPackageKeyValidator.buildAndValidateObjectKey(basePrefix, "master.m3u8");
        identity = new HlsPackageIdentity(assetId, sourceVersion, packageUuid, PROVIDER_ID, basePrefix, masterKey);

        mockArtifactBytes = new LinkedHashMap<>();
        List<HlsArtifactDescriptor> descriptors = new ArrayList<>();

        // Stage 1: Inits and segments
        addArtifact(descriptors, "video/720p/init.mp4", HlsArtifactType.INIT_SEGMENT,
                HlsPackageKeyValidator.MIME_FMP4_INIT, HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS, "init-video-bytes".getBytes(StandardCharsets.UTF_8));
        addArtifact(descriptors, "video/720p/seg_00000.m4s", HlsArtifactType.MEDIA_SEGMENT,
                HlsPackageKeyValidator.MIME_FMP4_SEGMENT, HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS, "segment-0-video-bytes".getBytes(StandardCharsets.UTF_8));
        addArtifact(descriptors, "audio/main/init.mp4", HlsArtifactType.INIT_SEGMENT,
                HlsPackageKeyValidator.MIME_FMP4_INIT, HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS, "init-audio-bytes".getBytes(StandardCharsets.UTF_8));
        addArtifact(descriptors, "audio/main/seg_00000.m4s", HlsArtifactType.MEDIA_SEGMENT,
                HlsPackageKeyValidator.MIME_FMP4_SEGMENT, HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS, "segment-0-audio-bytes".getBytes(StandardCharsets.UTF_8));

        // Stage 2: Media playlists
        addArtifact(descriptors, "video/720p/playlist.m3u8", HlsArtifactType.MEDIA_PLAYLIST,
                HlsPackageKeyValidator.MIME_HLS_PLAYLIST, HlsUploadStage.STAGE_2_MEDIA_PLAYLISTS, "#EXTM3U\n#EXT-X-TARGETDURATION:6\n".getBytes(StandardCharsets.UTF_8));
        addArtifact(descriptors, "audio/main/playlist.m3u8", HlsArtifactType.MEDIA_PLAYLIST,
                HlsPackageKeyValidator.MIME_HLS_PLAYLIST, HlsUploadStage.STAGE_2_MEDIA_PLAYLISTS, "#EXTM3U\n#EXT-X-TARGETDURATION:6\n".getBytes(StandardCharsets.UTF_8));

        // Stage 3: Master manifest
        addArtifact(descriptors, "master.m3u8", HlsArtifactType.MASTER_MANIFEST,
                HlsPackageKeyValidator.MIME_HLS_PLAYLIST, HlsUploadStage.STAGE_3_MASTER_MANIFEST, "#EXTM3U\n#EXT-X-STREAM-INF\n".getBytes(StandardCharsets.UTF_8));

        manifest = new HlsExpectedArtifactManifest(identity, descriptors, "{\"renditions\":[\"720p\"]}");

        // Setup default mock responses for PUT and GET
        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    RequestBody body = inv.getArgument(1);
                    if (body != null && body.contentStreamProvider() != null) {
                        try (InputStream in = body.contentStreamProvider().newStream()) {
                            in.readAllBytes();
                        }
                    }
                    return PutObjectResponse.builder().build();
                });

        when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenAnswer(inv -> {
                    GetObjectRequest req = inv.getArgument(0);
                    StorageKey key = StorageKey.of(req.key());
                    byte[] bytes = mockArtifactBytes.get(key);
                    if (bytes == null) {
                        throw S3Exception.builder().statusCode(404).message("NoSuchKey").build();
                    }
                    GetObjectResponse resp = GetObjectResponse.builder().contentLength((long) bytes.length).build();
                    return new ResponseInputStream<>(resp, AbortableInputStream.create(new ByteArrayInputStream(bytes)));
                });
    }

    private void addArtifact(
            List<HlsArtifactDescriptor> descriptors,
            String relativePath,
            HlsArtifactType type,
            MimeType mime,
            HlsUploadStage stage,
            byte[] bytes
    ) throws NoSuchAlgorithmException {
        addArtifactForPrefix(descriptors, basePrefix, relativePath, type, mime, stage, bytes);
    }

    private void addArtifactForPrefix(
            List<HlsArtifactDescriptor> descriptors,
            StorageKey prefix,
            String relativePath,
            HlsArtifactType type,
            MimeType mime,
            HlsUploadStage stage,
            byte[] bytes
    ) throws NoSuchAlgorithmException {
        StorageKey key = HlsPackageKeyValidator.buildAndValidateObjectKey(prefix, relativePath);
        ContentHash hash = computeSha256(bytes);
        descriptors.add(new HlsArtifactDescriptor(relativePath, key, bytes.length, hash, type, mime, stage));
        mockArtifactBytes.put(key, bytes);
    }

    private static ContentHash computeSha256(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return ContentHash.of(sb.toString());
    }

    private HlsArtifactStreamSource createMockStreamSource(AtomicBoolean closeRequested, AtomicBoolean scopeClosedTracker) {
        return new HlsArtifactStreamSource() {
            @Override
            public ActiveUploadScope openUploadScope() {
                return new ActiveUploadScope() {
                    @Override
                    public boolean isCloseRequested() {
                        return closeRequested != null && closeRequested.get();
                    }

                    @Override
                    public void close() {
                        if (scopeClosedTracker != null) {
                            scopeClosedTracker.set(true);
                        }
                    }
                };
            }

            @Override
            public InputStream openArtifactStream(StorageKey objectKey) throws IOException {
                if (closeRequested != null && closeRequested.get()) {
                    throw new IllegalStateException("Close requested on source");
                }
                byte[] bytes = mockArtifactBytes.get(objectKey);
                if (bytes == null) {
                    throw new IOException("Missing artifact: " + objectKey.value());
                }
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public <T> T withArtifactStream(StorageKey objectKey, ArtifactStreamReader<T> reader) throws IOException {
                try (InputStream in = openArtifactStream(objectKey)) {
                    byte[] bytes = mockArtifactBytes.get(objectKey);
                    return reader.read(in, bytes.length);
                }
            }

            @Override
            public void close() {
            }
        };
    }

    private void mockListingAllManifestArtifacts() {
        List<S3Object> contents = new ArrayList<>();
        for (HlsArtifactDescriptor d : manifest.orderedArtifacts()) {
            contents.add(S3Object.builder().key(d.objectKey().value()).size(d.sizeBytes()).build());
        }
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(contents).isTruncated(false).build());
    }

    // =========================================================================
    // 1. Upload & Stage Ordering Tests
    // =========================================================================

    @Test
    @DisplayName("uploadAndVerifyPackage uploads in strict stage order with master last and conditional create-only")
    void uploadAndVerifyPackage_strictOrderingAndConditionalPut() {
        mockListingAllManifestArtifacts();
        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), null);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        HlsUploadedPackageVerification result = adapter.uploadAndVerifyPackage(command);

        assertThat(result).isNotNull();
        assertThat(result.verifiedObjectCount()).isEqualTo(manifest.objectCount());
        assertThat(result.verifiedTotalBytes()).isEqualTo(manifest.totalSizeBytes());
        assertThat(result.remoteReceipt()).isNotNull();

        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(mockS3Client, times(manifest.objectCount())).putObject(putCaptor.capture(), any(RequestBody.class));

        List<PutObjectRequest> puts = putCaptor.getAllValues();
        for (PutObjectRequest put : puts) {
            assertThat(put.ifNoneMatch()).isEqualTo("*");
            assertThat(put.bucket()).isEqualTo(BUCKET);
        }

        // Master manifest must be the very last PUT request (Stage 3)
        PutObjectRequest lastPut = puts.get(puts.size() - 1);
        assertThat(lastPut.key()).isEqualTo(masterKey.value());
        assertThat(lastPut.contentType()).isEqualTo(HlsPackageKeyValidator.MIME_HLS_PLAYLIST.value());
    }

    @Test
    @DisplayName("Stage failure in Stage 1 immediately halts execution; Stage 2 and master.m3u8 are NEVER started")
    void stageFailureCannotBeLost_neverStartsLaterStages() {
        S3Exception simulatedFailure = (S3Exception) S3Exception.builder()
                .statusCode(500)
                .message("Simulated storage error")
                .build();

        // Fail any PUT for segment and ensure ambiguous completion probe sees object is absent
        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(simulatedFailure);
        when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("NoSuchKey").build());

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), null);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        assertThatThrownBy(() -> adapter.uploadAndVerifyPackage(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Simulated storage error");

        // Verify master.m3u8 was NEVER uploaded
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(mockS3Client, atLeastOnce()).putObject(captor.capture(), any(RequestBody.class));
        List<String> keysUploaded = captor.getAllValues().stream().map(PutObjectRequest::key).toList();
        assertThat(keysUploaded).doesNotContain(masterKey.value());
    }

    @Test
    @DisplayName("Worker cancellation awaits physical worker thread exit before ActiveUploadScope closes")
    void workerCancellation_scopeDoesNotCloseUntilWorkersPhysicallyTerminate() throws InterruptedException {
        AtomicBoolean scopeClosed = new AtomicBoolean(false);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean workerObservedScopeOpenDuringExecution = new AtomicBoolean(false);

        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    workerStarted.countDown();
                    releaseWorker.await(2, TimeUnit.SECONDS);
                    workerObservedScopeOpenDuringExecution.set(!scopeClosed.get());
                    throw S3Exception.builder().statusCode(500).message("Simulated boom").build();
                });

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), scopeClosed);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        // Run upload in background thread
        Thread runner = new Thread(() -> {
            try {
                adapter.uploadAndVerifyPackage(command);
            } catch (Exception ignored) {
            }
        });
        runner.start();

        assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        // Scope must still be open while worker is inside putObject
        assertThat(scopeClosed.get()).isFalse();

        // Release worker
        releaseWorker.countDown();
        runner.join(3000);

        assertThat(workerObservedScopeOpenDuringExecution.get()).isTrue();
        assertThat(scopeClosed.get()).isTrue();
    }

    // =========================================================================
    // MS08-HLS2B2B-CORRECTIVE02: Dedicated Lifecycle & Concurrency Regression Tests
    // =========================================================================

    @Test
    @DisplayName("Test A: Interruption-resistant PUT beyond timeout retains lifecycle ownership until worker terminates")
    void testA_interruptionResistantPut_retainsLifecycleOwnershipUntilWorkerTerminates() throws Exception {
        R2HlsStorageProperties shortTimeoutProps = new R2HlsStorageProperties(
                BUCKET,
                "https://account.r2.cloudflarestorage.com",
                "test-access-key-id",
                "test-secret-access-key-secret",
                "auto",
                2,
                1,                       // requestTimeoutSeconds
                1,                       // totalAttemptTimeoutSeconds
                0,                       // retries
                64L * 1024L * 1024L,
                1000,
                5,
                100,
                5000,
                false
        );
        R2HlsPackageStorageAdapter shortTimeoutAdapter = new R2HlsPackageStorageAdapter(shortTimeoutProps, mockS3Client);

        CountDownLatch putEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerExited = new CountDownLatch(1);
        AtomicBoolean scopeClosed = new AtomicBoolean(false);

        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    putEntered.countDown();
                    while (releaseWorker.getCount() > 0) {
                        try {
                            releaseWorker.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // Deliberately ignore interrupt to simulate uncooperative network thread
                        }
                    }
                    workerExited.countDown();
                    throw S3Exception.builder().statusCode(500).message("Simulated socket failure").build();
                });

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), scopeClosed);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        AtomicReference<Throwable> uploadException = new AtomicReference<>();
        long startTime = System.currentTimeMillis();
        Thread uploadThread = new Thread(() -> {
            try {
                shortTimeoutAdapter.uploadAndVerifyPackage(command);
            } catch (Throwable t) {
                uploadException.set(t);
            }
        });
        uploadThread.start();

        assertThat(putEntered.await(2, TimeUnit.SECONDS)).isTrue();
        uploadThread.join(3000);

        // 1. Caller reports failure within intended budget
        long elapsed = System.currentTimeMillis() - startTime;
        assertThat(uploadException.get())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("timeout bounds");
        assertThat(elapsed).isLessThan(2500L);

        // 2. Upload scope remains pinned while PUT is physically active
        assertThat(scopeClosed.get()).isFalse();

        // 3. Package-operation ownership remains reserved
        assertThat(shortTimeoutAdapter.isPackageOperationActive(basePrefix)).isTrue();

        // 4. Purge and overlapping upload are rejected
        assertThatThrownBy(() -> shortTimeoutAdapter.purgePackagePrefix(PROVIDER_ID, BUCKET, basePrefix))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("conflicting operation in progress");

        assertThatThrownBy(() -> shortTimeoutAdapter.uploadAndVerifyPackage(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("conflicting operation in progress");

        // 5. No successful verification receipt was produced (caller received StorageException)

        // 6. Releasing the worker permits eventual safe lifecycle cleanup
        releaseWorker.countDown();
        assertThat(workerExited.await(2, TimeUnit.SECONDS)).isTrue();

        long cleanupWaitDeadline = System.currentTimeMillis() + 1000L;
        while (!scopeClosed.get() && System.currentTimeMillis() < cleanupWaitDeadline) {
            Thread.sleep(10);
        }

        assertThat(scopeClosed.get()).isTrue();
        assertThat(shortTimeoutAdapter.isPackageOperationActive(basePrefix)).isFalse();

        // 7. Resources released exactly once: now purge on basePrefix succeeds cleanly
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of()).isTruncated(false).build());
        shortTimeoutAdapter.purgePackagePrefix(PROVIDER_ID, BUCKET, basePrefix);
        shortTimeoutAdapter.close();
    }

    @Test
    @DisplayName("Test B: Partial executor submission failure preserves lifecycle ownership until running worker terminates")
    void testB_partialExecutorSubmissionFailure_preservesLifecycleOwnershipUntilWorkerTerminates() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerExited = new CountDownLatch(1);
        AtomicBoolean scopeClosed = new AtomicBoolean(false);

        // Controlled executor: accepts task 0, rejects task 1
        ExecutorService realExecutor = Executors.newFixedThreadPool(2);
        AtomicInteger submittedCount = new AtomicInteger();
        ExecutorService controlledExecutor = new AbstractExecutorService() {
            @Override
            public void shutdown() {
                realExecutor.shutdown();
            }

            @Override
            public List<Runnable> shutdownNow() {
                return realExecutor.shutdownNow();
            }

            @Override
            public boolean isShutdown() {
                return realExecutor.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return realExecutor.isTerminated();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return realExecutor.awaitTermination(timeout, unit);
            }

            @Override
            public void execute(Runnable command) {
                if (submittedCount.incrementAndGet() > 1) {
                    try {
                        workerStarted.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    throw new RejectedExecutionException("Executor submission rejected for secondary worker");
                }
                realExecutor.execute(command);
            }
        };

        R2HlsStorageProperties shortTimeoutProps = new R2HlsStorageProperties(
                BUCKET,
                "https://account.r2.cloudflarestorage.com",
                "test-access-key-id",
                "test-secret-access-key-secret",
                "auto",
                2,
                1,
                1,
                0,
                64L * 1024L * 1024L,
                1000,
                5,
                100,
                5000,
                false
        );
        R2HlsPackageStorageAdapter controlledAdapter = new R2HlsPackageStorageAdapter(shortTimeoutProps, mockS3Client, controlledExecutor);

        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    workerStarted.countDown();
                    while (releaseWorker.getCount() > 0) {
                        try {
                            releaseWorker.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    workerExited.countDown();
                    return PutObjectResponse.builder().build();
                });

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), scopeClosed);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        AtomicReference<Throwable> uploadException = new AtomicReference<>();
        Thread uploadThread = new Thread(() -> {
            try {
                controlledAdapter.uploadAndVerifyPackage(command);
            } catch (Throwable t) {
                uploadException.set(t);
            }
        });
        uploadThread.start();

        assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        uploadThread.join(3000);

        // Upload failed due to rejected execution / timeout
        assertThat(uploadException.get()).isNotNull();

        // Scope must remain open and package operation must remain reserved while Worker 0 is active
        assertThat(scopeClosed.get()).isFalse();
        assertThat(controlledAdapter.isPackageOperationActive(basePrefix)).isTrue();
        assertThatThrownBy(() -> controlledAdapter.purgePackagePrefix(PROVIDER_ID, BUCKET, basePrefix))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("conflicting operation in progress");

        // Release accepted worker
        releaseWorker.countDown();
        assertThat(workerExited.await(2, TimeUnit.SECONDS)).isTrue();

        long deadline = System.currentTimeMillis() + 1000L;
        while (!scopeClosed.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(scopeClosed.get()).isTrue();
        assertThat(controlledAdapter.isPackageOperationActive(basePrefix)).isFalse();
        realExecutor.shutdownNow();
    }

    @Test
    @DisplayName("Test C: Shared executor saturation accounts for queued tasks without getting stuck or premature release")
    void testC_sharedExecutorSaturation_queuedTaskAccountingAndIndependentOwnership() throws Exception {
        // Single thread pool shared across both adapters
        ExecutorService singleThreadExecutor = Executors.newFixedThreadPool(1);
        CountDownLatch pkg1WorkerStarted = new CountDownLatch(1);
        CountDownLatch pkg2TaskSubmitted = new CountDownLatch(1);

        ExecutorService instrumentedExecutor = new AbstractExecutorService() {
            @Override
            public void shutdown() {
                singleThreadExecutor.shutdown();
            }

            @Override
            public List<Runnable> shutdownNow() {
                return singleThreadExecutor.shutdownNow();
            }

            @Override
            public boolean isShutdown() {
                return singleThreadExecutor.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return singleThreadExecutor.isTerminated();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return singleThreadExecutor.awaitTermination(timeout, unit);
            }

            @Override
            public void execute(Runnable command) {
                singleThreadExecutor.execute(command);
                if (pkg1WorkerStarted.getCount() == 0) {
                    pkg2TaskSubmitted.countDown();
                }
            }
        };

        R2HlsPackageStorageAdapter adapter1 = new R2HlsPackageStorageAdapter(properties, mockS3Client, instrumentedExecutor);
        R2HlsPackageStorageAdapter adapter2 = new R2HlsPackageStorageAdapter(properties, mockS3Client, instrumentedExecutor);

        UUID otherPackageUuid = UUID.fromString("88888888-8888-8888-8888-888888888888");
        StorageKey otherPrefix = HlsPackageKeyValidator.buildCanonicalPrefix(assetId, sourceVersion, otherPackageUuid);
        StorageKey otherMasterKey = HlsPackageKeyValidator.buildAndValidateObjectKey(otherPrefix, "master.m3u8");
        HlsPackageIdentity otherIdentity = new HlsPackageIdentity(assetId, sourceVersion, otherPackageUuid, PROVIDER_ID, otherPrefix, otherMasterKey);

        List<HlsArtifactDescriptor> otherDescriptors = new ArrayList<>();
        addArtifactForPrefix(otherDescriptors, otherPrefix, "video/720p/init.mp4", HlsArtifactType.INIT_SEGMENT,
                HlsPackageKeyValidator.MIME_FMP4_INIT, HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS, "other-init".getBytes(StandardCharsets.UTF_8));
        addArtifactForPrefix(otherDescriptors, otherPrefix, "video/720p/seg_00000.m4s", HlsArtifactType.MEDIA_SEGMENT,
                HlsPackageKeyValidator.MIME_FMP4_SEGMENT, HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS, "other-seg0".getBytes(StandardCharsets.UTF_8));
        addArtifactForPrefix(otherDescriptors, otherPrefix, "video/720p/playlist.m3u8", HlsArtifactType.MEDIA_PLAYLIST,
                HlsPackageKeyValidator.MIME_HLS_PLAYLIST, HlsUploadStage.STAGE_2_MEDIA_PLAYLISTS, "other-pl".getBytes(StandardCharsets.UTF_8));
        addArtifactForPrefix(otherDescriptors, otherPrefix, "master.m3u8", HlsArtifactType.MASTER_MANIFEST,
                HlsPackageKeyValidator.MIME_HLS_PLAYLIST, HlsUploadStage.STAGE_3_MASTER_MANIFEST, "#EXTM3U\n".getBytes(StandardCharsets.UTF_8));

        HlsExpectedArtifactManifest otherManifest = new HlsExpectedArtifactManifest(
                otherIdentity,
                otherDescriptors,
                "{\"renditions\":[\"720p\"]}"
        );

        CountDownLatch releasePkg1 = new CountDownLatch(1);
        AtomicBoolean pkg1ScopeClosed = new AtomicBoolean(false);
        AtomicBoolean pkg2ScopeClosed = new AtomicBoolean(false);

        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    PutObjectRequest req = inv.getArgument(0);
                    if (req.key().startsWith(basePrefix.value())) {
                        pkg1WorkerStarted.countDown();
                        releasePkg1.await(2, TimeUnit.SECONDS);
                    }
                    return PutObjectResponse.builder().build();
                });

        HlsArtifactStreamSource source1 = createMockStreamSource(new AtomicBoolean(false), pkg1ScopeClosed);
        HlsPackageUploadCommand cmd1 = new HlsPackageUploadCommand(BUCKET, manifest, source1);

        AtomicBoolean pkg2CloseRequested = new AtomicBoolean(false);
        HlsArtifactStreamSource source2 = createMockStreamSource(pkg2CloseRequested, pkg2ScopeClosed);
        HlsPackageUploadCommand cmd2 = new HlsPackageUploadCommand(BUCKET, otherManifest, source2);

        // Start Package 1 upload; its worker acquires the only executor thread
        Thread t1 = new Thread(() -> {
            try {
                adapter1.uploadAndVerifyPackage(cmd1);
            } catch (Exception ignored) {
            }
        });
        t1.start();
        assertThat(pkg1WorkerStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // Start Package 2 upload; its tasks are placed into the executor queue (waiting for thread)
        AtomicReference<Throwable> pkg2Error = new AtomicReference<>();
        Thread t2 = new Thread(() -> {
            try {
                adapter2.uploadAndVerifyPackage(cmd2);
            } catch (Throwable t) {
                pkg2Error.set(t);
            }
        });
        t2.start();

        // Deterministically await Package 2 enqueuing its task in the saturated executor
        assertThat(pkg2TaskSubmitted.await(2, TimeUnit.SECONDS)).isTrue();
        pkg2CloseRequested.set(true);

        // Package 2 should abort quickly without getting stuck on completion tracking for queued tasks
        t2.join(2000);
        assertThat(pkg2Error.get()).isNotNull();
        assertThat(pkg2ScopeClosed.get()).isTrue();
        assertThat(adapter2.isPackageOperationActive(otherPrefix)).isFalse();

        // Package 1 is still running with its scope open and operation active
        assertThat(pkg1ScopeClosed.get()).isFalse();
        assertThat(adapter1.isPackageOperationActive(basePrefix)).isTrue();

        // Release Package 1 worker and complete
        releasePkg1.countDown();
        t1.join(3000);

        singleThreadExecutor.shutdownNow();
    }

    @Test
    @DisplayName("Test D: Shutdown during active upload coordinates gracefully without corrupting operation state")
    void testD_shutdownDuringActiveUpload_coordinatesGracefully() throws Exception {
        CountDownLatch putStarted = new CountDownLatch(1);
        CountDownLatch releasePut = new CountDownLatch(1);
        AtomicBoolean scopeClosed = new AtomicBoolean(false);

        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    putStarted.countDown();
                    while (releasePut.getCount() > 0) {
                        try {
                            releasePut.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    return PutObjectResponse.builder().build();
                });

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), scopeClosed);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        Thread uploadThread = new Thread(() -> {
            try {
                adapter.uploadAndVerifyPackage(command);
            } catch (Exception ignored) {
            }
        });
        uploadThread.start();

        assertThat(putStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // Initiate adapter shutdown in separate thread while worker is inside PUT
        Thread closeThread = new Thread(adapter::close);
        closeThread.start();

        long waitCloseDeadline = System.currentTimeMillis() + 1000L;
        while (!adapter.isClosed() && System.currentTimeMillis() < waitCloseDeadline) {
            Thread.sleep(5);
        }

        // While worker is still inside PUT:
        // 1. Adapter must reject new uploads immediately
        assertThat(adapter.isClosed()).isTrue();
        assertThatThrownBy(() -> adapter.uploadAndVerifyPackage(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("closed or shutting down");

        // 2. Package operation ownership remains held and upload scope remains open
        assertThat(adapter.isPackageOperationActive(basePrefix)).isTrue();
        assertThat(scopeClosed.get()).isFalse();

        // Release the worker
        releasePut.countDown();
        uploadThread.join(3000);
        closeThread.join(3000);

        // Eventual safe cleanup: scope closed and operation released
        long deadline = System.currentTimeMillis() + 1000L;
        while (!scopeClosed.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(scopeClosed.get()).isTrue();
        assertThat(adapter.isPackageOperationActive(basePrefix)).isFalse();
    }

    // =========================================================================
    // MS08-HLS2B2B-CORRECTIVE03: Operation Admission, Scope Acquisition & Deferred S3 Client Shutdown Tests
    // =========================================================================

    @Test
    @DisplayName("Scope acquisition failure releases operation reservation immediately and permits subsequent operations")
    void scopeAcquisitionFailure_releasesOperationReservationImmediately() {
        HlsArtifactStreamSource failingSource = mock(HlsArtifactStreamSource.class);
        when(failingSource.openUploadScope()).thenThrow(new IllegalStateException("Simulated scope acquisition failure"));

        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, failingSource);

        assertThatThrownBy(() -> adapter.uploadAndVerifyPackage(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated scope acquisition failure");

        // Verify zero PUT operations were attempted
        verify(mockS3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        // Verify operation reservation was released immediately
        assertThat(adapter.isPackageOperationActive(basePrefix)).isFalse();

        // Verify subsequent operation on same prefix is admitted cleanly
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of()).isTruncated(false).build());
        adapter.purgePackagePrefix(PROVIDER_ID, BUCKET, basePrefix);
        assertThat(adapter.isPackageOperationActive(basePrefix)).isFalse();
    }

    @Test
    @DisplayName("Operation admission racing with adapter shutdown is rejected fail-closed")
    void operationAdmission_racingWithShutdown_rejectedFailClosed() throws Exception {
        adapter.close();
        assertThat(adapter.isClosed()).isTrue();

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), null);
        HlsPackageUploadCommand uploadCommand = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        assertThatThrownBy(() -> adapter.uploadAndVerifyPackage(uploadCommand))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("closed or shutting down");

        HlsRemotePackageVerificationCommand verifyCommand = new HlsRemotePackageVerificationCommand(BUCKET, manifest, false);
        assertThatThrownBy(() -> adapter.verifyRemotePackage(verifyCommand))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("closed or shutting down");

        assertThatThrownBy(() -> adapter.purgePackagePrefix(PROVIDER_ID, BUCKET, basePrefix))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("closed or shutting down");
    }

    @Test
    @DisplayName("Deferred S3 client disposal preserves client while worker is active and closes it when last operation drains")
    void deferredS3ClientDisposal_preservesClientUntilActiveOperationsDrain() throws Exception {
        S3Client ownedMockClient = mock(S3Client.class);
        ExecutorService testExecutor = Executors.newFixedThreadPool(2);
        R2HlsPackageStorageAdapter ownedAdapter = new R2HlsPackageStorageAdapter(
                properties,
                ownedMockClient,
                true,   // ownsS3Client = true
                testExecutor,
                false
        );

        CountDownLatch putStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerExited = new CountDownLatch(1);
        AtomicBoolean scopeClosed = new AtomicBoolean(false);

        when(ownedMockClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    putStarted.countDown();
                    releaseWorker.await(2, TimeUnit.SECONDS);
                    workerExited.countDown();
                    return PutObjectResponse.builder().build();
                });

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), scopeClosed);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        Thread uploadThread = new Thread(() -> {
            try {
                ownedAdapter.uploadAndVerifyPackage(command);
            } catch (Exception ignored) {
            }
        });
        uploadThread.start();

        assertThat(putStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(ownedAdapter.isPackageOperationActive(basePrefix)).isTrue();

        // Close adapter while upload is active
        ownedAdapter.close();
        assertThat(ownedAdapter.isClosed()).isTrue();

        // S3Client must NOT be closed yet because active operation is still in flight
        assertThat(ownedAdapter.isS3ClientClosed()).isFalse();
        verify(ownedMockClient, never()).close();

        // Release the worker
        releaseWorker.countDown();
        assertThat(workerExited.await(2, TimeUnit.SECONDS)).isTrue();
        uploadThread.join(3000);

        // Active operations drained -> S3 client should now be closed exactly once
        assertThat(ownedAdapter.isS3ClientClosed()).isTrue();
        verify(ownedMockClient, times(1)).close();

        testExecutor.shutdownNow();
    }

    @Test
    @DisplayName("Concurrent repeated close calls close S3 client exactly once")
    void concurrentRepeatedClose_closesClientExactlyOnce() throws Exception {
        S3Client ownedMockClient = mock(S3Client.class);
        ExecutorService testExecutor = Executors.newFixedThreadPool(2);
        R2HlsPackageStorageAdapter ownedAdapter = new R2HlsPackageStorageAdapter(
                properties,
                ownedMockClient,
                true,   // ownsS3Client = true
                testExecutor,
                false
        );

        int threads = 8;
        ExecutorService closerPool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            closerPool.execute(() -> {
                try {
                    startLatch.await();
                    ownedAdapter.close();
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(ownedAdapter.isClosed()).isTrue();
        assertThat(ownedAdapter.isS3ClientClosed()).isTrue();
        verify(ownedMockClient, times(1)).close();

        closerPool.shutdownNow();
        testExecutor.shutdownNow();
    }

    // =========================================================================
    // 2. Per-Package Coordination & Mutual Exclusion Tests
    // =========================================================================

    @Test
    @DisplayName("Purge is rejected while upload owns the package prefix")
    void purgeIsRejectedWhileUploadOwnsSamePackage() throws InterruptedException {
        CountDownLatch uploadActive = new CountDownLatch(1);
        CountDownLatch releaseUpload = new CountDownLatch(1);

        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    uploadActive.countDown();
                    releaseUpload.await(2, TimeUnit.SECONDS);
                    return PutObjectResponse.builder().build();
                });

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), null);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        Thread uploadThread = new Thread(() -> {
            try {
                adapter.uploadAndVerifyPackage(command);
            } catch (Exception ignored) {
            }
        });
        uploadThread.start();

        assertThat(uploadActive.await(2, TimeUnit.SECONDS)).isTrue();

        // While upload is active on basePrefix, purge on basePrefix must be rejected
        assertThatThrownBy(() -> adapter.purgePackagePrefix(PROVIDER_ID, BUCKET, basePrefix))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("conflicting operation in progress");

        releaseUpload.countDown();
        uploadThread.join(3000);
    }

    @Test
    @DisplayName("Upload is rejected while purge owns the package prefix")
    void uploadIsRejectedWhilePurgeOwnsSamePackage() throws InterruptedException {
        CountDownLatch purgeActive = new CountDownLatch(1);
        CountDownLatch releasePurge = new CountDownLatch(1);

        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenAnswer(inv -> {
                    purgeActive.countDown();
                    releasePurge.await(2, TimeUnit.SECONDS);
                    return ListObjectsV2Response.builder().contents(List.of()).isTruncated(false).build();
                });

        Thread purgeThread = new Thread(() -> {
            try {
                adapter.purgePackagePrefix(PROVIDER_ID, BUCKET, basePrefix);
            } catch (Exception ignored) {
            }
        });
        purgeThread.start();

        assertThat(purgeActive.await(2, TimeUnit.SECONDS)).isTrue();

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), null);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        // While purge is active on basePrefix, upload on basePrefix must be rejected
        assertThatThrownBy(() -> adapter.uploadAndVerifyPackage(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("conflicting operation in progress");

        releasePurge.countDown();
        purgeThread.join(3000);
    }

    @Test
    @DisplayName("Independent package UUIDs can operate concurrently without conflict")
    void independentPackageUuidsRemainConcurrent() throws Exception {
        UUID otherPackageUuid = UUID.fromString("99999999-9999-9999-9999-999999999999");
        StorageKey otherPrefix = HlsPackageKeyValidator.buildCanonicalPrefix(assetId, sourceVersion, otherPackageUuid);

        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of()).isTruncated(false).build());

        // Purge on otherPrefix succeeds and does not block operation on basePrefix
        adapter.purgePackagePrefix(PROVIDER_ID, BUCKET, otherPrefix);
        adapter.purgePackagePrefix(PROVIDER_ID, BUCKET, basePrefix);
    }

    // =========================================================================
    // MS08-HLS2B3B0: Decoupled Upload-Stage Timeout & Monotonic Clock Tests
    // =========================================================================

    @Test
    @DisplayName("Slow but progressing multi-artifact stage exceeding totalAttemptTimeout completes cleanly within uploadStageTimeout")
    void slowProgressingStage_exceedingTotalAttemptTimeoutSucceedsWithinUploadStageTimeout() {
        mockListingAllManifestArtifacts();

        java.util.concurrent.atomic.AtomicLong fakeNanos = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        R2HlsStorageProperties decoupledProps = new R2HlsStorageProperties(
                BUCKET,
                "https://account.r2.cloudflarestorage.com",
                "test-access-key-id",
                "test-secret-access-key-secret",
                "auto",
                1,          // maxConcurrentUploads=1 (sequential Stage 1 PUTs)
                1,          // requestTimeoutSeconds=1s
                2,          // totalAttemptTimeoutSeconds=2s (single artifact retry/call budget)
                10,         // uploadStageTimeoutSeconds=10s (multi-artifact stage budget)
                1,
                64L * 1024L * 1024L,
                5000,
                60,
                1000,
                5000,
                false
        );

        R2HlsPackageStorageAdapter decoupledAdapter = new R2HlsPackageStorageAdapter(
                decoupledProps,
                mockS3Client,
                false,
                Executors.newFixedThreadPool(1),
                true,
                fakeNanos::get
        );

        // Each of the 4 Stage-1 PUTs advances fake clock by 1.2s (< 2s per-call totalAttemptTimeout, 4.8s total > 2s!)
        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    fakeNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1200));
                    return PutObjectResponse.builder().build();
                });

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), null);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        HlsUploadedPackageVerification result = decoupledAdapter.uploadAndVerifyPackage(command);
        assertThat(result).isNotNull();
        assertThat(result.verifiedObjectCount()).isEqualTo(manifest.objectCount());
        decoupledAdapter.close();
    }

    @Test
    @DisplayName("Single stalled artifact still enforces totalAttemptTimeout even when uploadStageTimeout is much larger")
    void singleArtifactRetry_stillRespectsTotalAttemptTimeout() {
        java.util.concurrent.atomic.AtomicLong fakeNanos = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        R2HlsStorageProperties decoupledProps = new R2HlsStorageProperties(
                BUCKET,
                "https://account.r2.cloudflarestorage.com",
                "test-access-key-id",
                "test-secret-access-key-secret",
                "auto",
                1,
                1,          // requestTimeout=1s
                2,          // totalAttemptTimeout=2s
                60,         // uploadStageTimeout=60s
                3,          // retries
                64L * 1024L * 1024L,
                5000,
                60,
                1000,
                5000,
                false
        );

        R2HlsPackageStorageAdapter decoupledAdapter = new R2HlsPackageStorageAdapter(
                decoupledProps,
                mockS3Client,
                false,
                Executors.newFixedThreadPool(1),
                true,
                fakeNanos::get
        );

        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    fakeNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(2500));
                    throw S3Exception.builder().statusCode(503).message("Slow retryable failure").build();
                });
        when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("NoSuchKey").build());

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), null);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        assertThatThrownBy(() -> decoupledAdapter.uploadAndVerifyPackage(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Upload attempt timed out after 2 seconds");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(mockS3Client, atLeastOnce()).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getAllValues().stream().map(PutObjectRequest::key).toList())
                .doesNotContain(masterKey.value());
        decoupledAdapter.close();
    }

    @Test
    @DisplayName("Stage timeout cancels outstanding tasks, preserves detached ownership if unresponsive, and blocks master.m3u8")
    void stageTimeout_cancelsTasksDetachesUnresponsiveWorkerAndBlocksMaster() throws Exception {
        java.util.concurrent.atomic.AtomicLong fakeNanos = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        R2HlsStorageProperties shortStageProps = new R2HlsStorageProperties(
                BUCKET,
                "https://account.r2.cloudflarestorage.com",
                "test-access-key-id",
                "test-secret-access-key-secret",
                "auto",
                2,
                1,          // requestTimeout=1s
                1,          // totalAttemptTimeout=1s
                2,          // uploadStageTimeout=2s
                0,
                64L * 1024L * 1024L,
                5000,
                60,
                1000,
                5000,
                false
        );

        CountDownLatch workerInPut = new CountDownLatch(1);
        CountDownLatch releaseUnresponsiveWorker = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        AtomicBoolean scopeClosed = new AtomicBoolean(false);

        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    workerInPut.countDown();
                    while (releaseUnresponsiveWorker.getCount() > 0) {
                        try {
                            releaseUnresponsiveWorker.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    workerFinished.countDown();
                    return PutObjectResponse.builder().build();
                });

        R2HlsPackageStorageAdapter timeoutAdapter = new R2HlsPackageStorageAdapter(
                shortStageProps,
                mockS3Client,
                false,
                Executors.newFixedThreadPool(2),
                true,
                fakeNanos::get
        );

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), scopeClosed);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        AtomicReference<Throwable> failureRef = new AtomicReference<>();
        Thread uploadThread = new Thread(() -> {
            try {
                timeoutAdapter.uploadAndVerifyPackage(command);
            } catch (Throwable t) {
                failureRef.set(t);
            }
        });
        uploadThread.start();

        assertThat(workerInPut.await(5, TimeUnit.SECONDS)).isTrue();
        // Advance monotonic clock past uploadStageTimeout (2s)
        fakeNanos.addAndGet(TimeUnit.SECONDS.toNanos(3));
        uploadThread.join(5000);

        assertThat(failureRef.get())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("timeout bounds");
        assertThat(scopeClosed.get()).isFalse();
        assertThat(timeoutAdapter.isPackageOperationActive(basePrefix)).isTrue();

        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(mockS3Client, atLeastOnce()).putObject(putCaptor.capture(), any(RequestBody.class));
        assertThat(putCaptor.getAllValues().stream().map(PutObjectRequest::key).toList())
                .doesNotContain(masterKey.value());

        releaseUnresponsiveWorker.countDown();
        assertThat(workerFinished.await(5, TimeUnit.SECONDS)).isTrue();
        while (!scopeClosed.get()) {
            Thread.onSpinWait();
        }
        assertThat(timeoutAdapter.isPackageOperationActive(basePrefix)).isFalse();
        timeoutAdapter.close();
    }

    // =========================================================================
    // 3. Ambiguous Completion & HTTP 412 Tests
    // =========================================================================

    @Test
    @DisplayName("Ambiguous PUT timeout followed by remote GET 403 fails closed instead of assuming absent")
    void ambiguousPut_remoteGet403FailsClosed() {
        // PUT throws timeout
        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("Connect timed out"));

        // GET probe returns 403 AccessDenied
        S3Exception accessDenied = (S3Exception) S3Exception.builder()
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                .message("Access Denied")
                .build();
        when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(accessDenied);

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), null);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        assertThatThrownBy(() -> adapter.uploadAndVerifyPackage(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Uncertain remote state");
    }

    @Test
    @DisplayName("Ambiguous PUT timeout followed by remote GET 500 fails closed instead of assuming absent")
    void ambiguousPut_remoteGet500FailsClosed() {
        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("Read timed out"));

        S3Exception internalError = (S3Exception) S3Exception.builder()
                .statusCode(500)
                .message("Internal Error")
                .build();
        when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(internalError);

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), null);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        assertThatThrownBy(() -> adapter.uploadAndVerifyPackage(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Uncertain remote state");
    }

    @Test
    @DisplayName("Ambiguous PUT timeout followed by confirmed 404 permits bounded retry")
    void ambiguousPut_confirmed404PermitsRetry() {
        mockListingAllManifestArtifacts();

        // First attempt throws timeout, GET probe returns 404 (absent), second attempt succeeds
        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("Connection dropped"))
                .thenReturn(PutObjectResponse.builder().build());

        when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("NoSuchKey").build())
                .thenAnswer(inv -> {
                    GetObjectRequest req = inv.getArgument(0);
                    byte[] bytes = mockArtifactBytes.get(StorageKey.of(req.key()));
                    GetObjectResponse resp = GetObjectResponse.builder().contentLength((long) bytes.length).build();
                    return new ResponseInputStream<>(resp, AbortableInputStream.create(new ByteArrayInputStream(bytes)));
                });

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), null);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        HlsUploadedPackageVerification result = adapter.uploadAndVerifyPackage(command);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("412 PreconditionFailed reconciles cleanly if remote object matches size and SHA-256")
    void uploadArtifact_412ReconcilesWhenRemoteMatches() {
        mockListingAllManifestArtifacts();

        S3Exception preconditionFailed = (S3Exception) S3Exception.builder()
                .statusCode(412)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("PreconditionFailed").build())
                .message("Precondition failed")
                .build();

        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(preconditionFailed)
                .thenReturn(PutObjectResponse.builder().build());

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), null);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        HlsUploadedPackageVerification result = adapter.uploadAndVerifyPackage(command);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("412 PreconditionFailed fails closed with StorageObjectAlreadyExistsException if remote content mismatches")
    void uploadArtifact_412FailsClosedWhenRemoteMismatches() {
        mockListingAllManifestArtifacts();

        S3Exception preconditionFailed = (S3Exception) S3Exception.builder()
                .statusCode(412)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("PreconditionFailed").build())
                .message("Precondition failed")
                .build();

        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(preconditionFailed);

        byte[] corrupted = "corrupted-content".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse resp = GetObjectResponse.builder().contentLength((long) corrupted.length).build();
        when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(resp, AbortableInputStream.create(new ByteArrayInputStream(corrupted))));

        HlsArtifactStreamSource streamSource = createMockStreamSource(new AtomicBoolean(false), null);
        HlsPackageUploadCommand command = new HlsPackageUploadCommand(BUCKET, manifest, streamSource);

        assertThatThrownBy(() -> adapter.uploadAndVerifyPackage(command))
                .isInstanceOf(StorageObjectAlreadyExistsException.class);
    }

    // =========================================================================
    // 4. Stream Closure & Resource Management Tests
    // =========================================================================

    @Test
    @DisplayName("Remote ResponseInputStream is closed on early size mismatch in verifyRemotePackage")
    void responseStreamIsClosedOnEarlySizeMismatch() {
        mockListingAllManifestArtifacts();

        AtomicBoolean streamClosed = new AtomicBoolean(false);
        byte[] payload = "test-bytes".getBytes(StandardCharsets.UTF_8);
        InputStream trackingStream = new FilterInputStream(new ByteArrayInputStream(payload)) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };

        // Header declares wrong contentLength
        GetObjectResponse resp = GetObjectResponse.builder().contentLength(999999L).build();
        when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(resp, AbortableInputStream.create(trackingStream)));

        HlsRemotePackageVerificationCommand command = new HlsRemotePackageVerificationCommand(BUCKET, manifest, false);

        assertThatThrownBy(() -> adapter.verifyRemotePackage(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("mismatch");

        assertThat(streamClosed.get()).isTrue();
    }

    // =========================================================================
    // 5. Pagination & Bounds Tests
    // =========================================================================

    @Test
    @DisplayName("verifyRemotePackage fails closed on non-advancing repeated continuation token")
    void paginationFailsClosedOnRepeatedToken() {
        List<S3Object> page1 = List.of(
                S3Object.builder().key(manifest.orderedArtifacts().get(0).objectKey().value()).size(manifest.orderedArtifacts().get(0).sizeBytes()).build()
        );
        List<S3Object> page2 = List.of(
                S3Object.builder().key(manifest.orderedArtifacts().get(1).objectKey().value()).size(manifest.orderedArtifacts().get(1).sizeBytes()).build()
        );
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(page1).isTruncated(true).nextContinuationToken("same-token").build())
                .thenReturn(ListObjectsV2Response.builder().contents(page2).isTruncated(true).nextContinuationToken("same-token").build());

        HlsRemotePackageVerificationCommand command = new HlsRemotePackageVerificationCommand(BUCKET, manifest, false);
        assertThatThrownBy(() -> adapter.verifyRemotePackage(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("repeated continuation token");
    }

    @Test
    @DisplayName("verifyRemotePackage fails closed on truncated empty page")
    void paginationFailsClosedOnTruncatedEmptyPage() {
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of()).isTruncated(true).nextContinuationToken("token-1").build());

        HlsRemotePackageVerificationCommand command = new HlsRemotePackageVerificationCommand(BUCKET, manifest, false);
        assertThatThrownBy(() -> adapter.verifyRemotePackage(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("truncated empty page");
    }

    @Test
    @DisplayName("verifyRemotePackage fails closed when truncated is true but nextContinuationToken is null or blank")
    void paginationFailsClosedOnNullContinuationTokenWhenTruncated() {
        List<S3Object> contents = List.of(
                S3Object.builder().key(manifest.orderedArtifacts().get(0).objectKey().value()).size(10L).build()
        );
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(contents).isTruncated(true).nextContinuationToken(null).build());

        HlsRemotePackageVerificationCommand command = new HlsRemotePackageVerificationCommand(BUCKET, manifest, false);
        assertThatThrownBy(() -> adapter.verifyRemotePackage(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("nextContinuationToken is null or blank");
    }

    // =========================================================================
    // 6. Full-Integrity vs Manifests-Only Policy Tests
    // =========================================================================

    @Test
    @DisplayName("Full-integrity mode digests all media segments and records STREAMED_GET_ALL_ARTIFACTS_WITH_EXACT_LISTING")
    void fullIntegrityMode_digestsAllSegments() {
        mockListingAllManifestArtifacts();
        HlsRemotePackageVerificationCommand command = new HlsRemotePackageVerificationCommand(BUCKET, manifest, true);

        HlsUploadedPackageVerification result = adapter.verifyRemotePackage(command);

        assertThat(result).isNotNull();
        assertThat(result.integrityProof()).isEqualTo(HlsRemoteIntegrityProof.STREAMED_GET_ALL_ARTIFACTS_WITH_EXACT_LISTING);
        verify(mockS3Client, times(manifest.objectCount())).getObject(any(GetObjectRequest.class));
    }

    @Test
    @DisplayName("Full-integrity mode rejects corrupted .m4s segment")
    void fullIntegrityMode_rejectsCorruptedSegment() {
        mockListingAllManifestArtifacts();

        when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenAnswer(inv -> {
                    GetObjectRequest req = inv.getArgument(0);
                    byte[] bytes = mockArtifactBytes.get(StorageKey.of(req.key()));
                    if (req.key().endsWith(".m4s")) {
                        // Return corrupted bytes of same length
                        byte[] corrupted = new byte[bytes.length];
                        corrupted[0] = (byte) ~bytes[0];
                        GetObjectResponse resp = GetObjectResponse.builder().contentLength((long) corrupted.length).build();
                        return new ResponseInputStream<>(resp, AbortableInputStream.create(new ByteArrayInputStream(corrupted)));
                    }
                    GetObjectResponse resp = GetObjectResponse.builder().contentLength((long) bytes.length).build();
                    return new ResponseInputStream<>(resp, AbortableInputStream.create(new ByteArrayInputStream(bytes)));
                });

        HlsRemotePackageVerificationCommand command = new HlsRemotePackageVerificationCommand(BUCKET, manifest, true);
        assertThatThrownBy(() -> adapter.verifyRemotePackage(command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("SHA-256 mismatch");
    }

    // =========================================================================
    // 7. Purge Tests
    // =========================================================================

    @Test
    @DisplayName("purgePackagePrefix successfully batches deletion and confirms empty")
    void purgePackagePrefix_succeedsAndConfirmsEmpty() {
        List<S3Object> contents = new ArrayList<>();
        for (HlsArtifactDescriptor d : manifest.orderedArtifacts()) {
            contents.add(S3Object.builder().key(d.objectKey().value()).build());
        }

        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(contents).isTruncated(false).build())
                .thenReturn(ListObjectsV2Response.builder().contents(List.of()).isTruncated(false).build());

        when(mockS3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().errors(List.of()).build());

        adapter.purgePackagePrefix(PROVIDER_ID, BUCKET, basePrefix);

        verify(mockS3Client, times(1)).deleteObjects(any(DeleteObjectsRequest.class));
        verify(mockS3Client, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    @DisplayName("purgePackagePrefix is idempotent when prefix is already empty")
    void purgePackagePrefix_idempotentOnEmptyPrefix() {
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of()).isTruncated(false).build());

        adapter.purgePackagePrefix(PROVIDER_ID, BUCKET, basePrefix);
        verify(mockS3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    @DisplayName("purgePackagePrefix fails closed on DeleteObjects partial errors")
    void purgePackagePrefix_failsOnDeleteErrors() {
        List<S3Object> contents = List.of(
                S3Object.builder().key(basePrefix.value() + "master.m3u8").build()
        );
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(contents).isTruncated(false).build());

        S3Error error = S3Error.builder().key(basePrefix.value() + "master.m3u8").code("AccessDenied").message("Access denied").build();
        when(mockS3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().errors(List.of(error)).build());

        assertThatThrownBy(() -> adapter.purgePackagePrefix(PROVIDER_ID, BUCKET, basePrefix))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("partial errors");
    }

    @Test
    @DisplayName("purgePackagePrefix fails closed if prefix is not empty after deletion")
    void purgePackagePrefix_failsIfNotEmptyAfterPurge() {
        List<S3Object> contents = List.of(
                S3Object.builder().key(basePrefix.value() + "master.m3u8").build()
        );
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(contents).isTruncated(false).build())
                .thenReturn(ListObjectsV2Response.builder().contents(contents).isTruncated(false).build());

        when(mockS3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().errors(List.of()).build());

        assertThatThrownBy(() -> adapter.purgePackagePrefix(PROVIDER_ID, BUCKET, basePrefix))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("not empty after purge");
    }

    @Test
    @DisplayName("purgePackagePrefix rejects broad or non-canonical prefixes")
    void purgePackagePrefix_rejectsBroadPrefixes() {
        assertThatThrownBy(() -> adapter.purgePackagePrefix(PROVIDER_ID, BUCKET, StorageKey.of("objects/hls/")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> adapter.purgePackagePrefix(PROVIDER_ID, BUCKET, StorageKey.of("objects/hls/" + assetId + "/")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> adapter.purgePackagePrefix(StorageProviderId.of("r2"), BUCKET, basePrefix))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
