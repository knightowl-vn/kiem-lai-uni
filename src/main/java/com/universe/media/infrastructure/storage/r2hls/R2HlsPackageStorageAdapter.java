package com.universe.media.infrastructure.storage.r2hls;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.ports.storage.HlsArtifactDescriptor;
import com.universe.media.application.ports.storage.HlsArtifactStreamSource;
import com.universe.media.application.ports.storage.HlsArtifactType;
import com.universe.media.application.ports.storage.HlsExpectedArtifactManifest;
import com.universe.media.application.ports.storage.HlsPackageArtifactStoragePort;
import com.universe.media.application.ports.storage.HlsPackageIdentity;
import com.universe.media.application.ports.storage.HlsPackageKeyValidator;
import com.universe.media.application.ports.storage.HlsPackageUploadCommand;
import com.universe.media.application.ports.storage.HlsRemoteIntegrityProof;
import com.universe.media.application.ports.storage.HlsRemotePackageVerificationCommand;
import com.universe.media.application.ports.storage.HlsUploadedPackageVerification;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;

/**
 * Cloudflare R2 implementation of {@link HlsPackageArtifactStoragePort} using AWS SDK v2 S3 API (MS-08 HLS-2B2B).
 *
 * <p><strong>Key Architecture & Security Rules:</strong>
 * <ul>
 *   <li>Dormant by default (activated only when {@code media.hls.storage.r2.enabled=true}).</li>
 *   <li>Provider ID strictly {@code r2-hls}.</li>
 *   <li>CREATE-ONLY artifact PUTs enforcing {@code If-None-Match: *}.</li>
 *   <li>Lifecycle-pinned streaming via {@link HlsArtifactStreamSource#openArtifactStream(StorageKey)}.</li>
 *   <li>Zero whole-file byte-array buffering; constant 8KB streaming digest memory with guaranteed stream closure.</li>
 *   <li>Strict three-stage upload order: (1) Inits + media segments, (2) Media playlists, (3) {@code master.m3u8} last.</li>
 *   <li>Worker-queue bounded execution with confirmed physical thread termination before upload scope release.</li>
 *   <li>Mutual exclusion between upload/verification/purge on the same package attempt within the process.</li>
 *   <li>Fail-closed package-scoped prefix purge with batching, partial failure protection, and empty confirmation.</li>
 * </ul>
 *
 * <p><strong>Coordination Boundary Notice:</strong>
 * In-process mutual exclusion guarantees that upload, verification, and purge operations on the same
 * package attempt do not race within this JVM. Cross-process mutual exclusion across multiple instances
 * is enforced by HLS-1 database lease fencing and uniqueness constraints.
 */
@Component
@Conditional(R2HlsStorageCondition.class)
public class R2HlsPackageStorageAdapter implements HlsPackageArtifactStoragePort, AutoCloseable {

    private static final int DIGEST_BUFFER_SIZE = 8192;

    private enum PackageOperationType {
        UPLOAD_AND_VERIFY,
        VERIFY_REMOTE,
        PURGE
    }

    private final R2HlsStorageProperties properties;
    private final S3Client s3Client;
    private final boolean ownsS3Client;
    private final ExecutorService uploadExecutor;
    private final boolean ownsExecutor;
    private final LongSupplier nanoTimeSupplier;
    private final ConcurrentMap<StorageKey, PackageOperationType> activePackageOperations = new ConcurrentHashMap<>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicBoolean s3ClientClosed = new AtomicBoolean(false);
    private final ReentrantReadWriteLock admissionLock = new ReentrantReadWriteLock();

    @Autowired
    public R2HlsPackageStorageAdapter(R2HlsStorageProperties properties) {
        this(
                Objects.requireNonNull(properties, "R2HlsStorageProperties cannot be null."),
                createS3Client(properties),
                true,
                createUploadExecutor(properties),
                true,
                System::nanoTime
        );
    }

    public R2HlsPackageStorageAdapter(R2HlsStorageProperties properties, S3Client s3Client) {
        this(
                Objects.requireNonNull(properties, "R2HlsStorageProperties cannot be null."),
                Objects.requireNonNull(s3Client, "S3Client cannot be null."),
                false,
                createUploadExecutor(properties),
                true,
                System::nanoTime
        );
    }

    public R2HlsPackageStorageAdapter(
            R2HlsStorageProperties properties,
            S3Client s3Client,
            ExecutorService uploadExecutor
    ) {
        this(
                Objects.requireNonNull(properties, "R2HlsStorageProperties cannot be null."),
                Objects.requireNonNull(s3Client, "S3Client cannot be null."),
                false,
                Objects.requireNonNull(uploadExecutor, "uploadExecutor cannot be null."),
                false,
                System::nanoTime
        );
    }

    R2HlsPackageStorageAdapter(
            R2HlsStorageProperties properties,
            S3Client s3Client,
            boolean ownsS3Client,
            ExecutorService uploadExecutor,
            boolean ownsExecutor
    ) {
        this(properties, s3Client, ownsS3Client, uploadExecutor, ownsExecutor, System::nanoTime);
    }

