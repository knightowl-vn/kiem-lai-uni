package com.universe.media.application.hls;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.AssignMediaAssetClientTagUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.FindActiveMediaAssetsByClientTagKeysetUseCase;
import com.universe.media.application.asset.GetCurrentMediaAssetVersionSnapshotUseCase;
import com.universe.media.application.asset.GetMediaAssetCurrentMetadataUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.LockAndInspectMediaAssetUseCase;
import com.universe.media.application.asset.LockAndValidateActiveMediaAssetUseCase;
import com.universe.media.application.asset.OpenMediaAssetVersionContentUseCase;
import com.universe.media.application.asset.RasterContentSignatureValidator;
import com.universe.media.application.asset.RegisterMediaAssetUseCase;
import com.universe.media.application.asset.RegisterMediaAssetVersionUseCase;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionUseCase;
import com.universe.media.application.asset.VideoUploadValidator;
import com.universe.media.application.facade.MediaFacade;
import com.universe.media.application.ports.MediaAssetCandidate;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackagePurgeCandidate;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.application.ports.MediaVideoPackagingJobCandidate;
import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.storage.MediaStorageCutoverBarrier;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.MediaUploadSessionService;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.application.variant.GenerateMediaImageVariantUseCase;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("MS-08 HLS-2B3A3b: Opt-In Auto Enqueue Configuration, Eligibility & Coordinator Tests")
class HlsAutoEnqueueUseCaseAndCoordinatorTest {

    private static final Instant T0 = Instant.parse("2026-11-22T08:00:00Z");
    private static final StorageProviderId R2_MOVIE = StorageProviderId.of("r2-movie");
    private static final ContentHash HASH_1 =
            ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    private static final ContentHash HASH_2 =
            ContentHash.of("2222222222222222222222222222222222222222222222222222222222222222");

    private static final class InMemoryStores {
        final Map<UUID, MediaAsset> assets = new LinkedHashMap<>();
        final Map<UUID, MediaAssetVersion> versions = new LinkedHashMap<>();
        final Map<UUID, MediaVideoPackagingJob> jobs = new LinkedHashMap<>();
        final Map<UUID, MediaVideoHlsPackage> packages = new LinkedHashMap<>();

        final MediaAssetRepositoryPort assetRepo = new MediaAssetRepositoryPort() {
            @Override public Optional<MediaAsset> findById(UUID id) { return Optional.ofNullable(assets.get(id)); }
            @Override public Optional<MediaAsset> findByIdForUpdate(UUID id) { return findById(id); }
            @Override public MediaAsset save(MediaAsset asset) { assets.put(asset.getId(), asset); return asset; }
            @Override public void deleteById(UUID id) { assets.remove(id); }
            @Override public List<MediaAsset> findExpiredDeleted(Instant cutoff, int limit) { return List.of(); }
            @Override public List<MediaAssetCandidate> findActiveByClientTagKeyset(String t, Instant u, Instant c, UUID i, int l) { return List.of(); }
        };

