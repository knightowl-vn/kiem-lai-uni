package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.UpdateScreenFranchiseCommand;
import com.universe.screen.application.dto.ScreenFranchiseDTO;
import com.universe.screen.application.exceptions.ScreenFranchiseNotFoundException;
import com.universe.screen.application.exceptions.ScreenFranchiseSlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.domain.ScreenFranchise;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
public class UpdateScreenFranchiseUseCase {

    private final ScreenFranchiseRepositoryPort franchiseRepository;
    private final ScreenFranchiseMembershipRepositoryPort membershipRepository;

    public UpdateScreenFranchiseUseCase(
            ScreenFranchiseRepositoryPort franchiseRepository,
            ScreenFranchiseMembershipRepositoryPort membershipRepository
    ) {
        this.franchiseRepository = Objects.requireNonNull(franchiseRepository, "ScreenFranchiseRepositoryPort cannot be null.");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "ScreenFranchiseMembershipRepositoryPort cannot be null.");
    }

    public ScreenFranchiseDTO execute(UpdateScreenFranchiseCommand command) {
        Objects.requireNonNull(command, "UpdateScreenFranchiseCommand cannot be null.");

        if (command.franchiseId() == null) {
            throw new IllegalArgumentException("Franchise ID không được để trống.");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("Tên franchise không được để trống.");
        }
        if (command.slug() == null || command.slug().isBlank()) {
            throw new IllegalArgumentException("Slug franchise không được để trống.");
        }

        ScreenFranchise franchise = franchiseRepository.findById(command.franchiseId())
                .orElseThrow(() -> new ScreenFranchiseNotFoundException(command.franchiseId()));

        String normalizedSlug = command.slug().trim().toLowerCase(Locale.ROOT);
        Optional<ScreenFranchise> existingWithSlug = franchiseRepository.findBySlug(normalizedSlug);
        if (existingWithSlug.isPresent() && !existingWithSlug.get().getId().equals(franchise.getId())) {
            throw new ScreenFranchiseSlugAlreadyExistsException(normalizedSlug);
        }

        franchise.update(normalizedSlug, command.name().trim());
        ScreenFranchise saved = franchiseRepository.save(franchise);

        int memberCount = membershipRepository.findByFranchiseId(saved.getId()).size();
        return new ScreenFranchiseDTO(saved.getId(), saved.getName(), saved.getSlug(), memberCount);
    }
}
