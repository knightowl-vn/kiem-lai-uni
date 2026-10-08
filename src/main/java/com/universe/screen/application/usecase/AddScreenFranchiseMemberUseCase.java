package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.AddScreenFranchiseMemberCommand;
import com.universe.screen.application.dto.ScreenFranchiseMemberDTO;
import com.universe.screen.application.exceptions.DuplicateScreenFranchiseMembershipException;
import com.universe.screen.application.exceptions.ScreenFranchiseNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenFranchiseMembership;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class AddScreenFranchiseMemberUseCase {

    private final ScreenFranchiseRepositoryPort franchiseRepository;
    private final ScreenFranchiseMembershipRepositoryPort membershipRepository;
    private final ScreenTitleRepositoryPort titleRepository;

    public AddScreenFranchiseMemberUseCase(
            ScreenFranchiseRepositoryPort franchiseRepository,
            ScreenFranchiseMembershipRepositoryPort membershipRepository,
            ScreenTitleRepositoryPort titleRepository
    ) {
        this.franchiseRepository = Objects.requireNonNull(franchiseRepository, "ScreenFranchiseRepositoryPort cannot be null.");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "ScreenFranchiseMembershipRepositoryPort cannot be null.");
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
    }

    public ScreenFranchiseMemberDTO execute(AddScreenFranchiseMemberCommand command) {
        Objects.requireNonNull(command, "AddScreenFranchiseMemberCommand cannot be null.");

        if (command.franchiseId() == null) {
            throw new IllegalArgumentException("Franchise ID không được để trống.");
        }
        if (command.titleId() == null) {
            throw new IllegalArgumentException("Tiêu đề Screen không được để trống.");
        }

        if (!franchiseRepository.existsById(command.franchiseId())) {
            throw new ScreenFranchiseNotFoundException(command.franchiseId());
        }

        ScreenTitle title = titleRepository.findById(command.titleId())
                .orElseThrow(() -> new ScreenTitleNotFoundException(command.titleId()));

        if (membershipRepository.existsByFranchiseIdAndTitleId(command.franchiseId(), command.titleId())) {
            throw new DuplicateScreenFranchiseMembershipException(command.franchiseId(), command.titleId());
        }

        int displayOrder;
        if (command.displayOrder() != null) {
            if (command.displayOrder() < 0) {
                throw new IllegalArgumentException("Thứ tự hiển thị không được âm: " + command.displayOrder());
            }
            displayOrder = command.displayOrder();
        } else {
            List<ScreenFranchiseMembership> currentMembers = membershipRepository.findByFranchiseId(command.franchiseId());
            displayOrder = currentMembers.stream()
                    .mapToInt(ScreenFranchiseMembership::getDisplayOrder)
                    .max()
                    .orElse(-1) + 1;
        }

        ScreenFranchiseMembership membership = ScreenFranchiseMembership.create(
                UUID.randomUUID(),
                command.franchiseId(),
                command.titleId(),
                displayOrder
        );

        ScreenFranchiseMembership saved = membershipRepository.save(membership);

        return new ScreenFranchiseMemberDTO(
                saved.getId(),
                saved.getFranchiseId(),
                saved.getTitleId(),
                title.getTitle(),
                title.getSlug(),
                saved.getDisplayOrder(),
                title.getStructure(),
                title.getPresentation(),
                title.getStatus()
        );
    }
}