        final MediaAssetVersionRepositoryPort versionRepo = new MediaAssetVersionRepositoryPort() {
            @Override public MediaAssetVersion save(MediaAssetVersion v) { versions.put(v.getId(), v); return v; }
            @Override public Optional<MediaAssetVersion> findByAssetIdAndVersionNumber(UUID assetId, int versionNumber) {
                return versions.values().stream()
                        .filter(v -> v.getAssetId().equals(assetId) && v.getVersionNumber() == versionNumber)
                        .findFirst();
            }
            @Override public boolean existsByStorageLocation(StorageLocation loc) { return false; }
            @Override public List<MediaAssetVersion> findByContentHash(ContentHash h) { return List.of(); }
            @Override public List<MediaAssetVersion> findAllByAssetId(UUID assetId) {
                return versions.values().stream().filter(v -> v.getAssetId().equals(assetId)).toList();
            }
            @Override public void deleteByAssetId(UUID assetId) {}
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
                        .filter(j -> j.getSourceVersionId().equals(svId)
                                && (j.getStatus() == MediaVideoPackagingJobStatus.PENDING
                                || j.getStatus() == MediaVideoPackagingJobStatus.LEASED))
                        .findFirst();
            }
            @Override public Optional<MediaVideoPackagingJobStatus> findHighestPriorityTerminalStatusBySourceVersionId(UUID svId) {
                if (jobs.values().stream().anyMatch(j -> j.getSourceVersionId().equals(svId)
                        && j.getStatus() == MediaVideoPackagingJobStatus.SUCCEEDED)) {
                    return Optional.of(MediaVideoPackagingJobStatus.SUCCEEDED);
                }
                if (jobs.values().stream().anyMatch(j -> j.getSourceVersionId().equals(svId)
                        && j.getStatus() == MediaVideoPackagingJobStatus.FAILED)) {
                    return Optional.of(MediaVideoPackagingJobStatus.FAILED);
                }
                if (jobs.values().stream().anyMatch(j -> j.getSourceVersionId().equals(svId)
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
            @Override public Optional<MediaVideoHlsPackage> findReadyBySourceVersionId(UUID svId) {
                return packages.values().stream()
                        .filter(p -> p.getSourceVersionId().equals(svId) && p.getStatus() == MediaVideoHlsPackageStatus.READY)
                        .findFirst();
            }
            @Override public Optional<MediaVideoHlsPackage> findCurrentReadyByAssetId(UUID assetId) { return Optional.empty(); }
            @Override public List<MediaVideoHlsPackage> findBySourceVersionIdForUpdateOrderByIdAsc(UUID svId) { return List.of(); }
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

        MediaAssetVersion seedAsset(UUID assetId, UUID versionId, MediaType type, MediaAssetStatus status) {
            MediaAsset asset = MediaAsset.registerInitial(assetId, type, MediaVisibility.PRIVATE, "screen-video", T0);
            if (status == MediaAssetStatus.ARCHIVED) {
                asset.archive(T0.plusSeconds(1));
            } else if (status == MediaAssetStatus.DELETED) {
                asset.markDeleted(T0.plusSeconds(1));
            }
            assets.put(assetId, asset);
            MediaAssetVersion v = MediaAssetVersion.create(
                    versionId, assetId, 1,
                    StorageLocation.of(R2_MOVIE, StorageKey.of("video/" + assetId + "/v1.mp4")),
                    null, HASH_1, MimeType.of(type == MediaType.VIDEO ? "video/mp4" : "image/png"), 1024L, "file", T0
            );
            versions.put(versionId, v);
            return v;
        }
    }

    @Test
    @DisplayName("Configuration defaults disabled (false) when absent and stays worker/scheduler free when enabled")
    void configurationFlagsAndContextSafety() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(HlsAutoEnqueueProperties.class);

        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            HlsAutoEnqueueProperties props = ctx.getBean(HlsAutoEnqueueProperties.class);
            assertThat(props.isEnabled()).isFalse();
            assertThat(props.isOnRestore()).isFalse();
            assertThat(props.isRestoreAutoEnqueueActive()).isFalse();
            assertThat(ctx.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        });

        runner.withPropertyValues("media.hls.auto-enqueue.enabled=false").run(ctx -> {
            HlsAutoEnqueueProperties props = ctx.getBean(HlsAutoEnqueueProperties.class);
            assertThat(props.isEnabled()).isFalse();
            assertThat(props.isOnRestore()).isFalse();
            assertThat(props.isRestoreAutoEnqueueActive()).isFalse();
        });

        runner.withPropertyValues("media.hls.auto-enqueue.enabled=true").run(ctx -> {
            HlsAutoEnqueueProperties props = ctx.getBean(HlsAutoEnqueueProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.isOnRestore()).isFalse();
            assertThat(props.isRestoreAutoEnqueueActive()).isFalse();
        });

        runner.withPropertyValues(
                "media.hls.auto-enqueue.enabled=true",
                "media.hls.auto-enqueue.on-restore=true"
        ).run(ctx -> {
            HlsAutoEnqueueProperties props = ctx.getBean(HlsAutoEnqueueProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.isOnRestore()).isTrue();
            assertThat(props.isRestoreAutoEnqueueActive()).isTrue();
            assertThat(ctx.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("Disabled coordinator performs zero repository or use-case queries; failures isolate cleanly")
    void coordinatorDisabledAndFailureIsolation() {
        CreateMediaVideoPackagingJobUseCase mockUseCase = mock(CreateMediaVideoPackagingJobUseCase.class);
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        OptInHlsPackagingEnqueueCoordinator disabledCoordinator = new OptInHlsPackagingEnqueueCoordinator(
                HlsAutoEnqueueProperties.disabled(),
                mockUseCase
        );
        assertThat(disabledCoordinator.enqueueVideoVersionIfEnabledSafely(assetId, 1, versionId).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_DISABLED);
        assertThat(disabledCoordinator.enqueueRestoredCurrentVersionIfEnabledSafely(assetId).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_DISABLED);
        verifyNoInteractions(mockUseCase);

        OptInHlsPackagingEnqueueCoordinator restoreOffCoordinator = new OptInHlsPackagingEnqueueCoordinator(
                HlsAutoEnqueueProperties.of(true, false),
                mockUseCase
        );
        assertThat(restoreOffCoordinator.enqueueRestoredCurrentVersionIfEnabledSafely(assetId).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_DISABLED);
        verifyNoInteractions(mockUseCase);

        CreateMediaVideoPackagingJobUseCase throwingUseCase = new CreateMediaVideoPackagingJobUseCase(
                mock(MediaAssetRepositoryPort.class),
                mock(MediaAssetVersionRepositoryPort.class),
                mock(MediaVideoPackagingJobRepositoryPort.class),
                mock(MediaVideoHlsPackageRepositoryPort.class),
                () -> T0
        ) {
            @Override
            public AutoPackagingEnqueueResult executeIfEligibleForVersion(UUID a, int v, UUID vid, int m) {
                throw new IllegalStateException("Simulated DB timeout");
            }
            @Override
            public AutoPackagingEnqueueResult executeIfEligibleForCurrentVersion(UUID a, int m) {
                throw new IllegalStateException("Simulated restore enqueue failure");
            }
        };
        OptInHlsPackagingEnqueueCoordinator failingCoordinator = new OptInHlsPackagingEnqueueCoordinator(
                HlsAutoEnqueueProperties.of(true, true),
                throwingUseCase
        );
        assertThat(failingCoordinator.enqueueVideoVersionIfEnabledSafely(assetId, 1, versionId).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_FAILED_ISOLATION);
        assertThat(failingCoordinator.enqueueRestoredCurrentVersionIfEnabledSafely(assetId).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_FAILED_ISOLATION);
    }

    @Test
    @DisplayName("Automatic enqueue eligibility covers ACTIVE VIDEO, non-VIDEO, ARCHIVED, DELETED, version/UUID fence, READY, active & terminal jobs")
    void autoEnqueueEligibilityAndDeduplicationMatrix() {
        InMemoryStores stores = new InMemoryStores();
        CreateMediaVideoPackagingJobUseCase useCase = new CreateMediaVideoPackagingJobUseCase(
                stores.assetRepo, stores.versionRepo, stores.jobRepo, stores.pkgRepo, () -> T0
        );

        // 1. ACTIVE VIDEO v1 -> CREATED & repeated request -> REUSED_ACTIVE
        UUID activeVideoId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        stores.seedAsset(activeVideoId, v1Id, MediaType.VIDEO, MediaAssetStatus.ACTIVE);

        AutoPackagingEnqueueResult created = useCase.executeIfEligibleForVersion(activeVideoId, 1, v1Id, 3);
        assertThat(created.status()).isEqualTo(AutoPackagingEnqueueStatus.CREATED);
        assertThat(created.job().getStatus()).isEqualTo(MediaVideoPackagingJobStatus.PENDING);
        assertThat(created.job().getMaxAttempts()).isEqualTo(MediaVideoPackagingJob.DEFAULT_MAX_ATTEMPTS);

        AutoPackagingEnqueueResult reusedPending = useCase.executeIfEligibleForVersion(activeVideoId, 1, v1Id, 3);
        assertThat(reusedPending.status()).isEqualTo(AutoPackagingEnqueueStatus.REUSED_ACTIVE);
        assertThat(reusedPending.job().getId()).isEqualTo(created.job().getId());
        assertThat(stores.jobs).hasSize(1);

        // 2. LEASED -> REUSED_ACTIVE
        UUID pkg1Id = UUID.randomUUID();
        MediaVideoPackagingJob leased = stores.jobs.get(created.job().getId());
        leased.claimOrReclaimLease("worker-1", pkg1Id, java.time.Duration.ofMinutes(5), T0.plusSeconds(5));
        stores.jobRepo.save(leased);
        assertThat(useCase.executeIfEligibleForVersion(activeVideoId, 1, v1Id, 3).status())
                .isEqualTo(AutoPackagingEnqueueStatus.REUSED_ACTIVE);

        // 3. Expected version number mismatch & expected version UUID mismatch -> SKIPPED_STALE_VERSION
        assertThat(useCase.executeIfEligibleForVersion(activeVideoId, 2, null, 3).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_STALE_VERSION);
        assertThat(useCase.executeIfEligibleForVersion(activeVideoId, 1, UUID.randomUUID(), 3).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_STALE_VERSION);

        // 4. Existing READY package -> SKIPPED_READY (even if terminal job exists)
        MediaVideoHlsPackage pkg1 = MediaVideoHlsPackage.startProcessing(
                pkg1Id, activeVideoId, v1Id, 1, UUID.randomUUID(), 1,
                StorageProviderId.of("r2"), "hls-bkt", StorageKey.of("objects/hls/1/"),
                6, T0.plusSeconds(5)
        );
        pkg1.markReady(HASH_2, 1024L, 4, "[]", T0.plusSeconds(6), T0.plusSeconds(7));
        stores.pkgRepo.save(pkg1);
        leased.markSucceeded("worker-1", 1L, pkg1Id, T0.plusSeconds(7));
        stores.jobRepo.save(leased);
        assertThat(useCase.executeIfEligibleForVersion(activeVideoId, 1, v1Id, 3).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_READY);

        // 5. SUCCEEDED without READY -> SKIPPED_TERMINAL_JOB + bounded WARN diagnostic
        UUID missingReadyAssetId = UUID.randomUUID();
        UUID missingReadyV1Id = UUID.randomUUID();
        stores.seedAsset(missingReadyAssetId, missingReadyV1Id, MediaType.VIDEO, MediaAssetStatus.ACTIVE);
        MediaVideoPackagingJob inconsistentSucceeded = MediaVideoPackagingJob.createPending(
                UUID.randomUUID(), missingReadyAssetId, missingReadyV1Id, 1, 3, T0
        );
        UUID ghostPkgId = UUID.randomUUID();
        inconsistentSucceeded.claimOrReclaimLease("w", ghostPkgId, java.time.Duration.ofMinutes(5), T0.plusSeconds(1));
        inconsistentSucceeded.markSucceeded("w", 1L, ghostPkgId, T0.plusSeconds(2));
        stores.jobRepo.save(inconsistentSucceeded);

        Logger ucLogger = (Logger) LoggerFactory.getLogger(CreateMediaVideoPackagingJobUseCase.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ucLogger.addAppender(appender);
        try {
            assertThat(useCase.executeIfEligibleForVersion(missingReadyAssetId, 1, missingReadyV1Id, 3).status())
                    .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_TERMINAL_JOB);
            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("SUCCEEDED"));
        } finally {
            ucLogger.detachAppender(appender);
        }

        // 6. FAILED & CANCELLED -> SKIPPED_TERMINAL_JOB
        UUID failedAssetId = UUID.randomUUID();
        UUID failedV1Id = UUID.randomUUID();
        stores.seedAsset(failedAssetId, failedV1Id, MediaType.VIDEO, MediaAssetStatus.ACTIVE);
        MediaVideoPackagingJob failedJob = MediaVideoPackagingJob.createPending(
                UUID.randomUUID(), failedAssetId, failedV1Id, 1, 3, T0
        );
        failedJob.claimOrReclaimLease("w", UUID.randomUUID(), java.time.Duration.ofMinutes(5), T0.plusSeconds(1));
        failedJob.recordPermanentFailure("w", 1L, "Invalid MP4", T0.plusSeconds(2));
        stores.jobRepo.save(failedJob);
        assertThat(useCase.executeIfEligibleForVersion(failedAssetId, 1, failedV1Id, 3).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_TERMINAL_JOB);

        UUID cancelledAssetId = UUID.randomUUID();
        UUID cancelledV1Id = UUID.randomUUID();
        stores.seedAsset(cancelledAssetId, cancelledV1Id, MediaType.VIDEO, MediaAssetStatus.ACTIVE);
        MediaVideoPackagingJob cancelledJob = MediaVideoPackagingJob.createPending(
                UUID.randomUUID(), cancelledAssetId, cancelledV1Id, 1, 3, T0
        );
        cancelledJob.cancel("Archived", T0.plusSeconds(2));
        stores.jobRepo.save(cancelledJob);
        assertThat(useCase.executeIfEligibleForVersion(cancelledAssetId, 1, cancelledV1Id, 3).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_TERMINAL_JOB);

        // 7. Non-VIDEO, ARCHIVED, DELETED -> SKIPPED_INELIGIBLE_ASSET
        UUID imageId = UUID.randomUUID();
        stores.seedAsset(imageId, UUID.randomUUID(), MediaType.IMAGE, MediaAssetStatus.ACTIVE);
        UUID archivedVideoId = UUID.randomUUID();
        stores.seedAsset(archivedVideoId, UUID.randomUUID(), MediaType.VIDEO, MediaAssetStatus.ARCHIVED);
        UUID deletedVideoId = UUID.randomUUID();
        stores.seedAsset(deletedVideoId, UUID.randomUUID(), MediaType.VIDEO, MediaAssetStatus.DELETED);

        assertThat(useCase.executeIfEligibleForVersion(imageId, 1, null, 3).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_INELIGIBLE_ASSET);
        assertThat(useCase.executeIfEligibleForVersion(archivedVideoId, 1, null, 3).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_INELIGIBLE_ASSET);
        assertThat(useCase.executeIfEligibleForVersion(deletedVideoId, 1, null, 3).status())
                .isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_INELIGIBLE_ASSET);

        // 8. Invalid inputs reject cleanly + manual execute(...) remains compatible
        assertThatThrownBy(() -> useCase.executeIfEligibleForVersion(null, 1, null, 3))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> useCase.executeIfEligibleForVersion(activeVideoId, 0, null, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.executeIfEligibleForVersion(activeVideoId, 1, null, 0))
                .isInstanceOf(IllegalArgumentException.class);

        UUID manualAssetId = UUID.randomUUID();
        stores.seedAsset(manualAssetId, UUID.randomUUID(), MediaType.VIDEO, MediaAssetStatus.ACTIVE);
        MediaVideoPackagingJob manualJob = useCase.execute(manualAssetId);
        assertThat(manualJob.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.PENDING);
    }

    @Test
    @DisplayName("Real Spring @EnableTransactionManagement proxy starts 1 tx post-commit and blocks ambient outer transaction")
    void springAopProxyPostCommitAndAmbientGuard() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            InMemoryStores stores = new InMemoryStores();
            UUID assetId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            stores.seedAsset(assetId, versionId, MediaType.VIDEO, MediaAssetStatus.ACTIVE);

            List<String> txEvents = new ArrayList<>();
            AbstractPlatformTransactionManager txManager = new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object t, TransactionDefinition d) {
                    txEvents.add("BEGIN:" + d.getPropagationBehavior());
                }
                @Override protected void doCommit(DefaultTransactionStatus s) { txEvents.add("COMMIT"); }
                @Override protected void doRollback(DefaultTransactionStatus s) { txEvents.add("ROLLBACK"); }
            };

