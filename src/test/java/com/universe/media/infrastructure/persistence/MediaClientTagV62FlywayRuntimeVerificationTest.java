package com.universe.media.infrastructure.persistence;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Media Client Tag V62 Flyway Runtime Verification Tests")
class MediaClientTagV62FlywayRuntimeVerificationTest {

    private static final String TEST_DATABASE_NAME = "kiemlai_media_client_tag_v62_test";
    private static final UUID PRE_V62_ASSET_ID = UUID.fromString("62626262-6262-6262-6262-626262626261");
    private static final UUID POST_V62_ASSET_ID = UUID.fromString("62626262-6262-6262-6262-626262626262");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabaseSupport.resetTestDatabase(TEST_DATABASE_NAME);
        this.dataSource = TestDatabaseSupport.createTestDataSource(TEST_DATABASE_NAME);
        this.jdbcTemplate = new JdbcTemplate(this.dataSource);
    }

    @Test
    @DisplayName("Verify Flyway migration V61 -> V62 adds client_tag and index correctly")
    void shouldVerifyV62FlywayMigrationRuntime() {
        // 1. Migrate dedicated database up to V61
        Flyway flyway61 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("61")
                .load();
        flyway61.migrate();

        // 2. Insert representative media_asset under V61 (no client_tag column yet)
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                INSERT INTO media_assets (
                    id, media_type, visibility, status, current_version_number,
                    created_at, updated_at, persistence_version
                ) VALUES (?, 'IMAGE', 'PUBLIC', 'ACTIVE', 1, ?, ?, 0)
                """,
                PRE_V62_ASSET_ID.toString(), now, now
        );

        // 3. Migrate to V62
        Flyway flyway62 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("62")
                .load();
        flyway62.migrate();

        // 4. Assert Flyway schema history for V62
        Integer successV62 = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '62'",
                Integer.class
        );
        assertThat(successV62).isEqualTo(1);

        // 5. Verify column definitions in information_schema
        String dataType = jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'media_assets'
                  AND column_name = 'client_tag'
                """, String.class, TEST_DATABASE_NAME);
        assertThat(dataType).isEqualToIgnoringCase("varchar");

        Integer charLength = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'media_assets'
                  AND column_name = 'client_tag'
                """, Integer.class, TEST_DATABASE_NAME);
        assertThat(charLength).isEqualTo(64);

        String isNullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'media_assets'
                  AND column_name = 'client_tag'
                """, String.class, TEST_DATABASE_NAME);
        assertThat(isNullable).isEqualTo("YES");

        // 6. Verify index existence in information_schema.statistics
        List<String> indexedColumns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name = 'media_assets'
                  AND index_name = 'idx_media_assets_client_tag_status_created_at'
                ORDER BY seq_in_index
                """, String.class, TEST_DATABASE_NAME);
        assertThat(indexedColumns).containsExactly("client_tag", "status", "created_at", "id");

        // 7. Verify pre-existing row has NULL client_tag
        String preExistingTag = jdbcTemplate.queryForObject(
                "SELECT client_tag FROM media_assets WHERE id = ?",
                String.class,
                PRE_V62_ASSET_ID.toString()
        );
        assertThat(preExistingTag).isNull();

        // 8. Insert new asset with client_tag
        jdbcTemplate.update("""
                INSERT INTO media_assets (
                    id, media_type, visibility, client_tag, status, current_version_number,
                    created_at, updated_at, persistence_version
                ) VALUES (?, 'IMAGE', 'PUBLIC', 'wiki.article.cover', 'ACTIVE', 1, ?, ?, 0)
                """,
                POST_V62_ASSET_ID.toString(), now, now
        );

        String postTag = jdbcTemplate.queryForObject(
                "SELECT client_tag FROM media_assets WHERE id = ?",
                String.class,
                POST_V62_ASSET_ID.toString()
        );
        assertThat(postTag).isEqualTo("wiki.article.cover");

        // 9. Verify client_tag length constraint (> 64 chars fails)
        String overlyLongTag = "a".repeat(65);
        UUID invalidAssetId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO media_assets (
                    id, media_type, visibility, client_tag, status, current_version_number,
                    created_at, updated_at, persistence_version
                ) VALUES (?, 'IMAGE', 'PUBLIC', ?, 'ACTIVE', 1, ?, ?, 0)
                """,
                invalidAssetId.toString(), overlyLongTag, now, now
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
