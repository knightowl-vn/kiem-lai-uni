package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.NarrationManifestHasher;
import com.universe.novel.domain.narration.NarrationTextSegment;
import com.universe.shared.id.IdGeneratorPort;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReconcileChapterNarrationSegmentsUseCase Unit Tests")
class ReconcileChapterNarrationSegmentsUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID VOLUME_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-09-05T10:00:00Z");

    @Mock
    private ChapterRepositoryPort chapterRepositoryPort;

    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;

    @Mock
    private NarrationTextSegmenter narrationTextSegmenter;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private ReconcileChapterNarrationSegmentsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ReconcileChapterNarrationSegmentsUseCase(
                chapterRepositoryPort,
                segmentRepositoryPort,
                narrationTextSegmenter,
                idGeneratorPort,
                clockPort
        );
        lenient().when(clockPort.now()).thenReturn(T0);
    }

    private Chapter mockChapter(String content) {
        return mockChapter(content, 1L, ChapterStatus.PUBLISHED);
    }

    private Chapter mockChapter(String content, long contentVersion) {
        return mockChapter(content, contentVersion, ChapterStatus.PUBLISHED);
    }

    private Chapter mockChapter(String content, long contentVersion, ChapterStatus status) {
        return Chapter.rehydrate(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương 1",
                new Slug("chuong-1"),
                "Tóm tắt",
                content,
                status,
                USER_ID,
                USER_ID,
                status == ChapterStatus.PUBLISHED ? USER_ID : null,
                status == ChapterStatus.ARCHIVED ? USER_ID : null,
                T0,
                T0,
                status == ChapterStatus.PUBLISHED ? T0 : null,
                status == ChapterStatus.ARCHIVED ? T0 : null,
                1L,
                contentVersion
        );
    }

    @Test
    @DisplayName("Should throw ChapterNotFoundException when chapter does not exist")
    void shouldThrowWhenChapterNotFound() {
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID))
                .isInstanceOf(ChapterNotFoundException.class)
                .hasMessageContaining(CHAPTER_ID.toString());

        verify(segmentRepositoryPort, never()).saveAll(any());
    }

    @Test
    @DisplayName("Should reject reconciliation when chapter is in DRAFT status")
    void shouldRejectWhenChapterIsDraft() {
        Chapter draftChapter = mockChapter("Nội dung bản nháp", 1L, ChapterStatus.DRAFT);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(draftChapter));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHED")
                .hasMessageContaining("DRAFT");

        verifyNoInteractions(narrationTextSegmenter);
        verify(segmentRepositoryPort, never()).saveAll(any());
    }

    @Test
    @DisplayName("Should reject reconciliation when chapter is in ARCHIVED status")
    void shouldRejectWhenChapterIsArchived() {
        Chapter archivedChapter = mockChapter("Nội dung lưu trữ", 1L, ChapterStatus.ARCHIVED);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(archivedChapter));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHED")
                .hasMessageContaining("ARCHIVED");

        verifyNoInteractions(narrationTextSegmenter);
        verify(segmentRepositoryPort, never()).saveAll(any());
    }

    @Test
    @DisplayName("1. First reconciliation creates all segments when no persisted segments exist")
    void shouldCreateAllSegmentsOnFirstReconciliation() {
        Chapter chapter = mockChapter("Nội dung đoạn 1.\n\nNội dung đoạn 2.", 3L);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        List<NarrationTextSegment> desired = List.of(
                NarrationTextSegment.of(0, "Nội dung đoạn 1."),
                NarrationTextSegment.of(1, "Nội dung đoạn 2.")
        );
        when(narrationTextSegmenter.segment(chapter.getContent())).thenReturn(desired);
        when(segmentRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of());

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(idGeneratorPort.generate()).thenReturn(id1, id2);

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        assertThat(result.sourceContentVersion()).isEqualTo(3L);
        assertThat(result.manifestHash()).isEqualTo(NarrationManifestHasher.computeManifestHash(desired));
        assertThat(result.currentSegmentCount()).isEqualTo(2);
        assertThat(result.reusedCurrentCount()).isEqualTo(0);
        assertThat(result.unchangedCurrentCount()).isEqualTo(0);
        assertThat(result.repositionedCurrentCount()).isEqualTo(0);
        assertThat(result.restoredCount()).isEqualTo(0);
        assertThat(result.createdCount()).isEqualTo(2);
        assertThat(result.retiredCount()).isEqualTo(0);

        assertThat(result.currentSegments()).hasSize(2);
        assertThat(result.currentSegments().get(0)).isEqualTo(new ChapterNarrationSegmentReconciliationItem(
                id1, 0, ChapterNarrationSegmentReconciliationDisposition.CREATED
        ));
        assertThat(result.currentSegments().get(1)).isEqualTo(new ChapterNarrationSegmentReconciliationItem(
                id2, 1, ChapterNarrationSegmentReconciliationDisposition.CREATED
        ));
        assertThat(result.retiredSegmentIds()).isEmpty();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChapterNarrationSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(segmentRepositoryPort).saveAll(captor.capture());

        List<ChapterNarrationSegment> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getId()).isEqualTo(id1);
        assertThat(saved.get(0).getSegmentIndex()).isEqualTo(0);
        assertThat(saved.get(0).getText()).isEqualTo("Nội dung đoạn 1.");
        assertThat(saved.get(0).getStatus()).isEqualTo(ChapterNarrationSegmentStatus.CURRENT);

        assertThat(saved.get(1).getId()).isEqualTo(id2);
        assertThat(saved.get(1).getSegmentIndex()).isEqualTo(1);
        assertThat(saved.get(1).getText()).isEqualTo("Nội dung đoạn 2.");
        assertThat(saved.get(1).getStatus()).isEqualTo(ChapterNarrationSegmentStatus.CURRENT);
    }

    @Test
    @DisplayName("2. Identical reconciliation preserves all IDs and dispositions REUSED_UNCHANGED without writes")
    void shouldPreserveAllIdsOnIdenticalReconciliation() {
        Chapter chapter = mockChapter("Đoạn 1.\n\nĐoạn 2.", 2L);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        List<NarrationTextSegment> desired = List.of(
                NarrationTextSegment.of(0, "Đoạn 1."),
                NarrationTextSegment.of(1, "Đoạn 2.")
        );
        when(narrationTextSegmenter.segment(chapter.getContent())).thenReturn(desired);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChapterNarrationSegment seg1 = ChapterNarrationSegment.create(id1, CHAPTER_ID, 0, "Đoạn 1.", T0);
        ChapterNarrationSegment seg2 = ChapterNarrationSegment.create(id2, CHAPTER_ID, 1, "Đoạn 2.", T0);
        when(segmentRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(seg1, seg2));

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        assertThat(result.sourceContentVersion()).isEqualTo(2L);
        assertThat(result.manifestHash()).isEqualTo(NarrationManifestHasher.computeManifestHash(desired));
        assertThat(result.currentSegmentCount()).isEqualTo(2);
        assertThat(result.reusedCurrentCount()).isEqualTo(2);
        assertThat(result.unchangedCurrentCount()).isEqualTo(2);
        assertThat(result.repositionedCurrentCount()).isEqualTo(0);
        assertThat(result.restoredCount()).isEqualTo(0);
        assertThat(result.createdCount()).isEqualTo(0);
        assertThat(result.retiredCount()).isEqualTo(0);

        assertThat(result.currentSegments()).containsExactly(
                new ChapterNarrationSegmentReconciliationItem(id1, 0, ChapterNarrationSegmentReconciliationDisposition.REUSED_UNCHANGED),
                new ChapterNarrationSegmentReconciliationItem(id2, 1, ChapterNarrationSegmentReconciliationDisposition.REUSED_UNCHANGED)
        );
        assertThat(result.retiredSegmentIds()).isEmpty();

        verify(idGeneratorPort, never()).generate();
        verify(segmentRepositoryPort, never()).saveAll(any());
    }

    @Test
    @DisplayName("3. Inserted content shifts indexes to REUSED_REPOSITIONED and creates new segment")
    void shouldShiftIndexesAndReuseIdsWhenContentInsertedAtStart() {
        Chapter chapter = mockChapter("Đoạn mới mở đầu.\n\nĐoạn 1.\n\nĐoạn 2.", 1L);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        List<NarrationTextSegment> desired = List.of(
                NarrationTextSegment.of(0, "Đoạn mới mở đầu."),
                NarrationTextSegment.of(1, "Đoạn 1."),
                NarrationTextSegment.of(2, "Đoạn 2.")
        );
        when(narrationTextSegmenter.segment(chapter.getContent())).thenReturn(desired);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChapterNarrationSegment seg1 = ChapterNarrationSegment.create(id1, CHAPTER_ID, 0, "Đoạn 1.", T0);
        ChapterNarrationSegment seg2 = ChapterNarrationSegment.create(id2, CHAPTER_ID, 1, "Đoạn 2.", T0);
        when(segmentRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(seg1, seg2));

        UUID newId = UUID.randomUUID();
        when(idGeneratorPort.generate()).thenReturn(newId);

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        assertThat(result.currentSegmentCount()).isEqualTo(3);
        assertThat(result.reusedCurrentCount()).isEqualTo(2);
        assertThat(result.unchangedCurrentCount()).isEqualTo(0);
        assertThat(result.repositionedCurrentCount()).isEqualTo(2);
        assertThat(result.restoredCount()).isEqualTo(0);
        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.retiredCount()).isEqualTo(0);

        assertThat(result.currentSegments()).containsExactly(
                new ChapterNarrationSegmentReconciliationItem(newId, 0, ChapterNarrationSegmentReconciliationDisposition.CREATED),
                new ChapterNarrationSegmentReconciliationItem(id1, 1, ChapterNarrationSegmentReconciliationDisposition.REUSED_REPOSITIONED),
                new ChapterNarrationSegmentReconciliationItem(id2, 2, ChapterNarrationSegmentReconciliationDisposition.REUSED_REPOSITIONED)
        );
        assertThat(result.retiredSegmentIds()).isEmpty();

        assertThat(seg1.getSegmentIndex()).isEqualTo(1);
        assertThat(seg1.getId()).isEqualTo(id1);
        assertThat(seg2.getSegmentIndex()).isEqualTo(2);
        assertThat(seg2.getId()).isEqualTo(id2);
    }

    @Test
    @DisplayName("4. Edited segment creates a new ID and retires old segment")
    void shouldCreateNewIdAndRetireOldWhenSegmentEdited() {
        Chapter chapter = mockChapter("Đoạn 1.\n\nĐoạn 2 đã được sửa.", 1L);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        List<NarrationTextSegment> desired = List.of(
                NarrationTextSegment.of(0, "Đoạn 1."),
                NarrationTextSegment.of(1, "Đoạn 2 đã được sửa.")
        );
        when(narrationTextSegmenter.segment(chapter.getContent())).thenReturn(desired);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChapterNarrationSegment seg1 = ChapterNarrationSegment.create(id1, CHAPTER_ID, 0, "Đoạn 1.", T0);
        ChapterNarrationSegment seg2 = ChapterNarrationSegment.create(id2, CHAPTER_ID, 1, "Đoạn 2 gốc.", T0);
        when(segmentRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(seg1, seg2));

        UUID newId = UUID.randomUUID();
        when(idGeneratorPort.generate()).thenReturn(newId);

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        assertThat(result.currentSegmentCount()).isEqualTo(2);
        assertThat(result.reusedCurrentCount()).isEqualTo(1);
        assertThat(result.unchangedCurrentCount()).isEqualTo(1);
        assertThat(result.repositionedCurrentCount()).isEqualTo(0);
        assertThat(result.restoredCount()).isEqualTo(0);
        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.retiredCount()).isEqualTo(1);

        assertThat(result.currentSegments()).containsExactly(
                new ChapterNarrationSegmentReconciliationItem(id1, 0, ChapterNarrationSegmentReconciliationDisposition.REUSED_UNCHANGED),
                new ChapterNarrationSegmentReconciliationItem(newId, 1, ChapterNarrationSegmentReconciliationDisposition.CREATED)
        );
        assertThat(result.retiredSegmentIds()).containsExactly(id2);

        assertThat(seg1.isCurrent()).isTrue();
        assertThat(seg2.isRetired()).isTrue();
    }

    @Test
    @DisplayName("5. Removed segment becomes RETIRED deterministically")
    void shouldRetireRemovedSegment() {
        Chapter chapter = mockChapter("Đoạn 1.\n\nĐoạn 3.", 1L);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        List<NarrationTextSegment> desired = List.of(
                NarrationTextSegment.of(0, "Đoạn 1."),
                NarrationTextSegment.of(1, "Đoạn 3.")
        );
        when(narrationTextSegmenter.segment(chapter.getContent())).thenReturn(desired);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        ChapterNarrationSegment seg1 = ChapterNarrationSegment.create(id1, CHAPTER_ID, 0, "Đoạn 1.", T0);
        ChapterNarrationSegment seg2 = ChapterNarrationSegment.create(id2, CHAPTER_ID, 1, "Đoạn 2.", T0);
        ChapterNarrationSegment seg3 = ChapterNarrationSegment.create(id3, CHAPTER_ID, 2, "Đoạn 3.", T0);
        when(segmentRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(seg1, seg2, seg3));

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        assertThat(result.currentSegmentCount()).isEqualTo(2);
        assertThat(result.reusedCurrentCount()).isEqualTo(2);
        assertThat(result.unchangedCurrentCount()).isEqualTo(1);
        assertThat(result.repositionedCurrentCount()).isEqualTo(1);
        assertThat(result.restoredCount()).isEqualTo(0);
        assertThat(result.createdCount()).isEqualTo(0);
        assertThat(result.retiredCount()).isEqualTo(1);

        assertThat(result.currentSegments()).containsExactly(
                new ChapterNarrationSegmentReconciliationItem(id1, 0, ChapterNarrationSegmentReconciliationDisposition.REUSED_UNCHANGED),
                new ChapterNarrationSegmentReconciliationItem(id3, 1, ChapterNarrationSegmentReconciliationDisposition.REUSED_REPOSITIONED)
        );
        assertThat(result.retiredSegmentIds()).containsExactly(id2);

        assertThat(seg1.isCurrent()).isTrue();
        assertThat(seg2.isRetired()).isTrue();
        assertThat(seg3.isCurrent()).isTrue();
        assertThat(seg3.getSegmentIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("6. Exact retired segment can be restored with RESTORED disposition")
    void shouldRestoreRetiredSegmentWhenReintroduced() {
        Chapter chapter = mockChapter("Đoạn 1.\n\nĐoạn 2.", 1L);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        List<NarrationTextSegment> desired = List.of(
                NarrationTextSegment.of(0, "Đoạn 1."),
                NarrationTextSegment.of(1, "Đoạn 2.")
        );
        when(narrationTextSegmenter.segment(chapter.getContent())).thenReturn(desired);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChapterNarrationSegment seg1 = ChapterNarrationSegment.create(id1, CHAPTER_ID, 0, "Đoạn 1.", T0);
        ChapterNarrationSegment seg2 = ChapterNarrationSegment.create(id2, CHAPTER_ID, 1, "Đoạn 2.", T0);
        seg2.retire(T0);
        when(segmentRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(seg1, seg2));

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        assertThat(result.currentSegmentCount()).isEqualTo(2);
        assertThat(result.reusedCurrentCount()).isEqualTo(1);
        assertThat(result.unchangedCurrentCount()).isEqualTo(1);
        assertThat(result.repositionedCurrentCount()).isEqualTo(0);
        assertThat(result.restoredCount()).isEqualTo(1);
        assertThat(result.createdCount()).isEqualTo(0);
        assertThat(result.retiredCount()).isEqualTo(0);

        assertThat(result.currentSegments()).containsExactly(
                new ChapterNarrationSegmentReconciliationItem(id1, 0, ChapterNarrationSegmentReconciliationDisposition.REUSED_UNCHANGED),
                new ChapterNarrationSegmentReconciliationItem(id2, 1, ChapterNarrationSegmentReconciliationDisposition.RESTORED)
        );
        assertThat(result.retiredSegmentIds()).isEmpty();

        assertThat(seg2.isCurrent()).isTrue();
        assertThat(seg2.getSegmentIndex()).isEqualTo(1);
        verify(idGeneratorPort, never()).generate();
    }

    @Test
    @DisplayName("7. Duplicate identical hashes are matched one-to-one")
    void shouldMatchDuplicateHashesOneToOne() {
        Chapter chapter = mockChapter("Đoạn trùng.\n\nĐoạn trùng.\n\nĐoạn trùng.", 1L);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        List<NarrationTextSegment> desired = List.of(
                NarrationTextSegment.of(0, "Đoạn trùng."),
                NarrationTextSegment.of(1, "Đoạn trùng."),
                NarrationTextSegment.of(2, "Đoạn trùng.")
        );
        when(narrationTextSegmenter.segment(chapter.getContent())).thenReturn(desired);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChapterNarrationSegment seg1 = ChapterNarrationSegment.create(id1, CHAPTER_ID, 0, "Đoạn trùng.", T0);
        ChapterNarrationSegment seg2 = ChapterNarrationSegment.create(id2, CHAPTER_ID, 1, "Đoạn trùng.", T0);
        when(segmentRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(seg1, seg2));

        UUID newId = UUID.randomUUID();
        when(idGeneratorPort.generate()).thenReturn(newId);

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        assertThat(result.currentSegmentCount()).isEqualTo(3);
        assertThat(result.reusedCurrentCount()).isEqualTo(2);
        assertThat(result.restoredCount()).isEqualTo(0);
        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.retiredCount()).isEqualTo(0);

        // Sub-test: reduce from 3 to 1 identical segment
        ChapterNarrationSegment seg3 = ChapterNarrationSegment.create(newId, CHAPTER_ID, 2, "Đoạn trùng.", T0);
        List<NarrationTextSegment> reducedDesired = List.of(
                NarrationTextSegment.of(0, "Đoạn trùng.")
        );
        when(narrationTextSegmenter.segment(chapter.getContent())).thenReturn(reducedDesired);
        when(segmentRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(seg1, seg2, seg3));

        ReconcileChapterNarrationSegmentsResult reducedResult = useCase.execute(CHAPTER_ID);
        assertThat(reducedResult.currentSegmentCount()).isEqualTo(1);
        assertThat(reducedResult.reusedCurrentCount()).isEqualTo(1);
        assertThat(reducedResult.restoredCount()).isEqualTo(0);
        assertThat(reducedResult.createdCount()).isEqualTo(0);
        assertThat(reducedResult.retiredCount()).isEqualTo(2);

        assertThat(reducedResult.retiredSegmentIds()).containsExactly(id2, newId);
        assertThat(seg1.isCurrent()).isTrue();
        assertThat(seg2.isRetired()).isTrue();
        assertThat(seg3.isRetired()).isTrue();
    }

    @Test
    @DisplayName("8. Empty desired manifest retires existing CURRENT segments deterministically and returns empty hash")
    void shouldRetireAllWhenDesiredManifestIsEmpty() {
        Chapter chapter = mockChapter("", 1L);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        when(narrationTextSegmenter.segment(chapter.getContent())).thenReturn(List.of());

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChapterNarrationSegment seg1 = ChapterNarrationSegment.create(id1, CHAPTER_ID, 0, "Đoạn 1.", T0);
        ChapterNarrationSegment seg2 = ChapterNarrationSegment.create(id2, CHAPTER_ID, 1, "Đoạn 2.", T0);
        when(segmentRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(seg1, seg2));

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(new ReconcileChapterNarrationSegmentsCommand(CHAPTER_ID));

        assertThat(result.sourceContentVersion()).isEqualTo(1L);
        assertThat(result.manifestHash()).isEqualTo(NarrationManifestHasher.EMPTY_MANIFEST_HASH);
        assertThat(result.currentSegmentCount()).isEqualTo(0);
        assertThat(result.reusedCurrentCount()).isEqualTo(0);
        assertThat(result.restoredCount()).isEqualTo(0);
        assertThat(result.createdCount()).isEqualTo(0);
        assertThat(result.retiredCount()).isEqualTo(2);

        assertThat(result.currentSegments()).isEmpty();
        assertThat(result.retiredSegmentIds()).containsExactly(id1, id2);

        assertThat(seg1.isRetired()).isTrue();
        assertThat(seg2.isRetired()).isTrue();
    }

    @Test
    @DisplayName("9. Deterministic matching: Duplicate identical segments match same identities regardless of repository ordering")
    void shouldMatchDuplicateSegmentsDeterministicallyRegardlessOfRepositoryOrder() {
        Chapter chapter = mockChapter("Đoạn trùng lặp.\n\nĐoạn trùng lặp.", 1L);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        List<NarrationTextSegment> desired = List.of(
                NarrationTextSegment.of(0, "Đoạn trùng lặp."),
                NarrationTextSegment.of(1, "Đoạn trùng lặp.")
        );
        when(narrationTextSegmenter.segment(chapter.getContent())).thenReturn(desired);

        UUID idLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idHigh = UUID.fromString("00000000-0000-0000-0000-000000000002");
        ChapterNarrationSegment segLow = ChapterNarrationSegment.create(idLow, CHAPTER_ID, 5, "Đoạn trùng lặp.", T0);
        ChapterNarrationSegment segHigh = ChapterNarrationSegment.create(idHigh, CHAPTER_ID, 5, "Đoạn trùng lặp.", T0);

        // Repository returns segments in reverse ID order: [segHigh, segLow]
        when(segmentRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(segHigh, segLow));

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        assertThat(result.currentSegmentCount()).isEqualTo(2);
        assertThat(result.reusedCurrentCount()).isEqualTo(2);
        assertThat(result.createdCount()).isEqualTo(0);
        assertThat(result.retiredCount()).isEqualTo(0);

        // Deterministic matching chooses lower UUID for first desired position (0) and higher UUID for second (1)
        assertThat(result.currentSegments()).containsExactly(
                new ChapterNarrationSegmentReconciliationItem(idLow, 0, ChapterNarrationSegmentReconciliationDisposition.REUSED_REPOSITIONED),
                new ChapterNarrationSegmentReconciliationItem(idHigh, 1, ChapterNarrationSegmentReconciliationDisposition.REUSED_REPOSITIONED)
        );

        assertThat(segLow.getSegmentIndex()).isEqualTo(0);
        assertThat(segHigh.getSegmentIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("10. Result collections are strictly immutable")
    void shouldEnforceResultImmutability() {
        Chapter chapter = mockChapter("Đoạn 1.\n\nĐoạn 2.", 1L);
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        List<NarrationTextSegment> desired = List.of(
                NarrationTextSegment.of(0, "Đoạn 1.")
        );
        when(narrationTextSegmenter.segment(chapter.getContent())).thenReturn(desired);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChapterNarrationSegment seg1 = ChapterNarrationSegment.create(id1, CHAPTER_ID, 0, "Đoạn 1.", T0);
        ChapterNarrationSegment seg2 = ChapterNarrationSegment.create(id2, CHAPTER_ID, 1, "Đoạn 2.", T0);
        when(segmentRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(seg1, seg2));

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        List<ChapterNarrationSegmentReconciliationItem> currentList = result.currentSegments();
        assertThatThrownBy(() -> currentList.add(new ChapterNarrationSegmentReconciliationItem(
                UUID.randomUUID(), 1, ChapterNarrationSegmentReconciliationDisposition.CREATED
        ))).isInstanceOf(UnsupportedOperationException.class);

        List<UUID> retiredList = result.retiredSegmentIds();
        assertThatThrownBy(() -> retiredList.add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
