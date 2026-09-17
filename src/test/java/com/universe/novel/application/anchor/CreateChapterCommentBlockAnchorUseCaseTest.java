package com.universe.novel.application.anchor;

import com.universe.novel.application.exceptions.ChapterCommentAnchorVersionConflictException;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorKind;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateChapterCommentBlockAnchorUseCase Unit Tests")
class CreateChapterCommentBlockAnchorUseCaseTest {

    private static final UUID ROOT_COMMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String BLOCK_KEY = "blk-0123456789abcdef-1";
    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");

    @Mock
    private ChapterAnchorResolutionSourcePort resolutionSourcePort;

    @Mock
    private ChapterCommentAnchorRepositoryPort anchorRepositoryPort;

    @Mock
    private ClockPort clockPort;

    private CreateChapterCommentBlockAnchorUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateChapterCommentBlockAnchorUseCase(resolutionSourcePort, anchorRepositoryPort, clockPort);
    }

    @Test
    @DisplayName("Reject null command")
    void shouldRejectNullCommand() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class);
        verify(resolutionSourcePort, never()).loadCurrent(any());
        verify(anchorRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Call loadCurrent(chapterId) exactly once and persist BLOCK anchor on valid command")
    void shouldCallLoadCurrentOnceAndPersistBlockAnchorOnValidCommand() {
        String blockText = "Đạo khả đạo, phi thường đạo. Danh khả danh, phi thường danh.";
        when(clockPort.now()).thenReturn(NOW);
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                1L,
                List.of(new ReaderBlock(BLOCK_KEY, blockText))
        ));
        when(anchorRepositoryPort.save(any(ChapterCommentAnchor.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChapterCommentBlockAnchorCommand command = new CreateChapterCommentBlockAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY
        );

        ChapterCommentAnchor result = useCase.execute(command);

        verify(resolutionSourcePort).loadCurrent(CHAPTER_ID);
        ArgumentCaptor<ChapterCommentAnchor> captor = ArgumentCaptor.forClass(ChapterCommentAnchor.class);
        verify(anchorRepositoryPort).save(captor.capture());

        ChapterCommentAnchor saved = captor.getValue();
        assertThat(saved.getRootCommentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(saved.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(saved.getContentVersion()).isEqualTo(1L);
        assertThat(saved.getBlockKey()).isEqualTo(BLOCK_KEY);
        assertThat(saved.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.BLOCK);
        assertThat(saved.getStartOffset()).isNull();
        assertThat(saved.getEndOffset()).isNull();
        assertThat(saved.getSelectedText()).isEqualTo(blockText);
        assertThat(saved.getContextBefore()).isEmpty();
        assertThat(saved.getContextAfter()).isEmpty();
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(result).isSameAs(saved);
    }

    @Test
    @DisplayName("Reconstructs selectedText strictly from canonical Reader snapshot")
    void shouldReconstructSelectedTextFromCanonicalSnapshot() {
        String blockText = "Tu tiên chi lộ, vạn kiếp bất phục.";
        when(clockPort.now()).thenReturn(NOW);
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                2L,
                List.of(new ReaderBlock(BLOCK_KEY, blockText))
        ));
        when(anchorRepositoryPort.save(any(ChapterCommentAnchor.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChapterCommentBlockAnchorCommand command = new CreateChapterCommentBlockAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                2L,
                BLOCK_KEY
        );

        ChapterCommentAnchor result = useCase.execute(command);

        assertThat(result.getSelectedText()).isEqualTo(blockText);
        assertThat(result.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.BLOCK);
        assertThat(result.getStartOffset()).isNull();
        assertThat(result.getEndOffset()).isNull();
    }

    @Test
    @DisplayName("Throws ChapterCommentAnchorVersionConflictException when requested contentVersion differs from snapshot")
    void shouldThrowVersionConflictWhenRequestedVersionDiffersFromSnapshot() {
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                3L, // Snapshot is version 3
                List.of(new ReaderBlock(BLOCK_KEY, "Nội dung chương"))
        ));

        // Client requested version 2
        CreateChapterCommentBlockAnchorCommand command = new CreateChapterCommentBlockAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                2L,
                BLOCK_KEY
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ChapterCommentAnchorVersionConflictException.class)
                .hasMessageContaining("Phiên bản yêu cầu: 2")
                .hasMessageContaining("phiên bản hiện tại: 3");

        verify(anchorRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when blockKey is not found in snapshot")
    void shouldThrowWhenBlockKeyNotFoundInSnapshot() {
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                1L,
                List.of(new ReaderBlock("blk-different-block-1", "Nội dung"))
        ));

        CreateChapterCommentBlockAnchorCommand command = new CreateChapterCommentBlockAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Block key not found in chapter");

        verify(anchorRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when duplicate matching blockKey exists in snapshot")
    void shouldThrowWhenDuplicateMatchingBlockKeyExists() {
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                1L,
                List.of(
                        new ReaderBlock(BLOCK_KEY, "Nội dung thứ nhất"),
                        new ReaderBlock(BLOCK_KEY, "Nội dung thứ hai")
                )
        ));

        CreateChapterCommentBlockAnchorCommand command = new CreateChapterCommentBlockAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Multiple blocks found matching block key in chapter");

        verify(anchorRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Handles unicode supplementary emoji characters in block text")
    void shouldHandleSupplementaryEmojiInBlockText() {
        String blockText = "Kiếm 🗡️ Tuyệt 🌟 Đỉnh";
        when(clockPort.now()).thenReturn(NOW);
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                1L,
                List.of(new ReaderBlock(BLOCK_KEY, blockText))
        ));
        when(anchorRepositoryPort.save(any(ChapterCommentAnchor.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChapterCommentBlockAnchorCommand command = new CreateChapterCommentBlockAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY
        );

        ChapterCommentAnchor result = useCase.execute(command);

        assertThat(result.getSelectedText()).isEqualTo(blockText);
        assertThat(result.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.BLOCK);
        assertThat(result.getStartOffset()).isNull();
        assertThat(result.getEndOffset()).isNull();
        verify(anchorRepositoryPort).save(any());
    }
}
