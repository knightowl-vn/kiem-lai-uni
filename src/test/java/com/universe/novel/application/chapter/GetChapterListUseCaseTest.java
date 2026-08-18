package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Mock
    private ChapterRepositoryPort
            chapterRepositoryPort;

    @Mock
    private VolumeRepositoryPort
            volumeRepositoryPort;

    private GetChapterListUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new GetChapterListUseCase(
                        chapterRepositoryPort,
                        volumeRepositoryPort
                );
    }

    @Test
    @DisplayName(
            "Lấy danh sách Chapter trong Volume theo sortOrder ASC"
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

        when(
                chapterRepositoryPort
                        .findAllByVolumeIdOrderBySortOrder(
                                VOLUME_ID
                        )
        ).thenReturn(
                List.of(
                        createChapter(
                                CHAPTER_1_ID,
                                "Chương Một",
                                "chuong-mot",
                                1
                        ),
                        createChapter(
                                CHAPTER_2_ID,
                                "Chương Hai",
                                "chuong-hai",
                                2
                        ),
                        createChapter(
                                CHAPTER_3_ID,
                                "Chương Ba",
                                "chuong-ba",
                                3
                        )
                )
        );

        List<ChapterDTO> result =
                useCase.execute(
                        VOLUME_ID
                );

        assertThat(
                result
        ).hasSize(
                3
        );

        assertThat(
                result.stream()
                        .map(
                                ChapterDTO::sortOrder
                        )
        ).containsExactly(
                1,
                2,
                3
        );

        assertThat(
                result.stream()
                        .map(
                                ChapterDTO::title
                        )
        ).containsExactly(
                "Chương Một",
                "Chương Hai",
                "Chương Ba"
        );

        assertThat(
                result.stream()
                        .map(
                                ChapterDTO::volumeId
                        )
        ).containsOnly(
                VOLUME_ID
        );

        verify(
                chapterRepositoryPort
        ).findAllByVolumeIdOrderBySortOrder(
                VOLUME_ID
        );
    }

    @Test
    @DisplayName(
            "Trả danh sách rỗng khi Volume tồn tại nhưng chưa có Chapter"
    )
    void shouldReturnEmptyList() {

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        createVolume()
                )
        );

        when(
                chapterRepositoryPort
                        .findAllByVolumeIdOrderBySortOrder(
                                VOLUME_ID
                        )
        ).thenReturn(
                List.of()
        );

        List<ChapterDTO> result =
                useCase.execute(
                        VOLUME_ID
                );

        assertThat(
                result
        ).isEmpty();

        verify(
                chapterRepositoryPort
        ).findAllByVolumeIdOrderBySortOrder(
                VOLUME_ID
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
                        VOLUME_ID
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
                chapterRepositoryPort,
                never()
        ).findAllByVolumeIdOrderBySortOrder(
                any(UUID.class)
        );
    }

    @Test
    @DisplayName(
            "Từ chối Volume ID null"
    )
    void shouldRejectNullVolumeId() {

        assertThatThrownBy(() ->
                useCase.execute(
                        null
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
                chapterRepositoryPort,
                never()
        ).findAllByVolumeIdOrderBySortOrder(
                any(UUID.class)
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

    private Chapter createChapter(
            UUID chapterId,
            String title,
            String slug,
            int sortOrder
    ) {
        return Chapter.createDraft(
                chapterId,
                VOLUME_ID,
                sortOrder,
                sortOrder,
                title,
                new Slug(
                        slug
                ),
                "Tóm tắt.",
                "Nội dung.",
                ADMIN_ID,
                CREATED_AT
        );
    }
}