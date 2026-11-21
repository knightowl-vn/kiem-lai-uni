package com.universe.media.application.ports.storage;

import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HlsPackageContractAndKeyValidatorTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID PACKAGE_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final StorageProviderId R2_HLS = StorageProviderId.of("r2-hls");
    private static final ContentHash HASH_A = ContentHash.of("a".repeat(64));
    private static final ContentHash HASH_B = ContentHash.of("b".repeat(64));
    private static final ContentHash HASH_C = ContentHash.of("c".repeat(64));
    private static final ContentHash HASH_M = ContentHash.of("d".repeat(64));

    private static final HlsArtifactStreamSource DUMMY_STREAM_SOURCE = new HlsArtifactStreamSource() {
        @Override
        public ActiveUploadScope openUploadScope() {
            return new ActiveUploadScope() {
                @Override
                public boolean isCloseRequested() {
                    return false;
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public InputStream openArtifactStream(StorageKey objectKey) throws IOException {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public <T> T withArtifactStream(StorageKey objectKey, ArtifactStreamReader<T> reader) throws IOException {
            byte[] empty = new byte[0];
            return reader.read(new ByteArrayInputStream(empty), 0L);
        }

        @Override
        public void close() {
        }
    };

    @Test
    @DisplayName("Validates canonical prefix, identity equality, and rejects wrong asset/version/UUID")
    void shouldValidateCanonicalPrefixAndIdentityMatch() {
        StorageKey prefix = HlsPackageKeyValidator.buildCanonicalPrefix(ASSET_ID, 3, PACKAGE_UUID);
        assertThat(prefix.value())
                .isEqualTo("objects/hls/11111111-2222-3333-4444-555555555555/v3/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/");

        HlsPackageKeyValidator.ParsedCanonicalPrefix parsed =
                HlsPackageKeyValidator.validatePrefixMatchesIdentity(ASSET_ID, 3, PACKAGE_UUID, prefix);
        assertThat(parsed.assetId()).isEqualTo(ASSET_ID);
        assertThat(parsed.sourceVersionNumber()).isEqualTo(3);
        assertThat(parsed.packageUuid()).isEqualTo(PACKAGE_UUID);
        assertThat(parsed.masterManifestKey().value()).isEqualTo(prefix.value() + "master.m3u8");

        assertThatThrownBy(() -> HlsPackageKeyValidator.validatePrefixMatchesIdentity(
                UUID.randomUUID(), 3, PACKAGE_UUID, prefix
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");

        assertThatThrownBy(() -> HlsPackageKeyValidator.validatePrefixMatchesIdentity(
                ASSET_ID, 4, PACKAGE_UUID, prefix
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> HlsPackageKeyValidator.validatePrefixMatchesIdentity(
                ASSET_ID, 3, UUID.randomUUID(), prefix
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> HlsPackageKeyValidator.buildCanonicalPrefix(ASSET_ID, 0, PACKAGE_UUID))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> HlsPackageKeyValidator.parseAndValidateCanonicalPrefix(
                StorageKey.of(prefix.value().toUpperCase(java.util.Locale.ROOT))
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HlsPackageKeyValidator.parseAndValidateCanonicalPrefix(
                StorageKey.of("objects/hls/" + ASSET_ID + "/v03/" + PACKAGE_UUID + "/")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Enforces dedicated r2-hls provider and exact trusted bucket matching")
    void shouldEnforceDedicatedProviderAndTrustedBucket() {
        StorageKey validPrefix = HlsPackageKeyValidator.buildCanonicalPrefix(ASSET_ID, 1, PACKAGE_UUID);

        assertThat(HlsPackageKeyValidator.validateTrustedDestination(R2_HLS, "kiemlai-hls-priv", "kiemlai-hls-priv"))
                .isEqualTo("kiemlai-hls-priv");

        for (String foreign : List.of("r2", "r2-movie", "b2", "local")) {
            assertThatThrownBy(() -> HlsPackageKeyValidator.validatePurgeScope(
                    StorageProviderId.of(foreign), "kiemlai-hls-priv", validPrefix
            )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("r2-hls");
        }

        assertThatThrownBy(() -> HlsPackageKeyValidator.validateTrustedDestination(
                R2_HLS, "other-bucket", "kiemlai-hls-priv"
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mismatch");

        for (String invalidBucket : List.of("", "  ", "NONE", "none", "bad/bucket", "bad\\bucket", "a..b")) {
            assertThatThrownBy(() -> HlsPackageKeyValidator.validateBucketName(invalidBucket))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Rejects broad asset-level, version-level, and root prefixes on purge scope")
    void shouldRejectBroadPurgePrefixes() {
        List<String> broadOrMalformed = List.of(
                "objects/",
                "objects/hls/",
                "objects/hls/" + ASSET_ID + "/",
                "objects/hls/" + ASSET_ID + "/v1/",
                "objects/hls/" + ASSET_ID + "/v1/" + PACKAGE_UUID,
                "/objects/hls/" + ASSET_ID + "/v1/" + PACKAGE_UUID + "/"
        );
        for (String candidate : broadOrMalformed) {
            assertThatThrownBy(() -> HlsPackageKeyValidator.validatePurgeScope(
                    R2_HLS, "kiemlai-hls-priv", "kiemlai-hls-priv", StorageKey.of(candidate)
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Enforces exact canonical objectKey equality and rejects shadow directory or prefix substitutions")
    void shouldRejectShadowComponentsAndForeignPrefixSubstitutions() {
        StorageKey prefix = HlsPackageKeyValidator.buildCanonicalPrefix(ASSET_ID, 1, PACKAGE_UUID);
        StorageKey canonicalKey = HlsPackageKeyValidator.buildAndValidateObjectKey(prefix, "video/720p/seg_00000.m4s");
        assertThat(HlsPackageKeyValidator.validateExactObjectKey(prefix, "video/720p/seg_00000.m4s", canonicalKey))
                .isEqualTo(canonicalKey);

        // Regression P1-A: "{canonicalPrefix}shadow/video/720p/seg_00000.m4s" satisfies naive prefix & suffix checks
        StorageKey shadowedKey = StorageKey.of(prefix.value() + "shadow/video/720p/seg_00000.m4s");
        assertThatThrownBy(() -> HlsPackageKeyValidator.validateExactObjectKey(
                prefix, "video/720p/seg_00000.m4s", shadowedKey
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match canonical prefix");

        assertThatThrownBy(() -> new HlsArtifactDescriptor(
                "video/720p/seg_00000.m4s",
                shadowedKey,
                1024L,
                HASH_C,
                HlsArtifactType.MEDIA_SEGMENT,
                MimeType.of("video/iso.segment"),
                HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS
        )).isInstanceOf(IllegalArgumentException.class);

        // Substitution of another valid package UUID inside key rejected by manifest exact equality check
        HlsPackageIdentity identity = HlsPackageIdentity.ofCanonical(ASSET_ID, 1, PACKAGE_UUID, R2_HLS);
        StorageKey otherUuidPrefix = HlsPackageKeyValidator.buildCanonicalPrefix(ASSET_ID, 1, UUID.randomUUID());
        HlsArtifactDescriptor foreignSeg = new HlsArtifactDescriptor(
                "video/720p/seg_00000.m4s",
                StorageKey.of(otherUuidPrefix.value() + "video/720p/seg_00000.m4s"),
                1024L,
                HASH_C,
                HlsArtifactType.MEDIA_SEGMENT,
                MimeType.of("video/iso.segment"),
                HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS
        );
        HlsArtifactDescriptor master = descriptor(identity, "master.m3u8", 120, HASH_M,
                HlsArtifactType.MASTER_MANIFEST, "application/vnd.apple.mpegurl", HlsUploadStage.STAGE_3_MASTER_MANIFEST);
        HlsArtifactDescriptor vPl = descriptor(identity, "video/720p/playlist.m3u8", 220, HASH_A,
                HlsArtifactType.MEDIA_PLAYLIST, "application/vnd.apple.mpegurl", HlsUploadStage.STAGE_2_MEDIA_PLAYLISTS);
        HlsArtifactDescriptor vInit = descriptor(identity, "video/720p/init_0.mp4", 600, HASH_B,
                HlsArtifactType.INIT_SEGMENT, "video/mp4", HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS);

        assertThatThrownBy(() -> new HlsExpectedArtifactManifest(
                identity,
                List.of(master, vPl, vInit, foreignSeg),
                "{}"
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match canonical prefix");
    }

    @Test
    @DisplayName("Validates approved relative artifact paths and rejects traversal, encoded paths, unapproved tiers, and key overflow")
    void shouldValidateRelativePathsAndRejectMaliciousKeysAndOverflow() {
        StorageKey basePrefix = HlsPackageKeyValidator.buildCanonicalPrefix(ASSET_ID, 2, PACKAGE_UUID);

        assertThat(HlsPackageKeyValidator.validateAndClassifyRelativePath("master.m3u8").uploadStage())
                .isEqualTo(HlsUploadStage.STAGE_3_MASTER_MANIFEST);
        assertThat(HlsPackageKeyValidator.validateAndClassifyRelativePath("video/720p/playlist.m3u8").contentType().value())
                .isEqualTo("application/vnd.apple.mpegurl");
        assertThat(HlsPackageKeyValidator.validateAndClassifyRelativePath("video/source/init.mp4").contentType().value())
                .isEqualTo("video/mp4");
        assertThat(HlsPackageKeyValidator.validateAndClassifyRelativePath("video/2160p/seg_00012.m4s").contentType().value())
                .isEqualTo("video/iso.segment");
        assertThat(HlsPackageKeyValidator.validateAndClassifyRelativePath("audio/main/init_1.mp4").uploadStage())
                .isEqualTo(HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS);
        assertThat(HlsPackageKeyValidator.validateAndClassifyRelativePath("audio/main/playlist.m3u8").uploadStage())
                .isEqualTo(HlsUploadStage.STAGE_2_MEDIA_PLAYLISTS);

        for (String unsafe : List.of(
                "../escape.m4s",
                "video/720p/../../escape.m4s",
                "video/720p/%2e%2e/escape.m4s",
                "video\\720p\\seg_00000.m4s",
                "/video/720p/playlist.m3u8",
                "video//720p/playlist.m3u8",
                "video/480p/playlist.m3u8",
                "video/1080p/.hidden.m4s",
                "video/1080p/seg_00000.m4s?sig=1",
                "video/1080p/seg_00000.m4s#frag",
                "video/1080p/extra.m3u8",
                "audio/secondary/playlist.m3u8",
                "subtitles/en.vtt",
                "video/1080p/notes.txt",
                "video/1080p/seg\u0000.m4s"
        )) {
            assertThatThrownBy(() -> HlsPackageKeyValidator.buildAndValidateObjectKey(basePrefix, unsafe))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        String longValidSeg = "video/1080p/seg_" + "0".repeat(420) + ".m4s";
        assertThatThrownBy(() -> HlsPackageKeyValidator.buildAndValidateObjectKey(basePrefix, longValidSeg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds max length");
    }

    @Test
    @DisplayName("HlsExpectedArtifactManifest orders Stage1 -> Stage2 -> Stage3 master last and rejects duplicates")
    void shouldOrderExpectedArtifactsMasterLastAndRejectDuplicates() {
        HlsPackageIdentity identity = HlsPackageIdentity.ofCanonical(ASSET_ID, 1, PACKAGE_UUID, R2_HLS);
        HlsArtifactDescriptor master = descriptor(identity, "master.m3u8", 120, HASH_M,
                HlsArtifactType.MASTER_MANIFEST, "application/vnd.apple.mpegurl", HlsUploadStage.STAGE_3_MASTER_MANIFEST);
        HlsArtifactDescriptor vPl = descriptor(identity, "video/720p/playlist.m3u8", 220, HASH_A,
                HlsArtifactType.MEDIA_PLAYLIST, "application/vnd.apple.mpegurl", HlsUploadStage.STAGE_2_MEDIA_PLAYLISTS);
        HlsArtifactDescriptor vInit = descriptor(identity, "video/720p/init_0.mp4", 600, HASH_B,
                HlsArtifactType.INIT_SEGMENT, "video/mp4", HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS);
        HlsArtifactDescriptor vSeg = descriptor(identity, "video/720p/seg_00000.m4s", 1024, HASH_C,
                HlsArtifactType.MEDIA_SEGMENT, "video/iso.segment", HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS);

        HlsExpectedArtifactManifest manifest = new HlsExpectedArtifactManifest(
                identity,
                List.of(master, vPl, vSeg, vInit),
                "{\"video\":[{\"label\":\"720p\"}],\"audio\":null}"
        );

        assertThat(manifest.objectCount()).isEqualTo(4);
        assertThat(manifest.totalSizeBytes()).isEqualTo(120 + 220 + 600 + 1024);
        assertThat(manifest.masterManifestSha256()).isEqualTo(HASH_M);
        assertThat(manifest.orderedArtifacts())
                .extracting(HlsArtifactDescriptor::relativePath)
                .containsExactly(
                        "video/720p/init_0.mp4",
                        "video/720p/seg_00000.m4s",
                        "video/720p/playlist.m3u8",
                        "master.m3u8"
                );
        assertThat(manifest.stage3MasterManifest()).isEqualTo(master);

        assertThatThrownBy(() -> new HlsExpectedArtifactManifest(
                identity,
                List.of(master, vPl, vInit, vSeg, vSeg),
                "{}"
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");

        assertThatThrownBy(() -> new HlsPackageUploadCommand("NONE", manifest, DUMMY_STREAM_SOURCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Remote verification handoff validates structural parity cleanly and keeps private constructor boundary")
    void shouldEnforceRemoteVerificationStructuralParityAndBoundary() {
        HlsPackageIdentity identity = HlsPackageIdentity.ofCanonical(ASSET_ID, 1, PACKAGE_UUID, R2_HLS);
        HlsExpectedArtifactManifest manifest = new HlsExpectedArtifactManifest(
                identity,
                List.of(
                        descriptor(identity, "video/720p/init_0.mp4", 600, HASH_B,
                                HlsArtifactType.INIT_SEGMENT, "video/mp4", HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS),
                        descriptor(identity, "video/720p/seg_00000.m4s", 1024, HASH_C,
                                HlsArtifactType.MEDIA_SEGMENT, "video/iso.segment", HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS),
                        descriptor(identity, "video/720p/playlist.m3u8", 220, HASH_A,
                                HlsArtifactType.MEDIA_PLAYLIST, "application/vnd.apple.mpegurl", HlsUploadStage.STAGE_2_MEDIA_PLAYLISTS),
                        descriptor(identity, "master.m3u8", 120, HASH_M,
                                HlsArtifactType.MASTER_MANIFEST, "application/vnd.apple.mpegurl", HlsUploadStage.STAGE_3_MASTER_MANIFEST)
                ),
                "{\"video\":[{\"label\":\"720p\"}],\"audio\":null}"
        );

        assertThat(Modifier.isFinal(HlsUploadedPackageVerification.class.getModifiers())).isTrue();
        assertThat(HlsUploadedPackageVerification.class.getConstructors()).isEmpty();

        Instant now = Instant.now();
        HlsUploadedPackageVerification.RemoteVerificationEvidence validEvidence =
                new HlsUploadedPackageVerification.RemoteVerificationEvidence(
                        HlsRemoteIntegrityProof.STREAMED_GET_MANIFESTS_AND_INITS_WITH_EXACT_LISTING,
                        true,
                        true,
                        true,
                        3,
                        HASH_M,
                        1964L,
                        4,
                        now
                );

        HlsUploadedPackageVerification verified =
                HlsUploadedPackageVerification.fromRemoteVerification("kiemlai-hls-priv", manifest, validEvidence);
        assertThat(verified.exactPrefixKeySetMatched()).isTrue();
        assertThat(verified.remoteReceipt().masterManifestSha256()).isEqualTo(HASH_M);
        assertThat(verified.remoteReceipt().objectCount()).isEqualTo(4);
        assertThat(verified.remoteReceipt().totalSizeBytes()).isEqualTo(1964L);

        // Reject incomplete listing / unexpected foreign keys
        assertThatThrownBy(() -> HlsUploadedPackageVerification.fromRemoteVerification(
                "kiemlai-hls-priv", manifest,
                new HlsUploadedPackageVerification.RemoteVerificationEvidence(
                        HlsRemoteIntegrityProof.STREAMED_GET_MANIFESTS_AND_INITS_WITH_EXACT_LISTING,
                        true, true, false, 3, HASH_M, 1964L, 4, now
                )
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zero unexpected keys");

        // Reject insufficient digested manifests/inits (size-only or ETag-only insufficient)
        assertThatThrownBy(() -> HlsUploadedPackageVerification.fromRemoteVerification(
                "kiemlai-hls-priv", manifest,
                new HlsUploadedPackageVerification.RemoteVerificationEvidence(
                        HlsRemoteIntegrityProof.STREAMED_GET_MANIFESTS_AND_INITS_WITH_EXACT_LISTING,
                        true, true, true, 1, HASH_M, 1964L, 4, now
                )
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("streamed digest verification");

        // Reject master SHA-256 mismatch
        assertThatThrownBy(() -> HlsUploadedPackageVerification.fromRemoteVerification(
                "kiemlai-hls-priv", manifest,
                new HlsUploadedPackageVerification.RemoteVerificationEvidence(
                        HlsRemoteIntegrityProof.STREAMED_GET_ALL_ARTIFACTS_WITH_EXACT_LISTING,
                        true, true, true, 3, HASH_A, 1964L, 4, now
                )
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SHA-256 mismatch");

        // Reject count / byte mismatch
        assertThatThrownBy(() -> HlsUploadedPackageVerification.fromRemoteVerification(
                "kiemlai-hls-priv", manifest,
                new HlsUploadedPackageVerification.RemoteVerificationEvidence(
                        HlsRemoteIntegrityProof.STREAMED_GET_ALL_ARTIFACTS_WITH_EXACT_LISTING,
                        true, true, true, 3, HASH_M, 9999L, 4, now
                )
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("total bytes mismatch");
    }

    private static HlsArtifactDescriptor descriptor(
            HlsPackageIdentity identity,
            String rel,
            long size,
            ContentHash hash,
            HlsArtifactType type,
            String mime,
            HlsUploadStage stage
    ) {
        return new HlsArtifactDescriptor(
                rel,
                HlsPackageKeyValidator.buildAndValidateObjectKey(identity.basePrefix(), rel),
                size,
                hash,
                type,
                MimeType.of(mime),
                stage
        );
    }
}
