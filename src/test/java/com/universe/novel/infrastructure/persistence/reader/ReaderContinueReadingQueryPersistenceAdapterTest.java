package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReaderContinueReadingQueryPort.ReadableChapterDestination;
import com.universe.novel.infrastructure.persistence.chapter.ReaderChapterListItemProjection;
import com.universe.novel.infrastructure.persistence.chapter.SpringDataChapterJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderContinueReadingQueryPersistenceAdapterTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private SpringDataChapterJpaRepository chapterRepository;

    private ReaderContinueReadingQueryPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReaderContinueReadingQueryPersistenceAdapter(chapterRepository);
    }

    private ReaderChapterListItemProjection createProjection(UUID id, int chapterNumber, String title, String slug) {
        return new ReaderChapterListItemProjection() {
            @Override
            public String getId() {
                return id.toString();
            }

            @Override
            public Integer getChapterNumber() {
                return chapterNumber;
            }

            @Override
            public String getTitle() {
                return title;
            }

            @Override
            public String getSlug() {
                return slug;
            }
        };
    }

    @Test
    @DisplayName("findPublishedChapterById maps projection to ReadableChapterDestination when published chapter exists")
    void shouldFindPublishedChapterByIdWhenPublished() {
        ReaderChapterListItemProjection projection =
                createProjection(CHAPTER_ID, 10, "Tiêu Đề 10", "tieu-de-10");

        when(chapterRepository.findPublishedReaderChapterById(CHAPTER_ID.toString()))
                .thenReturn(Optional.of(projection));

        Optional<ReadableChapterDestination> result = adapter.findPublishedChapterById(CHAPTER_ID);

        assertThat(result).isPresent();
        ReadableChapterDestination dest = result.get();
        assertThat(dest.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(dest.chapterNumber()).isEqualTo(10);
        assertThat(dest.title()).isEqualTo("Tiêu Đề 10");
        assertThat(dest.slug()).isEqualTo("tieu-de-10");

        verify(chapterRepository).findPublishedReaderChapterById(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("findPublishedChapterById returns empty when chapter is draft/archived/in draft volume or unknown")
    void shouldReturnEmptyWhenPublishedChapterNotFound() {
        when(chapterRepository.findPublishedReaderChapterById(CHAPTER_ID.toString()))
                .thenReturn(Optional.empty());

        Optional<ReadableChapterDestination> result = adapter.findPublishedChapterById(CHAPTER_ID);

        assertThat(result).isEmpty();
        verify(chapterRepository).findPublishedReaderChapterById(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("findPublishedChapterById returns empty when chapterId is null")
    void shouldReturnEmptyWhenChapterIdIsNull() {
        Optional<ReadableChapterDestination> result = adapter.findPublishedChapterById(null);

        assertThat(result).isEmpty();
        verifyNoInteractions(chapterRepository);
    }

    @Test
    @DisplayName("findChapterNumberById returns structural chapter number regardless of publication state")
    void shouldFindChapterNumberById() {
        when(chapterRepository.findChapterNumberById(CHAPTER_ID.toString()))
                .thenReturn(Optional.of(32));

        Optional<Integer> result = adapter.findChapterNumberById(CHAPTER_ID);

        assertThat(result).contains(32);
        verify(chapterRepository).findChapterNumberById(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("findChapterNumberById returns empty when chapterId is null or not found")
    void shouldReturnEmptyWhenChapterNumberNotFoundOrNullId() {
        assertThat(adapter.findChapterNumberById(null)).isEmpty();

        when(chapterRepository.findChapterNumberById(CHAPTER_ID.toString()))
                .thenReturn(Optional.empty());
        assertThat(adapter.findChapterNumberById(CHAPTER_ID)).isEmpty();
    }

    @Test
    @DisplayName("findPreviousPublishedChapter delegates to repository and maps result")
    void shouldFindPreviousPublishedChapter() {
        UUID prevId = UUID.randomUUID();
        ReaderChapterListItemProjection projection =
                createProjection(prevId, 30, "Tiêu Đề 30", "tieu-de-30");

        when(chapterRepository.findPreviousPublishedReaderChapter(32))
                .thenReturn(Optional.of(projection));

        Optional<ReadableChapterDestination> result = adapter.findPreviousPublishedChapter(32);

        assertThat(result).isPresent();
        assertThat(result.get().chapterId()).isEqualTo(prevId);
        assertThat(result.get().chapterNumber()).isEqualTo(30);
        assertThat(result.get().title()).isEqualTo("Tiêu Đề 30");
        assertThat(result.get().slug()).isEqualTo("tieu-de-30");
    }

    @Test
    @DisplayName("findNextPublishedChapter delegates to repository and maps result")
    void shouldFindNextPublishedChapter() {
        UUID nextId = UUID.randomUUID();
        ReaderChapterListItemProjection projection =
                createProjection(nextId, 3, "Tiêu Đề 3", "tieu-de-3");

        when(chapterRepository.findNextPublishedReaderChapter(1))
                .thenReturn(Optional.of(projection));

        Optional<ReadableChapterDestination> result = adapter.findNextPublishedChapter(1);

        assertThat(result).isPresent();
        assertThat(result.get().chapterId()).isEqualTo(nextId);
        assertThat(result.get().chapterNumber()).isEqualTo(3);
        assertThat(result.get().title()).isEqualTo("Tiêu Đề 3");
        assertThat(result.get().slug()).isEqualTo("tieu-de-3");
    }
}
