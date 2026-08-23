package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.contracts.dto.reader.ReaderChapterListItemDTO;
import com.universe.novel.infrastructure.persistence.chapter.ReaderChapterListItemProjection;
import com.universe.novel.infrastructure.persistence.chapter.SpringDataChapterJpaRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderChapterListQueryPersistenceAdapterTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID CHAPTER_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    @Mock
    private SpringDataChapterJpaRepository
            chapterRepository;

    @Mock
    private ReaderChapterListItemProjection
            projection;

    private ReaderChapterListQueryPersistenceAdapter
            adapter;

    @BeforeEach
    void setUp() {
        adapter =
                new ReaderChapterListQueryPersistenceAdapter(
                        chapterRepository
                );
    }

    @Test
    @DisplayName(
            "Ánh xạ Published Chapter projection thành Reader Chapter DTO"
    )
    void shouldMapPublishedChapterProjectionToDTO() {

        when(
                projection.getId()
        ).thenReturn(
                CHAPTER_ID.toString()
        );

        when(
                projection.getChapterNumber()
        ).thenReturn(
                1266
        );

        when(
                projection.getTitle()
        ).thenReturn(
                "Chương 1266"
        );

        when(
                projection.getSlug()
        ).thenReturn(
                "quyen-13-chuong-1266"
        );

        when(
                chapterRepository
                        .findPublishedReaderChaptersByVolumeId(
                                VOLUME_ID.toString()
                        )
        ).thenReturn(
                List.of(
                        projection
                )
        );

        List<ReaderChapterListItemDTO> result =
                adapter
                        .findPublishedByVolumeIdOrderByChapterNumber(
                                VOLUME_ID
                        );

        assertThat(
                result
        ).hasSize(
                1
        );

        ReaderChapterListItemDTO chapter =
                result.get(0);

        assertThat(
                chapter.id()
        ).isEqualTo(
                CHAPTER_ID
        );

        assertThat(
                chapter.chapterNumber()
        ).isEqualTo(
                1266
        );

        assertThat(
                chapter.title()
        ).isEqualTo(
                "Chương 1266"
        );

        assertThat(
                chapter.slug()
        ).isEqualTo(
                "quyen-13-chuong-1266"
        );

        verify(
                chapterRepository
        ).findPublishedReaderChaptersByVolumeId(
                VOLUME_ID.toString()
        );
    }

    @Test
    @DisplayName(
            "Trả danh sách rỗng khi Volume chưa có Published Chapter"
    )
    void shouldReturnEmptyListWhenVolumeHasNoPublishedChapters() {

        when(
                chapterRepository
                        .findPublishedReaderChaptersByVolumeId(
                                VOLUME_ID.toString()
                        )
        ).thenReturn(
                List.of()
        );

        List<ReaderChapterListItemDTO> result =
                adapter
                        .findPublishedByVolumeIdOrderByChapterNumber(
                                VOLUME_ID
                        );

        assertThat(
                result
        ).isEmpty();

        verify(
                chapterRepository
        ).findPublishedReaderChaptersByVolumeId(
                VOLUME_ID.toString()
        );
    }
}