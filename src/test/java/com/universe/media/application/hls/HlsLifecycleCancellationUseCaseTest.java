package com.universe.media.application.hls;

import com.universe.media.application.asset.ArchiveMediaAssetCommand;
import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.DeleteMediaAssetCommand;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.RegisterMediaAssetVersionCommand;
import com.universe.media.application.asset.RegisterMediaAssetVersionUseCase;
import com.universe.media.application.asset.RestoreMediaAssetCommand;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackagePurgeCandidate;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.application.ports.MediaVideoPackagingJobCandidate;
import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.interfaces.MediaAssetReferenceCheckPort;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit and Spring AOP proxy propagation tests covering MS-08 HLS-2B3A3A:
 * same-transaction cancellation/supersession, archive READY preservation,
 * restore locking without resurrection/enqueue, MANDATORY/REQUIRES_NEW propagation,
 * and fail-closed production bean wiring.
 */
class HlsLifecycleCancellationUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final StorageProviderId R2_MOVIE = StorageProviderId.of("r2-movie");
    private static final StorageProviderId R2_HLS = StorageProviderId.of("r2-hls");
    private static final ContentHash HASH_1 =
            ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

    private static final class InMemoryLifecycleStores {
        final Map<UUID, MediaAsset> assets = new HashMap<>();
        final Map<UUID, MediaAssetVersion> versions = new HashMap<>();
        final Map<UUID, MediaVideoPackagingJob> jobs = new HashMap<>();
        final Map<UUID, MediaVideoHlsPackage> packages = new HashMap<>();
        final List<String> lockTrace = new ArrayList<>();

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
                return Optional.ofNullable(assets.get(id));
            }
            @Override public void deleteById(UUID id) { assets.remove(id); }
            @Override public List<MediaAsset> findExpiredDeleted(Instant cutoff, int limit) { return List.of(); }
            @Override public List<com.universe.media.application.ports.MediaAssetCandidate> findActiveByClientTagKeyset(
                    String c, Instant u, Instant l, UUID i, int lim) { return List.of(); }
        };

        final MediaAssetVersionRepositoryPort versionRepo = new MediaAssetVersionRepositoryPort() {
            @Override public MediaAssetVersion save(MediaAssetVersion v) {
                versions.put(v.getId(), v);
                return v;
            }
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
            @Override public Optional<MediaVideoPackagingJob> findActiveBySourceVersionIdForUpdate(UUID svId) { return Optional.empty(); }
            @Override public Optional<MediaVideoPackagingJobStatus> findHighestPriorityTerminalStatusBySourceVersionId(UUID svId) {
                return jobs.values().stream()
                        .filter(j -> j.getSourceVersionId().equals(svId))
                        .map(MediaVideoPackagingJob::getStatus)
                        .filter(s -> s == MediaVideoPackagingJobStatus.SUCCEEDED
                                || s == MediaVideoPackagingJobStatus.FAILED
                                || s == MediaVideoPackagingJobStatus.CANCELLED)
                        .findFirst();
            }
            @Override public List<MediaVideoPackagingJob> findByAssetIdForUpdateOrderByIdAsc(UUID assetId) {
                lockTrace.add("JOBS:" + assetId);
                return jobs.values().stream()
                        .filter(j -> j.getAssetId().equals(assetId))
                        .sorted(Comparator.comparing(j -> j.getId().toString()))
                        .toList();
            }
            @Override public List<MediaVideoPackagingJobCandidate> findClaimableCandidates(Instant now, int limit) { return List.of(); }
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
            @Override public Optional<MediaVideoHlsPackage> findReadyBySourceVersionId(UUID svId) { return Optional.empty(); }
            @Override public Optional<MediaVideoHlsPackage> findCurrentReadyByAssetId(UUID assetId) { return Optional.empty(); }
            @Override public List<MediaVideoHlsPackage> findBySourceVersionIdForUpdateOrderByIdAsc(UUID svId) { return List.of(); }
            @Override public List<MediaVideoHlsPackage> findByAssetIdForUpdateOrderByIdAsc(UUID assetId) {
                lockTrace.add("PKGS:" + assetId);
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
    }

    @Test
    @DisplayName("Spring AOP MANDATORY entry points reject non-tx calls and execute inside a single transaction with zero REQUIRES_NEW self-wait")
    void springProxyMandatesActiveTransactionAndJoinsOuterTransactionAtomically() {
        InMemoryLifecycleStores stores = new InMemoryLifecycleStores();
        List<Integer> propagations = new ArrayList<>();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(MediaAssetRepositoryPort.class, () -> stores.assetRepo);
            ctx.registerBean(MediaAssetVersionRepositoryPort.class, () -> stores.versionRepo);
            ctx.registerBean(MediaVideoPackagingJobRepositoryPort.class, () -> stores.jobRepo);
            ctx.registerBean(MediaVideoHlsPackageRepositoryPort.class, () -> stores.pkgRepo);
            ctx.registerBean(MediaAssetReferenceCheckPort.class, () -> new MediaAssetReferenceCheckPort() {
                @Override public boolean supports(MediaTypeDTO mediaType) { return true; }
                @Override public void ensureNotReferenced(UUID mediaAssetId) {}
            });
            ctx.registerBean(ClockPort.class, () -> () -> T0.plusSeconds(30));
            AtomicBoolean txActive = new AtomicBoolean(false);
            ctx.registerBean(PlatformTransactionManager.class, () -> new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return txActive; }
                @Override protected boolean isExistingTransaction(Object transaction) { return txActive.get(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
                    propagations.add(definition.getPropagationBehavior());
                    txActive.set(true);
                }
                @Override protected void doCommit(DefaultTransactionStatus status) { txActive.set(false); }
                @Override protected void doRollback(DefaultTransactionStatus status) { txActive.set(false); }
            });
            ctx.register(TestSpringTxConfig.class);
            ctx.refresh();

            CancelOrSupersedeMediaVideoPackagingUseCase cancelUseCase =
                    ctx.getBean(CancelOrSupersedeMediaVideoPackagingUseCase.class);
            RegisterMediaAssetVersionUseCase registerVersionUseCase =
                    ctx.getBean(RegisterMediaAssetVersionUseCase.class);
            DeleteMediaAssetUseCase deleteUseCase = ctx.getBean(DeleteMediaAssetUseCase.class);
            ArchiveMediaAssetUseCase archiveUseCase = ctx.getBean(ArchiveMediaAssetUseCase.class);

            UUID assetId = UUID.randomUUID();
            UUID v1Id = UUID.randomUUID();
            stores.assets.put(assetId, MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PRIVATE, T0));
            stores.versions.put(v1Id, MediaAssetVersion.create(
                    v1Id, assetId, 1, StorageLocation.of(R2_MOVIE, StorageKey.of("video/sources/v1.mp4")),
                    null, HASH_1, MimeType.of("video/mp4"), 1024L, "v1.mp4", T0
            ));

            // 1. Direct calls to *_InCurrentTransaction without an active transaction fail via Spring AOP MANDATORY check
            assertThatThrownBy(() -> cancelUseCase.cancelAndSupersedeOlderVersionsInCurrentTransaction(assetId, 2, "no-tx"))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> cancelUseCase.cancelAllForAssetDeletionInCurrentTransaction(assetId, "no-tx"))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> cancelUseCase.cancelActivePackagingForArchiveInCurrentTransaction(assetId, "no-tx"))
                    .isInstanceOf(IllegalTransactionStateException.class);

            // Seed V1 LEASED job + V1 PROCESSING package + V1 PURGED tombstone (must never regress)
            UUID j1Id = UUID.randomUUID();
            MediaVideoPackagingJob j1 = MediaVideoPackagingJob.createPending(j1Id, assetId, v1Id, 1, 3, T0);
            UUID p1Id = UUID.randomUUID();
            j1.claimOrReclaimLease("w1", p1Id, Duration.ofMinutes(5), T0.plusSeconds(1));
            stores.jobs.put(j1Id, j1);

            UUID pkgUuid1 = UUID.randomUUID();
            MediaVideoHlsPackage p1 = MediaVideoHlsPackage.startProcessing(
                    p1Id, assetId, v1Id, 1, pkgUuid1, 1, R2_HLS, "hls",
                    StorageKey.of("objects/hls/" + assetId + "/v1/" + pkgUuid1 + "/"), 6, T0.plusSeconds(1)
            );
            stores.packages.put(p1Id, p1);

            UUID tombId = UUID.randomUUID();
            UUID tombUuid = UUID.randomUUID();
            MediaVideoHlsPackage tombstone = MediaVideoHlsPackage.rehydrate(
                    tombId, assetId, v1Id, 1, tombUuid, 99, R2_HLS, "hls",
                    StorageKey.of("objects/hls/" + assetId + "/v1/" + tombUuid + "/"),
                    StorageKey.of("objects/hls/" + assetId + "/v1/" + tombUuid + "/master.m3u8"),
                    7, "FMP4", 6, null, null, null, null,
                    MediaVideoHlsPackageStatus.PURGED, "cleaned", T0, null, null, null, null, T0.plusSeconds(5), 2L
            );
            stores.packages.put(tombId, tombstone);

            // 2. Register v2 in outer Spring proxy tx -> V1 job cancelled, V1 PROCESSING abandoned, tombstone untouched, NO REQUIRES_NEW opened!
            propagations.clear();
            stores.lockTrace.clear();
            registerVersionUseCase.execute(new RegisterMediaAssetVersionCommand(
                    assetId, "r2-movie", "video/sources/v2.mp4", null,
                    "2222222222222222222222222222222222222222222222222222222222222222",
                    "video/mp4", 2048L, "v2.mp4"
            ));
            assertThat(propagations).containsExactly(TransactionDefinition.PROPAGATION_REQUIRED);
            assertThat(stores.lockTrace).containsExactly(
                    "ASSET:" + assetId, "ASSET:" + assetId, "JOBS:" + assetId, "PKGS:" + assetId
            );
            assertThat(stores.jobs.get(j1Id).getStatus()).isEqualTo(MediaVideoPackagingJobStatus.CANCELLED);
            assertThat(stores.packages.get(p1Id).getStatus()).isEqualTo(MediaVideoHlsPackageStatus.ABANDONED);
            assertThat(stores.packages.get(tombId).getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGED);

            // Duplicate standalone/current-tx calls are safe & preserve V2 READY during archive while cancelling V2 pending job
            UUID v2Id = stores.versions.values().stream().filter(v -> v.getVersionNumber() == 2).findFirst().orElseThrow().getId();
            UUID v2ReadyPkgId = UUID.randomUUID();
            UUID v2ReadyUuid = UUID.randomUUID();
            MediaVideoHlsPackage v2Ready = MediaVideoHlsPackage.startProcessing(
                    v2ReadyPkgId, assetId, v2Id, 2, v2ReadyUuid, 1, R2_HLS, "hls",
                    StorageKey.of("objects/hls/" + assetId + "/v2/" + v2ReadyUuid + "/"), 6, T0.plusSeconds(10)
            );
            v2Ready.markReady(HASH_1, 8192L, 5, "[{\"height\":720}]", T0.plusSeconds(11), T0.plusSeconds(12));
            stores.packages.put(v2ReadyPkgId, v2Ready);

            UUID v2PendingJobId = UUID.randomUUID();
            stores.jobs.put(v2PendingJobId, MediaVideoPackagingJob.createPending(v2PendingJobId, assetId, v2Id, 2, 3, T0.plusSeconds(12)));

            // Standalone REQUIRES_NEW still works
            propagations.clear();
            cancelUseCase.cancelAndSupersedeOlderVersions(assetId, 2, "duplicate-noop");
            assertThat(propagations).containsExactly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(stores.packages.get(v2ReadyPkgId).getStatus()).isEqualTo(MediaVideoHlsPackageStatus.READY);

            // 3. Archive cancels V2 active job cleanly yet preserves V2 READY package!
            archiveUseCase.execute(new ArchiveMediaAssetCommand(assetId));
            assertThat(stores.jobs.get(v2PendingJobId).getStatus()).isEqualTo(MediaVideoPackagingJobStatus.CANCELLED);
            assertThat(stores.packages.get(v2ReadyPkgId).getStatus()).isEqualTo(MediaVideoHlsPackageStatus.READY);

            // 4. Delete cancels/supersedes V2 READY -> SUPERSEDED inside same transaction
            deleteUseCase.execute(new DeleteMediaAssetCommand(assetId));
            assertThat(stores.packages.get(v2ReadyPkgId).getStatus()).isEqualTo(MediaVideoHlsPackageStatus.SUPERSEDED);
        }
    }

    @Test
    @DisplayName("Spring ApplicationContext fails closed when CancelOrSupersedeMediaVideoPackagingUseCase bean is missing")
    void springContextFailsClosedIfLifecycleCancellationBeanMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(DeleteMediaAssetUseCase.class)
                .withBean(MediaAssetRepositoryPort.class, () -> mock(MediaAssetRepositoryPort.class))
                .withBean(ClockPort.class, () -> Instant::now)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Configuration
    @EnableTransactionManagement
    static class TestSpringTxConfig {
        @Bean
        CancelOrSupersedeMediaVideoPackagingUseCase cancelUseCase(
                MediaAssetRepositoryPort a,
                MediaVideoPackagingJobRepositoryPort j,
                MediaVideoHlsPackageRepositoryPort p,
                ClockPort c
        ) {
            return new CancelOrSupersedeMediaVideoPackagingUseCase(a, j, p, c);
        }

        @Bean
        RegisterMediaAssetVersionUseCase registerVersionUseCase(
                MediaAssetRepositoryPort a,
                MediaAssetVersionRepositoryPort v,
                ClockPort c,
                CancelOrSupersedeMediaVideoPackagingUseCase cancelUseCase
        ) {
            return new RegisterMediaAssetVersionUseCase(a, v, c, null, null, cancelUseCase);
        }

        @Bean
        DeleteMediaAssetUseCase deleteUseCase(
                MediaAssetRepositoryPort a,
                ClockPort c,
                List<MediaAssetReferenceCheckPort> refs,
                CancelOrSupersedeMediaVideoPackagingUseCase cancelUseCase
        ) {
            return new DeleteMediaAssetUseCase(a, c, refs, cancelUseCase);
        }

        @Bean
        ArchiveMediaAssetUseCase archiveUseCase(
                MediaAssetRepositoryPort a,
                ClockPort c,
                List<MediaAssetReferenceCheckPort> refs,
                CancelOrSupersedeMediaVideoPackagingUseCase cancelUseCase
        ) {
            return new ArchiveMediaAssetUseCase(a, c, refs, cancelUseCase);
        }

        @Bean
        RestoreMediaAssetUseCase restoreUseCase(MediaAssetRepositoryPort a, ClockPort c) {
            return new RestoreMediaAssetUseCase(a, c);
        }
    }
}
