package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetVolumeDetailUseCaseTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-17T09:00:00Z"
            );

    @Mock
    private VolumeRepositoryPort
            volumeRepositoryPort;

    private GetVolumeDetailUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new GetVolumeDetailUseCase(
                        volumeRepositoryPort
                );
    }

    @Test
    @DisplayName(
            "Lấy chi tiết Volume thành công"
    )
    void shouldGetVolumeDetail() {

        Volume volume =
                createDraftVolume();

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        volume
                )
        );

        VolumeDTO result =
                useCase.execute(
                        VOLUME_ID
                );

        assertThat(
                result.id()
        ).isEqualTo(
                VOLUME_ID
        );

        assertThat(
                result.title()
        ).isEqualTo(
                "Kiếm Lai - Tập 1"
        );

        assertThat(
                result.slug()
        ).isEqualTo(
                "kiem-lai-tap-1"
        );

        assertThat(
                result.description()
        ).isEqualTo(
                "Tập đầu tiên."
        );

        assertThat(
                result.sortOrder()
        ).isEqualTo(
                1
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "DRAFT"
        );

        assertThat(
                result.createdBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                1L
        );

        verify(
                volumeRepositoryPort
        ).findById(
                VOLUME_ID
        );
    }

    @Test
    @DisplayName(
            "Từ chối khi Volume không tồn tại"
    )
    void shouldRejectMissingVolume() {

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        VOLUME_ID
                )
        )
                .isInstanceOf(
                        VolumeNotFoundException.class
                )
                .hasMessage(
                        "Không tìm thấy tập: "
                                + VOLUME_ID
                );

        verify(
                volumeRepositoryPort
        ).findById(
                VOLUME_ID
        );
    }

    @Test
    @DisplayName(
            "Từ chối Volume ID null"
    )
    void shouldRejectNullVolumeId() {

        assertThatThrownBy(() ->
                useCase.execute(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Volume ID không được để trống."
                );

        verify(
                volumeRepositoryPort,
                never()
        ).findById(
                any(UUID.class)
        );
    }

    private Volume createDraftVolume() {
        return Volume.createDraft(
                VOLUME_ID,
                "Kiếm Lai - Tập 1",
                new Slug(
                        "kiem-lai-tap-1"
                ),
                "Tập đầu tiên.",
                1,
                ADMIN_ID,
                CREATED_AT
        );
    }
}