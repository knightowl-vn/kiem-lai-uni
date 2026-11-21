package com.universe.media.infrastructure.hls;

import com.universe.media.application.asset.VideoUploadValidator;
import com.universe.media.application.exceptions.MediaAssetVersionContentHashMismatchException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.application.hls.DownloadedSourceScope;
import com.universe.media.application.hls.MediaVideoSourceDownloadPort;
import com.universe.media.application.hls.MediaVideoSourceSnapshot;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.domain.ContentHash;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Provider-neutral, chunked range downloader staging immutable source MP4 versions
 * into an isolated temporary file outside any database transaction (MS-08 HLS2B3A1).
 *
 * <p><strong>Key Security, Transaction &amp; Resource Rules:</strong>
 * <ul>
 *   <li>Dormant by default ({@code media.hls.packaging.enabled=false}); zero automatic scheduling or cloud startup.</li>
 *   <li>Executes strictly outside database transactions; callers must resolve {@link MediaVideoSourceSnapshot} first.</li>
 *   <li>Reads exclusively via bounded {@link BinaryStoragePort#openRange(com.universe.media.domain.StorageKey, long, long)}
 *       with fixed 8 KiB copy buffers (zero heap buffering of chunks or whole files).</li>
 *   <li>On transient chunk failure, truncates partial file bytes back to chunk boundary before retrying.</li>
 *   <li>Verifies exact downloaded size and SHA-256 against {@code snapshot.expectedContentHash()} before
 *       returning {@link DownloadedSourceScope}; incomplete {@code .part} files are never exposed or reused.</li>
 *   <li>Enforces a <strong>cooperative</strong> {@code totalTimeout} deadline checked before {@code openRange()},
 *       between chunks, before retry backoff, and between 8 KiB read iterations; individual synchronous
 *       {@code openRange()} / {@code InputStream.read()} calls rely on the underlying storage adapter HTTP client timeouts
 *       ({@code r2-movie} configures 30s call / 15s attempt timeouts on S3 client overrides; {@code b2} and {@code r2}
 *       use default {@code UrlConnectionHttpClient} settings without explicit {@code ClientOverrideConfiguration} timeouts;
 *       {@code local} performs synchronous filesystem reads).</li>
 *   <li>Advisory preflight disk-space check uses overflow-safe arithmetic; never claims to reserve disk capacity.</li>
 *   <li>On POSIX filesystems, creates source temp root (0700) and source files (0600); on non-POSIX hosts
 *       (e.g., Windows NTFS), confines files inside the non-symlinked directory inheriting directory ACLs.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "media.hls.packaging", name = "enabled", havingValue = "true")
public class MediaVideoSourceDownloader implements MediaVideoSourceDownloadPort {

    private static final int COPY_BUFFER_SIZE = 8192;
    private static final long MAX_BACKOFF_MILLIS = 10_000L;
    private static final String SOURCE_FILE_PREFIX = "hls-src-";
    private static final String PART_FILE_SUFFIX = ".mp4.part";
    private static final String VERIFIED_FILE_SUFFIX = ".mp4";

    @FunctionalInterface
    interface DiskSpaceChecker {
        long usableBytes(Path directory) throws IOException;
    }

