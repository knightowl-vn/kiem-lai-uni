package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.MediaImageVariant;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaImageVariantPersistenceAdapterTest {

    @Mock
    private SpringDataMediaImageVariantJpaRepository repository;

    @InjectMocks
    private MediaImageVariantPersistenceAdapter adapter;

    private UUID variantId;
    private UUID versionId;
    private Instant createdAt;

    @BeforeEach
    void setUp() {
        variantId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        createdAt = Instant.parse("2026-09-03T10:00:00Z");
    }

    @Test
    @DisplayName("save maps physical domain variant to JPA entity correctly")
    void shouldSaveMediaImageVariant() {
        MediaImageVariant variant = MediaImageVariant.create(
                variantId,
                versionId,
                ImageVariantSpec.of(300),
                StorageLocation.of("local", "objects/variants/w300.png"),
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/png"),
                15000L,
                300,
                450,
                createdAt
        );

        when(repository.save(any(MediaImageVariantJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MediaImageVariant result = adapter.save(variant);

        ArgumentCaptor<MediaImageVariantJpaEntity> captor = ArgumentCaptor.forClass(MediaImageVariantJpaEntity.class);
        verify(repository).save(captor.capture());

        MediaImageVariantJpaEntity captured = captor.getValue();
        assertThat(captured.getId()).isEqualTo(variantId.toString());
        assertThat(captured.getVersionId()).isEqualTo(versionId.toString());
        assertThat(captured.getVariantKey()).isEqualTo("w300");
        assertThat(captured.getTargetWidth()).isEqualTo(300);
        assertThat(captured.getStorageProviderId()).isEqualTo("local");
        assertThat(captured.getStorageKey()).isEqualTo("objects/variants/w300.png");
        assertThat(captured.getPublicUrl()).isNull();
        assertThat(captured.getContentHash()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(captured.getMimeType()).isEqualTo("image/png");
        assertThat(captured.getSizeBytes()).isEqualTo(15000L);
        assertThat(captured.getWidth()).isEqualTo(300);
        assertThat(captured.getHeight()).isEqualTo(450);
        assertThat(captured.getCreatedAt()).isEqualTo(createdAt);
        assertThat(captured.isNew()).isTrue();

        assertThat(result.getId()).isEqualTo(variantId);
        assertThat(result.getVersionId()).isEqualTo(versionId);
        assertThat(result.getVariantKey()).isEqualTo("w300");
        assertThat(result.isPhysical()).isTrue();
    }

    @Test
    @DisplayName("findByVersionIdAndVariantKey maps JPA entity to physical domain variant correctly")
    void shouldFindByVersionIdAndVariantKey() {
        MediaImageVariantJpaEntity entity = new MediaImageVariantJpaEntity();
        entity.setId(variantId.toString());
        entity.setVersionId(versionId.toString());
        entity.setVariantKey("w300");
        entity.setTargetWidth(300);
        entity.setStorageProviderId("local");
        entity.setStorageKey("objects/variants/w300.png");
        entity.setContentHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        entity.setMimeType("image/png");
        entity.setSizeBytes(15000L);
        entity.setWidth(300);
        entity.setHeight(450);
        entity.setCreatedAt(createdAt);

        when(repository.findByVersionIdAndVariantKey(versionId.toString(), "w300")).thenReturn(Optional.of(entity));

        Optional<MediaImageVariant> result = adapter.findByVersionIdAndVariantKey(versionId, "w300");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(variantId);
        assertThat(result.get().getVersionId()).isEqualTo(versionId);
        assertThat(result.get().getVariantKey()).isEqualTo("w300");
        assertThat(result.get().getTargetWidth()).isEqualTo(300);
        assertThat(result.get().getWidth()).isEqualTo(300);
        assertThat(result.get().getHeight()).isEqualTo(450);
        assertThat(result.get().isPhysical()).isTrue();
        assertThat(result.get().getStorageLocation()).isEqualTo(StorageLocation.of("local", "objects/variants/w300.png"));
    }

    @Test
    @DisplayName("save maps external virtual variant to JPA entity with NULL storage provider/key and publicUrl")
    void shouldSaveExternalVirtualVariant() {
        String publicUrl = "https://res.cloudinary.com/test/image/upload/c_scale,w_400/kiemlai/wiki/covers/great-ruler.webp";
        MediaImageVariant variant = MediaImageVariant.createExternal(
                variantId,
                versionId,
                ImageVariantSpec.of(400),
                publicUrl,
                MimeType.of("image/webp"),
                createdAt
        );

        when(repository.save(any(MediaImageVariantJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MediaImageVariant result = adapter.save(variant);

        ArgumentCaptor<MediaImageVariantJpaEntity> captor = ArgumentCaptor.forClass(MediaImageVariantJpaEntity.class);
        verify(repository).save(captor.capture());

        MediaImageVariantJpaEntity captured = captor.getValue();
        assertThat(captured.getId()).isEqualTo(variantId.toString());
        assertThat(captured.getStorageProviderId()).isNull();
        assertThat(captured.getStorageKey()).isNull();
        assertThat(captured.getPublicUrl()).isEqualTo(publicUrl);
        assertThat(captured.getContentHash()).isNull();
        assertThat(captured.getSizeBytes()).isNull();
        assertThat(captured.getWidth()).isNull();
        assertThat(captured.getHeight()).isNull();

        assertThat(result.getPublicUrl()).isEqualTo(publicUrl);
        assertThat(result.getStorageLocation()).isNull();
        assertThat(result.isVirtual()).isTrue();
    }

    @Test
    @DisplayName("findByVersionIdAndVariantKey maps external virtual JPA entity (null storage location) to domain correctly")
    void shouldFindExternalVirtualVariantByVersionIdAndVariantKey() {
        String publicUrl = "https://res.cloudinary.com/test/image/upload/c_scale,w_400/kiemlai/wiki/covers/great-ruler.webp";
        MediaImageVariantJpaEntity entity = new MediaImageVariantJpaEntity();
        entity.setId(variantId.toString());
        entity.setVersionId(versionId.toString());
        entity.setVariantKey("w400");
        entity.setTargetWidth(400);
        entity.setStorageProviderId(null);
        entity.setStorageKey(null);
        entity.setPublicUrl(publicUrl);
        entity.setMimeType("image/webp");
        entity.setCreatedAt(createdAt);

        when(repository.findByVersionIdAndVariantKey(versionId.toString(), "w400")).thenReturn(Optional.of(entity));

        Optional<MediaImageVariant> result = adapter.findByVersionIdAndVariantKey(versionId, "w400");

        assertThat(result).isPresent();
        MediaImageVariant loaded = result.get();
        assertThat(loaded.getId()).isEqualTo(variantId);
        assertThat(loaded.getStorageLocation()).isNull();
        assertThat(loaded.getPublicUrl()).isEqualTo(publicUrl);
        assertThat(loaded.hasPublicUrl()).isTrue();
        assertThat(loaded.isVirtual()).isTrue();
        assertThat(loaded.isPhysical()).isFalse();
        assertThat(loaded.getContentHash()).isNull();
        assertThat(loaded.getSizeBytes()).isNull();
    }

    @Test
    @DisplayName("fails closed with IllegalStateException when entity has provider without key")
    void shouldFailClosedWhenProviderPresentWithoutKey() {
        MediaImageVariantJpaEntity entity = new MediaImageVariantJpaEntity();
        entity.setId(variantId.toString());
        entity.setVersionId(versionId.toString());
        entity.setVariantKey("w300");
        entity.setTargetWidth(300);
        entity.setStorageProviderId("local");
        entity.setStorageKey(null);
        entity.setPublicUrl(null);
        entity.setContentHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        entity.setMimeType("image/png");
        entity.setSizeBytes(15000L);
        entity.setWidth(300);
        entity.setHeight(450);
        entity.setCreatedAt(createdAt);

        when(repository.findByVersionIdAndVariantKey(versionId.toString(), "w300")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> adapter.findByVersionIdAndVariantKey(versionId, "w300"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Corrupt media image variant storage location: provider/key must both be present or both absent");
    }

    @Test
    @DisplayName("fails closed with IllegalStateException when entity has key without provider")
    void shouldFailClosedWhenKeyPresentWithoutProvider() {
        MediaImageVariantJpaEntity entity = new MediaImageVariantJpaEntity();
        entity.setId(variantId.toString());
        entity.setVersionId(versionId.toString());
        entity.setVariantKey("w300");
        entity.setTargetWidth(300);
        entity.setStorageProviderId(null);
        entity.setStorageKey("objects/variants/w300.png");
        entity.setPublicUrl(null);
        entity.setContentHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        entity.setMimeType("image/png");
        entity.setSizeBytes(15000L);
        entity.setWidth(300);
        entity.setHeight(450);
        entity.setCreatedAt(createdAt);

        when(repository.findByVersionIdAndVariantKey(versionId.toString(), "w300")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> adapter.findByVersionIdAndVariantKey(versionId, "w300"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Corrupt media image variant storage location: provider/key must both be present or both absent");
    }

    @Test
    @DisplayName("existsByVersionIdAndVariantKey delegates to repository")
    void shouldCheckExistenceByVersionIdAndVariantKey() {
        when(repository.existsByVersionIdAndVariantKey(versionId.toString(), "w300")).thenReturn(true);

        boolean exists = adapter.existsByVersionIdAndVariantKey(versionId, "w300");

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("existsByStorageLocation delegates to repository")
    void shouldCheckExistenceByStorageLocation() {
        StorageLocation location = StorageLocation.of("local", "objects/variants/w300.png");
        when(repository.existsByStorageProviderIdAndStorageKey("local", "objects/variants/w300.png")).thenReturn(true);

        boolean exists = adapter.existsByStorageLocation(location);

        assertThat(exists).isTrue();
    }
}
