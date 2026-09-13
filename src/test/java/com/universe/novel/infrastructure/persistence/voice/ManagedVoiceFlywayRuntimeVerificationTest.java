package com.universe.novel.infrastructure.persistence.voice;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedVoiceFlywayRuntimeVerificationTest {

    private static DataSource createDataSource(String dbName) {
        return TestDatabaseSupport.createTestDataSource(dbName);
    }

    private static void resetDatabase(String dbName) {
        TestDatabaseSupport.resetTestDatabase(dbName);
    }

    @Test
    @DisplayName("V38 Flyway migration: Xác thực schema novel_managed_voices, columns, PK, unique guard, và check constraints")
    void shouldMigrateCleanDatabaseThroughV38AndVerifySchema() {
        String dbName = "kiemlai_managed_voice_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(38);

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(38);

        MigrationInfo v38Info = null;
        for (MigrationInfo mi : info) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("38".equals(mi.getVersion().getVersion())) {
                v38Info = mi;
            }
        }

        assertThat(v38Info).isNotNull();
        assertThat(v38Info.getDescription()).isEqualTo("create novel managed voices");
        assertThat(v38Info.getChecksum()).isNotNull();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 1. Check table existence
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'novel_managed_voices'",
                Integer.class,
                dbName
        );
        assertThat(tableCount).isEqualTo(1);

        // 2. Verify column definitions
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'novel_managed_voices'",
                String.class,
                dbName
        );
        assertThat(columns).contains(
                "id",
                "voice_key",
                "display_name",
                "provider_voice_id",
                "status",
                "display_order",
                "is_default",
                "is_default_unique_guard",
                "synthesis_revision",
                "persistence_version",
                "created_at",
                "updated_at"
        );

        // 3. Verify Unique Constraints
        List<String> uniqueConstraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'novel_managed_voices' AND constraint_type = 'UNIQUE'",
                String.class,
                dbName
        );
        assertThat(uniqueConstraints).contains(
                "uq_novel_managed_voices_key",
                "uq_novel_managed_voices_single_default"
        );

        // 4. Verify Check Constraints
        List<String> checkConstraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'novel_managed_voices' AND constraint_type = 'CHECK'",
                String.class,
                dbName
        );
        assertThat(checkConstraints).contains(
                "chk_novel_managed_voices_status",
                "chk_novel_managed_voices_active_default",
                "chk_novel_managed_voices_synthesis_revision",
                "chk_novel_managed_voices_persistence_version"
        );
    }

    @Test
    @DisplayName("V38 Database invariants: Single default guard, ACTIVE default check, duplicate key enforcement")
    void shouldEnforceManagedVoiceDatabaseConstraintsOnRealMySql() {
        String dbName = "kiemlai_managed_voice_constraints_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID voice1 = UUID.randomUUID();
        UUID voice2 = UUID.randomUUID();
        UUID voice3 = UUID.randomUUID();

        // 1. Zero default voices is valid: insert multiple rows with is_default = FALSE
        jdbc.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'voice-1', 'Voice 1', 'prov-1', 'ACTIVE', 1, FALSE, 1, 0, NOW(6), NOW(6))",
                voice1.toString()
        );

        jdbc.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'voice-2', 'Voice 2', 'prov-2', 'ACTIVE', 2, FALSE, 1, 0, NOW(6), NOW(6))",
                voice2.toString()
        );

        // 2. One ACTIVE default is valid: insert row with is_default = TRUE, status = 'ACTIVE'
        jdbc.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'voice-3', 'Voice 3', 'prov-3', 'ACTIVE', 3, TRUE, 1, 0, NOW(6), NOW(6))",
                voice3.toString()
        );

        // 3. A second default is rejected by database unique guard uq_novel_managed_voices_single_default
        UUID voice4 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'voice-4', 'Voice 4', 'prov-4', 'ACTIVE', 4, TRUE, 1, 0, NOW(6), NOW(6))",
                voice4.toString()
        )).hasMessageContaining("uq_novel_managed_voices_single_default");

        // 4. Default + DISABLED is rejected by check constraint chk_novel_managed_voices_active_default
        UUID voice5 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'voice-5', 'Voice 5', 'prov-5', 'DISABLED', 5, TRUE, 1, 0, NOW(6), NOW(6))",
                voice5.toString()
        )).hasMessageContaining("chk_novel_managed_voices_active_default");

        // 5. Duplicate voice_key is rejected by uq_novel_managed_voices_key
        UUID voice6 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'voice-1', 'Duplicate Voice', 'prov-1', 'ACTIVE', 6, FALSE, 1, 0, NOW(6), NOW(6))",
                voice6.toString()
        )).hasMessageContaining("uq_novel_managed_voices_key");
    }
}
