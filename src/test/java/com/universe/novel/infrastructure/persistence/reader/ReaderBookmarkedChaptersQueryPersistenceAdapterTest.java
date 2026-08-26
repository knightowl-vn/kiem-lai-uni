package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.contracts.dto.reader.ReaderBookmarkedChapterDTO;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderBookmarkedChaptersQueryPersistenceAdapterTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Instant NOW =
            Instant.parse("2026-08-25T11:00:00Z");

    @Mock
    private SpringDataChapterBookmarkJpaRepository repository;

    private ReaderBookmarkedChaptersQueryPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReaderBookmarkedChaptersQueryPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("findBookmarkedChaptersByUserId: chuyển đổi projection sang ReaderBookmarkedChapterDTO chính xác")
    void shouldMapProjectionToDto() {
        ReaderBookmarkedChapterProjection projection = new ReaderBookmarkedChapterProjection() {
            @Override
            public String getChapterId() {
                return CHAPTER_ID.toString();
            }

            @Override
            public int getChapterNumber() {
                return 1;
            }

            @Override
            public String getChapterTitle() {
                return "Chương 1";
            }

            @Override
            public String getChapterSlug() {
                return "chuong-1";
            }

            @Override
            public String getVolumeTitle() {
                return "Quyển 1";
            }

            @Override
            public Instant getBookmarkedAt() {
                return NOW;
            }
        };

        when(repository.findPublishedBookmarkedChaptersByUserId(USER_ID.toString()))
                .thenReturn(List.of(projection));

        List<ReaderBookmarkedChapterDTO> dtos = adapter.findBookmarkedChaptersByUserId(USER_ID);

        assertThat(dtos).hasSize(1);
        ReaderBookmarkedChapterDTO dto = dtos.get(0);
        assertThat(dto.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(dto.chapterNumber()).isEqualTo(1);
        assertThat(dto.chapterTitle()).isEqualTo("Chương 1");
        assertThat(dto.chapterSlug()).isEqualTo("chuong-1");
        assertThat(dto.volumeTitle()).isEqualTo("Quyển 1");
        assertThat(dto.bookmarkedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("findBookmarkedChaptersByUserId: trả list rỗng khi userId là null")
    void shouldReturnEmptyListWhenUserIdIsNull() {
        List<ReaderBookmarkedChapterDTO> result = adapter.findBookmarkedChaptersByUserId(null);

        assertThat(result).isEmpty();
        verify(repository, never()).findPublishedBookmarkedChaptersByUserId(any());
    }

    @Test
    @DisplayName("isBookmarked: trả true khi repository trả true")
    void shouldReturnTrueWhenRepositoryReturnsTrue() {
        when(repository.existsByUserIdAndChapterId(USER_ID.toString(), CHAPTER_ID.toString()))
                .thenReturn(true);

        assertThat(adapter.isBookmarked(USER_ID, CHAPTER_ID)).isTrue();
    }

    @Test
    @DisplayName("isBookmarked: trả false khi userId hoặc chapterId là null")
    void shouldReturnFalseWhenUserIdOrChapterIdIsNull() {
        assertThat(adapter.isBookmarked(null, CHAPTER_ID)).isFalse();
        assertThat(adapter.isBookmarked(USER_ID, null)).isFalse();
        verify(repository, never()).existsByUserIdAndChapterId(any(), any());
    }
}
