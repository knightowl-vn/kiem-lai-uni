package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.Volume;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class GetVolumeDetailUseCase {

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    public GetVolumeDetailUseCase(
            VolumeRepositoryPort volumeRepositoryPort
    ) {
        this.volumeRepositoryPort =
                volumeRepositoryPort;
    }

    @Transactional(readOnly = true)
    public VolumeDTO execute(
            UUID volumeId
    ) {
        Objects.requireNonNull(
                volumeId,
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

        return VolumeDTOMapper.toDTO(
                volume
        );
    }
}