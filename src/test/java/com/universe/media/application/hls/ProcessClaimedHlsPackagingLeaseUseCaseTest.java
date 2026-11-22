package com.universe.media.application.hls;

import com.universe.media.application.asset.DeleteMediaAssetCommand;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.RegisterMediaAssetVersionCommand;
import com.universe.media.application.asset.RegisterMediaAssetVersionUseCase;
import com.universe.media.application.exceptions.MediaAssetVersionContentHashMismatchException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.StalePackagingLeaseException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.application.ports.MediaAssetCandidate;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackagePurgeCandidate;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.application.ports.MediaVideoPackagingJobCandidate;
import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
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
import com.universe.media.application.ports.storage.HlsUploadStage;
import com.universe.media.application.ports.storage.HlsUploadedPackageVerification;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.interfaces.MediaAssetReferenceCheckPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ContentHashStatus;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVideoHlsPackage;
import com.universe.media.domain.MediaVideoHlsPackageStatus;
import com.universe.media.domain.MediaVideoPackagingJob;
import com.universe.media.domain.MediaVideoPackagingJobStatus;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.PackagingFailureDisposition;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MS-08 HLS-2B3B1: Offline End-to-End Orchestration & Failure Taxonomy Tests")
class ProcessClaimedHlsPackagingLeaseUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-11-22T09:00:00Z");
    private static final StorageProviderId SOURCE_PROVIDER = StorageProviderId.of("r2-movie");
    private static final StorageProviderId OUTPUT_PROVIDER = HlsPackageKeyValidator.REQUIRED_PROVIDER_ID;
    private static final String OUTPUT_BUCKET = "kiemlai-hls-priv";
    private static final String WORKER_ID = "worker-core-01";
    private static final byte[] SOURCE_BYTES = "verified-source-mp4-binary-payload-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final ContentHash SOURCE_HASH = sha256(SOURCE_BYTES);

    private static ContentHash sha256(byte[] data) {
        try {
            return ContentHash.of(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data)
            ).toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class InMemoryStores {
        final Map<UUID, MediaAsset> assets = new HashMap<>();
        final Map<UUID, MediaAssetVersion> versions = new HashMap<>();
        final Map<UUID, MediaVideoPackagingJob> jobs = new HashMap<>();
        final Map<UUID, MediaVideoHlsPackage> packages = new HashMap<>();
        final AtomicReference<Instant> clock = new AtomicReference<>(T0);

        final ClockPort clockPort = clock::get;

        final MediaAssetRepositoryPort assetRepo = new MediaAssetRepositoryPort() {
            @Override public MediaAsset save(MediaAsset a) { assets.put(a.getId(), a); return a; }
            @Override public Optional<MediaAsset> findById(UUID id) { return Optional.ofNullable(assets.get(id)); }
            @Override public Optional<MediaAsset> findByIdForUpdate(UUID id) { return findById(id); }
            @Override public void deleteById(UUID id) { assets.remove(id); }
            @Override public List<MediaAsset> findExpiredDeleted(Instant c, int l) { return List.of(); }
            @Override public List<MediaAssetCandidate> findActiveByClientTagKeyset(
                    String c, Instant u, Instant l, UUID i, int lim) { return List.of(); }
        };

        final MediaAssetVersionRepositoryPort versionRepo = new MediaAssetVersionRepositoryPort() {
            @Override public MediaAssetVersion save(MediaAssetVersion v) { versions.put(v.getId(), v); return v; }
            @Override public Optional<MediaAssetVersion> findByAssetIdAndVersionNumber(UUID assetId, int ver) {
                return versions.values().stream()
                        .filter(v -> v.getAssetId().equals(assetId) && v.getVersionNumber() == ver)
                        .findFirst();
            }
            @Override public boolean existsByStorageLocation(StorageLocation loc) {
                return versions.values().stream().anyMatch(v -> v.getStorageLocation().equals(loc));
            }
            @Override public List<MediaAssetVersion> findAllByAssetId(UUID assetId) {
                return versions.values().stream().filter(v -> v.getAssetId().equals(assetId)).toList();
            }
            @Override public void deleteByAssetId(UUID assetId) {}
            @Override public List<MediaAssetVersion> findByContentHash(ContentHash h) { return List.of(); }
        };

        final MediaVideoPackagingJobRepositoryPort jobRepo = new MediaVideoPackagingJobRepositoryPort() {
            @Override public MediaVideoPackagingJob save(MediaVideoPackagingJob job) {
                jobs.put(job.getId(), job);
                return job;
            }
            @Override public Optional<MediaVideoPackagingJob> findById(UUID id) { return Optional.ofNullable(jobs.get(id)); }
            @Override public Optional<MediaVideoPackagingJob> findByIdForUpdate(UUID id) { return findById(id); }
            @Override public Optional<MediaVideoPackagingJob> findActiveBySourceVersionIdForUpdate(UUID svId) {
                return jobs.values().stream()
                        .filter(j -> j.getSourceVersionId().equals(svId))
                        .filter(j -> j.getStatus() == MediaVideoPackagingJobStatus.PENDING
                                || j.getStatus() == MediaVideoPackagingJobStatus.LEASED
                                || j.getStatus() == MediaVideoPackagingJobStatus.FAILED)
                        .findFirst();
            }
            @Override public Optional<MediaVideoPackagingJobStatus> findHighestPriorityTerminalStatusBySourceVersionId(UUID svId) {
                return Optional.empty();
            }
            @Override public List<MediaVideoPackagingJob> findByAssetIdForUpdateOrderByIdAsc(UUID assetId) {
                return jobs.values().stream()
                        .filter(j -> j.getAssetId().equals(assetId))
                        .sorted(Comparator.comparing(j -> j.getId().toString()))
                        .toList();
            }
            @Override public List<MediaVideoPackagingJobCandidate> findClaimableCandidates(Instant now, int limit) {
                return jobs.values().stream()
                        .filter(j -> j.getStatus() == MediaVideoPackagingJobStatus.PENDING
                                || (j.getStatus() == MediaVideoPackagingJobStatus.FAILED
                                && !j.getNextAttemptAt().isAfter(now))
                                || (j.getStatus() == MediaVideoPackagingJobStatus.LEASED
                                && j.getLeaseExpiresAt() != null
                                && !j.getLeaseExpiresAt().isAfter(now)))
                        .limit(limit)
                        .map(j -> new MediaVideoPackagingJobCandidate(j.getId(), j.getAssetId()))
                        .toList();
            }
            @Override public int updateHeartbeatIfLeaseMatches(
                    UUID j, String w, long g, Instant n, Instant h, Instant l, Instant u) { return 0; }
            @Override public boolean existsByAssetId(UUID assetId) {
                return jobs.values().stream().anyMatch(j -> j.getAssetId().equals(assetId));
            }
        };

        final MediaVideoHlsPackageRepositoryPort pkgRepo = new MediaVideoHlsPackageRepositoryPort() {
            @Override public MediaVideoHlsPackage save(MediaVideoHlsPackage pkg) {
                packages.put(pkg.getId(), pkg);
                return pkg;
            }
            @Override public Optional<MediaVideoHlsPackage> findById(UUID id) { return Optional.ofNullable(packages.get(id)); }
            @Override public Optional<MediaVideoHlsPackage> findByIdForUpdate(UUID id) { return findById(id); }
            @Override public Optional<MediaVideoHlsPackage> findReadyBySourceVersionId(UUID svId) {
                return packages.values().stream()
                        .filter(p -> p.getSourceVersionId().equals(svId)
                                && p.getStatus() == MediaVideoHlsPackageStatus.READY)
                        .findFirst();
            }
            @Override public Optional<MediaVideoHlsPackage> findCurrentReadyByAssetId(UUID assetId) { return Optional.empty(); }
            @Override public List<MediaVideoHlsPackage> findBySourceVersionIdForUpdateOrderByIdAsc(UUID svId) {
                return packages.values().stream()
                        .filter(p -> p.getSourceVersionId().equals(svId))
                        .sorted(Comparator.comparing(p -> p.getId().toString()))
                        .toList();
            }
            @Override public List<MediaVideoHlsPackage> findByAssetIdForUpdateOrderByIdAsc(UUID assetId) {
                return packages.values().stream()
                        .filter(p -> p.getAssetId().equals(assetId))
                        .sorted(Comparator.comparing(p -> p.getId().toString()))
                        .toList();
            }
            @Override public List<MediaVideoHlsPackage> findByAssetId(UUID assetId) {
                return packages.values().stream().filter(p -> p.getAssetId().equals(assetId)).toList();
            }
            @Override public List<MediaVideoHlsPackagePurgeCandidate> findPurgeEligibleCandidates(Instant n, int l) { return List.of(); }
            @Override public boolean existsByAssetId(UUID assetId) {
                return packages.values().stream().anyMatch(p -> p.getAssetId().equals(assetId));
            }
        };

        final PlatformTransactionManager noOpTxManager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
            @Override protected void doCommit(DefaultTransactionStatus status) {}
            @Override protected void doRollback(DefaultTransactionStatus status) {}
        };

        ClaimMediaVideoPackagingJobUseCase claimUseCase() {
            return new ClaimMediaVideoPackagingJobUseCase(assetRepo, jobRepo, pkgRepo, clockPort, noOpTxManager);
        }

        ResolveMediaVideoSourceSnapshotUseCase resolveUseCase() {
            return new ResolveMediaVideoSourceSnapshotUseCase(assetRepo, versionRepo);
        }

        CompleteVerifiedHlsPackagingUseCase completeUseCase() {
            return new CompleteVerifiedHlsPackagingUseCase(assetRepo, jobRepo, pkgRepo, clockPort);
        }

        FailMediaVideoPackagingJobUseCase failUseCase() {
            return new FailMediaVideoPackagingJobUseCase(assetRepo, jobRepo, pkgRepo, clockPort);
        }

        CancelOrSupersedeMediaVideoPackagingUseCase cancelUseCase() {
            return new CancelOrSupersedeMediaVideoPackagingUseCase(assetRepo, jobRepo, pkgRepo, clockPort);
        }
    }

    private static ClaimedVideoPackagingLease seedClaimedAssetAndLease(InMemoryStores stores, int maxAttempts) {
        UUID assetId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PRIVATE, T0);
        MediaAssetVersion v1 = MediaAssetVersion.create(
                v1Id, assetId, 1,
                new StorageLocation(SOURCE_PROVIDER, StorageKey.of("videos/" + assetId + "/v1.mp4")),
                null, SOURCE_HASH, ContentHashStatus.SERVER_COMPUTED, MimeType.of("video/mp4"),
                (long) SOURCE_BYTES.length, "v1.mp4", T0
        );
        stores.assets.put(assetId, asset);
        stores.versions.put(v1Id, v1);

        MediaVideoPackagingJob job = MediaVideoPackagingJob.createPending(
                UUID.randomUUID(), assetId, v1Id, 1, maxAttempts, T0
        );
        stores.jobs.put(job.getId(), job);

        return stores.claimUseCase()
                .claimNext(WORKER_ID, Duration.ofMinutes(5), OUTPUT_PROVIDER, OUTPUT_BUCKET)
                .orElseThrow();
    }

    private static HlsPackagingLeaseGuard alwaysActiveGuard() {
        return new HlsPackagingLeaseGuard() {
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isLeaseConsideredLive() { return true; }
            @Override public Optional<String> cancellationReason() { return Optional.empty(); }
        };
    }

    private static final class FakeDownloadedSourceScope implements DownloadedSourceScope {
        private final Path sourceFile;
        private final MediaVideoSourceSnapshot snapshot;
        private final ContentHash sha256;
        private final long sizeBytes;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final IOException closeFailure;

        FakeDownloadedSourceScope(
                Path sourceFile,
                MediaVideoSourceSnapshot snapshot,
                ContentHash sha256,
                long sizeBytes,
                IOException closeFailure
        ) {
            this.sourceFile = sourceFile;
            this.snapshot = snapshot;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.closeFailure = closeFailure;
        }

        @Override public Path sourceFile() {
            if (closed.get()) {
                throw new IllegalStateException("DownloadedSourceScope is closed");
            }
            return sourceFile;
        }
        @Override public MediaVideoSourceSnapshot snapshot() { return snapshot; }
        @Override public ContentHash verifiedSha256() { return sha256; }
        @Override public long verifiedSizeBytes() { return sizeBytes; }
        @Override public boolean isClosed() { return closed.get(); }
        @Override public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                Files.deleteIfExists(sourceFile);
                if (closeFailure != null) {
                    throw closeFailure;
                }
            }
        }
    }

    private static final class FakePreparedHlsPackageScope implements PreparedHlsPackageScope {
        private final HlsExpectedArtifactManifest manifest;
        private final HlsArtifactStreamSource streamSource;
        private final int segmentDurationSeconds;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final RuntimeException closeRuntimeFailure;
        private final IOException closeIoFailure;

        FakePreparedHlsPackageScope(
                HlsExpectedArtifactManifest manifest,
                Map<StorageKey, byte[]> bytesByKey,
                int segmentDurationSeconds,
                IOException closeIoFailure,
                RuntimeException closeRuntimeFailure
        ) {
            this.manifest = manifest;
            this.segmentDurationSeconds = segmentDurationSeconds;
            this.closeIoFailure = closeIoFailure;
            this.closeRuntimeFailure = closeRuntimeFailure;
            this.streamSource = new HlsArtifactStreamSource() {
                @Override public ActiveUploadScope openUploadScope() {
                    if (closed.get()) {
                        throw new IllegalStateException("PreparedHlsPackageScope is closed");
                    }
                    return new ActiveUploadScope() {
                        @Override public boolean isCloseRequested() { return closed.get(); }
                        @Override public void close() {}
                    };
                }

                @Override public InputStream openArtifactStream(StorageKey objectKey) {
                    if (closed.get()) {
                        throw new IllegalStateException("PreparedHlsPackageScope is closed");
                    }
                    return new ByteArrayInputStream(bytesByKey.get(objectKey));
                }

                @Override public <T> T withArtifactStream(StorageKey objectKey, ArtifactStreamReader<T> reader)
                        throws IOException {
                    try (InputStream in = openArtifactStream(objectKey)) {
                        byte[] bytes = bytesByKey.get(objectKey);
                        return reader.read(in, bytes.length);
                    }
                }

                @Override public void close() {}
            };
        }

        @Override public HlsExpectedArtifactManifest expectedManifest() { return manifest; }
        @Override public HlsArtifactStreamSource artifactStreamSource() { return streamSource; }
        @Override public int segmentDurationSeconds() { return segmentDurationSeconds; }
        @Override public long totalSizeBytes() { return manifest.totalSizeBytes(); }
        @Override public int objectCount() { return manifest.objectCount(); }
        @Override public boolean isClosed() { return closed.get(); }
        @Override public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                if (closeRuntimeFailure != null) {
                    throw closeRuntimeFailure;
                }
                if (closeIoFailure != null) {
                    throw closeIoFailure;
                }
            }
        }
    }

    private record SyntheticManifestBundle(HlsExpectedArtifactManifest manifest, Map<StorageKey, byte[]> bytesByKey) {
    }

    private static SyntheticManifestBundle buildManifest(
            UUID assetId,
            int versionNum,
            UUID packageUuid,
            StorageProviderId providerId
    ) {
        HlsPackageIdentity id = HlsPackageIdentity.ofCanonical(assetId, versionNum, packageUuid, providerId);
        Map<StorageKey, byte[]> bytesByKey = new HashMap<>();
        List<HlsArtifactDescriptor> list = new ArrayList<>();

        list.add(addArtifact(id, "video/720p/init_0.mp4", HlsArtifactType.INIT_SEGMENT,
                MimeType.of("video/mp4"), HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS, "init0".getBytes(StandardCharsets.UTF_8), bytesByKey));
        list.add(addArtifact(id, "video/720p/seg_00000.m4s", HlsArtifactType.MEDIA_SEGMENT,
                MimeType.of("video/iso.segment"), HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS, "seg0".getBytes(StandardCharsets.UTF_8), bytesByKey));
        list.add(addArtifact(id, "video/720p/playlist.m3u8", HlsArtifactType.MEDIA_PLAYLIST,
                MimeType.of("application/vnd.apple.mpegurl"), HlsUploadStage.STAGE_2_MEDIA_PLAYLISTS, "#EXTM3U\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8), bytesByKey));
        list.add(addArtifact(id, "master.m3u8", HlsArtifactType.MASTER_MANIFEST,
                MimeType.of("application/vnd.apple.mpegurl"), HlsUploadStage.STAGE_3_MASTER_MANIFEST, "#EXTM3U\n#EXT-X-VERSION:7\n".getBytes(StandardCharsets.UTF_8), bytesByKey));

        return new SyntheticManifestBundle(
                new HlsExpectedArtifactManifest(id, list, "[{\"label\":\"720p\"}]"),
                bytesByKey
        );
    }

    private static HlsArtifactDescriptor addArtifact(
            HlsPackageIdentity id,
            String relPath,
            HlsArtifactType type,
            MimeType mime,
            HlsUploadStage stage,
            byte[] content,
            Map<StorageKey, byte[]> map
    ) {
        StorageKey key = HlsPackageKeyValidator.buildAndValidateObjectKey(id.basePrefix(), relPath);
        map.put(key, content);
        return new HlsArtifactDescriptor(relPath, key, content.length, sha256(content), type, mime, stage);
    }

    private static HlsUploadedPackageVerification validVerification(
            String bucket,
            HlsExpectedArtifactManifest manifest,
            Instant verifiedAt
    ) {
        return HlsUploadedPackageVerification.fromRemoteVerification(
                bucket,
                manifest,
                new HlsUploadedPackageVerification.RemoteVerificationEvidence(
                        HlsRemoteIntegrityProof.STREAMED_GET_MANIFESTS_AND_INITS_WITH_EXACT_LISTING,
                        true,
                        true,
                        true,
                        1 + manifest.stage2MediaPlaylists().size() + 1,
                        manifest.masterManifestSha256(),
                        manifest.totalSizeBytes(),
                        manifest.objectCount(),
                        verifiedAt
                )
        );
    }

    private static ProcessClaimedHlsPackagingLeaseUseCase newUseCase(
            InMemoryStores stores,
            MediaVideoSourceDownloadPort downloadPort,
            HlsLocalPackagingPort localPackagingPort,
            HlsPackageArtifactStoragePort storagePort,
            HlsPackagingArtifactLimits limits
    ) {
        return new ProcessClaimedHlsPackagingLeaseUseCase(
                stores.resolveUseCase(),
                downloadPort,
                localPackagingPort,
                storagePort,
                stores.completeUseCase(),
                stores.failUseCase(),
                OUTPUT_PROVIDER,
                OUTPUT_BUCKET,
                limits
        );
    }

    @Test
    @DisplayName("Happy path: full non-transactional pipeline, immediate source close before upload, package close after READY, zero purge calls")
    void happyPathEndToEnd(@TempDir Path tempDir) throws Exception {
        InMemoryStores stores = new InMemoryStores();
        ClaimedVideoPackagingLease lease = seedClaimedAssetAndLease(stores, 3);

        Path downloadedFile = tempDir.resolve("source.mp4");
        AtomicReference<FakeDownloadedSourceScope> sourceScopeRef = new AtomicReference<>();
        AtomicReference<FakePreparedHlsPackageScope> pkgScopeRef = new AtomicReference<>();
        AtomicReference<Path> seenSourceFile = new AtomicReference<>();
        AtomicReference<byte[]> seenPackagedInputBytes = new AtomicReference<>();
        AtomicBoolean sourceClosedBeforeUpload = new AtomicBoolean(false);
        AtomicBoolean packageOpenDuringUpload = new AtomicBoolean(false);
        AtomicInteger purgeCount = new AtomicInteger(0);

        MediaVideoSourceDownloadPort downloadPort = snapshot -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try {
                Files.write(downloadedFile, SOURCE_BYTES);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            FakeDownloadedSourceScope scope = new FakeDownloadedSourceScope(
                    downloadedFile, snapshot, SOURCE_HASH, SOURCE_BYTES.length, null
            );
            sourceScopeRef.set(scope);
            return scope;
        };

        HlsLocalPackagingPort localPackagingPort = cmd -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            seenSourceFile.set(cmd.sourceFile());
            try {
                seenPackagedInputBytes.set(Files.readAllBytes(cmd.sourceFile()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            SyntheticManifestBundle bundle = buildManifest(
                    cmd.assetId(), cmd.sourceVersionNumber(), cmd.packageUuid(), cmd.outputProviderId()
            );
            FakePreparedHlsPackageScope scope = new FakePreparedHlsPackageScope(
                    bundle.manifest(), bundle.bytesByKey(), cmd.segmentDurationSeconds(), null, null
            );
            pkgScopeRef.set(scope);
            return scope;
        };

        HlsPackageArtifactStoragePort storagePort = new HlsPackageArtifactStoragePort() {
            @Override public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand command) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                sourceClosedBeforeUpload.set(sourceScopeRef.get().isClosed());
                packageOpenDuringUpload.set(!pkgScopeRef.get().isClosed());
                return validVerification(command.bucketName(), command.expectedManifest(), T0.plusSeconds(10));
            }
            @Override public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand c) {
                throw new UnsupportedOperationException();
            }
            @Override public void purgePackagePrefix(StorageProviderId p, String b, StorageKey k) {
                purgeCount.incrementAndGet();
            }
        };

        ProcessClaimedHlsPackagingLeaseUseCase useCase = newUseCase(
                stores, downloadPort, localPackagingPort, storagePort, HlsPackagingArtifactLimits.defaults()
        );

        HlsPackagingAttemptOutcome outcome = useCase.process(lease, WORKER_ID, alwaysActiveGuard());

        assertThat(outcome.status()).isEqualTo(HlsPackagingOutcomeStatus.SUCCESS);
        assertThat(outcome.readyPackage().getStatus()).isEqualTo(MediaVideoHlsPackageStatus.READY);
        assertThat(outcome.cleanupFailure()).isNull();
        assertThat(seenSourceFile.get()).isEqualTo(downloadedFile);
        assertThat(seenPackagedInputBytes.get()).isEqualTo(SOURCE_BYTES);
        assertThat(sourceClosedBeforeUpload.get()).isTrue();
        assertThat(Files.exists(downloadedFile)).isFalse();
        assertThat(packageOpenDuringUpload.get()).isTrue();
        assertThat(pkgScopeRef.get().isClosed()).isTrue();
        assertThat(stores.jobs.get(lease.jobId()).getStatus()).isEqualTo(MediaVideoPackagingJobStatus.SUCCEEDED);
        assertThat(purgeCount.get()).isZero();
    }

    @Test
    @DisplayName("Ambient active transaction throws immediately without calling downstream ports")
    void rejectsAmbientActiveTransaction() {
        InMemoryStores stores = new InMemoryStores();
        ClaimedVideoPackagingLease lease = seedClaimedAssetAndLease(stores, 3);
        AtomicInteger portCalls = new AtomicInteger(0);

        ProcessClaimedHlsPackagingLeaseUseCase useCase = newUseCase(
                stores,
                s -> { portCalls.incrementAndGet(); throw new AssertionError("Should not run"); },
                c -> { portCalls.incrementAndGet(); throw new AssertionError("Should not run"); },
                new HlsPackageArtifactStoragePort() {
                    @Override public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand c) {
                        portCalls.incrementAndGet();
                        throw new AssertionError("Should not run");
                    }
                    @Override public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand c) {
                        throw new AssertionError("Should not run");
                    }
                    @Override public void purgePackagePrefix(StorageProviderId p, String b, StorageKey k) {
                        portCalls.incrementAndGet();
                    }
                },
                HlsPackagingArtifactLimits.defaults()
        );

        PlatformTransactionManager activeTxManager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
            @Override protected void doCommit(DefaultTransactionStatus status) {}
            @Override protected void doRollback(DefaultTransactionStatus status) {}
        };
        TransactionTemplate template = new TransactionTemplate(activeTxManager);
        assertThatThrownBy(() -> template.executeWithoutResult(status ->
                useCase.process(lease, WORKER_ID, alwaysActiveGuard())
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strictly outside any active database transaction");
        assertThat(portCalls.get()).isZero();
    }

    @Test
    @DisplayName("Rejects claimed lease with destination provider/bucket/prefix/manifest mismatch as OPERATIONAL_BLOCKED without remote calls")
    void rejectsClaimedLeaseDestinationAndIdentityMismatches() {
        InMemoryStores stores = new InMemoryStores();
        ClaimedVideoPackagingLease base = seedClaimedAssetAndLease(stores, 3);
        AtomicInteger purgeCount = new AtomicInteger(0);

        ProcessClaimedHlsPackagingLeaseUseCase useCase = newUseCase(
                stores,
                s -> { throw new AssertionError("download should not be invoked"); },
                c -> { throw new AssertionError("package should not be invoked"); },
                new HlsPackageArtifactStoragePort() {
                    @Override public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand c) {
                        throw new AssertionError("upload should not be invoked");
                    }
                    @Override public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand c) {
                        throw new AssertionError("verify should not be invoked");
                    }
                    @Override public void purgePackagePrefix(StorageProviderId p, String b, StorageKey k) {
                        purgeCount.incrementAndGet();
                    }
                },
                HlsPackagingArtifactLimits.defaults()
        );

        ClaimedVideoPackagingLease badBucket = new ClaimedVideoPackagingLease(
                base.jobId(), base.assetId(), base.sourceVersionId(), base.sourceVersionNumber(),
                base.packageId(), base.packageUuid(), base.leaseGeneration(), base.attemptNumber(),
                base.storageProviderId(), "wrong-bucket", base.basePrefix(), base.masterManifestKey(),
                base.leaseExpiresAt(), base.segmentDurationSeconds()
        );
        HlsPackagingAttemptOutcome bucketOut = useCase.process(badBucket, WORKER_ID, alwaysActiveGuard());
        assertThat(bucketOut.status()).isEqualTo(HlsPackagingOutcomeStatus.OPERATIONAL_BLOCKED);
        assertThat(bucketOut.updatedJob().getStatus()).isEqualTo(MediaVideoPackagingJobStatus.PENDING);
        assertThat(bucketOut.updatedJob().getNextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
        assertThat(stores.packages.get(base.packageId()).getStatus()).isEqualTo(MediaVideoHlsPackageStatus.FAILED);
        assertThat(purgeCount.get()).isZero();
    }

    @Test
    @DisplayName("Rejects mismatched segment duration, excess artifact count, and single artifact size limit before remote upload")
    void rejectsLocalPackagingBoundaryViolations(@TempDir Path tempDir) throws Exception {
        InMemoryStores stores = new InMemoryStores();
        ClaimedVideoPackagingLease lease = seedClaimedAssetAndLease(stores, 5);
        Path src = Files.write(tempDir.resolve("src.mp4"), SOURCE_BYTES);

        MediaVideoSourceDownloadPort download = snap -> new FakeDownloadedSourceScope(
                src, snap, SOURCE_HASH, SOURCE_BYTES.length, null
        );
        AtomicBoolean uploadCalled = new AtomicBoolean(false);
        HlsPackageArtifactStoragePort storage = new HlsPackageArtifactStoragePort() {
            @Override public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand c) {
                uploadCalled.set(true);
                throw new AssertionError("upload must not run");
            }
            @Override public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand c) {
                throw new AssertionError();
            }
            @Override public void purgePackagePrefix(StorageProviderId p, String b, StorageKey k) {
                throw new AssertionError();
            }
        };

        // Case 1: segment duration mismatch
        AtomicReference<FakePreparedHlsPackageScope> badDurScope = new AtomicReference<>();
        ProcessClaimedHlsPackagingLeaseUseCase uc1 = newUseCase(
                stores,
                download,
                cmd -> {
                    SyntheticManifestBundle b = buildManifest(
                            cmd.assetId(), cmd.sourceVersionNumber(), cmd.packageUuid(), cmd.outputProviderId()
                    );
                    FakePreparedHlsPackageScope s = new FakePreparedHlsPackageScope(
                            b.manifest(), b.bytesByKey(), cmd.segmentDurationSeconds() + 2, null, null
                    );
                    badDurScope.set(s);
                    return s;
                },
                storage,
                HlsPackagingArtifactLimits.defaults()
        );
        HlsPackagingAttemptOutcome out1 = uc1.process(lease, WORKER_ID, alwaysActiveGuard());
        assertThat(out1.status()).isEqualTo(HlsPackagingOutcomeStatus.TRANSIENT_FAILURE);
        assertThat(uploadCalled.get()).isFalse();
        assertThat(badDurScope.get().isClosed()).isTrue();
        assertThat(stores.packages.get(lease.packageId()).getStatus()).isEqualTo(MediaVideoHlsPackageStatus.FAILED);

        // Case 2: single object limit exceeded
        stores.clock.set(T0.plusSeconds(1200));
        ClaimedVideoPackagingLease lease2 = stores.claimUseCase()
                .claimNext(WORKER_ID, Duration.ofMinutes(5), OUTPUT_PROVIDER, OUTPUT_BUCKET)
                .orElseThrow();
        Path src2 = Files.write(tempDir.resolve("src2.mp4"), SOURCE_BYTES);
        ProcessClaimedHlsPackagingLeaseUseCase uc2 = newUseCase(
                stores,
                snap -> new FakeDownloadedSourceScope(src2, snap, SOURCE_HASH, SOURCE_BYTES.length, null),
                cmd -> {
                    SyntheticManifestBundle b = buildManifest(
                            cmd.assetId(), cmd.sourceVersionNumber(), cmd.packageUuid(), cmd.outputProviderId()
                    );
                    return new FakePreparedHlsPackageScope(
                            b.manifest(), b.bytesByKey(), cmd.segmentDurationSeconds(), null, null
                    );
                },
                storage,
                new HlsPackagingArtifactLimits(5000, 10L, Duration.ofMinutes(1), Duration.ofMinutes(15))
        );
        HlsPackagingAttemptOutcome out2 = uc2.process(lease2, WORKER_ID, alwaysActiveGuard());
        assertThat(out2.status()).isEqualTo(HlsPackagingOutcomeStatus.TRANSIENT_FAILURE);
        assertThat(out2.diagnosticMessage()).contains("maxSingleObjectBytes");
        assertThat(uploadCalled.get()).isFalse();
    }

    @Test
    @DisplayName("Rejects remote verification bucket/identity/hash/null failures without persisting READY")
    void rejectsInvalidRemoteVerificationReceipts(@TempDir Path tempDir) throws Exception {
        InMemoryStores stores = new InMemoryStores();
        ClaimedVideoPackagingLease lease = seedClaimedAssetAndLease(stores, 5);
        Path src = Files.write(tempDir.resolve("src.mp4"), SOURCE_BYTES);

        ProcessClaimedHlsPackagingLeaseUseCase nullReceiptUc = newUseCase(
                stores,
                snap -> new FakeDownloadedSourceScope(src, snap, SOURCE_HASH, SOURCE_BYTES.length, null),
                cmd -> {
                    SyntheticManifestBundle b = buildManifest(
                            cmd.assetId(), cmd.sourceVersionNumber(), cmd.packageUuid(), cmd.outputProviderId()
                    );
                    return new FakePreparedHlsPackageScope(
                            b.manifest(), b.bytesByKey(), cmd.segmentDurationSeconds(), null, null
                    );
                },
                new HlsPackageArtifactStoragePort() {
                    @Override public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand c) {
                        return null;
                    }
                    @Override public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand c) {
                        throw new AssertionError();
                    }
                    @Override public void purgePackagePrefix(StorageProviderId p, String b, StorageKey k) {
                        throw new AssertionError("no purge allowed");
                    }
                },
                HlsPackagingArtifactLimits.defaults()
        );

        HlsPackagingAttemptOutcome outcome = nullReceiptUc.process(lease, WORKER_ID, alwaysActiveGuard());
        assertThat(outcome.status()).isEqualTo(HlsPackagingOutcomeStatus.TRANSIENT_FAILURE);
        assertThat(stores.packages.get(lease.packageId()).getStatus()).isEqualTo(MediaVideoHlsPackageStatus.FAILED);
        assertThat(stores.jobs.get(lease.jobId()).getStatus()).isEqualTo(MediaVideoPackagingJobStatus.PENDING);
        assertThat(stores.jobs.get(lease.jobId()).getNextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));

        // Wrong bucket verification result
        stores.clock.set(T0.plusSeconds(1200));
        ClaimedVideoPackagingLease lease2 = stores.claimUseCase()
                .claimNext(WORKER_ID, Duration.ofMinutes(5), OUTPUT_PROVIDER, OUTPUT_BUCKET)
                .orElseThrow();
        Path src2 = Files.write(tempDir.resolve("src2.mp4"), SOURCE_BYTES);
        ProcessClaimedHlsPackagingLeaseUseCase mismatchBucketUc = newUseCase(
                stores,
                snap -> new FakeDownloadedSourceScope(src2, snap, SOURCE_HASH, SOURCE_BYTES.length, null),
                cmd -> {
                    SyntheticManifestBundle b = buildManifest(
                            cmd.assetId(), cmd.sourceVersionNumber(), cmd.packageUuid(), cmd.outputProviderId()
                    );
                    return new FakePreparedHlsPackageScope(
                            b.manifest(), b.bytesByKey(), cmd.segmentDurationSeconds(), null, null
                    );
                },
                new HlsPackageArtifactStoragePort() {
                    @Override public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand c) {
                        return validVerification("other-bucket", c.expectedManifest(), T0.plusSeconds(1210));
                    }
                    @Override public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand c) {
                        throw new AssertionError();
                    }
                    @Override public void purgePackagePrefix(StorageProviderId p, String b, StorageKey k) {
                        throw new AssertionError();
                    }
                },
                HlsPackagingArtifactLimits.defaults()
        );
        HlsPackagingAttemptOutcome outcome2 = mismatchBucketUc.process(lease2, WORKER_ID, alwaysActiveGuard());
        assertThat(outcome2.status()).isEqualTo(HlsPackagingOutcomeStatus.TRANSIENT_FAILURE);
        assertThat(outcome2.diagnosticMessage()).contains("bucket");
    }

    @Test
    @DisplayName("Lease cancellation at every checkpoint (before resolve/download/package/upload/complete) yields STALE_OR_CANCELLED and never marks READY")
    void cancellationAtEveryCheckpoint(@TempDir Path tempDir) throws Exception {
        for (int cancelOnCall = 1; cancelOnCall <= 5; cancelOnCall++) {
            InMemoryStores stores = new InMemoryStores();
            ClaimedVideoPackagingLease lease = seedClaimedAssetAndLease(stores, 3);
            Path src = Files.write(tempDir.resolve("src-" + cancelOnCall + ".mp4"), SOURCE_BYTES);

            final int targetCall = cancelOnCall;
            AtomicInteger checkCount = new AtomicInteger(0);
            AtomicReference<FakeDownloadedSourceScope> dlScope = new AtomicReference<>();
            AtomicReference<FakePreparedHlsPackageScope> pkgScope = new AtomicReference<>();

            HlsPackagingLeaseGuard guard = new HlsPackagingLeaseGuard() {
                @Override public boolean isCancelled() {
                    return checkCount.incrementAndGet() >= targetCall;
                }
                @Override public boolean isLeaseConsideredLive() { return true; }
                @Override public Optional<String> cancellationReason() { return Optional.of("cancelled at checkpoint " + targetCall); }
            };

            ProcessClaimedHlsPackagingLeaseUseCase useCase = newUseCase(
                    stores,
                    snap -> {
                        FakeDownloadedSourceScope s = new FakeDownloadedSourceScope(
                                src, snap, SOURCE_HASH, SOURCE_BYTES.length, null
                        );
                        dlScope.set(s);
                        return s;
                    },
                    cmd -> {
                        SyntheticManifestBundle b = buildManifest(
                                cmd.assetId(), cmd.sourceVersionNumber(), cmd.packageUuid(), cmd.outputProviderId()
                        );
                        FakePreparedHlsPackageScope p = new FakePreparedHlsPackageScope(
                                b.manifest(), b.bytesByKey(), cmd.segmentDurationSeconds(), null, null
                        );
                        pkgScope.set(p);
                        return p;
                    },
                    new HlsPackageArtifactStoragePort() {
                        @Override public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand c) {
                            return validVerification(c.bucketName(), c.expectedManifest(), T0.plusSeconds(10));
                        }
                        @Override public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand c) {
                            throw new AssertionError();
                        }
                        @Override public void purgePackagePrefix(StorageProviderId p, String b, StorageKey k) {
                            throw new AssertionError();
                        }
                    },
                    HlsPackagingArtifactLimits.defaults()
            );

            HlsPackagingAttemptOutcome outcome = useCase.process(lease, WORKER_ID, guard);
            assertThat(outcome.status()).isEqualTo(HlsPackagingOutcomeStatus.STALE_OR_CANCELLED);
            assertThat(stores.packages.get(lease.packageId()).getStatus())
                    .isEqualTo(MediaVideoHlsPackageStatus.PROCESSING);
            if (dlScope.get() != null) {
                assertThat(dlScope.get().isClosed()).isTrue();
            }
            if (pkgScope.get() != null) {
                assertThat(pkgScope.get().isClosed()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("Concurrent asset deletion and source version replacement racing READY completion return STALE_OR_CANCELLED")
    void concurrentDeleteAndSupersessionRaceCompletion(@TempDir Path tempDir) throws Exception {
        for (boolean deleteRace : new boolean[]{true, false}) {
            InMemoryStores stores = new InMemoryStores();
            ClaimedVideoPackagingLease lease = seedClaimedAssetAndLease(stores, 3);
            Path src = Files.write(tempDir.resolve("race-" + deleteRace + ".mp4"), SOURCE_BYTES);

            CountDownLatch beforeComplete = new CountDownLatch(1);
            CountDownLatch resumeWorker = new CountDownLatch(1);

            HlsPackageArtifactStoragePort storagePort = new HlsPackageArtifactStoragePort() {
                @Override public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand c) {
                    beforeComplete.countDown();
                    try {
                        assertThat(resumeWorker.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return validVerification(c.bucketName(), c.expectedManifest(), T0.plusSeconds(15));
                }
                @Override public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand c) {
                    throw new AssertionError();
                }
                @Override public void purgePackagePrefix(StorageProviderId p, String b, StorageKey k) {
                    throw new AssertionError();
                }
            };

            ProcessClaimedHlsPackagingLeaseUseCase useCase = newUseCase(
                    stores,
                    snap -> new FakeDownloadedSourceScope(src, snap, SOURCE_HASH, SOURCE_BYTES.length, null),
                    cmd -> {
                        SyntheticManifestBundle b = buildManifest(
                                cmd.assetId(), cmd.sourceVersionNumber(), cmd.packageUuid(), cmd.outputProviderId()
                        );
                        return new FakePreparedHlsPackageScope(
                                b.manifest(), b.bytesByKey(), cmd.segmentDurationSeconds(), null, null
                        );
                    },
                    storagePort,
                    HlsPackagingArtifactLimits.defaults()
            );

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<HlsPackagingAttemptOutcome> future =
                        pool.submit(() -> useCase.process(lease, WORKER_ID, alwaysActiveGuard()));
                assertThat(beforeComplete.await(5, TimeUnit.SECONDS)).isTrue();

                synchronized (stores) {
                    if (deleteRace) {
                        new DeleteMediaAssetUseCase(
                                stores.assetRepo,
                                stores.clockPort,
                                List.of(new MediaAssetReferenceCheckPort() {
                                    @Override public boolean supports(MediaTypeDTO mediaType) { return true; }
                                    @Override public void ensureNotReferenced(UUID mediaAssetId) {}
                                }),
                                stores.cancelUseCase()
                        ).execute(new DeleteMediaAssetCommand(lease.assetId()));
                    } else {
                        new RegisterMediaAssetVersionUseCase(
                                stores.assetRepo,
                                stores.versionRepo,
                                stores.clockPort,
                                null,
                                null,
                                stores.cancelUseCase()
                        ).execute(new RegisterMediaAssetVersionCommand(
                                lease.assetId(),
                                SOURCE_PROVIDER.value(),
                                "videos/" + lease.assetId() + "/v2.mp4",
                                null,
                                sha256("v2-bytes".getBytes(StandardCharsets.UTF_8)).value(),
                                "video/mp4",
                                8L,
                                "v2.mp4"
                        ));
                    }
                }
                resumeWorker.countDown();

                HlsPackagingAttemptOutcome outcome = future.get(5, TimeUnit.SECONDS);
                assertThat(outcome.status()).isEqualTo(HlsPackagingOutcomeStatus.STALE_OR_CANCELLED);
                assertThat(stores.jobs.get(lease.jobId()).getStatus())
                        .isEqualTo(MediaVideoPackagingJobStatus.CANCELLED);
                assertThat(stores.packages.get(lease.packageId()).getStatus())
                        .isNotEqualTo(MediaVideoHlsPackageStatus.READY);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("Failure taxonomy & backoff: maps transient, permanent source defects, operational blocked, stale fail reporting, and capped exponential backoff")
    void failureTaxonomyAndExponentialBackoff(@TempDir Path tempDir) throws Exception {
        HlsPackagingArtifactLimits limits = new HlsPackagingArtifactLimits(
                5000, 64L * 1024 * 1024, Duration.ofMinutes(1), Duration.ofMinutes(15)
        );
        assertThat(HlsPackagingFailureClassifier.computeExponentialBackoff(1, limits.baseRetryBackoff(), limits.maxRetryBackoff()))
                .isEqualTo(Duration.ofMinutes(1));
        assertThat(HlsPackagingFailureClassifier.computeExponentialBackoff(2, limits.baseRetryBackoff(), limits.maxRetryBackoff()))
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(HlsPackagingFailureClassifier.computeExponentialBackoff(4, limits.baseRetryBackoff(), limits.maxRetryBackoff()))
                .isEqualTo(Duration.ofMinutes(8));
        assertThat(HlsPackagingFailureClassifier.computeExponentialBackoff(10, limits.baseRetryBackoff(), limits.maxRetryBackoff()))
                .isEqualTo(Duration.ofMinutes(15));

        record FailureScenario(RuntimeException error, HlsPackagingOutcomeStatus expectedStatus, PackagingFailureDisposition expectedDisposition) {
        }
        List<FailureScenario> scenarios = List.of(
                new FailureScenario(new RuntimeException("S3 timeout", new IOException("connection reset")),
                        HlsPackagingOutcomeStatus.TRANSIENT_FAILURE, PackagingFailureDisposition.TRANSIENT_RETRYABLE),
                new FailureScenario(new HlsLocalPackagingException("ffmpeg exited 1"),
                        HlsPackagingOutcomeStatus.TRANSIENT_FAILURE, PackagingFailureDisposition.TRANSIENT_RETRYABLE),
                new FailureScenario(new HlsPackagingOperationalException("Cannot run program \"ffmpeg\""),
                        HlsPackagingOutcomeStatus.OPERATIONAL_BLOCKED, PackagingFailureDisposition.TRANSIENT_RETRYABLE),
                new FailureScenario(new UnsupportedHlsSourceMediaException("HDR10 unsupported"),
                        HlsPackagingOutcomeStatus.PERMANENT_FAILURE, PackagingFailureDisposition.PERMANENT_NON_RETRYABLE),
                new FailureScenario(new UploadContentMimeMismatchException("bad mime"),
                        HlsPackagingOutcomeStatus.PERMANENT_FAILURE, PackagingFailureDisposition.PERMANENT_NON_RETRYABLE),
                new FailureScenario(new MediaAssetVersionContentHashMismatchException(UUID.randomUUID(), 1),
                        HlsPackagingOutcomeStatus.PERMANENT_FAILURE, PackagingFailureDisposition.PERMANENT_NON_RETRYABLE),
                new FailureScenario(new StorageObjectNotFoundException(StorageKey.of("k")),
                        HlsPackagingOutcomeStatus.PERMANENT_FAILURE, PackagingFailureDisposition.PERMANENT_NON_RETRYABLE),
                new FailureScenario(new MediaAssetVersionNotFoundException(UUID.randomUUID(), 1),
                        HlsPackagingOutcomeStatus.PERMANENT_FAILURE, PackagingFailureDisposition.PERMANENT_NON_RETRYABLE)
        );

        for (int i = 0; i < scenarios.size(); i++) {
            final int scenarioIndex = i;
            FailureScenario scenario = scenarios.get(i);
            InMemoryStores stores = new InMemoryStores();
            ClaimedVideoPackagingLease lease = seedClaimedAssetAndLease(stores, 3);
            Path src = Files.write(tempDir.resolve("tax-" + i + ".mp4"), SOURCE_BYTES);

            ProcessClaimedHlsPackagingLeaseUseCase useCase = newUseCase(
                    stores,
                    snap -> {
                        if (scenarioIndex % 2 == 0) {
                            throw scenario.error();
                        }
                        return new FakeDownloadedSourceScope(src, snap, SOURCE_HASH, SOURCE_BYTES.length, null);
                    },
                    cmd -> {
                        throw scenario.error();
                    },
                    new HlsPackageArtifactStoragePort() {
                        @Override public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand c) {
                            throw new AssertionError();
                        }
                        @Override public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand c) {
                            throw new AssertionError();
                        }
                        @Override public void purgePackagePrefix(StorageProviderId p, String b, StorageKey k) {
                            throw new AssertionError();
                        }
                    },
                    limits
            );

            HlsPackagingAttemptOutcome outcome = useCase.process(lease, WORKER_ID, alwaysActiveGuard());
            assertThat(outcome.status()).isEqualTo(scenario.expectedStatus());
            assertThat(stores.packages.get(lease.packageId()).getStatus()).isEqualTo(MediaVideoHlsPackageStatus.FAILED);
            if (scenario.expectedDisposition() == PackagingFailureDisposition.PERMANENT_NON_RETRYABLE) {
                assertThat(outcome.updatedJob().getStatus()).isEqualTo(MediaVideoPackagingJobStatus.FAILED);
                assertThat(outcome.updatedJob().getAttemptCount()).isEqualTo(lease.attemptNumber());
            } else {
                assertThat(outcome.updatedJob().getStatus()).isEqualTo(MediaVideoPackagingJobStatus.PENDING);
                assertThat(outcome.updatedJob().getNextAttemptAt()).isAfter(T0);
            }
        }

        // Stale lease rejection when FailMediaVideoPackagingJobUseCase executes after expiry
        InMemoryStores expiredStores = new InMemoryStores();
        ClaimedVideoPackagingLease expiredLease = seedClaimedAssetAndLease(expiredStores, 3);
        expiredStores.clock.set(T0.plus(Duration.ofMinutes(10))); // past 5-minute lease expiry
        ProcessClaimedHlsPackagingLeaseUseCase expiredUc = newUseCase(
                expiredStores,
                snap -> { throw new RuntimeException("transient io"); },
                cmd -> { throw new AssertionError(); },
                new HlsPackageArtifactStoragePort() {
                    @Override public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand c) {
                        throw new AssertionError();
                    }
                    @Override public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand c) {
                        throw new AssertionError();
                    }
                    @Override public void purgePackagePrefix(StorageProviderId p, String b, StorageKey k) {
                        throw new AssertionError();
                    }
                },
                limits
        );
        HlsPackagingAttemptOutcome staleFailOutcome = expiredUc.process(expiredLease, WORKER_ID, alwaysActiveGuard());
        assertThat(staleFailOutcome.status()).isEqualTo(HlsPackagingOutcomeStatus.STALE_OR_CANCELLED);
    }

    @Test
    @DisplayName("Post-READY cleanup failure preserves SUCCESS outcome and captures cleanup diagnostics")
    void postReadyCleanupFailurePreservesSuccessOutcome(@TempDir Path tempDir) throws Exception {
        InMemoryStores stores = new InMemoryStores();
        ClaimedVideoPackagingLease lease = seedClaimedAssetAndLease(stores, 3);
        Path src = Files.write(tempDir.resolve("src-cleanup.mp4"), SOURCE_BYTES);

        ProcessClaimedHlsPackagingLeaseUseCase useCase = newUseCase(
                stores,
                snap -> new FakeDownloadedSourceScope(
                        src, snap, SOURCE_HASH, SOURCE_BYTES.length, new IOException("source close IO warning")
                ),
                cmd -> {
                    SyntheticManifestBundle b = buildManifest(
                            cmd.assetId(), cmd.sourceVersionNumber(), cmd.packageUuid(), cmd.outputProviderId()
                    );
                    return new FakePreparedHlsPackageScope(
                            b.manifest(),
                            b.bytesByKey(),
                            cmd.segmentDurationSeconds(),
                            new IOException("pkg scratch locked"),
                            null
                    );
                },
                new HlsPackageArtifactStoragePort() {
                    @Override public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand c) {
                        return validVerification(c.bucketName(), c.expectedManifest(), T0.plusSeconds(12));
                    }
                    @Override public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand c) {
                        throw new AssertionError();
                    }
                    @Override public void purgePackagePrefix(StorageProviderId p, String b, StorageKey k) {
                        throw new AssertionError("never purge");
                    }
                },
                HlsPackagingArtifactLimits.defaults()
        );

        HlsPackagingAttemptOutcome outcome = useCase.process(lease, WORKER_ID, alwaysActiveGuard());
        assertThat(outcome.status()).isEqualTo(HlsPackagingOutcomeStatus.SUCCESS);
        assertThat(outcome.readyPackage().getStatus()).isEqualTo(MediaVideoHlsPackageStatus.READY);
        assertThat(outcome.cleanupFailure()).isNotNull();
        assertThat(outcome.cleanupFailure().getMessage()).contains("source close IO warning");
        assertThat(outcome.cleanupFailure().getSuppressed()).hasSize(1);
    }
}