            ctx.registerBean(PlatformTransactionManager.class, () -> txManager);
            ctx.registerBean(MediaAssetRepositoryPort.class, () -> stores.assetRepo);
            ctx.registerBean(MediaAssetVersionRepositoryPort.class, () -> stores.versionRepo);
            ctx.registerBean(MediaVideoPackagingJobRepositoryPort.class, () -> stores.jobRepo);
            ctx.registerBean(MediaVideoHlsPackageRepositoryPort.class, () -> stores.pkgRepo);
            ctx.registerBean(ClockPort.class, () -> () -> T0);
            ctx.registerBean(HlsAutoEnqueueProperties.class, () -> HlsAutoEnqueueProperties.of(true, true));
            ctx.register(AopTxConfig.class, CreateMediaVideoPackagingJobUseCase.class, OptInHlsPackagingEnqueueCoordinator.class);
            ctx.refresh();

            OptInHlsPackagingEnqueueCoordinator coordinator = ctx.getBean(OptInHlsPackagingEnqueueCoordinator.class);

            // Post-commit execution opens 1 tx and commits cleanly
            AutoPackagingEnqueueResult ok = coordinator.enqueueVideoVersionIfEnabledSafely(assetId, 1, versionId);
            assertThat(ok.status()).isEqualTo(AutoPackagingEnqueueStatus.CREATED);
            assertThat(txEvents).containsExactly("BEGIN:" + TransactionDefinition.PROPAGATION_REQUIRED, "COMMIT");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

