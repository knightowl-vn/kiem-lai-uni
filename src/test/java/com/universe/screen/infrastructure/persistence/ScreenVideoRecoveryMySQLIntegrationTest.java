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
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.MediaUploadSessionService;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.application.variant.GenerateMediaImageVariantUseCase;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.infrastructure.persistence.MediaAssetPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaAssetVersionPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaUploadSessionJpaEntity;
import com.universe.media.infrastructure.persistence.MediaUploadSessionPersistenceAdapter;
import com.universe.media.infrastructure.persistence.SpringDataMediaUploadSessionJpaRepository;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadCommand;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult;
import com.universe.screen.application.dto.FindRecoverableScreenVideoUploadsQuery;
import com.universe.screen.application.dto.RecoverableVideoUploadPageDTO;
import com.universe.screen.application.dto.RecoverableVideoUploadView;
import com.universe.screen.application.dto.RecoveryClassification;
import com.universe.screen.application.dto.ScreenVideoOperationIntent;
import com.universe.screen.application.exceptions.ScreenVideoAlreadyReferencedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardAccessDeniedException;
import com.universe.screen.application.usecase.DiscardScreenEntryVideoUploadUseCase;
import com.universe.screen.application.usecase.FindRecoverableScreenVideoUploadsUseCase;
import com.universe.screen.infrastructure.media.ScreenMediaAssetReferenceCheckAdapter;
import com.universe.screen.infrastructure.media.ScreenMediaValidationAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Physical MySQL Integration Verification Test Suite for Screen Video Recovery Discovery
 * and Missing-Target Guarded Discard (MS-08E2 / S3-3C).
 *
 * <p>Scope and Invariant Coverage:
 * <ul>
 *   <li><b>Category A (Recovery Discovery):</b>
 *     <ul>
 *       <li>A1: SQL-level actor/owner isolation before pagination.</li>
 *       <li>A2: Media type filtering in SELECT and count queries (non-matching mediaType returns empty; V89 DDL prohibits non-VIDEO insertion).</li>
 *       <li>A3: Non-COMPLETED status exclusion in SELECT and count queries (INITIATED, ABORTED, FAILED with null asset IDs excluded).</li>
 *       <li>A4: Registered asset presence constraint enforcement (V89 DDL chk_media_upload_sessions_completion strictly prohibits null registered_asset_id for COMPLETED).</li>
 *       <li>A5: Deterministic tie-breaking on equal completedAt timestamps anchored relative to clock (ORDER BY s.completedAt DESC, s.id ASC).</li>
 *       <li>A6: Accurate database-level pagination, boundary clamping, and total elements using clock-anchored relative timestamps.</li>
 *       <li>A7: Target missing recovery discovery (TARGET_MISSING, [TARGET_DELETED], canDiscard=true, canRetry=false) with canonical client tag.</li>
 *       <li>A8: Reference status classification (UNATTACHED vs ATTACHED_TO_TARGET vs REFERENCED_ELSEWHERE) with canonical client tag.</li>
 *     </ul>
 *   </li>
 *   <li><b>Category B (Missing-Target Guarded Discard):</b>
 *     <ul>
 *       <li>B1: Unreferenced ACTIVE video discard when target entry is missing (session-rooted discard).</li>
 *       <li>B2: Non-owner access denial when target entry is missing.</li>
 *       <li>B3: SUPER_ADMIN bypass rejection when target entry is missing (actor ownership non-bypassable).</li>
 *       <li>B4: Referenced conflict protection when target entry is missing (foreign entry reference blocks discard).</li>
 *       <li>B5: ALREADY_DISCARDED idempotency when target entry is missing.</li>
 *       <li>B6: Session status preservation (MediaUploadSession remains COMPLETED, cleanup NONE).</li>
 *       <li>B7: Zero physical B2 storage deletion (only soft-delete in MySQL).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>SAFETY STATUS: PREPARED FOR HUMAN EXECUTION — NOT EXECUTED IN CURRENT AGENT TURN.
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
        ScreenMediaAssetReferenceCheckAdapter.class,
        ScreenMediaValidationAdapter.class,
        DiscardScreenEntryVideoUploadUseCase.class,
        FindRecoverableScreenVideoUploadsUseCase.class,
        DeleteMediaAssetUseCase.class,
        GetMediaAssetDetailUseCase.class,
        LockAndInspectMediaAssetUseCase.class,
        LockAndValidateActiveMediaAssetUseCase.class,
        ScreenVideoRecoveryMySQLIntegrationTest.TestWiringConfig.class
})
@DisplayName("Screen Video Recovery Discovery & Guarded Discard Real MySQL Integration Tests")
class ScreenVideoRecoveryMySQLIntegrationTest {

    static final int MYSQL_INNODB_LOCK_WAIT_SECONDS = 3;
    static final int JDBC_SOCKET_TIMEOUT_MS = 7000;

    private static final String ACTOR_ALICE = "actor-alice@example.com";
    private static final String ACTOR_BOB = "actor-bob@example.com";
    private static final String ACTOR_SUPERADMIN = "superadmin@example.com";
    private static final String DEFAULT_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        String host = TestDatabaseSupport.resolveHost();
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        // Strict pre-context guard executes BEFORE adding properties and BEFORE DataSource initialization
        ScreenTestDatabaseTargetGuard.validateTarget(host, dbName);
        TestDatabaseSupport.configureDynamicProperties(registry);

