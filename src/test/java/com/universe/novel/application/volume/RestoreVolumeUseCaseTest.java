package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.VolumeStatus;
import com.universe.shared.time.ClockPort;

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
import static org.mockito.ArgumentMatchers.anyLong;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestoreVolumeUseCaseTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID OTHER_ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-17T09:00:00Z"
            );

    private static final Instant RESTORED_AT =
            Instant.parse(
                    "2026-08-17T12:00:00Z"
            );

    @Mock
    private VolumeRepositoryPort
            volumeRepositoryPort;

    @Mock
    private ClockPort
            clockPort;

    private RestoreVolumeUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new RestoreVolumeUseCase(
                        volumeRepositoryPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Khôi phục Volume ARCHIVED về DRAFT với expected version trước mutation"
    )
    void shouldRestoreArchivedVolumeToDraft() {
        Volume volume =
                createArchivedPublishedVolume();

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                3L
        );

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        volume
                )
        );

        when(
                clockPort.now()
        ).thenReturn(
                RESTORED_AT
        );

        when(
                volumeRepositoryPort.save(
                        volume,
                        3L
                )
        ).thenReturn(
                volume
        );

        VolumeDTO result =
                useCase.execute(
                        new RestoreVolumeCommand(
                                VOLUME_ID,
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                volume.getStatus()
        ).isEqualTo(
                VolumeStatus.DRAFT
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "DRAFT"
        );

        assertThat(
                result.archivedBy()
        ).isNull();

        assertThat(
                result.archivedAt()
        ).isNull();

        assertThat(
                result.publishedBy()
        ).isNull();

        assertThat(
                result.publishedAt()
        ).isNull();

        assertThat(
                result.slug()
        ).isEqualTo(
                "quyen-1"
        );

        assertThat(
                result.updatedBy()
        ).isEqualTo(
                OTHER_ADMIN_ID
        );

        assertThat(
                result.updatedAt()
        ).isEqualTo(
                RESTORED_AT
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                4L
        );

        verify(
                volumeRepositoryPort
        ).findById(
                VOLUME_ID
        );

        verify(
                volumeRepositoryPort
        ).save(
                volume,
                3L
        );
    }

    @Test
    @DisplayName(
            "Từ chối restore Volume không tồn tại"
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
                        new RestoreVolumeCommand(
                                VOLUME_ID,
                                OTHER_ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        VolumeNotFoundException.class
                );

        verify(
                clockPort,
                never()
        ).now();

        verify(
                volumeRepositoryPort,
                never()
        ).save(
                any(Volume.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối restore khi Volume không ARCHIVED"
    )
    void shouldRejectRestoreWhenNotArchived() {
        Volume volume =
                Volume.createDraft(
                        VOLUME_ID,
                        "Kiếm Lai - Tập 1",
                        new Slug(
                                "quyen-1"
                        ),
                        "Tập đầu tiên.",
                        1,
                        ADMIN_ID,
                        CREATED_AT
                );

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        volume
                )
        );

        when(
                clockPort.now()
        ).thenReturn(
                RESTORED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new RestoreVolumeCommand(
                                VOLUME_ID,
                                OTHER_ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                );

        assertThat(
                volume.getStatus()
        ).isEqualTo(
                VolumeStatus.DRAFT
        );

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                1L
        );

        verify(
                volumeRepositoryPort,
                never()
        ).save(
                any(Volume.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối RestoreVolumeCommand null"
    )
    void shouldRejectNullCommand() {
        assertThatThrownBy(() ->
                useCase.execute(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Restore volume command không được để trống."
                );

        verify(
                volumeRepositoryPort,
                never()
        ).findById(
                any(UUID.class)
        );
    }

    private Volume createArchivedPublishedVolume() {
        Volume volume =
                Volume.createDraft(
                        VOLUME_ID,
                        "Kiếm Lai - Tập 1",
                        new Slug(
                                "quyen-1"
                        ),
                        "Tập đầu tiên.",
                        1,
                        ADMIN_ID,
                        CREATED_AT
                );

        volume.publish(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        60
                )
        );

        volume.archive(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        120
                )
        );

        return volume;
    }
}
