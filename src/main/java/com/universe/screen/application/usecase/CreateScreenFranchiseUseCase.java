package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.CreateScreenFranchiseCommand;
import com.universe.screen.application.dto.ScreenFranchiseDTO;
import com.universe.screen.application.exceptions.ScreenFranchiseSlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.domain.ScreenFranchise;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class CreateScreenFranchiseUseCase {

    private final ScreenFranchiseRepositoryPort franchiseRepository;

    public CreateScreenFranchiseUseCase(ScreenFranchiseRepositoryPort franchiseRepository) {
        this.franchiseRepository = Objects.requireNonNull(franchiseRepository, "ScreenFranchiseRepositoryPort cannot be null.");
    }

    public ScreenFranchiseDTO execute(CreateScreenFranchiseCommand command) {
        Objects.requireNonNull(command, "CreateScreenFranchiseCommand cannot be null.");

        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("Tên franchise không được để trống.");
        }
        if (command.slug() == null || command.slug().isBlank()) {
            throw new IllegalArgumentException("Slug franchise không được để trống.");
        }

        String normalizedSlug = command.slug().trim().toLowerCase(Locale.ROOT);
        if (franchiseRepository.existsBySlug(normalizedSlug)) {
            throw new ScreenFranchiseSlugAlreadyExistsException(normalizedSlug);
        }

        ScreenFranchise franchise = ScreenFranchise.create(
                UUID.randomUUID(),
                normalizedSlug,
                command.name().trim()
        );

        ScreenFranchise saved = franchiseRepository.save(franchise);
        return new ScreenFranchiseDTO(saved.getId(), saved.getName(), saved.getSlug(), 0);
    }
}
