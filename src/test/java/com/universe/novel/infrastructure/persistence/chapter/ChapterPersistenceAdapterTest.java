package com.universe.novel.infrastructure.persistence.chapter;

import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.ConcurrentModificationException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ChapterPersistenceAdapterTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-17T02:00:00Z"
            );

    @Mock
    private SpringDataChapterJpaRepository repository;

    private ChapterPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter =
                new ChapterPersistenceAdapter(
                        repository
                );
    }

    @Test
    @DisplayName(
            "findById ánh xạ ChapterJpaEntity thành Chapter"
    )
    void shouldMapEntityToDomainWhenFindingById() {
        ChapterJpaEntity entity =
                createDraftEntity();

        when(
                repository.findById(
                        CHAPTER_ID.toString()
                )
        ).thenReturn(
                Optional.of(
                        entity
                )
        );

        Chapter result =
                adapter.findById(
                                CHAPTER_ID
                        )
                        .orElseThrow();

        assertThat(result.getId())
                .isEqualTo(
                        CHAPTER_ID
                );

        assertThat(result.getVolumeId())
                .isEqualTo(
                        VOLUME_ID
                );

        assertThat(result.getChapterNumber())
                .isEqualTo(
                        1
                );

        assertThat(result.getSortOrder())
                .isEqualTo(
                        1
                );

        assertThat(result.getTitle())
                .isEqualTo(
                        "Chương Một"
                );

        assertThat(result.getSlug().value())
                .isEqualTo(
                        "chuong-mot"
                );

        assertThat(result.getSummary())
                .isEqualTo(
                        "Khởi đầu"
                );

        assertThat(result.getContent())
                .isEqualTo(
                        "Nội dung chương."
                );

        assertThat(result.getStatus())
                .isEqualTo(
                        ChapterStatus.DRAFT
                );

        assertThat(result.getAggregateVersion())
                .isEqualTo(
                        1L
                );

        assertThat(result.getContentVersion())
                .isEqualTo(
                        1L
                );
    }

    @Test
    @DisplayName(
            "Các lookup và uniqueness query delegate đúng"
    )
    void shouldDelegateLookupMethods() {
        Slug slug =
                new Slug(
                        "chuong-mot"
                );

        ChapterJpaEntity entity =
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
                repository.existsByVolumeIdAndSortOrder(
                        VOLUME_ID.toString(),
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
                adapter.existsByVolumeIdAndSortOrder(
                        VOLUME_ID,
                        1
                )
        ).isTrue();
    }

    @Test
    @DisplayName(
            "existsPublishedByVolumeId dùng đúng trạng thái PUBLISHED"
    )
    void shouldCheckPublishedChapterByVolumeId() {
        when(
                repository.existsByVolumeIdAndStatus(
                        VOLUME_ID.toString(),
                        ChapterStatus.PUBLISHED.name()
                )
        ).thenReturn(
                true
        );

        assertThat(
                adapter.existsPublishedByVolumeId(
                        VOLUME_ID
                )
        ).isTrue();

        verify(repository)
                .existsByVolumeIdAndStatus(
                        VOLUME_ID.toString(),
                        "PUBLISHED"
                );
    }

    @Test
    @DisplayName(
            "save tạo entity mới khi lưu Chapter mới"
    )
    void shouldCreateNewEntityWhenSavingNewChapter() {
        Chapter chapter =
                Chapter.createDraft(
                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        1,
                        "Chương Một",
                        new Slug(
                                "chuong-mot"
                        ),
                        "Khởi đầu",
                        "Nội dung chương.",
                        ADMIN_ID,
                        CREATED_AT
                );

        when(
                repository.findById(
                        CHAPTER_ID.toString()
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                repository.save(
                        any(ChapterJpaEntity.class)
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        Chapter result =
                adapter.save(
                        chapter,
                        0L
                );

        verify(repository)
                .save(
                        any(ChapterJpaEntity.class)
                );

        assertThat(result.getId())
                .isEqualTo(
                        CHAPTER_ID
                );

        assertThat(result.getVolumeId())
                .isEqualTo(
                        VOLUME_ID
                );

        assertThat(result.getStatus())
                .isEqualTo(
                        ChapterStatus.DRAFT
                );

        assertThat(result.getAggregateVersion())
                .isEqualTo(
                        1L
                );

        assertThat(result.getContentVersion())
                .isEqualTo(
                        1L
                );
    }
    
    @Test
    @DisplayName(
            "save tái sử dụng entity hiện có khi cập nhật Chapter hợp lệ"
    )
    void shouldReuseExistingEntityWhenUpdatingChapter() {
        Chapter updatedChapter =
                Chapter.rehydrate(
                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        1,
                        "Chương Một Đã Sửa",
                        new Slug(
                                "chuong-mot"
                        ),
                        "Tóm tắt đã sửa",
                        "Nội dung chương đã sửa.",
                        ChapterStatus.DRAFT,
                        ADMIN_ID,
                        ADMIN_ID,
                        null,
                        null,
                        CREATED_AT,
                        CREATED_AT.plusSeconds(60),
                        null,
                        null,
                        2L,
                        2L
                );

        ChapterJpaEntity existingEntity =
                createDraftEntity();

        existingEntity.setAggregateVersion(
                1L
        );

        existingEntity.setContentVersion(
                1L
        );

        when(
                repository.findById(
                        CHAPTER_ID.toString()
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

        Chapter result =
                adapter.save(
                        updatedChapter,
                        1L
                );

        verify(repository)
                .save(
                        existingEntity
                );

        assertThat(existingEntity.getTitle())
                .isEqualTo(
                        "Chương Một Đã Sửa"
                );

        assertThat(existingEntity.getContent())
                .isEqualTo(
                        "Nội dung chương đã sửa."
                );

        assertThat(existingEntity.getAggregateVersion())
                .isEqualTo(
                        2L
                );

        assertThat(existingEntity.getContentVersion())
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
            "Từ chối Chapter stale khi DB đã có aggregateVersion mới hơn"
    )
    void shouldRejectStaleChapter() {
        Chapter staleChapter =
                Chapter.rehydrate(
                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        1,
                        "Chương Một",
                        new Slug(
                                "chuong-mot"
                        ),
                        "Khởi đầu",
                        "Nội dung chương.",
                        ChapterStatus.DRAFT,
                        ADMIN_ID,
                        ADMIN_ID,
                        null,
                        null,
                        CREATED_AT,
                        CREATED_AT.plusSeconds(60),
                        null,
                        null,
                        6L,
                        1L
                );

        ChapterJpaEntity currentEntity =
                createDraftEntity();

        currentEntity.setAggregateVersion(
                6L
        );

        when(
                repository.findById(
                        CHAPTER_ID.toString()
                )
        ).thenReturn(
                Optional.of(
                        currentEntity
                )
        );

        assertThatThrownBy(() ->
                adapter.save(
                        staleChapter,
                        5L
                )
        )
                .isInstanceOf(
                        ConcurrentModificationException.class
                )
                .hasMessage(
                        "Chapter đã được cập nhật bởi tiến trình khác."
                );

        verify(repository, never())
                .save(
                        any(ChapterJpaEntity.class)
                );
    }
    
    @Test
    @DisplayName(
            "Từ chối tạo Chapter khi ID đã tồn tại"
    )
    void shouldRejectCreateWhenChapterAlreadyExists() {
        Chapter chapter =
                Chapter.createDraft(
                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        1,
                        "Chương Một",
                        new Slug(
                                "chuong-mot"
                        ),
                        "Khởi đầu",
                        "Nội dung chương.",
                        ADMIN_ID,
                        CREATED_AT
                );

        when(
                repository.findById(
                        CHAPTER_ID.toString()
                )
        ).thenReturn(
                Optional.of(
                        createDraftEntity()
                )
        );

        assertThatThrownBy(() ->
                adapter.save(
                        chapter,
                        0L
                )
        )
                .isInstanceOf(
                        ConcurrentModificationException.class
                );

        verify(repository, never())
                .save(
                        any(ChapterJpaEntity.class)
                );
    }

    @Test
    @DisplayName(
            "Từ chối cập nhật Chapter không còn tồn tại"
    )
    void shouldRejectUpdateWhenChapterDoesNotExist() {
        Chapter chapter =
                Chapter.rehydrate(
                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        1,
                        "Chương Một",
                        new Slug(
                                "chuong-mot"
                        ),
                        "Khởi đầu",
                        "Nội dung chương.",
                        ChapterStatus.DRAFT,
                        ADMIN_ID,
                        ADMIN_ID,
                        null,
                        null,
                        CREATED_AT,
                        CREATED_AT.plusSeconds(60),
                        null,
                        null,
                        2L,
                        1L
                );

        when(
                repository.findById(
                        CHAPTER_ID.toString()
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                adapter.save(
                        chapter,
                        1L
                )
        )
                .isInstanceOf(
                        ConcurrentModificationException.class
                );

        verify(repository, never())
                .save(
                        any(ChapterJpaEntity.class)
                );
    }

    @Test
    @DisplayName(
            "Từ chối Chapter khi expectedAggregateVersion âm"
    )
    void shouldRejectNegativeExpectedAggregateVersion() {
        Chapter chapter =
                Chapter.createDraft(
                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        1,
                        "Chương Một",
                        new Slug(
                                "chuong-mot"
                        ),
                        "Khởi đầu",
                        "Nội dung chương.",
                        ADMIN_ID,
                        CREATED_AT
                );

        assertThatThrownBy(() ->
                adapter.save(
                        chapter,
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
            "Từ chối update Chapter khi aggregateVersion chưa tăng"
    )
    void shouldRejectUpdateWithoutVersionIncrement() {
        Chapter chapter =
                Chapter.rehydrate(
                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        1,
                        "Chương Một",
                        new Slug(
                                "chuong-mot"
                        ),
                        "Khởi đầu",
                        "Nội dung chương.",
                        ChapterStatus.DRAFT,
                        ADMIN_ID,
                        ADMIN_ID,
                        null,
                        null,
                        CREATED_AT,
                        CREATED_AT,
                        null,
                        null,
                        1L,
                        1L
                );

        ChapterJpaEntity currentEntity =
                createDraftEntity();

        currentEntity.setAggregateVersion(
                1L
        );

        when(
                repository.findById(
                        CHAPTER_ID.toString()
                )
        ).thenReturn(
                Optional.of(
                        currentEntity
                )
        );

        assertThatThrownBy(() ->
                adapter.save(
                        chapter,
                        1L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );

        verify(repository, never())
                .save(
                        any(ChapterJpaEntity.class)
                );
    }
    
    @Test
    @DisplayName(
            "Lấy danh sách Chapter trong Volume theo sortOrder ASC"
    )
    void shouldFindAllByVolumeIdOrderBySortOrder() {

        ChapterJpaEntity first =
                createDraftEntity();

        first.setId(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"
        );

        first.setChapterNumber(
                1
        );

        first.setSortOrder(
                1
        );

        first.setTitle(
                "Chương Một"
        );

        first.setSlug(
                "chuong-mot"
        );

        ChapterJpaEntity second =
                createDraftEntity();

        second.setId(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2"
        );

        second.setChapterNumber(
                2
        );

        second.setSortOrder(
                2
        );

        second.setTitle(
                "Chương Hai"
        );

        second.setSlug(
                "chuong-hai"
        );

        when(
                repository
                        .findAllByVolumeIdOrderBySortOrderAsc(
                                VOLUME_ID.toString()
                        )
        ).thenReturn(
                List.of(
                        first,
                        second
                )
        );

        List<Chapter> result =
                adapter.findAllByVolumeIdOrderBySortOrder(
                        VOLUME_ID
                );

        assertThat(
                result
        ).hasSize(
                2
        );

        assertThat(
                result.stream()
                        .map(
                                Chapter::getSortOrder
                        )
        ).containsExactly(
                1,
                2
        );

        assertThat(
                result.stream()
                        .map(
                                Chapter::getTitle
                        )
        ).containsExactly(
                "Chương Một",
                "Chương Hai"
        );

        assertThat(
                result.stream()
                        .map(
                                chapter ->
                                        chapter.getSlug().value()
                        )
        ).containsExactly(
                "chuong-mot",
                "chuong-hai"
        );

        assertThat(
                result.stream()
                        .map(
                                Chapter::getVolumeId
                        )
        ).containsOnly(
                VOLUME_ID
        );

        verify(
                repository
        ).findAllByVolumeIdOrderBySortOrderAsc(
                VOLUME_ID.toString()
        );
    }

    @Test
    @DisplayName(
            "Input lookup không hợp lệ không truy cập repository"
    )
    void shouldHandleInvalidLookupWithoutRepositoryCall() {
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
                adapter.existsByVolumeIdAndSortOrder(
                        null,
                        1
                )
        ).isFalse();

        assertThat(
                adapter.existsByVolumeIdAndSortOrder(
                        VOLUME_ID,
                        0
                )
        ).isFalse();

        assertThat(
                adapter.existsPublishedByVolumeId(
                        null
                )
        ).isFalse();
        
        assertThat(
                adapter.findAllByVolumeIdOrderBySortOrder(
                        null
                )
        ).isEmpty();

        verifyNoInteractions(
                repository
        );
    }

    @Test
    @DisplayName(
            "Từ chối save Chapter null"
    )
    void shouldRejectNullChapterOnSave() {
        assertThatThrownBy(() ->
                adapter.save(
                        null, 0L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Chapter không được để trống."
                );
    }

    private ChapterJpaEntity createDraftEntity() {
        ChapterJpaEntity entity =
                new ChapterJpaEntity();

        entity.setId(
                CHAPTER_ID.toString()
        );

        entity.setVolumeId(
                VOLUME_ID.toString()
        );

        entity.setChapterNumber(
                1
        );

        entity.setSortOrder(
                1
        );

        entity.setTitle(
                "Chương Một"
        );

        entity.setSlug(
                "chuong-mot"
        );

        entity.setSummary(
                "Khởi đầu"
        );

        entity.setContent(
                "Nội dung chương."
        );

        entity.setStatus(
                ChapterStatus.DRAFT.name()
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

        entity.setContentVersion(
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
}