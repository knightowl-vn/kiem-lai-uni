package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.exceptions.VolumeSortOrderAlreadyExistsException;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReorderVolumeUseCaseTest {

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

    private static final Instant REORDERED_AT =
            Instant.parse(
                    "2026-08-17T10:00:00Z"
            );

    @Mock
    private VolumeRepositoryPort
            volumeRepositoryPort;

    @Mock
    private ClockPort
            clockPort;

    private ReorderVolumeUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new ReorderVolumeUseCase(
                        volumeRepositoryPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Sắp xếp lại Volume DRAFT thành công"
    )
    void shouldReorderDraftVolume() {

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
                volumeRepositoryPort.existsBySortOrder(
                        5
                )
        ).thenReturn(
                false
        );

        when(
                clockPort.now()
        ).thenReturn(
                REORDERED_AT
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
                        new ReorderVolumeCommand(
                                VOLUME_ID,
                                5,
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                volume.getSortOrder()
        ).isEqualTo(
                5
        );

        assertThat(
                volume.getStatus()
        ).isEqualTo(
                VolumeStatus.DRAFT
        );

        assertThat(
                volume.getUpdatedBy()
        ).isEqualTo(
                OTHER_ADMIN_ID
        );

        assertThat(
                volume.getUpdatedAt()
        ).isEqualTo(
                REORDERED_AT
        );

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                2L
        );

        assertThat(
                result.sortOrder()
        ).isEqualTo(
                5
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
            "Sắp xếp lại Volume PUBLISHED thành công"
    )
    void shouldReorderPublishedVolume() {

        Volume volume =
                createDraftVolume();

        volume.publish(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        60
                )
        );

        /*
         * Version hiện tại sau publish = 2.
         */
        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                2L
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
                volumeRepositoryPort.existsBySortOrder(
                        4
                )
        ).thenReturn(
                false
        );

        when(
                clockPort.now()
        ).thenReturn(
                REORDERED_AT
        );

        when(
                volumeRepositoryPort.save(
                        volume,
                        2L
                )
        ).thenReturn(
                volume
        );

        VolumeDTO result =
                useCase.execute(
                        new ReorderVolumeCommand(
                                VOLUME_ID,
                                4,
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                volume.getStatus()
        ).isEqualTo(
                VolumeStatus.PUBLISHED
        );

        assertThat(
                volume.getSortOrder()
        ).isEqualTo(
                4
        );

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                3L
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "PUBLISHED"
        );

        assertThat(
                result.sortOrder()
        ).isEqualTo(
                4
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                3L
        );

        verify(
                volumeRepositoryPort
        ).save(
                volume,
                2L
        );
    }

    @Test
    @DisplayName(
            "Cho phép giữ nguyên sortOrder của chính Volume"
    )
    void shouldAllowCurrentSortOrder() {

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
                REORDERED_AT
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
                        new ReorderVolumeCommand(
                                VOLUME_ID,
                                1,
                                OTHER_ADMIN_ID
                        )
                );

        /*
         * Không query duplicate vì sortOrder 1
         * đang thuộc chính Volume hiện tại.
         */
        verify(
                volumeRepositoryPort,
                never()
        ).existsBySortOrder(
                anyInt()
        );

        assertThat(
                result.sortOrder()
        ).isEqualTo(
                1
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
            "Từ chối sortOrder đã thuộc Volume khác"
    )
    void shouldRejectDuplicateSortOrder() {

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
                volumeRepositoryPort.existsBySortOrder(
                        2
                )
        ).thenReturn(
                true
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new ReorderVolumeCommand(
                                VOLUME_ID,
                                2,
                                OTHER_ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        VolumeSortOrderAlreadyExistsException.class
                )
                .hasMessage(
                        "Thứ tự sắp xếp của tập đã tồn tại: 2"
                );

        /*
         * Aggregate phải chưa bị mutate.
         */
        assertThat(
                volume.getSortOrder()
        ).isEqualTo(
                1
        );

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                1L
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
            "Từ chối reorder Volume không tồn tại"
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
                        new ReorderVolumeCommand(
                                VOLUME_ID,
                                2,
                                OTHER_ADMIN_ID
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
                volumeRepositoryPort,
                never()
        ).existsBySortOrder(
                anyInt()
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
            "Từ chối reorder Volume ARCHIVED"
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

        int sortOrderBefore =
                volume.getSortOrder();

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
                volumeRepositoryPort.existsBySortOrder(
                        5
                )
        ).thenReturn(
                false
        );

        when(
                clockPort.now()
        ).thenReturn(
                REORDERED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new ReorderVolumeCommand(
                                VOLUME_ID,
                                5,
                                OTHER_ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Không thể sắp xếp lại tập đã lưu trữ."
                );

        assertThat(
                volume.getSortOrder()
        ).isEqualTo(
                sortOrderBefore
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
            "Từ chối ReorderVolumeCommand null"
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
                        "Reorder volume command không được để trống."
                );

        verify(
                volumeRepositoryPort,
                never()
        ).findById(
                any(UUID.class)
        );

        verify(
                volumeRepositoryPort,
                never()
        ).existsBySortOrder(
                anyInt()
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