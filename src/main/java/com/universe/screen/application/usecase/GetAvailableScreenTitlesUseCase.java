package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenTitleSummaryDTO;
import com.universe.screen.application.exceptions.ScreenFranchiseNotFoundException;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenFranchiseMembership;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class GetAvailableScreenTitlesUseCase {

    private final ScreenFranchiseRepositoryPort franchiseRepository;
    private final ScreenFranchiseMembershipRepositoryPort membershipRepository;
    private final ScreenTitleRepositoryPort titleRepository;

    public GetAvailableScreenTitlesUseCase(
            ScreenFranchiseRepositoryPort franchiseRepository,
            ScreenFranchiseMembershipRepositoryPort membershipRepository,
            ScreenTitleRepositoryPort titleRepository
    ) {
        this.franchiseRepository = Objects.requireNonNull(franchiseRepository, "ScreenFranchiseRepositoryPort cannot be null.");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "ScreenFranchiseMembershipRepositoryPort cannot be null.");
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
    }

    public List<ScreenTitleSummaryDTO> execute(UUID franchiseId) {
        Objects.requireNonNull(franchiseId, "Franchise ID cannot be null.");

        if (!franchiseRepository.existsById(franchiseId)) {
            throw new ScreenFranchiseNotFoundException(franchiseId);
        }

        Set<UUID> memberTitleIds = membershipRepository.findByFranchiseId(franchiseId)
                .stream()
                .map(ScreenFranchiseMembership::getTitleId)
                .collect(Collectors.toSet());

        List<ScreenTitle> allTitles = titleRepository.findAll();

        return allTitles.stream()
                .filter(title -> !memberTitleIds.contains(title.getId()))
                .map(title -> new ScreenTitleSummaryDTO(
                        title.getId(),
                        title.getTitle(),
                        title.getSlug(),
                        title.getStructure(),
                        title.getPresentation(),
                        title.getStatus()
                ))
                .toList();
    }
}
