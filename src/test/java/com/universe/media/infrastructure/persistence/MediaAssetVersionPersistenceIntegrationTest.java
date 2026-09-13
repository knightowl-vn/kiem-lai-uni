package com.universe.media.infrastructure.persistence;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageLocation;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        MediaAssetVersionPersistenceAdapter.class
})
class MediaAssetVersionPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private MediaAssetPersistenceAdapter assetAdapter;

    @Autowired
    private MediaAssetVersionPersistenceAdapter versionAdapter;

    @Autowired
    private SpringDataMediaAssetVersionJpaRepository versionRepository;

    @Autowired
    private SpringDataMediaAssetJpaRepository assetRepository;

    private final List<UUID> createdVersionIds = new ArrayList<>();
    private final List<UUID> createdAssetIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID versionId : createdVersionIds) {
            try {
                if (versionRepository.existsById(versionId.toString())) {
                    versionRepository.deleteById(versionId.toString());
                }
            } catch (Exception ignored) {
                // Best effort FK-safe cleanup
            }
        }
        for (UUID assetId : createdAssetIds) {
            try {
                if (assetRepository.existsById(assetId.toString())) {
                    assetRepository.deleteById(assetId.toString());
                }
            } catch (Exception ignored) {
                // Best effort FK-safe cleanup
            }
        }
    }

    @Test
    @DisplayName("Duplicate (storage_provider_id, storage_key) on save translates to DuplicateStorageLocationException")
    void shouldTranslateDuplicateProviderKeyToDuplicateStorageLocationException() {
        Instant now = Instant.now();

        // 1. Seed first asset and version
        UUID assetId1 = UUID.randomUUID();
        UUID versionId1 = UUID.randomUUID();
        createdAssetIds.add(assetId1);
        createdVersionIds.add(versionId1);

        MediaAsset asset1 = MediaAsset.registerInitial(assetId1, MediaType.IMAGE, MediaVisibility.PUBLIC, now);
        assetAdapter.save(asset1);

        StorageLocation sharedLocation = StorageLocation.of("local", "objects/test-collision-" + UUID.randomUUID() + ".png");
        MediaAssetVersion version1 = MediaAssetVersion.create(
                versionId1,
                assetId1,
                1,
                sharedLocation,
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/png"),
                100L,
                "v1.png",
                now
        );
        versionAdapter.save(version1);

        // 2. Seed second independent asset
        UUID assetId2 = UUID.randomUUID();
        UUID versionId2 = UUID.randomUUID();
        createdAssetIds.add(assetId2);
        createdVersionIds.add(versionId2);

        MediaAsset asset2 = MediaAsset.registerInitial(assetId2, MediaType.IMAGE, MediaVisibility.PUBLIC, now);
        assetAdapter.save(asset2);

        // 3. Attempt to save version2 with the EXACT SAME storage location
        MediaAssetVersion version2 = MediaAssetVersion.create(
                versionId2,
                assetId2,
                1,
                sharedLocation,
                null,
                ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/png"),
                200L,
                "v2.png",
                now
        );

        assertThatThrownBy(() -> versionAdapter.save(version2))
                .isInstanceOf(DuplicateStorageLocationException.class)
                .hasMessageContaining(sharedLocation.providerId().value())
                .hasMessageContaining(sharedLocation.key().value());

        // 4. Verify version1 remains intact in database
        assertThat(versionRepository.existsById(versionId1.toString())).isTrue();
        assertThat(versionRepository.existsById(versionId2.toString())).isFalse();
    }

    @Test
    @DisplayName("Duplicate (asset_id, version_number) throws DataIntegrityViolationException and NOT DuplicateStorageLocationException")
    void shouldNotTranslateDuplicateAssetVersionToDuplicateStorageLocationException() {
        Instant now = Instant.now();

        UUID assetId = UUID.randomUUID();
        UUID versionId1 = UUID.randomUUID();
        UUID versionId2 = UUID.randomUUID();
        createdAssetIds.add(assetId);
        createdVersionIds.add(versionId1);
        createdVersionIds.add(versionId2);

        MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.IMAGE, MediaVisibility.PUBLIC, now);
        assetAdapter.save(asset);

        MediaAssetVersion version1 = MediaAssetVersion.create(
                versionId1,
                assetId,
                1,
                StorageLocation.of("local", "objects/unique-key-1-" + UUID.randomUUID() + ".png"),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/png"),
                100L,
                "v1.png",
                now
        );
        versionAdapter.save(version1);

        // Attempt to save another version for the same asset with the SAME version number 1 but different storage key
        MediaAssetVersion collidingVersionNumber = MediaAssetVersion.create(
                versionId2,
                assetId,
                1,
                StorageLocation.of("local", "objects/unique-key-2-" + UUID.randomUUID() + ".png"),
                null,
                ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/png"),
                200L,
                "v2.png",
                now
        );

        assertThatThrownBy(() -> versionAdapter.save(collidingVersionNumber))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateStorageLocationException.class);
    }

    @Test
    @DisplayName("Foreign key violation (unpersisted asset) throws DataIntegrityViolationException and NOT DuplicateStorageLocationException")
    void shouldNotTranslateForeignKeyViolationToDuplicateStorageLocationException() {
        Instant now = Instant.now();
        UUID unpersistedAssetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        createdVersionIds.add(versionId);

        MediaAssetVersion orphanVersion = MediaAssetVersion.create(
                versionId,
                unpersistedAssetId,
                1,
                StorageLocation.of("local", "objects/orphan-" + UUID.randomUUID() + ".png"),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/png"),
                100L,
                "orphan.png",
                now
        );

        assertThatThrownBy(() -> versionAdapter.save(orphanVersion))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateStorageLocationException.class);
    }
}
