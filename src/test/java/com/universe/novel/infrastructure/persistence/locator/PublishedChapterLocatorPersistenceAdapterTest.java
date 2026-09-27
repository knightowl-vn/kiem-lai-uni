package com.universe.novel.infrastructure.persistence.locator;

import com.universe.novel.application.ports.PublishedChapterLocatorQueryPort.PublishedChapterLocatorRecord;
import com.universe.novel.infrastructure.persistence.chapter.PublishedChapterLocatorProjection;
import com.universe.novel.infrastructure.persistence.chapter.SpringDataChapterJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublishedChapterLocatorPersistenceAdapterTest {

    @Mock
    private SpringDataChapterJpaRepository chapterRepository;

    private PublishedChapterLocatorPersistenceAdapter persistenceAdapter;

    private static final String CHAPTER_ID = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        persistenceAdapter = new PublishedChapterLocatorPersistenceAdapter(chapterRepository);
    }

    @Test
    @DisplayName("Maps projection correctly on findPublishedByChapterNumber")
    void shouldMapProjectionOnFindByChapterNumber() {
        PublishedChapterLocatorProjection projection = createProjection(
                CHAPTER_ID, 123, "Khởi Đầu", "quyen-1-chuong-123", 1, "Quyển 1"
        );
        when(chapterRepository.findPublishedChapterByNumber(123)).thenReturn(Optional.of(projection));

        Optional<PublishedChapterLocatorRecord> result = persistenceAdapter.findPublishedByChapterNumber(123);

        assertThat(result).isPresent();
        PublishedChapterLocatorRecord record = result.get();
        assertThat(record.id()).isEqualTo(UUID.fromString(CHAPTER_ID));
        assertThat(record.chapterNumber()).isEqualTo(123);
        assertThat(record.title()).isEqualTo("Khởi Đầu");
        assertThat(record.slug()).isEqualTo("quyen-1-chuong-123");
        assertThat(record.volumeSortOrder()).isEqualTo(1);
        assertThat(record.volumeTitle()).isEqualTo("Quyển 1");

        verify(chapterRepository).findPublishedChapterByNumber(123);
    }

    @Test
    @DisplayName("Returns empty Optional when chapter not found by number")
    void shouldReturnEmptyWhenChapterNotFoundByNumber() {
        when(chapterRepository.findPublishedChapterByNumber(999)).thenReturn(Optional.empty());

        Optional<PublishedChapterLocatorRecord> result = persistenceAdapter.findPublishedByChapterNumber(999);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Maps projections correctly on findPublishedByTitleKeyword with exact and LIKE parameters")
    void shouldMapProjectionsOnFindByTitleKeyword() {
        PublishedChapterLocatorProjection p1 = createProjection(
                CHAPTER_ID, 1, "Chương 1", "quyen-1-chuong-1", 1, "Quyển 1"
        );
        when(chapterRepository.findPublishedChaptersByTitleKeyword("chuong", "chuong", 20))
                .thenReturn(List.of(p1));

        List<PublishedChapterLocatorRecord> results =
                persistenceAdapter.findPublishedByTitleKeyword("chuong", "chuong", 20);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chapterNumber()).isEqualTo(1);
        assertThat(results.get(0).title()).isEqualTo("Chương 1");

        verify(chapterRepository).findPublishedChaptersByTitleKeyword("chuong", "chuong", 20);
    }

    private PublishedChapterLocatorProjection createProjection(
            String id,
            int chapterNumber,
            String title,
            String slug,
            int volumeSortOrder,
            String volumeTitle
    ) {
        return new PublishedChapterLocatorProjection() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public int getChapterNumber() {
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

            @Override
            public int getVolumeSortOrder() {
                return volumeSortOrder;
            }

            @Override
            public String getVolumeTitle() {
                return volumeTitle;
            }
        };
    }
}
