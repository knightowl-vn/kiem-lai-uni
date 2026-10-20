package com.universe.screen.infrastructure.persistence;

import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.AssignMediaAssetClientTagUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.FindActiveMediaAssetsByClientTagKeysetUseCase;
import com.universe.media.application.asset.GetCurrentMediaAssetVersionSnapshotUseCase;
import com.universe.media.application.asset.GetMediaAssetCurrentMetadataUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.LockAndInspectMediaAssetUseCase;
import com.universe.media.application.asset.LockAndValidateActiveMediaAssetUseCase;
import com.universe.media.application.asset.OpenMediaAssetVersionContentUseCase;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionUseCase;
import com.universe.media.application.facade.MediaFacade;
import com.universe.media.application.ports.MediaAssetCandidate;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.MediaUploadSessionService;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.application.variant.GenerateMediaImageVariantUseCase;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.domain.MediaAsset;
import com.universe.media.infrastructure.persistence.MediaAssetPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaAssetVersionPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaUploadSessionPersistenceAdapter;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadCommand;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenVideoAlreadyReferencedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardAccessDeniedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardValidationException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.usecase.AttachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.DiscardScreenEntryVideoUploadUseCase;
import com.universe.screen.application.usecase.ReplaceScreenEntryVideoUseCase;
import com.universe.screen.infrastructure.media.ScreenMediaValidationAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.hibernate.Session;
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

