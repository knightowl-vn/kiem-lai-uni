package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.RemoveScreenFranchiseMemberCommand;
import com.universe.screen.application.exceptions.ScreenFranchiseMembershipNotFoundException;
import com.universe.screen.application.exceptions.ScreenFranchiseNotFoundException;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.domain.ScreenFranchiseMembership;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@Transactional
public class RemoveScreenFranchiseMemberUseCase {

    private final ScreenFranchiseRepositoryPort franchiseRepository;
    private final ScreenFranchiseMembershipRepositoryPort membershipRepository;

    public RemoveScreenFranchiseMemberUseCase(
            ScreenFranchiseRepositoryPort franchiseRepository,
            ScreenFranchiseMembershipRepositoryPort membershipRepository
    ) {
        this.franchiseRepository = Objects.requireNonNull(franchiseRepository, "ScreenFranchiseRepositoryPort cannot be null.");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "ScreenFranchiseMembershipRepositoryPort cannot be null.");
    }

    public void execute(RemoveScreenFranchiseMemberCommand command) {
        Objects.requireNonNull(command, "RemoveScreenFranchiseMemberCommand cannot be null.");

        if (command.franchiseId() == null) {
            throw new IllegalArgumentException("Franchise ID không được để trống.");
        }
        if (command.membershipId() == null) {
            throw new IllegalArgumentException("Membership ID không được để trống.");
        }

        if (!franchiseRepository.existsById(command.franchiseId())) {
            throw new ScreenFranchiseNotFoundException(command.franchiseId());
        }

        ScreenFranchiseMembership membership = membershipRepository.findById(command.membershipId())
                .orElseThrow(() -> new ScreenFranchiseMembershipNotFoundException(command.membershipId()));

        if (!membership.getFranchiseId().equals(command.franchiseId())) {
            throw new ScreenFranchiseMembershipNotFoundException(command.membershipId());
        }

        membershipRepository.delete(membership);
    }
}
