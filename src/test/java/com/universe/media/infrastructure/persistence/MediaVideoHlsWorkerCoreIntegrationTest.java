package com.universe.media.infrastructure.persistence;

import com.universe.media.application.asset.DeleteMediaAssetCommand;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.RegisterMediaAssetVersionCommand;
import com.universe.media.application.asset.RegisterMediaAssetVersionUseCase;
import com.universe.media.application.hls.CancelOrSupersedeMediaVideoPackagingUseCase;
import com.universe.media.application.hls.ClaimMediaVideoPackagingJobUseCase;
import com.universe.media.application.hls.ClaimedVideoPackagingLease;
import com.universe.media.application.hls.CompleteVerifiedHlsPackagingUseCase;
import com.universe.media.application.hls.CreateMediaVideoPackagingJobUseCase;
import com.universe.media.application.hls.DownloadedSourceScope;
import com.universe.media.application.hls.FailMediaVideoPackagingJobUseCase;
import com.universe.media.application.hls.HlsAutoEnqueueProperties;
import com.universe.media.application.hls.HlsLocalPackagingPort;
import com.universe.media.application.hls.HlsPackagingArtifactLimits;
import com.universe.media.application.hls.HlsPackagingAttemptOutcome;
import com.universe.media.application.hls.HlsPackagingLeaseGuard;
import com.universe.media.application.hls.HlsPackagingOutcomeStatus;
import com.universe.media.application.hls.MediaVideoSourceDownloadPort;
import com.universe.media.application.hls.MediaVideoSourceSnapshot;
import com.universe.media.application.hls.OptInHlsPackagingEnqueueCoordinator;
import com.universe.media.application.hls.PrepareLocalHlsPackageCommand;
import com.universe.media.application.hls.PreparedHlsPackageScope;
import com.universe.media.application.hls.ProcessClaimedHlsPackagingLeaseUseCase;
import com.universe.media.application.hls.ResolveMediaVideoSourceSnapshotUseCase;
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
import com.universe.media.application.storage.MediaStorageCutoverBarrier;
import com.universe.media.application.storage.StorageKeyAllocationService;
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
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        MediaAssetPersistenceAdapter.class,
        MediaAssetVersionPersistenceAdapter.class,
        MediaImageVariantPersistenceAdapter.class,
        MediaVideoHlsPackagePersistenceAdapter.class,
        MediaVideoPackagingJobPersistenceAdapter.class,
        CreateMediaVideoPackagingJobUseCase.class,
        OptInHlsPackagingEnqueueCoordinator.class,
        ClaimMediaVideoPackagingJobUseCase.class,
        FailMediaVideoPackagingJobUseCase.class,
        CompleteVerifiedHlsPackagingUseCase.class,
        CancelOrSupersedeMediaVideoPackagingUseCase.class,
        ResolveMediaVideoSourceSnapshotUseCase.class,
        RegisterMediaAssetVersionUseCase.class,
        DeleteMediaAssetUseCase.class,
        MediaVideoHlsWorkerCoreIntegrationTest.TestConfig.class
})
@DisplayName("MS-08 HLS-2B3B1: Isolated MySQL Worker Core End-to-End & Fencing Integration Tests")
class MediaVideoHlsWorkerCoreIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-11-22T10:00:00Z");
    private static final StorageProviderId SOURCE_PROVIDER = StorageProviderId.of("r2-movie");
    private static final StorageProviderId OUTPUT_PROVIDER = HlsPackageKeyValidator.REQUIRED_PROVIDER_ID;
    private static final String OUTPUT_BUCKET = "kiemlai-hls-priv";
    private static final byte[] SOURCE_BYTES = "mysql-it-video-source-mp4-bytes".getBytes(StandardCharsets.UTF_8);
    private static final ContentHash SOURCE_SHA256 = sha256(SOURCE_BYTES);

    @FunctionalInterface
    interface PortStageInterceptor {
        void onStage(String stageName, HlsPackageUploadCommand command);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public AtomicReference<Instant> clockRef() {
            return new AtomicReference<>(BASE_TIME);
        }

        @Bean
        public ClockPort clockPort(AtomicReference<Instant> clockRef) {
            return clockRef::get;
        }

        @Bean
        public MediaAssetReferenceCheckPort mediaAssetReferenceCheckPort() {
            return new MediaAssetReferenceCheckPort() {
                @Override public boolean supports(MediaTypeDTO mediaType) { return true; }
                @Override public void ensureNotReferenced(UUID mediaAssetId) {}
            };
        }

        @Bean
        public StorageKeyAllocationService storageKeyAllocationService() {
            return Mockito.mock(StorageKeyAllocationService.class);
        }

        @Bean
        public MediaStorageCutoverBarrier mediaStorageCutoverBarrier() {
            MediaStorageCutoverBarrier barrier = Mockito.mock(MediaStorageCutoverBarrier.class);
            Mockito.when(barrier.isCutoverActive()).thenReturn(false);
            return barrier;
        }

        @Bean
        public HlsAutoEnqueueProperties hlsAutoEnqueueProperties() {
            return new HlsAutoEnqueueProperties(false, false);
        }

        @Bean
        public AtomicReference<PortStageInterceptor> stageInterceptorRef() {
            return new AtomicReference<>((stage, cmd) -> {});
        }

        @Bean
        public AtomicReference<Path> tempRootRef() {
            return new AtomicReference<>();
        }

        @Bean
        public List<Boolean> txActiveDuringExternalStages() {
            return new ArrayList<>();
        }

        @Bean
        public MediaVideoSourceDownloadPort fakeDownloadPort(
                AtomicReference<Path> tempRootRef,
                AtomicReference<PortStageInterceptor> interceptorRef,
                List<Boolean> txFlags
        ) {
            return snapshot -> {
                txFlags.add(TransactionSynchronizationManager.isActualTransactionActive());
                interceptorRef.get().onStage("download", null);
                try {
                    Path dir = tempRootRef.get();
                    Files.createDirectories(dir);
                    Path srcFile = Files.write(dir.resolve("src-" + UUID.randomUUID() + ".mp4"), SOURCE_BYTES);
                    return new DownloadedSourceScope() {
                        private boolean closed = false;
                        @Override public Path sourceFile() { return srcFile; }
                        @Override public MediaVideoSourceSnapshot snapshot() { return snapshot; }
                        @Override public ContentHash verifiedSha256() { return SOURCE_SHA256; }
                        @Override public long verifiedSizeBytes() { return SOURCE_BYTES.length; }
                        @Override public boolean isClosed() { return closed; }
                        @Override public void close() throws IOException {
                            if (!closed) {
                                closed = true;
                                Files.deleteIfExists(srcFile);
                            }
                        }
                    };
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
        }

        @Bean
        public HlsLocalPackagingPort fakeLocalPackagingPort(
                AtomicReference<PortStageInterceptor> interceptorRef,
                List<Boolean> txFlags
        ) {
            return command -> {
                txFlags.add(TransactionSynchronizationManager.isActualTransactionActive());
                interceptorRef.get().onStage("local-package", null);
                HlsPackageIdentity id = HlsPackageIdentity.ofCanonical(
                        command.assetId(), command.sourceVersionNumber(), command.packageUuid(), command.outputProviderId()
                );
                Map<StorageKey, byte[]> map = new HashMap<>();
                List<HlsArtifactDescriptor> artifacts = List.of(
                        artifact(id, "video/720p/init_0.mp4", HlsArtifactType.INIT_SEGMENT,
                                MimeType.of("video/mp4"), HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS, "init".getBytes(StandardCharsets.UTF_8), map),
                        artifact(id, "video/720p/seg_00000.m4s", HlsArtifactType.MEDIA_SEGMENT,
                                MimeType.of("video/iso.segment"), HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS, "seg".getBytes(StandardCharsets.UTF_8), map),
                        artifact(id, "video/720p/playlist.m3u8", HlsArtifactType.MEDIA_PLAYLIST,
                                MimeType.of("application/vnd.apple.mpegurl"), HlsUploadStage.STAGE_2_MEDIA_PLAYLISTS, "#EXTM3U\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8), map),
                        artifact(id, "master.m3u8", HlsArtifactType.MASTER_MANIFEST,
                                MimeType.of("application/vnd.apple.mpegurl"), HlsUploadStage.STAGE_3_MASTER_MANIFEST, "#EXTM3U\n#EXT-X-VERSION:7\n".getBytes(StandardCharsets.UTF_8), map)
                );
                HlsExpectedArtifactManifest manifest =
                        new HlsExpectedArtifactManifest(id, artifacts, "[{\"label\":\"720p\"}]");
                return new PreparedHlsPackageScope() {
                    private final AtomicBoolean closed = new AtomicBoolean(false);
                    private final HlsArtifactStreamSource source = new HlsArtifactStreamSource() {
                        @Override public ActiveUploadScope openUploadScope() {
                            return new ActiveUploadScope() {
                                @Override public boolean isCloseRequested() { return closed.get(); }
                                @Override public void close() {}
                            };
                        }
                        @Override public InputStream openArtifactStream(StorageKey key) {
                            return new ByteArrayInputStream(map.get(key));
                        }
                        @Override public <T> T withArtifactStream(StorageKey key, ArtifactStreamReader<T> reader) throws IOException {
                            byte[] b = map.get(key);
                            return reader.read(new ByteArrayInputStream(b), b.length);
                        }
                        @Override public void close() {}
                    };
                    @Override public HlsExpectedArtifactManifest expectedManifest() { return manifest; }
                    @Override public HlsArtifactStreamSource artifactStreamSource() { return source; }
                    @Override public int segmentDurationSeconds() { return command.segmentDurationSeconds(); }
                    @Override public long totalSizeBytes() { return manifest.totalSizeBytes(); }
                    @Override public int objectCount() { return manifest.objectCount(); }
                    @Override public boolean isClosed() { return closed.get(); }
                    @Override public void close() { closed.set(true); }
                };
            };
        }

        @Bean
        public HlsPackageArtifactStoragePort fakeStoragePort(
                AtomicReference<Instant> clockRef,
                AtomicReference<PortStageInterceptor> interceptorRef,
                List<Boolean> txFlags
        ) {
            return new HlsPackageArtifactStoragePort() {
                @Override public HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand command) {
                    txFlags.add(TransactionSynchronizationManager.isActualTransactionActive());
                    interceptorRef.get().onStage("upload-verify", command);
                    HlsExpectedArtifactManifest m = command.expectedManifest();
                    return HlsUploadedPackageVerification.fromRemoteVerification(
                            command.bucketName(),
                            m,
                            new HlsUploadedPackageVerification.RemoteVerificationEvidence(
                                    HlsRemoteIntegrityProof.STREAMED_GET_MANIFESTS_AND_INITS_WITH_EXACT_LISTING,
                                    true,
                                    true,
                                    true,
                                    1 + m.stage2MediaPlaylists().size() + 1,
                                    m.masterManifestSha256(),
                                    m.totalSizeBytes(),
                                    m.objectCount(),
                                    clockRef.get().plusSeconds(5)
                            )
                    );
                }
                @Override public HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand c) {
                    throw new AssertionError("Should not verify remotely directly");
                }
                @Override public void purgePackagePrefix(StorageProviderId p, String b, StorageKey k) {
                    throw new AssertionError("purgePackagePrefix must never be called by B1 worker core");
                }
            };
        }

        @Bean
        public ProcessClaimedHlsPackagingLeaseUseCase workerCoreUseCase(
                ResolveMediaVideoSourceSnapshotUseCase resolveUseCase,
                MediaVideoSourceDownloadPort downloadPort,
                HlsLocalPackagingPort localPackagingPort,
                HlsPackageArtifactStoragePort storagePort,
                CompleteVerifiedHlsPackagingUseCase completeUseCase,
                FailMediaVideoPackagingJobUseCase failUseCase
        ) {
            return new ProcessClaimedHlsPackagingLeaseUseCase(
                    resolveUseCase,
                    downloadPort,
                    localPackagingPort,
                    storagePort,
                    completeUseCase,
                    failUseCase,
                    OUTPUT_PROVIDER,
                    OUTPUT_BUCKET,
                    HlsPackagingArtifactLimits.defaults()
            );
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AtomicReference<Instant> clockRef;
    @Autowired private AtomicReference<PortStageInterceptor> stageInterceptorRef;
    @Autowired private AtomicReference<Path> tempRootRef;
    @Autowired private List<Boolean> txFlags;

    @Autowired private MediaAssetPersistenceAdapter assetAdapter;
    @Autowired private MediaAssetVersionPersistenceAdapter versionAdapter;
    @Autowired private MediaVideoPackagingJobPersistenceAdapter jobAdapter;
    @Autowired private MediaVideoHlsPackagePersistenceAdapter pkgAdapter;

    @Autowired private CreateMediaVideoPackagingJobUseCase createJobUseCase;
    @Autowired private ClaimMediaVideoPackagingJobUseCase claimUseCase;
    @Autowired private CompleteVerifiedHlsPackagingUseCase completeUseCase;
    @Autowired private FailMediaVideoPackagingJobUseCase failUseCase;
    @Autowired private DeleteMediaAssetUseCase deleteAssetUseCase;
    @Autowired private RegisterMediaAssetVersionUseCase registerVersionUseCase;
    @Autowired private ProcessClaimedHlsPackagingLeaseUseCase workerCoreUseCase;

    private final List<UUID> createdAssetIds = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        clockRef.set(BASE_TIME);
        stageInterceptorRef.set((stage, cmd) -> {});
        tempRootRef.set(tempDir);
        txFlags.clear();
        createdAssetIds.clear();
        jdbcTemplate.update("DELETE FROM media_video_packaging_jobs");
        jdbcTemplate.update("DELETE FROM media_video_hls_packages");
    }

    @AfterEach
    void tearDown() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            for (UUID assetId : createdAssetIds) {
                String id = assetId.toString();
                jdbcTemplate.update("DELETE FROM media_video_packaging_jobs WHERE asset_id = ?", id);
                jdbcTemplate.update("DELETE FROM media_video_hls_packages WHERE asset_id = ?", id);
                jdbcTemplate.update("DELETE FROM media_asset_versions WHERE asset_id = ?", id);
                jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", id);
            }
        });
    }

    private ClaimedVideoPackagingLease seedVideoAndClaimLease() {
        UUID assetId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        createdAssetIds.add(assetId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PRIVATE, BASE_TIME);
            assetAdapter.save(asset);
            MediaAssetVersion v1 = MediaAssetVersion.create(
                    v1Id, assetId, 1,
                    new StorageLocation(SOURCE_PROVIDER, StorageKey.of("videos/" + assetId + "/v1.mp4")),
                    null, SOURCE_SHA256, ContentHashStatus.SERVER_COMPUTED, MimeType.of("video/mp4"),
                    (long) SOURCE_BYTES.length, "v1.mp4", BASE_TIME
            );
            versionAdapter.save(v1);
        });
        createJobUseCase.execute(assetId, 3);
        return claimUseCase.claimNext("it-worker-1", Duration.ofMinutes(5), OUTPUT_PROVIDER, OUTPUT_BUCKET).orElseThrow();
    }

    private static HlsPackagingLeaseGuard activeGuard() {
        return new HlsPackagingLeaseGuard() {
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isLeaseConsideredLive() { return true; }
            @Override public Optional<String> cancellationReason() { return Optional.empty(); }
        };
    }

    @Test
    @DisplayName("Full MySQL roundtrip: real Spring AOP REQUIRES_NEW completion, zero tx during external stages, and immutable prefix/UUID")
    void fullMysqlRoundtripWithNonTxExternalStages() {
        assertThat(AopUtils.isAopProxy(completeUseCase)).isTrue();
        assertThat(AopUtils.isAopProxy(failUseCase)).isTrue();

        ClaimedVideoPackagingLease lease = seedVideoAndClaimLease();
        MediaVideoHlsPackage beforePkg = pkgAdapter.findById(lease.packageId()).orElseThrow();

        HlsPackagingAttemptOutcome outcome = workerCoreUseCase.process(lease, "it-worker-1", activeGuard());

        assertThat(outcome.status()).isEqualTo(HlsPackagingOutcomeStatus.SUCCESS);
        assertThat(txFlags).containsExactly(false, false, false);

        MediaVideoPackagingJob persistedJob = jobAdapter.findById(lease.jobId()).orElseThrow();
        MediaVideoHlsPackage persistedPkg = pkgAdapter.findById(lease.packageId()).orElseThrow();
        assertThat(persistedJob.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.SUCCEEDED);
        assertThat(persistedPkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.READY);
        assertThat(persistedPkg.getBasePrefix()).isEqualTo(beforePkg.getBasePrefix());
        assertThat(persistedPkg.getPackageUuid()).isEqualTo(beforePkg.getPackageUuid());
    }

    @Test
    @DisplayName("MySQL integration: upload/remote verification failure transitions attempt cleanly to FAILED with zero READY row")
    void uploadFailurePersistsFailedStateAndZeroReady() {
        ClaimedVideoPackagingLease lease = seedVideoAndClaimLease();
        stageInterceptorRef.set((stage, cmd) -> {
            if ("upload-verify".equals(stage)) {
                throw new RuntimeException("simulated R2 remote verification mismatch");
            }
        });

        HlsPackagingAttemptOutcome outcome = workerCoreUseCase.process(lease, "it-worker-1", activeGuard());

        assertThat(outcome.status()).isEqualTo(HlsPackagingOutcomeStatus.TRANSIENT_FAILURE);
        MediaVideoPackagingJob failedAttemptJob = jobAdapter.findById(lease.jobId()).orElseThrow();
        assertThat(failedAttemptJob.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.PENDING);
        assertThat(failedAttemptJob.getNextAttemptAt()).isEqualTo(BASE_TIME.plus(Duration.ofMinutes(1)));
        assertThat(pkgAdapter.findById(lease.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.FAILED);
        assertThat(pkgAdapter.findReadyBySourceVersionId(lease.sourceVersionId())).isEmpty();
    }

    @Test
    @DisplayName("MySQL integration: concurrent source replacement & deletion racing completion return STALE_OR_CANCELLED with zero READY")
    void concurrentReplacementAndDeletionRaceCompletion() throws Exception {
        for (boolean deleteAsset : new boolean[]{false, true}) {
            ClaimedVideoPackagingLease lease = seedVideoAndClaimLease();
            CountDownLatch pausedBeforeComplete = new CountDownLatch(1);
            CountDownLatch allowComplete = new CountDownLatch(1);

            stageInterceptorRef.set((stage, cmd) -> {
                if ("upload-verify".equals(stage)) {
                    pausedBeforeComplete.countDown();
                    try {
                        assertThat(allowComplete.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            });

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<HlsPackagingAttemptOutcome> future =
                        pool.submit(() -> workerCoreUseCase.process(lease, "it-worker-1", activeGuard()));
                assertThat(pausedBeforeComplete.await(10, TimeUnit.SECONDS)).isTrue();

                if (deleteAsset) {
                    deleteAssetUseCase.execute(new DeleteMediaAssetCommand(lease.assetId()));
                } else {
                    registerVersionUseCase.execute(new RegisterMediaAssetVersionCommand(
                            lease.assetId(),
                            SOURCE_PROVIDER.value(),
                            "videos/" + lease.assetId() + "/v2.mp4",
                            null,
                            sha256("v2-mp4".getBytes(StandardCharsets.UTF_8)).value(),
                            "video/mp4",
                            6L,
                            "v2.mp4"
                    ));
                }
                allowComplete.countDown();

                HlsPackagingAttemptOutcome outcome = future.get(10, TimeUnit.SECONDS);
                assertThat(outcome.status()).isEqualTo(HlsPackagingOutcomeStatus.STALE_OR_CANCELLED);
                assertThat(jobAdapter.findById(lease.jobId()).orElseThrow().getStatus())
                        .isEqualTo(MediaVideoPackagingJobStatus.CANCELLED);
                assertThat(pkgAdapter.findById(lease.packageId()).orElseThrow().getStatus())
                        .isNotEqualTo(MediaVideoHlsPackageStatus.READY);
                assertThat(pkgAdapter.findReadyBySourceVersionId(lease.sourceVersionId())).isEmpty();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("MySQL integration: stale expired lease failure reporting throws StalePackagingLeaseException and returns STALE_OR_CANCELLED")
    void staleLeaseFailureReturnsStaleOrCancelledWithoutMutatingDb() {
        ClaimedVideoPackagingLease lease = seedVideoAndClaimLease();
        stageInterceptorRef.set((stage, cmd) -> {
            if ("upload-verify".equals(stage)) {
                clockRef.set(BASE_TIME.plus(Duration.ofMinutes(10))); // surpass 5m lease expiry
                throw new RuntimeException("transient S3 error after lease expired");
            }
        });

        HlsPackagingAttemptOutcome outcome = workerCoreUseCase.process(lease, "it-worker-1", activeGuard());

        assertThat(outcome.status()).isEqualTo(HlsPackagingOutcomeStatus.STALE_OR_CANCELLED);
        assertThat(jobAdapter.findById(lease.jobId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoPackagingJobStatus.LEASED);
        assertThat(pkgAdapter.findById(lease.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.PROCESSING);
    }

    private static HlsArtifactDescriptor artifact(
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

    private static ContentHash sha256(byte[] data) {
        try {
            return ContentHash.of(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data)
            ).toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
