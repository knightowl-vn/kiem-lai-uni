package com.universe.media.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MS-08 R2-02: Isolated Disposable MySQL Schema Verification for Flyway V93.
 *
 * <p>Enforces strict execution gates:
 * <ul>
 *     <li>Disabled by default; requires explicit opt-in via JVM system property: {@code -Dkiemlai.v93.verify.enabled=true};</li>
 *     <li>Target schema must be explicitly supplied via {@code -Dkiemlai.v93.verify.schema=<name>} matching {@code ^kiemlai_r2_v93_verify_[a-zA-Z0-9_]+$};</li>
 *     <li>Requires dedicated test credentials; does not assume root or fall back to application credentials;</li>
 *     <li>Fully fail-closed schema pre-existence validation (aborts if schema already exists or query is inconclusive);</li>
 *     <li>Verifies {@code kiemlai_universe} and {@code kiemlai_test} are NOT targeted and remain untouched;</li>
 *     <li>Migrates baseline schema through V92 and confirms {@code provider_upload_id} is {@code VARCHAR(255)};</li>
 *     <li>Inserts synthetic B2 upload session fixtures with valid FK references pre-V93;</li>
 *     <li>Applies V93 through Flyway and validates execution outcome;</li>
 *     <li>Confirms post-V93 {@code provider_upload_id} is {@code VARCHAR(1024) NULL};</li>
 *     <li>Confirms composite index prefix {@code SUB_PART = 255} on {@code provider_upload_id};</li>
 *     <li>Confirms original CHECK constraint remains active;</li>
 *     <li>Confirms pre-existing synthetic B2 rows remain completely unchanged;</li>
 *     <li>Executes real SQL persistence round-trips for 343, 512, and 1024 characters;</li>
 *     <li>Confirms 1025-character insertion is safely rejected by MySQL;</li>
 *     <li>Does NOT drop or clean up the disposable schema after testing (preserved for owner audit).</li>
 * </ul>
 */
@DisplayName("MS-08 R2-02: Isolated MySQL Migration Verification for V93")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = MediaV93VerificationGuard.OPT_IN_PROPERTY, matches = "true")
class MediaV93IsolatedMySQLVerificationTest {

    private static String targetSchema;
    private static final String DRIVER_CLASS_NAME = "com.mysql.cj.jdbc.Driver";

    private static String host;
    private static String user;
    private static String password;
    private static DataSource targetDataSource;
    private static JdbcTemplate jdbc;

    // Synthetic B2 fixture IDs created pre-V93:
    private static final UUID PRE_V93_ALLOC_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRE_V93_SESSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PRE_V93_TARGET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String PRE_V93_B2_UPLOAD_ID = "4_z27c88f01f40941498b8c0812_f1001d3cb84024b48_d20261031_m153900_c001_v0001001_t0001";
    private static final Instant NOW = Instant.parse("2026-10-31T15:39:00Z");

