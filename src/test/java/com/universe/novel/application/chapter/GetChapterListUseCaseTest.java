package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.novel.contracts.dto.ChapterListPageDTO;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetChapterListUseCaseTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID CHAPTER_1_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID CHAPTER_2_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final UUID CHAPTER_3_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "55555555-5555-5555-5555-555555555555"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-18T05:00:00Z"
            );

    private static final int PAGE = 1;

    private static final int SIZE = 50;

    @Mock
    private ChapterListQueryPort
            chapterListQueryPort;

    @Mock
    private VolumeRepositoryPort
            volumeRepositoryPort;

    private GetChapterListUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new GetChapterListUseCase(
                        chapterListQueryPort,
                        volumeRepositoryPort
                );
    }

    @Test
    @DisplayName(
            "Lấy danh sách Chapter trong Volume theo chapterNumber ASC"
    )
    void shouldGetChapterList() {

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        createVolume()
                )
        );

        List<ChapterListItemDTO> items =
                List.of(
                        createChapterListItem(
                                CHAPTER_1_ID,
                                "Chương Một",
                                "chuong-mot",
                                1
                        ),
                        createChapterListItem(
                                CHAPTER_2_ID,
                                "Chương Hai",
                                "chuong-hai",
                                2
                        ),
                        createChapterListItem(
                                CHAPTER_3_ID,
                                "Chương Ba",
                                "chuong-ba",
                                3
                        )
                );

        ChapterListPageDTO pageResult =
                new ChapterListPageDTO(
                        items,
                        PAGE,
                        SIZE,
                        3L,
                        1,
                        false,
                        false
                );

        when(
                chapterListQueryPort
                        .findAllByVolumeIdOrderByChapterNumber(
                                VOLUME_ID,
                                null,
                                null,
                                PAGE,
                                SIZE
                        )
        ).thenReturn(
                pageResult
        );

        ChapterListPageDTO result =
                useCase.execute(
                        VOLUME_ID,
                        null,
                        null,
                        PAGE,
                        SIZE
                );

        assertThat(
                result.items()
        ).hasSize(
                3
        );

        assertThat(
                result.items()
                        .stream()
                        .map(
                                ChapterListItemDTO::chapterNumber
                        )
        ).containsExactly(
                1,
                2,
                3
        );

        assertThat(
                result.items()
                        .stream()
                        .map(
                                ChapterListItemDTO::title
                        )
        ).containsExactly(
                "Chương Một",
                "Chương Hai",
                "Chương Ba"
        );

        assertThat(
                result.page()
        ).isEqualTo(
                PAGE
        );

        assertThat(
                result.size()
        ).isEqualTo(
                SIZE
        );

        assertThat(
                result.totalItems()
        ).isEqualTo(
                3L
        );

        assertThat(
                result.totalPages()
        ).isEqualTo(
                1
        );

        assertThat(
                result.hasPrevious()
        ).isFalse();

        assertThat(
                result.hasNext()
        ).isFalse();

        verify(
                chapterListQueryPort
        ).findAllByVolumeIdOrderByChapterNumber(
                VOLUME_ID,
                null,
                null,
                PAGE,
                SIZE
        );
    }

    @Test
    @DisplayName(
            "Trả trang rỗng khi Volume tồn tại nhưng chưa có Chapter"
    )
    void shouldReturnEmptyPage() {

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        createVolume()
                )
        );

        ChapterListPageDTO emptyPage =
                new ChapterListPageDTO(
                        List.of(),
                        PAGE,
                        SIZE,
                        0L,
                        0,
                        false,
                        false
                );

        when(
                chapterListQueryPort
                        .findAllByVolumeIdOrderByChapterNumber(
                                VOLUME_ID,
                                null,
                                null,
                                PAGE,
                                SIZE
                        )
        ).thenReturn(
                emptyPage
        );

        ChapterListPageDTO result =
                useCase.execute(
                        VOLUME_ID,
                        null,
                        null,
                        PAGE,
                        SIZE
                );

        assertThat(
                result.items()
        ).isEmpty();

        assertThat(
                result.totalItems()
        ).isZero();

        assertThat(
                result.totalPages()
        ).isZero();

        assertThat(
                result.hasPrevious()
        ).isFalse();

        assertThat(
                result.hasNext()
        ).isFalse();

        verify(
                chapterListQueryPort
        ).findAllByVolumeIdOrderByChapterNumber(
                VOLUME_ID,
                null,
                null,
                PAGE,
                SIZE
        );
    }

    @Test
    @DisplayName(
            "Từ chối query Chapter list khi Volume không tồn tại"
    )
    void shouldRejectMissingVolume() {

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        VOLUME_ID,
                        null,
                        null,
                        PAGE,
                        SIZE
                )
        )
                .isInstanceOf(
                        VolumeNotFoundException.class
                )
                .hasMessage(
                        "Không tìm thấy tập: "
                                + VOLUME_ID
                );

        verify(
                chapterListQueryPort,
                never()
        ).findAllByVolumeIdOrderByChapterNumber(
                any(UUID.class),
                any(),
                any(),
                anyInt(),
                anyInt()
        );
    }

    @Test
    @DisplayName(
            "Từ chối Volume ID null"
    )
    void shouldRejectNullVolumeId() {

        assertThatThrownBy(() ->
                useCase.execute(
                        null,
                        null,
                        null,
                        PAGE,
                        SIZE
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Volume ID không được để trống."
                );

        verify(
                volumeRepositoryPort,
                never()
        ).findById(
                any(UUID.class)
        );

        verify(
                chapterListQueryPort,
                never()
        ).findAllByVolumeIdOrderByChapterNumber(
                any(UUID.class),
                any(),
                any(),
                anyInt(),
                anyInt()
        );
    }

    @Test
    @DisplayName(
            "Chuẩn hóa keyword và status trước khi query Chapter list"
    )
    void shouldNormalizeFiltersBeforeQuery() {

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        createVolume()
                )
        );

        ChapterListPageDTO emptyPage =
                new ChapterListPageDTO(
                        List.of(),
                        PAGE,
                        SIZE,
                        0L,
                        0,
                        false,
                        false
                );

        when(
                chapterListQueryPort
                        .findAllByVolumeIdOrderByChapterNumber(
                                VOLUME_ID,
                                "Chương Một",
                                "DRAFT",
                                PAGE,
                                SIZE
                        )
        ).thenReturn(
                emptyPage
        );

        useCase.execute(
                VOLUME_ID,
                "  Chương Một  ",
                "  DRAFT  ",
                PAGE,
                SIZE
        );

        verify(
                chapterListQueryPort
        ).findAllByVolumeIdOrderByChapterNumber(
                VOLUME_ID,
                "Chương Một",
                "DRAFT",
                PAGE,
                SIZE
        );
    }

    @Test
    @DisplayName(
            "Từ chối page nhỏ hơn 1"
    )
    void shouldRejectPageLessThanOne() {

        assertThatThrownBy(() ->
                useCase.execute(
                        VOLUME_ID,
                        null,
                        null,
                        0,
                        SIZE
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Số trang phải lớn hơn hoặc bằng 1."
                );

        verifyNoInteractions(
                volumeRepositoryPort,
                chapterListQueryPort
        );
    }

    @Test
    @DisplayName(
            "Từ chối size nhỏ hơn 1"
    )
    void shouldRejectPageSizeLessThanOne() {

        assertThatThrownBy(() ->
                useCase.execute(
                        VOLUME_ID,
                        null,
                        null,
                        PAGE,
                        0
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Kích thước trang phải từ 1 đến 100."
                );

        verifyNoInteractions(
                volumeRepositoryPort,
                chapterListQueryPort
        );
    }

    @Test
    @DisplayName(
            "Từ chối size lớn hơn giới hạn"
    )
    void shouldRejectPageSizeGreaterThanMaximum() {

        assertThatThrownBy(() ->
                useCase.execute(
                        VOLUME_ID,
                        null,
                        null,
                        PAGE,
                        101
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Kích thước trang phải từ 1 đến 100."
                );

        verifyNoInteractions(
                volumeRepositoryPort,
                chapterListQueryPort
        );
    }

    private Volume createVolume() {
        return Volume.createDraft(
                VOLUME_ID,
                "Quyển Một",
                new Slug(
                        "quyen-mot"
                ),
                "Volume test.",
                1,
                ADMIN_ID,
                CREATED_AT
        );
    }

    private ChapterListItemDTO createChapterListItem(
            UUID chapterId,
            String title,
            String slug,
            int chapterNumber
    ) {
        return new ChapterListItemDTO(
                chapterId,
                chapterNumber,
                title,
                slug,
                "DRAFT",
                CREATED_AT
        );
    }
}