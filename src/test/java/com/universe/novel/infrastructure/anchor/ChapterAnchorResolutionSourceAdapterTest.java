package com.universe.novel.infrastructure.anchor;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.infrastructure.markdown.CommonMarkNovelMarkdownRenderer;
import com.universe.novel.infrastructure.markdown.CommonMarkReaderCanonicalBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChapterAnchorResolutionSourceAdapterTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private ChapterRepositoryPort chapterRepositoryPort;

    private ChapterAnchorResolutionSourceAdapter adapter;

    @BeforeEach
    void setUp() {
        CommonMarkNovelMarkdownRenderer renderer = new CommonMarkNovelMarkdownRenderer();
        CommonMarkReaderCanonicalBlocks canonicalBlocks = new CommonMarkReaderCanonicalBlocks(renderer);
        adapter = new ChapterAnchorResolutionSourceAdapter(chapterRepositoryPort, canonicalBlocks);
    }

    @Test
    @DisplayName("Nạp snapshot chương hiện tại: bảo toàn chapterId, contentVersion, thứ tự block, blockKey và canonicalText")
    void shouldLoadCurrentChapterDocumentSnapshotCorrectly() {
        Chapter chapter = mock(Chapter.class);
        when(chapter.getId()).thenReturn(CHAPTER_ID);
        when(chapter.getContentVersion()).thenReturn(5L);
        when(chapter.getContent()).thenReturn("## Tiêu đề chương\n\nĐoạn văn mở đầu.\n\nĐoạn văn kết thúc.");
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        ChapterAnchorDocumentSnapshot snapshot = adapter.loadCurrent(CHAPTER_ID);

        assertThat(snapshot.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(snapshot.contentVersion()).isEqualTo(5L);
        assertThat(snapshot.blocks()).hasSize(3);

        // Block 0: heading
        assertThat(snapshot.blocks().get(0).blockKey()).startsWith("blk-");
        assertThat(snapshot.blocks().get(0).canonicalText()).isEqualTo("Tiêu đề chương");

        // Block 1: paragraph
        assertThat(snapshot.blocks().get(1).blockKey()).startsWith("blk-");
        assertThat(snapshot.blocks().get(1).canonicalText()).isEqualTo("Đoạn văn mở đầu.");

        // Block 2: paragraph
        assertThat(snapshot.blocks().get(2).blockKey()).startsWith("blk-");
        assertThat(snapshot.blocks().get(2).canonicalText()).isEqualTo("Đoạn văn kết thúc.");
    }

    @Test
    @DisplayName("Ném ChapterNotFoundException khi không tìm thấy chương trong ChapterRepositoryPort")
    void shouldThrowWhenChapterNotFound() {
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.loadCurrent(CHAPTER_ID))
                .isInstanceOf(ChapterNotFoundException.class)
                .hasMessageContaining(CHAPTER_ID.toString());
    }
}