    @BeforeAll
    static void setUpIsolatedDatabase() {
        if (!MediaV93VerificationGuard.isOptedIn()) {
            Assumptions.abort("V93 isolated MySQL verification is disabled by default. "
                    + "Set system property -D" + MediaV93VerificationGuard.OPT_IN_PROPERTY + "=true to opt in.");
            return;
        }

        String configuredSchema = System.getProperty(MediaV93VerificationGuard.SCHEMA_PROPERTY);
        targetSchema = MediaV93VerificationGuard.resolveAndValidateSchema(configuredSchema);

        String rawHost = resolveHost();
        host = MediaV93VerificationGuard.resolveAndValidateHost(rawHost);

        user = MediaV93VerificationGuard.resolveAndValidateUser(resolveUser());
        password = MediaV93VerificationGuard.resolveAndValidatePassword(resolvePassword());

        // 1. Connect to system schema on loopback to verify disposable schema does NOT exist
        DriverManagerDataSource rootDs = new DriverManagerDataSource();
        rootDs.setDriverClassName(DRIVER_CLASS_NAME);
        rootDs.setUrl("jdbc:mysql://" + host + "/information_schema?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        rootDs.setUsername(user);
        rootDs.setPassword(password);

        JdbcTemplate rootJdbc = new JdbcTemplate(rootDs);

        List<String> existingSchemas = rootJdbc.queryForList(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name = ?",
                String.class,
                targetSchema
        );

        MediaV93VerificationGuard.validateSchemaDoesNotExist(existingSchemas, targetSchema);

        // 2. Create the new disposable schema
        rootJdbc.execute("CREATE DATABASE `" + targetSchema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");

        // 3. Build isolated DataSource exclusively pointing to the newly created disposable schema
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(DRIVER_CLASS_NAME);
        ds.setUrl("jdbc:mysql://" + host + "/" + targetSchema + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        ds.setUsername(user);
        ds.setPassword(password);

        targetDataSource = ds;
        jdbc = new JdbcTemplate(targetDataSource);
    }

    @Test
    @Order(1)
    @DisplayName("1. Verify target connection identity is strictly the configured disposable schema")
    void step01_verifyDatabaseIdentity() {
        String currentDb = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertThat(currentDb).isEqualTo(targetSchema);
        assertThat(currentDb).doesNotContain("kiemlai_universe");
    }

    @Test
    @Order(2)
    @DisplayName("2. Baseline Flyway migration through V92 execution")
    void step02_executeBaselineThroughV92() {
        Flyway flywayV92 = Flyway.configure()
                .dataSource(targetDataSource)
                .locations("classpath:db/migration")
                .target("92")
                .load();

        flywayV92.migrate();

        MigrationInfo[] info = flywayV92.info().applied();
        assertThat(info).isNotEmpty();
        MigrationInfo latest = info[info.length - 1];
        assertThat(latest.getVersion().getVersion()).isEqualTo("92");
        assertThat(latest.getState()).isEqualTo(MigrationState.SUCCESS);
    }

    @Test
    @Order(3)
    @DisplayName("3. Verify baseline V92 column capacity is VARCHAR(255)")
    void step03_verifyPreV93ColumnCapacity() {
        Map<String, Object> col = jdbc.queryForMap(
                "SELECT data_type, character_maximum_length, is_nullable " +
                        "FROM information_schema.columns " +
                        "WHERE table_schema = ? AND table_name = 'media_upload_sessions' AND column_name = 'provider_upload_id'",
                targetSchema
        );

        assertThat(col.get("data_type")).isEqualTo("varchar");
        assertThat(((Number) col.get("character_maximum_length")).longValue()).isEqualTo(255L);
        assertThat(col.get("is_nullable")).isEqualTo("YES");
    }

    @Test
    @Order(4)
    @DisplayName("4. Verify baseline V92 composite index and check constraint")
    void step04_verifyPreV93IndexAndConstraint() {
        List<Map<String, Object>> indexRows = jdbc.queryForList(
                "SELECT column_name, sub_part " +
                        "FROM information_schema.statistics " +
                        "WHERE table_schema = ? AND table_name = 'media_upload_sessions' AND index_name = 'idx_media_upload_sessions_provider_upload' " +
                        "ORDER BY seq_in_index ASC",
                targetSchema
        );

        assertThat(indexRows).hasSize(2);
        assertThat(indexRows.get(0).get("column_name")).isEqualTo("storage_provider_id");
        assertThat(indexRows.get(0).get("sub_part")).isNull();
        assertThat(indexRows.get(1).get("column_name")).isEqualTo("provider_upload_id");
        // Prior to V93, provider_upload_id was indexed in full (no prefix sub_part):
        assertThat(indexRows.get(1).get("sub_part")).isNull();

        List<String> checkConstraints = jdbc.queryForList(
                "SELECT constraint_name " +
                        "FROM information_schema.table_constraints " +
                        "WHERE table_schema = ? AND table_name = 'media_upload_sessions' AND constraint_type = 'CHECK'",
                String.class,
                targetSchema
        );
        assertThat(checkConstraints).contains("chk_media_upload_sessions_provider_upload_id");
    }

    @Test
    @Order(5)
    @DisplayName("5. Insert pre-V93 synthetic B2 upload session fixtures")
    void step05_insertSyntheticB2Fixtures() {
        // Insert reserved allocation
        jdbc.update(
                "INSERT INTO media_storage_key_allocations (" +
                        "id, storage_provider_id, storage_bucket_name, storage_key, allocation_status, " +
                        "allocated_by_type, allocated_by_id, created_at, updated_at, persistence_version) " +
                        "VALUES (?, 'b2', 'kiemlai-video-test', 'movies/b2-pre-v93-test.mp4', 'RESERVED', " +
                        "'UPLOAD_SESSION', ?, ?, ?, 0)",
                PRE_V93_ALLOC_ID.toString(),
                PRE_V93_SESSION_ID.toString(),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );

        // Insert pre-V93 B2 upload session
        jdbc.update(
                "INSERT INTO media_upload_sessions (" +
                        "id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id, " +
                        "idempotency_key, media_type, declared_mime_type, declared_size_bytes, storage_provider_id, " +
                        "storage_bucket_name, storage_key, storage_allocation_id, provider_upload_id, part_size_bytes, " +
                        "expected_part_count, original_filename, client_tag, status, expires_at, cleanup_status, " +
                        "cleanup_retry_count, created_at, updated_at, persistence_version) " +
                        "VALUES (?, 'admin-b2-tester', 'screen', 'SCREEN_ENTRY', ?, " +
                        "'idem-b2-pre-v93', 'VIDEO', 'video/mp4', 52428800, 'b2', " +
                        "'kiemlai-video-test', 'movies/b2-pre-v93-test.mp4', ?, ?, 26214400, " +
                        "2, 'pre-v93-b2-video.mp4', 'screen-video', 'INITIATED', ?, 'NONE', " +
                        "0, ?, ?, 0)",
                PRE_V93_SESSION_ID.toString(),
                PRE_V93_TARGET_ID.toString(),
                PRE_V93_ALLOC_ID.toString(),
                PRE_V93_B2_UPLOAD_ID,
                Timestamp.from(NOW.plus(1, ChronoUnit.DAYS)),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );

        String readUploadId = jdbc.queryForObject(
                "SELECT provider_upload_id FROM media_upload_sessions WHERE id = ?",
                String.class,
                PRE_V93_SESSION_ID.toString()
        );
        assertThat(readUploadId).isEqualTo(PRE_V93_B2_UPLOAD_ID);
    }

    @Test
    @Order(6)
    @DisplayName("6. Execute Flyway migration V93")
    void step06_executeFlywayV93() {
        Flyway flywayV93 = Flyway.configure()
                .dataSource(targetDataSource)
                .locations("classpath:db/migration")
                .target("93")
                .load();

        flywayV93.migrate();

        MigrationInfo[] info = flywayV93.info().applied();
        MigrationInfo v93 = null;
        for (MigrationInfo mi : info) {
            if ("93".equals(mi.getVersion().getVersion())) {
                v93 = mi;
                break;
            }
        }

        assertThat(v93).isNotNull();
        assertThat(v93.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v93.getDescription()).isEqualTo("enlarge media upload sessions provider upload id");
    }

    @Test
    @Order(7)
    @DisplayName("7. Verify post-V93 column capacity is VARCHAR(1024) NULL")
    void step07_verifyPostV93ColumnCapacity() {
        Map<String, Object> col = jdbc.queryForMap(
                "SELECT data_type, character_maximum_length, is_nullable " +
                        "FROM information_schema.columns " +
                        "WHERE table_schema = ? AND table_name = 'media_upload_sessions' AND column_name = 'provider_upload_id'",
                targetSchema
        );

        assertThat(col.get("data_type")).isEqualTo("varchar");
        assertThat(((Number) col.get("character_maximum_length")).longValue()).isEqualTo(1024L);
        assertThat(col.get("is_nullable")).isEqualTo("YES");
    }

    @Test
    @Order(8)
    @DisplayName("8. Verify post-V93 composite index prefix (SUB_PART = 255)")
    void step08_verifyPostV93CompositeIndexPrefix() {
        List<Map<String, Object>> indexRows = jdbc.queryForList(
                "SELECT column_name, sub_part " +
                        "FROM information_schema.statistics " +
                        "WHERE table_schema = ? AND table_name = 'media_upload_sessions' AND index_name = 'idx_media_upload_sessions_provider_upload' " +
                        "ORDER BY seq_in_index ASC",
                targetSchema
        );

        assertThat(indexRows).hasSize(2);
        assertThat(indexRows.get(0).get("column_name")).isEqualTo("storage_provider_id");
        assertThat(indexRows.get(0).get("sub_part")).isNull(); // full VARCHAR(50)

        assertThat(indexRows.get(1).get("column_name")).isEqualTo("provider_upload_id");
        assertThat(((Number) indexRows.get(1).get("sub_part")).longValue()).isEqualTo(255L); // prefix 255 chars
    }

    @Test
    @Order(9)
    @DisplayName("9. Verify original CHECK constraint remains present")
    void step09_verifyCheckConstraintPreserved() {
        List<String> checkConstraints = jdbc.queryForList(
                "SELECT constraint_name " +
                        "FROM information_schema.table_constraints " +
                        "WHERE table_schema = ? AND table_name = 'media_upload_sessions' AND constraint_type = 'CHECK'",
                String.class,
                targetSchema
        );
        assertThat(checkConstraints).contains("chk_media_upload_sessions_provider_upload_id");
    }

    @Test
    @Order(10)
    @DisplayName("10. Confirm pre-existing synthetic B2 rows remain completely unchanged")
    void step10_verifyPreExistingB2RowsUnchanged() {
        Map<String, Object> sessionRow = jdbc.queryForMap(
                "SELECT storage_provider_id, storage_bucket_name, storage_key, provider_upload_id, status " +
                        "FROM media_upload_sessions WHERE id = ?",
                PRE_V93_SESSION_ID.toString()
        );

        assertThat(sessionRow.get("storage_provider_id")).isEqualTo("b2");
        assertThat(sessionRow.get("storage_bucket_name")).isEqualTo("kiemlai-video-test");
        assertThat(sessionRow.get("storage_key")).isEqualTo("movies/b2-pre-v93-test.mp4");
        assertThat(sessionRow.get("provider_upload_id")).isEqualTo(PRE_V93_B2_UPLOAD_ID);
        assertThat(sessionRow.get("status")).isEqualTo("INITIATED");
    }

    @Test
    @Order(11)
    @DisplayName("11. Real SQL persistence: 343-character synthetic R2 Upload ID persists and reads back exactly")
    void step11_persist343CharUploadId() {
        UUID allocId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String synthetic343 = "cf-r2-upload-token-" + "a".repeat(310) + "-part-seq-0001";
        assertThat(synthetic343.length()).isEqualTo(343);

        insertAllocatedSession(allocId, sessionId, "movies/r2-343-test.mp4");

        // Update to INITIATED with 343-character Upload ID
        jdbc.update(
                "UPDATE media_upload_sessions SET status = 'INITIATED', provider_upload_id = ?, updated_at = ? WHERE id = ?",
                synthetic343,
                Timestamp.from(NOW),
                sessionId.toString()
        );

        String readBack = jdbc.queryForObject(
                "SELECT provider_upload_id FROM media_upload_sessions WHERE id = ?",
                String.class,
                sessionId.toString()
        );

        assertThat(readBack).isEqualTo(synthetic343);
        assertThat(readBack.length()).isEqualTo(343);
    }

    @Test
    @Order(12)
    @DisplayName("12. Real SQL persistence: 512-character synthetic Upload ID persists and reads back exactly")
    void step12_persist512CharUploadId() {
        UUID allocId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        // 19 + 479 + 14 = 512
        String synthetic512 = "cf-r2-upload-token-" + "x".repeat(479) + "-part-seq-0512";
        assertThat(synthetic512.length()).isEqualTo(512);

        insertAllocatedSession(allocId, sessionId, "movies/r2-512-test.mp4");

        jdbc.update(
                "UPDATE media_upload_sessions SET status = 'INITIATED', provider_upload_id = ?, updated_at = ? WHERE id = ?",
                synthetic512,
                Timestamp.from(NOW),
                sessionId.toString()
        );

        String readBack = jdbc.queryForObject(
                "SELECT provider_upload_id FROM media_upload_sessions WHERE id = ?",
                String.class,
                sessionId.toString()
        );

        assertThat(readBack).isEqualTo(synthetic512);
        assertThat(readBack.length()).isEqualTo(512);
    }

    @Test
    @Order(13)
    @DisplayName("13. Real SQL persistence: 1024-character boundary synthetic Upload ID persists and reads back exactly")
    void step13_persist1024CharUploadId() {
        UUID allocId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        // 18 + 997 + 9 = 1024
        String synthetic1024 = "boundary-r2-token-" + "b".repeat(997) + "-end-1024";
        assertThat(synthetic1024.length()).isEqualTo(1024);

        insertAllocatedSession(allocId, sessionId, "movies/r2-1024-test.mp4");

        jdbc.update(
                "UPDATE media_upload_sessions SET status = 'INITIATED', provider_upload_id = ?, updated_at = ? WHERE id = ?",
                synthetic1024,
                Timestamp.from(NOW),
                sessionId.toString()
        );

        String readBack = jdbc.queryForObject(
                "SELECT provider_upload_id FROM media_upload_sessions WHERE id = ?",
                String.class,
                sessionId.toString()
        );

        assertThat(readBack).isEqualTo(synthetic1024);
        assertThat(readBack.length()).isEqualTo(1024);
    }

    @Test
    @Order(14)
    @DisplayName("14. Real SQL persistence: 1025-character synthetic Upload ID is rejected safely by MySQL")
    void step14_reject1025CharUploadId() {
        UUID allocId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        // 18 + 998 + 9 = 1025
        String synthetic1025 = "boundary-r2-token-" + "b".repeat(998) + "-end-1025";
        assertThat(synthetic1025.length()).isEqualTo(1025);

        insertAllocatedSession(allocId, sessionId, "movies/r2-1025-test.mp4");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE media_upload_sessions SET status = 'INITIATED', provider_upload_id = ?, updated_at = ? WHERE id = ?",
                synthetic1025,
                Timestamp.from(NOW),
                sessionId.toString()
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Data too long for column 'provider_upload_id'");
    }

    @Test
    @Order(15)
    @DisplayName("15. Real SQL persistence: NULL provider_upload_id remains legal in ALLOCATED state")
    void step15_nullLegalInAllocatedState() {
        UUID allocId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        insertAllocatedSession(allocId, sessionId, "movies/r2-null-test.mp4");

        Map<String, Object> sessionRow = jdbc.queryForMap(
                "SELECT status, provider_upload_id FROM media_upload_sessions WHERE id = ?",
                sessionId.toString()
        );

        assertThat(sessionRow.get("status")).isEqualTo("ALLOCATED");
        assertThat(sessionRow.get("provider_upload_id")).isNull();
    }

    @Test
    @Order(16)
    @DisplayName("16. Existing CHECK constraint rejects INITIATED status with NULL provider_upload_id")
    void step16_checkConstraintRejectsInitiatedWithNullUploadId() {
        UUID allocId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        insertAllocatedSession(allocId, sessionId, "movies/r2-chk-test.mp4");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE media_upload_sessions SET status = 'INITIATED', provider_upload_id = NULL, updated_at = ? WHERE id = ?",
                Timestamp.from(NOW),
                sessionId.toString()
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_media_upload_sessions_provider_upload_id");
    }

    // --- Helper methods ---

    private void insertAllocatedSession(UUID allocId, UUID sessionId, String key) {
        jdbc.update(
                "INSERT INTO media_storage_key_allocations (" +
                        "id, storage_provider_id, storage_bucket_name, storage_key, allocation_status, " +
                        "allocated_by_type, allocated_by_id, created_at, updated_at, persistence_version) " +
                        "VALUES (?, 'r2-movie', 'kiemlai-screen', ?, 'RESERVED', " +
                        "'UPLOAD_SESSION', ?, ?, ?, 0)",
                allocId.toString(),
                key,
                sessionId.toString(),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );

        jdbc.update(
                "INSERT INTO media_upload_sessions (" +
                        "id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id, " +
                        "idempotency_key, media_type, declared_mime_type, declared_size_bytes, storage_provider_id, " +
                        "storage_bucket_name, storage_key, storage_allocation_id, provider_upload_id, part_size_bytes, " +
                        "expected_part_count, original_filename, client_tag, status, expires_at, cleanup_status, " +
                        "cleanup_retry_count, created_at, updated_at, persistence_version) " +
                        "VALUES (?, 'admin-r2-tester', 'screen', 'SCREEN_ENTRY', ?, " +
                        "?, 'VIDEO', 'video/mp4', 52428800, 'r2-movie', " +
                        "'kiemlai-screen', ?, ?, NULL, 26214400, " +
                        "2, 'screen-video.mp4', 'screen-video', 'ALLOCATED', ?, 'NONE', " +
                        "0, ?, ?, 0)",
                sessionId.toString(),
                UUID.randomUUID().toString(),
                "idem-" + sessionId,
                key,
                allocId.toString(),
                Timestamp.from(NOW.plus(1, ChronoUnit.DAYS)),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
    }

    private static String resolveHost() {
        String h = System.getProperty(MediaV93VerificationGuard.HOST_PROPERTY);
        if (h != null && !h.isBlank()) return h.trim();
        String eh = System.getenv(MediaV93VerificationGuard.HOST_ENV);
        if (eh != null && !eh.isBlank()) return eh.trim();
        return "localhost:3306";
    }

    private static String resolveUser() {
        String u = System.getProperty(MediaV93VerificationGuard.USER_PROPERTY);
        if (u != null && !u.isBlank()) return u.trim();
        String eu = System.getenv(MediaV93VerificationGuard.USER_ENV);
        if (eu != null && !eu.isBlank()) return eu.trim();
        return null; // Triggers explicit exception in MediaV93VerificationGuard.resolveAndValidateUser
    }

    private static String resolvePassword() {
        String p = System.getProperty(MediaV93VerificationGuard.PASS_PROPERTY);
        if (p != null && !p.isBlank()) return p;
        String ep = System.getenv(MediaV93VerificationGuard.PASS_ENV);
        if (ep != null && !ep.isBlank()) return ep;
        return null; // Triggers explicit exception in MediaV93VerificationGuard.resolveAndValidatePassword
    }
}
