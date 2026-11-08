package com.universe.screen.infrastructure.persistence;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.contracts.dto.ChangeMediaVisibilityRequestDTO;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetInspectionDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaUploadSessionResponseDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.infrastructure.persistence.MediaUploadSessionPersistenceAdapter;
import com.universe.screen.application.exceptions.ScreenVideoProvenanceException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenMediaVisibilityPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.usecase.ArchiveScreenEntryUseCase;
import com.universe.screen.application.usecase.ArchiveScreenTitleUseCase;
import com.universe.screen.application.usecase.AttachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.PublishScreenEntryUseCase;
import com.universe.screen.application.usecase.PublishScreenTitleUseCase;
import com.universe.screen.application.usecase.ReconcileScreenEntryVideoVisibilityUseCase;
import com.universe.screen.application.usecase.ReplaceScreenEntryVideoUseCase;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenFranchise;
import com.universe.screen.domain.ScreenFranchiseMembership;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import com.universe.screen.infrastructure.media.ScreenMediaValidationAdapter;
import com.universe.screen.infrastructure.media.ScreenMediaVisibilityAdapter;
import com.universe.shared.time.ClockPort;
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
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Physical MySQL Integration Test Suite for Screen Video Visibility Lifecycle.
 *
 * <p>Executes strictly against local loopback {@code 127.0.0.1:3306/kiemlai_test}.
 * Never runs against shared Aiven or production databases.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
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
        ScreenFranchisePersistenceAdapter.class,
        ScreenFranchiseMembershipPersistenceAdapter.class,
        MediaUploadSessionPersistenceAdapter.class,
        ScreenMediaValidationAdapter.class,
        ScreenMediaVisibilityAdapter.class,
        PublishScreenTitleUseCase.class,
        ArchiveScreenTitleUseCase.class,
        PublishScreenEntryUseCase.class,
        ArchiveScreenEntryUseCase.class,
        AttachScreenEntryVideoUseCase.class,
        ReplaceScreenEntryVideoUseCase.class,
        ReconcileScreenEntryVideoVisibilityUseCase.class,
        ScreenVideoVisibilityLifecycleMySQLIntegrationTest.TestWiringConfig.class
})
@DisplayName("Screen Video Visibility Lifecycle Real MySQL Integration Tests")
class ScreenVideoVisibilityLifecycleMySQLIntegrationTest {

    private static final String DEFAULT_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

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
    private ScreenTitleRepositoryPort titleRepository;

    @Autowired
    private ScreenEntryRepositoryPort entryRepository;

    @Autowired
    private ScreenFranchiseRepositoryPort franchiseRepository;

    @Autowired
    private ScreenFranchiseMembershipRepositoryPort franchiseMembershipRepository;

    @Autowired
    private ScreenMediaVisibilityPort mediaVisibilityPort;

    @Autowired
    private PublishScreenTitleUseCase publishTitleUseCase;

    @Autowired
    private ArchiveScreenTitleUseCase archiveTitleUseCase;

    @Autowired
    private PublishScreenEntryUseCase publishEntryUseCase;

    @Autowired
    private ArchiveScreenEntryUseCase archiveEntryUseCase;

    @Autowired
    private AttachScreenEntryVideoUseCase attachEntryVideoUseCase;

    @Autowired
    private ReplaceScreenEntryVideoUseCase replaceEntryVideoUseCase;

    @Autowired
    private ReconcileScreenEntryVideoVisibilityUseCase reconcileUseCase;

    private static final AtomicBoolean simulateVisibilityFailure = new AtomicBoolean(false);
    private static final AtomicReference<Consumer<UUID>> beforeMediaLockHook = new AtomicReference<>();

    // Strict tracking for foreign-key ordered teardown
    private final List<UUID> trackedSessionIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> trackedAllocationIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> trackedEntryIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> trackedMembershipIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> trackedFranchiseIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> trackedTitleIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> trackedVersionIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> trackedAssetIds = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        simulateVisibilityFailure.set(false);
        beforeMediaLockHook.set(null);

        // Target Database Safety Guard: verify strictly local kiemlai_test
        try (Connection conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            String catalog = conn.getCatalog();
            assertThat(url).as("Database URL must be local loopback on port 3306").contains("3306");
            assertThat(url).as("Database URL must point to kiemlai_test").contains("kiemlai_test");
            assertThat(catalog).as("Active catalog must be kiemlai_test").isEqualTo("kiemlai_test");

            // Session lock timeout to fail fast on deadlocks/hangs
            try (var stmt = conn.createStatement()) {
                stmt.execute("SET SESSION innodb_lock_wait_timeout = 3");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Target Database Guard failed", e);
        }

        cleanupAll();
    }

    @AfterEach
    void tearDown() {
        simulateVisibilityFailure.set(false);
        beforeMediaLockHook.set(null);
        cleanupAll();
    }

