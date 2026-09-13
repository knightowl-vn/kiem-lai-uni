package com.universe.media.infrastructure.persistence;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageLocation;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAssetVersionPersistenceAdapterTest {

    @Mock
    private SpringDataMediaAssetVersionJpaRepository repository;

    @InjectMocks
    private MediaAssetVersionPersistenceAdapter adapter;

    private UUID versionId;
    private UUID assetId;
    private Instant createdAt;

    @BeforeEach
    void setUp() {
        versionId = UUID.randomUUID();
        assetId = UUID.randomUUID();
        createdAt = Instant.parse("2026-08-01T10:00:00Z");
    }

    @Test
    @DisplayName("save maps domain version to a NEW JPA entity and calls saveAndFlush without prior lookup")
    void shouldSaveVersionDirectlyWithoutPriorLookup() {
        MediaAssetVersion domainVersion = MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                StorageLocation.of("cloudinary", "covers/cover-1.webp"),
                "https://cdn.universe.local/cover.webp",
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/webp"),
                1024L,
                "cover.webp",
                createdAt
        );

        when(repository.saveAndFlush(any(MediaAssetVersionJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MediaAssetVersion result = adapter.save(domainVersion);

        // Verify saveAndFlush was called with correct mapping
        ArgumentCaptor<MediaAssetVersionJpaEntity> captor = ArgumentCaptor.forClass(MediaAssetVersionJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());

        MediaAssetVersionJpaEntity captured = captor.getValue();
        assertThat(captured.getId()).isEqualTo(versionId.toString());
        assertThat(captured.getAssetId()).isEqualTo(assetId.toString());
        assertThat(captured.getVersionNumber()).isEqualTo(1);
        assertThat(captured.getStorageProviderId()).isEqualTo("cloudinary");
        assertThat(captured.getStorageKey()).isEqualTo("covers/cover-1.webp");
        assertThat(captured.getPublicUrl()).isEqualTo("https://cdn.universe.local/cover.webp");
        assertThat(captured.getContentHash()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(captured.getMimeType()).isEqualTo("image/webp");
        assertThat(captured.getSizeBytes()).isEqualTo(1024L);
        assertThat(captured.getOriginalFilename()).isEqualTo("cover.webp");
        assertThat(captured.getCreatedAt()).isEqualTo(createdAt);
        assertThat(captured.isNew()).isTrue();

        // Verify NO existence lookup was executed before save
        verify(repository, never()).findById(any());
        verify(repository, never()).findByAssetIdAndVersionNumber(any(), any(int.class));

        assertThat(result.getId()).isEqualTo(versionId);
        assertThat(result.getAssetId()).isEqualTo(assetId);
        assertThat(result.getVersionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("save translates uq_media_asset_versions_provider_key constraint violation to DuplicateStorageLocationException")
    void shouldThrowDuplicateStorageLocationExceptionWhenProviderKeyConstraintViolated() {
        StorageLocation location = StorageLocation.of("local", "objects/collision.png");
        MediaAssetVersion domainVersion = MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                location,
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/png"),
                100L,
                "collision.png",
                createdAt
        );

        SQLException sqlEx = new SQLException("Duplicate entry 'local-objects/collision.png' for key 'media_asset_versions.uq_media_asset_versions_provider_key'", "23000", 1062);
        ConstraintViolationException cve = new ConstraintViolationException(
                "could not execute statement",
                sqlEx,
                "uq_media_asset_versions_provider_key"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.saveAndFlush(any(MediaAssetVersionJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(domainVersion))
                .isInstanceOf(DuplicateStorageLocationException.class)
                .hasMessageContaining(location.providerId().value())
                .hasMessageContaining(location.key().value());
    }

    @Test
    @DisplayName("save does NOT translate uq_media_asset_versions_asset_version constraint violation")
    void shouldReThrowDataIntegrityViolationExceptionWhenAssetVersionConstraintViolated() {
        StorageLocation location = StorageLocation.of("local", "objects/version-collision.png");
        MediaAssetVersion domainVersion = MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                location,
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/png"),
                100L,
                "version-collision.png",
                createdAt
        );

        SQLException sqlEx = new SQLException("Duplicate entry '11111111-1' for key 'media_asset_versions.uq_media_asset_versions_asset_version'", "23000", 1062);
        ConstraintViolationException cve = new ConstraintViolationException(
                "could not execute statement",
                sqlEx,
                "uq_media_asset_versions_asset_version"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.saveAndFlush(any(MediaAssetVersionJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(domainVersion))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateStorageLocationException.class);
    }

    @Test
    @DisplayName("save does NOT translate unrelated foreign key constraint violation")
    void shouldReThrowDataIntegrityViolationExceptionWhenUnrelatedConstraintViolated() {
        StorageLocation location = StorageLocation.of("local", "objects/fk-fail.png");
        MediaAssetVersion domainVersion = MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                location,
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/png"),
                100L,
                "fk-fail.png",
                createdAt
        );

        SQLException sqlEx = new SQLException("Cannot add or update a child row: a foreign key constraint fails", "23000", 1452);
        ConstraintViolationException cve = new ConstraintViolationException(
                "could not execute statement",
                sqlEx,
                "fk_media_asset_versions_asset"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("FK violation", cve);

        when(repository.saveAndFlush(any(MediaAssetVersionJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(domainVersion))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateStorageLocationException.class);
    }

    @Test
    @DisplayName("findByAssetIdAndVersionNumber maps JPA entity to domain correctly")
    void shouldFindByAssetIdAndVersionNumber() {
        MediaAssetVersionJpaEntity entity = new MediaAssetVersionJpaEntity();
        entity.setId(versionId.toString());
        entity.setAssetId(assetId.toString());
        entity.setVersionNumber(1);
        entity.setStorageProviderId("s3");
        entity.setStorageKey("avatars/user-1.png");
        entity.setPublicUrl(null);
        entity.setContentHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        entity.setMimeType("image/png");
        entity.setSizeBytes(2048L);
        entity.setOriginalFilename("user-1.png");
        entity.setCreatedAt(createdAt);

        when(repository.findByAssetIdAndVersionNumber(assetId.toString(), 1))
                .thenReturn(Optional.of(entity));

        Optional<MediaAssetVersion> opt = adapter.findByAssetIdAndVersionNumber(assetId, 1);

        assertThat(opt).isPresent();
        MediaAssetVersion version = opt.get();
        assertThat(version.getId()).isEqualTo(versionId);
        assertThat(version.getAssetId()).isEqualTo(assetId);
        assertThat(version.getVersionNumber()).isEqualTo(1);
        assertThat(version.getStorageLocation().providerId().value()).isEqualTo("s3");
        assertThat(version.getStorageLocation().key().value()).isEqualTo("avatars/user-1.png");
        assertThat(version.getPublicUrl()).isNull();
        assertThat(version.getContentHash().value()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(version.getMimeType().value()).isEqualTo("image/png");
        assertThat(version.getSizeBytes()).isEqualTo(2048L);
        assertThat(version.getOriginalFilename()).isEqualTo("user-1.png");
        assertThat(version.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("findByAssetIdAndVersionNumber throws on null asset ID")
    void shouldThrowForNullAssetId() {
        assertThatThrownBy(() -> adapter.findByAssetIdAndVersionNumber(null, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Media asset ID cannot be null.");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("existsByStorageLocation forwards exact provider and opaque key text")
    void shouldForwardExactStorageLocation() {
        StorageLocation location = StorageLocation.of("cloudinary", "raw/path/with Special_Chars.PNG");

        when(repository.existsByStorageProviderIdAndStorageKey("cloudinary", "raw/path/with Special_Chars.PNG"))
                .thenReturn(true);

        boolean exists = adapter.existsByStorageLocation(location);

        assertThat(exists).isTrue();
        verify(repository).existsByStorageProviderIdAndStorageKey("cloudinary", "raw/path/with Special_Chars.PNG");
    }

    @Test
    @DisplayName("existsByStorageLocation throws on null location")
    void shouldThrowForNullStorageLocation() {
        assertThatThrownBy(() -> adapter.existsByStorageLocation(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Storage location cannot be null.");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("findByContentHash maps all results and preserves repository order")
    void shouldFindByContentHashAndPreserveOrder() {
        ContentHash hash = ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        MediaAssetVersionJpaEntity entity1 = new MediaAssetVersionJpaEntity();
        entity1.setId(UUID.randomUUID().toString());
        entity1.setAssetId(assetId.toString());
        entity1.setVersionNumber(1);
        entity1.setStorageProviderId("cloudinary");
        entity1.setStorageKey("covers/1.webp");
        entity1.setContentHash(hash.value());
        entity1.setMimeType("image/webp");
        entity1.setSizeBytes(100L);
        entity1.setOriginalFilename("1.webp");
        entity1.setCreatedAt(createdAt);

        MediaAssetVersionJpaEntity entity2 = new MediaAssetVersionJpaEntity();
        entity2.setId(UUID.randomUUID().toString());
        entity2.setAssetId(UUID.randomUUID().toString());
        entity2.setVersionNumber(2);
        entity2.setStorageProviderId("s3");
        entity2.setStorageKey("covers/2.webp");
        entity2.setContentHash(hash.value());
        entity2.setMimeType("image/webp");
        entity2.setSizeBytes(200L);
        entity2.setOriginalFilename("2.webp");
        entity2.setCreatedAt(createdAt.plusSeconds(60));

        when(repository.findByContentHash(hash.value())).thenReturn(List.of(entity1, entity2));

        List<MediaAssetVersion> results = adapter.findByContentHash(hash);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getId()).isEqualTo(UUID.fromString(entity1.getId()));
        assertThat(results.get(1).getId()).isEqualTo(UUID.fromString(entity2.getId()));
    }

    @Test
    @DisplayName("findByContentHash throws on null contentHash")
    void shouldThrowForNullContentHash() {
        assertThatThrownBy(() -> adapter.findByContentHash(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Content hash cannot be null.");
        verifyNoInteractions(repository);
    }
}
