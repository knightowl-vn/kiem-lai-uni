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
        routingService = new MediaStorageRoutingService();
    }

    @Test
    @DisplayName("routes IMAGE to cloudinary and AUDIO to r2")
    void shouldRouteImagesToCloudinaryAndAudioToR2() {
        assertThat(routingService.resolveWriteProvider(MediaType.IMAGE, null))
                .isEqualTo(StorageProviderId.of("cloudinary"));
        assertThat(routingService.resolveWriteProvider(MediaType.IMAGE, "wiki.article.cover"))
                .isEqualTo(StorageProviderId.of("cloudinary"));

        assertThat(routingService.resolveWriteProvider(MediaType.AUDIO, null))
                .isEqualTo(StorageProviderId.of("r2"));
        assertThat(routingService.resolveWriteProvider(MediaType.AUDIO, "novel.chapter.audio"))
                .isEqualTo(StorageProviderId.of("r2"));
    }

    @Test
    @DisplayName("throws StorageException for unsupported media types (VIDEO, DOCUMENT)")
    void shouldThrowForUnsupportedMediaTypes() {
        assertThatThrownBy(() -> routingService.resolveWriteProvider(MediaType.VIDEO, null))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Unsupported MediaType for storage write routing: VIDEO");

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
    }
}
