package com.universe.screen.infrastructure.persistence;

import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.AssignMediaAssetClientTagUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.FindActiveMediaAssetsByClientTagKeysetUseCase;
import com.universe.media.application.asset.GetCurrentMediaAssetVersionSnapshotUseCase;
import com.universe.media.application.asset.GetMediaAssetCurrentMetadataUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.LockAndValidateActiveMediaAssetUseCase;
import com.universe.media.application.asset.OpenMediaAssetVersionContentUseCase;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionUseCase;
import com.universe.media.application.facade.MediaFacade;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.variant.GenerateMediaImageVariantUseCase;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.infrastructure.persistence.MediaAssetPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaAssetVersionPersistenceAdapter;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.usecase.AttachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.ReplaceScreenEntryVideoUseCase;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import com.universe.screen.infrastructure.media.ScreenMediaValidationAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Physical MySQL integration test verifying cross-module row locking participation
 * across the Screen use case boundary and Media transaction boundary.
 *
 * <p>Proves:
 * <ul>
 *   <li>A real {@link AttachScreenEntryVideoUseCase} invocation acquires the {@code MediaAsset} lock.</li>
 *   <li>The lock remains continuously held while {@code ScreenEntry} persistence is still uncommitted.</li>
 *   <li>An independent database connection fails to acquire {@code FOR UPDATE NOWAIT} while the transaction is active.</li>
 *   <li>After the Screen transaction commits, the independent connection acquires the asset lock.</li>
 *   <li>{@link ReplaceScreenEntryVideoUseCase} uses the exact same required locking contract.</li>
 *   <li>If the Screen transaction rolls back, the {@code MediaAsset} lock is cleanly released.</li>
 * </ul>
 *
 * <p>SAFETY: This test is designed and compiled for isolated verification, but is NOT executed during offline preflight.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
        MediaAssetPersistenceAdapter.class,
        MediaAssetVersionPersistenceAdapter.class,
        LockAndValidateActiveMediaAssetUseCase.class,
        ScreenMediaValidationAdapter.class,
        ScreenTitlePersistenceAdapter.class,
        ScreenEntryPersistenceAdapter.class,
        AttachScreenEntryVideoUseCase.class,
        ReplaceScreenEntryVideoUseCase.class,
        ScreenMediaAssetLockIntegrationTest.TestWiringConfig.class
})
@DisplayName("Screen MediaAsset Lock Physical MySQL Integration Tests")
class ScreenMediaAssetLockIntegrationTest {

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
    private ScreenMediaValidationPort screenMediaValidationPort;

    @Autowired
    private AttachScreenEntryVideoUseCase attachScreenEntryVideoUseCase;

    @Autowired
    private ReplaceScreenEntryVideoUseCase replaceScreenEntryVideoUseCase;

    @Autowired
    private LockContentionObservingClock contentionClock;

    private UUID titleId;
    private UUID entryId;
    private UUID initialAssetId;
    private UUID replacementAssetId;

    @BeforeEach
    void setUp() {
        titleId = UUID.randomUUID();
        entryId = UUID.randomUUID();
        initialAssetId = UUID.randomUUID();
        replacementAssetId = UUID.randomUUID();

        cleanup();

        // 1. Seed active initial MediaAsset
        seedMediaAsset(initialAssetId, "videos/" + initialAssetId + ".mp4");

        // 2. Seed active replacement MediaAsset
        seedMediaAsset(replacementAssetId, "videos/" + replacementAssetId + ".mp4");

        // 3. Seed ScreenTitle
        ScreenTitle title = ScreenTitle.createDraft(
                titleId,
                "title-" + titleId.toString().substring(0, 8),
                "Test Title",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                Instant.now()
        );
        titleRepository.save(title);
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
        if (initialAssetId != null) {
            jdbcTemplate.update("DELETE FROM media_asset_versions WHERE asset_id = ?", initialAssetId.toString());
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", initialAssetId.toString());
        }
        if (replacementAssetId != null) {
            jdbcTemplate.update("DELETE FROM media_asset_versions WHERE asset_id = ?", replacementAssetId.toString());
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", replacementAssetId.toString());
        }
    }

    private void seedMediaAsset(UUID aId, String storageKey) {
        Instant now = Instant.now();
        UUID versionId = UUID.randomUUID();
        String publicUrl = "https://cdn.example.com/" + storageKey;

        // Seed media_assets row
        jdbcTemplate.update("""
                INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, client_tag, created_at, updated_at)
                VALUES (?, 'VIDEO', 'PRIVATE', 'ACTIVE', 1, 'smoke-tag', ?, ?)
                """, aId.toString(), now, now);

        // Seed media_asset_versions row
        jdbcTemplate.update("""
                INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, original_filename, created_at)
                VALUES (?, ?, 1, 'b2', ?, ?, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'video/mp4', 10485760, 'video.mp4', ?)
                """, versionId.toString(), aId.toString(), storageKey, publicUrl, now);
    }

