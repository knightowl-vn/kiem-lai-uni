package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaAssetCurrentMetadataQueryPort;
import com.universe.media.application.ports.MediaAssetCurrentMetadataQueryPort.MediaAssetCurrentMetadataSnapshot;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVisibility;
import com.universe.test.TestDatabaseSupport;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MediaAssetCurrentMetadataQueryPersistenceAdapter.class)
class MediaAssetCurrentMetadataReadJpaIntegrationTest {

    private static final UUID ASSET_ID = UUID.fromString("a2300000-0000-0000-0000-000000000001");
    private static final UUID VERSION_1_ID = UUID.fromString("a2300000-0000-0000-0000-000000000002");
    private static final UUID VERSION_2_ID = UUID.fromString("a2300000-0000-0000-0000-000000000003");
    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-09-10T00:00:00Z"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MediaAssetCurrentMetadataQueryPort queryPort;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void insertAsset() {
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version) "
                        + "VALUES (?, 'AUDIO', 'PUBLIC', 'ACTIVE', 2, ?, ?, 0)",
                ASSET_ID.toString(), NOW, NOW
        );
    }

    @Test
    void resolvesTheAssetDeclaredCurrentVersionWithOneProjectionQuery() {
        insertVersion(VERSION_1_ID, 1);
        insertVersion(VERSION_2_ID, 2);
        Statistics statistics = statistics();
        statistics.clear();

        MediaAssetCurrentMetadataSnapshot result = queryPort.findByAssetId(ASSET_ID).orElseThrow();

        assertThat(result).isEqualTo(new MediaAssetCurrentMetadataSnapshot(
                ASSET_ID, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 2, ASSET_ID, 2
        ));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void distinguishesAnExistingAssetWithNoDeclaredCurrentVersionRow() {
        insertVersion(VERSION_1_ID, 1);
        Statistics statistics = statistics();
        statistics.clear();

        MediaAssetCurrentMetadataSnapshot result = queryPort.findByAssetId(ASSET_ID).orElseThrow();

        assertThat(result.assetId()).isEqualTo(ASSET_ID);
        assertThat(result.currentVersionNumber()).isEqualTo(2);
        assertThat(result.declaredCurrentVersionAssetId()).isNull();
        assertThat(result.declaredCurrentVersionNumber()).isNull();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    private void insertVersion(UUID versionId, int versionNumber) {
        jdbcTemplate.update(
                "INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, original_filename, created_at) "
                        + "VALUES (?, ?, ?, 'local', ?, NULL, ?, 'audio/mpeg', 1024, ?, ?)",
                versionId.toString(), ASSET_ID.toString(), versionNumber,
                "objects/perf-b2b2-" + versionNumber,
                Integer.toString(versionNumber).repeat(64),
                "chapter-" + versionNumber + ".mp3",
                NOW
        );
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
