package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteDraftChapterUseCaseTest {

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

    @Mock
    private ChapterRepositoryPort
            chapterRepositoryPort;

    private DeleteDraftChapterUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new DeleteDraftChapterUseCase(
                        chapterRepositoryPort
                );
    }

    @Test
    @DisplayName(
            "Xóa Chapter DRAFT thành công"
    )
    void shouldDeleteDraftChapter() {

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

        useCase.execute(
                new DeleteDraftChapterCommand(
                        CHAPTER_ID,
                        OTHER_ADMIN_ID
                )
        );

        verify(
                chapterRepositoryPort
        ).delete(
                chapter,
                1L
        );
    }

    @Test
    @DisplayName(
            "Từ chối xóa Chapter PUBLISHED"
    )
    void shouldRejectPublishedChapter() {

        Chapter chapter =
                createDraftChapter();

        chapter.publish(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        60
                )
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

        assertThatThrownBy(() ->
                useCase.execute(
                        command()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ chương ở trạng thái DRAFT mới được xóa."
                );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.PUBLISHED
        );

        verify(
                chapterRepositoryPort,
                never()
        ).delete(
                any(Chapter.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối xóa Chapter ARCHIVED"
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

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
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
                        "Chỉ chương ở trạng thái DRAFT mới được xóa."
                );

        verify(
                chapterRepositoryPort,
                never()
        ).delete(
                any(Chapter.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối xóa Chapter không tồn tại"
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
                chapterRepositoryPort,
                never()
        ).delete(
                any(Chapter.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối DeleteDraftChapterCommand null"
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
                        "Delete draft chapter command không được để trống."
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
        ).delete(
                any(Chapter.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối Actor ID null"
    )
    void shouldRejectNullActorId() {

        assertThatThrownBy(() ->
                useCase.execute(
                        new DeleteDraftChapterCommand(
                                CHAPTER_ID,
                                null
                        )
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Actor ID không được để trống."
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
        ).delete(
                any(Chapter.class),
                anyLong()
        );
    }

    private DeleteDraftChapterCommand command() {
        return new DeleteDraftChapterCommand(
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
}