    private void cleanupAll() {
        synchronized (trackedSessionIds) {
            for (UUID id : trackedSessionIds) {
                jdbcTemplate.update("DELETE FROM media_upload_sessions WHERE id = ?", id.toString());
            }
            trackedSessionIds.clear();
        }
        synchronized (trackedAllocationIds) {
            for (UUID id : trackedAllocationIds) {
                jdbcTemplate.update("DELETE FROM media_storage_key_allocations WHERE id = ?", id.toString());
            }
            trackedAllocationIds.clear();
        }
        synchronized (trackedEntryIds) {
            for (UUID id : trackedEntryIds) {
                jdbcTemplate.update("DELETE FROM screen_entries WHERE id = ?", id.toString());
            }
            trackedEntryIds.clear();
        }
        synchronized (trackedMembershipIds) {
            for (UUID id : trackedMembershipIds) {
                jdbcTemplate.update("DELETE FROM screen_franchise_memberships WHERE id = ?", id.toString());
            }
            trackedMembershipIds.clear();
        }
        synchronized (trackedFranchiseIds) {
            for (UUID id : trackedFranchiseIds) {
                jdbcTemplate.update("DELETE FROM screen_franchises WHERE id = ?", id.toString());
            }
            trackedFranchiseIds.clear();
        }
        synchronized (trackedTitleIds) {
            for (UUID id : trackedTitleIds) {
                jdbcTemplate.update("DELETE FROM screen_titles WHERE id = ?", id.toString());
            }
            trackedTitleIds.clear();
        }
        synchronized (trackedVersionIds) {
            for (UUID id : trackedVersionIds) {
                jdbcTemplate.update("DELETE FROM media_asset_versions WHERE id = ?", id.toString());
            }
            trackedVersionIds.clear();
        }
        synchronized (trackedAssetIds) {
            for (UUID id : trackedAssetIds) {
                jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", id.toString());
            }
            trackedAssetIds.clear();
        }
    }

    private UUID seedMediaAsset(String visibility) {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp tsNow = Timestamp.from(now);
        String storageKey = "videos/" + assetId + "/v1.mp4";

        jdbcTemplate.update("""
                INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version)
                VALUES (?, 'VIDEO', ?, 'ACTIVE', 1, ?, ?, 0)
                """, assetId.toString(), visibility, tsNow, tsNow);
        trackedAssetIds.add(assetId);

        jdbcTemplate.update("""
                INSERT INTO media_asset_versions (
                    id, asset_id, version_number, storage_provider_id, storage_key,
                    public_url, content_hash, mime_type, size_bytes, original_filename, created_at
                ) VALUES (
                    ?, ?, 1, 'b2', ?,
                    ?, ?, 'video/mp4', 26214400, 'test.mp4', ?
                )
                """, versionId.toString(), assetId.toString(), storageKey,
                "https://media.kiemlai.test/" + storageKey, DEFAULT_HASH, tsNow);
        trackedVersionIds.add(versionId);

        return assetId;
    }

    private void seedUploadSession(UUID assetId, UUID targetEntryId, String consumerContext, String targetEntityType, String status) {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp tsNow = Timestamp.from(now);
        Timestamp tsExpires = Timestamp.from(now.plusSeconds(3600));
        String storageKey = "sessions/" + sessionId + "/video.mp4";

        jdbcTemplate.update("""
                INSERT INTO media_storage_key_allocations (
                    id, storage_provider_id, storage_bucket_name, storage_key,
                    allocation_status, allocated_by_type, allocated_by_id, created_at, updated_at, persistence_version
                ) VALUES (?, 'b2', 'kiemlai-video', ?, 'COMMITTED', 'UPLOAD_SESSION', ?, ?, ?, 0)
                """, allocId.toString(), storageKey, sessionId.toString(), tsNow, tsNow);
        trackedAllocationIds.add(allocId);

        jdbcTemplate.update("""
                INSERT INTO media_upload_sessions (
                    id, initiator_principal_id, consumer_context, target_entity_type, target_entity_id,
                    idempotency_key, media_type, declared_mime_type, declared_size_bytes,
                    storage_provider_id, storage_bucket_name, storage_key, storage_allocation_id,
                    provider_upload_id, part_size_bytes, expected_part_count, declared_content_hash,
                    original_filename, client_tag, status, expires_at, completed_at, cleanup_status, cleanup_retry_count,
                    registered_asset_id, created_at, updated_at, persistence_version
                ) VALUES (
                    ?, 'admin', ?, ?, ?,
                    ?, 'VIDEO', 'video/mp4', 26214400,
                    'b2', 'kiemlai-video', ?, ?,
                    'upload-id', 26214400, 1, ?,
                    'video.mp4', 'tag', ?, ?, ?, 'NONE', 0,
                    ?, ?, ?, 0
                )
                """,
                sessionId.toString(),
                consumerContext,
                targetEntityType,
                targetEntryId.toString(),
                "idemp-" + sessionId.toString().substring(0, 8),
                storageKey,
                allocId.toString(),
                DEFAULT_HASH,
                status,
                tsExpires,
                tsNow,
                assetId != null ? assetId.toString() : null,
                tsNow,
                tsNow
        );
        trackedSessionIds.add(sessionId);
    }

