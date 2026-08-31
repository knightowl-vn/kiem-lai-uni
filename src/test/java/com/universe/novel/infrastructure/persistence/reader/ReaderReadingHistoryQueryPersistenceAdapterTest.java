package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.contracts.dto.reader.ReaderReadingHistoryDTO;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderReadingHistoryQueryPersistenceAdapterTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Instant LAST_READ_AT =
            Instant.parse("2026-08-26T08:30:00Z");

    @Mock
    private SpringDataReadingHistoryJpaRepository repository;

    private ReaderReadingHistoryQueryPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReaderReadingHistoryQueryPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("findReadingHistoryByUserId: map chính xác từ Projection sang DTO")
    void shouldMapProjectionToDTO() {
        ReaderReadingHistoryProjection projection = mock(ReaderReadingHistoryProjection.class);
        when(projection.getChapterId()).thenReturn(CHAPTER_ID.toString());
        when(projection.getChapterNumber()).thenReturn(5);
        when(projection.getChapterTitle()).thenReturn("Chương 5: Bước Ngoặt");
        when(projection.getChapterSlug()).thenReturn("chuong-5-buoc-ngoat");
        when(projection.getVolumeTitle()).thenReturn("Quyển 1");
        when(projection.getLastReadAt()).thenReturn(LAST_READ_AT);

        when(repository.findPublishedReadingHistoryByUserId(USER_ID.toString()))
                .thenReturn(List.of(projection));

        List<ReaderReadingHistoryDTO> result = adapter.findReadingHistoryByUserId(USER_ID);

        assertThat(result).hasSize(1);
        ReaderReadingHistoryDTO dto = result.get(0);
        assertThat(dto.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(dto.chapterNumber()).isEqualTo(5);
        assertThat(dto.chapterTitle()).isEqualTo("Chương 5: Bước Ngoặt");
        assertThat(dto.chapterSlug()).isEqualTo("chuong-5-buoc-ngoat");
        assertThat(dto.volumeTitle()).isEqualTo("Quyển 1");
        assertThat(dto.lastReadAt()).isEqualTo(LAST_READ_AT);

        verify(repository).findPublishedReadingHistoryByUserId(USER_ID.toString());
    }

    @Test
    @DisplayName("findReadingHistoryByUserId: trả về empty list khi userId là null")
    void shouldReturnEmptyListWhenUserIdIsNull() {
        List<ReaderReadingHistoryDTO> result = adapter.findReadingHistoryByUserId(null);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Constructor: từ chối repository là null")
    void shouldRejectNullRepository() {
        assertThatThrownBy(() -> new ReaderReadingHistoryQueryPersistenceAdapter(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SpringDataReadingHistoryJpaRepository không được để trống.");
    }
}
