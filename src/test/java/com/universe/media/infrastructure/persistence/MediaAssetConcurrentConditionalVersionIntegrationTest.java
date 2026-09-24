package com.universe.media.infrastructure.persistence;

import com.universe.media.application.asset.RasterContentSignatureValidator;
import com.universe.media.application.asset.RegisterMediaAssetVersionUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionCommand;
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalResult;
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalUseCase;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.contracts.dto.MediaVersionUploadOutcome;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        MediaAssetPersistenceAdapter.class,
        MediaAssetVersionPersistenceAdapter.class,
        RegisterMediaAssetVersionUseCase.class,
        RasterContentSignatureValidator.class,
        UploadMediaAssetVersionConditionalUseCase.class,
        MediaAssetConcurrentConditionalVersionIntegrationTest.TestConfig.class
})
class MediaAssetConcurrentConditionalVersionIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static final StorageProviderId LOCAL_PROVIDER = StorageProviderId.of("local");

    private static byte[] createPngBytes(String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] payload = new byte[header.length + textBytes.length];
        System.arraycopy(header, 0, payload, 0, header.length);
        System.arraycopy(textBytes, 0, payload, header.length, textBytes.length);
        return payload;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final byte[] PNG_V1 = createPngBytes("binary-v1");
    private static final byte[] PNG_V2 = createPngBytes("binary-v2-different");

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }

        @Bean
        public ConcurrentTrackingStoragePort concurrentTrackingStoragePort() {
            return new ConcurrentTrackingStoragePort();
        }
    }

    static class ConcurrentTrackingStoragePort implements BinaryStoragePort {
        final AtomicInteger storeCallCount = new AtomicInteger(0);
        final AtomicInteger deleteCallCount = new AtomicInteger(0);

        @Override
        public StorageProviderId providerId() {
            return LOCAL_PROVIDER;
        }

        @Override
        public void store(StorageKey storageKey, InputStream content, long sizeBytes, MimeType mimeType) {
            storeCallCount.incrementAndGet();
        }

        @Override
        public InputStream open(StorageKey storageKey) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream openRange(StorageKey storageKey, long startInclusive, long length) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void delete(StorageKey storageKey) {
            deleteCallCount.incrementAndGet();
        }

        public void reset() {
            storeCallCount.set(0);
            deleteCallCount.set(0);
        }
    }

    @Autowired
    private MediaAssetPersistenceAdapter assetAdapter;

    @Autowired
    private MediaAssetVersionPersistenceAdapter versionAdapter;

    @Autowired
    private UploadMediaAssetVersionConditionalUseCase conditionalUseCase;

    @Autowired
    private ConcurrentTrackingStoragePort storagePort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdAssetIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        storagePort.reset();
    }

    @AfterEach
    void cleanUp() {
        for (UUID assetId : createdAssetIds) {
            jdbcTemplate.update("DELETE FROM media_image_variants WHERE version_id IN (SELECT id FROM media_asset_versions WHERE asset_id = ?)", assetId.toString());
            jdbcTemplate.update("DELETE FROM media_asset_versions WHERE asset_id = ?", assetId.toString());
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", assetId.toString());
        }
        createdAssetIds.clear();
    }

    private UUID seedAssetWithVersion1(byte[] binaryBytes, String filename) {
        Instant now = Instant.now();
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        MediaAsset asset = MediaAsset.registerInitial(assetId, MediaType.IMAGE, MediaVisibility.PUBLIC, "wiki.article.cover", now);
        assetAdapter.save(asset);

        String hash = sha256Hex(binaryBytes);
        UUID versionId = UUID.randomUUID();
        MediaAssetVersion version = MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                StorageLocation.of(LOCAL_PROVIDER, StorageKey.of("objects/" + UUID.randomUUID())),
                "https://cdn.universe.com/covers/" + filename,
                ContentHash.of(hash),
                MimeType.of("image/png"),
                binaryBytes.length,
                filename,
                now
        );
        versionAdapter.save(version);
        return assetId;
    }

    @Test
    @DisplayName("Concurrent identical uploads: exactly one thread creates version 2, second thread resolves to UNCHANGED and cleans storage")
    void shouldReconcileConcurrentIdenticalVersionUploadsDeterministically() throws Exception {
        UUID assetId = seedAssetWithVersion1(PNG_V1, "cover.png");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<UploadMediaAssetVersionConditionalResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                readyLatch.await(5, TimeUnit.SECONDS);
                startLatch.await(5, TimeUnit.SECONDS);

                UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                        assetId,
                        new ByteArrayInputStream(PNG_V2),
                        PNG_V2.length,
                        "image/png",
                        "cover-thread-" + index + ".png"
                );
                return conditionalUseCase.execute(command);
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int unchangedCount = 0;
        int versionCreatedCount = 0;

        for (Future<UploadMediaAssetVersionConditionalResult> future : futures) {
            UploadMediaAssetVersionConditionalResult result = future.get(10, TimeUnit.SECONDS);
            assertThat(result.assetId()).isEqualTo(assetId);
            assertThat(result.versionNumber()).isEqualTo(2);

            if (result.outcome() == MediaVersionUploadOutcome.VERSION_CREATED) {
                versionCreatedCount++;
            } else if (result.outcome() == MediaVersionUploadOutcome.UNCHANGED) {
                unchangedCount++;
            }
        }

        executor.shutdown();

        // Exactly one created version 2, the other reconciled to UNCHANGED
        assertThat(versionCreatedCount).isEqualTo(1);
        assertThat(unchangedCount).isEqualTo(1);

        // Database asserts: exactly 2 versions exist in DB, current_version_number is 2
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_asset_versions WHERE asset_id = ?",
                Integer.class,
                assetId.toString()
        );
        assertThat(versionCount).isEqualTo(2);

        Integer currentVersionNumber = jdbcTemplate.queryForObject(
                "SELECT current_version_number FROM media_assets WHERE id = ?",
                Integer.class,
                assetId.toString()
        );
        assertThat(currentVersionNumber).isEqualTo(2);

        // Redundant storage object from second thread was compensated
        assertThat(storagePort.deleteCallCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent uploads of current binary: both threads return UNCHANGED with 0 storage writes and 0 DB increments")
    void shouldReturnUnchangedOnAllConcurrentCurrentBinaryUploads() throws Exception {
        UUID assetId = seedAssetWithVersion1(PNG_V1, "cover.png");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<UploadMediaAssetVersionConditionalResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                readyLatch.await(5, TimeUnit.SECONDS);
                startLatch.await(5, TimeUnit.SECONDS);

                UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                        assetId,
                        new ByteArrayInputStream(PNG_V1),
                        PNG_V1.length,
                        "image/png",
                        "cover-same.png"
                );
                return conditionalUseCase.execute(command);
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<UploadMediaAssetVersionConditionalResult> future : futures) {
            UploadMediaAssetVersionConditionalResult result = future.get(10, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(MediaVersionUploadOutcome.UNCHANGED);
            assertThat(result.versionNumber()).isEqualTo(1);
        }

        executor.shutdown();

        // Zero storage writes
        assertThat(storagePort.storeCallCount.get()).isEqualTo(0);

        // DB remains at version 1
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_asset_versions WHERE asset_id = ?",
                Integer.class,
                assetId.toString()
        );
        assertThat(versionCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Stale duplicate race: Worker 1 spools H1, Worker 2 commits H2; Worker 1 probe sees H2, rejects duplicate, and creates Version 3")
    void shouldNotReturnStaleUnchangedWhenConcurrentWorkerReplacesVersionDuringSpooling() throws Exception {
        UUID assetId = seedAssetWithVersion1(PNG_V1, "cover-v1.png");

        CountDownLatch worker1SpooledLatch = new CountDownLatch(1);
        CountDownLatch worker2CommittedLatch = new CountDownLatch(1);
        AtomicBoolean signaled = new AtomicBoolean(false);

        InputStream blockingStream = new FilterInputStream(new ByteArrayInputStream(PNG_V1)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int res = super.read(b, off, len);
                if (res == -1 && signaled.compareAndSet(false, true)) {
                    worker1SpooledLatch.countDown();
                    try {
                        boolean ok = worker2CommittedLatch.await(10, TimeUnit.SECONDS);
                        if (!ok) {
                            throw new IOException("Worker 2 timed out before committing");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted waiting for Worker 2", e);
                    }
                }
                return res;
            }

            @Override
            public int read() throws IOException {
                int res = super.read();
                if (res == -1 && signaled.compareAndSet(false, true)) {
                    worker1SpooledLatch.countDown();
                    try {
                        boolean ok = worker2CommittedLatch.await(10, TimeUnit.SECONDS);
                        if (!ok) {
                            throw new IOException("Worker 2 timed out before committing");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted waiting for Worker 2", e);
                    }
                }
                return res;
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<UploadMediaAssetVersionConditionalResult> worker1Future = executor.submit(() -> {
                UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                        assetId,
                        blockingStream,
                        PNG_V1.length,
                        "image/png",
                        "cover-v1-replay.png"
                );
                return conditionalUseCase.execute(command);
            });

            // Wait until Worker 1 has spooled PNG_V1 and computed hash H1
            boolean spooled = worker1SpooledLatch.await(10, TimeUnit.SECONDS);
            assertThat(spooled).isTrue();

            // At this point Worker 1 is paused right before the authoritative duplicate probe.
            // Worker 2 runs and commits Version 2 with PNG_V2 (hash H2).
            UploadMediaAssetVersionCommand worker2Command = new UploadMediaAssetVersionCommand(
                    assetId,
                    new ByteArrayInputStream(PNG_V2),
                    PNG_V2.length,
                    "image/png",
                    "cover-v2.png"
            );
            UploadMediaAssetVersionConditionalResult worker2Result = conditionalUseCase.execute(worker2Command);

            assertThat(worker2Result.outcome()).isEqualTo(MediaVersionUploadOutcome.VERSION_CREATED);
            assertThat(worker2Result.versionNumber()).isEqualTo(2);

            // Now release Worker 1 to perform its authoritative duplicate probe
            worker2CommittedLatch.countDown();

            UploadMediaAssetVersionConditionalResult worker1Result = worker1Future.get(10, TimeUnit.SECONDS);

            // In MS-05G9.1, Worker 1 probe must NOT take stale UNCHANGED; it observes currentVersion=2 (H2 != H1)
            // and must proceed to store PNG_V1 and create Version 3.
            assertThat(worker1Result.outcome()).isEqualTo(MediaVersionUploadOutcome.VERSION_CREATED);
            assertThat(worker1Result.versionNumber()).isEqualTo(3);

            // Total 3 versions exist in the database
            Integer versionCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM media_asset_versions WHERE asset_id = ?",
                    Integer.class,
                    assetId.toString()
            );
            assertThat(versionCount).isEqualTo(3);

            Integer currentVersionNumber = jdbcTemplate.queryForObject(
                    "SELECT current_version_number FROM media_assets WHERE id = ?",
                    Integer.class,
                    assetId.toString()
            );
            assertThat(currentVersionNumber).isEqualTo(3);

            // Check content hashes of versions 1, 2, 3
            String hashV1 = sha256Hex(PNG_V1);
            String hashV2 = sha256Hex(PNG_V2);

            String v1DbHash = jdbcTemplate.queryForObject(
                    "SELECT content_hash FROM media_asset_versions WHERE asset_id = ? AND version_number = 1",
                    String.class,
                    assetId.toString()
            );
            String v2DbHash = jdbcTemplate.queryForObject(
                    "SELECT content_hash FROM media_asset_versions WHERE asset_id = ? AND version_number = 2",
                    String.class,
                    assetId.toString()
            );
            String v3DbHash = jdbcTemplate.queryForObject(
                    "SELECT content_hash FROM media_asset_versions WHERE asset_id = ? AND version_number = 3",
                    String.class,
                    assetId.toString()
            );

            assertThat(v1DbHash).isEqualTo(hashV1);
            assertThat(v2DbHash).isEqualTo(hashV2);
            assertThat(v3DbHash).isEqualTo(hashV1);

        } finally {
            executor.shutdownNow();
        }
    }
}
