package com.universe.media;

import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.AssignMediaAssetClientTagUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.FindActiveMediaAssetsByClientTagKeysetUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.GetMediaAssetCurrentMetadataUseCase;
import com.universe.media.application.asset.GetCurrentMediaAssetVersionSnapshotUseCase;
import com.universe.media.application.asset.OpenMediaAssetVersionContentUseCase;
import com.universe.media.application.asset.RasterContentSignatureValidator;
import com.universe.media.application.asset.RegisterMediaAssetUseCase;
import com.universe.media.application.asset.RegisterMediaAssetVersionUseCase;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalUseCase;
import com.universe.media.application.facade.MediaFacade;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.infrastructure.persistence.MediaAssetPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaAssetCurrentMetadataQueryPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaAssetVersionPersistenceAdapter;
import com.universe.media.infrastructure.storage.DefaultStorageProviderRegistry;
import com.universe.media.infrastructure.storage.local.LocalFilesystemStorageAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.universe.media.application.asset.VideoUploadValidator;
import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.universe.media.application.variant.GenerateMediaImageVariantUseCase;
import com.universe.media.infrastructure.image.JavaImageProcessorAdapter;
import com.universe.media.infrastructure.persistence.MediaImageVariantPersistenceAdapter;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        MediaAssetPersistenceAdapter.class,
        MediaAssetCurrentMetadataQueryPersistenceAdapter.class,
        MediaAssetVersionPersistenceAdapter.class,
        MediaImageVariantPersistenceAdapter.class,
        JavaImageProcessorAdapter.class,
        RegisterMediaAssetUseCase.class,
        RegisterMediaAssetVersionUseCase.class,
        GetMediaAssetDetailUseCase.class,
        GetMediaAssetCurrentMetadataUseCase.class,
        GetCurrentMediaAssetVersionSnapshotUseCase.class,
        OpenMediaAssetVersionContentUseCase.class,
        ChangeMediaVisibilityUseCase.class,
        ArchiveMediaAssetUseCase.class,
        RestoreMediaAssetUseCase.class,
        DeleteMediaAssetUseCase.class,
        AssignMediaAssetClientTagUseCase.class,
        FindActiveMediaAssetsByClientTagKeysetUseCase.class,
        UploadMediaAssetUseCase.class,
        UploadMediaAssetVersionUseCase.class,
        UploadMediaAssetVersionConditionalUseCase.class,
        RasterContentSignatureValidator.class,
        VideoUploadValidator.class,
        GenerateMediaImageVariantUseCase.class,
        LocalFilesystemStorageAdapter.class,
        MediaStorageRoutingService.class,
        DefaultStorageProviderRegistry.class,
        MediaFacade.class,
        MediaUploadIntegrationTest.TestConfig.class
})
class MediaUploadIntegrationTest {

    private static Path tempStorageDir;

    @DynamicPropertySource
    static void configureDynamicProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
        try {
            tempStorageDir = Files.createTempDirectory("media-upload-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        registry.add("media.storage.local.root-dir", () -> tempStorageDir.toAbsolutePath().toString());
    }

    @TestConfiguration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }

        @Bean
        public BinaryStoragePort cloudinaryStoragePort() {
            return new TestCloudinaryStoragePort(tempStorageDir);
        }

        @Bean
        public TestB2StoragePort b2StoragePort() {
            return new TestB2StoragePort(tempStorageDir);
        }

