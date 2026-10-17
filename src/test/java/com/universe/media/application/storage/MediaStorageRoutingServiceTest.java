package com.universe.media.application.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaStorageRoutingServiceTest {

    private MediaStorageRoutingService routingService;

    @BeforeEach
    void setUp() {
        routingService = new MediaStorageRoutingService("kiemlai-video-test", "kiemlai-audio-test");
    }

    @Test
    @DisplayName("routes IMAGE to cloudinary, AUDIO to r2, and VIDEO to b2")
    void shouldRouteImagesToCloudinaryAudioToR2AndVideoToB2() {
        assertThat(routingService.resolveWriteProvider(MediaType.IMAGE, null))
                .isEqualTo(StorageProviderId.of("cloudinary"));
        assertThat(routingService.resolveWriteProvider(MediaType.IMAGE, "wiki.article.cover"))
                .isEqualTo(StorageProviderId.of("cloudinary"));

        assertThat(routingService.resolveWriteProvider(MediaType.AUDIO, null))
                .isEqualTo(StorageProviderId.of("r2"));
        assertThat(routingService.resolveWriteProvider(MediaType.AUDIO, "novel.chapter.audio"))
                .isEqualTo(StorageProviderId.of("r2"));

        assertThat(routingService.resolveWriteProvider(MediaType.VIDEO, null))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(routingService.resolveWriteProvider(MediaType.VIDEO, "screen.entry"))
                .isEqualTo(StorageProviderId.of("b2"));
    }

    @Test
    @DisplayName("throws StorageException for unsupported media types (DOCUMENT)")
    void shouldThrowForUnsupportedMediaTypes() {
        assertThatThrownBy(() -> routingService.resolveWriteProvider(MediaType.DOCUMENT, null))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Unsupported MediaType for storage write routing: DOCUMENT");
    }

    @Test
    @DisplayName("generates unique physical storage keys with diagnostic prefix for version replacements")
    void shouldGenerateUniqueStorageKeysForVersions() {
        UUID assetId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        // Wiki cover version replacement
        StorageKey wikiCoverKey1 = routingService.generateStorageKey(
                MediaType.IMAGE,
                "wiki.article.cover",
                assetId,
                1
        );
        StorageKey wikiCoverKey2 = routingService.generateStorageKey(
                MediaType.IMAGE,
                "wiki.article.cover",
                assetId,
                1
        );
        assertThat(wikiCoverKey1.value()).startsWith("kiemlai/wiki/covers/11111111-1111-1111-1111-111111111111_v1_");
        assertThat(wikiCoverKey2.value()).startsWith("kiemlai/wiki/covers/11111111-1111-1111-1111-111111111111_v1_");
        assertThat(wikiCoverKey1).isNotEqualTo(wikiCoverKey2);

        // Avatar version replacement
        StorageKey avatarKey1 = routingService.generateStorageKey(
                MediaType.IMAGE,
                "identity.avatar",
                assetId,
                2
        );
        StorageKey avatarKey2 = routingService.generateStorageKey(
                MediaType.IMAGE,
                "identity.avatar",
                assetId,
                2
        );
        assertThat(avatarKey1.value()).startsWith("kiemlai/avatars/11111111-1111-1111-1111-111111111111_v2_");
        assertThat(avatarKey2.value()).startsWith("kiemlai/avatars/11111111-1111-1111-1111-111111111111_v2_");
        assertThat(avatarKey1).isNotEqualTo(avatarKey2);

        // Generic image version replacement
        StorageKey imageKey1 = routingService.generateStorageKey(
                MediaType.IMAGE,
                null,
                assetId,
                3
        );
        StorageKey imageKey2 = routingService.generateStorageKey(
                MediaType.IMAGE,
                null,
                assetId,
                3
        );
        assertThat(imageKey1.value()).startsWith("kiemlai/images/11111111-1111-1111-1111-111111111111_v3_");
        assertThat(imageKey2.value()).startsWith("kiemlai/images/11111111-1111-1111-1111-111111111111_v3_");
        assertThat(imageKey1).isNotEqualTo(imageKey2);
    }

    @Test
    @DisplayName("generates unique physical storage keys in consistent namespaces for initial uploads")
    void shouldGenerateUniqueStorageKeysForInitialUploads() {
        // Wiki cover initial
        StorageKey wikiCoverInitial1 = routingService.generateStorageKey(
                MediaType.IMAGE,
                "wiki.article.cover",
                null,
                1
        );
        StorageKey wikiCoverInitial2 = routingService.generateStorageKey(
                MediaType.IMAGE,
                "wiki.article.cover",
                null,
                1
        );
        assertThat(wikiCoverInitial1.value()).startsWith("kiemlai/wiki/covers/");
        assertThat(wikiCoverInitial2.value()).startsWith("kiemlai/wiki/covers/");
        assertThat(wikiCoverInitial1).isNotEqualTo(wikiCoverInitial2);

        // Generic image initial upload stays in kiemlai/images namespace
        StorageKey imageInitial1 = routingService.generateStorageKey(
                MediaType.IMAGE,
                null,
                null,
                1
        );
        StorageKey imageInitial2 = routingService.generateStorageKey(
                MediaType.IMAGE,
                null,
                null,
                1
        );
        assertThat(imageInitial1.value()).startsWith("kiemlai/images/");
        assertThat(imageInitial2.value()).startsWith("kiemlai/images/");
        assertThat(imageInitial1).isNotEqualTo(imageInitial2);
    }

    @Test
    @DisplayName("routes domain-specific client tags (wiki.*, novel.*) into their respective namespaces")
    void shouldRouteDomainSpecificClientTags() {
        UUID assetId = UUID.randomUUID();

        // Wiki non-cover image
        StorageKey wikiImageKey = routingService.generateStorageKey(
                MediaType.IMAGE,
                "wiki.illustration",
                assetId,
                1
        );
        assertThat(wikiImageKey.value()).startsWith("kiemlai/wiki/" + assetId + "_v1_");

        // Novel image
        StorageKey novelImageKey = routingService.generateStorageKey(
                MediaType.IMAGE,
                "novel.cover",
                assetId,
                1
        );
        assertThat(novelImageKey.value()).startsWith("kiemlai/novel/" + assetId + "_v1_");

        // Screen poster without slug hint
        StorageKey screenPosterKey = routingService.generateStorageKey(
                MediaType.IMAGE,
                "screen.poster",
                assetId,
                1
        );
        assertThat(screenPosterKey.value()).startsWith("kiemlai/screen/posters/" + assetId + "_v1_");

        // Screen poster with slug hint
        StorageKey screenPosterWithSlugKey = routingService.generateStorageKey(
                MediaType.IMAGE,
                "screen.poster:kiem-lai-phan-1",
                assetId,
                1
        );
        assertThat(screenPosterWithSlugKey.value()).startsWith("kiemlai/screen/posters/kiem-lai-phan-1-" + assetId + "_v1_");

        // Screen backdrop with slug hint
        StorageKey screenBackdropKey = routingService.generateStorageKey(
                MediaType.IMAGE,
                "screen.backdrop:kiem-lai-phan-1",
                assetId,
                1
        );
        assertThat(screenBackdropKey.value()).startsWith("kiemlai/screen/backdrops/kiem-lai-phan-1-" + assetId + "_v1_");

        // Screen generic tag
        StorageKey screenGenericKey = routingService.generateStorageKey(
                MediaType.IMAGE,
                "screen.banner",
                assetId,
                1
        );
        assertThat(screenGenericKey.value()).startsWith("kiemlai/screen/" + assetId + "_v1_");

        // Slug hint length truncation (> 40 chars)
        String veryLongSlug = "this-is-a-very-long-screen-title-slug-that-exceeds-forty-characters";
        StorageKey longSlugKey = routingService.generateStorageKey(
                MediaType.IMAGE,
                "screen.poster:" + veryLongSlug,
                assetId,
                1
        );
        String prefix = veryLongSlug.substring(0, 40).replaceAll("-+$", "");
        assertThat(longSlugKey.value()).startsWith("kiemlai/screen/posters/" + prefix + "-" + assetId + "_v1_");
    }

    @Test
    @DisplayName("generates storage keys in objects folder for VIDEO assets")
    void shouldGenerateStorageKeysForVideo() {
        UUID assetId = UUID.randomUUID();

        StorageKey initialKey = routingService.generateStorageKey(
                MediaType.VIDEO,
                null,
                null,
                1
        );
        assertThat(initialKey.value()).startsWith("objects/");

        StorageKey versionKey = routingService.generateStorageKey(
                MediaType.VIDEO,
                "screen.entry",
                assetId,
                2
        );
        assertThat(versionKey.value()).startsWith("objects/" + assetId + "_v2_");
    }

    @Test
    @DisplayName("resolves bucket name correctly for B2, R2 and bucketless providers")
    void shouldResolveBucketNameCorrectly() {
        assertThat(routingService.resolveBucketName(MediaStorageRoutingService.B2_PROVIDER))
                .isEqualTo("kiemlai-video-test");
        assertThat(routingService.resolveBucketName(MediaStorageRoutingService.R2_PROVIDER))
                .isEqualTo("kiemlai-audio-test");
        assertThat(routingService.resolveBucketName(MediaStorageRoutingService.CLOUDINARY_PROVIDER))
                .isEqualTo("NONE");
    }

    @Test
    @DisplayName("fails closed when B2 bucket is missing, blank, or sentinel")
    void shouldFailClosedWhenB2BucketIsInvalid() {
        MediaStorageRoutingService nullB2Service = new MediaStorageRoutingService(null, "audio-bucket");
        assertThatThrownBy(() -> nullB2Service.resolveBucketName(MediaStorageRoutingService.B2_PROVIDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Storage provider 'b2' requires an authentic configured bucket");

        MediaStorageRoutingService blankB2Service = new MediaStorageRoutingService("   ", "audio-bucket");
        assertThatThrownBy(() -> blankB2Service.resolveBucketName(MediaStorageRoutingService.B2_PROVIDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Storage provider 'b2' requires an authentic configured bucket");

        MediaStorageRoutingService sentinelB2Service = new MediaStorageRoutingService("NONE", "audio-bucket");
        assertThatThrownBy(() -> sentinelB2Service.resolveBucketName(MediaStorageRoutingService.B2_PROVIDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Storage provider 'b2' requires an authentic configured bucket");
    }

    @Test
    @DisplayName("does not extract slug prefix for non-Screen client tags containing colons")
    void shouldNotExtractSlugPrefixForNonScreenClientTags() {
        UUID assetId = UUID.randomUUID();

        StorageKey wikiKey = routingService.generateStorageKey(
                MediaType.IMAGE,
                "wiki.article:some-slug",
                assetId,
                1
        );
        assertThat(wikiKey.value()).startsWith("kiemlai/wiki/" + assetId + "_v1_");
        assertThat(wikiKey.value()).doesNotContain("some-slug");

        StorageKey novelKey = routingService.generateStorageKey(
                MediaType.IMAGE,
                "novel.chapter:cover",
                assetId,
                1
        );
        assertThat(novelKey.value()).startsWith("kiemlai/novel/" + assetId + "_v1_");
        assertThat(novelKey.value()).doesNotContain("cover");
    }
}
