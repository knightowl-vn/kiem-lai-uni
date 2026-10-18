package com.universe.media.infrastructure.persistence;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.exceptions.StorageUploadNotFoundException;
import com.universe.media.application.ports.storage.MultipartCompletedObject;
import com.universe.media.application.ports.storage.MultipartObjectMetadata;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.application.ports.storage.MultipartUploadedPart;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.MediaUploadSessionService;
import com.universe.media.application.storage.MultipartManifestCanonicalizer;
import com.universe.media.application.storage.MultipartManifestPart;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real MySQL integration tests for {@link MediaUploadSessionService#completeMultipartUpload(UUID, String, List)}
 * and its transactional registration workflow (TX-B).
 *
 * <p>Verifies:
 * <ul>
 *     <li>Atomic registration across {@code media_assets}, {@code media_asset_versions},
 *         {@code media_storage_key_allocations}, and {@code media_upload_sessions} in a single physical MySQL transaction;</li>
 *     <li>Full ACID rollback with in-flight flush observation and physical database rollback;</li>
 *     <li>Idempotent re-execution without duplicate asset or version registration;</li>
 *     <li>Fail-closed behavior on unacknowledged provider completion and missing uploads;</li>
 *     <li>Controlled multi-worker concurrency and lease fencing;</li>
 *     <li>Expired completion lease reclamation and stale worker rejection;</li>
 *     <li>Active completion claim rejection of concurrent abort operations;</li>
 *     <li>Strict {@link Propagation#NEVER} proxy and runtime guard enforcement;</li>
 *     <li>Provider storage operations execute outside any active database transaction.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "media.storage.b2.bucket=kiemlai-video",
        "media.storage.r2.bucket=kiemlai-r2"
})
@Import({
        MediaUploadSessionPersistenceAdapter.class,
        StorageKeyAllocationPersistenceAdapter.class,
        MediaAssetPersistenceAdapter.class,
        MediaAssetVersionPersistenceAdapter.class,
        StorageKeyAllocationService.class,
        MediaStorageRoutingService.class,
        MediaUploadSessionService.class,
        MediaUploadSessionCompleteIntegrationTest.TestConfig.class
})
@DisplayName("MediaUploadSession Real MySQL CompleteMultipartUpload & Registration Atomicity Integration Test")
class MediaUploadSessionCompleteIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        private static final AtomicReference<Instant> testClock = new AtomicReference<>();

        public static void setClock(Instant instant) {
            testClock.set(instant);
        }

        public static void resetClock() {
            testClock.set(null);
        }

        @Bean
        public ClockPort clockPort() {
            return () -> {
                Instant fixed = testClock.get();
                return fixed != null ? fixed : Instant.now();
            };
        }

        @Bean
        public MultipartStoragePort multipartStoragePort() {
            return Mockito.mock(MultipartStoragePort.class);
        }
    }

    @Autowired
    private MediaUploadSessionService sessionService;

    @Autowired
    private ClockPort clockPort;

    @Autowired
    private MultipartStoragePort multipartStoragePort;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private StorageKeyAllocationPersistenceAdapter storageKeyAllocationPersistenceAdapter;

    @SpyBean
    private MediaAssetPersistenceAdapter mediaAssetPersistenceAdapter;

    @SpyBean
    private MediaAssetVersionPersistenceAdapter mediaAssetVersionPersistenceAdapter;

    @SpyBean
    private StorageKeyAllocationService storageKeyAllocationService;

    @SpyBean
    private MediaUploadSessionPersistenceAdapter mediaUploadSessionPersistenceAdapter;

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";
    private static final String PRINCIPAL = "user-creator-s2cc";
    private static final String CONSUMER = "SCREEN";
    private static final String TARGET_TYPE = "SCREEN_ENTRY";
    private static final String CLIENT_TAG = "screen-video";
    private static final String VALID_CONTENT_HASH = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final long DECLARED_SIZE = 52428800L; // 50 MiB, 2 parts of 26214400 bytes each
    private static final String PROVIDER_UPLOAD_ID = "b2-up-real-atomicity-001";

    private final List<UUID> createdSessionIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdAllocationIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdAssetIds = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        TestConfig.resetClock();
        Mockito.reset(
                multipartStoragePort,
                mediaAssetPersistenceAdapter,
                mediaAssetVersionPersistenceAdapter,
                storageKeyAllocationService,
                mediaUploadSessionPersistenceAdapter
        );

        // Fixture-specific real persistence spy tracking:
        // Automatically capture every MediaAsset ID saved through the real persistence adapter
        Mockito.doAnswer(inv -> {
            MediaAsset asset = inv.getArgument(0);
            if (asset != null && asset.getId() != null) {
                createdAssetIds.add(asset.getId());
            }
            return inv.callRealMethod();
        }).when(mediaAssetPersistenceAdapter).save(any(MediaAsset.class));
    }

    @AfterEach
    void tearDown() {
        TestConfig.resetClock();
        Mockito.reset(
                multipartStoragePort,
                mediaAssetPersistenceAdapter,
                mediaAssetVersionPersistenceAdapter,
                storageKeyAllocationService,
                mediaUploadSessionPersistenceAdapter
        );

        List<String> cleanupErrors = new ArrayList<>();

        // 1. Discover dynamically registered asset IDs from test-owned sessions
        for (UUID sessionId : createdSessionIds) {
            try {
                List<String> registeredAssetIds = jdbcTemplate.queryForList(
                        "SELECT registered_asset_id FROM media_upload_sessions WHERE id = ? AND registered_asset_id IS NOT NULL",
                        String.class,
                        sessionId.toString()
                );
                for (String assetIdStr : registeredAssetIds) {
                    createdAssetIds.add(UUID.fromString(assetIdStr));
                }
            } catch (Exception e) {
                cleanupErrors.add("Failed to query registered_asset_id from media_upload_sessions [id=" + sessionId + "]: " + e.getMessage());
            }
        }

        // 2. Discover dynamically committed asset IDs from test-owned allocations
        for (UUID allocId : createdAllocationIds) {
            try {
                List<String> targetIds = jdbcTemplate.queryForList(
                        "SELECT allocated_by_id FROM media_storage_key_allocations WHERE id = ? AND allocated_by_type = 'ASSET_VERSION'",
                        String.class,
                        allocId.toString()
                );
                for (String versionIdStr : targetIds) {
                    List<String> assetIds = jdbcTemplate.queryForList(
                            "SELECT asset_id FROM media_asset_versions WHERE id = ?",
                            String.class,
                            versionIdStr
                    );
                    for (String assetIdStr : assetIds) {
                        createdAssetIds.add(UUID.fromString(assetIdStr));
                    }
                }
            } catch (Exception e) {
                cleanupErrors.add("Failed to query asset_id from media_storage_key_allocations [id=" + allocId + "]: " + e.getMessage());
            }
        }

        // 3. Strict foreign-key deletion sequence:
        // Step A: media_upload_sessions (references media_assets and media_storage_key_allocations)
        for (UUID sessionId : createdSessionIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_upload_sessions WHERE id = ?", sessionId.toString());
            } catch (Exception e) {
                cleanupErrors.add("Failed to delete media_upload_sessions [id=" + sessionId + "]: " + e.getMessage());
            }
        }

        // Step B: media_asset_versions (references media_assets)
        for (UUID assetId : createdAssetIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_asset_versions WHERE asset_id = ?", assetId.toString());
            } catch (Exception e) {
                cleanupErrors.add("Failed to delete media_asset_versions [asset_id=" + assetId + "]: " + e.getMessage());
            }
        }

        // Step C: media_assets
        for (UUID assetId : createdAssetIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", assetId.toString());
            } catch (Exception e) {
                cleanupErrors.add("Failed to delete media_assets [id=" + assetId + "]: " + e.getMessage());
            }
        }

        // Step D: media_storage_key_allocations
        for (UUID allocId : createdAllocationIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_storage_key_allocations WHERE id = ?", allocId.toString());
            } catch (Exception e) {
                cleanupErrors.add("Failed to delete media_storage_key_allocations [id=" + allocId + "]: " + e.getMessage());
            }
        }

        if (!cleanupErrors.isEmpty()) {
            throw new AssertionError("Database fixture cleanup failed with " + cleanupErrors.size() + " error(s):\n"
                    + String.join("\n", cleanupErrors));
        }
    }

    private record TestFixture(
            UUID sessionId,
            UUID allocationId,
            StorageKey storageKey,
            List<MultipartManifestPart> manifest,
            String manifestHash
    ) {}

    private List<MultipartManifestPart> createStandard2PartManifest() {
        return List.of(
                new MultipartManifestPart(1, 26214400L, "\"etag-part-1\""),
                new MultipartManifestPart(2, 26214400L, "\"etag-part-2\"")
        );
    }

    private List<MultipartUploadedPart> createStandard2UploadedParts() {
        return List.of(
                new MultipartUploadedPart(1, 26214400L, "\"etag-part-1\""),
                new MultipartUploadedPart(2, 26214400L, "\"etag-part-2\"")
        );
    }

    private TestFixture createAndPersistCompletingFixture(String suffix) {
        UUID sessionId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        StorageKey storageKey = StorageKey.of("media/video/ms08-atomicity-" + suffix + "-" + UUID.randomUUID() + ".mp4");

        // 1. Reserve and persist allocation in MySQL using real adapter
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                B2_PROVIDER,
                B2_BUCKET,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                Instant.now()
        );
        storageKeyAllocationPersistenceAdapter.save(allocation);
        createdAllocationIds.add(allocationId);

        // 2. Prepare manifest and canonical hash
        List<MultipartManifestPart> manifest = createStandard2PartManifest();
        int expectedParts = (int) Math.ceil((double) DECLARED_SIZE / MediaUploadSession.FIXED_PART_SIZE_BYTES);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofDays(1));

        MediaUploadSession prototype = MediaUploadSession.rehydrate(
                sessionId,
                PRINCIPAL,
                CONSUMER,
                TARGET_TYPE,
                targetId,
                "idem-key-" + sessionId,
                MediaType.VIDEO,
                "video/mp4",
                DECLARED_SIZE,
                B2_PROVIDER,
                B2_BUCKET,
                storageKey,
                allocationId,
                PROVIDER_UPLOAD_ID,
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                expectedParts,
                VALID_CONTENT_HASH,
                "video.mp4",
                CLIENT_TAG,
                MediaUploadSessionStatus.COMPLETING,
                expiresAt,
                null,
                null,
                null,
                UploadSessionCleanupStatus.NONE,
                0,
                null,
                null,
                now.minus(Duration.ofHours(1)),
                now.minus(Duration.ofMinutes(10)),
                null,
                null,
                null
        );

        String manifestHash = MultipartManifestCanonicalizer.validateAndCanonicalize(prototype, manifest);

        // 3. Persist session with accepted manifestHash in MySQL
        MediaUploadSession session = MediaUploadSession.rehydrate(
                sessionId,
                PRINCIPAL,
                CONSUMER,
                TARGET_TYPE,
                targetId,
                "idem-key-" + sessionId,
                MediaType.VIDEO,
                "video/mp4",
                DECLARED_SIZE,
                B2_PROVIDER,
                B2_BUCKET,
                storageKey,
                allocationId,
                PROVIDER_UPLOAD_ID,
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                expectedParts,
                VALID_CONTENT_HASH,
                "video.mp4",
                CLIENT_TAG,
                MediaUploadSessionStatus.COMPLETING,
                expiresAt,
                null,
                null,
                null,
                UploadSessionCleanupStatus.NONE,
                0,
                null,
                null,
                now.minus(Duration.ofHours(1)),
                now.minus(Duration.ofMinutes(10)),
                null,
                null,
                manifestHash
        );
        mediaUploadSessionPersistenceAdapter.save(session);
        createdSessionIds.add(sessionId);

        return new TestFixture(sessionId, allocationId, storageKey, manifest, manifestHash);
    }

    private void mockSuccessfulProviderCompletion(TestFixture fixture, String versionId) {
        when(multipartStoragePort.listUploadedParts(fixture.storageKey(), PROVIDER_UPLOAD_ID))
                .thenReturn(createStandard2UploadedParts());

        MultipartCompletedObject completedObject = new MultipartCompletedObject(
                StorageLocation.of(B2_PROVIDER, fixture.storageKey()),
                "\"complete-etag-50mib\"",
                versionId
        );
        when(multipartStoragePort.completeMultipartUpload(eq(fixture.storageKey()), eq(PROVIDER_UPLOAD_ID), any()))
                .thenReturn(completedObject);

        MultipartObjectMetadata headMetadata = new MultipartObjectMetadata(
                fixture.storageKey(),
                DECLARED_SIZE,
                "video/mp4",
                "\"complete-etag-50mib\"",
                Map.of(
                        MediaUploadSessionService.METADATA_SESSION_ID, fixture.sessionId().toString(),
                        MediaUploadSessionService.METADATA_ALLOCATION_ID, fixture.allocationId().toString()
                ),
                versionId,
                B2_PROVIDER,
                B2_BUCKET
        );
        when(multipartStoragePort.inspectObjectMetadata(fixture.storageKey()))
                .thenReturn(headMetadata);
    }

    private void updatePersistedLease(UUID sessionId, String leaseOwner, Instant leaseExpiresAt) {
        MediaUploadSession current = mediaUploadSessionPersistenceAdapter.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));
        MediaUploadSession updated = MediaUploadSession.rehydrate(
                current.getId(),
                current.getInitiatorPrincipalId(),
                current.getConsumerContext(),
                current.getTargetEntityType(),
                current.getTargetEntityId(),
                current.getIdempotencyKey(),
                current.getMediaType(),
                current.getDeclaredMimeType(),
                current.getDeclaredSizeBytes(),
                current.getStorageProviderId(),
                current.getStorageBucketName(),
                current.getStorageKey(),
                current.getStorageAllocationId(),
                current.getProviderUploadId(),
                current.getPartSizeBytes(),
                current.getExpectedPartCount(),
                current.getDeclaredContentHash(),
                current.getOriginalFilename(),
                current.getClientTag(),
                current.getStatus(),
                current.getExpiresAt(),
                leaseOwner,
                leaseExpiresAt,
                current.getRegisteredAssetId(),
                current.getCleanupStatus(),
                current.getCleanupRetryCount(),
                current.getCleanupLastAttemptAt(),
                current.getCleanupErrorDetail(),
                current.getCreatedAt(),
                current.getUpdatedAt(),
                current.getCompletedAt(),
                current.getPersistenceVersion(),
                current.getManifestHash()
        );
        mediaUploadSessionPersistenceAdapter.save(updated);
        entityManager.clear();
    }

    // =============================================================================================
    // 1. Successful Registration & Durable Persistence Contract
    // =============================================================================================

    @Test
    @DisplayName("Successfully completes multipart upload and atomically persists MediaAsset, Version, committed allocation, and session")
    void shouldAtomicallyRegisterAssetAndCommitAllocationOnAcknowledgedCompletion() {
        TestFixture fixture = createAndPersistCompletingFixture("happy");
        mockSuccessfulProviderCompletion(fixture, "ver-b2-happy-001");

        MediaUploadSession completedSession = sessionService.completeMultipartUpload(
                fixture.sessionId(),
                PRINCIPAL,
                fixture.manifest()
        );

        assertThat(completedSession).isNotNull();
        assertThat(completedSession.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(completedSession.getRegisteredAssetId()).isNotNull();
        assertThat(completedSession.getCompletedAt()).isNotNull();
        assertThat(completedSession.getLeaseOwner()).isNull();
        assertThat(completedSession.getLeaseExpiresAt()).isNull();

        UUID registeredAssetId = completedSession.getRegisteredAssetId();
        createdAssetIds.add(registeredAssetId);

        // 1. Verify MediaAsset row in MySQL
        Map<String, Object> assetRow = jdbcTemplate.queryForMap(
                "SELECT id, media_type, visibility, status, current_version_number, client_tag, created_at, updated_at FROM media_assets WHERE id = ?",
                registeredAssetId.toString()
        );
        assertThat(assetRow.get("id")).isEqualTo(registeredAssetId.toString());
        assertThat(assetRow.get("media_type")).isEqualTo("VIDEO");
        assertThat(assetRow.get("visibility")).isEqualTo("PRIVATE");
        assertThat(assetRow.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) assetRow.get("current_version_number")).intValue()).isEqualTo(1);
        assertThat(assetRow.get("client_tag")).isEqualTo(CLIENT_TAG);
        assertThat(assetRow.get("created_at")).isNotNull();
        assertThat(assetRow.get("updated_at")).isNotNull();

        // 2. Verify MediaAssetVersion row in MySQL including content_hash_status provenance
        Map<String, Object> versionRow = jdbcTemplate.queryForMap(
                "SELECT id, asset_id, version_number, storage_provider_id, storage_key, mime_type, size_bytes, content_hash, content_hash_status, original_filename, created_at FROM media_asset_versions WHERE asset_id = ?",
                registeredAssetId.toString()
        );
        assertThat(versionRow.get("asset_id")).isEqualTo(registeredAssetId.toString());
        assertThat(((Number) versionRow.get("version_number")).intValue()).isEqualTo(1);
        assertThat(versionRow.get("storage_provider_id")).isEqualTo("b2");
        assertThat(versionRow.get("storage_key")).isEqualTo(fixture.storageKey().value());
        assertThat(versionRow.get("mime_type")).isEqualTo("video/mp4");
        assertThat(((Number) versionRow.get("size_bytes")).longValue()).isEqualTo(DECLARED_SIZE);
        assertThat(versionRow.get("content_hash")).isEqualTo(VALID_CONTENT_HASH);
        assertThat(versionRow.get("content_hash_status")).isEqualTo("CLIENT_DECLARED_UNVERIFIED");
        assertThat(versionRow.get("original_filename")).isEqualTo("video.mp4");
        assertThat(versionRow.get("created_at")).isNotNull();

        // 3. Verify StorageKeyAllocation transition to COMMITTED with ownership handoff to ASSET_VERSION
        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT id, allocation_status, allocated_by_type, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                fixture.allocationId().toString()
        );
        assertThat(allocRow.get("id")).isEqualTo(fixture.allocationId().toString());
        assertThat(allocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.COMMITTED.name());
        assertThat(allocRow.get("allocated_by_type")).isEqualTo(StorageKeyAllocationType.ASSET_VERSION.name());
        assertThat(allocRow.get("allocated_by_id")).isEqualTo(versionRow.get("id"));

        // 4. Verify MediaUploadSession row in MySQL
        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT id, status, registered_asset_id, lease_owner, lease_expires_at, completed_at FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(sessionRow.get("id")).isEqualTo(fixture.sessionId().toString());
        assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETED.name());
        assertThat(sessionRow.get("registered_asset_id")).isEqualTo(registeredAssetId.toString());
        assertThat(sessionRow.get("lease_owner")).isNull();
        assertThat(sessionRow.get("lease_expires_at")).isNull();
        assertThat(sessionRow.get("completed_at")).isNotNull();
    }

    // =============================================================================================
    // 2. Atomic Rollback Verification via Fault Injection
    // =============================================================================================

    @Test
    @DisplayName("Failure during version registration throws before version persistence; verifies asset save occurred but rolled back")
    void shouldRollbackAssetWhenVersionRegistrationThrows() {
        TestFixture fixture = createAndPersistCompletingFixture("rb-version");
        mockSuccessfulProviderCompletion(fixture, "ver-b2-rb-ver-001");

        Set<String> preExistingAssetIds = new HashSet<>(
                jdbcTemplate.queryForList("SELECT id FROM media_assets", String.class)
        );

        // Fault Injection: let real MediaAsset save succeed, but throw during MediaAssetVersion save
        Mockito.doThrow(new RuntimeException("Injected failure during MediaAssetVersion registration"))
                .when(mediaAssetVersionPersistenceAdapter)
                .save(any(MediaAssetVersion.class));

        assertThatThrownBy(() -> sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Injected failure during MediaAssetVersion registration");

        // Fresh Database Assertions outside the rolled-back transaction
        List<String> currentAssetIds = jdbcTemplate.queryForList("SELECT id FROM media_assets", String.class);
        assertThat(currentAssetIds).containsExactlyInAnyOrderElementsOf(preExistingAssetIds);

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_asset_versions WHERE storage_key = ?",
                Integer.class,
                fixture.storageKey().value()
        );
        assertThat(versionCount).isEqualTo(0);

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status, allocated_by_type, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                fixture.allocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.RESERVED.name());
        assertThat(allocRow.get("allocated_by_type")).isEqualTo(StorageKeyAllocationType.UPLOAD_SESSION.name());
        assertThat(allocRow.get("allocated_by_id")).isEqualTo(fixture.sessionId().toString());

        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, registered_asset_id FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETING.name());
        assertThat(sessionRow.get("registered_asset_id")).isNull();
    }

    @Test
    @DisplayName("Failure during allocation ownership handoff throws after asset and version persisted; verifies both roll back")
    void shouldRollbackAssetAndVersionWhenAllocationCommitThrows() {
        TestFixture fixture = createAndPersistCompletingFixture("rb-alloc");
        mockSuccessfulProviderCompletion(fixture, "ver-b2-rb-alloc-001");

        Set<String> preExistingAssetIds = new HashSet<>(
                jdbcTemplate.queryForList("SELECT id FROM media_assets", String.class)
        );

        // Fault Injection: let real asset and real version save succeed, but throw during allocation commit
        Mockito.doThrow(new RuntimeException("Injected failure during allocation ownership commit"))
                .when(storageKeyAllocationService)
                .commitAllocation(any(), any(), any());

        assertThatThrownBy(() -> sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Injected failure during allocation ownership commit");

        List<String> currentAssetIds = jdbcTemplate.queryForList("SELECT id FROM media_assets", String.class);
        assertThat(currentAssetIds).containsExactlyInAnyOrderElementsOf(preExistingAssetIds);

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_asset_versions WHERE storage_key = ?",
                Integer.class,
                fixture.storageKey().value()
        );
        assertThat(versionCount).isEqualTo(0);

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status, allocated_by_type, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                fixture.allocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.RESERVED.name());
        assertThat(allocRow.get("allocated_by_type")).isEqualTo(StorageKeyAllocationType.UPLOAD_SESSION.name());
        assertThat(allocRow.get("allocated_by_id")).isEqualTo(fixture.sessionId().toString());

        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, registered_asset_id FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETING.name());
        assertThat(sessionRow.get("registered_asset_id")).isNull();
    }

    @Test
    @DisplayName("Late failure test: real asset, version, allocation, and session persist; verified in-transaction, then physical rollback confirmed")
    void shouldAtomicallyRollbackAllMutationsWhenLateFailureOccursBeforeTxBCommit() {
        TestFixture fixture = createAndPersistCompletingFixture("rb-late");
        mockSuccessfulProviderCompletion(fixture, "ver-b2-rb-late-001");

        Set<String> preExistingAssetIds = new HashSet<>(
                jdbcTemplate.queryForList("SELECT id FROM media_assets", String.class)
        );

        AtomicReference<UUID> generatedAssetIdRef = new AtomicReference<>();
        AtomicReference<String> txAClaimTokenRef = new AtomicReference<>();

        // Capture TX-A claim token from MySQL after TX-A commits and while provider inspectObjectMetadata executes
        when(multipartStoragePort.inspectObjectMetadata(fixture.storageKey())).thenAnswer(inv -> {
            String leaseOwner = jdbcTemplate.queryForObject(
                    "SELECT lease_owner FROM media_upload_sessions WHERE id = ?",
                    String.class,
                    fixture.sessionId().toString()
            );
            txAClaimTokenRef.set(leaseOwner);
            return new MultipartObjectMetadata(
                    fixture.storageKey(),
                    DECLARED_SIZE,
                    "video/mp4",
                    "\"complete-etag-50mib\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, fixture.sessionId().toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, fixture.allocationId().toString()
                    ),
                    "ver-b2-rb-late-001",
                    B2_PROVIDER,
                    B2_BUCKET
            );
        });

        // Real persistence operations execute for asset, version, allocation, and session.
        // We intercept the final session persistence call in TX-B.
        Mockito.doAnswer(inv -> {
            MediaUploadSession sessionArg = inv.getArgument(0);
            if (sessionArg.getStatus() == MediaUploadSessionStatus.COMPLETED) {
                // 1. Verify an actual TX-B database transaction is active at the fault injection point
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

                // 2. Call real save method so entity updates reach JPA persistence context
                MediaUploadSession savedSession = (MediaUploadSession) inv.callRealMethod();

                UUID registeredAssetId = sessionArg.getRegisteredAssetId();
                generatedAssetIdRef.set(registeredAssetId);
                createdAssetIds.add(registeredAssetId);

                // 3. Ensure real JPA mutations are physically flushed to MySQL within TX-B before querying state
                entityManager.flush();

                // 4. Verify in-flight database state on the active uncommitted transaction connection
                Integer inFlightAssetCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM media_assets WHERE id = ?",
                        Integer.class,
                        registeredAssetId.toString()
                );
                assertThat(inFlightAssetCount).isEqualTo(1);

                Integer inFlightVersionCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM media_asset_versions WHERE asset_id = ?",
                        Integer.class,
                        registeredAssetId.toString()
                );
                assertThat(inFlightVersionCount).isEqualTo(1);

                Map<String, Object> inFlightAllocRow = jdbcTemplate.queryForMap(
                        "SELECT allocation_status, allocated_by_type, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                        fixture.allocationId().toString()
                );
                assertThat(inFlightAllocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.COMMITTED.name());
                assertThat(inFlightAllocRow.get("allocated_by_type")).isEqualTo(StorageKeyAllocationType.ASSET_VERSION.name());

                Map<String, Object> inFlightSessionRow = jdbcTemplate.queryForMap(
                        "SELECT status, registered_asset_id FROM media_upload_sessions WHERE id = ?",
                        fixture.sessionId().toString()
                );
                assertThat(inFlightSessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETED.name());
                assertThat(inFlightSessionRow.get("registered_asset_id")).isEqualTo(registeredAssetId.toString());

                // 5. Inject exception late in TX-B before transaction commit
                throw new RuntimeException("Simulated late failure in TX-B before transaction commit");
            }
            return inv.callRealMethod();
        }).when(mediaUploadSessionPersistenceAdapter).save(any(MediaUploadSession.class));

        assertThatThrownBy(() -> sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated late failure in TX-B before transaction commit");

        // Fresh Database Assertions outside the rolled-back transaction
        List<String> currentAssetIds = jdbcTemplate.queryForList("SELECT id FROM media_assets", String.class);
        assertThat(currentAssetIds).containsExactlyInAnyOrderElementsOf(preExistingAssetIds);

        if (generatedAssetIdRef.get() != null) {
            Integer postRollbackAssetCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM media_assets WHERE id = ?",
                    Integer.class,
                    generatedAssetIdRef.get().toString()
            );
            assertThat(postRollbackAssetCount).isEqualTo(0);
        }

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_asset_versions WHERE storage_key = ?",
                Integer.class,
                fixture.storageKey().value()
        );
        assertThat(versionCount).isEqualTo(0);

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status, allocated_by_type, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                fixture.allocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.RESERVED.name());
        assertThat(allocRow.get("allocated_by_type")).isEqualTo(StorageKeyAllocationType.UPLOAD_SESSION.name());
        assertThat(allocRow.get("allocated_by_id")).isEqualTo(fixture.sessionId().toString());

        // Session remains COMPLETING, registered_asset_id rolled back, previously committed TX-A claim preserved
        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, registered_asset_id, lease_owner FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETING.name());
        assertThat(sessionRow.get("registered_asset_id")).isNull();
        assertThat(sessionRow.get("lease_owner")).isNotNull();
        if (txAClaimTokenRef.get() != null) {
            assertThat(sessionRow.get("lease_owner")).isEqualTo(txAClaimTokenRef.get());
        }
    }

    // =============================================================================================
    // 3. Idempotency & Repeated Completion
    // =============================================================================================

    @Test
    @DisplayName("Repeated completeMultipartUpload on already-COMPLETED session returns idempotently without duplicate rows")
    void shouldReturnIdempotentlyOnRepeatedCompletionWithoutDuplicateAssetOrVersion() {
        TestFixture fixture = createAndPersistCompletingFixture("idem");
        mockSuccessfulProviderCompletion(fixture, "ver-b2-idem-001");

        // First completion
        MediaUploadSession first = sessionService.completeMultipartUpload(
                fixture.sessionId(),
                PRINCIPAL,
                fixture.manifest()
        );
        assertThat(first.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        UUID assetId = first.getRegisteredAssetId();
        assertThat(assetId).isNotNull();
        createdAssetIds.add(assetId);

        // Second completion (idempotent re-execution)
        MediaUploadSession second = sessionService.completeMultipartUpload(
                fixture.sessionId(),
                PRINCIPAL,
                fixture.manifest()
        );
        assertThat(second.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(second.getRegisteredAssetId()).isEqualTo(assetId);

        // Verify zero duplicates in MySQL
        Integer assetCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_assets WHERE id = ?",
                Integer.class,
                assetId.toString()
        );
        assertThat(assetCount).isEqualTo(1);

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_asset_versions WHERE asset_id = ?",
                Integer.class,
                assetId.toString()
        );
        assertThat(versionCount).isEqualTo(1);

        Integer allocCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_storage_key_allocations WHERE id = ? AND allocation_status = 'COMMITTED'",
                Integer.class,
                fixture.allocationId().toString()
        );
        assertThat(allocCount).isEqualTo(1);
    }

    // =============================================================================================
    // 4. External Storage Failures & Fail-Closed Integrity
    // =============================================================================================

    @Test
    @DisplayName("Preserves COMPLETING state and RESERVED allocation when remote CompleteMultipartUpload throws StorageException")
    void shouldPreserveCompletingStateWhenCompleteMultipartUploadThrowsStorageException() {
        TestFixture fixture = createAndPersistCompletingFixture("err-complete");

        when(multipartStoragePort.listUploadedParts(fixture.storageKey(), PROVIDER_UPLOAD_ID))
                .thenReturn(createStandard2UploadedParts());

        when(multipartStoragePort.completeMultipartUpload(eq(fixture.storageKey()), eq(PROVIDER_UPLOAD_ID), any()))
                .thenThrow(new StorageException("B2 504 Gateway Timeout during completion"));

        when(multipartStoragePort.inspectObjectMetadata(fixture.storageKey()))
                .thenReturn(new MultipartObjectMetadata(fixture.storageKey(), DECLARED_SIZE, "video/mp4", "\"probe-etag\""));

        assertThatThrownBy(() -> sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest()))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("504 Gateway Timeout");

        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, registered_asset_id FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETING.name());
        assertThat(sessionRow.get("registered_asset_id")).isNull();

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                fixture.allocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.RESERVED.name());

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_asset_versions WHERE storage_key = ?",
                Integer.class,
                fixture.storageKey().value()
        );
        assertThat(versionCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Fails closed when provider listUploadedParts throws unrelated StorageObjectNotFoundException, never initiating recovery HEAD inspection")
    void shouldFailClosedWhenListUploadedPartsReturnsNoSuchUpload() {
        TestFixture fixture = createAndPersistCompletingFixture("err-list-nosuch");

        when(multipartStoragePort.listUploadedParts(fixture.storageKey(), PROVIDER_UPLOAD_ID))
                .thenThrow(new StorageObjectNotFoundException(
                        fixture.storageKey(),
                        new RuntimeException("NoSuchUpload: upload not found")
                ));

        when(multipartStoragePort.inspectObjectMetadata(fixture.storageKey()))
                .thenReturn(new MultipartObjectMetadata(fixture.storageKey(), DECLARED_SIZE, "video/mp4", "\"probe-etag\""));

        assertThatThrownBy(() -> sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest()))
                .isInstanceOf(StorageObjectNotFoundException.class);

        // An unrelated StorageObjectNotFoundException must fail closed immediately and never initiate recovery HEAD inspection
        verify(multipartStoragePort, never()).inspectObjectMetadata(any());

        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, registered_asset_id FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETING.name());
        assertThat(sessionRow.get("registered_asset_id")).isNull();

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                fixture.allocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.RESERVED.name());

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_asset_versions WHERE storage_key = ?",
                Integer.class,
                fixture.storageKey().value()
        );
        assertThat(versionCount).isEqualTo(0);
    }

    // =============================================================================================
    // 5. Concurrency Fencing, Reclaim & Transaction Boundaries
    // =============================================================================================

    @Test
    @DisplayName("Concurrent completion worker invocation with controlled contention: winning worker completes, competing worker rejects")
    void shouldHandleConcurrentCompletionWorkersDeterministically() throws Exception {
        TestFixture fixture = createAndPersistCompletingFixture("race-workers");
        mockSuccessfulProviderCompletion(fixture, "ver-b2-race-001");

        CountDownLatch worker1TxACommittedLatch = new CountDownLatch(1);
        CountDownLatch worker2AttemptedLatch = new CountDownLatch(1);

        // Control contention: Worker 1 commits TX-A and proceeds to B2 listUploadedParts outside the DB transaction.
        // During listUploadedParts, Worker 1 signals Worker 2 to attempt completeMultipartUpload while Worker 1's
        // completion lease is actively committed in MySQL.
        when(multipartStoragePort.listUploadedParts(fixture.storageKey(), PROVIDER_UPLOAD_ID)).thenAnswer(inv -> {
            worker1TxACommittedLatch.countDown();
            assertThat(worker2AttemptedLatch.await(10, TimeUnit.SECONDS)).isTrue();
            return createStandard2UploadedParts();
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Thread 1: Worker 1 initiates completion and commits TX-A
            Future<MediaUploadSession> worker1Future = executor.submit(() ->
                    sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest())
            );

            // Thread 2: Competing Worker 2 races while Worker 1's completion lease is actively held in MySQL
            Future<MediaUploadSession> worker2Future = executor.submit(() -> {
                try {
                    assertThat(worker1TxACommittedLatch.await(10, TimeUnit.SECONDS)).isTrue();
                    return sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest());
                } finally {
                    worker2AttemptedLatch.countDown();
                }
            });

            // Worker 2 must be strictly rejected with IllegalStateException due to active lease
            assertThatThrownBy(() -> worker2Future.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Concurrent completion in progress");

            // Worker 1 finishes successfully
            MediaUploadSession worker1Session = worker1Future.get(15, TimeUnit.SECONDS);
            assertThat(worker1Session).isNotNull();
            assertThat(worker1Session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
            createdAssetIds.add(worker1Session.getRegisteredAssetId());

            // Verify exactly one MediaAssetVersion exists for the fixture's unique storage key
            Integer keyVersionCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM media_asset_versions WHERE storage_key = ?",
                    Integer.class,
                    fixture.storageKey().value()
            );
            assertThat(keyVersionCount).isEqualTo(1);

            // Verify exactly one MediaAsset exists for the winning asset ID with PRIVATE visibility and CLIENT_TAG
            Map<String, Object> winningAssetRow = jdbcTemplate.queryForMap(
                    "SELECT id, media_type, visibility, status, current_version_number, client_tag FROM media_assets WHERE id = ?",
                    worker1Session.getRegisteredAssetId().toString()
            );
            assertThat(winningAssetRow.get("id")).isEqualTo(worker1Session.getRegisteredAssetId().toString());
            assertThat(winningAssetRow.get("media_type")).isEqualTo("VIDEO");
            assertThat(winningAssetRow.get("visibility")).isEqualTo("PRIVATE");
            assertThat(winningAssetRow.get("status")).isEqualTo("ACTIVE");
            assertThat(((Number) winningAssetRow.get("current_version_number")).intValue()).isEqualTo(1);
            assertThat(winningAssetRow.get("client_tag")).isEqualTo(CLIENT_TAG);

            // Verify consistent committed allocation state pointing to the registered version
            Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                    "SELECT allocation_status, allocated_by_type, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                    fixture.allocationId().toString()
            );
            assertThat(allocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.COMMITTED.name());
            assertThat(allocRow.get("allocated_by_type")).isEqualTo(StorageKeyAllocationType.ASSET_VERSION.name());

            // Verify session is COMPLETED with cleared lease
            Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                    "SELECT status, registered_asset_id, lease_owner, lease_expires_at FROM media_upload_sessions WHERE id = ?",
                    fixture.sessionId().toString()
            );
            assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETED.name());
            assertThat(sessionRow.get("registered_asset_id")).isEqualTo(worker1Session.getRegisteredAssetId().toString());
            assertThat(sessionRow.get("lease_owner")).isNull();
            assertThat(sessionRow.get("lease_expires_at")).isNull();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Reclaims expired completion lease and completes registration successfully")
    void shouldReclaimExpiredCompletionLeaseAndSucceed() {
        TestFixture fixture = createAndPersistCompletingFixture("reclaim-lease");
        mockSuccessfulProviderCompletion(fixture, "ver-b2-reclaim-001");

        // Simulate an expired completion lease previously committed in MySQL by a stale worker
        // Persist expired lease using real JPA persistence adapter and deterministic Java Instant
        Instant expiredLeaseAt = clockPort.now().minus(Duration.ofMinutes(5));
        updatePersistedLease(fixture.sessionId(), "stale-worker-old", expiredLeaseAt);

        // Source-grounded verification: verify persisted lease is expired according to ClockPort semantics before reclaim
        MediaUploadSession persistedBeforeReclaim = mediaUploadSessionPersistenceAdapter
                .findById(fixture.sessionId())
                .orElseThrow();
        assertThat(persistedBeforeReclaim.getLeaseOwner()).isEqualTo("stale-worker-old");
        assertThat(persistedBeforeReclaim.getLeaseExpiresAt()).isNotNull();
        assertThat(persistedBeforeReclaim.isCompletionLeaseExpired(clockPort.now())).isTrue();
        assertThat(persistedBeforeReclaim.hasActiveCompletionLease(clockPort.now())).isFalse();

        // Raw database verification: verify MySQL row contains the persisted lease data
        Map<String, Object> rawDbRow = jdbcTemplate.queryForMap(
                "SELECT lease_owner, lease_expires_at FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(rawDbRow.get("lease_owner")).isEqualTo("stale-worker-old");
        assertThat(rawDbRow.get("lease_expires_at")).isNotNull();

        MediaUploadSession completedSession = sessionService.completeMultipartUpload(
                fixture.sessionId(),
                PRINCIPAL,
                fixture.manifest()
        );

        assertThat(completedSession.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(completedSession.getRegisteredAssetId()).isNotNull();
        createdAssetIds.add(completedSession.getRegisteredAssetId());

        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, registered_asset_id, lease_owner, lease_expires_at FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETED.name());
        assertThat(sessionRow.get("registered_asset_id")).isEqualTo(completedSession.getRegisteredAssetId().toString());
        assertThat(sessionRow.get("lease_owner")).isNull();
        assertThat(sessionRow.get("lease_expires_at")).isNull();
    }

    @Test
    @DisplayName("Rejects registration in TX-B when completion lease has expired before registration commits")
    void shouldRejectStaleWorkerWhenCompletionLeaseExpiresBeforeTxB() {
        TestFixture fixture = createAndPersistCompletingFixture("fence-stale-txb");

        when(multipartStoragePort.listUploadedParts(fixture.storageKey(), PROVIDER_UPLOAD_ID))
                .thenReturn(createStandard2UploadedParts());

        when(multipartStoragePort.completeMultipartUpload(eq(fixture.storageKey()), eq(PROVIDER_UPLOAD_ID), any()))
                .thenReturn(new MultipartCompletedObject(
                        StorageLocation.of(B2_PROVIDER, fixture.storageKey()),
                        "\"complete-etag-50mib\"",
                        "ver-stale-001"
                ));

        // When inspectObjectMetadata is called (right before TX-B begins), simulate lease expiration in MySQL
        when(multipartStoragePort.inspectObjectMetadata(fixture.storageKey())).thenAnswer(inv -> {
            MediaUploadSession activeSession = mediaUploadSessionPersistenceAdapter
                    .findById(fixture.sessionId())
                    .orElseThrow();
            // Preserve the active worker's lease owner (claimToken), but expire the lease timestamp using real JPA adapter
            Instant expiredLeaseAt = clockPort.now().minus(Duration.ofMinutes(1));
            updatePersistedLease(fixture.sessionId(), activeSession.getLeaseOwner(), expiredLeaseAt);

            // Source-grounded verification: verify persisted lease is expired according to ClockPort semantics before TX-B runs
            MediaUploadSession persistedBeforeTxB = mediaUploadSessionPersistenceAdapter
                    .findById(fixture.sessionId())
                    .orElseThrow();
            assertThat(persistedBeforeTxB.getLeaseOwner()).isEqualTo(activeSession.getLeaseOwner());
            assertThat(persistedBeforeTxB.isCompletionLeaseExpired(clockPort.now())).isTrue();

            return new MultipartObjectMetadata(
                    fixture.storageKey(),
                    DECLARED_SIZE,
                    "video/mp4",
                    "\"complete-etag-50mib\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, fixture.sessionId().toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, fixture.allocationId().toString()
                    ),
                    "ver-stale-001",
                    B2_PROVIDER,
                    B2_BUCKET
            );
        });

        assertThatThrownBy(() -> sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired")
                .hasMessageContaining("Stale worker cannot commit registration");

        // Verify session remains COMPLETING in MySQL and zero assets were committed
        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, registered_asset_id FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETING.name());
        assertThat(sessionRow.get("registered_asset_id")).isNull();

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                fixture.allocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.RESERVED.name());

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_asset_versions WHERE storage_key = ?",
                Integer.class,
                fixture.storageKey().value()
        );
        assertThat(versionCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Active completion claim rejects concurrent abortSession and abortBySystem")
    void shouldRejectConcurrentAbortWhenCompletionClaimIsActive() {
        TestFixture fixture = createAndPersistCompletingFixture("abort-claim-reject");

        // Set active completion lease in MySQL using deterministic Instant and JPA persistence adapter
        Instant activeLeaseExpiresAt = clockPort.now().plus(Duration.ofMinutes(5));
        updatePersistedLease(fixture.sessionId(), "worker-in-flight-777", activeLeaseExpiresAt);

        // User abortSession must fail
        assertThatThrownBy(() -> sessionService.abortSession(fixture.sessionId(), PRINCIPAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active completion claim");

        // System reaper abortBySystem must also fail
        assertThatThrownBy(() -> sessionService.abortBySystem(fixture.sessionId(), "system-reaper"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active completion claim");

        // Session remains COMPLETING and allocation remains RESERVED
        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, registered_asset_id FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETING.name());
        assertThat(sessionRow.get("registered_asset_id")).isNull();

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                fixture.allocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.RESERVED.name());
    }

    @Test
    @DisplayName("Rejects completion when invoked within an active ambient database transaction (Propagation.NEVER)")
    void shouldRejectCompletionWhenAmbientTransactionIsActive() {
        TestFixture fixture = createAndPersistCompletingFixture("prop-never");

        TransactionTemplate outerTx = new TransactionTemplate(transactionManager);

        // 1. Spring proxy contract: Propagation.NEVER causes Spring's transaction interceptor
        // to throw IllegalTransactionStateException before entering the method body.
        assertThatThrownBy(() -> outerTx.execute(status ->
                sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest())
        ))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("Existing transaction found for transaction marked with propagation 'never'");

        verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());

        // 2. Direct unproxied invocation guard: If invoked directly bypassing the Spring proxy,
        // TransactionSynchronizationManager.isActualTransactionActive() runtime guard throws IllegalStateException.
        MediaUploadSessionService unproxiedService = AopTestUtils.getUltimateTargetObject(sessionService);
        assertThatThrownBy(() -> outerTx.execute(status ->
                unproxiedService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest())
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completeMultipartUpload cannot be invoked from an active ambient transaction.");
    }

    @Test
    @DisplayName("Guarantees that provider storage operations execute outside any active database transaction")
    void shouldExecuteProviderOperationsOutsideActiveDatabaseTransaction() {
        TestFixture fixture = createAndPersistCompletingFixture("tx-boundaries");

        AtomicBoolean listPartsTxActive = new AtomicBoolean(true);
        AtomicBoolean completeTxActive = new AtomicBoolean(true);
        AtomicBoolean headTxActive = new AtomicBoolean(true);

        when(multipartStoragePort.listUploadedParts(fixture.storageKey(), PROVIDER_UPLOAD_ID)).thenAnswer(inv -> {
            listPartsTxActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return createStandard2UploadedParts();
        });

        when(multipartStoragePort.completeMultipartUpload(eq(fixture.storageKey()), eq(PROVIDER_UPLOAD_ID), any())).thenAnswer(inv -> {
            completeTxActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new MultipartCompletedObject(
                    StorageLocation.of(B2_PROVIDER, fixture.storageKey()),
                    "\"complete-etag-50mib\"",
                    "ver-boundary-001"
            );
        });

        when(multipartStoragePort.inspectObjectMetadata(fixture.storageKey())).thenAnswer(inv -> {
            headTxActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new MultipartObjectMetadata(
                    fixture.storageKey(),
                    DECLARED_SIZE,
                    "video/mp4",
                    "\"complete-etag-50mib\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, fixture.sessionId().toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, fixture.allocationId().toString()
                    ),
                    "ver-boundary-001",
                    B2_PROVIDER,
                    B2_BUCKET
            );
        });

        MediaUploadSession completed = sessionService.completeMultipartUpload(
                fixture.sessionId(),
                PRINCIPAL,
                fixture.manifest()
        );

        assertThat(completed.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        createdAssetIds.add(completed.getRegisteredAssetId());

        assertThat(listPartsTxActive.get()).isFalse();
        assertThat(completeTxActive.get()).isFalse();
        assertThat(headTxActive.get()).isFalse();
    }

    // =============================================================================================
    // 6. Guarded Multipart Completion Recovery & Concurrent Fencing (MS-08E2-G1A)
    // =============================================================================================

    @Test
    @DisplayName("Guarded recovery after TxB rollback: enforces active lease rejection, succeeds after legitimate expiry via NoSuchUpload + HEAD, commits exactly 1 asset/version, and returns idempotently")
    void shouldRecoverMultipartCompletionAfterTxBRegistrationRollbackAndHonorLeaseFencing() {
        TestFixture fixture = createAndPersistCompletingFixture("recovery-rb");
        Instant baseTime = Instant.now();
        TestConfig.setClock(baseTime);

        // 1. Initial Attempt: Mock B2 completion succeeds
        when(multipartStoragePort.listUploadedParts(fixture.storageKey(), PROVIDER_UPLOAD_ID))
                .thenReturn(createStandard2UploadedParts());

        when(multipartStoragePort.completeMultipartUpload(eq(fixture.storageKey()), eq(PROVIDER_UPLOAD_ID), any()))
                .thenReturn(new MultipartCompletedObject(
                        StorageLocation.of(B2_PROVIDER, fixture.storageKey()),
                        "\"complete-etag-50mib\"",
                        "ver-b2-rec-001"
                ));

        when(multipartStoragePort.inspectObjectMetadata(fixture.storageKey()))
                .thenReturn(new MultipartObjectMetadata(
                        fixture.storageKey(),
                        DECLARED_SIZE,
                        "video/mp4",
                        "\"complete-etag-50mib\"",
                        Map.of(
                                MediaUploadSessionService.METADATA_SESSION_ID, fixture.sessionId().toString(),
                                MediaUploadSessionService.METADATA_ALLOCATION_ID, fixture.allocationId().toString()
                        ),
                        "ver-b2-rec-001",
                        B2_PROVIDER,
                        B2_BUCKET
                ));

        Set<String> preExistingAssetIds = new HashSet<>(
                jdbcTemplate.queryForList("SELECT id FROM media_assets", String.class)
        );

        // 2. Fault Injection: Inject failure during TX-B version save
        Mockito.doThrow(new RuntimeException("Injected TX-B rollback failure on initial completion attempt"))
                .when(mediaAssetVersionPersistenceAdapter)
                .save(any(MediaAssetVersion.class));

        assertThatThrownBy(() -> sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Injected TX-B rollback failure");

        // 3. Assert real MySQL rollback: zero assets, zero versions, session COMPLETING, allocation RESERVED
        List<String> postRollbackAssetIds = jdbcTemplate.queryForList("SELECT id FROM media_assets", String.class);
        assertThat(postRollbackAssetIds).containsExactlyInAnyOrderElementsOf(preExistingAssetIds);

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_asset_versions WHERE storage_key = ?",
                Integer.class,
                fixture.storageKey().value()
        );
        assertThat(versionCount).isEqualTo(0);

        Map<String, Object> rolledBackAlloc = jdbcTemplate.queryForMap(
                "SELECT allocation_status, allocated_by_type, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                fixture.allocationId().toString()
        );
        assertThat(rolledBackAlloc.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.RESERVED.name());
        assertThat(rolledBackAlloc.get("allocated_by_type")).isEqualTo(StorageKeyAllocationType.UPLOAD_SESSION.name());
        assertThat(rolledBackAlloc.get("allocated_by_id")).isEqualTo(fixture.sessionId().toString());

        Map<String, Object> rolledBackSession = jdbcTemplate.queryForMap(
                "SELECT status, registered_asset_id, lease_owner, lease_expires_at FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(rolledBackSession.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETING.name());
        assertThat(rolledBackSession.get("registered_asset_id")).isNull();
        String activeClaimToken = (String) rolledBackSession.get("lease_owner");
        assertThat(activeClaimToken).isNotNull().startsWith("COMPLETION_CLAIM:");
        assertThat(rolledBackSession.get("lease_expires_at")).isNotNull();

        // 4. Active Lease Fencing: Immediate retry while lease is active MUST be rejected with IllegalStateException
        assertThatThrownBy(() -> sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Concurrent completion in progress")
                .hasMessageContaining("lease held by");

        // 5. Restore persistence adapter spy to call real method for recovery attempt
        Mockito.doAnswer(inv -> inv.callRealMethod())
                .when(mediaAssetVersionPersistenceAdapter)
                .save(any(MediaAssetVersion.class));

        // 6. Legitimate Expiration: Advance clock past 5-minute lease duration (6 minutes later)
        TestConfig.setClock(baseTime.plus(Duration.ofMinutes(6)));

        // 7. Mock remote provider for Scenario A recovery:
        // listUploadedParts throws StorageUploadNotFoundException (upload already assembled on provider)
        when(multipartStoragePort.listUploadedParts(fixture.storageKey(), PROVIDER_UPLOAD_ID))
                .thenThrow(new StorageUploadNotFoundException(fixture.storageKey(), PROVIDER_UPLOAD_ID));

        // 8. Execute recovery completion attempt
        MediaUploadSession recovered = sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest());

        assertThat(recovered).isNotNull();
        assertThat(recovered.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(recovered.getRegisteredAssetId()).isNotNull();
        assertThat(recovered.getLeaseOwner()).isNull();
        assertThat(recovered.getLeaseExpiresAt()).isNull();

        UUID registeredAssetId = recovered.getRegisteredAssetId();
        createdAssetIds.add(registeredAssetId);

        // 9. Verify exact MySQL database state after guarded recovery:
        // A: Exactly 1 MediaAsset
        Map<String, Object> assetRow = jdbcTemplate.queryForMap(
                "SELECT id, media_type, visibility, status, current_version_number, client_tag FROM media_assets WHERE id = ?",
                registeredAssetId.toString()
        );
        assertThat(assetRow.get("id")).isEqualTo(registeredAssetId.toString());
        assertThat(assetRow.get("media_type")).isEqualTo("VIDEO");
        assertThat(assetRow.get("visibility")).isEqualTo("PRIVATE");
        assertThat(assetRow.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) assetRow.get("current_version_number")).intValue()).isEqualTo(1);
        assertThat(assetRow.get("client_tag")).isEqualTo(CLIENT_TAG);

        // B: Exactly 1 MediaAssetVersion with server-origin / client-declared provenance
        Map<String, Object> versionRow = jdbcTemplate.queryForMap(
                "SELECT id, asset_id, version_number, storage_provider_id, storage_key, content_hash_status, size_bytes FROM media_asset_versions WHERE asset_id = ?",
                registeredAssetId.toString()
        );
        assertThat(versionRow.get("asset_id")).isEqualTo(registeredAssetId.toString());
        assertThat(((Number) versionRow.get("version_number")).intValue()).isEqualTo(1);
        assertThat(versionRow.get("storage_provider_id")).isEqualTo("b2");
        assertThat(versionRow.get("storage_key")).isEqualTo(fixture.storageKey().value());
        assertThat(versionRow.get("content_hash_status")).isEqualTo("CLIENT_DECLARED_UNVERIFIED");
        assertThat(((Number) versionRow.get("size_bytes")).longValue()).isEqualTo(DECLARED_SIZE);

        // C: Exactly 1 COMMITTED allocation with ownership handoff to ASSET_VERSION
        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT id, allocation_status, allocated_by_type, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                fixture.allocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.COMMITTED.name());
        assertThat(allocRow.get("allocated_by_type")).isEqualTo(StorageKeyAllocationType.ASSET_VERSION.name());
        assertThat(allocRow.get("allocated_by_id")).isEqualTo(versionRow.get("id"));

        // D: MediaUploadSession completed in MySQL
        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT id, status, registered_asset_id, lease_owner, lease_expires_at, completed_at FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETED.name());
        assertThat(sessionRow.get("registered_asset_id")).isEqualTo(registeredAssetId.toString());
        assertThat(sessionRow.get("lease_owner")).isNull();
        assertThat(sessionRow.get("lease_expires_at")).isNull();
        assertThat(sessionRow.get("completed_at")).isNotNull();

        // E: Verify provider completeMultipartUpload was NOT called a second time during recovery
        verify(multipartStoragePort, times(1)).completeMultipartUpload(any(), any(), any());

        // 10. Repeated Completion Idempotency: re-call completeMultipartUpload
        MediaUploadSession idempotent = sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest());
        assertThat(idempotent.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(idempotent.getRegisteredAssetId()).isEqualTo(registeredAssetId);

        // Verify zero additional rows inserted in MySQL
        Integer finalAssetCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_assets WHERE id = ?",
                Integer.class,
                registeredAssetId.toString()
        );
        assertThat(finalAssetCount).isEqualTo(1);

        Integer finalVersionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_asset_versions WHERE asset_id = ?",
                Integer.class,
                registeredAssetId.toString()
        );
        assertThat(finalVersionCount).isEqualTo(1);

        // CompleteMultipartUpload still called only once across the entire test
        verify(multipartStoragePort, times(1)).completeMultipartUpload(any(), any(), any());
    }

    @Test
    @DisplayName("Concurrent recovery callers with controlled contention: winning recovery worker completes, competing worker rejects, exactly one registration")
    void shouldHandleConcurrentRecoveryWorkersDeterministicallyWithSingleWinner() throws Exception {
        TestFixture fixture = createAndPersistCompletingFixture("race-recovery");

        // Simulate an expired lease from a previous failed attempt eligible for recovery reclaim
        Instant expiredLeaseAt = clockPort.now().minus(Duration.ofMinutes(5));
        updatePersistedLease(fixture.sessionId(), "stale-worker-recovery", expiredLeaseAt);

        // Provider reports upload already assembled/absent on listUploadedParts
        when(multipartStoragePort.listUploadedParts(fixture.storageKey(), PROVIDER_UPLOAD_ID))
                .thenThrow(new StorageUploadNotFoundException(fixture.storageKey(), PROVIDER_UPLOAD_ID));

        CountDownLatch worker1TxACommittedLatch = new CountDownLatch(1);
        CountDownLatch worker2AttemptedLatch = new CountDownLatch(1);

        // Synchronize during Scenario A inspectObjectMetadata (after Worker 1 committed TX-A recovery claim in MySQL)
        when(multipartStoragePort.inspectObjectMetadata(fixture.storageKey())).thenAnswer(inv -> {
            worker1TxACommittedLatch.countDown();
            assertThat(worker2AttemptedLatch.await(10, TimeUnit.SECONDS)).isTrue();
            return new MultipartObjectMetadata(
                    fixture.storageKey(),
                    DECLARED_SIZE,
                    "video/mp4",
                    "\"complete-etag-50mib\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, fixture.sessionId().toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, fixture.allocationId().toString()
                    ),
                    "ver-b2-race-rec-001",
                    B2_PROVIDER,
                    B2_BUCKET
            );
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Thread 1: Worker 1 reclaims expired lease and recovers object metadata
            Future<MediaUploadSession> worker1Future = executor.submit(() ->
                    sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest())
            );

            // Thread 2: Worker 2 races while Worker 1's recovery claim lease is actively held in MySQL
            Future<MediaUploadSession> worker2Future = executor.submit(() -> {
                try {
                    assertThat(worker1TxACommittedLatch.await(10, TimeUnit.SECONDS)).isTrue();
                    return sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest());
                } finally {
                    worker2AttemptedLatch.countDown();
                }
            });

            // Worker 2 must be strictly rejected by active lease check
            assertThatThrownBy(() -> worker2Future.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Concurrent completion in progress");

            // Worker 1 completes registration successfully
            MediaUploadSession worker1Session = worker1Future.get(15, TimeUnit.SECONDS);
            assertThat(worker1Session).isNotNull();
            assertThat(worker1Session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
            UUID registeredAssetId = worker1Session.getRegisteredAssetId();
            assertThat(registeredAssetId).isNotNull();
            createdAssetIds.add(registeredAssetId);

            // Verify exactly one MediaAsset in MySQL with PRIVATE visibility and CLIENT_TAG
            Map<String, Object> assetRow = jdbcTemplate.queryForMap(
                    "SELECT id, media_type, visibility, status, current_version_number, client_tag FROM media_assets WHERE id = ?",
                    registeredAssetId.toString()
            );
            assertThat(assetRow.get("id")).isEqualTo(registeredAssetId.toString());
            assertThat(assetRow.get("media_type")).isEqualTo("VIDEO");
            assertThat(assetRow.get("visibility")).isEqualTo("PRIVATE");
            assertThat(assetRow.get("status")).isEqualTo("ACTIVE");
            assertThat(((Number) assetRow.get("current_version_number")).intValue()).isEqualTo(1);
            assertThat(assetRow.get("client_tag")).isEqualTo(CLIENT_TAG);

            // Verify exactly one MediaAssetVersion in MySQL
            Integer versionCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM media_asset_versions WHERE asset_id = ?",
                    Integer.class,
                    registeredAssetId.toString()
            );
            assertThat(versionCount).isEqualTo(1);

            // Verify exactly one COMMITTED allocation in MySQL
            Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                    "SELECT allocation_status, allocated_by_type, allocated_by_id FROM media_storage_key_allocations WHERE id = ?",
                    fixture.allocationId().toString()
            );
            assertThat(allocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.COMMITTED.name());
            assertThat(allocRow.get("allocated_by_type")).isEqualTo(StorageKeyAllocationType.ASSET_VERSION.name());

            // Verify session is COMPLETED in MySQL with cleared lease
            Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                    "SELECT status, registered_asset_id, lease_owner, lease_expires_at FROM media_upload_sessions WHERE id = ?",
                    fixture.sessionId().toString()
            );
            assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETED.name());
            assertThat(sessionRow.get("registered_asset_id")).isEqualTo(registeredAssetId.toString());
            assertThat(sessionRow.get("lease_owner")).isNull();
            assertThat(sessionRow.get("lease_expires_at")).isNull();

            // Zero calls to completeMultipartUpload or abortMultipartUpload
            verify(multipartStoragePort, never()).completeMultipartUpload(any(), any(), any());
            verify(multipartStoragePort, never()).abortMultipartUpload(any(), any());

            // Subsequent idempotent call by Worker 2 succeeds and returns the winning asset ID
            MediaUploadSession followUpSession = sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest());
            assertThat(followUpSession.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
            assertThat(followUpSession.getRegisteredAssetId()).isEqualTo(registeredAssetId);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Rejects recovery TX-B registration when claim token is replaced by competing worker before TX-B commits")
    void shouldRejectStaleRecoveryWorkerWhenClaimTokenReplacedBeforeTxBCommit() {
        TestFixture fixture = createAndPersistCompletingFixture("fence-replace-claim");

        when(multipartStoragePort.listUploadedParts(fixture.storageKey(), PROVIDER_UPLOAD_ID))
                .thenThrow(new StorageUploadNotFoundException(fixture.storageKey(), PROVIDER_UPLOAD_ID));

        // When inspectObjectMetadata is called (outside MySQL transaction, between TX-A and TX-B),
        // simulate a competing worker or reaper replacing the lease in MySQL with a different claim token
        when(multipartStoragePort.inspectObjectMetadata(fixture.storageKey())).thenAnswer(inv -> {
            Instant futureLeaseAt = clockPort.now().plus(Duration.ofMinutes(5));
            updatePersistedLease(fixture.sessionId(), "COMPLETION_CLAIM:competing-replacement-token", futureLeaseAt);

            return new MultipartObjectMetadata(
                    fixture.storageKey(),
                    DECLARED_SIZE,
                    "video/mp4",
                    "\"complete-etag-50mib\"",
                    Map.of(
                            MediaUploadSessionService.METADATA_SESSION_ID, fixture.sessionId().toString(),
                            MediaUploadSessionService.METADATA_ALLOCATION_ID, fixture.allocationId().toString()
                    ),
                    "ver-b2-fence-rep-001",
                    B2_PROVIDER,
                    B2_BUCKET
            );
        });

        // Stale worker's attempt to commit TX-B MUST fail due to claim token mismatch
        assertThatThrownBy(() -> sessionService.completeMultipartUpload(fixture.sessionId(), PRINCIPAL, fixture.manifest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Completion claim token mismatch")
                .hasMessageContaining("competing-replacement-token");

        // Verify session remains COMPLETING in MySQL and zero assets/versions were committed by the stale worker
        Map<String, Object> sessionRow = jdbcTemplate.queryForMap(
                "SELECT status, registered_asset_id FROM media_upload_sessions WHERE id = ?",
                fixture.sessionId().toString()
        );
        assertThat(sessionRow.get("status")).isEqualTo(MediaUploadSessionStatus.COMPLETING.name());
        assertThat(sessionRow.get("registered_asset_id")).isNull();

        Map<String, Object> allocRow = jdbcTemplate.queryForMap(
                "SELECT allocation_status FROM media_storage_key_allocations WHERE id = ?",
                fixture.allocationId().toString()
        );
        assertThat(allocRow.get("allocation_status")).isEqualTo(StorageKeyAllocationStatus.RESERVED.name());

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_asset_versions WHERE storage_key = ?",
                Integer.class,
                fixture.storageKey().value()
        );
        assertThat(versionCount).isEqualTo(0);
    }
}
