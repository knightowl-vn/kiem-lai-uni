package com.universe.wiki.infrastructure.persistence.orphan;

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

@DisplayName("Wiki Cover Orphan V63 Flyway Runtime Verification Tests")
class WikiCoverOrphanV63FlywayRuntimeVerificationTest {

    private static final String TEST_DATABASE_NAME = "kiemlai_wiki_cover_orphan_v63_test";
    private static final UUID TEST_ASSET_ID = UUID.fromString("63636363-6363-6363-6363-636363636363");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabaseSupport.resetTestDatabase(TEST_DATABASE_NAME);
        this.dataSource = TestDatabaseSupport.createTestDataSource(TEST_DATABASE_NAME);
        this.jdbcTemplate = new JdbcTemplate(this.dataSource);
    }

    @Test
    @DisplayName("Verify Flyway migration V62 -> V63 creates wiki_cover_orphans table and indexes correctly")
    void shouldVerifyV63FlywayMigrationRuntime() {
        // 1. Migrate dedicated database up to V62
        Flyway flyway62 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("62")
                .load();
        flyway62.migrate();

        // 2. Migrate to V63
        Flyway flyway63 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("63")
                .load();
        flyway63.migrate();

        // 3. Assert Flyway schema history for V63
        Integer successV63 = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '63'",
                Integer.class
        );
        assertThat(successV63).isEqualTo(1);

        // 4. Verify columns in information_schema
        List<String> columnNames = jdbcTemplate.query(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_cover_orphans'
                ORDER BY ordinal_position
                """,
                (rs, rowNum) -> rs.getString("column_name"),
                TEST_DATABASE_NAME
        );

        assertThat(columnNames).containsExactly(
                "media_asset_id",
                "status",
                "first_seen_orphan_at",
                "claim_token",
                "locked_at",
                "retry_count",
                "last_error",
                "created_at",
                "updated_at"
        );

        // 5. Verify Primary Key
        String pkColumn = jdbcTemplate.queryForObject(
                """
                SELECT column_name FROM information_schema.key_column_usage
                WHERE table_schema = ?
                  AND table_name = 'wiki_cover_orphans'
                  AND constraint_name = 'PRIMARY'
                """,
                String.class,
                TEST_DATABASE_NAME
        );
        assertThat(pkColumn).isEqualTo("media_asset_id");

        // 6. Verify Indexes
        List<String> indexes = jdbcTemplate.query(
                """
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name = 'wiki_cover_orphans'
                """,
                (rs, rowNum) -> rs.getString("index_name"),
                TEST_DATABASE_NAME
        );

        assertThat(indexes).contains(
                "PRIMARY",
                "idx_wiki_cover_orphans_status_first_seen",
                "idx_wiki_cover_orphans_status_locked"
        );

        // 7. Verify valid insert and query
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                INSERT INTO wiki_cover_orphans (
                    media_asset_id, status, first_seen_orphan_at, created_at, updated_at
                ) VALUES (?, 'PENDING', ?, ?, ?)
                """,
                TEST_ASSET_ID.toString(), now, now, now
        );

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM wiki_cover_orphans WHERE media_asset_id = ?",
                String.class,
                TEST_ASSET_ID.toString()
        );
        assertThat(status).isEqualTo("PENDING");

        // 8. Verify check constraint rejects invalid status
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wiki_cover_orphans (
                    media_asset_id, status, first_seen_orphan_at, created_at, updated_at
                ) VALUES (?, 'FAILED', ?, ?, ?)
                """,
                UUID.randomUUID().toString(), now, now, now
        )).hasMessageContaining("chk_wiki_cover_orphans_status");
    }
}
