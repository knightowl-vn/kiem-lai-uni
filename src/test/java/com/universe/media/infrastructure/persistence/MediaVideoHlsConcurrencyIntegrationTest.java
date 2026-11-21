package com.universe.media.infrastructure.persistence;

import com.universe.media.application.asset.PurgeMediaAssetMetadataService;
import com.universe.media.application.exceptions.StalePackagingLeaseException;
import com.universe.media.application.hls.CancelOrSupersedeMediaVideoPackagingUseCase;
import com.universe.media.application.hls.ClaimMediaVideoPackagingJobUseCase;
import com.universe.media.application.hls.ClaimedVideoPackagingLease;
import com.universe.media.application.hls.CompleteVerifiedHlsPackagingUseCase;
import com.universe.media.application.hls.CreateMediaVideoPackagingJobUseCase;
import com.universe.media.application.hls.FailMediaVideoPackagingJobUseCase;
import com.universe.media.application.hls.HeartbeatMediaVideoPackagingJobUseCase;
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
        ClaimMediaVideoPackagingJobUseCase.class,
        HeartbeatMediaVideoPackagingJobUseCase.class,
        FailMediaVideoPackagingJobUseCase.class,
        CompleteVerifiedHlsPackagingUseCase.class,
        CancelOrSupersedeMediaVideoPackagingUseCase.class,
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
    private ClaimMediaVideoPackagingJobUseCase claimJobUseCase;

    @Autowired
    private HeartbeatMediaVideoPackagingJobUseCase heartbeatUseCase;

    @Autowired
    private FailMediaVideoPackagingJobUseCase failJobUseCase;

    @Autowired
    private CompleteVerifiedHlsPackagingUseCase completeJobUseCase;

    @Autowired
    private CancelOrSupersedeMediaVideoPackagingUseCase cancelOrSupersedeUseCase;

    private final List<UUID> createdAssetIds = new ArrayList<>();

    @BeforeEach
    void resetClock() {
        clockRef.set(BASE_TIME);
    }

    @AfterEach
    void cleanUp() {
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
}
