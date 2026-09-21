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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    @Test
    @DisplayName("findListItemsByIds: returns empty map when chapterIds is empty, null, or contains only nulls without calling repository")
    void shouldReturnEmptyMapWhenChapterIdsEmptyOrNull() {
        assertThat(adapter.findListItemsByIds(null)).isEmpty();
        assertThat(adapter.findListItemsByIds(Set.of())).isEmpty();

        Set<UUID> onlyNulls = Collections.singleton(null);
        assertThat(adapter.findListItemsByIds(onlyNulls)).isEmpty();

        verify(repository, never()).findListItemsByIds(any());
    }

    @Test
    @DisplayName("findListItemsByIds: resolves multiple chapters with various statuses and maps all fields accurately")
    void shouldResolveMultipleChaptersWithMixedStatusesAndOmitMissingIds() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID idMissing = UUID.randomUUID();

        ChapterListItemProjection proj1 = org.mockito.Mockito.mock(ChapterListItemProjection.class);
        when(proj1.getId()).thenReturn(id1.toString());
        when(proj1.getChapterNumber()).thenReturn(1);
        when(proj1.getTitle()).thenReturn("Chương 1: Khởi đầu");
        when(proj1.getSlug()).thenReturn("chuong-1");
        when(proj1.getStatus()).thenReturn("PUBLISHED");
        when(proj1.getUpdatedAt()).thenReturn(UPDATED_AT);

        ChapterListItemProjection proj2 = org.mockito.Mockito.mock(ChapterListItemProjection.class);
        when(proj2.getId()).thenReturn(id2.toString());
        when(proj2.getChapterNumber()).thenReturn(2);
        when(proj2.getTitle()).thenReturn("Chương 2: Bản nháp");
        when(proj2.getSlug()).thenReturn("chuong-2");
        when(proj2.getStatus()).thenReturn("DRAFT");
        when(proj2.getUpdatedAt()).thenReturn(UPDATED_AT.plusSeconds(3600));

        when(repository.findListItemsByIds(Set.of(id1.toString(), id2.toString(), idMissing.toString())))
                .thenReturn(List.of(proj1, proj2));

        Map<UUID, ChapterListItemDTO> result = adapter.findListItemsByIds(Set.of(id1, id2, idMissing));

        assertThat(result).hasSize(2);
        assertThat(result).containsOnlyKeys(id1, id2);
        assertThat(result).doesNotContainKey(idMissing);

        ChapterListItemDTO item1 = result.get(id1);
        assertThat(item1.id()).isEqualTo(id1);
        assertThat(item1.chapterNumber()).isEqualTo(1);
        assertThat(item1.title()).isEqualTo("Chương 1: Khởi đầu");
        assertThat(item1.slug()).isEqualTo("chuong-1");
        assertThat(item1.status()).isEqualTo("PUBLISHED");
        assertThat(item1.updatedAt()).isEqualTo(UPDATED_AT);

        ChapterListItemDTO item2 = result.get(id2);
        assertThat(item2.id()).isEqualTo(id2);
        assertThat(item2.chapterNumber()).isEqualTo(2);
        assertThat(item2.title()).isEqualTo("Chương 2: Bản nháp");
        assertThat(item2.slug()).isEqualTo("chuong-2");
        assertThat(item2.status()).isEqualTo("DRAFT");
        assertThat(item2.updatedAt()).isEqualTo(UPDATED_AT.plusSeconds(3600));
    }
}