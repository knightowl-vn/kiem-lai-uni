package com.universe.media.infrastructure.persistence;

import com.universe.media.application.asset.PurgeDeletedMediaAssetCommand;
import com.universe.media.application.asset.PurgeDeletedMediaAssetUseCase;
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
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
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
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
        MediaV100HlsFoundationIntegrationTest.TestConfig.class
})
class MediaV100HlsFoundationIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-11-20T12:00:00Z");
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
    private AtomicReference<Instant> clockRef;

    @Autowired
    private MediaAssetPersistenceAdapter assetAdapter;

    @Autowired
    private MediaAssetVersionPersistenceAdapter versionAdapter;

    @Autowired
    private MediaImageVariantPersistenceAdapter variantAdapter;

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
    private HeartbeatMediaVideoPackagingJobUseCase heartbeatJobUseCase;

    @Autowired
    private FailMediaVideoPackagingJobUseCase failJobUseCase;

    @Autowired
    private CompleteVerifiedHlsPackagingUseCase completeJobUseCase;

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
            jdbcTemplate.update(
                    "DELETE v FROM media_image_variants v "
                            + "JOIN media_asset_versions av ON av.id = v.version_id "
                            + "WHERE av.asset_id = ?",
                    id
            );
            jdbcTemplate.update("DELETE FROM media_asset_versions WHERE asset_id = ?", id);
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", id);
        }
        createdAssetIds.clear();
    }

    private MediaAsset seedActiveVideoAsset(UUID assetId, UUID versionId) {
        createdAssetIds.add(assetId);
        MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, clockRef.get());
        assetAdapter.save(asset);
        MediaAssetVersion v1 = MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                StorageLocation.of(R2_PROVIDER.value(), "video/sources/" + assetId + "/v1/source.mp4"),
                null,
                SOURCE_SHA256,
                MimeType.of("video/mp4"),
                10_000_000L,
                "source.mp4",
                clockRef.get()
        );
        versionAdapter.save(v1);
        return asset;
    }

    @Test
    @DisplayName("V100 Flyway schema installs tables, unique indexes, and version identity triplet FK constraints")
    void shouldVerifyV100SchemaTablesUniqueConstraintsAndTripletFks() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name IN ('media_video_hls_packages', 'media_video_packaging_jobs', 'media_hls_revocation_outbox')",
                String.class
        );
        assertThat(tables).containsExactlyInAnyOrder(
                "media_video_hls_packages",
                "media_video_packaging_jobs",
                "media_hls_revocation_outbox"
        );

        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() "
                        + "AND constraint_name IN ("
                        + "'uq_media_asset_versions_identity_triplet', "
                        + "'fk_hls_pkg_version_triplet', 'uq_hls_pkg_one_ready', 'uq_hls_pkg_uuid', 'uq_hls_pkg_provider_prefix', "
                        + "'fk_hls_job_version_triplet', 'uq_hls_job_one_active', 'uq_hls_job_current_pkg', "
                        + "'uq_hls_rev_outbox_idempotency', 'uq_hls_rev_outbox_scope_seq')",
                String.class
        );
        assertThat(constraints).containsExactlyInAnyOrder(
                "uq_media_asset_versions_identity_triplet",
                "fk_hls_pkg_version_triplet",
                "uq_hls_pkg_one_ready",
                "uq_hls_pkg_uuid",
                "uq_hls_pkg_provider_prefix",
                "fk_hls_job_version_triplet",
                "uq_hls_job_one_active",
                "uq_hls_job_current_pkg",
                "uq_hls_rev_outbox_idempotency",
                "uq_hls_rev_outbox_scope_seq"
        );
    }

    @Test
    @DisplayName("V100 rejects mismatched version triplet FKs, invalid CHECK constraints, duplicate READY packages, and duplicate active jobs")
    void shouldRejectTripletMismatchesCheckViolationsAndDuplicateActiveOrReadyRows() {
        UUID assetA = UUID.randomUUID();
        UUID verA1 = UUID.randomUUID();
        seedActiveVideoAsset(assetA, verA1);

        UUID assetB = UUID.randomUUID();
        UUID verB1 = UUID.randomUUID();
        seedActiveVideoAsset(assetB, verB1);

        Timestamp ts = Timestamp.from(clockRef.get());

        // 1. Mismatched asset_id vs source_version_id rejected by fk_hls_pkg_version_triplet
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO media_video_hls_packages (id, asset_id, source_version_id, source_version_number, "
                        + "package_uuid, attempt_number, storage_provider_id, storage_bucket_name, base_prefix, "
                        + "master_manifest_key, segment_duration_seconds, status, created_at, updated_at, persistence_version) "
                        + "VALUES (?, ?, ?, 1, ?, 1, 'r2-movie', 'bucket', ?, ?, 6, 'PROCESSING', ?, ?, 0)",
                UUID.randomUUID().toString(), assetA.toString(), verB1.toString(), UUID.randomUUID().toString(),
                "hls/bad-asset/", "hls/bad-asset/master.m3u8", ts, ts
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 2. Mismatched source_version_number (2 instead of 1) rejected by fk_hls_job_version_triplet
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO media_video_packaging_jobs (id, asset_id, source_version_id, source_version_number, "
                        + "status, next_attempt_at, created_at, updated_at, persistence_version) "
                        + "VALUES (?, ?, ?, 2, 'PENDING', ?, ?, ?, 0)",
                UUID.randomUUID().toString(), assetA.toString(), verA1.toString(), ts, ts, ts
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 3. Invalid segment duration (out of 2..10) rejected by CHECK
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO media_video_hls_packages (id, asset_id, source_version_id, source_version_number, "
                        + "package_uuid, attempt_number, storage_provider_id, storage_bucket_name, base_prefix, "
                        + "master_manifest_key, segment_duration_seconds, status, created_at, updated_at, persistence_version) "
                        + "VALUES (?, ?, ?, 1, ?, 1, 'r2-movie', 'bucket', ?, ?, 1, 'PROCESSING', ?, ?, 0)",
                UUID.randomUUID().toString(), assetA.toString(), verA1.toString(), UUID.randomUUID().toString(),
                "hls/bad-dur/", "hls/bad-dur/master.m3u8", ts, ts
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_hls_pkg_segment_duration");

        // 4. READY without verification evidence rejected by chk_hls_pkg_ready_evidence
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO media_video_hls_packages (id, asset_id, source_version_id, source_version_number, "
                        + "package_uuid, attempt_number, storage_provider_id, storage_bucket_name, base_prefix, "
                        + "master_manifest_key, segment_duration_seconds, status, created_at, updated_at, persistence_version) "
                        + "VALUES (?, ?, ?, 1, ?, 1, 'r2-movie', 'bucket', ?, ?, 6, 'READY', ?, ?, 0)",
                UUID.randomUUID().toString(), assetA.toString(), verA1.toString(), UUID.randomUUID().toString(),
                "hls/bad-ready/", "hls/bad-ready/master.m3u8", ts, ts
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_hls_pkg_ready_evidence");

        // 5. uq_hls_pkg_one_ready blocks a second READY package for same source_version_id
        jdbcTemplate.update(
                "INSERT INTO media_video_hls_packages (id, asset_id, source_version_id, source_version_number, "
                        + "package_uuid, attempt_number, storage_provider_id, storage_bucket_name, base_prefix, "
                        + "master_manifest_key, segment_duration_seconds, master_manifest_sha256, total_size_bytes, "
                        + "object_count, status, verified_at, ready_at, created_at, updated_at, persistence_version) "
                        + "VALUES (?, ?, ?, 1, ?, 1, 'r2-movie', 'bucket', ?, ?, 6, ?, 1024, 4, 'READY', ?, ?, ?, ?, 0)",
                UUID.randomUUID().toString(), assetA.toString(), verA1.toString(), UUID.randomUUID().toString(),
                "hls/ready-1/", "hls/ready-1/master.m3u8", MANIFEST_SHA256.value(), ts, ts, ts, ts
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO media_video_hls_packages (id, asset_id, source_version_id, source_version_number, "
                        + "package_uuid, attempt_number, storage_provider_id, storage_bucket_name, base_prefix, "
                        + "master_manifest_key, segment_duration_seconds, master_manifest_sha256, total_size_bytes, "
                        + "object_count, status, verified_at, ready_at, created_at, updated_at, persistence_version) "
                        + "VALUES (?, ?, ?, 1, ?, 2, 'r2-movie', 'bucket', ?, ?, 6, ?, 2048, 5, 'READY', ?, ?, ?, ?, 0)",
                UUID.randomUUID().toString(), assetA.toString(), verA1.toString(), UUID.randomUUID().toString(),
                "hls/ready-2/", "hls/ready-2/master.m3u8", MANIFEST_SHA256.value(), ts, ts, ts, ts
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 6. uq_hls_job_one_active allows 1 PENDING job and rejects second active job, while SUCCEEDED does not collide
        jdbcTemplate.update(
                "INSERT INTO media_video_packaging_jobs (id, asset_id, source_version_id, source_version_number, "
                        + "status, next_attempt_at, created_at, updated_at, persistence_version) "
                        + "VALUES (?, ?, ?, 1, 'PENDING', ?, ?, ?, 0)",
                UUID.randomUUID().toString(), assetA.toString(), verA1.toString(), ts, ts, ts
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO media_video_packaging_jobs (id, asset_id, source_version_id, source_version_number, "
                        + "status, next_attempt_at, created_at, updated_at, persistence_version) "
                        + "VALUES (?, ?, ?, 1, 'PENDING', ?, ?, ?, 0)",
                UUID.randomUUID().toString(), assetA.toString(), verA1.toString(), ts, ts, ts
        )).isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update(
                "INSERT INTO media_video_packaging_jobs (id, asset_id, source_version_id, source_version_number, "
                        + "status, next_attempt_at, created_at, updated_at, completed_at, persistence_version) "
                        + "VALUES (?, ?, ?, 1, 'SUCCEEDED', ?, ?, ?, ?, 0)",
                UUID.randomUUID().toString(), assetA.toString(), verA1.toString(), ts, ts, ts, ts
        );
    }

    @Test
    @DisplayName("Lifecycle across v1 re-packaging and v2 upgrade strictly respects authoritative currentVersionNumber")
    void shouldHandleRepackagingAndVersionUpgradeWithoutStaleReadySelection() {
        UUID assetId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        MediaAsset asset = seedActiveVideoAsset(assetId, v1Id);

        // Enqueue v1 job (idempotent)
        MediaVideoPackagingJob job1 = createJobUseCase.execute(assetId);
        MediaVideoPackagingJob sameJob = createJobUseCase.execute(assetId);
        assertThat(sameJob.getId()).isEqualTo(job1.getId());

        // Claim attempt 1 -> PROCESSING package; current ready must still be empty
        ClaimedVideoPackagingLease lease1 = claimJobUseCase.claimNext("worker-1", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket")
                .orElseThrow();
        assertThat(lease1.leaseGeneration()).isEqualTo(1L);
        assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetId)).isEmpty();

        // Fail attempt 1 -> schedules retry, package becomes FAILED
        clockRef.set(BASE_TIME.plusSeconds(10));
        MediaVideoPackagingJob failedJob = failJobUseCase.execute(
                assetId, lease1.jobId(), "worker-1", lease1.leaseGeneration(), "transient", Duration.ofSeconds(30)
        );
        assertThat(failedJob.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.PENDING);
        assertThat(hlsPackageAdapter.findById(lease1.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.FAILED);

        // Advance past backoff, claim attempt 2, complete -> READY
        clockRef.set(BASE_TIME.plusSeconds(60));
        ClaimedVideoPackagingLease lease2 = claimJobUseCase.claimNext("worker-1", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket")
                .orElseThrow();
        assertThat(lease2.leaseGeneration()).isEqualTo(2L);
        assertThat(lease2.packageId()).isNotEqualTo(lease1.packageId());

        MediaVideoHlsPackage readyPkg1 = completeJobUseCase.completeWithVerifiedReceipt(
                assetId, lease2.jobId(), lease2.packageId(), "worker-1", lease2.leaseGeneration(),
                new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 51200L, 6, "[{\"height\":720}]", clockRef.get())
        );
        assertThat(readyPkg1.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.READY);
        assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetId)).containsInstanceOf(MediaVideoHlsPackage.class);

        // Re-package v1 via new job -> old READY pkg becomes SUPERSEDED, new pkg becomes READY
        clockRef.set(BASE_TIME.plusSeconds(120));
        MediaVideoPackagingJob repackageJob = createJobUseCase.execute(assetId);
        assertThat(repackageJob.getId()).isNotEqualTo(job1.getId());
        ClaimedVideoPackagingLease repackageLease = claimJobUseCase.claimNext("worker-2", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket")
                .orElseThrow();
        MediaVideoHlsPackage readyPkg2 = completeJobUseCase.completeWithVerifiedReceipt(
                assetId, repackageLease.jobId(), repackageLease.packageId(), "worker-2", repackageLease.leaseGeneration(),
                new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 61440L, 7, "[{\"height\":1080}]", clockRef.get())
        );
        assertThat(hlsPackageAdapter.findById(readyPkg1.getId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.SUPERSEDED);
        assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetId))
                .get().extracting(MediaVideoHlsPackage::getId).isEqualTo(readyPkg2.getId());

        // Advance asset to v2 -> v1 READY package must NEVER be returned by findCurrentReadyByAssetId
        clockRef.set(BASE_TIME.plusSeconds(180));
        int v2Num = asset.registerNextVersion(clockRef.get());
        assetAdapter.save(asset);
        UUID v2Id = UUID.randomUUID();
        versionAdapter.save(MediaAssetVersion.create(
                v2Id, assetId, v2Num,
                StorageLocation.of(R2_PROVIDER.value(), "video/sources/" + assetId + "/v2/source.mp4"),
                null, SOURCE_SHA256, MimeType.of("video/mp4"), 20_000_000L, "source-v2.mp4", clockRef.get()
        ));
        assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetId)).isEmpty();
        assertThat(hlsPackageAdapter.findReadyBySourceVersionId(v1Id)).isPresent();
    }

    @Test
    @DisplayName("PurgeDeletedMediaAssetUseCase and PurgeMediaAssetMetadataService fail closed while HLS rows exist")
    void shouldFailClosedDuringPurgeWhenHlsPackagesOrJobsExist() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        Instant deletedAt = BASE_TIME.minus(Duration.ofDays(10));
        MediaAsset deletedAsset = MediaAsset.rehydrate(
                assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, MediaAssetStatus.DELETED, 1,
                deletedAt.minus(Duration.ofDays(5)), deletedAt
        );
        assetAdapter.save(deletedAsset);
        versionAdapter.save(MediaAssetVersion.create(
                versionId, assetId, 1,
                StorageLocation.of(R2_PROVIDER.value(), "video/sources/" + assetId + "/v1/source.mp4"),
                null, SOURCE_SHA256, MimeType.of("video/mp4"), 10_000L, "source.mp4", deletedAt
        ));

        // Attach an HLS job directly (pending)
        packagingJobAdapter.save(MediaVideoPackagingJob.createPending(
                UUID.randomUUID(), assetId, versionId, 1, 3, deletedAt
        ));

        BinaryStoragePort storageSpy = Mockito.mock(BinaryStoragePort.class);
        StorageProviderResolverPort resolver = providerId -> storageSpy;
        MediaAssetReferenceCheckPort noRefPort = new MediaAssetReferenceCheckPort() {
            @Override
            public boolean supports(MediaTypeDTO mediaType) {
                return mediaType == MediaTypeDTO.VIDEO;
            }

            @Override
            public void ensureNotReferenced(UUID id) {
                // unreferenced
            }
        };
        PurgeDeletedMediaAssetUseCase purgeUseCase = new PurgeDeletedMediaAssetUseCase(
                assetAdapter, versionAdapter, variantAdapter, resolver, purgeMetadataService,
                clockRef::get, null, null, List.of(noRefPort), hlsPackageAdapter, packagingJobAdapter
        );

        assertThatThrownBy(() -> purgeMetadataService.execute(assetId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HLS packaging jobs or packages still exist");

        assertThatThrownBy(() -> purgeUseCase.execute(new PurgeDeletedMediaAssetCommand(assetId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HLS packaging jobs or packages exist");

        verify(storageSpy, never()).purgeExactKey(any());
        assertThat(assetAdapter.findById(assetId)).isPresent();
    }

    @Test
    @DisplayName("LS-01..LS-08: Enforces exact leaseExpiresAt > now boundary for heartbeat, completion, failure, and reclaim")
    void shouldEnforceExactLeaseExpiryBoundaryAcrossHeartbeatCompletionFailAndReclaim() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        seedActiveVideoAsset(assetId, versionId);
        createJobUseCase.execute(assetId);

        Duration leaseDuration = Duration.ofMinutes(5);
        ClaimedVideoPackagingLease lease1 = claimJobUseCase.claimNext("worker-hb", leaseDuration, R2_PROVIDER, "movie-bucket")
                .orElseThrow();
        Instant initialExpiry = BASE_TIME.plus(leaseDuration);

        // LS-01 & LS-04: Heartbeat immediately before expiry succeeds and extends leaseExpiresAt
        clockRef.set(initialExpiry.minusMillis(1));
        boolean heartbeatBeforeExpiry = heartbeatJobUseCase.execute(
                lease1.jobId(), "worker-hb", lease1.leaseGeneration(), Duration.ofMinutes(5)
        );
        assertThat(heartbeatBeforeExpiry).isTrue();
        Instant extendedExpiry = packagingJobAdapter.findById(lease1.jobId()).orElseThrow().getLeaseExpiresAt();
        assertThat(extendedExpiry).isEqualTo(initialExpiry.minusMillis(1).plus(Duration.ofMinutes(5)));

        // LS-03: Heartbeat exactly at extendedExpiry fails even with zero reclaim
        clockRef.set(extendedExpiry);
        boolean heartbeatAtExactExpiry = heartbeatJobUseCase.execute(
                lease1.jobId(), "worker-hb", lease1.leaseGeneration(), Duration.ofMinutes(5)
        );
        assertThat(heartbeatAtExactExpiry).isFalse();
        assertThat(packagingJobAdapter.findById(lease1.jobId()).orElseThrow().getLeaseExpiresAt())
                .isEqualTo(extendedExpiry);

        // LS-02: Heartbeat after expiry fails even with zero reclaim
        clockRef.set(extendedExpiry.plusSeconds(1));
        boolean heartbeatAfterExpiry = heartbeatJobUseCase.execute(
                lease1.jobId(), "worker-hb", lease1.leaseGeneration(), Duration.ofMinutes(5)
        );
        assertThat(heartbeatAfterExpiry).isFalse();

        // LS-08: Completion exactly at expiry fails without READY publication
        VerifiedHlsPackageReceipt receipt = new VerifiedHlsPackageReceipt(
                MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", BASE_TIME
        );
        clockRef.set(extendedExpiry);
        assertThatThrownBy(() -> completeJobUseCase.completeWithVerifiedReceipt(
                assetId, lease1.jobId(), lease1.packageId(), "worker-hb", lease1.leaseGeneration(), receipt
        )).isInstanceOf(StalePackagingLeaseException.class);
        assertThat(hlsPackageAdapter.findById(lease1.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.PROCESSING);
        assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetId)).isEmpty();

        // LS-07: Completion and failure reporting after expiry fail without READY publication or mutation
        clockRef.set(extendedExpiry.plusSeconds(10));
        assertThatThrownBy(() -> completeJobUseCase.completeWithVerifiedReceipt(
                assetId, lease1.jobId(), lease1.packageId(), "worker-hb", lease1.leaseGeneration(), receipt
        )).isInstanceOf(StalePackagingLeaseException.class);
        assertThatThrownBy(() -> failJobUseCase.execute(
                assetId, lease1.jobId(), "worker-hb", lease1.leaseGeneration(), "too late", Duration.ofSeconds(10)
        )).isInstanceOf(StalePackagingLeaseException.class);
        assertThat(hlsPackageAdapter.findById(lease1.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.PROCESSING);
        assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetId)).isEmpty();

        // Exact boundary reclaim: claimNext at leaseExpiresAt succeeds (gen=2)
        ClaimedVideoPackagingLease lease2 = claimJobUseCase.claimNext("worker-reclaim", leaseDuration, R2_PROVIDER, "movie-bucket")
                .orElseThrow();
        assertThat(lease2.leaseGeneration()).isEqualTo(2L);
        assertThat(hlsPackageAdapter.findById(lease1.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.ABANDONED);

        // LS-05: Old worker heartbeat returns false after generation incremented
        assertThat(heartbeatJobUseCase.execute(
                lease1.jobId(), "worker-hb", lease1.leaseGeneration(), Duration.ofMinutes(5)
        )).isFalse();

        // LS-06 & LS-10: Current worker completes before expiry cleanly
        clockRef.set(lease2.leaseExpiresAt().minusMillis(1));
        MediaVideoHlsPackage ready = completeJobUseCase.completeWithVerifiedReceipt(
                assetId, lease2.jobId(), lease2.packageId(), "worker-reclaim", lease2.leaseGeneration(), receipt
        );
        assertThat(ready.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.READY);
        assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(assetId))
                .get().extracting(MediaVideoHlsPackage::getId).isEqualTo(lease2.packageId());
    }
}
