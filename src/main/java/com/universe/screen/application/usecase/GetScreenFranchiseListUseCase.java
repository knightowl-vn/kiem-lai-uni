package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenFranchiseDTO;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.domain.ScreenFranchise;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class GetScreenFranchiseListUseCase {

    private final ScreenFranchiseRepositoryPort franchiseRepository;
    private final ScreenFranchiseMembershipRepositoryPort membershipRepository;

    public GetScreenFranchiseListUseCase(
            ScreenFranchiseRepositoryPort franchiseRepository,
            ScreenFranchiseMembershipRepositoryPort membershipRepository
    ) {
        this.franchiseRepository = Objects.requireNonNull(franchiseRepository, "ScreenFranchiseRepositoryPort cannot be null.");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "ScreenFranchiseMembershipRepositoryPort cannot be null.");
    }

    public List<ScreenFranchiseDTO> execute() {
        List<ScreenFranchise> franchises = franchiseRepository.findAll();
        return franchises.stream()
                .map(f -> {
                    int memberCount = membershipRepository.findByFranchiseId(f.getId()).size();
                    return new ScreenFranchiseDTO(f.getId(), f.getName(), f.getSlug(), memberCount);
                })
                .toList();
    }
}
