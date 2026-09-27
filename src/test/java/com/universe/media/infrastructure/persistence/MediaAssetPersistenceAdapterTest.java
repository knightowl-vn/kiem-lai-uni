package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAssetPersistenceAdapterTest {

    @Mock
    private SpringDataMediaAssetJpaRepository repository;

    @InjectMocks
    private MediaAssetPersistenceAdapter adapter;

    private UUID assetId;
    private Instant createdAt;
    private Instant updatedAt;

    @BeforeEach
    void setUp() {
        assetId = UUID.randomUUID();
        createdAt = Instant.parse("2026-08-01T10:00:00Z");
        updatedAt = Instant.parse("2026-08-01T10:00:00Z");
    }

    @Test
    @DisplayName("findById maps existing JPA entity to domain MediaAsset correctly")
    void shouldMapExistingEntityToDomain() {
        MediaAssetJpaEntity entity = new MediaAssetJpaEntity();
        entity.setId(assetId.toString());
        entity.setMediaType("IMAGE");
        entity.setVisibility("PUBLIC");
        entity.setStatus("ACTIVE");
        entity.setCurrentVersionNumber(2);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);
        entity.setPersistenceVersion(1L);

        when(repository.findById(assetId.toString())).thenReturn(Optional.of(entity));

        Optional<MediaAsset> optAsset = adapter.findById(assetId);

        assertThat(optAsset).isPresent();
        MediaAsset asset = optAsset.get();
        assertThat(asset.getId()).isEqualTo(assetId);
        assertThat(asset.getMediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(asset.getVisibility()).isEqualTo(MediaVisibility.PUBLIC);
        assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(asset.getCurrentVersionNumber()).isEqualTo(2);
        assertThat(asset.getCreatedAt()).isEqualTo(createdAt);
        assertThat(asset.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("findById returns Optional.empty() when entity is missing and throws on null ID")
    void shouldHandleMissingAndNullId() {
        assertThatThrownBy(() -> adapter.findById(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Media asset ID cannot be null.");
        verifyNoInteractions(repository);

        when(repository.findById(assetId.toString())).thenReturn(Optional.empty());
        assertThat(adapter.findById(assetId)).isEmpty();
    }

    @Test
    @DisplayName("save for new asset maps to new JPA entity with persistenceVersion unset")
    void shouldSaveNewAssetWithUnsetPersistenceVersion() {
        MediaAsset domainAsset = MediaAsset.registerInitial(
                assetId,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                createdAt
        );

        when(repository.findById(assetId.toString())).thenReturn(Optional.empty());
        when(repository.save(any(MediaAssetJpaEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MediaAsset result = adapter.save(domainAsset);

        ArgumentCaptor<MediaAssetJpaEntity> captor = ArgumentCaptor.forClass(MediaAssetJpaEntity.class);
        verify(repository).save(captor.capture());

        MediaAssetJpaEntity captured = captor.getValue();
        assertThat(captured.getId()).isEqualTo(assetId.toString());
        assertThat(captured.getMediaType()).isEqualTo("IMAGE");
        assertThat(captured.getVisibility()).isEqualTo("PUBLIC");
        assertThat(captured.getStatus()).isEqualTo("ACTIVE");
        assertThat(captured.getCurrentVersionNumber()).isEqualTo(1);
        assertThat(captured.getCreatedAt()).isEqualTo(createdAt);
        assertThat(captured.getUpdatedAt()).isEqualTo(createdAt);
        assertThat(captured.getPersistenceVersion()).isNull();

        assertThat(result.getId()).isEqualTo(assetId);
        assertThat(result.getMediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(result.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
    }

    @Test
    @DisplayName("save for existing asset mutates mutable fields while preserving mediaType, createdAt, persistenceVersion")
    void shouldSaveExistingAssetPreservingImmutableAndConcurrencyFields() {
        MediaAssetJpaEntity existingEntity = new MediaAssetJpaEntity();
        existingEntity.setId(assetId.toString());
        existingEntity.setMediaType("IMAGE");
        existingEntity.setVisibility("PUBLIC");
        existingEntity.setStatus("ACTIVE");
        existingEntity.setCurrentVersionNumber(1);
        existingEntity.setCreatedAt(createdAt);
        existingEntity.setUpdatedAt(updatedAt);
        existingEntity.setPersistenceVersion(5L);

        when(repository.findById(assetId.toString())).thenReturn(Optional.of(existingEntity));
        when(repository.save(existingEntity)).thenReturn(existingEntity);

        Instant differentCreatedAt = Instant.parse("2020-01-01T00:00:00Z");
        Instant mutatedTime = Instant.parse("2026-08-01T12:00:00Z");
        // Domain asset with different mediaType (DOCUMENT) and different createdAt to prove adapter preservation
        MediaAsset domainAsset = MediaAsset.rehydrate(
                assetId,
                MediaType.DOCUMENT,
                MediaVisibility.PRIVATE,
                MediaAssetStatus.ARCHIVED,
                2,
                differentCreatedAt,
                mutatedTime
        );

        MediaAsset result = adapter.save(domainAsset);

        verify(repository).save(existingEntity);
        assertThat(existingEntity.getId()).isEqualTo(assetId.toString());
        assertThat(existingEntity.getMediaType()).isEqualTo("IMAGE"); // preserved from DB entity, not overridden by DOCUMENT
        assertThat(existingEntity.getCreatedAt()).isEqualTo(createdAt); // preserved from DB entity, not overridden by 2020 timestamp
        assertThat(existingEntity.getPersistenceVersion()).isEqualTo(5L); // preserved

        assertThat(existingEntity.getVisibility()).isEqualTo("PRIVATE"); // mutated
        assertThat(existingEntity.getStatus()).isEqualTo("ARCHIVED"); // mutated
        assertThat(existingEntity.getCurrentVersionNumber()).isEqualTo(2); // mutated
        assertThat(existingEntity.getUpdatedAt()).isEqualTo(mutatedTime); // mutated

        assertThat(result.getVisibility()).isEqualTo(MediaVisibility.PRIVATE);
        assertThat(result.getStatus()).isEqualTo(MediaAssetStatus.ARCHIVED);
    }

    @Test
    @DisplayName("findById maps clientTag from JPA entity to domain MediaAsset")
    void shouldMapClientTagFromEntityToDomain() {
        MediaAssetJpaEntity entity = new MediaAssetJpaEntity();
        entity.setId(assetId.toString());
        entity.setMediaType("IMAGE");
        entity.setVisibility("PUBLIC");
        entity.setStatus("ACTIVE");
        entity.setCurrentVersionNumber(1);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);
        entity.setClientTag("wiki.article.cover");

        when(repository.findById(assetId.toString())).thenReturn(Optional.of(entity));

        Optional<MediaAsset> optAsset = adapter.findById(assetId);

        assertThat(optAsset).isPresent();
        assertThat(optAsset.get().getClientTag()).isEqualTo("wiki.article.cover");
    }

    @Test
    @DisplayName("findByIdForUpdate acquires pessimistic lock and maps entity to domain")
    void shouldFindByIdForUpdateAndMapToDomain() {
        MediaAssetJpaEntity entity = new MediaAssetJpaEntity();
        entity.setId(assetId.toString());
        entity.setMediaType("IMAGE");
        entity.setVisibility("PUBLIC");
        entity.setStatus("ACTIVE");
        entity.setCurrentVersionNumber(1);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);
        entity.setClientTag("wiki.article.cover");

        when(repository.findByIdForUpdate(assetId.toString())).thenReturn(Optional.of(entity));

        Optional<MediaAsset> optAsset = adapter.findByIdForUpdate(assetId);

        assertThat(optAsset).isPresent();
        assertThat(optAsset.get().getId()).isEqualTo(assetId);
        assertThat(optAsset.get().getClientTag()).isEqualTo("wiki.article.cover");
        verify(repository).findByIdForUpdate(assetId.toString());
    }

    @Test
    @DisplayName("findByIdForUpdate throws on null ID")
    void shouldThrowOnNullIdForUpdate() {
        assertThatThrownBy(() -> adapter.findByIdForUpdate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Media asset ID cannot be null.");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("save maps clientTag to JPA entity for new asset")
    void shouldSaveNewAssetWithClientTag() {
        MediaAsset domainAsset = MediaAsset.registerInitial(
                assetId,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                createdAt,
                "wiki.article.cover"
        );

        when(repository.findById(assetId.toString())).thenReturn(Optional.empty());
        when(repository.save(any(MediaAssetJpaEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MediaAsset result = adapter.save(domainAsset);

        ArgumentCaptor<MediaAssetJpaEntity> captor = ArgumentCaptor.forClass(MediaAssetJpaEntity.class);
        verify(repository).save(captor.capture());

        MediaAssetJpaEntity captured = captor.getValue();
        assertThat(captured.getClientTag()).isEqualTo("wiki.article.cover");
        assertThat(result.getClientTag()).isEqualTo("wiki.article.cover");
    }

    @Test
    @DisplayName("findActiveByClientTagKeyset delegates first page and maps projections to domain candidates")
    void shouldDelegateFirstPageInFindActiveByClientTagKeyset() {
        String clientTag = "wiki.article.cover";
        Instant upperBound = Instant.parse("2026-09-01T12:00:00Z");

        MediaAssetCandidateProjection projection = new MediaAssetCandidateProjection() {
            @Override
            public String getId() {
                return assetId.toString();
            }

            @Override
            public Instant getCreatedAt() {
                return createdAt;
            }
        };

        when(repository.findActiveByClientTagFirstPage(
                org.mockito.ArgumentMatchers.eq(clientTag),
                org.mockito.ArgumentMatchers.eq(upperBound),
                any()
        )).thenReturn(List.of(projection));

        List<com.universe.media.application.ports.MediaAssetCandidate> candidates =
                adapter.findActiveByClientTagKeyset(clientTag, upperBound, null, null, 10);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).assetId()).isEqualTo(assetId);
        assertThat(candidates.get(0).createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("findActiveByClientTagKeyset delegates subsequent page and maps projections to domain candidates")
    void shouldDelegateSubsequentPageInFindActiveByClientTagKeyset() {
        String clientTag = "wiki.article.cover";
        Instant upperBound = Instant.parse("2026-09-01T12:00:00Z");
        Instant lastCreatedAt = Instant.parse("2026-09-01T09:00:00Z");
        UUID lastAssetId = UUID.randomUUID();

        MediaAssetCandidateProjection projection = new MediaAssetCandidateProjection() {
            @Override
            public String getId() {
                return assetId.toString();
            }

            @Override
            public Instant getCreatedAt() {
                return createdAt;
            }
        };

        when(repository.findActiveByClientTagSubsequentPage(
                org.mockito.ArgumentMatchers.eq(clientTag),
                org.mockito.ArgumentMatchers.eq(upperBound),
                org.mockito.ArgumentMatchers.eq(lastCreatedAt),
                org.mockito.ArgumentMatchers.eq(lastAssetId.toString()),
                any()
        )).thenReturn(List.of(projection));

        List<com.universe.media.application.ports.MediaAssetCandidate> candidates =
                adapter.findActiveByClientTagKeyset(clientTag, upperBound, lastCreatedAt, lastAssetId, 10);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).assetId()).isEqualTo(assetId);
        assertThat(candidates.get(0).createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("findActiveByClientTagKeyset forwards exact valid limit to PageRequest")
    void shouldForwardExactValidLimitToPageRequest() {
        String clientTag = "wiki.article.cover";
        Instant upperBound = Instant.parse("2026-09-01T12:00:00Z");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        when(repository.findActiveByClientTagFirstPage(
                org.mockito.ArgumentMatchers.eq(clientTag),
                org.mockito.ArgumentMatchers.eq(upperBound),
                captor.capture()
        )).thenReturn(List.of());

        // limit = 1 -> PageRequest size exactly 1
        adapter.findActiveByClientTagKeyset(clientTag, upperBound, null, null, 1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);

        // limit = 100 -> PageRequest size exactly 100
        adapter.findActiveByClientTagKeyset(clientTag, upperBound, null, null, 100);
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("findActiveByClientTagKeyset rejects limit <= 0 with IllegalArgumentException")
    void shouldRejectInvalidLimitZeroOrNegative() {
        assertThatThrownBy(() -> adapter.findActiveByClientTagKeyset(
                "tag",
                Instant.now(),
                null,
                null,
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Query limit must be greater than zero: 0");

        assertThatThrownBy(() -> adapter.findActiveByClientTagKeyset(
                "tag",
                Instant.now(),
                null,
                null,
                -1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Query limit must be greater than zero: -1");
    }

    @Test
    @DisplayName("findActiveByClientTagKeyset rejects limit > 100 with IllegalArgumentException")
    void shouldRejectInvalidLimitExceedingMax() {
        assertThatThrownBy(() -> adapter.findActiveByClientTagKeyset(
                "tag",
                Instant.now(),
                null,
                null,
                101
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Query limit cannot exceed 100: 101");
    }
}
