package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
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
import static org.mockito.ArgumentMatchers.anyLong;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchiveChapterUseCaseTest {

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
                    "2026-08-18T02:00:00Z"
            );

    private static final Instant ARCHIVED_AT =
            Instant.parse(
                    "2026-08-18T03:00:00Z"
            );

    @Mock
    private ChapterRepositoryPort
            chapterRepositoryPort;

    @Mock
    private ClockPort
            clockPort;

    private ArchiveChapterUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new ArchiveChapterUseCase(
                        chapterRepositoryPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Lưu trữ Chapter DRAFT thành công"
    )
    void shouldArchiveDraftChapter() {

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
                ARCHIVED_AT
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
                        new ArchiveChapterCommand(
                                CHAPTER_ID,
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.ARCHIVED
        );

        assertThat(
                chapter.getArchivedBy()
        ).isEqualTo(
                OTHER_ADMIN_ID
        );

        assertThat(
                chapter.getArchivedAt()
        ).isEqualTo(
                ARCHIVED_AT
        );

        assertThat(
                chapter.getUpdatedBy()
        ).isEqualTo(
                OTHER_ADMIN_ID
        );

        assertThat(
                chapter.getUpdatedAt()
        ).isEqualTo(
                ARCHIVED_AT
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                2L
        );

        /*
         * Archive không thay content.
         */
        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "ARCHIVED"
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                2L
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
            "Lưu trữ Chapter PUBLISHED thành công"
    )
    void shouldArchivePublishedChapter() {

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
                clockPort.now()
        ).thenReturn(
                ARCHIVED_AT
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
                        new ArchiveChapterCommand(
                                CHAPTER_ID,
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.ARCHIVED
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
                "ARCHIVED"
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                3L
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
            "Từ chối archive Chapter đã ARCHIVED"
    )
    void shouldRejectAlreadyArchivedChapter() {

        Chapter chapter =
                createDraftChapter();

        chapter.archive(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        60
                )
        );

        long aggregateVersionBefore =
                chapter.getAggregateVersion();

        long contentVersionBefore =
                chapter.getContentVersion();

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
                ARCHIVED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new ArchiveChapterCommand(
                                CHAPTER_ID,
                                OTHER_ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.ARCHIVED
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                aggregateVersionBefore
        );

        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                contentVersionBefore
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
            "Từ chối archive Chapter không tồn tại"
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
                        command()
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
            "Từ chối ArchiveChapterCommand null"
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
                        "Archive chapter command không được để trống."
                );

        verify(
                chapterRepositoryPort,
                never()
        ).findById(
                any(UUID.class)
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
            "Từ chối Chapter ID null"
    )
    void shouldRejectNullChapterId() {

        assertThatThrownBy(() ->
                useCase.execute(
                        new ArchiveChapterCommand(
                                null,
                                ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Chapter ID không được để trống."
                );

        verify(
                chapterRepositoryPort,
                never()
        ).findById(
                any(UUID.class)
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

    private ArchiveChapterCommand command() {
        return new ArchiveChapterCommand(
                CHAPTER_ID,
                OTHER_ADMIN_ID
        );
    }

    private Chapter createDraftChapter() {
        return Chapter.createDraft(                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương Một",
                new Slug(
                        "chuong-mot"
                ),
                "Tóm tắt.",
                "Nội dung chương.",
                ADMIN_ID,
                CREATED_AT);
    }
}