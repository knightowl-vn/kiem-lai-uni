package com.universe.media.infrastructure.persistence;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Media V89 Flyway Runtime Verification Integration Test")
class MediaV89FlywayRuntimeVerificationTest {

    private static final String TEST_DATABASE_NAME = "kiemlai_media_v89_schema_test";

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabaseSupport.resetTestDatabase(TEST_DATABASE_NAME);
        this.dataSource = TestDatabaseSupport.createTestDataSource(TEST_DATABASE_NAME);
        this.jdbcTemplate = new JdbcTemplate(this.dataSource);
    }

    private static SQLException extractSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlEx) {
                return sqlEx;
            }
            current = current.getCause();
        }
        return null;
    }

    private static void assertCheckConstraintViolation(ThrowableAssert.ThrowingCallable callable, String expectedConstraintName) {
        assertThatThrownBy(callable)
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> {
                    SQLException sqlEx = extractSqlException(ex);
                    assertThat((Throwable) sqlEx).as("Underlying SQLException must be present").isNotNull();
                    assertThat(sqlEx.getErrorCode())
                            .as("MySQL vendor error code for CHECK constraint violation must be 3819")
                            .isEqualTo(3819);
                    assertThat(sqlEx.getMessage().toLowerCase())
                            .as("Error message must identify check constraint " + expectedConstraintName)
                            .contains(expectedConstraintName.toLowerCase());
                });
    }

    private static void assertForeignKeyViolation(ThrowableAssert.ThrowingCallable callable, String expectedConstraintName) {
        assertThatThrownBy(callable)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> {
                    SQLException sqlEx = extractSqlException(ex);
                    assertThat((Throwable) sqlEx).as("Underlying SQLException must be present").isNotNull();
                    assertThat(sqlEx.getErrorCode())
                            .as("MySQL vendor error code for FOREIGN KEY violation must be 1452")
                            .isEqualTo(1452);
                    assertThat(sqlEx.getMessage().toLowerCase())
                            .as("Error message must identify foreign key constraint " + expectedConstraintName)
                            .contains(expectedConstraintName.toLowerCase());
                });
    }

    private static void assertUniqueConstraintViolation(ThrowableAssert.ThrowingCallable callable, String expectedConstraintName) {
        assertThatThrownBy(callable)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> {
                    SQLException sqlEx = extractSqlException(ex);
                    assertThat((Throwable) sqlEx).as("Underlying SQLException must be present").isNotNull();
                    assertThat(sqlEx.getErrorCode())
                            .as("MySQL vendor error code for UNIQUE constraint violation must be 1062")
                            .isEqualTo(1062);
                    assertThat(sqlEx.getMessage().toLowerCase())
                            .as("Error message must identify unique constraint " + expectedConstraintName)
                            .contains(expectedConstraintName.toLowerCase());
                });
    }

    private static void assertNotNullViolation(ThrowableAssert.ThrowingCallable callable, String expectedColumnName) {
        assertThatThrownBy(callable)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> {
                    SQLException sqlEx = extractSqlException(ex);
                    assertThat((Throwable) sqlEx).as("Underlying SQLException must be present").isNotNull();
                    assertThat(sqlEx.getErrorCode())
                            .as("MySQL vendor error code for NOT NULL violation must be 1048")
                            .isEqualTo(1048);
                    assertThat(sqlEx.getMessage().toLowerCase())
                            .as("Error message must identify NOT NULL column " + expectedColumnName)
                            .contains(expectedColumnName.toLowerCase());
                });
    }

    @Test
    @DisplayName("Verify Flyway migration up to V89 creates tables, constraints, and check invariants correctly")
    void shouldVerifyV89FlywayMigrationRuntime() {
        // 1. Run migrations up to V89
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("89")
                .load();
        flyway.migrate();

        // 2. Verify Flyway schema history for V89
        Integer successV89 = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '89'",
                Integer.class
        );
        assertThat(successV89).isEqualTo(1);

        // 3. Verify media_storage_key_allocations table exists and enforces uniqueness
        UUID allocId1 = UUID.randomUUID();
        UUID allocId2 = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'b2', 'kiemlai-video', 'objects/unique_video_1.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, allocId1.toString(), ownerId.toString(), now, now);

        // Duplicate insert of identical (storage_provider_id, storage_key) must fail
        assertUniqueConstraintViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'b2', 'kiemlai-video', 'objects/unique_video_1.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, allocId2.toString(), ownerId.toString(), now, now),
                "uq_media_storage_key_allocations_provider_key"
        );

        // 4. Verify media_upload_sessions allows NULL provider_upload_id in ALLOCATED state
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-123', 'screen', 'screen_entry', ?,
                    'idemp-001', 'VIDEO', 'video/mp4', 104857600,
                    'b2', 'kiemlai-video', 'objects/unique_video_1.mp4', ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', ?, 'NONE', 0,
                    ?, ?, 0
                )
                """,
                sessionId.toString(),
                UUID.randomUUID().toString(),
                allocId1.toString(),
                Timestamp.from(Instant.now().plusSeconds(86400)),
                now, now
        );

        // 5. Verify mismatched storage key is rejected by composite FK constraint (using unclaimed allocation)
        UUID unclaimedAllocKeyMismatch = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'b2', 'kiemlai-video', 'objects/unclaimed_key_mismatch.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, unclaimedAllocKeyMismatch.toString(), ownerId.toString(), now, now);

        UUID mismatchedKeySessionId = UUID.randomUUID();
        assertForeignKeyViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-123', 'screen', 'screen_entry', ?,
                    'idemp-mismatch-key', 'VIDEO', 'video/mp4', 104857600,
                    'b2', 'kiemlai-video', 'objects/mismatched_key.mp4', ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', ?, 'NONE', 0,
                    ?, ?, 0
                )
                """,
                mismatchedKeySessionId.toString(),
                UUID.randomUUID().toString(),
                unclaimedAllocKeyMismatch.toString(),
                Timestamp.from(Instant.now().plusSeconds(86400)),
                now, now
        ), "fk_media_upload_sessions_allocation_location");

        // 6. Verify mismatched provider is rejected by composite FK constraint (using unclaimed allocation)
        UUID unclaimedAllocProviderMismatch = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'b2', 'kiemlai-video', 'objects/unclaimed_prov_mismatch.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, unclaimedAllocProviderMismatch.toString(), ownerId.toString(), now, now);

        UUID mismatchedProviderSessionId = UUID.randomUUID();
        assertForeignKeyViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-123', 'screen', 'screen_entry', ?,
                    'idemp-mismatch-provider', 'VIDEO', 'video/mp4', 104857600,
                    's3', 'kiemlai-video', 'objects/unclaimed_prov_mismatch.mp4', ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', ?, 'NONE', 0,
                    ?, ?, 0
                )
                """,
                mismatchedProviderSessionId.toString(),
                UUID.randomUUID().toString(),
                unclaimedAllocProviderMismatch.toString(),
                Timestamp.from(Instant.now().plusSeconds(86400)),
                now, now
        ), "fk_media_upload_sessions_allocation_location");

        // 6b. Verify mismatched bucket is rejected by composite FK constraint (using unclaimed allocation)
        UUID unclaimedAllocBucketMismatch = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'b2', 'kiemlai-video', 'objects/unclaimed_bucket_mismatch.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, unclaimedAllocBucketMismatch.toString(), ownerId.toString(), now, now);

        UUID mismatchedBucketSessionId = UUID.randomUUID();
        assertForeignKeyViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-123', 'screen', 'screen_entry', ?,
                    'idemp-mismatch-bucket', 'VIDEO', 'video/mp4', 104857600,
                    'b2', 'other-bucket', 'objects/unclaimed_bucket_mismatch.mp4', ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', ?, 'NONE', 0,
                    ?, ?, 0
                )
                """,
                mismatchedBucketSessionId.toString(),
                UUID.randomUUID().toString(),
                unclaimedAllocBucketMismatch.toString(),
                Timestamp.from(Instant.now().plusSeconds(86400)),
                now, now
        ), "fk_media_upload_sessions_allocation_location");

        // 6c. Verify bucketless provider semantics (canonical sentinel 'NONE')
        UUID bucketlessAllocId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'local', 'NONE', 'local/unique_local_1.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, bucketlessAllocId.toString(), ownerId.toString(), now, now);

        UUID bucketlessSessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-local', 'screen', 'screen_entry', ?,
                    'idemp-local-1', 'VIDEO', 'video/mp4', 104857600,
                    'local', 'NONE', 'local/unique_local_1.mp4', ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', ?, 'NONE', 0,
                    ?, ?, 0
                )
                """,
                bucketlessSessionId.toString(),
                UUID.randomUUID().toString(),
                bucketlessAllocId.toString(),
                Timestamp.from(Instant.now().plusSeconds(86400)),
                now, now
        );

        // Reject non-sentinel bucket against bucketless allocation (composite FK mismatch using unclaimed allocation)
        UUID unclaimedBucketlessForMismatch = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'local', 'NONE', 'local/unclaimed_local_mismatch.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, unclaimedBucketlessForMismatch.toString(), ownerId.toString(), now, now);

        UUID badBucketForLocalSessionId = UUID.randomUUID();
        assertForeignKeyViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-local-bad', 'screen', 'screen_entry', ?,
                    'idemp-local-badbucket', 'VIDEO', 'video/mp4', 104857600,
                    'local', 'unexpected-bucket', 'local/unclaimed_local_mismatch.mp4', ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', ?, 'NONE', 0,
                    ?, ?, 0
                )
                """,
                badBucketForLocalSessionId.toString(),
                UUID.randomUUID().toString(),
                unclaimedBucketlessForMismatch.toString(),
                Timestamp.from(Instant.now().plusSeconds(86400)),
                now, now
        ), "fk_media_upload_sessions_allocation_location");

        // 6d. Reject NULL bucket on allocation and session (NOT NULL column enforcement)
        UUID nullBucketAllocId = UUID.randomUUID();
        assertNotNullViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'local', NULL, 'local/null_bucket.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, nullBucketAllocId.toString(), ownerId.toString(), now, now),
                "storage_bucket_name"
        );

        UUID unclaimedAllocForNullSession = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'local', 'NONE', 'local/unclaimed_null_session.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, unclaimedAllocForNullSession.toString(), ownerId.toString(), now, now);

        UUID nullBucketSessionId = UUID.randomUUID();
        assertNotNullViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-null', 'screen', 'screen_entry', ?,
                    'idemp-local-nullbucket', 'VIDEO', 'video/mp4', 104857600,
                    'local', NULL, 'local/unclaimed_null_session.mp4', ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', ?, 'NONE', 0,
                    ?, ?, 0
                )
                """,
                nullBucketSessionId.toString(),
                UUID.randomUUID().toString(),
                unclaimedAllocForNullSession.toString(),
                Timestamp.from(Instant.now().plusSeconds(86400)),
                now, now
        ), "storage_bucket_name");

        // 6e. Reject sentinel 'NONE' for b2 provider on allocation and session (CHECK chk_*_b2_bucket)
        UUID b2SentinelAllocId = UUID.randomUUID();
        assertCheckConstraintViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'b2', 'NONE', 'objects/b2_sentinel.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, b2SentinelAllocId.toString(), ownerId.toString(), now, now),
                "chk_media_storage_key_allocations_b2_bucket"
        );

        UUID unclaimedAllocForB2SentinelSession = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'b2', 'kiemlai-video', 'objects/unclaimed_b2_sentinel_session.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, unclaimedAllocForB2SentinelSession.toString(), ownerId.toString(), now, now);

        UUID b2SentinelSessionId = UUID.randomUUID();
        assertCheckConstraintViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-b2-sentinel', 'screen', 'screen_entry', ?,
                    'idemp-b2-sentinel', 'VIDEO', 'video/mp4', 104857600,
                    'b2', 'NONE', 'objects/unclaimed_b2_sentinel_session.mp4', ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', ?, 'NONE', 0,
                    ?, ?, 0
                )
                """,
                b2SentinelSessionId.toString(),
                UUID.randomUUID().toString(),
                unclaimedAllocForB2SentinelSession.toString(),
                Timestamp.from(Instant.now().plusSeconds(86400)),
                now, now
        ), "chk_media_upload_sessions_b2_bucket");

        // 7. Verify two sessions cannot claim the same allocation
        UUID duplicateAllocationSessionId = UUID.randomUUID();
        assertUniqueConstraintViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-123', 'screen', 'screen_entry', ?,
                    'idemp-dup-alloc', 'VIDEO', 'video/mp4', 104857600,
                    'b2', 'kiemlai-video', 'objects/unique_video_1.mp4', ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', ?, 'NONE', 0,
                    ?, ?, 0
                )
                """,
                duplicateAllocationSessionId.toString(),
                UUID.randomUUID().toString(),
                allocId1.toString(),
                Timestamp.from(Instant.now().plusSeconds(86400)),
                now, now
        ), "uq_media_upload_sessions_allocation");

        // 8. Verify provider_upload_id constraint: transitioning to INITIATED with NULL provider_upload_id fails
        assertCheckConstraintViolation(() -> jdbcTemplate.update("""
                UPDATE media_upload_sessions SET status = 'INITIATED' WHERE id = ?
                """, sessionId.toString()),
                "chk_media_upload_sessions_provider_upload_id"
        );

        // Setting provider_upload_id permits transition to INITIATED
        int updated = jdbcTemplate.update("""
                UPDATE media_upload_sessions SET status = 'INITIATED', provider_upload_id = 'b2-upload-xyz' WHERE id = ?
                """, sessionId.toString());
        assertThat(updated).isEqualTo(1);

        // 8b. Verify completion constraint: COMPLETED without declared_content_hash, registered_asset_id or completed_at fails
        assertCheckConstraintViolation(() -> jdbcTemplate.update("""
                UPDATE media_upload_sessions SET status = 'COMPLETED' WHERE id = ?
                """, sessionId.toString()),
                "chk_media_upload_sessions_completion"
        );

        // 8c. Verify media_upload_sessions media_type check constraint rejects non-VIDEO (using unclaimed allocation)
        UUID unclaimedAllocForMediaType = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'b2', 'kiemlai-video', 'objects/unclaimed_media_type.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, unclaimedAllocForMediaType.toString(), ownerId.toString(), now, now);

        UUID invalidSessionId = UUID.randomUUID();
        assertCheckConstraintViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-123', 'screen', 'screen_entry', ?,
                    'idemp-invalid-type', 'IMAGE', 'video/mp4', 104857600,
                    'b2', 'kiemlai-video', 'objects/unclaimed_media_type.mp4', ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', ?, 'NONE', 0,
                    ?, ?, 0
                )
                """,
                invalidSessionId.toString(),
                UUID.randomUUID().toString(),
                unclaimedAllocForMediaType.toString(),
                Timestamp.from(Instant.now().plusSeconds(86400)),
                now, now
        ), "chk_media_upload_sessions_media_type");

        // 8d. Verify media_upload_sessions expected_part_count check constraint rejects mismatched count (!= CEIL(declared_size_bytes / part_size_bytes)) (using unclaimed allocation)
        UUID unclaimedAllocForParts = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at
                ) VALUES (?, 'b2', 'kiemlai-video', 'objects/unclaimed_parts.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?)
                """, unclaimedAllocForParts.toString(), ownerId.toString(), now, now);

        UUID invalidPartsSessionId = UUID.randomUUID();
        assertCheckConstraintViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-123', 'screen', 'screen_entry', ?,
                    'idemp-invalid-parts', 'VIDEO', 'video/mp4', 104857600,
                    'b2', 'kiemlai-video', 'objects/unclaimed_parts.mp4', ?,
                    NULL, 26214400, 5, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', ?, 'NONE', 0,
                    ?, ?, 0
                )
                """,
                invalidPartsSessionId.toString(),
                UUID.randomUUID().toString(),
                unclaimedAllocForParts.toString(),
                Timestamp.from(Instant.now().plusSeconds(86400)),
                now, now
        ), "chk_media_upload_sessions_expected_parts");

        // 9. Verify media_asset_versions content_hash_status column exists and check constraint allows valid statuses
        String columnType = jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'media_asset_versions'
                  AND column_name = 'content_hash_status'
                """, String.class, TEST_DATABASE_NAME);
        assertThat(columnType).isEqualTo("varchar");
    }
}
