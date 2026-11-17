package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaPublicImageBatchDeliveryQueryPort;
import com.universe.media.application.ports.MediaPublicImageBatchDeliveryQueryPort.MediaPublicImageBatchDeliveryRow;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageProviderId;
import com.universe.test.TestDatabaseSupport;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MediaPublicImageBatchDeliveryQueryPersistenceAdapter.class)
@DisplayName("MediaPublicImageBatchDeliveryReadJpaIntegrationTest - Isolated MySQL Query Verification")
class MediaPublicImageBatchDeliveryReadJpaIntegrationTest {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-11-17T12:00:00Z"));
    private static final String CLOUDINARY_PROVIDER = "cloudinary";
    private static final String CLOUDINARY_HOST = "https://res.cloudinary.com/kiemlai/image/upload";

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MediaPublicImageBatchDeliveryQueryPort queryPort;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private void insertAsset(UUID assetId, String mediaType, String visibility, String status, int currentVersion) {
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                assetId.toString(), mediaType, visibility, status, currentVersion, NOW, NOW
        );
    }

    private void insertVersion(UUID versionId, UUID assetId, int versionNumber, String provider, String publicUrl) {
        jdbcTemplate.update(
                "INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, original_filename, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'image/jpeg', 10240, 'img.jpg', ?)",
                versionId.toString(), assetId.toString(), versionNumber, provider,
                "images/" + assetId + "/v" + versionNumber + ".jpg", publicUrl,
                "a".repeat(64), NOW
        );
    }

    private void insertVariant(UUID variantId, UUID versionId, String variantKey, int targetWidth, String publicUrl) {
        jdbcTemplate.update(
                "INSERT INTO media_image_variants (id, version_id, variant_key, target_width, public_url, mime_type, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'image/webp', ?)",
                variantId.toString(), versionId.toString(), variantKey, targetWidth, publicUrl, NOW
        );
    }

    private void insertPhysicalVariant(UUID variantId, UUID versionId, String variantKey, int targetWidth) {
        jdbcTemplate.update(
                "INSERT INTO media_image_variants (id, version_id, variant_key, target_width, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, width, height, created_at) "
                        + "VALUES (?, ?, ?, ?, 'local', ?, NULL, ?, 'image/webp', 5000, 300, 400, ?)",
                variantId.toString(), versionId.toString(), variantKey, targetWidth,
                "variants/" + variantId + ".webp", "b".repeat(64), NOW
        );
    }

    @Test
    @DisplayName("Single image query executes exactly 1 SQL statement")
    void resolvesEligibleBatchWithOneStatementFor1Asset() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        String sourceUrl = CLOUDINARY_HOST + "/source.jpg";
        String variantUrl = CLOUDINARY_HOST + "/c_scale,w_300/variant.webp";

        insertAsset(assetId, "IMAGE", "PUBLIC", "ACTIVE", 1);
        insertVersion(versionId, assetId, 1, CLOUDINARY_PROVIDER, sourceUrl);
        insertVariant(variantId, versionId, "w300", 300, variantUrl);

        Statistics stats = statistics();
        stats.clear();

        List<MediaPublicImageBatchDeliveryRow> rows =
                queryPort.findEligibleBatchDeliveryRows(List.of(assetId), "w300");

        assertThat(rows).hasSize(1);
        MediaPublicImageBatchDeliveryRow row = rows.get(0);
        assertThat(row.assetId()).isEqualTo(assetId);
        assertThat(row.status()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(row.visibility()).isEqualTo(MediaVisibility.PUBLIC);
        assertThat(row.mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(row.storageProviderId()).isEqualTo(StorageProviderId.of("cloudinary"));
        assertThat(row.versionPublicUrl()).isEqualTo(sourceUrl);
        assertThat(row.variantId()).isEqualTo(variantId);
        assertThat(row.variantPublicUrl()).isEqualTo(variantUrl);
        assertThat(row.isVariantPresent()).isTrue();

        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("12 images query executes exactly 1 SQL statement (O(1) query count)")
    void resolvesEligibleBatchWithOneStatementFor12Assets() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID assetId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            UUID variantId = UUID.randomUUID();
            ids.add(assetId);

            insertAsset(assetId, "IMAGE", "PUBLIC", "ACTIVE", 1);
            insertVersion(versionId, assetId, 1, CLOUDINARY_PROVIDER, CLOUDINARY_HOST + "/v" + i + ".jpg");
            insertVariant(variantId, versionId, "w300", 300, CLOUDINARY_HOST + "/w300_" + i + ".webp");
        }

        Statistics stats = statistics();
        stats.clear();

        List<MediaPublicImageBatchDeliveryRow> rows =
                queryPort.findEligibleBatchDeliveryRows(ids, "w300");

        assertThat(rows).hasSize(12);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("100 images query executes exactly 1 SQL statement (O(1) query count)")
    void resolvesEligibleBatchWithOneStatementFor100Assets() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            UUID assetId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            UUID variantId = UUID.randomUUID();
            ids.add(assetId);

            insertAsset(assetId, "IMAGE", "PUBLIC", "ACTIVE", 1);
            insertVersion(versionId, assetId, 1, CLOUDINARY_PROVIDER, CLOUDINARY_HOST + "/v" + i + ".jpg");
            insertVariant(variantId, versionId, "w300", 300, CLOUDINARY_HOST + "/w300_" + i + ".webp");
        }

        Statistics stats = statistics();
        stats.clear();

        List<MediaPublicImageBatchDeliveryRow> rows =
                queryPort.findEligibleBatchDeliveryRows(ids, "w300");

        assertThat(rows).hasSize(100);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Database query strictly filters out non-image, private, archived, and non-cloudinary records")
    void excludesIneligibleAssetsInSingleStatement() {
        UUID eligibleId = UUID.randomUUID();
        UUID privateId = UUID.randomUUID();
        UUID archivedId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        UUID nonCloudinaryId = UUID.randomUUID();

        // 1. Eligible: IMAGE, PUBLIC, ACTIVE, Cloudinary
        insertAsset(eligibleId, "IMAGE", "PUBLIC", "ACTIVE", 1);
        insertVersion(UUID.randomUUID(), eligibleId, 1, CLOUDINARY_PROVIDER, CLOUDINARY_HOST + "/eligible.jpg");

        // 2. Ineligible: PRIVATE
        insertAsset(privateId, "IMAGE", "PRIVATE", "ACTIVE", 1);
        insertVersion(UUID.randomUUID(), privateId, 1, CLOUDINARY_PROVIDER, CLOUDINARY_HOST + "/private.jpg");

        // 3. Ineligible: ARCHIVED
        insertAsset(archivedId, "IMAGE", "PUBLIC", "ARCHIVED", 1);
        insertVersion(UUID.randomUUID(), archivedId, 1, CLOUDINARY_PROVIDER, CLOUDINARY_HOST + "/archived.jpg");

        // 4. Ineligible: VIDEO
        insertAsset(videoId, "VIDEO", "PUBLIC", "ACTIVE", 1);
        insertVersion(UUID.randomUUID(), videoId, 1, CLOUDINARY_PROVIDER, CLOUDINARY_HOST + "/video.mp4");

        // 5. Ineligible: local provider (not cloudinary)
        insertAsset(nonCloudinaryId, "IMAGE", "PUBLIC", "ACTIVE", 1);
        insertVersion(UUID.randomUUID(), nonCloudinaryId, 1, "local", "https://local/img.jpg");

        List<UUID> probeIds = List.of(eligibleId, privateId, archivedId, videoId, nonCloudinaryId);

        Statistics stats = statistics();
        stats.clear();

        List<MediaPublicImageBatchDeliveryRow> rows =
                queryPort.findEligibleBatchDeliveryRows(probeIds, (String) null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).assetId()).isEqualTo(eligibleId);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Physical variant with null publicUrl is represented with variantId populated and null variantPublicUrl")
    void resolvesPhysicalVariantWithNullPublicUrlInSingleStatement() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        String sourceUrl = CLOUDINARY_HOST + "/source_physical.jpg";

        insertAsset(assetId, "IMAGE", "PUBLIC", "ACTIVE", 1);
        insertVersion(versionId, assetId, 1, CLOUDINARY_PROVIDER, sourceUrl);
        // Physical variant satisfies MySQL chk_media_image_variants_delivery_mode with NULL public_url
        insertPhysicalVariant(variantId, versionId, "w300", 300);

        Statistics stats = statistics();
        stats.clear();

        List<MediaPublicImageBatchDeliveryRow> rows =
                queryPort.findEligibleBatchDeliveryRows(List.of(assetId), "w300");

        assertThat(rows).hasSize(1);
        MediaPublicImageBatchDeliveryRow row = rows.get(0);
        assertThat(row.assetId()).isEqualTo(assetId);
        assertThat(row.variantId()).isEqualTo(variantId);
        assertThat(row.isVariantPresent()).isTrue();
        assertThat(row.variantPublicUrl()).isNull();
        assertThat(row.versionPublicUrl()).isEqualTo(sourceUrl);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }
}
