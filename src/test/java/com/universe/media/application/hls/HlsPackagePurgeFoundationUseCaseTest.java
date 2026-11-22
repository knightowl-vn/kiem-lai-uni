package com.universe.media.application.hls;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.StalePurgeClaimException;
import com.universe.media.application.ports.MediaAssetCandidate;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackagePurgeCandidate;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVideoHlsPackage;
import com.universe.media.domain.MediaVideoHlsPackageStatus;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
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

class HlsPackagePurgeFoundationUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-11-20T00:00:00Z");
    private static final StorageProviderId R2_HLS = StorageProviderId.of("r2-hls");
    private static final ContentHash MANIFEST_SHA256 =
            ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2");

    private static final class InMemoryPurgeStores {
        final Map<UUID, MediaAsset> assets = new HashMap<>();
        final Map<UUID, MediaVideoHlsPackage> packages = new HashMap<>();
        final List<String> lockTrace = new ArrayList<>();
        boolean mutateCandidateToReadyBeforeLock = false;
        UUID candidateToMutateId = null;

        MediaVideoHlsPackage simulateSaveBump(MediaVideoHlsPackage pkg) {
            long nextVer = pkg.getPersistenceVersion() == null ? 0L : pkg.getPersistenceVersion() + 1L;
            MediaVideoHlsPackage stored = MediaVideoHlsPackage.rehydrate(
                    pkg.getId(), pkg.getAssetId(), pkg.getSourceVersionId(), pkg.getSourceVersionNumber(),
                    pkg.getPackageUuid(), pkg.getAttemptNumber(), pkg.getStorageProviderId(),
                    pkg.getStorageBucketName(), pkg.getBasePrefix(), pkg.getMasterManifestKey(),
                    pkg.getHlsVersion(), pkg.getSegmentFormat(), pkg.getSegmentDurationSeconds(),
                    pkg.getRenditionsJson(), pkg.getMasterManifestSha256(), pkg.getTotalSizeBytes(),
                    pkg.getObjectCount(), pkg.getStatus(), pkg.getFailureReason(),
                    pkg.getCreatedAt(), pkg.getVerifiedAt(), pkg.getReadyAt(),
                    pkg.getSupersededAt(), pkg.getPurgeAfter(), pkg.getUpdatedAt(), nextVer
            );
            packages.put(stored.getId(), stored);
            return stored;
        }

        final MediaAssetRepositoryPort assetRepo = new MediaAssetRepositoryPort() {
            @Override public MediaAsset save(MediaAsset asset) {
                assets.put(asset.getId(), asset);
                return asset;
            }
            @Override public Optional<MediaAsset> findById(UUID id) {
                return Optional.ofNullable(assets.get(id));
            }
            @Override public Optional<MediaAsset> findByIdForUpdate(UUID id) {
                lockTrace.add("ASSET:" + id);
                if (mutateCandidateToReadyBeforeLock && candidateToMutateId != null) {
                    mutateCandidateToReadyBeforeLock = false;
                    MediaVideoHlsPackage p = packages.get(candidateToMutateId);
                    if (p != null && p.getStatus() == MediaVideoHlsPackageStatus.PROCESSING) {
                        p.markReady(MANIFEST_SHA256, 4096L, 5, "[{\"height\":720}]", T0.plusSeconds(1), T0.plusSeconds(2));
                    }
                }
                return Optional.ofNullable(assets.get(id));
            }
            @Override public void deleteById(UUID id) { assets.remove(id); }
            @Override public List<MediaAsset> findExpiredDeleted(Instant cutoff, int limit) { return List.of(); }
            @Override public List<MediaAssetCandidate> findActiveByClientTagKeyset(
                    String clientTag, Instant upperBound, Instant lastCreatedAt, UUID lastAssetId, int limit) {
                return List.of();
            }
        };

        final MediaVideoHlsPackageRepositoryPort packageRepo = new MediaVideoHlsPackageRepositoryPort() {
            @Override public MediaVideoHlsPackage save(MediaVideoHlsPackage pkg) {
                return simulateSaveBump(pkg);
            }
            @Override public Optional<MediaVideoHlsPackage> findById(UUID id) {
                return Optional.ofNullable(packages.get(id));
            }
            @Override public Optional<MediaVideoHlsPackage> findByIdForUpdate(UUID id) {
                lockTrace.add("PKG:" + id);
                return Optional.ofNullable(packages.get(id));
            }
            @Override public Optional<MediaVideoHlsPackage> findReadyBySourceVersionId(UUID sourceVersionId) {
                return packages.values().stream()
                        .filter(p -> p.getSourceVersionId().equals(sourceVersionId)
                                && p.getStatus() == MediaVideoHlsPackageStatus.READY)
                        .findFirst();
            }
            @Override public Optional<MediaVideoHlsPackage> findCurrentReadyByAssetId(UUID assetId) {
                return packages.values().stream()
                        .filter(p -> p.getAssetId().equals(assetId) && p.getStatus() == MediaVideoHlsPackageStatus.READY)
                        .findFirst();
            }
            @Override public List<MediaVideoHlsPackage> findBySourceVersionIdForUpdateOrderByIdAsc(UUID sourceVersionId) {
                return packages.values().stream().filter(p -> p.getSourceVersionId().equals(sourceVersionId)).toList();
            }
            @Override public List<MediaVideoHlsPackage> findByAssetIdForUpdateOrderByIdAsc(UUID assetId) {
                return packages.values().stream().filter(p -> p.getAssetId().equals(assetId)).toList();
            }
            @Override public List<MediaVideoHlsPackage> findByAssetId(UUID assetId) {
                return packages.values().stream().filter(p -> p.getAssetId().equals(assetId)).toList();
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

    private static MediaVideoHlsPackage failedPkg(UUID pkgId, UUID assetId, Instant createdAt, Instant failedAt, Instant storedPurgeAfter) {
        UUID pkgUuid = UUID.randomUUID();
        return MediaVideoHlsPackage.rehydrate(
                pkgId, assetId, UUID.randomUUID(), 1, pkgUuid, 1,
                R2_HLS, "hls-bucket",
                StorageKey.of("objects/hls/" + assetId + "/v1/" + pkgUuid + "/"),
                StorageKey.of("objects/hls/" + assetId + "/v1/" + pkgUuid + "/master.m3u8"),
                7, "FMP4", 6, null, null, null, null,
                MediaVideoHlsPackageStatus.FAILED, "Prior attempt error",
                createdAt, null, null, null, storedPurgeAfter, failedAt, 1L
        );
    }

    @Test
    @DisplayName("Claim orders deterministically, avoids legacy starvation before limit, enforces Asset->Pkg locks, isolates attempts, and has zero R2/scheduler wiring")
    void claimOrdersDeterministicallyAvoidsLegacyStarvationAndPreservesReadyIsolation() {
        // Verify dormancy & absence of @Scheduled methods and storage port dependency
        for (Method m : ClaimHlsPackagePurgeUseCase.class.getDeclaredMethods()) {
            assertThat(m.isAnnotationPresent(Scheduled.class)).isFalse();
        }
        for (Method m : CompleteHlsPackagePurgeUseCase.class.getDeclaredMethods()) {
            assertThat(m.isAnnotationPresent(Scheduled.class)).isFalse();
        }

        InMemoryPurgeStores stores = new InMemoryPurgeStores();
        AtomicReference<Instant> nowRef = new AtomicReference<>(T0.plus(Duration.ofHours(25)));
        List<Integer> propagations = new ArrayList<>();
        PlatformTransactionManager txMgr = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                propagations.add(definition.getPropagationBehavior());
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) {}
            @Override public void rollback(TransactionStatus status) {}
        };

        ClaimHlsPackagePurgeUseCase claimUseCase = new ClaimHlsPackagePurgeUseCase(
                stores.assetRepo, stores.packageRepo, nowRef::get, txMgr
        );

        UUID assetId = UUID.randomUUID();
        stores.assetRepo.save(MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, T0));

        // 5 recent legacy FAILED rows (updatedAt = T0+5h, stored purgeAfter = T0+5h < now, but NOT yet past 24h floor!)
        for (int i = 0; i < 5; i++) {
            Instant recent = T0.plus(Duration.ofHours(5)).plusSeconds(i);
            MediaVideoHlsPackage legacyNotYetPastFloor = failedPkg(UUID.randomUUID(), assetId, T0, recent, recent);
            stores.packages.put(legacyNotYetPastFloor.getId(), legacyNotYetPastFloor);
        }

        // 1 truly eligible FAILED row at T0 (updatedAt = T0, now = T0+25h -> eligible!)
        UUID eligibleId = UUID.randomUUID();
        MediaVideoHlsPackage eligible = failedPkg(eligibleId, assetId, T0, T0, T0.plus(Duration.ofHours(24)));
        stores.packages.put(eligible.getId(), eligible);

        // Concurrent READY package attempt on same asset must remain untouched
        UUID readyId = UUID.randomUUID();
        UUID readyUuid = UUID.randomUUID();
        MediaVideoHlsPackage ready = MediaVideoHlsPackage.startProcessing(
                readyId, assetId, eligible.getSourceVersionId(), 1, readyUuid, 2,
                R2_HLS, "hls-bucket",
                StorageKey.of("objects/hls/" + assetId + "/v1/" + readyUuid + "/"),
                6, T0
        );
        ready.markReady(MANIFEST_SHA256, 8192L, 5, "[{\"height\":720}]", T0.plusSeconds(2), T0.plusSeconds(3));
        stores.packages.put(readyId, ready);

        // Even with candidateBatchSize=1, truly eligible row is NOT starved behind the 5 recent legacy rows!
        ClaimedHlsPackagePurgeLease lease = claimUseCase.claimNext(Duration.ofMinutes(5), 1).orElseThrow();
        assertThat(lease.packageId()).isEqualTo(eligibleId);
        assertThat(lease.assetId()).isEqualTo(assetId);
        assertThat(lease.storageProviderId()).isEqualTo(R2_HLS);
        assertThat(lease.storageBucketName()).isEqualTo("hls-bucket");
        assertThat(lease.basePrefix()).isEqualTo(eligible.getBasePrefix());
        assertThat(lease.claimVersion()).isEqualTo(2L);
        assertThat(lease.claimExpiresAt()).isEqualTo(nowRef.get().plus(Duration.ofMinutes(5)));

        // Lock order verified: ASSET locked strictly before PKG
        assertThat(stores.lockTrace).containsExactly("ASSET:" + assetId, "PKG:" + eligibleId);
        assertThat(propagations).containsExactly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(stores.packageRepo.findById(readyId).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.READY);

        // Missing asset fails closed cleanly without mutating orphaned row
        UUID missingAssetId = UUID.randomUUID();
        UUID orphanPkgId = UUID.randomUUID();
        stores.packages.put(orphanPkgId, failedPkg(orphanPkgId, missingAssetId, T0, T0, T0.plus(Duration.ofHours(24))));
        assertThat(claimUseCase.claimNext(Duration.ofMinutes(5), 10)).isEmpty();
        assertThat(stores.packageRepo.findById(orphanPkgId).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.FAILED);
    }

    @Test
    @DisplayName("CompleteHlsPackagePurgeUseCase opens REQUIRES_NEW via Spring AOP, enforces claimVersion, clamps backoff, and rejects stale/identity mismatches")
    void completionAndFailureEnforceSpringProxyVersionIdentityAndBackoff() {
        InMemoryPurgeStores stores = new InMemoryPurgeStores();
        AtomicReference<Instant> nowRef = new AtomicReference<>(T0.plus(Duration.ofHours(25)));
        List<Integer> propagations = new ArrayList<>();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(MediaAssetRepositoryPort.class, () -> stores.assetRepo);
            ctx.registerBean(MediaVideoHlsPackageRepositoryPort.class, () -> stores.packageRepo);
            ctx.registerBean(ClockPort.class, () -> nowRef::get);
            ctx.registerBean(PlatformTransactionManager.class, () -> new PlatformTransactionManager() {
                @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                    propagations.add(definition.getPropagationBehavior());
                    return new SimpleTransactionStatus();
                }
                @Override public void commit(TransactionStatus status) {}
                @Override public void rollback(TransactionStatus status) {}
            });
            ctx.register(PurgeTestConfig.class);
            ctx.refresh();

            ClaimHlsPackagePurgeUseCase claimUseCase = ctx.getBean(ClaimHlsPackagePurgeUseCase.class);
            CompleteHlsPackagePurgeUseCase completeUseCase = ctx.getBean(CompleteHlsPackagePurgeUseCase.class);

            UUID assetId = UUID.randomUUID();
            stores.assetRepo.save(MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, T0));
            UUID pkgId = UUID.randomUUID();
            stores.packages.put(pkgId, failedPkg(pkgId, assetId, T0, T0, T0.plus(Duration.ofHours(24))));

            ClaimedHlsPackagePurgeLease lease1 = claimUseCase.claimNext(Duration.ofMinutes(5)).orElseThrow();
            assertThat(lease1.claimVersion()).isEqualTo(2L);

            // Wrong prefix / bucket identity rejected
            ClaimedHlsPackagePurgeLease forgedPrefix = new ClaimedHlsPackagePurgeLease(
                    lease1.packageId(), lease1.assetId(), lease1.sourceVersionId(), lease1.sourceVersionNumber(),
                    lease1.packageUuid(), lease1.storageProviderId(), lease1.storageBucketName(),
                    StorageKey.of("objects/hls/evil/v1/x/"), lease1.claimVersion(), lease1.claimExpiresAt()
            );
            assertThatThrownBy(() -> completeUseCase.markPurged(forgedPrefix))
                    .isInstanceOf(StalePurgeClaimException.class);

            // Failure with >24h requested backoff clamps safely to 24h and bumps persistence_version -> 3L
            nowRef.set(nowRef.get().plusSeconds(30));
            MediaVideoHlsPackage afterFailure = completeUseCase.recordFailure(
                    lease1, "R2 DeleteObjects 503", Duration.ofDays(365)
            );
            assertThat(afterFailure.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGING);
            assertThat(afterFailure.getPersistenceVersion()).isEqualTo(3L);
            assertThat(afterFailure.getPurgeAfter()).isEqualTo(nowRef.get().plus(MediaVideoHlsPackage.MAX_PURGE_RETRY_BACKOFF));

            // Old lease1 (version 2) is now stale and cannot complete or fail again
            assertThatThrownBy(() -> completeUseCase.markPurged(lease1))
                    .isInstanceOf(StalePurgeClaimException.class);
            assertThatThrownBy(() -> completeUseCase.recordFailure(lease1, "late", Duration.ofMinutes(5)))
                    .isInstanceOf(StalePurgeClaimException.class);

            // Advance past backoff, reclaim -> version 4L -> complete -> PURGED (version 5L, purgeAfter = null)
            nowRef.set(afterFailure.getPurgeAfter().plusSeconds(1));
            ClaimedHlsPackagePurgeLease lease2 = claimUseCase.claimNext(Duration.ofMinutes(5)).orElseThrow();
            assertThat(lease2.claimVersion()).isEqualTo(4L);

            nowRef.set(nowRef.get().plusSeconds(10));
            MediaVideoHlsPackage purged = completeUseCase.markPurged(lease2);
            assertThat(purged.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGED);
            assertThat(purged.getPurgeAfter()).isNull();
            assertThat(purged.getPersistenceVersion()).isEqualTo(5L);

            // Missing asset fails closed
            ClaimedHlsPackagePurgeLease missingAssetLease = new ClaimedHlsPackagePurgeLease(
                    pkgId, UUID.randomUUID(), lease2.sourceVersionId(), lease2.sourceVersionNumber(),
                    lease2.packageUuid(), R2_HLS, "hls-bucket", lease2.basePrefix(), 5L, nowRef.get().plusSeconds(60)
            );
            assertThatThrownBy(() -> completeUseCase.markPurged(missingAssetLease))
                    .isInstanceOf(MediaAssetNotFoundException.class);

            assertThat(propagations).allMatch(p -> p == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    @Test
    @DisplayName("Claim duration bounds reject invalid/extreme/sub-second durations and valid recordFailure uses post-lock timestamp")
    void claimDurationBoundsAndPostLockRecordFailureTimestampAreEnforced() {
        InMemoryPurgeStores stores = new InMemoryPurgeStores();
        AtomicReference<Instant> nowRef = new AtomicReference<>(T0.plus(Duration.ofHours(25)));
        AtomicReference<Runnable> afterLockHook = new AtomicReference<>();

        MediaAssetRepositoryPort hookAwareAssetRepo = new MediaAssetRepositoryPort() {
            @Override public MediaAsset save(MediaAsset asset) { return stores.assetRepo.save(asset); }
            @Override public Optional<MediaAsset> findById(UUID id) { return stores.assetRepo.findById(id); }
            @Override public Optional<MediaAsset> findByIdForUpdate(UUID id) {
                Optional<MediaAsset> found = stores.assetRepo.findByIdForUpdate(id);
                Runnable hook = afterLockHook.getAndSet(null);
                if (hook != null) {
                    hook.run();
                }
                return found;
            }
            @Override public void deleteById(UUID id) { stores.assetRepo.deleteById(id); }
            @Override public List<MediaAsset> findExpiredDeleted(Instant cutoff, int limit) { return List.of(); }
            @Override public List<MediaAssetCandidate> findActiveByClientTagKeyset(
                    String c, Instant u, Instant l, UUID i, int lim) { return List.of(); }
        };

        PlatformTransactionManager txMgr = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) {}
            @Override public void rollback(TransactionStatus status) {}
        };

        ClaimHlsPackagePurgeUseCase claimUseCase = new ClaimHlsPackagePurgeUseCase(
                hookAwareAssetRepo, stores.packageRepo, nowRef::get, txMgr
        );
        CompleteHlsPackagePurgeUseCase completeUseCase = new CompleteHlsPackagePurgeUseCase(
                hookAwareAssetRepo, stores.packageRepo, nowRef::get
        );

        // Duration bounds on ClaimHlsPackagePurgeUseCase
        assertThatThrownBy(() -> claimUseCase.claimNext(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> claimUseCase.claimNext(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimUseCase.claimNext(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimUseCase.claimNext(Duration.ofNanos(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimUseCase.claimNext(Duration.ofNanos(999))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimUseCase.claimNext(Duration.ofMillis(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimUseCase.claimNext(Duration.ofMillis(999))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimUseCase.claimNext(MediaVideoHlsPackage.MAX_PURGE_CLAIM_LEASE.plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimUseCase.claimNext(Duration.ofDays(365))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimUseCase.claimNext(Duration.ofSeconds(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);

        UUID assetId = UUID.randomUUID();
        stores.assetRepo.save(MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, T0));
        UUID pkgId = UUID.randomUUID();
        stores.packages.put(pkgId, failedPkg(pkgId, assetId, T0, T0, T0.plus(Duration.ofHours(24))));

        // Max allowed (30m) is accepted and clock advance during lock wait yields post-lock claimExpiresAt
        Instant tScan = nowRef.get();
        Instant tLockedClaim = tScan.plusSeconds(12);
        afterLockHook.set(() -> nowRef.set(tLockedClaim));
        ClaimedHlsPackagePurgeLease maxLease = claimUseCase.claimNext(MediaVideoHlsPackage.MAX_PURGE_CLAIM_LEASE).orElseThrow();
        assertThat(maxLease.claimExpiresAt()).isEqualTo(tLockedClaim.plus(MediaVideoHlsPackage.MAX_PURGE_CLAIM_LEASE));

        // When recordFailure waits for DB locks while claim stays active, nextPurgeAfter uses post-lock time
        Instant tFailPreLock = tLockedClaim.plusSeconds(5);
        Instant tFailPostLock = tLockedClaim.plusSeconds(25);
        nowRef.set(tFailPreLock);
        afterLockHook.set(() -> nowRef.set(tFailPostLock));
        MediaVideoHlsPackage backedOff = completeUseCase.recordFailure(maxLease, "Transient 503", Duration.ofMinutes(10));
        assertThat(backedOff.getPurgeAfter()).isEqualTo(tFailPostLock.plus(Duration.ofMinutes(10)));
        assertThat(backedOff.getUpdatedAt()).isEqualTo(tFailPostLock);

        // Min allowed (1s) is also accepted cleanly
        nowRef.set(tFailPostLock.plus(Duration.ofMinutes(11)));
        ClaimedHlsPackagePurgeLease minLease = claimUseCase.claimNext(MediaVideoHlsPackage.MIN_PURGE_CLAIM_LEASE).orElseThrow();
        assertThat(minLease.claimExpiresAt()).isEqualTo(nowRef.get().plus(MediaVideoHlsPackage.MIN_PURGE_CLAIM_LEASE));
    }

    @Configuration
    @EnableTransactionManagement
    static class PurgeTestConfig {
        @Bean
        ClaimHlsPackagePurgeUseCase claimHlsPackagePurgeUseCase(
                MediaAssetRepositoryPort a,
                MediaVideoHlsPackageRepositoryPort p,
                ClockPort c,
                PlatformTransactionManager tm
        ) {
            return new ClaimHlsPackagePurgeUseCase(a, p, c, tm);
        }

        @Bean
        CompleteHlsPackagePurgeUseCase completeHlsPackagePurgeUseCase(
                MediaAssetRepositoryPort a,
                MediaVideoHlsPackageRepositoryPort p,
                ClockPort c
        ) {
            return new CompleteHlsPackagePurgeUseCase(a, p, c);
        }
    }
}
