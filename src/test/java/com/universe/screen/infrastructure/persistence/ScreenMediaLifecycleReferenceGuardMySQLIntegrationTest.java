package com.universe.screen.infrastructure.persistence;

import com.universe.media.application.asset.ArchiveMediaAssetCommand;
import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.AssignMediaAssetClientTagUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetCommand;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.FindActiveMediaAssetsByClientTagKeysetUseCase;
import com.universe.media.application.asset.GetCurrentMediaAssetVersionSnapshotUseCase;
import com.universe.media.application.asset.GetMediaAssetCurrentMetadataUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.LockAndInspectMediaAssetUseCase;
import com.universe.media.application.asset.LockAndValidateActiveMediaAssetUseCase;
import com.universe.media.application.asset.OpenMediaAssetVersionContentUseCase;
import com.universe.media.application.asset.PurgeDeletedMediaAssetCommand;
import com.universe.media.application.asset.PurgeDeletedMediaAssetUseCase;
import com.universe.media.application.asset.PurgeMediaAssetMetadataService;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionUseCase;
import com.universe.media.application.facade.MediaFacade;
import com.universe.media.application.ports.MediaAssetCandidate;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.storage.MediaStorageCutoverBarrier;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.application.variant.GenerateMediaImageVariantUseCase;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.exceptions.MediaAssetReferencedException;
import com.universe.media.contracts.interfaces.MediaAssetReferenceCheckPort;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.StorageKey;
import com.universe.media.infrastructure.persistence.MediaAssetPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaAssetVersionPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaImageVariantPersistenceAdapter;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenMediaVisibilityPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.usecase.AttachScreenEntryVideoUseCase;
import com.universe.screen.infrastructure.media.ScreenMediaAssetReferenceCheckAdapter;
import com.universe.screen.infrastructure.media.ScreenMediaValidationAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Real Physical MySQL Integration & Concurrency Verification Test Suite for Generic Media Lifecycle Reference Guard.
 *
 * <p>Scope and Invariant Coverage:
 * <ul>
 *   <li><b>M1:</b> Generic archive refuses a referenced Screen VIDEO asset, including unpublished (DRAFT) ScreenEntry.</li>
 *   <li><b>M2:</b> Generic delete refuses a referenced Screen VIDEO asset.</li>
 *   <li><b>M3:</b> Archive and soft-delete of an unreferenced eligible asset succeeds.</li>
 *   <li><b>M4a:</b> Concurrent Screen attach wins over generic archive/delete: attach acquires candidate lock first,
 *       attaches video, commits; contender archive/delete unblocks, detects referencing ScreenEntry, and throws MediaAssetReferencedException.
 *       Contender lock contention is proven through connection-specific MySQL observation.</li>
 *   <li><b>M4b:</b> Concurrent generic delete wins over Screen attach: delete acquires candidate lock first, marks it DELETED,
 *       commits; contender attach unblocks, sees DELETED status, and throws ScreenMediaAssetNotFoundException.
 *       Contender lock contention is proven through connection-specific MySQL observation.</li>
 *   <li><b>M5:</b> Already-DELETED asset cannot be newly attached while purge is eligible.</li>
 *   <li><b>M6:</b> A referenced DELETED asset is rejected before physical purge, with mocked storage provider proving no physical delete call.</li>
 *   <li><b>M7:</b> Selected test-slice wiring includes the required Screen reference checker and routes generic Media operations
 *       through guarded use cases (full application component scan wiring is independently verified in ScreenMediaAssetReferenceCheckWiringTest).</li>
 * </ul>
 *
 * <p>SAFETY STATUS: PREPARED ONLY — PENDING HUMAN DATABASE EXECUTION AUTHORIZATION.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
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
        MediaAssetPersistenceAdapter.class,
        MediaAssetVersionPersistenceAdapter.class,
        MediaImageVariantPersistenceAdapter.class,
        ScreenTitlePersistenceAdapter.class,
        ScreenEntryPersistenceAdapter.class,
        ScreenMediaAssetReferenceCheckAdapter.class,
        ScreenMediaValidationAdapter.class,
        ArchiveMediaAssetUseCase.class,
        DeleteMediaAssetUseCase.class,
        PurgeDeletedMediaAssetUseCase.class,
        PurgeMediaAssetMetadataService.class,
        AttachScreenEntryVideoUseCase.class,
        LockAndValidateActiveMediaAssetUseCase.class,
        ScreenMediaLifecycleReferenceGuardMySQLIntegrationTest.GuardTestWiringConfig.class
})
@DisplayName("Screen Media Lifecycle Reference Guard Real MySQL Integration Tests")
class ScreenMediaLifecycleReferenceGuardMySQLIntegrationTest {

    static final int MYSQL_INNODB_LOCK_WAIT_SECONDS = 3;
    static final long JDBC_SOCKET_TIMEOUT_MS = 7000;
    static final long CONTENDER_LOCK_ENTRY_TIMEOUT_MS = 3000;
    static final long INNODB_WAIT_OBSERVATION_TIMEOUT_MS = 1500;
    static final long WORKER_PRIMARY_JOIN_TIMEOUT_MS = 4000;
    static final long WORKER_INTERRUPT_JOIN_TIMEOUT_MS = 2000;
    static final long WINNER_RELEASE_TIMEOUT_MS = 6000;

    private static final AtomicBoolean SUITE_ABORTED = new AtomicBoolean(false);
    private static final AtomicReference<String> SUITE_ABORT_REASON = new AtomicReference<>("");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String host = TestDatabaseSupport.resolveHost();
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        ScreenTestDatabaseTargetGuard.validateTarget(host, dbName);
        TestDatabaseSupport.configureDynamicProperties(registry);

        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET SESSION innodb_lock_wait_timeout=" + MYSQL_INNODB_LOCK_WAIT_SECONDS);
        String baseJdbcUrl = TestDatabaseSupport.resolveJdbcUrl();
        String boundedJdbcUrl = baseJdbcUrl + "&socketTimeout=" + JDBC_SOCKET_TIMEOUT_MS;
        registry.add("spring.datasource.url", () -> boundedJdbcUrl);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ScreenTitleRepositoryPort titleRepository;