/**
 * Real Physical MySQL Integration & Concurrency Verification Test Suite for Guarded Video Discard (MS-08E2-G3C-S2-B2-C2-B-STAGE1-R3).
 *
 * <p>Scope and Invariant Coverage:
 * <ul>
 *   <li><b>C2B-01:</b> Attach wins over concurrent discard: candidate MediaAsset lock serializes discard behind attach;
 *       contender proves lock attempt before winner commits; after attach commits, discard's current-read
 *       reference check observes the attached entry and throws ScreenVideoAlreadyReferencedException.
 *       (HTTP 409 mapping is verified in C2-A WebMvc tests).</li>
 *   <li><b>C2B-02:</b> Discard wins over concurrent attach: discard locks and soft-deletes unreferenced MediaAsset;
 *       contender proves lock attempt before winner commits; concurrent attach unblocks and fails safely with
 *       ScreenMediaAssetNotFoundException because candidate asset is DELETED.</li>
 *   <li><b>C2B-03:</b> Replace wins over concurrent discard: replace acquires lock on new MediaAsset, swaps reference, commits;
 *       contender proves lock attempt before winner commits; concurrent discard detects entry reference and throws
 *       ScreenVideoAlreadyReferencedException without deleting the new or old asset.</li>
 *   <li><b>C2B-04:</b> Discard wins over concurrent replace: discard marks new candidate asset DELETED;
 *       contender proves lock attempt before winner commits; concurrent replace unblocks and fails validation
 *       with ScreenMediaAssetNotFoundException, leaving old usable video attached and ACTIVE.</li>
 *   <li><b>C2B-05:</b> Existing reference rejection: uncommitted/committed discard rejects an already-referenced asset.</li>
 *   <li><b>C2B-06:</b> DELETED idempotency: unreferenced DELETED asset returns ALREADY_DISCARDED;
 *       referenced DELETED asset rejects with reference conflict before outcome evaluation.</li>
 *   <li><b>C2B-07:</b> Stale REPEATABLE READ snapshot: discard's locking current-read breaks through MVCC snapshot.</li>
 *   <li><b>C2B-08:</b> Direct locked lookup integration: validates target ScreenEntry under pessimistic row lock without prior unlocked reads.</li>
 *   <li><b>C2B-09:</b> Physical rollback integrity: verifies in-transaction soft-delete with explicit JPA entity flush prior to forced rollback, and verifies post-rollback restoration to ACTIVE.</li>
 *   <li><b>C2B-10:</b> Authorization failure non-mutation: unauthorized requests reject before locks or DB mutations.</li>
 *   <li><b>C2B-11:</b> Publication safety: editorial publication status (PUBLISHED, exact publishedAt) remains unmodified across successful attach and failed replace.</li>
 *   <li><b>C2B-12:</b> Spring wiring proof: verifies selected test container instantiation, @Transactional proxies, real DataSource/TransactionManager.</li>
 * </ul>
 *
 * <p>SAFETY STATUS: NOT EXECUTED — PENDING HUMAN DB SAFETY APPROVAL.
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
        MediaUploadSessionPersistenceAdapter.class,
        ScreenTitlePersistenceAdapter.class,
        ScreenEntryPersistenceAdapter.class,
        ScreenMediaValidationAdapter.class,
        DiscardScreenEntryVideoUploadUseCase.class,
        AttachScreenEntryVideoUseCase.class,
        ReplaceScreenEntryVideoUseCase.class,
        LockAndInspectMediaAssetUseCase.class,
        LockAndValidateActiveMediaAssetUseCase.class,
        DeleteMediaAssetUseCase.class,
        ScreenGuardedDiscardConcurrencyIntegrationTest.TestWiringConfig.class
})
@DisplayName("Screen Guarded Video Discard Real MySQL Concurrency & Transaction Integration Tests")
class ScreenGuardedDiscardConcurrencyIntegrationTest {

    // =========================================================================
    // TIMING MODEL CONSTANTS (R3-SR-01 & R4 Timing Hierarchy)
    // Coherent timing inequality:
    // INNODB_WAIT_OBSERVATION_TIMEOUT_MS (1.5s)
    //   < MYSQL_INNODB_LOCK_WAIT_SECONDS (3s)
    //   < WORKER_PRIMARY_JOIN_TIMEOUT_MS (4s)
    //   < WINNER_RELEASE_TIMEOUT_MS / WORKER_SHUTDOWN (6s)
    //   < JDBC_SOCKET_TIMEOUT_MS (7s)
    //   < TEST_METHOD_TIMEOUT_SECONDS (20s)
    // =========================================================================
    static final int MYSQL_INNODB_LOCK_WAIT_SECONDS = 3;
    static final int JDBC_SOCKET_TIMEOUT_MS = 7000;
    static final long WINNER_CHECKPOINT_TIMEOUT_MS = 3000L;
    static final long CONTENDER_LOCK_ENTRY_TIMEOUT_MS = 3000L;
    static final long INNODB_WAIT_OBSERVATION_TIMEOUT_MS = 1500L;
    static final long WINNER_RELEASE_TIMEOUT_MS = 6000L;
    static final long WORKER_PRIMARY_JOIN_TIMEOUT_MS = 4000L;
    static final long WORKER_INTERRUPT_JOIN_TIMEOUT_MS = 2000L;
    static final long TEST_METHOD_TIMEOUT_SECONDS = 20L;

    private static final String DEFAULT_ACTOR = "admin@example.com";
    private static final String DEFAULT_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    // Suite-level fail-closed barrier: if any worker leaks or fails to terminate, abort all subsequent tests
    private static final AtomicBoolean SUITE_ABORTED = new AtomicBoolean(false);
    private static final AtomicReference<String> SUITE_ABORT_REASON = new AtomicReference<>();

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        String host = TestDatabaseSupport.resolveHost();
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        // R1-F04: Strict pre-context guard executes BEFORE adding properties and BEFORE DataSource initialization
        // Strictly case-sensitive: must equal "kiemlai_test"
        ScreenTestDatabaseTargetGuard.validateTarget(host, dbName);
        TestDatabaseSupport.configureDynamicProperties(registry);

        // R2-SR-04 & R4-SR-01: Local-only bounded wait strategy:
        // 1. Session-level lock wait timeout = 3s via connection-init-sql (does NOT modify global server configuration)
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET SESSION innodb_lock_wait_timeout=" + MYSQL_INNODB_LOCK_WAIT_SECONDS);
        // 2. Driver-level socket timeout = 7000ms (7 seconds)
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
    private ScreenTitleRepositoryPort titleRepository;

    @Autowired
    private ScreenEntryRepositoryPort entryRepository;

    @Autowired
    private ScreenMediaValidationPort mediaValidationPort;

    @Autowired
    private DiscardScreenEntryVideoUploadUseCase discardUseCase;

    @Autowired
    private AttachScreenEntryVideoUseCase attachUseCase;

    @Autowired
    private ReplaceScreenEntryVideoUseCase replaceUseCase;

    @Autowired
    private TestSyncClock testSyncClock;

    @Autowired
    private TestLockCoordinator testLockCoordinator;

    // R1-F06: Comprehensive test-instance worker registry tracking all created workers
    private final List<TestWorker<?>> registeredWorkers = new CopyOnWriteArrayList<>();

    // Tracked IDs for strict foreign-key ordered teardown per test
    private final List<UUID> trackedEntryIds = new ArrayList<>();
    private final List<UUID> trackedTitleIds = new ArrayList<>();
    private final List<UUID> trackedSessionIds = new ArrayList<>();
    private final List<UUID> trackedAllocationIds = new ArrayList<>();
    private final List<UUID> trackedAssetIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        if (SUITE_ABORTED.get()) {
            throw new IllegalStateException("SUITE-LEVEL FAIL-CLOSED BLOCK: Previous test leaked a worker thread! Aborting suite. Reason: " + SUITE_ABORT_REASON.get());
        }
        registeredWorkers.clear();
        testSyncClock.reset();
        testLockCoordinator.reset();
    }

    @AfterEach
    void tearDown() {
        // Step 1 of Teardown Contract (R2-SR-03): Release all test-owned coordination latches to unblock any paused workers
        testSyncClock.releaseAll();
        testLockCoordinator.releaseAll();

        // Step 2 & 3 of Teardown Contract: Inspect all registered workers and boundedly await (4s join + interrupt + 2s join)
        List<String> survivingWorkers = new ArrayList<>();
        boolean wasInterrupted = false;
        for (TestWorker<?> worker : registeredWorkers) {
            if (worker.isStarted() && worker.isAlive()) {
                boolean terminated = worker.awaitTermination(WORKER_PRIMARY_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!terminated) {
                    worker.interrupt();
                    terminated = worker.awaitTermination(WORKER_INTERRUPT_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
                if (worker.isAlive()) {
                    survivingWorkers.add(worker.getName());
                }
            }
        }

        if (Thread.currentThread().isInterrupted()) {
            wasInterrupted = true;
        }

        // Step 4 of Teardown Contract (R2-SR-03): If any worker leaked or teardown was interrupted, fail-close suite and skip cleanup
        if (!survivingWorkers.isEmpty() || wasInterrupted) {
            String msg = "SURVIVING WORKERS DETECTED IN TEARDOWN: " + survivingWorkers
                    + (wasInterrupted ? " (Teardown thread interrupted!)" : "")
                    + ". Cannot safely run fixture cleanup! Tracked IDs: " + getTrackedIdsSummary();
            SUITE_ABORT_REASON.set(msg);
            SUITE_ABORTED.set(true);
            System.err.println("CRITICAL: " + msg);
            throw new IllegalStateException("CRITICAL CONCURRENCY FAILURE: " + msg);
        }

        if (SUITE_ABORTED.get()) {
            System.err.println("CRITICAL: Skipping fixture cleanup because suite was previously aborted! Details: " + SUITE_ABORT_REASON.get());
            throw new IllegalStateException("CRITICAL CONCURRENCY FAILURE: Suite previously aborted! Details: " + SUITE_ABORT_REASON.get());
        }

        // Step 5: Only when all workers are provably dead, run fixture cleanup
        cleanupTrackedFixtures();
    }

    private void cleanupTrackedFixtures() {
        // Strict foreign key reverse dependency deletion order:
        // 1. screen_entries (references screen_titles)
        for (UUID entryId : trackedEntryIds) {
            jdbcTemplate.update("DELETE FROM screen_entries WHERE id = ?", entryId.toString());
        }
        trackedEntryIds.clear();

        // 2. screen_titles
        for (UUID titleId : trackedTitleIds) {
            jdbcTemplate.update("DELETE FROM screen_titles WHERE id = ?", titleId.toString());
        }
        trackedTitleIds.clear();

        // 3. media_upload_sessions (references media_assets and media_storage_key_allocations)
        for (UUID sessionId : trackedSessionIds) {
            jdbcTemplate.update("DELETE FROM media_upload_sessions WHERE id = ?", sessionId.toString());
        }
        trackedSessionIds.clear();

        // 4. media_storage_key_allocations
        for (UUID allocId : trackedAllocationIds) {
            jdbcTemplate.update("DELETE FROM media_storage_key_allocations WHERE id = ?", allocId.toString());
        }
        trackedAllocationIds.clear();

        // 5. media_asset_versions (references media_assets)
        for (UUID assetId : trackedAssetIds) {
            jdbcTemplate.update("DELETE FROM media_asset_versions WHERE asset_id = ?", assetId.toString());
        }

        // 6. media_assets
        for (UUID assetId : trackedAssetIds) {
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", assetId.toString());
        }
        trackedAssetIds.clear();
    }

    private String getTrackedIdsSummary() {
        return "trackedEntries=" + trackedEntryIds + ", trackedTitles=" + trackedTitleIds
                + ", trackedSessions=" + trackedSessionIds + ", trackedAllocations=" + trackedAllocationIds
                + ", trackedAssets=" + trackedAssetIds;
    }

    // =========================================================================
    // FIXTURE FACTORIES (R1-F03, R1-F05 & R2-SR-05)
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
        jdbcTemplate.update("""
                INSERT INTO screen_titles (id, slug, title, structure, presentation, status, published_at, created_at, updated_at)
                VALUES (?, ?, 'Published Title Fixture', 'SERIES', 'LIVE_ACTION', 'PUBLISHED', ?, ?, ?)
                """, titleId.toString(), "title-" + titleId.toString().substring(0, 8),
                ts, ts, ts);
        return titleId;
    }

    private UUID createTrackedEntry(UUID titleId, UUID videoAssetId, int episodeNumber) {
        UUID entryId = UUID.randomUUID();
        trackedEntryIds.add(entryId);
        // R1-F03: Construct entry title in Java to prevent MySQL '+' arithmetic coercion
        String entryTitle = "Episode " + episodeNumber;
        Timestamp ts = Timestamp.from(Instant.now().minus(Duration.ofMinutes(10)));
        jdbcTemplate.update("""
                INSERT INTO screen_entries (id, title_id, slug, type, number, title, video_media_asset_id, status, created_at, updated_at)
                VALUES (?, ?, ?, 'EPISODE', ?, ?, ?, 'DRAFT', ?, ?)
                """, entryId.toString(), titleId.toString(), "entry-" + entryId.toString().substring(0, 8),
                episodeNumber, entryTitle,
                videoAssetId != null ? videoAssetId.toString() : null, ts, ts);
        return entryId;
    }

    private UUID createTrackedPublishedEntry(UUID titleId, UUID videoAssetId, int episodeNumber, Instant publishedAt) {
        UUID entryId = UUID.randomUUID();
        trackedEntryIds.add(entryId);
        // R1-F03: Construct entry title in Java to prevent MySQL '+' arithmetic coercion
        String entryTitle = "Episode " + episodeNumber;
        Timestamp ts = Timestamp.from(publishedAt);
        jdbcTemplate.update("""
                INSERT INTO screen_entries (id, title_id, slug, type, number, title, video_media_asset_id, status, published_at, created_at, updated_at)
                VALUES (?, ?, ?, 'EPISODE', ?, ?, ?, 'PUBLISHED', ?, ?, ?)
                """, entryId.toString(), titleId.toString(), "entry-" + entryId.toString().substring(0, 8),
                episodeNumber, entryTitle,
                videoAssetId != null ? videoAssetId.toString() : null, ts, ts, ts);
        return entryId;
    }

    private record MediaAssetFixture(UUID assetId, UUID versionId, String storageKey) {}

    private MediaAssetFixture createTrackedMediaAsset(String status) {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        trackedAssetIds.add(assetId);
        String storageKey = "videos/" + assetId + ".mp4";
        Timestamp ts = Timestamp.from(Instant.now().minus(Duration.ofMinutes(10)));

        jdbcTemplate.update("""
                INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version)
                VALUES (?, 'VIDEO', 'PRIVATE', ?, 1, ?, ?, 0)
                """, assetId.toString(), status, ts, ts);

        jdbcTemplate.update("""
                INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, original_filename, created_at)
                VALUES (?, ?, 1, 'b2', ?, 'https://cdn.example.com/video.mp4', ?, 'video/mp4', 26214400, 'video.mp4', ?)
                """, versionId.toString(), assetId.toString(), storageKey, DEFAULT_HASH, ts);

        return new MediaAssetFixture(assetId, versionId, storageKey);
    }

    private UUID createTrackedUploadSession(
            UUID targetEntryId,
            UUID registeredAssetId,
            UUID versionId,
            String storageKey,
            String status,
            String actor
    ) {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        trackedSessionIds.add(sessionId);
        trackedAllocationIds.add(allocId);

        Instant baseTime = Instant.now().minus(Duration.ofMinutes(10));
        Instant sessionCompletedAt = "COMPLETED".equalsIgnoreCase(status) ? baseTime.plus(Duration.ofMinutes(5)) : null;
        Instant sessionExpiresAt = baseTime.plus(Duration.ofDays(1));

        Timestamp tsCreated = Timestamp.from(baseTime);
        Timestamp tsCompleted = sessionCompletedAt != null ? Timestamp.from(sessionCompletedAt) : null;
        Timestamp tsExpires = Timestamp.from(sessionExpiresAt);

        // SR-06 & R2-SR-05: Aligned with V89 schema and MediaUploadSessionService lines 1696-1700
        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at, persistence_version
                ) VALUES (?, 'b2', 'kiemlai-video', ?, 'COMMITTED', 'ASSET_VERSION', ?, ?, ?, 0)
                """, allocId.toString(), storageKey, versionId.toString(), tsCreated, tsCreated);

        // SR-02 & R2-SR-05: Explicitly populate registered_asset_id adhering to chk_media_upload_sessions_completion
        // Safely parameterizes timestamps so createdAt <= updatedAt <= completedAt < Instant.now() < expiresAt (Issue A)
        jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, completed_at, cleanup_status, cleanup_retry_count,
                    registered_asset_id, created_at, updated_at, persistence_version
                ) VALUES (
                    ?, ?, 'screen', 'SCREEN_ENTRY', ?,
                    ?, 'VIDEO', 'video/mp4', 26214400,
                    'b2', 'kiemlai-video', ?, ?,
                    'b2-upload-part-1', 26214400, 1, ?,
                    'video.mp4', 'test-tag', ?, ?,
                    ?, 'NONE', 0,
                    ?, ?, ?, 0
                )
                """,
                sessionId.toString(),
                actor,
                targetEntryId.toString(),
                "idemp-" + sessionId.toString().substring(0, 8),
                storageKey,
                allocId.toString(),
                DEFAULT_HASH,
                status,
                tsExpires,
                tsCompleted,
                registeredAssetId != null ? registeredAssetId.toString() : null,
                tsCreated,
                tsCreated
        );

        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id, status, registered_asset_id FROM media_upload_sessions WHERE id = ?",
                sessionId.toString()
        );
        assertThat(sessionRow.get("id")).isEqualTo(sessionId.toString());
        assertThat(sessionRow.get("initiator_principal_id")).isEqualTo(actor);
        assertThat(sessionRow.get("consumer_context")).isEqualTo("screen");
        assertThat(sessionRow.get("target_entity_type")).isEqualTo("SCREEN_ENTRY");
        assertThat(sessionRow.get("target_entity_id")).isEqualTo(targetEntryId.toString());
        assertThat(sessionRow.get("status")).isEqualTo(status);
        if (registeredAssetId != null) {
            assertThat(sessionRow.get("registered_asset_id")).isEqualTo(registeredAssetId.toString());
        }

        return sessionId;
    }

    // =========================================================================
    // WORKER LIFECYCLE & REGISTRY INFRASTRUCTURE (R1-F06)
    // =========================================================================

    private <T> TestWorker<T> createAndRegisterWorker(String name, Callable<T> task) {
        TestWorker<T> worker = new TestWorker<>(name, task);
        registeredWorkers.add(worker);
        return worker;
    }

    static class TestWorker<T> {
        private final String name;
        private final Callable<T> task;
        private final Thread thread;
        private final AtomicReference<T> result = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean completed = new AtomicBoolean(false);

        TestWorker(String name, Callable<T> task) {
            this.name = name;
            this.task = task;
            this.thread = new Thread(this::run, name);
        }

        void start() {
            started.set(true);
            thread.start();
        }

        private void run() {
            try {
                T res = task.call();
                result.set(res);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                completed.set(true);
            }
        }

        boolean awaitTermination(long timeout, TimeUnit unit) {
            try {
                thread.join(unit.toMillis(timeout));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return !thread.isAlive();
        }

        void interrupt() {
            thread.interrupt();
        }

        boolean isAlive() {
            return thread.isAlive();
        }

        boolean isStarted() {
            return started.get();
        }

        boolean isCompleted() {
            return completed.get();
        }

        T getResult() {
            return result.get();
        }

        Throwable getError() {
            return error.get();
        }

        Thread getThread() {
            return thread;
        }

        String getName() {
            return name;
        }
    }

    private void terminateAndVerifyWorkers(TestWorker<?>... workers) {
        List<String> survivingWorkers = new ArrayList<>();
        for (TestWorker<?> worker : workers) {
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
            String msg = "SURVIVING WORKERS DETECTED: " + survivingWorkers + ". Cannot safely run fixture cleanup! Tracked IDs: " + getTrackedIdsSummary();
            SUITE_ABORT_REASON.set(msg);
            SUITE_ABORTED.set(true);
            throw new IllegalStateException("CRITICAL CONCURRENCY FAILURE: " + msg);
        }
    }

    // =========================================================================
    // CONTENTION & OVERLAP EVIDENCE HELPERS (R1-F01, R1-F02 & R2-SR-01)
    // =========================================================================

    private boolean isThreadExecutingDatabaseLockCall(Thread thread) {
        if (thread == null) return false;
        StackTraceElement[] stack = thread.getStackTrace();
        for (StackTraceElement elem : stack) {
            String className = elem.getClassName();
            String methodName = elem.getMethodName();
            if (className.contains("MediaAssetPersistenceAdapter") && methodName.equals("findByIdForUpdate")) {
                return true;
            }
            if (className.contains("SpringDataMediaAssetJpaRepository") && methodName.contains("findByIdForUpdate")) {
                return true;
            }
            if (className.contains("PreparedStatement") && methodName.contains("execute")) {
                return true;
            }
            if (className.contains("SocketInputStream") || className.contains("NativeProtocol")) {
                return true;
            }
        }
        return false;
    }

    private boolean awaitContenderInInnoDBLockWait(long connectionId, long timeoutMs) {
        return awaitContenderInInnoDBLockWait(connectionId, timeoutMs, null, null);
    }

    /**
     * R2-SR-01 & MS-08E2 C2-B: Verifies that the specific contender MySQL transaction (identified by its connectionId)
     * is physically in LOCK WAIT in MySQL InnoDB, verified through information_schema.innodb_trx,
     * performance_schema.data_locks (WAITING), performance_schema.data_lock_waits, or sys.innodb_lock_waits.
     * Bounded polling prevents exhausting InnoDB lock timeout.
     * Uses monotonic System.nanoTime() to remain immune to wall-clock skew.
     * On observation timeout, executes narrow performance_schema, metadata_locks, and thread diagnostics.
     */
    private boolean awaitContenderInInnoDBLockWait(
            long contenderConnectionId,
            long timeoutMs,
            TestWorker<?> contenderWorker,
            Long winnerConnectionId
    ) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadlineNanos) {
            if (isContenderInLockWait(contenderConnectionId)) {
                return true;
            }
            if (contenderWorker != null && contenderWorker.isCompleted()) {
                // Contender completed early before lock wait was established
                break;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        // Bounded test-only diagnostics on observation timeout
        dumpLockContentionDiagnostics(contenderConnectionId, winnerConnectionId, timeoutMs, contenderWorker);
        return false;
    }

    /**
     * Checks all authoritative MySQL 8 tables for active record lock contention on the contender connection.
     */
    private boolean isContenderInLockWait(long contenderConnectionId) {
        // 1. Check information_schema.innodb_trx (compatibility view)
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

        // 2. Check performance_schema.data_locks (MySQL 8 native engine row lock table)
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

        // 3. Check performance_schema.data_lock_waits (MySQL 8 native lock wait table)
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

        // 4. Check sys.innodb_lock_waits (MySQL 8 sys schema view)
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

    /**
     * Dumps narrow, test-only diagnostics on lock wait observation timeout.
     */
    private void dumpLockContentionDiagnostics(
            long contenderConnectionId,
            Long winnerConnectionId,
            long timeoutMs,
            TestWorker<?> contenderWorker
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
                        "SELECT ENGINE_TRANSACTION_ID, THREAD_ID, OBJECT_NAME, INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA FROM performance_schema.data_locks WHERE OBJECT_NAME IN ('media_assets', 'screen_entries', 'media_upload_sessions')"
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

            List<Map<String, Object>> psMetadataLocks = List.of();
            try {
                psMetadataLocks = jdbcTemplate.queryForList(
                        "SELECT OBJECT_TYPE, OBJECT_SCHEMA, OBJECT_NAME, LOCK_TYPE, LOCK_DURATION, LOCK_STATUS, OWNER_THREAD_ID FROM performance_schema.metadata_locks WHERE OBJECT_NAME IN ('media_assets', 'screen_entries', 'media_upload_sessions')"
                );
            } catch (Exception e) {
                System.err.println("DIAGNOSTIC - Could not query performance_schema.metadata_locks: " + e.getMessage());
            }

            List<Map<String, Object>> sysLockWaits = List.of();
            try {
                sysLockWaits = jdbcTemplate.queryForList(
                        "SELECT waiting_pid, waiting_query, blocking_pid, blocking_query, locked_table FROM sys.innodb_lock_waits"
                );
            } catch (Exception e) {
                System.err.println("DIAGNOSTIC - Could not query sys.innodb_lock_waits: " + e.getMessage());
            }

            boolean isCompleted = contenderWorker != null && contenderWorker.isCompleted();
            Throwable error = contenderWorker != null ? contenderWorker.getError() : null;
            String threadState = "UNKNOWN";
            StringBuilder stackTraceBuilder = new StringBuilder();
            if (contenderWorker != null && contenderWorker.getThread() != null) {
                Thread t = contenderWorker.getThread();
                threadState = t.getState().toString();
                for (StackTraceElement ste : t.getStackTrace()) {
                    stackTraceBuilder.append("\n\tat ").append(ste);
                }
            }

            System.err.println("DIAGNOSTIC - Lock wait observation timed out for contender connectionId=" + contenderConnectionId
                    + " (winner=" + winnerConnectionId + ") after " + timeoutMs + "ms."
                    + "\n  Worker: name=" + (contenderWorker != null ? contenderWorker.getName() : "null")
                    + ", isCompleted=" + isCompleted
                    + ", threadState=" + threadState
                    + ", error=" + (error != null ? error.toString() : "none")
                    + "\n  Java StackTrace=" + stackTraceBuilder
                    + "\n  innodb_trx=" + innodbTrx
                    + "\n  performance_schema.threads=" + psThreads
                    + "\n  performance_schema.data_locks=" + psDataLocks
                    + "\n  performance_schema.data_lock_waits=" + psDataLockWaits
                    + "\n  performance_schema.metadata_locks=" + psMetadataLocks
                    + "\n  sys.innodb_lock_waits=" + sysLockWaits);
        } catch (Exception diagEx) {
            System.err.println("DIAGNOSTIC - Error collecting diagnostics: " + diagEx.getMessage());
        }
    }

    // =========================================================================
    // SCENARIO C2B-01: ATTACH WINS OVER CONCURRENT DISCARD (R1-F01 & R2-SR-01)
    // =========================================================================

    @Test
    @DisplayName("C2B-01. Attach wins over concurrent discard: candidate MediaAsset lock serializes discard behind attach; contender proves lock attempt before winner commits; after attach commits, discard detects reference and rejects with ScreenVideoAlreadyReferencedException")
    void c2b01_attachWinsOverConcurrentDiscard_guardsCandidateAssetFromDeletion() throws Exception {
        UUID titleId = createTrackedTitle();
        UUID entryId = createTrackedEntry(titleId, null, 1);
        MediaAssetFixture candidate = createTrackedMediaAsset("ACTIVE");
        UUID sessionId = createTrackedUploadSession(entryId, candidate.assetId(), candidate.versionId(), candidate.storageKey(), "COMPLETED", DEFAULT_ACTOR);

        CountDownLatch attachHoldingLockLatch = new CountDownLatch(1);
        CountDownLatch releaseAttachLatch = new CountDownLatch(1);
        CountDownLatch discardAttemptingLockLatch = new CountDownLatch(1);

        testSyncClock.setSyncForThread("worker-c2b01-attach", attachHoldingLockLatch, releaseAttachLatch);
        testLockCoordinator.setBeforeLockLatch("worker-c2b01-discard", discardAttemptingLockLatch);

        // Worker 1: Winning operation
        TestWorker<ScreenEntryDTO> workerAttach = createAndRegisterWorker("worker-c2b01-attach", () ->
                attachUseCase.execute(titleId, entryId, candidate.assetId())
        );

        // Worker 2: Contender operation
        TestWorker<DiscardScreenEntryVideoUploadResult> workerDiscard = createAndRegisterWorker("worker-c2b01-discard", () -> {
            DiscardScreenEntryVideoUploadCommand cmd = new DiscardScreenEntryVideoUploadCommand(
                    titleId, entryId, sessionId, candidate.assetId(), DEFAULT_ACTOR
            );
            return discardUseCase.execute(cmd);
        });

        // 1. Start winning operation in its own transaction
        workerAttach.start();

        // 2. Stop winner at verified post-lock checkpoint
        boolean attachLocked = attachHoldingLockLatch.await(WINNER_CHECKPOINT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(attachLocked).as("Attach worker must acquire MediaAsset lock within timeout").isTrue();

        // 3. Confirm candidate MediaAsset lock via independent NOWAIT probe
        assertMediaAssetRowLockedWithNowait(candidate.assetId());

        // 4. Start competing operation on distinct worker transaction
        workerDiscard.start();

        // 5. Record contender progress toward actual locked operation (R2-SR-01)
        boolean discardReachedLock = discardAttemptingLockLatch.await(CONTENDER_LOCK_ENTRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(discardReachedLock).as("Discard worker must reach database lock acquisition attempt within timeout").isTrue();

        Long contenderConnId = testLockCoordinator.getContenderConnectionId("worker-c2b01-discard");
        assertThat(contenderConnId).as("Contender MySQL CONNECTION_ID() must be captured from active transaction").isNotNull();
        Long winnerConnId = testLockCoordinator.getContenderConnectionId("worker-c2b01-attach");

        // 6. Provide evidence of meaningful overlap or actual lock contention before releasing winner (R2-SR-01 & R4-SR-02):
        assertThat(workerDiscard.isCompleted()).as("Discard worker must NOT complete while lock is held").isFalse();
        boolean lockWaitObserved = awaitContenderInInnoDBLockWait(contenderConnId, INNODB_WAIT_OBSERVATION_TIMEOUT_MS, workerDiscard, winnerConnId);
        assertThat(lockWaitObserved)
                .as("Contender MySQL session [" + contenderConnId + "] must be physically observed in LOCK WAIT in information_schema.innodb_trx or performance_schema")
                .isTrue();
        assertMediaAssetRowLockedWithNowait(candidate.assetId());

        // 7. Commit winner: release attach worker
        releaseAttachLatch.countDown();

        // 8 & 9. Wait until both transactions have completed and observe outcomes
        terminateAndVerifyWorkers(workerAttach, workerDiscard);

        assertThat(workerAttach.getError()).isNull();
        assertThat(workerAttach.getResult()).isNotNull();
        assertThat(workerAttach.getResult().videoMediaAssetId()).isEqualTo(candidate.assetId());

        assertThat(workerDiscard.getError())
                .isNotNull()
                .isInstanceOf(ScreenVideoAlreadyReferencedException.class);

        // 10. Assert authoritative committed database state from fresh reader
        assertDatabaseState(candidate.assetId(), "ACTIVE", entryId, candidate.assetId(), sessionId, "COMPLETED");
    }

    // =========================================================================
    // SCENARIO C2B-02: DISCARD WINS OVER CONCURRENT ATTACH (R1-F01 & R2-SR-01)
    // =========================================================================

    @Test
    @DisplayName("C2B-02. Discard wins over concurrent attach: discard soft-deletes unreferenced asset; contender proves lock attempt before winner commits; concurrent attach unblocks and fails safely with ScreenMediaAssetNotFoundException because asset is DELETED")
    void c2b02_discardWinsOverConcurrentAttach_preventsAttachmentOfDeletedAsset() throws Exception {
        UUID titleId = createTrackedTitle();
        UUID entryId = createTrackedEntry(titleId, null, 1);
        MediaAssetFixture candidate = createTrackedMediaAsset("ACTIVE");
        UUID sessionId = createTrackedUploadSession(entryId, candidate.assetId(), candidate.versionId(), candidate.storageKey(), "COMPLETED", DEFAULT_ACTOR);

        CountDownLatch discardHoldingLockLatch = new CountDownLatch(1);
        CountDownLatch releaseDiscardLatch = new CountDownLatch(1);
        CountDownLatch attachAttemptingLockLatch = new CountDownLatch(1);

        testSyncClock.setSyncForThread("worker-c2b02-discard", discardHoldingLockLatch, releaseDiscardLatch);
        testLockCoordinator.setBeforeLockLatch("worker-c2b02-attach", attachAttemptingLockLatch);

        // Worker 1: Winning discard operation
        TestWorker<DiscardScreenEntryVideoUploadResult> workerDiscard = createAndRegisterWorker("worker-c2b02-discard", () -> {
            DiscardScreenEntryVideoUploadCommand cmd = new DiscardScreenEntryVideoUploadCommand(
                    titleId, entryId, sessionId, candidate.assetId(), DEFAULT_ACTOR
            );
            return discardUseCase.execute(cmd);
        });

        // Worker 2: Contender attach operation
        TestWorker<ScreenEntryDTO> workerAttach = createAndRegisterWorker("worker-c2b02-attach", () ->
                attachUseCase.execute(titleId, entryId, candidate.assetId())
        );

        // 1. Start winning operation
        workerDiscard.start();

        // 2. Stop winner at verified post-lock checkpoint
        boolean discardLocked = discardHoldingLockLatch.await(WINNER_CHECKPOINT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(discardLocked).as("Discard worker must acquire MediaAsset lock").isTrue();

        // 3. Confirm candidate MediaAsset lock via independent NOWAIT probe
        assertMediaAssetRowLockedWithNowait(candidate.assetId());

        // 4. Start competing operation
        workerAttach.start();

        // 5. Record contender progress toward actual locked operation (R2-SR-01)
        boolean attachReachedLock = attachAttemptingLockLatch.await(CONTENDER_LOCK_ENTRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(attachReachedLock).as("Attach worker must reach database lock acquisition attempt within timeout").isTrue();

        Long contenderConnId = testLockCoordinator.getContenderConnectionId("worker-c2b02-attach");
        assertThat(contenderConnId).as("Contender MySQL CONNECTION_ID() must be captured from active transaction").isNotNull();
        Long winnerConnId = testLockCoordinator.getContenderConnectionId("worker-c2b02-discard");

        // 6. Provide evidence of meaningful overlap or actual lock contention before releasing winner (R2-SR-01 & R4-SR-02):
        assertThat(workerAttach.isCompleted()).as("Attach worker must NOT complete while lock is held").isFalse();
        boolean lockWaitObserved = awaitContenderInInnoDBLockWait(contenderConnId, INNODB_WAIT_OBSERVATION_TIMEOUT_MS, workerAttach, winnerConnId);
        assertThat(lockWaitObserved)
                .as("Contender MySQL session [" + contenderConnId + "] must be physically observed in LOCK WAIT in information_schema.innodb_trx or performance_schema")
                .isTrue();
        assertMediaAssetRowLockedWithNowait(candidate.assetId());

        // 7. Commit winner: release discard worker
        releaseDiscardLatch.countDown();

        // 8 & 9. Wait until both transactions have completed and observe outcomes
        terminateAndVerifyWorkers(workerDiscard, workerAttach);

        assertThat(workerDiscard.getError()).isNull();
        assertThat(workerDiscard.getResult()).isNotNull();
        assertThat(workerDiscard.getResult().outcome()).isEqualTo(DiscardScreenEntryVideoUploadResult.DiscardOutcome.DISCARDED);

        assertThat(workerAttach.getError())
                .isNotNull()
                .isInstanceOf(ScreenMediaAssetNotFoundException.class);

        // 10. Assert authoritative committed database state from fresh reader
        assertDatabaseState(candidate.assetId(), "DELETED", entryId, null, sessionId, "COMPLETED");
    }

    // =========================================================================
    // SCENARIO C2B-03: REPLACE WINS OVER CONCURRENT DISCARD (R1-F01 & R2-SR-01)
    // =========================================================================

    @Test
    @DisplayName("C2B-03. Replace wins over concurrent discard: replacement acquires new asset lock, commits swap; contender proves lock attempt before winner commits; concurrent discard detects reference and rejects with ScreenVideoAlreadyReferencedException without deleting either asset")
    void c2b03_replaceWinsOverConcurrentDiscard_protectsNewAttachedVideoAndPreservesOldVideo() throws Exception {
        UUID titleId = createTrackedTitle();
        MediaAssetFixture oldAsset = createTrackedMediaAsset("ACTIVE");
        UUID entryId = createTrackedEntry(titleId, oldAsset.assetId(), 1);
        MediaAssetFixture newAsset = createTrackedMediaAsset("ACTIVE");
        UUID sessionId = createTrackedUploadSession(entryId, newAsset.assetId(), newAsset.versionId(), newAsset.storageKey(), "COMPLETED", DEFAULT_ACTOR);

        CountDownLatch replaceHoldingLockLatch = new CountDownLatch(1);
        CountDownLatch releaseReplaceLatch = new CountDownLatch(1);
        CountDownLatch discardAttemptingLockLatch = new CountDownLatch(1);

        testSyncClock.setSyncForThread("worker-c2b03-replace", replaceHoldingLockLatch, releaseReplaceLatch);
        testLockCoordinator.setBeforeLockLatch("worker-c2b03-discard", discardAttemptingLockLatch);

        // Worker 1: Winning replace operation
        TestWorker<ScreenEntryDTO> workerReplace = createAndRegisterWorker("worker-c2b03-replace", () ->
                replaceUseCase.execute(titleId, entryId, newAsset.assetId(), oldAsset.assetId())
        );

        // Worker 2: Contender discard operation
        TestWorker<DiscardScreenEntryVideoUploadResult> workerDiscard = createAndRegisterWorker("worker-c2b03-discard", () -> {
            DiscardScreenEntryVideoUploadCommand cmd = new DiscardScreenEntryVideoUploadCommand(
                    titleId, entryId, sessionId, newAsset.assetId(), DEFAULT_ACTOR
            );
            return discardUseCase.execute(cmd);
        });

        // 1. Start winning operation
        workerReplace.start();

        // 2. Stop winner at verified post-lock checkpoint
        boolean replaceLocked = replaceHoldingLockLatch.await(WINNER_CHECKPOINT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(replaceLocked).as("Replace worker must hold new MediaAsset lock").isTrue();

        // 3. Confirm candidate MediaAsset lock via independent NOWAIT probe
        assertMediaAssetRowLockedWithNowait(newAsset.assetId());

        // 4. Start competing operation
        workerDiscard.start();

        // 5. Record contender progress toward actual locked operation (R2-SR-01)
        boolean discardReachedLock = discardAttemptingLockLatch.await(CONTENDER_LOCK_ENTRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(discardReachedLock).as("Discard worker must reach database lock acquisition attempt within timeout").isTrue();

        Long contenderConnId = testLockCoordinator.getContenderConnectionId("worker-c2b03-discard");
        assertThat(contenderConnId).as("Contender MySQL CONNECTION_ID() must be captured from active transaction").isNotNull();
        Long winnerConnId = testLockCoordinator.getContenderConnectionId("worker-c2b03-replace");

        // 6. Provide evidence of meaningful overlap or actual lock contention before releasing winner (R2-SR-01 & R4-SR-02):
        assertThat(workerDiscard.isCompleted()).as("Discard worker must NOT complete while lock is held").isFalse();
        boolean lockWaitObserved = awaitContenderInInnoDBLockWait(contenderConnId, INNODB_WAIT_OBSERVATION_TIMEOUT_MS, workerDiscard, winnerConnId);
        assertThat(lockWaitObserved)
                .as("Contender MySQL session [" + contenderConnId + "] must be physically observed in LOCK WAIT in information_schema.innodb_trx or performance_schema")
                .isTrue();
        assertMediaAssetRowLockedWithNowait(newAsset.assetId());

        // 7. Commit winner: release replace worker
        releaseReplaceLatch.countDown();

        // 8 & 9. Wait until both transactions have completed and observe outcomes
        terminateAndVerifyWorkers(workerReplace, workerDiscard);

        assertThat(workerReplace.getError()).isNull();
        assertThat(workerReplace.getResult()).isNotNull();
        assertThat(workerReplace.getResult().videoMediaAssetId()).isEqualTo(newAsset.assetId());

        assertThat(workerDiscard.getError())
                .isNotNull()
                .isInstanceOf(ScreenVideoAlreadyReferencedException.class);

        // 10. Assert authoritative committed database state from fresh reader
        assertDatabaseState(newAsset.assetId(), "ACTIVE", entryId, newAsset.assetId(), sessionId, "COMPLETED");
        assertMediaAssetStatus(oldAsset.assetId(), "ACTIVE");
    }

    // =========================================================================
    // SCENARIO C2B-04: DISCARD WINS OVER CONCURRENT REPLACE (R1-F01 & R2-SR-01)
    // =========================================================================

    @Test
    @DisplayName("C2B-04. Discard wins over concurrent replace: discard soft-deletes new asset; contender proves lock attempt before winner commits; concurrent replace unblocks and fails safely with ScreenMediaAssetNotFoundException, preserving old usable video")
    void c2b04_discardWinsOverConcurrentReplace_preventsReplacementAndPreservesOldVideo() throws Exception {
        UUID titleId = createTrackedTitle();
        MediaAssetFixture oldAsset = createTrackedMediaAsset("ACTIVE");
        UUID entryId = createTrackedEntry(titleId, oldAsset.assetId(), 1);
        MediaAssetFixture newAsset = createTrackedMediaAsset("ACTIVE");
        UUID sessionId = createTrackedUploadSession(entryId, newAsset.assetId(), newAsset.versionId(), newAsset.storageKey(), "COMPLETED", DEFAULT_ACTOR);

        CountDownLatch discardHoldingLockLatch = new CountDownLatch(1);
        CountDownLatch releaseDiscardLatch = new CountDownLatch(1);
        CountDownLatch replaceAttemptingLockLatch = new CountDownLatch(1);

        testSyncClock.setSyncForThread("worker-c2b04-discard", discardHoldingLockLatch, releaseDiscardLatch);
        testLockCoordinator.setBeforeLockLatch("worker-c2b04-replace", replaceAttemptingLockLatch);

        // Worker 1: Winning discard operation
        TestWorker<DiscardScreenEntryVideoUploadResult> workerDiscard = createAndRegisterWorker("worker-c2b04-discard", () -> {
            DiscardScreenEntryVideoUploadCommand cmd = new DiscardScreenEntryVideoUploadCommand(
                    titleId, entryId, sessionId, newAsset.assetId(), DEFAULT_ACTOR
            );
            return discardUseCase.execute(cmd);
        });

        // Worker 2: Contender replace operation
        TestWorker<ScreenEntryDTO> workerReplace = createAndRegisterWorker("worker-c2b04-replace", () ->
                replaceUseCase.execute(titleId, entryId, newAsset.assetId(), oldAsset.assetId())
        );

        // 1. Start winning operation
        workerDiscard.start();

        // 2. Stop winner at verified post-lock checkpoint
        boolean discardLocked = discardHoldingLockLatch.await(WINNER_CHECKPOINT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(discardLocked).as("Discard worker must acquire lock").isTrue();

        // 3. Confirm candidate MediaAsset lock via independent NOWAIT probe
        assertMediaAssetRowLockedWithNowait(newAsset.assetId());

        // 4. Start competing operation
        workerReplace.start();

        // 5. Record contender progress toward actual locked operation (R2-SR-01)
        boolean replaceReachedLock = replaceAttemptingLockLatch.await(CONTENDER_LOCK_ENTRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(replaceReachedLock).as("Replace worker must reach database lock acquisition attempt within timeout").isTrue();

        Long contenderConnId = testLockCoordinator.getContenderConnectionId("worker-c2b04-replace");
        assertThat(contenderConnId).as("Contender MySQL CONNECTION_ID() must be captured from active transaction").isNotNull();
        Long winnerConnId = testLockCoordinator.getContenderConnectionId("worker-c2b04-discard");

        // 6. Provide evidence of meaningful overlap or actual lock contention before releasing winner (R2-SR-01 & R4-SR-02):
        assertThat(workerReplace.isCompleted()).as("Replace worker must NOT complete while lock is held").isFalse();
        boolean lockWaitObserved = awaitContenderInInnoDBLockWait(contenderConnId, INNODB_WAIT_OBSERVATION_TIMEOUT_MS, workerReplace, winnerConnId);
        assertThat(lockWaitObserved)
                .as("Contender MySQL session [" + contenderConnId + "] must be physically observed in LOCK WAIT in information_schema.innodb_trx or performance_schema")
                .isTrue();
        assertMediaAssetRowLockedWithNowait(newAsset.assetId());

        // 7. Commit winner: release discard worker
        releaseDiscardLatch.countDown();

        // 8 & 9. Wait until both transactions have completed and observe outcomes
        terminateAndVerifyWorkers(workerDiscard, workerReplace);

        assertThat(workerDiscard.getError()).isNull();
        assertThat(workerDiscard.getResult()).isNotNull();
        assertThat(workerDiscard.getResult().outcome()).isEqualTo(DiscardScreenEntryVideoUploadResult.DiscardOutcome.DISCARDED);

        assertThat(workerReplace.getError())
                .isNotNull()
                .isInstanceOf(ScreenMediaAssetNotFoundException.class);

        // 10. Assert authoritative committed database state from fresh reader
        assertDatabaseState(newAsset.assetId(), "DELETED", entryId, oldAsset.assetId(), sessionId, "COMPLETED");
        assertMediaAssetStatus(oldAsset.assetId(), "ACTIVE");
    }

    // =========================================================================
    // SCENARIO C2B-05: EXISTING REFERENCE REJECTION
    // =========================================================================

    @Test
    @DisplayName("C2B-05. Existing reference rejection: discard rejects an asset already referenced by a ScreenEntry without deleting it")
    void c2b05_existingReferenceRejection_blocksDiscardWithoutMutatingAsset() {
        UUID titleId = createTrackedTitle();
        MediaAssetFixture asset = createTrackedMediaAsset("ACTIVE");
        UUID entryId = createTrackedEntry(titleId, asset.assetId(), 1); // Already referenced!
        UUID sessionId = createTrackedUploadSession(entryId, asset.assetId(), asset.versionId(), asset.storageKey(), "COMPLETED", DEFAULT_ACTOR);

        DiscardScreenEntryVideoUploadCommand cmd = new DiscardScreenEntryVideoUploadCommand(
                titleId, entryId, sessionId, asset.assetId(), DEFAULT_ACTOR
        );

        assertThatThrownBy(() -> discardUseCase.execute(cmd))
                .isInstanceOf(ScreenVideoAlreadyReferencedException.class);

        assertDatabaseState(asset.assetId(), "ACTIVE", entryId, asset.assetId(), sessionId, "COMPLETED");
    }

    // =========================================================================
    // SCENARIO C2B-06: DELETED IDEMPOTENCY & REFERENCED REJECTION
    // =========================================================================

    @Test
    @DisplayName("C2B-06. DELETED idempotency: unreferenced DELETED asset returns ALREADY_DISCARDED; referenced DELETED asset rejects with reference conflict before outcome evaluation")
    void c2b06_deletedIdempotency_returnsAlreadyDiscardedWhenUnreferenced_rejectsWhenReferenced() {
        // SR-07: Separate distinct titles and episode numbers for sub-scenarios A and B to eliminate unique collisions
        // Sub-scenario 6A: Unreferenced DELETED asset -> ALREADY_DISCARDED
        UUID titleIdA = createTrackedTitle();
        UUID entryIdA = createTrackedEntry(titleIdA, null, 1);
        MediaAssetFixture assetA = createTrackedMediaAsset("DELETED");
        UUID sessionIdA = createTrackedUploadSession(entryIdA, assetA.assetId(), assetA.versionId(), assetA.storageKey(), "COMPLETED", DEFAULT_ACTOR);

        DiscardScreenEntryVideoUploadResult resultA = discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(titleIdA, entryIdA, sessionIdA, assetA.assetId(), DEFAULT_ACTOR)
        );

        assertThat(resultA.outcome()).isEqualTo(DiscardScreenEntryVideoUploadResult.DiscardOutcome.ALREADY_DISCARDED);
        assertDatabaseState(assetA.assetId(), "DELETED", entryIdA, null, sessionIdA, "COMPLETED");

        // Sub-scenario 6B: Referenced DELETED asset -> Rejection with ScreenVideoAlreadyReferencedException
        UUID titleIdB = createTrackedTitle();
        MediaAssetFixture assetB = createTrackedMediaAsset("DELETED");
        UUID entryIdB = createTrackedEntry(titleIdB, assetB.assetId(), 1); // Referenced!
        UUID sessionIdB = createTrackedUploadSession(entryIdB, assetB.assetId(), assetB.versionId(), assetB.storageKey(), "COMPLETED", DEFAULT_ACTOR);

        assertThatThrownBy(() -> discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(titleIdB, entryIdB, sessionIdB, assetB.assetId(), DEFAULT_ACTOR)
        )).isInstanceOf(ScreenVideoAlreadyReferencedException.class);

        assertDatabaseState(assetB.assetId(), "DELETED", entryIdB, assetB.assetId(), sessionIdB, "COMPLETED");
    }

    // =========================================================================
    // SCENARIO C2B-07: STALE REPEATABLE READ SNAPSHOT BREAKTHROUGH
    // =========================================================================

    @Test
    @DisplayName("C2B-07. Stale snapshot under REPEATABLE READ: discard's locking current-read breaks through MVCC snapshot to observe committed reference and prevent deletion")
    void c2b07_staleRepeatableReadSnapshot_breaksThroughMvccSnapshotWithCurrentRead() throws Exception {
        UUID titleId = createTrackedTitle();
        UUID entryId = createTrackedEntry(titleId, null, 1);
        MediaAssetFixture candidate = createTrackedMediaAsset("ACTIVE");
        UUID sessionId = createTrackedUploadSession(entryId, candidate.assetId(), candidate.versionId(), candidate.storageKey(), "COMPLETED", DEFAULT_ACTOR);

        DefaultTransactionDefinition txDefA = new DefaultTransactionDefinition();
        txDefA.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txDefA.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        TransactionTemplate txA = new TransactionTemplate(transactionManager, txDefA);
        TransactionTemplate txB = new TransactionTemplate(transactionManager);
        txB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch txAEstablishedSnapshotLatch = new CountDownLatch(1);
        CountDownLatch txBCommittedLatch = new CountDownLatch(1);

        // R1-F06: Track worker in test-instance worker registry
        TestWorker<Void> threadTxA = createAndRegisterWorker("thread-c2b07-txA", () -> {
            txA.execute(status -> {
                try {
                    // Ordinary SELECT sets MVCC snapshot in Transaction A seeing 0 referencing entries
                    Integer countBefore = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM screen_entries WHERE video_media_asset_id = ?",
                            Integer.class,
                            candidate.assetId().toString()
                    );
                    assertThat(countBefore).isEqualTo(0);
                } finally {
                    txAEstablishedSnapshotLatch.countDown();
                }

                try {
                    if (!txBCommittedLatch.await(WINNER_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("Timed out waiting for TxB commit");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }

                // Under REPEATABLE READ, ordinary SELECT still sees stale snapshot count = 0
                Integer countStale = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM screen_entries WHERE video_media_asset_id = ?",
                        Integer.class,
                        candidate.assetId().toString()
                );
                assertThat(countStale).as("Ordinary read in TxA must see stale snapshot 0").isEqualTo(0);

                // Discard use case executes findEntryIdsByVideoMediaAssetIdForUpdate (InnoDB current-read FOR UPDATE)
                // Breaking through the snapshot to see committed reference!
                DiscardScreenEntryVideoUploadCommand cmd = new DiscardScreenEntryVideoUploadCommand(
                        titleId, entryId, sessionId, candidate.assetId(), DEFAULT_ACTOR
                );
                discardUseCase.execute(cmd);
                return null;
            });
            return null;
        });

        threadTxA.start();

        try {
            boolean snapshotReady = txAEstablishedSnapshotLatch.await(WINNER_CHECKPOINT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertThat(snapshotReady).as("TxA must establish snapshot").isTrue();

            // Transaction B: Attach asset to entry and commit
            txB.execute(status -> {
                attachUseCase.execute(titleId, entryId, candidate.assetId());
                return null;
            });
            txBCommittedLatch.countDown();

            terminateAndVerifyWorkers(threadTxA);
        } finally {
            txBCommittedLatch.countDown();
            terminateAndVerifyWorkers(threadTxA);
        }

        assertThat(threadTxA.getError())
                .isNotNull()
                .isInstanceOf(ScreenVideoAlreadyReferencedException.class);

        // Fresh transaction proves candidate asset was protected and never deleted
        assertDatabaseState(candidate.assetId(), "ACTIVE", entryId, candidate.assetId(), sessionId, "COMPLETED");
    }

    // =========================================================================
    // SCENARIO C2B-08: DIRECT LOCKED LOOKUP INTEGRATION (R1-F05)
    // =========================================================================

    @Test
    @DisplayName("C2B-08. Direct locked lookup integration: validates target ScreenEntry under pessimistic row lock without prior unlocked reads")
    void c2b08_directLockedLookupIntegration_validatesTargetUnderRowLockWithoutPriorUnlockedRead() {
        UUID titleId = createTrackedTitle();
        UUID entryId = createTrackedEntry(titleId, null, 1);
        MediaAssetFixture candidate = createTrackedMediaAsset("ACTIVE");
        UUID sessionId = createTrackedUploadSession(entryId, candidate.assetId(), candidate.versionId(), candidate.storageKey(), "COMPLETED", DEFAULT_ACTOR);

        // Accurately scoped proof: Discard directly executes entryRepository.findByIdForUpdate(entryId)
        // without an unlocked pre-read, eliminating the specific L1 cache staleness risk identified in C1-R2.
        DiscardScreenEntryVideoUploadResult result = discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(titleId, entryId, sessionId, candidate.assetId(), DEFAULT_ACTOR)
        );

        assertThat(result.outcome()).isEqualTo(DiscardScreenEntryVideoUploadResult.DiscardOutcome.DISCARDED);
        assertDatabaseState(candidate.assetId(), "DELETED", entryId, null, sessionId, "COMPLETED");
    }

    // =========================================================================
    // SCENARIO C2B-09: PHYSICAL TRANSACTION ROLLBACK INTEGRITY (R2-SR-02)
    // =========================================================================

    @Test
    @DisplayName("C2B-09. Physical transaction rollback integrity: verifies in-transaction soft-delete with explicit JPA entity flush prior to forced rollback, and verifies post-rollback restoration to ACTIVE")
    void c2b09_physicalRollback_preservesActiveStateWhenOuterTransactionRollsBack() {
        UUID titleId = createTrackedTitle();
        UUID entryId = createTrackedEntry(titleId, null, 1);
        MediaAssetFixture candidate = createTrackedMediaAsset("ACTIVE");
        UUID sessionId = createTrackedUploadSession(entryId, candidate.assetId(), candidate.versionId(), candidate.storageKey(), "COMPLETED", DEFAULT_ACTOR);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // R2-SR-02: Execute in outer transaction, flush JPA changes across JDBC boundary, verify in-transaction DELETED state, then force rollback
        assertThatThrownBy(() -> txTemplate.execute(status -> {
            DiscardScreenEntryVideoUploadCommand cmd = new DiscardScreenEntryVideoUploadCommand(
                    titleId, entryId, sessionId, candidate.assetId(), DEFAULT_ACTOR
            );
            DiscardScreenEntryVideoUploadResult discardResult = discardUseCase.execute(cmd);
            assertThat(discardResult.outcome()).isEqualTo(DiscardScreenEntryVideoUploadResult.DiscardOutcome.DISCARDED);

            // Step 3 of R2-SR-02: Explicitly flush pending JPA entity mutations to physical DB before JDBC inspection
            entityManager.flush();

            // Step 4 of R2-SR-02: Verify within the same uncommitted transaction that MediaAsset was marked DELETED in MySQL
            String statusInTx = jdbcTemplate.queryForObject(
                    "SELECT status FROM media_assets WHERE id = ?", String.class, candidate.assetId().toString()
            );
            assertThat(statusInTx).as("MediaAsset must be DELETED within the active transaction").isEqualTo("DELETED");

            // Step 6: Force deliberate transaction rollback
            throw new RuntimeException("Test forced physical transaction rollback");
        })).isInstanceOf(RuntimeException.class)
                .hasMessage("Test forced physical transaction rollback");

        // Fresh database verification: MediaAsset status must physically roll back to ACTIVE
        assertDatabaseState(candidate.assetId(), "ACTIVE", entryId, null, sessionId, "COMPLETED");
    }

    // =========================================================================
    // SCENARIO C2B-10: AUTHORIZATION FAILURE IS NON-MUTATING
    // =========================================================================

    @Test
    @DisplayName("C2B-10. Authorization failure is non-mutating: unauthorized requests reject before locks or DB mutations")
    void c2b10_authorizationNonMutation_rejectsUnauthorizedAttemptsBeforeMutation() {
        UUID titleId = createTrackedTitle();
        UUID entryId = createTrackedEntry(titleId, null, 1);
        MediaAssetFixture asset = createTrackedMediaAsset("ACTIVE");
        UUID sessionId = createTrackedUploadSession(entryId, asset.assetId(), asset.versionId(), asset.storageKey(), "COMPLETED", DEFAULT_ACTOR);

        // 1. Wrong actor -> 403 Access Denied
        assertThatThrownBy(() -> discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(titleId, entryId, sessionId, asset.assetId(), "impostor@example.com")
        )).isInstanceOf(ScreenVideoDiscardAccessDeniedException.class);

        // 2. Wrong entry ID -> Target mismatch validation error
        UUID wrongEntryId = UUID.randomUUID();
        assertThatThrownBy(() -> discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(titleId, wrongEntryId, sessionId, asset.assetId(), DEFAULT_ACTOR)
        )).isInstanceOf(ScreenVideoDiscardValidationException.class);

        // 3. Wrong registered asset ID -> Asset mismatch validation error
        UUID wrongAssetId = UUID.randomUUID();
        assertThatThrownBy(() -> discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(titleId, entryId, sessionId, wrongAssetId, DEFAULT_ACTOR)
        )).isInstanceOf(ScreenVideoDiscardValidationException.class);

        // 4. Mismatched title ID -> Entry not found in target title error
        UUID wrongTitleId = UUID.randomUUID();
        assertThatThrownBy(() -> discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(wrongTitleId, entryId, sessionId, asset.assetId(), DEFAULT_ACTOR)
        )).isInstanceOf(ScreenEntryNotFoundException.class);

        // Verify zero mutations occurred in database
        assertDatabaseState(asset.assetId(), "ACTIVE", entryId, null, sessionId, "COMPLETED");
    }

    // =========================================================================
    // SCENARIO C2B-11: PUBLICATION SAFETY (R1-F05)
    // =========================================================================

    @Test
    @DisplayName("C2B-11. Publication safety: editorial publication status (PUBLISHED, exact publishedAt) remains unmodified across successful attach and failed replace")
    void c2b11_publicationSafety_preservesPublishedStateDuringAttachmentAndFailedReplacement() {
        Instant exactPublishedAt = Instant.parse("2026-10-15T08:00:00Z");
        // R1-F05: Realistic published title and entry fixture conforming to schema publication CHECK constraints
        UUID titleId = createTrackedPublishedTitle(exactPublishedAt);
        MediaAssetFixture oldAsset = createTrackedMediaAsset("ACTIVE");
        UUID entryId = createTrackedPublishedEntry(titleId, null, 1, exactPublishedAt);

        // Step 1: Record initial published state before mutation
        Map<String, Object> initialRow = jdbcTemplate.queryForMap(
                "SELECT status, published_at, video_media_asset_id FROM screen_entries WHERE id = ?",
                entryId.toString()
        );
        assertThat(initialRow.get("status")).isEqualTo("PUBLISHED");
        assertThat(initialRow.get("video_media_asset_id")).isNull();
        assertThat(initialRow.get("published_at")).isNotNull();

        // Step 2: Successful video mutation (Attach)
        ScreenEntryDTO attachResult = attachUseCase.execute(titleId, entryId, oldAsset.assetId());
        assertThat(attachResult.videoMediaAssetId()).isEqualTo(oldAsset.assetId());

        // Verify published status and exact publishedAt timestamp remain unmodified
        Map<String, Object> afterAttachRow = jdbcTemplate.queryForMap(
                "SELECT status, published_at, video_media_asset_id FROM screen_entries WHERE id = ?",
                entryId.toString()
        );
        assertThat(afterAttachRow.get("status")).isEqualTo("PUBLISHED");
        assertThat(afterAttachRow.get("video_media_asset_id")).isEqualTo(oldAsset.assetId().toString());
        assertThat(afterAttachRow.get("published_at")).isEqualTo(initialRow.get("published_at"));

        // Step 3: Failed video mutation (Replace with mismatched expectedCurrentVideoMediaAssetId)
        MediaAssetFixture newAsset = createTrackedMediaAsset("ACTIVE");
        UUID wrongExpectedId = UUID.randomUUID();

        assertThatThrownBy(() -> replaceUseCase.execute(titleId, entryId, newAsset.assetId(), wrongExpectedId))
                .isInstanceOf(com.universe.screen.application.exceptions.ScreenEntryVideoConflictException.class);

        // Verify that ScreenEntry status, exact publishedAt, and old video reference are completely intact
        Map<String, Object> afterFailedReplaceRow = jdbcTemplate.queryForMap(
                "SELECT status, published_at, video_media_asset_id FROM screen_entries WHERE id = ?",
                entryId.toString()
        );
        assertThat(afterFailedReplaceRow.get("status")).isEqualTo("PUBLISHED");
        assertThat(afterFailedReplaceRow.get("video_media_asset_id")).isEqualTo(oldAsset.assetId().toString());
        assertThat(afterFailedReplaceRow.get("published_at")).isEqualTo(initialRow.get("published_at"));
    }

    // =========================================================================
    // SCENARIO C2B-12: SELECTED SPRING TEST CONTEXT WIRING PROOF
    // =========================================================================

    @Test
    @DisplayName("C2B-12. Selected Spring context wiring proof: verifies container instantiation, @Transactional proxies, real DataSource/TransactionManager")
    void c2b12_realSpringWiring_provesContainerInstantiationAndTransactionalProxies() {
        assertThat(discardUseCase).isNotNull();
        assertThat(attachUseCase).isNotNull();
        assertThat(replaceUseCase).isNotNull();
        assertThat(titleRepository).isNotNull();
        assertThat(entryRepository).isNotNull();
        assertThat(mediaValidationPort).isNotNull();
        assertThat(transactionManager).isNotNull();
        assertThat(dataSource).isNotNull();

        assertThat(AopUtils.isAopProxy(discardUseCase))
                .as("DiscardScreenEntryVideoUploadUseCase must be wrapped in Spring @Transactional AOP proxy")
                .isTrue();
        assertThat(AopUtils.isAopProxy(attachUseCase))
                .as("AttachScreenEntryVideoUseCase must be wrapped in Spring @Transactional AOP proxy")
                .isTrue();
        assertThat(AopUtils.isAopProxy(replaceUseCase))
                .as("ReplaceScreenEntryVideoUseCase must be wrapped in Spring @Transactional AOP proxy")
                .isTrue();
    }

    // =========================================================================
    // ASSERTION & CONCURRENCY PROBE HELPERS (R1-F02 ERROR CLASSIFICATION)
    // =========================================================================

    private void assertDatabaseState(
            UUID assetId,
            String expectedAssetStatus,
            UUID entryId,
            UUID expectedVideoAssetId,
            UUID sessionId,
            String expectedSessionStatus
    ) {
        Map<String, Object> assetRow = jdbcTemplate.queryForMap(
                "SELECT status FROM media_assets WHERE id = ?", assetId.toString()
        );
        assertThat(assetRow.get("status")).isEqualTo(expectedAssetStatus);

        Map<String, Object> entryRow = jdbcTemplate.queryForMap(
                "SELECT video_media_asset_id FROM screen_entries WHERE id = ?", entryId.toString()
        );
        Object actualVideo = entryRow.get("video_media_asset_id");
        if (expectedVideoAssetId == null) {
            assertThat(actualVideo).isNull();
        } else {
            assertThat(actualVideo).isEqualTo(expectedVideoAssetId.toString());
        }

        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status FROM media_upload_sessions WHERE id = ?", sessionId.toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo(expectedSessionStatus);
    }

    private void assertMediaAssetStatus(UUID assetId, String expectedStatus) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?", String.class, assetId.toString()
        );
        assertThat(status).isEqualTo(expectedStatus);
    }

    private void assertMediaAssetRowLockedWithNowait(UUID assetId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id FROM media_assets WHERE id = ? FOR UPDATE NOWAIT")) {
                stmt.setString(1, assetId.toString());
                stmt.executeQuery();
                throw new AssertionError("Expected ER_LOCK_NOWAIT (3572) on locked media_assets row, but query succeeded!");
            } catch (SQLException e) {
                if (!isLockNowaitError(e)) {
                    throw new AssertionError("Unexpected database error (expected MySQL error code 3572 ER_LOCK_NOWAIT, got "
                            + e.getErrorCode() + "): " + e.getMessage(), e);
                }
            } finally {
                conn.rollback();
            }
        }
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

    // =========================================================================
    // TEST WIRING CONFIGURATION & THREAD COORDINATION (R1-F01, SR-09 & R2-SR-01)
    // =========================================================================

    @TestConfiguration
    static class TestWiringConfig {

        @Bean
        public TestSyncClock testSyncClock() {
            return new TestSyncClock();
        }

        @Bean
        public ClockPort clockPort(TestSyncClock clock) {
            return clock;
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
        public MediaUploadSessionService mediaUploadSessionService(
                MediaUploadSessionRepositoryPort sessionRepositoryPort,
                ClockPort clockPort,
                PlatformTransactionManager transactionManager
        ) {
            return new MediaUploadSessionService(
                    sessionRepositoryPort,
                    Mockito.mock(StorageKeyAllocationService.class),
                    Mockito.mock(MediaStorageRoutingService.class),
                    clockPort,
                    transactionManager,
                    null,
                    null,
                    null
            );
        }

        @Bean
        public MediaContract mediaContract(
                DeleteMediaAssetUseCase deleteMediaAssetUseCase,
                LockAndValidateActiveMediaAssetUseCase lockAndValidateActiveMediaAssetUseCase,
                LockAndInspectMediaAssetUseCase lockAndInspectMediaAssetUseCase,
                MediaUploadSessionService mediaUploadSessionService
        ) {
            return new MediaFacade(
                    Mockito.mock(GetMediaAssetDetailUseCase.class),
                    Mockito.mock(GetMediaAssetCurrentMetadataUseCase.class),
                    Mockito.mock(ChangeMediaVisibilityUseCase.class),
                    Mockito.mock(ArchiveMediaAssetUseCase.class),
                    Mockito.mock(RestoreMediaAssetUseCase.class),
                    deleteMediaAssetUseCase,
                    Mockito.mock(UploadMediaAssetUseCase.class),
                    Mockito.mock(UploadMediaAssetVersionUseCase.class),
                    Mockito.mock(GenerateMediaImageVariantUseCase.class),
                    Mockito.mock(GetCurrentMediaAssetVersionSnapshotUseCase.class),
                    Mockito.mock(OpenMediaAssetVersionContentUseCase.class),
                    Mockito.mock(AssignMediaAssetClientTagUseCase.class),
                    Mockito.mock(FindActiveMediaAssetsByClientTagKeysetUseCase.class),
                    Mockito.mock(UploadMediaAssetVersionConditionalUseCase.class),
                    lockAndValidateActiveMediaAssetUseCase,
                    lockAndInspectMediaAssetUseCase,
                    mediaUploadSessionService,
                    null
            );
        }
    }

    /**
     * R1-F01 & R2-SR-01: Test-only lock coordinator.
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
     * R1-F01 & R2-SR-01: Coordinating delegating repository.
     * Executes SELECT CONNECTION_ID() within the contender's active Spring transaction to correlate
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
            // Issue B: Capture connectionId directly from the active JPA transaction's physical connection
            // to ensure it matches the connection performing findByIdForUpdate
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
            // Fallback: jdbcTemplate
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

    /**
     * SR-09 & R2-SR-03: Thread-scoped, non-invasive coordination clock.
     * Signals post-lock checkpoint and pauses until released. Full releaseAll() guarantees
     * all latches are counted down on teardown.
     */
    static class TestSyncClock implements ClockPort {

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

            // Only synchronize the targeted worker thread
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
}
