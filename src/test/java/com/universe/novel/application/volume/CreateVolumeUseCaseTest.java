package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeSlugAlreadyExistsException;
import com.universe.novel.application.exceptions.VolumeSortOrderAlreadyExistsException;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.VolumeStatus;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateVolumeUseCaseTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-17T10:00:00Z"
            );

    @Mock
    private VolumeRepositoryPort
            volumeRepositoryPort;

    @Mock
    private IdGeneratorPort
            idGeneratorPort;

    @Mock
    private ClockPort
            clockPort;

    private CreateVolumeUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new CreateVolumeUseCase(
                        volumeRepositoryPort,
                        idGeneratorPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Tạo Volume mới ở trạng thái DRAFT"
    )
    void shouldCreateDraftVolume() {

        Slug slug =
                new Slug(
                        "kiem-lai-tap-1"
                );

        when(
                volumeRepositoryPort.existsBySlug(
                        slug
                )
        ).thenReturn(
                false
        );

        when(
                volumeRepositoryPort.existsBySortOrder(
                        1
                )
        ).thenReturn(
                false
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                VOLUME_ID
        );

        when(
                clockPort.now()
        ).thenReturn(
                NOW
        );

        when(
                volumeRepositoryPort.save(
                        any(Volume.class),
                        eq(0L)
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        VolumeDTO result =
                useCase.execute(
                        new CreateVolumeCommand(
                                "  Kiếm Lai - Tập 1  ",
                                "  KIEM-LAI-TAP-1  ",
                                "  Tập mở đầu của Kiếm Lai.  ",
                                1,
                                ADMIN_ID
                        )
                );

        ArgumentCaptor<Volume> volumeCaptor =
                ArgumentCaptor.forClass(
                        Volume.class
                );

        verify(
                volumeRepositoryPort
        ).save(
                volumeCaptor.capture(),
                eq(0L)
        );

        Volume savedVolume =
                volumeCaptor.getValue();

        assertThat(
                savedVolume.getId()
        ).isEqualTo(
                VOLUME_ID
        );

        assertThat(
                savedVolume.getTitle()
        ).isEqualTo(
                "Kiếm Lai - Tập 1"
        );

        assertThat(
                savedVolume.getSlug().value()
        ).isEqualTo(
                "kiem-lai-tap-1"
        );

        assertThat(
                savedVolume.getDescription()
        ).isEqualTo(
                "Tập mở đầu của Kiếm Lai."
        );

        assertThat(
                savedVolume.getSortOrder()
        ).isEqualTo(
                1
        );

        assertThat(
                savedVolume.getStatus()
        ).isEqualTo(
                VolumeStatus.DRAFT
        );

        assertThat(
                savedVolume.getCreatedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                savedVolume.getUpdatedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                savedVolume.getCreatedAt()
        ).isEqualTo(
                NOW
        );

        assertThat(
                savedVolume.getUpdatedAt()
        ).isEqualTo(
                NOW
        );

        assertThat(
                savedVolume.getAggregateVersion()
        ).isEqualTo(
                1L
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
                result.status()
        ).isEqualTo(
                "DRAFT"
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                1L
        );
    }

    @Test
    @DisplayName(
            "Từ chối tạo Volume khi slug đã tồn tại"
    )
    void shouldRejectDuplicateSlug() {

        Slug slug =
                new Slug(
                        "kiem-lai-tap-1"
                );

        when(
                volumeRepositoryPort.existsBySlug(
                        slug
                )
        ).thenReturn(
                true
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        VolumeSlugAlreadyExistsException.class
                )
                .hasMessage(
                        "Slug của tập đã tồn tại: kiem-lai-tap-1"
                );

        verify(
                volumeRepositoryPort,
                never()
        ).existsBySortOrder(
                any(Integer.class)
        );

        verify(
                idGeneratorPort,
                never()
        ).generate();

        verify(
                clockPort,
                never()
        ).now();

        verify(
                volumeRepositoryPort,
                never()
        ).save(
                any(Volume.class),
                any(Long.class)
        );
    }

    @Test
    @DisplayName(
            "Từ chối tạo Volume khi sortOrder đã tồn tại"
    )
    void shouldRejectDuplicateSortOrder() {

        Slug slug =
                new Slug(
                        "kiem-lai-tap-1"
                );

        when(
                volumeRepositoryPort.existsBySlug(
                        slug
                )
        ).thenReturn(
                false
        );

        when(
                volumeRepositoryPort.existsBySortOrder(
                        1
                )
        ).thenReturn(
                true
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        VolumeSortOrderAlreadyExistsException.class
                )
                .hasMessage(
                        "Thứ tự sắp xếp của tập đã tồn tại: 1"
                );

        verify(
                idGeneratorPort,
                never()
        ).generate();

        verify(
                clockPort,
                never()
        ).now();

        verify(
                volumeRepositoryPort,
                never()
        ).save(
                any(Volume.class),
                any(Long.class)
        );
    }

    @Test
    @DisplayName(
            "Từ chối CreateVolumeCommand null"
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
                        "Create volume command không được để trống."
                );

        verify(
                volumeRepositoryPort,
                never()
        ).existsBySlug(
                any(Slug.class)
        );

        verify(
                volumeRepositoryPort,
                never()
        ).existsBySortOrder(
                any(Integer.class)
        );

        verify(
                idGeneratorPort,
                never()
        ).generate();

        verify(
                clockPort,
                never()
        ).now();
    }

    private CreateVolumeCommand createCommand() {
        return new CreateVolumeCommand(
                "Kiếm Lai - Tập 1",
                "kiem-lai-tap-1",
                "Tập mở đầu của Kiếm Lai.",
                1,
                ADMIN_ID
        );
    }
}