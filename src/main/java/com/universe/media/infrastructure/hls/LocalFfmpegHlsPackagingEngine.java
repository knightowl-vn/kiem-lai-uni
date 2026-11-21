package com.universe.media.infrastructure.hls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Production infrastructure engine orchestrating local FFmpeg fMP4 HLS packaging
 * for an authorized local source file (MS-08 HLS2B1).
 *
 * <p><strong>Dormant Activation:</strong> Enabled only when {@code media.hls.packaging.enabled=true}.
 * Does not poll DB jobs, upload to R2/B2, create {@code VerifiedHlsPackageReceipt},
 * or mark any HLS package READY.
 */
@Component
@ConditionalOnProperty(prefix = "media.hls.packaging", name = "enabled", havingValue = "true")
public class LocalFfmpegHlsPackagingEngine {

    private static final String ATTEMPT_DIR_PREFIX = "hls-pkg-";

    /**
     * Engine-issued packaging result owning cleanup of its exact created attempt directory.
     * The constructor is private so external callers cannot forge ownership of unrelated directories.
     */
    public static final class LocalHlsPackagingResult implements AutoCloseable {

        private final Path packageRoot;
        private final Path scratchRoot;
        private final HlsLadderPlanner.ProbedVideoSource probedSource;
        private final HlsLadderPlanner.HlsPackagingPlan plan;
        private final List<String> ffmpegCommand;
        private final long encodeElapsedMillis;
        private final LocalHlsPackageVerifier.LocalHlsVerificationReport verificationReport;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private LocalHlsPackagingResult(
                Path packageRoot,
                Path scratchRoot,
                HlsLadderPlanner.ProbedVideoSource probedSource,
                HlsLadderPlanner.HlsPackagingPlan plan,
                List<String> ffmpegCommand,
                long encodeElapsedMillis,
                LocalHlsPackageVerifier.LocalHlsVerificationReport verificationReport
        ) {
            this.packageRoot = Objects.requireNonNull(packageRoot, "packageRoot cannot be null.");
            this.scratchRoot = Objects.requireNonNull(scratchRoot, "scratchRoot cannot be null.");
            this.probedSource = Objects.requireNonNull(probedSource, "probedSource cannot be null.");
            this.plan = Objects.requireNonNull(plan, "plan cannot be null.");
            this.ffmpegCommand = List.copyOf(Objects.requireNonNull(ffmpegCommand, "ffmpegCommand cannot be null."));
            this.encodeElapsedMillis = encodeElapsedMillis;
            this.verificationReport = Objects.requireNonNull(verificationReport, "verificationReport cannot be null.");
        }

        public Path packageRoot() {
            return packageRoot;
        }

        public Path scratchRoot() {
            return scratchRoot;
        }

        public HlsLadderPlanner.ProbedVideoSource probedSource() {
            return probedSource;
        }

        public HlsLadderPlanner.HlsPackagingPlan plan() {
            return plan;
        }

        public List<String> ffmpegCommand() {
            return ffmpegCommand;
        }

        public long encodeElapsedMillis() {
            return encodeElapsedMillis;
        }