        // Local-only bounded wait strategy:
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET SESSION innodb_lock_wait_timeout=" + MYSQL_INNODB_LOCK_WAIT_SECONDS);
        String baseJdbcUrl = TestDatabaseSupport.resolveJdbcUrl();
        String boundedJdbcUrl = baseJdbcUrl + "&socketTimeout=" + JDBC_SOCKET_TIMEOUT_MS;
        registry.add("spring.datasource.url", () -> boundedJdbcUrl);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FindRecoverableScreenVideoUploadsUseCase findRecoverableUseCase;

    @Autowired
    private DiscardScreenEntryVideoUploadUseCase discardUseCase;

    @Autowired
    private SpringDataMediaUploadSessionJpaRepository sessionRepository;

    // Tracked IDs for strict foreign-key ordered teardown per test
    private final List<UUID> trackedEntryIds = new ArrayList<>();
    private final List<UUID> trackedTitleIds = new ArrayList<>();
    private final List<UUID> trackedSessionIds = new ArrayList<>();
    private final List<UUID> trackedAllocationIds = new ArrayList<>();
    private final List<UUID> trackedAssetIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
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

    // =========================================================================
    // FIXTURE FACTORIES
    // =========================================================================

    private UUID createTrackedTitle() {
        UUID titleId = UUID.randomUUID();
        trackedTitleIds.add(titleId);
        Timestamp ts = Timestamp.from(Instant.now().minus(Duration.ofMinutes(30)));
        jdbcTemplate.update("""
                INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at)
                VALUES (?, ?, 'Title Fixture', 'SERIES', 'LIVE_ACTION', 'DRAFT', ?, ?)
                """, titleId.toString(), "title-" + titleId.toString().substring(0, 8), ts, ts);
        return titleId;
    }

    private UUID createTrackedEntry(UUID titleId, UUID videoAssetId, int episodeNumber) {
        UUID entryId = UUID.randomUUID();
        trackedEntryIds.add(entryId);
        String entryTitle = "Episode " + episodeNumber;
        Timestamp ts = Timestamp.from(Instant.now().minus(Duration.ofMinutes(30)));
        jdbcTemplate.update("""
                INSERT INTO screen_entries (id, title_id, slug, type, number, title, video_media_asset_id, status, created_at, updated_at)
                VALUES (?, ?, ?, 'EPISODE', ?, ?, ?, 'DRAFT', ?, ?)
                """, entryId.toString(), titleId.toString(), "entry-" + entryId.toString().substring(0, 8),
                episodeNumber, entryTitle,
                videoAssetId != null ? videoAssetId.toString() : null, ts, ts);
        return entryId;
    }

    private record MediaAssetFixture(UUID assetId, UUID versionId, String storageKey) {}

    private MediaAssetFixture createTrackedMediaAsset(String mediaType, String status) {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        trackedAssetIds.add(assetId);
        String extension = "VIDEO".equalsIgnoreCase(mediaType) ? ".mp4" : ".png";
        String mime = "VIDEO".equalsIgnoreCase(mediaType) ? "video/mp4" : "image/png";
        String storageKey = mediaType.toLowerCase() + "s/" + assetId + extension;
        Timestamp ts = Timestamp.from(Instant.now().minus(Duration.ofMinutes(30)));

        jdbcTemplate.update("""
                INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version)
                VALUES (?, ?, 'PRIVATE', ?, 1, ?, ?, 0)
                """, assetId.toString(), mediaType, status, ts, ts);

        jdbcTemplate.update("""
                INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, original_filename, created_at)
                VALUES (?, ?, 1, 'b2', ?, 'https://cdn.example.com/asset', ?, ?, 26214400, ?, ?)
                """, versionId.toString(), assetId.toString(), storageKey, DEFAULT_HASH, mime, "file" + extension, ts);

        return new MediaAssetFixture(assetId, versionId, storageKey);
    }

