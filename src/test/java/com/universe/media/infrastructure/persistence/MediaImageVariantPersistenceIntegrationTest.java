package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaImageVariant;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageLocation;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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
        MediaImageVariantPersistenceAdapter.class
})
class MediaImageVariantPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MediaAssetPersistenceAdapter assetAdapter;

    @Autowired
    private MediaAssetVersionPersistenceAdapter versionAdapter;

    @Autowired
    private MediaImageVariantPersistenceAdapter variantAdapter;

    @Autowired
    private SpringDataMediaAssetVersionJpaRepository versionRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM media_image_variants");
        jdbcTemplate.update("DELETE FROM media_asset_versions");
        jdbcTemplate.update("DELETE FROM media_assets");
    }

    @Test
    @DisplayName("persists and retrieves MediaImageVariant via adapter with full roundtrip mapping")
    void shouldPersistAndRetrieveMediaImageVariantViaAdapter() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Seed parent asset and version
        MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.IMAGE, MediaVisibility.PUBLIC, now);
        assetAdapter.save(asset);

        MediaAssetVersion version = MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                StorageLocation.of("local", "objects/source-1.jpg"),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/jpeg"),
                100000L,
                "cover.jpg",
                now
        );
        versionAdapter.save(version);

        // 2. Persist derivative variant
        ImageVariantSpec spec = ImageVariantSpec.of(300);
        StorageLocation variantLocation = StorageLocation.of("local", "objects/variants/w300.jpg");
        ContentHash variantHash = ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2");
        MimeType variantMime = MimeType.of("image/jpeg");

        MediaImageVariant variant = MediaImageVariant.create(
                variantId,
                versionId,
                spec,
                variantLocation,
                variantHash,
                variantMime,
                25000L,
                300,
                450,
                now
        );

        MediaImageVariant saved = variantAdapter.save(variant);
        assertThat(saved).isNotNull();

        // 3. Find by versionId and variantKey
        Optional<MediaImageVariant> found = variantAdapter.findByVersionIdAndVariantKey(versionId, "w300");
        assertThat(found).isPresent();
        MediaImageVariant loaded = found.get();
        assertThat(loaded.getId()).isEqualTo(variantId);
        assertThat(loaded.getVersionId()).isEqualTo(versionId);
        assertThat(loaded.getVariantKey()).isEqualTo("w300");
        assertThat(loaded.getTargetWidth()).isEqualTo(300);
        assertThat(loaded.getStorageLocation()).isEqualTo(variantLocation);
        assertThat(loaded.getContentHash()).isEqualTo(variantHash);
        assertThat(loaded.getMimeType()).isEqualTo(variantMime);
        assertThat(loaded.getSizeBytes()).isEqualTo(25000L);
        assertThat(loaded.getWidth()).isEqualTo(300);
        assertThat(loaded.getHeight()).isEqualTo(450);

        // 4. Check existence queries
        assertThat(variantAdapter.existsByVersionIdAndVariantKey(versionId, "w300")).isTrue();
        assertThat(variantAdapter.existsByVersionIdAndVariantKey(versionId, "w800")).isFalse();
        assertThat(variantAdapter.existsByStorageLocation(variantLocation)).isTrue();
        assertThat(variantAdapter.existsByStorageLocation(StorageLocation.of("local", "objects/variants/nonexistent.jpg"))).isFalse();
    }

    @Test
    @DisplayName("persists and retrieves variant with actual width smaller than targetWidth (proportional resize)")
    void shouldPersistAndRetrieveVariantWithActualWidthSmallerThanTargetWidth() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        Instant now = Instant.now();

        assetAdapter.save(MediaAsset.registerInitial(assetId, MediaType.IMAGE, MediaVisibility.PUBLIC, now));
        versionAdapter.save(MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                StorageLocation.of("local", "objects/source-smaller.jpg"),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/jpeg"),
                100000L,
                "cover.jpg",
                now
        ));

        // Create variant with targetWidth 800 and actual width 640
        ImageVariantSpec spec = ImageVariantSpec.of(800);
        StorageLocation variantLocation = StorageLocation.of("local", "objects/variants/w800.jpg");
        ContentHash variantHash = ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2");
        MimeType variantMime = MimeType.of("image/jpeg");

        MediaImageVariant variant = MediaImageVariant.create(
                variantId,
                versionId,
                spec,
                variantLocation,
                variantHash,
                variantMime,
                40000L,
                640,
                960,
                now
        );

        MediaImageVariant saved = variantAdapter.save(variant);
        assertThat(saved).isNotNull();

        Optional<MediaImageVariant> found = variantAdapter.findByVersionIdAndVariantKey(versionId, "w800");
        assertThat(found).isPresent();
        MediaImageVariant loaded = found.get();
        assertThat(loaded.getId()).isEqualTo(variantId);
        assertThat(loaded.getTargetWidth()).isEqualTo(800);
        assertThat(loaded.getWidth()).isEqualTo(640);
        assertThat(loaded.getHeight()).isEqualTo(960);
    }

    @Test
    @DisplayName("enforces UNIQUE(version_id, variant_key) constraint in database")
    void shouldEnforceUniqueVersionAndVariantKeyConstraint() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();

        assetAdapter.save(MediaAsset.registerInitial(assetId, MediaType.IMAGE, MediaVisibility.PUBLIC, now));
        versionAdapter.save(MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                StorageLocation.of("local", "objects/source-2.jpg"),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/jpeg"),
                100000L,
                "cover.jpg",
                now
        ));

        // Save first variant with w300
        variantAdapter.save(MediaImageVariant.create(
                UUID.randomUUID(),
                versionId,
                ImageVariantSpec.of(300),
                StorageLocation.of("local", "objects/variants/w300-1.jpg"),
                ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/jpeg"),
                20000L,
                300,
                450,
                now
        ));

        // Try to save second variant for SAME version with same w300 key
        MediaImageVariant duplicateKeyVariant = MediaImageVariant.create(
                UUID.randomUUID(),
                versionId,
                ImageVariantSpec.of(300),
                StorageLocation.of("local", "objects/variants/w300-2.jpg"),
                ContentHash.of("b1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/jpeg"),
                21000L,
                300,
                450,
                now
        );

        assertThatThrownBy(() -> variantAdapter.save(duplicateKeyVariant))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("enforces UNIQUE(storage_provider_id, storage_key) constraint in database")
    void shouldEnforceUniqueStorageLocationConstraint() {
        UUID assetId = UUID.randomUUID();
        UUID versionId1 = UUID.randomUUID();
        UUID versionId2 = UUID.randomUUID();
        Instant now = Instant.now();

        assetAdapter.save(MediaAsset.registerInitial(assetId, MediaType.IMAGE, MediaVisibility.PUBLIC, now));
        versionAdapter.save(MediaAssetVersion.create(
                versionId1,
                assetId,
                1,
                StorageLocation.of("local", "objects/source-v1.jpg"),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/jpeg"),
                100000L,
                "cover.jpg",
                now
        ));
        versionAdapter.save(MediaAssetVersion.create(
                versionId2,
                assetId,
                2,
                StorageLocation.of("local", "objects/source-v2.jpg"),
                null,
                ContentHash.of("f3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/jpeg"),
                100000L,
                "cover.jpg",
                now
        ));

        // Save variant on version 1 at location "objects/variants/shared.jpg"
        StorageLocation sharedLocation = StorageLocation.of("local", "objects/variants/shared.jpg");
        variantAdapter.save(MediaImageVariant.create(
                UUID.randomUUID(),
                versionId1,
                ImageVariantSpec.of(300),
                sharedLocation,
                ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/jpeg"),
                20000L,
                300,
                450,
                now
        ));

        // Try to save variant on version 2 using the SAME storage location
        MediaImageVariant duplicateLocationVariant = MediaImageVariant.create(
                UUID.randomUUID(),
                versionId2,
                ImageVariantSpec.of(300),
                sharedLocation,
                ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/jpeg"),
                20000L,
                300,
                450,
                now
        );

        assertThatThrownBy(() -> variantAdapter.save(duplicateLocationVariant))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("enforces foreign key ON DELETE RESTRICT from media_asset_versions")
    void shouldEnforceForeignKeyRestrictOnParentVersionDelete() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();

        assetAdapter.save(MediaAsset.registerInitial(assetId, MediaType.IMAGE, MediaVisibility.PUBLIC, now));
        versionAdapter.save(MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                StorageLocation.of("local", "objects/source-3.jpg"),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/jpeg"),
                100000L,
                "cover.jpg",
                now
        ));

        variantAdapter.save(MediaImageVariant.create(
                UUID.randomUUID(),
                versionId,
                ImageVariantSpec.of(300),
                StorageLocation.of("local", "objects/variants/w300-3.jpg"),
                ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/jpeg"),
                20000L,
                300,
                450,
                now
        ));

        // Attempting to delete the parent version directly via repository must fail with FK restriction
        assertThatThrownBy(() -> versionRepository.deleteById(versionId.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
