package com.universe.novel.application.reader;

import com.universe.novel.application.narration.NarrationTextSegmentPlan;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.NarrationTextSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReaderNarrationBlockMappingResolverTest {
    private final UUID chapter = UUID.randomUUID();
    private final ReaderNarrationBlockMappingResolver resolver = new ReaderNarrationBlockMappingResolver();
    private final List<NarrationTextSegmentPlan> plans = List.of(
            new NarrationTextSegmentPlan(NarrationTextSegment.of(0, "Repeated"), List.of(0, 1)),
            new NarrationTextSegmentPlan(NarrationTextSegment.of(1, "Repeated"), List.of(1, 2)));

    private ChapterNarrationSegment row(int index) {
        return ChapterNarrationSegment.create(UUID.randomUUID(), chapter, index, "Repeated", Instant.EPOCH);
    }

    @Test
    void exactSequenceUsesRealIdsByIndexEvenForDuplicateTextAndUnorderedRows() {
        var a = row(0);
        var b = row(1);
        var mapping = resolver.resolve(chapter, plans, List.of(b, a));
        assertThat(mapping.get(0)).containsExactly(a.getId());
        assertThat(mapping.get(1)).containsExactly(a.getId(), b.getId());
        assertThat(mapping.get(2)).containsExactly(b.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "extra", "duplicateIndex", "gap", "text", "hash", "count", "duplicateId", "retired", "chapter"})
    void rejectsEntireMappingWithoutMutatingRows(String defect) {
        var first = spy(row(0));
        var second = spy(row(1));
        List<ChapterNarrationSegment> rows = List.of(first, second);
        switch (defect) {
            case "missing" -> rows = List.of(first);
            case "extra" -> rows = List.of(first, second, row(2));
            case "duplicateIndex" -> doReturn(0).when(second).getSegmentIndex();
            case "gap" -> doReturn(2).when(second).getSegmentIndex();
            case "text" -> doReturn("Other").when(second).getText();
            case "hash" -> doReturn("0".repeat(64)).when(second).getContentHash();
            case "count" -> doReturn(999).when(second).getCharacterCount();
            case "duplicateId" -> doReturn(first.getId()).when(second).getId();
            case "retired" -> doReturn(false).when(second).isCurrent();
            case "chapter" -> doReturn(UUID.randomUUID()).when(second).getChapterId();
        }
        assertThat(resolver.resolve(chapter, plans, rows)).isEmpty();
        for (var row : List.of(first, second)) {
            verify(row, never()).retire(any());
            verify(row, never()).restore(anyInt(), any());
            verify(row, never()).reposition(anyInt(), any());
        }
    }
}
