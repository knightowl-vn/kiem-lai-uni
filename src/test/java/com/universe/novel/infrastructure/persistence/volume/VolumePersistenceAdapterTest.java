package com.universe.novel.infrastructure.persistence.volume;

import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.VolumeStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class VolumePersistenceAdapterTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-17T02:00:00Z"
            );

    @Mock
    private SpringDataVolumeJpaRepository repository;

    private VolumePersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter =
                new VolumePersistenceAdapter(
                        repository
                );
    }

    @Test
    @DisplayName(
            "findById ánh xạ VolumeJpaEntity thành Volume"
    )
    void shouldMapEntityToDomainWhenFindingById() {
        VolumeJpaEntity entity =
                createDraftEntity();

        when(
                repository.findById(
                        VOLUME_ID.toString()
                )
        ).thenReturn(
                Optional.of(
                        entity
                )
        );

        Volume result =
                adapter.findById(
                                VOLUME_ID
                        )
                        .orElseThrow();

        assertThat(result.getId())
                .isEqualTo(VOLUME_ID);

        assertThat(result.getTitle())
                .isEqualTo("Quyển Một");

        assertThat(result.getSlug().value())
                .isEqualTo("quyen-mot");

        assertThat(result.getDescription())
                .isEqualTo("Mở đầu hành trình");

        assertThat(result.getSortOrder())
                .isEqualTo(1);

        assertThat(result.getStatus())
                .isEqualTo(
                        VolumeStatus.DRAFT
                );

        assertThat(result.getCreatedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(result.getUpdatedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(result.getAggregateVersion())
                .isEqualTo(1L);
    }
    
    @Test
    @DisplayName(
            "findByIdForUpdate delegate xuống repository và ánh xạ Domain"
    )
    void shouldFindByIdForUpdate() {

        VolumeJpaEntity entity =
                createDraftEntity();

        when(
                repository.findByIdForUpdate(
                        VOLUME_ID.toString()
                )
        ).thenReturn(
                Optional.of(
                        entity
                )
        );

        Volume result =
                adapter.findByIdForUpdate(
                                VOLUME_ID
                        )
                        .orElseThrow();

        assertThat(
                result.getId()
        ).isEqualTo(
                VOLUME_ID
        );

        assertThat(
                result.getStatus()
        ).isEqualTo(
                VolumeStatus.DRAFT
        );

        assertThat(
                result.getAggregateVersion()
        ).isEqualTo(
                1L
        );

        verify(
                repository
        ).findByIdForUpdate(
                VOLUME_ID.toString()
        );
    }

    @Test
    @DisplayName(
            "Các lookup đơn giản delegate đúng xuống Spring Data"
    )
    void shouldDelegateLookupMethods() {
        Slug slug =
                new Slug(
                        "quyen-mot"
                );

        VolumeJpaEntity entity =
                createDraftEntity();

        when(
                repository.findBySlug(
                        slug.value()
                )
        ).thenReturn(
                Optional.of(
                        entity
                )
        );

        when(
                repository.existsBySlug(
                        slug.value()
                )
        ).thenReturn(
                true
        );

        when(
                repository.existsBySortOrder(
                        1
                )
        ).thenReturn(
                true
        );

        assertThat(
                adapter.findBySlug(
                        slug
                )
        ).isPresent();

        assertThat(
                adapter.existsBySlug(
                        slug
                )
        ).isTrue();

        assertThat(
                adapter.existsBySortOrder(
                        1
                )
        ).isTrue();
    }

    @Test
    @DisplayName(
            "save tạo entity mới khi lưu Volume mới"
    )
    void shouldCreateNewEntityWhenSavingNewVolume() {
        Volume volume =
                Volume.createDraft(
                        VOLUME_ID,
                        "Quyển Một",
                        new Slug(
                                "quyen-mot"
                        ),
                        "Mở đầu hành trình",
                        1,
                        ADMIN_ID,
                        CREATED_AT
                );

        when(
                repository.findById(
                        VOLUME_ID.toString()
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                repository.save(
                        any(VolumeJpaEntity.class)
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        Volume result =
                adapter.save(
                        volume,
                        0L
                );

        verify(repository)
                .save(
                        any(VolumeJpaEntity.class)
                );

        assertThat(result.getId())
                .isEqualTo(
                        VOLUME_ID
                );

        assertThat(result.getTitle())
                .isEqualTo(
                        "Quyển Một"
                );

        assertThat(result.getSlug().value())
                .isEqualTo(
                        "quyen-mot"
                );

        assertThat(result.getStatus())
                .isEqualTo(
                        VolumeStatus.DRAFT
                );

        assertThat(result.getAggregateVersion())
                .isEqualTo(
                        1L
                );
    }
    
    @Test
    @DisplayName(
            "save tái sử dụng entity hiện có khi cập nhật Volume hợp lệ"
    )
    void shouldReuseExistingEntityWhenUpdatingVolume() {
        Volume updatedVolume =
                Volume.rehydrate(
                        VOLUME_ID,
                        "Quyển Một Đã Sửa",
                        new Slug(
                                "quyen-mot"
                        ),
                        "Mô tả đã sửa",
                        1,
                        VolumeStatus.DRAFT,
                        ADMIN_ID,
                        ADMIN_ID,
                        null,
                        null,
                        CREATED_AT,
                        CREATED_AT.plusSeconds(60),
                        null,
                        null,
                        2L
                );

        VolumeJpaEntity existingEntity =
                createDraftEntity();

        existingEntity.setAggregateVersion(
                1L
        );

        when(
                repository.findById(
                        VOLUME_ID.toString()
                )
        ).thenReturn(
                Optional.of(
                        existingEntity
                )
        );

        when(
                repository.save(
                        existingEntity
                )
        ).thenReturn(
                existingEntity
        );

        Volume result =
                adapter.save(
                        updatedVolume,
                        1L
                );

        verify(repository)
                .save(
                        existingEntity
                );

        assertThat(existingEntity.getTitle())
                .isEqualTo(
                        "Quyển Một Đã Sửa"
                );

        assertThat(existingEntity.getDescription())
                .isEqualTo(
                        "Mô tả đã sửa"
                );

        assertThat(existingEntity.getAggregateVersion())
                .isEqualTo(
                        2L
                );

        assertThat(result.getAggregateVersion())
                .isEqualTo(
                        2L
                );
    }

    @Test
    @DisplayName(
            "Null lookup không truy cập repository"
    )
    void shouldHandleNullLookupWithoutRepositoryCall() {
        assertThat(
                adapter.findById(
                        null
                )
        ).isEmpty();

        assertThat(
                adapter.findBySlug(
                        null
                )
        ).isEmpty();

        assertThat(
                adapter.existsBySlug(
                        null
                )
        ).isFalse();

        assertThat(
                adapter.existsBySortOrder(
                        0
                )
        ).isFalse();

        verifyNoInteractions(
                repository
        );
    }

    @Test
    @DisplayName(
            "Từ chối save Volume null"
    )
    void shouldRejectNullVolumeOnSave() {
        assertThatThrownBy(() ->
                adapter.save(
                        null, 0L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Volume không được để trống."
                );
    }

    private VolumeJpaEntity createDraftEntity() {
        VolumeJpaEntity entity =
                new VolumeJpaEntity();

        entity.setId(
                VOLUME_ID.toString()
        );

        entity.setTitle(
                "Quyển Một"
        );

        entity.setSlug(
                "quyen-mot"
        );

        entity.setDescription(
                "Mở đầu hành trình"
        );

        entity.setSortOrder(
                1
        );

        entity.setStatus(
                VolumeStatus.DRAFT.name()
        );

        entity.setCreatedBy(
                ADMIN_ID.toString()
        );

        entity.setUpdatedBy(
                ADMIN_ID.toString()
        );

        entity.setAggregateVersion(
                1L
        );

        entity.setCreatedAt(
                CREATED_AT
        );

        entity.setUpdatedAt(
                CREATED_AT
        );

        return entity;
    }
    
    @Test
    @DisplayName(
            "Từ chối Volume stale khi DB đã có aggregateVersion mới hơn"
    )
    void shouldRejectStaleVolume() {
        Volume staleVolume =
                Volume.rehydrate(
                        VOLUME_ID,
                        "Quyển Một",
                        new Slug(
                                "quyen-mot"
                        ),
                        "Mở đầu hành trình",
                        1,
                        VolumeStatus.DRAFT,
                        ADMIN_ID,
                        ADMIN_ID,
                        null,
                        null,
                        CREATED_AT,
                        CREATED_AT.plusSeconds(60),
                        null,
                        null,
                        6L
                );

        VolumeJpaEntity currentEntity =
                createDraftEntity();

        currentEntity.setAggregateVersion(
                6L
        );

        when(
                repository.findById(
                        VOLUME_ID.toString()
                )
        ).thenReturn(
                Optional.of(
                        currentEntity
                )
        );

        assertThatThrownBy(() ->
                adapter.save(
                        staleVolume,
                        5L
                )
        )
                .isInstanceOf(
                        java.util.ConcurrentModificationException.class
                )
                .hasMessage(
                        "Volume đã được cập nhật bởi tiến trình khác."
                );
        verify(repository, never())
        .save(
                any(VolumeJpaEntity.class)
        );
    }
    
    @Test
    @DisplayName(
            "Từ chối tạo Volume khi ID đã tồn tại"
    )
    void shouldRejectCreateWhenVolumeAlreadyExists() {
        Volume volume =
                Volume.createDraft(
                        VOLUME_ID,
                        "Quyển Một",
                        new Slug(
                                "quyen-mot"
                        ),
                        "Mở đầu hành trình",
                        1,
                        ADMIN_ID,
                        CREATED_AT
                );

        when(
                repository.findById(
                        VOLUME_ID.toString()
                )
        ).thenReturn(
                Optional.of(
                        createDraftEntity()
                )
        );

        assertThatThrownBy(() ->
                adapter.save(
                        volume,
                        0L
                )
        )
                .isInstanceOf(
                        ConcurrentModificationException.class
                );

        verify(repository, never())
                .save(
                        any(VolumeJpaEntity.class)
                );
    }

    @Test
    @DisplayName(
            "Từ chối cập nhật Volume không còn tồn tại"
    )
    void shouldRejectUpdateWhenVolumeDoesNotExist() {
        Volume volume =
                Volume.rehydrate(
                        VOLUME_ID,
                        "Quyển Một",
                        new Slug(
                                "quyen-mot"
                        ),
                        "Mở đầu hành trình",
                        1,
                        VolumeStatus.DRAFT,
                        ADMIN_ID,
                        ADMIN_ID,
                        null,
                        null,
                        CREATED_AT,
                        CREATED_AT.plusSeconds(60),
                        null,
                        null,
                        2L
                );

        when(
                repository.findById(
                        VOLUME_ID.toString()
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                adapter.save(
                        volume,
                        1L
                )
        )
                .isInstanceOf(
                        ConcurrentModificationException.class
                );

        verify(repository, never())
                .save(
                        any(VolumeJpaEntity.class)
                );
    }

    @Test
    @DisplayName(
            "Từ chối expectedAggregateVersion âm"
    )
    void shouldRejectNegativeExpectedAggregateVersion() {
        Volume volume =
                Volume.createDraft(
                        VOLUME_ID,
                        "Quyển Một",
                        new Slug(
                                "quyen-mot"
                        ),
                        "Mở đầu hành trình",
                        1,
                        ADMIN_ID,
                        CREATED_AT
                );

        assertThatThrownBy(() ->
                adapter.save(
                        volume,
                        -1L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );

        verifyNoInteractions(
                repository
        );
    }

    @Test
    @DisplayName(
            "Từ chối update khi Volume chưa tăng aggregateVersion"
    )
    void shouldRejectUpdateWithoutVersionIncrement() {
        Volume volume =
                Volume.rehydrate(
                        VOLUME_ID,
                        "Quyển Một",
                        new Slug(
                                "quyen-mot"
                        ),
                        "Mở đầu hành trình",
                        1,
                        VolumeStatus.DRAFT,
                        ADMIN_ID,
                        ADMIN_ID,
                        null,
                        null,
                        CREATED_AT,
                        CREATED_AT,
                        null,
                        null,
                        1L
                );

        VolumeJpaEntity currentEntity =
                createDraftEntity();

        currentEntity.setAggregateVersion(
                1L
        );

        when(
                repository.findById(
                        VOLUME_ID.toString()
                )
        ).thenReturn(
                Optional.of(
                        currentEntity
                )
        );

        assertThatThrownBy(() ->
                adapter.save(
                        volume,
                        1L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );

        verify(repository, never())
                .save(
                        any(VolumeJpaEntity.class)
                );
    }
    
    @Test
    @DisplayName(
            "Lấy danh sách Volume theo sortOrder ASC"
    )
    void shouldFindAllOrderBySortOrder() {

        VolumeJpaEntity first =
                createDraftEntity();

        first.setId(
                "11111111-1111-1111-1111-111111111111"
        );

        first.setTitle(
                "Kiếm Lai - Tập 1"
        );

        first.setSlug(
                "quyen-1"
        );

        first.setSortOrder(
                1
        );

        VolumeJpaEntity second =
                createDraftEntity();

        second.setId(
                "22222222-2222-2222-2222-222222222222"
        );

        second.setTitle(
                "Kiếm Lai - Tập 2"
        );

        second.setSlug(
                "quyen-2"
        );

        second.setSortOrder(
                2
        );

        VolumeJpaEntity thirteenth =
                createDraftEntity();

        thirteenth.setId(
                "33333333-3333-3333-3333-333333333333"
        );

        thirteenth.setTitle(
                "Kiếm Lai - Tập 13"
        );

        thirteenth.setSlug(
                "quyen-13"
        );

        thirteenth.setSortOrder(
                13
        );

        when(
                repository
                        .findAllByOrderBySortOrderAsc()
        ).thenReturn(
                List.of(
                        first,
                        second,
                        thirteenth
                )
        );

        List<Volume> result =
                adapter.findAllOrderBySortOrder();

        assertThat(
                result
        ).hasSize(
                3
        );

        assertThat(
                result
                        .stream()
                        .map(
                                Volume::getSortOrder
                        )
        ).containsExactly(
                1,
                2,
                13
        );

        assertThat(
                result
                        .stream()
                        .map(
                                Volume::getTitle
                        )
        ).containsExactly(
                "Kiếm Lai - Tập 1",
                "Kiếm Lai - Tập 2",
                "Kiếm Lai - Tập 13"
        );

        assertThat(
                result
                        .stream()
                        .map(
                                volume ->
                                        volume.getSlug().value()
                        )
        ).containsExactly(
                "quyen-1",
                "quyen-2",
                "quyen-13"
        );

        verify(
                repository
        ).findAllByOrderBySortOrderAsc();
    }
}