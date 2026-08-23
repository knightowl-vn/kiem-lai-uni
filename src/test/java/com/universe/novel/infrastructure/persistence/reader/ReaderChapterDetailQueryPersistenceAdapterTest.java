package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReaderChapterDetailQueryPort.ReaderChapterRecord;
import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;
import com.universe.novel.infrastructure.persistence.chapter.ReaderChapterDetailProjection;
import com.universe.novel.infrastructure.persistence.chapter.ReaderChapterListItemProjection;
import com.universe.novel.infrastructure.persistence.chapter.SpringDataChapterJpaRepository;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderChapterDetailQueryPersistenceAdapterTest {

    private static final String CHAPTER_ID_STR =
            "11111111-1111-1111-1111-111111111111";

    private static final String VOLUME_ID_STR =
            "22222222-2222-2222-2222-222222222222";

    @Mock
    private SpringDataChapterJpaRepository
            chapterRepository;

    private ReaderChapterDetailQueryPersistenceAdapter
            adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReaderChapterDetailQueryPersistenceAdapter(
                chapterRepository
        );
    }

    @Test
    @DisplayName("Ánh xạ chính xác từ ReaderChapterDetailProjection sang ReaderChapterRecord")
    void shouldMapDetailProjectionToRecord() {
        ReaderChapterDetailProjection projection = mock(ReaderChapterDetailProjection.class);
        when(projection.getId()).thenReturn(CHAPTER_ID_STR);
        when(projection.getVolumeId()).thenReturn(VOLUME_ID_STR);
        when(projection.getChapterNumber()).thenReturn(10);
        when(projection.getTitle()).thenReturn("Chương 10");
        when(projection.getSlug()).thenReturn("chuong-10");
        when(projection.getContent()).thenReturn("Nội dung chương 10 markdown");
        when(projection.getVolumeTitle()).thenReturn("Quyển 1");
        when(projection.getVolumeSlug()).thenReturn("quyen-1");
        when(projection.getVolumeSortOrder()).thenReturn(1);

        when(chapterRepository.findPublishedReaderChapterBySlug("chuong-10"))
                .thenReturn(Optional.of(projection));

        Optional<ReaderChapterRecord> result =
                adapter.findPublishedChapterBySlug("chuong-10");

        assertThat(result).isPresent();
        ReaderChapterRecord record = result.get();
        assertThat(record.id()).isEqualTo(UUID.fromString(CHAPTER_ID_STR));
        assertThat(record.volumeId()).isEqualTo(UUID.fromString(VOLUME_ID_STR));
        assertThat(record.chapterNumber()).isEqualTo(10);
        assertThat(record.title()).isEqualTo("Chương 10");
        assertThat(record.slug()).isEqualTo("chuong-10");
        assertThat(record.rawContent()).isEqualTo("Nội dung chương 10 markdown");
        assertThat(record.volumeTitle()).isEqualTo("Quyển 1");
        assertThat(record.volumeSlug()).isEqualTo("quyen-1");
        assertThat(record.volumeSortOrder()).isEqualTo(1);

        verify(chapterRepository).findPublishedReaderChapterBySlug("chuong-10");
    }

    @Test
    @DisplayName("Ánh xạ chính xác previous/next projection sang ReaderChapterNavigationDTO")
    void shouldMapNavigationProjections() {
        ReaderChapterListItemProjection prevProjection = mock(ReaderChapterListItemProjection.class);
        when(prevProjection.getChapterNumber()).thenReturn(9);
        when(prevProjection.getTitle()).thenReturn("Chương 9");
        when(prevProjection.getSlug()).thenReturn("chuong-9");

        ReaderChapterListItemProjection nextProjection = mock(ReaderChapterListItemProjection.class);
        when(nextProjection.getChapterNumber()).thenReturn(11);
        when(nextProjection.getTitle()).thenReturn("Chương 11");
        when(nextProjection.getSlug()).thenReturn("chuong-11");

        when(chapterRepository.findPreviousPublishedReaderChapter(10))
                .thenReturn(Optional.of(prevProjection));
        when(chapterRepository.findNextPublishedReaderChapter(10))
                .thenReturn(Optional.of(nextProjection));

        Optional<ReaderChapterNavigationDTO> prevResult =
                adapter.findPreviousPublishedChapter(10);
        Optional<ReaderChapterNavigationDTO> nextResult =
                adapter.findNextPublishedChapter(10);

        assertThat(prevResult).isPresent();
        assertThat(prevResult.get().chapterNumber()).isEqualTo(9);
        assertThat(prevResult.get().title()).isEqualTo("Chương 9");
        assertThat(prevResult.get().slug()).isEqualTo("chuong-9");

        assertThat(nextResult).isPresent();
        assertThat(nextResult.get().chapterNumber()).isEqualTo(11);
        assertThat(nextResult.get().title()).isEqualTo("Chương 11");
        assertThat(nextResult.get().slug()).isEqualTo("chuong-11");

        verify(chapterRepository).findPreviousPublishedReaderChapter(10);
        verify(chapterRepository).findNextPublishedReaderChapter(10);
    }

    @Test
    @DisplayName("Ánh xạ danh sách projection sang danh sách ReaderChapterTocItemDTO sắp xếp theo chapter_number")
    void shouldMapTocProjections() {
        ReaderChapterListItemProjection toc1 = mock(ReaderChapterListItemProjection.class);
        when(toc1.getChapterNumber()).thenReturn(1);
        when(toc1.getTitle()).thenReturn("Khởi Đầu");
        when(toc1.getSlug()).thenReturn("chuong-1-khoi-dau");

        ReaderChapterListItemProjection toc2 = mock(ReaderChapterListItemProjection.class);
        when(toc2.getChapterNumber()).thenReturn(5);
        when(toc2.getTitle()).thenReturn("Họa Phúc");
        when(toc2.getSlug()).thenReturn("chuong-5-hoa-phuc");

        when(chapterRepository.findAllPublishedReaderChaptersOrderByChapterNumber())
                .thenReturn(List.of(toc1, toc2));

        List<ReaderChapterTocItemDTO> result = adapter.findAllPublishedChaptersForToc();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).chapterNumber()).isEqualTo(1);
        assertThat(result.get(0).title()).isEqualTo("Khởi Đầu");
        assertThat(result.get(0).slug()).isEqualTo("chuong-1-khoi-dau");

        assertThat(result.get(1).chapterNumber()).isEqualTo(5);
        assertThat(result.get(1).title()).isEqualTo("Họa Phúc");
        assertThat(result.get(1).slug()).isEqualTo("chuong-5-hoa-phuc");

        verify(chapterRepository).findAllPublishedReaderChaptersOrderByChapterNumber();
    }
}
