package com.universe.novel.infrastructure.persistence.chapter;

import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.novel.contracts.dto.ChapterListPageDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChapterListQueryPersistenceAdapterTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID CHAPTER_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-22T02:00:00Z"
            );

    private static final int PAGE = 2;

    private static final int SIZE = 50;

    private static final long TOTAL_ITEMS = 1266L;

    @Mock
    private SpringDataChapterJpaRepository
            repository;

    @Mock
    private ChapterListItemProjection
            projection;

    private ChapterListQueryPersistenceAdapter
            adapter;

    @BeforeEach
    void setUp() {
        adapter =
                new ChapterListQueryPersistenceAdapter(
                        repository
                );
    }

    @Test
    @DisplayName(
            "Ánh xạ Chapter list projection và thông tin pagination thành DTO"
    )
    void shouldMapProjectionAndPaginationToChapterListPageDTO() {

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
                projection.getStatus()
        ).thenReturn(
                "PUBLISHED"
        );

        when(
                projection.getUpdatedAt()
        ).thenReturn(
                UPDATED_AT
        );

        PageRequest pageable =
                PageRequest.of(
                        PAGE - 1,
                        SIZE
                );

        PageImpl<ChapterListItemProjection> repositoryPage =
                new PageImpl<>(
                        List.of(
                                projection
                        ),
                        pageable,
                        TOTAL_ITEMS
                );

        when(
                repository.findListItems(
                        VOLUME_ID.toString(),
                        null,
                        null,
                        pageable
                )
        ).thenReturn(
                repositoryPage
        );

        ChapterListPageDTO result =
                adapter.findAllByVolumeIdOrderByChapterNumber(
                        VOLUME_ID,
                        null,
                        null,
                        PAGE,
                        SIZE
                );

        assertThat(
                result.items()
        ).hasSize(
                1
        );

        ChapterListItemDTO item =
                result.items()
                        .get(0);

        assertThat(
                item.id()
        ).isEqualTo(
                CHAPTER_ID
        );

        assertThat(
                item.chapterNumber()
        ).isEqualTo(
                1266
        );

        assertThat(
                item.title()
        ).isEqualTo(
                "Chương 1266"
        );

        assertThat(
                item.slug()
        ).isEqualTo(
                "quyen-13-chuong-1266"
        );

        assertThat(
                item.status()
        ).isEqualTo(
                "PUBLISHED"
        );

        assertThat(
                item.updatedAt()
        ).isEqualTo(
                UPDATED_AT
        );

        assertThat(
                result.page()
        ).isEqualTo(
                PAGE
        );

        assertThat(
                result.size()
        ).isEqualTo(
                SIZE
        );

        assertThat(
                result.totalItems()
        ).isEqualTo(
                TOTAL_ITEMS
        );

        assertThat(
                result.totalPages()
        ).isEqualTo(
                26
        );

        assertThat(
                result.hasPrevious()
        ).isTrue();

        assertThat(
                result.hasNext()
        ).isTrue();

        verify(
                repository
        ).findListItems(
                VOLUME_ID.toString(),
                null,
                null,
                pageable
        );
    }
}