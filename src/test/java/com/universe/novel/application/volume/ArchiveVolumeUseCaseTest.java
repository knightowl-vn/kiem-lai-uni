package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeHasPublishedChaptersException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
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
class ArchiveVolumeUseCaseTest {

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

    private static final Instant ARCHIVED_AT =
            Instant.parse(
                    "2026-08-17T10:00:00Z"
            );

    @Mock
    private VolumeRepositoryPort
            volumeRepositoryPort;

    @Mock
    private ChapterRepositoryPort
            chapterRepositoryPort;

    @Mock
    private ClockPort
            clockPort;

    private ArchiveVolumeUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new ArchiveVolumeUseCase(
                        volumeRepositoryPort,
                        chapterRepositoryPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Lưu trữ Volume DRAFT thành công khi không có Chapter PUBLISHED"
    )
    void shouldArchiveDraftVolume() {

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
                chapterRepositoryPort
                        .existsPublishedByVolumeId(
                                VOLUME_ID
                        )
        ).thenReturn(
                false
        );

        when(
                clockPort.now()
        ).thenReturn(
                ARCHIVED_AT
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
                        new ArchiveVolumeCommand(
                                VOLUME_ID,
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                volume.getStatus()
        ).isEqualTo(
                VolumeStatus.ARCHIVED
        );

        assertThat(
                volume.getArchivedBy()
        ).isEqualTo(
                OTHER_ADMIN_ID
        );

        assertThat(
                volume.getArchivedAt()
        ).isEqualTo(
                ARCHIVED_AT
        );

        assertThat(
                volume.getUpdatedBy()
        ).isEqualTo(
                OTHER_ADMIN_ID
        );

        assertThat(
                volume.getUpdatedAt()
        ).isEqualTo(
                ARCHIVED_AT
        );

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                2L
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "ARCHIVED"
        );

        assertThat(
                result.archivedBy()
        ).isEqualTo(
                OTHER_ADMIN_ID
        );

        assertThat(
                result.archivedAt()
        ).isEqualTo(
                ARCHIVED_AT
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                2L
        );

        verify(
                chapterRepositoryPort
        ).existsPublishedByVolumeId(
                VOLUME_ID
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
            "Lưu trữ Volume PUBLISHED thành công khi không có Chapter PUBLISHED"
    )
    void shouldArchivePublishedVolume() {

        Volume volume =
                createDraftVolume();

        volume.publish(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        60
                )
        );

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
                chapterRepositoryPort
                        .existsPublishedByVolumeId(
                                VOLUME_ID
                        )
        ).thenReturn(
                false
        );

        when(
                clockPort.now()
        ).thenReturn(
                ARCHIVED_AT
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
                        new ArchiveVolumeCommand(
                                VOLUME_ID,
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                volume.getStatus()
        ).isEqualTo(
                VolumeStatus.ARCHIVED
        );

        assertThat(
                volume.getAggregateVersion()
        ).isEqualTo(
                3L
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "ARCHIVED"
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
            "Từ chối archive Volume khi vẫn còn Chapter PUBLISHED"
    )
    void shouldRejectVolumeWithPublishedChapters() {

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
                chapterRepositoryPort
                        .existsPublishedByVolumeId(
                                VOLUME_ID
                        )
        ).thenReturn(
                true
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new ArchiveVolumeCommand(
                                VOLUME_ID,
                                OTHER_ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        VolumeHasPublishedChaptersException.class
                )
                .hasMessage(
                        "Không thể lưu trữ tập vì vẫn còn chương đã xuất bản: "
                                + VOLUME_ID
                );

        /*
         * Application validation phải xảy ra
         * trước khi Aggregate bị mutate.
         */
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

        assertThat(
                volume.getArchivedBy()
        ).isNull();

        assertThat(
                volume.getArchivedAt()
        ).isNull();

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
            "Từ chối archive Volume không tồn tại"
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
                        new ArchiveVolumeCommand(
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
                chapterRepositoryPort,
                never()
        ).existsPublishedByVolumeId(
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
            "Từ chối archive Volume đã ARCHIVED"
    )
    void shouldRejectAlreadyArchivedVolume() {

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
                chapterRepositoryPort
                        .existsPublishedByVolumeId(
                                VOLUME_ID
                        )
        ).thenReturn(
                false
        );

        when(
                clockPort.now()
        ).thenReturn(
                ARCHIVED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new ArchiveVolumeCommand(
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
            "Từ chối ArchiveVolumeCommand null"
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
                        "Archive volume command không được để trống."
                );

        verify(
                volumeRepositoryPort,
                never()
        ).findById(
                any(UUID.class)
        );

        verify(
                chapterRepositoryPort,
                never()
        ).existsPublishedByVolumeId(
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

    @Test
    @DisplayName(
            "Từ chối Volume ID null"
    )
    void shouldRejectNullVolumeId() {

        assertThatThrownBy(() ->
                useCase.execute(
                        new ArchiveVolumeCommand(
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
                chapterRepositoryPort,
                never()
        ).existsPublishedByVolumeId(
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