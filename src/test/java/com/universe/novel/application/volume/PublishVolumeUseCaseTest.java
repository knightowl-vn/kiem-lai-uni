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
class PublishVolumeUseCaseTest {

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

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-08-17T10:00:00Z"
            );

    @Mock
    private VolumeRepositoryPort
            volumeRepositoryPort;

    @Mock
    private ClockPort
            clockPort;

    private PublishVolumeUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new PublishVolumeUseCase(
                        volumeRepositoryPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Xuất bản Volume DRAFT thành công"
    )
    void shouldPublishDraftVolume() {

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

        when(
                clockPort.now()
        ).thenReturn(
                PUBLISHED_AT
        );

        when(
                volumeRepositoryPort.save(
                        volume,
                        1L
                )
        ).thenReturn(
                volume
        );

        VolumeDTO result =
                useCase.execute(
                        new PublishVolumeCommand(
                                VOLUME_ID,
                                ADMIN_ID
                        )
                );

        assertThat(
                volume.getStatus()
        ).isEqualTo(
                VolumeStatus.PUBLISHED
        );

        assertThat(
                volume.getPublishedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                volume.getPublishedAt()
        ).isEqualTo(
                PUBLISHED_AT
        );

        assertThat(
                volume.getUpdatedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                volume.getUpdatedAt()
        ).isEqualTo(
                PUBLISHED_AT
        );

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                2L
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "PUBLISHED"
        );

        assertThat(
                result.publishedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                result.publishedAt()
        ).isEqualTo(
                PUBLISHED_AT
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                2L
        );

        verify(
                volumeRepositoryPort
        ).save(
                volume,
                1L
        );
    }

    @Test
    @DisplayName(
            "Từ chối publish Volume không tồn tại"
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
                        new PublishVolumeCommand(
                                VOLUME_ID,
                                ADMIN_ID
                        )
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
            "Từ chối publish Volume đã PUBLISHED"
    )
    void shouldRejectAlreadyPublishedVolume() {

        Volume volume =
                createDraftVolume();

        volume.publish(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        60
                )
        );

        long versionBefore =
                volume.getAggregateVersion();

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
                PUBLISHED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new PublishVolumeCommand(
                                VOLUME_ID,
                                ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ tập ở trạng thái DRAFT mới được xuất bản."
                );

        assertThat(
                volume.getStatus()
        ).isEqualTo(
                VolumeStatus.PUBLISHED
        );

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                versionBefore
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
            "Từ chối publish Volume đã ARCHIVED"
    )
    void shouldRejectArchivedVolume() {

        Volume volume =
                createDraftVolume();

        volume.archive(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        60
                )
        );

        long versionBefore =
                volume.getAggregateVersion();

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
                PUBLISHED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new PublishVolumeCommand(
                                VOLUME_ID,
                                ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ tập ở trạng thái DRAFT mới được xuất bản."
                );

        assertThat(
                volume.getStatus()
        ).isEqualTo(
                VolumeStatus.ARCHIVED
        );

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                versionBefore
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
            "Từ chối PublishVolumeCommand null"
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
                        "Publish volume command không được để trống."
                );

        verify(
                volumeRepositoryPort,
                never()
        ).findById(
                any(UUID.class)
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
            "Từ chối Volume ID null"
    )
    void shouldRejectNullVolumeId() {

        assertThatThrownBy(() ->
                useCase.execute(
                        new PublishVolumeCommand(
                                null,
                                ADMIN_ID
                        )
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