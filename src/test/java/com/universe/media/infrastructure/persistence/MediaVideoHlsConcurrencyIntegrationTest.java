package com.universe.media.infrastructure.persistence;

import com.universe.media.application.asset.ArchiveMediaAssetCommand;
import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.DeleteMediaAssetCommand;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.PurgeMediaAssetMetadataService;
import com.universe.media.application.asset.RegisterMediaAssetVersionCommand;
import com.universe.media.application.asset.RegisterMediaAssetVersionResult;
import com.universe.media.application.asset.RegisterMediaAssetVersionUseCase;
import com.universe.media.application.asset.RestoreMediaAssetCommand;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.exceptions.StalePackagingLeaseException;
import com.universe.media.application.storage.MediaStorageCutoverBarrier;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.interfaces.MediaAssetReferenceCheckPort;
import com.universe.media.application.exceptions.StalePurgeClaimException;
import com.universe.media.application.hls.AutoPackagingEnqueueResult;
import com.universe.media.application.hls.AutoPackagingEnqueueStatus;
import com.universe.media.application.hls.CancelOrSupersedeMediaVideoPackagingUseCase;
import com.universe.media.application.hls.ClaimHlsPackagePurgeUseCase;
import com.universe.media.application.hls.ClaimMediaVideoPackagingJobUseCase;
import com.universe.media.application.hls.ClaimedHlsPackagePurgeLease;
import com.universe.media.application.hls.ClaimedVideoPackagingLease;
import com.universe.media.application.hls.CompleteHlsPackagePurgeUseCase;
import com.universe.media.application.hls.CompleteVerifiedHlsPackagingUseCase;
import com.universe.media.application.hls.CreateMediaVideoPackagingJobUseCase;
import com.universe.media.application.hls.FailMediaVideoPackagingJobUseCase;
import com.universe.media.application.hls.HeartbeatMediaVideoPackagingJobUseCase;
import com.universe.media.application.hls.HlsAutoEnqueueProperties;
import com.universe.media.application.hls.OptInHlsPackagingEnqueueCoordinator;
import com.universe.media.application.hls.VerifiedHlsPackageReceipt;
import com.universe.media.domain.ContentHash;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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
        PurgeMediaAssetMetadataService.class,
        CreateMediaVideoPackagingJobUseCase.class,
        OptInHlsPackagingEnqueueCoordinator.class,
        ClaimMediaVideoPackagingJobUseCase.class,
        HeartbeatMediaVideoPackagingJobUseCase.class,
        FailMediaVideoPackagingJobUseCase.class,
        CompleteVerifiedHlsPackagingUseCase.class,
        CancelOrSupersedeMediaVideoPackagingUseCase.class,
        ClaimHlsPackagePurgeUseCase.class,
        CompleteHlsPackagePurgeUseCase.class,
        RegisterMediaAssetVersionUseCase.class,
        DeleteMediaAssetUseCase.class,
        ArchiveMediaAssetUseCase.class,
        RestoreMediaAssetUseCase.class,
        MediaVideoHlsConcurrencyIntegrationTest.TestConfig.class
})
class MediaVideoHlsConcurrencyIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-11-20T14:00:00Z");
    private static final StorageProviderId R2_PROVIDER = StorageProviderId.of("r2-movie");
    private static final ContentHash SOURCE_SHA256 =
            ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    private static final ContentHash MANIFEST_SHA256 =
            ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2");

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
            return org.mockito.Mockito.mock(StorageKeyAllocationService.class);
        }

        @Bean
        public MediaStorageCutoverBarrier mediaStorageCutoverBarrier() {
            MediaStorageCutoverBarrier barrier = org.mockito.Mockito.mock(MediaStorageCutoverBarrier.class);
            org.mockito.Mockito.when(barrier.isCutoverActive()).thenReturn(false);
            return barrier;
        }

        @Bean
        public AtomicBoolean autoEnqueueEnabledFlag() {
            return new AtomicBoolean(false);
        }

        @Bean
        public HlsAutoEnqueueProperties hlsAutoEnqueueProperties(AtomicBoolean autoEnqueueEnabledFlag) {
            return new HlsAutoEnqueueProperties(false, false) {
                @Override public boolean isEnabled() { return autoEnqueueEnabledFlag.get(); }
                @Override public boolean isOnRestore() { return false; }
                @Override public boolean isRestoreAutoEnqueueActive() { return false; }
            };
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AtomicReference<Instant> clockRef;

    @Autowired
    private MediaAssetPersistenceAdapter assetAdapter;

    @Autowired
    private MediaAssetVersionPersistenceAdapter versionAdapter;

    @Autowired
    private MediaVideoHlsPackagePersistenceAdapter hlsPackageAdapter;

    @Autowired
    private MediaVideoPackagingJobPersistenceAdapter packagingJobAdapter;

    @Autowired
    private PurgeMediaAssetMetadataService purgeMetadataService;

    @Autowired
    private CreateMediaVideoPackagingJobUseCase createJobUseCase;

    @Autowired
    private OptInHlsPackagingEnqueueCoordinator optInEnqueueCoordinator;

    @Autowired
    private AtomicBoolean autoEnqueueEnabledFlag;

    @Autowired
    private ClaimMediaVideoPackagingJobUseCase claimJobUseCase;

    @Autowired
    private HeartbeatMediaVideoPackagingJobUseCase heartbeatUseCase;

    @Autowired
    private FailMediaVideoPackagingJobUseCase failJobUseCase;

    @Autowired
    private CompleteVerifiedHlsPackagingUseCase completeJobUseCase;

    @Autowired
    private CancelOrSupersedeMediaVideoPackagingUseCase cancelOrSupersedeUseCase;

    @Autowired
    private ClaimHlsPackagePurgeUseCase claimPurgeUseCase;

    @Autowired
    private CompleteHlsPackagePurgeUseCase completePurgeUseCase;

    @Autowired
    private RegisterMediaAssetVersionUseCase registerVersionUseCase;

    @Autowired
    private DeleteMediaAssetUseCase deleteAssetUseCase;

    @Autowired
    private ArchiveMediaAssetUseCase archiveAssetUseCase;

    @Autowired
    private RestoreMediaAssetUseCase restoreAssetUseCase;

    private final List<UUID> createdAssetIds = new ArrayList<>();

    @BeforeEach
    void resetClock() {
        clockRef.set(BASE_TIME);
        jdbcTemplate.update("DELETE FROM media_video_packaging_jobs");
        jdbcTemplate.update("DELETE FROM media_video_hls_packages");
    }

    @AfterEach
    void cleanUp() {
        autoEnqueueEnabledFlag.set(false);
        for (UUID assetId : createdAssetIds) {
            String id = assetId.toString();
            jdbcTemplate.update("DELETE FROM media_video_packaging_jobs WHERE asset_id = ?", id);
            jdbcTemplate.update("DELETE FROM media_video_hls_packages WHERE asset_id = ?", id);
            jdbcTemplate.update("DELETE FROM media_asset_versions WHERE asset_id = ?", id);
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", id);
        }
        createdAssetIds.clear();
    }

    private UUID seedVideoAssetWithPendingJob() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        createdAssetIds.add(assetId);
        MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, clockRef.get());
        assetAdapter.save(asset);
        versionAdapter.save(MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                StorageLocation.of(R2_PROVIDER.value(), "video/sources/" + assetId + "/v1/source.mp4"),
                null,
                SOURCE_SHA256,
                MimeType.of("video/mp4"),
                15_000_000L,
                "source.mp4",
                clockRef.get()
        ));
        return assetId;
    }

    private TransactionTemplate requiresNewTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Test
    @DisplayName("CX-01: Two workers concurrently claiming the same PENDING job serialize cleanly with exactly one winner")
    void cx01ShouldSerializeConcurrentJobClaimWithSingleWinner() throws Exception {
        UUID assetId = seedVideoAssetWithPendingJob();
        MediaVideoPackagingJob createdJob = createJobUseCase.execute(assetId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Optional<ClaimedVideoPackagingLease>> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return claimJobUseCase.claimNext("worker-1", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket");
            });
            Future<Optional<ClaimedVideoPackagingLease>> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return claimJobUseCase.claimNext("worker-2", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket");
            });

            Optional<ClaimedVideoPackagingLease> r1 = f1.get(15, TimeUnit.SECONDS);
            Optional<ClaimedVideoPackagingLease> r2 = f2.get(15, TimeUnit.SECONDS);

            List<ClaimedVideoPackagingLease> winners = java.util.stream.Stream.of(r1, r2)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
            assertThat(winners).hasSize(1);
            assertThat(winners.get(0).jobId()).isEqualTo(createdJob.getId());
            assertThat(winners.get(0).leaseGeneration()).isEqualTo(1L);
            assertThat(hlsPackageAdapter.findByAssetId(assetId)).hasSize(1);
            assertThat(hlsPackageAdapter.findByAssetId(assetId).get(0).getStatus())
                    .isEqualTo(MediaVideoHlsPackageStatus.PROCESSING);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-02: Expired lease reclaim increments generation, abandons old package, and rejects stale heartbeat")
    void cx02ShouldReclaimExpiredLeaseAndRejectStaleHeartbeat() {
        UUID assetId = seedVideoAssetWithPendingJob();
        createJobUseCase.execute(assetId);

        ClaimedVideoPackagingLease lease1 = claimJobUseCase.claimNext(
                "worker-a", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();
        assertThat(lease1.leaseGeneration()).isEqualTo(1L);

        // Advance past lease expiry so worker-b reclaims
        clockRef.set(BASE_TIME.plus(Duration.ofMinutes(6)));
        ClaimedVideoPackagingLease lease2 = claimJobUseCase.claimNext(
                "worker-b", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();

        assertThat(lease2.jobId()).isEqualTo(lease1.jobId());
        assertThat(lease2.leaseGeneration()).isEqualTo(2L);
        assertThat(lease2.packageId()).isNotEqualTo(lease1.packageId());
        assertThat(lease2.basePrefix()).isNotEqualTo(lease1.basePrefix());

        assertThat(hlsPackageAdapter.findById(lease1.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.ABANDONED);
        assertThat(hlsPackageAdapter.findById(lease2.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.PROCESSING);

        // Stale heartbeat with generation 1 fails conditionally (0 rows updated)
        boolean staleHeartbeat = heartbeatUseCase.execute(
                lease1.jobId(), "worker-a", lease1.leaseGeneration(), Duration.ofMinutes(5)
        );
        assertThat(staleHeartbeat).isFalse();

        // Live heartbeat with generation 2 succeeds
        boolean freshHeartbeat = heartbeatUseCase.execute(
                lease2.jobId(), "worker-b", lease2.leaseGeneration(), Duration.ofMinutes(5)
        );
        assertThat(freshHeartbeat).isTrue();
    }

    @Test
    @DisplayName("CX-03: Concurrent completion race rejects stale generation 1 and accepts live generation 2")
    void cx03ShouldRejectStaleCompletionAndAcceptCurrentGeneration() throws Exception {
        UUID assetId = seedVideoAssetWithPendingJob();
        createJobUseCase.execute(assetId);

        ClaimedVideoPackagingLease staleLease = claimJobUseCase.claimNext(
                "worker-a", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();

        clockRef.set(BASE_TIME.plus(Duration.ofMinutes(6)));
        ClaimedVideoPackagingLease freshLease = claimJobUseCase.claimNext(
                "worker-b", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();

        VerifiedHlsPackageReceipt receipt = new VerifiedHlsPackageReceipt(
                MANIFEST_SHA256, 65536L, 6, "[{\"height\":720}]", clockRef.get()
        );

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Throwable> staleOutcome = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    completeJobUseCase.completeWithVerifiedReceipt(
                            assetId, staleLease.jobId(), staleLease.packageId(),
                            "worker-a", staleLease.leaseGeneration(), receipt
                    );
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<MediaVideoHlsPackage> freshOutcome = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return completeJobUseCase.completeWithVerifiedReceipt(
                        assetId, freshLease.jobId(), freshLease.packageId(),
                        "worker-b", freshLease.leaseGeneration(), receipt
                );
            });

            Throwable staleError = staleOutcome.get(15, TimeUnit.SECONDS);
            MediaVideoHlsPackage readyPackage = freshOutcome.get(15, TimeUnit.SECONDS);

            assertThat(staleError).isInstanceOf(StalePackagingLeaseException.class);
            assertThat(readyPackage.getId()).isEqualTo(freshLease.packageId());
            assertThat(readyPackage.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.READY);
            assertThat(packagingJobAdapter.findById(freshLease.jobId()).orElseThrow().getStatus())
                    .isEqualTo(MediaVideoPackagingJobStatus.SUCCEEDED);
            assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetId))
                    .get().extracting(MediaVideoHlsPackage::getId).isEqualTo(freshLease.packageId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-04: Parent MediaAsset FOR UPDATE concurrent with Claim/Package INSERT incurs zero InnoDB deadlocks")
    void cx04ShouldAvoidFkLockInversionDeadlocksAcrossParentLockAndClaim() throws Exception {
        for (int round = 0; round < 5; round++) {
            clockRef.set(BASE_TIME.plusSeconds(round * 2L));
            UUID assetId = seedVideoAssetWithPendingJob();
            createJobUseCase.execute(assetId);

            CountDownLatch parentLocked = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Void> parentTx = pool.submit(() -> requiresNewTemplate().execute(status -> {
                    MediaAsset lockedAsset = assetAdapter.findByIdForUpdate(assetId).orElseThrow();
                    parentLocked.countDown();
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    lockedAsset.changeVisibility(MediaVisibility.RESTRICTED, clockRef.get().plusSeconds(1));
                    assetAdapter.save(lockedAsset);
                    return null;
                }));

                Future<Optional<ClaimedVideoPackagingLease>> claimTx = pool.submit(() -> {
                    parentLocked.await(10, TimeUnit.SECONDS);
                    return claimJobUseCase.claimNext(
                            "worker-lock-order", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
                    );
                });

                parentTx.get(15, TimeUnit.SECONDS);
                Optional<ClaimedVideoPackagingLease> lease = claimTx.get(15, TimeUnit.SECONDS);
                assertThat(lease).isPresent();
                assertThat(lease.get().assetId()).isEqualTo(assetId);
                assertThat(lease.get().leaseGeneration()).isEqualTo(1L);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("CX-05: Concurrent source version replacement (v1 -> v2) blocks or supersedes stale v1 completion")
    void cx05ShouldRejectOrSupersedeV1CompletionWhenSourceVersionAdvances() throws Exception {
        UUID assetId = seedVideoAssetWithPendingJob();
        createJobUseCase.execute(assetId);
        ClaimedVideoPackagingLease v1Lease = claimJobUseCase.claimNext(
                "worker-v1", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();

        CountDownLatch v2ParentLocked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Thread 1 holds MediaAsset FOR UPDATE and upgrades currentVersionNumber to v2 before completing cancel/supersede
            Future<Void> replaceVersionTx = pool.submit(() -> {
                requiresNewTemplate().execute(status -> {
                    MediaAsset lockedAsset = assetAdapter.findByIdForUpdate(assetId).orElseThrow();
                    v2ParentLocked.countDown();
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    Instant t2 = clockRef.get().plusSeconds(10);
                    int v2Num = lockedAsset.registerNextVersion(t2);
                    assetAdapter.save(lockedAsset);
                    versionAdapter.save(MediaAssetVersion.create(
                            UUID.randomUUID(),
                            assetId,
                            v2Num,
                            StorageLocation.of(R2_PROVIDER.value(), "video/sources/" + assetId + "/v2/source.mp4"),
                            null,
                            SOURCE_SHA256,
                            MimeType.of("video/mp4"),
                            22_000_000L,
                            "source-v2.mp4",
                            t2
                    ));
                    return null;
                });
                cancelOrSupersedeUseCase.cancelAndSupersedeOlderVersions(assetId, 2, "Replaced by v2");
                return null;
            });

            Future<Throwable> completeV1Tx = pool.submit(() -> {
                v2ParentLocked.await(10, TimeUnit.SECONDS);
                try {
                    completeJobUseCase.completeWithVerifiedReceipt(
                            assetId, v1Lease.jobId(), v1Lease.packageId(),
                            "worker-v1", v1Lease.leaseGeneration(),
                            new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", clockRef.get())
                    );
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            replaceVersionTx.get(15, TimeUnit.SECONDS);
            Throwable completionError = completeV1Tx.get(15, TimeUnit.SECONDS);

            assertThat(completionError).isInstanceOf(StalePackagingLeaseException.class);
            assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetId)).isEmpty();
            assertThat(hlsPackageAdapter.findById(v1Lease.packageId()).orElseThrow().getStatus())
                    .isEqualTo(MediaVideoHlsPackageStatus.ABANDONED);
            assertThat(packagingJobAdapter.findById(v1Lease.jobId()).orElseThrow().getStatus())
                    .isEqualTo(MediaVideoPackagingJobStatus.CANCELLED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-06: Concurrent deletion / fail-closed purge vs claim or fail runs deadlock-free and preserves state")
    void cx06ShouldSerializeDeleteAndFailClosedPurgeAgainstPackagingWorkersWithoutDeadlock() throws Exception {
        // Sub-case A: Concurrent markDeleted + cancel vs live completion
        UUID assetA = seedVideoAssetWithPendingJob();
        createJobUseCase.execute(assetA);
        ClaimedVideoPackagingLease leaseA = claimJobUseCase.claimNext(
                "worker-del", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();

        CountDownLatch deleteLocked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> deleteTx = pool.submit(() -> {
                requiresNewTemplate().execute(status -> {
                    MediaAsset lockedAsset = assetAdapter.findByIdForUpdate(assetA).orElseThrow();
                    deleteLocked.countDown();
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    lockedAsset.markDeleted(clockRef.get().plusSeconds(10));
                    assetAdapter.save(lockedAsset);
                    return null;
                });
                cancelOrSupersedeUseCase.cancelAllForAssetDeletion(assetA, "Deleted concurrently");
                return null;
            });

            Future<Throwable> completeTx = pool.submit(() -> {
                deleteLocked.await(10, TimeUnit.SECONDS);
                try {
                    completeJobUseCase.completeWithVerifiedReceipt(
                            assetA, leaseA.jobId(), leaseA.packageId(),
                            "worker-del", leaseA.leaseGeneration(),
                            new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", clockRef.get())
                    );
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            deleteTx.get(15, TimeUnit.SECONDS);
            Throwable completeErr = completeTx.get(15, TimeUnit.SECONDS);
            assertThat(completeErr).isInstanceOf(StalePackagingLeaseException.class);
            assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetA)).isEmpty();
        } finally {
            pool.shutdownNow();
        }

        // Sub-case B: Expired DELETED asset concurrent PurgeMediaAssetMetadataService vs FailMediaVideoPackagingJobUseCase
        UUID assetB = UUID.randomUUID();
        UUID verB = UUID.randomUUID();
        createdAssetIds.add(assetB);
        Instant oldDeletedAt = BASE_TIME.minus(Duration.ofDays(10));
        assetAdapter.save(MediaAsset.rehydrate(
                assetB, MediaType.VIDEO, MediaVisibility.PUBLIC, MediaAssetStatus.DELETED, 1,
                oldDeletedAt.minus(Duration.ofDays(2)), oldDeletedAt
        ));
        versionAdapter.save(MediaAssetVersion.create(
                verB, assetB, 1,
                StorageLocation.of(R2_PROVIDER.value(), "video/sources/" + assetB + "/v1/source.mp4"),
                null, SOURCE_SHA256, MimeType.of("video/mp4"), 1000L, "src.mp4", oldDeletedAt
        ));
        MediaVideoPackagingJob jobB = packagingJobAdapter.save(MediaVideoPackagingJob.createPending(
                UUID.randomUUID(), assetB, verB, 1, 3, oldDeletedAt
        ));

        ExecutorService poolB = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Throwable> purgeFuture = poolB.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    purgeMetadataService.execute(assetB);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<Void> cancelFuture = poolB.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                cancelOrSupersedeUseCase.cancelAllForAssetDeletion(assetB, "Concurrent purge race");
                return null;
            });

            Throwable purgeError = purgeFuture.get(15, TimeUnit.SECONDS);
            cancelFuture.get(15, TimeUnit.SECONDS);

            assertThat(purgeError).isInstanceOf(IllegalStateException.class);
            assertThat(assetAdapter.findById(assetB)).isPresent();
            assertThat(packagingJobAdapter.findById(jobB.getId())).isPresent();
        } finally {
            poolB.shutdownNow();
        }
    }

    @Test
    @DisplayName("LS-11: Concurrent heartbeat vs reclaim at boundary prevents split ownership or generation rollback")
    void cx07Ls11ShouldPreventSplitOwnershipOrStateRollbackDuringConcurrentHeartbeatAndReclaim() throws Exception {
        // Case 1: Just before expiry -> heartbeat extends OR claim sees active lease and returns empty; never two owners
        UUID assetBefore = seedVideoAssetWithPendingJob();
        createJobUseCase.execute(assetBefore);
        ClaimedVideoPackagingLease activeLease = claimJobUseCase.claimNext(
                "worker-a", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();
        clockRef.set(activeLease.leaseExpiresAt().minusMillis(5));

        ExecutorService pool1 = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier1 = new CyclicBarrier(2);
            Future<Boolean> hbFuture = pool1.submit(() -> {
                barrier1.await(10, TimeUnit.SECONDS);
                return heartbeatUseCase.execute(
                        activeLease.jobId(), "worker-a", activeLease.leaseGeneration(), Duration.ofMinutes(5)
                );
            });
            Future<Optional<ClaimedVideoPackagingLease>> reclaimFuture = pool1.submit(() -> {
                barrier1.await(10, TimeUnit.SECONDS);
                return claimJobUseCase.claimNext("worker-b", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket");
            });

            assertThat(hbFuture.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(reclaimFuture.get(15, TimeUnit.SECONDS)).isEmpty();
            MediaVideoPackagingJob persisted = packagingJobAdapter.findById(activeLease.jobId()).orElseThrow();
            assertThat(persisted.getLeaseGeneration()).isEqualTo(1L);
            assertThat(persisted.getLeaseOwner()).isEqualTo("worker-a");
        } finally {
            pool1.shutdownNow();
        }

        // Clean active job before boundary subcase so claimNext targets the second job deterministically
        cancelOrSupersedeUseCase.cancelAllForAssetDeletion(assetBefore, "Done with subcase 1");

        // Case 2: At exact expiry -> heartbeat fails while reclaim advances generation monotonically to 2
        UUID assetAtExpiry = seedVideoAssetWithPendingJob();
        clockRef.set(BASE_TIME.plusSeconds(600));
        createJobUseCase.execute(assetAtExpiry);
        ClaimedVideoPackagingLease expiringLease = claimJobUseCase.claimNext(
                "worker-a", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();
        clockRef.set(expiringLease.leaseExpiresAt());

        ExecutorService pool2 = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier2 = new CyclicBarrier(2);
            Future<Boolean> lateHbFuture = pool2.submit(() -> {
                barrier2.await(10, TimeUnit.SECONDS);
                return heartbeatUseCase.execute(
                        expiringLease.jobId(), "worker-a", expiringLease.leaseGeneration(), Duration.ofMinutes(5)
                );
            });
            Future<Optional<ClaimedVideoPackagingLease>> reclaimAtBoundary = pool2.submit(() -> {
                barrier2.await(10, TimeUnit.SECONDS);
                return claimJobUseCase.claimNext("worker-b", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket");
            });

            boolean hbResult = lateHbFuture.get(15, TimeUnit.SECONDS);
            Optional<ClaimedVideoPackagingLease> reclaimed = reclaimAtBoundary.get(15, TimeUnit.SECONDS);

            assertThat(hbResult).isFalse();
            assertThat(reclaimed).isPresent();
            assertThat(reclaimed.get().leaseGeneration()).isEqualTo(2L);

            MediaVideoPackagingJob finalJob = packagingJobAdapter.findById(expiringLease.jobId()).orElseThrow();
            assertThat(finalJob.getLeaseGeneration()).isEqualTo(2L);
            assertThat(finalJob.getLeaseOwner()).isEqualTo("worker-b");
            assertThat(hlsPackageAdapter.findById(expiringLease.packageId()).orElseThrow().getStatus())
                    .isEqualTo(MediaVideoHlsPackageStatus.ABANDONED);
        } finally {
            pool2.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-08: Stale permanent failure fences cleanly against reclaimed completion and preserves 24h ABANDONED retention")
    void cx08ShouldFenceStalePermanentFailureAgainstReclaimCompletionAndPreserve24hRetention() throws Exception {
        UUID assetId = seedVideoAssetWithPendingJob();
        createJobUseCase.execute(assetId);

        ClaimedVideoPackagingLease staleLease = claimJobUseCase.claimNext(
                "worker-stale", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();

        // Reclaim expired lease -> P1 becomes ABANDONED with purgeAfter = reclaimTime + 24h
        Instant reclaimTime = BASE_TIME.plus(Duration.ofMinutes(6));
        clockRef.set(reclaimTime);
        ClaimedVideoPackagingLease freshLease = claimJobUseCase.claimNext(
                "worker-fresh", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();

        MediaVideoHlsPackage abandonedP1 = hlsPackageAdapter.findById(staleLease.packageId()).orElseThrow();
        assertThat(abandonedP1.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.ABANDONED);
        assertThat(abandonedP1.getPurgeAfter()).isEqualTo(reclaimTime.plus(Duration.ofHours(24)));

        // Concurrent stale permanent failure (gen=1) vs fresh READY completion (gen=2)
        VerifiedHlsPackageReceipt receipt = new VerifiedHlsPackageReceipt(
                MANIFEST_SHA256, 48_000L, 6, "[{\"height\":720}]", reclaimTime.plusSeconds(5)
        );
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Throwable> staleFailFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    failJobUseCase.executePermanentFailure(
                            assetId, staleLease.jobId(), "worker-stale", staleLease.leaseGeneration(),
                            "Delayed HDR defect from stale gen=1"
                    );
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<MediaVideoHlsPackage> freshCompleteFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return completeJobUseCase.completeWithVerifiedReceipt(
                        assetId, freshLease.jobId(), freshLease.packageId(),
                        "worker-fresh", freshLease.leaseGeneration(), receipt
                );
            });

            Throwable staleErr = staleFailFuture.get(15, TimeUnit.SECONDS);
            MediaVideoHlsPackage readyPkg = freshCompleteFuture.get(15, TimeUnit.SECONDS);

            assertThat(staleErr).isInstanceOf(StalePackagingLeaseException.class);
            assertThat(readyPkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.READY);
            assertThat(packagingJobAdapter.findById(freshLease.jobId()).orElseThrow().getStatus())
                    .isEqualTo(MediaVideoPackagingJobStatus.SUCCEEDED);
            assertThat(hlsPackageAdapter.findById(staleLease.packageId()).orElseThrow().getPurgeAfter())
                    .isEqualTo(reclaimTime.plus(Duration.ofHours(24)));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-09: Two concurrent purge claimers serialize via MediaAsset -> HlsPackage lock order with single winner")
    void cx09ShouldSerializeConcurrentPurgeClaimsWithSingleWinner() throws Exception {
        UUID assetId = seedVideoAssetWithPendingJob();
        createJobUseCase.execute(assetId);
        ClaimedVideoPackagingLease pkgLease = claimJobUseCase.claimNext(
                "worker-prep", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();
        failJobUseCase.executePermanentFailure(
                assetId, pkgLease.jobId(), "worker-prep", pkgLease.leaseGeneration(), "Permanent HDR reject"
        );

        // Advance past 24h retention floor
        Instant eligibleTime = BASE_TIME.plus(Duration.ofHours(25));
        clockRef.set(eligibleTime);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Optional<ClaimedHlsPackagePurgeLease>> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return claimPurgeUseCase.claimNext(Duration.ofMinutes(5));
            });
            Future<Optional<ClaimedHlsPackagePurgeLease>> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return claimPurgeUseCase.claimNext(Duration.ofMinutes(5));
            });

            Optional<ClaimedHlsPackagePurgeLease> r1 = f1.get(15, TimeUnit.SECONDS);
            Optional<ClaimedHlsPackagePurgeLease> r2 = f2.get(15, TimeUnit.SECONDS);
            List<ClaimedHlsPackagePurgeLease> winners = java.util.stream.Stream.of(r1, r2)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();

            assertThat(winners).hasSize(1);
            ClaimedHlsPackagePurgeLease winner = winners.get(0);
            assertThat(winner.packageId()).isEqualTo(pkgLease.packageId());
            assertThat(winner.claimVersion()).isGreaterThan(1L);
            MediaVideoHlsPackage claimedPkg = hlsPackageAdapter.findById(pkgLease.packageId()).orElseThrow();
            assertThat(claimedPkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGING);
            assertThat(claimedPkg.getPersistenceVersion()).isEqualTo(winner.claimVersion());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-10: Crash recovery, stale purge completion/failure fencing across reclaim, backoff, and PURGED tombstone")
    void cx10ShouldFenceStalePurgeCompletionAndFailureAcrossCrashReclaimAndExpiry() throws Exception {
        UUID assetId = seedVideoAssetWithPendingJob();
        createJobUseCase.execute(assetId);
        ClaimedVideoPackagingLease jobLease = claimJobUseCase.claimNext(
                "worker-1", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();
        failJobUseCase.executePermanentFailure(
                assetId, jobLease.jobId(), "worker-1", jobLease.leaseGeneration(), "Corrupt MP4 container"
        );

        // Sweeper A claims at T0+25h -> hypothetical crash after claim (remains PURGING, never falsely PURGED)
        Instant tClaimA = BASE_TIME.plus(Duration.ofHours(25));
        clockRef.set(tClaimA);
        ClaimedHlsPackagePurgeLease leaseA = claimPurgeUseCase.claimNext(Duration.ofMinutes(5)).orElseThrow();

        // Exactly at expiry: Sweeper A completion rejected (expired)
        clockRef.set(leaseA.claimExpiresAt());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> completePurgeUseCase.markPurged(leaseA))
                .isInstanceOf(StalePurgeClaimException.class);
        assertThat(hlsPackageAdapter.findById(leaseA.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.PURGING);

        // Sweeper B reclaims expired claim -> bumped version
        clockRef.set(leaseA.claimExpiresAt().plusSeconds(5));
        ClaimedHlsPackagePurgeLease leaseB = claimPurgeUseCase.claimNext(Duration.ofMinutes(5)).orElseThrow();
        assertThat(leaseB.claimVersion()).isGreaterThan(leaseA.claimVersion());

        // Concurrent race: Sweeper A attempts completion & failure with stale leaseA vs Sweeper B recording failure backoff
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Throwable> staleComplete = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    completePurgeUseCase.markPurged(leaseA);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<MediaVideoHlsPackage> freshBackoff = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return completePurgeUseCase.recordFailure(leaseB, "Transient R2 503", Duration.ofMinutes(10));
            });

            assertThat(staleComplete.get(15, TimeUnit.SECONDS)).isInstanceOf(StalePurgeClaimException.class);
            MediaVideoHlsPackage backedOff = freshBackoff.get(15, TimeUnit.SECONDS);
            assertThat(backedOff.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGING);
            assertThat(backedOff.getPersistenceVersion()).isGreaterThan(leaseB.claimVersion());
            assertThat(backedOff.getFailureReason()).isEqualTo("Corrupt MP4 container | [purge-error: Transient R2 503]");
        } finally {
            pool.shutdownNow();
        }

        // Stale leaseA & old leaseB failure reports are both rejected cleanly
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> completePurgeUseCase.recordFailure(leaseA, "stale-A", Duration.ofMinutes(5)))
                .isInstanceOf(StalePurgeClaimException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> completePurgeUseCase.recordFailure(leaseB, "stale-B", Duration.ofMinutes(5)))
                .isInstanceOf(StalePurgeClaimException.class);

        // Restart recovery after backoff -> Sweeper C reclaims and completes with live version -> PURGED tombstone
        Instant tAfterBackoff = clockRef.get().plus(Duration.ofMinutes(11));
        clockRef.set(tAfterBackoff);
        ClaimedHlsPackagePurgeLease leaseC = claimPurgeUseCase.claimNext(Duration.ofMinutes(5)).orElseThrow();
        assertThat(leaseC.claimVersion()).isGreaterThan(leaseB.claimVersion() + 1L);

        clockRef.set(tAfterBackoff.plusSeconds(2));
        MediaVideoHlsPackage purged = completePurgeUseCase.markPurged(leaseC);
        assertThat(purged.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGED);
        assertThat(purged.getPurgeAfter()).isNull();
        assertThat(purged.getPersistenceVersion()).isEqualTo(leaseC.claimVersion() + 1L);
    }

    @Test
    @DisplayName("CX-11: Concurrent purge claim vs packaging job claim and supersession preserves parent-first lock order and READY package")
    void cx11ShouldSerializePurgeClaimAgainstConcurrentJobLifecycleWithoutDeadlock() throws Exception {
        for (int round = 0; round < 3; round++) {
            Instant tStart = BASE_TIME.plus(Duration.ofDays(round * 10L));
            clockRef.set(tStart);
            UUID assetId = seedVideoAssetWithPendingJob();

            // Produce 1 FAILED attempt (eligible at tStart + 25h) AND 1 READY attempt on the same asset
            MediaVideoPackagingJob job1 = createJobUseCase.execute(assetId);
            ClaimedVideoPackagingLease l1 = claimJobUseCase.claimNext(
                    "worker-1", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
            ).orElseThrow();
            failJobUseCase.execute(assetId, job1.getId(), "worker-1", l1.leaseGeneration(), "fail-1", Duration.ofSeconds(5));

            clockRef.set(tStart.plusSeconds(10));
            ClaimedVideoPackagingLease l2 = claimJobUseCase.claimNext(
                    "worker-2", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
            ).orElseThrow();
            completeJobUseCase.completeWithVerifiedReceipt(
                    assetId, job1.getId(), l2.packageId(), "worker-2", l2.leaseGeneration(),
                    new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", tStart.plusSeconds(10))
            );

            // Re-enqueue a second packaging job on same asset & advance 25h so l1.packageId() is purge-eligible
            Instant tRace = tStart.plus(Duration.ofHours(25));
            clockRef.set(tRace);
            createJobUseCase.execute(assetId);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Optional<ClaimedHlsPackagePurgeLease>> purgeClaimFuture = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return claimPurgeUseCase.claimNext(Duration.ofMinutes(5));
                });
                Future<Optional<ClaimedVideoPackagingLease>> jobClaimFuture = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return claimJobUseCase.claimNext("worker-3", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket");
                });

                Optional<ClaimedHlsPackagePurgeLease> purgedLease = purgeClaimFuture.get(15, TimeUnit.SECONDS);
                Optional<ClaimedVideoPackagingLease> nextJobLease = jobClaimFuture.get(15, TimeUnit.SECONDS);

                assertThat(purgedLease).isPresent();
                assertThat(purgedLease.get().packageId()).isEqualTo(l1.packageId());
                assertThat(nextJobLease).isPresent();
                assertThat(hlsPackageAdapter.findById(l2.packageId()).orElseThrow().getStatus())
                        .isEqualTo(MediaVideoHlsPackageStatus.READY);

                MediaVideoHlsPackage finished = completePurgeUseCase.markPurged(purgedLease.get());
                assertThat(finished.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGED);

                // Clean up round rows before next loop iteration so neither active jobs nor ABANDONED/SUPERSEDED packages leak into round N+1
                cleanUp();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("CX-12: Expired purge claim while waiting for DB row lock rejects markPurged without mutating package")
    void cx12ShouldRejectExpiredPurgeCompletionWhenClockAdvancesDuringRowLockWait() throws Exception {
        ClaimedHlsPackagePurgeLease lease = seedEligiblePurgeLease("worker-cx12", "Corrupt MP4 CX12");
        clockRef.set(lease.claimExpiresAt().minusSeconds(1));

        Throwable thrown = executeWhileAssetRowLockedAndWaitForInnodbLockWait(
                lease.assetId(),
                () -> clockRef.set(lease.claimExpiresAt().plusSeconds(10)),
                () -> {
                    try {
                        completePurgeUseCase.markPurged(lease);
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }
        );

        assertThat(thrown).isInstanceOf(StalePurgeClaimException.class);
        MediaVideoHlsPackage persisted = hlsPackageAdapter.findById(lease.packageId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGING);
        assertThat(persisted.getPurgeAfter()).isEqualTo(lease.claimExpiresAt());
        assertThat(persisted.getPersistenceVersion()).isEqualTo(lease.claimVersion());
    }

    @Test
    @DisplayName("CX-13: Expired purge claim while waiting for DB row lock rejects recordFailure without writing retry deadline")
    void cx13ShouldRejectExpiredPurgeFailureWhenClockAdvancesDuringRowLockWait() throws Exception {
        ClaimedHlsPackagePurgeLease lease = seedEligiblePurgeLease("worker-cx13", "Corrupt MP4 CX13");
        clockRef.set(lease.claimExpiresAt().minusSeconds(1));

        Throwable thrown = executeWhileAssetRowLockedAndWaitForInnodbLockWait(
                lease.assetId(),
                () -> clockRef.set(lease.claimExpiresAt().plusSeconds(10)),
                () -> {
                    try {
                        completePurgeUseCase.recordFailure(lease, "Transient R2 503", Duration.ofMinutes(10));
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }
        );

        assertThat(thrown).isInstanceOf(StalePurgeClaimException.class);
        MediaVideoHlsPackage persisted = hlsPackageAdapter.findById(lease.packageId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGING);
        assertThat(persisted.getPurgeAfter()).isEqualTo(lease.claimExpiresAt());
        assertThat(persisted.getFailureReason()).isEqualTo("Corrupt MP4 CX13");
        assertThat(persisted.getPersistenceVersion()).isEqualTo(lease.claimVersion());
    }

    @Test
    @DisplayName("CX-14: Purge claim blocked on DB row lock issues lease and purgeAfter from post-lock timestamp")
    void cx14ShouldUsePostLockTimestampWhenPurgeClaimWaitsForRowLock() throws Exception {
        UUID assetId = seedVideoAssetWithPendingJob();
        createJobUseCase.execute(assetId);
        ClaimedVideoPackagingLease pkgLease = claimJobUseCase.claimNext(
                "worker-cx14", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();
        failJobUseCase.executePermanentFailure(
                assetId, pkgLease.jobId(), "worker-cx14", pkgLease.leaseGeneration(), "Permanent CX14"
        );

        Instant tScan = BASE_TIME.plus(Duration.ofHours(25));
        Instant tLocked = tScan.plus(Duration.ofMinutes(3));
        clockRef.set(tScan);

        Optional<ClaimedHlsPackagePurgeLease> claimedOpt = executeWhileAssetRowLockedAndWaitForInnodbLockWait(
                assetId,
                () -> clockRef.set(tLocked),
                () -> claimPurgeUseCase.claimNext(Duration.ofMinutes(5))
        );

        assertThat(claimedOpt).isPresent();
        ClaimedHlsPackagePurgeLease claimed = claimedOpt.get();
        Instant expectedExpiry = tLocked.plus(Duration.ofMinutes(5));
        assertThat(claimed.claimExpiresAt()).isEqualTo(expectedExpiry);

        MediaVideoHlsPackage persisted = hlsPackageAdapter.findById(claimed.packageId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGING);
        assertThat(persisted.getPurgeAfter()).isEqualTo(expectedExpiry);
        assertThat(persisted.getUpdatedAt()).isEqualTo(tLocked);
    }

    private ClaimedHlsPackagePurgeLease seedEligiblePurgeLease(String workerId, String failReason) {
        UUID assetId = seedVideoAssetWithPendingJob();
        createJobUseCase.execute(assetId);
        ClaimedVideoPackagingLease jobLease = claimJobUseCase.claimNext(
                workerId, Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();
        failJobUseCase.executePermanentFailure(
                assetId, jobLease.jobId(), workerId, jobLease.leaseGeneration(), failReason
        );
        clockRef.set(BASE_TIME.plus(Duration.ofHours(25)));
        return claimPurgeUseCase.claimNext(Duration.ofMinutes(5)).orElseThrow();
    }

    private <T> T executeWhileAssetRowLockedAndWaitForInnodbLockWait(
            UUID assetId,
            Runnable onLockWaitConfirmed,
            java.util.concurrent.Callable<T> blockedOperation
    ) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch holderLocked = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Future<Void> lockHolderFuture = null;
        Future<T> waiterFuture = null;
        try {
            lockHolderFuture = pool.submit(() -> {
                requiresNewTemplate().execute(status -> {
                    assetAdapter.findByIdForUpdate(assetId).orElseThrow();
                    holderLocked.countDown();
                    try {
                        if (!releaseHolder.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to release InnoDB holder lock");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while holding InnoDB lock", e);
                    }
                    return null;
                });
                return null;
            });

            assertThat(holderLocked.await(10, TimeUnit.SECONDS)).isTrue();
            waiterFuture = pool.submit(blockedOperation);

            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int lockWaitCount = 0;
            while (System.nanoTime() < deadlineNanos) {
                Integer trxWaitCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT'",
                        Integer.class
                );
                Integer dataWaitCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM performance_schema.data_lock_waits",
                        Integer.class
                );
                if ((trxWaitCount != null && trxWaitCount >= 1) || (dataWaitCount != null && dataWaitCount >= 1)) {
                    lockWaitCount = Math.max(trxWaitCount == null ? 0 : trxWaitCount, dataWaitCount == null ? 0 : dataWaitCount);
                    break;
                }
                Thread.sleep(20);
            }
            if (lockWaitCount < 1) {
                System.err.println("[DIAG] innodb_trx=" + jdbcTemplate.queryForList("SELECT trx_id, trx_state, trx_query FROM information_schema.innodb_trx"));
                System.err.println("[DIAG] data_locks=" + jdbcTemplate.queryForList("SELECT OBJECT_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS FROM performance_schema.data_locks"));
            }
            assertThat(lockWaitCount)
                    .as("Expected waiter thread to enter real InnoDB LOCK WAIT before advancing clock")
                    .isGreaterThanOrEqualTo(1);

            onLockWaitConfirmed.run();
            releaseHolder.countDown();

            lockHolderFuture.get(15, TimeUnit.SECONDS);
            return waiterFuture.get(15, TimeUnit.SECONDS);
        } finally {
            releaseHolder.countDown();
            if (lockHolderFuture != null) {
                try { lockHolderFuture.get(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
            if (waiterFuture != null) {
                try { waiterFuture.get(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-15: Concurrent V1 packaging completion vs V2 replacement and concurrent V2/V3 updates serialize with monotonic versions and stale V1 rejection")
    void cx15ShouldSerializeV1CompletionAgainstV2ReplacementAndKeepVersionsMonotonic() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID assetId = seedVideoAssetWithPendingJob();
            MediaVideoPackagingJob job1 = createJobUseCase.execute(assetId);
            ClaimedVideoPackagingLease v1Lease = claimJobUseCase.claimNext(
                    "w-cx15", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
            ).orElseThrow();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Throwable> completeFuture = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        completeJobUseCase.completeWithVerifiedReceipt(
                                assetId, job1.getId(), v1Lease.packageId(), "w-cx15", v1Lease.leaseGeneration(),
                                new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", BASE_TIME)
                        );
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                });
                Future<RegisterMediaAssetVersionResult> v2Future = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return registerVersionUseCase.execute(new RegisterMediaAssetVersionCommand(
                            assetId, R2_PROVIDER.value(), "video/sources/" + assetId + "/v2/source.mp4", null,
                            "1111111111111111111111111111111111111111111111111111111111111111", "video/mp4", 20_000_000L, "v2.mp4"
                    ));
                });

                Throwable completeErr = completeFuture.get(15, TimeUnit.SECONDS);
                RegisterMediaAssetVersionResult v2 = v2Future.get(15, TimeUnit.SECONDS);
                assertThat(v2.newVersionNumber()).isEqualTo(2);

                MediaVideoHlsPackage pkg1 = hlsPackageAdapter.findById(v1Lease.packageId()).orElseThrow();
                MediaVideoPackagingJob job1Final = packagingJobAdapter.findById(job1.getId()).orElseThrow();
                if (completeErr == null) {
                    // Completion won first, then V2 replacement superseded READY -> SUPERSEDED cleanly
                    assertThat(pkg1.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.SUPERSEDED);
                    assertThat(job1Final.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.SUCCEEDED);
                } else {
                    // V2 won first -> V1 completion rejected as StalePackagingLeaseException, V1 job CANCELLED & PROCESSING->ABANDONED
                    assertThat(completeErr).isInstanceOf(StalePackagingLeaseException.class);
                    assertThat(pkg1.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.ABANDONED);
                    assertThat(job1Final.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.CANCELLED);
                }
                assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetId)).isEmpty();
            } finally {
                pool.shutdownNow();
            }

            // Two concurrent writers racing on RegisterMediaAssetVersionUseCase.execute() serialize via FOR UPDATE cleanly -> versions 3 & 4
            ExecutorService vPool = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier vBarrier = new CyclicBarrier(2);
                Future<RegisterMediaAssetVersionResult> fA = vPool.submit(() -> {
                    vBarrier.await(10, TimeUnit.SECONDS);
                    return registerVersionUseCase.execute(new RegisterMediaAssetVersionCommand(
                            assetId, R2_PROVIDER.value(), "video/sources/" + assetId + "/v-race-a-" + UUID.randomUUID() + ".mp4", null,
                            "2222222222222222222222222222222222222222222222222222222222222222", "video/mp4", 21_000_000L, "va.mp4"
                    ));
                });
                Future<RegisterMediaAssetVersionResult> fB = vPool.submit(() -> {
                    vBarrier.await(10, TimeUnit.SECONDS);
                    return registerVersionUseCase.execute(new RegisterMediaAssetVersionCommand(
                            assetId, R2_PROVIDER.value(), "video/sources/" + assetId + "/v-race-b-" + UUID.randomUUID() + ".mp4", null,
                            "3333333333333333333333333333333333333333333333333333333333333333", "video/mp4", 22_000_000L, "vb.mp4"
                    ));
                });

                List<Integer> nums = java.util.List.of(fA.get(15, TimeUnit.SECONDS).newVersionNumber(),
                        fB.get(15, TimeUnit.SECONDS).newVersionNumber());
                assertThat(nums).containsExactlyInAnyOrder(3, 4);
                assertThat(assetAdapter.findById(assetId).orElseThrow().getCurrentVersionNumber()).isEqualTo(4);
            } finally {
                vPool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("CX-16: Concurrent Delete vs packaging completion serializes without deadlock and never leaves active READY/PENDING state")
    void cx16ShouldSerializeDeleteAgainstPackagingCompletionWithoutDeadlock() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID assetId = seedVideoAssetWithPendingJob();
            MediaVideoPackagingJob job = createJobUseCase.execute(assetId);
            ClaimedVideoPackagingLease lease = claimJobUseCase.claimNext(
                    "w-cx16", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
            ).orElseThrow();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Throwable> completeFuture = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        completeJobUseCase.completeWithVerifiedReceipt(
                                assetId, job.getId(), lease.packageId(), "w-cx16", lease.leaseGeneration(),
                                new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", BASE_TIME)
                        );
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                });
                Future<Void> deleteFuture = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    deleteAssetUseCase.execute(new DeleteMediaAssetCommand(assetId));
                    return null;
                });

                Throwable completeErr = completeFuture.get(15, TimeUnit.SECONDS);
                deleteFuture.get(15, TimeUnit.SECONDS);

                assertThat(assetAdapter.findById(assetId).orElseThrow().getStatus()).isEqualTo(MediaAssetStatus.DELETED);
                MediaVideoHlsPackage pkg = hlsPackageAdapter.findById(lease.packageId()).orElseThrow();
                if (completeErr == null) {
                    assertThat(pkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.SUPERSEDED);
                } else {
                    assertThat(completeErr).isInstanceOf(StalePackagingLeaseException.class);
                    assertThat(pkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.ABANDONED);
                }
                assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetId)).isEmpty();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("CX-17: Concurrent Archive vs completion and Restore vs Delete serialize cleanly via parent MediaAsset locks")
    void cx17ShouldSerializeArchiveVsCompletionAndRestoreVsDeleteViaParentLocks() throws Exception {
        // Part A: Archive vs completion
        UUID assetA = seedVideoAssetWithPendingJob();
        MediaVideoPackagingJob jobA = createJobUseCase.execute(assetA);
        ClaimedVideoPackagingLease leaseA = claimJobUseCase.claimNext(
                "w-cx17", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();

        ExecutorService poolA = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Throwable> compF = poolA.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    completeJobUseCase.completeWithVerifiedReceipt(
                            assetA, jobA.getId(), leaseA.packageId(), "w-cx17", leaseA.leaseGeneration(),
                            new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", BASE_TIME)
                    );
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<Void> archF = poolA.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                archiveAssetUseCase.execute(new ArchiveMediaAssetCommand(assetA));
                return null;
            });

            Throwable compErr = compF.get(15, TimeUnit.SECONDS);
            archF.get(15, TimeUnit.SECONDS);

            assertThat(assetAdapter.findById(assetA).orElseThrow().getStatus()).isEqualTo(MediaAssetStatus.ARCHIVED);
            MediaVideoHlsPackage pkgA = hlsPackageAdapter.findById(leaseA.packageId()).orElseThrow();
            if (compErr == null) {
                assertThat(pkgA.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.READY);
            } else {
                assertThat(compErr).isInstanceOf(StalePackagingLeaseException.class);
                assertThat(pkgA.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.ABANDONED);
            }
        } finally {
            poolA.shutdownNow();
        }

        // Part B: Concurrent Restore vs Delete on ARCHIVED asset with parent-row locking (single winner for Restore, Delete terminal wins)
        UUID assetB = seedVideoAssetWithPendingJob();
        archiveAssetUseCase.execute(new ArchiveMediaAssetCommand(assetB));
        ExecutorService poolB = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Throwable> restoreF = poolB.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    restoreAssetUseCase.execute(new RestoreMediaAssetCommand(assetB));
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<Void> deleteF = poolB.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                deleteAssetUseCase.execute(new DeleteMediaAssetCommand(assetB));
                return null;
            });

            Throwable restoreErr = restoreF.get(15, TimeUnit.SECONDS);
            deleteF.get(15, TimeUnit.SECONDS);
            // Delete transitions ARCHIVED->DELETED or ACTIVE->DELETED; if Delete ran first, Restore throws IllegalStateException ("Cannot restore a DELETED media asset")
            assertThat(assetAdapter.findById(assetB).orElseThrow().getStatus()).isEqualTo(MediaAssetStatus.DELETED);
            if (restoreErr != null) {
                assertThat(restoreErr).isInstanceOf(IllegalStateException.class);
            }
        } finally {
            poolB.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-18: Lifecycle Delete/V2 replacement vs PurgeClaim serialize deadlock-free and preserve PURGED tombstones")
    void cx18ShouldSerializeLifecycleCancelAgainstPurgeClaimAndPreserveTombstones() throws Exception {
        UUID assetId = seedVideoAssetWithPendingJob();
        createJobUseCase.execute(assetId);
        ClaimedVideoPackagingLease l1 = claimJobUseCase.claimNext("w1-cx18", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket").orElseThrow();
        failJobUseCase.executePermanentFailure(assetId, l1.jobId(), "w1-cx18", l1.leaseGeneration(), "fail-cx18");

        // Advance past 24h so l1.packageId is purge-eligible; create READY attempt on v2
        registerVersionUseCase.execute(new RegisterMediaAssetVersionCommand(
                assetId, R2_PROVIDER.value(), "video/sources/" + assetId + "/v2/source.mp4", null,
                "4444444444444444444444444444444444444444444444444444444444444444", "video/mp4", 30_000_000L, "v2.mp4"
        ));
        MediaVideoPackagingJob job2 = createJobUseCase.execute(assetId);
        ClaimedVideoPackagingLease l2 = claimJobUseCase.claimNext("w2-cx18", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket").orElseThrow();
        completeJobUseCase.completeWithVerifiedReceipt(
                assetId, job2.getId(), l2.packageId(), "w2-cx18", l2.leaseGeneration(),
                new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", BASE_TIME.plusSeconds(10))
        );

        clockRef.set(BASE_TIME.plus(Duration.ofHours(25)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Optional<ClaimedHlsPackagePurgeLease>> purgeFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return claimPurgeUseCase.claimNext(Duration.ofMinutes(5));
            });
            Future<Void> deleteFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                deleteAssetUseCase.execute(new DeleteMediaAssetCommand(assetId));
                return null;
            });

            Optional<ClaimedHlsPackagePurgeLease> claimed = purgeFuture.get(15, TimeUnit.SECONDS);
            deleteFuture.get(15, TimeUnit.SECONDS);

            assertThat(claimed).isPresent();
            assertThat(claimed.get().packageId()).isEqualTo(l1.packageId());
            MediaVideoHlsPackage finished = completePurgeUseCase.markPurged(claimed.get());
            assertThat(finished.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGED);
            assertThat(hlsPackageAdapter.findById(l2.packageId()).orElseThrow().getStatus())
                    .isEqualTo(MediaVideoHlsPackageStatus.SUPERSEDED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-19: Concurrent duplicate auto-enqueues for same authoritative version produce one PENDING job winner")
    void cx19ConcurrentAutoEnqueuesCreateSingleActiveJob() throws Exception {
        autoEnqueueEnabledFlag.set(true);
        UUID assetId = seedVideoAssetWithPendingJob();
        MediaAssetVersion v1 = versionAdapter.findByAssetIdAndVersionNumber(assetId, 1).orElseThrow();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<AutoPackagingEnqueueResult> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return optInEnqueueCoordinator.enqueueVideoVersionIfEnabledSafely(assetId, 1, v1.getId());
            });
            Future<AutoPackagingEnqueueResult> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return optInEnqueueCoordinator.enqueueVideoVersionIfEnabledSafely(assetId, 1, v1.getId());
            });

            AutoPackagingEnqueueResult r1 = f1.get(15, TimeUnit.SECONDS);
            AutoPackagingEnqueueResult r2 = f2.get(15, TimeUnit.SECONDS);

            assertThat(List.of(r1.status(), r2.status()))
                    .containsExactlyInAnyOrder(AutoPackagingEnqueueStatus.CREATED, AutoPackagingEnqueueStatus.REUSED_ACTIVE);
            assertThat(r1.job().getId()).isEqualTo(r2.job().getId());

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM media_video_packaging_jobs WHERE asset_id = ?",
                    Integer.class, assetId.toString()
            );
            assertThat(count).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-20: Delayed v2 auto-enqueue skips after concurrent v3 commit while v3 creates at most one job")
    void cx20VersionReplacementBeatsDelayedOldVersionEnqueue() throws Exception {
        autoEnqueueEnabledFlag.set(true);
        UUID assetId = seedVideoAssetWithPendingJob();
        RegisterMediaAssetVersionResult v2 = registerVersionUseCase.execute(new RegisterMediaAssetVersionCommand(
                assetId, R2_PROVIDER.value(), "video/sources/" + assetId + "/v2/source.mp4", null,
                "1212121212121212121212121212121212121212121212121212121212121212", "video/mp4", 10_000_000L, "v2.mp4"
        ));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<AutoPackagingEnqueueResult> delayedV2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return optInEnqueueCoordinator.enqueueVideoVersionIfEnabledSafely(assetId, 2, v2.versionId());
            });
            Future<RegisterMediaAssetVersionResult> commitV3AndEnqueue = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                RegisterMediaAssetVersionResult v3 = registerVersionUseCase.execute(new RegisterMediaAssetVersionCommand(
                        assetId, R2_PROVIDER.value(), "video/sources/" + assetId + "/v3/source.mp4", null,
                        "3434343434343434343434343434343434343434343434343434343434343434", "video/mp4", 11_000_000L, "v3.mp4"
                ));
                optInEnqueueCoordinator.enqueueVideoVersionIfEnabledSafely(assetId, 3, v3.versionId());
                return v3;
            });

            AutoPackagingEnqueueResult v2Res = delayedV2.get(15, TimeUnit.SECONDS);
            RegisterMediaAssetVersionResult v3 = commitV3AndEnqueue.get(15, TimeUnit.SECONDS);

            assertThat(v2Res.status()).isIn(AutoPackagingEnqueueStatus.CREATED, AutoPackagingEnqueueStatus.SKIPPED_STALE_VERSION);
            assertThat(assetAdapter.findById(assetId).orElseThrow().getCurrentVersionNumber()).isEqualTo(3);
            assertThat(findActiveJobInTx(v2.versionId())).isEmpty();
            assertThat(findActiveJobInTx(v3.versionId())).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-21: Auto-enqueue racing against Delete or Archive never leaves active packaging jobs surviving")
    void cx21AutoEnqueueRacesAgainstDeleteAndArchiveSafely() throws Exception {
        autoEnqueueEnabledFlag.set(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Part A: Delete race
            UUID deleteAssetId = seedVideoAssetWithPendingJob();
            MediaAssetVersion dv1 = versionAdapter.findByAssetIdAndVersionNumber(deleteAssetId, 1).orElseThrow();
            CyclicBarrier b1 = new CyclicBarrier(2);
            Future<AutoPackagingEnqueueResult> enqA = pool.submit(() -> {
                b1.await(10, TimeUnit.SECONDS);
                return optInEnqueueCoordinator.enqueueVideoVersionIfEnabledSafely(deleteAssetId, 1, dv1.getId());
            });
            Future<Void> delA = pool.submit(() -> {
                b1.await(10, TimeUnit.SECONDS);
                deleteAssetUseCase.execute(new DeleteMediaAssetCommand(deleteAssetId));
                return null;
            });
            enqA.get(15, TimeUnit.SECONDS);
            delA.get(15, TimeUnit.SECONDS);

            assertThat(assetAdapter.findById(deleteAssetId).orElseThrow().getStatus()).isEqualTo(MediaAssetStatus.DELETED);
            assertThat(findActiveJobInTx(dv1.getId())).isEmpty();

            // Part B: Archive race
            UUID archiveAssetId = seedVideoAssetWithPendingJob();
            MediaAssetVersion av1 = versionAdapter.findByAssetIdAndVersionNumber(archiveAssetId, 1).orElseThrow();
            CyclicBarrier b2 = new CyclicBarrier(2);
            Future<AutoPackagingEnqueueResult> enqB = pool.submit(() -> {
                b2.await(10, TimeUnit.SECONDS);
                return optInEnqueueCoordinator.enqueueVideoVersionIfEnabledSafely(archiveAssetId, 1, av1.getId());
            });
            Future<Void> archB = pool.submit(() -> {
                b2.await(10, TimeUnit.SECONDS);
                archiveAssetUseCase.execute(new ArchiveMediaAssetCommand(archiveAssetId));
                return null;
            });
            enqB.get(15, TimeUnit.SECONDS);
            archB.get(15, TimeUnit.SECONDS);

            assertThat(assetAdapter.findById(archiveAssetId).orElseThrow().getStatus()).isEqualTo(MediaAssetStatus.ARCHIVED);
            assertThat(findActiveJobInTx(av1.getId())).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-22: READY completion racing against duplicate auto-enqueue creates zero redundant active jobs")
    void cx22ReadyCompletionVsDuplicateAutoEnqueue() throws Exception {
        autoEnqueueEnabledFlag.set(true);
        UUID assetId = seedVideoAssetWithPendingJob();
        MediaAssetVersion v1 = versionAdapter.findByAssetIdAndVersionNumber(assetId, 1).orElseThrow();
        MediaVideoPackagingJob job = createJobUseCase.execute(assetId);
        ClaimedVideoPackagingLease lease = claimJobUseCase.claimNext(
                "w-cx22", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Void> completeF = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                completeJobUseCase.completeWithVerifiedReceipt(
                        assetId, job.getId(), lease.packageId(), "w-cx22", lease.leaseGeneration(),
                        new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", BASE_TIME)
                );
                return null;
            });
            Future<AutoPackagingEnqueueResult> enqF = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return optInEnqueueCoordinator.enqueueVideoVersionIfEnabledSafely(assetId, 1, v1.getId());
            });

            completeF.get(15, TimeUnit.SECONDS);
            AutoPackagingEnqueueResult res = enqF.get(15, TimeUnit.SECONDS);

            assertThat(res.status()).isIn(AutoPackagingEnqueueStatus.REUSED_ACTIVE, AutoPackagingEnqueueStatus.SKIPPED_READY);
            assertThat(findActiveJobInTx(v1.getId())).isEmpty();
            assertThat(readJobsInTx(assetId)).hasSize(1);
            assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetId)).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CX-23: Uncommitted transaction visibility, rollback isolation, and terminal FAILED deduplication in MySQL")
    void cx23TransactionVisibilityAndTerminalFailureDeduplication() throws Exception {
        autoEnqueueEnabledFlag.set(true);
        UUID assetId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch insertedUncommitted = new CountDownLatch(1);
        CountDownLatch checkedUncommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Void> txWriter = pool.submit(() -> {
                try {
                    txTemplate.executeWithoutResult(status -> {
                        MediaAsset a = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, BASE_TIME);
                        MediaAssetVersion v = MediaAssetVersion.create(
                                v1Id, assetId, 1,
                                StorageLocation.of(R2_PROVIDER, StorageKey.of("video/cx23/" + assetId + ".mp4")),
                                null, SOURCE_SHA256, MimeType.of("video/mp4"), 1024L, "cx23.mp4", BASE_TIME
                        );
                        assetAdapter.save(a);
                        versionAdapter.save(v);
                        insertedUncommitted.countDown();
                        try {
                            checkedUncommitted.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        status.setRollbackOnly();
                    });
                } finally {
                    insertedUncommitted.countDown();
                }
                return null;
            });

            assertThat(insertedUncommitted.await(10, TimeUnit.SECONDS)).isTrue();
            // Concurrent coordinator cannot observe uncommitted registration
            assertThat(optInEnqueueCoordinator.enqueueVideoVersionIfEnabledSafely(assetId, 1, v1Id).status())
                    .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_INELIGIBLE_ASSET);
            checkedUncommitted.countDown();
            txWriter.get(15, TimeUnit.SECONDS);
            assertThat(packagingJobAdapter.existsByAssetId(assetId)).isFalse();
        } finally {
            pool.shutdownNow();
        }

        // Now commit asset, create & permanently fail job, verify auto-enqueue skips retry cleanly
        UUID committedAsset = seedVideoAssetWithPendingJob();
        MediaAssetVersion committedV1 = versionAdapter.findByAssetIdAndVersionNumber(committedAsset, 1).orElseThrow();
        AutoPackagingEnqueueResult created = optInEnqueueCoordinator.enqueueVideoVersionIfEnabledSafely(
                committedAsset, 1, committedV1.getId()
        );
        assertThat(created.status()).isEqualTo(AutoPackagingEnqueueStatus.CREATED);

        ClaimedVideoPackagingLease lease = claimJobUseCase.claimNext(
                "w-cx23", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket"
        ).orElseThrow();
        failJobUseCase.executePermanentFailure(
                committedAsset, lease.jobId(), "w-cx23", lease.leaseGeneration(), "Invalid codec"
        );

        AutoPackagingEnqueueResult retryAfterFailed = optInEnqueueCoordinator.enqueueVideoVersionIfEnabledSafely(
                committedAsset, 1, committedV1.getId()
        );
        assertThat(retryAfterFailed.status()).isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_TERMINAL_JOB);
        assertThat(readJobsInTx(committedAsset)).hasSize(1);
    }

    private Optional<MediaVideoPackagingJob> findActiveJobInTx(UUID sourceVersionId) {
        return new TransactionTemplate(transactionManager)
                .execute(s -> packagingJobAdapter.findActiveBySourceVersionIdForUpdate(sourceVersionId));
    }

    private List<MediaVideoPackagingJob> readJobsInTx(UUID assetId) {
        return new TransactionTemplate(transactionManager)
                .execute(s -> packagingJobAdapter.findByAssetIdForUpdateOrderByIdAsc(assetId));
    }
}
