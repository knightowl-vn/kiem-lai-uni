package com.universe.media.application.hls;

import com.universe.media.application.asset.VideoUploadValidator;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ContentHashStatus;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.infrastructure.hls.HlsSourceDownloadProperties;
import com.universe.media.infrastructure.hls.MediaVideoSourceDownloader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResolveMediaVideoSourceSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-11-21T16:00:00Z");
    private static final ContentHash HASH =
            ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

    private static ClaimedVideoPackagingLease leaseFor(UUID assetId, UUID versionId, int versionNumber) {
        UUID pkgId = UUID.randomUUID();
        UUID pkgUuid = UUID.randomUUID();
        StorageKey prefix = StorageKey.of("objects/hls/" + assetId + "/v" + versionNumber + "/" + pkgUuid + "/");
        return new ClaimedVideoPackagingLease(
                UUID.randomUUID(),
                assetId,
                versionId,
                versionNumber,
                pkgId,
                pkgUuid,
                1L,
                1,
                StorageProviderId.of("r2-hls"),
                "hls-bucket",
                prefix,
                StorageKey.of(prefix.value() + "master.m3u8"),
                NOW.plusSeconds(300)
        );
    }

    private static MediaAsset activeVideoAsset(UUID assetId, int currentVersionNumber) {
        return MediaAsset.rehydrate(
                assetId,
                MediaType.VIDEO,
                MediaVisibility.PRIVATE,
                MediaAssetStatus.ACTIVE,
                currentVersionNumber,
                "screen-video",
                NOW.minusSeconds(60),
                NOW
        );
    }

    private static MediaAssetVersion videoVersion(
            UUID assetId,
            UUID versionId,
            int versionNumber,
            String provider,
            ContentHashStatus hashStatus,
            long sizeBytes,
            String mimeType
    ) {
        return MediaAssetVersion.rehydrate(
                versionId,
                assetId,
                versionNumber,
                StorageLocation.of(provider, "objects/video-" + assetId + "-v" + versionNumber + ".mp4"),
                null,
                HASH,
                hashStatus,
                MimeType.of(mimeType),
                sizeBytes,
                "movie.mp4",
                NOW
        );
    }

    @Test
    @DisplayName("Resolves immutable source snapshot from ClaimedVideoPackagingLease preserving ContentHashStatus")
    void resolvesSnapshotFromLeasePreservingHashStatus() {
        MediaAssetRepositoryPort assetRepo = mock(MediaAssetRepositoryPort.class);
        MediaAssetVersionRepositoryPort versionRepo = mock(MediaAssetVersionRepositoryPort.class);
        ResolveMediaVideoSourceSnapshotUseCase useCase = new ResolveMediaVideoSourceSnapshotUseCase(assetRepo, versionRepo);

        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        ClaimedVideoPackagingLease lease = leaseFor(assetId, versionId, 2);

        when(assetRepo.findById(assetId)).thenReturn(Optional.of(activeVideoAsset(assetId, 2)));
        when(versionRepo.findByAssetIdAndVersionNumber(assetId, 2)).thenReturn(Optional.of(
                videoVersion(assetId, versionId, 2, "r2-movie", ContentHashStatus.CLIENT_DECLARED_UNVERIFIED, 1024L, "video/mp4")
        ));

        MediaVideoSourceSnapshot snapshot = useCase.resolve(lease);

        assertThat(snapshot.assetId()).isEqualTo(assetId);
        assertThat(snapshot.sourceVersionId()).isEqualTo(versionId);
        assertThat(snapshot.sourceVersionNumber()).isEqualTo(2);
        assertThat(snapshot.storageProviderId()).isEqualTo(StorageProviderId.of("r2-movie"));
        assertThat(snapshot.expectedContentHash()).isEqualTo(HASH);
        assertThat(snapshot.contentHashStatus()).isEqualTo(ContentHashStatus.CLIENT_DECLARED_UNVERIFIED);
        assertThat(snapshot.expectedSizeBytes()).isEqualTo(1024L);
        assertThat(snapshot.mimeType()).isEqualTo(MimeType.of("video/mp4"));
    }

    @Test
    @DisplayName("Fails closed on missing asset, inactive asset, missing version, superseded version, and identity mismatch")
    void failsClosedOnMissingOrStaleMetadata() {
        MediaAssetRepositoryPort assetRepo = mock(MediaAssetRepositoryPort.class);
        MediaAssetVersionRepositoryPort versionRepo = mock(MediaAssetVersionRepositoryPort.class);
        ResolveMediaVideoSourceSnapshotUseCase useCase = new ResolveMediaVideoSourceSnapshotUseCase(assetRepo, versionRepo);

        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        // Missing asset
        when(assetRepo.findById(assetId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.resolve(leaseFor(assetId, versionId, 1)))
                .isInstanceOf(MediaAssetNotFoundException.class);

        // Inactive asset (ARCHIVED / DELETED)
        when(assetRepo.findById(assetId)).thenReturn(Optional.of(MediaAsset.rehydrate(
                assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, MediaAssetStatus.ARCHIVED, 1, NOW, NOW
        )));
        assertThatThrownBy(() -> useCase.resolve(leaseFor(assetId, versionId, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-ACTIVE");

        when(assetRepo.findById(assetId)).thenReturn(Optional.of(MediaAsset.rehydrate(
                assetId, MediaType.VIDEO, MediaVisibility.PUBLIC, MediaAssetStatus.DELETED, 1, NOW, NOW
        )));
        assertThatThrownBy(() -> useCase.resolve(leaseFor(assetId, versionId, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-ACTIVE");

        // Superseded version
        when(assetRepo.findById(assetId)).thenReturn(Optional.of(activeVideoAsset(assetId, 2)));
        assertThatThrownBy(() -> useCase.resolve(leaseFor(assetId, versionId, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer authoritative");

        // Missing version row
        when(versionRepo.findByAssetIdAndVersionNumber(assetId, 2)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.resolve(leaseFor(assetId, versionId, 2)))
                .isInstanceOf(MediaAssetVersionNotFoundException.class);

        // Version ID mismatch
        UUID otherVersionId = UUID.randomUUID();
        when(versionRepo.findByAssetIdAndVersionNumber(assetId, 2)).thenReturn(Optional.of(
                videoVersion(assetId, otherVersionId, 2, "b2", null, 2048L, "video/mp4")
        ));
        assertThatThrownBy(() -> useCase.resolve(leaseFor(assetId, versionId, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity mismatch");
    }

    @Test
    @DisplayName("Rejects non-VIDEO asset, non-video/mp4 MIME, and excessive source size")
    void rejectsUnsupportedTypeMimeAndExcessiveSize() {
        MediaAssetRepositoryPort assetRepo = mock(MediaAssetRepositoryPort.class);
        MediaAssetVersionRepositoryPort versionRepo = mock(MediaAssetVersionRepositoryPort.class);
        ResolveMediaVideoSourceSnapshotUseCase useCase =
                new ResolveMediaVideoSourceSnapshotUseCase(assetRepo, versionRepo, 10_000L);

        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        // Non-VIDEO asset
        when(assetRepo.findById(assetId)).thenReturn(Optional.of(MediaAsset.rehydrate(
                assetId, MediaType.AUDIO, MediaVisibility.PUBLIC, MediaAssetStatus.ACTIVE, 1, NOW, NOW
        )));
        assertThatThrownBy(() -> useCase.resolve(leaseFor(assetId, versionId, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MediaType.VIDEO");

        // Non-mp4 MIME
        when(assetRepo.findById(assetId)).thenReturn(Optional.of(activeVideoAsset(assetId, 1)));
        when(versionRepo.findByAssetIdAndVersionNumber(assetId, 1)).thenReturn(Optional.of(
                videoVersion(assetId, versionId, 1, "b2", null, 5000L, "video/webm")
        ));
        assertThatThrownBy(() -> useCase.resolve(leaseFor(assetId, versionId, 1)))
                .isInstanceOf(UploadContentMimeMismatchException.class);

        // Excessive size
        when(versionRepo.findByAssetIdAndVersionNumber(assetId, 1)).thenReturn(Optional.of(
                videoVersion(assetId, versionId, 1, "b2", null, 10_001L, "video/mp4")
        ));
        assertThatThrownBy(() -> useCase.resolve(leaseFor(assetId, versionId, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed bounds");
    }

    @Test
    @DisplayName("Transaction isolation: readOnly tx commits BEFORE StorageProviderResolverPort and openRange execute")
    void verifiesReadOnlyTransactionClosesBeforeStorageIo(@TempDir Path tempDir) throws Exception {
        byte[] payload = "synthetic-mp4-bytes-for-tx-boundary-proof".getBytes(StandardCharsets.UTF_8);
        ContentHash payloadHash = sha256(payload);
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        AtomicInteger txBegun = new AtomicInteger();
        AtomicInteger txCommitted = new AtomicInteger();
        AtomicBoolean sawActiveTxDuringVersionLookup = new AtomicBoolean(false);
        AtomicBoolean sawReadOnlyTxDuringVersionLookup = new AtomicBoolean(false);
        AtomicBoolean sawActiveTxDuringStorageResolve = new AtomicBoolean(true);
        AtomicBoolean sawActiveTxDuringOpenRange = new AtomicBoolean(true);

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("payload", byte[].class, () -> payload);
            ctx.registerBean("payloadHash", ContentHash.class, () -> payloadHash);
            ctx.registerBean("assetId", UUID.class, () -> assetId);
            ctx.registerBean("versionId", UUID.class, () -> versionId);
            ctx.registerBean("tempDir", Path.class, () -> tempDir);
            ctx.register(TxBoundaryTestConfig.class);

            ctx.registerBean(MediaAssetRepositoryPort.class, () -> {
                MediaAssetRepositoryPort repo = mock(MediaAssetRepositoryPort.class);
                when(repo.findById(assetId)).thenReturn(Optional.of(activeVideoAsset(assetId, 1)));
                return repo;
            });

            ctx.registerBean(MediaAssetVersionRepositoryPort.class, () -> {
                MediaAssetVersionRepositoryPort repo = mock(MediaAssetVersionRepositoryPort.class);
                when(repo.findByAssetIdAndVersionNumber(assetId, 1)).thenAnswer(inv -> {
                    sawActiveTxDuringVersionLookup.set(TransactionSynchronizationManager.isActualTransactionActive());
                    sawReadOnlyTxDuringVersionLookup.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                    return Optional.of(MediaAssetVersion.rehydrate(
                            versionId, assetId, 1,
                            StorageLocation.of("b2", "objects/tx-proof.mp4"),
                            null, payloadHash, ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                            MimeType.of("video/mp4"), payload.length, "tx-proof.mp4", NOW
                    ));
                });
                return repo;
            });

            ctx.registerBean(PlatformTransactionManager.class, () -> new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    txBegun.incrementAndGet();
                    TransactionSynchronizationManager.setActualTransactionActive(true);
                    TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition != null && definition.isReadOnly());
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                    txCommitted.incrementAndGet();
                    TransactionSynchronizationManager.setActualTransactionActive(false);
                    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
                }

                @Override
                public void rollback(TransactionStatus status) {
                    TransactionSynchronizationManager.setActualTransactionActive(false);
                    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
                }
            });

            ctx.refresh();

            ResolveMediaVideoSourceSnapshotUseCase useCase = ctx.getBean(ResolveMediaVideoSourceSnapshotUseCase.class);
            BinaryStoragePort fakeB2 = new BinaryStoragePort() {
                @Override
                public StorageProviderId providerId() {
                    return StorageProviderId.of("b2");
                }

                @Override
                public com.universe.media.application.ports.storage.StoredBinaryObject store(
                        StorageKey key, InputStream content, long sizeBytes, MimeType mimeType) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public InputStream open(StorageKey key) {
                    throw new AssertionError("Downloader must use openRange, not open()");
                }

                @Override
                public InputStream openRange(StorageKey key, long startInclusive, long length) {
                    sawActiveTxDuringOpenRange.set(TransactionSynchronizationManager.isActualTransactionActive());
                    int start = (int) startInclusive;
                    int len = (int) length;
                    return new ByteArrayInputStream(payload, start, len);
                }

                @Override
                public void delete(StorageKey key) {
                }
            };

            StorageProviderResolverPort resolver = providerId -> {
                sawActiveTxDuringStorageResolve.set(TransactionSynchronizationManager.isActualTransactionActive());
                return fakeB2;
            };

            MediaVideoSourceDownloader downloader = new MediaVideoSourceDownloader(
                    resolver,
                    HlsSourceDownloadProperties.defaults(tempDir.resolve("sources"))
            );

            assertThat(MediaVideoSourceDownloader.class.isAnnotationPresent(Transactional.class)).isFalse();

            MediaVideoSourceSnapshot snapshot = useCase.resolve(leaseFor(assetId, versionId, 1));
            assertThat(txBegun.get()).isEqualTo(1);
            assertThat(txCommitted.get()).isEqualTo(1);
            assertThat(sawActiveTxDuringVersionLookup.get()).isTrue();
            assertThat(sawReadOnlyTxDuringVersionLookup.get()).isTrue();

            try (DownloadedSourceScope scope = downloader.downloadVerifiedSource(snapshot)) {
                assertThat(sawActiveTxDuringStorageResolve.get()).isFalse();
                assertThat(sawActiveTxDuringOpenRange.get()).isFalse();
                assertThat(scope.verifiedSha256()).isEqualTo(payloadHash);
                assertThat(scope.verifiedSizeBytes()).isEqualTo(payload.length);
            }
        }
    }

    @Test
    @DisplayName("Rejects download inside active Spring-managed transaction without provider resolution or temp files, and succeeds after commit")
    void rejectsDownloadInsideActiveSpringManagedTransactionAndAllowsDownloadAfterCommit(@TempDir Path tempDir)
            throws Exception {
        byte[] payload = "synthetic-mp4-bytes-for-active-tx-guard".getBytes(StandardCharsets.UTF_8);
        ContentHash payloadHash = sha256(payload);
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Path sourceRoot = tempDir.resolve("sources");

        AtomicInteger txBegun = new AtomicInteger();
        AtomicInteger txCommitted = new AtomicInteger();
        AtomicInteger resolveCalls = new AtomicInteger();
        AtomicInteger openRangeCalls = new AtomicInteger();
        AtomicBoolean sawActiveTxInsideBlock = new AtomicBoolean(false);

        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                txBegun.incrementAndGet();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(
                        definition != null && definition.isReadOnly()
                );
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                txCommitted.incrementAndGet();
                TransactionSynchronizationManager.setActualTransactionActive(false);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            }

            @Override
            public void rollback(TransactionStatus status) {
                TransactionSynchronizationManager.setActualTransactionActive(false);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            }
        };

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(TxBoundaryTestConfig.class);
            ctx.registerBean(MediaAssetRepositoryPort.class, () -> {
                MediaAssetRepositoryPort repo = mock(MediaAssetRepositoryPort.class);
                when(repo.findById(assetId)).thenReturn(Optional.of(activeVideoAsset(assetId, 1)));
                return repo;
            });
            ctx.registerBean(MediaAssetVersionRepositoryPort.class, () -> {
                MediaAssetVersionRepositoryPort repo = mock(MediaAssetVersionRepositoryPort.class);
                when(repo.findByAssetIdAndVersionNumber(assetId, 1)).thenReturn(Optional.of(
                        MediaAssetVersion.rehydrate(
                                versionId, assetId, 1,
                                StorageLocation.of("b2", "objects/active-tx-guard.mp4"),
                                null, payloadHash, ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                                MimeType.of("video/mp4"), payload.length, "active-tx-guard.mp4", NOW
                        )
                ));
                return repo;
            });
            ctx.registerBean(PlatformTransactionManager.class, () -> txManager);
            ctx.refresh();

            ResolveMediaVideoSourceSnapshotUseCase useCase = ctx.getBean(ResolveMediaVideoSourceSnapshotUseCase.class);
            MediaVideoSourceSnapshot snapshot = useCase.resolve(leaseFor(assetId, versionId, 1));

            BinaryStoragePort fakeB2 = new BinaryStoragePort() {
                @Override
                public StorageProviderId providerId() {
                    return StorageProviderId.of("b2");
                }

                @Override
                public com.universe.media.application.ports.storage.StoredBinaryObject store(
                        StorageKey key, InputStream content, long sizeBytes, MimeType mimeType) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public InputStream open(StorageKey key) {
                    throw new AssertionError("Whole-object open() must never be called");
                }

                @Override
                public InputStream openRange(StorageKey key, long startInclusive, long length) {
                    openRangeCalls.incrementAndGet();
                    return new ByteArrayInputStream(payload, (int) startInclusive, (int) length);
                }

                @Override
                public void delete(StorageKey key) {
                }
            };

            StorageProviderResolverPort resolver = providerId -> {
                resolveCalls.incrementAndGet();
                return fakeB2;
            };

            MediaVideoSourceDownloader downloader = new MediaVideoSourceDownloader(
                    resolver,
                    HlsSourceDownloadProperties.defaults(sourceRoot)
            );

            TransactionTemplate txTemplate = new TransactionTemplate(txManager);
            txTemplate.executeWithoutResult(status -> {
                sawActiveTxInsideBlock.set(TransactionSynchronizationManager.isActualTransactionActive());
                assertThatThrownBy(() -> downloader.downloadVerifiedSource(snapshot))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("active database transaction");
            });

            assertThat(sawActiveTxInsideBlock.get()).isTrue();
            assertThat(txBegun.get()).isEqualTo(2);
            assertThat(txCommitted.get()).isEqualTo(2);
            assertThat(resolveCalls.get()).isZero();
            assertThat(openRangeCalls.get()).isZero();
            assertThat(Files.exists(sourceRoot)).isFalse();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

            try (DownloadedSourceScope scope = downloader.downloadVerifiedSource(snapshot)) {
                assertThat(resolveCalls.get()).isEqualTo(1);
                assertThat(openRangeCalls.get()).isEqualTo(1);
                assertThat(scope.verifiedSizeBytes()).isEqualTo(payload.length);
                assertThat(scope.verifiedSha256()).isEqualTo(payloadHash);
                assertThat(Files.isRegularFile(scope.sourceFile())).isTrue();
            }
        }
    }

    private static ContentHash sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return ContentHash.of(HexFormat.of().formatHex(md.digest(bytes)).toLowerCase(Locale.ROOT));
    }

    @Configuration
    @EnableTransactionManagement
    static class TxBoundaryTestConfig {
        @Bean
        ResolveMediaVideoSourceSnapshotUseCase resolveMediaVideoSourceSnapshotUseCase(
                MediaAssetRepositoryPort assetRepo,
                MediaAssetVersionRepositoryPort versionRepo
        ) {
            return new ResolveMediaVideoSourceSnapshotUseCase(assetRepo, versionRepo);
        }
    }
}
