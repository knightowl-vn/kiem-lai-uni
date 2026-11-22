package com.universe.media.infrastructure.persistence;

import com.universe.media.application.asset.ArchiveMediaAssetCommand;
import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.AssignMediaAssetClientTagUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetCommand;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.FindActiveMediaAssetsByClientTagKeysetUseCase;
import com.universe.media.application.asset.GetCurrentMediaAssetVersionSnapshotUseCase;
import com.universe.media.application.asset.GetMediaAssetCurrentMetadataUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.LockAndInspectMediaAssetUseCase;
import com.universe.media.application.asset.LockAndValidateActiveMediaAssetUseCase;
import com.universe.media.application.asset.OpenMediaAssetVersionContentUseCase;
import com.universe.media.application.asset.PurgeDeletedMediaAssetCommand;
import com.universe.media.application.asset.PurgeDeletedMediaAssetUseCase;
import com.universe.media.application.asset.PurgeMediaAssetMetadataService;
import com.universe.media.application.asset.RasterContentSignatureValidator;
import com.universe.media.application.asset.RegisterMediaAssetUseCase;
import com.universe.media.application.asset.RegisterMediaAssetVersionCommand;
import com.universe.media.application.asset.RegisterMediaAssetVersionConditionalResult;
import com.universe.media.application.asset.RegisterMediaAssetVersionUseCase;
import com.universe.media.application.asset.RestoreMediaAssetCommand;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetCommand;
import com.universe.media.application.asset.UploadMediaAssetResult;
import com.universe.media.application.asset.UploadMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionCommand;
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalResult;
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionResult;
import com.universe.media.application.asset.UploadMediaAssetVersionUseCase;
import com.universe.media.application.asset.VideoUploadValidator;
import com.universe.media.application.exceptions.StalePackagingLeaseException;
import com.universe.media.application.facade.MediaFacade;
import com.universe.media.application.hls.AutoPackagingEnqueueResult;
import com.universe.media.application.hls.AutoPackagingEnqueueStatus;
import com.universe.media.application.hls.HlsAutoEnqueueProperties;
import com.universe.media.application.hls.OptInHlsPackagingEnqueueCoordinator;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.application.storage.MediaStorageCutoverBarrier;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.application.variant.GenerateMediaImageVariantUseCase;
import com.universe.media.contracts.dto.MediaVersionUploadOutcome;
import com.universe.media.contracts.exceptions.MediaAssetReferencedException;
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
import com.universe.media.application.hls.VerifiedHlsPackageReceipt;
import com.universe.media.application.ports.MediaVideoHlsPackagePurgeCandidate;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
        OptInHlsPackagingEnqueueCoordinator.class,
        ClaimMediaVideoPackagingJobUseCase.class,
        HeartbeatMediaVideoPackagingJobUseCase.class,
        FailMediaVideoPackagingJobUseCase.class,
        CompleteVerifiedHlsPackagingUseCase.class,
        CancelOrSupersedeMediaVideoPackagingUseCase.class,
        ClaimHlsPackagePurgeUseCase.class,
        CompleteHlsPackagePurgeUseCase.class,
        RegisterMediaAssetUseCase.class,
        RegisterMediaAssetVersionUseCase.class,
        UploadMediaAssetUseCase.class,
        UploadMediaAssetVersionUseCase.class,
        UploadMediaAssetVersionConditionalUseCase.class,
        RasterContentSignatureValidator.class,
        VideoUploadValidator.class,
        DeleteMediaAssetUseCase.class,
        ArchiveMediaAssetUseCase.class,
        RestoreMediaAssetUseCase.class,
        MediaFacade.class,
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

        @Bean
        public AtomicReference<UUID> referencedAssetGuard() {
            return new AtomicReference<>();
        }

        @Bean
        public MediaAssetReferenceCheckPort mediaAssetReferenceCheckPort(AtomicReference<UUID> referencedAssetGuard) {
            return new MediaAssetReferenceCheckPort() {
                @Override public boolean supports(MediaTypeDTO mediaType) { return true; }
                @Override public void ensureNotReferenced(UUID mediaAssetId) {
                    if (mediaAssetId.equals(referencedAssetGuard.get())) {
                        throw new MediaAssetReferencedException(mediaAssetId);
                    }
                }
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
        public AtomicBoolean autoEnqueueEnabledFlag() {
            return new AtomicBoolean(false);
        }

        @Bean
        public AtomicBoolean autoEnqueueOnRestoreFlag() {
            return new AtomicBoolean(false);
        }

        @Bean
        public HlsAutoEnqueueProperties hlsAutoEnqueueProperties(
                AtomicBoolean autoEnqueueEnabledFlag,
                AtomicBoolean autoEnqueueOnRestoreFlag
        ) {
            return new HlsAutoEnqueueProperties(false, false) {
                @Override public boolean isEnabled() { return autoEnqueueEnabledFlag.get(); }
                @Override public boolean isOnRestore() { return autoEnqueueOnRestoreFlag.get(); }
                @Override public boolean isRestoreAutoEnqueueActive() {
                    return autoEnqueueEnabledFlag.get() && autoEnqueueOnRestoreFlag.get();
                }
            };
        }

        @Bean
        public BinaryStoragePort binaryStoragePort() {
            BinaryStoragePort port = Mockito.mock(BinaryStoragePort.class);
            Mockito.when(port.providerId()).thenReturn(StorageProviderId.of("local"));
            Mockito.when(port.store(any(), any(), Mockito.anyLong(), any())).thenAnswer(inv -> {
                StorageKey key = inv.getArgument(0);
                InputStream in = inv.getArgument(1);
                in.readAllBytes();
                return new StoredBinaryObject(StorageLocation.of(StorageProviderId.of("local"), key), null);
            });
            return port;
        }

        @Bean
        public StorageProviderResolverPort storageProviderResolverPort(BinaryStoragePort binaryStoragePort) {
            StorageProviderResolverPort resolver = Mockito.mock(StorageProviderResolverPort.class);
            Mockito.when(resolver.resolve(any())).thenReturn(binaryStoragePort);
            return resolver;
        }

        @Bean
        public MediaStorageRoutingService mediaStorageRoutingService() {
            MediaStorageRoutingService routing = Mockito.mock(MediaStorageRoutingService.class);
            Mockito.when(routing.resolveWriteProvider(any(), any())).thenReturn(StorageProviderId.of("local"));
            Mockito.when(routing.generateStorageKey(any(), any(), any(), Mockito.anyInt()))
                    .thenAnswer(inv -> StorageKey.of("video/test/" + UUID.randomUUID() + ".mp4"));
            return routing;
        }

        @Bean public GetMediaAssetDetailUseCase getMediaAssetDetailUseCase() { return Mockito.mock(GetMediaAssetDetailUseCase.class); }
        @Bean public GetMediaAssetCurrentMetadataUseCase getMediaAssetCurrentMetadataUseCase() { return Mockito.mock(GetMediaAssetCurrentMetadataUseCase.class); }
        @Bean public ChangeMediaVisibilityUseCase changeMediaVisibilityUseCase() { return Mockito.mock(ChangeMediaVisibilityUseCase.class); }
        @Bean public GenerateMediaImageVariantUseCase generateMediaImageVariantUseCase() { return Mockito.mock(GenerateMediaImageVariantUseCase.class); }
        @Bean public GetCurrentMediaAssetVersionSnapshotUseCase getCurrentMediaAssetVersionSnapshotUseCase() { return Mockito.mock(GetCurrentMediaAssetVersionSnapshotUseCase.class); }
        @Bean public OpenMediaAssetVersionContentUseCase openMediaAssetVersionContentUseCase() { return Mockito.mock(OpenMediaAssetVersionContentUseCase.class); }
        @Bean public AssignMediaAssetClientTagUseCase assignMediaAssetClientTagUseCase() { return Mockito.mock(AssignMediaAssetClientTagUseCase.class); }
        @Bean public FindActiveMediaAssetsByClientTagKeysetUseCase findActiveMediaAssetsByClientTagKeysetUseCase() { return Mockito.mock(FindActiveMediaAssetsByClientTagKeysetUseCase.class); }
        @Bean public LockAndValidateActiveMediaAssetUseCase lockAndValidateActiveMediaAssetUseCase() { return Mockito.mock(LockAndValidateActiveMediaAssetUseCase.class); }
        @Bean public LockAndInspectMediaAssetUseCase lockAndInspectMediaAssetUseCase() { return Mockito.mock(LockAndInspectMediaAssetUseCase.class); }
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

    @Autowired
    private CancelOrSupersedeMediaVideoPackagingUseCase cancelOrSupersedeUseCase;

    @Autowired
    private UploadMediaAssetUseCase uploadMediaAssetUseCase;

    @Autowired
    private UploadMediaAssetVersionUseCase uploadMediaAssetVersionUseCase;

    @Autowired
    private UploadMediaAssetVersionConditionalUseCase uploadMediaAssetVersionConditionalUseCase;

    @Autowired
    private OptInHlsPackagingEnqueueCoordinator optInEnqueueCoordinator;

    @Autowired
    private MediaFacade mediaFacade;

    @Autowired
    private AtomicBoolean autoEnqueueEnabledFlag;

    @Autowired
    private AtomicBoolean autoEnqueueOnRestoreFlag;

    @Autowired
    private AtomicReference<UUID> referencedAssetGuard;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private final List<UUID> createdAssetIds = new ArrayList<>();

    @BeforeEach
    void resetClock() {
        clockRef.set(BASE_TIME);
        referencedAssetGuard.set(null);
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

        // Fail attempt 1 -> schedules retry, package becomes FAILED with 24h retention
        Instant failTime = BASE_TIME.plusSeconds(10);
        clockRef.set(failTime);
        MediaVideoPackagingJob failedJob = failJobUseCase.execute(
                assetId, lease1.jobId(), "worker-1", lease1.leaseGeneration(), "transient", Duration.ofSeconds(30)
        );
        assertThat(failedJob.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.PENDING);
        MediaVideoHlsPackage failedPkg1 = hlsPackageAdapter.findById(lease1.packageId()).orElseThrow();
        assertThat(failedPkg1.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.FAILED);
        assertThat(failedPkg1.getPurgeAfter()).isEqualTo(failTime.plus(Duration.ofHours(24)));

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

    @Test
    @DisplayName("Permanent failure persists terminal FAILED job with attempts remaining, sets 24h package retention, and clears active job slot")
    void shouldPersistPermanentFailureWith24hPackageRetentionAndClearActiveJobSlot() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        seedActiveVideoAsset(assetId, versionId);
        MediaVideoPackagingJob created = createJobUseCase.execute(assetId, 3);

        ClaimedVideoPackagingLease lease = claimJobUseCase.claimNext("worker-perm", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket")
                .orElseThrow();
        Instant permFailTime = BASE_TIME.plusSeconds(20);
        clockRef.set(permFailTime);

        MediaVideoPackagingJob failed = failJobUseCase.executePermanentFailure(
                assetId, lease.jobId(), "worker-perm", lease.leaseGeneration(), "Unsupported HDR source"
        );
        assertThat(failed.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getMaxAttempts()).isEqualTo(3);
        assertThat(failed.getLeaseOwner()).isNull();
        assertThat(failed.getLeaseExpiresAt()).isNull();

        MediaVideoHlsPackage pkg = hlsPackageAdapter.findById(lease.packageId()).orElseThrow();
        assertThat(pkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.FAILED);
        assertThat(pkg.getPurgeAfter()).isEqualTo(permFailTime.plus(Duration.ofHours(24)));

        // Re-verify DB candidate query skips terminal FAILED job even far in the future
        assertThat(packagingJobAdapter.findClaimableCandidates(permFailTime.plus(Duration.ofDays(30)), 20))
                .noneMatch(c -> c.jobId().equals(created.getId()));
    }

    @Test
    @DisplayName("Purge candidate scan uses idx_hls_pkg_purge, avoids legacy starvation, tracks @Version across txs, and preserves PURGED tombstone")
    void shouldVerifyPurgeCandidateIndexNonStarvationVersionIncrementsAndTombstoneRetention() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        seedActiveVideoAsset(assetId, versionId);

        Timestamp baseTs = Timestamp.from(BASE_TIME);
        Timestamp recentLegacyTs = Timestamp.from(BASE_TIME.plus(Duration.ofHours(2)));

        // Insert 3 legacy FAILED rows with purge_after = updated_at = T0+2h (stored purge_after <= now at T0+24h30m,
        // yet NOT past the 24h floor!) alongside 1 truly eligible FAILED row created at T0.
        for (int i = 0; i < 3; i++) {
            UUID legId = UUID.randomUUID();
            UUID legUuid = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO media_video_hls_packages (id, asset_id, source_version_id, source_version_number, "
                            + "package_uuid, attempt_number, storage_provider_id, storage_bucket_name, base_prefix, "
                            + "master_manifest_key, segment_duration_seconds, status, failure_reason, "
                            + "created_at, updated_at, purge_after, persistence_version) "
                            + "VALUES (?, ?, ?, 1, ?, ?, 'r2-hls', 'hls-bucket', ?, ?, 6, 'FAILED', 'Legacy immediate', ?, ?, ?, 0)",
                    legId.toString(), assetId.toString(), versionId.toString(), legUuid.toString(), i + 1,
                    "objects/hls/" + assetId + "/v1/" + legUuid + "/",
                    "objects/hls/" + assetId + "/v1/" + legUuid + "/master.m3u8",
                    baseTs, recentLegacyTs, recentLegacyTs
            );
        }

        UUID eligibleId = UUID.randomUUID();
        UUID eligibleUuid = UUID.randomUUID();
        MediaVideoHlsPackage initial = MediaVideoHlsPackage.startProcessing(
                eligibleId, assetId, versionId, 1, eligibleUuid, 10,
                StorageProviderId.of("r2-hls"), "hls-bucket",
                StorageKey.of("objects/hls/" + assetId + "/v1/" + eligibleUuid + "/"),
                6, BASE_TIME
        );
        hlsPackageAdapter.save(initial);
        MediaVideoHlsPackage loadedProcessing = hlsPackageAdapter.findById(eligibleId).orElseThrow();
        long vProcessing = loadedProcessing.getPersistenceVersion();
        assertThat(vProcessing).isEqualTo(0L);

        loadedProcessing.markFailed("Encoder timeout", BASE_TIME, BASE_TIME); // even with legacy purgeAfter=T0
        MediaVideoHlsPackage savedFailed = hlsPackageAdapter.save(loadedProcessing);
        assertThat(savedFailed.getPersistenceVersion()).isEqualTo(1L);

        // At T0 + 24h30m: limit=1 scan must return eligibleId cleanly without starving behind the 3 recent legacy rows
        Instant scanTime = BASE_TIME.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(30));
        List<MediaVideoHlsPackagePurgeCandidate> candidates =
                hlsPackageAdapter.findPurgeEligibleCandidates(scanTime, 1);
        assertThat(candidates).containsExactly(new MediaVideoHlsPackagePurgeCandidate(eligibleId, assetId));

        // Verify MySQL 8 query plan includes idx_hls_pkg_purge
        List<java.util.Map<String, Object>> explainRows = jdbcTemplate.queryForList(
                "EXPLAIN SELECT p.id, p.asset_id FROM media_video_hls_packages p "
                        + "WHERE p.status IN ('FAILED','ABANDONED','SUPERSEDED','PURGING') "
                        + "AND p.purge_after IS NOT NULL AND p.purge_after <= ? "
                        + "AND ((p.status IN ('FAILED','ABANDONED') AND p.updated_at <= ? AND p.updated_at >= p.created_at) "
                        + "OR (p.status = 'SUPERSEDED' AND p.superseded_at IS NOT NULL AND p.superseded_at <= ? AND p.superseded_at >= p.created_at) "
                        + "OR p.status = 'PURGING') "
                        + "ORDER BY p.purge_after ASC, p.id ASC LIMIT 10",
                Timestamp.from(scanTime),
                Timestamp.from(scanTime.minus(Duration.ofHours(24))),
                Timestamp.from(scanTime.minus(Duration.ofDays(7)))
        );
        assertThat(explainRows).isNotEmpty();
        java.util.Map<String, Object> plan = explainRows.get(0);
        System.out.println("[HLS-2B3A2B-EXPLAIN] type=" + plan.get("type")
                + ", possible_keys=" + plan.get("possible_keys")
                + ", key=" + plan.get("key")
                + ", key_len=" + plan.get("key_len")
                + ", ref=" + plan.get("ref")
                + ", rows=" + plan.get("rows")
                + ", filtered=" + plan.get("filtered")
                + ", Extra=" + plan.get("Extra"));
        assertThat(String.valueOf(plan.get("possible_keys"))).contains("idx_hls_pkg_purge");
        assertThat(plan).containsKeys("type", "key", "rows", "Extra");

        // Claim -> version=2, fail -> version=3, reclaim -> version=4, PURGED -> version=5 & purgeAfter=null
        clockRef.set(scanTime);
        ClaimedHlsPackagePurgeLease lease1 = claimPurgeUseCase.claimNext(Duration.ofMinutes(5), 1).orElseThrow();
        assertThat(lease1.packageId()).isEqualTo(eligibleId);
        assertThat(lease1.claimVersion()).isEqualTo(2L);
        assertThat(hlsPackageAdapter.findById(eligibleId).orElseThrow().getPersistenceVersion()).isEqualTo(2L);

        clockRef.set(scanTime.plusSeconds(10));
        MediaVideoHlsPackage failedPurge = completePurgeUseCase.recordFailure(lease1, "R2 timeout", Duration.ofMinutes(15));
        assertThat(failedPurge.getPersistenceVersion()).isEqualTo(3L);
        assertThat(failedPurge.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGING);
        assertThat(failedPurge.getFailureReason()).isEqualTo("Encoder timeout | [purge-error: R2 timeout]");

        // Advance past backoff + 24h legacy floor so reclaim picks up eligibleId first (purgeAfter = scanTime+10s+15m < legacy T0+26h)
        clockRef.set(scanTime.plus(Duration.ofMinutes(16)));
        ClaimedHlsPackagePurgeLease lease2 = claimPurgeUseCase.claimNext(Duration.ofMinutes(5), 10).orElseThrow();
        assertThat(lease2.packageId()).isEqualTo(eligibleId);
        assertThat(lease2.claimVersion()).isEqualTo(4L);

        clockRef.set(scanTime.plus(Duration.ofMinutes(17)));
        MediaVideoHlsPackage purged = completePurgeUseCase.markPurged(lease2);
        assertThat(purged.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGED);
        assertThat(purged.getPurgeAfter()).isNull();
        assertThat(purged.getPersistenceVersion()).isEqualTo(5L);

        // Tombstone row stays persisted with immutable base_prefix and zero future purge eligibility
        MediaVideoHlsPackage tombstone = hlsPackageAdapter.findById(eligibleId).orElseThrow();
        assertThat(tombstone.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGED);
        assertThat(tombstone.getPurgeAfter()).isNull();
        assertThat(tombstone.getBasePrefix()).isEqualTo(initial.getBasePrefix());
        assertThat(hlsPackageAdapter.findPurgeEligibleCandidates(scanTime.plus(Duration.ofDays(30)), 20))
                .noneMatch(c -> c.packageId().equals(eligibleId));
    }

    @Test
    @DisplayName("A3a same-transaction hooks handle V2 supersession, UNCHANGED no-op, archive READY preservation, restore, delete cleanup & atomicity")
    void shouldCoordinateSameTransactionHlsLifecycleAcrossVersionReplaceArchiveRestoreDeleteAndRollback() {
        // 1. Seed VIDEO asset with v1 READY + v2 PENDING? First: v1 READY + duplicate v1 hash UNCHANGED leaves READY & PENDING untouched
        UUID assetId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        seedActiveVideoAsset(assetId, v1Id);

        MediaVideoPackagingJob v1Job = createJobUseCase.execute(assetId);
        ClaimedVideoPackagingLease v1Lease = claimJobUseCase.claimNext("w1", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket").orElseThrow();
        completeJobUseCase.completeWithVerifiedReceipt(
                assetId, v1Job.getId(), v1Lease.packageId(), "w1", v1Lease.leaseGeneration(),
                new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", BASE_TIME.plusSeconds(2))
        );

        RegisterMediaAssetVersionConditionalResult unchanged = registerVersionUseCase.registerConditionalVersion(
                new RegisterMediaAssetVersionCommand(
                        assetId, R2_PROVIDER.value(), "video/sources/" + assetId + "/v-dup/source.mp4", null,
                        SOURCE_SHA256.value(), "video/mp4", 10_000_000L, "dup.mp4"
                )
        );
        assertThat(unchanged.outcome()).isEqualTo(MediaVersionUploadOutcome.UNCHANGED);
        assertThat(hlsPackageAdapter.findById(v1Lease.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.READY);

        // 2. Forced rollback inside outer tx holding V2 version replacement rolls back BOTH v2 row AND v1 supersession
        org.springframework.transaction.support.TransactionTemplate txTpl =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        txTpl.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(() -> txTpl.execute(status -> {
            registerVersionUseCase.execute(new RegisterMediaAssetVersionCommand(
                    assetId, R2_PROVIDER.value(), "video/sources/" + assetId + "/v2-rollback/source.mp4", null,
                    "2222222222222222222222222222222222222222222222222222222222222222", "video/mp4", 12_000_000L, "v2.mp4"
            ));
            throw new IllegalStateException("Simulated rollback after V2 + HLS supersede");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(assetAdapter.findById(assetId).orElseThrow().getCurrentVersionNumber()).isEqualTo(1);
        assertThat(hlsPackageAdapter.findById(v1Lease.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.READY);

        // 3. Committed V2 registration atomically supersedes V1 READY package (7d) & creates zero V2 auto-enqueue jobs (A3b deferred)
        clockRef.set(BASE_TIME.plus(Duration.ofMinutes(5)));
        var v2Result = registerVersionUseCase.execute(new RegisterMediaAssetVersionCommand(
                assetId, R2_PROVIDER.value(), "video/sources/" + assetId + "/v2/source.mp4", null,
                "2222222222222222222222222222222222222222222222222222222222222222", "video/mp4", 12_000_000L, "v2.mp4"
        ));
        assertThat(v2Result.newVersionNumber()).isEqualTo(2);
        MediaVideoHlsPackage v1Superseded = hlsPackageAdapter.findById(v1Lease.packageId()).orElseThrow();
        assertThat(v1Superseded.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.SUPERSEDED);
        assertThat(v1Superseded.getPurgeAfter()).isEqualTo(clockRef.get().plus(Duration.ofDays(7)));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_video_packaging_jobs WHERE asset_id = ?",
                Integer.class,
                assetId.toString()
        )).isEqualTo(1); // Only V1 job; zero V2 auto-enqueue

        // 4. Seed V2 READY + an active V2 processing attempt on a second asset -> Archive preserves READY and abandons PROCESSING (24h)
        UUID asset2Id = UUID.randomUUID();
        UUID a2v1Id = UUID.randomUUID();
        seedActiveVideoAsset(asset2Id, a2v1Id);
        MediaVideoPackagingJob a2ReadyJob = createJobUseCase.execute(asset2Id);
        ClaimedVideoPackagingLease a2ReadyLease = claimJobUseCase.claimNext("w2", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket").orElseThrow();
        completeJobUseCase.completeWithVerifiedReceipt(
                asset2Id, a2ReadyJob.getId(), a2ReadyLease.packageId(), "w2", a2ReadyLease.leaseGeneration(),
                new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", clockRef.get().plusSeconds(1))
        );
        // Simulate v2 on asset2 with PENDING job, then blocked Archive due to consumer reference must leave job untouched
        registerVersionUseCase.execute(new RegisterMediaAssetVersionCommand(
                asset2Id, R2_PROVIDER.value(), "video/sources/" + asset2Id + "/v2/source.mp4", null,
                "3333333333333333333333333333333333333333333333333333333333333333", "video/mp4", 14_000_000L, "v2.mp4"
        ));
        MediaVideoPackagingJob a2v2Job = createJobUseCase.execute(asset2Id);
        ClaimedVideoPackagingLease a2v2Lease = claimJobUseCase.claimNext("w3", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket").orElseThrow();

        referencedAssetGuard.set(asset2Id);
        assertThatThrownBy(() -> archiveAssetUseCase.execute(new ArchiveMediaAssetCommand(asset2Id)))
                .isInstanceOf(MediaAssetReferencedException.class);
        assertThat(packagingJobAdapter.findById(a2v2Job.getId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoPackagingJobStatus.LEASED);

        // Allowed Archive cancels LEASED v2 job + abandons PROCESSING v2 package (24h), while v1 remains SUPERSEDED (never resurrected)
        referencedAssetGuard.set(null);
        Instant archiveAt = clockRef.get().plus(Duration.ofMinutes(2));
        clockRef.set(archiveAt);
        archiveAssetUseCase.execute(new ArchiveMediaAssetCommand(asset2Id));
        assertThat(packagingJobAdapter.findById(a2v2Job.getId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoPackagingJobStatus.CANCELLED);
        MediaVideoHlsPackage abandonedV2Pkg = hlsPackageAdapter.findById(a2v2Lease.packageId()).orElseThrow();
        assertThat(abandonedV2Pkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.ABANDONED);
        assertThat(abandonedV2Pkg.getPurgeAfter()).isEqualTo(archiveAt.plus(Duration.ofHours(24)));

        // Restore locks & moves back to ACTIVE with zero re-enqueue and zero SUPERSEDED resurrection
        clockRef.set(archiveAt.plusSeconds(10));
        restoreAssetUseCase.execute(new RestoreMediaAssetCommand(asset2Id));
        assertThat(assetAdapter.findById(asset2Id).orElseThrow().getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(hlsPackageAdapter.findById(a2ReadyLease.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.SUPERSEDED);
        assertThat(packagingJobAdapter.findById(a2v2Job.getId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoPackagingJobStatus.CANCELLED);

        // 5. Preserve READY across Archive -> Restore for asset with READY on current version, then Delete supersedes READY atomically (and rollback preserves READY!)
        UUID asset3Id = UUID.randomUUID();
        UUID a3v1Id = UUID.randomUUID();
        seedActiveVideoAsset(asset3Id, a3v1Id);
        MediaVideoPackagingJob a3Job = createJobUseCase.execute(asset3Id);
        ClaimedVideoPackagingLease a3Lease = claimJobUseCase.claimNext("w4", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket").orElseThrow();
        completeJobUseCase.completeWithVerifiedReceipt(
                asset3Id, a3Job.getId(), a3Lease.packageId(), "w4", a3Lease.leaseGeneration(),
                new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", clockRef.get().plusSeconds(1))
        );

        archiveAssetUseCase.execute(new ArchiveMediaAssetCommand(asset3Id));
        assertThat(hlsPackageAdapter.findById(a3Lease.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.READY);
        restoreAssetUseCase.execute(new RestoreMediaAssetCommand(asset3Id));
        assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(asset3Id))
                .get().extracting(MediaVideoHlsPackage::getId).isEqualTo(a3Lease.packageId());

        // Forced rollback on Delete leaves asset ACTIVE & package READY
        assertThatThrownBy(() -> txTpl.execute(status -> {
            deleteAssetUseCase.execute(new DeleteMediaAssetCommand(asset3Id));
            throw new IllegalStateException("Simulated rollback after Delete");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(assetAdapter.findById(asset3Id).orElseThrow().getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(hlsPackageAdapter.findById(a3Lease.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.READY);

        // Actual Delete atomically marks DELETED + SUPERSEDED (7d)
        deleteAssetUseCase.execute(new DeleteMediaAssetCommand(asset3Id));
        assertThat(assetAdapter.findById(asset3Id).orElseThrow().getStatus()).isEqualTo(MediaAssetStatus.DELETED);
        assertThat(hlsPackageAdapter.findById(a3Lease.packageId()).orElseThrow().getStatus())
                .isEqualTo(MediaVideoHlsPackageStatus.SUPERSEDED);
    }

    @Test
    @DisplayName("A3b opt-in post-commit enqueue integrates direct upload, conditional replace, delayed v2 skip & conservative restore policy in MySQL")
    void shouldCoordinateOptInPostCommitAutoEnqueueAcrossUploadVersionReplaceAndRestoreInMySql() {
        autoEnqueueEnabledFlag.set(false);
        autoEnqueueOnRestoreFlag.set(false);
        try {
            byte[] mp4V1 = "ftypmp42-v1-payload-bytes".getBytes(StandardCharsets.UTF_8);

            // 1. Flag OFF -> zero jobs created for initial VIDEO upload
            UploadMediaAssetResult offResult = uploadMediaAssetUseCase.execute(new UploadMediaAssetCommand(
                    new ByteArrayInputStream(mp4V1), mp4V1.length, "video/mp4",
                    MediaType.VIDEO, MediaVisibility.PRIVATE, "movie-off.mp4", "screen-video"
            ));
            createdAssetIds.add(offResult.assetId());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM media_video_packaging_jobs WHERE asset_id = ?",
                    Integer.class, offResult.assetId().toString()
            )).isEqualTo(0);

            // 2. Flag ON -> initial VIDEO upload creates 1 PENDING v1 job; non-VIDEO IMAGE creates 0 jobs
            autoEnqueueEnabledFlag.set(true);
            UploadMediaAssetResult onVideo = uploadMediaAssetUseCase.execute(new UploadMediaAssetCommand(
                    new ByteArrayInputStream(mp4V1), mp4V1.length, "video/mp4",
                    MediaType.VIDEO, MediaVisibility.PRIVATE, "movie-on.mp4", "screen-video"
            ));
            createdAssetIds.add(onVideo.assetId());
            List<MediaVideoPackagingJob> v1Jobs = readJobs(onVideo.assetId());
            assertThat(v1Jobs).hasSize(1);
            assertThat(v1Jobs.get(0).getStatus()).isEqualTo(MediaVideoPackagingJobStatus.PENDING);
            assertThat(v1Jobs.get(0).getSourceVersionNumber()).isEqualTo(1);
            assertThat(v1Jobs.get(0).getSourceVersionId()).isEqualTo(onVideo.versionId());

            byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
            UploadMediaAssetResult onImage = uploadMediaAssetUseCase.execute(new UploadMediaAssetCommand(
                    new ByteArrayInputStream(png), png.length, "image/png",
                    MediaType.IMAGE, MediaVisibility.PUBLIC, "cover.png", "cover"
            ));
            createdAssetIds.add(onImage.assetId());
            assertThat(packagingJobAdapter.existsByAssetId(onImage.assetId())).isFalse();

            // 3. Conditional upload UNCHANGED -> keeps v1 PENDING job intact, zero new jobs
            UploadMediaAssetVersionConditionalResult unchangedRes = uploadMediaAssetVersionConditionalUseCase.execute(
                    new UploadMediaAssetVersionCommand(
                            onVideo.assetId(), new ByteArrayInputStream(mp4V1), mp4V1.length, "video/mp4", "dup.mp4"
                    )
            );
            assertThat(unchangedRes.outcome()).isEqualTo(MediaVersionUploadOutcome.UNCHANGED);
            assertThat(readJobs(onVideo.assetId())).hasSize(1);
            assertThat(packagingJobAdapter.findById(v1Jobs.get(0).getId()).orElseThrow().getStatus())
                    .isEqualTo(MediaVideoPackagingJobStatus.PENDING);

            // 4. Conditional VERSION_CREATED -> cancels v1 & creates PENDING v2 job
            byte[] mp4V2 = "ftypmp42-v2-different-payload".getBytes(StandardCharsets.UTF_8);
            UploadMediaAssetVersionConditionalResult v2Res = uploadMediaAssetVersionConditionalUseCase.execute(
                    new UploadMediaAssetVersionCommand(
                            onVideo.assetId(), new ByteArrayInputStream(mp4V2), mp4V2.length, "video/mp4", "v2.mp4"
                    )
            );
            assertThat(v2Res.outcome()).isEqualTo(MediaVersionUploadOutcome.VERSION_CREATED);
            assertThat(v2Res.versionNumber()).isEqualTo(2);
            assertThat(packagingJobAdapter.findById(v1Jobs.get(0).getId()).orElseThrow().getStatus())
                    .isEqualTo(MediaVideoPackagingJobStatus.CANCELLED);
            MediaVideoPackagingJob v2Job = findActiveJobInTx(v2Res.versionId()).orElseThrow();
            assertThat(v2Job.getSourceVersionNumber()).isEqualTo(2);

            // 5. Commit V3 & replay delayed V2 enqueue -> V2 skips cleanly; V3 has active job
            byte[] mp4V3 = "ftypmp42-v3-newest-payload".getBytes(StandardCharsets.UTF_8);
            UploadMediaAssetVersionResult v3Res = uploadMediaAssetVersionUseCase.execute(
                    new UploadMediaAssetVersionCommand(
                            onVideo.assetId(), new ByteArrayInputStream(mp4V3), mp4V3.length, "video/mp4", "v3.mp4"
                    )
            );
            assertThat(v3Res.versionNumber()).isEqualTo(3);
            AutoPackagingEnqueueResult delayedV2 = optInEnqueueCoordinator.enqueueVideoVersionIfEnabledSafely(
                    onVideo.assetId(), 2, v2Res.versionId()
            );
            assertThat(delayedV2.status()).isEqualTo(AutoPackagingEnqueueStatus.SKIPPED_STALE_VERSION);
            assertThat(findActiveJobInTx(v2Res.versionId())).isEmpty();
            assertThat(findActiveJobInTx(v3Res.versionId())).isPresent();

            // 6. Restore policy checks:
            // 6a) onRestore=false restores archived asset with zero new jobs
            archiveAssetUseCase.execute(new ArchiveMediaAssetCommand(offResult.assetId()));
            mediaFacade.restore(offResult.assetId());
            assertThat(assetAdapter.findById(offResult.assetId()).orElseThrow().getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
            assertThat(packagingJobAdapter.existsByAssetId(offResult.assetId())).isFalse();

            // 6b) enabled=true + onRestore=true enqueues virgin archived asset once, NEVER retries previously CANCELLED attempt
            autoEnqueueOnRestoreFlag.set(true);
            archiveAssetUseCase.execute(new ArchiveMediaAssetCommand(offResult.assetId()));
            mediaFacade.restore(offResult.assetId());
            assertThat(readJobs(offResult.assetId()))
                    .hasSize(1)
                    .allSatisfy(j -> assertThat(j.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.PENDING));

            // Archive cancels that PENDING job; subsequent restore with onRestore=true MUST NOT auto-requeue CANCELLED
            archiveAssetUseCase.execute(new ArchiveMediaAssetCommand(offResult.assetId()));
            mediaFacade.restore(offResult.assetId());
            assertThat(readJobs(offResult.assetId()))
                    .hasSize(1)
                    .allSatisfy(j -> assertThat(j.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.CANCELLED));

            // 6c) enabled=true + onRestore=true skips existing READY package on restore
            deleteAssetUseCase.execute(new DeleteMediaAssetCommand(onVideo.assetId()));
            UUID readyRestoreId = UUID.randomUUID();
            UUID readyV1Id = UUID.randomUUID();
            seedActiveVideoAsset(readyRestoreId, readyV1Id);
            MediaVideoPackagingJob rJob = createJobUseCase.execute(readyRestoreId);
            ClaimedVideoPackagingLease rLease = claimJobUseCase.claimNext("w-rest", Duration.ofMinutes(5), R2_PROVIDER, "movie-bucket").orElseThrow();
            assertThat(rLease.jobId()).isEqualTo(rJob.getId());
            completeJobUseCase.completeWithVerifiedReceipt(
                    readyRestoreId, rJob.getId(), rLease.packageId(), "w-rest", rLease.leaseGeneration(),
                    new VerifiedHlsPackageReceipt(MANIFEST_SHA256, 40960L, 5, "[{\"height\":720}]", clockRef.get().plusSeconds(1))
            );
            archiveAssetUseCase.execute(new ArchiveMediaAssetCommand(readyRestoreId));
            mediaFacade.restore(readyRestoreId);
            assertThat(readJobs(readyRestoreId)).hasSize(1);
            assertThat(hlsPackageAdapter.findCurrentReadyByAssetId(readyRestoreId)).isPresent();
        } finally {
            autoEnqueueEnabledFlag.set(false);
            autoEnqueueOnRestoreFlag.set(false);
        }
    }

    private List<MediaVideoPackagingJob> readJobs(UUID assetId) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(s -> packagingJobAdapter.findByAssetIdForUpdateOrderByIdAsc(assetId));
    }

    private java.util.Optional<MediaVideoPackagingJob> findActiveJobInTx(UUID sourceVersionId) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(s -> packagingJobAdapter.findActiveBySourceVersionIdForUpdate(sourceVersionId));
    }
}
