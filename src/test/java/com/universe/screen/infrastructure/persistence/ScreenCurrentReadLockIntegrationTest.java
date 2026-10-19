package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Physical MySQL Integration Tests for Screen Video Reference Current-Read & Locking.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Native SQL {@code SELECT id FROM screen_entries WHERE video_media_asset_id = ? FOR UPDATE}
 *       acquires physical exclusive row/gap locks in MySQL InnoDB.</li>
 *   <li>Under MySQL {@code REPEATABLE READ}, an ordinary MVCC read uses an existing snapshot
 *       and misses concurrent attachments, while {@code findEntryIdsByVideoMediaAssetIdForUpdate}
 *       is an InnoDB current-read that breaks through the snapshot to observe committed references.</li>
 *   <li>Reference visibility is immediate after concurrent attach transaction commits.</li>
 *   <li>Targeted cleanup ensures safety against guarded test database (no DROP/CREATE/reset).</li>
 * </ul>
 *
 * <p>SAFETY: This test is designed and compiled for isolated verification, but is NOT executed during offline preflight.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(value = 15, unit = TimeUnit.SECONDS)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@EntityScan(basePackages = {
        "com.universe.screen.infrastructure.persistence",
        "com.universe.media.infrastructure.persistence"
})
@EnableJpaRepositories(basePackages = {
        "com.universe.screen.infrastructure.persistence",
        "com.universe.media.infrastructure.persistence"
})
@Import({
        ScreenEntryPersistenceAdapter.class,
        ScreenTitlePersistenceAdapter.class,
        ScreenCurrentReadLockIntegrationTest.TestWiringConfig.class
})
@DisplayName("Screen Video Reference Current-Read Physical MySQL Integration Tests")
class ScreenCurrentReadLockIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ScreenEntryRepositoryPort entryRepository;

    private UUID titleId;
    private UUID entryId;
    private UUID videoAssetId;

    @BeforeEach
    void setUp() {
        titleId = UUID.randomUUID();
        entryId = UUID.randomUUID();
        videoAssetId = UUID.randomUUID();

        cleanup();

        // 1. Seed media_assets row for candidate video
        jdbcTemplate.update("""
                INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, client_tag, created_at, updated_at)
                VALUES (?, 'VIDEO', 'PRIVATE', 'ACTIVE', 1, 'test-tag', NOW(), NOW())
                """, videoAssetId.toString());

        // 2. Seed screen_titles row (aligned with V87 schema: title TEXT NOT NULL, status VARCHAR(20) NOT NULL)
        jdbcTemplate.update("""
                INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at)
                VALUES (?, ?, 'Current Read Test Title', 'SERIES', 'LIVE_ACTION', 'DRAFT', NOW(), NOW())
                """, titleId.toString(), "title-" + titleId.toString().substring(0, 8));
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        if (entryId != null) {
            jdbcTemplate.update("DELETE FROM screen_entries WHERE id = ?", entryId.toString());
        }
        if (titleId != null) {
            jdbcTemplate.update("DELETE FROM screen_titles WHERE id = ?", titleId.toString());
        }
        if (videoAssetId != null) {
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", videoAssetId.toString());
        }
    }

    @Test
    @DisplayName("MySQL Current-Read: acquires exclusive row lock on referencing ScreenEntry blocking NOWAIT probe")
    void shouldAcquirePessimisticRowLockOnReferencingEntry() throws Exception {
        // Seed referencing screen_entries row
        jdbcTemplate.update("""
                INSERT INTO screen_entries (id, title_id, slug, type, number, title, video_media_asset_id, status, created_at, updated_at)
                VALUES (?, ?, 'ep-1', 'EPISODE', 1, 'Episode 1', ?, 'DRAFT', NOW(), NOW())
                """, entryId.toString(), titleId.toString(), videoAssetId.toString());

        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseTxLatch = new CountDownLatch(1);
        AtomicBoolean nowaitBlocked = new AtomicBoolean(false);
        AtomicReference<Throwable> backgroundError = new AtomicReference<>();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Thread holdingThread = new Thread(() -> {
            try {
                txTemplate.execute(status -> {
                    try {
                        List<UUID> referencingIds = entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoAssetId);
                        assertThat(referencingIds).containsExactly(entryId);
                    } finally {
                        lockAcquiredLatch.countDown();
                    }

                    try {
                        boolean released = releaseTxLatch.await(5, TimeUnit.SECONDS);
                        if (!released) {
                            throw new IllegalStateException("Timed out waiting for test completion");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return null;
                });
            } catch (Throwable t) {
                backgroundError.set(t);
            } finally {
                lockAcquiredLatch.countDown();
            }
        }, "holdingThread-lock-test");

        holdingThread.start();

        try {
            boolean acquired = lockAcquiredLatch.await(5, TimeUnit.SECONDS);
            assertThat(acquired).as("Timed out waiting for lock acquisition in background worker").isTrue();
            if (backgroundError.get() != null) {
                throw new AssertionError("Background worker failed to acquire lock or execute query", backgroundError.get());
            }

            // Independent probe attempting NOWAIT on the locked screen_entries row
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT id FROM screen_entries WHERE video_media_asset_id = ? FOR UPDATE NOWAIT")) {
                    stmt.setString(1, videoAssetId.toString());
                    stmt.executeQuery();
                    throw new AssertionError("Expected ER_LOCK_NOWAIT (3572) but query succeeded!");
                } catch (SQLException e) {
                    if (!isLockNowaitError(e)) {
                        throw new AssertionError("Unexpected database error (expected MySQL error code 3572 ER_LOCK_NOWAIT, got "
                                + e.getErrorCode() + "): " + e.getMessage(), e);
                    }
                    nowaitBlocked.set(true);
                } finally {
                    conn.rollback();
                }
            }
        } finally {
            releaseTxLatch.countDown();
            holdingThread.join(5000);
            if (holdingThread.isAlive()) {
                holdingThread.interrupt();
                holdingThread.join(1000);
            }
            assertThat(holdingThread.isAlive()).as("Worker holdingThread must be terminated").isFalse();
        }

        assertThat(nowaitBlocked.get()).isTrue();
        if (backgroundError.get() != null) {
            throw new AssertionError("Background worker encountered error", backgroundError.get());
        }
    }

    @Test
    @DisplayName("MySQL REPEATABLE READ: locking current-read breaks through stale snapshot to observe committed reference")
    void shouldBreakThroughStaleRepeatableReadSnapshotWithCurrentRead() throws Exception {
        // Initially, NO entry references videoAssetId in screen_entries.

        DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
        txDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txDef.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        TransactionTemplate txA = new TransactionTemplate(transactionManager, txDef);
        TransactionTemplate txB = new TransactionTemplate(transactionManager);
        txB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch txAStartedLatch = new CountDownLatch(1);
        CountDownLatch txBCommittedLatch = new CountDownLatch(1);
        CountDownLatch txACompleteLatch = new CountDownLatch(1);
        AtomicReference<Throwable> txAError = new AtomicReference<>();

        Thread threadA = new Thread(() -> {
            try {
                txA.execute(status -> {
                    try {
                        // Step 1: Initial non-locking read creates REPEATABLE READ snapshot
                        Integer countBefore = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM screen_entries WHERE video_media_asset_id = ?",
                                Integer.class,
                                videoAssetId.toString()
                        );
                        assertThat(countBefore).isEqualTo(0);
                    } finally {
                        txAStartedLatch.countDown();
                    }

                    // Wait for Transaction B to attach and commit
                    try {
                        if (!txBCommittedLatch.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting for TxB commit");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }

                    // Step 2: Ordinary non-locking MVCC query still uses old snapshot under REPEATABLE READ!
                    Integer countAfterSnapshot = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM screen_entries WHERE video_media_asset_id = ?",
                            Integer.class,
                            videoAssetId.toString()
                    );
                    assertThat(countAfterSnapshot)
                            .as("Under REPEATABLE READ, ordinary SELECT sees old snapshot where count is 0")
                            .isEqualTo(0);

                    // Step 3: Current-read (FOR UPDATE) breaks through snapshot to read latest committed data!
                    List<UUID> currentReadIds = entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoAssetId);
                    assertThat(currentReadIds)
                            .as("Pessimistic locking current-read sees newly committed entry reference!")
                            .containsExactly(entryId);

                    return null;
                });
            } catch (Throwable t) {
                txAError.set(t);
            } finally {
                txAStartedLatch.countDown();
                txACompleteLatch.countDown();
            }
        }, "threadA-repeatable-read-test");

        threadA.start();

        try {
            boolean txAStarted = txAStartedLatch.await(5, TimeUnit.SECONDS);
            assertThat(txAStarted).as("Timed out waiting for TxA to establish snapshot").isTrue();
            if (txAError.get() != null) {
                throw new AssertionError("Transaction A failed to establish initial snapshot", txAError.get());
            }

            // Transaction B: Attach asset to entry and commit
            txB.execute(status -> {
                jdbcTemplate.update("""
                        INSERT INTO screen_entries (id, title_id, slug, type, number, title, video_media_asset_id, status, created_at, updated_at)
                        VALUES (?, ?, 'ep-1', 'EPISODE', 1, 'Episode 1', ?, 'DRAFT', NOW(), NOW())
                        """, entryId.toString(), titleId.toString(), videoAssetId.toString());
                return null;
            });

            txBCommittedLatch.countDown();

            boolean txACompleted = txACompleteLatch.await(5, TimeUnit.SECONDS);
            assertThat(txACompleted).as("Timed out waiting for TxA completion").isTrue();
        } finally {
            txBCommittedLatch.countDown();
            threadA.join(5000);
            if (threadA.isAlive()) {
                threadA.interrupt();
                threadA.join(1000);
            }
            assertThat(threadA.isAlive()).as("Worker threadA must be terminated").isFalse();
        }

        if (txAError.get() != null) {
            throw new AssertionError("Transaction A failed", txAError.get());
        }
    }

    @Test
    @DisplayName("MySQL Reference Visibility: returns empty list when asset is unattached")
    void shouldReturnEmptyListWhenAssetIsUnattached() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.execute(status -> {
            List<UUID> referencingIds = entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoAssetId);
            assertThat(referencingIds).isEmpty();
            return null;
        });
    }

    private static boolean isLockNowaitError(SQLException e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof SQLException sqlEx) {
                if (sqlEx.getErrorCode() == 3572) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    @Configuration
    static class TestWiringConfig {
    }
}