        public LocalHlsPackageVerifier.LocalHlsVerificationReport verificationReport() {
            return verificationReport;
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                deleteAttemptDirectorySafely(packageRoot, scratchRoot);
            }
        }
    }

    private final HlsPackagingProperties properties;
    private final HlsFfmpegExecutionGate executionGate;
    private final FfprobeMediaSourceProbe sourceProbe;
    private final HlsLadderPlanner ladderPlanner;
    private final FfmpegHlsPackager packager;
    private final LocalHlsPackageVerifier verifier;

    @Autowired
    public LocalFfmpegHlsPackagingEngine(
            HlsPackagingProperties properties,
            HlsFfmpegExecutionGate executionGate,
            FfprobeMediaSourceProbe sourceProbe,
            FfmpegHlsPackager packager,
            LocalHlsPackageVerifier verifier
    ) {
        this(
                properties,
                executionGate,
                sourceProbe,
                new HlsLadderPlanner(),
                packager,
                verifier
        );
    }

    public LocalFfmpegHlsPackagingEngine(
            HlsPackagingProperties properties,
            HlsFfmpegExecutionGate executionGate,
            FfprobeMediaSourceProbe sourceProbe,
            HlsLadderPlanner ladderPlanner,
            FfmpegHlsPackager packager,
            LocalHlsPackageVerifier verifier
    ) {
        this.properties = Objects.requireNonNull(properties, "HlsPackagingProperties cannot be null.");
        this.executionGate = Objects.requireNonNull(executionGate, "HlsFfmpegExecutionGate cannot be null.");
        this.sourceProbe = Objects.requireNonNull(sourceProbe, "FfprobeMediaSourceProbe cannot be null.");
        this.ladderPlanner = Objects.requireNonNull(ladderPlanner, "HlsLadderPlanner cannot be null.");
        this.packager = Objects.requireNonNull(packager, "FfmpegHlsPackager cannot be null.");
        this.verifier = Objects.requireNonNull(verifier, "LocalHlsPackageVerifier cannot be null.");
    }

    public LocalHlsPackagingResult packageLocalSource(Path sourceFile)
            throws IOException, InterruptedException, TimeoutException {
        return packageLocalSource(
                sourceFile,
                properties.defaultSegmentDurationSeconds(),
                HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE,
                HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        );
    }

    public LocalHlsPackagingResult packageLocalSource(
            Path sourceFile,
            int segmentDurationSeconds,
            HlsLadderPlanner.Below720Policy below720Policy,
            HlsLadderPlanner.HdrPolicy hdrPolicy
    ) throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(sourceFile, "sourceFile cannot be null.");
        Objects.requireNonNull(below720Policy, "below720Policy cannot be null.");
        Objects.requireNonNull(hdrPolicy, "hdrPolicy cannot be null.");

        Path normalizedSource = sourceFile.toAbsolutePath().normalize();
        if (!Files.exists(normalizedSource) || !Files.isRegularFile(normalizedSource) || !Files.isReadable(normalizedSource)) {
            throw new IOException("Authorized local source file is missing or unreadable: " + normalizedSource);
        }
        if (Files.size(normalizedSource) <= 0) {
            throw new IOException("Authorized local source file is empty: " + normalizedSource);
        }
        Path realSource = normalizedSource.toRealPath();

        Path scratchRoot = ensureValidScratchRoot();
        if (realSource.startsWith(scratchRoot)) {
            throw new IllegalArgumentException(
                    "Source file must not reside inside HLS packaging scratchRoot [" + scratchRoot + "]: " + realSource
            );
        }
        ensureSufficientScratchSpace(scratchRoot);

        // Probe and plan before waiting on the heavy encode permit so unsupported inputs fail fast
        HlsLadderPlanner.ProbedVideoSource probed = sourceProbe.probeSource(realSource);
        HlsLadderPlanner.HlsPackagingPlan plan =
                ladderPlanner.plan(probed, segmentDurationSeconds, below720Policy, hdrPolicy);

        try {
            return executionGate.execute(() -> executeGuardedPackaging(realSource, scratchRoot, probed, plan));
        } catch (IOException | InterruptedException | TimeoutException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected HLS packaging engine failure: " + e.getMessage(), e);
        }
    }

    private LocalHlsPackagingResult executeGuardedPackaging(
            Path realSource,
            Path realScratchRoot,
            HlsLadderPlanner.ProbedVideoSource probed,
            HlsLadderPlanner.HlsPackagingPlan plan
    ) throws IOException, InterruptedException, TimeoutException {
        ensureSufficientScratchSpace(realScratchRoot);

        Path attemptDir = Files.createTempDirectory(realScratchRoot, ATTEMPT_DIR_PREFIX).toRealPath();
        if (realSource.startsWith(attemptDir)) {
            deleteAttemptDirectorySafely(attemptDir, realScratchRoot);
            throw new IllegalStateException("Attempt output directory overlaps source file.");
        }

        boolean succeeded = false;
        try {
            FfmpegHlsPackager.PackagingExecution exec = packager.packageToDirectory(realSource, attemptDir, plan);
            LocalHlsPackageVerifier.LocalHlsVerificationReport report = verifier.verify(attemptDir, plan);
            succeeded = true;
            return new LocalHlsPackagingResult(
                    attemptDir,
                    realScratchRoot,
                    probed,
                    plan,
                    exec.command(),
                    exec.elapsedMillis(),
                    report
            );
        } finally {
            if (!succeeded) {
                deleteAttemptDirectorySafely(attemptDir, realScratchRoot);
            }
        }
    }

    private Path ensureValidScratchRoot() throws IOException {
        Path configured = properties.scratchRootDir().toAbsolutePath().normalize();
        HlsPackagingProperties.validateScratchRootOutsideWorktree(configured);
        rejectUnsafeScratchLinkOrJunction(configured);

        Files.createDirectories(configured);
        Path realScratch = configured.toRealPath();
        HlsPackagingProperties.validateScratchRootOutsideWorktree(realScratch);
        if (!Files.isDirectory(realScratch, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(realScratch)) {
            throw new IOException("Scratch root directory is not writable: " + realScratch);
        }
        return realScratch;
    }

    private static void rejectUnsafeScratchLinkOrJunction(Path configured) throws IOException {
        if (!Files.exists(configured, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(configured)) {
            throw new IOException("Scratch root directory must not be a symbolic link: " + configured);
        }
        BasicFileAttributes attrs = Files.readAttributes(configured, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isOther()) {
            Path realConfigured = configured.toRealPath();
            if (!realConfigured.toString().equalsIgnoreCase(configured.toString())) {
                throw new IOException(
                        "Scratch root directory must not be a junction/reparse alias: "
                                + configured + " -> " + realConfigured
                );
            }
        }
    }

    private void ensureSufficientScratchSpace(Path realScratchRoot) throws IOException {
        long minRequired = properties.minFreeScratchBytes();
        if (minRequired <= 0) {
            return;
        }
        FileStore store = Files.getFileStore(realScratchRoot);
        long usable = store.getUsableSpace();
        if (usable < minRequired) {
            throw new IOException(
                    "Insufficient usable scratch disk space in [" + realScratchRoot + "]: available="
                            + usable + " bytes, required=" + minRequired + " bytes."
            );
        }
    }

    private static void deleteAttemptDirectorySafely(Path attemptDir, Path scratchRoot) throws IOException {
        if (attemptDir == null || scratchRoot == null) {
            return;
        }
        Path normAttempt = attemptDir.toAbsolutePath().normalize();
        Path normScratch = scratchRoot.toAbsolutePath().normalize();
        if (normAttempt.equals(normScratch)
                || !normAttempt.startsWith(normScratch)
                || !normAttempt.getFileName().toString().startsWith(ATTEMPT_DIR_PREFIX)
                || (normAttempt.getParent() != null && !normAttempt.getParent().equals(normScratch))
                || Files.isSymbolicLink(normAttempt)) {
            throw new IllegalArgumentException(
                    "Refusing unsafe HLS cleanup outside isolated attempt prefix: attempt="
                            + normAttempt + ", scratch=" + normScratch
            );
        }
        if (!Files.exists(normAttempt, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(normAttempt)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
