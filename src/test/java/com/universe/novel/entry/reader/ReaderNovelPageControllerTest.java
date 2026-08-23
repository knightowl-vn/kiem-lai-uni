package com.universe.novel.entry.reader;

import com.universe.novel.application.reader.GetReaderNovelLandingUseCase;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelOverviewDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeListItemDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.ui.ExtendedModelMap;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderNovelPageControllerTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    @Mock
    private GetReaderNovelLandingUseCase
            getReaderNovelLandingUseCase;

    private ReaderNovelPageController
            controller;

    @BeforeEach
    void setUp() {
        controller =
                new ReaderNovelPageController(
                        getReaderNovelLandingUseCase
                );
    }

    @Test
    @DisplayName(
            "Hiển thị trang Reader Novel với thông tin truyện và Published Volumes"
    )
    void shouldShowReaderNovelLandingPage() {

        ReaderNovelOverviewDTO novel =
                new ReaderNovelOverviewDTO(
                        "Kiếm Lai",
                        "kiem-lai",
                        "Phong Hỏa Hí Chư Hầu",
                        "Giới thiệu Kiếm Lai.",
                        "/images/novel/kiem-lai.jpg",
                        "ONGOING"
                );

        ReaderVolumeListItemDTO volume =
                new ReaderVolumeListItemDTO(
                        VOLUME_ID,
                        "Quyển Một - Lung Trung Tước",
                        "quyen-1",
                        1,
                        81L
                );

        ReaderNovelLandingDTO landing =
                new ReaderNovelLandingDTO(
                        novel,
                        List.of(
                                volume
                        )
                );

        when(
                getReaderNovelLandingUseCase.execute()
        ).thenReturn(
                landing
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        String viewName =
                controller.landingPage(
                        model
                );

        assertThat(
                viewName
        ).isEqualTo(
                "novel/index"
        );

        assertThat(
                model.getAttribute(
                        "novel"
                )
        ).isEqualTo(
                novel
        );

        assertThat(
                model.getAttribute(
                        "volumes"
                )
        ).isEqualTo(
                List.of(
                        volume
                )
        );

        assertThat(
                model.getAttribute(
                        "pageTitle"
                )
        ).isEqualTo(
                "Kiếm Lai"
        );

        verify(
                getReaderNovelLandingUseCase
        ).execute();
    }

    @Test
    @DisplayName(
            "Hiển thị Reader Novel khi chưa có Published Volume"
    )
    void shouldShowReaderNovelLandingWithoutPublishedVolumes() {

        ReaderNovelOverviewDTO novel =
                new ReaderNovelOverviewDTO(
                        "Kiếm Lai",
                        "kiem-lai",
                        "Phong Hỏa Hí Chư Hầu",
                        "Giới thiệu Kiếm Lai.",
                        null,
                        "ONGOING"
                );

        ReaderNovelLandingDTO landing =
                new ReaderNovelLandingDTO(
                        novel,
                        List.of()
                );

        when(
                getReaderNovelLandingUseCase.execute()
        ).thenReturn(
                landing
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        String viewName =
                controller.landingPage(
                        model
                );

        assertThat(
                viewName
        ).isEqualTo(
                "novel/index"
        );

        assertThat(
                model.getAttribute(
                        "novel"
                )
        ).isEqualTo(
                novel
        );

        assertThat(
                model.getAttribute(
                        "volumes"
                )
        ).isEqualTo(
                List.of()
        );

        verify(
                getReaderNovelLandingUseCase
        ).execute();
    }
}