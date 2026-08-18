package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterSortOrderAlreadyExistsException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.shared.time.ClockPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReorderChapterUseCaseTest {

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

    private static final UUID OTHER_ADMIN_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-17T10:00:00Z"
            );

    private static final Instant REORDERED_AT =
            Instant.parse(
                    "2026-08-17T11:00:00Z"
            );

    @Mock
    private ChapterRepositoryPort
            chapterRepositoryPort;

    @Mock
    private ClockPort
            clockPort;

    private ReorderChapterUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new ReorderChapterUseCase(
                        chapterRepositoryPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Sắp xếp lại Chapter DRAFT thành công"
    )
    void shouldReorderDraftChapter() {

        Chapter chapter =
                createDraftChapter();

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
        );

        when(
                chapterRepositoryPort
                        .existsByVolumeIdAndSortOrder(
                                VOLUME_ID,
                                5
                        )
        ).thenReturn(
                false
        );

        when(
                clockPort.now()
        ).thenReturn(
                REORDERED_AT
        );

        when(
                chapterRepositoryPort.save(
                        chapter,
                        1L
                )
        ).thenReturn(
                chapter
        );

        ChapterDTO result =
                useCase.execute(
                        new ReorderChapterCommand(
                                CHAPTER_ID,
                                5,
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                chapter.getSortOrder()
        ).isEqualTo(
                5
        );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.DRAFT
        );

        assertThat(
                chapter.getUpdatedBy()
        ).isEqualTo(
                OTHER_ADMIN_ID
        );

        assertThat(
                chapter.getUpdatedAt()
        ).isEqualTo(
                REORDERED_AT
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                2L
        );

        /*
         * Reorder không đổi content.
         */
        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                result.sortOrder()
        ).isEqualTo(
                5
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                2L
        );

        assertThat(
                result.contentVersion()
        ).isEqualTo(
                1L
        );

        verify(
                chapterRepositoryPort
        ).save(
                chapter,
                1L
        );
    }

    @Test
    @DisplayName(
            "Sắp xếp lại Chapter PUBLISHED thành công"
    )
    void shouldReorderPublishedChapter() {

        Chapter chapter =
                createDraftChapter();

        chapter.publish(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        60
                )
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                2L
        );

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
        );

        when(
                chapterRepositoryPort
                        .existsByVolumeIdAndSortOrder(
                                VOLUME_ID,
                                4
                        )
        ).thenReturn(
                false
        );

        when(
                clockPort.now()
        ).thenReturn(
                REORDERED_AT
        );

        when(
                chapterRepositoryPort.save(
                        chapter,
                        2L
                )
        ).thenReturn(
                chapter
        );

        ChapterDTO result =
                useCase.execute(
                        new ReorderChapterCommand(
                                CHAPTER_ID,
                                4,
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.PUBLISHED
        );

        assertThat(
                chapter.getSortOrder()
        ).isEqualTo(
                4
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                3L
        );

        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "PUBLISHED"
        );

        assertThat(
                result.sortOrder()
        ).isEqualTo(
                4
        );

        verify(
                chapterRepositoryPort
        ).save(
                chapter,
                2L
        );
    }

    @Test
    @DisplayName(
            "Cho phép giữ nguyên sortOrder của chính Chapter"
    )
    void shouldAllowCurrentSortOrder() {

        Chapter chapter =
                createDraftChapter();

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
        );

        when(
                clockPort.now()
        ).thenReturn(
                REORDERED_AT
        );

        when(
                chapterRepositoryPort.save(
                        chapter,
                        1L
                )
        ).thenReturn(
                chapter
        );

        ChapterDTO result =
                useCase.execute(
                        new ReorderChapterCommand(
                                CHAPTER_ID,
                                1,
                                OTHER_ADMIN_ID
                        )
                );

        /*
         * Không kiểm duplicate vì vị trí 1
         * đang thuộc chính Chapter này.
         */
        verify(
                chapterRepositoryPort,
                never()
        ).existsByVolumeIdAndSortOrder(
                any(UUID.class),
                anyInt()
        );

        assertThat(
                result.sortOrder()
        ).isEqualTo(
                1
        );

        /*
         * Domain hiện vẫn coi đây là một reorder,
         * nên aggregateVersion tăng.
         */
        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                2L
        );

        assertThat(
                result.contentVersion()
        ).isEqualTo(
                1L
        );

        verify(
                chapterRepositoryPort
        ).save(
                chapter,
                1L
        );
    }

    @Test
    @DisplayName(
            "Từ chối sortOrder đã tồn tại trong cùng Volume"
    )
    void shouldRejectDuplicateSortOrder() {

        Chapter chapter =
                createDraftChapter();

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
        );

        when(
                chapterRepositoryPort
                        .existsByVolumeIdAndSortOrder(
                                VOLUME_ID,
                                2
                        )
        ).thenReturn(
                true
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new ReorderChapterCommand(
                                CHAPTER_ID,
                                2,
                                OTHER_ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        ChapterSortOrderAlreadyExistsException.class
                );

        assertThat(
                chapter.getSortOrder()
        ).isEqualTo(
                1
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                1L
        );

        verify(
                clockPort,
                never()
        ).now();

        verify(
                chapterRepositoryPort,
                never()
        ).save(
                any(Chapter.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối reorder Chapter không tồn tại"
    )
    void shouldRejectMissingChapter() {

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        reorderCommand()
                )
        )
                .isInstanceOf(
                        ChapterNotFoundException.class
                )
                .hasMessage(
                        "Không tìm thấy chương: "
                                + CHAPTER_ID
                );

        verify(
                chapterRepositoryPort,
                never()
        ).existsByVolumeIdAndSortOrder(
                any(UUID.class),
                anyInt()
        );

        verify(
                clockPort,
                never()
        ).now();

        verify(
                chapterRepositoryPort,
                never()
        ).save(
                any(Chapter.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối reorder Chapter ARCHIVED"
    )
    void shouldRejectArchivedChapter() {

        Chapter chapter =
                createDraftChapter();

        chapter.archive(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        60
                )
        );

        long versionBefore =
                chapter.getAggregateVersion();

        int sortOrderBefore =
                chapter.getSortOrder();

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
        );

        when(
                chapterRepositoryPort
                        .existsByVolumeIdAndSortOrder(
                                VOLUME_ID,
                                5
                        )
        ).thenReturn(
                false
        );

        when(
                clockPort.now()
        ).thenReturn(
                REORDERED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new ReorderChapterCommand(
                                CHAPTER_ID,
                                5,
                                OTHER_ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Không thể sắp xếp lại chương đã lưu trữ."
                );

        assertThat(
                chapter.getSortOrder()
        ).isEqualTo(
                sortOrderBefore
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                versionBefore
        );

        verify(
                chapterRepositoryPort,
                never()
        ).save(
                any(Chapter.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối ReorderChapterCommand null"
    )
    void shouldRejectNullCommand() {

        assertThatThrownBy(() ->
                useCase.execute(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Reorder chapter command không được để trống."
                );

        verify(
                chapterRepositoryPort,
                never()
        ).findById(
                any(UUID.class)
        );

        verify(
                chapterRepositoryPort,
                never()
        ).save(
                any(Chapter.class),
                anyLong()
        );
    }

    private ReorderChapterCommand reorderCommand() {
        return new ReorderChapterCommand(
                CHAPTER_ID,
                5,
                OTHER_ADMIN_ID
        );
    }

    private Chapter createDraftChapter() {
        return Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                1,
                "Chương Một",
                new Slug(
                        "chuong-mot"
                ),
                "Tóm tắt.",
                "Nội dung chương.",
                ADMIN_ID,
                CREATED_AT
        );
    }
}