            // Inside ambient transaction: coordinator refuses to start REQUIRES_NEW and does not fail outer tx
            txEvents.clear();
            TransactionTemplate outerTx = new TransactionTemplate(txManager);
            AutoPackagingEnqueueResult insideOuter = outerTx.execute(status -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return coordinator.enqueueVideoVersionIfEnabledSafely(assetId, 1, versionId);
            });
            assertThat(insideOuter.status()).isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_AMBIENT_TRANSACTION);
            assertThat(txEvents).containsExactly("BEGIN:" + TransactionDefinition.PROPAGATION_REQUIRED, "COMMIT");
        }
    }

    @Test
    @DisplayName("Spring ApplicationContext fails closed when OptInHlsPackagingEnqueueCoordinator bean is missing")
    void springContextFailsClosedWhenCoordinatorMissing() {
        for (Class<?> targetBean : List.of(
                UploadMediaAssetUseCase.class,
                UploadMediaAssetVersionUseCase.class,
                UploadMediaAssetVersionConditionalUseCase.class,
                MediaUploadSessionService.class,
                MediaFacade.class
        )) {
            assertThatThrownBy(() -> {
                try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
                    ctx.registerBean(MediaAssetRepositoryPort.class, () -> mock(MediaAssetRepositoryPort.class));
                    ctx.registerBean(MediaAssetVersionRepositoryPort.class, () -> mock(MediaAssetVersionRepositoryPort.class));
                    ctx.registerBean(MediaUploadSessionRepositoryPort.class, () -> mock(MediaUploadSessionRepositoryPort.class));
                    ctx.registerBean(MediaStorageRoutingService.class, () -> mock(MediaStorageRoutingService.class));
                    ctx.registerBean(StorageProviderResolverPort.class, () -> mock(StorageProviderResolverPort.class));
                    ctx.registerBean(StorageKeyAllocationService.class, () -> mock(StorageKeyAllocationService.class));
                    ctx.registerBean(MediaStorageCutoverBarrier.class, () -> mock(MediaStorageCutoverBarrier.class));
                    ctx.registerBean(ClockPort.class, () -> () -> T0);
                    ctx.registerBean(RegisterMediaAssetUseCase.class, () -> mock(RegisterMediaAssetUseCase.class));
                    ctx.registerBean(RegisterMediaAssetVersionUseCase.class, () -> mock(RegisterMediaAssetVersionUseCase.class));
                    ctx.registerBean(RasterContentSignatureValidator.class, () -> mock(RasterContentSignatureValidator.class));
                    ctx.registerBean(VideoUploadValidator.class, () -> mock(VideoUploadValidator.class));
                    ctx.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
                    ctx.registerBean(MultipartStoragePort.class, () -> mock(MultipartStoragePort.class));
                    ctx.registerBean(GetMediaAssetDetailUseCase.class, () -> mock(GetMediaAssetDetailUseCase.class));
                    ctx.registerBean(GetMediaAssetCurrentMetadataUseCase.class, () -> mock(GetMediaAssetCurrentMetadataUseCase.class));
                    ctx.registerBean(ChangeMediaVisibilityUseCase.class, () -> mock(ChangeMediaVisibilityUseCase.class));
                    ctx.registerBean(ArchiveMediaAssetUseCase.class, () -> mock(ArchiveMediaAssetUseCase.class));
                    ctx.registerBean(RestoreMediaAssetUseCase.class, () -> mock(RestoreMediaAssetUseCase.class));
                    ctx.registerBean(DeleteMediaAssetUseCase.class, () -> mock(DeleteMediaAssetUseCase.class));
                    ctx.registerBean(GenerateMediaImageVariantUseCase.class, () -> mock(GenerateMediaImageVariantUseCase.class));
                    ctx.registerBean(GetCurrentMediaAssetVersionSnapshotUseCase.class, () -> mock(GetCurrentMediaAssetVersionSnapshotUseCase.class));
                    ctx.registerBean(OpenMediaAssetVersionContentUseCase.class, () -> mock(OpenMediaAssetVersionContentUseCase.class));
                    ctx.registerBean(AssignMediaAssetClientTagUseCase.class, () -> mock(AssignMediaAssetClientTagUseCase.class));
                    ctx.registerBean(FindActiveMediaAssetsByClientTagKeysetUseCase.class, () -> mock(FindActiveMediaAssetsByClientTagKeysetUseCase.class));
                    ctx.registerBean(LockAndValidateActiveMediaAssetUseCase.class, () -> mock(LockAndValidateActiveMediaAssetUseCase.class));
                    ctx.registerBean(LockAndInspectMediaAssetUseCase.class, () -> mock(LockAndInspectMediaAssetUseCase.class));
                    if (targetBean != UploadMediaAssetUseCase.class) {
                        ctx.registerBean(UploadMediaAssetUseCase.class, () -> mock(UploadMediaAssetUseCase.class));
                    }
                    if (targetBean != UploadMediaAssetVersionUseCase.class) {
                        ctx.registerBean(UploadMediaAssetVersionUseCase.class, () -> mock(UploadMediaAssetVersionUseCase.class));
                    }
                    if (targetBean != UploadMediaAssetVersionConditionalUseCase.class) {
                        ctx.registerBean(UploadMediaAssetVersionConditionalUseCase.class, () -> mock(UploadMediaAssetVersionConditionalUseCase.class));
                    }
                    ctx.registerBean(targetBean);
                    ctx.refresh();
                }
            }).isInstanceOf(UnsatisfiedDependencyException.class)
                    .hasMessageContaining("OptInHlsPackagingEnqueueCoordinator");
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class AopTxConfig {
        @Bean
        public String markerBean() {
            return "aop";
        }
    }
}