    private ScreenTitle seedTitle(String status) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp tsNow = Timestamp.from(now);
        String slug = "title-" + id.toString().substring(0, 8);

        jdbcTemplate.update("""
                INSERT INTO screen_titles (id, slug, title, structure, presentation, status, published_at, created_at, updated_at)
                VALUES (?, ?, 'Title Test', 'SERIES', 'ANIMATION_3D', ?, ?, ?, ?)
                """, id.toString(), slug, status, "PUBLISHED".equals(status) ? tsNow : null, tsNow, tsNow);
        trackedTitleIds.add(id);

        return titleRepository.findById(id).orElseThrow();
    }

    private ScreenEntry seedEntry(UUID titleId, UUID videoAssetId, String status) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp tsNow = Timestamp.from(now);
        String slug = "entry-" + id.toString().substring(0, 8);

        jdbcTemplate.update("""
                INSERT INTO screen_entries (id, title_id, slug, type, number, title, video_media_asset_id, status, published_at, created_at, updated_at)
                VALUES (?, ?, ?, 'EPISODE', 1, 'Entry Test', ?, ?, ?, ?, ?)
                """, id.toString(), titleId.toString(), slug, videoAssetId != null ? videoAssetId.toString() : null,
                status, "PUBLISHED".equals(status) ? tsNow : null, tsNow, tsNow);
        trackedEntryIds.add(id);

        return entryRepository.findById(id).orElseThrow();
    }

    private String getMediaAssetVisibility(UUID assetId) {
        return jdbcTemplate.queryForObject(
                "SELECT visibility FROM media_assets WHERE id = ?",
                String.class,
                assetId.toString()
        );
    }

    @Test
    @DisplayName("Scenario 1: Concurrent Publish Title & Publish Entry reconciles video to PUBLIC authoritatively")
    void scenario1_concurrentPublishTitleAndPublishEntry() throws Exception {
        ScreenTitle title = seedTitle("DRAFT");
        UUID videoId = seedMediaAsset("PRIVATE");
        ScreenEntry entry = seedEntry(title.getId(), videoId, "DRAFT");
        seedUploadSession(videoId, entry.getId(), "screen", "SCREEN_ENTRY", "COMPLETED");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            Future<?> futureTitle = executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    publishTitleUseCase.execute(title.getId());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Future<?> futureEntry = executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    publishEntryUseCase.execute(title.getId(), entry.getId());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            futureTitle.get(15, TimeUnit.SECONDS);
            futureEntry.get(15, TimeUnit.SECONDS);

            ScreenTitle updatedTitle = titleRepository.findById(title.getId()).orElseThrow();
            ScreenEntry updatedEntry = entryRepository.findById(entry.getId()).orElseThrow();

            assertThat(updatedTitle.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
            assertThat(updatedEntry.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
            assertThat(getMediaAssetVisibility(videoId)).isEqualTo("PUBLIC");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Scenario 2: Concurrent Archive Title & Replace Video leaves no orphaned PUBLIC video")
    void scenario2_concurrentArchiveTitleAndReplaceVideo() throws Exception {
        ScreenTitle title = seedTitle("PUBLISHED");
        UUID oldVideoId = seedMediaAsset("PUBLIC");
        UUID newVideoId = seedMediaAsset("PRIVATE");
        ScreenEntry entry = seedEntry(title.getId(), oldVideoId, "PUBLISHED");

        seedUploadSession(oldVideoId, entry.getId(), "screen", "SCREEN_ENTRY", "COMPLETED");
        seedUploadSession(newVideoId, entry.getId(), "screen", "SCREEN_ENTRY", "COMPLETED");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            Future<?> futureArchive = executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    archiveTitleUseCase.execute(title.getId());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Future<?> futureReplace = executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    replaceEntryVideoUseCase.execute(title.getId(), entry.getId(), newVideoId, oldVideoId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            futureArchive.get(15, TimeUnit.SECONDS);
            futureReplace.get(15, TimeUnit.SECONDS);

            ScreenTitle updatedTitle = titleRepository.findById(title.getId()).orElseThrow();
            assertThat(updatedTitle.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);

            // Neither old nor new video can remain PUBLIC since the title is ARCHIVED
            assertThat(getMediaAssetVisibility(oldVideoId)).isEqualTo("PRIVATE");
            assertThat(getMediaAssetVisibility(newVideoId)).isEqualTo("PRIVATE");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Scenario 3: Concurrent Archive Title & Attach Video does not leave video PUBLIC")
    void scenario3_concurrentArchiveTitleAndAttachVideo() throws Exception {
        ScreenTitle title = seedTitle("PUBLISHED");
        UUID videoId = seedMediaAsset("PRIVATE");
        ScreenEntry entry = seedEntry(title.getId(), null, "PUBLISHED");
        seedUploadSession(videoId, entry.getId(), "screen", "SCREEN_ENTRY", "COMPLETED");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            Future<?> futureArchive = executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    archiveTitleUseCase.execute(title.getId());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Future<?> futureAttach = executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    attachEntryVideoUseCase.execute(title.getId(), entry.getId(), videoId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            futureArchive.get(15, TimeUnit.SECONDS);
            futureAttach.get(15, TimeUnit.SECONDS);

            ScreenTitle updatedTitle = titleRepository.findById(title.getId()).orElseThrow();
            assertThat(updatedTitle.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
            assertThat(getMediaAssetVisibility(videoId)).isEqualTo("PRIVATE");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Scenario 4: Shared-reference visibility preservation across published titles in the same franchise")
    void scenario4_sharedReferencePreservationAcrossTitles() {
        ScreenFranchise franchise = franchiseRepository.save(new ScreenFranchise(UUID.randomUUID(), "shared-franchise", "Shared Franchise"));
        trackedFranchiseIds.add(franchise.getId());

        ScreenTitle title1 = seedTitle("PUBLISHED");
        ScreenTitle title2 = seedTitle("PUBLISHED");

        ScreenFranchiseMembership mem1 = franchiseMembershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), title1.getId(), 0));
        trackedMembershipIds.add(mem1.getId());
        ScreenFranchiseMembership mem2 = franchiseMembershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), title2.getId(), 1));
        trackedMembershipIds.add(mem2.getId());

        UUID sharedVideoId = seedMediaAsset("PUBLIC");

        ScreenEntry entry1 = seedEntry(title1.getId(), sharedVideoId, "PUBLISHED");
        ScreenEntry entry2 = seedEntry(title2.getId(), sharedVideoId, "PUBLISHED");

        seedUploadSession(sharedVideoId, entry1.getId(), "screen", "SCREEN_ENTRY", "COMPLETED");

        // Archiving Title 1 must preserve sharedVideo as PUBLIC because Title 2's Entry 2 is still PUBLISHED
        archiveTitleUseCase.execute(title1.getId());

        ScreenTitle updatedTitle1 = titleRepository.findById(title1.getId()).orElseThrow();
        assertThat(updatedTitle1.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
        assertThat(getMediaAssetVisibility(sharedVideoId)).isEqualTo("PUBLIC");

        // Now archiving Title 2 demotes sharedVideo to PRIVATE
        archiveTitleUseCase.execute(title2.getId());

        ScreenTitle updatedTitle2 = titleRepository.findById(title2.getId()).orElseThrow();
        assertThat(updatedTitle2.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
        assertThat(getMediaAssetVisibility(sharedVideoId)).isEqualTo("PRIVATE");
    }

    @Test
    @DisplayName("Scenario 5: Unauthorized provenance rejection even when original Entry A is PUBLISHED under unrelated Title and Franchise")
    void scenario5_unauthorizedProvenanceRejectionAndRollback() {
        ScreenFranchise franchise1 = franchiseRepository.save(new ScreenFranchise(UUID.randomUUID(), "franchise-1", "Franchise 1"));
        trackedFranchiseIds.add(franchise1.getId());
        ScreenFranchise franchise2 = franchiseRepository.save(new ScreenFranchise(UUID.randomUUID(), "franchise-2", "Franchise 2"));
        trackedFranchiseIds.add(franchise2.getId());

        ScreenTitle title1 = seedTitle("PUBLISHED");
        ScreenTitle title2 = seedTitle("PUBLISHED");

        ScreenFranchiseMembership mem1 = franchiseMembershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise1.getId(), title1.getId(), 0));
        trackedMembershipIds.add(mem1.getId());
        ScreenFranchiseMembership mem2 = franchiseMembershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise2.getId(), title2.getId(), 0));
        trackedMembershipIds.add(mem2.getId());

        UUID videoId = seedMediaAsset("PUBLIC");
        // Original Entry 1 is PUBLISHED and references videoId under Title 1 / Franchise 1
        ScreenEntry entry1 = seedEntry(title1.getId(), videoId, "PUBLISHED");
        ScreenEntry entry2 = seedEntry(title2.getId(), null, "DRAFT");

        // Video was uploaded for Entry 1 under Franchise 1
        seedUploadSession(videoId, entry1.getId(), "screen", "SCREEN_ENTRY", "COMPLETED");

        // Attempting to attach to unrelated Entry 2 under Title 2 / Franchise 2 must fail provenance even though Entry 1 is PUBLISHED
        assertThatThrownBy(() -> attachEntryVideoUseCase.execute(title2.getId(), entry2.getId(), videoId))
                .isInstanceOf(ScreenVideoProvenanceException.class);

        // Verify transaction rolled back completely: Entry 2 has no video attached, Video remains PUBLIC for Entry 1
        ScreenEntry reloadedEntry2 = entryRepository.findById(entry2.getId()).orElseThrow();
        assertThat(reloadedEntry2.getVideoMediaAssetId()).isNull();
        assertThat(getMediaAssetVisibility(videoId)).isEqualTo("PUBLIC");
    }

    @Test
    @DisplayName("Scenario 6: Transaction rollback after visibility downstream failure")
    void scenario6_transactionRollbackAfterVisibilityFailure() {
        ScreenTitle title = seedTitle("PUBLISHED");
        UUID videoId = seedMediaAsset("PRIVATE");
        ScreenEntry entry = seedEntry(title.getId(), videoId, "DRAFT");
        seedUploadSession(videoId, entry.getId(), "screen", "SCREEN_ENTRY", "COMPLETED");

        simulateVisibilityFailure.set(true);

        assertThatThrownBy(() -> publishEntryUseCase.execute(title.getId(), entry.getId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated downstream media storage visibility change failure");

        // Verify entry remains DRAFT and publishedAt is null in database
        ScreenEntry reloadedEntry = entryRepository.findById(entry.getId()).orElseThrow();
        assertThat(reloadedEntry.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(reloadedEntry.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("Scenario 7: hasOtherPublishedReference breaks through MySQL REPEATABLE READ snapshot via LOCK IN SHARE MODE")
    void scenario7_hasOtherPublishedReferenceBreaksThroughSnapshot() throws Exception {
        ScreenTitle title1 = seedTitle("PUBLISHED");
        ScreenTitle title2 = seedTitle("PUBLISHED");
        UUID sharedVideoId = seedMediaAsset("PUBLIC");

        ScreenEntry entry1 = seedEntry(title1.getId(), sharedVideoId, "DRAFT");
        ScreenEntry entry2 = seedEntry(title2.getId(), sharedVideoId, "DRAFT");

        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
        tx1.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch tx1SnapshotEstablished = new CountDownLatch(1);
        CountDownLatch tx2Committed = new CountDownLatch(1);
        AtomicBoolean tx1ObservedConcurrentCommit = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> futureTx1 = executor.submit(() -> {
                tx1.execute(status -> {
                    // 1. Establish MVCC snapshot read-view in Tx1
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM screen_titles", Long.class);
                    tx1SnapshotEstablished.countDown();

                    try {
                        tx2Committed.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }

                    // 2. Call hasOtherPublishedReference which uses LOCK IN SHARE MODE
                    // If MVCC snapshot were used, it would see Entry 2 as DRAFT and return false.
                    // But with LOCK IN SHARE MODE current-read, it must see Entry 2 as PUBLISHED and return true!
                    boolean hasOther = entryRepository.hasOtherPublishedReference(sharedVideoId, entry1.getId());
                    tx1ObservedConcurrentCommit.set(hasOther);
                    return null;
                });
            });

            Future<?> futureTx2 = executor.submit(() -> {
                try {
                    tx1SnapshotEstablished.await(5, TimeUnit.SECONDS);

                    TransactionTemplate tx2 = new TransactionTemplate(transactionManager);
                    tx2.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    tx2.execute(status -> {
                        Instant now = Instant.now();
                        Timestamp tsNow = Timestamp.from(now);
                        jdbcTemplate.update(
                                "UPDATE screen_entries SET status = 'PUBLISHED', published_at = ?, updated_at = ? WHERE id = ?",
                                tsNow,
                                tsNow,
                                entry2.getId().toString()
                        );
                        return null;
                    });
                    tx2Committed.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            futureTx1.get(15, TimeUnit.SECONDS);
            futureTx2.get(15, TimeUnit.SECONDS);

            assertThat(tx1ObservedConcurrentCommit.get())
                    .as("hasOtherPublishedReference must break through MySQL REPEATABLE READ snapshot to see newly committed published reference")
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Scenario 8 (Finding A Regression): Archive Title forces L1 cache staleness via preliminary scan before concurrent Replace Video commits; detects changed binding and demotes B")
    void scenario8_archiveTitleDetectsChangedBindingDespiteHibernateL1Cache() throws Exception {
        ScreenTitle title = seedTitle("PUBLISHED");
        UUID videoA = seedMediaAsset("PUBLIC");
        UUID videoB = seedMediaAsset("PRIVATE");
        ScreenEntry entry = seedEntry(title.getId(), videoA, "PUBLISHED");

        seedUploadSession(videoA, entry.getId(), "screen", "SCREEN_ENTRY", "COMPLETED");
        seedUploadSession(videoB, entry.getId(), "screen", "SCREEN_ENTRY", "COMPLETED");

        CountDownLatch txAPreliminaryScanCachedEntryLatch = new CountDownLatch(1);
        CountDownLatch txBReplacedAndCommittedLatch = new CountDownLatch(1);
        AtomicInteger archiveLockInspections = new AtomicInteger(0);

        // Hook runs on Transaction A's first attempt right AFTER findByTitleId() cached Entry E (videoA)
        // in Transaction A's Hibernate persistence context, and BEFORE acquiring MediaAsset locks.
        beforeMediaLockHook.set(assetId -> {
            if (Thread.currentThread().getName().contains("txA-archive")
                    && archiveLockInspections.incrementAndGet() == 1) {
                txAPreliminaryScanCachedEntryLatch.countDown();
                try {
                    assertThat(txBReplacedAndCommittedLatch.await(10, TimeUnit.SECONDS))
                            .as("Transaction B must complete video replacement and commit while Transaction A holds Entry E in L1 cache")
                            .isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> futureTxA = executor.submit(() -> {
                Thread.currentThread().setName("txA-archive");
                archiveTitleUseCase.execute(title.getId());
            });

            Future<?> futureTxB = executor.submit(() -> {
                Thread.currentThread().setName("txB-replace");
                try {
                    assertThat(txAPreliminaryScanCachedEntryLatch.await(10, TimeUnit.SECONDS))
                            .as("Transaction A must finish preliminary scan and cache Entry E before Transaction B starts")
                            .isTrue();
                    replaceEntryVideoUseCase.execute(title.getId(), entry.getId(), videoB, videoA);
                    txBReplacedAndCommittedLatch.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            futureTxB.get(15, TimeUnit.SECONDS);
            futureTxA.get(15, TimeUnit.SECONDS);

            // Prove exact interleaving occurred: Attempt 1 inspected videoA, detected conflict on post-lock read,
            // rolled back Attempt 1, and Attempt 2 inspected videoB.
            assertThat(archiveLockInspections.get())
                    .as("ArchiveScreenTitleUseCase must detect post-lock binding conflict and retry in a second attempt")
                    .isEqualTo(2);

            ScreenTitle updatedTitle = titleRepository.findById(title.getId()).orElseThrow();
            ScreenEntry updatedEntry = entryRepository.findById(entry.getId()).orElseThrow();

            assertThat(updatedTitle.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
            assertThat(updatedEntry.getVideoMediaAssetId()).isEqualTo(videoB);
            assertThat(getMediaAssetVisibility(videoA)).isEqualTo("PRIVATE");
            assertThat(getMediaAssetVisibility(videoB)).isEqualTo("PRIVATE");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Scenario 9 (Finding A Regression): Publish Title forces preliminary scan before concurrent Publish Entry & Replace Video commit; detects changed bindings and promotes all published videos")
    void scenario9_publishTitleDetectsNewlyPublishedEntryAndChangedBindingDespiteHibernateL1Cache() throws Exception {
        ScreenTitle title = seedTitle("DRAFT");
        UUID videoA = seedMediaAsset("PRIVATE");
        UUID videoB = seedMediaAsset("PRIVATE");
        UUID videoC = seedMediaAsset("PRIVATE");

        // Entry 1 is already PUBLISHED with videoA (so preliminary scan caches Entry 1 with videoA in L1)
        ScreenEntry entry1 = seedEntry(title.getId(), videoA, "PUBLISHED");
        // Entry 2 is initially DRAFT with videoC
        ScreenEntry entry2 = seedEntry(title.getId(), videoC, "DRAFT");

        seedUploadSession(videoA, entry1.getId(), "screen", "SCREEN_ENTRY", "COMPLETED");
        seedUploadSession(videoB, entry1.getId(), "screen", "SCREEN_ENTRY", "COMPLETED");
        seedUploadSession(videoC, entry2.getId(), "screen", "SCREEN_ENTRY", "COMPLETED");

        CountDownLatch txAPreliminaryScanCachedLatch = new CountDownLatch(1);
        CountDownLatch txBCommittedLatch = new CountDownLatch(1);
        AtomicInteger publishTitleLockAttempts = new AtomicInteger(0);

        beforeMediaLockHook.set(assetId -> {
            if (Thread.currentThread().getName().contains("txA-publish-title")
                    && publishTitleLockAttempts.incrementAndGet() == 1) {
                txAPreliminaryScanCachedLatch.countDown();
                try {
                    assertThat(txBCommittedLatch.await(10, TimeUnit.SECONDS))
                            .as("Transaction B must commit entry replacement and new entry publication while Transaction A is paused after preliminary scan")
                            .isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> futureTxA = executor.submit(() -> {
                Thread.currentThread().setName("txA-publish-title");
                publishTitleUseCase.execute(title.getId());
            });

            Future<?> futureTxB = executor.submit(() -> {
                Thread.currentThread().setName("txB-concurrent-entry-mutations");
                try {
                    assertThat(txAPreliminaryScanCachedLatch.await(10, TimeUnit.SECONDS))
                            .as("Transaction A must finish preliminary scan before Transaction B mutates entries")
                            .isTrue();
                    // 1. Replace cached Entry 1's video from videoA to videoB
                    replaceEntryVideoUseCase.execute(title.getId(), entry1.getId(), videoB, videoA);
                    // 2. Publish Entry 2 (with videoC) while Title is still DRAFT
                    publishEntryUseCase.execute(title.getId(), entry2.getId());
                    txBCommittedLatch.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            futureTxB.get(15, TimeUnit.SECONDS);
            futureTxA.get(15, TimeUnit.SECONDS);

            // Attempt 1 locked videoA (1 call); Attempt 2 locked both videoB and videoC (2 calls) -> total >= 3
            assertThat(publishTitleLockAttempts.get())
                    .as("PublishScreenTitleUseCase must detect post-lock binding conflict and retry")
                    .isGreaterThanOrEqualTo(2);

            ScreenTitle updatedTitle = titleRepository.findById(title.getId()).orElseThrow();
            ScreenEntry updatedEntry1 = entryRepository.findById(entry1.getId()).orElseThrow();
            ScreenEntry updatedEntry2 = entryRepository.findById(entry2.getId()).orElseThrow();

            assertThat(updatedTitle.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
            assertThat(updatedEntry1.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
            assertThat(updatedEntry1.getVideoMediaAssetId()).isEqualTo(videoB);
            assertThat(updatedEntry2.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
            assertThat(updatedEntry2.getVideoMediaAssetId()).isEqualTo(videoC);

            assertThat(getMediaAssetVisibility(videoA)).isEqualTo("PRIVATE");
            assertThat(getMediaAssetVisibility(videoB)).isEqualTo("PUBLIC");
            assertThat(getMediaAssetVisibility(videoC)).isEqualTo("PUBLIC");
        } finally {
            executor.shutdownNow();
        }
    }

    @TestConfiguration
    static class TestWiringConfig {

        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }

        @Bean
        @Primary
        public MediaContract testMediaContract(JdbcTemplate jdbcTemplate) {
            return new TestMediaContract(jdbcTemplate);
        }
    }

    static class TestMediaContract implements MediaContract {
        private final JdbcTemplate jdbcTemplate;

        TestMediaContract(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public MediaAssetDetailDTO lockAndValidateActiveAsset(UUID id, MediaTypeDTO expectedType) {
            Consumer<UUID> hook = beforeMediaLockHook.get();
            if (hook != null) {
                hook.accept(id);
            }
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT id, media_type, status, visibility, current_version_number, created_at, updated_at FROM media_assets WHERE id = ? FOR UPDATE",
                    id.toString()
            );
            String statusStr = (String) row.get("status");
            if ("DELETED".equals(statusStr)) {
                throw new MediaAssetNotFoundException(id);
            }
            List<Map<String, Object>> versionRows = jdbcTemplate.queryForList(
                    "SELECT id, mime_type, size_bytes, original_filename, created_at FROM media_asset_versions WHERE asset_id = ? AND version_number = ?",
                    id.toString(),
                    row.get("current_version_number")
            );
            MediaVersionDTO versionDTO = null;
            if (!versionRows.isEmpty()) {
                Map<String, Object> vRow = versionRows.get(0);
                versionDTO = new MediaVersionDTO(
                        UUID.fromString((String) vRow.get("id")),
                        id,
                        ((Number) row.get("current_version_number")).intValue(),
                        "https://media.kiemlai.test/video.mp4",
                        (String) vRow.get("mime_type"),
                        ((Number) vRow.get("size_bytes")).longValue(),
                        (String) vRow.get("original_filename"),
                        toInstant(vRow.get("created_at"))
                );
            }
            return new MediaAssetDetailDTO(
                    id,
                    MediaTypeDTO.valueOf((String) row.get("media_type")),
                    MediaVisibilityDTO.valueOf((String) row.get("visibility")),
                    MediaAssetStatusDTO.valueOf(statusStr),
                    ((Number) row.get("current_version_number")).intValue(),
                    toInstant(row.get("created_at")),
                    toInstant(row.get("updated_at")),
                    versionDTO
            );
        }

        @Override
        public MediaAssetInspectionDTO lockAndInspectAsset(UUID id, MediaTypeDTO expectedType) {
            Consumer<UUID> hook = beforeMediaLockHook.get();
            if (hook != null) {
                hook.accept(id);
            }
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT id, media_type, status, visibility, current_version_number FROM media_assets WHERE id = ? FOR UPDATE",
                    id.toString()
            );
            return new MediaAssetInspectionDTO(
                    id,
                    MediaTypeDTO.valueOf((String) row.get("media_type")),
                    MediaVisibilityDTO.valueOf((String) row.get("visibility")),
                    MediaAssetStatusDTO.valueOf((String) row.get("status")),
                    ((Number) row.get("current_version_number")).intValue()
            );
        }

        @Override
        public Optional<MediaUploadSessionResponseDTO> findCompletedSessionByAssetId(UUID assetId) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM media_upload_sessions WHERE registered_asset_id = ? AND status = 'COMPLETED'",
                    assetId.toString()
            );
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> row = rows.get(0);
            return Optional.of(new MediaUploadSessionResponseDTO(
                    UUID.fromString((String) row.get("id")),
                    (String) row.get("status"),
                    (String) row.get("initiator_principal_id"),
                    (String) row.get("consumer_context"),
                    (String) row.get("target_entity_type"),
                    UUID.fromString((String) row.get("target_entity_id")),
                    ((Number) row.get("declared_size_bytes")).longValue(),
                    ((Number) row.get("declared_size_bytes")).longValue(),
                    ((Number) row.get("expected_part_count")).intValue(),
                    (String) row.get("original_filename"),
                    (String) row.get("client_tag"),
                    assetId,
                    Instant.now(),
                    Instant.now().plusSeconds(3600),
                    Instant.now()
            ));
        }

        @Override
        public void changeVisibility(ChangeMediaVisibilityRequestDTO request) {
            if (simulateVisibilityFailure.get()) {
                throw new RuntimeException("Simulated downstream media storage visibility change failure");
            }
            jdbcTemplate.update(
                    "UPDATE media_assets SET visibility = ?, updated_at = NOW() WHERE id = ?",
                    request.newVisibility().name(),
                    request.assetId().toString()
            );
        }

        @Override
        public Optional<MediaAssetDetailDTO> getAssetDetail(UUID id) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, media_type, status, visibility, current_version_number, created_at, updated_at FROM media_assets WHERE id = ?",
                    id.toString()
            );
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> row = rows.get(0);
            List<Map<String, Object>> versionRows = jdbcTemplate.queryForList(
                    "SELECT id, mime_type, size_bytes, original_filename, created_at FROM media_asset_versions WHERE asset_id = ? AND version_number = ?",
                    id.toString(),
                    row.get("current_version_number")
            );
            MediaVersionDTO versionDTO = null;
            if (!versionRows.isEmpty()) {
                Map<String, Object> vRow = versionRows.get(0);
                versionDTO = new MediaVersionDTO(
                        UUID.fromString((String) vRow.get("id")),
                        id,
                        ((Number) row.get("current_version_number")).intValue(),
                        "https://media.kiemlai.test/video.mp4",
                        (String) vRow.get("mime_type"),
                        ((Number) vRow.get("size_bytes")).longValue(),
                        (String) vRow.get("original_filename"),
                        toInstant(vRow.get("created_at"))
                );
            }
            return Optional.of(new MediaAssetDetailDTO(
                    id,
                    MediaTypeDTO.valueOf((String) row.get("media_type")),
                    MediaVisibilityDTO.valueOf((String) row.get("visibility")),
                    MediaAssetStatusDTO.valueOf((String) row.get("status")),
                    ((Number) row.get("current_version_number")).intValue(),
                    toInstant(row.get("created_at")),
                    toInstant(row.get("updated_at")),
                    versionDTO
            ));
        }

        private static Instant toInstant(Object val) {
            if (val == null) {
                return Instant.now();
            }
            if (val instanceof Timestamp ts) {
                return ts.toInstant();
            }
            if (val instanceof java.time.LocalDateTime ldt) {
                return ldt.toInstant(java.time.ZoneOffset.UTC);
            }
            if (val instanceof Instant inst) {
                return inst;
            }
            return Instant.now();
        }

        @Override
        public com.universe.media.contracts.dto.UploadMediaAssetResponseDTO uploadAsset(com.universe.media.contracts.dto.UploadMediaAssetRequestDTO request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.universe.media.contracts.dto.UploadMediaAssetVersionResponseDTO uploadVersion(com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.universe.media.contracts.dto.UploadMediaAssetVersionConditionalResponseDTO uploadVersionIfContentChanged(com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.universe.media.contracts.dto.MediaAssetCurrentMetadataDTO> getAssetCurrentMetadata(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO> getCurrentVersionSnapshot(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.universe.media.contracts.dto.MediaAssetVersionContentDTO openVersionContent(com.universe.media.contracts.dto.MediaAssetVersionReferenceDTO reference) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void archive(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void restore(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(UUID id) {
            jdbcTemplate.update("UPDATE media_assets SET status = 'DELETED', updated_at = NOW() WHERE id = ?", id.toString());
        }

        @Override
        public void assignClientTagIfAbsent(UUID id, String clientTag) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.universe.media.contracts.dto.MediaAssetCandidateDTO> findActiveAssetsByClientTagKeyset(com.universe.media.contracts.dto.FindActiveMediaAssetsKeysetQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void generateImageVariant(com.universe.media.contracts.dto.GenerateImageVariantRequestDTO request) {
            throw new UnsupportedOperationException();
        }
    }
}
