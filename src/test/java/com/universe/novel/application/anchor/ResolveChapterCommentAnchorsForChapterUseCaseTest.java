package com.universe.novel.application.anchor;

import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResolveChapterCommentAnchorsForChapterUseCase Unit Tests")
class ResolveChapterCommentAnchorsForChapterUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOT_2 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOT_3 = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @Mock
    private ChapterCommentAnchorRepositoryPort anchorRepositoryPort;

    @Mock
    private ChapterAnchorResolutionSourcePort resolutionSourcePort;

    private ChapterCommentAnchorResolver resolver;
    private ResolveChapterCommentAnchorsForChapterUseCase useCase;

    @BeforeEach
    void setUp() {
        resolver = new ChapterCommentAnchorResolver();
        useCase = new ResolveChapterCommentAnchorsForChapterUseCase(anchorRepositoryPort, resolutionSourcePort, resolver);
    }

    @Test
    @DisplayName("execute: throws NullPointerException when chapterId is null")
    void shouldThrowWhenChapterIdNull() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("chapterId cannot be null");
    }

    @Test
    @DisplayName("execute: returns empty view when no anchors exist for chapter without loading snapshot")
    void shouldReturnEmptyViewWhenNoAnchors() {
        when(anchorRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of());

        ChapterAnchorResolutionBulkView result = useCase.execute(CHAPTER_ID);

        assertThat(result.resolutions()).isEmpty();
        assertThat(result.orderedBlockKeys()).isEmpty();
        verify(anchorRepositoryPort, times(1)).findByChapterId(CHAPTER_ID);
        verify(resolutionSourcePort, never()).loadCurrent(any());
        verify(anchorRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("execute: loads snapshot exactly once and projects CURRENT, RELOCATED, and STALE rows, preserving block order")
    void shouldResolveAllAnchorsAndProjectMinimalRowsPreservingBlockOrder() {
        // 1. Anchor 1: same version (2L == 2L) -> CURRENT
        ChapterCommentAnchor anchor1 = ChapterCommentAnchor.createBlock(
                ROOT_1, CHAPTER_ID, 2L, "blk-0000000000000001-1", "Block 1", NOW
        );
        // 2. Anchor 2: different version (1L vs 2L), block key still present -> RELOCATED
        ChapterCommentAnchor anchor2 = ChapterCommentAnchor.createBlock(
                ROOT_2, CHAPTER_ID, 1L, "blk-0000000000000002-1", "Block 2", NOW
        );
        // 3. Anchor 3: different version (1L vs 2L), block key and text absent -> STALE
        ChapterCommentAnchor anchor3 = ChapterCommentAnchor.createBlock(
                ROOT_3, CHAPTER_ID, 1L, "blk-0000000000000099-1", "Deleted text", NOW
        );

        when(anchorRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(anchor1, anchor2, anchor3));

        ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                2L,
                List.of(
                        new ReaderBlock("blk-0000000000000001-1", "Block 1"),
                        new ReaderBlock("blk-0000000000000002-1", "Block 2 updated"),
                        new ReaderBlock("blk-0000000000000003-1", "Block 3")
                )
        );
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

        ChapterAnchorResolutionBulkView result = useCase.execute(CHAPTER_ID);

        // Verify findByChapterId called exactly once
        verify(anchorRepositoryPort, times(1)).findByChapterId(CHAPTER_ID);

        // Verify loadCurrent called exactly once
        verify(resolutionSourcePort, times(1)).loadCurrent(CHAPTER_ID);

        // Verify resolutions size and row projections
        assertThat(result.resolutions()).hasSize(3);

        // 4. Result projection preserves one CURRENT row
        ChapterAnchorResolutionBulkView.ResolutionRow row1 = result.resolutions().get(0);
        assertThat(row1.rootCommentId()).isEqualTo(ROOT_1);
        assertThat(row1.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.CURRENT);
        assertThat(row1.resolvedBlockKey()).isEqualTo("blk-0000000000000001-1");

        // 5. Result projection preserves one RELOCATED row
        ChapterAnchorResolutionBulkView.ResolutionRow row2 = result.resolutions().get(1);
        assertThat(row2.rootCommentId()).isEqualTo(ROOT_2);
        assertThat(row2.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
        assertThat(row2.resolvedBlockKey()).isEqualTo("blk-0000000000000002-1");

        // 6. Result projection preserves one STALE row
        ChapterAnchorResolutionBulkView.ResolutionRow row3 = result.resolutions().get(2);
        assertThat(row3.rootCommentId()).isEqualTo(ROOT_3);
        assertThat(row3.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
        assertThat(row3.resolvedBlockKey()).isNull();

        // 7. Reader block order remains unchanged
        assertThat(result.orderedBlockKeys()).containsExactly(
                "blk-0000000000000001-1",
                "blk-0000000000000002-1",
                "blk-0000000000000003-1"
        );

        // 8. Anchor repository save is never called
        verify(anchorRepositoryPort, never()).save(any());
    }
}
