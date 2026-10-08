package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class GetScreenTitleListUseCase {

    private final ScreenTitleRepositoryPort titleRepository;

    public GetScreenTitleListUseCase(ScreenTitleRepositoryPort titleRepository) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
    }

    public List<ScreenTitleDTO> execute() {
        return titleRepository.findAll()
                .stream()
                .map(GetScreenTitleListUseCase::toDTO)
                .toList();
    }

    private static ScreenTitleDTO toDTO(ScreenTitle title) {
        return new ScreenTitleDTO(
                title.getId(),
                title.getSlug(),
                title.getTitle(),
                title.getOriginalTitle(),
                title.getSynopsis(),
                title.getStructure(),
                title.getPresentation(),
                title.getPosterMediaAssetId(),
                title.getBackdropMediaAssetId(),
                title.getStatus(),
                title.getPublishedAt(),
                title.getCreatedAt(),
                title.getUpdatedAt()
        );
    }
}
