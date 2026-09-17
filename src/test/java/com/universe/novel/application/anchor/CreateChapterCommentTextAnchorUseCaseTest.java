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
@DisplayName("CreateChapterCommentTextAnchorUseCase Unit Tests")
class CreateChapterCommentTextAnchorUseCaseTest {

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

    private CreateChapterCommentTextAnchorUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateChapterCommentTextAnchorUseCase(resolutionSourcePort, anchorRepositoryPort, clockPort);
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
    @DisplayName("Call loadCurrent(chapterId) exactly once and persist TEXT_RANGE anchor on valid command")
    void shouldCallLoadCurrentOnceAndPersistAnchorOnValidCommand() {
        String blockText = "Đạo khả đạo, phi thường đạo. Danh khả danh, phi thường danh.";
        //                  0         10        20        30        40        50
        // "phi thường đạo" at index 13..27
        int start = 13;
        int end = 27;
        when(clockPort.now()).thenReturn(NOW);
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                1L,
                List.of(new ReaderBlock(BLOCK_KEY, blockText))
        ));
        when(anchorRepositoryPort.save(any(ChapterCommentAnchor.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChapterCommentTextAnchorCommand command = new CreateChapterCommentTextAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY,
                start,
                end
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
        assertThat(saved.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.TEXT_RANGE);
        assertThat(saved.getStartOffset()).isEqualTo(start);
        assertThat(saved.getEndOffset()).isEqualTo(end);
        assertThat(saved.getSelectedText()).isEqualTo("phi thường đạo");
        assertThat(saved.getContextBefore()).isEqualTo("Đạo khả đạo, ");
        assertThat(saved.getContextAfter()).isEqualTo(". Danh khả danh, phi thường danh.");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(result).isSameAs(saved);
    }

    @Test
    @DisplayName("Derives selectedText strictly from canonical Reader text (ignoring any client spoofing)")
    void shouldReconstructSelectedTextFromCanonicalSnapshot() {
        String blockText = "Tu tiên chi lộ, vạn kiếp bất phục.";
        int start = 16;
        int end = 33; // "vạn kiếp bất phục"
        when(clockPort.now()).thenReturn(NOW);
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                2L,
                List.of(new ReaderBlock(BLOCK_KEY, blockText))
        ));
        when(anchorRepositoryPort.save(any(ChapterCommentAnchor.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChapterCommentTextAnchorCommand command = new CreateChapterCommentTextAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                2L,
                BLOCK_KEY,
                start,
                end
        );

        ChapterCommentAnchor result = useCase.execute(command);

        assertThat(result.getSelectedText()).isEqualTo("vạn kiếp bất phục");
        assertThat(result.getContextBefore()).isEqualTo("Tu tiên chi lộ, ");
        assertThat(result.getContextAfter()).isEqualTo(".");
    }

    @Test
    @DisplayName("Context before and after are bounded to at most 64 UTF-16 code units")
    void shouldBoundContextBeforeAndAfterTo64CodeUnits() {
        String longBefore = "A".repeat(100);
        String selected = "SELECTED";
        String longAfter = "Z".repeat(100);
        String fullText = longBefore + selected + longAfter;

        int start = 100;
        int end = 100 + selected.length();

        when(clockPort.now()).thenReturn(NOW);
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                1L,
                List.of(new ReaderBlock(BLOCK_KEY, fullText))
        ));
        when(anchorRepositoryPort.save(any(ChapterCommentAnchor.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChapterCommentTextAnchorCommand command = new CreateChapterCommentTextAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY,
                start,
                end
        );

        ChapterCommentAnchor result = useCase.execute(command);

        assertThat(result.getSelectedText()).isEqualTo(selected);
        assertThat(result.getContextBefore()).hasSize(64).isEqualTo("A".repeat(64));
        assertThat(result.getContextAfter()).hasSize(64).isEqualTo("Z".repeat(64));
    }

    @Test
    @DisplayName("Handles beginning of block where contextBefore is empty")
    void shouldHandleBeginningOfBlockContext() {
        String blockText = "Bắt đầu đoạn văn bản.";
        int start = 0;
        int end = 7; // "Bắt đầu"

        when(clockPort.now()).thenReturn(NOW);
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                1L,
                List.of(new ReaderBlock(BLOCK_KEY, blockText))
        ));
        when(anchorRepositoryPort.save(any(ChapterCommentAnchor.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChapterCommentTextAnchorCommand command = new CreateChapterCommentTextAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY,
                start,
                end
        );

        ChapterCommentAnchor result = useCase.execute(command);

        assertThat(result.getSelectedText()).isEqualTo("Bắt đầu");
        assertThat(result.getContextBefore()).isEmpty();
        assertThat(result.getContextAfter()).isEqualTo(" đoạn văn bản.");
    }

    @Test
    @DisplayName("Handles end of block where contextAfter is empty")
    void shouldHandleEndOfBlockContext() {
        String blockText = "Kết thúc đoạn văn bản.";
        int start = 9;
        int end = blockText.length(); // "đoạn văn bản."

        when(clockPort.now()).thenReturn(NOW);
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                1L,
                List.of(new ReaderBlock(BLOCK_KEY, blockText))
        ));
        when(anchorRepositoryPort.save(any(ChapterCommentAnchor.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChapterCommentTextAnchorCommand command = new CreateChapterCommentTextAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY,
                start,
                end
        );

        ChapterCommentAnchor result = useCase.execute(command);

        assertThat(result.getSelectedText()).isEqualTo("đoạn văn bản.");
        assertThat(result.getContextBefore()).isEqualTo("Kết thúc ");
        assertThat(result.getContextAfter()).isEmpty();
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
        CreateChapterCommentTextAnchorCommand command = new CreateChapterCommentTextAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                2L,
                BLOCK_KEY,
                0,
                5
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

        CreateChapterCommentTextAnchorCommand command = new CreateChapterCommentTextAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY,
                0,
                3
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

        CreateChapterCommentTextAnchorCommand command = new CreateChapterCommentTextAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY,
                0,
                3
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Multiple blocks found matching block key in chapter");

        verify(anchorRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when offsets exceed block canonical text length")
    void shouldThrowWhenOffsetsExceedBlockLength() {
        String shortText = "Ngắn"; // Length 4
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                1L,
                List.of(new ReaderBlock(BLOCK_KEY, shortText))
        ));

        CreateChapterCommentTextAnchorCommand command = new CreateChapterCommentTextAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY,
                0,
                10 // Exceeds length 4!
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid offsets [0, 10] for canonical text of length 4");

        verify(anchorRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("UTF-16 emoji case proves browser offsets align with Java String code units")
    void shouldHandleEmojiSupplementaryCharactersWithUtf16CodeUnits() {
        // "Kiếm 🗡️ Tuyệt 🌟 Đỉnh"
        // '🗡' is U+1F5E1 (surrogate pair, length 2)
        // '️' is U+FE0F (variation selector, length 1)
        // '🌟' is U+1F31F (surrogate pair, length 2)
        String blockText = "Kiếm 🗡️ Tuyệt 🌟 Đỉnh";
        int start = blockText.indexOf("Tuyệt");
        int end = start + "Tuyệt".length();

        when(clockPort.now()).thenReturn(NOW);
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                1L,
                List.of(new ReaderBlock(BLOCK_KEY, blockText))
        ));
        when(anchorRepositoryPort.save(any(ChapterCommentAnchor.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChapterCommentTextAnchorCommand command = new CreateChapterCommentTextAnchorCommand(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY,
                start,
                end
        );

        ChapterCommentAnchor result = useCase.execute(command);

        assertThat(result.getSelectedText()).isEqualTo("Tuyệt");
        assertThat(result.getStartOffset()).isEqualTo(start);
        assertThat(result.getEndOffset()).isEqualTo(end);
        verify(anchorRepositoryPort).save(any());
    }
}