    @FunctionalInterface
    interface SleepStrategy {
        void sleepMillis(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface ChannelOpener {
        FileChannel openWriteChannel(Path partFile) throws IOException;
    }

    @FunctionalInterface
    interface PromotedFileValidator {
        void validate(Path verifiedFile, long expectedSizeBytes) throws IOException;
    }

    @FunctionalInterface
    interface FileDeleter {
        boolean deleteIfExists(Path path) throws IOException;
    }

    private final StorageProviderResolverPort storageProviderResolverPort;
    private final HlsSourceDownloadProperties properties;
    private final DiskSpaceChecker diskSpaceChecker;
    private final LongSupplier nanoTimeSupplier;
    private final SleepStrategy sleepStrategy;
    private final ChannelOpener channelOpener;
    private final PromotedFileValidator promotedFileValidator;
    private final FileDeleter fileDeleter;

    @Autowired
    public MediaVideoSourceDownloader(
            StorageProviderResolverPort storageProviderResolverPort,
            HlsSourceDownloadProperties properties,
            @Autowired(required = false) HlsPackagingProperties packagingProperties
    ) {
        this(
                storageProviderResolverPort,
                properties,
                packagingProperties,
                dir -> Files.getFileStore(dir).getUsableSpace(),
                System::nanoTime,
                Thread::sleep,
                partFile -> FileChannel.open(partFile, StandardOpenOption.WRITE),
                MediaVideoSourceDownloader::defaultValidatePromotedFile,
                Files::deleteIfExists
        );
    }

    public MediaVideoSourceDownloader(
            StorageProviderResolverPort storageProviderResolverPort,
            HlsSourceDownloadProperties properties
    ) {
        this(
                storageProviderResolverPort,
                properties,
                null,
                dir -> Files.getFileStore(dir).getUsableSpace(),
                System::nanoTime,
                Thread::sleep,
                partFile -> FileChannel.open(partFile, StandardOpenOption.WRITE),
                MediaVideoSourceDownloader::defaultValidatePromotedFile,
                Files::deleteIfExists
        );
    }

    MediaVideoSourceDownloader(
            StorageProviderResolverPort storageProviderResolverPort,
            HlsSourceDownloadProperties properties,
            HlsPackagingProperties packagingProperties,
            DiskSpaceChecker diskSpaceChecker,
            LongSupplier nanoTimeSupplier,
            SleepStrategy sleepStrategy
    ) {
        this(
                storageProviderResolverPort,
                properties,
                packagingProperties,
                diskSpaceChecker,
                nanoTimeSupplier,
                sleepStrategy,
                partFile -> FileChannel.open(partFile, StandardOpenOption.WRITE),
                MediaVideoSourceDownloader::defaultValidatePromotedFile,
                Files::deleteIfExists
        );
    }

    MediaVideoSourceDownloader(
            StorageProviderResolverPort storageProviderResolverPort,
            HlsSourceDownloadProperties properties,
            HlsPackagingProperties packagingProperties,
            DiskSpaceChecker diskSpaceChecker,
            LongSupplier nanoTimeSupplier,
            SleepStrategy sleepStrategy,
            ChannelOpener channelOpener
    ) {
        this(
                storageProviderResolverPort,
                properties,
                packagingProperties,
                diskSpaceChecker,
                nanoTimeSupplier,
                sleepStrategy,
                channelOpener,
                MediaVideoSourceDownloader::defaultValidatePromotedFile,
                Files::deleteIfExists
        );
    }

    MediaVideoSourceDownloader(
            StorageProviderResolverPort storageProviderResolverPort,
            HlsSourceDownloadProperties properties,
            HlsPackagingProperties packagingProperties,
            DiskSpaceChecker diskSpaceChecker,
            LongSupplier nanoTimeSupplier,
            SleepStrategy sleepStrategy,
            ChannelOpener channelOpener,
            PromotedFileValidator promotedFileValidator,
            FileDeleter fileDeleter
    ) {
        this.storageProviderResolverPort = Objects.requireNonNull(
                storageProviderResolverPort,
                "StorageProviderResolverPort cannot be null."
        );
        this.properties = Objects.requireNonNull(properties, "HlsSourceDownloadProperties cannot be null.");
        if (packagingProperties != null) {
            HlsSourceDownloadProperties.validateDisjointFromPackagingScratch(
                    properties.tempRootDir(),
                    packagingProperties.scratchRootDir()
            );
        }
        this.diskSpaceChecker = Objects.requireNonNull(diskSpaceChecker, "DiskSpaceChecker cannot be null.");
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier cannot be null.");
        this.sleepStrategy = Objects.requireNonNull(sleepStrategy, "sleepStrategy cannot be null.");
        this.channelOpener = Objects.requireNonNull(channelOpener, "channelOpener cannot be null.");
        this.promotedFileValidator = Objects.requireNonNull(
                promotedFileValidator,
                "promotedFileValidator cannot be null."
        );
        this.fileDeleter = Objects.requireNonNull(fileDeleter, "fileDeleter cannot be null.");
    }

    @Override
    public DownloadedSourceScope downloadVerifiedSource(MediaVideoSourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "MediaVideoSourceSnapshot cannot be null.");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "MediaVideoSourceDownloader.downloadVerifiedSource must execute strictly outside any active "
                            + "database transaction (asset [" + snapshot.assetId() + "])."
            );
        }
        validateSnapshotForDownload(snapshot);

