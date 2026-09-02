package com.universe.media;

import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.RegisterMediaAssetUseCase;
import com.universe.media.application.asset.RegisterMediaAssetVersionUseCase;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionUseCase;
import com.universe.media.application.facade.MediaFacade;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.domain.StorageKey;
import com.universe.media.infrastructure.persistence.MediaAssetPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaAssetVersionPersistenceAdapter;
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
import java.io.File;
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
        RegisterMediaAssetUseCase.class,
        RegisterMediaAssetVersionUseCase.class,
        GetMediaAssetDetailUseCase.class,
        ChangeMediaVisibilityUseCase.class,
        ArchiveMediaAssetUseCase.class,
        RestoreMediaAssetUseCase.class,
        DeleteMediaAssetUseCase.class,
        UploadMediaAssetUseCase.class,
        UploadMediaAssetVersionUseCase.class,
        LocalFilesystemStorageAdapter.class,
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
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }
    }

    @Autowired
    private MediaContract mediaContract;

    @Autowired
    private BinaryStoragePort binaryStoragePort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdAssetIds = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUpDatabase() {
        for (UUID assetId : createdAssetIds) {
            jdbcTemplate.update("DELETE FROM media_asset_versions WHERE asset_id = ?", assetId.toString());
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", assetId.toString());
        }
        createdAssetIds.clear();
    }

    @AfterAll
    static void cleanUpTempStorage() throws IOException {
        if (tempStorageDir != null && Files.exists(tempStorageDir)) {
            try (var stream = Files.walk(tempStorageDir)) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    @Test
    @DisplayName("End-to-end media upload and version registration through public MediaContract on local MySQL")
    void shouldUploadAssetAndNewVersionSuccessfully() throws Exception {
        // --- 1. Upload Initial Binary Asset ---
        byte[] initialBytes = "Initial v1 binary content for integration test".getBytes(StandardCharsets.UTF_8);
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

        // --- 4 & 5. Verify provider is 'local' and SHA-256 matches in MySQL ---
        Map<String, Object> v1Row = jdbcTemplate.queryForMap(
                "SELECT storage_provider_id, storage_key, content_hash, size_bytes, mime_type FROM media_asset_versions WHERE asset_id = ? AND version_number = 1",
                assetId.toString()
        );
        assertThat(v1Row.get("storage_provider_id")).isEqualTo("local");
        assertThat(v1Row.get("content_hash")).isEqualTo(initialSha256);
        assertThat(((Number) v1Row.get("size_bytes")).longValue()).isEqualTo(initialBytes.length);
        assertThat(v1Row.get("mime_type")).isEqualTo("image/webp");

        String v1StorageKey = (String) v1Row.get("storage_key");
        assertThat(v1StorageKey).startsWith("objects/");

        // --- 6. Open stored binary through BinaryStoragePort and verify exact bytes ---
        try (InputStream storedStream = binaryStoragePort.open(StorageKey.of(v1StorageKey))) {
            byte[] readBytes = storedStream.readAllBytes();
            assertThat(readBytes).isEqualTo(initialBytes);
        }

        // --- 7. Upload a second version and verify version number advances ---
        byte[] v2Bytes = "Updated v2 binary content payload".getBytes(StandardCharsets.UTF_8);
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
        assertThat(v2Row.get("storage_provider_id")).isEqualTo("local");
        assertThat(v2Row.get("content_hash")).isEqualTo(v2Sha256);
        assertThat(((Number) v2Row.get("size_bytes")).longValue()).isEqualTo(v2Bytes.length);

        String v2StorageKey = (String) v2Row.get("storage_key");
        assertThat(v2StorageKey).startsWith("objects/");
        assertThat(v2StorageKey).isNotEqualTo(v1StorageKey);

        try (InputStream storedV2Stream = binaryStoragePort.open(StorageKey.of(v2StorageKey))) {
            byte[] readV2Bytes = storedV2Stream.readAllBytes();
            assertThat(readV2Bytes).isEqualTo(v2Bytes);
        }
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
