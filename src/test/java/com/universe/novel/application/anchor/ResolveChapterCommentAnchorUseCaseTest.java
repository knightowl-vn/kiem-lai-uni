package com.universe.novel.application.anchor;

import com.universe.novel.application.exceptions.ChapterCommentAnchorNotFoundException;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolution;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResolveChapterCommentAnchorUseCaseTest {

    private static final UUID ROOT_COMMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T12:00:00Z");

    @Mock
    private ChapterCommentAnchorRepositoryPort anchorRepositoryPort;

    @Mock
    private ChapterAnchorResolutionSourcePort resolutionSourcePort;

    private ResolveChapterCommentAnchorUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ResolveChapterCommentAnchorUseCase(anchorRepositoryPort, resolutionSourcePort);
    }

    // =========================================================================
    // ANCHOR NOT FOUND
    // =========================================================================

    @Test
    @DisplayName("Ném ChapterCommentAnchorNotFoundException khi không tìm thấy anchor (không trả về STALE)")
    void shouldThrowWhenAnchorNotFound() {
        when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.resolve(ROOT_COMMENT_ID))
                .isInstanceOf(ChapterCommentAnchorNotFoundException.class)
                .hasMessageContaining(ROOT_COMMENT_ID.toString());

        verify(anchorRepositoryPort, never()).save(any());
    }

    // =========================================================================
    // SAME VERSION (snapshot.version == anchor.version)
    // =========================================================================

    @Nested
    @DisplayName("Cùng contentVersion: Chỉ kiểm tra trực tiếp tọa độ, không chạy relocation")
    class SameVersionTests {

        @Test
        @DisplayName("BLOCK: Khóa blockKey tồn tại chính xác -> CURRENT")
        void blockExactKeyResolvesCurrent() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1", "Nội dung đoạn văn", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 1L, List.of(new ReaderBlock("blk-1111111111111111-1", "Nội dung đoạn văn"))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.CURRENT);
            assertThat(res.resolvedBlockKey()).isEqualTo("blk-1111111111111111-1");
            assertThat(res.resolvedStartOffset()).isNull();
            assertThat(res.resolvedEndOffset()).isNull();
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("BLOCK: Khóa blockKey không tồn tại trong snapshot -> STALE")
        void blockMissingKeyResolvesStale() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1", "Nội dung đoạn văn", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 1L, List.of(new ReaderBlock("blk-2222222222222222-1", "Đoạn văn khác"))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
            assertThat(res.resolvedBlockKey()).isNull();
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("TEXT_RANGE: Khóa blockKey và chuỗi con [start, end] trùng khớp chính xác -> CURRENT")
        void textRangeExactOffsetsResolvesCurrent() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1",
                    9, 17, "thế giới", "Xin chào ", " tươi đẹp", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 1L, List.of(new ReaderBlock("blk-1111111111111111-1", "Xin chào thế giới tươi đẹp"))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.CURRENT);
            assertThat(res.resolvedBlockKey()).isEqualTo("blk-1111111111111111-1");
            assertThat(res.resolvedStartOffset()).isEqualTo(9);
            assertThat(res.resolvedEndOffset()).isEqualTo(17);
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("TEXT_RANGE: Tọa độ vượt quá độ dài canonicalText -> STALE")
        void textRangeOutOfRangeCoordinatesResolvesStale() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1",
                    10, 18, "thế giới", "Xin chào ", "!", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            // Snapshot text has only 4 chars, so start=10 is out of range
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 1L, List.of(new ReaderBlock("blk-1111111111111111-1", "Ngắn"))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("TEXT_RANGE: Lệch chuỗi con tại tọa độ gốc KHÔNG BAO GIỜ chạy tìm kiếm relocation -> STALE")
        void textRangeMismatchMustNeverRelocateBySearching() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1",
                    0, 8, "Kiếm Lai", "", " chương 1", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            // In the snapshot, chars [0, 8] is "Đạo tổ K", but "Kiếm Lai" appears at [7, 15]
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 1L, List.of(new ReaderBlock("blk-1111111111111111-1", "Đạo tổ Kiếm Lai chương 1"))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            // Same-version must NOT relocate! Must be STALE
            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
            verify(anchorRepositoryPort, never()).save(any());
        }
    }

    // =========================================================================
    // DIFFERENT VERSION — BLOCK
    // =========================================================================

    @Nested
    @DisplayName("Khác contentVersion — BLOCK anchor")
    class DifferentVersionBlockTests {

        @Test
        @DisplayName("Bước 1: Khóa blockKey gốc vẫn tồn tại duy nhất -> RELOCATED")
        void blockExactKeyResolvesRelocated() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1", "Nội dung cũ", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(new ReaderBlock("blk-1111111111111111-1", "Nội dung mới trong cùng block"))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
            assertThat(res.resolvedBlockKey()).isEqualTo("blk-1111111111111111-1");
            assertThat(res.resolvedStartOffset()).isNull();
            assertThat(res.resolvedEndOffset()).isNull();
            assertThat(res.originalContentVersion()).isEqualTo(1L);
            assertThat(res.currentContentVersion()).isEqualTo(2L);
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Bước 2: Khóa blockKey gốc vắng mặt, có đúng 1 block trùng khớp canonicalText -> RELOCATED")
        void blockChangedKeyOneExactTextMatchResolvesRelocated() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0000000000000001-1", "Đoạn văn không đổi", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(
                            new ReaderBlock("blk-0000000000000002-1", "Đoạn mở đầu"),
                            new ReaderBlock("blk-0000000000000003-1", "Đoạn văn không đổi")
                    )
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
            assertThat(res.resolvedBlockKey()).isEqualTo("blk-0000000000000003-1");
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Bước 2: Khóa blockKey vắng mặt và không có block nào trùng canonicalText -> STALE")
        void blockChangedKeyNoTextMatchResolvesStale() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0000000000000001-1", "Đoạn văn đã bị xóa", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(new ReaderBlock("blk-0000000000000002-1", "Đoạn hoàn toàn khác"))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Bước 2: Khóa blockKey vắng mặt nhưng có nhiều hơn 1 block trùng text -> STALE (mơ hồ)")
        void blockChangedKeyDuplicateExactTextResolvesStale() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0000000000000001-1", "Đoạn lặp lại", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(
                            new ReaderBlock("blk-0000000000000004-1", "Đoạn lặp lại"),
                            new ReaderBlock("blk-0000000000000004-2", "Đoạn lặp lại")
                    )
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
            verify(anchorRepositoryPort, never()).save(any());
        }
    }

    // =========================================================================
    // DIFFERENT VERSION — TEXT_RANGE
    // =========================================================================

    @Nested
    @DisplayName("Khác contentVersion — TEXT_RANGE anchor")
    class DifferentVersionTextRangeTests {

        @Test
        @DisplayName("Trường hợp A: Khóa blockKey gốc còn tồn tại, tọa độ gốc vẫn khớp chính xác -> RELOCATED tại cùng tọa độ")
        void sameKeyOriginalOffsetsStillExactResolvesRelocated() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1",
                    0, 5, "Hello", "", " World", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(new ReaderBlock("blk-1111111111111111-1", "Hello World!"))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
            assertThat(res.resolvedBlockKey()).isEqualTo("blk-1111111111111111-1");
            assertThat(res.resolvedStartOffset()).isEqualTo(0);
            assertThat(res.resolvedEndOffset()).isEqualTo(5);
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Trường hợp A: Khóa blockKey gốc còn tồn tại, text bị dịch chuyển trong cùng block -> RELOCATED với tọa độ mới")
        void sameKeySelectedTextShiftedResolvesRelocatedWithNewOffsets() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1",
                    0, 5, "World", "", "!", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            // "Hello World" -> "World" is at [6, 11]
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(new ReaderBlock("blk-1111111111111111-1", "Hello World"))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
            assertThat(res.resolvedBlockKey()).isEqualTo("blk-1111111111111111-1");
            assertThat(res.resolvedStartOffset()).isEqualTo(6);
            assertThat(res.resolvedEndOffset()).isEqualTo(11);
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Trường hợp A: Khóa blockKey gốc còn tồn tại, text lặp lại trong block, ngữ cảnh tìm ra người chiến thắng duy nhất -> RELOCATED")
        void sameKeyRepeatedTextUniqueContextWinnerResolvesRelocated() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1",
                    10, 13, "foo", "apple ", " bar", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            // Block contains "banana foo baz and apple foo bar"
            // Occurrence 1: at index 7 ("banana foo baz") -> before matches nothing ("banana "), after matches nothing (" baz")
            // Occurrence 2: at index 25 ("apple foo bar") -> before matches "apple " (6 chars), after matches " bar" (4 chars) -> totalScore = 10
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(new ReaderBlock("blk-1111111111111111-1", "banana foo baz and apple foo bar"))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
            assertThat(res.resolvedBlockKey()).isEqualTo("blk-1111111111111111-1");
            assertThat(res.resolvedStartOffset()).isEqualTo(25);
            assertThat(res.resolvedEndOffset()).isEqualTo(28);
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Trường hợp A: Khóa blockKey gốc còn tồn tại, text lặp lại, điểm ngữ cảnh bằng nhau (tie) -> STALE")
        void sameKeyTiedContextResolvesStale() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1",
                    0, 3, "foo", "", "", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            // Block contains "bar: foo and foo" -> neither has context before/after -> both scores 0 -> STALE
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(new ReaderBlock("blk-1111111111111111-1", "bar: foo and foo"))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Trường hợp A: Khóa blockKey gốc còn tồn tại nhưng text đã biến mất -> STALE, KHÔNG nhảy sang block khác")
        void sameKeyExistsButSelectedTextAbsentDoesNotJumpBlocks() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1",
                    0, 5, "apple", "", "", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            // targetBlock exists, but "apple" is removed. Another block "blk-0000000000000002-1" has "apple".
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(
                            new ReaderBlock("blk-1111111111111111-1", "orange and banana"),
                            new ReaderBlock("blk-0000000000000002-1", "fresh apple here")
                    )
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            // Must NOT jump to blk-0000000000000002-1! Must be STALE!
            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Trường hợp B: Khóa blockKey gốc đã mất, có đúng 1 vị trí xuất hiện trên toàn bộ chương -> RELOCATED")
        void oldKeyAbsentOneGlobalOccurrenceResolvesRelocated() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0000000000000001-1",
                    0, 7, "Chân lý", "", "", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(
                            new ReaderBlock("blk-0000000000000002-1", "Đoạn văn 1"),
                            new ReaderBlock("blk-0000000000000003-1", "Con đường tìm kiếm Chân lý bất tận")
                    )
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
            assertThat(res.resolvedBlockKey()).isEqualTo("blk-0000000000000003-1");
            int expectedStart = "Con đường tìm kiếm ".length();
            assertThat(res.resolvedStartOffset()).isEqualTo(expectedStart);
            assertThat(res.resolvedEndOffset()).isEqualTo(expectedStart + "Chân lý".length());
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Trường hợp B: Khóa blockKey gốc đã mất, có nhiều ứng viên toàn cục, ngữ cảnh phân giải người chiến thắng duy nhất -> RELOCATED")
        void oldKeyAbsentMultipleCandidatesUniqueContextWinnerResolvesRelocated() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0000000000000001-1",
                    10, 18, "kiếm khí", "ngưng tụ ", " ngút trời", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            // Candidate 1 in block 1: "bộc phát kiếm khí rực rỡ" -> before matches nothing, after matches nothing
            // Candidate 2 in block 2: "ngưng tụ kiếm khí ngút trời" -> before matches "ngưng tụ " (9 chars), after matches " ngút trời" (10 chars) -> score = 19
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(
                            new ReaderBlock("blk-0000000000000002-1", "bộc phát kiếm khí rực rỡ"),
                            new ReaderBlock("blk-0000000000000003-1", "ngưng tụ kiếm khí ngút trời")
                    )
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
            assertThat(res.resolvedBlockKey()).isEqualTo("blk-0000000000000003-1");
            assertThat(res.resolvedStartOffset()).isEqualTo(9);
            assertThat(res.resolvedEndOffset()).isEqualTo(17);
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Trường hợp B: Khóa blockKey gốc đã mất, các ứng viên toàn cục mơ hồ (tie hoặc điểm 0) -> STALE")
        void oldKeyAbsentAmbiguousCandidatesResolvesStale() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0000000000000001-1",
                    0, 6, "mây mù", "", "", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(
                            new ReaderBlock("blk-0000000000000002-1", "mây mù bao phủ đỉnh núi"),
                            new ReaderBlock("blk-0000000000000003-1", "mây mù giăng lối nơi này")
                    )
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Trường hợp B: Khóa blockKey gốc đã mất và chuỗi selectedText biến mất hoàn toàn -> STALE")
        void oldKeyAbsentSelectedTextAbsentResolvesStale() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0000000000000001-1",
                    0, 11, "vô ảnh kiếm", "", "", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(new ReaderBlock("blk-0000000000000002-1", "đao pháp vô song"))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
            verify(anchorRepositoryPort, never()).save(any());
        }
    }

    // =========================================================================
    // UTF-16 SUPPLEMENTARY CHARACTERS (🐉)
    // =========================================================================

    @Nested
    @DisplayName("Hợp đồng UTF-16: Ký tự bổ trợ (Emoji rồng 🐉) và tọa độ code units")
    class Utf16Tests {

        private static final String DRAGON_EMOJI = "🐉"; // 2 UTF-16 code units: \uD83D\uDC09

        @Test
        @DisplayName("SelectedText chứa emoji 🐉: Kiểm tra độ dài 2 UTF-16 units và xác định tọa độ chính xác")
        void selectedTextWithDragonEmojiPreservesUtf16Offsets() {
            assertThat(DRAGON_EMOJI.length()).isEqualTo(2);

            String selected = "Hỏa Long 🐉 xuất thế";
            assertThat(selected.length()).isEqualTo(20);

            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0000000000000001-1",
                    0, 20, selected, "", "", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            String prefix = "Truyền thuyết kể rằng "; // 22 code units
            String fullText = prefix + selected + ".";

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(new ReaderBlock("blk-0000000000000002-1", fullText))
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
            assertThat(res.resolvedBlockKey()).isEqualTo("blk-0000000000000002-1");
            assertThat(res.resolvedStartOffset()).isEqualTo(22);
            assertThat(res.resolvedEndOffset()).isEqualTo(42);
            assertThat(fullText.substring(res.resolvedStartOffset(), res.resolvedEndOffset())).isEqualTo(selected);
            verify(anchorRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Ngữ cảnh trước chứa emoji 🐉: Điểm số ngữ cảnh tính chính xác theo UTF-16 code units")
        void contextBeforeWithDragonEmojiCalculatesScoreCorrectly() {
            String selected = "tiếng gầm";
            String contextBefore = "Thần Long 🐉 "; // "Thần Long " (10) + "🐉" (2) + " " (1) = 13 code units
            assertThat(contextBefore.length()).isEqualTo(13);

            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0000000000000001-1",
                    13, 22, selected, contextBefore, " vang dội", CREATED_AT
            );
            when(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_ID)).thenReturn(Optional.of(anchor));

            // Candidate 1: preceded by "Hổ gầm " -> does not match
            // Candidate 2: preceded by "Thần Long 🐉 " -> matches exactly 13 UTF-16 code units
            String text1 = "Hổ gầm tiếng gầm vang xa";
            String text2 = "Thần Long 🐉 tiếng gầm vang dội";

            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(
                            new ReaderBlock("blk-0000000000000003-1", text1),
                            new ReaderBlock("blk-0000000000000004-1", text2)
                    )
            );
            when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(snapshot);

            ChapterCommentAnchorResolution res = useCase.resolve(ROOT_COMMENT_ID);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
            assertThat(res.resolvedBlockKey()).isEqualTo("blk-0000000000000004-1");
            assertThat(res.resolvedStartOffset()).isEqualTo(13);
            assertThat(res.resolvedEndOffset()).isEqualTo(22);
            verify(anchorRepositoryPort, never()).save(any());
        }
    }
}
