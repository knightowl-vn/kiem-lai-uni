package com.universe.novel.application.volume;

import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;

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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetVolumeListUseCaseTest {

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID VOLUME_1_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID VOLUME_2_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final UUID VOLUME_3_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-17T09:00:00Z"
            );

    @Mock
    private VolumeRepositoryPort
            volumeRepositoryPort;

    private GetVolumeListUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new GetVolumeListUseCase(
                        volumeRepositoryPort
                );
    }

    @Test
    @DisplayName(
            "Lấy danh sách Volume theo sortOrder ASC và giữ số thứ tự thực"
    )
    void shouldGetVolumeList() {

        Volume volume1 =
                createVolume(
                        VOLUME_1_ID,
                        "Kiếm Lai - Tập 1",
                        "quyen-1",
                        1
                );

        Volume volume2 =
                createVolume(
                        VOLUME_2_ID,
                        "Kiếm Lai - Tập 2",
                        "quyen-2",
                        2
                );

        Volume volume3 =
                createVolume(
                        VOLUME_3_ID,
                        "Kiếm Lai - Tập 13",
                        "quyen-13",
                        13
                );

        when(
                volumeRepositoryPort
                        .findAllOrderBySortOrder()
        ).thenReturn(
                List.of(
                        volume1,
                        volume2,
                        volume3
                )
        );

        List<VolumeDTO> result =
                useCase.execute();

        assertThat(
                result
        ).hasSize(
                3
        );

        assertThat(
                result
                        .stream()
                        .map(
                                VolumeDTO::sortOrder
                        )
        ).containsExactly(
                1,
                2,
                13
        );

        assertThat(
                result
                        .stream()
                        .map(
                                VolumeDTO::title
                        )
        ).containsExactly(
                "Kiếm Lai - Tập 1",
                "Kiếm Lai - Tập 2",
                "Kiếm Lai - Tập 13"
        );

        assertThat(
                result
                        .stream()
                        .map(
                                VolumeDTO::slug
                        )
        ).containsExactly(
                "quyen-1",
                "quyen-2",
                "quyen-13"
        );

        verify(
                volumeRepositoryPort
        ).findAllOrderBySortOrder();
    }

    @Test
    @DisplayName(
            "Trả danh sách rỗng khi chưa có Volume"
    )
    void shouldReturnEmptyList() {

        when(
                volumeRepositoryPort
                        .findAllOrderBySortOrder()
        ).thenReturn(
                List.of()
        );

        List<VolumeDTO> result =
                useCase.execute();

        assertThat(
                result
        ).isEmpty();

        verify(
                volumeRepositoryPort
        ).findAllOrderBySortOrder();
    }

    private Volume createVolume(
            UUID id,
            String title,
            String slug,
            int sortOrder
    ) {
        return Volume.createDraft(
                id,
                title,
                new Slug(
                        slug
                ),
                "Mô tả.",
                sortOrder,
                ADMIN_ID,
                CREATED_AT
        );
    }
}