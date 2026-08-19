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
class UpdateDraftVolumeUseCaseTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
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
            "Cập nhật Volume DRAFT không đổi slug và lưu với expected version cũ"
    )
    void shouldUpdateDraftVolumeWithoutChangingSlug() {

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
                "quyen-1"
        );

        assertThat(
                volume.getDescription()
        ).isEqualTo(
                "Mô tả mới của tập."
        );

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
                result.slug()
        ).isEqualTo(
                "quyen-1"
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

        verify(
                volumeRepositoryPort,
                never()
        ).findBySlug(
                any(Slug.class)
        );
    }

    @Test
    @DisplayName(
            "Không lưu khi title và description sau chuẩn hóa không đổi"
    )
    void shouldSkipSaveWhenNormalizedTitleAndDescriptionUnchanged() {

        Volume volume =
                Volume.createDraft(
                        VOLUME_ID,
                        "Quyển Một",
                        new Slug(
                                "quyen-1"
                        ),
                        "Mô tả",
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
                UPDATED_AT
        );

        VolumeDTO result =
                createUseCase().execute(
                        new UpdateDraftVolumeCommand(
                                VOLUME_ID,
                                "  Quyển Một  ",
                                "  Mô tả  ",
                                ADMIN_ID
                        )
                );

        assertThat(
                volume.getTitle()
        ).isEqualTo(
                "Quyển Một"
        );

        assertThat(
                volume.getDescription()
        ).isEqualTo(
                "Mô tả"
        );

        assertThat(
                volume.getUpdatedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                volume.getUpdatedAt()
        ).isEqualTo(
                CREATED_AT
        );

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                result.aggregateVersion()
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
                volume.getSlug().value()
        ).isEqualTo(
                "quyen-1"
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
                        "quyen-1"
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
                "Mô tả mới.",
                ADMIN_ID
        );
    }
}
