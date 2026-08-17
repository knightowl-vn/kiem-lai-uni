package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.exceptions.VolumeSortOrderAlreadyExistsException;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.Volume;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReorderVolumeUseCase {

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    private final ClockPort
            clockPort;

    public ReorderVolumeUseCase(
            VolumeRepositoryPort volumeRepositoryPort,
            ClockPort clockPort
    ) {
        this.volumeRepositoryPort =
                volumeRepositoryPort;

        this.clockPort =
                clockPort;
    }

    @Transactional
    public VolumeDTO execute(
            ReorderVolumeCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Reorder volume command không được để trống."
        );

        UUID volumeId =
                Objects.requireNonNull(
                        command.volumeId(),
                        "Volume ID không được để trống."
                );

        Volume volume =
                volumeRepositoryPort
                        .findById(
                                volumeId
                        )
                        .orElseThrow(() ->
                                new VolumeNotFoundException(
                                        volumeId
                                )
                        );

        int newSortOrder =
                command.sortOrder();

        ensureSortOrderAvailable(
                volume,
                newSortOrder
        );

        long expectedVersion =
                volume.getAggregateVersion();

        Instant now =
                clockPort.now();

        volume.reorder(
                newSortOrder,
                command.actorId(),
                now
        );

        Volume savedVolume =
                volumeRepositoryPort.save(
                        volume,
                        expectedVersion
                );

        return VolumeDTOMapper.toDTO(
                savedVolume
        );
    }

    private void ensureSortOrderAvailable(
            Volume currentVolume,
            int newSortOrder
    ) {
        /*
         * Nếu giữ nguyên sortOrder hiện tại,
         * giá trị đó đương nhiên đã thuộc chính Volume này.
         */
        if (currentVolume.getSortOrder()
                == newSortOrder) {
            return;
        }

        if (volumeRepositoryPort.existsBySortOrder(
                newSortOrder
        )) {
            throw new VolumeSortOrderAlreadyExistsException(
                    "Thứ tự sắp xếp của tập đã tồn tại: "
                            + newSortOrder
            );
        }
    }
}