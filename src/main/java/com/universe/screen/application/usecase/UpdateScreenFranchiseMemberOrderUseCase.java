package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.UpdateScreenFranchiseMemberOrderCommand;
import com.universe.screen.application.dto.ScreenFranchiseMemberDTO;
import com.universe.screen.application.exceptions.ScreenFranchiseMembershipNotFoundException;
import com.universe.screen.application.exceptions.ScreenFranchiseNotFoundException;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenFranchiseMembership;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
public class UpdateScreenFranchiseMemberOrderUseCase {

    private final ScreenFranchiseRepositoryPort franchiseRepository;
    private final ScreenFranchiseMembershipRepositoryPort membershipRepository;
    private final ScreenTitleRepositoryPort titleRepository;

    public UpdateScreenFranchiseMemberOrderUseCase(
            ScreenFranchiseRepositoryPort franchiseRepository,
            ScreenFranchiseMembershipRepositoryPort membershipRepository,
            ScreenTitleRepositoryPort titleRepository
    ) {
        this.franchiseRepository = Objects.requireNonNull(franchiseRepository, "ScreenFranchiseRepositoryPort cannot be null.");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "ScreenFranchiseMembershipRepositoryPort cannot be null.");
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
    }

    public ScreenFranchiseMemberDTO execute(UpdateScreenFranchiseMemberOrderCommand command) {
        Objects.requireNonNull(command, "UpdateScreenFranchiseMemberOrderCommand cannot be null.");

        if (command.franchiseId() == null) {
            throw new IllegalArgumentException("Franchise ID không được để trống.");
        }
        if (command.membershipId() == null) {
            throw new IllegalArgumentException("Membership ID không được để trống.");
        }
        if (command.displayOrder() < 0) {
            throw new IllegalArgumentException("Thứ tự hiển thị không được âm: " + command.displayOrder());
        }

        if (!franchiseRepository.existsById(command.franchiseId())) {
            throw new ScreenFranchiseNotFoundException(command.franchiseId());
        }

        ScreenFranchiseMembership membership = membershipRepository.findById(command.membershipId())
                .orElseThrow(() -> new ScreenFranchiseMembershipNotFoundException(command.membershipId()));

        if (!membership.getFranchiseId().equals(command.franchiseId())) {
            throw new ScreenFranchiseMembershipNotFoundException(command.membershipId());
        }

        membership.updateDisplayOrder(command.displayOrder());
        ScreenFranchiseMembership saved = membershipRepository.save(membership);

        Optional<ScreenTitle> titleOpt = titleRepository.findById(saved.getTitleId());
        String title = titleOpt.map(ScreenTitle::getTitle).orElse("Unknown Title");
        String titleSlug = titleOpt.map(ScreenTitle::getSlug).orElse("");
        var structure = titleOpt.map(ScreenTitle::getStructure).orElse(null);
        var presentation = titleOpt.map(ScreenTitle::getPresentation).orElse(null);
        var status = titleOpt.map(ScreenTitle::getStatus).orElse(null);

        return new ScreenFranchiseMemberDTO(
                saved.getId(),
                saved.getFranchiseId(),
                saved.getTitleId(),
                title,
                titleSlug,
                saved.getDisplayOrder(),
                structure,
                presentation,
                status
        );
    }
}