    private UUID createTrackedUploadSession(
            UUID targetEntryId,
            UUID registeredAssetId,
            UUID versionId,
            String storageKey,
            String mediaType,
            String status,
            String actor,
            Instant completedAt,
            String clientTag
    ) {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        trackedSessionIds.add(sessionId);
        trackedAllocationIds.add(allocId);

        // Deterministic relative timestamps anchored in the past
        Instant baseTime = completedAt != null
                ? completedAt.minus(Duration.ofMinutes(5))
                : Instant.now().minus(Duration.ofMinutes(25)).truncatedTo(ChronoUnit.MILLIS);
        Instant effectiveCompletedAt = "COMPLETED".equalsIgnoreCase(status)
                ? (completedAt != null ? completedAt : baseTime.plus(Duration.ofMinutes(5)))
                : null;
        Instant sessionExpiresAt = baseTime.plus(Duration.ofDays(1));

        Timestamp tsCreated = Timestamp.from(baseTime);
        Timestamp tsUpdated = Timestamp.from(effectiveCompletedAt != null ? effectiveCompletedAt : baseTime);
        Timestamp tsCompleted = effectiveCompletedAt != null ? Timestamp.from(effectiveCompletedAt) : null;
        Timestamp tsExpires = Timestamp.from(sessionExpiresAt);

        // F2: Use canonical production clientTag ("screen-video")
        String effectiveClientTag = clientTag != null ? clientTag : ScreenVideoOperationIntent.CLIENT_TAG_INITIAL;

        // V89 storage key allocation invariant: COMMITTED for COMPLETED sessions with asset version; RESERVED for in-flight sessions
        String allocStatus = "COMPLETED".equalsIgnoreCase(status) && versionId != null ? "COMMITTED" : "RESERVED";
        String allocByType = "COMPLETED".equalsIgnoreCase(status) && versionId != null ? "ASSET_VERSION" : "UPLOAD_SESSION";
        String allocById = "COMPLETED".equalsIgnoreCase(status) && versionId != null ? versionId.toString() : sessionId.toString();

        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at, persistence_version
                ) VALUES (?, 'b2', 'kiemlai-video', ?, ?, ?, ?, ?, ?, 0)
                """, allocId.toString(), storageKey, allocStatus, allocByType, allocById, tsCreated, tsCreated);

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
                    ?, ?, 'video/mp4', 26214400,
                    'b2', 'kiemlai-video', ?, ?,
                    ?, 26214400, 1, ?,
                    'video.mp4', ?, ?, ?,
                    ?, 'NONE', 0,
                    ?, ?, ?, 0
                )
                """,
                sessionId.toString(),
                actor,
                targetEntryId != null ? targetEntryId.toString() : UUID.randomUUID().toString(),
                "idemp-" + sessionId.toString().substring(0, 8),
                mediaType,
                storageKey,
                allocId.toString(),
                "b2-upload-" + sessionId.toString().substring(0, 8),
                DEFAULT_HASH,
                effectiveClientTag,
                status,
                tsExpires,
                tsCompleted,
                registeredAssetId != null ? registeredAssetId.toString() : null,
                tsCreated,
                tsUpdated
        );

        return sessionId;
    }

    // =========================================================================
    // CATEGORY A: RECOVERY DISCOVERY REAL-MYSQL TESTS
    // =========================================================================

    @Test
    @DisplayName("A1. Owner isolation before pagination at SQL level: Actor A query never includes Actor B sessions")
    void testA1_ownerIsolationBeforePaginationAtSqlLevel() {
        // Given: 2 completed sessions for Alice, 3 completed sessions for Bob
        MediaAssetFixture assetA1 = createTrackedMediaAsset("VIDEO", "ACTIVE");
        MediaAssetFixture assetA2 = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID sessionA1 = createTrackedUploadSession(UUID.randomUUID(), assetA1.assetId(), assetA1.versionId(), assetA1.storageKey(), "VIDEO", "COMPLETED", ACTOR_ALICE, null, null);
        UUID sessionA2 = createTrackedUploadSession(UUID.randomUUID(), assetA2.assetId(), assetA2.versionId(), assetA2.storageKey(), "VIDEO", "COMPLETED", ACTOR_ALICE, null, null);

        MediaAssetFixture assetB1 = createTrackedMediaAsset("VIDEO", "ACTIVE");
        MediaAssetFixture assetB2 = createTrackedMediaAsset("VIDEO", "ACTIVE");
        MediaAssetFixture assetB3 = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID sessionB1 = createTrackedUploadSession(UUID.randomUUID(), assetB1.assetId(), assetB1.versionId(), assetB1.storageKey(), "VIDEO", "COMPLETED", ACTOR_BOB, null, null);
        UUID sessionB2 = createTrackedUploadSession(UUID.randomUUID(), assetB2.assetId(), assetB2.versionId(), assetB2.storageKey(), "VIDEO", "COMPLETED", ACTOR_BOB, null, null);
        UUID sessionB3 = createTrackedUploadSession(UUID.randomUUID(), assetB3.assetId(), assetB3.versionId(), assetB3.storageKey(), "VIDEO", "COMPLETED", ACTOR_BOB, null, null);

        // When: Query for Alice
        RecoverableVideoUploadPageDTO alicePage = findRecoverableUseCase.execute(
                FindRecoverableScreenVideoUploadsQuery.of(ACTOR_ALICE, null, 0, 10)
        );

        // Then: Alice sees strictly 2 items, both belonging to Alice
        assertThat(alicePage.totalElements()).isEqualTo(2);
        assertThat(alicePage.items()).hasSize(2);
        assertThat(alicePage.items())
                .extracting(RecoverableVideoUploadView::sessionId)
                .containsExactlyInAnyOrder(sessionA1, sessionA2)
                .doesNotContain(sessionB1, sessionB2, sessionB3);

        // When: Query for Bob
        RecoverableVideoUploadPageDTO bobPage = findRecoverableUseCase.execute(
                FindRecoverableScreenVideoUploadsQuery.of(ACTOR_BOB, null, 0, 10)
        );

        // Then: Bob sees strictly 3 items, none belonging to Alice
        assertThat(bobPage.totalElements()).isEqualTo(3);
        assertThat(bobPage.items()).hasSize(3);
        assertThat(bobPage.items())
                .extracting(RecoverableVideoUploadView::sessionId)
                .containsExactlyInAnyOrder(sessionB1, sessionB2, sessionB3)
                .doesNotContain(sessionA1, sessionA2);
    }

    @Test
    @DisplayName("A2. Media type filtering in SELECT and count queries: non-matching mediaType returns empty; V89 DDL prohibits non-VIDEO insertion")
    void testA2_nonVideoExclusionInSelectAndCountQueriesAtDatabaseLevel() {
        // Part 1: Schema invariant verification (V89 chk_media_upload_sessions_media_type)
        // In physical MySQL, media_upload_sessions table restricts media_type strictly to 'VIDEO'.
        // Attempting to persist an 'IMAGE' session directly violates the DDL CHECK constraint.
        UUID allocId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        trackedSessionIds.add(sessionId);
        trackedAllocationIds.add(allocId);
        Timestamp nowTs = Timestamp.from(Instant.now().minus(Duration.ofMinutes(30)));

        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at, persistence_version
                ) VALUES (?, 'b2', 'kiemlai-video', ?, 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?, 0)
                """, allocId.toString(), "images/invalid-" + sessionId + ".png", sessionId.toString(), nowTs, nowTs);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, completed_at, cleanup_status, cleanup_retry_count,
                    registered_asset_id, created_at, updated_at, persistence_version
                ) VALUES (
                    ?, ?, 'screen', 'SCREEN_ENTRY', ?,
                    ?, 'IMAGE', 'video/mp4', 26214400,
                    'b2', 'kiemlai-video', ?, ?,
                    'b2-upload-invalid', 26214400, 1, ?,
                    'image.png', 'screen-video', 'INITIATED', ?, NULL, 'NONE', 0,
                    NULL, ?, ?, 0
                )
                """,
                sessionId.toString(),
                ACTOR_ALICE,
                UUID.randomUUID().toString(),
                "idemp-" + sessionId.toString().substring(0, 8),
                "images/invalid-" + sessionId + ".png",
                allocId.toString(),
                DEFAULT_HASH,
                nowTs,
                nowTs,
                nowTs
        )).isInstanceOf(UncategorizedSQLException.class)
          .hasRootCauseInstanceOf(SQLException.class)
          .satisfies(ex -> {
              UncategorizedSQLException sqlEx = (UncategorizedSQLException) ex;
              SQLException rootSqlEx = sqlEx.getSQLException();
              assertThat(rootSqlEx.getErrorCode()).isEqualTo(3819);
              assertThat(rootSqlEx.getSQLState()).isEqualTo("HY000");
              assertThat(rootSqlEx.getMessage()).contains("chk_media_upload_sessions_media_type");
          });

        // Part 2: SQL query filtering on mediaType parameter
        // Given: 2 completed VIDEO sessions for Alice
        MediaAssetFixture video1 = createTrackedMediaAsset("VIDEO", "ACTIVE");
        MediaAssetFixture video2 = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID videoSession1 = createTrackedUploadSession(UUID.randomUUID(), video1.assetId(), video1.versionId(), video1.storageKey(), "VIDEO", "COMPLETED", ACTOR_ALICE, null, null);
        UUID videoSession2 = createTrackedUploadSession(UUID.randomUUID(), video2.assetId(), video2.versionId(), video2.storageKey(), "VIDEO", "COMPLETED", ACTOR_ALICE, null, null);

        // When: Query for Alice (recovery discovery specifies mediaType="VIDEO" at database level)
        RecoverableVideoUploadPageDTO result = findRecoverableUseCase.execute(
                FindRecoverableScreenVideoUploadsQuery.of(ACTOR_ALICE, null, 0, 10)
        );

        // Then: Exactly 2 VIDEO sessions returned; count query totalElements is 2
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items())
                .extracting(RecoverableVideoUploadView::sessionId)
                .containsExactlyInAnyOrder(videoSession1, videoSession2);

        // And: Direct repository query with non-matching mediaType="IMAGE" returns 0 elements and 0 count at SQL level
        Page<MediaUploadSessionJpaEntity> nonVideoPage = sessionRepository.findCompletedSessions(
                ACTOR_ALICE, "screen", "SCREEN_ENTRY", "IMAGE", null, PageRequest.of(0, 10)
        );
        assertThat(nonVideoPage.getTotalElements()).isZero();
        assertThat(nonVideoPage.getContent()).isEmpty();
    }

    @Test
    @DisplayName("A3. Non-COMPLETED status exclusion in SELECT and count queries at database level (F3 consistent lifecycle fixtures)")
    void testA3_nonCompletedExclusionInSelectAndCountQueriesAtDatabaseLevel() {
        // Given: Sessions in various valid lifecycle states for Alice: INITIATED, ABORTED, FAILED, COMPLETED
        // Only COMPLETED sessions have registered MediaAssets; in-flight and terminated sessions have null asset IDs
        MediaAssetFixture asset1 = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID completedSession = createTrackedUploadSession(
                UUID.randomUUID(), asset1.assetId(), asset1.versionId(), asset1.storageKey(),
                "VIDEO", "COMPLETED", ACTOR_ALICE, null, null
        );

        UUID initiatedSession = createTrackedUploadSession(
                UUID.randomUUID(), null, null, "videos/init-" + UUID.randomUUID() + ".mp4",
                "VIDEO", "INITIATED", ACTOR_ALICE, null, null
        );

        UUID abortedSession = createTrackedUploadSession(
                UUID.randomUUID(), null, null, "videos/abort-" + UUID.randomUUID() + ".mp4",
                "VIDEO", "ABORTED", ACTOR_ALICE, null, null
        );

        UUID failedSession = createTrackedUploadSession(
                UUID.randomUUID(), null, null, "videos/fail-" + UUID.randomUUID() + ".mp4",
                "VIDEO", "FAILED", ACTOR_ALICE, null, null
        );

        // When: Query for Alice
        RecoverableVideoUploadPageDTO result = findRecoverableUseCase.execute(
                FindRecoverableScreenVideoUploadsQuery.of(ACTOR_ALICE, null, 0, 10)
        );

        // Then: Only the COMPLETED session is returned; count query totalElements is 1
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).sessionId()).isEqualTo(completedSession);
    }

    @Test
    @DisplayName("A4. Registered asset presence constraint: COMPLETED requires registered_asset_id, unlinked sessions rejected by DDL (F1)")
    void testA4_registeredAssetPresenceConstraint() {
        // Part 1: Schema invariant verification (V89 chk_media_upload_sessions_completion)
        // In physical MySQL, status='COMPLETED' with registered_asset_id=null is prohibited by the schema.
        UUID allocId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        trackedSessionIds.add(sessionId);
        trackedAllocationIds.add(allocId);
        Timestamp nowTs = Timestamp.from(Instant.now().minus(Duration.ofMinutes(30)));

        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at, persistence_version
                ) VALUES (?, 'b2', 'kiemlai-video', ?, 'RESERVED', 'UPLOAD_SESSION', ?, ?, ?, 0)
                """, allocId.toString(), "videos/invalid-" + sessionId + ".mp4", sessionId.toString(), nowTs, nowTs);

        assertThatThrownBy(() -> jdbcTemplate.update("""
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
                    'b2-upload-invalid', 26214400, 1, ?,
                    'video.mp4', 'screen-video', 'COMPLETED', ?, ?, 'NONE', 0,
                    NULL, ?, ?, 0
                )
                """,
                sessionId.toString(),
                ACTOR_ALICE,
                UUID.randomUUID().toString(),
                "idemp-" + sessionId.toString().substring(0, 8),
                "videos/invalid-" + sessionId + ".mp4",
                allocId.toString(),
                DEFAULT_HASH,
                nowTs,
                nowTs,
                nowTs,
                nowTs
        )).isInstanceOf(UncategorizedSQLException.class)
          .hasRootCauseInstanceOf(SQLException.class)
          .satisfies(ex -> {
              UncategorizedSQLException sqlEx = (UncategorizedSQLException) ex;
              SQLException rootSqlEx = sqlEx.getSQLException();
              assertThat(rootSqlEx.getErrorCode()).isEqualTo(3819);
              assertThat(rootSqlEx.getSQLState()).isEqualTo("HY000");
              assertThat(rootSqlEx.getMessage()).contains("chk_media_upload_sessions_completion");
          });

        // Part 2: Query-level discovery verification
        // 1 completed VIDEO session with registeredAssetId (discovered)
        MediaAssetFixture asset = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID validSession = createTrackedUploadSession(
                UUID.randomUUID(), asset.assetId(), asset.versionId(), asset.storageKey(),
                "VIDEO", "COMPLETED", ACTOR_ALICE, null, null
        );

        // 1 valid INITIATED session with null registeredAssetId (excluded by query)
        UUID initiatedSession = createTrackedUploadSession(
                UUID.randomUUID(), null, null, "videos/initiated-" + UUID.randomUUID() + ".mp4",
                "VIDEO", "INITIATED", ACTOR_ALICE, null, null
        );

        // When: Query for Alice
        RecoverableVideoUploadPageDTO result = findRecoverableUseCase.execute(
                FindRecoverableScreenVideoUploadsQuery.of(ACTOR_ALICE, null, 0, 10)
        );

        // Then: Only the session with registered asset is discovered
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items().get(0).sessionId()).isEqualTo(validSession);
        assertThat(result.items().get(0).videoMediaAssetId()).isEqualTo(asset.assetId());
    }

    @Test
    @DisplayName("A5. Deterministic ordering with equal completedAt timestamps: sorted by id ASC tie-breaking (F4 clock robustness)")
    void testA5_deterministicOrderingOnEqualCompletedAtTimestamps() {
        // Given: 3 completed sessions with the exact same completedAt timestamp anchored before current clock
        Instant fixedCompletedAt = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MILLIS);
        MediaAssetFixture a1 = createTrackedMediaAsset("VIDEO", "ACTIVE");
        MediaAssetFixture a2 = createTrackedMediaAsset("VIDEO", "ACTIVE");
        MediaAssetFixture a3 = createTrackedMediaAsset("VIDEO", "ACTIVE");

        UUID s1 = createTrackedUploadSession(UUID.randomUUID(), a1.assetId(), a1.versionId(), a1.storageKey(), "VIDEO", "COMPLETED", ACTOR_ALICE, fixedCompletedAt, null);
        UUID s2 = createTrackedUploadSession(UUID.randomUUID(), a2.assetId(), a2.versionId(), a2.storageKey(), "VIDEO", "COMPLETED", ACTOR_ALICE, fixedCompletedAt, null);
        UUID s3 = createTrackedUploadSession(UUID.randomUUID(), a3.assetId(), a3.versionId(), a3.storageKey(), "VIDEO", "COMPLETED", ACTOR_ALICE, fixedCompletedAt, null);

        List<UUID> expectedOrder = new ArrayList<>(List.of(s1, s2, s3));
        expectedOrder.sort(Comparator.comparing(UUID::toString));

        // When: Query sessions for Alice
        RecoverableVideoUploadPageDTO result = findRecoverableUseCase.execute(
                FindRecoverableScreenVideoUploadsQuery.of(ACTOR_ALICE, null, 0, 10)
        );

        // Then: Total elements is 3 and items strictly match id ASC tie-breaking order
        assertThat(result.totalElements()).isEqualTo(3);
        List<UUID> returnedIds = result.items().stream().map(RecoverableVideoUploadView::sessionId).toList();
        assertThat(returnedIds).isEqualTo(expectedOrder);
    }

    @Test
    @DisplayName("A6. Database-level pagination, boundary clamping, and accurate totalElements (F4 clock robustness)")
    void testA6_databaseLevelPaginationAndAccurateTotalElements() {
        // Given: 5 completed VIDEO sessions for Alice with distinct timestamps anchored relative to current clock
        Instant baseTime = Instant.now().minus(Duration.ofHours(5)).truncatedTo(ChronoUnit.MILLIS);
        List<UUID> sessionsDesc = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MediaAssetFixture asset = createTrackedMediaAsset("VIDEO", "ACTIVE");
            Instant completedTime = baseTime.plus(Duration.ofMinutes(i * 10));
            UUID sessionId = createTrackedUploadSession(UUID.randomUUID(), asset.assetId(), asset.versionId(), asset.storageKey(), "VIDEO", "COMPLETED", ACTOR_ALICE, completedTime, null);
            sessionsDesc.add(0, sessionId); // latest completedAt first
        }

        // When: Fetch Page 0 (size 2)
        RecoverableVideoUploadPageDTO p0 = findRecoverableUseCase.execute(
                FindRecoverableScreenVideoUploadsQuery.of(ACTOR_ALICE, null, 0, 2)
        );
        assertThat(p0.totalElements()).isEqualTo(5);
        assertThat(p0.totalPages()).isEqualTo(3);
        assertThat(p0.first()).isTrue();
        assertThat(p0.last()).isFalse();
        assertThat(p0.items()).hasSize(2);
        assertThat(p0.items().get(0).sessionId()).isEqualTo(sessionsDesc.get(0));
        assertThat(p0.items().get(1).sessionId()).isEqualTo(sessionsDesc.get(1));

        // When: Fetch Page 1 (size 2)
        RecoverableVideoUploadPageDTO p1 = findRecoverableUseCase.execute(
                FindRecoverableScreenVideoUploadsQuery.of(ACTOR_ALICE, null, 1, 2)
        );
        assertThat(p1.totalElements()).isEqualTo(5);
        assertThat(p1.totalPages()).isEqualTo(3);
        assertThat(p1.first()).isFalse();
        assertThat(p1.last()).isFalse();
        assertThat(p1.items()).hasSize(2);
        assertThat(p1.items().get(0).sessionId()).isEqualTo(sessionsDesc.get(2));
        assertThat(p1.items().get(1).sessionId()).isEqualTo(sessionsDesc.get(3));

        // When: Fetch Page 2 (size 2)
        RecoverableVideoUploadPageDTO p2 = findRecoverableUseCase.execute(
                FindRecoverableScreenVideoUploadsQuery.of(ACTOR_ALICE, null, 2, 2)
        );
        assertThat(p2.totalElements()).isEqualTo(5);
        assertThat(p2.totalPages()).isEqualTo(3);
        assertThat(p2.first()).isFalse();
        assertThat(p2.last()).isTrue();
        assertThat(p2.items()).hasSize(1);
        assertThat(p2.items().get(0).sessionId()).isEqualTo(sessionsDesc.get(4));
    }

    @Test
    @DisplayName("A7. Target missing recovery discovery: TARGET_MISSING, [TARGET_DELETED], canDiscard=true, canRetry=false (F2 canonical tag)")
    void testA7_targetMissingRecoveryDiscovery() {
        // Given: Completed VIDEO upload session pointing to a non-existent target entry ID with canonical client tag
        UUID nonExistentEntryId = UUID.randomUUID();
        MediaAssetFixture asset = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID sessionId = createTrackedUploadSession(
                nonExistentEntryId,
                asset.assetId(),
                asset.versionId(),
                asset.storageKey(),
                "VIDEO",
                "COMPLETED",
                ACTOR_ALICE,
                null,
                ScreenVideoOperationIntent.CLIENT_TAG_INITIAL
        );

        // When: Query for Alice
        RecoverableVideoUploadPageDTO result = findRecoverableUseCase.execute(
                FindRecoverableScreenVideoUploadsQuery.of(ACTOR_ALICE, null, 0, 10)
        );

        // Then: View reflects TARGET_MISSING classification and safe discardability
        assertThat(result.items()).hasSize(1);
        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.sessionId()).isEqualTo(sessionId);
        assertThat(view.videoMediaAssetId()).isEqualTo(asset.assetId());
        assertThat(view.targetEntryId()).isEqualTo(nonExistentEntryId);
        assertThat(view.targetEntryTitle()).isEqualTo(FindRecoverableScreenVideoUploadsUseCase.TARGET_DELETED_TITLE);
        assertThat(view.targetTitleId()).isNull();
        assertThat(view.classification()).isEqualTo(RecoveryClassification.TARGET_MISSING);
        assertThat(view.canDiscard()).isTrue();
        assertThat(view.canRetry()).isFalse();
    }

    @Test
    @DisplayName("A8. Reference status classification: UNATTACHED vs ATTACHED_TO_TARGET vs REFERENCED_ELSEWHERE (F2 canonical tag)")
    void testA8_classificationAttachedVsUnattachedVsReferencedElsewhere() {
        UUID titleId = createTrackedTitle();

        // 1. Unattached asset: target entry exists with video=null, canonical initial tag allows retry
        MediaAssetFixture assetUnattached = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID entry1 = createTrackedEntry(titleId, null, 1);
        UUID sUnattached = createTrackedUploadSession(
                entry1, assetUnattached.assetId(), assetUnattached.versionId(), assetUnattached.storageKey(),
                "VIDEO", "COMPLETED", ACTOR_ALICE, null, ScreenVideoOperationIntent.CLIENT_TAG_INITIAL
        );

        // 2. Attached to target: target entry references this asset
        MediaAssetFixture assetAttached = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID entry2 = createTrackedEntry(titleId, assetAttached.assetId(), 2);
        UUID sAttached = createTrackedUploadSession(
                entry2, assetAttached.assetId(), assetAttached.versionId(), assetAttached.storageKey(),
                "VIDEO", "COMPLETED", ACTOR_ALICE, null, ScreenVideoOperationIntent.CLIENT_TAG_INITIAL
        );

        // 3. Referenced elsewhere: session target is entry3, but entry4 references this asset
        MediaAssetFixture assetElsewhere = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID entry3 = createTrackedEntry(titleId, null, 3);
        UUID entry4 = createTrackedEntry(titleId, assetElsewhere.assetId(), 4);
        UUID sElsewhere = createTrackedUploadSession(
                entry3, assetElsewhere.assetId(), assetElsewhere.versionId(), assetElsewhere.storageKey(),
                "VIDEO", "COMPLETED", ACTOR_ALICE, null, ScreenVideoOperationIntent.CLIENT_TAG_INITIAL
        );

        // When: Query for Alice
        RecoverableVideoUploadPageDTO result = findRecoverableUseCase.execute(
                FindRecoverableScreenVideoUploadsQuery.of(ACTOR_ALICE, null, 0, 10)
        );

        // Then: Each session has exact expected classification
        Map<UUID, RecoverableVideoUploadView> viewMap = result.items().stream()
                .collect(java.util.stream.Collectors.toMap(RecoverableVideoUploadView::sessionId, v -> v));

        RecoverableVideoUploadView v1 = viewMap.get(sUnattached);
        assertThat(v1.classification()).isEqualTo(RecoveryClassification.UNATTACHED);
        assertThat(v1.canDiscard()).isTrue();
        assertThat(v1.canRetry()).isTrue();

        RecoverableVideoUploadView v2 = viewMap.get(sAttached);
        assertThat(v2.classification()).isEqualTo(RecoveryClassification.ATTACHED_TO_TARGET);
        assertThat(v2.canDiscard()).isFalse();
        assertThat(v2.canRetry()).isFalse();

        RecoverableVideoUploadView v3 = viewMap.get(sElsewhere);
        assertThat(v3.classification()).isEqualTo(RecoveryClassification.REFERENCED_ELSEWHERE);
        assertThat(v3.canDiscard()).isFalse();
        assertThat(v3.canRetry()).isFalse();
    }

    // =========================================================================
    // CATEGORY B: MISSING-TARGET GUARDED DISCARD REAL-MYSQL TESTS
    // =========================================================================

    @Test
    @DisplayName("B1. Unreferenced ACTIVE video discard when target entry is missing (session-rooted discard)")
    void testB1_unreferencedActiveVideoDiscardWhenTargetEntryIsMissing_sessionRooted() {
        // Given: Session exists whose target entry does NOT exist in screen_entries
        UUID missingEntryId = UUID.randomUUID();
        MediaAssetFixture asset = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID sessionId = createTrackedUploadSession(
                missingEntryId,
                asset.assetId(),
                asset.versionId(),
                asset.storageKey(),
                "VIDEO",
                "COMPLETED",
                ACTOR_ALICE,
                null,
                null
        );

        // When: Execute session-rooted discard (titleId = null, entryId = null)
        DiscardScreenEntryVideoUploadResult result = discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(
                        null,
                        null,
                        sessionId,
                        asset.assetId(),
                        ACTOR_ALICE
                )
        );

        // Then: Discard succeeds with DISCARDED outcome
        assertThat(result.outcome()).isEqualTo(DiscardScreenEntryVideoUploadResult.DiscardOutcome.DISCARDED);
        assertThat(result.videoMediaAssetId()).isEqualTo(asset.assetId());

        // Verify physical MySQL state: media_assets.status transitioned to DELETED
        String dbAssetStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?",
                String.class,
                asset.assetId().toString()
        );
        assertThat(dbAssetStatus).isEqualTo("DELETED");

        // Verify media_upload_sessions.status remains COMPLETED
        String dbSessionStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM media_upload_sessions WHERE id = ?",
                String.class,
                sessionId.toString()
        );
        assertThat(dbSessionStatus).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("B2. Non-owner access denial when target entry is missing: DB state unmodified")
    void testB2_nonOwnerAccessDeniedWhenTargetEntryIsMissing() {
        // Given: Session owned by Alice, target entry does not exist
        UUID missingEntryId = UUID.randomUUID();
        MediaAssetFixture asset = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID sessionId = createTrackedUploadSession(
                missingEntryId,
                asset.assetId(),
                asset.versionId(),
                asset.storageKey(),
                "VIDEO",
                "COMPLETED",
                ACTOR_ALICE,
                null,
                null
        );

        // When & Then: Bob attempts to discard Alice's session -> AccessDenied
        assertThatThrownBy(() -> discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(
                        null,
                        null,
                        sessionId,
                        asset.assetId(),
                        ACTOR_BOB
                )
        )).isInstanceOf(ScreenVideoDiscardAccessDeniedException.class);

        // Verify physical MySQL state: asset remains ACTIVE, zero changes
        String dbAssetStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?",
                String.class,
                asset.assetId().toString()
        );
        assertThat(dbAssetStatus).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("B3. SUPER_ADMIN bypass rejection when target entry is missing: actor ownership non-bypassable")
    void testB3_superAdminBypassRejectedWhenTargetEntryIsMissing() {
        // Given: Session owned by Alice, target entry does not exist
        UUID missingEntryId = UUID.randomUUID();
        MediaAssetFixture asset = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID sessionId = createTrackedUploadSession(
                missingEntryId,
                asset.assetId(),
                asset.versionId(),
                asset.storageKey(),
                "VIDEO",
                "COMPLETED",
                ACTOR_ALICE,
                null,
                null
        );

        // When & Then: SuperAdmin attempts to discard Alice's session -> AccessDenied (product policy: no bypass)
        assertThatThrownBy(() -> discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(
                        null,
                        null,
                        sessionId,
                        asset.assetId(),
                        ACTOR_SUPERADMIN
                )
        )).isInstanceOf(ScreenVideoDiscardAccessDeniedException.class);

        // Verify physical MySQL state: asset remains ACTIVE
        String dbAssetStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?",
                String.class,
                asset.assetId().toString()
        );
        assertThat(dbAssetStatus).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("B4. Referenced conflict protection when target entry is missing: foreign entry reference blocks discard")
    void testB4_referencedConflictProtectionWhenTargetEntryIsMissing() {
        // Given: Session target entry does not exist, but another entry in DB references the asset
        UUID missingEntryId = UUID.randomUUID();
        MediaAssetFixture asset = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID titleId = createTrackedTitle();
        UUID otherEntryId = createTrackedEntry(titleId, asset.assetId(), 1);

        UUID sessionId = createTrackedUploadSession(
                missingEntryId,
                asset.assetId(),
                asset.versionId(),
                asset.storageKey(),
                "VIDEO",
                "COMPLETED",
                ACTOR_ALICE,
                null,
                null
        );

        // When & Then: Alice attempts to discard -> Rejected due to reference conflict
        assertThatThrownBy(() -> discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(
                        null,
                        null,
                        sessionId,
                        asset.assetId(),
                        ACTOR_ALICE
                )
        )).isInstanceOf(ScreenVideoAlreadyReferencedException.class)
          .satisfies(ex -> {
              ScreenVideoAlreadyReferencedException refEx = (ScreenVideoAlreadyReferencedException) ex;
              assertThat(refEx.getVideoMediaAssetId()).isEqualTo(asset.assetId());
              assertThat(refEx.getReferencingEntryIds()).contains(otherEntryId);
          });

        // Verify physical MySQL state: asset remains ACTIVE
        String dbAssetStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?",
                String.class,
                asset.assetId().toString()
        );
        assertThat(dbAssetStatus).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("B5. ALREADY_DISCARDED idempotency when target entry is missing")
    void testB5_alreadyDiscardedIdempotencyWhenTargetEntryIsMissing() {
        // Given: Target entry does not exist, and asset is already DELETED and unreferenced
        UUID missingEntryId = UUID.randomUUID();
        MediaAssetFixture asset = createTrackedMediaAsset("VIDEO", "DELETED");
        UUID sessionId = createTrackedUploadSession(
                missingEntryId,
                asset.assetId(),
                asset.versionId(),
                asset.storageKey(),
                "VIDEO",
                "COMPLETED",
                ACTOR_ALICE,
                null,
                null
        );

        // When: Execute discard on already DELETED asset
        DiscardScreenEntryVideoUploadResult result = discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(
                        null,
                        null,
                        sessionId,
                        asset.assetId(),
                        ACTOR_ALICE
                )
        );

        // Then: Idempotent success with ALREADY_DISCARDED outcome
        assertThat(result.outcome()).isEqualTo(DiscardScreenEntryVideoUploadResult.DiscardOutcome.ALREADY_DISCARDED);
        assertThat(result.videoMediaAssetId()).isEqualTo(asset.assetId());

        // Verify physical MySQL state: asset remains DELETED, session remains COMPLETED
        String dbAssetStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?",
                String.class,
                asset.assetId().toString()
        );
        assertThat(dbAssetStatus).isEqualTo("DELETED");
    }

    @Test
    @DisplayName("B6. Session status preservation: MediaUploadSession remains COMPLETED and cleanup NONE")
    void testB6_sessionStatusPreservationAfterDiscard() {
        // Given: Completed session with missing target entry
        UUID missingEntryId = UUID.randomUUID();
        MediaAssetFixture asset = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID sessionId = createTrackedUploadSession(
                missingEntryId,
                asset.assetId(),
                asset.versionId(),
                asset.storageKey(),
                "VIDEO",
                "COMPLETED",
                ACTOR_ALICE,
                null,
                null
        );

        // When: Discard is executed
        discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(null, null, sessionId, asset.assetId(), ACTOR_ALICE)
        );

        // Then: Verify session row in MySQL is still COMPLETED and cleanup_status is NONE
        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, cleanup_status FROM media_upload_sessions WHERE id = ?",
                sessionId.toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo("COMPLETED");
        assertThat(sessionRow.get("cleanup_status")).isEqualTo("NONE");
    }

    @Test
    @DisplayName("B7. Zero physical B2 storage deletion: only soft-deletes in MySQL without touching storage keys")
    void testB7_zeroPhysicalStorageDeletion() {
        // Given: Completed session with missing target entry
        UUID missingEntryId = UUID.randomUUID();
        MediaAssetFixture asset = createTrackedMediaAsset("VIDEO", "ACTIVE");
        UUID sessionId = createTrackedUploadSession(
                missingEntryId,
                asset.assetId(),
                asset.versionId(),
                asset.storageKey(),
                "VIDEO",
                "COMPLETED",
                ACTOR_ALICE,
                null,
                null
        );

        // When: Discard is executed
        discardUseCase.execute(
                new DiscardScreenEntryVideoUploadCommand(null, null, sessionId, asset.assetId(), ACTOR_ALICE)
        );

        // Then: media_asset_versions and storage_key_allocations rows are completely intact in MySQL
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_asset_versions WHERE asset_id = ?",
                Integer.class,
                asset.assetId().toString()
        );
        assertThat(versionCount).isEqualTo(1);

        Integer allocationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_storage_key_allocations WHERE storage_key = ?",
                Integer.class,
                asset.storageKey()
        );
        assertThat(allocationCount).isEqualTo(1);
    }

    // =========================================================================
    // TEST WIRING CONFIGURATION
    // =========================================================================

    @TestConfiguration
    static class TestWiringConfig {

        @Bean
        public ClockPort clockPort() {
            return Instant::now;
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
                GetMediaAssetDetailUseCase getMediaAssetDetailUseCase,
                LockAndValidateActiveMediaAssetUseCase lockAndValidateActiveMediaAssetUseCase,
                LockAndInspectMediaAssetUseCase lockAndInspectMediaAssetUseCase,
                MediaUploadSessionService mediaUploadSessionService
        ) {
            return new MediaFacade(
                    getMediaAssetDetailUseCase,
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
}
