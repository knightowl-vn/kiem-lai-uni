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
class UnpublishChapterUseCaseTest {

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

    private static final Instant UNPUBLISHED_AT =
            Instant.parse(
                    "2026-08-18T03:00:00Z"
            );

    @Mock
    private ChapterRepositoryPort
            chapterRepositoryPort;

    @Mock
    private ClockPort
            clockPort;

    private UnpublishChapterUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new UnpublishChapterUseCase(
                        chapterRepositoryPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Gỡ xuất bản Chapter PUBLISHED thành công"
    )
    void shouldUnpublishPublishedChapter() {

        Chapter chapter =
                createPublishedChapter();

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
                UNPUBLISHED_AT
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
                        new UnpublishChapterCommand(
                                CHAPTER_ID,
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.DRAFT
        );

        assertThat(
                chapter.getPublishedBy()
        ).isNull();

        assertThat(
                chapter.getPublishedAt()
        ).isNull();

        assertThat(
                chapter.getUpdatedBy()
        ).isEqualTo(
                OTHER_ADMIN_ID
        );

        assertThat(
                chapter.getUpdatedAt()
        ).isEqualTo(
                UNPUBLISHED_AT
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
                "DRAFT"
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
            "Từ chối unpublish Chapter DRAFT"
    )
    void shouldRejectDraftChapter() {

        Chapter chapter =
                createDraftChapter();

        long aggregateVersionBefore =
                chapter.getAggregateVersion();

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
                UNPUBLISHED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        command()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ chương ở trạng thái PUBLISHED mới được gỡ xuất bản."
                );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.DRAFT
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                aggregateVersionBefore
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
            "Từ chối unpublish Chapter không tồn tại"
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
            "Từ chối UnpublishChapterCommand null"
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
                        "Unpublish chapter command không được để trống."
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

    private UnpublishChapterCommand command() {
        return new UnpublishChapterCommand(
                CHAPTER_ID,
                OTHER_ADMIN_ID
        );
    }

    private Chapter createDraftChapter() {
        return Chapter.createDraft(        CHAPTER_ID,
        VOLUME_ID,
        1,
        "Chương Một",
        new Slug(
                        "quyen-1-chuong-1"
                ),
        "Tóm tắt.",
        "Nội dung chương.",
        ADMIN_ID,
        CREATED_AT);
    }

    private Chapter createPublishedChapter() {

        Chapter chapter =
                createDraftChapter();

        chapter.publish(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        60
                )
        );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.PUBLISHED
        );

        return chapter;
    }
}
