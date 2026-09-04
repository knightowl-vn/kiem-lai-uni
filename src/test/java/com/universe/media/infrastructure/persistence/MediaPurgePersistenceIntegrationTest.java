package com.universe.media.infrastructure.persistence;

import com.universe.media.application.asset.PurgeMediaAssetMetadataService;
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
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
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

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
        MediaAssetVersionPersistenceAdapter.class,
        MediaImageVariantPersistenceAdapter.class,
        PurgeMediaAssetMetadataService.class
})
class MediaPurgePersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private DataSource dataSource;

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

    @Autowired
    private PurgeMediaAssetMetadataService purgeMediaAssetMetadataService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM media_image_variants");
        jdbcTemplate.update("DELETE FROM media_asset_versions");
        jdbcTemplate.update("DELETE FROM media_assets");
    }

    @Test
    @DisplayName("V37 Flyway migration: Verify composite index on media_assets(status, updated_at)")
    void shouldVerifyV37MigrationAndIndex() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo v37Info = null;
        for (MigrationInfo mi : flyway.info().all()) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("37".equals(mi.getVersion().getVersion())) {
                v37Info = mi;
            }
        }

        assertThat(v37Info).isNotNull();
        assertThat(v37Info.getDescription()).isEqualTo("add media assets status updated at index");

        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT index_name FROM information_schema.statistics " +
                        "WHERE table_schema = DATABASE() AND table_name = 'media_assets' AND index_name = 'idx_media_assets_status_updated_at'",
                String.class
        );
        assertThat(indexes).isNotEmpty();
    }

    @Test
    @DisplayName("findExpiredDeleted finds only expired DELETED assets via repository adapter")
    void shouldFindExpiredDeletedAssetsUsingAdapter() {
        Instant now = Instant.parse("2026-09-10T12:00:00Z");
        Instant cutoff = now.minus(Duration.ofDays(7)); // 2026-09-03T12:00:00Z

        UUID expiredDeletedId1 = UUID.randomUUID();
        UUID expiredDeletedId2 = UUID.randomUUID();
        UUID recentDeletedId = UUID.randomUUID();
        UUID activeAssetId = UUID.randomUUID();
        UUID archivedAssetId = UUID.randomUUID();

        // 1. Expired DELETED asset 1 (deleted 10 days ago)
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version) " +
                        "VALUES (?, 'IMAGE', 'PUBLIC', 'DELETED', 1, ?, ?, 0)",
                expiredDeletedId1.toString(), Timestamp.from(now.minus(Duration.ofDays(15))), Timestamp.from(now.minus(Duration.ofDays(10)))
        );

        // 2. Expired DELETED asset 2 (deleted 8 days ago)
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version) " +
                        "VALUES (?, 'IMAGE', 'PUBLIC', 'DELETED', 1, ?, ?, 0)",
                expiredDeletedId2.toString(), Timestamp.from(now.minus(Duration.ofDays(12))), Timestamp.from(now.minus(Duration.ofDays(8)))
        );

        // 3. Recent DELETED asset (deleted 3 days ago, within 7-day grace period)
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version) " +
                        "VALUES (?, 'IMAGE', 'PUBLIC', 'DELETED', 1, ?, ?, 0)",
                recentDeletedId.toString(), Timestamp.from(now.minus(Duration.ofDays(5))), Timestamp.from(now.minus(Duration.ofDays(3)))
        );

        // 4. ACTIVE asset (updated 20 days ago)
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version) " +
                        "VALUES (?, 'IMAGE', 'PUBLIC', 'ACTIVE', 1, ?, ?, 0)",
                activeAssetId.toString(), Timestamp.from(now.minus(Duration.ofDays(20))), Timestamp.from(now.minus(Duration.ofDays(20)))
        );

        // 5. ARCHIVED asset (archived 20 days ago)
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version) " +
                        "VALUES (?, 'IMAGE', 'PUBLIC', 'ARCHIVED', 1, ?, ?, 0)",
                archivedAssetId.toString(), Timestamp.from(now.minus(Duration.ofDays(20))), Timestamp.from(now.minus(Duration.ofDays(20)))
        );

        // Execute query through repository adapter
        List<MediaAsset> expiredAssets = assetAdapter.findExpiredDeleted(cutoff, 10);
        List<UUID> expiredIds = expiredAssets.stream().map(MediaAsset::getId).toList();

        assertThat(expiredIds).containsExactly(
                expiredDeletedId1,
                expiredDeletedId2
        );
        assertThat(expiredIds).doesNotContain(
                recentDeletedId,
                activeAssetId,
                archivedAssetId
        );
    }

    @Test
    @DisplayName("PurgeMediaAssetMetadataService purges variants, versions, and asset bottom-up via adapters without FK violations")
    void shouldPurgeMetadataBottomUpUsingServiceAndAdapters() {
        UUID assetId = UUID.randomUUID();
        UUID version1Id = UUID.randomUUID();
        UUID version2Id = UUID.randomUUID();
        UUID variant1Id = UUID.randomUUID();
        UUID variant2Id = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Seed parent asset (DELETED) via adapter
        MediaAsset asset = MediaAsset.rehydrate(
                assetId,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                MediaAssetStatus.DELETED,
                2,
                now.minus(Duration.ofDays(10)),
                now.minus(Duration.ofDays(8))
        );
        assetAdapter.save(asset);

        // 2. Seed version 1 and 2 via adapter
        MediaAssetVersion v1 = MediaAssetVersion.create(
                version1Id,
                assetId,
                1,
                StorageLocation.of("local", "objects/purge-v1.png"),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/png"),
                100L,
                "v1.png",
                now.minus(Duration.ofDays(10))
        );
        versionAdapter.save(v1);

        MediaAssetVersion v2 = MediaAssetVersion.create(
                version2Id,
                assetId,
                2,
                StorageLocation.of("local", "objects/purge-v2.png"),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/png"),
                200L,
                "v2.png",
                now.minus(Duration.ofDays(8))
        );
        versionAdapter.save(v2);

        // 3. Seed derivative variants via adapter
        MediaImageVariant var1 = MediaImageVariant.create(
                variant1Id,
                version1Id,
                ImageVariantSpec.of(300),
                StorageLocation.of("local", "variants/purge-v1-w300.webp"),
                ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/webp"),
                50L,
                300,
                300,
                now.minus(Duration.ofDays(10))
        );
        variantAdapter.save(var1);

        MediaImageVariant var2 = MediaImageVariant.create(
                variant2Id,
                version2Id,
                ImageVariantSpec.of(800),
                StorageLocation.of("local", "variants/purge-v2-w800.webp"),
                ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/webp"),
                80L,
                800,
                800,
                now.minus(Duration.ofDays(8))
        );
        variantAdapter.save(var2);

        // 4. Verify ON DELETE RESTRICT foreign key protection when deleting parent directly
        assertThatThrownBy(() -> assetAdapter.deleteById(assetId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> versionRepository.deleteById(version1Id.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 5. Execute PurgeMediaAssetMetadataService (bottom-up within transaction)
        purgeMediaAssetMetadataService.execute(assetId);

        // 6. Verify full metadata removal using adapters
        assertThat(variantAdapter.findByVersionIdAndVariantKey(version1Id, "w300")).isEmpty();
        assertThat(variantAdapter.findByVersionIdAndVariantKey(version2Id, "w800")).isEmpty();
        assertThat(versionAdapter.findAllByAssetId(assetId)).isEmpty();
        assertThat(assetAdapter.findById(assetId)).isEmpty();
    }

    @Test
    @DisplayName("PurgeMediaAssetMetadataService is idempotent when asset does not exist")
    void shouldBeIdempotentWhenAssetAlreadyMissing() {
        UUID nonExistentAssetId = UUID.randomUUID();

        // Must succeed without throwing
        purgeMediaAssetMetadataService.execute(nonExistentAssetId);

        assertThat(assetAdapter.findById(nonExistentAssetId)).isEmpty();
    }

    @Test
    @DisplayName("PurgeMediaAssetMetadataService refuses deletion when asset is ACTIVE")
    void shouldRefuseDeletionWhenAssetIsActive() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();

        MediaAsset activeAsset = MediaAsset.registerInitial(assetId, MediaType.IMAGE, MediaVisibility.PUBLIC, now);
        assetAdapter.save(activeAsset);

        MediaAssetVersion v1 = MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                StorageLocation.of("local", "objects/active.png"),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/png"),
                100L,
                "active.png",
                now
        );
        versionAdapter.save(v1);

        assertThatThrownBy(() -> purgeMediaAssetMetadataService.execute(assetId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("Only DELETED assets may be purged");

        // Verify metadata was NOT deleted
        assertThat(assetAdapter.findById(assetId)).isPresent();
        assertThat(versionAdapter.findAllByAssetId(assetId)).hasSize(1);
    }
}
