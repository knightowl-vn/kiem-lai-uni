package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.contracts.dto.reader.ReaderNovelOverviewDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeListItemDTO;
import com.universe.novel.infrastructure.persistence.profile.NovelProfileJpaEntity;
import com.universe.novel.infrastructure.persistence.profile.SpringDataNovelProfileJpaRepository;
import com.universe.novel.infrastructure.persistence.volume.ReaderVolumeListItemProjection;
import com.universe.novel.infrastructure.persistence.volume.SpringDataVolumeJpaRepository;

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
class ReaderNovelLandingQueryPersistenceAdapterTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    @Mock
    private SpringDataNovelProfileJpaRepository
            novelProfileRepository;

    @Mock
    private SpringDataVolumeJpaRepository
            volumeRepository;

    @Mock
    private NovelProfileJpaEntity
            novelProfile;

    @Mock
    private ReaderVolumeListItemProjection
            volumeProjection;

    private ReaderNovelLandingQueryPersistenceAdapter
            adapter;

    @BeforeEach
    void setUp() {
        adapter =
                new ReaderNovelLandingQueryPersistenceAdapter(
                        novelProfileRepository,
                        volumeRepository
                );
    }

    @Test
    @DisplayName(
            "Ánh xạ Novel Profile thành Reader Novel Overview"
    )
    void shouldMapNovelProfileToReaderNovelOverview() {

        when(
                novelProfileRepository.findBySlug(
                        "kiem-lai"
                )
        ).thenReturn(
                Optional.of(
                        novelProfile
                )
        );

        when(
                novelProfile.getTitle()
        ).thenReturn(
                "Kiếm Lai"
        );

        when(
                novelProfile.getSlug()
        ).thenReturn(
                "kiem-lai"
        );

        when(
                novelProfile.getAuthor()
        ).thenReturn(
                "Phong Hỏa Hí Chư Hầu"
        );

        when(
                novelProfile.getDescription()
        ).thenReturn(
                "Giới thiệu Kiếm Lai."
        );

        when(
                novelProfile.getCoverImageUrl()
        ).thenReturn(
                "/images/novel/kiem-lai.jpg"
        );

        when(
                novelProfile.getStatus()
        ).thenReturn(
                "ONGOING"
        );

        Optional<ReaderNovelOverviewDTO> result =
                adapter.findNovelOverview();

        assertThat(
                result
        ).isPresent();

        ReaderNovelOverviewDTO novel =
                result.orElseThrow();

        assertThat(
                novel.title()
        ).isEqualTo(
                "Kiếm Lai"
        );

        assertThat(
                novel.slug()
        ).isEqualTo(
                "kiem-lai"
        );

        assertThat(
                novel.author()
        ).isEqualTo(
                "Phong Hỏa Hí Chư Hầu"
        );

        assertThat(
                novel.description()
        ).isEqualTo(
                "Giới thiệu Kiếm Lai."
        );

        assertThat(
                novel.coverImageUrl()
        ).isEqualTo(
                "/images/novel/kiem-lai.jpg"
        );

        assertThat(
                novel.status()
        ).isEqualTo(
                "ONGOING"
        );

        verify(
                novelProfileRepository
        ).findBySlug(
                "kiem-lai"
        );
    }

    @Test
    @DisplayName(
            "Trả Optional rỗng khi không tìm thấy Novel Profile"
    )
    void shouldReturnEmptyWhenNovelProfileDoesNotExist() {

        when(
                novelProfileRepository.findBySlug(
                        "kiem-lai"
                )
        ).thenReturn(
                Optional.empty()
        );

        Optional<ReaderNovelOverviewDTO> result =
                adapter.findNovelOverview();

        assertThat(
                result
        ).isEmpty();

        verify(
                novelProfileRepository
        ).findBySlug(
                "kiem-lai"
        );
    }

    @Test
    @DisplayName(
            "Ánh xạ Published Volume projection thành Reader Volume DTO"
    )
    void shouldMapPublishedVolumesToReaderVolumeListItemDTO() {

        when(
                volumeProjection.getId()
        ).thenReturn(
                VOLUME_ID.toString()
        );

        when(
                volumeProjection.getTitle()
        ).thenReturn(
                "Quyển Một - Lung Trung Tước"
        );

        when(
                volumeProjection.getSlug()
        ).thenReturn(
                "quyen-1"
        );

        when(
                volumeProjection.getSortOrder()
        ).thenReturn(
                1
        );

        when(
                volumeProjection.getPublishedChapterCount()
        ).thenReturn(
                81L
        );

        when(
                volumeRepository.findPublishedReaderVolumes()
        ).thenReturn(
                List.of(
                        volumeProjection
                )
        );

        List<ReaderVolumeListItemDTO> result =
                adapter.findPublishedVolumes();

        assertThat(
                result
        ).hasSize(
                1
        );

        ReaderVolumeListItemDTO volume =
                result.get(0);

        assertThat(
                volume.id()
        ).isEqualTo(
                VOLUME_ID
        );

        assertThat(
                volume.title()
        ).isEqualTo(
                "Quyển Một - Lung Trung Tước"
        );

        assertThat(
                volume.slug()
        ).isEqualTo(
                "quyen-1"
        );

        assertThat(
                volume.sortOrder()
        ).isEqualTo(
                1
        );

        assertThat(
                volume.publishedChapterCount()
        ).isEqualTo(
                81L
        );

        verify(
                volumeRepository
        ).findPublishedReaderVolumes();
    }
}