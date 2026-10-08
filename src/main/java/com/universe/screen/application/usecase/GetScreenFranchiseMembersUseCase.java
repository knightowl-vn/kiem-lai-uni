package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenFranchiseMemberDTO;
import com.universe.screen.application.exceptions.ScreenFranchiseNotFoundException;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenFranchiseMembership;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class GetScreenFranchiseMembersUseCase {

    private final ScreenFranchiseRepositoryPort franchiseRepository;
    private final ScreenFranchiseMembershipRepositoryPort membershipRepository;
    private final ScreenTitleRepositoryPort titleRepository;

    public GetScreenFranchiseMembersUseCase(
            ScreenFranchiseRepositoryPort franchiseRepository,
            ScreenFranchiseMembershipRepositoryPort membershipRepository,
            ScreenTitleRepositoryPort titleRepository
    ) {
        this.franchiseRepository = Objects.requireNonNull(franchiseRepository, "ScreenFranchiseRepositoryPort cannot be null.");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "ScreenFranchiseMembershipRepositoryPort cannot be null.");
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
    }

    public List<ScreenFranchiseMemberDTO> execute(UUID franchiseId) {
        Objects.requireNonNull(franchiseId, "Franchise ID cannot be null.");

        if (!franchiseRepository.existsById(franchiseId)) {
            throw new ScreenFranchiseNotFoundException(franchiseId);
        }

        List<ScreenFranchiseMembership> memberships = membershipRepository.findByFranchiseId(franchiseId);
        List<ScreenFranchiseMemberDTO> result = new ArrayList<>(memberships.size());

        for (ScreenFranchiseMembership membership : memberships) {
            Optional<ScreenTitle> titleOpt = titleRepository.findById(membership.getTitleId());
            String title = titleOpt.map(ScreenTitle::getTitle).orElse("Unknown Title (" + membership.getTitleId() + ")");
            String titleSlug = titleOpt.map(ScreenTitle::getSlug).orElse("");
            var structure = titleOpt.map(ScreenTitle::getStructure).orElse(null);
            var presentation = titleOpt.map(ScreenTitle::getPresentation).orElse(null);
            var status = titleOpt.map(ScreenTitle::getStatus).orElse(null);

            result.add(new ScreenFranchiseMemberDTO(
                    membership.getId(),
                    membership.getFranchiseId(),
                    membership.getTitleId(),
                    title,
                    titleSlug,
                    membership.getDisplayOrder(),
                    structure,
                    presentation,
                    status
            ));
        }

        return result;
    }
}
