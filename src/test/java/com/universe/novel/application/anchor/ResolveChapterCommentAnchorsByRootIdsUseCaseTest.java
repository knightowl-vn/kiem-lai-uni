package com.universe.novel.application.anchor;

import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolution;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
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
@DisplayName("ResolveChapterCommentAnchorsByRootIdsUseCase Unit Tests")
class ResolveChapterCommentAnchorsByRootIdsUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CHAPTER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ROOT_1 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_2 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOT_3 = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-09-18T01:00:00Z");

    private static final String BLK_1 = "blk-0000000000000001-1";
    private static final String BLK_2 = "blk-0000000000000002-1";
    private static final String BLK_3 = "blk-0000000000000003-1";
    private static final String BLK_4 = "blk-0000000000000004-1";
    private static final String BLK_VALID = "blk-0000000000000005-1";
    private static final String BLK_GHOST_1 = "blk-0000000000000008-1";
    private static final String BLK_GHOST_2 = "blk-0000000000000009-1";

    @Mock
    private ChapterCommentAnchorRepositoryPort anchorRepositoryPort;

    @Mock
    private ChapterAnchorResolutionSourcePort resolutionSourcePort;

    @Mock
    private ChapterCommentAnchorResolver resolver;

    private ResolveChapterCommentAnchorsByRootIdsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ResolveChapterCommentAnchorsByRootIdsUseCase(
                anchorRepositoryPort,
                resolutionSourcePort,
                resolver
        );
    }

    @Test
    @DisplayName("execute: throws NullPointerException when chapterId is null")
    void shouldThrowWhenChapterIdIsNull() {
        assertThatThrownBy(() -> useCase.execute(null, List.of(ROOT_1)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("chapterId cannot be null");
    }

    @Test
    @DisplayName("execute: returns empty list when rootCommentIds is null or empty without calling downstream ports")
    void shouldReturnEmptyWhenRootCommentIdsNullOrEmpty() {
        assertThat(useCase.execute(CHAPTER_ID, null)).isEmpty();
        assertThat(useCase.execute(CHAPTER_ID, Collections.emptyList())).isEmpty();

        verify(anchorRepositoryPort, never()).findByRootCommentIds(any());
        verify(resolutionSourcePort, never()).loadCurrent(any());
        verify(resolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("execute: returns empty list when no anchors found for root IDs without loading snapshot")
    void shouldReturnEmptyWhenNoAnchorsFound() {
        when(anchorRepositoryPort.findByRootCommentIds(List.of(ROOT_1, ROOT_2)))
                .thenReturn(List.of());

        List<ResolvedChapterCommentAnchorView> result = useCase.execute(CHAPTER_ID, List.of(ROOT_1, ROOT_2));

        assertThat(result).isEmpty();
        verify(anchorRepositoryPort, times(1)).findByRootCommentIds(List.of(ROOT_1, ROOT_2));
        verify(resolutionSourcePort, never()).loadCurrent(any());
        verify(resolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("execute: filters out anchors for other chapters and returns empty without loading snapshot")
    void shouldFilterOutAnchorsFromOtherChapters() {
        ChapterCommentAnchor otherAnchor = ChapterCommentAnchor.createBlock(
                ROOT_1, OTHER_CHAPTER_ID, 1L, BLK_1, "Other text", NOW
        );
        when(anchorRepositoryPort.findByRootCommentIds(List.of(ROOT_1)))
                .thenReturn(List.of(otherAnchor));

        List<ResolvedChapterCommentAnchorView> result = useCase.execute(CHAPTER_ID, List.of(ROOT_1));

        assertThat(result).isEmpty();
        verify(anchorRepositoryPort, times(1)).findByRootCommentIds(List.of(ROOT_1));
        verify(resolutionSourcePort, never()).loadCurrent(any());
        verify(resolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("execute: loads snapshot once and resolves CURRENT, RELOCATED, and STALE anchors")
    void shouldResolveCurrentRelocatedAndStaleAnchors() {
        ChapterCommentAnchor anchor1 = ChapterCommentAnchor.createBlock(
                ROOT_1, CHAPTER_ID, 1L, BLK_1, "Original text 1", NOW
        );
        ChapterCommentAnchor anchor2 = ChapterCommentAnchor.createBlock(
                ROOT_2, CHAPTER_ID, 1L, BLK_2, "Original text 2", NOW
        );
        ChapterCommentAnchor anchor3 = ChapterCommentAnchor.createBlock(
                ROOT_3, CHAPTER_ID, 1L, BLK_3, "Original text 3 deleted", NOW
        );

        when(anchorRepositoryPort.findByRootCommentIds(List.of(ROOT_1, ROOT_2, ROOT_3)))
                .thenReturn(List.of(anchor1, anchor2, anchor3));

        ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                2L,
                List.of(
                        new ReaderBlock(BLK_1, "Canonical text 1 for block 1"),
                        new ReaderBlock(BLK_4, "Relocated text 2 for block 2")
                )
        );
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

        when(resolver.resolve(anchor1, snapshot))
                .thenReturn(ChapterCommentAnchorResolution.currentBlock(ROOT_1, CHAPTER_ID, 2L, BLK_1));
        when(resolver.resolve(anchor2, snapshot))
                .thenReturn(ChapterCommentAnchorResolution.relocatedBlock(ROOT_2, CHAPTER_ID, 1L, 2L, BLK_4));
        when(resolver.resolve(anchor3, snapshot))
                .thenReturn(ChapterCommentAnchorResolution.stale(ROOT_3, CHAPTER_ID, 1L, 2L));

        List<ResolvedChapterCommentAnchorView> views = useCase.execute(CHAPTER_ID, List.of(ROOT_1, ROOT_2, ROOT_3));

        assertThat(views).hasSize(3);

        // CURRENT anchor
        ResolvedChapterCommentAnchorView view1 = views.get(0);
        assertThat(view1.rootCommentId()).isEqualTo(ROOT_1);
        assertThat(view1.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.CURRENT);
        assertThat(view1.resolvedBlockKey()).isEqualTo(BLK_1);
        assertThat(view1.passageText()).isEqualTo("Canonical text 1 for block 1");

        // RELOCATED anchor
        ResolvedChapterCommentAnchorView view2 = views.get(1);
        assertThat(view2.rootCommentId()).isEqualTo(ROOT_2);
        assertThat(view2.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
        assertThat(view2.resolvedBlockKey()).isEqualTo(BLK_4);
        assertThat(view2.passageText()).isEqualTo("Relocated text 2 for block 2");

        // STALE anchor
        ResolvedChapterCommentAnchorView view3 = views.get(2);
        assertThat(view3.rootCommentId()).isEqualTo(ROOT_3);
        assertThat(view3.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
        assertThat(view3.resolvedBlockKey()).isNull();
        assertThat(view3.passageText()).isEqualTo("Original text 3 deleted");

        verify(resolutionSourcePort, times(1)).loadCurrent(CHAPTER_ID);
        verify(anchorRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("execute: degrades CURRENT and RELOCATED to STALE when resolvedBlockKey is absent in snapshot")
    void shouldDegradeToStaleWhenResolvedKeyMissingFromSnapshot() {
        ChapterCommentAnchor anchor1 = ChapterCommentAnchor.createBlock(
                ROOT_1, CHAPTER_ID, 1L, BLK_1, "Evidence text 1", NOW
        );
        ChapterCommentAnchor anchor2 = ChapterCommentAnchor.createBlock(
                ROOT_2, CHAPTER_ID, 1L, BLK_2, "Evidence text 2", NOW
        );

        when(anchorRepositoryPort.findByRootCommentIds(List.of(ROOT_1, ROOT_2)))
                .thenReturn(List.of(anchor1, anchor2));

        ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                2L,
                List.of(
                        new ReaderBlock(BLK_VALID, "Valid block text")
                )
        );
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

        // Both return resolved keys that do NOT exist in snapshot
        when(resolver.resolve(anchor1, snapshot))
                .thenReturn(ChapterCommentAnchorResolution.currentBlock(ROOT_1, CHAPTER_ID, 2L, BLK_GHOST_1));
        when(resolver.resolve(anchor2, snapshot))
                .thenReturn(ChapterCommentAnchorResolution.relocatedBlock(ROOT_2, CHAPTER_ID, 1L, 2L, BLK_GHOST_2));

        List<ResolvedChapterCommentAnchorView> views = useCase.execute(CHAPTER_ID, List.of(ROOT_1, ROOT_2));

        assertThat(views).hasSize(2);

        // anchor 1 degraded to STALE
        ResolvedChapterCommentAnchorView view1 = views.get(0);
        assertThat(view1.rootCommentId()).isEqualTo(ROOT_1);
        assertThat(view1.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
        assertThat(view1.resolvedBlockKey()).isNull();
        assertThat(view1.passageText()).isEqualTo("Evidence text 1");

        // anchor 2 degraded to STALE
        ResolvedChapterCommentAnchorView view2 = views.get(1);
        assertThat(view2.rootCommentId()).isEqualTo(ROOT_2);
        assertThat(view2.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
        assertThat(view2.resolvedBlockKey()).isNull();
        assertThat(view2.passageText()).isEqualTo("Evidence text 2");

        verify(resolutionSourcePort, times(1)).loadCurrent(CHAPTER_ID);
        verify(anchorRepositoryPort, never()).save(any());
    }
}
