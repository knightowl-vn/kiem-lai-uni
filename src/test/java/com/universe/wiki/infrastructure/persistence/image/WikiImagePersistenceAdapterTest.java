package com.universe.wiki.infrastructure.persistence.image;

import com.universe.wiki.application.image.WikiImageAsset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiImagePersistenceAdapterTest {

    private static final UUID IMAGE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String CONTENT_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String CLOUDINARY_URL = "https://res.cloudinary.com/demo/image/upload/v123456/kiemlai/wiki/legacy.webp";
    private static final String CLOUDINARY_PUBLIC_ID = "kiemlai/wiki/legacy-public-id";
    private static final Instant CREATED_AT = Instant.parse("2026-08-20T10:00:00Z");

    @Mock
    private SpringDataWikiImageJpaRepository repository;

    private WikiImagePersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WikiImagePersistenceAdapter(repository);
    }

    @Test
    @DisplayName("findByContentHash ánh xạ chính xác bản ghi legacy với publicId và mediaAssetId là null")
    void shouldFindAndMapLegacyImageWithNullMediaAssetId() {
        WikiImageJpaEntity entity = new WikiImageJpaEntity();
        entity.setId(IMAGE_ID.toString());
        entity.setContentHash(CONTENT_HASH);
        entity.setUrl(CLOUDINARY_URL);
        entity.setPublicId(CLOUDINARY_PUBLIC_ID);
        entity.setMediaAssetId(null);
        entity.setSourceContentType("image/png");
        entity.setSizeBytes(2048L);
        entity.setCreatedAt(CREATED_AT);

        when(repository.findByContentHash(CONTENT_HASH)).thenReturn(Optional.of(entity));

        Optional<WikiImageAsset> result = adapter.findByContentHash(CONTENT_HASH);

        assertThat(result).isPresent();
        WikiImageAsset asset = result.get();
        assertThat(asset.id()).isEqualTo(IMAGE_ID);
        assertThat(asset.contentHash()).isEqualTo(CONTENT_HASH);
        assertThat(asset.url()).isEqualTo(CLOUDINARY_URL);
        assertThat(asset.publicId()).isEqualTo(CLOUDINARY_PUBLIC_ID);
        assertThat(asset.mediaAssetId()).isNull();
        assertThat(asset.sourceContentType()).isEqualTo("image/png");
        assertThat(asset.sizeBytes()).isEqualTo(2048L);
        assertThat(asset.createdAt()).isEqualTo(CREATED_AT);

        verify(repository).findByContentHash(CONTENT_HASH);
    }

    @Test
    @DisplayName("findByContentHash ánh xạ chính xác bản ghi Media-backed với UUID mediaAssetId và publicId là null")
    void shouldFindAndMapMediaBackedImageWithMediaAssetIdAndNullPublicId() {
        UUID mediaAssetId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String mediaDeliveryUrl = "/media/assets/" + mediaAssetId + "/content";

        WikiImageJpaEntity entity = new WikiImageJpaEntity();
        entity.setId(IMAGE_ID.toString());
        entity.setContentHash(CONTENT_HASH);
        entity.setUrl(mediaDeliveryUrl);
        entity.setPublicId(null);
        entity.setMediaAssetId(mediaAssetId.toString());
        entity.setSourceContentType("image/webp");
        entity.setSizeBytes(4096L);
        entity.setCreatedAt(CREATED_AT);

        when(repository.findByContentHash(CONTENT_HASH)).thenReturn(Optional.of(entity));

        Optional<WikiImageAsset> result = adapter.findByContentHash(CONTENT_HASH);

        assertThat(result).isPresent();
        WikiImageAsset asset = result.get();
        assertThat(asset.id()).isEqualTo(IMAGE_ID);
        assertThat(asset.contentHash()).isEqualTo(CONTENT_HASH);
        assertThat(asset.url()).isEqualTo(mediaDeliveryUrl);
        assertThat(asset.publicId()).isNull();
        assertThat(asset.mediaAssetId()).isEqualTo(mediaAssetId);
        assertThat(asset.sourceContentType()).isEqualTo("image/webp");
        assertThat(asset.sizeBytes()).isEqualTo(4096L);
        assertThat(asset.createdAt()).isEqualTo(CREATED_AT);

        verify(repository).findByContentHash(CONTENT_HASH);
    }

    @Test
    @DisplayName("save lưu chính xác bản ghi Media-backed với mediaAssetId và publicId là null")
    void shouldSaveMediaBackedImageWithMediaAssetId() {
        UUID mediaAssetId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        String mediaDeliveryUrl = "/media/assets/" + mediaAssetId + "/content";

        WikiImageAsset asset = new WikiImageAsset(
                IMAGE_ID,
                CONTENT_HASH,
                mediaDeliveryUrl,
                null,
                mediaAssetId,
                "image/jpeg",
                1024L,
                CREATED_AT
        );

        adapter.save(asset);

        ArgumentCaptor<WikiImageJpaEntity> captor = ArgumentCaptor.forClass(WikiImageJpaEntity.class);
        verify(repository).save(captor.capture());

        WikiImageJpaEntity savedEntity = captor.getValue();
        assertThat(savedEntity.getId()).isEqualTo(IMAGE_ID.toString());
        assertThat(savedEntity.getContentHash()).isEqualTo(CONTENT_HASH);
        assertThat(savedEntity.getUrl()).isEqualTo(mediaDeliveryUrl);
        assertThat(savedEntity.getPublicId()).isNull();
        assertThat(savedEntity.getMediaAssetId()).isEqualTo(mediaAssetId.toString());
        assertThat(savedEntity.getSourceContentType()).isEqualTo("image/jpeg");
        assertThat(savedEntity.getSizeBytes()).isEqualTo(1024L);
        assertThat(savedEntity.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("save lưu chính xác bản ghi legacy với publicId và mediaAssetId là null")
    void shouldSaveLegacyImageWithPublicIdAndNullMediaAssetId() {
        WikiImageAsset asset = new WikiImageAsset(
                IMAGE_ID,
                CONTENT_HASH,
                CLOUDINARY_URL,
                CLOUDINARY_PUBLIC_ID,
                "image/png",
                2048L,
                CREATED_AT
        );

        adapter.save(asset);

        ArgumentCaptor<WikiImageJpaEntity> captor = ArgumentCaptor.forClass(WikiImageJpaEntity.class);
        verify(repository).save(captor.capture());

        WikiImageJpaEntity savedEntity = captor.getValue();
        assertThat(savedEntity.getId()).isEqualTo(IMAGE_ID.toString());
        assertThat(savedEntity.getContentHash()).isEqualTo(CONTENT_HASH);
        assertThat(savedEntity.getUrl()).isEqualTo(CLOUDINARY_URL);
        assertThat(savedEntity.getPublicId()).isEqualTo(CLOUDINARY_PUBLIC_ID);
        assertThat(savedEntity.getMediaAssetId()).isNull();
        assertThat(savedEntity.getSourceContentType()).isEqualTo("image/png");
        assertThat(savedEntity.getSizeBytes()).isEqualTo(2048L);
        assertThat(savedEntity.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("save ném IllegalArgumentException khi asset là null")
    void shouldThrowExceptionWhenSavingNullAsset() {
        assertThatThrownBy(() -> adapter.save(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("deleteById gọi repository deleteById")
    void shouldCallRepositoryDeleteById() {
        adapter.deleteById(IMAGE_ID);
        verify(repository).deleteById(IMAGE_ID.toString());
    }

    @Test
    @DisplayName("findOrphanImages ánh xạ danh sách entities sang DTOs")
    void shouldFindOrphanImagesAndMap() {
        WikiImageJpaEntity entity = new WikiImageJpaEntity();
        entity.setId(IMAGE_ID.toString());
        entity.setContentHash(CONTENT_HASH);
        entity.setUrl(CLOUDINARY_URL);
        entity.setPublicId(CLOUDINARY_PUBLIC_ID);
        entity.setMediaAssetId(null);
        entity.setSourceContentType("image/png");
        entity.setSizeBytes(2048L);
        entity.setCreatedAt(CREATED_AT);

        when(repository.findOrphanImages()).thenReturn(List.of(entity));

        List<WikiImageAsset> orphans = adapter.findOrphanImages();
        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).id()).isEqualTo(IMAGE_ID);
        assertThat(orphans.get(0).mediaAssetId()).isNull();
    }
}
