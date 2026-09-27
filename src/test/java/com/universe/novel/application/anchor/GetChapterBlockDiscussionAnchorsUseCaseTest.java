package com.universe.novel.application.anchor;

import com.universe.novel.application.exceptions.ReaderBlockNotFoundException;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
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
@DisplayName("GetChapterBlockDiscussionAnchorsUseCase Unit Tests")
class GetChapterBlockDiscussionAnchorsUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CHAPTER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ROOT_CURRENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_RELOCATED = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOT_STALE = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ROOT_OTHER_BLOCK = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ROOT_OTHER_CHAPTER = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");

    private static final String TARGET_BLOCK_KEY = "blk-1111222233334444-1";
    private static final String OTHER_BLOCK_KEY = "blk-5555666677778888-1";

    @Mock
    private ChapterCommentAnchorRepositoryPort anchorRepositoryPort;

    @Mock
    private ChapterAnchorResolutionSourcePort resolutionSourcePort;

    private ChapterCommentAnchorResolver resolver;
    private GetChapterBlockDiscussionAnchorsUseCase useCase;

    @BeforeEach
    void setUp() {
        resolver = new ChapterCommentAnchorResolver();
        useCase = new GetChapterBlockDiscussionAnchorsUseCase(anchorRepositoryPort, resolutionSourcePort, resolver);
    }

    @Test
    @DisplayName("execute: throws NullPointerException when chapterId is null")
    void shouldThrowWhenChapterIdNull() {
        assertThatThrownBy(() -> useCase.execute(null, TARGET_BLOCK_KEY))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("chapterId cannot be null");
    }

    @Test
    @DisplayName("execute: throws IllegalArgumentException when blockKey is blank or null")
    void shouldThrowWhenBlockKeyBlank() {
        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockKey cannot be blank");

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockKey cannot be blank");
    }

    @Test
    @DisplayName("1. Snapshot loaded once and 2. requested block must exist (throws ReaderBlockNotFoundException when missing)")
    void shouldThrowReaderBlockNotFoundExceptionWhenBlockNotInCurrentSnapshot() {
        ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                2L,
                List.of(new ReaderBlock(OTHER_BLOCK_KEY, "Some other text"))
        );
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, TARGET_BLOCK_KEY))
                .isInstanceOf(ReaderBlockNotFoundException.class)
                .hasMessageContaining(TARGET_BLOCK_KEY);

        verify(resolutionSourcePort, times(1)).loadCurrent(CHAPTER_ID);
        verify(anchorRepositoryPort, never()).findByChapterId(any());
    }

    @Test
    @DisplayName("2. Throws IllegalStateException when duplicate block key exists in current snapshot")
    void shouldThrowIllegalStateExceptionWhenDuplicateBlockKeyInSnapshot() {
        ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                2L,
                List.of(
                        new ReaderBlock(TARGET_BLOCK_KEY, "Text version 1"),
                        new ReaderBlock(TARGET_BLOCK_KEY, "Text version 2")
                )
        );
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, TARGET_BLOCK_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate canonical blockKey found");

        verify(resolutionSourcePort, times(1)).loadCurrent(CHAPTER_ID);
        verify(anchorRepositoryPort, never()).findByChapterId(any());
    }

    @Test
    @DisplayName("Throws IllegalStateException when snapshot contentVersion < 1")
    void shouldThrowWhenContentVersionLessThanOne() {
        ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                0L,
                List.of(new ReaderBlock(TARGET_BLOCK_KEY, "Text"))
        );
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, TARGET_BLOCK_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid current chapter contentVersion");
    }

    @Test
    @DisplayName("Proves: snapshot loaded once, canonicalText from current block, CURRENT and RELOCATED included, STALE and OTHER excluded, wrong chapter excluded, no duplicates, no mutation")
    void shouldResolveDiscussionAnchorsCorrectly() {
        // Current snapshot version = 2L
        ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                2L,
                List.of(
                        new ReaderBlock(TARGET_BLOCK_KEY, "Canonical text of target block"),
                        new ReaderBlock(OTHER_BLOCK_KEY, "Canonical text of other block")
                )
        );
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

        // 4. CURRENT anchor on requested block (version 2L == 2L, blockKey = TARGET_BLOCK_KEY)
        ChapterCommentAnchor anchorCurrent = ChapterCommentAnchor.createBlock(
                ROOT_CURRENT, CHAPTER_ID, 2L, TARGET_BLOCK_KEY, "Canonical text of target block", NOW
        );

        // 5. RELOCATED anchor on requested block (version 1L vs 2L, blockKey still matches TARGET_BLOCK_KEY)
        ChapterCommentAnchor anchorRelocated = ChapterCommentAnchor.createBlock(
                ROOT_RELOCATED, CHAPTER_ID, 1L, TARGET_BLOCK_KEY, "Old canonical text", NOW
        );

        // Duplicate anchor for ROOT_CURRENT: proves deterministic rootCommentId deduplication
        ChapterCommentAnchor anchorDuplicateRoot = ChapterCommentAnchor.createBlock(
                ROOT_CURRENT, CHAPTER_ID, 2L, TARGET_BLOCK_KEY, "Canonical text of target block", NOW
        );

        // 6. STALE anchor (version 1L, old blockKey absent from snapshot, old text absent)
        ChapterCommentAnchor anchorStale = ChapterCommentAnchor.createBlock(
                ROOT_STALE, CHAPTER_ID, 1L, "blk-9999888877776666-1", "Deleted text completely gone", NOW
        );

        // 7. Resolved anchor pointing to ANOTHER block (version 2L, blockKey = OTHER_BLOCK_KEY)
        ChapterCommentAnchor anchorOtherBlock = ChapterCommentAnchor.createBlock(
                ROOT_OTHER_BLOCK, CHAPTER_ID, 2L, OTHER_BLOCK_KEY, "Canonical text of other block", NOW
        );

        // 9. Wrong chapter anchor (belongs to OTHER_CHAPTER_ID)
        ChapterCommentAnchor anchorWrongChapter = ChapterCommentAnchor.createBlock(
                ROOT_OTHER_CHAPTER, OTHER_CHAPTER_ID, 2L, TARGET_BLOCK_KEY, "Text", NOW
        );

        when(anchorRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(
                anchorCurrent,
                anchorRelocated,
                anchorDuplicateRoot,
                anchorStale,
                anchorOtherBlock,
                anchorWrongChapter
        ));

        // Execute query
        ChapterBlockDiscussionAnchorView view = useCase.execute(CHAPTER_ID, TARGET_BLOCK_KEY);

        // 1. Current snapshot loaded once
        verify(resolutionSourcePort, times(1)).loadCurrent(CHAPTER_ID);

        // 3. canonicalText comes from current block
        assertThat(view.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(view.contentVersion()).isEqualTo(2L);
        assertThat(view.blockKey()).isEqualTo(TARGET_BLOCK_KEY);
        assertThat(view.canonicalText()).isEqualTo("Canonical text of target block");

        // 4 & 5 & 11: Includes ROOT_CURRENT and ROOT_RELOCATED exactly once in deterministic order
        assertThat(view.rootCommentIds()).containsExactly(ROOT_CURRENT, ROOT_RELOCATED);

        // 6. STALE anchor excluded
        assertThat(view.rootCommentIds()).doesNotContain(ROOT_STALE);

        // 7. Other block anchor excluded
        assertThat(view.rootCommentIds()).doesNotContain(ROOT_OTHER_BLOCK);

        // 9. Wrong chapter anchor excluded
        assertThat(view.rootCommentIds()).doesNotContain(ROOT_OTHER_CHAPTER);

        // 10. No anchor mutation/save occurs
        verify(anchorRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Returns empty rootCommentIds when no anchors exist for chapter")
    void shouldReturnEmptyRootCommentIdsWhenNoAnchors() {
        ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                1L,
                List.of(new ReaderBlock(TARGET_BLOCK_KEY, "Empty discussions block"))
        );
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);
        when(anchorRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of());

        ChapterBlockDiscussionAnchorView view = useCase.execute(CHAPTER_ID, TARGET_BLOCK_KEY);

        assertThat(view.blockKey()).isEqualTo(TARGET_BLOCK_KEY);
        assertThat(view.canonicalText()).isEqualTo("Empty discussions block");
        assertThat(view.rootCommentIds()).isEmpty();

        verify(anchorRepositoryPort, never()).save(any());
    }
}
