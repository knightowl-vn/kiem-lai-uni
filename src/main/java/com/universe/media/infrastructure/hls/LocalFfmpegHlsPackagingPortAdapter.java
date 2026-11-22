package com.universe.media.infrastructure.hls;

import com.universe.media.application.hls.HlsLocalPackagingException;
import com.universe.media.application.hls.HlsLocalPackagingPort;
import com.universe.media.application.hls.HlsPackagingOperationalException;
import com.universe.media.application.hls.PrepareLocalHlsPackageCommand;
import com.universe.media.application.hls.PreparedHlsPackageScope;
import com.universe.media.application.hls.UnsupportedHlsSourceMediaException;
import com.universe.media.application.ports.storage.HlsArtifactStreamSource;
import com.universe.media.application.ports.storage.HlsExpectedArtifactManifest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Infrastructure adapter bridging {@link HlsLocalPackagingPort} to
 * {@link LocalFfmpegHlsPackagingEngine} and bounded {@link LocalHlsPackageInventory}
 * (MS-08 HLS-2B3B1).
 */
@Component
@ConditionalOnProperty(prefix = "media.hls.packaging", name = "enabled", havingValue = "true")
public class LocalFfmpegHlsPackagingPortAdapter implements HlsLocalPackagingPort {

    private final LocalFfmpegHlsPackagingEngine packagingEngine;

    @Autowired
    public LocalFfmpegHlsPackagingPortAdapter(LocalFfmpegHlsPackagingEngine packagingEngine) {
        this.packagingEngine = Objects.requireNonNull(packagingEngine, "LocalFfmpegHlsPackagingEngine cannot be null.");
    }

    @Override
    public PreparedHlsPackageScope preparePackage(PrepareLocalHlsPackageCommand command) {
        Objects.requireNonNull(command, "PrepareLocalHlsPackageCommand cannot be null.");

        final LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult localResult;
        try {
            localResult = packagingEngine.packageLocalSource(
                    command.sourceFile(),
                    command.segmentDurationSeconds(),
                    HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE,
                    HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
            );
        } catch (HlsLadderPlanner.UnsupportedHlsSourceException unsupportedEx) {
            throw new UnsupportedHlsSourceMediaException(unsupportedEx.getMessage(), unsupportedEx);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new HlsLocalPackagingException("Local HLS packaging interrupted: " + ie.getMessage(), ie);
        } catch (TimeoutException te) {
            throw new HlsLocalPackagingException("Local HLS packaging timed out: " + te.getMessage(), te);
        } catch (IOException ioe) {
            if (isMissingExecutableStartFailure(ioe)) {
                throw new HlsPackagingOperationalException("FFmpeg/ffprobe executable unavailable: " + ioe.getMessage(), ioe);
            }
            throw new HlsLocalPackagingException("Local HLS packaging I/O failure: " + ioe.getMessage(), ioe);
        }

        try {
            LocalHlsPackageInventory.InventoryLimits limits =
                    LocalHlsPackageInventory.InventoryLimits.stricterOfDefaultsAndRemote(
                            command.maxObjectCount(),
                            command.maxSingleObjectBytes()
                    );
            LocalHlsPackageInventory inventory = LocalHlsPackageInventory.fromVerifiedResult(
                    localResult,
                    command.assetId(),
                    command.sourceVersionNumber(),
                    command.packageUuid(),
                    command.outputProviderId(),
                    limits
            );
            return new EngineBackedPreparedHlsPackageScope(localResult, inventory);
        } catch (Throwable inventoryFailure) {
            try {
                localResult.close();
            } catch (IOException | RuntimeException closeEx) {
                inventoryFailure.addSuppressed(closeEx);
            }
            if (inventoryFailure instanceof RuntimeException re) {
                throw re;
            }
            throw new HlsLocalPackagingException(
                    "Local HLS inventory construction failed: " + inventoryFailure.getMessage(),
                    inventoryFailure
            );
        }
    }

    private static boolean isMissingExecutableStartFailure(IOException ioe) {
        String msg = ioe.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("cannot run program")
                || (lower.contains("createprocess error=2"))
                || (lower.contains("no such file or directory") && (lower.contains("ffmpeg") || lower.contains("ffprobe")));
    }

    private static final class EngineBackedPreparedHlsPackageScope implements PreparedHlsPackageScope {

        private final LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult localResult;
        private final HlsExpectedArtifactManifest manifest;
        private final HlsArtifactStreamSource streamSource;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private EngineBackedPreparedHlsPackageScope(
                LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult localResult,
                LocalHlsPackageInventory inventory
        ) {
            this.localResult = localResult;
            this.manifest = inventory.expectedManifest();
            this.streamSource = inventory.openArtifactStreamSource();
        }

        @Override
        public HlsExpectedArtifactManifest expectedManifest() {
            ensureOpen();
            return manifest;
        }

        @Override
        public HlsArtifactStreamSource artifactStreamSource() {
            ensureOpen();
            return streamSource;
        }

        @Override
        public int segmentDurationSeconds() {
            return localResult.plan().segmentDurationSeconds();
        }

        @Override
        public long totalSizeBytes() {
            return manifest.totalSizeBytes();
        }

        @Override
        public int objectCount() {
            return manifest.objectCount();
        }

        @Override
        public boolean isClosed() {
            return closed.get() || localResult.isClosed();
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                localResult.close();
            }
        }

        private void ensureOpen() {
            if (isClosed()) {
                throw new IllegalStateException("PreparedHlsPackageScope has already been closed.");
            }
        }
    }
}