    private void seedScreenEntry(UUID currentVideoId) {
        ScreenEntry entry = ScreenEntry.createDraft(
                entryId,
                titleId,
                "entry-" + entryId.toString().substring(0, 8),
                ScreenEntryType.EPISODE,
                1,
                "Episode 1",
                "Synopsis",
                currentVideoId,
                null,
                1200L,
                Instant.now()
        );
        entryRepository.save(entry);
    }

    @Test
    @DisplayName("Attach: real AttachScreenEntryVideoUseCase invocation acquires MediaAsset lock and holds it until commit")
    void shouldHoldMediaAssetLockDuringScreenAttachUntilCommit() {
        seedScreenEntry(null);

        // Configure clock to observe lock contention on initialAssetId while inside the uncommitted transaction
        contentionClock.expectContentionOn(initialAssetId);

        // Execute real Screen attach use-case boundary
        ScreenEntryDTO result = attachScreenEntryVideoUseCase.execute(titleId, entryId, initialAssetId);

        assertThat(result).isNotNull();
        assertThat(result.videoMediaAssetId()).isEqualTo(initialAssetId);

        // Verify that lock contention was physically observed on an independent connection while uncommitted
        assertThat(contentionClock.wasContentionObserved())
                .as("MediaAsset row lock must have blocked concurrent NOWAIT acquisition while Screen transaction was in-flight")
                .isTrue();

        // Verify that once the Screen transaction has committed, the lock is released
        verifyLockAvailable(initialAssetId);
    }

    @Test
    @DisplayName("Replace: real ReplaceScreenEntryVideoUseCase invocation acquires new MediaAsset lock and holds it until commit")
    void shouldHoldMediaAssetLockDuringScreenReplaceUntilCommit() {
        seedScreenEntry(initialAssetId);

        // Configure clock to observe lock contention on replacementAssetId while inside the uncommitted transaction
        contentionClock.expectContentionOn(replacementAssetId);

        // Execute real Screen replace use-case boundary
        ScreenEntryDTO result = replaceScreenEntryVideoUseCase.execute(
                titleId, entryId, replacementAssetId, initialAssetId
        );

        assertThat(result).isNotNull();
        assertThat(result.videoMediaAssetId()).isEqualTo(replacementAssetId);

        // Verify that lock contention was physically observed on replacementAssetId
        assertThat(contentionClock.wasContentionObserved())
                .as("Replacement MediaAsset row lock must have blocked concurrent NOWAIT acquisition while Screen transaction was in-flight")
                .isTrue();

        // Verify that once the Screen transaction has committed, the lock is released
        verifyLockAvailable(replacementAssetId);

        // Verify that initialAssetId was not locked or modified
        verifyLockAvailable(initialAssetId);
    }