        // Fail fast if provider is unknown before touching disk
        BinaryStoragePort storagePort = storageProviderResolverPort.resolve(snapshot.storageProviderId());

        final Path realTempRoot;
        try {
            realTempRoot = ensureValidTempRoot();
            ensureAdvisoryDiskSpace(realTempRoot, snapshot.expectedSizeBytes());
        } catch (IOException e) {
            throw new StorageException(
                    "Failed preparing source download workspace for asset [" + snapshot.assetId() + "]: " + e.getMessage(),
                    e
            );
        }

        Path partFile = null;
        Path verifiedFile = null;
        FileChannel channel = null;
        boolean succeeded = false;
        Throwable primaryFailure = null;

        try {
            partFile = createIsolatedTempFile(realTempRoot);
            channel = channelOpener.openWriteChannel(partFile);

            long deadlineNanos = addNanosCapped(nanoTimeSupplier.getAsLong(), properties.totalTimeout().toNanos());
            long expectedTotal = snapshot.expectedSizeBytes();
            long offset = 0L;
            byte[] copyBuffer = new byte[COPY_BUFFER_SIZE];
            MessageDigest digest = createSha256Digest();
            boolean needFullRehash = false;

            while (offset < expectedTotal) {
                checkInterruptedAndDeadline(snapshot, deadlineNanos);
                long remaining = expectedTotal - offset;
                long chunkLength = Math.min(properties.chunkSizeBytes(), remaining);

                ChunkAttemptOutcome outcome = downloadChunkWithRetry(
                        storagePort,
                        snapshot,
                        channel,
                        offset,
                        chunkLength,
                        copyBuffer,
                        digest,
                        deadlineNanos
                );
                digest = outcome.updatedDigest();
                if (outcome.needFullRehash()) {
                    needFullRehash = true;
                }
                offset += chunkLength;
            }

            long actualFileSize = channel.size();
            channel.close();
            channel = null;

            if (actualFileSize != expectedTotal) {
                throw new StorageException(
                        "Downloaded source file size mismatch for asset [" + snapshot.assetId()
                                + "]: expected=" + expectedTotal + ", actual=" + actualFileSize
                );
            }

            ContentHash computedSha256 = needFullRehash
                    ? computeStreamingSha256(partFile, expectedTotal)
                    : ContentHash.of(HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT));

            if (!snapshot.expectedContentHash().equals(computedSha256)) {
                throw new MediaAssetVersionContentHashMismatchException(
                        snapshot.assetId(),
                        snapshot.sourceVersionNumber()
                );
            }

            AtomicBoolean movedToVerified = new AtomicBoolean(false);
            try {
                verifiedFile = promoteVerifiedFile(realTempRoot, partFile, expectedTotal, movedToVerified);
            } finally {
                if (movedToVerified.get()) {
                    partFile = null;
                }
            }
            succeeded = true;

