package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        MediaUploadSessionPersistenceAdapter.class,
        StorageKeyAllocationPersistenceAdapter.class,
        MediaUploadSessionPersistenceIntegrationTest.TestConfig.class
})
@DisplayName("MediaUploadSession MySQL Persistence & Constraint Integration Test")
class MediaUploadSessionPersistenceIntegrationTest {

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
    private MediaUploadSessionPersistenceAdapter sessionAdapter;

    @Autowired
    private StorageKeyAllocationPersistenceAdapter allocationAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";
    private static final StorageKey STORAGE_KEY_1 = StorageKey.of("media/video/session_test_001.mp4");

    private final List<UUID> createdSessionIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdAllocationIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void tearDown() {
        for (UUID id : createdSessionIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_upload_sessions WHERE id = ?", id.toString());
            } catch (Exception ignored) {
            }
        }
        for (UUID allocId : createdAllocationIds) {
            try {
                jdbcTemplate.update("DELETE FROM media_storage_key_allocations WHERE id = ?", allocId.toString());
            } catch (Exception ignored) {
            }
        }
    }

    private StorageKeyAllocation createAndSaveAllocation(StorageKey storageKey) {
        UUID allocId = UUID.randomUUID();
        StorageKeyAllocation alloc = StorageKeyAllocation.reserve(
                allocId,
                B2_PROVIDER,
                B2_BUCKET,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                UUID.randomUUID(),
                Instant.now()
        );
        allocationAdapter.save(alloc);
        createdAllocationIds.add(allocId);
        return alloc;
    }

    @Test
    @DisplayName("Should persist and retrieve MediaUploadSession referencing valid StorageKeyAllocation")
    void shouldPersistAndRetrieveSession() {
        StorageKeyAllocation alloc = createAndSaveAllocation(STORAGE_KEY_1);

        UUID sessionId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();
        MediaUploadSession session = MediaUploadSession.create(
                sessionId,
                "user-pers-1",
                "screen",
                "ScreenItem",
                targetId,
                "idem-pers-1",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 3,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY_1,
                alloc.getId(),
                "video.mp4",
                "screen",
                now.plus(24, ChronoUnit.HOURS),
                now
        );

        sessionAdapter.save(session);
        createdSessionIds.add(sessionId);

        Optional<MediaUploadSession> retrieved = sessionAdapter.findById(sessionId);
        assertThat(retrieved).isPresent();
        MediaUploadSession s = retrieved.get();
        assertThat(s.getId()).isEqualTo(sessionId);
        assertThat(s.getInitiatorPrincipalId()).isEqualTo("user-pers-1");
        assertThat(s.getIdempotencyKey()).isEqualTo("idem-pers-1");
        assertThat(s.getStorageAllocationId()).isEqualTo(alloc.getId());
        assertThat(s.getStorageProviderId()).isEqualTo(B2_PROVIDER);
        assertThat(s.getStorageBucketName()).isEqualTo(B2_BUCKET);
        assertThat(s.getStorageKey()).isEqualTo(STORAGE_KEY_1);
        assertThat(s.getStatus()).isEqualTo(MediaUploadSessionStatus.ALLOCATED);
        assertThat(s.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.NONE);
        assertThat(s.getExpectedPartCount()).isEqualTo(3);
        assertThat(s.getManifestHash()).isNull();
    }

    @Test
    @DisplayName("Should reject session referencing non-existent allocation via foreign key")
    void shouldRejectNonExistentAllocationFk() {
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        MediaUploadSession session = MediaUploadSession.create(
                sessionId,
                "user-fk-1",
                "screen",
                "ScreenItem",
                UUID.randomUUID(),
                "idem-fk-1",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY_1,
                UUID.randomUUID(), // Non-existent allocation ID!
                "video.mp4",
                "screen",
                now.plus(24, ChronoUnit.HOURS),
                now
        );

        assertThatThrownBy(() -> sessionAdapter.save(session))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should reject duplicate owner and idempotency key via unique constraint")
    void shouldRejectDuplicateOwnerAndIdempotencyKey() {
        StorageKeyAllocation alloc1 = createAndSaveAllocation(StorageKey.of("media/video/dup_1.mp4"));
        StorageKeyAllocation alloc2 = createAndSaveAllocation(StorageKey.of("media/video/dup_2.mp4"));

        UUID sessionId1 = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();
        MediaUploadSession session1 = MediaUploadSession.create(
                sessionId1,
                "same-owner",
                "screen",
                "ScreenItem",
                targetId,
                "same-idem-key",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                B2_PROVIDER,
                B2_BUCKET,
                StorageKey.of("media/video/dup_1.mp4"),
                alloc1.getId(),
                "video1.mp4",
                "screen",
                now.plus(24, ChronoUnit.HOURS),
                now
        );
        sessionAdapter.save(session1);
        createdSessionIds.add(sessionId1);

        UUID sessionId2 = UUID.randomUUID();
        MediaUploadSession session2 = MediaUploadSession.create(
                sessionId2,
                "same-owner",
                "screen",
                "ScreenItem",
                targetId,
                "same-idem-key",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                B2_PROVIDER,
                B2_BUCKET,
                StorageKey.of("media/video/dup_2.mp4"),
                alloc2.getId(),
                "video2.mp4",
                "screen",
                now.plus(24, ChronoUnit.HOURS),
                now
        );

        assertThatThrownBy(() -> sessionAdapter.save(session2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should detect concurrent modification and throw OptimisticLockingFailureException")
    void shouldDetectOptimisticLockingFailure() {
        StorageKeyAllocation alloc = createAndSaveAllocation(StorageKey.of("media/video/opt_lock.mp4"));

        UUID sessionId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();
        MediaUploadSession session = MediaUploadSession.create(
                sessionId,
                "user-opt-1",
                "screen",
                "ScreenItem",
                targetId,
                "idem-opt-1",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                B2_PROVIDER,
                B2_BUCKET,
                StorageKey.of("media/video/opt_lock.mp4"),
                alloc.getId(),
                "video.mp4",
                "screen",
                now.plus(24, ChronoUnit.HOURS),
                now
        );
        sessionAdapter.save(session);
        createdSessionIds.add(sessionId);

        // Load entity in two separate instances
        MediaUploadSession sessionInstanceA = sessionAdapter.findById(sessionId).orElseThrow();
        MediaUploadSession sessionInstanceB = sessionAdapter.findById(sessionId).orElseThrow();

        sessionInstanceA.markInitiated("upload-a", Instant.now());
        sessionAdapter.save(sessionInstanceA);

        sessionInstanceB.markInitiated("upload-b", Instant.now());
        assertThatThrownBy(() -> sessionAdapter.save(sessionInstanceB))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("Should persist and roundtrip manifestHash in MediaUploadSession")
    void shouldPersistAndRoundtripManifestHash() {
        StorageKeyAllocation alloc = createAndSaveAllocation(StorageKey.of("media/video/session_manifest_001.mp4"));

        UUID sessionId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();
        String manifestHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        MediaUploadSession session = MediaUploadSession.create(
                sessionId,
                "user-pers-manifest",
                "screen",
                "ScreenItem",
                targetId,
                "idem-pers-manifest",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                B2_PROVIDER,
                B2_BUCKET,
                StorageKey.of("media/video/session_manifest_001.mp4"),
                alloc.getId(),
                "video.mp4",
                "screen",
                now.plus(24, ChronoUnit.HOURS),
                now
        );
        session.recordManifestHash(manifestHash);

        sessionAdapter.save(session);
        createdSessionIds.add(sessionId);

        Optional<MediaUploadSession> retrieved = sessionAdapter.findById(sessionId);
        assertThat(retrieved).isPresent();
        MediaUploadSession s = retrieved.get();
        assertThat(s.getManifestHash()).isEqualTo(manifestHash);
    }
}
