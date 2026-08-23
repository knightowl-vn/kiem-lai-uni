package com.universe.novel.application.volume;

import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.VolumeDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GetVolumeListUseCase {

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    public GetVolumeListUseCase(
            VolumeRepositoryPort volumeRepositoryPort
    ) {
        this.volumeRepositoryPort =
                volumeRepositoryPort;
    }

    @Transactional(readOnly = true)
    public List<VolumeDTO> execute() {
        return volumeRepositoryPort
                .findAllOrderBySortOrder()
                .stream()
                .map(
                        VolumeDTOMapper::toDTO
                )
                .toList();
    }
}