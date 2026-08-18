package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetChapterDetailUseCaseTest {

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
                    "2026-08-18T04:00:00Z"
            );

    @Mock
    private ChapterRepositoryPort
            chapterRepositoryPort;

    private GetChapterDetailUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new GetChapterDetailUseCase(
                        chapterRepositoryPort
                );
    }

    @Test
    @DisplayName(
            "Lấy chi tiết Chapter thành công"
    )
    void shouldGetChapterDetail() {

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

        ChapterDTO result =
                useCase.execute(
                        CHAPTER_ID
                );

        assertThat(
                result.id()
        ).isEqualTo(
                CHAPTER_ID
        );

        assertThat(
                result.volumeId()
        ).isEqualTo(
                VOLUME_ID
        );

        assertThat(
                result.chapterNumber()
        ).isEqualTo(
                1
        );

        assertThat(
                result.sortOrder()
        ).isEqualTo(
                1
        );

        assertThat(
                result.title()
        ).isEqualTo(
                "Chương Một"
        );

        assertThat(
                result.slug()
        ).isEqualTo(
                "chuong-mot"
        );

        assertThat(
                result.summary()
        ).isEqualTo(
                "Tóm tắt chương."
        );

        assertThat(
                result.content()
        ).isEqualTo(
                "Nội dung chương."
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "DRAFT"
        );

        assertThat(
                result.createdBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                result.updatedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                result.createdAt()
        ).isEqualTo(
                CREATED_AT
        );

        assertThat(
                result.updatedAt()
        ).isEqualTo(
                CREATED_AT
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                result.contentVersion()
        ).isEqualTo(
                1L
        );

        verify(
                chapterRepositoryPort
        ).findById(
                CHAPTER_ID
        );
    }

    @Test
    @DisplayName(
            "Từ chối khi Chapter không tồn tại"
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
                        CHAPTER_ID
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
                chapterRepositoryPort
        ).findById(
                CHAPTER_ID
        );
    }

    @Test
    @DisplayName(
            "Từ chối Chapter ID null"
    )
    void shouldRejectNullChapterId() {

        assertThatThrownBy(() ->
                useCase.execute(
                        null
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
                "Tóm tắt chương.",
                "Nội dung chương.",
                ADMIN_ID,
                CREATED_AT
        );
    }
}