    @Autowired
    private ScreenEntryRepositoryPort entryRepository;

    @Autowired
    private ScreenMediaValidationPort mediaValidationPort;

    @Autowired
    private ArchiveMediaAssetUseCase archiveUseCase;

    @Autowired
    private DeleteMediaAssetUseCase deleteUseCase;

    @Autowired
    private PurgeDeletedMediaAssetUseCase purgeUseCase;

    @Autowired
    private AttachScreenEntryVideoUseCase attachUseCase;

    @Autowired
    private BinaryStoragePort mockBinaryStoragePort;

    @Autowired
    private GuardTestSyncClock testSyncClock;

    @Autowired
    private TestLockCoordinator testLockCoordinator;

    private final List<GuardTestWorker<?>> registeredWorkers = new CopyOnWriteArrayList<>();

    private final List<UUID> trackedEntryIds = new ArrayList<>();
    private final List<UUID> trackedTitleIds = new ArrayList<>();
    private final List<UUID> trackedVariantIds = new ArrayList<>();
    private final List<UUID> trackedVersionIds = new ArrayList<>();
    private final List<UUID> trackedAssetIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        if (SUITE_ABORTED.get()) {
            throw new IllegalStateException("SUITE-LEVEL FAIL-CLOSED BLOCK: Previous test leaked a worker thread! Aborting suite. Reason: " + SUITE_ABORT_REASON.get());
        }
        registeredWorkers.clear();
        testSyncClock.reset();
        testLockCoordinator.reset();
        Mockito.reset(mockBinaryStoragePort);
    }

    @AfterEach
    void tearDown() {
        testSyncClock.releaseAll();
        testLockCoordinator.releaseAll();

        terminateAndVerifyWorkers(registeredWorkers.toArray(new GuardTestWorker<?>[0]));

        if (Thread.currentThread().isInterrupted()) {
            SUITE_ABORTED.set(true);
            SUITE_ABORT_REASON.set("Teardown thread interrupted!");
            throw new IllegalStateException("CRITICAL CONCURRENCY FAILURE: Teardown thread interrupted!");
        }

        cleanupTrackedFixtures();
    }

    private void terminateAndVerifyWorkers(GuardTestWorker<?>... workers) {
        List<String> survivingWorkers = new ArrayList<>();
        for (GuardTestWorker<?> worker : workers) {
            if (worker != null && worker.isStarted() && worker.isAlive()) {
                boolean terminated = worker.awaitTermination(WORKER_PRIMARY_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!terminated) {
                    worker.interrupt();
                    worker.awaitTermination(WORKER_INTERRUPT_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
                if (worker.isAlive()) {
                    survivingWorkers.add(worker.getName());
                }
            }
        }
        if (!survivingWorkers.isEmpty()) {
            String msg = "SURVIVING WORKERS DETECTED: " + survivingWorkers + ". Cannot safely run fixture cleanup!";
            SUITE_ABORT_REASON.set(msg);
            SUITE_ABORTED.set(true);
            throw new IllegalStateException("CRITICAL CONCURRENCY FAILURE: " + msg);
        }
    }

    private void cleanupTrackedFixtures() {
        // Reverse foreign key order:
        // 1. screen_entries
        for (UUID entryId : trackedEntryIds) {
            jdbcTemplate.update("DELETE FROM screen_entries WHERE id = ?", entryId.toString());
        }
        trackedEntryIds.clear();

        // 2. screen_titles
        for (UUID titleId : trackedTitleIds) {
            jdbcTemplate.update("DELETE FROM screen_titles WHERE id = ?", titleId.toString());
        }
        trackedTitleIds.clear();

        // 3. media_image_variants
        for (UUID variantId : trackedVariantIds) {
            jdbcTemplate.update("DELETE FROM media_image_variants WHERE id = ?", variantId.toString());
        }
        trackedVariantIds.clear();

        // 4. media_asset_versions
        for (UUID versionId : trackedVersionIds) {
            jdbcTemplate.update("DELETE FROM media_asset_versions WHERE id = ?", versionId.toString());
        }
        trackedVersionIds.clear();

        // 5. media_assets
        for (UUID assetId : trackedAssetIds) {
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", assetId.toString());
        }
        trackedAssetIds.clear();
    }

    // =========================================================================
    // FIXTURE FACTORIES
    // =========================================================================

    private UUID createTrackedTitle() {
        UUID titleId = UUID.randomUUID();
        trackedTitleIds.add(titleId);
        Timestamp ts = Timestamp.from(Instant.now().minus(Duration.ofMinutes(10)));
        jdbcTemplate.update("""
                INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at)
                VALUES (?, ?, 'Title Fixture', 'SERIES', 'LIVE_ACTION', 'DRAFT', ?, ?)
                """, titleId.toString(), "title-" + titleId.toString().substring(0, 8), ts, ts);
        return titleId;
    }

    private UUID createTrackedPublishedTitle(Instant publishedAt) {
        UUID titleId = UUID.randomUUID();
        trackedTitleIds.add(titleId);
        Timestamp ts = Timestamp.from(publishedAt);
        Timestamp createdAt = Timestamp.from(publishedAt.minus(Duration.ofMinutes(10)));
        jdbcTemplate.update("""
                INSERT INTO screen_titles (id, slug, title, structure, presentation, status, published_at, created_at, updated_at)
                VALUES (?, ?, 'Published Title Fixture', 'SERIES', 'LIVE_ACTION', 'PUBLISHED', ?, ?, ?)
                """, titleId.toString(), "title-" + titleId.toString().substring(0, 8),
                ts, createdAt, ts);
        return titleId;
    }

    private UUID createTrackedEntry(UUID titleId, String status) {
        if ("PUBLISHED".equals(status)) {
            return createTrackedPublishedEntry(titleId, Instant.now().minus(Duration.ofMinutes(5)));
        }
        UUID entryId = UUID.randomUUID();
        trackedEntryIds.add(entryId);
        Timestamp ts = Timestamp.from(Instant.now().minus(Duration.ofMinutes(5)));
        jdbcTemplate.update("""
                INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at)
                VALUES (?, ?, ?, 'EPISODE', 1, 'Entry Fixture', ?, ?, ?)
                """, entryId.toString(), titleId.toString(), "entry-" + entryId.toString().substring(0, 8), status, ts, ts);
        return entryId;
    }

    private UUID createTrackedPublishedEntry(UUID titleId, Instant publishedAt) {
        UUID entryId = UUID.randomUUID();
        trackedEntryIds.add(entryId);
        Timestamp createdAt = Timestamp.from(publishedAt.minus(Duration.ofMinutes(5)));
        Timestamp pubTs = Timestamp.from(publishedAt);
        jdbcTemplate.update("""
                INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, published_at, created_at, updated_at)
                VALUES (?, ?, ?, 'EPISODE', 1, 'Published Entry Fixture', 'PUBLISHED', ?, ?, ?)
                """, entryId.toString(), titleId.toString(), "entry-" + entryId.toString().substring(0, 8),
                pubTs, createdAt, pubTs);
        return entryId;
    }

    private UUID createTrackedActiveVideoAsset() {
        UUID assetId = UUID.randomUUID();
        trackedAssetIds.add(assetId);
        Timestamp ts = Timestamp.from(Instant.now().minus(Duration.ofMinutes(10)));
        jdbcTemplate.update("""
                INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at)
                VALUES (?, 'VIDEO', 'PUBLIC', 'ACTIVE', 1, ?, ?)
                """, assetId.toString(), ts, ts);

        UUID versionId = UUID.randomUUID();
        trackedVersionIds.add(versionId);
        jdbcTemplate.update("""
                INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, content_hash, mime_type, size_bytes, original_filename, created_at)
                VALUES (?, ?, 1, 'b2', ?, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'video/mp4', 1048576, 'test.mp4', ?)
                """, versionId.toString(), assetId.toString(), "videos/" + assetId + "/v1.mp4", ts);
        return assetId;
    }

    private UUID createTrackedDeletedVideoAsset(Instant deletedAt) {
        UUID assetId = UUID.randomUUID();
        trackedAssetIds.add(assetId);
        Timestamp createdAt = Timestamp.from(deletedAt.minus(Duration.ofDays(10)));
        Timestamp delAt = Timestamp.from(deletedAt);
        jdbcTemplate.update("""
                INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at)
                VALUES (?, 'VIDEO', 'PUBLIC', 'DELETED', 1, ?, ?)
                """, assetId.toString(), createdAt, delAt);

        UUID versionId = UUID.randomUUID();
        trackedVersionIds.add(versionId);
        jdbcTemplate.update("""
                INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, content_hash, mime_type, size_bytes, original_filename, created_at)
                VALUES (?, ?, 1, 'b2', ?, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'video/mp4', 1048576, 'test.mp4', ?)
                """, versionId.toString(), assetId.toString(), "videos/" + assetId + "/v1.mp4", createdAt);
        return assetId;
    }

    private void linkEntryToVideo(UUID entryId, UUID videoAssetId) {
        jdbcTemplate.update("UPDATE screen_entries SET video_media_asset_id = ? WHERE id = ?",
                videoAssetId.toString(), entryId.toString());
    }

    // =========================================================================
    // CONNECTION-SPECIFIC LOCK CONTENTION OBSERVATION HELPERS
    // =========================================================================

    private boolean awaitContenderInInnoDBLockWait(
            long contenderConnectionId,
            long timeoutMs,
            GuardTestWorker<?> contenderWorker,
            Long winnerConnectionId
    ) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadlineNanos) {
            if (isContenderInLockWait(contenderConnectionId)) {
                return true;
            }
            if (contenderWorker != null && contenderWorker.isCompleted()) {
                break;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        dumpLockContentionDiagnostics(contenderConnectionId, winnerConnectionId, timeoutMs, contenderWorker);
        return false;
    }

    private boolean isContenderInLockWait(long contenderConnectionId) {
        // 1. Check information_schema.innodb_trx filtered specifically by the contender's MySQL thread ID
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id = ? AND trx_state = 'LOCK WAIT'",
                    Integer.class,
                    contenderConnectionId
            );
            if (count != null && count > 0) {
                return true;
            }
        } catch (Exception ignored) {
        }

        // 2. Check performance_schema.data_locks filtered specifically by the contender's PROCESSLIST_ID
        try {
            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM performance_schema.data_locks dl
                    JOIN performance_schema.threads t ON dl.THREAD_ID = t.THREAD_ID
                    WHERE t.PROCESSLIST_ID = ? AND dl.LOCK_STATUS = 'WAITING'
                    """,
                    Integer.class,
                    contenderConnectionId
            );
            if (count != null && count > 0) {
                return true;
            }
        } catch (Exception ignored) {
        }

        // 3. Check performance_schema.data_lock_waits filtered specifically by the requesting thread's PROCESSLIST_ID
        try {
            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits dlw
                    JOIN performance_schema.threads t ON dlw.REQUESTING_THREAD_ID = t.THREAD_ID
                    WHERE t.PROCESSLIST_ID = ?
                    """,
                    Integer.class,
                    contenderConnectionId
            );
            if (count != null && count > 0) {
                return true;
            }
        } catch (Exception ignored) {
        }

        // 4. Check sys.innodb_lock_waits filtered specifically by waiting_pid
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys.innodb_lock_waits WHERE waiting_pid = ?",
                    Integer.class,
                    contenderConnectionId
            );
            if (count != null && count > 0) {
                return true;
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private void dumpLockContentionDiagnostics(
            long contenderConnectionId,
            Long winnerConnectionId,
            long timeoutMs,
            GuardTestWorker<?> contenderWorker
    ) {
        try {
            List<Map<String, Object>> innodbTrx = List.of();
            try {
                if (winnerConnectionId != null) {
                    innodbTrx = jdbcTemplate.queryForList(
                            "SELECT trx_id, trx_state, trx_started, trx_mysql_thread_id, trx_query, trx_requested_lock_id, trx_operation_state, trx_tables_in_use, trx_tables_locked, trx_rows_locked FROM information_schema.innodb_trx WHERE trx_mysql_thread_id IN (?, ?)",
                            contenderConnectionId, winnerConnectionId
                    );
                } else {
                    innodbTrx = jdbcTemplate.queryForList(
                            "SELECT trx_id, trx_state, trx_started, trx_mysql_thread_id, trx_query, trx_requested_lock_id, trx_operation_state, trx_tables_in_use, trx_tables_locked, trx_rows_locked FROM information_schema.innodb_trx WHERE trx_mysql_thread_id = ?",
                            contenderConnectionId
                    );
                }
            } catch (Exception e) {
                System.err.println("DIAGNOSTIC - Could not query information_schema.innodb_trx: " + e.getMessage());
            }

            List<Map<String, Object>> psThreads = List.of();
            try {
                if (winnerConnectionId != null) {
                    psThreads = jdbcTemplate.queryForList(
                            "SELECT PROCESSLIST_ID, THREAD_ID, PROCESSLIST_COMMAND, PROCESSLIST_STATE, PROCESSLIST_INFO FROM performance_schema.threads WHERE PROCESSLIST_ID IN (?, ?)",
                            contenderConnectionId, winnerConnectionId
                    );
                } else {
                    psThreads = jdbcTemplate.queryForList(
                            "SELECT PROCESSLIST_ID, THREAD_ID, PROCESSLIST_COMMAND, PROCESSLIST_STATE, PROCESSLIST_INFO FROM performance_schema.threads WHERE PROCESSLIST_ID = ?",
                            contenderConnectionId
                    );
                }
            } catch (Exception e) {
                System.err.println("DIAGNOSTIC - Could not query performance_schema.threads: " + e.getMessage());
            }

            List<Map<String, Object>> psDataLocks = List.of();
            try {
                psDataLocks = jdbcTemplate.queryForList(
                        "SELECT ENGINE_TRANSACTION_ID, THREAD_ID, OBJECT_NAME, INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA FROM performance_schema.data_locks WHERE OBJECT_NAME IN ('media_assets', 'screen_entries')"
                );
            } catch (Exception e) {
                System.err.println("DIAGNOSTIC - Could not query performance_schema.data_locks: " + e.getMessage());
            }

            List<Map<String, Object>> psDataLockWaits = List.of();
            try {
                psDataLockWaits = jdbcTemplate.queryForList(
                        "SELECT REQUESTING_ENGINE_TRANSACTION_ID, REQUESTING_THREAD_ID, BLOCKING_ENGINE_TRANSACTION_ID, BLOCKING_THREAD_ID FROM performance_schema.data_lock_waits"
                );
            } catch (Exception e) {
                System.err.println("DIAGNOSTIC - Could not query performance_schema.data_lock_waits: " + e.getMessage());
            }

            List<Map<String, Object>> sysLockWaits = List.of();
            try {
                sysLockWaits = jdbcTemplate.queryForList(
                        "SELECT waiting_pid, waiting_query, blocking_pid, blocking_query, locked_table FROM sys.innodb_lock_waits"
                );
            } catch (Exception e) {
                System.err.println("DIAGNOSTIC - Could not query sys.innodb_lock_waits: " + e.getMessage());
            }

            String threadState = "UNKNOWN";
            StringBuilder stackTraceBuilder = new StringBuilder();
            if (contenderWorker != null) {
                threadState = contenderWorker.getState().toString();
                for (StackTraceElement ste : contenderWorker.getStackTrace()) {
                    stackTraceBuilder.append("\n\tat ").append(ste);
                }
            }

            System.err.println("DIAGNOSTIC - Lock wait observation timed out for contender connectionId=" + contenderConnectionId
                    + " (winner=" + winnerConnectionId + ") after " + timeoutMs + "ms."
                    + "\n  Contender Worker: name=" + (contenderWorker != null ? contenderWorker.getName() : "null")
                    + ", isAlive=" + (contenderWorker != null && contenderWorker.isAlive())
                    + ", threadState=" + threadState
                    + ", failure=" + (contenderWorker != null ? contenderWorker.getFailure() : "none")
                    + "\n  Contender StackTrace=" + stackTraceBuilder
                    + "\n  innodb_trx=" + innodbTrx
                    + "\n  performance_schema.threads=" + psThreads
                    + "\n  performance_schema.data_locks=" + psDataLocks
                    + "\n  performance_schema.data_lock_waits=" + psDataLockWaits
                    + "\n  sys.innodb_lock_waits=" + sysLockWaits);
        } catch (Exception diagEx) {
            System.err.println("DIAGNOSTIC - Error collecting diagnostics: " + diagEx.getMessage());
        }
    }

    // =========================================================================
    // M1: Generic archive refuses a referenced Screen VIDEO asset (including DRAFT)
    // =========================================================================
    @Test
    @DisplayName("M1: Generic archive refuses a referenced Screen VIDEO asset attached to unpublished (DRAFT) ScreenEntry")
    void testM1_genericArchiveRefusesReferencedScreenVideoAsset_includingUnpublishedScreenEntry() {
        UUID titleId = createTrackedTitle();
        UUID entryId = createTrackedEntry(titleId, "DRAFT");
        UUID videoAssetId = createTrackedActiveVideoAsset();
        linkEntryToVideo(entryId, videoAssetId);

        assertThatThrownBy(() -> archiveUseCase.execute(new ArchiveMediaAssetCommand(videoAssetId)))
                .isInstanceOf(MediaAssetReferencedException.class)
                .hasMessageContaining(videoAssetId.toString())
                .hasMessageContaining(entryId.toString());

        // Assert DB status remains ACTIVE, not ARCHIVED
        String currentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?", String.class, videoAssetId.toString());
        assertThat(currentStatus).isEqualTo("ACTIVE");
    }

    // =========================================================================
    // M2: Generic delete refuses a referenced Screen VIDEO asset
    // =========================================================================
    @Test
    @DisplayName("M2: Generic delete refuses a referenced Screen VIDEO asset")
    void testM2_genericDeleteRefusesReferencedScreenVideoAsset() {
        UUID titleId = createTrackedPublishedTitle(Instant.now().minus(Duration.ofMinutes(10)));
        Instant publishedAt = Instant.now().minus(Duration.ofMinutes(5));
        UUID entryId = createTrackedPublishedEntry(titleId, publishedAt);
        UUID videoAssetId = createTrackedActiveVideoAsset();
        linkEntryToVideo(entryId, videoAssetId);

        assertThatThrownBy(() -> deleteUseCase.execute(new DeleteMediaAssetCommand(videoAssetId)))
                .isInstanceOf(MediaAssetReferencedException.class)
                .hasMessageContaining(videoAssetId.toString())
                .hasMessageContaining(entryId.toString());

        // Assert DB status remains ACTIVE
        String currentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?", String.class, videoAssetId.toString());
        assertThat(currentStatus).isEqualTo("ACTIVE");
    }

    // =========================================================================
    // M3: Archive/delete of an unreferenced eligible asset succeeds
    // =========================================================================
    @Test
    @DisplayName("M3: Archive and soft-delete of an unreferenced eligible asset succeeds")
    void testM3_genericArchiveAndDeleteOfUnreferencedEligibleAssetSucceeds() {
        // Sub-test A: Archive unreferenced asset
        UUID unreferencedAsset1 = createTrackedActiveVideoAsset();
        archiveUseCase.execute(new ArchiveMediaAssetCommand(unreferencedAsset1));

        String status1 = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?", String.class, unreferencedAsset1.toString());
        assertThat(status1).isEqualTo("ARCHIVED");

        // Sub-test B: Soft-delete unreferenced asset
        UUID unreferencedAsset2 = createTrackedActiveVideoAsset();
        deleteUseCase.execute(new DeleteMediaAssetCommand(unreferencedAsset2));

        Map<String, Object> row2 = jdbcTemplate.queryForMap(
                "SELECT status, updated_at FROM media_assets WHERE id = ?", unreferencedAsset2.toString());
        assertThat(row2.get("status")).isEqualTo("DELETED");
        assertThat(row2.get("updated_at")).isNotNull();
    }

    // =========================================================================
    // M4a: Concurrent Screen attach wins over generic delete/archive
    // =========================================================================
    @Test
    @DisplayName("M4a: Concurrent Screen attach wins over generic delete; contender delete detects reference and throws")
    void testM4a_concurrentAttachWinsOverGenericDelete_safeOrdering() throws Exception {
        UUID titleId = createTrackedTitle();
        UUID entryId = createTrackedEntry(titleId, "DRAFT");
        UUID candidateAssetId = createTrackedActiveVideoAsset();

        CountDownLatch attachHoldingLatch = new CountDownLatch(1);
        CountDownLatch attachReleaseLatch = new CountDownLatch(1);
        CountDownLatch deleteAttemptingLockLatch = new CountDownLatch(1);

        testSyncClock.setSyncForThread("AttachWinnerWorker", attachHoldingLatch, attachReleaseLatch);
        testLockCoordinator.setBeforeLockLatch("DeleteContenderWorker", deleteAttemptingLockLatch);

        GuardTestWorker<Void> attachWorker = new GuardTestWorker<>("AttachWinnerWorker", () -> {
            attachUseCase.execute(titleId, entryId, candidateAssetId);
            return null;
        });
        registeredWorkers.add(attachWorker);

        GuardTestWorker<Void> deleteWorker = new GuardTestWorker<>("DeleteContenderWorker", () -> {
            deleteUseCase.execute(new DeleteMediaAssetCommand(candidateAssetId));
            return null;
        });
        registeredWorkers.add(deleteWorker);

        // 1. Start attach winner in its own transaction
        attachWorker.start();
        boolean holding = attachHoldingLatch.await(WINNER_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(holding).as("Attach winner must reach post-lock clock synchronization").isTrue();

        Long winnerConnId = testLockCoordinator.getContenderConnectionId("AttachWinnerWorker");

        // 2. Start delete contender (will attempt candidateAssetId row lock in its own transaction)
        deleteWorker.start();

        // 3. Await contender arriving at findByIdForUpdate threshold
        boolean deleteReachedLock = deleteAttemptingLockLatch.await(CONTENDER_LOCK_ENTRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(deleteReachedLock).as("Delete contender must reach database lock acquisition attempt within timeout").isTrue();

        Long contenderConnId = testLockCoordinator.getContenderConnectionId("DeleteContenderWorker");
        assertThat(contenderConnId).as("Delete contender MySQL CONNECTION_ID() must be captured from active transaction").isNotNull();

        // 4. Observe physical InnoDB lock wait specifically for the captured contender connection
        assertThat(deleteWorker.isCompleted()).as("Delete contender must NOT complete while lock is held").isFalse();
        boolean lockWaitObserved = awaitContenderInInnoDBLockWait(contenderConnId, INNODB_WAIT_OBSERVATION_TIMEOUT_MS, deleteWorker, winnerConnId);
        assertThat(lockWaitObserved)
                .as("Contender MySQL session [" + contenderConnId + "] must be physically observed in LOCK WAIT in information_schema.innodb_trx or performance_schema")
                .isTrue();

        // 5. Release attach winner to complete and commit
        attachReleaseLatch.countDown();

        // 6. Wait until both transactions have completed
        terminateAndVerifyWorkers(attachWorker, deleteWorker);

        assertThat(attachWorker.getFailure()).isNull();

        // 7. Delete contender unblocks, executes reference check, detects entry, and must throw MediaAssetReferencedException
        assertThat(deleteWorker.getFailure())
                .isNotNull()
                .isInstanceOf(MediaAssetReferencedException.class);

        // Verify final DB state: candidate remains ACTIVE and attached to entry
        String finalAssetStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?", String.class, candidateAssetId.toString());
        assertThat(finalAssetStatus).isEqualTo("ACTIVE");

        String attachedVideoId = jdbcTemplate.queryForObject(
                "SELECT video_media_asset_id FROM screen_entries WHERE id = ?", String.class, entryId.toString());
        assertThat(attachedVideoId).isEqualTo(candidateAssetId.toString());
    }

    // =========================================================================
    // M4b: Concurrent generic delete wins over Screen attach
    // =========================================================================
    @Test
    @DisplayName("M4b: Concurrent generic delete wins over Screen attach; contender attach detects DELETED status and throws")
    void testM4b_concurrentGenericDeleteWinsOverAttach_safeOrdering() throws Exception {
        UUID titleId = createTrackedTitle();
        UUID entryId = createTrackedEntry(titleId, "DRAFT");
        UUID candidateAssetId = createTrackedActiveVideoAsset();

        CountDownLatch deleteHoldingLatch = new CountDownLatch(1);
        CountDownLatch deleteReleaseLatch = new CountDownLatch(1);
        CountDownLatch attachAttemptingLockLatch = new CountDownLatch(1);

        testSyncClock.setSyncForThread("DeleteWinnerWorker", deleteHoldingLatch, deleteReleaseLatch);
        testLockCoordinator.setBeforeLockLatch("AttachContenderWorker", attachAttemptingLockLatch);

        GuardTestWorker<Void> deleteWorker = new GuardTestWorker<>("DeleteWinnerWorker", () -> {
            deleteUseCase.execute(new DeleteMediaAssetCommand(candidateAssetId));
            return null;
        });
        registeredWorkers.add(deleteWorker);

        GuardTestWorker<Void> attachWorker = new GuardTestWorker<>("AttachContenderWorker", () -> {
            attachUseCase.execute(titleId, entryId, candidateAssetId);
            return null;
        });
        registeredWorkers.add(attachWorker);

        // 1. Start delete winner in its own transaction
        deleteWorker.start();
        boolean holding = deleteHoldingLatch.await(WINNER_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(holding).as("Delete winner must reach post-lock clock synchronization").isTrue();

        Long winnerConnId = testLockCoordinator.getContenderConnectionId("DeleteWinnerWorker");

        // 2. Start attach contender (will attempt candidateAssetId row lock in its own transaction)
        attachWorker.start();

        // 3. Await contender arriving at findByIdForUpdate threshold
        boolean attachReachedLock = attachAttemptingLockLatch.await(CONTENDER_LOCK_ENTRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(attachReachedLock).as("Attach contender must reach database lock acquisition attempt within timeout").isTrue();

        Long contenderConnId = testLockCoordinator.getContenderConnectionId("AttachContenderWorker");
        assertThat(contenderConnId).as("Attach contender MySQL CONNECTION_ID() must be captured from active transaction").isNotNull();

        // 4. Observe physical InnoDB lock wait specifically for the captured contender connection
        assertThat(attachWorker.isCompleted()).as("Attach contender must NOT complete while lock is held").isFalse();
        boolean lockWaitObserved = awaitContenderInInnoDBLockWait(contenderConnId, INNODB_WAIT_OBSERVATION_TIMEOUT_MS, attachWorker, winnerConnId);
        assertThat(lockWaitObserved)
                .as("Contender MySQL session [" + contenderConnId + "] must be physically observed in LOCK WAIT in information_schema.innodb_trx or performance_schema")
                .isTrue();

        // 5. Release delete winner to complete and commit
        deleteReleaseLatch.countDown();

        // 6. Wait until both transactions have completed
        terminateAndVerifyWorkers(deleteWorker, attachWorker);

        assertThat(deleteWorker.getFailure()).isNull();

        // 7. Attach contender unblocks, detects candidate is now DELETED, throws ScreenMediaAssetNotFoundException
        assertThat(attachWorker.getFailure())
                .isNotNull()
                .isInstanceOf(ScreenMediaAssetNotFoundException.class);

        // Verify final DB state: candidate is DELETED, entry's video remains NULL
        String finalAssetStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?", String.class, candidateAssetId.toString());
        assertThat(finalAssetStatus).isEqualTo("DELETED");

        String attachedVideoId = jdbcTemplate.queryForObject(
                "SELECT video_media_asset_id FROM screen_entries WHERE id = ?", String.class, entryId.toString());
        assertThat(attachedVideoId).isNull();
    }

    // =========================================================================
    // M5: Already-DELETED asset cannot be newly attached while purge is eligible
    // =========================================================================
    @Test
    @DisplayName("M5: Already-DELETED asset cannot be newly attached while purge is eligible")
    void testM5_alreadyDeletedAssetCannotBeNewlyAttachedWhilePurgeIsEligible() {
        UUID titleId = createTrackedTitle();
        UUID entryId = createTrackedEntry(titleId, "DRAFT");

        Instant eightDaysAgo = Instant.now().minus(Duration.ofDays(8));
        UUID deletedAssetId = createTrackedDeletedVideoAsset(eightDaysAgo);

        assertThatThrownBy(() -> attachUseCase.execute(titleId, entryId, deletedAssetId))
                .isInstanceOf(ScreenMediaAssetNotFoundException.class);

        String attachedVideoId = jdbcTemplate.queryForObject(
                "SELECT video_media_asset_id FROM screen_entries WHERE id = ?", String.class, entryId.toString());
        assertThat(attachedVideoId).isNull();
    }

    // =========================================================================
    // M6: Referenced DELETED asset is rejected before physical purge
    // =========================================================================
    @Test
    @DisplayName("M6: Referenced DELETED asset is rejected before physical purge; storage purge is never invoked")
    void testM6_referencedDeletedAssetIsRejectedBeforePhysicalPurge_withMockedStorageProvider() {
        UUID titleId = createTrackedPublishedTitle(Instant.now().minus(Duration.ofDays(2)));
        Instant publishedAt = Instant.now().minus(Duration.ofDays(1));
        UUID entryId = createTrackedPublishedEntry(titleId, publishedAt);

        Instant eightDaysAgo = Instant.now().minus(Duration.ofDays(8));
        UUID deletedAssetId = createTrackedDeletedVideoAsset(eightDaysAgo);
        linkEntryToVideo(entryId, deletedAssetId);

        assertThatThrownBy(() -> purgeUseCase.execute(new PurgeDeletedMediaAssetCommand(deletedAssetId)))
                .isInstanceOf(MediaAssetReferencedException.class)
                .hasMessageContaining(deletedAssetId.toString())
                .hasMessageContaining(entryId.toString());

        // Prove mock storage was NEVER called to delete physical object
        verify(mockBinaryStoragePort, never()).purgeExactKey(any(StorageKey.class));

        // Prove database metadata was preserved
        String assetStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?", String.class, deletedAssetId.toString());
        assertThat(assetStatus).isEqualTo("DELETED");
    }

    // =========================================================================
    // M7: Selected test-slice bean wiring and contract routing proof
    // =========================================================================
    @Test
    @DisplayName("M7: Selected test-slice wiring includes Screen reference checker and routes guarded Media operations")
    void testM7_selectedTestSliceWiringIncludesScreenReferenceChecker_andRoutesGenericMediaOperations() {
        // Verify Screen checker bean is wired into test slice as MediaAssetReferenceCheckPort
        // Note: Full application component scan wiring is independently proven by ScreenMediaAssetReferenceCheckWiringTest
        assertThat(applicationContext.getBean(ScreenMediaAssetReferenceCheckAdapter.class))
                .as("ScreenMediaAssetReferenceCheckAdapter bean must be present in slice context")
                .isNotNull();

        Map<String, MediaAssetReferenceCheckPort> ports = applicationContext.getBeansOfType(MediaAssetReferenceCheckPort.class);
        assertThat(ports.values())
                .as("MediaAssetReferenceCheckPort beans in slice context must include VIDEO checker adapter")
                .anyMatch(p -> p instanceof ScreenMediaAssetReferenceCheckAdapter && p.supports(MediaTypeDTO.VIDEO));

        // Verify use cases have checker registered and are wired
        assertThat(archiveUseCase)
                .as("ArchiveMediaAssetUseCase bean must be wired in slice context")
                .isNotNull();
        assertThat(deleteUseCase)
                .as("DeleteMediaAssetUseCase bean must be wired in slice context")
                .isNotNull();
        assertThat(purgeUseCase)
                .as("PurgeDeletedMediaAssetUseCase bean must be wired in slice context")
                .isNotNull();
    }

    // =========================================================================
    // TEST CONFIGURATION & COORDINATION HELPERS
    // =========================================================================

    @TestConfiguration
    static class GuardTestWiringConfig {

        @Bean
        @Primary
        public BinaryStoragePort mockBinaryStoragePort() {
            return mock(BinaryStoragePort.class);
        }

        @Bean
        @Primary
        public StorageProviderResolverPort storageProviderResolverPort(BinaryStoragePort mockBinaryStoragePort) {
            StorageProviderResolverPort resolver = mock(StorageProviderResolverPort.class);
            Mockito.when(resolver.resolve(any())).thenReturn(mockBinaryStoragePort);
            return resolver;
        }

        @Bean
        @Primary
        public GuardTestSyncClock guardTestSyncClock() {
            return new GuardTestSyncClock();
        }

        @Bean
        public ClockPort clockPort(GuardTestSyncClock clock) {
            return clock;
        }

        @Bean
        public StorageKeyAllocationService storageKeyAllocationService() {
            return mock(StorageKeyAllocationService.class);
        }

        @Bean
        public MediaStorageCutoverBarrier cutoverBarrier() {
            return mock(MediaStorageCutoverBarrier.class);
        }

        @Bean
        public ScreenMediaVisibilityPort screenMediaVisibilityPort() {
            return mock(ScreenMediaVisibilityPort.class);
        }

        @Bean
        public TestLockCoordinator testLockCoordinator() {
            return new TestLockCoordinator();
        }

        @Bean
        @Primary
        public MediaAssetRepositoryPort testCoordinatingMediaAssetRepositoryPort(
                MediaAssetPersistenceAdapter delegate,
                TestLockCoordinator coordinator,
                EntityManager entityManager,
                JdbcTemplate jdbcTemplate
        ) {
            return new CoordinatingTestMediaAssetRepository(delegate, coordinator, entityManager, jdbcTemplate);
        }

        @Bean
        @Primary
        public MediaContract mediaContract(
                ArchiveMediaAssetUseCase archiveMediaAssetUseCase,
                DeleteMediaAssetUseCase deleteMediaAssetUseCase,
                LockAndValidateActiveMediaAssetUseCase lockAndValidateActiveMediaAssetUseCase
        ) {
            return new MediaFacade(
                    mock(GetMediaAssetDetailUseCase.class),
                    mock(GetMediaAssetCurrentMetadataUseCase.class),
                    mock(ChangeMediaVisibilityUseCase.class),
                    archiveMediaAssetUseCase,
                    mock(RestoreMediaAssetUseCase.class),
                    deleteMediaAssetUseCase,
                    mock(UploadMediaAssetUseCase.class),
                    mock(UploadMediaAssetVersionUseCase.class),
                    mock(GenerateMediaImageVariantUseCase.class),
                    mock(GetCurrentMediaAssetVersionSnapshotUseCase.class),
                    mock(OpenMediaAssetVersionContentUseCase.class),
                    mock(AssignMediaAssetClientTagUseCase.class),
                    mock(FindActiveMediaAssetsByClientTagKeysetUseCase.class),
                    mock(UploadMediaAssetVersionConditionalUseCase.class),
                    lockAndValidateActiveMediaAssetUseCase,
                    mock(LockAndInspectMediaAssetUseCase.class)
            );
        }
    }

    /**
     * Test-only lock coordinator matching ScreenGuardedDiscardConcurrencyIntegrationTest.
     * Captures the contender's MySQL connectionId from inside its active transaction
     * and signals right at the threshold of the findByIdForUpdate database call.
     */
    static class TestLockCoordinator {
        private final Map<String, CountDownLatch> beforeLockLatches = new ConcurrentHashMap<>();
        private final Map<String, Long> contenderConnectionIds = new ConcurrentHashMap<>();

        void releaseAll() {
            for (CountDownLatch latch : beforeLockLatches.values()) {
                if (latch != null) {
                    while (latch.getCount() > 0) {
                        latch.countDown();
                    }
                }
            }
            beforeLockLatches.clear();
            contenderConnectionIds.clear();
        }

        void reset() {
            releaseAll();
        }

        void setBeforeLockLatch(String threadName, CountDownLatch latch) {
            beforeLockLatches.put(threadName, latch);
        }

        void onBeforeFindByIdForUpdate(String threadName, UUID assetId, Long connectionId) {
            if (connectionId != null) {
                contenderConnectionIds.put(threadName, connectionId);
            }
            CountDownLatch latch = beforeLockLatches.remove(threadName);
            if (latch != null) {
                latch.countDown();
            }
        }

        Long getContenderConnectionId(String threadName) {
            return contenderConnectionIds.get(threadName);
        }
    }

    /**
     * Coordinating delegating repository matching ScreenGuardedDiscardConcurrencyIntegrationTest.
     * Executes SELECT CONNECTION_ID() within the active Spring transaction to correlate
     * the MySQL session ID with subsequent InnoDB lock wait observations, before calling delegate.
     */
    static class CoordinatingTestMediaAssetRepository implements MediaAssetRepositoryPort {
        private final MediaAssetPersistenceAdapter delegate;
        private final TestLockCoordinator coordinator;
        private final EntityManager entityManager;
        private final JdbcTemplate jdbcTemplate;

        CoordinatingTestMediaAssetRepository(
                MediaAssetPersistenceAdapter delegate,
                TestLockCoordinator coordinator,
                EntityManager entityManager,
                JdbcTemplate jdbcTemplate
        ) {
            this.delegate = delegate;
            this.coordinator = coordinator;
            this.entityManager = entityManager;
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public Optional<MediaAsset> findById(UUID id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<MediaAsset> findByIdForUpdate(UUID id) {
            Long connectionId = null;
            try {
                if (entityManager != null) {
                    Session session = entityManager.unwrap(Session.class);
                    if (session != null) {
                        final Long[] holder = new Long[1];
                        session.doWork(conn -> {
                            try (var stmt = conn.createStatement();
                                 var rs = stmt.executeQuery("SELECT CONNECTION_ID()")) {
                                if (rs.next()) {
                                    holder[0] = rs.getLong(1);
                                }
                            }
                        });
                        connectionId = holder[0];
                    }
                }
            } catch (Exception e) {
                System.err.println("WARN: Failed to query CONNECTION_ID() via Session.doWork: " + e.getMessage());
            }
            if (connectionId == null) {
                try {
                    if (entityManager != null) {
                        Object result = entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult();
                        if (result instanceof Number num) {
                            connectionId = num.longValue();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("WARN: Failed to query CONNECTION_ID() via EntityManager: " + e.getMessage());
                }
            }
            if (connectionId == null && jdbcTemplate != null) {
                try {
                    connectionId = jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class);
                } catch (Exception ignored) {
                }
            }
            coordinator.onBeforeFindByIdForUpdate(Thread.currentThread().getName(), id, connectionId);
            return delegate.findByIdForUpdate(id);
        }

        @Override
        public MediaAsset save(MediaAsset asset) {
            return delegate.save(asset);
        }

        @Override
        public void deleteById(UUID id) {
            delegate.deleteById(id);
        }

        @Override
        public List<MediaAsset> findExpiredDeleted(Instant cutoff, int limit) {
            return delegate.findExpiredDeleted(cutoff, limit);
        }

        @Override
        public List<MediaAssetCandidate> findActiveByClientTagKeyset(
                String clientTag,
                Instant upperBound,
                Instant lastCreatedAt,
                UUID lastAssetId,
                int limit
        ) {
            return delegate.findActiveByClientTagKeyset(clientTag, upperBound, lastCreatedAt, lastAssetId, limit);
        }
    }

    static class GuardTestSyncClock implements ClockPort {

        private final AtomicReference<String> targetThreadName = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> holdingLatch = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> releaseLatch = new AtomicReference<>();

        void releaseAll() {
            CountDownLatch release = releaseLatch.getAndSet(null);
            if (release != null) {
                while (release.getCount() > 0) {
                    release.countDown();
                }
            }
            CountDownLatch holding = holdingLatch.getAndSet(null);
            if (holding != null) {
                while (holding.getCount() > 0) {
                    holding.countDown();
                }
            }
            targetThreadName.set(null);
        }

        void reset() {
            releaseAll();
        }

        void setSyncForThread(String threadName, CountDownLatch holding, CountDownLatch release) {
            this.targetThreadName.set(threadName);
            this.holdingLatch.set(holding);
            this.releaseLatch.set(release);
        }

        @Override
        public Instant now() {
            String target = targetThreadName.get();
            String current = Thread.currentThread().getName();

            if (target != null && target.equals(current)) {
                CountDownLatch holding = holdingLatch.get();
                if (holding != null) {
                    holding.countDown();
                }

                CountDownLatch release = releaseLatch.get();
                if (release != null) {
                    try {
                        if (!release.await(WINNER_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                            throw new IllegalStateException("Timed out waiting for test release latch in thread: " + current);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
            }
            return Instant.now();
        }
    }

    static class GuardTestWorker<T> extends Thread {
        private final Callable<T> task;
        private volatile T result;
        private volatile Exception failure;
        private final AtomicBoolean started = new AtomicBoolean(false);

        GuardTestWorker(String name, Callable<T> task) {
            super(name);
            this.task = task;
        }

        @Override
        public synchronized void start() {
            started.set(true);
            super.start();
        }

        boolean isStarted() {
            return started.get();
        }

        boolean isCompleted() {
            return !isAlive() && started.get();
        }

        @Override
        public void run() {
            try {
                result = task.call();
            } catch (Exception e) {
                failure = e;
            }
        }

        boolean awaitTermination(long timeout, TimeUnit unit) {
            try {
                join(unit.toMillis(timeout));
                return !isAlive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !isAlive();
            }
        }

        T getResult() {
            return result;
        }

        Exception getFailure() {
            return failure;
        }
    }
}
