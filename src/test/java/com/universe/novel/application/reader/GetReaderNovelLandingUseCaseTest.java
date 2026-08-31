package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderNovelLandingQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelOverviewDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeListItemDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetReaderNovelLandingUseCaseTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    @Mock
    private ReaderNovelLandingQueryPort
            readerNovelLandingQueryPort;

    private GetReaderNovelLandingUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new GetReaderNovelLandingUseCase(
                        readerNovelLandingQueryPort
                );
    }

    @Test
    @DisplayName(
            "Ghép Novel Overview, Published Volumes và First Published Chapter thành Reader Landing"
    )
    void shouldGetReaderNovelLanding() {

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

        ReaderChapterNavigationDTO firstChapter =
                new ReaderChapterNavigationDTO(
                        1,
                        "Khởi Đầu",
                        "chuong-1-khoi-dau"
                );

        when(
                readerNovelLandingQueryPort.findNovelOverview()
        ).thenReturn(
                Optional.of(
                        novel
                )
        );

        when(
                readerNovelLandingQueryPort.findPublishedVolumes()
        ).thenReturn(
                List.of(
                        volume
                )
        );

        when(
                readerNovelLandingQueryPort.findFirstPublishedChapter()
        ).thenReturn(
                Optional.of(
                        firstChapter
                )
        );

        ReaderNovelLandingDTO result =
                useCase.execute();

        assertThat(
                result.novel()
        ).isEqualTo(
                novel
        );

        assertThat(
                result.volumes()
        ).containsExactly(
                volume
        );

        assertThat(
                result.firstChapter()
        ).isEqualTo(
                firstChapter
        );

        verify(
                readerNovelLandingQueryPort
        ).findNovelOverview();

        verify(
                readerNovelLandingQueryPort
        ).findPublishedVolumes();

        verify(
                readerNovelLandingQueryPort
        ).findFirstPublishedChapter();
    }

    @Test
    @DisplayName(
            "Cho phép Reader Landing không có Published Volume"
    )
    void shouldAllowReaderLandingWithoutPublishedVolumes() {

        ReaderNovelOverviewDTO novel =
                new ReaderNovelOverviewDTO(
                        "Kiếm Lai",
                        "kiem-lai",
                        "Phong Hỏa Hí Chư Hầu",
                        "Giới thiệu Kiếm Lai.",
                        null,
                        "ONGOING"
                );

        when(
                readerNovelLandingQueryPort.findNovelOverview()
        ).thenReturn(
                Optional.of(
                        novel
                )
        );

        when(
                readerNovelLandingQueryPort.findPublishedVolumes()
        ).thenReturn(
                List.of()
        );

        ReaderNovelLandingDTO result =
                useCase.execute();

        assertThat(
                result.novel()
        ).isEqualTo(
                novel
        );

        assertThat(
                result.volumes()
        ).isEmpty();
    }

    @Test
    @DisplayName(
            "Từ chối Reader Landing khi Novel Profile không tồn tại"
    )
    void shouldRejectMissingNovelProfile() {

        when(
                readerNovelLandingQueryPort.findNovelOverview()
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(
                () -> useCase.execute()
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Không tìm thấy thông tin tiểu thuyết."
                );

        verify(
                readerNovelLandingQueryPort
        ).findNovelOverview();

        verifyNoInteractionsWithVolumeQuery();
    }

    private void verifyNoInteractionsWithVolumeQuery() {
        verify(
                readerNovelLandingQueryPort,
                org.mockito.Mockito.never()
        ).findPublishedVolumes();
    }
}