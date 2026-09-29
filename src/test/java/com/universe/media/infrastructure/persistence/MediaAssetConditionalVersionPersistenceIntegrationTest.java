package com.universe.media.infrastructure.persistence;

import com.universe.media.application.asset.RasterContentSignatureValidator;
import com.universe.media.application.asset.RegisterMediaAssetVersionUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionCommand;
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalResult;
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalUseCase;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.contracts.dto.MediaVersionUploadOutcome;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        RegisterMediaAssetVersionUseCase.class,
        RasterContentSignatureValidator.class,
        UploadMediaAssetVersionConditionalUseCase.class,
        MediaAssetConditionalVersionPersistenceIntegrationTest.TestConfig.class
})
class MediaAssetConditionalVersionPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static final StorageProviderId LOCAL_PROVIDER = StorageProviderId.of("local");

    private static byte[] createPngBytes(String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] payload = new byte[header.length + textBytes.length];
        System.arraycopy(header, 0, payload, 0, header.length);
        System.arraycopy(textBytes, 0, payload, header.length, textBytes.length);
        return payload;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final byte[] PNG_V1 = createPngBytes("binary-v1");
    private static final byte[] PNG_V2 = createPngBytes("binary-v2-different");

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }

        @Bean
        public TrackingBinaryStoragePort trackingBinaryStoragePort() {
            return new TrackingBinaryStoragePort();
        }

        @Bean
        public MediaStorageRoutingService mediaStorageRoutingService() {
            return new MediaStorageRoutingService();
        }

        @Bean
        public StorageProviderResolverPort storageProviderResolverPort(TrackingBinaryStoragePort storagePort) {
            return providerId -> storagePort;
        }
    }

    static class TrackingBinaryStoragePort implements BinaryStoragePort {
        final AtomicInteger storeCallCount = new AtomicInteger(0);
        final AtomicInteger deleteCallCount = new AtomicInteger(0);

        @Override
        public StorageProviderId providerId() {
            return LOCAL_PROVIDER;
        }

        @Override
        public StoredBinaryObject store(StorageKey storageKey, InputStream content, long sizeBytes, MimeType mimeType) {
            storeCallCount.incrementAndGet();
            return StoredBinaryObject.of(com.universe.media.domain.StorageLocation.of(LOCAL_PROVIDER, storageKey));
        }

        @Override
        public InputStream open(StorageKey storageKey) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream openRange(StorageKey storageKey, long startInclusive, long length) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void delete(StorageKey storageKey) {
            deleteCallCount.incrementAndGet();
        }

        public void reset() {
            storeCallCount.set(0);
            deleteCallCount.set(0);
        }
    }

    @Autowired
    private MediaAssetPersistenceAdapter assetAdapter;

    @Autowired
    private MediaAssetVersionPersistenceAdapter versionAdapter;

    @Autowired
    private UploadMediaAssetVersionConditionalUseCase conditionalUseCase;

    @Autowired
    private TrackingBinaryStoragePort storagePort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdAssetIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        storagePort.reset();
    }

    @AfterEach
    void cleanUp() {
        for (UUID assetId : createdAssetIds) {
            jdbcTemplate.update("DELETE FROM media_image_variants WHERE version_id IN (SELECT id FROM media_asset_versions WHERE asset_id = ?)", assetId.toString());
            jdbcTemplate.update("DELETE FROM media_asset_versions WHERE asset_id = ?", assetId.toString());
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", assetId.toString());
        }
        createdAssetIds.clear();
    }

    private UUID seedAssetWithVersion1(byte[] binaryBytes, String filename) {
        Instant now = Instant.now();
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.IMAGE, MediaVisibility.PUBLIC, "wiki.article.cover", now);
        assetAdapter.save(asset);

        String hash = sha256Hex(binaryBytes);
        UUID versionId = UUID.randomUUID();
        MediaAssetVersion version = MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                StorageLocation.of(LOCAL_PROVIDER, StorageKey.of("objects/" + UUID.randomUUID())),
                "https://cdn.universe.com/covers/" + filename,
                ContentHash.of(hash),
                MimeType.of("image/png"),
                binaryBytes.length,
                filename,
                now
        );
        versionAdapter.save(version);
        return assetId;
    }

    @Test
    @DisplayName("Mandatory Test A: Same current binary returns UNCHANGED with 0 storage writes and 0 DB increments")
    void shouldReturnUnchangedAndNeverCallStorageWhenBinaryIsIdentical() {
        UUID assetId = seedAssetWithVersion1(PNG_V1, "cover.png");

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                assetId,
                new ByteArrayInputStream(PNG_V1),
                PNG_V1.length,
                "image/png",
                "cover.png"
        );

        UploadMediaAssetVersionConditionalResult result = conditionalUseCase.execute(command);

        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.UNCHANGED);
        assertThat(result.assetId()).isEqualTo(assetId);
        assertThat(result.versionNumber()).isEqualTo(1);
        assertThat(result.versionId()).isNull();

        // Database asserts: 1 version row, current_version_number remains 1
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_asset_versions WHERE asset_id = ?",
                Integer.class,
                assetId.toString()
        );
        assertThat(versionCount).isEqualTo(1);

        Integer currentVersionNumber = jdbcTemplate.queryForObject(
                "SELECT current_version_number FROM media_assets WHERE id = ?",
                Integer.class,
                assetId.toString()
        );
        assertThat(currentVersionNumber).isEqualTo(1);

        // Storage assert: NEVER called
        assertThat(storagePort.storeCallCount.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("Mandatory Test B: Different binary returns VERSION_CREATED with exactly one N+1 version and storage write")
    void shouldReturnVersionCreatedAndStoreBinaryWhenBinaryDiffers() {
        UUID assetId = seedAssetWithVersion1(PNG_V1, "cover.png");

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                assetId,
                new ByteArrayInputStream(PNG_V2),
                PNG_V2.length,
                "image/png",
                "cover-v2.png"
        );

        UploadMediaAssetVersionConditionalResult result = conditionalUseCase.execute(command);

        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.VERSION_CREATED);
        assertThat(result.assetId()).isEqualTo(assetId);
        assertThat(result.versionNumber()).isEqualTo(2);
        assertThat(result.versionId()).isNotNull();

        // Database asserts: 2 version rows, current_version_number = 2
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_asset_versions WHERE asset_id = ?",
                Integer.class,
                assetId.toString()
        );
        assertThat(versionCount).isEqualTo(2);

        Integer currentVersionNumber = jdbcTemplate.queryForObject(
                "SELECT current_version_number FROM media_assets WHERE id = ?",
                Integer.class,
                assetId.toString()
        );
        assertThat(currentVersionNumber).isEqualTo(2);

        // Verify version 2 hash matches V2
        String v2Hash = jdbcTemplate.queryForObject(
                "SELECT content_hash FROM media_asset_versions WHERE asset_id = ? AND version_number = 2",
                String.class,
                assetId.toString()
        );
        assertThat(v2Hash).isEqualTo(sha256Hex(PNG_V2));

        // Storage assert: store called exactly once
        assertThat(storagePort.storeCallCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Mandatory Test C: Same bytes / different filename returns UNCHANGED and 0 storage writes")
    void shouldReturnUnchangedWhenSameBytesUploadedWithDifferentFilename() {
        UUID assetId = seedAssetWithVersion1(PNG_V1, "original.png");

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                assetId,
                new ByteArrayInputStream(PNG_V1),
                PNG_V1.length,
                "image/png",
                "renamed.png"
        );

        UploadMediaAssetVersionConditionalResult result = conditionalUseCase.execute(command);

        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.UNCHANGED);
        assertThat(result.versionNumber()).isEqualTo(1);
        assertThat(storagePort.storeCallCount.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("Mandatory Test D: Same filename / different bytes returns VERSION_CREATED and writes storage")
    void shouldReturnVersionCreatedWhenSameFilenameHasDifferentBytes() {
        UUID assetId = seedAssetWithVersion1(PNG_V1, "same-filename.png");

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                assetId,
                new ByteArrayInputStream(PNG_V2),
                PNG_V2.length,
                "image/png",
                "same-filename.png"
        );

        UploadMediaAssetVersionConditionalResult result = conditionalUseCase.execute(command);

        assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.VERSION_CREATED);
        assertThat(result.versionNumber()).isEqualTo(2);
        assertThat(storagePort.storeCallCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Mandatory Test E: Missing asset throws MediaAssetNotFoundException with 0 storage writes")
    void shouldThrowNotFoundWhenAssetDoesNotExist() {
        UUID nonExistentAssetId = UUID.randomUUID();

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                nonExistentAssetId,
                new ByteArrayInputStream(PNG_V1),
                PNG_V1.length,
                "image/png",
                "cover.png"
        );

        assertThatThrownBy(() -> conditionalUseCase.execute(command))
                .isInstanceOf(MediaAssetNotFoundException.class);

        assertThat(storagePort.storeCallCount.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("Mandatory Test F: Inactive/archived status throws IllegalStateException with 0 storage writes")
    void shouldThrowIllegalStateExceptionWhenAssetIsArchived() {
        UUID assetId = seedAssetWithVersion1(PNG_V1, "cover.png");
        MediaAsset asset = assetAdapter.findById(assetId).orElseThrow();
        asset.archive(Instant.now());
        assetAdapter.save(asset);

        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                assetId,
                new ByteArrayInputStream(PNG_V2),
                PNG_V2.length,
                "image/png",
                "cover.png"
        );

        assertThatThrownBy(() -> conditionalUseCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCHIVED");

        assertThat(storagePort.storeCallCount.get()).isEqualTo(0);
    }
}
