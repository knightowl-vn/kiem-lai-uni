package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class GetScreenTitleDetailUseCase {

    private final ScreenTitleRepositoryPort titleRepository;

    public GetScreenTitleDetailUseCase(ScreenTitleRepositoryPort titleRepository) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
    }

    public ScreenTitleDTO execute(UUID titleId) {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");

        ScreenTitle title = titleRepository.findById(titleId)
                .orElseThrow(() -> new ScreenTitleNotFoundException(titleId));

        return toDTO(title);
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