        @Bean
        public TestCommitFailureAspect testCommitFailureAspect() {
            return new TestCommitFailureAspect();
        }
    }

    static class TestCloudinaryStoragePort implements BinaryStoragePort {
        private final LocalFilesystemStorageAdapter delegate;

        public TestCloudinaryStoragePort(Path rootDir) {
            this.delegate = new LocalFilesystemStorageAdapter(rootDir);
        }

        @Override
        public StorageProviderId providerId() {
            return StorageProviderId.of("cloudinary");
        }

        @Override
        public StoredBinaryObject store(StorageKey storageKey, InputStream content, long sizeBytes, MimeType mimeType) {
            delegate.store(storageKey, content, sizeBytes, mimeType);
            return StoredBinaryObject.of(StorageLocation.of(providerId(), storageKey));
        }

        @Override
        public InputStream open(StorageKey storageKey) {
            return delegate.open(storageKey);
        }

        @Override
        public InputStream openRange(StorageKey storageKey, long startInclusive, long length) {
            return delegate.openRange(storageKey, startInclusive, length);
        }

        @Override
        public void delete(StorageKey storageKey) {
            delegate.delete(storageKey);
        }
    }

    static class TestB2StoragePort implements BinaryStoragePort {
        private final LocalFilesystemStorageAdapter delegate;
        private final List<StorageKey> storedKeys = new CopyOnWriteArrayList<>();
        private final List<StorageKey> deletedKeys = new CopyOnWriteArrayList<>();
        private volatile boolean simulateDeleteFailure = false;

        public TestB2StoragePort(Path rootDir) {
            this.delegate = new LocalFilesystemStorageAdapter(rootDir);
        }

        @Override
        public StorageProviderId providerId() {
            return StorageProviderId.of("b2");
        }

        @Override
        public StoredBinaryObject store(StorageKey storageKey, InputStream content, long sizeBytes, MimeType mimeType) {
            delegate.store(storageKey, content, sizeBytes, mimeType);
            storedKeys.add(storageKey);
            return StoredBinaryObject.of(StorageLocation.of(providerId(), storageKey));
        }

        @Override
        public InputStream open(StorageKey storageKey) {
            return delegate.open(storageKey);
        }

        @Override
        public InputStream openRange(StorageKey storageKey, long startInclusive, long length) {
            return delegate.openRange(storageKey, startInclusive, length);
        }

        @Override
        public void delete(StorageKey storageKey) {
            deletedKeys.add(storageKey);
            if (simulateDeleteFailure) {
                throw new RuntimeException("Simulated B2 delete network timeout");
            }
            delegate.delete(storageKey);
        }

        public List<StorageKey> getStoredKeys() {
            return storedKeys;
        }

        public List<StorageKey> getDeletedKeys() {
            return deletedKeys;
        }

        public void setSimulateDeleteFailure(boolean simulateDeleteFailure) {
            this.simulateDeleteFailure = simulateDeleteFailure;
        }

        public void reset() {
            storedKeys.clear();
            deletedKeys.clear();
            simulateDeleteFailure = false;
        }
    }

    enum CommitFailureTarget {
        NONE,
        INITIAL_ASSET,
        NORMAL_VERSION,
        CONDITIONAL_VERSION
    }

    @Aspect
    static class TestCommitFailureAspect {
        private volatile CommitFailureTarget target = CommitFailureTarget.NONE;

        public void setTarget(CommitFailureTarget target) {
            this.target = target;
        }

        public void reset() {
            this.target = CommitFailureTarget.NONE;
        }

        @org.aspectj.lang.annotation.Before("execution(* com.universe.media.application.asset.RegisterMediaAssetUseCase.execute(..))")
        public void onRegisterInitialAsset() {
            if (target == CommitFailureTarget.INITIAL_ASSET) {
                registerCommitSynchronization("Simulated database transaction commit failure");
            }
        }

        @org.aspectj.lang.annotation.Before("execution(* com.universe.media.application.asset.RegisterMediaAssetVersionUseCase.execute(..))")
        public void onRegisterNormalVersion() {
            if (target == CommitFailureTarget.NORMAL_VERSION) {
                registerCommitSynchronization("Simulated normal version transaction commit failure");
            }
        }

        @org.aspectj.lang.annotation.Before("execution(* com.universe.media.application.asset.RegisterMediaAssetVersionUseCase.registerConditionalVersion(..))")
        public void onRegisterConditionalVersion() {
            if (target == CommitFailureTarget.CONDITIONAL_VERSION) {
                registerCommitSynchronization("Simulated conditional version transaction commit failure");
            }
        }

        private void registerCommitSynchronization(String errorMessage) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            throw new RuntimeException(errorMessage);
                        }
                    }
            );
        }
    }

    @Autowired
    private MediaContract mediaContract;

    @Autowired
    private StorageProviderResolverPort storageProviderResolverPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestB2StoragePort testB2StoragePort;

    @Autowired
    private TestCommitFailureAspect testCommitFailureAspect;

    private final List<UUID> createdAssetIds = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUpDatabase() {
        testB2StoragePort.reset();
        testCommitFailureAspect.reset();
        for (UUID assetId : createdAssetIds) {
            jdbcTemplate.update("DELETE FROM media_image_variants WHERE version_id IN (SELECT id FROM media_asset_versions WHERE asset_id = ?)", assetId.toString());
            jdbcTemplate.update("DELETE FROM media_asset_versions WHERE asset_id = ?", assetId.toString());
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", assetId.toString());
        }
        createdAssetIds.clear();
    }

    @AfterAll
    static void cleanUpTempStorage() throws IOException {
        if (tempStorageDir != null && Files.exists(tempStorageDir)) {
            List<Path> paths;
            try (var stream = Files.walk(tempStorageDir)) {
                paths = stream.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    @DisplayName("End-to-end media upload and version registration through public MediaContract on local MySQL")
    void shouldUploadAssetAndNewVersionSuccessfully() throws Exception {
        // --- 1. Upload Initial Binary Asset ---
        byte[] initialBytes = createWebpSamplePayload("Initial v1 binary content for integration test");
        String initialSha256 = computeSha256(initialBytes);

        UploadMediaAssetRequestDTO uploadRequest = new UploadMediaAssetRequestDTO(
                new ByteArrayInputStream(initialBytes),
                initialBytes.length,
                "image/webp",
                MediaTypeDTO.IMAGE,
                MediaVisibilityDTO.PUBLIC,
                "banner.webp"
        );

        UploadMediaAssetResponseDTO uploadResponse = mediaContract.uploadAsset(uploadRequest);

        // --- 2. Verify returned assetId ---
        UUID assetId = uploadResponse.assetId();
        assertThat(assetId).isNotNull();
        createdAssetIds.add(assetId);

        // --- 3. Verify persisted asset and current-version metadata via MediaContract ---
        Optional<MediaAssetDetailDTO> optDetail = mediaContract.getAssetDetail(assetId);
        assertThat(optDetail).isPresent();
        MediaAssetDetailDTO detail = optDetail.get();
        assertThat(detail.id()).isEqualTo(assetId);
        assertThat(detail.mediaType()).isEqualTo(MediaTypeDTO.IMAGE);
        assertThat(detail.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(detail.status()).isEqualTo(MediaAssetStatusDTO.ACTIVE);
        assertThat(detail.currentVersionNumber()).isEqualTo(1);
        assertThat(detail.currentVersion()).isNotNull();
        assertThat(detail.currentVersion().versionNumber()).isEqualTo(1);
        assertThat(detail.currentVersion().mimeType()).isEqualTo("image/webp");
        assertThat(detail.currentVersion().sizeBytes()).isEqualTo(initialBytes.length);
        assertThat(detail.currentVersion().originalFilename()).isEqualTo("banner.webp");

        // --- 4 & 5. Verify provider is 'cloudinary' and SHA-256 matches in MySQL ---
        Map<String, Object> v1Row = jdbcTemplate.queryForMap(
                "SELECT storage_provider_id, storage_key, content_hash, size_bytes, mime_type FROM media_asset_versions WHERE asset_id = ? AND version_number = 1",
                assetId.toString()
        );
        assertThat(v1Row.get("storage_provider_id")).isEqualTo("cloudinary");
        assertThat(v1Row.get("content_hash")).isEqualTo(initialSha256);
        assertThat(((Number) v1Row.get("size_bytes")).longValue()).isEqualTo(initialBytes.length);
        assertThat(v1Row.get("mime_type")).isEqualTo("image/webp");

        String v1StorageKey = (String) v1Row.get("storage_key");
        assertThat(v1StorageKey).startsWith("kiemlai/images/");

        // --- 6. Open stored binary through StorageProviderResolverPort and verify exact bytes ---
        try (InputStream storedStream = storageProviderResolverPort.resolve(StorageProviderId.of("cloudinary")).open(StorageKey.of(v1StorageKey))) {
            byte[] readBytes = storedStream.readAllBytes();
            assertThat(readBytes).isEqualTo(initialBytes);
        }

        // --- 7. Upload a second version and verify version number advances ---
        byte[] v2Bytes = createWebpSamplePayload("Updated v2 binary content payload");
        String v2Sha256 = computeSha256(v2Bytes);

        UploadMediaAssetVersionRequestDTO v2Request = new UploadMediaAssetVersionRequestDTO(
                assetId,
                new ByteArrayInputStream(v2Bytes),
                v2Bytes.length,
                "image/webp",
                "banner_v2.webp"
        );

        UploadMediaAssetVersionResponseDTO v2Response = mediaContract.uploadVersion(v2Request);
        assertThat(v2Response.assetId()).isEqualTo(assetId);
        assertThat(v2Response.versionNumber()).isEqualTo(2);

        // --- 8. Verify second binary and metadata are persisted correctly ---
        Optional<MediaAssetDetailDTO> optV2Detail = mediaContract.getAssetDetail(assetId);
        assertThat(optV2Detail).isPresent();
        MediaAssetDetailDTO v2Detail = optV2Detail.get();
        assertThat(v2Detail.currentVersionNumber()).isEqualTo(2);
        assertThat(v2Detail.currentVersion().versionNumber()).isEqualTo(2);
        assertThat(v2Detail.currentVersion().sizeBytes()).isEqualTo(v2Bytes.length);
        assertThat(v2Detail.currentVersion().originalFilename()).isEqualTo("banner_v2.webp");

        Map<String, Object> v2Row = jdbcTemplate.queryForMap(
                "SELECT storage_provider_id, storage_key, content_hash, size_bytes, mime_type FROM media_asset_versions WHERE asset_id = ? AND version_number = 2",
                assetId.toString()
        );
        assertThat(v2Row.get("storage_provider_id")).isEqualTo("cloudinary");
        assertThat(v2Row.get("content_hash")).isEqualTo(v2Sha256);
        assertThat(((Number) v2Row.get("size_bytes")).longValue()).isEqualTo(v2Bytes.length);

        String v2StorageKey = (String) v2Row.get("storage_key");
        assertThat(v2StorageKey).startsWith("kiemlai/images/");
        assertThat(v2StorageKey).isNotEqualTo(v1StorageKey);

        try (InputStream storedV2Stream = storageProviderResolverPort.resolve(StorageProviderId.of("cloudinary")).open(StorageKey.of(v2StorageKey))) {
            byte[] readV2Bytes = storedV2Stream.readAllBytes();
            assertThat(readV2Bytes).isEqualTo(v2Bytes);
        }
    }

    @Test
    @DisplayName("End-to-end VIDEO media upload and version registration through MediaContract routed to B2 on local MySQL")
    void shouldUploadVideoAssetAndNewVersionSuccessfully() throws Exception {
        // --- 1. Upload Initial VIDEO Asset ---
        byte[] initialBytes = createMp4SamplePayload("Initial v1 video binary content for B2 integration test");
        String initialSha256 = computeSha256(initialBytes);

        UploadMediaAssetRequestDTO uploadRequest = new UploadMediaAssetRequestDTO(
                new ByteArrayInputStream(initialBytes),
                initialBytes.length,
                "video/mp4",
                MediaTypeDTO.VIDEO,
                MediaVisibilityDTO.PUBLIC,
                "intro.mp4"
        );

        UploadMediaAssetResponseDTO uploadResponse = mediaContract.uploadAsset(uploadRequest);

        // --- 2. Verify returned assetId ---
        UUID assetId = uploadResponse.assetId();
        assertThat(assetId).isNotNull();
        createdAssetIds.add(assetId);

        // --- 3. Verify persisted asset and current-version metadata via MediaContract ---
        Optional<MediaAssetDetailDTO> optDetail = mediaContract.getAssetDetail(assetId);
        assertThat(optDetail).isPresent();
        MediaAssetDetailDTO detail = optDetail.get();
        assertThat(detail.id()).isEqualTo(assetId);
        assertThat(detail.mediaType()).isEqualTo(MediaTypeDTO.VIDEO);
        assertThat(detail.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(detail.status()).isEqualTo(MediaAssetStatusDTO.ACTIVE);
        assertThat(detail.currentVersionNumber()).isEqualTo(1);
        assertThat(detail.currentVersion()).isNotNull();
        assertThat(detail.currentVersion().versionNumber()).isEqualTo(1);
        assertThat(detail.currentVersion().mimeType()).isEqualTo("video/mp4");
        assertThat(detail.currentVersion().sizeBytes()).isEqualTo(initialBytes.length);
        assertThat(detail.currentVersion().originalFilename()).isEqualTo("intro.mp4");

        // --- 4 & 5. Verify provider is 'b2' and storage_key uses media-owned objects/ prefix ---
        Map<String, Object> v1Row = jdbcTemplate.queryForMap(
                "SELECT storage_provider_id, storage_key, content_hash, size_bytes, mime_type FROM media_asset_versions WHERE asset_id = ? AND version_number = 1",
                assetId.toString()
        );
        assertThat(v1Row.get("storage_provider_id")).isEqualTo("b2");
        assertThat(v1Row.get("content_hash")).isEqualTo(initialSha256);
        assertThat(((Number) v1Row.get("size_bytes")).longValue()).isEqualTo(initialBytes.length);
        assertThat(v1Row.get("mime_type")).isEqualTo("video/mp4");

        String v1StorageKey = (String) v1Row.get("storage_key");
        assertThat(v1StorageKey).startsWith("objects/");

        // --- 6. Open stored binary through StorageProviderResolverPort and verify exact bytes ---
        try (InputStream storedStream = storageProviderResolverPort.resolve(StorageProviderId.of("b2")).open(StorageKey.of(v1StorageKey))) {
            byte[] readBytes = storedStream.readAllBytes();
            assertThat(readBytes).isEqualTo(initialBytes);
        }

        // --- 7. Upload a second VIDEO version and verify version number advances ---
        byte[] v2Bytes = createMp4SamplePayload("Updated v2 video binary content for B2 integration test");
        String v2Sha256 = computeSha256(v2Bytes);

        UploadMediaAssetVersionRequestDTO v2Request = new UploadMediaAssetVersionRequestDTO(
                assetId,
                new ByteArrayInputStream(v2Bytes),
                v2Bytes.length,
                "video/mp4",
                "intro_v2.mp4"
        );

        UploadMediaAssetVersionResponseDTO v2Response = mediaContract.uploadVersion(v2Request);
        assertThat(v2Response.assetId()).isEqualTo(assetId);
        assertThat(v2Response.versionNumber()).isEqualTo(2);

        // --- 8. Verify second binary and metadata are persisted correctly ---
        Optional<MediaAssetDetailDTO> optV2Detail = mediaContract.getAssetDetail(assetId);
        assertThat(optV2Detail).isPresent();
        MediaAssetDetailDTO v2Detail = optV2Detail.get();
        assertThat(v2Detail.currentVersionNumber()).isEqualTo(2);
        assertThat(v2Detail.currentVersion().versionNumber()).isEqualTo(2);
        assertThat(v2Detail.currentVersion().sizeBytes()).isEqualTo(v2Bytes.length);
        assertThat(v2Detail.currentVersion().originalFilename()).isEqualTo("intro_v2.mp4");

        Map<String, Object> v2Row = jdbcTemplate.queryForMap(
                "SELECT storage_provider_id, storage_key, content_hash, size_bytes, mime_type FROM media_asset_versions WHERE asset_id = ? AND version_number = 2",
                assetId.toString()
        );
        assertThat(v2Row.get("storage_provider_id")).isEqualTo("b2");
        assertThat(v2Row.get("content_hash")).isEqualTo(v2Sha256);
        assertThat(((Number) v2Row.get("size_bytes")).longValue()).isEqualTo(v2Bytes.length);

        String v2StorageKey = (String) v2Row.get("storage_key");
        assertThat(v2StorageKey).startsWith("objects/");
        assertThat(v2StorageKey).isNotEqualTo(v1StorageKey);

        try (InputStream storedV2Stream = storageProviderResolverPort.resolve(StorageProviderId.of("b2")).open(StorageKey.of(v2StorageKey))) {
            byte[] readV2Bytes = storedV2Stream.readAllBytes();
            assertThat(readV2Bytes).isEqualTo(v2Bytes);
        }
    }

    @Test
    @DisplayName("Should compensate and delete B2 object when MySQL persistence flush fails")
    void shouldCompensateAndCleanupB2StorageWhenImmediatePersistenceFails() {
        byte[] payload = createMp4SamplePayload("Failed persistence payload");
        String excessivelyLongFilename = "toolong_filename_".repeat(20) + ".mp4"; // > 255 chars

        UploadMediaAssetRequestDTO request = new UploadMediaAssetRequestDTO(
                new ByteArrayInputStream(payload),
                payload.length,
                "video/mp4",
                MediaTypeDTO.VIDEO,
                MediaVisibilityDTO.PUBLIC,
                excessivelyLongFilename
        );

        assertThrows(IllegalArgumentException.class, () -> mediaContract.uploadAsset(request));

        assertThat(testB2StoragePort.getStoredKeys()).hasSize(1);
        StorageKey storedKey = testB2StoragePort.getStoredKeys().get(0);
        assertThat(testB2StoragePort.getDeletedKeys()).contains(storedKey);

        Integer assetCount = jdbcTemplate.queryForObject("SELECT count(*) FROM media_assets", Integer.class);
        assertThat(assetCount).isZero();
    }

    @Test
    @DisplayName("Should retain primary persistence exception and suppress cleanup failure when B2 compensation fails")
    void shouldRetainPrimaryPersistenceExceptionAndSuppressCleanupFailureWhenB2CleanupFails() {
        byte[] payload = createMp4SamplePayload("Failed cleanup payload");
        String excessivelyLongFilename = "toolong_filename_".repeat(20) + ".mp4"; // > 255 chars
        testB2StoragePort.setSimulateDeleteFailure(true);

        UploadMediaAssetRequestDTO request = new UploadMediaAssetRequestDTO(
                new ByteArrayInputStream(payload),
                payload.length,
                "video/mp4",
                MediaTypeDTO.VIDEO,
                MediaVisibilityDTO.PUBLIC,
                excessivelyLongFilename
        );

        IllegalArgumentException primaryEx = assertThrows(
                IllegalArgumentException.class,
                () -> mediaContract.uploadAsset(request)
        );

        assertThat(primaryEx.getSuppressed())
                .anyMatch(suppressed -> suppressed.getMessage() != null && suppressed.getMessage().contains("Simulated B2 delete network timeout"));

        Integer assetCount = jdbcTemplate.queryForObject("SELECT count(*) FROM media_assets", Integer.class);
        assertThat(assetCount).isZero();
    }

    @Test
    @DisplayName("Should compensate and delete B2 object when initial VIDEO Spring transaction commit fails")
    void shouldCompensateAndCleanupB2StorageWhenTransactionCommitFails() {
        byte[] payload = createMp4SamplePayload("Commit failure payload");
        testCommitFailureAspect.setTarget(CommitFailureTarget.INITIAL_ASSET);

        UploadMediaAssetRequestDTO request = new UploadMediaAssetRequestDTO(
                new ByteArrayInputStream(payload),
                payload.length,
                "video/mp4",
                MediaTypeDTO.VIDEO,
                MediaVisibilityDTO.PUBLIC,
                "commit_fail.mp4"
        );

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> mediaContract.uploadAsset(request)
        );

        assertThat(thrown).hasMessageFindingMatch("Simulated database transaction commit failure");

        assertThat(testB2StoragePort.getStoredKeys()).hasSize(1);
        StorageKey storedKey = testB2StoragePort.getStoredKeys().get(0);
        assertThat(testB2StoragePort.getDeletedKeys()).contains(storedKey);

        Integer assetCount = jdbcTemplate.queryForObject("SELECT count(*) FROM media_assets", Integer.class);
        assertThat(assetCount).isZero();
    }

    @Test
    @DisplayName("Should compensate and delete B2 object when normal VIDEO version Spring transaction commit fails")
    void shouldCompensateAndCleanupB2StorageWhenNormalVideoVersionTransactionCommitFails() {
        // --- 1. Create a valid initial VIDEO asset first ---
        byte[] v1Bytes = createMp4SamplePayload("Initial valid video payload for version commit test");
        UploadMediaAssetRequestDTO v1Request = new UploadMediaAssetRequestDTO(
                new ByteArrayInputStream(v1Bytes),
                v1Bytes.length,
                "video/mp4",
                MediaTypeDTO.VIDEO,
                MediaVisibilityDTO.PUBLIC,
                "initial.mp4"
        );
        UploadMediaAssetResponseDTO v1Response = mediaContract.uploadAsset(v1Request);
        UUID assetId = v1Response.assetId();
        assertThat(assetId).isNotNull();
        createdAssetIds.add(assetId);

        // --- 2. Clear/reset TestB2StoragePort tracking after initial setup ---
        testB2StoragePort.reset();

        // --- 3. Enable commit-failure hook specifically for normal version registration ---
        testCommitFailureAspect.setTarget(CommitFailureTarget.NORMAL_VERSION);

        // --- 4. Invoke the ACTUAL Spring-managed upload orchestration ---
        byte[] v2Bytes = createMp4SamplePayload("Second version payload intended to fail at commit");
        UploadMediaAssetVersionRequestDTO v2Request = new UploadMediaAssetVersionRequestDTO(
                assetId,
                new ByteArrayInputStream(v2Bytes),
                v2Bytes.length,
                "video/mp4",
                "normal_v2_fail.mp4"
        );

        // --- 9. Prove the exception escapes to the caller ---
        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> mediaContract.uploadVersion(v2Request)
        );
        assertThat(thrown).hasMessageFindingMatch("Simulated normal version transaction commit failure");

        // --- 5. Prove one new object was stored ---
        assertThat(testB2StoragePort.getStoredKeys()).hasSize(1);
        StorageKey v2StoredKey = testB2StoragePort.getStoredKeys().get(0);

        // --- 6. Prove that exact new object was deleted after transaction commit failure ---
        assertThat(testB2StoragePort.getDeletedKeys()).contains(v2StoredKey);

        // --- 7. Prove no new version row remains in MySQL ---
        Integer v2RowCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_asset_versions WHERE asset_id = ? AND version_number = 2",
                Integer.class,
                assetId.toString()
        );
        assertThat(v2RowCount).isZero();

        // --- 8. Prove currentVersionNumber remains unchanged (1) ---
        Optional<MediaAssetDetailDTO> detail = mediaContract.getAssetDetail(assetId);
        assertThat(detail).isPresent();
        assertThat(detail.get().currentVersionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should compensate and delete B2 object when conditional VIDEO version Spring transaction commit fails")
    void shouldCompensateAndCleanupB2StorageWhenConditionalVideoVersionTransactionCommitFails() {
        // --- 1. Create a valid initial VIDEO asset first ---
        byte[] v1Bytes = createMp4SamplePayload("Initial valid video payload for conditional commit test");
        UploadMediaAssetRequestDTO v1Request = new UploadMediaAssetRequestDTO(
                new ByteArrayInputStream(v1Bytes),
                v1Bytes.length,
                "video/mp4",
                MediaTypeDTO.VIDEO,
                MediaVisibilityDTO.PUBLIC,
                "initial_cond.mp4"
        );
        UploadMediaAssetResponseDTO v1Response = mediaContract.uploadAsset(v1Request);
        UUID assetId = v1Response.assetId();
        assertThat(assetId).isNotNull();
        createdAssetIds.add(assetId);

        // --- 2. Clear/reset TestB2StoragePort tracking after initial setup ---
        testB2StoragePort.reset();

        // --- 3. Enable commit-failure hook specifically for conditional version registration ---
        testCommitFailureAspect.setTarget(CommitFailureTarget.CONDITIONAL_VERSION);

        // --- 4. Invoke the ACTUAL Spring-managed upload orchestration with DIFFERENT content ---
        byte[] v2DifferentBytes = createMp4SamplePayload("Different content for conditional version upload");
        UploadMediaAssetVersionRequestDTO v2Request = new UploadMediaAssetVersionRequestDTO(
                assetId,
                new ByteArrayInputStream(v2DifferentBytes),
                v2DifferentBytes.length,
                "video/mp4",
                "cond_v2_fail.mp4"
        );

        // --- 9. Prove the exception escapes to the caller ---
        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> mediaContract.uploadVersionIfContentChanged(v2Request)
        );
        assertThat(thrown).hasMessageFindingMatch("Simulated conditional version transaction commit failure");

        // --- 5. Prove one new object was stored ---
        assertThat(testB2StoragePort.getStoredKeys()).hasSize(1);
        StorageKey v2StoredKey = testB2StoragePort.getStoredKeys().get(0);

        // --- 6. Prove that exact new object was deleted after transaction commit failure ---
        assertThat(testB2StoragePort.getDeletedKeys()).contains(v2StoredKey);

        // --- 7. Prove no new version row remains in MySQL ---
        Integer v2RowCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_asset_versions WHERE asset_id = ? AND version_number = 2",
                Integer.class,
                assetId.toString()
        );
        assertThat(v2RowCount).isZero();

        // --- 8. Prove currentVersionNumber remains unchanged (1) ---
        Optional<MediaAssetDetailDTO> detail = mediaContract.getAssetDetail(assetId);
        assertThat(detail).isPresent();
        assertThat(detail.get().currentVersionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should reject VIDEO upload before storage write when MIME type is not video/mp4")
    void shouldRejectVideoUploadWhenMimeTypeIsNotMp4() {
        byte[] payload = createMp4SamplePayload("Rejected mime payload");

        UploadMediaAssetRequestDTO request = new UploadMediaAssetRequestDTO(
                new ByteArrayInputStream(payload),
                payload.length,
                "video/webm",
                MediaTypeDTO.VIDEO,
                MediaVisibilityDTO.PUBLIC,
                "clip.webm"
        );

        assertThrows(UploadContentMimeMismatchException.class, () -> mediaContract.uploadAsset(request));
        assertThat(testB2StoragePort.getStoredKeys()).isEmpty();
    }

    @Test
    @DisplayName("Should reject VIDEO upload before storage write when size exceeds 4 GiB")
    void shouldRejectVideoUploadWhenSizeExceeds4GiB() {
        long oversized = 4_294_967_297L; // 4 GiB + 1 byte

        UploadMediaAssetRequestDTO request = new UploadMediaAssetRequestDTO(
                new ByteArrayInputStream(new byte[0]),
                oversized,
                "video/mp4",
                MediaTypeDTO.VIDEO,
                MediaVisibilityDTO.PUBLIC,
                "huge.mp4"
        );

        assertThrows(IllegalArgumentException.class, () -> mediaContract.uploadAsset(request));
        assertThat(testB2StoragePort.getStoredKeys()).isEmpty();
    }

    @Test
    @DisplayName("Should reject VIDEO upload before storage write when size is zero")
    void shouldRejectVideoUploadWhenSizeIsZero() {
        UploadMediaAssetRequestDTO request = new UploadMediaAssetRequestDTO(
                new ByteArrayInputStream(new byte[0]),
                0L,
                "video/mp4",
                MediaTypeDTO.VIDEO,
                MediaVisibilityDTO.PUBLIC,
                "empty.mp4"
        );

        assertThrows(IllegalArgumentException.class, () -> mediaContract.uploadAsset(request));
        assertThat(testB2StoragePort.getStoredKeys()).isEmpty();
    }

    private static byte[] createMp4SamplePayload(String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[8 + textBytes.length];
        payload[4] = 'f';
        payload[5] = 't';
        payload[6] = 'y';
        payload[7] = 'p';
        System.arraycopy(textBytes, 0, payload, 8, textBytes.length);
        return payload;
    }

    private static byte[] createWebpSamplePayload(String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[12 + textBytes.length];
        payload[0] = 'R';
        payload[1] = 'I';
        payload[2] = 'F';
        payload[3] = 'F';
        payload[8] = 'W';
        payload[9] = 'E';
        payload[10] = 'B';
        payload[11] = 'P';
        System.arraycopy(textBytes, 0, payload, 12, textBytes.length);
        return payload;
    }

    private String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
