package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.exceptions.VolumeSlugAlreadyExistsException;
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
class UpdateDraftVolumeUseCaseTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID OTHER_VOLUME_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-17T09:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-17T10:00:00Z"
            );

    private VolumeRepositoryPort
            volumeRepositoryPort;

    @Mock
    private ClockPort
            clockPort;

    @BeforeEach
    void setUp(
            @Mock VolumeRepositoryPort repository
    ) {
        this.volumeRepositoryPort =
                repository;
    }

    private UpdateDraftVolumeUseCase createUseCase() {
        return new UpdateDraftVolumeUseCase(
                volumeRepositoryPort,
                clockPort
        );
    }

    @Test
    @DisplayName(
            "Cập nhật Volume DRAFT và lưu với expected aggregate version cũ"
    )
    void shouldUpdateDraftVolume() {

        Volume volume =
                createDraftVolume();

        Slug newSlug =
                new Slug(
                        "kiem-lai-tap-mot-hoan-thien"
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
                volumeRepositoryPort.findBySlug(
                        newSlug
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                clockPort.now()
        ).thenReturn(
                UPDATED_AT
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
                createUseCase().execute(
                        new UpdateDraftVolumeCommand(
                                VOLUME_ID,
                                "  Kiếm Lai - Tập Một Hoàn Thiện  ",
                                "  KIEM-LAI-TAP-MOT-HOAN-THIEN  ",
                                "  Mô tả mới của tập.  ",
                                ADMIN_ID
                        )
                );

        assertThat(
                volume.getTitle()
        ).isEqualTo(
                "Kiếm Lai - Tập Một Hoàn Thiện"
        );

        assertThat(
                volume.getSlug().value()
        ).isEqualTo(
                "kiem-lai-tap-mot-hoan-thien"
        );

        assertThat(
                volume.getDescription()
        ).isEqualTo(
                "Mô tả mới của tập."
        );

        /*
         * Update Draft không được thay sortOrder.
         */
        assertThat(
                volume.getSortOrder()
        ).isEqualTo(
                1
        );

        assertThat(
                volume.getStatus()
        ).isEqualTo(
                VolumeStatus.DRAFT
        );

        assertThat(
                volume.getUpdatedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                volume.getUpdatedAt()
        ).isEqualTo(
                UPDATED_AT
        );

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                2L
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
            "Cho phép giữ nguyên slug của chính Volume"
    )
    void shouldAllowCurrentVolumeSlug() {

        Volume volume =
                createDraftVolume();

        Slug currentSlug =
                new Slug(
                        "kiem-lai-tap-1"
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
                volumeRepositoryPort.findBySlug(
                        currentSlug
                )
        ).thenReturn(
                Optional.of(
                        volume
                )
        );

        when(
                clockPort.now()
        ).thenReturn(
                UPDATED_AT
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
                createUseCase().execute(
                        new UpdateDraftVolumeCommand(
                                VOLUME_ID,
                                "Kiếm Lai - Tập 1 chỉnh sửa",
                                "kiem-lai-tap-1",
                                "Mô tả mới.",
                                ADMIN_ID
                        )
                );

        assertThat(
                result.slug()
        ).isEqualTo(
                "kiem-lai-tap-1"
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
            "Từ chối slug thuộc về Volume khác và không mutate Aggregate"
    )
    void shouldRejectSlugOwnedByAnotherVolume() {

        Volume volume =
                createDraftVolume();

        Volume otherVolume =
                Volume.createDraft(
                        OTHER_VOLUME_ID,
                        "Kiếm Lai - Tập 2",
                        new Slug(
                                "kiem-lai-tap-2"
                        ),
                        "Tập khác.",
                        2,
                        ADMIN_ID,
                        CREATED_AT
                );

        Slug duplicateSlug =
                new Slug(
                        "kiem-lai-tap-2"
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
                volumeRepositoryPort.findBySlug(
                        duplicateSlug
                )
        ).thenReturn(
                Optional.of(
                        otherVolume
                )
        );

        assertThatThrownBy(() ->
                createUseCase().execute(
                        new UpdateDraftVolumeCommand(
                                VOLUME_ID,
                                "Tên mới không được lưu",
                                "kiem-lai-tap-2",
                                "Mô tả mới không được lưu",
                                ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        VolumeSlugAlreadyExistsException.class
                )
                .hasMessage(
                        "Slug của tập đã tồn tại: kiem-lai-tap-2"
                );

        /*
         * Validation application phải xảy ra
         * trước khi Domain bị mutate.
         */
        assertThat(
                volume.getTitle()
        ).isEqualTo(
                "Kiếm Lai - Tập 1"
        );

        assertThat(
                volume.getSlug().value()
        ).isEqualTo(
                "kiem-lai-tap-1"
        );

        assertThat(
                volume.getDescription()
        ).isEqualTo(
                "Tập đầu tiên."
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
            "Từ chối cập nhật Volume không tồn tại"
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
                createUseCase().execute(
                        updateCommand()
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
        ).findBySlug(
                any(Slug.class)
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
            "Từ chối chỉnh sửa Volume đã PUBLISHED"
    )
    void shouldRejectPublishedVolume() {

        Volume volume =
                createDraftVolume();

        volume.publish(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        60
                )
        );

        long versionBeforeUpdate =
                volume.getAggregateVersion();

        Slug currentSlug =
                new Slug(
                        "kiem-lai-tap-1"
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
                volumeRepositoryPort.findBySlug(
                        currentSlug
                )
        ).thenReturn(
                Optional.of(
                        volume
                )
        );

        when(
                clockPort.now()
        ).thenReturn(
                UPDATED_AT
        );

        assertThatThrownBy(() ->
                createUseCase().execute(
                        updateCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ được cập nhật nội dung khi tập còn là bản nháp."
                );

        assertThat(
                volume.getStatus()
        ).isEqualTo(
                VolumeStatus.PUBLISHED
        );

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                versionBeforeUpdate
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
            "Từ chối UpdateDraftVolumeCommand null"
    )
    void shouldRejectNullCommand() {

        assertThatThrownBy(() ->
                createUseCase().execute(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Update draft volume command không được để trống."
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

    private UpdateDraftVolumeCommand updateCommand() {
        return new UpdateDraftVolumeCommand(
                VOLUME_ID,
                "Kiếm Lai - Tập 1 chỉnh sửa",
                "kiem-lai-tap-1",
                "Mô tả mới.",
                ADMIN_ID
        );
    }
}