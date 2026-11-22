package com.universe.media.application.hls;

import com.universe.media.application.exceptions.StalePackagingLeaseException;
import com.universe.media.application.ports.MediaAssetCandidate;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackagePurgeCandidate;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.application.ports.MediaVideoPackagingJobCandidate;
import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVideoHlsPackage;
import com.universe.media.domain.MediaVideoHlsPackageStatus;
import com.universe.media.domain.MediaVideoPackagingJob;
import com.universe.media.domain.MediaVideoPackagingJobStatus;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.PackagingFailureDisposition;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HlsRetentionAndFailurePolicyUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");
    private static final StorageProviderId R2_HLS = StorageProviderId.of("r2-hls");
    private static final ContentHash MANIFEST_SHA256 =
            ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2");

    private static final class InMemoryStores {
        final Map<UUID, MediaAsset> assets = new HashMap<>();
        final Map<UUID, MediaVideoPackagingJob> jobs = new HashMap<>();
        final Map<UUID, MediaVideoHlsPackage> packages = new HashMap<>();

        final MediaAssetRepositoryPort assetRepo = new MediaAssetRepositoryPort() {
            @Override public MediaAsset save(MediaAsset asset) {
                assets.put(asset.getId(), asset);
                return asset;
            }
            @Override public Optional<MediaAsset> findById(UUID id) {
                return Optional.ofNullable(assets.get(id));
            }
            @Override public Optional<MediaAsset> findByIdForUpdate(UUID id) {
                return Optional.ofNullable(assets.get(id));
            }
            @Override public void deleteById(UUID id) {
                assets.remove(id);
            }
            @Override public List<MediaAsset> findExpiredDeleted(Instant cutoff, int limit) {
                return List.of();
            }
            @Override public List<MediaAssetCandidate> findActiveByClientTagKeyset(
                    String clientTag, Instant upperBound, Instant lastCreatedAt, UUID lastAssetId, int limit) {
                return List.of();
            }
        };

        final MediaVideoPackagingJobRepositoryPort jobRepo = new MediaVideoPackagingJobRepositoryPort() {
            @Override public MediaVideoPackagingJob save(MediaVideoPackagingJob job) {
                jobs.put(job.getId(), job);
                return job;
            }
            @Override public Optional<MediaVideoPackagingJob> findById(UUID id) {
                return Optional.ofNullable(jobs.get(id));
            }
            @Override public Optional<MediaVideoPackagingJob> findByIdForUpdate(UUID id) {
                return Optional.ofNullable(jobs.get(id));
            }
            @Override public Optional<MediaVideoPackagingJob> findActiveBySourceVersionIdForUpdate(UUID sourceVersionId) {
                return jobs.values().stream()
                        .filter(j -> j.getSourceVersionId().equals(sourceVersionId)
                                && (j.getStatus() == MediaVideoPackagingJobStatus.PENDING
                                || j.getStatus() == MediaVideoPackagingJobStatus.LEASED))
                        .findFirst();
            }
            @Override public Optional<MediaVideoPackagingJobStatus> findHighestPriorityTerminalStatusBySourceVersionId(UUID sourceVersionId) {
                if (jobs.values().stream().anyMatch(j -> j.getSourceVersionId().equals(sourceVersionId)
                        && j.getStatus() == MediaVideoPackagingJobStatus.SUCCEEDED)) {
                    return Optional.of(MediaVideoPackagingJobStatus.SUCCEEDED);
                }
                if (jobs.values().stream().anyMatch(j -> j.getSourceVersionId().equals(sourceVersionId)
                        && j.getStatus() == MediaVideoPackagingJobStatus.FAILED)) {
                    return Optional.of(MediaVideoPackagingJobStatus.FAILED);
                }
                if (jobs.values().stream().anyMatch(j -> j.getSourceVersionId().equals(sourceVersionId)
                        && j.getStatus() == MediaVideoPackagingJobStatus.CANCELLED)) {
                    return Optional.of(MediaVideoPackagingJobStatus.CANCELLED);
                }
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
                        .filter(j -> j.isClaimableAt(now))
                        .limit(limit)
                        .map(j -> new MediaVideoPackagingJobCandidate(j.getId(), j.getAssetId()))
                        .toList();
            }
            @Override public int updateHeartbeatIfLeaseMatches(
                    UUID jobId, String leaseOwner, long leaseGeneration,
                    Instant now, Instant heartbeatAt, Instant leaseExpiresAt, Instant updatedAt) {
                return 0;
            }
            @Override public boolean existsByAssetId(UUID assetId) {
                return jobs.values().stream().anyMatch(j -> j.getAssetId().equals(assetId));
            }
        };

        final MediaVideoHlsPackageRepositoryPort packageRepo = new MediaVideoHlsPackageRepositoryPort() {
            @Override public MediaVideoHlsPackage save(MediaVideoHlsPackage pkg) {
                packages.put(pkg.getId(), pkg);
                return pkg;
            }
            @Override public Optional<MediaVideoHlsPackage> findById(UUID id) {
                return Optional.ofNullable(packages.get(id));
            }
            @Override public Optional<MediaVideoHlsPackage> findByIdForUpdate(UUID id) {
                return Optional.ofNullable(packages.get(id));
            }
            @Override public Optional<MediaVideoHlsPackage> findReadyBySourceVersionId(UUID sourceVersionId) {
                return packages.values().stream()
                        .filter(p -> p.getSourceVersionId().equals(sourceVersionId)
                                && p.getStatus() == MediaVideoHlsPackageStatus.READY)
                        .findFirst();
            }
            @Override public Optional<MediaVideoHlsPackage> findCurrentReadyByAssetId(UUID assetId) {
                MediaAsset asset = assets.get(assetId);
                if (asset == null || asset.getStatus() != MediaAssetStatus.ACTIVE) {
                    return Optional.empty();
                }
                return packages.values().stream()
                        .filter(p -> p.getAssetId().equals(assetId)
                                && p.getSourceVersionNumber() == asset.getCurrentVersionNumber()
                                && p.getStatus() == MediaVideoHlsPackageStatus.READY)
                        .findFirst();
            }
            @Override public List<MediaVideoHlsPackage> findBySourceVersionIdForUpdateOrderByIdAsc(UUID sourceVersionId) {
                return packages.values().stream()
                        .filter(p -> p.getSourceVersionId().equals(sourceVersionId))
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
                return packages.values().stream()
                        .filter(p -> p.getAssetId().equals(assetId))
                        .toList();
            }
            @Override public List<MediaVideoHlsPackagePurgeCandidate> findPurgeEligibleCandidates(Instant now, int limit) {
                return packages.values().stream()
                        .filter(p -> p.isPurgeEligibleAt(now))
                        .sorted(Comparator.comparing(MediaVideoHlsPackage::getPurgeAfter)
                                .thenComparing(p -> p.getId().toString()))
                        .limit(limit)
                        .map(p -> new MediaVideoHlsPackagePurgeCandidate(p.getId(), p.getAssetId()))
                        .toList();
            }
            @Override public boolean existsByAssetId(UUID assetId) {
                return packages.values().stream().anyMatch(p -> p.getAssetId().equals(assetId));
            }
        };
    }

    private static PlatformTransactionManager recordingRequiresNewTxManager(
            List<Integer> propagations,
            AtomicInteger commits
    ) {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                propagations.add(definition.getPropagationBehavior());
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                commits.incrementAndGet();
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }

    @Test
    @DisplayName("Claim reclaim and transient/permanent fail enforce >=24h purgeAfter while preserving 7-day supersession and READY isolation")
    void enforcesMinimum24hRetentionAnd7DaySupersessionIsolation() {
        InMemoryStores stores = new InMemoryStores();
        AtomicReference<Instant> nowRef = new AtomicReference<>(T0);
        ClockPort clock = nowRef::get;
        List<Integer> txPropagations = new ArrayList<>();
        AtomicInteger txCommits = new AtomicInteger();
        PlatformTransactionManager txMgr = recordingRequiresNewTxManager(txPropagations, txCommits);

        ClaimMediaVideoPackagingJobUseCase claimUseCase = new ClaimMediaVideoPackagingJobUseCase(
                stores.assetRepo, stores.jobRepo, stores.packageRepo, clock, txMgr
        );
        FailMediaVideoPackagingJobUseCase failUseCase = new FailMediaVideoPackagingJobUseCase(
                stores.assetRepo, stores.jobRepo, stores.packageRepo, clock
        );
        CompleteVerifiedHlsPackagingUseCase completeUseCase = new CompleteVerifiedHlsPackagingUseCase(
                stores.assetRepo, stores.jobRepo, stores.packageRepo, clock
        );

        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, T0);
        stores.assetRepo.save(asset);

        // Pre-existing READY package on same version to ensure failure attempts never corrupt READY package
        UUID existingReadyId = UUID.randomUUID();
        UUID existingReadyUuid = UUID.randomUUID();
        MediaVideoHlsPackage existingReady = MediaVideoHlsPackage.startProcessing(
                existingReadyId, assetId, versionId, 1, existingReadyUuid, 1,
                R2_HLS, "hls-bucket",
                StorageKey.of("objects/hls/" + assetId + "/v1/" + existingReadyUuid + "/"),
                6, T0
        );
        existingReady.markReady(MANIFEST_SHA256, 10_000L, 5, "[{\"height\":720}]", T0.plusSeconds(2), T0.plusSeconds(3));
        stores.packageRepo.save(existingReady);

        // Enqueue re-packaging job (maxAttempts=3)
        MediaVideoPackagingJob job = MediaVideoPackagingJob.createPending(
                UUID.randomUUID(), assetId, versionId, 1, 3, T0.plusSeconds(10)
        );
        stores.jobRepo.save(job);

        // 1. Worker-1 claims at T0+15s, lease expires without heartbeat -> Worker-2 reclaims at T0+6m
        nowRef.set(T0.plusSeconds(15));
        ClaimedVideoPackagingLease lease1 = claimUseCase.claimNext("worker-1", Duration.ofMinutes(5), R2_HLS, "hls-bucket")
                .orElseThrow();

        Instant reclaimTime = T0.plusSeconds(15).plus(Duration.ofMinutes(6));
        nowRef.set(reclaimTime);
        ClaimedVideoPackagingLease lease2 = claimUseCase.claimNext("worker-2", Duration.ofMinutes(5), R2_HLS, "hls-bucket")
                .orElseThrow();

        MediaVideoHlsPackage abandonedPkg = stores.packageRepo.findById(lease1.packageId()).orElseThrow();
        assertThat(abandonedPkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.ABANDONED);
        assertThat(abandonedPkg.getPurgeAfter()).isEqualTo(reclaimTime.plus(Duration.ofHours(24)));
        assertThat(lease2.basePrefix()).isNotEqualTo(lease1.basePrefix());
        assertThat(lease2.packageId()).isNotEqualTo(lease1.packageId());

        // 2. Worker-2 records backward-compatible transient failure -> schedules retry, package gets 24h retention
        Instant transientFailTime = reclaimTime.plusSeconds(20);
        nowRef.set(transientFailTime);
        MediaVideoPackagingJob afterTransient = failUseCase.execute(
                assetId, lease2.jobId(), "worker-2", lease2.leaseGeneration(),
                "R2 503 temporary outage", Duration.ofSeconds(30)
        );
        assertThat(afterTransient.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.PENDING);
        assertThat(afterTransient.getNextAttemptAt()).isEqualTo(transientFailTime.plusSeconds(30));
        MediaVideoHlsPackage transientFailedPkg = stores.packageRepo.findById(lease2.packageId()).orElseThrow();
        assertThat(transientFailedPkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.FAILED);
        assertThat(transientFailedPkg.getPurgeAfter()).isEqualTo(transientFailTime.plus(Duration.ofHours(24)));

        // READY package remains completely untouched
        assertThat(stores.packageRepo.findById(existingReadyId).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.READY);

        // 3. Complete a fresh re-packaging job to prove 7-day supersession on existingReady remains unchanged
        Instant claim3Time = transientFailTime.plusSeconds(40);
        nowRef.set(claim3Time);
        ClaimedVideoPackagingLease lease3 = claimUseCase.claimNext("worker-3", Duration.ofMinutes(5), R2_HLS, "hls-bucket")
                .orElseThrow();
        Instant completeTime = claim3Time.plusSeconds(10);
        nowRef.set(completeTime);
        completeUseCase.completeWithVerifiedReceipt(
                assetId, lease3.jobId(), lease3.packageId(), "worker-3", lease3.leaseGeneration(),
                new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 20_000L, 6, "[{\"height\":1080}]", completeTime)
        );
        MediaVideoHlsPackage supersededPkg = stores.packageRepo.findById(existingReadyId).orElseThrow();
        assertThat(supersededPkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.SUPERSEDED);
        assertThat(supersededPkg.getPurgeAfter()).isEqualTo(completeTime.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("Permanent failure terminates LEASED job immediately with 24h package retention, rejects stale/outdated claims, and opens REQUIRES_NEW tx via Spring proxy")
    void permanentFailureTerminatesImmediatelyThroughSpringProxyAndRejectsStaleOrOutdatedAssets() {
        InMemoryStores stores = new InMemoryStores();
        AtomicReference<Instant> nowRef = new AtomicReference<>(T0);
        List<Integer> txPropagations = new ArrayList<>();
        AtomicInteger txCommits = new AtomicInteger();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(MediaAssetRepositoryPort.class, () -> stores.assetRepo);
            ctx.registerBean(MediaVideoPackagingJobRepositoryPort.class, () -> stores.jobRepo);
            ctx.registerBean(MediaVideoHlsPackageRepositoryPort.class, () -> stores.packageRepo);
            ctx.registerBean(ClockPort.class, () -> nowRef::get);
            ctx.registerBean(PlatformTransactionManager.class, () -> recordingRequiresNewTxManager(txPropagations, txCommits));
            ctx.register(UseCaseTxTestConfig.class);
            ctx.refresh();

            ClaimMediaVideoPackagingJobUseCase claimUseCase = ctx.getBean(ClaimMediaVideoPackagingJobUseCase.class);
            FailMediaVideoPackagingJobUseCase failUseCase = ctx.getBean(FailMediaVideoPackagingJobUseCase.class);

            UUID assetId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, T0);
            stores.assetRepo.save(asset);

            MediaVideoPackagingJob job = MediaVideoPackagingJob.createPending(
                    UUID.randomUUID(), assetId, versionId, 1, 3, T0
            );
            stores.jobRepo.save(job);

            ClaimedVideoPackagingLease lease = claimUseCase.claimNext("worker-a", Duration.ofMinutes(5), R2_HLS, "hls-bucket")
                    .orElseThrow();

            // Stale lease generation on permanent failure changes neither job nor package
            Instant failAt = T0.plusSeconds(30);
            nowRef.set(failAt);
            assertThatThrownBy(() -> failUseCase.executePermanentFailure(
                    assetId, lease.jobId(), "worker-a", 99L, "HDR10 unsupported"
            )).isInstanceOf(StalePackagingLeaseException.class);
            assertThat(stores.jobRepo.findById(lease.jobId()).orElseThrow().getStatus())
                    .isEqualTo(MediaVideoPackagingJobStatus.LEASED);
            assertThat(stores.packageRepo.findById(lease.packageId()).orElseThrow().getStatus())
                    .isEqualTo(MediaVideoHlsPackageStatus.PROCESSING);

            // Expired lease on permanent failure is rejected cleanly
            Instant expiredAt = T0.plus(Duration.ofMinutes(5));
            nowRef.set(expiredAt);
            assertThatThrownBy(() -> failUseCase.execute(
                    assetId, lease.jobId(), "worker-a", lease.leaseGeneration(),
                    PackagingFailureDisposition.PERMANENT_NON_RETRYABLE, "HDR10 unsupported", null
            )).isInstanceOf(StalePackagingLeaseException.class);

            // Superseded / deleted asset is never blindly marked as permanent media failure
            asset.registerNextVersion(T0.plusSeconds(20)); // asset is now v2 while job is v1
            nowRef.set(T0.plusSeconds(40));
            assertThatThrownBy(() -> failUseCase.executePermanentFailure(
                    assetId, lease.jobId(), "worker-a", lease.leaseGeneration(), "Version mismatch defect"
            )).isInstanceOf(StalePackagingLeaseException.class)
                    .hasMessageContaining("superseded");
            assertThat(stores.jobRepo.findById(lease.jobId()).orElseThrow().getStatus())
                    .isEqualTo(MediaVideoPackagingJobStatus.LEASED);

            // Restore current version back to fresh asset-2 to execute genuine permanent failure
            UUID asset2Id = UUID.randomUUID();
            UUID ver2Id = UUID.randomUUID();
            stores.assetRepo.save(MediaAsset.registerInitial(asset2Id, MediaType.VIDEO, MediaVisibility.PUBLIC, T0));
            MediaVideoPackagingJob job2 = MediaVideoPackagingJob.createPending(
                    UUID.randomUUID(), asset2Id, ver2Id, 1, 3, T0.plusSeconds(40)
            );
            stores.jobRepo.save(job2);
            ClaimedVideoPackagingLease lease2 = claimUseCase.claimNext("worker-b", Duration.ofMinutes(5), R2_HLS, "hls-bucket")
                    .orElseThrow();

            Instant permTime = T0.plusSeconds(50);
            nowRef.set(permTime);
            MediaVideoPackagingJob failedJob = failUseCase.executePermanentFailure(
                    asset2Id, lease2.jobId(), "worker-b", lease2.leaseGeneration(),
                    "Unsupported 10-bit HDR profile (bt2020)"
            );

            assertThat(failedJob.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.FAILED);
            assertThat(failedJob.getAttemptCount()).isEqualTo(1);
            assertThat(failedJob.getMaxAttempts()).isEqualTo(3);
            assertThat(failedJob.getLeaseOwner()).isNull();
            assertThat(failedJob.getLeaseExpiresAt()).isNull();
            assertThat(failedJob.getLastError()).contains("Unsupported 10-bit HDR profile");

            MediaVideoHlsPackage failedPkg = stores.packageRepo.findById(lease2.packageId()).orElseThrow();
            assertThat(failedPkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.FAILED);
            assertThat(failedPkg.getPurgeAfter()).isEqualTo(permTime.plus(Duration.ofHours(24)));

            // Candidate selection excludes terminal FAILED job2 forever, while stale superseded job1 cancels on reclaim
            assertThat(stores.jobRepo.findClaimableCandidates(permTime.plus(Duration.ofDays(30)), 10))
                    .noneMatch(c -> c.jobId().equals(job2.getId()));
            nowRef.set(permTime.plus(Duration.ofDays(30)));
            assertThat(claimUseCase.claimNext("worker-c", Duration.ofMinutes(5), R2_HLS, "hls-bucket")).isEmpty();
            assertThat(stores.jobRepo.findById(job.getId()).orElseThrow().getStatus())
                    .isEqualTo(MediaVideoPackagingJobStatus.CANCELLED);
            assertThat(stores.jobRepo.findClaimableCandidates(permTime.plus(Duration.ofDays(30)), 10)).isEmpty();

            // Verify Spring AOP opened REQUIRES_NEW for each failUseCase invocation cleanly (no self-invocation bypass)
            assertThat(txPropagations).isNotEmpty();
            assertThat(txPropagations).allMatch(p -> p == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    @Test
    @DisplayName("Claim captures fresh timestamp strictly AFTER acquiring DB locks, revalidates eligibility, and propagates configurable segmentDurationSeconds")
    void claimCapturesPostLockTimestampAndHonorsSegmentDurationOverload() {
        InMemoryStores stores = new InMemoryStores();
        AtomicReference<Instant> nowRef = new AtomicReference<>(T0);
        ClockPort clock = nowRef::get;
        AtomicInteger txCommits = new AtomicInteger();
        PlatformTransactionManager txMgr = recordingRequiresNewTxManager(new ArrayList<>(), txCommits);

        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        stores.assetRepo.save(MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, T0));
        MediaVideoPackagingJob job = MediaVideoPackagingJob.createPending(
                UUID.randomUUID(), assetId, versionId, 1, 3, T0
        );
        stores.jobRepo.save(job);

        // Simulate lock wait inside findByIdForUpdate: clock advances 120s while waiting for MediaAsset lock
        MediaAssetRepositoryPort lockAdvancingAssetRepo = new MediaAssetRepositoryPort() {
            @Override public MediaAsset save(MediaAsset asset) { return stores.assetRepo.save(asset); }
            @Override public Optional<MediaAsset> findById(UUID id) { return stores.assetRepo.findById(id); }
            @Override public Optional<MediaAsset> findByIdForUpdate(UUID id) {
                nowRef.set(T0.plusSeconds(120));
                return stores.assetRepo.findByIdForUpdate(id);
            }
            @Override public void deleteById(UUID id) { stores.assetRepo.deleteById(id); }
            @Override public List<MediaAsset> findExpiredDeleted(Instant cutoff, int limit) { return List.of(); }
            @Override public List<MediaAssetCandidate> findActiveByClientTagKeyset(
                    String c, Instant u, Instant lc, UUID la, int l) { return List.of(); }
        };

        ClaimMediaVideoPackagingJobUseCase claimUseCase = new ClaimMediaVideoPackagingJobUseCase(
                lockAdvancingAssetRepo, stores.jobRepo, stores.packageRepo, clock, txMgr
        );

        ClaimedVideoPackagingLease customLease = claimUseCase.claimNext(
                "worker-dur-4", Duration.ofMinutes(5), R2_HLS, "hls-bucket", 4
        ).orElseThrow();

        // Lease expiry MUST use post-lock T0+120s (not pre-lock T0), and package/lease must match 4s segment duration
        assertThat(customLease.leaseExpiresAt()).isEqualTo(T0.plusSeconds(120).plus(Duration.ofMinutes(5)));
        assertThat(customLease.segmentDurationSeconds()).isEqualTo(4);
        MediaVideoPackagingJob persistedJob = stores.jobRepo.findById(job.getId()).orElseThrow();
        assertThat(persistedJob.getLeaseExpiresAt()).isEqualTo(customLease.leaseExpiresAt());
        MediaVideoHlsPackage persistedPkg = stores.packageRepo.findById(customLease.packageId()).orElseThrow();
        assertThat(persistedPkg.getSegmentDurationSeconds()).isEqualTo(4);

        // Invalid segment durations fail closed
        assertThatThrownBy(() -> claimUseCase.claimNext("w", Duration.ofMinutes(5), R2_HLS, "b", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimUseCase.claimNext("w", Duration.ofMinutes(5), R2_HLS, "b", 11))
                .isInstanceOf(IllegalArgumentException.class);

        // If lease is extended via heartbeat while candidate lock-waits, post-lock revalidation rejects reclaim
        UUID asset2Id = UUID.randomUUID();
        UUID ver2Id = UUID.randomUUID();
        stores.assetRepo.save(MediaAsset.registerInitial(asset2Id, MediaType.VIDEO, MediaVisibility.PUBLIC, T0));
        MediaVideoPackagingJob job2 = MediaVideoPackagingJob.createPending(
                UUID.randomUUID(), asset2Id, ver2Id, 1, 3, T0
        );
        // Set LEASED expired at T0+300s so scanNow=T0+310s picks it up, then simulate concurrent heartbeat before Job lock
        job2.claimOrReclaimLease("worker-alive", UUID.randomUUID(), Duration.ofSeconds(300), T0);
        stores.jobRepo.save(job2);
        nowRef.set(T0.plusSeconds(310));

        MediaVideoPackagingJobRepositoryPort concurrentHeartbeatJobRepo = new MediaVideoPackagingJobRepositoryPort() {
            @Override public MediaVideoPackagingJob save(MediaVideoPackagingJob j) { return stores.jobRepo.save(j); }
            @Override public Optional<MediaVideoPackagingJob> findById(UUID id) { return stores.jobRepo.findById(id); }
            @Override public Optional<MediaVideoPackagingJob> findByIdForUpdate(UUID id) {
                Optional<MediaVideoPackagingJob> found = stores.jobRepo.findByIdForUpdate(id);
                // Rehydrate extended expiry as if heartbeat committed right before we took the row lock
                found.ifPresent(j -> {
                    MediaVideoPackagingJob refreshed = MediaVideoPackagingJob.rehydrate(
                            j.getId(), j.getAssetId(), j.getSourceVersionId(), j.getSourceVersionNumber(),
                            j.getStatus(), j.getLeaseGeneration(), j.getCurrentPackageId(), j.getAttemptCount(),
                            j.getMaxAttempts(), j.getLeaseOwner(), T0.plusSeconds(600), T0.plusSeconds(310),
                            j.getNextAttemptAt(), j.getLastError(), j.getCreatedAt(), T0.plusSeconds(310),
                            j.getCompletedAt(), j.getPersistenceVersion()
                    );
                    stores.jobRepo.save(refreshed);
                });
                return stores.jobRepo.findByIdForUpdate(id);
            }
            @Override public Optional<MediaVideoPackagingJob> findActiveBySourceVersionIdForUpdate(UUID s) { return stores.jobRepo.findActiveBySourceVersionIdForUpdate(s); }
            @Override public Optional<MediaVideoPackagingJobStatus> findHighestPriorityTerminalStatusBySourceVersionId(UUID s) { return stores.jobRepo.findHighestPriorityTerminalStatusBySourceVersionId(s); }
            @Override public List<MediaVideoPackagingJob> findByAssetIdForUpdateOrderByIdAsc(UUID a) { return stores.jobRepo.findByAssetIdForUpdateOrderByIdAsc(a); }
            @Override public List<MediaVideoPackagingJobCandidate> findClaimableCandidates(Instant now, int limit) { return stores.jobRepo.findClaimableCandidates(now, limit); }
            @Override public int updateHeartbeatIfLeaseMatches(UUID j, String l, long g, Instant n, Instant h, Instant le, Instant u) { return 0; }
            @Override public boolean existsByAssetId(UUID a) { return stores.jobRepo.existsByAssetId(a); }
        };

        ClaimMediaVideoPackagingJobUseCase racedClaimUseCase = new ClaimMediaVideoPackagingJobUseCase(
                stores.assetRepo, concurrentHeartbeatJobRepo, stores.packageRepo, clock, txMgr
        );
        assertThat(racedClaimUseCase.claimNext("worker-stealer", Duration.ofMinutes(5), R2_HLS, "hls-bucket", 8))
                .isEmpty();
    }

    @Configuration
    @EnableTransactionManagement
    static class UseCaseTxTestConfig {
        @Bean
        ClaimMediaVideoPackagingJobUseCase claimMediaVideoPackagingJobUseCase(
                MediaAssetRepositoryPort a,
                MediaVideoPackagingJobRepositoryPort j,
                MediaVideoHlsPackageRepositoryPort p,
                ClockPort c,
                PlatformTransactionManager tm
        ) {
            return new ClaimMediaVideoPackagingJobUseCase(a, j, p, c, tm);
        }

        @Bean
        FailMediaVideoPackagingJobUseCase failMediaVideoPackagingJobUseCase(
                MediaAssetRepositoryPort a,
                MediaVideoPackagingJobRepositoryPort j,
                MediaVideoHlsPackageRepositoryPort p,
                ClockPort c
        ) {
            return new FailMediaVideoPackagingJobUseCase(a, j, p, c);
        }
    }
}