    R2HlsPackageStorageAdapter(
            R2HlsStorageProperties properties,
            S3Client s3Client,
            boolean ownsS3Client,
            ExecutorService uploadExecutor,
            boolean ownsExecutor,
            LongSupplier nanoTimeSupplier
    ) {
        this.properties = properties;
        this.s3Client = s3Client;
        this.ownsS3Client = ownsS3Client;
        this.uploadExecutor = uploadExecutor;
        this.ownsExecutor = ownsExecutor;
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier cannot be null.");
    }

    public StorageProviderId providerId() {
        return HlsPackageKeyValidator.REQUIRED_PROVIDER_ID;
    }

    public String configuredBucket() {
        return properties.bucket();
    }

    @Override
    public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand command) {
        Objects.requireNonNull(command, "command cannot be null.");
        String trustedBucket = HlsPackageKeyValidator.validateTrustedDestination(
                HlsPackageKeyValidator.REQUIRED_PROVIDER_ID,
                command.bucketName(),
                properties.bucket()
        );
        HlsExpectedArtifactManifest manifest = command.expectedManifest();
        HlsPackageIdentity identity = manifest.identity();
        HlsPackageKeyValidator.validateRequiredProvider(identity.providerId());
        HlsPackageKeyValidator.parseAndValidateCanonicalPrefix(identity.basePrefix());

        StorageKey basePrefix = identity.basePrefix();
        admitPackageOperation(basePrefix, PackageOperationType.UPLOAD_AND_VERIFY);

        PackageUploadLifecycleCoordinator coordinator = null;
        try {
            HlsArtifactStreamSource.ActiveUploadScope scope = command.artifactSource().openUploadScope();
            coordinator = new PackageUploadLifecycleCoordinator(
                    basePrefix,
                    scope,
                    activePackageOperations,
                    this::tryCloseS3ClientIfShutdownAndDrained
            );

            if (coordinator.scope().isCloseRequested()) {
                throw new IllegalStateException("Artifact stream source closure requested before upload start.");
            }

            // STAGE 1: All binary init segments and media segments
            uploadStage(trustedBucket, manifest.stage1BinaryArtifacts(), command.artifactSource(), coordinator);

            // STAGE 2: All video and audio media playlists (only reachable if Stage 1 succeeded)
            uploadStage(trustedBucket, manifest.stage2MediaPlaylists(), command.artifactSource(), coordinator);

            // STAGE 3: master.m3u8 last (only reachable if Stage 1 and Stage 2 succeeded)
            uploadArtifact(trustedBucket, manifest.stage3MasterManifest(), command.artifactSource(), coordinator.scope());

            // Safely close upload scope once all uploads are complete before entering remote verification
            coordinator.closeScopeOnly();

            // Post-upload independent remote verification executed while still holding UPLOAD_AND_VERIFY lock
            boolean requireFullIntegrity = properties.requireFullSegmentIntegrityVerification();
            return verifyRemoteInternal(trustedBucket, manifest, requireFullIntegrity);

        } catch (Throwable t) {
            if (coordinator != null && coordinator.activeWorkerCount() > 0) {
                coordinator.detachOwnership();
            }
            throw t;
        } finally {
            if (coordinator == null) {
                releasePackageOperation(basePrefix, PackageOperationType.UPLOAD_AND_VERIFY);
            } else if (!coordinator.isDetached()) {
                coordinator.safeCleanup();
            }
        }
    }

    @Override
    public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand command) {
        Objects.requireNonNull(command, "command cannot be null.");
        String trustedBucket = HlsPackageKeyValidator.validateTrustedDestination(
                HlsPackageKeyValidator.REQUIRED_PROVIDER_ID,
                command.bucketName(),
                properties.bucket()
        );
        HlsExpectedArtifactManifest manifest = command.expectedManifest();
        HlsPackageIdentity identity = manifest.identity();
        HlsPackageKeyValidator.validateRequiredProvider(identity.providerId());
        HlsPackageKeyValidator.parseAndValidateCanonicalPrefix(identity.basePrefix());

        StorageKey basePrefix = identity.basePrefix();
        admitPackageOperation(basePrefix, PackageOperationType.VERIFY_REMOTE);
        try {
            return verifyRemoteInternal(trustedBucket, manifest, command.digestAllSegmentsViaGet());
        } finally {
            releasePackageOperation(basePrefix, PackageOperationType.VERIFY_REMOTE);
        }
    }

    @Override
    public void purgePackagePrefix(StorageProviderId providerId, String bucketName, StorageKey validatedBasePrefix) {
        HlsPackageKeyValidator.validatePurgeScope(
                providerId,
                bucketName,
                properties.bucket(),
                validatedBasePrefix
        );

        admitPackageOperation(validatedBasePrefix, PackageOperationType.PURGE);
        try {
            String prefix = validatedBasePrefix.value();
            List<ObjectIdentifier> keysToDelete = new ArrayList<>();
            String continuationToken = null;
            Set<String> seenTokens = new HashSet<>();
            int totalInspected = 0;
            int pageCount = 0;
            int maxPages = (properties.purgeMaxKeys() / 100) + 20;
            long startNanos = nanoTimeSupplier.getAsLong();
            long maxDurationNanos = properties.purgeMaxDuration().toNanos();

            do {
                pageCount++;
                if (pageCount > maxPages) {
                    throw new StorageException("Purge listing exceeded maximum pagination page limit: " + maxPages);
                }
                if (nanoTimeSupplier.getAsLong() - startNanos > maxDurationNanos) {
                    throw new StorageException(
                            "Purge listing exceeded maximum allowed duration of "
                                    + properties.purgeMaxDuration().toSeconds() + " seconds for prefix " + prefix
                    );
                }

                ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                        .bucket(properties.bucket())
                        .prefix(prefix)
                        .continuationToken(continuationToken)
                        .build();

                ListObjectsV2Response listResp;
                try {
                    listResp = s3Client.listObjectsV2(listReq);
                } catch (Exception e) {
                    throw new StorageException("Failed listing remote HLS objects during purge under " + prefix + ": " + e.getMessage(), e);
                }

                if (listResp.isTruncated() && listResp.contents().isEmpty()) {
                    throw new StorageException("S3 ListObjectsV2 returned truncated empty page during purge under " + prefix);
                }

                for (S3Object obj : listResp.contents()) {
                    String key = obj.key();
                    if (key == null || !key.startsWith(prefix)) {
                        throw new StorageException("Foreign key detected during package purge under " + prefix + ": " + key);
                    }
                    keysToDelete.add(ObjectIdentifier.builder().key(key).build());
                    totalInspected++;
                    if (totalInspected > properties.purgeMaxKeys()) {
                        throw new StorageException(
                                "Purge object count exceeded limit (" + properties.purgeMaxKeys()
                                        + ") under prefix " + prefix
                        );
                    }
                }

                if (listResp.isTruncated()) {
                    continuationToken = listResp.nextContinuationToken();
                    if (continuationToken == null || continuationToken.isBlank()) {
                        throw new StorageException("S3 ListObjectsV2 reported isTruncated=true but nextContinuationToken is null or blank");
                    }
                    if (!seenTokens.add(continuationToken)) {
                        throw new StorageException("S3 ListObjectsV2 non-advancing or repeated continuation token detected: " + continuationToken);
                    }
                } else {
                    continuationToken = null;
                }
            } while (continuationToken != null);

            if (keysToDelete.isEmpty()) {
                return; // Idempotent success on already-empty prefix
            }

            // Batch DeleteObjects
            int batchSize = properties.purgeBatchSize();
            for (int i = 0; i < keysToDelete.size(); i += batchSize) {
                if (nanoTimeSupplier.getAsLong() - startNanos > maxDurationNanos) {
                    throw new StorageException("Purge deletion batch execution exceeded maximum allowed duration");
                }
                int toIndex = Math.min(i + batchSize, keysToDelete.size());
                List<ObjectIdentifier> subList = keysToDelete.subList(i, toIndex);

                DeleteObjectsRequest deleteReq = DeleteObjectsRequest.builder()
                        .bucket(properties.bucket())
                        .delete(Delete.builder().objects(subList).quiet(false).build())
                        .build();

                DeleteObjectsResponse deleteResp;
                try {
                    deleteResp = s3Client.deleteObjects(deleteReq);
                } catch (Exception e) {
                    throw new StorageException("DeleteObjects batch failed during purge under prefix " + prefix + ": " + e.getMessage(), e);
                }

                if (deleteResp.hasErrors() && !deleteResp.errors().isEmpty()) {
                    S3Error firstError = deleteResp.errors().get(0);
                    throw new StorageException(
                            "DeleteObjects returned partial errors during package prefix purge: key="
                                    + firstError.key() + ", code=" + firstError.code() + ", message=" + firstError.message()
                    );
                }
            }

            // Confirm prefix is empty after deletion
            ListObjectsV2Response confirmResp;
            try {
                confirmResp = s3Client.listObjectsV2(
                        ListObjectsV2Request.builder()
                                .bucket(properties.bucket())
                                .prefix(prefix)
                                .maxKeys(1)
                                .build()
                );
            } catch (Exception e) {
                throw new StorageException("Failed confirming empty prefix after purge: " + e.getMessage(), e);
            }

            if (confirmResp.hasContents() && !confirmResp.contents().isEmpty()) {
                throw new StorageException(
                        "Package prefix is not empty after purge; remaining key: " + confirmResp.contents().get(0).key()
                );
            }
        } finally {
            releasePackageOperation(validatedBasePrefix, PackageOperationType.PURGE);
        }
    }

    private void admitPackageOperation(StorageKey basePrefix, PackageOperationType opType) {
        admissionLock.readLock().lock();
        try {
            if (isClosed.get()) {
                throw new StorageException("R2HlsPackageStorageAdapter is closed or shutting down.");
            }
            acquirePackageOperation(basePrefix, opType);
        } finally {
            admissionLock.readLock().unlock();
        }
    }

    private void acquirePackageOperation(StorageKey basePrefix, PackageOperationType opType) {
        PackageOperationType existing = activePackageOperations.putIfAbsent(basePrefix, opType);
        if (existing != null) {
            throw new StorageException(
                    "Cannot start " + opType + " for package prefix " + basePrefix.value()
                            + "; conflicting operation in progress: " + existing
            );
        }
    }

    private void releasePackageOperation(StorageKey basePrefix, PackageOperationType opType) {
        activePackageOperations.remove(basePrefix, opType);
        tryCloseS3ClientIfShutdownAndDrained();
    }

    private void tryCloseS3ClientIfShutdownAndDrained() {
        if (isClosed.get() && ownsS3Client && activePackageOperations.isEmpty()) {
            if (s3ClientClosed.compareAndSet(false, true)) {
                try {
                    s3Client.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    boolean isS3ClientClosed() {
        return s3ClientClosed.get();
    }

    boolean isPackageOperationActive(StorageKey basePrefix) {
        return activePackageOperations.containsKey(basePrefix);
    }

    boolean isClosed() {
        return isClosed.get();
    }

    static final class PackageUploadLifecycleCoordinator {
        private final StorageKey basePrefix;
        private final HlsArtifactStreamSource.ActiveUploadScope scope;
        private final ConcurrentMap<StorageKey, PackageOperationType> activePackageOperations;
        private final Runnable onOperationReleased;
        private final AtomicInteger activePhysicalWorkers = new AtomicInteger(0);
        private final Set<Thread> physicalWorkerThreads = ConcurrentHashMap.newKeySet();
        private final Object workerMonitor = new Object();
        private final AtomicBoolean detached = new AtomicBoolean(false);
        private final AtomicBoolean scopeClosed = new AtomicBoolean(false);
        private final AtomicBoolean operationReleased = new AtomicBoolean(false);

        PackageUploadLifecycleCoordinator(
                StorageKey basePrefix,
                HlsArtifactStreamSource.ActiveUploadScope scope,
                ConcurrentMap<StorageKey, PackageOperationType> activePackageOperations,
                Runnable onOperationReleased
        ) {
            this.basePrefix = Objects.requireNonNull(basePrefix, "basePrefix cannot be null");
            this.scope = Objects.requireNonNull(scope, "scope cannot be null");
            this.activePackageOperations = Objects.requireNonNull(activePackageOperations, "activePackageOperations cannot be null");
            this.onOperationReleased = onOperationReleased;
        }

        HlsArtifactStreamSource.ActiveUploadScope scope() {
            return scope;
        }

        void workerStarted(Thread thread) {
            activePhysicalWorkers.incrementAndGet();
            physicalWorkerThreads.add(thread);
        }

        void workerTerminated(Thread thread) {
            physicalWorkerThreads.remove(thread);
            int remaining = activePhysicalWorkers.decrementAndGet();
            synchronized (workerMonitor) {
                workerMonitor.notifyAll();
            }
            if (remaining == 0 && detached.get()) {
                safeCleanup();
            }
        }

        void interruptAllActiveWorkers() {
            for (Thread t : physicalWorkerThreads) {
                try {
                    t.interrupt();
                } catch (SecurityException ignored) {
                }
            }
        }

        boolean awaitZeroPhysicalWorkers(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            synchronized (workerMonitor) {
                while (activePhysicalWorkers.get() > 0) {
                    long waitTime = deadline - System.currentTimeMillis();
                    if (waitTime <= 0) {
                        return false;
                    }
                    workerMonitor.wait(waitTime);
                }
                return true;
            }
        }

        void detachOwnership() {
            detached.set(true);
            if (activePhysicalWorkers.get() == 0) {
                safeCleanup();
            }
        }

        boolean isDetached() {
            return detached.get();
        }

        int activeWorkerCount() {
            return activePhysicalWorkers.get();
        }

        void closeScopeOnly() {
            if (scopeClosed.compareAndSet(false, true)) {
                scope.close();
            }
        }

        void safeCleanup() {
            try {
                closeScopeOnly();
            } finally {
                if (operationReleased.compareAndSet(false, true)) {
                    activePackageOperations.remove(basePrefix, PackageOperationType.UPLOAD_AND_VERIFY);
                    if (onOperationReleased != null) {
                        try {
                            onOperationReleased.run();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
    }

    private void uploadStage(
            String trustedBucket,
            List<HlsArtifactDescriptor> artifacts,
            HlsArtifactStreamSource artifactSource,
            PackageUploadLifecycleCoordinator coordinator
    ) {
        if (artifacts.isEmpty()) {
            return;
        }

        if (coordinator.scope().isCloseRequested()) {
            throw new IllegalStateException("Upload stage cannot start: artifact source closure requested.");
        }

        if (isClosed.get()) {
            throw new StorageException("Upload stage cannot start: adapter is closed or shutting down.");
        }

        int workerCount = Math.min(artifacts.size(), properties.maxConcurrentUploads());
        ConcurrentLinkedQueue<HlsArtifactDescriptor> remaining = new ConcurrentLinkedQueue<>(artifacts);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicInteger completedCount = new AtomicInteger();
        AtomicBoolean stageAborted = new AtomicBoolean(false);
        List<Future<?>> workerFutures = new ArrayList<>(workerCount);
        final Object stageMonitor = new Object();

        for (int i = 0; i < workerCount; i++) {
            if (stageAborted.get() || coordinator.scope().isCloseRequested() || isClosed.get()) {
                break;
            }
            try {
                Future<?> f = uploadExecutor.submit(() -> {
                    if (stageAborted.get() || Thread.currentThread().isInterrupted() || coordinator.scope().isCloseRequested()) {
                        return;
                    }
                    Thread current = Thread.currentThread();
                    coordinator.workerStarted(current);
                    try {
                        while (!stageAborted.get() && !current.isInterrupted() && firstError.get() == null && !coordinator.scope().isCloseRequested()) {
                            HlsArtifactDescriptor next = remaining.poll();
                            if (next == null) {
                                break;
                            }
                            try {
                                uploadArtifact(trustedBucket, next, artifactSource, coordinator.scope());
                                completedCount.incrementAndGet();
                                synchronized (stageMonitor) {
                                    stageMonitor.notifyAll();
                                }
                            } catch (Throwable t) {
                                firstError.compareAndSet(null, t);
                                stageAborted.set(true);
                                synchronized (stageMonitor) {
                                    stageMonitor.notifyAll();
                                }
                                break;
                            }
                        }
                    } finally {
                        coordinator.workerTerminated(current);
                        synchronized (stageMonitor) {
                            stageMonitor.notifyAll();
                        }
                    }
                });
                workerFutures.add(f);
            } catch (Throwable t) {
                firstError.compareAndSet(null, t);
                stageAborted.set(true);
                break;
            }
        }

        boolean interrupted = false;
        boolean timedOut = false;
        long stageBudgetNanos = properties.uploadStageTimeout().toNanos();
        long stageStartNanos = nanoTimeSupplier.getAsLong();

        synchronized (stageMonitor) {
            while (completedCount.get() < artifacts.size()
                    && firstError.get() == null
                    && !coordinator.scope().isCloseRequested()
                    && !stageAborted.get()) {
                long elapsedNanos = nanoTimeSupplier.getAsLong() - stageStartNanos;
                long remainingNanos = stageBudgetNanos - elapsedNanos;
                if (remainingNanos <= 0L) {
                    timedOut = true;
                    break;
                }
                long waitTimeMillis = Math.min(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)), 200L);
                try {
                    stageMonitor.wait(waitTimeMillis);
                } catch (InterruptedException e) {
                    interrupted = true;
                    Thread.currentThread().interrupt();
                    firstError.compareAndSet(null, e);
                    stageAborted.set(true);
                    break;
                }
            }
        }

        // Cancel pending tasks and signal abort if stage has not cleanly completed
        if (completedCount.get() < artifacts.size() || firstError.get() != null || coordinator.scope().isCloseRequested() || interrupted || timedOut) {
            stageAborted.set(true);
            for (Future<?> f : workerFutures) {
                f.cancel(true);
            }
            coordinator.interruptAllActiveWorkers();
        }

        // Bounded await for physical worker termination
        long gracePeriodMs = Math.max(50L, properties.requestTimeout().toMillis());
        boolean allTerminated = false;
        try {
            allTerminated = coordinator.awaitZeroPhysicalWorkers(gracePeriodMs);
        } catch (InterruptedException e) {
            interrupted = true;
            Thread.currentThread().interrupt();
        }

        if (!allTerminated) {
            throw new StorageException("Upload stage workers failed to physically terminate within timeout bounds");
        }

        if (firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error err) throw err;
            throw new StorageException("Upload stage failed: " + t.getMessage(), t);
        }

        if (coordinator.scope().isCloseRequested()) {
            throw new IllegalStateException("Upload stage aborted: artifact source closure requested.");
        }

        if (interrupted) {
            throw new StorageException("Upload stage was interrupted");
        }

        if (timedOut) {
            throw new StorageException(
                    "Upload stage exceeded uploadStageTimeout (" + properties.uploadStageTimeout().toSeconds()
                            + "s): expected=" + artifacts.size() + ", completed=" + completedCount.get()
            );
        }

        if (completedCount.get() != artifacts.size()) {
            throw new StorageException(
                    "Upload stage failed to upload all artifacts: expected=" + artifacts.size()
                            + ", completed=" + completedCount.get()
            );
        }
    }

    private void uploadArtifact(
            String trustedBucket,
            HlsArtifactDescriptor descriptor,
            HlsArtifactStreamSource artifactSource,
            HlsArtifactStreamSource.ActiveUploadScope scope
    ) {
        if (scope.isCloseRequested()) {
            throw new IllegalStateException("Cannot start artifact upload: package closure requested.");
        }
        if (descriptor.sizeBytes() > properties.maxArtifactSizeBytes()) {
            throw new StorageException(
                    "Artifact size (" + descriptor.sizeBytes() + " bytes) exceeds configured maximum ("
                            + properties.maxArtifactSizeBytes() + " bytes) for key " + descriptor.objectKey().value()
            );
        }

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(trustedBucket)
                .key(descriptor.objectKey().value())
                .ifNoneMatch("*")
                .contentType(descriptor.contentType().value())
                .contentLength(descriptor.sizeBytes())
                .build();

        ContentStreamProvider streamProvider = () -> {
            try {
                return artifactSource.openArtifactStream(descriptor.objectKey());
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Failed opening artifact stream for key: " + descriptor.objectKey().value(), e
                );
            }
        };

        RequestBody requestBody = RequestBody.fromContentProvider(
                streamProvider,
                descriptor.sizeBytes(),
                descriptor.contentType().value()
        );

        int attempt = 0;
        long startNanos = nanoTimeSupplier.getAsLong();
        long maxDurationNanos = properties.totalAttemptTimeout().toNanos();

        while (true) {
            attempt++;
            if (scope.isCloseRequested()) {
                throw new IllegalStateException("Upload aborted: package closure requested during retry.");
            }
            if (nanoTimeSupplier.getAsLong() - startNanos > maxDurationNanos) {
                throw new StorageException(
                        "Upload attempt timed out after " + properties.totalAttemptTimeout().toSeconds()
                                + " seconds for key: " + descriptor.objectKey().value()
                );
            }

            try {
                s3Client.putObject(putRequest, requestBody);
                return; // Successfully uploaded!
            } catch (S3Exception e) {
                if (e.statusCode() == 412 || "PreconditionFailed".equalsIgnoreCase(e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null)) {
                    // Pre-existing object encountered; independent content verification required
                    reconcilePreexistingObject(trustedBucket, descriptor);
                    return;
                }

                if (isTransientS3Exception(e) && attempt <= properties.maxRetries()) {
                    if (reconcileAmbiguousCompletion(trustedBucket, descriptor)) {
                        return;
                    }
                    sleepBackoff(attempt);
                    continue;
                }

                throw translateS3Exception("Failed uploading HLS artifact " + descriptor.objectKey().value(), e);
            } catch (SdkClientException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new StorageException("Upload interrupted for key: " + descriptor.objectKey().value(), e);
                }

                if (reconcileAmbiguousCompletion(trustedBucket, descriptor)) {
                    return;
                }

                if (attempt <= properties.maxRetries()) {
                    sleepBackoff(attempt);
                    continue;
                }

                throw new StorageException(
                        "SDK client exception uploading HLS artifact " + descriptor.objectKey().value() + ": " + e.getMessage(), e
                );
            }
        }
    }

    private void reconcilePreexistingObject(String trustedBucket, HlsArtifactDescriptor descriptor) {
        try (ResponseInputStream<GetObjectResponse> getResponse = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(trustedBucket)
                        .key(descriptor.objectKey().value())
                        .build()
        )) {
            long remoteSize = getResponse.response().contentLength();
            if (remoteSize != descriptor.sizeBytes()) {
                throw new StorageObjectAlreadyExistsException(descriptor.objectKey());
            }

            ContentHash remoteSha = digestStream(getResponse, remoteSize);
            if (!descriptor.sha256().equals(remoteSha)) {
                throw new StorageObjectAlreadyExistsException(descriptor.objectKey());
            }
        } catch (StorageObjectAlreadyExistsException e) {
            throw e;
        } catch (NoSuchKeyException e) {
            throw new StorageException(
                    "HTTP 412 conflict received but remote object was missing on read: " + descriptor.objectKey().value(), e
            );
        } catch (Exception e) {
            throw new StorageException(
                    "Failed verifying pre-existing remote object at " + descriptor.objectKey().value() + ": " + e.getMessage(), e
            );
        }
    }

    private boolean reconcileAmbiguousCompletion(String trustedBucket, HlsArtifactDescriptor descriptor) {
        try (ResponseInputStream<GetObjectResponse> getResponse = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(trustedBucket)
                        .key(descriptor.objectKey().value())
                        .build()
        )) {
            long remoteSize = getResponse.response().contentLength();
            if (remoteSize != descriptor.sizeBytes()) {
                throw new StorageException(
                        "Remote object size mismatch during ambiguous completion check at "
                                + descriptor.objectKey().value() + ": expected " + descriptor.sizeBytes()
                                + ", observed " + remoteSize
                );
            }

            ContentHash remoteSha = digestStream(getResponse, remoteSize);
            if (!descriptor.sha256().equals(remoteSha)) {
                throw new StorageException(
                        "Remote object SHA-256 mismatch during ambiguous completion check at "
                                + descriptor.objectKey().value() + ": expected " + descriptor.sha256().value()
                                + ", observed " + remoteSha.value()
                );
            }
            return true; // Exists and matches perfectly
        } catch (NoSuchKeyException e) {
            return false; // Confirmed absent, safe to retry create-only PUT
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || "NoSuchKey".equalsIgnoreCase(e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null)) {
                return false; // Confirmed absent, safe to retry create-only PUT
            }
            // 401/403/5xx/etc are NOT proof of absence! Fail closed.
            throw new StorageException(
                    "Uncertain remote state (HTTP " + e.statusCode() + ") during ambiguous completion check for key "
                            + descriptor.objectKey().value() + ": " + e.getMessage(), e
            );
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            // Network or client exception during probe is NOT proof of absence! Fail closed.
            throw new StorageException(
                    "Uncertain remote state during ambiguous completion check for key "
                            + descriptor.objectKey().value() + ": " + e.getMessage(), e
            );
        }
    }

    private HlsUploadedPackageVerification verifyRemoteInternal(
            String trustedBucket,
            HlsExpectedArtifactManifest expectedManifest,
            boolean digestAllSegmentsViaGet
    ) {
        String prefix = expectedManifest.identity().basePrefix().value();
        Map<String, Long> remoteKeySizes = new HashMap<>();
        long remoteTotalBytes = 0L;
        String continuationToken = null;
        Set<String> seenTokens = new HashSet<>();
        int pageCount = 0;
        int maxPages = (properties.maxVerificationCount() / 100) + 20;
        long startNanos = nanoTimeSupplier.getAsLong();
        long maxDurationNanos = properties.totalAttemptTimeout().toNanos();

        do {
            pageCount++;
            if (pageCount > maxPages) {
                throw new StorageException("Remote listing exceeded maximum pagination page limit: " + maxPages);
            }
            if (nanoTimeSupplier.getAsLong() - startNanos > maxDurationNanos) {
                throw new StorageException("Remote listing exceeded total timeout budget for prefix: " + prefix);
            }

            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(trustedBucket)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build();

            ListObjectsV2Response listResp;
            try {
                listResp = s3Client.listObjectsV2(listReq);
            } catch (Exception e) {
                throw new StorageException(
                        "Failed listing remote HLS package objects under " + prefix + ": " + e.getMessage(), e
                );
            }

            if (listResp.isTruncated() && listResp.contents().isEmpty()) {
                throw new StorageException("S3 ListObjectsV2 returned truncated empty page under prefix: " + prefix);
            }

            for (S3Object obj : listResp.contents()) {
                String key = obj.key();
                if (key == null || !key.startsWith(prefix)) {
                    throw new StorageException("Remote object key '" + key + "' violates prefix '" + prefix + "'.");
                }
                String relPath = key.substring(prefix.length());
                HlsPackageKeyValidator.validateAndClassifyRelativePath(relPath);

                if (remoteKeySizes.put(key, obj.size()) != null) {
                    throw new StorageException("Duplicate remote key returned in object listing: " + key);
                }
                remoteTotalBytes = Math.addExact(remoteTotalBytes, obj.size());

                if (remoteKeySizes.size() > properties.maxVerificationCount()) {
                    throw new StorageException(
                            "Remote object count exceeded maxVerificationCount (" + properties.maxVerificationCount() + ")"
                    );
                }
            }

            if (listResp.isTruncated()) {
                continuationToken = listResp.nextContinuationToken();
                if (continuationToken == null || continuationToken.isBlank()) {
                    throw new StorageException("S3 ListObjectsV2 reported isTruncated=true but nextContinuationToken is null or blank");
                }
                if (!seenTokens.add(continuationToken)) {
                    throw new StorageException("S3 ListObjectsV2 non-advancing or repeated continuation token detected: " + continuationToken);
                }
            } else {
                continuationToken = null;
            }
        } while (continuationToken != null);

        // Inventory size and count matching
        if (remoteKeySizes.size() != expectedManifest.objectCount()) {
            throw new StorageException(
                    "Remote object count mismatch: expected=" + expectedManifest.objectCount()
                            + ", observed=" + remoteKeySizes.size()
            );
        }
        if (remoteTotalBytes != expectedManifest.totalSizeBytes()) {
            throw new StorageException(
                    "Remote total bytes mismatch: expected=" + expectedManifest.totalSizeBytes()
                            + ", observed=" + remoteTotalBytes
            );
        }

        for (HlsArtifactDescriptor expected : expectedManifest.orderedArtifacts()) {
            String key = expected.objectKey().value();
            Long observedSize = remoteKeySizes.get(key);
            if (observedSize == null) {
                throw new StorageException("Missing remote HLS artifact: " + key);
            }
            if (observedSize != expected.sizeBytes()) {
                throw new StorageException(
                        "Remote size mismatch for key " + key + ": expected=" + expected.sizeBytes()
                                + ", observed=" + observedSize
                );
            }
        }

        // Streamed GET verification
        List<HlsArtifactDescriptor> artifactsToDigest = new ArrayList<>();
        artifactsToDigest.add(expectedManifest.stage3MasterManifest());
        artifactsToDigest.addAll(expectedManifest.stage2MediaPlaylists());
        for (HlsArtifactDescriptor a : expectedManifest.stage1BinaryArtifacts()) {
            if (a.artifactType() == HlsArtifactType.INIT_SEGMENT) {
                artifactsToDigest.add(a);
            }
        }
        int manifestAndInitCount = artifactsToDigest.size();

        if (digestAllSegmentsViaGet) {
            for (HlsArtifactDescriptor a : expectedManifest.stage1BinaryArtifacts()) {
                if (a.artifactType() != HlsArtifactType.INIT_SEGMENT) {
                    artifactsToDigest.add(a);
                }
            }
        }

        ContentHash observedMasterSha = null;
        for (HlsArtifactDescriptor descriptor : artifactsToDigest) {
            try (ResponseInputStream<GetObjectResponse> getResp = s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(trustedBucket)
                            .key(descriptor.objectKey().value())
                            .build()
            )) {
                long observedSize = getResp.response().contentLength();
                if (observedSize != descriptor.sizeBytes()) {
                    throw new StorageException(
                            "Remote GET content length mismatch for " + descriptor.objectKey().value()
                                    + ": expected=" + descriptor.sizeBytes() + ", observed=" + observedSize
                    );
                }

                ContentHash computedSha = digestStream(getResp, observedSize);
                if (!descriptor.sha256().equals(computedSha)) {
                    throw new StorageException(
                            "Remote GET SHA-256 mismatch for " + descriptor.objectKey().value()
                                    + ": expected=" + descriptor.sha256().value() + ", observed=" + computedSha.value()
                    );
                }

                if (descriptor.artifactType() == HlsArtifactType.MASTER_MANIFEST) {
                    observedMasterSha = computedSha;
                }
            } catch (StorageException e) {
                throw e;
            } catch (Exception e) {
                throw new StorageException(
                        "Failed streamed GET digest verification for " + descriptor.objectKey().value() + ": " + e.getMessage(), e
                );
            }
        }

        HlsRemoteIntegrityProof proof = digestAllSegmentsViaGet
                ? HlsRemoteIntegrityProof.STREAMED_GET_ALL_ARTIFACTS_WITH_EXACT_LISTING
                : HlsRemoteIntegrityProof.STREAMED_GET_MANIFESTS_AND_INITS_WITH_EXACT_LISTING;

        HlsUploadedPackageVerification.RemoteVerificationEvidence evidence =
                new HlsUploadedPackageVerification.RemoteVerificationEvidence(
                        proof,
                        true,
                        true,
                        true,
                        manifestAndInitCount,
                        observedMasterSha != null ? observedMasterSha : expectedManifest.masterManifestSha256(),
                        remoteTotalBytes,
                        remoteKeySizes.size(),
                        Instant.now()
                );

        return HlsUploadedPackageVerification.fromRemoteVerification(
                trustedBucket,
                expectedManifest,
                evidence
        );
    }

    private static ContentHash digestStream(InputStream in, long expectedSizeBytes) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest not available", e);
        }

        byte[] buffer = new byte[DIGEST_BUFFER_SIZE];
        long totalRead = 0L;
        int read;
        while ((read = in.read(buffer)) != -1) {
            md.update(buffer, 0, read);
            totalRead += read;
        }

        if (totalRead != expectedSizeBytes) {
            throw new IOException(
                    "Stream read bytes (" + totalRead + ") does not match expected size (" + expectedSizeBytes + ")"
            );
        }

        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return ContentHash.of(sb.toString());
    }

    private static boolean isTransientS3Exception(S3Exception e) {
        int status = e.statusCode();
        return status == 500 || status == 502 || status == 503 || status == 504 || status == 429;
    }

    private void sleepBackoff(int attempt) {
        long millis = Math.min(50L * (1L << Math.min(attempt, 6)), 1000L);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrupted during upload retry backoff", e);
        }
    }

    private static StorageException translateS3Exception(String message, S3Exception e) {
        int statusCode = e.statusCode();
        String errorCode = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : "UNKNOWN";
        return new StorageException(message + " (HTTP " + statusCode + ", code: " + errorCode + "): " + e.getMessage(), e);
    }

    private static S3Client createS3Client(R2HlsStorageProperties properties) {
        S3Configuration serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build();

        ClientOverrideConfiguration overrideConfig = ClientOverrideConfiguration.builder()
                .apiCallTimeout(properties.totalAttemptTimeout())
                .apiCallAttemptTimeout(properties.requestTimeout())
                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                .build();

        SdkHttpClient httpClient = UrlConnectionHttpClient.builder()
                .socketTimeout(properties.requestTimeout())
                .connectionTimeout(properties.requestTimeout())
                .build();

        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey())
                ))
                .region(Region.of(properties.region()))
                .serviceConfiguration(serviceConfig)
                .overrideConfiguration(overrideConfig)
                .httpClient(httpClient)
                .build();
    }

    private static ExecutorService createUploadExecutor(R2HlsStorageProperties properties) {
        return Executors.newFixedThreadPool(
                properties.maxConcurrentUploads(),
                r -> {
                    Thread t = new Thread(r, "r2-hls-upload-worker");
                    t.setDaemon(true);
                    return t;
                }
        );
    }

    @PreDestroy
    @Override
    public void close() {
        admissionLock.writeLock().lock();
        try {
            if (!isClosed.compareAndSet(false, true)) {
                return;
            }
        } finally {
            admissionLock.writeLock().unlock();
        }

        long shutdownTimeoutSec = Math.max(1L, properties.requestTimeout().toSeconds() + 5L);

        if (ownsExecutor && uploadExecutor != null) {
            uploadExecutor.shutdown();
            boolean terminated = false;
            try {
                terminated = uploadExecutor.awaitTermination(shutdownTimeoutSec, TimeUnit.SECONDS);
                if (!terminated) {
                    uploadExecutor.shutdownNow();
                    terminated = uploadExecutor.awaitTermination(Math.min(shutdownTimeoutSec, 5L), TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                uploadExecutor.shutdownNow();
            }
        }

        tryCloseS3ClientIfShutdownAndDrained();
    }
}
