package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenFranchiseDTO;
import com.universe.screen.application.exceptions.ScreenFranchiseNotFoundException;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.domain.ScreenFranchise;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class GetScreenFranchiseDetailUseCase {

    private final ScreenFranchiseRepositoryPort franchiseRepository;
    private final ScreenFranchiseMembershipRepositoryPort membershipRepository;

    public GetScreenFranchiseDetailUseCase(
            ScreenFranchiseRepositoryPort franchiseRepository,
            ScreenFranchiseMembershipRepositoryPort membershipRepository
    ) {
        this.franchiseRepository = Objects.requireNonNull(franchiseRepository, "ScreenFranchiseRepositoryPort cannot be null.");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "ScreenFranchiseMembershipRepositoryPort cannot be null.");
    }

    public ScreenFranchiseDTO execute(UUID franchiseId) {
        Objects.requireNonNull(franchiseId, "Franchise ID cannot be null.");

        ScreenFranchise franchise = franchiseRepository.findById(franchiseId)
                .orElseThrow(() -> new ScreenFranchiseNotFoundException(franchiseId));

        int memberCount = membershipRepository.findByFranchiseId(franchiseId).size();
        return new ScreenFranchiseDTO(franchise.getId(), franchise.getName(), franchise.getSlug(), memberCount);
    }
}
