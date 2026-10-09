package com.universe.media.infrastructure.persistence;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.assertj.core.api.ThrowableAssert;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        StorageKeyAllocationPersistenceAdapter.class,
        StorageKeyAllocationService.class,
        StorageKeyAllocationPersistenceIntegrationTest.TestConfig.class
})
@DisplayName("StorageKeyAllocation MySQL Persistence & Concurrency Integration Test")
class StorageKeyAllocationPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }
    }

    @Autowired
    private StorageKeyAllocationPersistenceAdapter adapter;

    @Autowired
    private StorageKeyAllocationService service;

    @Autowired
    private SpringDataStorageKeyAllocationJpaRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> createdAllocationIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void tearDown() {
        for (UUID id : createdAllocationIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_upload_sessions WHERE storage_allocation_id = ?", id.toString());
                jdbcTemplate.update("DELETE FROM media_storage_key_allocations WHERE id = ?", id.toString());
            } catch (Exception ignored) {
            }
        }
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
    @DisplayName("1. Reserve -> INSERT -> reload")
    void shouldReserveInsertAndReload() {
        StorageProviderId providerId = StorageProviderId.of("b2");
        StorageKey storageKey = StorageKey.of("test/video_" + UUID.randomUUID() + ".mp4");
        UUID ownerId = UUID.randomUUID();

        StorageKeyAllocation allocation = service.reserveKey(
                providerId, "kiemlai-video", storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION, ownerId
        );
        createdAllocationIds.add(allocation.getId());

        assertThat(allocation.getId()).isNotNull();
        assertThat(allocation.getStatus()).isEqualTo(StorageKeyAllocationStatus.RESERVED);

        Optional<StorageKeyAllocation> reloaded = adapter.findById(allocation.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getId()).isEqualTo(allocation.getId());
        assertThat(reloaded.get().getProviderId()).isEqualTo(providerId);
        assertThat(reloaded.get().getStorageKey()).isEqualTo(storageKey);
        assertThat(reloaded.get().getStatus()).isEqualTo(StorageKeyAllocationStatus.RESERVED);
        assertThat(reloaded.get().getPersistenceVersion()).isNotNull();
    }

    @Test
    @DisplayName("2. Commit -> UPDATE -> reload, same allocation ID")
    void shouldCommitUpdateAndReloadSameId() {
        StorageProviderId providerId = StorageProviderId.of("b2");
        StorageKey storageKey = StorageKey.of("test/video_" + UUID.randomUUID() + ".mp4");
        UUID ownerId = UUID.randomUUID();

        StorageKeyAllocation allocation = service.reserveKey(
                providerId, "kiemlai-video", storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION, ownerId
        );
        UUID allocId = allocation.getId();
        createdAllocationIds.add(allocId);

        UUID targetVersionId = UUID.randomUUID();
        StorageKeyAllocation committed = service.commitAllocation(
                allocId, StorageKeyAllocationType.ASSET_VERSION, targetVersionId
        );

        assertThat(committed.getId()).isEqualTo(allocId);
        assertThat(committed.getStatus()).isEqualTo(StorageKeyAllocationStatus.COMMITTED);
        assertThat(committed.getAllocatedByType()).isEqualTo(StorageKeyAllocationType.ASSET_VERSION);
        assertThat(committed.getAllocatedById()).isEqualTo(targetVersionId);

        Optional<StorageKeyAllocation> reloaded = adapter.findById(allocId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getId()).isEqualTo(allocId);
        assertThat(reloaded.get().getStatus()).isEqualTo(StorageKeyAllocationStatus.COMMITTED);
        assertThat(reloaded.get().getAllocatedById()).isEqualTo(targetVersionId);
    }

    @Test
    @DisplayName("3. Tombstone -> UPDATE -> reload")
    void shouldTombstoneUpdateAndReload() {
        StorageProviderId providerId = StorageProviderId.of("b2");
        StorageKey storageKey = StorageKey.of("test/video_" + UUID.randomUUID() + ".mp4");
        UUID ownerId = UUID.randomUUID();

        StorageKeyAllocation allocation = service.reserveKey(
                providerId, "kiemlai-video", storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION, ownerId
        );
        UUID allocId = allocation.getId();
        createdAllocationIds.add(allocId);

        service.tombstoneAllocation(allocId);

        Optional<StorageKeyAllocation> reloaded = adapter.findById(allocId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getId()).isEqualTo(allocId);
        assertThat(reloaded.get().getStatus()).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE);
    }

    @Test
    @DisplayName("4. Tombstoned key cannot be reserved again")
    void shouldPreventReservingTombstonedKeyAgain() {
        StorageProviderId providerId = StorageProviderId.of("b2");
        StorageKey storageKey = StorageKey.of("test/video_" + UUID.randomUUID() + ".mp4");
        UUID ownerId = UUID.randomUUID();

        StorageKeyAllocation allocation = service.reserveKey(
                providerId, "kiemlai-video", storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION, ownerId
        );
        createdAllocationIds.add(allocation.getId());

        service.tombstoneAllocation(allocation.getId());

        // Attempting to reserve the exact same provider/key must fail
        assertThatThrownBy(() -> service.reserveKey(
                providerId, "kiemlai-video", storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION, UUID.randomUUID()
        )).isInstanceOf(DuplicateStorageLocationException.class);
    }

    @Test
    @DisplayName("5. Duplicate storage key produces intended DuplicateStorageLocationException")
    void shouldProduceDuplicateStorageLocationExceptionOnCollision() {
        StorageProviderId providerId = StorageProviderId.of("b2");
        StorageKey storageKey = StorageKey.of("test/video_" + UUID.randomUUID() + ".mp4");
        UUID ownerId1 = UUID.randomUUID();
        UUID ownerId2 = UUID.randomUUID();

        StorageKeyAllocation first = service.reserveKey(
                providerId, "kiemlai-video", storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION, ownerId1
        );
        createdAllocationIds.add(first.getId());

        assertThatThrownBy(() -> service.reserveKey(
                providerId, "kiemlai-video", storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION, ownerId2
        )).isInstanceOf(DuplicateStorageLocationException.class);
    }

    @Test
    @DisplayName("6. Unrelated persistence failures remain distinguishable from duplicate storage location")
    void shouldDistinguishUnrelatedPersistenceFailures() {
        // Attempting to insert an allocation with an invalid allocation_status violates CHECK constraint
        UUID id = UUID.randomUUID();
        createdAllocationIds.add(id);

        assertCheckConstraintViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at, persistence_version
                ) VALUES (?, 'b2', 'kiemlai-video', 'objects/invalid_status.mp4', 'INVALID_STATUS', 'UPLOAD_SESSION', ?, NOW(6), NOW(6), 0)
                """, id.toString(), UUID.randomUUID().toString()),
                "chk_media_storage_key_allocations_status"
        );
    }

    @Test
    @DisplayName("7. Two concurrent attempts to reserve the same provider/key: exactly one succeeds")
    void shouldEnsureExactlyOneSucceedsInConcurrentReservation() throws InterruptedException, ExecutionException {
        StorageProviderId providerId = StorageProviderId.of("b2");
        StorageKey storageKey = StorageKey.of("test/video_concurrent_" + UUID.randomUUID() + ".mp4");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    StorageKeyAllocation alloc = service.reserveKey(
                            providerId, "kiemlai-video", storageKey,
                            StorageKeyAllocationType.UPLOAD_SESSION, UUID.randomUUID()
                    );
                    createdAllocationIds.add(alloc.getId());
                    successCount.incrementAndGet();
                } catch (DuplicateStorageLocationException e) {
                    duplicateCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(duplicateCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("8. Competing concurrent updates to the same allocation trigger optimistic locking protection")
    void shouldProtectAgainstConcurrentStaleUpdates() {
        StorageProviderId providerId = StorageProviderId.of("b2");
        StorageKey storageKey = StorageKey.of("test/video_" + UUID.randomUUID() + ".mp4");
        UUID ownerId = UUID.randomUUID();

        StorageKeyAllocation initial = service.reserveKey(
                providerId, "kiemlai-video", storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION, ownerId
        );
        createdAllocationIds.add(initial.getId());

        // First update advances persistence_version in DB
        UUID target1 = UUID.randomUUID();
        service.commitAllocation(initial.getId(), StorageKeyAllocationType.ASSET_VERSION, target1);

        // Attempting to save initial stale allocation (which has stale persistenceVersion) must fail
        StorageKeyAllocation stale = StorageKeyAllocation.rehydrate(
                initial.getId(), providerId, initial.getBucketName(), storageKey,
                StorageKeyAllocationStatus.COMMITTED,
                StorageKeyAllocationType.IMAGE_VARIANT,
                UUID.randomUUID(),
                initial.getCreatedAt(), initial.getUpdatedAt(),
                initial.getPersistenceVersion() // version 0, but DB is at 1
        );

        assertThatThrownBy(() -> adapter.save(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("9. Two simultaneous transactions reading the same RESERVED allocation: exactly one succeeds with optimistic locking failure")
    void shouldEnsureExactlyOneSucceedsInSimultaneousConflictingMutations() throws Exception {
        StorageProviderId providerId = StorageProviderId.of("b2");
        StorageKey storageKey = StorageKey.of("test/video_concurrent_tx_" + UUID.randomUUID() + ".mp4");
        UUID ownerId = UUID.randomUUID();

        StorageKeyAllocation initial = service.reserveKey(
                providerId, "kiemlai-video", storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION, ownerId
        );
        UUID allocId = initial.getId();
        createdAllocationIds.add(allocId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readLatch = new CountDownLatch(2);
            CountDownLatch startCommitLatch = new CountDownLatch(1);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger optimisticLockFailureCount = new AtomicInteger(0);

            UUID target1 = UUID.randomUUID();
            UUID target2 = UUID.randomUUID();

            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            List<Future<?>> futures = new ArrayList<>();

            // Transaction A: starts new TX, reads version 0, waits at read barrier inside TX, mutates and commits
            futures.add(executor.submit(() -> {
                try {
                    txTemplate.execute(status -> {
                        StorageKeyAllocation read1 = adapter.findById(allocId).orElseThrow();
                        assertThat(read1.getPersistenceVersion()).isEqualTo(0L);
                        readLatch.countDown();
                        try {
                            boolean ok = startCommitLatch.await(5, TimeUnit.SECONDS);
                            assertThat(ok).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        read1.commit(StorageKeyAllocationType.ASSET_VERSION, target1, Instant.now());
                        adapter.save(read1);
                        return null;
                    });
                    successCount.incrementAndGet();
                } catch (OptimisticLockingFailureException e) {
                    optimisticLockFailureCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));

            // Transaction B: starts new TX, reads version 0, waits at read barrier inside TX, mutates and commits
            futures.add(executor.submit(() -> {
                try {
                    txTemplate.execute(status -> {
                        StorageKeyAllocation read2 = adapter.findById(allocId).orElseThrow();
                        assertThat(read2.getPersistenceVersion()).isEqualTo(0L);
                        readLatch.countDown();
                        try {
                            boolean ok = startCommitLatch.await(5, TimeUnit.SECONDS);
                            assertThat(ok).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        read2.commit(StorageKeyAllocationType.ASSET_VERSION, target2, Instant.now());
                        adapter.save(read2);
                        return null;
                    });
                    successCount.incrementAndGet();
                } catch (OptimisticLockingFailureException e) {
                    optimisticLockFailureCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));

            // Wait for both independent transactions to read version 0
            boolean readOk = readLatch.await(5, TimeUnit.SECONDS);
            assertThat(readOk).isTrue();

            // Release both transactions simultaneously to commit their pre-read mutations
            startCommitLatch.countDown();

            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

            assertThat(successCount.get()).isEqualTo(1);
            assertThat(optimisticLockFailureCount.get()).isEqualTo(1);

            // Verify final persisted state is coherent and version is advanced
            StorageKeyAllocation finalAlloc = adapter.findById(allocId).orElseThrow();
            assertThat(finalAlloc.getStatus()).isEqualTo(StorageKeyAllocationStatus.COMMITTED);
            assertThat(finalAlloc.getPersistenceVersion()).isEqualTo(1L);
            assertThat(finalAlloc.getAllocatedById()).isIn(target1, target2);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("10. Nine mandatory storage-key and bucket integrity cases")
    void shouldEnforceAllNineStorageKeyAndBucketIntegrityCases() {
        StorageProviderId b2Provider = StorageProviderId.of("b2");
        StorageKey b2Key = StorageKey.of("test/video_fk_" + UUID.randomUUID() + ".mp4");
        UUID ownerId = UUID.randomUUID();

        StorageKeyAllocation allocation = service.reserveKey(
                b2Provider, "kiemlai-video", b2Key,
                StorageKeyAllocationType.UPLOAD_SESSION, ownerId
        );
        UUID allocId = allocation.getId();
        createdAllocationIds.add(allocId);

        UUID validSessionId = UUID.randomUUID();

        // Case 1: B2 allocation with real bucket + matching session bucket: ACCEPT
        jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-1', 'screen', 'screen_entry', ?,
                    'idemp-case-1', 'VIDEO', 'video/mp4', 104857600,
                    'b2', 'kiemlai-video', ?, ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', NOW(6) + INTERVAL 1 DAY, 'NONE', 0,
                    NOW(6), NOW(6), 0
                )
                """,
                validSessionId.toString(),
                UUID.randomUUID().toString(),
                b2Key.value(),
                allocId.toString()
        );

        // Case 2: B2 allocation with real bucket + different session bucket: REJECT (composite FK mismatch using unclaimed allocation)
        StorageKey badBucketAllocKey = StorageKey.of("test/video_badbucket_" + UUID.randomUUID() + ".mp4");
        StorageKeyAllocation badBucketAlloc = service.reserveKey(
                b2Provider, "kiemlai-video", badBucketAllocKey,
                StorageKeyAllocationType.UPLOAD_SESSION, UUID.randomUUID()
        );
        createdAllocationIds.add(badBucketAlloc.getId());

        UUID badBucketSessionId = UUID.randomUUID();
        assertForeignKeyViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-1', 'screen', 'screen_entry', ?,
                    'idemp-case-2', 'VIDEO', 'video/mp4', 104857600,
                    'b2', 'other-bucket', ?, ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', NOW(6) + INTERVAL 1 DAY, 'NONE', 0,
                    NOW(6), NOW(6), 0
                )
                """,
                badBucketSessionId.toString(),
                UUID.randomUUID().toString(),
                badBucketAllocKey.value(),
                badBucketAlloc.getId().toString()
        ), "fk_media_upload_sessions_allocation_location");

        // Case 3: B2 allocation with real bucket + NULL session bucket: REJECT (NOT NULL column using unclaimed allocation)
        StorageKey nullBucketAllocKey = StorageKey.of("test/video_nullbucket_" + UUID.randomUUID() + ".mp4");
        StorageKeyAllocation nullBucketAlloc = service.reserveKey(
                b2Provider, "kiemlai-video", nullBucketAllocKey,
                StorageKeyAllocationType.UPLOAD_SESSION, UUID.randomUUID()
        );
        createdAllocationIds.add(nullBucketAlloc.getId());

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
                    ?, 'user-1', 'screen', 'screen_entry', ?,
                    'idemp-case-3', 'VIDEO', 'video/mp4', 104857600,
                    'b2', NULL, ?, ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', NOW(6) + INTERVAL 1 DAY, 'NONE', 0,
                    NOW(6), NOW(6), 0
                )
                """,
                nullBucketSessionId.toString(),
                UUID.randomUUID().toString(),
                nullBucketAllocKey.value(),
                nullBucketAlloc.getId().toString()
        ), "storage_bucket_name");

        // Case 4: B2 allocation with NULL/sentinel bucket: REJECT
        // 4a. Domain level rejection of sentinel for B2
        assertThatThrownBy(() -> service.reserveKey(
                b2Provider, "NONE", StorageKey.of("test/video_b2_none.mp4"),
                StorageKeyAllocationType.UPLOAD_SESSION, UUID.randomUUID()
        )).isInstanceOf(IllegalArgumentException.class);

        // 4b. Schema level rejection: B2 allocation with NULL bucket fails NOT NULL
        UUID nullBucketAllocId = UUID.randomUUID();
        assertNotNullViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at, persistence_version
                ) VALUES (?, 'b2', NULL, 'test/video_b2_null.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, NOW(6), NOW(6), 0)
                """, nullBucketAllocId.toString(), UUID.randomUUID().toString()),
                "storage_bucket_name"
        );

        // 4c. Schema level rejection: B2 allocation with 'NONE' bucket fails chk_media_storage_key_allocations_b2_bucket
        UUID noneBucketAllocId = UUID.randomUUID();
        assertCheckConstraintViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at, persistence_version
                ) VALUES (?, 'b2', 'NONE', 'test/video_b2_none.mp4', 'RESERVED', 'UPLOAD_SESSION', ?, NOW(6), NOW(6), 0)
                """, noneBucketAllocId.toString(), UUID.randomUUID().toString()),
                "chk_media_storage_key_allocations_b2_bucket"
        );

        // Case 5: Session referencing different provider: REJECT (composite FK mismatch using unclaimed allocation)
        StorageKey badProviderAllocKey = StorageKey.of("test/video_badprovider_" + UUID.randomUUID() + ".mp4");
        StorageKeyAllocation badProviderAlloc = service.reserveKey(
                b2Provider, "kiemlai-video", badProviderAllocKey,
                StorageKeyAllocationType.UPLOAD_SESSION, UUID.randomUUID()
        );
        createdAllocationIds.add(badProviderAlloc.getId());

        UUID badProviderSessionId = UUID.randomUUID();
        assertForeignKeyViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-1', 'screen', 'screen_entry', ?,
                    'idemp-case-5', 'VIDEO', 'video/mp4', 104857600,
                    's3', 'kiemlai-video', ?, ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', NOW(6) + INTERVAL 1 DAY, 'NONE', 0,
                    NOW(6), NOW(6), 0
                )
                """,
                badProviderSessionId.toString(),
                UUID.randomUUID().toString(),
                badProviderAllocKey.value(),
                badProviderAlloc.getId().toString()
        ), "fk_media_upload_sessions_allocation_location");

        // Case 6: Session referencing different key: REJECT (composite FK mismatch using unclaimed allocation)
        StorageKey badKeyAllocKey = StorageKey.of("test/video_badkey_" + UUID.randomUUID() + ".mp4");
        StorageKeyAllocation badKeyAlloc = service.reserveKey(
                b2Provider, "kiemlai-video", badKeyAllocKey,
                StorageKeyAllocationType.UPLOAD_SESSION, UUID.randomUUID()
        );
        createdAllocationIds.add(badKeyAlloc.getId());

        UUID badKeySessionId = UUID.randomUUID();
        assertForeignKeyViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-1', 'screen', 'screen_entry', ?,
                    'idemp-case-6', 'VIDEO', 'video/mp4', 104857600,
                    'b2', 'kiemlai-video', 'objects/mismatched_key.mp4', ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', NOW(6) + INTERVAL 1 DAY, 'NONE', 0,
                    NOW(6), NOW(6), 0
                )
                """,
                badKeySessionId.toString(),
                UUID.randomUUID().toString(),
                badKeyAlloc.getId().toString()
        ), "fk_media_upload_sessions_allocation_location");

        // Case 7: Duplicate sessions claiming one allocation: REJECT (UQ uq_media_upload_sessions_allocation)
        UUID secondSessionId = UUID.randomUUID();
        assertUniqueConstraintViolation(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, cleanup_status, cleanup_retry_count,
                    created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'user-2', 'screen', 'screen_entry', ?,
                    'idemp-case-7', 'VIDEO', 'video/mp4', 104857600,
                    'b2', 'kiemlai-video', ?, ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', NOW(6) + INTERVAL 1 DAY, 'NONE', 0,
                    NOW(6), NOW(6), 0
                )
                """,
                secondSessionId.toString(),
                UUID.randomUUID().toString(),
                b2Key.value(),
                allocId.toString()
        ), "uq_media_upload_sessions_allocation");

        // Case 8: Two bucketless allocations with same provider and key: REJECT (UQ uq_media_storage_key_allocations_provider_key)
        StorageProviderId localProvider = StorageProviderId.of("local");
        StorageKey sharedLocalKey = StorageKey.of("test/video_local_shared_" + UUID.randomUUID() + ".mp4");
        StorageKeyAllocation firstLocalAlloc = service.reserveKey(
                localProvider, null, sharedLocalKey,
                StorageKeyAllocationType.UPLOAD_SESSION, UUID.randomUUID()
        );
        createdAllocationIds.add(firstLocalAlloc.getId());

        assertThatThrownBy(() -> service.reserveKey(
                localProvider, null, sharedLocalKey,
                StorageKeyAllocationType.UPLOAD_SESSION, UUID.randomUUID()
        )).isInstanceOf(DuplicateStorageLocationException.class);

        // Case 9: Valid canonical bucketless-provider allocation ('local', 'NONE') + matching session: ACCEPT
        StorageKey independentLocalKey = StorageKey.of("test/video_local_indep_" + UUID.randomUUID() + ".mp4");
        StorageKeyAllocation validBucketlessAlloc = service.reserveKey(
                localProvider, null, independentLocalKey,
                StorageKeyAllocationType.UPLOAD_SESSION, UUID.randomUUID()
        );
        createdAllocationIds.add(validBucketlessAlloc.getId());
        assertThat(validBucketlessAlloc.getBucketName()).isEqualTo(StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL);

        UUID localSessionId = UUID.randomUUID();
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
                    'idemp-case-9', 'VIDEO', 'video/mp4', 104857600,
                    'local', 'NONE', ?, ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', NOW(6) + INTERVAL 1 DAY, 'NONE', 0,
                    NOW(6), NOW(6), 0
                )
                """,
                localSessionId.toString(),
                UUID.randomUUID().toString(),
                independentLocalKey.value(),
                validBucketlessAlloc.getId().toString()
        );

        // Specifying a non-sentinel bucket against a bucketless allocation is rejected by composite FK (using unclaimed bucketless allocation)
        StorageKey unclaimedLocalMismatchKey = StorageKey.of("test/video_local_badbucket_" + UUID.randomUUID() + ".mp4");
        StorageKeyAllocation unclaimedLocalAlloc = service.reserveKey(
                localProvider, null, unclaimedLocalMismatchKey,
                StorageKeyAllocationType.UPLOAD_SESSION, UUID.randomUUID()
        );
        createdAllocationIds.add(unclaimedLocalAlloc.getId());

        UUID badBucketLocalSessionId = UUID.randomUUID();
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
                    'idemp-case-9-mismatch', 'VIDEO', 'video/mp4', 104857600,
                    'local', 'unexpected-bucket', ?, ?,
                    NULL, 26214400, 4, NULL,
                    'clip.mp4', NULL, 'ALLOCATED', NOW(6) + INTERVAL 1 DAY, 'NONE', 0,
                    NOW(6), NOW(6), 0
                )
                """,
                badBucketLocalSessionId.toString(),
                UUID.randomUUID().toString(),
                unclaimedLocalMismatchKey.value(),
                unclaimedLocalAlloc.getId().toString()
        ), "fk_media_upload_sessions_allocation_location");

        // Clean up created sessions
        jdbcTemplate.update("DELETE FROM media_upload_sessions WHERE id = ?", localSessionId.toString());
        jdbcTemplate.update("DELETE FROM media_upload_sessions WHERE id = ?", validSessionId.toString());
    }
}