            return new LocalDownloadedSourceScope(
                    realTempRoot,
                    verifiedFile,
                    snapshot,
                    computedSha256,
                    expectedTotal,
                    fileDeleter
            );
        } catch (MediaAssetVersionContentHashMismatchException | StorageException e) {
            primaryFailure = e;
            throw e;
        } catch (ClosedByInterruptException e) {
            Thread.currentThread().interrupt();
            StorageException wrapped = new StorageException(
                    "Source download interrupted for asset [" + snapshot.assetId() + "].",
                    e
            );
            primaryFailure = wrapped;
            throw wrapped;
        } catch (IOException e) {
            StorageException wrapped = Thread.currentThread().isInterrupted()
                    ? new StorageException(
                            "Source download interrupted for asset [" + snapshot.assetId() + "].",
                            e
                    )
                    : new StorageException(
                            "Local I/O failure downloading source MP4 for asset [" + snapshot.assetId() + "]: "
                                    + e.getMessage(),
                            e
                    );
            primaryFailure = wrapped;
            throw wrapped;
        } catch (RuntimeException | Error t) {
            primaryFailure = t;
            throw t;
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeEx) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(closeEx);
                    }
                }
            }
            if (!succeeded) {
                if (partFile != null) {
                    try {
                        deleteManagedFileIfExists(realTempRoot, partFile, PART_FILE_SUFFIX, fileDeleter);
                    } catch (IOException | RuntimeException cleanupEx) {
                        if (primaryFailure != null) {
                            primaryFailure.addSuppressed(cleanupEx);
                        }
                    }
                }
                if (verifiedFile != null) {
                    try {
                        deleteManagedFileIfExists(realTempRoot, verifiedFile, VERIFIED_FILE_SUFFIX, fileDeleter);
                    } catch (IOException | RuntimeException cleanupEx) {
                        if (primaryFailure != null) {
                            primaryFailure.addSuppressed(cleanupEx);
                        }
                    }
                }
            }
        }
    }

    private void validateSnapshotForDownload(MediaVideoSourceSnapshot snapshot) {
        long size = snapshot.expectedSizeBytes();
        if (size <= 0L || size > properties.maxSourceBytes()) {
            throw new IllegalArgumentException(
                    "Source video size (" + size + " bytes) exceeds configured maxSourceBytes ("
                            + properties.maxSourceBytes() + ")."
            );
        }
        String mime = snapshot.mimeType().value().toLowerCase(Locale.ROOT);
        if (!VideoUploadValidator.CANONICAL_MIME_TYPE.equals(mime)) {
            throw new UploadContentMimeMismatchException(
                    "Unsupported source MIME type '" + snapshot.mimeType().value()
                            + "'; required '" + VideoUploadValidator.CANONICAL_MIME_TYPE + "'."
            );
        }
    }

    private ChunkAttemptOutcome downloadChunkWithRetry(
            BinaryStoragePort storagePort,
            MediaVideoSourceSnapshot snapshot,
            FileChannel channel,
            long chunkOffset,
            long chunkLength,
            byte[] copyBuffer,
            MessageDigest runningDigest,
            long deadlineNanos
    ) throws IOException {
        boolean needFullRehash = false;
        int attempt = 0;

        while (true) {
            checkInterruptedAndDeadline(snapshot, deadlineNanos);
            MessageDigest digestSnapshot = tryCloneDigest(runningDigest);
            MessageDigest activeDigest = digestSnapshot != null ? runningDigest : null;

            try {
                readAndWriteSingleChunk(
                        storagePort,
                        snapshot,
                        channel,
                        chunkOffset,
                        chunkLength,
                        copyBuffer,
                        activeDigest,
                        deadlineNanos
                );
                if (activeDigest == null) {
                    needFullRehash = true;
                }
                return new ChunkAttemptOutcome(runningDigest, needFullRehash);
            } catch (LocalDiskIoException e) {
                throw e.causeIOException();
            } catch (RuntimeException e) {
                rollbackChunkFilePosition(channel, chunkOffset);
                if (digestSnapshot != null) {
                    runningDigest = digestSnapshot;
                } else {
                    needFullRehash = true;
                }

                boolean interrupted = Thread.currentThread().isInterrupted() || hasInterruptionInChain(e);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                    throw new StorageException(
                            "Source download interrupted for asset [" + snapshot.assetId() + "].",
                            e
                    );
                }

                if (!isRetryableRangeFailure(e) || attempt >= properties.maxChunkRetries()) {
                    if (e instanceof StorageException se) {
                        throw se;
                    }
                    throw new StorageException(
                            "Failed downloading chunk at offset " + chunkOffset + " for asset ["
                                    + snapshot.assetId() + "]: " + e.getMessage(),
                            e
                    );
                }

                attempt++;
                sleepBoundedBackoff(attempt, deadlineNanos, snapshot);
            }
        }
    }

    private void readAndWriteSingleChunk(
            BinaryStoragePort storagePort,
            MediaVideoSourceSnapshot snapshot,
            FileChannel channel,
            long chunkOffset,
            long chunkLength,
            byte[] copyBuffer,
            MessageDigest digestOrNull,
            long deadlineNanos
    ) {
        final InputStream rawStream;
        try {
            rawStream = storagePort.openRange(snapshot.storageKey(), chunkOffset, chunkLength);
        } catch (RuntimeException e) {
            throw e;
        }

        if (rawStream == null) {
            throw new NonRetryableRangeException(
                    "Storage provider returned null InputStream for openRange at offset " + chunkOffset
            );
        }

        try (InputStream in = rawStream) {
            long chunkRead = 0L;
            while (chunkRead < chunkLength) {
                checkInterruptedAndDeadline(snapshot, deadlineNanos);
                int toRead = (int) Math.min(copyBuffer.length, chunkLength - chunkRead);
                int n;
                try {
                    n = in.read(copyBuffer, 0, toRead);
                } catch (IOException readEx) {
                    throw new RetryableStreamReadException(
                            "Stream read failed at offset " + (chunkOffset + chunkRead) + ": " + readEx.getMessage(),
                            readEx
                    );
                }

                if (n == -1) {
                    throw new NonRetryableRangeException(
                            "Truncated range / early EOF at offset " + (chunkOffset + chunkRead)
                                    + " (expected chunkLength=" + chunkLength + ", read=" + chunkRead + ")"
                    );
                }

                if (n > 0) {
                    checkInterruptedAndDeadline(snapshot, deadlineNanos);
                    writeFully(channel, copyBuffer, n);
                    if (digestOrNull != null) {
                        digestOrNull.update(copyBuffer, 0, n);
                    }
                    chunkRead += n;
                }
            }

            try {
                if (in.read() != -1) {
                    throw new NonRetryableRangeException(
                            "Range response exceeded requested length " + chunkLength + " at offset " + chunkOffset
                    );
                }
            } catch (IOException readEx) {
                throw new RetryableStreamReadException(
                        "Failed reading range EOF terminator at offset " + (chunkOffset + chunkRead),
                        readEx
                );
            }
        } catch (IOException closeEx) {
            // Stream close failure after all expected bytes were written & digested is non-fatal,
            // or if read failed we are already unwinding with the primary exception.
        }
    }

    private static void writeFully(FileChannel channel, byte[] buffer, int length) {
        ByteBuffer bb = ByteBuffer.wrap(buffer, 0, length);
        try {
            while (bb.hasRemaining()) {
                channel.write(bb);
            }
        } catch (IOException e) {
            throw new LocalDiskIoException(e);
        }
    }

    private static void rollbackChunkFilePosition(FileChannel channel, long targetOffset) throws IOException {
        channel.truncate(targetOffset);
        channel.position(targetOffset);
    }

    private boolean isRetryableRangeFailure(Throwable t) {
        if (hasInterruptionInChain(t)
                || t instanceof StorageObjectNotFoundException
                || t instanceof NonRetryableRangeException
                || t instanceof SecurityException
                || t instanceof IllegalArgumentException
                || t instanceof IllegalStateException) {
            return false;
        }
        if (t instanceof RetryableStreamReadException) {
            return true;
        }

        // Inspect causal chain for S3 / SDK / transport exceptions
        Throwable current = t;
        boolean sawSdkClientOrNetwork = false;
        while (current != null) {
            if (current instanceof StorageObjectNotFoundException) {
                return false;
            }
            if (current instanceof S3Exception s3Ex) {
                int status = s3Ex.statusCode();
                return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
            }
            if (current instanceof AwsServiceException) {
                return false;
            }
            if (current instanceof SdkClientException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof IOException) {
                sawSdkClientOrNetwork = true;
            }
            current = current.getCause();
        }
        return sawSdkClientOrNetwork;
    }

    private static boolean hasInterruptionInChain(Throwable t) {
        if (t == null) {
            return false;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> queue = new ArrayDeque<>();
        queue.add(t);
        while (!queue.isEmpty()) {
            Throwable current = queue.poll();
            if (current == null || !visited.add(current)) {
                continue;
            }
            if (current instanceof InterruptedException
                    || current instanceof ClosedByInterruptException
                    || (current instanceof InterruptedIOException && !(current instanceof SocketTimeoutException))) {
                return true;
            }
            if (current.getCause() != null) {
                queue.add(current.getCause());
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed != null) {
                    queue.add(suppressed);
                }
            }
        }
        return false;
    }

    private void sleepBoundedBackoff(int retryAttempt, long deadlineNanos, MediaVideoSourceSnapshot snapshot) {
        checkInterruptedAndDeadline(snapshot, deadlineNanos);
        long baseBackoff = properties.retryBackoffMillis();
        if (baseBackoff <= 0L) {
            return;
        }
        long shift = Math.min(retryAttempt - 1, 6);
        long delayMillis = Math.min(baseBackoff * (1L << shift), MAX_BACKOFF_MILLIS);
        long remainingNanos = deadlineNanos - nanoTimeSupplier.getAsLong();
        if (remainingNanos <= 0L) {
            throw new StorageException(
                    "Source download exceeded total timeout budget (" + properties.totalTimeout()
                            + ") for asset [" + snapshot.assetId() + "]."
            );
        }
        long cappedDelayMillis = Math.min(delayMillis, Math.max(1L, remainingNanos / 1_000_000L));
        try {
            sleepStrategy.sleepMillis(cappedDelayMillis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new StorageException(
                    "Source download interrupted during retry backoff for asset [" + snapshot.assetId() + "].",
                    ie
            );
        }
    }

    private void checkInterruptedAndDeadline(MediaVideoSourceSnapshot snapshot, long deadlineNanos) {
        if (Thread.currentThread().isInterrupted()) {
            throw new StorageException(
                    "Source download interrupted for asset [" + snapshot.assetId() + "]."
            );
        }
        if (nanoTimeSupplier.getAsLong() >= deadlineNanos) {
            throw new StorageException(
                    "Source download exceeded total timeout budget (" + properties.totalTimeout()
                            + ") for asset [" + snapshot.assetId() + "]."
            );
        }
    }

    private Path ensureValidTempRoot() throws IOException {
        Path configured = properties.tempRootDir().toAbsolutePath().normalize();
        HlsPackagingProperties.validateDirectoryOutsideWorktree(configured, "HLS source download tempRootDir");
        rejectUnsafeDirectoryLinkOrJunction(configured);

        if (!Files.exists(configured, LinkOption.NOFOLLOW_LINKS)) {
            if (isPosixSupported()) {
                FileAttribute<Set<PosixFilePermission>> dirAttrs =
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
                Files.createDirectories(configured, dirAttrs);
            } else {
                Files.createDirectories(configured);
            }
        }

        rejectUnsafeDirectoryLinkOrJunction(configured);
        Path realRoot = configured.toRealPath();
        HlsPackagingProperties.validateDirectoryOutsideWorktree(realRoot, "HLS source download tempRootDir");
        if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(realRoot)) {
            throw new IOException("Source download tempRootDir is not a writable directory: " + realRoot);
        }
        return realRoot;
    }

    private static void rejectUnsafeDirectoryLinkOrJunction(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Source download tempRootDir must not be a symbolic link: " + path);
        }
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isOther()) {
            Path realPath = path.toRealPath();
            if (!realPath.toString().equalsIgnoreCase(path.toString())) {
                throw new IOException(
                        "Source download tempRootDir must not be a junction/reparse alias: "
                                + path + " -> " + realPath
                );
            }
        }
    }

    private void ensureAdvisoryDiskSpace(Path realTempRoot, long expectedSourceBytes) throws IOException {
        final long requiredBytes;
        try {
            requiredBytes = Math.addExact(expectedSourceBytes, properties.minFreeDiskBytes());
        } catch (ArithmeticException overflow) {
            throw new StorageException(
                    "Overflow computing required disk space for source size " + expectedSourceBytes
                            + " and reserve " + properties.minFreeDiskBytes(),
                    overflow
            );
        }

        long usable = diskSpaceChecker.usableBytes(realTempRoot);
        if (usable < requiredBytes) {
            throw new StorageException(
                    "Insufficient usable disk space in [" + realTempRoot + "]: usable=" + usable
                            + " bytes, required=" + requiredBytes + " bytes (source=" + expectedSourceBytes
                            + ", reserve=" + properties.minFreeDiskBytes() + ")."
            );
        }
    }

    private static Path createIsolatedTempFile(Path realTempRoot) throws IOException {
        if (isPosixSupported()) {
            FileAttribute<Set<PosixFilePermission>> attrs =
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            return Files.createTempFile(realTempRoot, SOURCE_FILE_PREFIX, PART_FILE_SUFFIX, attrs);
        }
        return Files.createTempFile(realTempRoot, SOURCE_FILE_PREFIX, PART_FILE_SUFFIX);
    }

    private Path promoteVerifiedFile(
            Path realTempRoot,
            Path partFile,
            long expectedSizeBytes,
            AtomicBoolean movedToVerified
    ) throws IOException {
        String partName = partFile.getFileName().toString();
        String baseName = partName.endsWith(PART_FILE_SUFFIX)
                ? partName.substring(0, partName.length() - PART_FILE_SUFFIX.length())
                : SOURCE_FILE_PREFIX + System.nanoTime();
        Path verifiedFile = realTempRoot.resolve(baseName + VERIFIED_FILE_SUFFIX).normalize();
        if (!verifiedFile.getParent().equals(realTempRoot)) {
            throw new IOException("Verified source path escapes tempRootDir: " + verifiedFile);
        }
        if (Files.exists(verifiedFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(verifiedFile.toString());
        }

        try {
            Files.move(partFile, verifiedFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partFile, verifiedFile);
        }
        movedToVerified.set(true);

        try {
            promotedFileValidator.validate(verifiedFile, expectedSizeBytes);
            return verifiedFile;
        } catch (IOException | RuntimeException validationEx) {
            try {
                deleteManagedFileIfExists(realTempRoot, verifiedFile, VERIFIED_FILE_SUFFIX, fileDeleter);
            } catch (IOException | RuntimeException cleanupEx) {
                validationEx.addSuppressed(cleanupEx);
            }
            throw validationEx;
        }
    }

    private static void defaultValidatePromotedFile(Path verifiedFile, long expectedSizeBytes) throws IOException {
        if (Files.isSymbolicLink(verifiedFile)
                || !Files.isRegularFile(verifiedFile, LinkOption.NOFOLLOW_LINKS)
                || Files.size(verifiedFile) != expectedSizeBytes) {
            throw new IOException("Promoted verified source file failed post-move validation: " + verifiedFile);
        }
    }

    private static boolean isPosixSupported() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest unavailable.", e);
        }
    }

    private static MessageDigest tryCloneDigest(MessageDigest digest) {
        try {
            return (MessageDigest) digest.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    private static ContentHash computeStreamingSha256(Path file, long expectedSizeBytes) throws IOException {
        MessageDigest digest = createSha256Digest();
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long readTotal = 0L;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n > 0) {
                    digest.update(buffer, 0, n);
                    readTotal += n;
                }
            }
        }
        if (readTotal != expectedSizeBytes) {
            throw new IOException(
                    "Fallback SHA-256 read length mismatch: expected=" + expectedSizeBytes + ", read=" + readTotal
            );
        }
        return ContentHash.of(HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT));
    }

    private static long addNanosCapped(long startNanos, long durationNanos) {
        long result = startNanos + durationNanos;
        if (durationNanos > 0 && result < startNanos) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static void deleteManagedFileIfExists(
            Path expectedTempRoot,
            Path managedFile,
            String requiredSuffix,
            FileDeleter deleter
    ) throws IOException {
        Path normRoot = expectedTempRoot.toAbsolutePath().normalize();
        Path normFile = managedFile.toAbsolutePath().normalize();
        String fileName = normFile.getFileName() != null ? normFile.getFileName().toString() : "";
        if (!normRoot.equals(normFile.getParent())
                || !fileName.startsWith(SOURCE_FILE_PREFIX)
                || !fileName.endsWith(requiredSuffix)
                || Files.isSymbolicLink(normRoot)
                || Files.isSymbolicLink(normFile)
                || Files.isDirectory(normFile, LinkOption.NOFOLLOW_LINKS)
                || (Files.exists(normFile, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(normFile, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Refusing unsafe cleanup outside isolated regular source file: " + normFile);
        }
        deleter.deleteIfExists(normFile);
    }

    private record ChunkAttemptOutcome(MessageDigest updatedDigest, boolean needFullRehash) {
    }

    private static final class LocalDiskIoException extends RuntimeException {
        private final IOException causeIOException;

        private LocalDiskIoException(IOException causeIOException) {
            super(causeIOException.getMessage(), causeIOException);
            this.causeIOException = causeIOException;
        }

        private IOException causeIOException() {
            return causeIOException;
        }
    }

    private static final class RetryableStreamReadException extends StorageException {
        private RetryableStreamReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class NonRetryableRangeException extends StorageException {
        private NonRetryableRangeException(String message) {
            super(message);
        }
    }

    static final class LocalDownloadedSourceScope implements DownloadedSourceScope {

        private final Path tempRootDir;
        private final Path sourceFile;
        private final MediaVideoSourceSnapshot snapshot;
        private final ContentHash verifiedSha256;
        private final long verifiedSizeBytes;
        private final FileDeleter fileDeleter;
        private final AtomicBoolean closeRequested = new AtomicBoolean(false);
        private final AtomicBoolean cleanupCompleted = new AtomicBoolean(false);
        private final Object closeLock = new Object();

        LocalDownloadedSourceScope(
                Path tempRootDir,
                Path sourceFile,
                MediaVideoSourceSnapshot snapshot,
                ContentHash verifiedSha256,
                long verifiedSizeBytes
        ) {
            this(tempRootDir, sourceFile, snapshot, verifiedSha256, verifiedSizeBytes, Files::deleteIfExists);
        }

        LocalDownloadedSourceScope(
                Path tempRootDir,
                Path sourceFile,
                MediaVideoSourceSnapshot snapshot,
                ContentHash verifiedSha256,
                long verifiedSizeBytes,
                FileDeleter fileDeleter
        ) {
            this.tempRootDir = Objects.requireNonNull(tempRootDir, "tempRootDir cannot be null.");
            this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile cannot be null.");
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot cannot be null.");
            this.verifiedSha256 = Objects.requireNonNull(verifiedSha256, "verifiedSha256 cannot be null.");
            this.verifiedSizeBytes = verifiedSizeBytes;
            this.fileDeleter = Objects.requireNonNull(fileDeleter, "fileDeleter cannot be null.");
        }

        @Override
        public Path sourceFile() {
            if (closeRequested.get()) {
                throw new IllegalStateException("DownloadedSourceScope has already been closed.");
            }
            return sourceFile;
        }

        @Override
        public MediaVideoSourceSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public ContentHash verifiedSha256() {
            return verifiedSha256;
        }

        @Override
        public long verifiedSizeBytes() {
            return verifiedSizeBytes;
        }

        @Override
        public boolean isClosed() {
            return closeRequested.get();
        }

        boolean isCleanupCompleted() {
            return cleanupCompleted.get();
        }

        @Override
        public void close() throws IOException {
            closeRequested.set(true);
            if (cleanupCompleted.get()) {
                return;
            }
            synchronized (closeLock) {
                if (cleanupCompleted.get()) {
                    return;
                }
                deleteManagedFileIfExists(tempRootDir, sourceFile, VERIFIED_FILE_SUFFIX, fileDeleter);
                cleanupCompleted.set(true);
            }
        }
    }
}
