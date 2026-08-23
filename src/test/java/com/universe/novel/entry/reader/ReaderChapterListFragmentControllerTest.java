package com.universe.novel.entry.reader;

import com.universe.novel.application.reader.GetReaderChapterListUseCase;
import com.universe.novel.contracts.dto.reader.ReaderChapterListItemDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReaderChapterListFragmentControllerTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID CHAPTER_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private GetReaderChapterListUseCase
            getReaderChapterListUseCase;

    private ReaderChapterListFragmentController
            controller;

    @BeforeEach
    void setUp() {

        getReaderChapterListUseCase =
                mock(
                        GetReaderChapterListUseCase.class
                );

        controller =
                new ReaderChapterListFragmentController(
                        getReaderChapterListUseCase
                );
    }

    @Test
    @DisplayName(
            "Trả Thymeleaf fragment chứa Published Chapters"
    )
    void shouldReturnChapterListFragment() {

        ReaderChapterListItemDTO chapter =
                new ReaderChapterListItemDTO(
                        CHAPTER_ID,
                        81,
                        "Ly Châu Động Thiên",
                        "chuong-81-ly-chau-dong-thien"
                );

        when(
                getReaderChapterListUseCase.execute(
                        VOLUME_ID
                )
        ).thenReturn(
                List.of(chapter)
        );

        Model model =
                new ExtendedModelMap();

        String view =
                controller.getChapterList(
                        VOLUME_ID,
                        model
                );

        assertThat(view)
                .isEqualTo(
                        "novel/chapter-list :: chapterList"
                );

        assertThat(
                model.getAttribute(
                        "chapters"
                )
        ).isEqualTo(
                List.of(chapter)
        );

        verify(
                getReaderChapterListUseCase
        ).execute(
                VOLUME_ID
        );
    }

    @Test
    @DisplayName(
            "Fragment vẫn render khi Volume không có Published Chapter"
    )
    void shouldReturnFragmentWithEmptyChapterList() {

        when(
                getReaderChapterListUseCase.execute(
                        VOLUME_ID
                )
        ).thenReturn(
                List.of()
        );

        Model model =
                new ExtendedModelMap();

        String view =
                controller.getChapterList(
                        VOLUME_ID,
                        model
                );

        assertThat(view)
                .isEqualTo(
                        "novel/chapter-list :: chapterList"
                );

        assertThat(
                model.getAttribute(
                        "chapters"
                )
        ).isEqualTo(
                List.of()
        );

        verify(
                getReaderChapterListUseCase
        ).execute(
                VOLUME_ID
        );
    }
}