    @Test
    @DisplayName("Rollback: MediaAsset row lock is cleanly released if Screen transaction rolls back")
    void shouldReleaseMediaAssetLockWhenScreenTransactionRollsBack() {
        // Step 1: ScreenEntry starts with videoMediaAssetId = null
        seedScreenEntry(null);
        assertThat(entryRepository.findById(entryId).orElseThrow().getVideoMediaAssetId())
                .as("ScreenEntry must initially have null videoMediaAssetId")
                .isNull();

        // Step 2: Configure ClockPort observer to verify in-flight contention on initialAssetId
        contentionClock.expectContentionOn(initialAssetId);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Step 3: Exercise actual AttachScreenEntryVideoUseCase inside outer transaction forced to roll back
        assertThatThrownBy(() -> txTemplate.execute(status -> {
            attachScreenEntryVideoUseCase.execute(titleId, entryId, initialAssetId);
            throw new RuntimeException("Simulated Screen persistence rollback after attach");
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated Screen persistence rollback after attach");

        // Step 4: Verify in-flight contention was observed with MySQL 3572 before rollback
        assertThat(contentionClock.wasContentionObserved())
                .as("In-flight lock contention (MySQL 3572) must have been observed during AttachScreenEntryVideoUseCase")
                .isTrue();

        // Step 5: Verify committed ScreenEntry.videoMediaAssetId remains null after rollback
        ScreenEntry reloadedEntry = entryRepository.findById(entryId).orElseThrow();
        assertThat(reloadedEntry.getVideoMediaAssetId())
                .as("Committed ScreenEntry videoMediaAssetId must remain null after rollback")
                .isNull();

        // Step 6: Verify MediaAsset status remains ACTIVE
        String mediaAssetStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?",
                String.class,
                initialAssetId.toString()
        );
        assertThat(mediaAssetStatus)
                .as("MediaAsset status must remain ACTIVE after Screen transaction rollback")
                .isEqualTo("ACTIVE");

        // Step 7: Separate connection acquires MediaAsset lock after rollback
        verifyLockAvailable(initialAssetId);
    }

    private void verifyLockAvailable(UUID assetIdToVerify) {
        try (Connection independentConn = dataSource.getConnection()) {
            independentConn.setAutoCommit(false);
            try (PreparedStatement stmt = independentConn.prepareStatement(
                    "SELECT id FROM media_assets WHERE id = ? FOR UPDATE NOWAIT")) {
                stmt.setQueryTimeout(3);
                stmt.setString(1, assetIdToVerify.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("id")).isEqualTo(assetIdToVerify.toString());
                }
            } finally {
                independentConn.rollback();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Lock on media_assets [" + assetIdToVerify + "] should be released but threw: " + e.getMessage(), e);
        }
    }

    private static boolean isLockNowaitError(SQLException e) {
        // MySQL error code 3572 is ER_LOCK_NOWAIT
        // ("Statement aborted because lock(s) could not be acquired with NOWAIT.")
        return e.getErrorCode() == 3572;
    }

    /**
     * Bounded test configuration wiring real MediaFacade with real LockAndValidateActiveMediaAssetUseCase
     * and a Contention-Observing ClockPort.
     */
    @TestConfiguration
    static class TestWiringConfig {

        @Bean
        public MediaContract mediaContract(
                GetMediaAssetDetailUseCase getMediaAssetDetailUseCase,
                GetMediaAssetCurrentMetadataUseCase getMediaAssetCurrentMetadataUseCase,
                ChangeMediaVisibilityUseCase changeMediaVisibilityUseCase,
                ArchiveMediaAssetUseCase archiveMediaAssetUseCase,
                RestoreMediaAssetUseCase restoreMediaAssetUseCase,
                DeleteMediaAssetUseCase deleteMediaAssetUseCase,
                UploadMediaAssetUseCase uploadMediaAssetUseCase,
                UploadMediaAssetVersionUseCase uploadMediaAssetVersionUseCase,
                GenerateMediaImageVariantUseCase generateMediaImageVariantUseCase,
                GetCurrentMediaAssetVersionSnapshotUseCase getCurrentMediaAssetVersionSnapshotUseCase,
                OpenMediaAssetVersionContentUseCase openMediaAssetVersionContentUseCase,
                AssignMediaAssetClientTagUseCase assignMediaAssetClientTagUseCase,
                FindActiveMediaAssetsByClientTagKeysetUseCase findActiveMediaAssetsByClientTagKeysetUseCase,
                UploadMediaAssetVersionConditionalUseCase uploadMediaAssetVersionConditionalUseCase,
                LockAndValidateActiveMediaAssetUseCase lockAndValidateActiveMediaAssetUseCase
        ) {
            return new MediaFacade(
                    getMediaAssetDetailUseCase,
                    getMediaAssetCurrentMetadataUseCase,
                    changeMediaVisibilityUseCase,
                    archiveMediaAssetUseCase,
                    restoreMediaAssetUseCase,
                    deleteMediaAssetUseCase,
                    uploadMediaAssetUseCase,
                    uploadMediaAssetVersionUseCase,
                    generateMediaImageVariantUseCase,
                    getCurrentMediaAssetVersionSnapshotUseCase,
                    openMediaAssetVersionContentUseCase,
                    assignMediaAssetClientTagUseCase,
                    findActiveMediaAssetsByClientTagKeysetUseCase,
                    uploadMediaAssetVersionConditionalUseCase,
                    lockAndValidateActiveMediaAssetUseCase
            );
        }

        @Bean
        public GetMediaAssetDetailUseCase getMediaAssetDetailUseCase(
                MediaAssetRepositoryPort assetRepo,
                MediaAssetVersionRepositoryPort versionRepo
        ) {
            return new GetMediaAssetDetailUseCase(assetRepo, versionRepo);
        }

        @Bean
        public GetMediaAssetCurrentMetadataUseCase getMediaAssetCurrentMetadataUseCase() {
            return Mockito.mock(GetMediaAssetCurrentMetadataUseCase.class);
        }

        @Bean
        public ChangeMediaVisibilityUseCase changeMediaVisibilityUseCase() {
            return Mockito.mock(ChangeMediaVisibilityUseCase.class);
        }

        @Bean
        public ArchiveMediaAssetUseCase archiveMediaAssetUseCase() {
            return Mockito.mock(ArchiveMediaAssetUseCase.class);
        }

        @Bean
        public RestoreMediaAssetUseCase restoreMediaAssetUseCase() {
            return Mockito.mock(RestoreMediaAssetUseCase.class);
        }

        @Bean
        public DeleteMediaAssetUseCase deleteMediaAssetUseCase() {
            return Mockito.mock(DeleteMediaAssetUseCase.class);
        }

        @Bean
        public UploadMediaAssetUseCase uploadMediaAssetUseCase() {
            return Mockito.mock(UploadMediaAssetUseCase.class);
        }

        @Bean
        public UploadMediaAssetVersionUseCase uploadMediaAssetVersionUseCase() {
            return Mockito.mock(UploadMediaAssetVersionUseCase.class);
        }

        @Bean
        public GenerateMediaImageVariantUseCase generateMediaImageVariantUseCase() {
            return Mockito.mock(GenerateMediaImageVariantUseCase.class);
        }

        @Bean
        public GetCurrentMediaAssetVersionSnapshotUseCase getCurrentMediaAssetVersionSnapshotUseCase() {
            return Mockito.mock(GetCurrentMediaAssetVersionSnapshotUseCase.class);
        }

        @Bean
        public OpenMediaAssetVersionContentUseCase openMediaAssetVersionContentUseCase() {
            return Mockito.mock(OpenMediaAssetVersionContentUseCase.class);
        }

        @Bean
        public AssignMediaAssetClientTagUseCase assignMediaAssetClientTagUseCase() {
            return Mockito.mock(AssignMediaAssetClientTagUseCase.class);
        }

        @Bean
        public FindActiveMediaAssetsByClientTagKeysetUseCase findActiveMediaAssetsByClientTagKeysetUseCase() {
            return Mockito.mock(FindActiveMediaAssetsByClientTagKeysetUseCase.class);
        }

        @Bean
        public UploadMediaAssetVersionConditionalUseCase uploadMediaAssetVersionConditionalUseCase() {
            return Mockito.mock(UploadMediaAssetVersionConditionalUseCase.class);
        }

        @Bean
        public LockContentionObservingClock contentionClock(DataSource dataSource) {
            return new LockContentionObservingClock(dataSource);
        }

        @Bean
        public ClockPort clockPort(LockContentionObservingClock clock) {
            return clock;
        }
    }

    /**
     * Test-specific ClockPort implementation that acts as an in-flight transaction observer.
     *
     * <p>When {@link #now()} is invoked by the Screen use cases (between Media/Screen locking
     * and final persistence commit), this observer uses an independent database connection
     * to attempt {@code SELECT ... FOR UPDATE NOWAIT} on the target {@code media_assets} row.
     */
    static class LockContentionObservingClock implements ClockPort {

        private final DataSource dataSource;
        private final AtomicReference<UUID> probeAssetId = new AtomicReference<>();
        private final AtomicBoolean lockContentionObserved = new AtomicBoolean(false);

        LockContentionObservingClock(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        void expectContentionOn(UUID assetId) {
            this.probeAssetId.set(assetId);
            this.lockContentionObserved.set(false);
        }

        boolean wasContentionObserved() {
            return lockContentionObserved.get();
        }

        @Override
        public Instant now() {
            UUID target = probeAssetId.get();
            if (target != null) {
                // We are inside the active Screen transaction: Step 1 (MediaAsset lock) and Step 2 (ScreenEntry lock)
                // have succeeded, but ScreenEntry has not yet been saved / committed.
                try (Connection independentConn = dataSource.getConnection()) {
                    independentConn.setAutoCommit(false);
                    try (PreparedStatement stmt = independentConn.prepareStatement(
                            "SELECT id FROM media_assets WHERE id = ? FOR UPDATE NOWAIT")) {
                        stmt.setQueryTimeout(3);
                        stmt.setString(1, target.toString());
                        // Attempting to acquire lock with NOWAIT on the independent connection MUST fail
                        stmt.executeQuery();
                        throw new IllegalStateException("Expected NOWAIT to fail because media_assets row is held by in-flight Screen transaction!");
                    } catch (SQLException e) {
                        if (!isLockNowaitError(e)) {
                            throw new RuntimeException("Unexpected database error during lock contention observation (expected MySQL error code 3572 ER_LOCK_NOWAIT, got " + e.getErrorCode() + "): " + e.getMessage(), e);
                        }
                        lockContentionObserved.set(true);
                    } finally {
                        independentConn.rollback();
                    }
                } catch (SQLException e) {
                    throw new RuntimeException("Unexpected error on independent connection during lock observation", e);
                }
            }
            return Instant.now();
        }
    }
}
