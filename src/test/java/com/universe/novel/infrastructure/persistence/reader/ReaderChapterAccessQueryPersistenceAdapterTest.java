package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableChapterReference;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableNarrationChapterReference;
import com.universe.novel.infrastructure.persistence.chapter.ReadableChapterAccessProjection;
import com.universe.novel.infrastructure.persistence.chapter.ReadableChapterNarrationAccessProjection;
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
class ReaderChapterAccessQueryPersistenceAdapterTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private SpringDataChapterJpaRepository chapterRepository;

    private ReaderChapterAccessQueryPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReaderChapterAccessQueryPersistenceAdapter(chapterRepository);
    }

    @Test
    @DisplayName("Returns lightweight ReadableChapterReference when published Chapter in published Volume exists")
    void shouldReturnReadableChapterReferenceWhenPublishedChapterInPublishedVolumeExists() {
        ReadableChapterAccessProjection projection = new ReadableChapterAccessProjection() {
            @Override
            public String getId() {
                return CHAPTER_ID.toString();
            }

            @Override
            public Integer getChapterNumber() {
                return 42;
            }
        };

        when(chapterRepository.findPublishedAccessById(CHAPTER_ID.toString()))
                .thenReturn(Optional.of(projection));

        Optional<ReadableChapterReference> result = adapter.findPublishedById(CHAPTER_ID);

        assertThat(result).isPresent();
        ReadableChapterReference ref = result.get();
        assertThat(ref.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(ref.chapterNumber()).isEqualTo(42);

        verify(chapterRepository).findPublishedAccessById(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("Returns Optional.empty() when chapterId is null")
    void shouldReturnEmptyWhenChapterIdIsNull() {
        Optional<ReadableChapterReference> result = adapter.findPublishedById(null);

        assertThat(result).isEmpty();
        verifyNoInteractions(chapterRepository);
    }

    @Test
    @DisplayName("Returns Optional.empty() when Chapter is in DRAFT status")
    void shouldReturnEmptyWhenChapterIsDraft() {
        when(chapterRepository.findPublishedAccessById(CHAPTER_ID.toString()))
                .thenReturn(Optional.empty());

        Optional<ReadableChapterReference> result = adapter.findPublishedById(CHAPTER_ID);

        assertThat(result).isEmpty();
        verify(chapterRepository).findPublishedAccessById(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("Returns Optional.empty() when Chapter is in ARCHIVED status")
    void shouldReturnEmptyWhenChapterIsArchived() {
        when(chapterRepository.findPublishedAccessById(CHAPTER_ID.toString()))
                .thenReturn(Optional.empty());

        Optional<ReadableChapterReference> result = adapter.findPublishedById(CHAPTER_ID);

        assertThat(result).isEmpty();
        verify(chapterRepository).findPublishedAccessById(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("Returns Optional.empty() when Chapter is PUBLISHED but Volume is not PUBLISHED (DRAFT or ARCHIVED)")
    void shouldReturnEmptyWhenChapterIsPublishedButVolumeIsNotPublished() {
        when(chapterRepository.findPublishedAccessById(CHAPTER_ID.toString()))
                .thenReturn(Optional.empty());

        Optional<ReadableChapterReference> result = adapter.findPublishedById(CHAPTER_ID);

        assertThat(result).isEmpty();
        verify(chapterRepository).findPublishedAccessById(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("Returns Optional.empty() when Chapter does not exist")
    void shouldReturnEmptyWhenChapterDoesNotExist() {
        when(chapterRepository.findPublishedAccessById(CHAPTER_ID.toString()))
                .thenReturn(Optional.empty());

        Optional<ReadableChapterReference> result = adapter.findPublishedById(CHAPTER_ID);

        assertThat(result).isEmpty();
        verify(chapterRepository).findPublishedAccessById(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("Returns chapter ID and content version for narration when Chapter and Volume are published")
    void shouldReturnPublishedNarrationChapterReference() {
        ReadableChapterNarrationAccessProjection projection = new ReadableChapterNarrationAccessProjection() {
            @Override
            public String getId() {
                return CHAPTER_ID.toString();
            }

            @Override
            public Long getContentVersion() {
                return 17L;
            }
        };
        when(chapterRepository.findPublishedNarrationAccessById(CHAPTER_ID.toString()))
                .thenReturn(Optional.of(projection));

        Optional<ReadableNarrationChapterReference> result = adapter.findPublishedNarrationById(CHAPTER_ID);

        assertThat(result).contains(new ReadableNarrationChapterReference(CHAPTER_ID, 17L));
        verify(chapterRepository).findPublishedNarrationAccessById(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("Returns empty narration access when Chapter is not published")
    void shouldRejectUnpublishedChapterForNarration() {
        when(chapterRepository.findPublishedNarrationAccessById(CHAPTER_ID.toString()))
                .thenReturn(Optional.empty());

        assertThat(adapter.findPublishedNarrationById(CHAPTER_ID)).isEmpty();

        verify(chapterRepository).findPublishedNarrationAccessById(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("Returns empty narration access when owning Volume is not published")
    void shouldRejectNarrationChapterInUnpublishedVolume() {
        when(chapterRepository.findPublishedNarrationAccessById(CHAPTER_ID.toString()))
                .thenReturn(Optional.empty());

        assertThat(adapter.findPublishedNarrationById(CHAPTER_ID)).isEmpty();

        verify(chapterRepository).findPublishedNarrationAccessById(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("Returns empty narration access without querying persistence when chapter ID is null")
    void shouldReturnEmptyNarrationAccessWhenChapterIdIsNull() {
        assertThat(adapter.findPublishedNarrationById(null)).isEmpty();

        verifyNoInteractions(chapterRepository);
    }
}
