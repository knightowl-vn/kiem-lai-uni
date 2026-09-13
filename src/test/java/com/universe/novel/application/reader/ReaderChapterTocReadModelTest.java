package com.universe.novel.application.reader;

import com.universe.novel.application.ports.PublicReaderNavigationIndexCachePort;
import com.universe.novel.application.ports.PublicReaderRenderedChapterCachePort;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterRenderedSnapshotDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeSummaryDTO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderChapterTocReadModelTest {

    @Mock
    private PublicReaderNavigationIndexCachePort navigationCachePort;

    @Test
    @DisplayName("ReaderChapterTocItemDTO là lightweight DTO chỉ chứa navigation metadata (chapterNumber, title, slug)")
    void tocDtoContainsOnlyNavigationMetadata() {
        ReaderChapterTocItemDTO item = new ReaderChapterTocItemDTO(1, "Khởi Đầu", "chuong-1-khoi-dau");

        assertThat(item.chapterNumber()).isEqualTo(1);
        assertThat(item.title()).isEqualTo("Khởi Đầu");
        assertThat(item.slug()).isEqualTo("chuong-1-khoi-dau");

        // Record has exactly 3 component getters
        assertThat(ReaderChapterTocItemDTO.class.getRecordComponents()).hasSize(3);
    }

    @Test
    @DisplayName("TOC được sắp xếp toàn cục theo chapterNumber ASC và cho phép khoảng nhảy (gaps)")
    void tocIsOrderedGloballyByChapterNumberAscWithGapsAllowed() {
        // Gaps: 1 -> 3 -> 7 -> 15
        List<ReaderChapterTocItemDTO> tocWithGaps = List.of(
                new ReaderChapterTocItemDTO(1, "Chương 1", "chuong-1"),
                new ReaderChapterTocItemDTO(3, "Chương 3", "chuong-3"),
                new ReaderChapterTocItemDTO(7, "Chương 7", "chuong-7"),
                new ReaderChapterTocItemDTO(15, "Chương 15", "chuong-15")
        );

        assertThat(tocWithGaps)
                .isSortedAccordingTo(Comparator.comparingInt(ReaderChapterTocItemDTO::chapterNumber));
        assertThat(tocWithGaps.get(0).chapterNumber()).isEqualTo(1);
        assertThat(tocWithGaps.get(1).chapterNumber()).isEqualTo(3);
        assertThat(tocWithGaps.get(2).chapterNumber()).isEqualTo(7);
        assertThat(tocWithGaps.get(3).chapterNumber()).isEqualTo(15);
    }

    @Test
    @DisplayName("GetReaderChapterDetailUseCase expose Previous/Next chapter được derive từ chung 1 TOC snapshot")
    void useCaseDerivesPreviousAndNextFromTocSnapshot() {
        PublicReaderRenderedChapterCachePort cachePort = mock(PublicReaderRenderedChapterCachePort.class);
        GetReaderChapterDetailUseCase useCase = new GetReaderChapterDetailUseCase(
                cachePort,
                mock(PublicReaderRenderedChapterLoader.class),
                navigationCachePort,
                mock(PublicReaderNavigationIndexLoader.class)
        );

        UUID chapterId = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        String slug = "chuong-3";

        com.universe.novel.contracts.dto.reader.ReaderVolumeSummaryDTO volume = new com.universe.novel.contracts.dto.reader.ReaderVolumeSummaryDTO(volumeId, "Quyển 1", "quyen-1", 1);
        com.universe.novel.contracts.dto.reader.ReaderChapterRenderedSnapshotDTO snapshot = new com.universe.novel.contracts.dto.reader.ReaderChapterRenderedSnapshotDTO(
                chapterId, 3, "Chương 3", slug, "<p>HTML</p>", volume
        );

        List<ReaderChapterTocItemDTO> toc = List.of(
                new ReaderChapterTocItemDTO(1, "Chương 1", "chuong-1"),
                new ReaderChapterTocItemDTO(3, "Chương 3", "chuong-3"),
                new ReaderChapterTocItemDTO(7, "Chương 7", "chuong-7")
        );

        when(cachePort.getOrLoad(any(), any())).thenReturn(snapshot);
        when(navigationCachePort.getOrLoad(any())).thenReturn(toc);

        ReaderChapterDetailDTO result = useCase.execute(slug);

        assertThat(result).isNotNull();
        assertThat(result.tableOfContents()).containsExactlyElementsOf(toc);
        assertThat(result.previousChapter().chapterNumber()).isEqualTo(1);
        assertThat(result.previousChapter().slug()).isEqualTo("chuong-1");
        assertThat(result.nextChapter().chapterNumber()).isEqualTo(7);
        assertThat(result.nextChapter().slug()).isEqualTo("chuong-7");

        verify(navigationCachePort).getOrLoad(any());
    }
}
