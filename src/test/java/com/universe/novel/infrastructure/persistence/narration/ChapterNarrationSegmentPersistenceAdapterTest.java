package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.NarrationTextSegment;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChapterNarrationSegmentPersistenceAdapter Unit Tests")
class ChapterNarrationSegmentPersistenceAdapterTest {

    private static final UUID SEGMENT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID CHAPTER_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final Instant T0 = Instant.parse("2026-09-05T10:00:00Z");

    @Mock
    private SpringDataChapterNarrationSegmentJpaRepository repository;

    private ChapterNarrationSegmentPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ChapterNarrationSegmentPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("Should find segment by ID and map to domain")
    void shouldFindByIdAndMapToDomain() {
        String text = "Đoạn văn đọc thử.";
        String hash = NarrationTextSegment.computeSha256(text);

        ChapterNarrationSegmentJpaEntity entity = new ChapterNarrationSegmentJpaEntity(
                SEGMENT_ID.toString(),
                CHAPTER_ID.toString(),
                0,
                text,
                text.length(),
                hash,
                "CURRENT",
                T0,
                T0
        );
        when(repository.findById(SEGMENT_ID.toString())).thenReturn(Optional.of(entity));

        Optional<ChapterNarrationSegment> result = adapter.findById(SEGMENT_ID);

        assertThat(result).isPresent();
        ChapterNarrationSegment segment = result.get();
        assertThat(segment.getId()).isEqualTo(SEGMENT_ID);
        assertThat(segment.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(segment.getSegmentIndex()).isEqualTo(0);
        assertThat(segment.getText()).isEqualTo(text);
        assertThat(segment.getCharacterCount()).isEqualTo(text.length());
        assertThat(segment.getContentHash()).isEqualTo(hash);
        assertThat(segment.getStatus()).isEqualTo(ChapterNarrationSegmentStatus.CURRENT);
    }

    @Test
    @DisplayName("Should find segments by chapter ID ordered by segmentIndex ASC")
    void shouldFindByChapterId() {
        String text1 = "Đoạn văn 1.";
        String text2 = "Đoạn văn 2.";
        ChapterNarrationSegmentJpaEntity entity1 = new ChapterNarrationSegmentJpaEntity(
                UUID.randomUUID().toString(),
                CHAPTER_ID.toString(),
                0,
                text1,
                text1.length(),
                NarrationTextSegment.computeSha256(text1),
                "CURRENT",
                T0,
                T0
        );
        ChapterNarrationSegmentJpaEntity entity2 = new ChapterNarrationSegmentJpaEntity(
                UUID.randomUUID().toString(),
                CHAPTER_ID.toString(),
                1,
                text2,
                text2.length(),
                NarrationTextSegment.computeSha256(text2),
                "RETIRED",
                T0,
                T0
        );
        when(repository.findByChapterIdOrderBySegmentIndexAsc(CHAPTER_ID.toString()))
                .thenReturn(List.of(entity1, entity2));

        List<ChapterNarrationSegment> result = adapter.findByChapterId(CHAPTER_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSegmentIndex()).isEqualTo(0);
        assertThat(result.get(0).isCurrent()).isTrue();
        assertThat(result.get(1).getSegmentIndex()).isEqualTo(1);
        assertThat(result.get(1).isRetired()).isTrue();
    }

    @Test
    @DisplayName("Should find segments by chapter ID and status")
    void shouldFindByChapterIdAndStatus() {
        String text = "Đoạn văn 1.";
        ChapterNarrationSegmentJpaEntity entity = new ChapterNarrationSegmentJpaEntity(
                SEGMENT_ID.toString(),
                CHAPTER_ID.toString(),
                0,
                text,
                text.length(),
                NarrationTextSegment.computeSha256(text),
                "CURRENT",
                T0,
                T0
        );
        when(repository.findByChapterIdAndStatusOrderBySegmentIndexAsc(CHAPTER_ID.toString(), "CURRENT"))
                .thenReturn(List.of(entity));

        List<ChapterNarrationSegment> result = adapter.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isCurrent()).isTrue();
    }

    @Test
    @DisplayName("Should save single domain segment")
    void shouldSaveSegment() {
        String text = "Đoạn văn lưu mới.";
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(SEGMENT_ID, CHAPTER_ID, 0, text, T0);

        when(repository.saveAndFlush(any(ChapterNarrationSegmentJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ChapterNarrationSegment saved = adapter.save(segment);

        assertThat(saved.getId()).isEqualTo(SEGMENT_ID);
        assertThat(saved.getText()).isEqualTo(text);
        verify(repository).saveAndFlush(any(ChapterNarrationSegmentJpaEntity.class));
    }

    @Test
    @DisplayName("Should saveAll segments")
    void shouldSaveAllSegments() {
        ChapterNarrationSegment seg1 = ChapterNarrationSegment.create(UUID.randomUUID(), CHAPTER_ID, 0, "Đoạn 1.", T0);
        ChapterNarrationSegment seg2 = ChapterNarrationSegment.create(UUID.randomUUID(), CHAPTER_ID, 1, "Đoạn 2.", T0);

        when(repository.saveAllAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        List<ChapterNarrationSegment> saved = adapter.saveAll(List.of(seg1, seg2));

        assertThat(saved).hasSize(2);
        verify(repository).saveAllAndFlush(any());
    }
}
